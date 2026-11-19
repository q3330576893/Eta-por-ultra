package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.prompt.SamplingInjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class FanoutModelTarget(
    val key: String,
    val modelId: String,
    val displayName: String,
    val config: AgentModelClient.ModelConfig,
)
internal data class FanoutRequest(
    val prompt: String,
    val targets: List<FanoutModelTarget>,
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
    val images: List<AgentModelClient.ModelImage> = emptyList(),
    /** Construct once: every provider receives an independent copy of these exact messages. */
    val messagesJson: String? = null,
)
internal enum class FanoutBranchStatus { Pending, Running, Succeeded, Failed, Skipped }
internal data class FanoutBranchResult(
    val targetKey: String,
    val status: FanoutBranchStatus,
    val content: String = "",
    val reasoningContent: String = "",
    val usage: AgentTokenUsage? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
) { val succeeded: Boolean get() = status == FanoutBranchStatus.Succeeded }

internal class AgentMultiModelFanout(private val maxModels: Int = DEFAULT_MAX_MODELS) {
    companion object { const val DEFAULT_MAX_MODELS = 3 }
    suspend fun fanout(request: FanoutRequest, runBranch: (FanoutModelTarget) -> FanoutBranchResult): List<FanoutBranchResult> = withContext(Dispatchers.IO) {
        val targets = request.targets.distinctBy { it.key }
        val results = supervisorScope {
            targets.take(maxModels.coerceIn(1, 3)).map { target -> async {
                try { runBranch(target) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { FanoutBranchResult(target.key, FanoutBranchStatus.Failed, error = failure.message) }
            } }.awaitAll()
        }
        results + targets.drop(maxModels.coerceIn(1, 3)).map {
            FanoutBranchResult(it.key, FanoutBranchStatus.Skipped, error = "超出并排对比上限（最多 3 个模型）")
        }
    }

    /** Exactly one provider call per branch; there is deliberately no runtime/tool executor. */
    fun runReadOnly(
        target: FanoutModelTarget,
        request: FanoutRequest,
        controller: AgentRunController,
        providerClient: AgentProviderClient? = null,
        onText: (String) -> Unit = {},
    ): FanoutBranchResult {
        val config = target.config.copy(terminalTools = false, browserTools = false,
            deviceDirectTools = false, deviceSensitiveReadTools = false, deviceSensitiveActionTools = false,
            subagentTools = false, hostedWebSearchEnabled = false, supportsSearchSuggestions = false,
            extraBodyJson = "", customBody = emptyList())
        val provider = providerClient ?: ProviderClientFactory.getClient(config)
        val streamed = StringBuilder()
        val warnings = SamplingInjection.project(config.sessionSamplingOverride,
            provider.capabilities.endpoint, config.providerType, config.authMode).skipped
        return try {
            controller.throwIfCancelled()
            require(request.images.isEmpty() || provider.capabilities.imageInput) { "Image input is unsupported by this provider" }
            val messages = request.messagesJson?.let(::JSONArray) ?: JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", config.systemPrompt))
                request.history.filter { it.role == "user" || it.role == "assistant" }.forEach {
                    put(JSONObject().put("role", it.role).put("content", it.content))
                }
                put(AgentConversationCodec.userMessage(request.prompt, request.images))
            }
            val response = provider.complete(ProviderRequest(config, messages, JSONArray(),
                purpose = ProviderRequestPurpose.COMPARISON), controller) { event ->
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TEXT) {
                    streamed.append(event.delta)
                    onText(streamed.toString())
                }
            }
            controller.throwIfCancelled()
            val content = response.assistantMessage.optString("content").ifBlank { streamed.toString() }
            val failure = when {
                response.stopReason == AssistantStopReason.TOOL_USE ||
                    (response.assistantMessage.optJSONArray("tool_calls")?.length() ?: 0) > 0 -> "只读对比收到工具请求，未执行任何工具"
                response.stopReason == AssistantStopReason.CONTENT_FILTER -> "模型服务限制了此回答"
                response.stopReason == AssistantStopReason.OUTPUT_LIMIT -> "达到输出上限，已保留部分回答"
                content.isBlank() -> "模型未返回可用的回答"
                else -> null
            }
            FanoutBranchResult(target.key, if (failure == null) FanoutBranchStatus.Succeeded else FanoutBranchStatus.Failed,
                content = content, error = failure, warnings = warnings)
        } catch (failure: Exception) {
            FanoutBranchResult(target.key, FanoutBranchStatus.Failed, content = streamed.toString(),
                error = if (controller.isCancelled) "Cancelled" else failure.message ?: failure.javaClass.simpleName,
                warnings = warnings)
        } finally { controller.cancel() }
    }

    /** Compatibility for existing callers; supplied executor is never invoked. */
    @Suppress("UNUSED_PARAMETER")
    fun runWithDefaultClient(target: FanoutModelTarget, request: FanoutRequest,
        toolExecutor: AgentModelClient.ToolExecutor): FanoutBranchResult =
        runReadOnly(target, request, AgentRunController())
}

internal fun totalFanoutTokens(results: List<FanoutBranchResult>): Int? = results.mapNotNull {
    it.usage?.let { usage -> listOfNotNull(usage.inputTokens, usage.outputTokens).sum().takeIf { value -> value > 0 } }
}.takeIf { it.isNotEmpty() }?.sum()
