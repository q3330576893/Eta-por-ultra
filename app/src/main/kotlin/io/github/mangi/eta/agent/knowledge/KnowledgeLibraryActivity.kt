package io.github.mangi.eta.agent.knowledge

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/** Standalone management surface; its caller supplies the real conversation ID, never a run ID. */
class KnowledgeLibraryActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var layout: LinearLayout
    private lateinit var status: TextView
    private var selectedCollectionId: String? = null
    private var importCollectionId: String? = null
    private val conversationId: String get() = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.kb_library_title)
        selectedCollectionId = savedInstanceState?.getString("selected")
        importCollectionId = savedInstanceState?.getString("import_collection")
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selectedCollectionId)
        outState.putString("import_collection", importCollectionId)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 24) }
        setContentView(ScrollView(this).apply { addView(layout) })
        status = TextView(this).apply { setPadding(0, 8, 0, 16) }
        layout.addView(TextView(this).apply { text = getString(R.string.kb_library_keyword_note); textSize = 16f })
        layout.addView(status)
        layout.addView(button(R.string.kb_library_create) {
            textDialog(R.string.kb_library_create, false) { name -> perform { access -> access.store.createCollection(name); refreshOnUi() } }
        })
        perform { access ->
            val snapshot = access.store.snapshot(conversationId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (snapshot.collections.none { it.id == selectedCollectionId }) selectedCollectionId = snapshot.collections.firstOrNull()?.id
                snapshot.collections.forEach { collection ->
                    layout.addView(Button(this).apply {
                        text = (if (collection.id == selectedCollectionId) "✓ " else "") + collection.name
                        setOnClickListener { selectedCollectionId = collection.id; refresh() }
                    })
                }
                val selected = snapshot.collections.firstOrNull { it.id == selectedCollectionId }
                if (selected == null) {
                    status.setText(R.string.kb_library_empty)
                    return@runOnUiThread
                }
                status.text = getString(R.string.kb_library_selected, selected.name)
                layout.addView(CheckBox(this).apply {
                    setText(R.string.kb_library_attach)
                    isChecked = selected.id in snapshot.attachedCollectionIds
                    isEnabled = conversationId.isNotBlank()
                    setOnCheckedChangeListener { _, checked -> perform { access -> access.store.attach(conversationId, selected.id, checked) } }
                })
                if (conversationId.isBlank()) layout.addView(TextView(this).apply { setText(R.string.kb_library_need_conversation) })
                layout.addView(button(R.string.kb_library_import_saf) { chooseDocument(selected.id) })
                layout.addView(button(R.string.kb_library_import_text) {
                    textDialog(R.string.kb_library_import_text, true) { text ->
                        perform { access ->
                            requireTextPermission()
                            KnowledgeLibraryStore.validateText(text)
                            val id = UUID.randomUUID().toString()
                            access.store.upsert(KnowledgeDocument("", selected.id, getString(R.string.kb_library_pasted_name),
                                "import://$id", "android", "user", KnowledgeLibraryStore.digest(text.toByteArray(Charsets.UTF_8)), text, "import"))
                            refreshOnUi()
                        }
                    }
                })
                layout.addView(button(R.string.kb_library_import_path) { pathDialog(selected.id) })
                layout.addView(button(R.string.kb_library_delete_collection) {
                    AlertDialog.Builder(this).setMessage(R.string.kb_library_delete_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ -> perform { access -> access.store.deleteCollection(selected.id); refreshOnUi() } }
                        .setNegativeButton(android.R.string.cancel, null).show()
                })
                snapshot.documents.filter { it.collectionId == selected.id }.forEach { document ->
                    layout.addView(TextView(this).apply { text = "${document.filename}\n${document.path}\n${document.environment}/${document.identity} · ${document.bytes} B"; setPadding(0, 18, 0, 4) })
                    layout.addView(button(R.string.kb_library_open_source) { openSource(document, 1, 1) })
                    layout.addView(CheckBox(this).apply {
                        setText(R.string.kb_library_pin)
                        isChecked = document.id in snapshot.pinnedDocumentIds
                        isEnabled = conversationId.isNotBlank()
                        setOnCheckedChangeListener { _, checked -> perform { access -> access.store.pin(conversationId, document.id, checked) } }
                    })
                    layout.addView(button(R.string.kb_library_delete_index) { perform { access -> access.store.deleteDocument(document.id); refreshOnUi() } })
                }
                val query = EditText(this).apply { setHint(R.string.kb_library_query); filters = arrayOf(InputFilter.LengthFilter(500)); isSingleLine = true }
                layout.addView(query)
                val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                layout.addView(button(R.string.kb_library_search) {
                    val keywords = query.text.toString()
                    perform { access ->
                        val searched = access.store.search(conversationId, keywords, 20, access.tools::validate)
                        runOnUiThread {
                            results.removeAllViews()
                            if (searched.hits.isEmpty()) results.addView(TextView(this).apply { setText(R.string.kb_library_no_hits) })
                            searched.hits.forEach { hit ->
                                results.addView(Button(this).apply {
                                    text = "${hit.document.filename} · ${hit.startLine}–${hit.endLine}\n${hit.document.path}\n${hit.excerpt}"
                                    setOnClickListener { openSource(hit.document, hit.startLine, hit.endLine) }
                                })
                            }
                        }
                    }
                })
                layout.addView(results)
            }
        }
    }

    private fun pathDialog(collectionId: String) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0) }
        val path = EditText(this).apply { setHint(R.string.kb_library_path); isSingleLine = true; filters = arrayOf(InputFilter.LengthFilter(4096)) }
        val environment = EditText(this).apply { setText("android"); setHint(R.string.kb_library_environment); isSingleLine = true }
        val identity = EditText(this).apply { setHint(R.string.kb_library_identity); isSingleLine = true }
        listOf(path, environment, identity).forEach(fields::addView)
        AlertDialog.Builder(this).setTitle(R.string.kb_library_import_path).setView(fields)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val args = JSONObject().put("path", path.text.toString()).put("environment", environment.text.toString().trim())
                if (identity.text.isNotBlank()) args.put("identity", identity.text.toString().trim())
                perform { access ->
                    val document = access.tools.readFile(collectionId, args)
                    access.tools.validate(document)
                    access.store.upsert(document)
                    refreshOnUi()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun textDialog(title: Int, multiline: Boolean, submit: (String) -> Unit) {
        val input = EditText(this).apply {
            isSingleLine = !multiline
            minLines = if (multiline) 6 else 1
            maxLines = 12
            filters = arrayOf(InputFilter.LengthFilter(if (multiline) KnowledgeLibraryStore.MAX_DOCUMENT_BYTES else 100))
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> submit(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseDocument(collectionId: String) {
        importCollectionId = collectionId
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_DOCUMENT)
    }

    @Deprecated("Uses the platform document picker contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DOCUMENT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val collection = importCollectionId ?: return
        importCollectionId = null
        perform { access ->
            requireTextPermission()
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "document.txt"
            access.tools.importUri(collection, uri.toString(), name)
            refreshOnUi()
        }
    }

    private fun openSource(document: KnowledgeDocument, startLine: Int, endLine: Int) {
        startActivity(KnowledgeSourceActivity.intent(this, document.collectionId, document.id, document.revision, startLine, endLine))
    }
    private fun button(text: Int, action: () -> Unit): Button = Button(this).apply { setText(text); setOnClickListener { action() } }
    private fun requireTextPermission() {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)) throw KnowledgeException("TERMINAL_TOOLS_DISABLED", getString(R.string.kb_library_permission))
    }
    private fun refreshOnUi() = runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
    private fun perform(action: (KnowledgeLibraryAccess) -> Unit) {
        worker.execute {
            try { KnowledgeLibraryAccess(this, conversationId).use(action) }
            catch (failure: Exception) {
                runOnUiThread { if (!isFinishing && !isDestroyed) status.text = failure.message ?: getString(R.string.kb_library_failed) }
            }
        }
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    companion object {
        const val EXTRA_CONVERSATION_ID = "io.github.mangi.eta.knowledge.CONVERSATION_ID"
        private const val REQUEST_DOCUMENT = 3107
        fun intent(context: Context, conversationId: String): Intent = Intent(context, KnowledgeLibraryActivity::class.java)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }
}
