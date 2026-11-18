package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface SkillDao {
    @Query("SELECT * FROM skill_registry ORDER BY skill_id ASC")
    suspend fun registryEntries(): List<SkillRegistryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRegistryEntry(entry: SkillRegistryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegistryEntries(entries: List<SkillRegistryEntity>)

    @Query("DELETE FROM skill_registry")
    suspend fun deleteRegistryEntries()

    @Query("DELETE FROM skill_registry WHERE skill_id = :skillId")
    suspend fun deleteRegistryEntry(skillId: String)

    @Transaction
    suspend fun replaceRegistry(entries: List<SkillRegistryEntity>) {
        deleteRegistryEntries()
        if (entries.isNotEmpty()) {
            insertRegistryEntries(entries)
        }
    }

    // ---- 3.3 Skill 评分反馈闭环：仅本地 Room，不联网 ----

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFeedback(feedback: SkillFeedbackEntity): Long

    /** 单个 Skill 的平均分与反馈条数；无反馈时平均分为 0、条数为 0。 */
    @Query(
        "SELECT skill_id AS skillId, AVG(rating) AS averageRating, COUNT(*) AS feedbackCount " +
            "FROM skill_feedback WHERE skill_id = :skillId"
    )
    suspend fun ratingSummary(skillId: String): SkillRatingSummary?

    /** 一次性读取所有 Skill 的评分汇总，用于列表展示与本地排序。 */
    @Query(
        "SELECT skill_id AS skillId, AVG(rating) AS averageRating, COUNT(*) AS feedbackCount " +
            "FROM skill_feedback GROUP BY skill_id"
    )
    suspend fun ratingSummaries(): List<SkillRatingSummary>

    /** 某 Skill 的全部反馈，按时间倒序。 */
    @Query("SELECT * FROM skill_feedback WHERE skill_id = :skillId ORDER BY created_at DESC, id DESC")
    suspend fun feedbackForSkill(skillId: String): List<SkillFeedbackEntity>

    @Query("DELETE FROM skill_feedback WHERE skill_id = :skillId")
    suspend fun deleteFeedbackForSkill(skillId: String)
}
