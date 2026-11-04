package build.raft.app

import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftShellStoreScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * xxchan 11-04: an Artifact row in Activity opened the web page in the browser, not the in-app
 * viewer. The Activity tab is composed in an app-shell child page whose page data is only its tab
 * (RaftAppShellChildTabHost), so the viewer open from that page had no shell scope and was
 * rejected (missing_source_scope); the opener then fell back to the browser.
 */
class RaftArtifactViewerOpenScopeTest {
    private val opened = mutableListOf<Pair<String, JsonObject>>()
    private val transport = HostBridgeTransport { _, _, _ -> }
    private val navigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) {
                opened += pageName to pageData
            }

            override fun closePage() = Unit
        }

    /** The Activity tab's page: an app-shell child tab page, as RaftAppShellChildTabHost builds it. */
    private fun activityTabPager(): Pager =
        object : Pager() {}.also { pager ->
            pager.pagerId = "artifact-open-scope-test"
            pager.pageData =
                PageData(
                    raftEmbeddedChildPageData(
                        JSONObject().apply {
                            put("shellId", "shell-artifact-test")
                            put("tab", "activity")
                        }
                    )
                )
            pager.host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        }

    private val target = ArtifactLinkTarget("pt_artifact1", 2)
    private val activityScope = RaftShellStoreScope.create("android-default-app-shell", "server-1")

    @Test
    fun theActivityTabsOwnPageHasNoScopeToOpenTheViewerWith() {
        // Without the Activity's scope (the code before the fix): rejected, so the browser opened.
        assertFalse(activityTabPager().openRaftArtifactViewerPage(target, storeScope = null))
        assertEquals(emptyList(), opened)
    }

    @Test
    fun withTheActivitysScopeTheViewerPageOpens() {
        assertTrue(activityTabPager().openRaftArtifactViewerPage(target, storeScope = activityScope))
        val (pageName, pageData) = opened.single()
        assertEquals(RAFT_ARTIFACT_VIEWER_PAGE, pageName)
        assertEquals("android-default-app-shell", pageData[RAFT_SHELL_INSTANCE_ID]?.jsonPrimitive?.content)
        assertEquals("server-1", pageData[RAFT_SHELL_SERVER_ID]?.jsonPrimitive?.content)
        assertEquals(target, JSONObject(pageData.toString()).raftArtifactViewerTargetOrNull())
    }

    @Test
    fun aPageWithItsOwnScopeStillOpensWithoutOne() {
        // The chat card's Open: the conversation page's data carries the shell scope.
        val chatPager =
            activityTabPager().also { pager ->
                pager.pageData = PageData(JSONObject().withRaftShellStoreScope(activityScope))
            }
        assertTrue(chatPager.openRaftArtifactViewerPage(target, storeScope = null))
        assertEquals(RAFT_ARTIFACT_VIEWER_PAGE, opened.single().first)
    }
}
