package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Explicit, editable compatibility template; never applied to an unrelated provider automatically. */
internal object XingchiCompatibility {
    const val HOST = "api.xingchiapi.com"
    // Preserve the existing default. All four exact ETA-attributed values were verified on 2026-11-20.
    const val USER_AGENT = "opencode/1.19.6 (EtaUltra compatibility)"

    enum class ClientPreset(val displayName: String, val userAgent: String) {
        OPENCODE("OpenCode", USER_AGENT),
        WORKBUDDY("WorkBuddy", "WorkBuddy/1.0.0 (EtaUltra compatibility)"),
        CODEX("Codex", "codex_cli_rs/0.114.0 (EtaUltra compatibility)"),
        DEEPSEEK_HARNESS("DeepSeek Harness", "deepseek-harness/1.0.0 (EtaUltra compatibility)"),
    }

    /** Derive the selection from saved headers, never from a separate global preference. */
    fun selectedPreset(draft: ProviderConfigDraft): ClientPreset? {
        val userAgent = draft.headers.singleOrNull { isUserAgent(it) }?.header?.value ?: return null
        return ClientPreset.entries.singleOrNull { it.userAgent == userAgent }
    }

    /** An explicit selection changes only User-Agent; URL and protocol remain independently editable. */
    fun select(draft: ProviderConfigDraft, preset: ClientPreset): ProviderConfigDraft {
        requireApplicable(draft)
        val existing = draft.headers.firstOrNull { isUserAgent(it) }
        val replacement = existing?.copy(header = CustomHeader("User-Agent", preset.userAgent))
            ?: ProviderHeaderDraft(header = CustomHeader("User-Agent", preset.userAgent))
        return draft.copy(headers = draft.headers.filterNot { isUserAgent(it) } + replacement)
    }

    private fun isUserAgent(draft: ProviderHeaderDraft): Boolean =
        draft.header.name.trim().equals("User-Agent", ignoreCase = true)

    private fun requireApplicable(draft: ProviderConfigDraft) {
        require(draft.authMode == ProviderAuthModes.API_KEY && matches(draft.baseUrl)) {
            "兼容模板仅适用于星驰 HTTPS API Key 连接"
        }
    }

    fun available(provider: ProviderSetting, draft: ProviderConfigDraft): Boolean =
        (provider is OpenAiCompatibleProviderSetting || provider is CustomProviderSetting) &&
            draft.authMode == ProviderAuthModes.API_KEY && matches(draft.baseUrl)

    private fun matches(baseUrl: String): Boolean {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host == HOST && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null
    }

    fun apply(draft: ProviderConfigDraft): ProviderConfigDraft {
        requireApplicable(draft)
        val url = requireNotNull(draft.baseUrl.trim().toHttpUrlOrNull())
        val baseUrl = if (url.encodedPath.trim('/').isEmpty() && url.query == null && url.fragment == null) {
            url.newBuilder().encodedPath("/v1").build().toString().trimEnd('/')
        } else draft.baseUrl
        val hasUserAgent = draft.headers.any {
            it.header.name.trim().equals("User-Agent", ignoreCase = true) && it.header.value.isNotBlank()
        }
        val headers = if (hasUserAgent) draft.headers else draft.headers.filterNot {
            it.header.name.trim().equals("User-Agent", ignoreCase = true)
        } + ProviderHeaderDraft(header = CustomHeader("User-Agent", USER_AGENT))
        return draft.copy(baseUrl = baseUrl, endpointMode = OpenAiEndpointMode.RESPONSES, headers = headers)
    }
}
