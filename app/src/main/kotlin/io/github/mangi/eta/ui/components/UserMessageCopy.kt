package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec

/** Copies the same user-authored text that the bubble displays; never copies injected skill instructions. */
internal fun copyUserMessageText(content: String, writeClipboard: (String) -> Unit): Boolean {
    val visibleText = AgentFileReferencePromptCodec.parse(content).request
    if (visibleText.isBlank()) return false
    writeClipboard(visibleText)
    return true
}
