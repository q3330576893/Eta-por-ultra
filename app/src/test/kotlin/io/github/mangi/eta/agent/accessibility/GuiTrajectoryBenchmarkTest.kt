package io.github.mangi.eta.agent.accessibility

import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.agent.model.AgentScreenObservationContract.ObservedNode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.3 GUI Agent 本地回归基准（借鉴 android_world）。
 *
 * - 从 app/src/main/assets/fixtures/gui_tasks/ 下的 json 文件读取任务（start_app + 目标 UI 断言 + 成功判据）。
 * - 复用 [AccessibilityNodeIdentity] 的节点身份匹配规则（window/package/class/viewId/text/desc，
 *   文本忽略纯数字跳动），对一段"动作前 → 动作后"的节点轨迹做 headless 断言。
 * - 复用 [AgentScreenObservationContract.computeGoalProgress] 校验 after.goal_progress 判据。
 * - 只做确定性节点/坐标回归；CI 无设备，不做端到端真机交互。
 */
class GuiTrajectoryBenchmarkTest {

    @Test
    fun fixturesAreWellFormedAndDeclareStartAppStepsAndSuccessCriteria() {
        val fixtures = loadFixtures()
        assertTrue("至少要有 GUI 基准任务", fixtures.isNotEmpty())
        fixtures.forEach { fixture ->
            val id = fixture.optString("id")
            assertTrue("$id 缺少 start_app", fixture.has("start_app"))
            assertEquals("$id start_app 必须是 launch_app", "launch_app", fixture.getJSONObject("start_app").getString("tool"))
            assertTrue("$id start_app 缺少 package_name", fixture.getJSONObject("start_app").getJSONObject("args").has("package_name"))
            assertTrue("$id 缺少 goal_keywords", fixture.getJSONArray("goal_keywords").length() > 0)
            assertTrue("$id 缺少 steps", fixture.getJSONArray("steps").length() > 0)
            assertTrue("$id 缺少 success_criteria", fixture.has("success_criteria"))
            val criteria = fixture.getJSONObject("success_criteria")
            assertTrue("$id 要求 all_expectations_met", criteria.getBoolean("all_expectations_met"))
            assertTrue(
                "$id success_criteria 必须声明 goal_progress_status_any",
                criteria.getJSONArray("goal_progress_status_any").length() > 0,
            )
        }
    }

    @Test
    fun identityMatchUsesStableFieldsAndIgnoresDigitOnlyJitter() {
        val observed = node(text = "未读 3 条", viewId = "com.app:id/row")
        val refreshed = node(text = "未读 9 条", viewId = "com.app:id/row")

        // 与 AccessibilityNodeIdentity.matches 一致：文字部分相同、纯数字跳动不算换目标。
        assertTrue(identityOf(observed).matches(identityOf(refreshed)))

        val changed = node(text = "已读 9 条", viewId = "com.app:id/row")
        assertFalse(identityOf(observed).matches(identityOf(changed)))
    }

    @Test
    fun nodeExpectationsMatchAgainstHeadlessTrajectory() {
        val fixture = loadFixtures().first { it.getString("id") == "settings_toggle_wifi" }
        val trajectory = listOf(
            node(viewId = "com.android.settings:id/main_content", text = "设置"),
            node(viewId = "com.android.settings:id/title", text = "WLAN"),
            node(viewId = "com.android.settings:id/title", text = "蓝牙"),
        )
        val expectations = fixture.getJSONArray("steps")
            .getJSONObject(0)
            .getJSONArray("expect")

        (0 until expectations.length()).forEach { i ->
            val expectation = expectations.getJSONObject(i)
            assertTrue(
                "${expectation.optString("description")} 应在轨迹中命中",
                trajectory.any { matchesExpectation(it, expectation.getJSONObject("match")) },
            )
        }
    }

    @Test
    fun goalProgressIsProgressWhenTargetKeywordGainAndUnknownWhenTruncated() {
        val before = listOf(node(text = "设置"), node(text = "蓝牙"))
        val after = listOf(node(text = "设置"), node(text = "WLAN"), node(text = "蓝牙"))
        val keywords = listOf("WLAN", "Wi-Fi")

        val progressed = AgentScreenObservationContract.computeGoalProgress(
            before, after, keywords, afterTruncated = false,
        )
        assertEquals("progress", progressed.status)
        assertTrue(progressed.keywordHits.any { it.keyword == "WLAN" && it.after > it.before })

        val unknown = AgentScreenObservationContract.computeGoalProgress(
            before, after, keywords, afterTruncated = true,
        )
        assertEquals("unknown", unknown.status)
        assertTrue(unknown.note.contains("不确定") || unknown.note.contains("无法判断"))

        val noChange = AgentScreenObservationContract.computeGoalProgress(
            before, before, keywords, afterTruncated = false,
        )
        assertEquals("no_change", noChange.status)
    }

    @Test
    fun fixtureSuccessCriteriaHoldForDeclaredTrajectory() {
        val fixture = loadFixtures().first { it.getString("id") == "dialer_place_call_entry" }
        val keywords = fixture.getJSONArray("goal_keywords").let { arr ->
            (0 until arr.length()).map(arr::getString)
        }
        val trajectory = listOf(
            node(viewId = "com.google.android.dialer:id/dialpad", text = "拨号键盘"),
            node(viewId = "com.google.android.dialer:id/tab", text = "电话"),
        )
        val before = listOf(node(viewId = "com.google.android.dialer:id/tab", text = "电话"))

        val expectations = fixture.getJSONArray("steps").getJSONObject(0).getJSONArray("expect")
        val allMet = (0 until expectations.length()).all { i ->
            val match = expectations.getJSONObject(i).getJSONObject("match")
            trajectory.any { matchesExpectation(it, match) }
        }
        assertTrue(allMet)

        val progress = AgentScreenObservationContract.computeGoalProgress(
            before, trajectory, keywords, afterTruncated = false,
        )
        val allowed = fixture.getJSONObject("success_criteria")
            .getJSONArray("goal_progress_status_any").let { arr ->
                (0 until arr.length()).map(arr::getString)
            }
        assertTrue(
            "goal_progress.status=${progress.status} 须在 $allowed 内",
            progress.status in allowed,
        )
    }

    // ---- headless 匹配规则：复用 AccessibilityNodeIdentity 的稳定字段语义 ----

    private fun matchesExpectation(node: ObservedNode, match: JSONObject): Boolean {
        match.optString("view_id").takeIf { it.isNotBlank() }?.let { viewId ->
            if (node.viewId != viewId) return false
        }
        match.optJSONArray("view_id_any")?.let { arr ->
            val candidates = (0 until arr.length()).map(arr::getString)
            if (candidates.isNotEmpty() && node.viewId !in candidates) return false
        }
        match.optJSONArray("text_any")?.let { arr ->
            val candidates = (0 until arr.length()).map(arr::getString)
            if (candidates.none { node.text.contains(it, true) }) return false
        }
        match.optString("text").takeIf { it.isNotBlank() }?.let { text ->
            if (node.text != text) return false
        }
        match.optString("desc").takeIf { it.isNotBlank() }?.let { desc ->
            if (node.desc != desc) return false
        }
        return true
    }

    private fun identityOf(node: ObservedNode) = AccessibilityNodeIdentity(
        uniqueId = "",
        windowId = 7,
        packageName = node.packageName.ifBlank { "example.app" },
        className = node.className.ifBlank { "android.widget.TextView" },
        viewId = node.viewId,
        text = node.text,
        description = node.desc,
        password = false,
    )

    private fun node(
        viewId: String = "",
        text: String = "",
        desc: String = "",
        checked: Boolean? = null,
        enabled: Boolean = true,
    ) = ObservedNode(
        viewId = viewId,
        text = text,
        desc = desc,
        checked = checked,
        enabled = enabled,
        className = "android.widget.TextView",
        packageName = "example.app",
    )

    private fun loadFixtures(): List<JSONObject> {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val assetsRoot = listOf(
            File(workingDirectory, "app/src/main/assets"),
            File(workingDirectory, "src/main/assets"),
        ).first { it.isDirectory }
        val directory = File(assetsRoot, "fixtures/gui_tasks")
        assertTrue("fixtures/gui_tasks 目录不存在", directory.isDirectory)
        return directory.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .map { JSONObject(it.readText()) }
    }
}
