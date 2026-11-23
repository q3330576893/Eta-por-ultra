package io.github.mangi.eta

import io.github.mangi.eta.agent.voice.SpeechOssUpload
import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.display.VirtualScreenFloatWindow
import io.github.mangi.eta.agent.display.VscrDiag
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.CrashRecorder
import io.github.mangi.eta.agent.automation.AutoBackupAlarm
import io.github.mangi.eta.data.repository.AutoBackupRunner
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.agent.github.GitHubAccountManager
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        // 全量包还原：必须在 Room / DataStore 打开之前把暂存内容覆盖回 dataDir，
        // 否则进程会先读到旧库，还原就等于白做。只在主进程执行，hook 进程不碰数据。
        if (Application.getProcessName() == packageName) {
            runCatching {
                io.github.mangi.eta.data.repository.EtaFullBackupArchive.applyPendingRestore(this)
            }
        }
        Prefs.initLocal(this)
        // 前台判定：审批只在用户不在 App 里时才发通知，在场时会话卡已经在他眼前。
        runCatching { io.github.mangi.eta.core.AppForeground.install(this) }
        // 测试版同意状态：任何进程启动都要能读到，且要在可能拉起服务/任务之前。
        io.github.mangi.eta.data.repository.TestConsentRepository.init(this)
        // 请求快照：只记 system 全文与工具描述，写盘在独立线程，失败不影响对话。
        runCatching { io.github.mangi.eta.agent.model.AgentRequestSnapshot.init(this) }
        // 崩溃留证：任何进程一启动就装好，写入失败也不影响原有崩溃流程。
        runCatching { CrashRecorder.install(this) }
        // 自留日志：任何进程启动就落盘，诊断包不再依赖 logcat 的 2 分钟缓冲。
        runCatching { io.github.mangi.eta.core.EtaTrace.init(this) }
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        // 文本库体量：升级后第一次起动把超上限的历史字段按写入侧规则保尾截断（只跑一次）。
        applicationScope.launch {
            runCatching { io.github.mangi.eta.data.db.TextStoreMaintenance.runOnceIfNeeded(this@EtaApp) }
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        applicationScope.launch { TerminalRuntime.backgroundJobs(this@EtaApp).resume() }
        SettingsDataStore.init(this)
        runCatching { VscrDiag.init(this) }
        VirtualScreenFloatWindow.init(this)
        // 待答/待审批卡片：上一次被杀掉时没答完的那条要能捡回来，
        // 否则模型在等、用户回来却什么都看不到。
        runCatching { io.github.mangi.eta.agent.display.dashboard.AgentConfirmGuard.init(this) }
        // 预测返回先按默认关闭应用，避免在 onCreate 主线程阻塞读取 DataStore；
        // 真实设置随后在后台读取并修正，冷启动不再等待磁盘。
        PredictiveBackController.apply(applicationInfo, false)
        applicationScope.launch(Dispatchers.IO) {
            val enabled = runCatching {
                AppearanceSettingsRepository.settings().predictiveBackEnabled
            }.getOrDefault(false)
            if (enabled) PredictiveBackController.apply(applicationInfo, true)
        }
        AgentMemoryRepository.init(this)
        ProviderRepository.init(this)
        McpServerRepository.init(this)
        GitHubAccountManager.init(this)
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                // OAuth 会话构造挪到这里：首帧不依赖，首次登录/取令牌前通常已预热完成。
                ProviderRepository.warmUp()
                io.github.mangi.eta.agent.automation.AgentTaskScheduler.recoverAfterUpgrade(this@EtaApp)
                io.github.mangi.eta.agent.automation.AgentTaskScheduler.enqueueDue(this@EtaApp)
                io.github.mangi.eta.agent.automation.AgentTaskScheduler.refresh(this@EtaApp)
                // 自动备份：先排下一次唤醒（闹钟可能已被系统清掉），再补一次到期检查。
                AutoBackupAlarm.refresh(this@EtaApp)
                AutoBackupRunner.maybeRun(this@EtaApp)
                // 诊断包每周自动导出一次，直接落到「诊断包备份」。
                io.github.mangi.eta.core.DiagnosticsAutoExport.refresh(this@EtaApp)
            }.onFailure { AndroidAgentLogger.warn("Automation recovery unavailable") }
        }
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            try {
                SpeechOssUpload(this@EtaApp).retryPending(this@EtaApp)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn("Eta speech cleanup unavailable: type=${error.javaClass.simpleName}")
            }
        }
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileVendorAssistantPreferences(service)
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
