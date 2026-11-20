package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Artifact's original message in the viewer's page (named by its conversation: a DM's by the
 * person or agent) used to close the viewer before the conversation opened. On a phone the
 * conversation now opens over the viewer, which is put back on the Artifact, so Back returns to it.
 */
class RaftArtifactViewerOpenOverTest {
    private val web = "https://app.raft.build"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val embed = "embed=raft-settings-v1&shell=host"
    private val shownAt = "$web/artifacts/$id/v/2?$embed&file=notes.md&hostLoaded=1#panel=comments"
    private val dmMessage = "$web/s/acme/dm/d0d0d0d0-0000-4000-8000-000000000002?msg=a0a0a0a0-0000-4000-8000-000000000003"

    private fun opensOver(url: String, inColumn: Boolean = false, artifactAddress: String? = shownAt) =
        raftArtifactViewerOpensOverViewer(raftArtifactViewerNavigation(url, web, id), inColumn, artifactAddress)

    @Test
    fun aConversationThePageMovesToOpensOverTheViewerOnAPhone() {
        assertEquals(RaftArtifactViewerNavigation.OpenMessage(dmMessage), raftArtifactViewerNavigation(dmMessage, web, id))
        assertTrue(opensOver(dmMessage))
        assertTrue(opensOver("$web/s/acme/channel/c0c0c0c0-0000-4000-8000-000000000001?msg=a0a0a0a0-0000-4000-8000-000000000009&$embed"))
        // The wide layout's Artifact column closes as before.
        assertFalse(opensOver(dmMessage, inColumn = true))
        // No address of the Artifact to put the page back on: the viewer closes as before.
        assertFalse(opensOver(dmMessage, artifactAddress = null))
        assertFalse(opensOver(dmMessage, artifactAddress = ""))
    }

    @Test
    fun anythingElseThatLeavesTheArtifactStillClosesTheViewer() {
        val closes =
            listOf(
                // The page's own Back, the server after a Delete, a profile (no in-app route), another Artifact.
                "$shownAt".substringBefore('#') + RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT,
                "$web/s/acme",
                "$web/s/acme/agent/a0a0a0a0-0000-4000-8000-000000000004",
                "$web/artifacts/pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21",
            )
        for (url in closes) assertFalse(opensOver(url), url)
    }

    @Test
    fun thePageIsPutBackOnTheAddressItShowedTheArtifactAt() {
        val script = raftArtifactViewerReturnScript(web, shownAt)
        // The version, the file, the open list and the embed contract are the address's: all of it goes back.
        assertTrue("var address = \"$shownAt\";" in script)
        assertTrue("pushState" in script && "PopStateEvent('popstate'" in script)
        // Guarded like every script the viewer sends: a top-level document of the web origin only.
        assertTrue("window.location.origin !== \"$web\"" in script)
        assertTrue(script.indexOf("window.top !== window") < script.indexOf("pushState"))
    }
}
