package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `hostConversation=<conversation id>` (botiverse/slock #15103, #proj-workspace:#309): the viewer
 * names the conversation an Artifact was opened from, and only then; the page's title lists the
 * Artifacts recently opened there and moves the page in place to a picked one, which the viewer
 * follows without waiting for `hostLoaded=1`.
 */
class RaftArtifactViewerHostConversationTest {
    private val web = "https://raft-app-staging.botiverse.dev"
    private val env = "https://api-aws-staging.botiverse.dev/api"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val other = "pt_0b6d3c1e-9a2f-4c7e-8d11-2f3a4b5c6d7e"
    private val channel = "c0c0c0c0-0000-4000-8000-000000000001"
    private val embed = "embed=raft-settings-v1&shell=host&back=host&hostMenu=desktop-mode,copy-link,share-link,float-window&hostOpen=message"
    private val maya = RaftArtifactViewerScope(web, env, "user-maya", "server-northwind")
    private val token = "access-token-1"

    @Test
    fun theParamIsThereOnlyWithAConversation() {
        // From a conversation: alongside hostMenu.
        assertEquals(
            "$web/artifacts/$id?$embed&hostConversation=$channel",
            raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id), channel),
        )
        assertEquals(
            "$web/artifacts/$id/v/3?$embed&hostConversation=$channel",
            raftArtifactViewerEmbeddedPageUrl("$web/", ArtifactLinkTarget(id, 3), channel),
        )
        // Without one (Artifacts home, Activity, a link from outside): nothing.
        assertEquals("$web/artifacts/$id?$embed", raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id)))
        assertEquals("$web/artifacts/$id?$embed", raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id), null))
        // Not an id the web takes (slock embed.ts `[A-Za-z0-9_-]{1,64}`): nothing either.
        for (bad in listOf("", "  ", "a/b", "a&b=c", "id with space", "a".repeat(65))) {
            assertEquals("$web/artifacts/$id?$embed", raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id), bad), bad)
        }
        assertEquals("a".repeat(64), raftArtifactViewerHostConversationOrNull("a".repeat(64)))
        assertEquals("task_conv-1", raftArtifactViewerHostConversationOrNull(" task_conv-1 "))
        // One already in an address is the App's to set: replaced by this open's, or dropped without one.
        assertEquals(
            "$web/artifacts/$id?a=1&$embed&hostConversation=$channel",
            raftArtifactViewerEmbeddedUrl("$web/artifacts/$id?hostConversation=stale&a=1", channel),
        )
        assertEquals("$web/artifacts/$id?a=1&$embed", raftArtifactViewerEmbeddedUrl("$web/artifacts/$id?hostConversation=stale&a=1"))
        // The prewarmed document boots with it (the web latches it per document).
        assertEquals(
            "$web/artifacts/$RAFT_ARTIFACT_VIEWER_WARM_SEGMENT?$embed&hostConversation=$channel",
            raftArtifactViewerWarmUrl(web, channel),
        )
        assertEquals("$web/artifacts/$RAFT_ARTIFACT_VIEWER_WARM_SEGMENT?$embed", raftArtifactViewerWarmUrl(web))
        // A kept document is routed at an address naming the same one.
        assertTrue("\"/artifacts/$id?$embed&hostConversation=$channel\"" in raftArtifactViewerNavigateScript(web, "", ArtifactLinkTarget(id), channel))
        assertTrue("\"/artifacts/$id?$embed\"" in raftArtifactViewerNavigateScript(web, "", ArtifactLinkTarget(id)))
    }

    @Test
    fun aPickInThePageTitleSwitchesInPlaceOnlyFromAConversation() {
        val picked = "$web/artifacts/$other?$embed&hostConversation=$channel"
        val navigation = raftArtifactViewerNavigation(picked, web, id)
        assertEquals(RaftArtifactViewerNavigation.OpenArtifact(ArtifactLinkTarget(other)), navigation)
        assertEquals(ArtifactLinkTarget(other), raftArtifactViewerSwitchesInPlace(navigation, channel))
        // The keeper may put the params back a moment later: the bare address is the same move.
        assertEquals(
            ArtifactLinkTarget(other),
            raftArtifactViewerSwitchesInPlace(raftArtifactViewerNavigation("$web/artifacts/$other", web, id), channel),
        )
        assertEquals(
            ArtifactLinkTarget(other, 4),
            raftArtifactViewerSwitchesInPlace(raftArtifactViewerNavigation("$web/s/acme/artifacts/$other/v/4?$embed", web, id), channel),
        )
        // Then the address is this viewer's Artifact: it stays (Desktop Mode, Open in Browser, Copy link follow it).
        assertEquals(RaftArtifactViewerNavigation.Stay(null), raftArtifactViewerNavigation(picked, web, other))
        // Its Back still closes the viewer in one step.
        assertEquals(RaftArtifactViewerNavigation.Close, raftArtifactViewerNavigation("$picked#host=close", web, other))
        assertNull(raftArtifactViewerSwitchesInPlace(raftArtifactViewerNavigation("$picked#host=close", web, other), channel))
        // Without a conversation another Artifact opens its own viewer, as before.
        assertNull(raftArtifactViewerSwitchesInPlace(navigation, null))
        assertNull(raftArtifactViewerSwitchesInPlace(navigation, "a/b"))
        // Anything else is never a switch.
        for (url in listOf("$web/artifacts/$id?$embed", "$web/s/acme?$embed", "$web/")) {
            assertNull(raftArtifactViewerSwitchesInPlace(raftArtifactViewerNavigation(url, web, id), channel), url)
        }
    }

    @Test
    fun aKeptDocumentIsReusedOnlyForTheConversationItLatched() {
        val cache = RaftArtifactViewerCache()
        cache.markKeptWarm(maya, token, channel)
        assertTrue(cache.keptDocumentUsable(maya, token, channel))
        assertFalse(cache.keptDocumentUsable(maya, token, "c0c0c0c0-0000-4000-8000-000000000002"), "another conversation")
        assertFalse(cache.keptDocumentUsable(maya, token), "no conversation")

        // A new document loaded for none latches none.
        assertTrue(cache.rememberKeptDocument(maya, ArtifactLinkTarget(id), "$web/artifacts/$id?$embed", token))
        cache.noteKeptBoot(maya, null)
        assertTrue(cache.keptDocumentUsable(maya, token))
        assertFalse(cache.keptDocumentUsable(maya, token, channel))

        // Routing (or a move in place) keeps the boot's conversation.
        cache.noteKeptBoot(maya, channel)
        assertTrue(cache.rememberKeptDocument(maya, ArtifactLinkTarget(other), "$web/artifacts/$other?$embed&hostConversation=$channel", token))
        assertTrue(cache.keptDocumentShows(maya, ArtifactLinkTarget(other)))
        assertTrue(cache.keptDocumentUsable(maya, token, channel))

        // Dropped: nothing latched is left.
        cache.forgetKeptDocument(maya)
        cache.markKeptWarm(maya, token)
        assertFalse(cache.keptDocumentUsable(maya, token, channel))
        assertTrue(cache.keptDocumentUsable(maya, token))
    }
}
