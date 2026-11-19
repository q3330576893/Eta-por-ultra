package io.github.mangi.eta.ui.markdown

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.ui.platform.UriHandler
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.knowledge.KnowledgeCitationLinks

/** Scoped to the chat Markdown renderer; KB links never leave the application. */
internal class MarkdownKnowledgeUriHandler(
    private val context: Context,
    private val delegate: UriHandler,
) : UriHandler {
    override fun openUri(uri: String) {
        if (!KnowledgeCitationLinks.recognizes(uri)) {
            delegate.openUri(uri)
            return
        }
        val reference = KnowledgeCitationLinks.parse(uri)
        if (reference == null) {
            Toast.makeText(context, R.string.kb_library_invalid_citation, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = KnowledgeCitationLinks.intent(context, reference)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.kb_library_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
