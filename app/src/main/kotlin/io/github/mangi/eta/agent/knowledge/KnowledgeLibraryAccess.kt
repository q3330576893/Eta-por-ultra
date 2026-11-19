package io.github.mangi.eta.agent.knowledge

import android.content.Context
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository

/** Activity-side reads use the same environment, authority and file dispatcher as agent tools. */
internal class KnowledgeLibraryAccess(context: Context, conversationId: String) : AutoCloseable {
    val store = KnowledgeLibraryStore(context)
    private val executionContext = LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment.let { environment ->
        TerminalExecutionContext(linuxEnvironment = environment,
            linuxRootfsPath = LinuxEnvironmentPaths.rootfsDir(context, checkNotNull(environment.linuxDistribution)).absolutePath)
    }
    private val controller = RootShellTerminalController(logger = AndroidAgentLogger,
        rootAvailable = { RootAccess.isGranted }, executionContext = executionContext,
        linuxSharedMountsProvider = { SharedFolderMounts.current() })
    val tools = KnowledgeToolExecutor(store, conversationId, controller::fileTool,
        enabled = { Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS) },
        environmentKey = { environment -> if (environment == "linux")
            "linux:${executionContext.linuxEnvironment}:${executionContext.linuxRootfsPath}" else environment },
        uriReader = KnowledgeToolExecutor.uriReader(context))
    override fun close() = controller.close()
}
