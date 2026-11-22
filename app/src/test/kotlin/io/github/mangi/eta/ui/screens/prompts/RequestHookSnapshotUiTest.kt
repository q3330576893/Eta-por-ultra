package io.github.mangi.eta.ui.screens.prompts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestHookSnapshotUiTest {
    @Test
    fun absentMetadataRemainsUnknownForOldSnapshots() {
        assertNull(RequestHookSnapshotUi.from(JSONObject().put("system", JSONArray())))
    }

    @Test
    fun readsChannelHashAndEstimatedTokens() {
        val snapshot = JSONObject().put(
            "request_hook",
            JSONObject().put("active", true).put("channel", "SYSTEM")
                .put("revision", "0123456789ab").put("estimated_tokens", 42),
        )

        assertEquals(RequestHookSnapshotUi(true, "SYSTEM", "0123456789ab", 42), RequestHookSnapshotUi.from(snapshot))
    }

    @Test
    fun copyOmitsHookRowsAndKeepsRecordedSystemText() {
        val system = JSONArray()
            .put(JSONObject().put("text", "ordinary system"))
            .put(JSONObject().put("request_hook", true).put("text", "excluded marker"))
            .put(JSONObject().put("content", "legacy system"))

        assertEquals("ordinary system\n\nlegacy system\n\n", recordedSystemText(system))
    }
}
