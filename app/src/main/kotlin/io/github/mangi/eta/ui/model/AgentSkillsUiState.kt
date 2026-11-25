package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentSkillsUiState(
    val skills: List<SkillItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val busySkillId: String? = null,
    val replacement: SkillReplacementUi? = null,
    val notice: SkillNoticeUi? = null,
    /** 各 Skill 的本地评分汇总，仅用于展示与本地排序。 */
    val ratings: Map<String, SkillRatingUi> = emptyMap(),
    /**
     * 评分提交动作的处理入口（3.3 评分反馈闭环）。
     *
     * 由 AgentSkillsStore 在构造状态时注入，Screen 直接调用即可把反馈落到本地 Room，
     * 无需改动全局 AgentSkillsAction 分发链。
     */
    val onSubmitFeedback: ((SkillFeedbackAction) -> Unit)? = null,
    /** GitHub 技能浏览/安装面板状态；null 表示面板关闭。 */
    val github: SkillGithubUiState? = null,
)

/**
 * GitHub 技能浏览面板。
 *
 * 后端早已具备「列出仓库可用 Skill → 固定 commit → 下载校验 → 安装」全链路，
 * 这里只是把它的四个阶段暴露成可见状态。
 */
@Immutable
data class SkillGithubUiState(
    val repository: String = "",
    val phase: Phase = Phase.Idle,
    val candidates: List<SkillGithubCandidateUi> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val resolvedRef: String? = null,
    val error: String? = null,
    val message: String? = null,
) {
    enum class Phase { Idle, Inspecting, Inspected, Installing, Installed }
}

/** 仓库中可安装的一个 Skill 候选。 */
@Immutable
data class SkillGithubCandidateUi(
    val name: String,
    val path: String,
)

/** 单个 Skill 的评分展示数据。 */
@Immutable
data class SkillRatingUi(
    val averageRating: Double,
    val feedbackCount: Int,
)

/** 评分提交动作载荷：星级 1..5 + 可选评论（限 500 字符、Store 内脱敏）。 */
@Immutable
data class SkillFeedbackAction(
    val skillId: String,
    val rating: Int,
    val comment: String,
)

/** 评论最大字符数（脱敏后）。 */
internal const val SKILL_FEEDBACK_COMMENT_MAX = 500

/**
 * 评论脱敏（纯函数、可 JVM 单测）：折叠控制字符为空格、去除不可打印字符、
 * 限长 500 字。只产出落库文本，绝不记录原文日志——与 AgentLogger
 * 「只记类型/计数、不记敏感正文」的风格一致。
 */
internal fun sanitizeSkillFeedbackComment(raw: String): String = raw
    .map { ch -> if (ch == '\n' || ch == '\r' || ch == '\t') ' ' else ch }
    .filter { ch -> ch >= ' ' && ch != '\u007F' }
    .joinToString("")
    .trim()
    .take(SKILL_FEEDBACK_COMMENT_MAX)

@Immutable
data class SkillReplacementUi(
    val id: String,
    val name: String,
)

@Immutable
data class SkillNoticeUi(
    val id: Long,
    val title: String,
    val message: String,
    val isError: Boolean,
)

@Immutable
data class SkillItemUi(
    val id: String,
    val name: String,
    val description: String,
    val source: String,
    val enabled: Boolean,
    val installed: Boolean,
    val capabilities: List<String>,
)

internal val SkillItemUi.canDeleteUserSkill: Boolean
    get() = installed && source == "user"
