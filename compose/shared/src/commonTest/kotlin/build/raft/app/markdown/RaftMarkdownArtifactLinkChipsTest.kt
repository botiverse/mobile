package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import build.raft.app.RaftColor
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.threadAppLinkPreviewsEnabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Workspace Artifacts links in a message's text (web `AppLinkChip.tsx` `ObjectLinkChip`): the App
 * link chip with the kind's icon and the title, no domain; an unavailable one stays the author's link.
 */
class RaftMarkdownArtifactLinkChipsTest {
    private val id = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"
    private val link = "https://app.raft.build/artifacts/$id"
    private val hosts = listOf("app.raft.build")
    private val summary =
        ArtifactSummary(
            id = id,
            title = "Q3 report",
            description = null,
            kind = ArtifactKind.Html,
            version = 4,
            pinned = false,
            latestVersion = 4,
            home = ArtifactHome.Conversation("proj-workspace", dm = false),
        )

    private fun source(url: String = link, text: String = url): AnnotatedString =
        buildAnnotatedString {
            append("Read ")
            withLink(LinkAnnotation.Url(url)) { append(text) }
            append(" today")
        }

    private fun AnnotatedString.visibleText(): String = text.replace("￼", "").replace("⁠", "").replace(' ', ' ')

    private fun host(opened: MutableList<ArtifactLinkTarget>? = null, summaries: Map<String, ArtifactSummary> = mapOf(id to summary)) =
        RaftAppLinkChipHost(
            results = emptyMap(),
            onAsk = {},
            artifacts = summaries,
            artifactHosts = hosts,
            onOpenArtifact = opened?.let { list -> { target: ArtifactLinkTarget -> list += target } },
        )

    @Test
    fun anAvailableArtifactIsACyanChipWithItsTitleAndNoDomain() {
        val opened = mutableListOf<ArtifactLinkTarget>()
        val chip = source().withRaftAppLinkChipContent(host(opened)) {}
        assertEquals("Read Q3 report today", chip.text.visibleText(), "no domain on the chip")
        assertEquals(RaftColor.InfoSoft, chip.text.raftInlineBoxRanges().single().item.backgroundColor)
        // The kind's icon and its gap.
        assertEquals(2, chip.inlineContent.size)
        val annotation = chip.text.getLinkAnnotations(0, chip.text.length).single().item as LinkAnnotation.Clickable
        assertEquals(RaftArtifactLinkTag, annotation.tag)
        annotation.linkInteractionListener!!.onClick(annotation)
        assertEquals(listOf(ArtifactLinkTarget(id)), opened)
    }

    @Test
    fun unavailableWordedOrForeignLinksStayPlain() {
        // Not answered `ok` (loading, unavailable, failed): the author's link.
        val unavailable = source().withRaftAppLinkChipContent(host(summaries = mapOf("pt_other" to summary))) {}
        assertEquals("Read $link today", unavailable.text.text)
        assertTrue(unavailable.inlineContent.isEmpty())
        // A link the author gave words to.
        val worded = source(text = "the report").withRaftAppLinkChipContent(host()) {}
        assertEquals("Read the report today", worded.text.text)
        // A lookalike host and the content domain.
        for (url in listOf("https://app.raft.build.evil.example/artifacts/$id", "https://a1b2.w.raftusercontent.com/artifacts/$id")) {
            val plain = source(url).withRaftAppLinkChipContent(host()) {}
            assertEquals("Read $url today", plain.text.text)
        }
    }

    @Test
    fun aPinnedVersionIsItsOwnReference() {
        val pinned = "$link/v/2"
        val chip = source(pinned).withRaftAppLinkChipContent(host(summaries = mapOf("$id;version=2" to summary.copy(version = 2, pinned = true)))) {}
        assertEquals("Read Q3 report today", chip.text.visibleText())
    }

    @Test
    fun withoutAnOpenerTheChipOpensTheLink() {
        val opened = mutableListOf<String>()
        val chip = source().withRaftAppLinkChipContent(host(opened = null)) { opened += it }
        val annotation = chip.text.getLinkAnnotations(0, chip.text.length).single().item as LinkAnnotation.Url
        annotation.linkInteractionListener!!.onClick(annotation)
        assertEquals(listOf(link), opened)
    }

    @Test
    fun shareCaptureAndNoHostKeepTheAuthorsLink() {
        // A share image keeps the links the author wrote: the message scope reads nothing and
        // provides no chip host while capture is active.
        assertFalse(threadAppLinkPreviewsEnabled(sentMessage = true, shareCaptureActive = true))
        val text = source()
        assertSame(text, text.withRaftAppLinkChipContent(null) {}.text)
    }
}
