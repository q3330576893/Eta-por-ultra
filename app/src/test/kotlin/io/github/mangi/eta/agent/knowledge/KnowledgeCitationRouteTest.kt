package io.github.mangi.eta.agent.knowledge

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.LinkAnnotation
import io.github.mangi.eta.R
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import io.github.mangi.eta.ui.markdown.MarkdownKnowledgeUriHandler
import io.github.mangi.eta.ui.markdown.MarkdownParagraph
import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import io.github.mangi.eta.ui.markdown.safeLinkUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class KnowledgeCitationRouteTest {
    @Test fun buildParseRoundTripRetainsOpaqueRevisionAndExactLineRange() {
        val reference = reference().copy(revision = "android:user:opaque+/&=?#")
        val url = KnowledgeCitationLinks.build(reference)
        assertTrue(url.startsWith("eta-kb://source?"))
        assertTrue(url.contains("%2B%2F%26%3D%3F%23"))
        assertTrue(url.length <= KnowledgeCitationLinks.MAX_URI_CHARS)
        assertEquals(reference, KnowledgeCitationLinks.parse(url))
        assertEquals(url, safeLinkUrl(url))
        val upperScheme = url.replaceFirst("eta-kb:", "ETA-KB:")
        assertEquals(reference, KnowledgeCitationLinks.parse(upperScheme))
    }

    @Test fun parserRejectsWrongScopeDuplicateUnknownMalformedAndOutOfRangeParameters() {
        val url = KnowledgeCitationLinks.build(reference())
        val invalid = listOf(
            url.replace("eta-kb:", "https:"),
            url.replace("//source?", "//outside?"),
            url.replace("//source?", "//source:123?"),
            url.replace("//source?", "//user@source?"),
            url.replace("//source?", "//source/path?"),
            "$url#fragment",
            "$url&start_line=2",
            url.replace("&document_id=", "&collection_id="),
            url.replace("&revision=", "&unrecognized="),
            url.replace("&end_line=7", ""),
            url.replace("start_line=3", "start_line=0"),
            url.replace("start_line=3", "start_line=-1"),
            url.replace("start_line=3", "start_line=03"),
            url.replace("start_line=3", "start_line=9999999999999999999"),
            url.replace("end_line=7", "end_line=2"),
            url.replace("end_line=7", "end_line=${KnowledgeCitationLinks.MAX_LINE + 1}"),
            url.replace("revision=r1", "revision=%FF"),
            url.replace("revision=r1", "revision=%00"),
            url.replace("revision=r1", "revision=%"),
            url.replace("revision=r1", "revision=%0A"),
            url.replace("revision=r1", "revision=with+space"),
            url.replace("collection_id=550e8400-e29b-41d4-a716-446655440000", "collection_id=..%2Fprivate"),
            "$url${"a".repeat(KnowledgeCitationLinks.MAX_URI_CHARS)}",
        )
        invalid.forEach { value -> assertNull(value, KnowledgeCitationLinks.parse(value)) }
        invalid.filter(KnowledgeCitationLinks::recognizes).forEach { assertNull(safeLinkUrl(it)) }
    }

    @Test fun escapedFilenameProducesExactlyOneClickableInternalCitationInMarkdown() {
        val filename = "预算 [final](https://outside.example) _稿_ `v2` & notes.md"
        val markdown = KnowledgeCitationLinks.markdown(filename, reference())
        assertTrue(markdown.contains("eta-kb://source?"))
        val paragraph = StreamingGfmParserSession().parse(markdown, isComplete = true).document.blocks.single() as MarkdownParagraph
        val links = paragraph.text.getLinkAnnotations(0, paragraph.text.length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
        assertEquals(listOf(KnowledgeCitationLinks.build(reference())), links)
        assertTrue(paragraph.text.text, paragraph.text.text.contains(filename))
    }

    @Test fun searchHitPublishesItsActualVersionAndLineBoundCitationFields() {
        val ref = reference()
        val document = KnowledgeDocument(ref.documentId, ref.collectionId, "资料 [1].md", "/approved/source.md", "android", "user",
            ref.revision, (1..7).joinToString("\n") { "line $it" })
        val json = KnowledgeHit(document, ref.startLine, ref.endLine, "excerpt", 3, listOf("excerpt")).toJson()
        assertEquals(ref, KnowledgeCitationLinks.parse(json.getString("citation_url")))
        assertEquals(KnowledgeCitationLinks.markdown(document.filename, ref), json.getString("citation"))
        assertEquals("r1", json.getString("revision"))
        assertEquals(3, json.getInt("start_line"))
        assertEquals(7, json.getInt("end_line"))
    }

    @Test fun markdownHandlerActuallyStartsThePrivateSourceActivityWithValidatedExtras() {
        val context = RuntimeEnvironment.getApplication() as Application
        val external = mutableListOf<String>()
        val handler = MarkdownKnowledgeUriHandler(context, delegate(external))
        val ref = reference()
        handler.openUri(KnowledgeCitationLinks.build(ref))
        val launched = shadowOf(context).nextStartedActivity
        assertNotNull(launched)
        assertEquals(ComponentName(context, KnowledgeSourceActivity::class.java), launched.component)
        assertNull(launched.action)
        assertNull(launched.data)
        assertEquals(ref, KnowledgeCitationLinks.fromIntent(launched))
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, launched.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(external.isEmpty())
        val activity = context.packageManager.getActivityInfo(ComponentName(context, KnowledgeSourceActivity::class.java), 0)
        assertFalse(activity.exported)
    }

    @Test fun malformedInternalLinksAreConsumedWithoutExternalFallback() {
        val context = RuntimeEnvironment.getApplication() as Application
        val external = mutableListOf<String>()
        val handler = MarkdownKnowledgeUriHandler(context, delegate(external))
        handler.openUri("eta-kb://source?collection_id=bad")
        assertNull(shadowOf(context).nextStartedActivity)
        assertTrue(external.isEmpty())
        assertEquals(context.getString(R.string.kb_library_invalid_citation), ShadowToast.getTextOfLatestToast())
        handler.openUri("https://example.com/source")
        assertEquals(listOf("https://example.com/source"), external)
    }

    @Test fun sourceActivityRejectsMalformedExplicitExtrasBeforeLoadingAnySource() {
        val context = RuntimeEnvironment.getApplication() as Application
        val malformed = Intent(context, KnowledgeSourceActivity::class.java).putExtra("collection_id", "../private")
            .putExtra("document_id", "bad").putExtra("revision", "r1").putExtra("start_line", 0).putExtra("end_line", Int.MAX_VALUE)
        val controller = Robolectric.buildActivity(KnowledgeSourceActivity::class.java, malformed).create().start().resume()
        try {
            val activity = controller.get()
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            assertEquals(context.getString(R.string.kb_library_invalid_citation), (root.getChildAt(0) as TextView).text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun invalidReferenceAndOversizedEncodedRevisionNeverProduceACitation() {
        val ref = reference()
        assertNull(KnowledgeCitationLinks.reference(ref.collectionId, ref.documentId, " ", 1, 1))
        assertNull(KnowledgeCitationLinks.reference(ref.collectionId, ref.documentId, "文".repeat(256), 1, 1))
        assertNull(KnowledgeCitationLinks.reference(ref.collectionId, ref.documentId, "r1", -1, 1))
        assertNull(KnowledgeCitationLinks.reference(ref.collectionId, ref.documentId, "r1", 1, KnowledgeCitationLinks.MAX_LINE + 1))
        try { KnowledgeSourceActivity.intent(RuntimeEnvironment.getApplication(), ref.collectionId, ref.documentId, "r1", 0, 1); fail("Invalid line accepted") }
        catch (_: IllegalArgumentException) { }
    }

    private fun reference() = KnowledgeCitationReference("550e8400-e29b-41d4-a716-446655440000", "a".repeat(64), "r1", 3, 7)
    private fun delegate(opened: MutableList<String>) = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
}
