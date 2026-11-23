package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.auth.EncryptedProviderCredentialStorage
import io.github.mangi.eta.agent.auth.LiveProviderOAuthClient
import io.github.mangi.eta.agent.auth.ProviderAccountSummary
import io.github.mangi.eta.agent.auth.ProviderAuthException
import io.github.mangi.eta.agent.auth.ProviderCredentialSessions
import io.github.mangi.eta.agent.auth.ProviderCredentialBinding
import io.github.mangi.eta.agent.auth.ProviderLoginPrompt
import io.github.mangi.eta.agent.runtime.AgentRunController

/** 仅由 Eta 主进程初始化和访问；调用方在 I/O 线程执行，不将返回的令牌写入配置。 */
internal object ProviderCredentialRepository {
    @Volatile private var applicationContext: Context? = null

    @Volatile private var sessions: ProviderCredentialSessions? = null

    /** 会话构造可能被后台预热与首个调用方同时触发，用独立锁串行，避免双重构造。 */
    private val sessionLock = Any()

    /**
     * 启动时只记上下文，同步完成、永不「未就绪」。
     *
     * 会话对象里带 OkHttp 客户端与 OIDC 验签器，构造一次要几十毫秒；它们只服务于
     * OAuth 登录与取令牌，首帧界面不读。所以这里不再顺手把会话建出来，真正的构造
     * 推迟到首次使用或 [warmUp] 的后台预热。
     */
    fun init(context: Context) {
        if (applicationContext == null) {
            synchronized(this) {
                if (applicationContext == null) applicationContext = context.applicationContext
            }
        }
    }

    /** 后台预热：在 IO 线程把会话建好，主线程后续调用只剩一次字段读取。 */
    fun warmUp() {
        runCatching { sessions() }
    }

    fun account(providerId: String, authMode: String): ProviderAccountSummary? =
        sessions().account(providerId, authMode)

    fun binding(providerId: String, authMode: String): ProviderCredentialBinding = sessions().binding(providerId, authMode)

    fun isCurrent(binding: ProviderCredentialBinding): Boolean = sessions().isCurrent(binding)

    fun login(providerId: String, authMode: String, controller: AgentRunController,
        onPrompt: (ProviderLoginPrompt) -> Unit): ProviderAccountSummary =
        sessions().login(providerId, authMode, controller, onPrompt)

    fun accessToken(providerId: String, authMode: String, controller: AgentRunController,
        rejectedAccessToken: String? = null): String =
        sessions().accessToken(providerId, authMode, controller, rejectedAccessToken)

    fun disconnect(providerId: String, authMode: String, controller: AgentRunController): Boolean? =
        sessions().disconnect(providerId, authMode, controller)

    fun forget(providerId: String) = sessions().forget(providerId)

    private fun sessions(): ProviderCredentialSessions {
        sessions?.let { return it }
        // 上下文缺失才算「未就绪」：init 之后任何调用都能拿到可用会话。
        val context = applicationContext
            ?: throw ProviderAuthException("oauth_not_initialized", "登录服务尚未就绪，请重新打开 Eta")
        synchronized(sessionLock) {
            sessions?.let { return it }
            return ProviderCredentialSessions(
                EncryptedProviderCredentialStorage(context),
                LiveProviderOAuthClient(),
            ).also { sessions = it }
        }
    }
}
