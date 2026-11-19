package io.github.mangi.eta.agent.display.dashboard

/** The consumed card's identity travels with its answer, independently of the viewed conversation. */
internal data class ApprovalResolution(
    val token: String,
    val conversationKey: String,
    val runId: String? = null,
    val followUp: String,
    val kind: Kind = Kind.ANSWER,
) {
    enum class Kind { ANSWER, MANUAL, MANUAL_COMPLETE }
}

/** Pure answer construction. Validation happens before a card is consumed. */
internal object ApprovalAnswers {
    fun option(current: PendingApproval, optionId: String): ApprovalResolution? {
        val chosen = current.options.firstOrNull { it.id == optionId }
        if (current.options.isNotEmpty() && chosen == null) return null
        if (chosen == null && optionId !in setOf(PromptOptionId.APPROVE, PromptOptionId.REJECT)) return null
        if (optionId == PromptOptionId.REMEMBER && !current.canRemember) return null
        val label = chosen?.label ?: optionId
        val detail = chosen?.description?.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()
        val text = when (optionId) {
            PromptOptionId.APPROVE, PromptOptionId.REMEMBER -> {
                val fields = current.fields.joinToString(" · ") { "${it.label} ${it.value}" }.take(160)
                val suffix = if (fields.isBlank()) "" else "（$fields）"
                "用户已确认「${current.title}」$suffix。请先 observe_screen 重新观察当前页面，" +
                    "再根据当前状态继续这一步；不要重放拦截时的坐标，也不要重复询问同一操作。"
            }
            PromptOptionId.REJECT ->
                "用户拒绝了「${current.title}」。请停止这个操作，不要换方式重试，并说明接下来可行的替代做法。"
            PromptOptionId.MANUAL ->
                "用户选择自己手动完成「${current.title}」。请立刻停下所有操作，" +
                    "说明当前进度和已经做过的改动，等用户明确表示已完成后再继续。"
            else -> "用户对「${current.title}」选择了「$label」$detail。请按这个选择继续，不要再问一遍。"
        }
        return current.answer(text, if (optionId == PromptOptionId.MANUAL) ApprovalResolution.Kind.MANUAL else ApprovalResolution.Kind.ANSWER)
    }

    fun many(current: PendingApproval, optionIds: List<String>): ApprovalResolution? {
        if (!current.allowMultiple || optionIds.isEmpty()) return null
        val ids = optionIds.distinct()
        if (ids.any { id -> current.options.none { it.id == id } }) return null
        val labels = ids.map { id -> current.options.first { it.id == id }.label }
        val others = current.options.filter { it.id !in ids }.map { it.label }
        val hint = if (others.isEmpty()) "" else {
            "（未选：${others.joinToString("、")}。用户之后如果说要改，以他最新的说法为准。）"
        }
        return current.answer("用户对「${current.title}」选择了：${labels.joinToString("、")}。请按这些选择继续，不要再问一遍。$hint")
    }

    fun freeform(current: PendingApproval, text: String, extraIds: List<String>): ApprovalResolution? {
        val answer = text.trim().take(500)
        if (answer.isEmpty()) return null
        if (extraIds.isNotEmpty() && !current.allowMultiple) return null
        if (extraIds.any { id -> current.options.none { it.id == id } }) return null
        val labels = extraIds.distinct().map { id -> current.options.first { it.id == id }.label }
        val textReply = if (labels.isEmpty()) {
            "用户对「${current.title}」自己填写了：$answer。请按这个回答继续，不要再问一遍。"
        } else {
            "用户对「${current.title}」勾选了「${labels.joinToString("、")}」，另外补充：$answer。" +
                "请把勾选项和这段补充一起当作他的答案。"
        }
        return current.answer(textReply)
    }

    fun skip(current: PendingApproval): ApprovalResolution = current.answer(
        "用户跳过了「${current.title}」，没有选也没有回答。" +
            "不要重复问同一个问题，按你认为最合理的方式继续；若这一步不可撤销，就停下来说明为什么需要他决定。",
    )

    fun manualComplete(current: PendingApproval): ApprovalResolution = current.answer(
        "【人工接管结束】用户已手动完成「${current.title}」。请先 observe_screen 重新读屏，" +
            "核对用户操作后的当前状态，再决定下一步；不要重放接管前的坐标或沿用旧页面记忆。",
        ApprovalResolution.Kind.MANUAL_COMPLETE,
    ).copy(token = current.token + ":manual-complete")

    private fun PendingApproval.answer(text: String, kind: ApprovalResolution.Kind = ApprovalResolution.Kind.ANSWER) =
        ApprovalResolution(token, conversationKey, runId?.takeIf(String::isNotBlank), text, kind)
}

/** One lock covers token consumption, owner checks and the durable card/outbox transaction. */
internal class ApprovalLedger(
    private val persist: (List<PendingApproval>, List<PendingApproval>, ApprovalResolution?) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cards = emptyList<PendingApproval>()
    private var manuals = emptyList<PendingApproval>()

    @Synchronized
    fun restore(pendings: List<PendingApproval>, manualPendings: List<PendingApproval> = emptyList()) {
        cards = pendings.filter { !it.expired(clock()) }.distinctBy { it.token }
        manuals = manualPendings.distinctBy { it.token }
    }

    @Synchronized
    fun all(): List<PendingApproval> = cards.toList()

    @Synchronized
    fun replaceForOwner(pending: PendingApproval) {
        val next = cards.filter { it.conversationKey != pending.conversationKey && !it.expired(clock()) } + pending
        if (persist(next, manuals, null)) cards = next
    }

    @Synchronized
    fun find(token: String): PendingApproval? = cards.firstOrNull { it.token == token && !it.expired(clock()) }

    @Synchronized
    fun resolve(
        token: String,
        expectedConversation: String = "",
        retainAnswer: Boolean = true,
        answer: (PendingApproval) -> ApprovalResolution?,
    ): ApprovalResolution? {
        val current = cards.firstOrNull { it.token == token } ?: return null
        val next = cards.filter { it.token != token }
        if (current.expired(clock())) {
            if (persist(next, manuals, null)) cards = next
            return null
        }
        if (expectedConversation.isNotBlank() && expectedConversation != current.conversationKey) return null
        val resolution = answer(current) ?: return null
        val nextManuals = if (resolution.kind == ApprovalResolution.Kind.MANUAL) manuals + current else manuals
        // A failed durable write leaves the card available for retry.
        if (!persist(next, nextManuals, resolution.takeIf { retainAnswer })) return null
        cards = next
        manuals = nextManuals
        return resolution
    }

    @Synchronized
    fun manual(token: String, expectedConversation: String = ""): PendingApproval? = manuals.firstOrNull {
        it.token == token && (expectedConversation.isBlank() || expectedConversation == it.conversationKey)
    }

    @Synchronized
    fun completeManual(token: String, expectedConversation: String = ""): ApprovalResolution? {
        val current = manual(token, expectedConversation) ?: return null
        val resolution = ApprovalAnswers.manualComplete(current)
        val next = manuals.filter { it.token != token }
        if (!persist(cards, next, resolution)) return null
        manuals = next
        return resolution
    }

    /** Local-only decisions never create a chat follow-up or resume another run. */
    @Synchronized
    fun consumeLocal(token: String, owner: String, action: (PendingApproval) -> Unit): Boolean {
        val current = cards.firstOrNull { it.token == token } ?: return false
        if (current.expired(clock())) {
            val next = cards.filterNot { it.token == token }
            if (persist(next, manuals, null)) cards = next
            return false
        }
        if (owner.isBlank() || current.conversationKey != owner || !current.isMemoryReview) return false
        action(current)
        val next = cards.filterNot { it.token == token }
        if (!persist(next, manuals, null)) return false
        cards = next
        return true
    }

    @Synchronized
    fun pruneInvalid(): List<PendingApproval> {
        val invalid = cards.filter { it.expired(clock()) || it.isMemoryReview &&
            (it.conversationKey.isBlank() || it.memoryReviewCandidates.isEmpty()) }
        if (invalid.isEmpty()) return emptyList()
        val next = cards - invalid.toSet()
        if (!persist(next, manuals, null)) return emptyList()
        cards = next
        return invalid
    }

    @Synchronized
    fun dismiss(token: String) {
        val next = cards.filter { it.token != token }
        if (persist(next, manuals, null)) cards = next
    }
}
