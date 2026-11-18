package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * 同会话并排对比中的一个目标模型。
 *
 * [key] 必须在本轮内稳定且唯一（一般用 modelId，重复模型由 UI 层去重）；
 * [config] 是已经解析好 key/baseUrl 的可直接发起请求的运行配置。
 */
internal data class FanoutModelTarget(
    val key: String,
    val modelId: String,
    val displayName: String,
    val config: AgentModelClient.ModelConfig,
)

/** 一次「一问多答」的输入：同一个问题 + N 个目标模型。 */
internal data class FanoutRequest(
    val prompt: String,
    val targets: List<FanoutModelTarget>,
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
)

internal enum class FanoutBranchStatus {
    Pending,
    Running,
    Succeeded,
    Failed,
    /** 超出并发上限或参数能力不支持，未真正发起请求。 */
    Skipped,
}

/**
 * 单个模型分支的结果。token/费用提示复用 [usage]，不在此另造计费模型。
 */
internal data class FanoutBranchResult(
    val targetKey: String,
    val status: FanoutBranchStatus,
    val content: String = "",
    val reasoningContent: String = "",
    val usage: AgentTokenUsage? = null,
    val error: String? = null,
    /** 采样覆盖里被该模型跳过的字段说明（能力降级，标注而非静默丢）。 */
    val warnings: List<String> = emptyList(),
) {
    val succeeded: Boolean get() = status == FanoutBranchStatus.Succeeded
}

/**
 * 一问多答编排器：把一个提问并发分发给多个模型，逐分支收集结果。
 *
 * 默认最多 [DEFAULT_MAX_MODELS] 路并发——多路 SSE 同时开着有内存压力，且 token/费用按 N 倍增长；
 * 超出上限的目标直接标 [FanoutBranchStatus.Skipped]，不排队、不静默丢弃。
 * 单个分支失败不影响其它分支（supervisorScope + 各自 try/catch）。
 */
internal class AgentMultiModelFanout(
    private val maxModels: Int = DEFAULT_MAX_MODELS,
) {
    companion object {
        const val DEFAULT_MAX_MODELS = 3
    }

    /**
     * @param runBranch 真正执行一路请求的回调，由调用方注入（内部会走
     *   [AgentModelClient.complete] / [ProviderClientFactory.getClient]）。
     */
    suspend fun fanout(
        request: FanoutRequest,
        runBranch: (FanoutModelTarget) -> FanoutBranchResult,
    ): List<FanoutBranchResult> = withContext(Dispatchers.IO) {
        // UI 层已去重；这里再按 key 兜一次，避免同一模型被请求两遍、费用翻倍。
        val distinct = request.targets.distinctBy { it.key }
        val accepted = distinct.take(maxModels)
        val skipped = distinct.drop(maxModels).map { target ->
            FanoutBranchResult(
                targetKey = target.key,
                status = FanoutBranchStatus.Skipped,
                error = "超出并排对比上限（最多 $maxModels 个模型）",
            )
        }
        val results = supervisorScope {
            accepted.map { target ->
                async {
                    try {
                        runBranch(target)
                    } catch (throwable: Throwable) {
                        FanoutBranchResult(
                            targetKey = target.key,
                            status = FanoutBranchStatus.Failed,
                            error = throwable.message ?: throwable.javaClass.simpleName,
                        )
                    }
                }
            }.awaitAll()
        }
        results + skipped
    }

    /**
     * 走真实调用链的默认分支执行器：ProviderClientFactory 选 client，AgentModelClient.complete 跑一轮。
     * 采样覆盖与 Instruct 模板都在 [FanoutModelTarget.config] 里带着，各 Request 构造自动注入。
     */
    fun runWithDefaultClient(
        target: FanoutModelTarget,
        request: FanoutRequest,
        toolExecutor: AgentModelClient.ToolExecutor,
    ): FanoutBranchResult {
        var usage: AgentTokenUsage? = null
        return try {
            val response = AgentModelClient.complete(
                config = target.config,
                prompt = request.prompt,
                toolExecutor = toolExecutor,
                history = request.history,
                provider = ProviderClientFactory.getClient(target.config),
                runController = AgentRunController(),
                onEvent = { event ->
                    if (event is io.github.mangi.eta.agent.runtime.AgentEvent.UsageReceived) usage = event.usage
                },
            )
            FanoutBranchResult(
                targetKey = target.key,
                status = FanoutBranchStatus.Succeeded,
                content = response.content,
                reasoningContent = response.reasoningContent,
                usage = usage,
            )
        } catch (throwable: Throwable) {
            FanoutBranchResult(
                targetKey = target.key,
                status = FanoutBranchStatus.Failed,
                error = throwable.message ?: throwable.javaClass.simpleName,
                usage = usage,
            )
        }
    }
}

/** 把 fanout 结果投影成「费用提示」文案所用的总量；任何分支有用量就累加。 */
internal fun totalFanoutTokens(results: List<FanoutBranchResult>): Int? {
    val parts = results.mapNotNull { result ->
        result.usage?.let { usage ->
            listOfNotNull(usage.inputTokens, usage.outputTokens).sum().takeIf { it > 0 }
        }
    }
    return parts.takeIf(List<*>::isNotEmpty)?.sum()
}
