package io.github.mangi.eta.agent.knowledge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.mangi.eta.R
import java.util.concurrent.Executors

/** Opens the exact stored revision after rechecking the original source's current read authority. */
class KnowledgeSourceActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var windowStart = 1
    private lateinit var body: LinearLayout
    private var sourceReference: KnowledgeCitationReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.kb_library_open_source)
        val reference = KnowledgeCitationLinks.fromIntent(intent)
        if (reference == null) {
            setContentView(TextView(this).apply { setText(R.string.kb_library_invalid_citation) })
            return
        }
        sourceReference = reference
        windowStart = savedInstanceState?.getInt("window_start") ?: (reference.startLine - 20).coerceAtLeast(1)
    }
    override fun onResume() { super.onResume(); if (sourceReference != null) render() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("window_start", windowStart); super.onSaveInstanceState(outState) }

    private fun render() {
        val reference = sourceReference ?: return
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 24) }
        setContentView(ScrollView(this).apply { addView(body) })
        val destination = body
        worker.execute {
            try {
                KnowledgeLibraryAccess(this, "").use { access ->
                    val document = access.store.source(reference.collectionId, reference.documentId, reference.revision, access.tools::validate)
                    val allLines = document.content.split('\n')
                    if (reference.startLine > allLines.size || reference.endLine > allLines.size) {
                        throw KnowledgeException("KB_INVALID_CITATION", getString(R.string.kb_library_invalid_citation))
                    }
                    val first = windowStart.coerceIn(1, allLines.size.coerceAtLeast(1))
                    val last = minOf(allLines.size, first + 199)
                    val highlightStart = reference.startLine
                    val highlightEnd = reference.endLine
                    val text = SpannableStringBuilder()
                    for (line in first..last) {
                        val start = text.length
                        text.append(line.toString()).append("  ").append(allLines[line - 1]).append('\n')
                        if (line in highlightStart..highlightEnd) text.setSpan(BackgroundColorSpan(Color.argb(60, 255, 193, 7)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || body !== destination) return@runOnUiThread
                        destination.addView(TextView(this).apply {
                            this.text = "${document.filename}\n${document.path}\n${document.environment}/${document.identity}\n${document.revision}\n$first–$last / ${allLines.size}"
                            setPadding(0, 0, 0, 20)
                        })
                        destination.addView(Button(this).apply {
                            setText(R.string.kb_library_previous_lines); isEnabled = first > 1
                            setOnClickListener { windowStart = (first - 200).coerceAtLeast(1); render() }
                        })
                        destination.addView(TextView(this).apply { this.text = text; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE; textSize = 14f })
                        destination.addView(Button(this).apply {
                            setText(R.string.kb_library_next_lines); isEnabled = last < allLines.size
                            setOnClickListener { windowStart = last + 1; render() }
                        })
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && body === destination) {
                        destination.removeAllViews()
                        destination.addView(TextView(this).apply { text = failure.message ?: getString(R.string.kb_library_failed) })
                    }
                }
            }
        }
    }
    override fun onPause() { if (::body.isInitialized) body.removeAllViews(); super.onPause() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    companion object {
        fun intent(context: Context, collectionId: String, documentId: String, revision: String, startLine: Int = 1, endLine: Int = startLine): Intent {
            require(KnowledgeCitationLinks.reference(collectionId, documentId, revision, startLine, endLine) != null) { "Invalid internal KB citation" }
            return Intent(context, KnowledgeSourceActivity::class.java).putExtra("collection_id", collectionId).putExtra("document_id", documentId)
                .putExtra("revision", revision).putExtra("start_line", startLine).putExtra("end_line", endLine)
        }
    }
}
