package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "skill_registry")
internal data class SkillRegistryEntity(
    @PrimaryKey @ColumnInfo(name = "skill_id") val skillId: String,
    val enabled: Boolean,
    val source: String,
    @ColumnInfo(name = "install_state") val installState: String,
)

/**
 * 本地 Skill 评分反馈（3.3 评分反馈闭环）。
 *
 * 只存本地 Room，不联网、不回传作者；`comment` 为已脱敏并限长（500 字符）后的纯文本，
 * 仅用于本机展示，绝不写入日志正文。`rating` 取值范围 1..5，由写入方校验。
 */
@Entity(tableName = "skill_feedback", indices = [Index(value = ["skill_id"])])
internal data class SkillFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "skill_id") val skillId: String,
    val rating: Int,
    val comment: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * 单个 Skill 的评分汇总，供列表展示与本地排序使用。
 *
 * 作为 @Query 聚合结果的投影，列名与 DAO SQL 中的别名（skillId / averageRating /
 * feedbackCount）逐字对应，因此这里不声明 @ColumnInfo。
 */
internal data class SkillRatingSummary(
    val skillId: String,
    val averageRating: Double,
    val feedbackCount: Int,
)
