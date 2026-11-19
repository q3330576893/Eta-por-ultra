package io.github.mangi.eta.agent.display.dashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.EtaTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次待用户裁决的拦截：会话内审批卡与通知共用同一份，避免两个入口口径分裂。
 *
 * [token] 是唯一凭据：批准/拒绝必须带它回来，过期或不匹配一律作废，
 * 防止用户点开旧通知时误批了后面那一单。
 */
internal data class PendingApproval(
    val token: String,
    /** 来源：拦截产生（guard）还是模型主动提问（ask）。决定用户选完怎么继续。 */
    val source: String = SOURCE_GUARD,
    val risk: ConfirmRisk,
    val title: String,
    val fields: List<ConfirmField>,
    /** 静默档：只在聊天页留卡，不发通知、不振动、不抢焦点。 */
    val quiet: Boolean,
    /** 主体签名：同一主体在宽限期内不再重复询问。 */
    val subject: String = "",
    /** 同一主体被重复拦截的次数（>1 时卡上提示，不再重复通知）。 */
    val repeatCount: Int = 1,
    /** 卡片正文：说明「这一步要做什么、为什么问你」。 */
    val body: String = "",
    /** 复盘记忆审批的候选正文，绑定此 token 持久保存，点击批准时由本地仓库一次性写入。 */
    val memoryReviewCandidates: List<String> = emptyList(),
    /** 可选项，至少两个。空表示用默认的批准/拒绝。 */
    val options: List<PromptOption> = emptyList(),
    /** 是否允许多选（当前 UI 只做单选，字段先留着给多选批次）。 */
    val allowMultiple: Boolean = false,
    /** 归属会话：新开会话不再继承上一会话的卡。 */
    val conversationKey: String = "",
    /** Exact originating run, retained even after RunControl removes its live mapping. */
    val runId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isMemoryReview: Boolean get() = source == SOURCE_MEMORY_REVIEW ||
        source == SOURCE_ASK && title == "后台复盘提炼出几条可长期保存的经验，是否写入记忆？"

    val riskLabel: String
        get() = when (risk) {
            ConfirmRisk.MONEY -> "资金"
            ConfirmRisk.PRIVACY -> "个人信息"
            ConfirmRisk.IRREVERSIBLE -> "不可撤销"
        }

    /** 资金类不提供「以后不问」：钱每次都该人批。 */
    val canRemember: Boolean get() = risk != ConfirmRisk.MONEY

    fun expired(now: Long = System.currentTimeMillis()): Boolean =
        createdAt <= 0L || createdAt > now + 60_000L || now - createdAt > LIFETIME_MS

    companion object {
        /** 超过这个时间，用户再点就当作没看见：不批、不拒，只作废。 */
        const val LIFETIME_MS = 10 * 60 * 1000L

        const val SOURCE_GUARD = "guard"
        const val SOURCE_ASK = "ask"
        const val SOURCE_MEMORY_REVIEW = "memory_review"
    }
}

/**
 * 决策拦截器的执行侧：把 [ConfirmRiskDetector] 的判定结果变成「拦下来 + 等用户裁决」。
 *
 * 判定本身是纯函数，这里只负责四件事：
 * 1. 记住最近一次的屏上文本，供判定当上下文；
 * 2. 命中时生成 [PendingApproval]，交给聊天页内联卡与（按风险档位的）通知；
 * 3. 给调用方一个可直接返回给模型的 `NEED_CONFIRM` 结果，**不执行这次注入**；
 * 4. 用户裁决后，把结论通过 steering 通道回灌给运行中的 AI。
 *
 * 为什么批准不「重放旧坐标」：拦截到批准之间可能过了几十秒，快照早过期，
 * 盲目注入正是最危险的来源。正确语义是「给 AI 一个可以继续的许可」，
 * 由它自己重新观察再走一步——Muse / Grok Bot 也是这个模型。
 *
 * 取舍：宁可多拦一次，也不放行一次扣款。误拦的代价是模型多问一句，
 * 漏拦的代价是钱已经花出去。
 */
internal object AgentConfirmGuard {

    private const val CHANNEL = "eta_confirm_required"
    /** 静默渠道：IMPORTANCE_LOW，不响不振动不抬头，只在通知栏里等着被翻到。 */
    private const val CHANNEL_QUIET = "eta_confirm_quiet"
    private const val NOTIFICATION_ID = 8891

    /** 批准后同一主体在宽限期内直接放行：同一个按钮不该被连着问三遍。 */
    private const val GRANT_WINDOW_MS = 30 * 60 * 1000L

    /** 用户显式选择「24 小时内不再问」时的宽限期。同样只在内存里，重启即失效。 */
    private const val GRANT_LONG_MS = 24 * 60 * 60 * 1000L

    /** 强打扰预算：一小时内最多几条审批通知，超出降级为静默卡（卡仍在会话里）。 */
    private const val MAX_NOTIFY_PER_HOUR = 2

    /** subject -> 放行截止时间。纯内存，进程重启即失效，不需要撤销入口。 */
    private val grants = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** [0]=窗口起点，[1]=窗口内已发通知数。 */
    private val notifyWindow = LongArray(2)

    /**
     * 每个会话各挂一张卡，互不覆盖。
     *
     * 早先是全局单个 pending：A 会话有卡在等时，B 会话连提问都调不动
     * （`ask` 直接返回 PROMPT_PENDING），等于一张卡锁死全部对话。
     */
    private val mutablePendings = MutableStateFlow<List<PendingApproval>>(emptyList())
    private val ledger = ApprovalLedger(persist = { cards, manuals, answer ->
        PendingPromptStore.commit(cards, manuals, answer).also { saved ->
            if (saved) mutablePendings.value = cards
        }
    })
    @Volatile
    private var initialized = false

    /** 全部待答卡。 */
    val pendings: StateFlow<List<PendingApproval>> = mutablePendings.asStateFlow()

    /** 指定会话的待答卡（同一会话最多一张）。 */
    fun pendingFor(conversationKey: String): PendingApproval? =
        mutablePendings.value.firstOrNull {
            !it.expired() && it.conversationKey == conversationKey
        }

    /** 别的会话还挂着的卡：用来在当前会话里提示「那边有一条在等你」。 */
    fun pendingElsewhere(conversationKey: String): List<PendingApproval> =
        mutablePendings.value.filter {
            !it.expired() && it.conversationKey.isNotBlank() && it.conversationKey != conversationKey
        }

    private var tokenSeed = 0L

    /** 当前会话 key：由 UI 层在切换会话时写入，用来做卡片的会话归属。 */
    @Volatile
    private var currentConversation: String = ""

    /**
     * 启动时恢复未答的卡。
     *
     * 之前 pending 只在内存里，进程被 LMK 杀掉后卡片直接消失，用户回来什么也看不到。
     * 重启后把落盘那条读回来，过期（>10 分钟）的直接丢掉。
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        PendingPromptStore.init(context)
        val stored = PendingPromptStore.load()
        val restored = stored.filter { !it.expired() && !(it.isMemoryReview &&
            (it.conversationKey.isBlank() || it.memoryReviewCandidates.isEmpty())) }
        // Handback remains available after the original approval card's ten-minute answer window.
        val manuals = PendingPromptStore.loadManuals()
        ledger.restore(restored, manuals)
        mutablePendings.value = ledger.all()
        initialized = true
        if (restored.size != PendingPromptStore.load().size || manuals.size != PendingPromptStore.loadManuals().size) {
            PendingPromptStore.commit(restored, manuals, null)
        }
    }

    /** UI 切会话时调用：只记录「现在看的是哪个」，卡的取舍交给 [pendingFor]/[pendingElsewhere]。 */
    fun bindConversation(conversationKey: String) {
        currentConversation = conversationKey
        if (initialized) prunePending()
    }

    fun prunePending(context: Context? = null) {
        ledger.pruneInvalid().forEach { clearNotification(context, it.token) }
    }

    enum class MemoryReviewDecision { SAVED, DISCARDED, NO_LONGER_PENDING }

    /** Call from a worker: durable append precedes consuming the card; append itself is token-idempotent. */
    fun decideMemoryReview(context: Context, token: String, owner: String, save: Boolean): MemoryReviewDecision {
        ensureInitialized(context)
        var stored = false
        val consumed = ledger.consumeLocal(token, owner) { card ->
            if (save) {
                check(kotlinx.coroutines.runBlocking { io.github.mangi.eta.data.repository.AgentMemoryRepository.isEnabled() }) { "记忆已关闭，请先启用记忆或选择丢弃" }
                val validated = io.github.mangi.eta.agent.runtime.ExperienceReviewPolicy.distillCandidates(card.memoryReviewCandidates.joinToString("\n"))
                check(validated == card.memoryReviewCandidates) { "候选内容已失效，请丢弃后重新复盘" }
                io.github.mangi.eta.data.repository.AgentMemoryRepository.appendReviewedCandidates(card.token, validated)
                stored = true
            }
        }
        if (!consumed) {
            if (ledger.find(token)?.let { it.isMemoryReview && it.conversationKey == owner } == true) {
                throw java.io.IOException("复盘处理状态尚未保存，请重试")
            }
            prunePending(context)
            return MemoryReviewDecision.NO_LONGER_PENDING
        }
        clearNotification(context, token)
        return if (stored) MemoryReviewDecision.SAVED else MemoryReviewDecision.DISCARDED
    }

    /**
     * 由 runId 反查它属于哪个会话。
     *
     * 卡片的归属必须是「发起这次调用/提问的会话」，不是「UI 现在正在看的会话」。
     * 之前用 currentConversation 当归属，A 会话弹卡时如果你正看着 B，
     * 卡就被打上 B 的标签、显示在 B、选完也发去 B（实测踩到）。
     */
    private fun conversationOfRun(runId: String): String {
        if (runId.isBlank()) return ""
        return runCatching {
            io.github.mangi.eta.agent.runtime.RunControl.state.value.conversationOfRun[runId].orEmpty()
        }.getOrDefault("")
    }

    /** 用户此刻正在看的会话（由 UI 的 bindConversation 写入）。 */
    val viewedConversation: String get() = currentConversation

    /** 卡片是否该在当前会话显示。 */
    fun visibleIn(conversationKey: String): Boolean = pendingFor(conversationKey) != null

    /** 屏上一个可点区域：文本 + 中心坐标。 */
    data class Spot(val label: String, val x: Int, val y: Int)

    /** 最近一次观测到的可点区域。只在内存里，不出进程。 */
    @Volatile
    private var spots: List<Spot> = emptyList()

    /** 最近一次观测到的全部可见文本。 */
    @Volatile
    private var texts: List<String> = emptyList()

    /** 这些动作会真的落到 App 上，注入前必须过一遍判定。 */
    private val GUARDED_ACTIONS = setOf(
        "tap", "tap_area", "long_press", "tap_smart", "tap_element", "long_press_element", "touch",
    )

    fun remember(nodes: List<Spot>, visible: List<String>) {
        spots = nodes
        texts = visible
    }

    fun shouldGuard(action: String): Boolean = action in GUARDED_ACTIONS

    /** 纯坐标点击：按最近的节点缓存反查这一下落在哪个控件文本上。 */
    fun labelAt(x: Int, y: Int): String? = spots.lastOrNull { spot ->
        kotlin.math.abs(spot.x - x) <= 48 && kotlin.math.abs(spot.y - y) <= 48
    }?.label

    /**
     * 判定并提醒。返回 null 表示放行；非 null 表示必须拦下，
     * 调用方应当直接把它作为工具结果返回，不要执行注入。
     */
    fun inspect(context: Context?, target: String?, action: String, runId: String = ""): ConfirmDraft? {
        // 先按控件文本判；判不出来再看整屏是不是收银台——
        // 拼多多/淘宝这类 App 的支付按钮常在自定义 View 里，无障碍拿到的 text 是空的，
        // 只靠控件文本会漏掉最关键的那一下（实测：点「立即支付」没拦，钱真扣了）。
        val draft = ConfirmRiskDetector.inspect(target, texts) ?: checkoutFallback() ?: return null
        EtaTrace.log("confirm.block", "action=$action risk=${draft.risk} target=${target?.take(40)}")
        // 个人信息类降级为静默：卡留在会话里，但不弹通知、不振动。
        // 资金与不可撤销仍然要高优先级喊人——这两类漏一次的代价不可挽回。
        return register(context, draft, target, action, runId)
    }

    /**
     * 非 GUI 工具（terminal / write_file …）的拦截入口。
     *
     * 与 GUI 共用同一张卡、同一套 grant 与回灌，只是判定换成
     * [ConfirmRiskDetector.inspectTool]：命令与参数就是证据，没有屏幕可看。
     */
    fun inspectTool(
        context: Context?,
        toolName: String,
        args: org.json.JSONObject,
        runId: String = "",
    ): ConfirmDraft? {
        val draft = ConfirmRiskDetector.inspectTool(toolName, args) ?: return null
        val target = when (toolName) {
            "terminal", "terminal_job", "terminal_daemon" -> args.optString("command").take(40)
            else -> args.optString("path").take(40)
        }
        return register(context, draft, target, toolName, runId)
    }

    /** GUI 与非 GUI 共用的登记路径：宽限期、去重、落卡、通知、暂停。 */
    private fun register(
        context: Context?,
        draft: ConfirmDraft,
        target: String?,
        action: String,
        runId: String = "",
    ): ConfirmDraft? {
        val subject = subjectOf(draft, target)
        // 归属优先取 run 自己的会话；查不到才退回 UI 当前会话（单会话场景下等价）。
        ensureInitialized(context)
        // A known run without a resolved conversation keeps an empty conversation key;
        // the runtime seam can still target the exact run, while UI routing never guesses an owner.
        val owner = if (runId.isBlank()) currentConversation else conversationOfRun(runId)
        val now = System.currentTimeMillis()
        // 刚批准过同一主体：宽限期内直接放行，模型可以继续，不再问第二遍。
        val grantedUntil = grants[subject]
        if (grantedUntil != null && grantedUntil > now) {
            EtaTrace.log("confirm.granted", "subject=$subject")
            return null
        }
        if (grantedUntil != null) grants.remove(subject)

        val quiet = draft.risk == ConfirmRisk.PRIVACY
        val options = buildList {
            add(PromptOption(PromptOptionId.APPROVE, "批准这一步", "按它说的做"))
            add(PromptOption(PromptOptionId.REJECT, "拒绝", "停下，让它换个做法"))
            add(PromptOption(PromptOptionId.MANUAL, "我自己来", "停下，你接管手动完成"))
            if (draft.risk != ConfirmRisk.MONEY) {
                add(PromptOption(PromptOptionId.REMEMBER, "这类以后不问", "24 小时内同一操作直接放行"))
            }
        }
        val body = when (draft.risk) {
            ConfirmRisk.MONEY -> "它准备付钱，这一步花出去就收不回来。"
            ConfirmRisk.PRIVACY -> "它准备提交你的个人信息。"
            ConfirmRisk.IRREVERSIBLE -> "这一步提交后无法撤销。"
        }
        // 同一会话里同一主体重复拦：不刷新卡片、不重复通知，只累加次数。
        // 别的会话的卡一律保留，不能因为这边又拦了一次就把它们冲掉。
        val previous = mutablePendings.value.firstOrNull { it.conversationKey == owner && !it.expired() }
        val pending = if (previous != null && previous.subject == subject && previous.runId == runId.takeIf(String::isNotBlank)) {
            previous.copy(repeatCount = previous.repeatCount + 1)
        } else {
            PendingApproval(
                token = nextToken(),
                risk = draft.risk,
                title = draft.title,
                fields = draft.fields,
                quiet = quiet,
                subject = subject,
                body = body,
                options = options,
                conversationKey = owner,
                runId = runId.takeIf(String::isNotBlank),
            )
        }
        ledger.replaceForOwner(pending)
        // 刻意不 pause。
        // 早先的做法是命中即 pauseRun()，想着「别让模型继续跑」。实测两次翻车：
        // ① 模型拿到 NEED_CONFIRM 后本来就把这一轮收尾了，run 变 terminal，
        //    用户裁决时 resumeRun() 找不到活动 run，setPaused(false) 根本不执行，
        //    界面永远停在「已暂停 · 点继续恢复」；
        // ② 就算清了标记，运行线程仍卡在 pauseCondition.await()，模型照样不动。
        // 结论：拦截不需要暂停，它只需要「这一句不执行 + 把结论当成新消息推进下一轮」。
        if (pending.repeatCount == 1) {
            context?.let { runCatching { notify(it, pending) } }
        } else {
            EtaTrace.log("confirm.repeat", "subject=$subject count=${pending.repeatCount}")
        }
        return draft
    }

    /** Resolve once and retain the answer. This method never steers or resumes a runtime. */
    fun resolve(
        context: Context?, token: String, optionId: String, conversationId: String = "",
    ): ApprovalResolution? = resolveOption(context, token, optionId, conversationId, retainAnswer = true)

    private fun resolveOption(
        context: Context?, token: String, optionId: String, conversationId: String, retainAnswer: Boolean,
    ): ApprovalResolution? {
        var approved: PendingApproval? = null
        val resolution = resolveAnswer(context, token, conversationId, retainAnswer) { current ->
            ApprovalAnswers.option(current, optionId)?.also {
                if (optionId == PromptOptionId.APPROVE || optionId == PromptOptionId.REMEMBER) approved = current
            }
        }
        if (resolution != null) approved?.let { grantFor(it, optionId == PromptOptionId.REMEMBER) }
        return resolution
    }

    fun resolveMany(
        context: Context?, token: String, optionIds: List<String>, conversationId: String = "",
    ): ApprovalResolution? = resolveAnswer(context, token, conversationId) { ApprovalAnswers.many(it, optionIds) }

    fun resolveFreeform(
        context: Context?, token: String, text: String, extraIds: List<String> = emptyList(), conversationId: String = "",
    ): ApprovalResolution? = resolveAnswer(context, token, conversationId) { ApprovalAnswers.freeform(it, text, extraIds) }

    fun resolveSkip(context: Context?, token: String, conversationId: String = ""): ApprovalResolution? =
        resolveAnswer(context, token, conversationId, answer = ApprovalAnswers::skip)

    private fun resolveAnswer(
        context: Context?, token: String, conversationId: String, retainAnswer: Boolean = true,
        answer: (PendingApproval) -> ApprovalResolution?,
    ): ApprovalResolution? {
        ensureInitialized(context)
        if (ledger.find(token)?.isMemoryReview == true) return null
        return ledger.resolve(token, conversationId, retainAnswer, answer)?.also { clearNotification(context, token) }
    }

    /** String compatibility: ChatBody owns delivery through onSubmit; this path never queues a second delivery. */
    fun choose(context: Context?, token: String, optionId: String, conversationId: String = ""): String? =
        resolveOption(context, token, optionId, conversationId, retainAnswer = false)?.followUp

    fun chooseMany(context: Context?, token: String, optionIds: List<String>, conversationId: String = ""): String? =
        resolveAnswer(context, token, conversationId, retainAnswer = false) { ApprovalAnswers.many(it, optionIds) }?.followUp

    fun chooseFreeform(
        context: Context?, token: String, text: String, extraIds: List<String> = emptyList(), conversationId: String = "",
    ): String? = resolveAnswer(context, token, conversationId, retainAnswer = false) {
        ApprovalAnswers.freeform(it, text, extraIds)
    }?.followUp

    fun skip(context: Context?, token: String, conversationId: String = ""): String? =
        resolveAnswer(context, token, conversationId, retainAnswer = false, answer = ApprovalAnswers::skip)?.followUp

    /** Boolean compatibility for notification callers: retain and dispatch rather than drop the text. */
    fun approve(context: Context?, token: String, conversationId: String = "", remember: Boolean = false): Boolean {
        val answer = resolve(context, token, if (remember) PromptOptionId.REMEMBER else PromptOptionId.APPROVE, conversationId)
            ?: return false
        context?.let { ApprovalFollowUpDispatcher.dispatch(it, answer) }
        return true
    }

    fun reject(context: Context?, token: String, conversationId: String = ""): Boolean {
        val answer = resolve(context, token, PromptOptionId.REJECT, conversationId) ?: return false
        context?.let { ApprovalFollowUpDispatcher.dispatch(it, answer) }
        return true
    }

    fun approval(token: String): PendingApproval? = ledger.find(token)

    fun manualTakeover(token: String, conversationId: String = ""): PendingApproval? = ledger.manual(token, conversationId)

    fun completeManual(context: Context?, token: String, conversationId: String = ""): ApprovalResolution? {
        ensureInitialized(context)
        return ledger.completeManual(token, conversationId)?.also {
            // The durable transaction replaces the initial manual notice with this handback.
            clearNotification(context, token)
        }
    }

    private fun ensureInitialized(context: Context?) {
        if (context != null && !initialized) init(context)
    }

    /**
     * AI 主动提问：带选项、暂停 run、等用户选。
     *
     * 与拦截的区别只在来源：拦截是系统判定的「危险动作」，这里是模型自己觉得
     * 「我拿不准，需要人来定」。两者共用同一张卡和同一套回灌。
     */
    @Synchronized
    fun ask(
        context: Context?,
        question: String,
        body: String,
        options: List<PromptOption>,
        allowMultiple: Boolean = false,
        runId: String = "",
        conversationKey: String? = null,
        source: String = PendingApproval.SOURCE_ASK,
        memoryReviewCandidates: List<String> = emptyList(),
    ): String {
        // 一次只留一张卡：直接覆盖会让上一问凭空消失，用户还没答就丢了。
        ensureInitialized(context)
        val owner = conversationKey?.takeIf(String::isNotBlank)
            ?: if (runId.isBlank()) currentConversation else conversationOfRun(runId)
        prunePending(context)
        val existing = mutablePendings.value.firstOrNull { it.conversationKey == owner && !it.expired() }
        if (existing != null) {
            return JSONObject()
                .put("ok", false)
                .put("code", "PROMPT_PENDING")
                .put("message", "已经有一张卡在等用户回答，不要重复提问；等他答完再问下一题。")
                .toString()
        }

        if (source == PendingApproval.SOURCE_MEMORY_REVIEW && (owner.isBlank() || memoryReviewCandidates.isEmpty())) {
            return JSONObject().put("ok", false).put("code", "REVIEW_OWNER_REQUIRED").toString()
        }
        val clean = options.filter { it.label.isNotBlank() }
        if (question.isBlank() || clean.size < 2) {
            return JSONObject()
                .put("ok", false)
                .put("code", "INVALID_ASK")
                .put("message", "ask_user 至少需要一句问题和两个选项。")
                .toString()
        }
        val pending = PendingApproval(
            token = nextToken(),
            risk = ConfirmRisk.IRREVERSIBLE,
            title = question.take(60),
            fields = emptyList(),
            // 不再强制静默：用户不在 App 里时必须能收到提醒，否则他根本不知道有人在等回答。
            // 在不在前台由 notify 用 AppForeground 判断。
            quiet = source == PendingApproval.SOURCE_MEMORY_REVIEW,
            subject = "",
            body = body,
            options = clean,
            allowMultiple = allowMultiple,
            conversationKey = owner,
            runId = runId.takeIf(String::isNotBlank),
            source = source,
            memoryReviewCandidates = memoryReviewCandidates.take(3).map { it.take(300) },
        )
        ledger.replaceForOwner(pending)
        if (ledger.find(pending.token) == null) {
            return JSONObject().put("ok", false).put("code", "PROMPT_SAVE_FAILED")
                .put("message", "待处理内容保存失败，原卡片未变更").toString()
        }
        // 提问也要发通知：用户可能正在别的应用里，不提醒他根本不知道有人在等。
        // （早先只有拦截路径调了 notify，提问这条线一直是静默的。）
        context?.let { runCatching { notify(it, pending) } }
        // 刻意不 pause：本轮会拿到 AWAITING_USER 后自然收尾。
        // 挂住反而不对——用户选完时 run 往往已经结束，resume/steer 都会落空。
        AndroidAgentLogger.info("Ask user: ${question.take(40)} options=${clean.size}")
        return JSONObject()
            .put("ok", true)
            .put("code", "AWAITING_USER")
            .put("token", pending.token)
            .put("message", "问题已经展示给用户，本轮已暂停。用户选完会以消息形式告诉你他选了什么，不要自己猜答案，也不要重复提问。")
            .toString()
    }

    private fun grantFor(current: PendingApproval, remember: Boolean) {
        if (current.subject.isBlank() || current.risk == ConfirmRisk.MONEY) return
        val window = if (remember) GRANT_LONG_MS else GRANT_WINDOW_MS
        grants[current.subject] = System.currentTimeMillis() + window
    }

    /** Only dismiss this token; unrelated runs and paused markers remain untouched. */
    fun dismiss(token: String) {
        ledger.dismiss(token)
    }

    /** 每张卡一个通知 id：多会话时不能互相覆盖。 */
    private fun notificationIdOf(token: String): Int = NOTIFICATION_ID + (token.hashCode() and 0x7FFF)

    private fun clearNotification(context: Context?, token: String = "") {
        val app = context?.applicationContext ?: return
        runCatching {
            val manager = app.getSystemService(NotificationManager::class.java)
            if (token.isBlank()) manager?.cancel(NOTIFICATION_ID)
            else manager?.cancel(notificationIdOf(token))
        }
    }

    /** 主体签名：风险 + 卡片标题 + 控件文本。同一个「立即支付」按钮落在这里。 */
    private fun subjectOf(draft: ConfirmDraft, target: String?): String =
        draft.risk.name + "|" + draft.title + "|" + target.orEmpty().trim().take(24)

    /** 一小时窗口内的通知预算；超出就只留会话卡，不再响人。 */
    private fun takeNotifyBudget(): Boolean = synchronized(notifyWindow) {
        val now = System.currentTimeMillis()
        if (now - notifyWindow[0] > 3_600_000L) {
            notifyWindow[0] = now
            notifyWindow[1] = 0
        }
        if (notifyWindow[1] >= MAX_NOTIFY_PER_HOUR) return@synchronized false
        notifyWindow[1] += 1
        true
    }

    /** 清空宽限期（设置页「撤销批准记忆」用；当前没有 UI 入口）。 */
    fun clearGrants() {
        grants.clear()
    }

    private fun nextToken(): String {
        tokenSeed += 1
        return "guard-" + java.util.UUID.randomUUID().toString()
    }

    private val amountPattern = Regex("""(?:¥|￥)\s?\d+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?\s?元""")

    /**
     * 页面级兜底：拿不到控件文本时，用整屏特征判。
     * 只在「金额 + 支付动作词」同时出现时成立，避免把普通浏览页也拦下来。
     */
    private fun checkoutFallback(): ConfirmDraft? {
        val all = texts.joinToString(" ")
        if (all.isBlank()) return null
        val amount = amountPattern.find(all)?.value ?: return null
        val payWord = ConfirmRiskDetector.irreversibleWords.firstOrNull { all.contains(it) } ?: return null
        val fields = buildList {
            add(ConfirmField("动作", payWord))
            add(ConfirmField("金额", amount))
            texts.firstOrNull { it.contains("实付") || it.contains("应付") || it.contains("合计") }
                ?.take(40)?.let { add(ConfirmField("订单", it)) }
            texts.firstOrNull { it.contains("收货") || it.contains("地址") }
                ?.take(40)?.let { add(ConfirmField("收货", it)) }
        }
        EtaTrace.log("confirm.checkout", "页面级命中 word=$payWord amount=$amount")
        return ConfirmDraft(ConfirmRisk.MONEY, "确认支付", fields)
    }

    /** 组装成给模型的回执：明确「没点」以及为什么没点，避免它换个坐标硬来。 */
    fun blockedResult(draft: ConfirmDraft): String = JSONObject()
        .put("ok", false)
        .put("code", "NEED_CONFIRM")
        .put("risk", draft.risk.name)
        .put("title", draft.title)
        .put(
            "message",
            "这一步涉及" + when (draft.risk) {
                ConfirmRisk.MONEY -> "资金支出"
                ConfirmRisk.PRIVACY -> "个人信息"
                ConfirmRisk.IRREVERSIBLE -> "不可撤销的提交"
            } + "，已拦下，没有执行。已给用户发提醒：请让用户自己确认或接管，不要换坐标重试。",
        )
        .put(
            "fields",
            JSONArray().apply {
                draft.fields.forEach { field ->
                    put(JSONObject().put("label", field.label).put("value", field.value))
                }
            },
        )
        .toString()

    private fun notify(context: Context, pending: PendingApproval) {
        // 静默档不打扰人，也就不必要求通知权限：没有权限时直接跳过，会话卡还在。
        if (pending.quiet) {
            AndroidAgentLogger.info("Confirm pending (quiet) risk=${pending.risk} title=${pending.title}")
            return
        }
        // 人就在 App 里：审批卡已经在会话末尾，再叠一条通知只是噪音。
        if (io.github.mangi.eta.core.AppForeground.isForeground) {
            AndroidAgentLogger.info("Confirm pending (in-app) risk=${pending.risk} title=${pending.title}")
            return
        }
        // 打扰预算只约束「拦截类」：那类可能一分钟内连拦好几次。
        // 提问类（ask_user）是模型等你拍板才发起的，一小时内本来就没几条，
        // 用预算把它静默掉等于让对话卡死——实测确实会被前面的拦截把额度吃光。
        if (pending.source != PendingApproval.SOURCE_ASK && !takeNotifyBudget()) {
            AndroidAgentLogger.info("Confirm notify budget exhausted risk=${pending.risk} title=${pending.title}")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            AndroidAgentLogger.warn("Confirm notification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.confirm_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.confirm_channel_desc)
                enableVibration(true)
            },
        )
        val token = pending.token
        // 通知正文要能看懂「在等什么」：有字段列字段，纯提问就列选项。
        val summary = if (pending.fields.isNotEmpty()) {
            pending.fields.joinToString(" · ") { "${it.label} ${it.value}" }.take(120)
        } else {
            pending.options.joinToString(" / ") { it.label }.take(120)
        }
        val title = if (pending.source == PendingApproval.SOURCE_ASK) {
            "Eta 有问题要问你"
        } else {
            context.getString(R.string.confirm_notify_title, pending.title)
        }
        // 证据图：从当前灵屏真帧裁一块，确认页展示真实画面而不是生成式卡片。
        val preview = runCatching { AgentConfirmActivity.capturePreview(context, token) }.getOrDefault("")
        // 提问的答案只能在会话卡上选，半屏确认页帮不上忙：直接进**那一张卡所属的会话**。
        // 带着会话 id 走 ACTION_OPEN_CONVERSATION_ID，MainActivity 会切到对应会话；
        // 用户在通知上长按/下拉进小窗，就能一边看别的应用一边把这一题答了。
        val askIntent = Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
            .setAction(io.github.mangi.eta.ui.MainActivity.ACTION_OPEN_CONVERSATION_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(
                io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_ID,
                pending.conversationKey,
            )
        val confirmIntent = Intent(context, AgentConfirmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(
            context,
            notificationIdOf(token),
            (if (pending.source == PendingApproval.SOURCE_ASK) askIntent else confirmIntent)
                // 跳转必须带上归属会话：不然只会停在上次那个会话，
                // 卡片按 conversationKey 过滤后就消失，用户看到「跳了但没东西」。
                .putExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_KEY, pending.conversationKey)
                .putExtra(AgentConfirmActivity.EXTRA_TOKEN, token)
                .putExtra(AgentConfirmActivity.EXTRA_CONVERSATION, pending.conversationKey)
                .putExtra(AgentConfirmActivity.EXTRA_TITLE, pending.title)
                .putExtra(AgentConfirmActivity.EXTRA_RISK, pending.risk.name)
                .putExtra(
                    AgentConfirmActivity.EXTRA_FIELDS,
                    pending.fields.joinToString("|") { "${it.label}=${it.value}" },
                )
                .putExtra(AgentConfirmActivity.EXTRA_PREVIEW_PATH, preview),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary.ifBlank { context.getString(R.string.confirm_notify_body) })
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setCategory(Notification.CATEGORY_REMINDER)
            // heads-up（悬浮通知）：不锁屏也能直接看到，且不会把当前应用顶走。
            .setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (pending.source == PendingApproval.SOURCE_GUARD) {
            builder.addAction(notificationAction(context, pending, AgentConfirmActionReceiver.ACTION_APPROVE, "批准"))
            builder.addAction(notificationAction(context, pending, AgentConfirmActionReceiver.ACTION_REJECT, "拒绝"))
            builder.addAction(notificationAction(context, pending, AgentConfirmActionReceiver.ACTION_MANUAL, "我自己来"))
        }
        manager.notify(notificationIdOf(token), builder.build())
        AndroidAgentLogger.info("Confirm required risk=${pending.risk} fields=${pending.fields.size}")
    }

    /** Replaces the consumed card with an exact-token handback action after manual takeover. */
    internal fun showManualCompletionNotification(context: Context, resolution: ApprovalResolution) {
        val pending = manualTakeover(resolution.token, resolution.conversationKey) ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.confirm_channel_name), NotificationManager.IMPORTANCE_HIGH),
        )
        val action = Intent(context, AgentConfirmActionReceiver::class.java)
            .setAction(AgentConfirmActionReceiver.ACTION_MANUAL_COMPLETE)
            .putExtra(AgentConfirmActionReceiver.EXTRA_TOKEN, pending.token)
            .putExtra(AgentConfirmActionReceiver.EXTRA_CONVERSATION, pending.conversationKey)
        val complete = PendingIntent.getBroadcast(
            context,
            notificationIdOf(pending.token) + 1,
            action,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context,
            notificationIdOf(pending.token),
            Intent(context, AgentConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AgentConfirmActivity.EXTRA_TOKEN, pending.token)
                .putExtra(AgentConfirmActivity.EXTRA_CONVERSATION, pending.conversationKey),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notificationIdOf(pending.token),
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("人工接管进行中")
                .setContentText("完成操作后，回到 Eta 继续")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(false)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "我已完成继续", complete).build())
                .build(),
        )
    }

    private fun notificationAction(
        context: Context,
        pending: PendingApproval,
        action: String,
        label: String,
    ): Notification.Action {
        val intent = Intent(context, AgentConfirmActionReceiver::class.java)
            .setAction(action)
            .putExtra(AgentConfirmActionReceiver.EXTRA_TOKEN, pending.token)
            .putExtra(AgentConfirmActionReceiver.EXTRA_CONVERSATION, pending.conversationKey)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            notificationIdOf(pending.token) * 31 + action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, label, pendingIntent).build()
    }
}
