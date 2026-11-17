package io.github.mangi.eta.hook.system

import android.content.Intent
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.core.ModuleConfig

internal data class AssistantBinding(
    val target: PowerAssistantTarget,
    val packageName: String,
    val componentName: String,
    val displayName: String,
)

internal fun assistantBindingFor(target: PowerAssistantTarget): AssistantBinding? = when (target) {
    PowerAssistantTarget.OEM -> null
    PowerAssistantTarget.GEMINI -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.GOOGLE_PACKAGE,
        componentName = ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
        displayName = "Gemini",
    )
    PowerAssistantTarget.ETA -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.ETA_PACKAGE,
        componentName = ModuleConfig.ETA_VOICE_INTERACTION_COMPONENT,
        displayName = "Eta",
    )
}

// Eta owns a session overlay; an Activity fallback would replace the foreground app.
internal fun assistantActivityFallbackActions(target: PowerAssistantTarget): List<String> =
    if (target == PowerAssistantTarget.GEMINI) {
        listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)
    } else {
        emptyList()
    }

internal fun tryAssistantActivityFallback(
    target: PowerAssistantTarget,
    startActivity: (String) -> Boolean,
): Boolean = assistantActivityFallbackActions(target).any(startActivity)

/** One background repair followed by bounded session attempts, stopping at the first success. */
internal class AssistantSessionRecovery {
    @Volatile
    private var pending = false

    val isPending: Boolean
        get() = pending

    fun schedule(
        reconfigure: () -> Unit,
        postDelayed: (Runnable, Long) -> Boolean,
        isCurrent: () -> Boolean,
        showSession: () -> Boolean,
        onShown: () -> Unit,
    ): Boolean {
        synchronized(this) {
            if (pending) return true
            pending = true
        }
        if (!isCurrent()) {
            finish()
            return false
        }
        reconfigure()
        return scheduleAttempt(0, postDelayed, isCurrent, showSession, onShown)
    }

    private fun scheduleAttempt(
        index: Int,
        postDelayed: (Runnable, Long) -> Boolean,
        isCurrent: () -> Boolean,
        showSession: () -> Boolean,
        onShown: () -> Unit,
    ): Boolean {
        val scheduled = postDelayed(Runnable {
            if (!isCurrent()) {
                finish()
            } else if (showSession()) {
                finish()
                onShown()
            } else if (index + 1 < RETRY_DELAYS_MS.size) {
                scheduleAttempt(index + 1, postDelayed, isCurrent, showSession, onShown)
            } else {
                finish()
            }
        }, RETRY_DELAYS_MS[index])
        if (!scheduled) finish()
        return scheduled
    }

    private fun finish() {
        synchronized(this) { pending = false }
    }

    private companion object {
        // RoleController may take 15 seconds; the last attempt also follows its 17-second watchdog.
        val RETRY_DELAYS_MS = longArrayOf(250L, 750L, 2_000L, 5_000L, 10_000L)
    }
}

internal fun shouldConfigureAssistant(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
): Boolean = autoConfigEnabled && target != PowerAssistantTarget.OEM

internal enum class AssistantSelectionAction {
    NONE,
    CONFIGURE_MANAGED,
    RESTORE_OEM,
}

internal fun assistantSelectionAction(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
): AssistantSelectionAction = when {
    target == PowerAssistantTarget.OEM -> AssistantSelectionAction.RESTORE_OEM
    shouldConfigureAssistant(autoConfigEnabled, target) ->
        AssistantSelectionAction.CONFIGURE_MANAGED
    else -> AssistantSelectionAction.NONE
}

internal fun isAssistantConfigurationCurrent(
    autoConfigEnabled: Boolean,
    expectedTarget: PowerAssistantTarget,
    currentTarget: PowerAssistantTarget,
): Boolean = shouldConfigureAssistant(autoConfigEnabled, currentTarget) &&
    expectedTarget == currentTarget
