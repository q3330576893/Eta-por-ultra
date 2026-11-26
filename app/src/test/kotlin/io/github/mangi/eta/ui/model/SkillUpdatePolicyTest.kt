package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新入口的可见性策略：没有来源记录的老 Skill 不出现更新检查。 */
class SkillUpdatePolicyTest {
    @Test
    fun onlyInstalledUserSkillsWithSourceRecordCanCheckForUpdate() {
        assertTrue(skill(source = "user", installed = true, hasSource = true).canCheckForUpdate)
        // 手工装 / ZIP 导入：没有来源记录，无从比较新旧。
        assertFalse(skill(source = "user", installed = true, hasSource = false).canCheckForUpdate)
        // 内置 Skill 由 assets 提供，不存在「来源仓库更新」。
        assertFalse(skill(source = "builtin", installed = true, hasSource = true).canCheckForUpdate)
        // 已删除的占位条目没有实体目录。
        assertFalse(skill(source = "user", installed = false, hasSource = true).canCheckForUpdate)
    }

    @Test
    fun badgeOnlyShowsForRealUpdatesWithoutError() {
        val state = AgentSkillsUiState(
            updates = mapOf(
                "has-update" to SkillUpdateUi("has-update", "11111111", "22222222", hasUpdate = true),
                "up-to-date" to SkillUpdateUi("up-to-date", "11111111", "11111111", hasUpdate = false),
                "failed" to SkillUpdateUi("failed", "11111111", null, error = "GitHub 请求失败（HTTP 500）"),
            ),
        )

        assertEquals("22222222", state.updateBadgeFor("has-update")?.latestCommit)
        assertNull("已是最新不应出现标记", state.updateBadgeFor("up-to-date"))
        assertNull("查询失败不能当成有更新", state.updateBadgeFor("failed"))
    }

    @Test
    fun busyFlagCoversCheckingAndUpdating() {
        assertFalse(AgentSkillsUiState().isSkillUpdateBusy)
        assertTrue(AgentSkillsUiState(checkingUpdateIds = setOf("alpha")).isSkillUpdateBusy)
        assertTrue(AgentSkillsUiState(updatingSkillId = "alpha").isSkillUpdateBusy)
    }

    private fun skill(source: String, installed: Boolean, hasSource: Boolean) = SkillItemUi(
        id = "test-skill",
        name = "Test Skill",
        description = "Test update policy.",
        source = source,
        enabled = true,
        installed = installed,
        capabilities = emptyList(),
        hasGitHubSource = hasSource,
    )
}
