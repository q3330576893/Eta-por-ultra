package io.github.mangi.eta.hook.hyperos

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.hook.system.AssistantManager

internal object HyperOsPowerHooks {
    fun install(module: XposedModule, logger: ModuleLogger, loader: ClassLoader): HookInstallation {
        val hooks = HookRegistrar(module, logger, "HyperOsPower")
        return hooks.install {
            val type = HookSupport.findClassOrNull(loader, "com.miui.server.input.util.ShortCutActionsUtils")
            if (type == null) {
                skipped("hyperos.power-shortcut", "ShortCutActionsUtils", "当前系统未提供 HyperOS 快捷动作分发器")
                return@install
            }
            val contextField = HookSupport.findField(type, "mContext")
            if (contextField == null || !Context::class.java.isAssignableFrom(contextField.type)) {
                missing("hyperos.power-shortcut", "ShortCutActionsUtils.mContext", "HyperOS: 快捷动作分发器缺少 Context")
                return@install
            }
            val parameters = arrayOf(String::class.java, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType!!)
            for (signature in listOf(parameters, parameters + String::class.java)) {
                val method = HookSupport.findMethod(type, "triggerFunction", *signature)
                val id = "hyperos.power-shortcut-${signature.size}"
                if (method == null || method.returnType != Boolean::class.javaPrimitiveType) {
                    missing(id, "ShortCutActionsUtils.triggerFunction/${signature.size}", "HyperOS: 快捷动作分发签名 ${signature.size} 不匹配")
                    continue
                }
                intercept(id, method, "ShortCutActionsUtils.triggerFunction/${signature.size}") { chain ->
                    // 同时匹配功能与来源，不能把关机菜单、SOS 或其他快捷动作改成助理。
                    if (!HyperOsPowerPolicy.isAssistantShortcut(chain.getArg(0) as? String, chain.getArg(1) as? String)) {
                        return@intercept chain.proceed()
                    }
                    val target = Prefs.powerAssistantTarget()
                    val context = contextField.get(chain.getThisObject()) as? Context
                    if (target == PowerAssistantTarget.OEM || context == null) return@intercept chain.proceed()
                    if (AssistantManager.showAssistantSession(context, target, hooks.logger, "HyperOsPower", logFailures = true)) {
                        return@intercept true
                    }
                    // 重启后 secure assistant / voice_interaction_service 可能尚未落盘，
                    // 首次长按会失败。这里入队一次有界后台修复，让下一次长按能够成功，
                    // 同时保持本次不改变前台应用（不启动任何 Activity）。
                    if (target == PowerAssistantTarget.ETA) {
                        scheduleRecovery(context, hooks.logger, "HyperOsPower")
                    }
                    chain.proceed()
                }
            }
        }
    }

    /** 每次长按最多入队一次修复，避免系统连续分发时堆积任务。 */
    private fun scheduleRecovery(context: Context, logger: ModuleLogger, source: String) {
        if (!Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG)) return
        if (!recoveryScheduled.compareAndSet(false, true)) return
        val handler = Handler(Looper.getMainLooper())
        val scheduled = AssistantManager.scheduleAssistantRecovery(
            context = context,
            logger = logger,
            handler = handler,
            forceRefresh = true,
        )
        if (scheduled) {
            logger.debug { "$source: 默认助理绑定尚未就绪，已入队一次后台修复" }
            handler.postDelayed({ recoveryScheduled.set(false) }, RECOVERY_RESET_MS)
        } else {
            recoveryScheduled.set(false)
            logger.warnThrottled("${source}_recovery_enqueue_failed") {
                "$source: 默认助理后台修复无法入队"
            }
        }
    }

    private val recoveryScheduled = AtomicBoolean(false)

    private const val RECOVERY_RESET_MS = 20_000L
}
