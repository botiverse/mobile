package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The in-app Artifact viewer under the App's own title bar: the web page is opened with the embed
 * contract (`embed=raft-settings-v1&shell=host`, slock #11691), so it draws no second header, and a
 * top-level address that leaves the Artifact closes the viewer ([raftArtifactViewerNavigation]).
 */
class RaftArtifactViewerEmbedTest {
    private val web = "https://raft-app-staging.botiverse.dev"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val embed = "embed=raft-settings-v1&shell=host"

    @Test
    fun theViewerOpensThePageUnderTheAppTitleBar() {
        assertEquals("$web/artifacts/$id?$embed", raftArtifactViewerEmbeddedPageUrl(web, ArtifactLinkTarget(id)))
        // A pinned version keeps its address.
        assertEquals("$web/artifacts/$id/v/3?$embed", raftArtifactViewerEmbeddedPageUrl("$web/", ArtifactLinkTarget(id, 3)))
        // Open in browser stays the page as the web shows it.
        assertEquals("$web/artifacts/$id/v/3", raftArtifactViewerPageUrl(web, ArtifactLinkTarget(id, 3)))
        // A kept document is moved to the next Artifact at an address that keeps the contract.
        assertTrue("\"/artifacts/$id/v/2?$embed\"" in raftArtifactViewerNavigateScript(web, "", ArtifactLinkTarget(id, 2)))
    }

    @Test
    fun embedParamsMergeIntoAnExistingQuery() {
        val cases =
            listOf(
                "$web/artifacts/$id" to "$web/artifacts/$id?$embed",
                "$web/artifacts/$id?" to "$web/artifacts/$id?$embed",
                "$web/artifacts/$id/v/2?from=chat" to "$web/artifacts/$id/v/2?from=chat&$embed",
                "$web/artifacts/$id?a=1&b=2#c-9" to "$web/artifacts/$id?a=1&b=2&$embed#c-9",
                // Another value of the contract is replaced, never doubled; the rest stays in order.
                "$web/artifacts/$id?embed=1&a=1&shell=web" to "$web/artifacts/$id?a=1&$embed",
                "$web/artifacts/$id?$embed" to "$web/artifacts/$id?$embed",
            )
        for ((url, expected) in cases) assertEquals(expected, raftArtifactViewerEmbeddedUrl(url), url)
    }

    @Test
    fun whereAnAddressTakesTheViewer() {
        val cases =
            listOf(
                // This Artifact, any version, with or without the contract: the viewer stays.
                "$web/artifacts/$id?$embed" to RaftArtifactViewerNavigation.Stay(null),
                "$web/artifacts/$id/" to RaftArtifactViewerNavigation.Stay(null),
                "$web/artifacts/$id/v/4?$embed" to RaftArtifactViewerNavigation.Stay(4),
                "$web/s/acme/artifacts/$id/v/2" to RaftArtifactViewerNavigation.Stay(2),
                // Its original message (the toolbar's link): open it natively.
                "$web/s/acme/channel/ch_1?msg=m_9&$embed" to RaftArtifactViewerNavigation.OpenMessage("$web/s/acme/channel/ch_1?msg=m_9&$embed"),
                "$web/s/acme/dm/dm_2?msg=m_3&thread=dm_2%3Am_1" to RaftArtifactViewerNavigation.OpenMessage("$web/s/acme/dm/dm_2?msg=m_3&thread=dm_2%3Am_1"),
                // Anywhere else in the web app (the server after a Delete, another Artifact, a bad version): close.
                "$web/s/acme?$embed" to RaftArtifactViewerNavigation.Close,
                "$web/" to RaftArtifactViewerNavigation.Close,
                "$web/artifacts/pt_other" to RaftArtifactViewerNavigation.Close,
                "$web/artifacts/$id/v/0" to RaftArtifactViewerNavigation.Close,
                "$web/artifacts/$RAFT_ARTIFACT_VIEWER_WARM_SEGMENT?$embed" to RaftArtifactViewerNavigation.Close,
                // A message link on another host is not this web app's page: the system browser.
                "https://evil.test/s/acme/channel/ch_1?msg=m_9" to RaftArtifactViewerNavigation.SystemBrowser("https://evil.test/s/acme/channel/ch_1?msg=m_9"),
                "https://w-x.w-staging.raftusercontent.com/page" to RaftArtifactViewerNavigation.SystemBrowser("https://w-x.w-staging.raftusercontent.com/page"),
                // Not a page.
                "" to RaftArtifactViewerNavigation.Ignore,
                "about:blank" to RaftArtifactViewerNavigation.Ignore,
                "javascript:alert(1)" to RaftArtifactViewerNavigation.Ignore,
            )
        for ((url, expected) in cases) assertEquals(expected, raftArtifactViewerNavigation(url, web, id), url)
    }

    @Test
    fun aKeptDocumentThatLeftItsArtifactIsNotKept() {
        val scope = RaftArtifactViewerScope(web, "https://api-aws-staging.botiverse.dev/api", "user-maya", "server-northwind")
        var drops = 0
        val cache = RaftArtifactViewerCache(onKeptDocumentDropped = { drops += 1 })
        val target = ArtifactLinkTarget(id)
        assertTrue(cache.rememberKeptDocument(scope, target, raftArtifactViewerEmbeddedPageUrl(web, target)))
        // Another subject's leave touches nothing.
        cache.forgetKeptDocument(scope.copy(accountId = "user-other"))
        assertTrue(cache.keptDocumentShows(scope, target))
        assertEquals(0, drops)
        cache.forgetKeptDocument(scope)
        assertEquals(false, cache.keptDocumentShows(scope, target))
        assertEquals(1, drops)
    }
}
