package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/**
 * 分支画布上的一个节点。
 *
 * 只带画布需要的字段：分叉归属、分叉时刻、消息数、分叉点消息摘要。
 * 正文与上下文留在会话自己的加载链路里，画布不碰它们。
 */
@Immutable
data class BranchNodeUi(
    val id: String,
    val title: String,
    /** 会话创建时间：分支会话即「分叉那一刻」，画布按它排时间顺序。 */
    val createdAt: Long,
    val parentConversationId: String? = null,
    val parentMessageId: String? = null,
    val messageCount: Int = 0,
    /** 分叉点那条消息的开头一段，用来让人认出「从哪句分出」。 */
    val forkPreview: String = "",
) {
    val isBranch: Boolean get() = parentConversationId != null
}

/**
 * 并排对比中一个模型分支的状态（与 agent.model.FanoutBranchStatus 一一对应，
 * UI 层单独持有，避免 ui.model 反向依赖 agent.model 的运行期类型）。
 */
enum class FanoutBranchStatusUi {
    Pending,
    Running,
    Succeeded,
    Failed,
    Skipped,
}

/**
 * 并排对比画布上的一列：一个模型对同一个提问的回答。
 *
 * 复用分支画布的多列布局——每列挂在一个共同的「提问」节点下，
 * 所以画布只需要认识 [BranchNodeUi] 的父子关系即可把 N 路回答摊开。
 */
@Immutable
data class FanoutBranchColumnUi(
    val id: String,
    val modelId: String,
    val modelName: String,
    val status: FanoutBranchStatusUi,
    val answerPreview: String = "",
    val tokenText: String? = null,
    val errorText: String? = null,
    /** 能力降级提示：哪些采样字段被该模型跳过。 */
    val warnings: List<String> = emptyList(),
)

/**
 * 一次「一问多答」在分支画布上的投影。
 *
 * [root] 是那一条提问节点（所有列共享同一个 parentMessageId），
 * [columns] 是 N 个模型分支。画布按 [BranchNodeUi] 的父子关系把它们画成多列。
 */
@Immutable
data class FanoutGraphUi(
    val root: BranchNodeUi,
    val columns: List<FanoutBranchColumnUi>,
) {
    val active: Boolean get() = columns.isNotEmpty()

    val estimatedTotalTokensText: String?
        get() = columns.mapNotNull { it.tokenText }.takeIf(List<*>::isNotEmpty)?.joinToString(" + ")
}
