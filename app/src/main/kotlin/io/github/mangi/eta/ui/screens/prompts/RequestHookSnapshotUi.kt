package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import org.json.JSONArray
import org.json.JSONObject

/** Snapshot-only metadata: it describes local request preparation, without grading a remote reply. */
internal data class RequestHookSnapshotUi(
    val active: Boolean,
    val channel: String,
    val revision: String,
    val estimatedTokens: Int,
) {
    companion object {
        fun from(json: JSONObject?): RequestHookSnapshotUi? {
            val hook = json?.optJSONObject("request_hook") ?: return null
            val channel = hook.optString("channel").takeIf { it in setOf("SYSTEM", "USER", "BOTH") } ?: "—"
            val revision = hook.optString("revision").takeIf { it.matches(Regex("[0-9a-f]{12}")) } ?: "—"
            return RequestHookSnapshotUi(
                active = hook.optBoolean("active"),
                channel = channel,
                revision = revision,
                estimatedTokens = hook.optInt("estimated_tokens").coerceAtLeast(0),
            )
        }
    }
}

@Composable
internal fun requestHookSnapshotSummary(hook: RequestHookSnapshotUi): String =
    if (hook.active) {
        stringResource(
            R.string.prompt_snapshot_request_hook_active,
            hook.channel,
            hook.revision,
            hook.estimatedTokens,
        )
    } else {
        stringResource(R.string.prompt_snapshot_request_hook_inactive)
    }

/** Copies contain only recorded ordinary system text, including when opening older snapshot files. */
internal fun recordedSystemText(system: JSONArray?): String = buildString {
    for (index in 0 until (system?.length() ?: 0)) {
        val segment = system?.optJSONObject(index) ?: continue
        if (segment.optBoolean("request_hook")) continue
        append(segment.optString("text").ifBlank { segment.optString("content") })
        append("\n\n")
    }
}
