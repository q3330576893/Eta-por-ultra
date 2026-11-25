package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.MessageReferenceUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi

/**
 * 长会话渲染投影层：把「每次流式增量都全量重算」的派生数据改成
 * 「只重算真正变化的那一段，未变化的实例原样复用」。
 *
 * 全部是纯函数：输入 [AgentChatMessageUi] 列表 + 上一版投影，输出新投影。
 * Compose 侧只用 `remember` 持有上一版投影，不含业务判断。
 *
 * 复用判定统一按**实例引用**（`===`）而不是 `equals`：流式投影（AgentRunMessageProjector）
 * 每来一个增量只 `copy()` 真正变化的那一条，其余消息保持同一实例；反过来，
 * 内容变化必然伴随新实例。因此「实例相同 ⇒ 全部派生字段相同」，复用安全。
 */

// ── 可见消息 ─────────────────────────────────────────────────────────

/**
 * 可见消息投影。[source] 是上一版输入列表，[visible] 是过滤后的结果；
 * 没有空白正文消息时两者是同一个实例。
 */
internal data class VisibleMessagesProjection(
    val source: List<AgentChatMessageUi>,
    val visible: List<AgentChatMessageUi>,
    val targetMessageId: String?,
)

/**
 * 等价于 `visibleMessagesForEdit(messages, target).filterNot { 空白 Agent 正文 }`。
 *
 * 省掉的开销：
 * 1. 没有编辑目标、且列表里没有空白正文 Agent 消息时（长会话常态），直接把 [visible]
 *    指向上游 [messages] 本身：原来每帧都要走一次 `filterNot`，而 `filterNot` 无条件
 *    新建一个 ArrayList（数百条消息 ⇒ 每帧一次 O(n) 分配 + n 次写入）；现在只剩一次
 *    无分配的 `any` 扫描（`isBlank()` 遇到第一个非空白字符即返回，正文消息是 O(1)）。
 * 2. 消息实例全同时（上游只换了列表包装）直接沿用上一版的 [visible]，连扫描都不做。
 */
internal fun projectVisibleMessages(
    messages: List<AgentChatMessageUi>,
    targetMessageId: String?,
    previous: VisibleMessagesProjection?,
): VisibleMessagesProjection {
    if (targetMessageId == null) {
        // 实例全同 ⇒ 过滤结果必然相同：直接沿用上一版。
        if (previous != null &&
            previous.targetMessageId == null &&
            sameInstances(previous.source, messages)
        ) {
            return previous.copy(source = messages)
        }
        if (!hasBlankAgentContent(messages)) {
            return VisibleMessagesProjection(messages, messages, targetMessageId = null)
        }
        return VisibleMessagesProjection(
            source = messages,
            visible = filterBlankAgentMessages(messages),
            targetMessageId = null,
        )
    }
    // 编辑预览是低频路径，保持原有语义：截断到目标消息（含）后过滤空白正文。
    val trimmed = AgentConversationRevisionReducer.visibleMessagesForEdit(
        messages = messages,
        targetMessageId = targetMessageId,
    )
    val visible = if (hasBlankAgentContent(trimmed)) filterBlankAgentMessages(trimmed) else trimmed
    return VisibleMessagesProjection(
        source = messages,
        visible = visible,
        targetMessageId = targetMessageId,
    )
}

private fun hasBlankAgentContent(messages: List<AgentChatMessageUi>): Boolean =
    messages.any { message -> message is AgentMessageUi && message.content.isBlank() }

private fun filterBlankAgentMessages(
    messages: List<AgentChatMessageUi>,
): List<AgentChatMessageUi> = messages.filterNot { message ->
    message is AgentMessageUi && message.content.isBlank()
}

// ── 时间线条目 ───────────────────────────────────────────────────────

/**
 * 与 [toTimelineEntries] 等价的时间线投影，但复用未变化的条目实例。
 *
 * 省掉的开销：每个流式增量不再为每条消息新建一个 `AgentTimelineEntry.Message`
 * 包装对象（数百条消息 ⇒ 每次增量数百次分配），也不再为每个工作过程分组
 * 新建 `messages.toList()`；条目全部复用时直接返回 [previous] 同一列表实例，
 * `LazyColumn items(items = ...)` 拿到稳定引用，未变化条目按实例跳过重组。
 */
internal fun projectTimelineEntries(
    messages: List<AgentChatMessageUi>,
    previous: List<AgentTimelineEntry>?,
): List<AgentTimelineEntry> {
    // 第一帧（或历史被整体重置）没有可比对的上一版，退回一次性分组。
    val previousEntries = previous?.takeIf { it.isNotEmpty() } ?: return messages.toTimelineEntries()

    val result = ArrayList<AgentTimelineEntry>(previousEntries.size)
    val workMessages = ArrayList<AgentChatMessageUi>()
    var changed = false
    // 上一版工作过程分组按 key 的兜底索引：只在位置对不上时才建（热路径不分配）。
    var previousWork: HashMap<String, AgentTimelineEntry.WorkProcess>? = null

    fun flushWorkProcess() {
        if (workMessages.isEmpty()) return
        val key = "work-${workMessages.first().id}"
        val aligned = previousEntries.getOrNull(result.size) as? AgentTimelineEntry.WorkProcess
        var reusable = aligned?.takeIf { it.matchesWorkProcess(key, workMessages) }
        if (reusable == null) {
            // 位置平移（前面插入/移除了消息）：退回按 key 找上一版分组。
            val lookup = previousWork ?: HashMap<String, AgentTimelineEntry.WorkProcess>(previousEntries.size)
                .apply {
                    previousEntries.forEach { entry ->
                        if (entry is AgentTimelineEntry.WorkProcess) put(entry.key, entry)
                    }
                }.also { previousWork = it }
            reusable = lookup[key]?.takeIf { it.matchesWorkProcess(key, workMessages) }
        }
        if (reusable != null) {
            result.add(reusable)
            // 复用的不是同位置条目 ⇒ 顺序发生了变化，不能返回上一版列表。
            if (reusable !== aligned) changed = true
        } else {
            result.add(AgentTimelineEntry.WorkProcess(key = key, messages = workMessages.toList()))
            changed = true
        }
        workMessages.clear()
    }

    for (message in messages) {
        if (message.isWorkProcessMessage()) {
            workMessages.add(message)
        } else {
            flushWorkProcess()
            val aligned = previousEntries.getOrNull(result.size) as? AgentTimelineEntry.Message
            val reusable = aligned?.takeIf { it.message === message }
            if (reusable != null) {
                result.add(reusable)
            } else {
                result.add(AgentTimelineEntry.Message(message))
                changed = true
            }
        }
    }
    flushWorkProcess()

    return if (!changed && result.size == previousEntries.size) previousEntries else result
}

private fun AgentTimelineEntry.WorkProcess.matchesWorkProcess(
    key: String,
    messages: List<AgentChatMessageUi>,
): Boolean = this.key == key &&
    this.messages.size == messages.size &&
    this.messages.indices.all { index -> this.messages[index] === messages[index] }

internal fun AgentChatMessageUi.isWorkProcessMessage(): Boolean =
    this is ThinkingMessageUi || this is ToolActivityMessageUi || this is ToolSummaryMessageUi

// ── 最终结果 id 集合 ─────────────────────────────────────────────────

/**
 * 最终结果 id 投影。
 *
 * - [finalizedEnd]：上次计算时「最后一条用户消息下标 + 1」，即已封口前缀的长度；
 * - [finalizedIds]：这段前缀里每一轮的最终结果 id；
 * - [tailAgentId]：前缀之后（当前这一轮）最后一条 Agent 正文的 id；
 * - [isStreaming]：上次计算时的流式状态。
 *
 * 四者都不变时 [ids] 必然不变，因此可以整条投影原样复用。
 */
internal data class FinalResultIdsProjection(
    val source: List<AgentChatMessageUi>,
    val finalizedEnd: Int,
    val finalizedIds: Set<String>,
    val tailAgentId: String?,
    val isStreaming: Boolean,
    val ids: Set<String>,
)

/**
 * 与 [resolveFinalResultMessageIds] 等价，但已封口轮次的 id 集合按实例复用。
 *
 * 省掉的开销：流式期间每个增量不再遍历全部消息重建 `LinkedHashSet`
 * （数百条消息 ⇒ 每次增量数百次 `add` 与一次集合分配）；前缀实例未变时
 * 只做 O(已封口条数) 的引用比对 + 尾部少量消息的倒扫；当前轮的尾部 id 与
 * 流式状态都没变时整条投影原样返回，`message.id in finalResultMessageIds`
 * 的下游 item 不会因集合换实例而全部重组。
 */
internal fun projectFinalResultMessageIds(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean,
    previous: FinalResultIdsProjection?,
): FinalResultIdsProjection {
    val finalizedEnd = messages.indexOfLast { it is UserMessageUi } + 1
    // 已封口前缀（最后一条用户消息之前）只要实例逐个未变，每轮的最终结果 id 就不会变。
    val reusableFinalized = previous?.takeIf {
        it.finalizedEnd == finalizedEnd &&
            it.source.size >= finalizedEnd &&
            prefixSameInstances(it.source, messages, finalizedEnd)
    }
    val finalizedIds = reusableFinalized?.finalizedIds ?: finalizedIdsUpTo(messages, finalizedEnd)

    // 尾部（最后一条用户消息之后）只有当前这一轮，最终回答就是其中最后一条 Agent 正文。
    var tailAgentId: String? = null
    for (index in messages.lastIndex downTo finalizedEnd) {
        val message = messages[index]
        if (message is AgentMessageUi) {
            tailAgentId = message.id
            break
        }
    }

    if (reusableFinalized != null &&
        reusableFinalized.tailAgentId == tailAgentId &&
        reusableFinalized.isStreaming == isStreaming
    ) {
        // 前缀、尾部与流式状态全同 ⇒ ids 必然相同，连集合都不用再拼。
        return reusableFinalized.copy(source = messages)
    }

    val ids = if (!isStreaming && tailAgentId != null) finalizedIds + tailAgentId else finalizedIds
    return FinalResultIdsProjection(
        source = messages,
        finalizedEnd = finalizedEnd,
        finalizedIds = finalizedIds,
        tailAgentId = tailAgentId,
        isStreaming = isStreaming,
        ids = ids,
    )
}

private fun finalizedIdsUpTo(messages: List<AgentChatMessageUi>, endExclusive: Int): Set<String> {
    val ids = LinkedHashSet<String>()
    var lastAgentId: String? = null
    for (index in 0 until endExclusive) {
        when (val message = messages[index]) {
            is UserMessageUi -> {
                lastAgentId?.let(ids::add)
                lastAgentId = null
            }
            is AgentMessageUi -> lastAgentId = message.id
            else -> Unit
        }
    }
    return ids
}

// ── 上下文用量 ───────────────────────────────────────────────────────

/**
 * 上下文用量投影。缓存键全部取自**派生值真正的来源**（[TokenUsageUi] 实例），
 * 而不是承载它的消息实例：流式期间消息会被 `copy()` 成新实例，但它携带的
 * `usage` 对象不变，派生结果也就不变。
 */
internal data class ContextUsageProjection(
    val witnessUsage: TokenUsageUi?,
    val witnessId: String?,
    /** witness 是「上下文已压缩」通知时为 true（此时 usage 为空）。 */
    val witnessIsCompaction: Boolean,
    val lastUsage: TokenUsageUi?,
    val selectedModel: AgentModelOptionUi?,
    val usage: AgentContextUsageUi,
)

/**
 * 与 `latestContextUsage(messages, model) + 尾部 TPS 覆盖` 完全等价，但按来源实例复用。
 *
 * 省掉的开销：流式期间每个增量都会新建一个 [AgentContextUsageUi]（原实现每次
 * 两次反向扫描 + 一次 `copy`）。这里在扫描结束后按 `usage` 实例比对，派生结果
 * 没变时返回上一版同一个对象，输入框上的 token 用量 chip 不会因参数换实例而重组。
 */
internal fun projectContextUsage(
    messages: List<AgentChatMessageUi>,
    selectedModel: AgentModelOptionUi?,
    previous: ContextUsageProjection?,
): ContextUsageProjection {
    var lastUsage: TokenUsageUi? = null
    var witnessMessage: AgentMessageUi? = null
    var witnessNotice: SystemNoticeMessageUi? = null
    var witnessFound = false
    // 与 latestContextUsage 同口径：从尾部倒查，第一条「有 usage 且带 contextTokens」的
    // Agent 消息即为用量来源；若先遇到 ContextCompaction 通知，则视为压缩后无用量。
    for (index in messages.lastIndex downTo 0) {
        when (val message = messages[index]) {
            is AgentMessageUi -> {
                if (lastUsage == null && message.usage != null) lastUsage = message.usage
                if (!witnessFound && message.usage?.contextTokens != null) {
                    witnessMessage = message
                    witnessFound = true
                }
            }
            is SystemNoticeMessageUi -> if (!witnessFound &&
                message.code == SystemNoticeCode.ContextCompaction
            ) {
                witnessNotice = message
                witnessFound = true
            }
            else -> Unit
        }
        if (witnessFound && lastUsage != null) break
    }

    val witnessUsage = witnessMessage?.usage
    val witnessId = witnessMessage?.id ?: witnessNotice?.id
    val witnessIsCompaction = witnessNotice != null
    if (previous != null &&
        previous.witnessUsage === witnessUsage &&
        previous.witnessId == witnessId &&
        previous.witnessIsCompaction == witnessIsCompaction &&
        previous.lastUsage === lastUsage &&
        previous.selectedModel == selectedModel
    ) {
        return previous
    }

    val base = if (witnessUsage != null) {
        AgentContextUsageUi(
            contextTokens = witnessUsage.contextTokens,
            contextWindow = selectedModel?.contextWindow,
            latestUsage = witnessUsage,
            systemPromptTokens = witnessUsage.systemPromptTokens,
            toolDefinitionTokens = witnessUsage.toolDefinitionTokens,
            conversationTokens = witnessUsage.conversationTokens,
        )
    } else {
        AgentContextUsageUi(contextTokens = null, contextWindow = selectedModel?.contextWindow)
    }
    val usage = base.copy(
        // 与 Token 用量面板的「输出速度（TPS）」同一口径：只算真正在吐 token 的那段，
        // 否则输入框一个数、面板另一个数，用户只会当成算错。
        averageTokensPerSecond = lastUsage?.generationTokensPerSecond ?: lastUsage?.averageTokensPerSecond,
        latestUsage = base.latestUsage ?: lastUsage,
    )
    return ContextUsageProjection(
        witnessUsage = witnessUsage,
        witnessId = witnessId,
        witnessIsCompaction = witnessIsCompaction,
        lastUsage = lastUsage,
        selectedModel = selectedModel,
        usage = usage,
    )
}

// ── @ 引用候选 ───────────────────────────────────────────────────────

/**
 * @ 引用候选投影。[rawContents] 与 [candidates] 按下标一一对应，存生成候选时
 * 用到的**未 trim** 正文，用于下次零分配地判断该候选是否需要重建。
 */
internal data class ReferenceCandidatesProjection(
    val candidates: List<MessageReferenceUi>,
    val rawContents: List<String>,
)

/**
 * 与「倒扫尾部 [limit] 条有正文的消息、再翻回正序」等价的候选投影。
 *
 * 省掉的开销：流式期间每个增量不再为最多 80 条候选逐条跑一次
 * `Regex("\\s+").replace`（作用在**整段**正文上，可能是几万字）与字符串截断；
 * 实例未变的候选连同列表一起复用，只有真正追加了正文的那一条重建。
 * 预览也改成只扫描产出 60 字所需的前缀，不再对整段正文跑正则。
 */
internal fun projectReferenceCandidates(
    messages: List<AgentChatMessageUi>,
    limit: Int,
    previous: ReferenceCandidatesProjection?,
): ReferenceCandidatesProjection {
    val previousCandidates = previous?.candidates ?: emptyList()
    val previousRaw = previous?.rawContents ?: emptyList()
    val initialCapacity = limit.coerceAtMost(messages.size).coerceAtLeast(0)
    val result = ArrayList<MessageReferenceUi>(initialCapacity)
    val rawContents = ArrayList<String>(initialCapacity)
    var allReused = true
    // 已收集的候选数 == 上一版窗口内的位置偏移（上一版只装正文消息，两边口径一致）。
    var collected = 0
    // 上一版与当前窗口的固定位置差。流式追加会让窗口整体后移，差值是常量，
    // 所以只在第一次对不上时线性找一次（≤ limit 次 id 比较，零分配），
    // 之后直接用位移对齐，不再为每个增量分配 HashMap。
    var shift: Int? = null

    for (index in messages.lastIndex downTo 0) {
        val message = messages[index]
        val raw = messageReferenceContent(message) ?: continue
        if (raw.isBlank()) continue

        val base = previousCandidates.size - 1 - collected
        var alignedIndex = base + (shift ?: 0)
        var aligned = previousCandidates.getOrNull(alignedIndex)
        if (shift == null && aligned?.messageId != message.id && previousCandidates.isNotEmpty()) {
            // 窗口平移了：找这条消息在上一版里的位置，定下位移。
            val slot = previousCandidates.indexOfFirst { it.messageId == message.id }
            if (slot >= 0) {
                shift = slot - base
                alignedIndex = slot
                aligned = previousCandidates[slot]
            }
        }
        // 复用的充要条件：同一 id、同一正文实例、序号与角色都没变 ⇒ trim/截断/预览必然相同。
        val reusable = aligned?.takeIf {
            it.messageId == message.id &&
                previousRaw.getOrNull(alignedIndex) === raw &&
                it.sequence == index + 1 &&
                it.fromUser == (message is UserMessageUi)
        }

        if (reusable != null) {
            result.add(reusable)
        } else {
            val content = raw.trim()
            result.add(
                MessageReferenceUi(
                    messageId = message.id,
                    sequence = index + 1,
                    fromUser = message is UserMessageUi,
                    preview = collapsedPreview(content),
                    searchText = content.take(ReferenceSearchTextLimit),
                ),
            )
            allReused = false
        }
        rawContents.add(raw)
        collected++
        if (collected == limit) break
    }

    result.reverse()
    rawContents.reverse()
    val stableCandidates = if (allReused && result.size == previousCandidates.size) {
        previousCandidates
    } else {
        result
    }
    val stableRaw = if (allReused && rawContents.size == previousRaw.size) previousRaw else rawContents
    return ReferenceCandidatesProjection(stableCandidates, stableRaw)
}

private fun messageReferenceContent(message: AgentChatMessageUi): String? = when (message) {
    is UserMessageUi -> message.content
    is AgentMessageUi -> message.content
    else -> null
}

/**
 * 等价于 `content.replace(Regex("\\s+"), " ").take(limit)`，但只遍历到产出 [limit]
 * 个字符为止。`\s` 在 Java/Kotlin 正则里默认是 `[ \t\n\x0B\f\r]`，这里逐字符复刻同一集合。
 */
internal fun collapsedPreview(content: String, limit: Int = PreviewLengthLimit): String {
    if (content.isEmpty() || limit <= 0) return ""
    val builder = StringBuilder(limit)
    var inWhitespace = false
    for (char in content) {
        // 已产出 limit 个字符：正则版 take(limit) 之后不可能再变长，后续空白只会被
        // 合并进已有的那个空格，因此这里可以安全收工，不再扫完可能几万字的正文。
        if (builder.length == limit) break
        val whitespace = char == ' ' || char == '\t' || char == '\n' ||
            char == '\u000B' || char == '\u000C' || char == '\r'
        if (whitespace) {
            if (!inWhitespace) {
                builder.append(' ')
                inWhitespace = true
            }
        } else {
            builder.append(char)
            inWhitespace = false
        }
    }
    return builder.toString()
}

// ── @ 面板匹配 ───────────────────────────────────────────────────────

/**
 * @ 面板候选匹配投影。
 *
 * [matchedFor] 与 [matched] 按下标对齐：`matched[i]` 是 `matchedFor[i]` 这条候选
 * 在 [query] 下的命中结果。下次先用实例引用定位一次位移，再按下标取回命中结果，
 * 不必重跑 `contains`。
 */
internal class MentionMatchesProjection(
    val query: String,
    val matchedFor: List<MessageReferenceUi>,
    val matched: BooleanArray,
    val matches: List<MessageReferenceUi>,
) {
    fun withCandidates(
        matchedFor: List<MessageReferenceUi>,
        matched: BooleanArray,
    ): MentionMatchesProjection = MentionMatchesProjection(
        query = query,
        matchedFor = matchedFor,
        matched = matched,
        matches = matches,
    )
}

/**
 * 与输入框原有匹配逻辑一致：空查询取尾部 [limit] 条并倒序；否则按全文
 * （[MessageReferenceUi.searchText]，最多 4000 字）与预览做大小写无关包含匹配，
 * 再取尾部 [limit] 条倒序。
 *
 * 省掉的开销：
 * 1. 面板关闭时查询为空，走「尾部 limit 条」分支，不读任何正文；结果与上一版
 *    逐项同一时直接返回上一版实例，面板不因流式增量重组（原来每个增量都新建列表）。
 * 2. 面板打开且模型仍在流式输出时，不再对全部 80 条候选重跑
 *    `contains(..., ignoreCase = true)`（最多 80 × 4000 字符扫描）：命中结果按
 *    实例引用对齐上一版，只有实例变化的那一条重新判定。
 */
internal fun projectMentionMatches(
    candidates: List<MessageReferenceUi>,
    query: String,
    limit: Int,
    previous: MentionMatchesProjection?,
): MentionMatchesProjection {
    if (query.isEmpty()) {
        // 面板关闭的常态路径：等价于 candidates.takeLast(limit).reversed()，
        // 但先逐项比实例，命中时连一个列表都不分配。
        val wanted = minOf(candidates.size, limit)
        if (previous != null && previous.query.isEmpty() && previous.matches.size == wanted) {
            var identical = true
            for (offset in 0 until wanted) {
                if (previous.matches[offset] !== candidates[candidates.size - 1 - offset]) {
                    identical = false
                    break
                }
            }
            if (identical) return previous
        }
        val tail = ArrayList<MessageReferenceUi>(wanted)
        for (offset in 0 until wanted) tail.add(candidates[candidates.size - 1 - offset])
        return MentionMatchesProjection(
            query = "",
            matchedFor = emptyList(),
            matched = EmptyMatchedFlags,
            matches = tail,
        )
    }

    // 上一版同查询下的命中结果。候选窗口只有两种变化：尾部追加新候选，或窗口满了
    // 从头部丢一条 —— 两种情况下「首条候选」在上一版里都还在，用它定位一次位移
    // （O(n) 引用比较、零分配），之后逐条按位移对齐即可。定位不到时才全量重算。
    val previousMatches = previous?.takeIf { it.query == query }
    val shift = if (previousMatches == null || candidates.isEmpty()) {
        null
    } else {
        val slot = previousMatches.matchedFor.indexOfFirst { it === candidates[0] }
        if (slot >= 0) slot else null
    }
    val matched = BooleanArray(candidates.size) { index ->
        val candidate = candidates[index]
        val slot = if (shift != null) index + shift else -1
        if (previousMatches != null &&
            slot >= 0 &&
            slot < previousMatches.matchedFor.size &&
            slot < previousMatches.matched.size &&
            previousMatches.matchedFor[slot] === candidate
        ) {
            // 同一条候选实例、同一查询 ⇒ 命中结果必然相同，不再扫 4000 字正文。
            previousMatches.matched[slot]
        } else {
            candidate.matchesQuery(query)
        }
    }
    val matches = takeTailReversed(candidates, matched, limit)
    if (previous != null && previous.query == query && sameReferences(previous.matches, matches)) {
        return previous.withCandidates(matchedFor = candidates, matched = matched)
    }
    return MentionMatchesProjection(
        query = query,
        matchedFor = candidates,
        matched = matched,
        matches = matches,
    )
}

private val EmptyMatchedFlags = BooleanArray(0)

/** `candidates.filter { matched }.takeLast(limit).reversed()`，一次分配。 */
private fun takeTailReversed(
    candidates: List<MessageReferenceUi>,
    matched: BooleanArray,
    limit: Int,
): List<MessageReferenceUi> {
    var total = 0
    for (flag in matched) if (flag) total++
    val wanted = minOf(total, limit)
    if (wanted <= 0) return emptyList()
    val result = ArrayList<MessageReferenceUi>(wanted)
    for (index in candidates.lastIndex downTo 0) {
        if (!matched[index]) continue
        result.add(candidates[index])
        if (result.size == wanted) break
    }
    return result
}

private fun MessageReferenceUi.matchesQuery(query: String): Boolean =
    searchText.contains(query, ignoreCase = true) || preview.contains(query, ignoreCase = true)

// ── 引用比对工具 ─────────────────────────────────────────────────────

internal fun sameInstances(
    previous: List<AgentChatMessageUi>,
    next: List<AgentChatMessageUi>,
): Boolean {
    if (previous === next) return true
    if (previous.size != next.size) return false
    for (index in next.indices) {
        if (previous[index] !== next[index]) return false
    }
    return true
}

private fun prefixSameInstances(
    previous: List<AgentChatMessageUi>,
    next: List<AgentChatMessageUi>,
    endExclusive: Int,
): Boolean {
    for (index in 0 until endExclusive) {
        if (previous[index] !== next[index]) return false
    }
    return true
}

private fun sameReferences(
    previous: List<MessageReferenceUi>,
    next: List<MessageReferenceUi>,
): Boolean {
    if (previous === next) return true
    if (previous.size != next.size) return false
    for (index in next.indices) {
        if (previous[index] !== next[index]) return false
    }
    return true
}

internal const val ReferenceSearchTextLimit = 4_000
internal const val PreviewLengthLimit = 60
