package io.github.mangi.eta.ui.screens.mcp

import io.github.mangi.eta.data.model.McpAuthorizationType
import org.junit.Assert.assertEquals
import org.junit.Test

class McpCredentialUpdateTest {
    @Test
    fun replacingBearerCredentialKeepsBearerAuthentication() {
        val update = McpCredentialUpdate.from(McpAuthorizationType.BEARER, "replacement-token")

        assertEquals(McpAuthorizationType.BEARER, update.authorizationType)
        assertEquals("replacement-token", update.value)
    }

    @Test
    fun clearingBearerCredentialAndRefillingRestoresBearerAuthentication() {
        val cleared = McpCredentialUpdate.from(McpAuthorizationType.BEARER, "  ")
        assertEquals(McpAuthorizationType.NONE, cleared.authorizationType)

        val refilled = McpCredentialUpdate.from(cleared.authorizationType, "replacement-token")
        assertEquals(McpAuthorizationType.BEARER, refilled.authorizationType)
    }

    @Test
    fun replacingQueryCredentialsPreservesEachParameterType() {
        listOf(McpAuthorizationType.QUERY_KEY, McpAuthorizationType.QUERY_AK).forEach { type ->
            val update = McpCredentialUpdate.from(type, "replacement-key")

            assertEquals(type, update.authorizationType)
            assertEquals("replacement-key", update.value)
        }
    }

    @Test
    fun clearingQueryCredentialsPreservesTypeForLaterRefill() {
        listOf(McpAuthorizationType.QUERY_KEY, McpAuthorizationType.QUERY_AK).forEach { type ->
            listOf("", "  ").forEach { blank ->
                val cleared = McpCredentialUpdate.from(type, blank)
                assertEquals(type, cleared.authorizationType)
                assertEquals(blank, cleared.value)

                val refilled = McpCredentialUpdate.from(cleared.authorizationType, "replacement-key")
                assertEquals(type, refilled.authorizationType)
                assertEquals("replacement-key", refilled.value)
            }
        }
    }

    @Test
    fun savingWithoutPreviouslyConfiguredAuthenticationUsesOptionalBearerSemantics() {
        assertEquals(
            McpAuthorizationType.NONE,
            McpCredentialUpdate.from(McpAuthorizationType.NONE, "").authorizationType,
        )
        assertEquals(
            McpAuthorizationType.BEARER,
            McpCredentialUpdate.from(McpAuthorizationType.NONE, "new-token").authorizationType,
        )
    }

    @Test
    fun capturedCredentialIsIndependentOfSubsequentInputClearing() {
        var input = "pending-key"
        val update = McpCredentialUpdate.from(McpAuthorizationType.QUERY_AK, input)
        input = ""

        assertEquals("", input)
        assertEquals("pending-key", update.value)
        assertEquals(McpAuthorizationType.QUERY_AK, update.authorizationType)
    }
}
