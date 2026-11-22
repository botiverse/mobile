package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.data.ArtifactLinkTarget
import build.raft.designsystem.theme.RaftControlMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `hostHead=<px>` (agreed with the web owner): in the wide layout's Artifact column the App tells the page the shell's
 * head height (the conversation's head beside it has the same: 56 by touch, 44 with a mouse), so the page draws its
 * toolbar as one row that tall and its bottom line is the conversation head's. The viewer as a page of its own (a
 * phone) sends none and the page keeps its phone toolbar. The web reads it once per document, so a kept document
 * booted with another value, or none, is not reused.
 */
class RaftArtifactViewerHostHeadTest {
    private val web = "https://raft-app-staging.botiverse.dev"
    private val env = "https://api-aws-staging.botiverse.dev/api"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val other = "pt_0b6d3c1e-9a2f-4c7e-8d11-2f3a4b5c6d7e"
    private val channel = "c0c0c0c0-0000-4000-8000-000000000001"
    private val embed = "embed=raft-settings-v1&shell=host&back=host&hostMenu=desktop-mode,open-in-browser,copy-link&hostOpen=message"
    private val maya = RaftArtifactViewerScope(web, env, "user-maya", "server-northwind")
    private val token = "access-token-1"

    /** What the column's viewer sends by touch and with a mouse (RaftTheme's control metrics for each input). */
    private val touch = raftArtifactViewerHostHead(inColumn = true, headHeight = RaftControlMetrics.Mobile.headHeight)
    private val mouse = raftArtifactViewerHostHead(inColumn = true, headHeight = RaftControlMetrics.Desktop.headHeight)

    @Test
    fun theColumnTellsThePageTheShellHeadHeightAndAPageOfItsOwnTellsNone() {
        assertEquals("$web/artifacts/$id?$embed&hostHead=56", raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id), hostHead = touch))
        assertEquals(
            "$web/artifacts/$id/v/3?$embed&hostConversation=$channel&hostHead=44",
            raftArtifactViewerEmbeddedPageUrl("$web/", ArtifactLinkTarget(id, 3), channel, mouse),
        )
        // A page of its own (a phone; on a tablet, opened from Artifacts home or Activity): none, whatever the input.
        for (headHeight in listOf(RaftControlMetrics.Mobile.headHeight, RaftControlMetrics.Desktop.headHeight)) {
            val none = raftArtifactViewerHostHead(inColumn = false, headHeight = headHeight)
            assertNull(none)
            assertEquals("$web/artifacts/$id?$embed", raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id), hostHead = none))
        }
        // One already in an address is the App's to set: replaced by this open's, or dropped without one.
        assertEquals("$web/artifacts/$id?a=1&$embed&hostHead=44", raftArtifactViewerEmbeddedUrl("$web/artifacts/$id?hostHead=56&a=1", hostHead = mouse))
        assertEquals("$web/artifacts/$id?a=1&$embed", raftArtifactViewerEmbeddedUrl("$web/artifacts/$id?hostHead=56&a=1"))
        // Never a value the web ignores (it takes 36 to 96).
        assertNull(raftArtifactViewerHostHead(inColumn = true, headHeight = 120.dp))
        assertEquals("$web/artifacts/$id?$embed", raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id), hostHead = 120))
        // A prewarmed document boots with what the viewer opened from where it was warmed sends.
        assertEquals(
            "$web/artifacts/$RAFT_ARTIFACT_VIEWER_WARM_SEGMENT?$embed&hostConversation=$channel&hostHead=56",
            raftArtifactViewerWarmUrl(web, channel, touch),
        )
        assertEquals("$web/artifacts/$RAFT_ARTIFACT_VIEWER_WARM_SEGMENT?$embed", raftArtifactViewerWarmUrl(web))
    }

    @Test
    fun theAddressKeepsItWhenTheKeptPageMovesAndFollowsTheInputInUse() {
        // A kept document routed to another Artifact (a recent one picked in its title) or a version: the address it
        // is moved to names the head it booted with.
        assertTrue("\"/artifacts/$other?$embed&hostConversation=$channel&hostHead=56\"" in raftArtifactViewerNavigateScript(web, "", ArtifactLinkTarget(other), channel, touch))
        assertTrue("\"/artifacts/$id/v/2?$embed&hostHead=44\"" in raftArtifactViewerNavigateScript(web, "", ArtifactLinkTarget(id, 2), hostHead = mouse))
        assertTrue("\"/artifacts/$id/v/2?$embed\"" in raftArtifactViewerNavigateScript(web, "", ArtifactLinkTarget(id, 2)))
        // While open, the App writes it where the address does not say the one in use: a route that dropped it, or a
        // mouse used since the page opened by touch. An address that says it is left alone.
        val page = "$web/artifacts/$id/v/2?$embed&hostHead=56&hostToolbar=1#panel=comments"
        assertTrue(raftArtifactViewerAddressSaysHostHead(page, touch))
        assertFalse(raftArtifactViewerAddressSaysHostHead(page, mouse))
        assertFalse(raftArtifactViewerAddressSaysHostHead("$web/artifacts/$id/v/2?$embed&hostToolbar=1", touch))
        // A page of its own carries none.
        assertTrue(raftArtifactViewerAddressSaysHostHead("$web/artifacts/$id?$embed&hostToolbar=1", null))
        assertFalse(raftArtifactViewerAddressSaysHostHead(page, null))
        // What a document read is its first address's.
        assertEquals(56, raftArtifactViewerHostHeadIn(page))
        assertNull(raftArtifactViewerHostHeadIn("$web/artifacts/$id?$embed"))
    }

    @Test
    fun aKeptDocumentIsReusedOnlyAtTheHeadHeightItBootedWith() {
        val cache = RaftArtifactViewerCache()
        // Prewarmed beside a conversation whose Artifacts open in the column, by touch.
        cache.markKeptWarm(maya, token, channel, touch)
        assertTrue(cache.keptDocumentUsable(maya, token, channel, touch))
        assertFalse(cache.keptDocumentUsable(maya, token, channel, mouse), "a mouse used since")
        assertFalse(cache.keptDocumentUsable(maya, token, channel), "the viewer as a page of its own (the window narrowed)")

        // Booted as a page of its own (Artifacts home on the tablet): not reused in the column.
        assertTrue(cache.rememberKeptDocument(maya, ArtifactLinkTarget(id), raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id)), token))
        cache.noteKeptBoot(maya, null, null)
        assertTrue(cache.keptDocumentUsable(maya, token))
        assertFalse(cache.keptDocumentUsable(maya, token, hostHead = touch))

        // Booted in the column with a mouse: reused there with a mouse only, routing included.
        cache.noteKeptBoot(maya, channel, mouse)
        assertTrue(cache.rememberKeptDocument(maya, ArtifactLinkTarget(other), raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(other), channel, mouse), token))
        assertTrue(cache.keptDocumentUsable(maya, token, channel, mouse))
        assertFalse(cache.keptDocumentUsable(maya, token, channel, touch))

        // Dropped: nothing latched is left; a warm one for a page of its own latches none.
        cache.forgetKeptDocument(maya)
        cache.markKeptWarm(maya, token)
        assertFalse(cache.keptDocumentUsable(maya, token, hostHead = touch))
        assertTrue(cache.keptDocumentUsable(maya, token))
    }
}
