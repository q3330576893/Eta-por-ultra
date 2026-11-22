package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ModelRequestHook
import io.github.mangi.eta.data.model.ModelRequestHookChannel
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import io.github.mangi.eta.testsupport.PosixIntegrationHostRule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [33, 36], application = Application::class)
class ModelRequestHookPosixTransportTest {
    @get:Rule
    val nativeIntegrationHost = PosixIntegrationHostRule("escapedHookWriterUsesUnlinkedDescriptorAndPreservesText")

    @Test
    fun escapedHookWriterUsesUnlinkedDescriptorAndPreservesText() {
        val hook = ModelRequestHook(
            enabled = true,
            channel = ModelRequestHookChannel.BOTH,
            prompt = "\n".repeat(ModelRequestHook.MAX_PROMPT_CHARS - 1) + "末",
        )
        val request = AgentRuntimeWire.RunRequest(
            runId = "hook-posix-writer",
            prompt = "Question",
            images = emptyList(),
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid",
                apiKey = "fixture",
                model = "fixture",
                systemPrompt = "system",
                reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.OFF,
                requestHook = hook,
            ),
        )
        val directory = File(
            RuntimeEnvironment.getApplication().cacheDir,
            "hook-posix-writer-${java.util.UUID.randomUUID()}",
        ).apply { check(mkdirs()) }
        var bundle: android.os.Bundle? = null
        var incoming: AgentRuntimeWire.IncomingRunRequest? = null
        var descriptor: ParcelFileDescriptor? = null
        try {
            bundle = AgentRuntimeWire.toBundle(request, emptyList(), directory)
            assertFalse(bundle.containsKey("request_hook_json"))
            assertTrue(AgentWireText.hasDescriptor(bundle, "request_hook_json"))
            assertTrue("Writer must unlink its temporary text path after opening the descriptor",
                directory.listFiles().orEmpty().isEmpty())

            descriptor = bundle.getParcelable(
                "request_hook_json_eta_text_fd",
                ParcelFileDescriptor::class.java,
            )
            assertNotNull(descriptor)
            assertTrue(requireNotNull(descriptor).statSize > ModelRequestHook.MAX_PROMPT_CHARS)

            val received = AgentRuntimeWire.incomingRunRequestFromBundle(bundle)
            incoming = received
            assertEquals(ModelRequestHook(), received.request.config.requestHook)
            assertEquals(request, received.materializeText())
            assertFalse(AgentWireText.hasDescriptor(bundle, "request_hook_json"))
            assertFalse("Reader must close the consumed descriptor", requireNotNull(descriptor).fileDescriptor.valid())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {
            if (incoming != null) {
                incoming.close()
            } else {
                bundle?.let(AgentWireText::close)
            }
            runCatching { descriptor?.close() }
            directory.deleteRecursively()
        }
    }
}
