package io.github.mangi.eta.agent.display.dashboard

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 卡片上的一个可选项。`id` 是回灌给模型的值，`label` 给用户看。 */
internal data class PromptOption(
    val id: String,
    val label: String,
    val description: String = "",
)

internal object PromptOptionId {
    const val APPROVE = "approve"
    const val REJECT = "reject"
    const val MANUAL = "manual"
    const val REMEMBER = "remember"
    const val MEMORY_REVIEW_WRITE = "memory_review_write"
    const val MEMORY_REVIEW_DISCARD = "memory_review_discard"
}

/** Cards, manual handbacks and their outbox share one synchronous preferences transaction. */
internal object PendingPromptStore {
    private const val FILE = "eta_pending_prompt"
    private const val KEY = "current"
    private const val MANUALS = "manuals"
    private const val FOLLOW_UPS = "follow_ups"
    private var prefs: SharedPreferences? = null
    private val claims = mutableSetOf<String>()
    private val mutableFollowUps = MutableStateFlow<List<ApprovalResolution>>(emptyList())

    val followUps: StateFlow<List<ApprovalResolution>> = mutableFollowUps.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        mutableFollowUps.value = decodeArray(prefs?.getString(FOLLOW_UPS, null), ::decodeResolution)
    }

    @Synchronized
    fun load(): List<PendingApproval> = decodeArray(prefs?.getString(KEY, null), ::decode)

    @Synchronized
    fun loadManuals(): List<PendingApproval> = decodeArray(prefs?.getString(MANUALS, null), ::decode)

    /** Consume a token and retain its answer atomically, before any IPC or UI submission. */
    @Synchronized
    fun commit(
        pendings: List<PendingApproval>,
        manuals: List<PendingApproval>,
        resolution: ApprovalResolution?,
    ): Boolean {
        val answers = mutableFollowUps.value.let { previous ->
            if (resolution == null) previous else {
                val superseded = if (resolution.kind == ApprovalResolution.Kind.MANUAL_COMPLETE) {
                    resolution.token.removeSuffix(":manual-complete")
                } else null
                previous.filter { it.token != resolution.token && it.token != superseded } + resolution
            }
        }
        val editor = prefs?.edit()
        // Null-context unit callers can still use the in-memory ledger. Production initializes in EtaApp.
        val saved = editor == null || editor
            .putString(KEY, encodeArray(pendings, ::encode))
            .putString(MANUALS, encodeArray(manuals, ::encode))
            .putString(FOLLOW_UPS, encodeArray(answers, ::encodeResolution))
            .commit()
        if (saved) mutableFollowUps.value = answers
        return saved
    }

    /** Exact identity match used by notification delivery; no new outbox row is created here. */
    @Synchronized
    fun pendingExactUnclaimed(resolution: ApprovalResolution): ApprovalResolution? =
        mutableFollowUps.value.firstOrNull { it.matches(resolution) && it.token !in claims }

    /** Claim only the exact token/owner/run tuple. A UI or another dispatcher claim wins the race. */
    @Synchronized
    fun claimExact(resolution: ApprovalResolution): ApprovalResolution? {
        val answer = pendingExactUnclaimed(resolution) ?: return null
        if (!claims.add(answer.token)) return null
        return answer
    }

    fun pendingFor(conversationKey: String): List<ApprovalResolution> =
        mutableFollowUps.value.filter { conversationKey.isNotBlank() && it.conversationKey == conversationKey }

    @Synchronized
    fun claim(token: String): ApprovalResolution? {
        val answer = mutableFollowUps.value.firstOrNull { it.token == token } ?: return null
        if (!claims.add(token)) return null
        return answer
    }

    /** Hold the store lock through the short entry action so a UI claim cannot race the final check. */
    @Synchronized
    fun ifPendingExactUnclaimed(resolution: ApprovalResolution, action: (ApprovalResolution) -> Unit): Boolean {
        val answer = pendingExactUnclaimed(resolution) ?: return false
        action(answer)
        return true
    }

    @Synchronized
    fun release(token: String) {
        claims.remove(token)
    }

    private fun ApprovalResolution.matches(other: ApprovalResolution): Boolean = this == other

    /** Acknowledgement follows an accepted runtime message or the original conversation's submit. */
    @Synchronized
    fun acknowledge(token: String): Boolean {
        val next = mutableFollowUps.value.filter { it.token != token }
        if (prefs?.edit()?.putString(FOLLOW_UPS, encodeArray(next, ::encodeResolution))?.commit() == false) return false
        mutableFollowUps.value = next
        claims.remove(token)
        return true
    }

    /** UI consumer: call only after the matching conversation and its submit callback are ready. */
    fun submitPending(conversationKey: String, onSubmit: (ApprovalResolution) -> Boolean): Int {
        var count = 0
        for (pending in pendingFor(conversationKey)) {
            val claimed = claimExact(pending) ?: continue
            try {
                if (!onSubmit(claimed)) break
                if (!acknowledge(claimed.token)) break
                count += 1
            } finally {
                release(claimed.token)
            }
        }
        return count
    }

    private fun encode(pending: PendingApproval): JSONObject = JSONObject()
        .put("token", pending.token)
        .put("source", pending.source)
        .put("risk", pending.risk.name)
        .put("title", pending.title)
        .put("body", pending.body)
        .put("memoryReviewCandidates", JSONArray(pending.memoryReviewCandidates.take(3).map { it.take(300) }))
        .put("quiet", pending.quiet)
        .put("subject", pending.subject)
        .put("repeatCount", pending.repeatCount)
        .put("conversationKey", pending.conversationKey)
        .put("runId", pending.runId.orEmpty())
        .put("allowMultiple", pending.allowMultiple)
        .put("createdAt", pending.createdAt)
        .put("fields", JSONArray().apply {
            pending.fields.forEach { put(JSONObject().put("label", it.label).put("value", it.value)) }
        })
        .put("options", JSONArray().apply {
            pending.options.forEach {
                put(JSONObject().put("id", it.id).put("label", it.label).put("description", it.description))
            }
        })

    private fun decode(json: JSONObject): PendingApproval? = runCatching {
        val token = json.optString("token").takeIf(String::isNotBlank) ?: return null
        val fields = json.optJSONArray("fields") ?: JSONArray()
        val options = json.optJSONArray("options") ?: JSONArray()
        PendingApproval(
            token = token,
            source = json.optString("source").ifBlank { PendingApproval.SOURCE_GUARD },
            risk = runCatching { ConfirmRisk.valueOf(json.optString("risk")) }.getOrDefault(ConfirmRisk.IRREVERSIBLE),
            title = json.optString("title"),
            fields = (0 until fields.length()).mapNotNull { i ->
                fields.optJSONObject(i)?.let { ConfirmField(it.optString("label"), it.optString("value")) }
            },
            quiet = json.optBoolean("quiet"),
            subject = json.optString("subject"),
            repeatCount = json.optInt("repeatCount", 1),
            body = json.optString("body"),
            memoryReviewCandidates = json.optJSONArray("memoryReviewCandidates")?.let { candidates ->
                (0 until minOf(candidates.length(), 3)).mapNotNull { i ->
                    candidates.optString(i).takeIf(String::isNotBlank)?.take(300)
                }
            }.orEmpty(),
            options = (0 until options.length()).mapNotNull { i ->
                options.optJSONObject(i)?.let { PromptOption(it.optString("id"), it.optString("label"), it.optString("description")) }
            },
            allowMultiple = json.optBoolean("allowMultiple"),
            conversationKey = json.optString("conversationKey"),
            runId = json.optString("runId").takeIf(String::isNotBlank),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        )
    }.getOrNull()

    private fun encodeResolution(answer: ApprovalResolution): JSONObject = JSONObject()
        .put("token", answer.token)
        .put("conversationKey", answer.conversationKey)
        .put("runId", answer.runId.orEmpty())
        .put("followUp", answer.followUp)
        .put("kind", answer.kind.name)

    private fun decodeResolution(json: JSONObject): ApprovalResolution? = runCatching {
        val token = json.optString("token").takeIf(String::isNotBlank) ?: return null
        val text = json.optString("followUp").takeIf(String::isNotBlank) ?: return null
        ApprovalResolution(
            token, json.optString("conversationKey"), json.optString("runId").takeIf(String::isNotBlank), text,
            runCatching { ApprovalResolution.Kind.valueOf(json.optString("kind")) }.getOrDefault(ApprovalResolution.Kind.ANSWER),
        )
    }.getOrNull()

    private fun <T> decodeArray(raw: String?, decode: (JSONObject) -> T?): List<T> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(decode) }
    }.getOrDefault(emptyList())

    private fun <T> encodeArray(values: List<T>, encode: (T) -> JSONObject): String =
        JSONArray().apply { values.forEach { put(encode(it)) } }.toString()
}
