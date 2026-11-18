package io.github.mangi.eta.core

import android.service.voice.VoiceInteractionSession

internal object AssistantContextFlags {
    /** Ordinary invocation never requests application content or a screenshot. */
    fun forSdk(sdk: Int, captureRequested: Boolean = false): Int =
        if (captureRequested) {
            VoiceInteractionSession.SHOW_WITH_ASSIST or
                VoiceInteractionSession.SHOW_WITH_SCREENSHOT or
                if (sdk >= 37) VoiceInteractionSession.SHOW_WITH_ASSIST_STRUCTURE_SCREEN_CONTENT else 0
        } else {
            0
        }

    val disabledContext: Int =
        VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
}
