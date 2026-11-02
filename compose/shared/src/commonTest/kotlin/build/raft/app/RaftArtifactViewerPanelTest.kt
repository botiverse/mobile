package build.raft.app

import build.raft.app.data.ArtifactViewerPanel
import build.raft.app.data.artifactViewerPanelOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page's open list in its address (`#panel=`, botiverse/slock staging a2654a29f9
 * `packages/web/src/apps/artifacts/artifactPageOpening.ts`; vectors from
 * `packages/web/tests/artifactPageOpening.test.ts`): the page writes `#panel=comments` /
 * `#panel=versions`, and no fragment at all when none is open.
 */
class RaftArtifactViewerPanelTest {
    private val web = "https://raft-app-staging.botiverse.dev"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val page = "$web/artifacts/$id?embed=raft-settings-v1&shell=host&chrome=none"

    @Test
    fun theOpenListIsReadFromTheAddress() {
        val cases =
            listOf(
                "$page#panel=comments" to ArtifactViewerPanel.Comments,
                "$page#panel=versions" to ArtifactViewerPanel.Versions,
                "$page#panel=none" to ArtifactViewerPanel.None,
                // None open is no fragment at all (artifactHashForPanel("none") is "").
                page to ArtifactViewerPanel.None,
                "$page#" to ArtifactViewerPanel.None,
                // Any other fragment is not the page's list: unchanged.
                "$page#panel=files" to null,
                "$page#comment-1" to null,
                "$page#panel=comments&x=1" to null,
            )
        for ((url, expected) in cases) assertEquals(expected, artifactViewerPanelOf(url), url)
    }

    @Test
    fun theListInTheAddressKeepsTheViewerOnItsArtifact() {
        assertEquals(RaftArtifactViewerNavigation.Stay(null), raftArtifactViewerNavigation("$page#panel=comments", web, id))
        assertEquals(RaftArtifactViewerNavigation.Stay(3), raftArtifactViewerNavigation("$web/artifacts/$id/v/3#panel=versions", web, id))
    }

    @Test
    fun theAppAsksForAListByReplacingTheFragmentAndSayingSo() {
        val script = raftArtifactViewerPanelScript(web, ArtifactViewerPanel.Comments)
        // Guarded to the web origin's top-level document (rule 1), no new history entry, and the
        // page told the way it listens (`hashchange`).
        assertTrue("window.location.origin !== \"$web\"" in script)
        assertTrue("\"#panel=comments\"" in script)
        assertTrue("replaceState" in script && "pushState" !in script)
        assertTrue("new HashChangeEvent('hashchange')" in script)
        assertTrue("\"#panel=none\"" in raftArtifactViewerPanelScript(web, ArtifactViewerPanel.None))
    }
}
