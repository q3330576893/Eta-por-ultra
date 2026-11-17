package io.github.mangi.eta.agent.voice

import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import io.github.mangi.eta.core.AssistantContextFlags

class EtaVoiceInteractionService : VoiceInteractionService() {
    private var receiverRegistered = false
    private val refreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == ACTION_REFRESH_CONTEXT) showEtaSession(refresh = true)
        }
    }
    override fun onReady() {
        super.onReady()
        activeService = this
        if (!receiverRegistered) {
            registerReceiver(refreshReceiver, android.content.IntentFilter(ACTION_REFRESH_CONTEXT), android.content.Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        if (Build.VERSION.SDK_INT >= 37) {
            setInvocationEffectEnabled(true)
        }
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        if (receiverRegistered) { unregisterReceiver(refreshReceiver); receiverRegistered = false }
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        // The VoiceInteractionSession owns the transparent overlay. Starting an Activity here
        // briefly brings Eta to the foreground before the capsule appears.
        showEtaSession()
    }

    private fun showEtaSession(refresh: Boolean = false) {
        showSession(Bundle().apply { putBoolean(EXTRA_REFRESH_CONTEXT, refresh) }, AssistantContextFlags.forSdk(Build.VERSION.SDK_INT))
    }

    companion object {
        internal const val EXTRA_REFRESH_CONTEXT = "eta_refresh_screen_context"
        private const val ACTION_REFRESH_CONTEXT = "io.github.mangi.eta.REFRESH_SCREEN_CONTEXT"

        internal fun refreshContext(context: android.content.Context) {
            context.sendBroadcast(android.content.Intent(ACTION_REFRESH_CONTEXT).setPackage(context.packageName))
        }

        @Volatile
        private var activeService: EtaVoiceInteractionService? = null

        internal fun requestSession(): Boolean {
            val service = activeService ?: return false
            service.showEtaSession()
            return true
        }
    }
}
