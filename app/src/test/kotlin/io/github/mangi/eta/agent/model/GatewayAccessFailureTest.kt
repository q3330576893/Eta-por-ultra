package io.github.mangi.eta.agent.model

import android.app.Application
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class GatewayAccessFailureTest {
    private val groupError = """{"error":{"message":"API Key is not assigned to any group and cannot be used. Please contact the administrator to assign it to a group. fixture-sensitive-body","type":"permission_error"}}"""
    private val clientError = """{"error":{"code":"client_not_allowed","message":"fixture-sensitive-body"}}"""

    @Test fun capturedGroupDenialHasDistinctActionableNonRetryingClassification() {
        val failure = AgentModelFailure.http(403, groupError)
        assertEquals("PROVIDER_GROUP_UNASSIGNED", failure.code)
        assertFalse(failure.retryable)
        assertTrue(failure.message!!.contains("分组"))
        assertTrue(failure.message!!.contains("不能授予"))
        assertFalse(failure.message!!.contains("fixture-sensitive-body"))
        assertFalse(failure.message!!.contains("Key 无效"))
    }

    @Test fun clientGateIsDistinctFromAccountGroupAndDoesNotTriggerBlindRetries() {
        val failure = AgentModelFailure.http(403, clientError)
        assertEquals("PROVIDER_CLIENT_NOT_ALLOWED", failure.code)
        assertFalse(failure.retryable)
        assertTrue(failure.message!!.contains("User-Agent"))
        assertFalse(failure.message!!.contains("fixture-sensitive-body"))
    }

    @Test fun streamDenialsUseSameSanitizedClassification() {
        for ((body, code) in listOf(groupError to "PROVIDER_GROUP_UNASSIGNED", clientError to "PROVIDER_CLIENT_NOT_ALLOWED")) {
            val error = JSONObject(body).getJSONObject("error")
            val failure = AgentModelFailure.stream(error, "fixture-sensitive-body")
            assertEquals(code, failure.code)
            assertFalse(failure.retryable)
            assertFalse(failure.message!!.contains("fixture-sensitive-body"))
        }
    }

    @Test fun unknownForbiddenAndOtherStatusesKeepTheirExistingClassification() {
        assertEquals("HTTP_403", AgentModelFailure.http(403, """{"error":{"type":"permission_error","message":"other restriction"}}""").code)
        assertEquals("HTTP_403", AgentModelFailure.http(403, "<html>not assigned to any group</html>").code)
        val upstreamFailure = AgentModelFailure.http(503, groupError)
        assertEquals("HTTP_503", upstreamFailure.code)
        assertTrue(upstreamFailure.retryable)
    }
}
