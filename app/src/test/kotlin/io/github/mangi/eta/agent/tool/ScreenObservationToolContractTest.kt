package io.github.mangi.eta.agent.tool

import android.app.Application
import android.content.Context
import android.graphics.Rect
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.display.VirtualScreenRoutingPolicy
import io.github.mangi.eta.agent.model.AgentGestureToolCatalog
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.agent.model.ScreenNodeLocator
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ScreenObservationToolContractTest {
    @Test fun publishedLocateToolExecutesAgainstTheActualCachedObservation() {
        val catalog = JSONArray().also(AgentGestureToolCatalog::appendTo)
        assertTrue((0 until catalog.length()).any { catalog.getJSONObject(it).getJSONObject("function").getString("name") == "locate_on_screen" })
        var observationId = "current-observation"
        val node = RootShellDeviceController.UiNode(1, "保存", "保存资料", "Button", "example.app", "save", Rect(10, 20, 80, 60),
            clickable = true, longClickable = false, scrollable = false, focused = false, editable = false, password = false, enabled = true)
        AgentLocalTools(RuntimeEnvironment.getApplication() as Context, QuietLogger, rootAvailable = { false },
            virtualScreenSettings = { Settings() }, screenObservationProvider = {
                RootShellDeviceController.Observation(JSONObject().put("ok", true)
                    .put("screen", JSONObject().put("width", 101).put("height", 101)).toString(), null,
                    RootShellDeviceController.ElementObservation(observationId, RootShellDeviceController.ElementSource.ACCESSIBILITY,
                        "example.app", 1, listOf(node), 60, false), null)
            }).use { tools ->
            invoke(tools, "observe_screen", JSONObject())
            val args = JSONObject().put("target", "保存").put("observation_id", observationId)
            val result = invoke(tools, "locate_on_screen", args)
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("current-observation", result.getString("observation_id"))
            assertEquals(0, result.getInt("display_id"))
            assertEquals("ui_node_text", result.getString("method"))
            val candidate = result.getJSONArray("candidates").getJSONObject(0)
            assertEquals(1.0, candidate.getDouble("confidence"), 0.0)
            val bounds = candidate.getJSONObject("bbox")
            assertEquals(99, bounds.getInt("x1"))
            assertEquals(199, bounds.getInt("y1"))
            assertEquals(799, bounds.getInt("x2"))
            assertEquals(599, bounds.getInt("y2"))
            observationId = "new-observation"
            invoke(tools, "observe_screen", JSONObject())
            assertEquals("STALE_OBSERVATION", invoke(tools, "locate_on_screen", args).getString("code"))
            assertEquals("STALE_OBSERVATION", invoke(tools, "tap_area", JSONObject().put("x1", 99).put("y1", 199)
                .put("x2", 799).put("y2", 599).put("coordinate_space", "normalized")
                .put("observation_id", "current-observation").put("display_id", 0)).getString("code"))
        }
    }

    @Test fun screenshotOnlyOrMissingNodesReturnsUnsupportedRatherThanInventingBoxes() {
        assertEquals("LOCATE_UNSUPPORTED_NO_UI_NODES", ScreenNodeLocator.locate(null, arguments(), 1000).getString("code"))
        assertEquals("LOCATE_UNSUPPORTED_NO_UI_NODES", ScreenNodeLocator.locate(snapshot(emptyList()), arguments(), 1000).getString("code"))
        AgentLocalTools(RuntimeEnvironment.getApplication() as Context, QuietLogger, rootAvailable = { false },
            virtualScreenSettings = { Settings() }).use { tools ->
            assertEquals("LOCATE_UNSUPPORTED_NO_UI_NODES", invoke(tools, "locate_on_screen", arguments()).getString("code"))
        }
    }

    @Test fun virtualDisplayScopeAndFreshnessArePreservedAndCrossDisplayReplayIsRejected() {
        assertTrue("locate_on_screen" in VirtualScreenRoutingPolicy.uiTools)
        val snap = snapshot(listOf(ScreenNodeLocator.Node(0, "保存", "", 0, 0, 100, 100))).copy(displayId = 7, sessionId = "virtual-session")
        val result = ScreenNodeLocator.locate(snap, arguments().put("display_id", 7), 1000)
        assertTrue(result.getBoolean("ok"))
        assertEquals("virtual-session", result.getJSONObject("display_scope").getString("session_id"))
        assertEquals(7, result.getInt("display_id"))
        assertEquals("DISPLAY_SCOPE_MISMATCH", ScreenNodeLocator.locate(snap, arguments().put("display_id", 0), 1000).getString("code"))
        assertEquals("STALE_OBSERVATION", ScreenNodeLocator.locate(snap, arguments(), 1000 + ScreenNodeLocator.MAX_AGE_MS + 1).getString("code"))
    }

    @Test fun candidateBoundsAreClampedPasswordsExcludedAndVisualDescriptionsNeverBecomeMatches() {
        val snap = snapshot(listOf(ScreenNodeLocator.Node(0, "保存", "", -30, -10, 400, 200),
            ScreenNodeLocator.Node(1, "保存", "", 1, 1, 90, 90, password = true)))
        val result = ScreenNodeLocator.locate(snap, arguments().put("max_candidates", 10000), 1000)
        assertEquals(1, result.getInt("count"))
        val box = result.getJSONArray("candidates").getJSONObject(0).getJSONObject("bbox")
        assertEquals(0, box.getInt("x1")); assertEquals(999, box.getInt("x2"))
        assertEquals(0, ScreenNodeLocator.locate(snap, arguments().put("target", "右下角红色圆形按钮"), 1000).getInt("count"))
    }

    @Test fun nodeChangesNeverClaimGoalProgressAndUnstableOrEmptyBaselinesStayUnknown() {
        val before = listOf(observed("old"))
        val after = listOf(observed("new"))
        assertEquals("changed", AgentScreenObservationContract.computeGoalProgress(before, after, emptyList(), false).status)
        assertEquals("changed", AgentScreenObservationContract.computeGoalProgress(before, after, listOf("new"), false).status)
        assertEquals("unknown", AgentScreenObservationContract.computeGoalProgress(before, before, emptyList(), false).status)
        assertEquals("unknown", AgentScreenObservationContract.computeGoalProgress(emptyList(), after, emptyList(), false).status)
        assertEquals("unknown", AgentScreenObservationContract.computeGoalProgress(before, after, emptyList(), false, beforeTruncated = true).status)
        assertEquals("unknown", AgentScreenObservationContract.computeGoalProgress(before, after, emptyList(), false, nodesStable = false).status)
    }

    @Test fun changeJsonHasFiniteNodeAndTextBudgets() {
        val before = (0 until 120).map { observed("old-$it" + "x".repeat(2000)) }
        val after = (0 until 120).map { observed("new-$it" + "y".repeat(2000)) }
        val json = AgentScreenObservationContract.computeGoalProgress(before, after, emptyList(), false).toJson()
        assertEquals("changed", json.getString("status"))
        assertEquals(12, json.getJSONArray("added").length())
        assertEquals(12, json.getJSONArray("removed").length())
        assertTrue(json.getBoolean("diff_truncated"))
        assertFalse(json.getBoolean("goal_verified"))
        assertTrue(json.toString().length < 20000)
    }

    private fun arguments() = JSONObject().put("target", "保存").put("observation_id", "snapshot")
    private fun snapshot(nodes: List<ScreenNodeLocator.Node>) = ScreenNodeLocator.Snapshot("snapshot", 0, "primary", 101, 101, nodes, 1000)
    private fun observed(text: String) = AgentScreenObservationContract.ObservedNode(text = text)
    private fun invoke(tools: AgentLocalTools, name: String, args: JSONObject): JSONObject = JSONObject(tools.execute(AgentModelClient.ToolCall("screen-test", name, args.toString())).content)
    private object QuietLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
