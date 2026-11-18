package io.github.mangi.eta.agent.voice

import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import io.github.mangi.eta.core.AssistantContextFlags

class EtaVoiceInteractionService : VoiceInteractionService() {
    private var receiverRegistered = false
    private val refreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != ACTION_REFRESH_CONTEXT) return
            val permit = intent.getStringExtra(EXTRA_CAPTURE_PERMIT)
            if (!EtaAssistantScreenContexts.isRefreshPending(permit)) return
            EtaVoiceInteractionSession.retireForRefresh()
            showEtaSession(permit)
        }
    }

    override fun onReady() {
        super.onReady()
        activeService = this
        setDisabledShowContext(AssistantContextFlags.disabledContext)
        if (!receiverRegistered) {
            registerReceiver(refreshReceiver, android.content.IntentFilter(ACTION_REFRESH_CONTEXT), android.content.Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        if (Build.VERSION.SDK_INT >= 37) setInvocationEffectEnabled(true)
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        EtaAssistantScreenContexts.setEnabled(false)
        if (receiverRegistered) { unregisterReceiver(refreshReceiver); receiverRegistered = false }
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() = showEtaSession()

    private fun showEtaSession(refreshPermit: String? = null) {
        val capture = EtaAssistantScreenContexts.isRefreshPending(refreshPermit)
        if (!capture) EtaAssistantScreenContexts.setEnabled(false)
        setDisabledShowContext(if (capture) 0 else AssistantContextFlags.disabledContext)
        showSession(Bundle().apply {
            putBoolean(EXTRA_REFRESH_CONTEXT, capture)
            if (capture) putString(EXTRA_CAPTURE_PERMIT, refreshPermit)
        }, AssistantContextFlags.forSdk(Build.VERSION.SDK_INT, capture))
    }

    companion object {
        internal const val EXTRA_REFRESH_CONTEXT = "eta_refresh_screen_context"
        internal const val EXTRA_CAPTURE_PERMIT = "eta_capture_permit"
        private const val ACTION_REFRESH_CONTEXT = "io.github.mangi.eta.REFRESH_SCREEN_CONTEXT"
        @Volatile private var activeService: EtaVoiceInteractionService? = null

        internal fun refreshContext(context: android.content.Context): Boolean {
            if (activeService == null) return false
            val permit = EtaAssistantScreenContexts.requestRefresh() ?: return false
            return try {
                context.sendBroadcast(android.content.Intent(ACTION_REFRESH_CONTEXT).setPackage(context.packageName)
                    .putExtra(EXTRA_CAPTURE_PERMIT, permit))
                true
            } catch (_: Exception) {
                EtaAssistantScreenContexts.cancelRefresh(permit)
                false
            }
        }

        internal fun blockAutomaticContext() {
            activeService?.setDisabledShowContext(AssistantContextFlags.disabledContext)
        }

        internal fun requestSession(): Boolean {
            val service = activeService ?: return false
            service.showEtaSession()
            return true
        }
    }
}
