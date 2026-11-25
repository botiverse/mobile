package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.Module
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftArtifactOpenInBrowserSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject

/**
 * The in-app Artifact viewer under the App's own title bar: the web page is opened with the embed
 * contract (`embed=raft-settings-v1&shell=host`, slock #11691) and no `chrome=none` (owner decision
 * 2026-11-10), so it draws its own toolbar and full ⋯ menu under the App's title bar, and a
 * top-level address that leaves the Artifact closes the viewer ([raftArtifactViewerNavigation]).
 */
class RaftArtifactViewerEmbedTest {
    private val web = "https://raft-app-staging.botiverse.dev"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
    private val embed = "embed=raft-settings-v1&shell=host&back=host&hostMenu=desktop-mode,open-in-browser,copy-link,share-link&hostOpen=message"

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
    fun whereAnAddressTakesTheViewer() {
        val cases =
            listOf(
                // The page's own Back (`back=host`) writes `#host=close`: the viewer closes.
                "$web/artifacts/$id?$embed#host=close" to RaftArtifactViewerNavigation.Close,
                "$web/artifacts/$id/v/2?$embed#host=close" to RaftArtifactViewerNavigation.Close,
                // Any other fragment is the page's own (a list, a comment): still this Artifact.
                "$web/artifacts/$id?$embed#panel=comments" to RaftArtifactViewerNavigation.Stay(null),
                "$web/artifacts/$id?$embed#host=closed" to RaftArtifactViewerNavigation.Stay(null),
                // This Artifact, any version, with or without the contract: the viewer stays.
                "$web/artifacts/$id?$embed" to RaftArtifactViewerNavigation.Stay(null),
                "$web/artifacts/$id/" to RaftArtifactViewerNavigation.Stay(null),
                "$web/artifacts/$id/v/4?$embed" to RaftArtifactViewerNavigation.Stay(4),
                "$web/s/acme/artifacts/$id/v/2" to RaftArtifactViewerNavigation.Stay(2),
                // `shown=<n>` (the latest read again in place, slock #12043) is still this Artifact: never leaving.
                "$web/artifacts/$id?$embed&shown=4" to RaftArtifactViewerNavigation.Stay(null, 4),
                "$web/s/acme/artifacts/$id?shown=x&$embed" to RaftArtifactViewerNavigation.Stay(null),
                // Another Artifact (a link in the page) opens in its own viewer; on another page `shown` keeps nothing open.
                "$web/artifacts/pt_other?$embed&shown=4" to RaftArtifactViewerNavigation.OpenArtifact(ArtifactLinkTarget("pt_other")),
                "$web/s/acme?shown=4" to RaftArtifactViewerNavigation.Close,
                // Its original message (the toolbar's link): open it natively.
                "$web/s/acme/channel/c0c0c0c0-0000-4000-8000-000000000001?msg=a0a0a0a0-0000-4000-8000-000000000009&$embed" to RaftArtifactViewerNavigation.OpenMessage("$web/s/acme/channel/c0c0c0c0-0000-4000-8000-000000000001?msg=a0a0a0a0-0000-4000-8000-000000000009&$embed"),
                "$web/s/acme/dm/d0d0d0d0-0000-4000-8000-000000000002?msg=a0a0a0a0-0000-4000-8000-000000000003&thread=d0d0d0d0-0000-4000-8000-000000000002%3Aa0a0a0a0-0000-4000-8000-000000000001" to RaftArtifactViewerNavigation.OpenMessage("$web/s/acme/dm/d0d0d0d0-0000-4000-8000-000000000002?msg=a0a0a0a0-0000-4000-8000-000000000003&thread=d0d0d0d0-0000-4000-8000-000000000002%3Aa0a0a0a0-0000-4000-8000-000000000001"),
                // Anywhere else in the web app (the server after a Delete, a bad version): close.
                "$web/s/acme?$embed" to RaftArtifactViewerNavigation.Close,
                "$web/" to RaftArtifactViewerNavigation.Close,
                "$web/artifacts/pt_other" to RaftArtifactViewerNavigation.OpenArtifact(ArtifactLinkTarget("pt_other")),
                "$web/artifacts/$id/v/0" to RaftArtifactViewerNavigation.Close,
                "$web/artifacts/$RAFT_ARTIFACT_VIEWER_WARM_SEGMENT?$embed" to RaftArtifactViewerNavigation.Close,
                // A message link on another host is not this web app's page: the system browser.
                "https://evil.test/s/acme/channel/c0c0c0c0-0000-4000-8000-000000000001?msg=a0a0a0a0-0000-4000-8000-000000000009" to RaftArtifactViewerNavigation.SystemBrowser("https://evil.test/s/acme/channel/c0c0c0c0-0000-4000-8000-000000000001?msg=a0a0a0a0-0000-4000-8000-000000000009"),
                "https://w-x.w-staging.raftusercontent.com/page" to RaftArtifactViewerNavigation.SystemBrowser("https://w-x.w-staging.raftusercontent.com/page"),
                // Not a page.
                "" to RaftArtifactViewerNavigation.Ignore,
                "about:blank" to RaftArtifactViewerNavigation.Ignore,
                "javascript:alert(1)" to RaftArtifactViewerNavigation.Ignore,
            )
        for ((url, expected) in cases) assertEquals(expected, raftArtifactViewerNavigation(url, web, id), url)
    }

    @Test
    fun thePageOwnMenuAsksTheAppByItsAddress() {
        val page = "$web/artifacts/$id/v/3?$embed&hostDesktop=off"
        // An App item in the page's ⋯ (`#host=<name>`): still this Artifact, at the version shown.
        assertEquals(RaftArtifactViewerHostAction.DesktopMode, raftArtifactViewerHostAction("$page#host=desktop-mode"))
        assertEquals(RaftArtifactViewerHostAction.OpenInBrowser, raftArtifactViewerHostAction("$page#host=open-in-browser"))
        assertEquals(RaftArtifactViewerNavigation.Stay(3), raftArtifactViewerNavigation("$page#host=open-in-browser", web, id))
        // Copy link (Hands d292d99f) and Share link (slock #16442): the page's own items ask, and the viewer stays on this Artifact.
        assertEquals(RaftArtifactViewerHostAction.CopyLink, raftArtifactViewerHostAction("$page#host=copy-link"))
        assertEquals(RaftArtifactViewerNavigation.Stay(3), raftArtifactViewerNavigation("$page#host=copy-link", web, id))
        assertEquals(RaftArtifactViewerHostAction.ShareLink, raftArtifactViewerHostAction("$page#host=share-link"))
        assertEquals(RaftArtifactViewerNavigation.Stay(3), raftArtifactViewerNavigation("$page#host=share-link", web, id))
        // Back's `#host=close` still closes, and is no menu item; unknown fragments are none either.
        assertEquals(null, raftArtifactViewerHostAction("$page#host=close"))
        assertEquals(RaftArtifactViewerNavigation.Close, raftArtifactViewerNavigation("$page#host=close", web, id))
        for (other in listOf(page, "$page#host=share", "$page#panel=comments", "$page#host=desktop-mode-x")) {
            assertEquals(null, raftArtifactViewerHostAction(other), other)
        }
    }

    @Test
    fun theCopiedLinkIsTheShareableAddressOnTheViewersWebBase() {
        // What CopyLink copies: this environment's web base (never production's host, never the
        // content frame's), the Artifact's page, `/v/<n>` only for a pinned version, no embed query.
        assertEquals("$web/artifacts/$id", raftArtifactViewerPageUrl("$web/", ArtifactLinkTarget(id, null)))
        assertEquals("$web/artifacts/$id/v/3", raftArtifactViewerPageUrl(web, ArtifactLinkTarget(id, 3)))
    }

    @Test
    fun oneTapIsOneAction() {
        val tap = "$web/artifacts/$id?$embed#host=desktop-mode"
        assertTrue(raftArtifactViewerHostActionIsNew(tap, handledUrl = null, handledCleared = true, sinceHandledMs = 0))
        // The second address callback, or Android's Desktop Mode reload at that address before it was taken off.
        assertEquals(false, raftArtifactViewerHostActionIsNew(tap, handledUrl = tap, handledCleared = false, sinceHandledMs = 60_000))
        assertEquals(false, raftArtifactViewerHostActionIsNew(tap, handledUrl = tap, handledCleared = true, sinceHandledMs = 100))
        // Taken off since: the page set it again, a new tap.
        assertTrue(raftArtifactViewerHostActionIsNew(tap, handledUrl = tap, handledCleared = true, sinceHandledMs = RAFT_ARTIFACT_VIEWER_HOST_ACTION_REPEAT_MS))
        assertTrue(raftArtifactViewerHostActionIsNew("$web/artifacts/$id?$embed#host=open-in-browser", handledUrl = tap, handledCleared = false, sinceHandledMs = 0))
        assertTrue(raftArtifactViewerHostActionIsNew("$web/artifacts/$id?$embed#host=copy-link", handledUrl = tap, handledCleared = false, sinceHandledMs = 0))
        assertTrue(raftArtifactViewerHostActionIsNew("$web/artifacts/$id?$embed#host=share-link", handledUrl = tap, handledCleared = false, sinceHandledMs = 0))
    }

    @Test
    fun onlyAPageThatSaysSoDrawsTheOnlyBar() {
        val page = "$web/artifacts/$id?$embed"
        assertEquals(false, raftArtifactViewerPageDrawsHostBar(page))
        assertEquals(false, raftArtifactViewerPageDrawsHostBar("$page&hostToolbar=0"))
        assertEquals(false, raftArtifactViewerPageDrawsHostBar("$page#hostToolbar=1"))
        assertEquals(true, raftArtifactViewerPageDrawsHostBar("$page&hostToolbar=1"))
        assertEquals(true, raftArtifactViewerPageDrawsHostBar("$web/artifacts/$id/v/2?hostToolbar=1&$embed#panel=files"))
        // The App never asks for it itself: an address carrying it is opened without it.
        assertEquals("$web/artifacts/$id?$embed", raftArtifactViewerEmbeddedUrl("$page&hostToolbar=1"))
    }

    @Test
    fun theListInTheAddressKeepsTheViewerOnItsArtifact() {
        // The page's open list is its address's fragment (`#panel=`); it never leaves the Artifact.
        val page = "$web/artifacts/$id?$embed"
        assertEquals(RaftArtifactViewerNavigation.Stay(null), raftArtifactViewerNavigation("$page#panel=comments", web, id))
        assertEquals(RaftArtifactViewerNavigation.Stay(3), raftArtifactViewerNavigation("$web/artifacts/$id/v/3#panel=versions", web, id))
    }

    @Test
    fun aShortLinkOpensTheViewerOnItsShortAddress() {
        // slock staging 380482116 artifactShortId.test.ts vector: 0b6f1c2e-… is 2QthvfPeGfDnqwr7LG6itx.
        val short = "2QthvfPeGfDnqwr7LG6itx"
        val pt = "pt_0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21"
        val target = build.raft.app.data.parseArtifactLinkTarget("https://app.raft.build/artifacts/$short/v/3", listOf("app.raft.build"))!!
        assertEquals("$web/artifacts/$short/v/3?$embed", raftArtifactViewerEmbeddedPageUrl(web, target))
        assertTrue("\"/artifacts/$short/v/3?$embed\"" in raftArtifactViewerNavigateScript(web, "", target))
        // Either form of the same Artifact in the address keeps the viewer (the web writes the short
        // id when a version is picked); another Artifact opens in its own viewer.
        assertEquals(RaftArtifactViewerNavigation.Stay(4), raftArtifactViewerNavigation("$web/artifacts/$short/v/4?$embed", web, short))
        assertEquals(RaftArtifactViewerNavigation.Stay(4), raftArtifactViewerNavigation("$web/artifacts/$short/v/4?$embed", web, pt))
        assertEquals(RaftArtifactViewerNavigation.Stay(null), raftArtifactViewerNavigation("$web/artifacts/$pt", web, short))
        assertEquals(
            RaftArtifactViewerNavigation.OpenArtifact(ArtifactLinkTarget(id, 2)),
            raftArtifactViewerNavigation("$web/artifacts/$id/v/2", web, short),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun shareLinkInvokesNativeShareTextAndFallsBackToCopyWithToast() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Pair<String, String>>()
            var shareOk = true
            val transport =
                HostBridgeTransport { method, params, callback ->
                    calls += method to params
                    if (method == "settingsCapability") {
                        callback?.invoke(if (shareOk) """{"status":"ok"}""" else """{"code":"NOT_IMPLEMENTED"}""")
                    }
                }
            val capabilities = BridgeWireHostCapabilities(transport)
            val navigator =
                object : RaftCmpNavigator {
                    override fun openPage(pageName: String, pageData: JsonObject) = Unit
                    override fun closePage() = Unit
                }
            val pager =
                object : Pager() {
                    override fun createExternalModules(): Map<String, Module> = mapOf(BridgeModule.MODULE_NAME to BridgeModule())
                }
            pager.pagerId = "artifact-share-link-test"
            pager.pageData = PageData(JSONObject())
            pager.host = RaftCmpHost(capabilities, navigator, bridgeTransport = transport)
            val bridge: BridgeModule = pager.acquireModule(BridgeModule.MODULE_NAME)

            val link = "$web/artifacts/$id/v/3"
            assertTrue(raftArtifactViewerShareLink(bridge, link, "Link copied."))
            assertEquals(1, calls.size)
            assertEquals("settingsCapability", calls[0].first)
            val shareReq = JSONObject(calls[0].second)
            assertEquals("shareText", shareReq.optString("action"))
            assertEquals(link, shareReq.optString("text"))

            // When the host lacks a native share sheet (e.g. Desktop), falls back to clipboard + toast.
            calls.clear()
            shareOk = false
            assertFalse(raftArtifactViewerShareLink(bridge, link, "Link copied."))
            assertEquals("settingsCapability", calls[0].first)
            assertTrue(calls.any { it.first == "copyToPasteboard" && JSONObject(it.second).optString("content") == link })
            assertTrue(calls.any { it.first == "toast" && JSONObject(it.second).optString("content") == "Link copied." })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun browserHandoffClosesViewerOnlyForStandaloneConversationOpens() {
        assertTrue(shouldCloseArtifactViewerAfterBrowserHandoff(conversationId = "c0c0c0c0-0000-4000-8000-000000000001", inColumn = false))
        // Wide split-pane keeps the conversation visible beside the Artifact column: do not close.
        assertFalse(shouldCloseArtifactViewerAfterBrowserHandoff(conversationId = "c0c0c0c0-0000-4000-8000-000000000001", inColumn = true))
        // Opened outside a conversation (Artifacts home, Activity, external link): keep viewer open.
        assertFalse(shouldCloseArtifactViewerAfterBrowserHandoff(conversationId = null, inColumn = false))
        assertFalse(shouldCloseArtifactViewerAfterBrowserHandoff(conversationId = "", inColumn = false))
        assertFalse(shouldCloseArtifactViewerAfterBrowserHandoff(conversationId = "   ", inColumn = false))
        assertFalse(shouldCloseArtifactViewerAfterBrowserHandoff(conversationId = null, inColumn = true))
    }

    @Test
    fun openUrlWireSerializesExternalBrowserOnlyWhenRequested() {
        val calls = mutableListOf<Pair<String, String>>()
        val transport = HostBridgeTransport { method, params, _ -> calls += method to params }
        val capabilities = BridgeWireHostCapabilities(transport)
        val navigator =
            object : RaftCmpNavigator {
                override fun openPage(pageName: String, pageData: JsonObject) = Unit
                override fun closePage() = Unit
            }
        val pager =
            object : Pager() {
                override fun createExternalModules(): Map<String, Module> = mapOf(BridgeModule.MODULE_NAME to BridgeModule())
            }
        pager.pagerId = "artifact-open-url-wire-test"
        pager.pageData = PageData(JSONObject())
        pager.host = RaftCmpHost(capabilities, navigator, bridgeTransport = transport)
        val bridge: BridgeModule = pager.acquireModule(BridgeModule.MODULE_NAME)
        val link = "$web/artifacts/$id/v/3"

        bridge.openUrl(link)
        val defaultPayload = JSONObject(calls.removeAt(0).second)
        assertEquals(link, defaultPayload.optString("url"))
        assertFalse(defaultPayload.has("browser"))
        assertFalse(defaultPayload.has("externalBrowser"))

        bridge.openUrl(link, inBrowser = true)
        val inBrowserPayload = JSONObject(calls.removeAt(0).second)
        assertEquals(link, inBrowserPayload.optString("url"))
        assertTrue(inBrowserPayload.optBoolean("browser", false))
        assertFalse(inBrowserPayload.has("externalBrowser"))

        bridge.openUrl(link, inBrowser = true, externalBrowser = true)
        val externalPayload = JSONObject(calls.removeAt(0).second)
        assertEquals(link, externalPayload.optString("url"))
        assertTrue(externalPayload.optBoolean("browser", false))
        assertTrue(externalPayload.optBoolean("externalBrowser", false))

        bridge.openUrl(link, externalBrowser = true)
        val externalOnlyPayload = JSONObject(calls.removeAt(0).second)
        assertEquals(link, externalOnlyPayload.optString("url"))
        assertFalse(externalOnlyPayload.has("browser"))
        assertTrue(externalOnlyPayload.optBoolean("externalBrowser", false))
    }

    @Test
    fun hostOpenInBrowserMarkerAndShownSurfaceRulesAvoidDenominatorPollution() {
        val v138Url = "$web/artifacts/$id?embed=raft-settings-v1&shell=host&hostToolbar=1"
        val v139Url = "$web/artifacts/$id?embed=raft-settings-v1&shell=host&hostToolbar=1&hostOpenInBrowser=1#top"
        assertFalse(raftArtifactViewerPageShowsOpenInBrowser(v138Url))
        assertTrue(raftArtifactViewerPageShowsOpenInBrowser(v139Url))
        assertFalse(raftArtifactViewerKeptPageShowedOpenInBrowser(null))
        assertFalse(raftArtifactViewerKeptPageShowedOpenInBrowser(v138Url))
        assertTrue(raftArtifactViewerKeptPageShowedOpenInBrowser(v139Url))

        // v1.38.3 loading (splash still up) and loaded (hostToolbar=1 without hostOpenInBrowser=1): NEVER emit shown.
        assertNull(raftArtifactOpenInBrowserShownSurface(viewing = true, pageDrawsBar = false, webShowsOpenInBrowser = false, bodyRevealed = false))
        assertNull(raftArtifactOpenInBrowserShownSurface(viewing = true, pageDrawsBar = true, webShowsOpenInBrowser = false, bodyRevealed = true))
        // v1.39 web single bar confirmed hostOpenInBrowser=1: emit web_bar.
        assertEquals(
            RaftArtifactOpenInBrowserSurface.WebBar,
            raftArtifactOpenInBrowserShownSurface(viewing = true, pageDrawsBar = true, webShowsOpenInBrowser = true, bodyRevealed = true),
        )
        // Legacy web without hostToolbar=1 once revealed: emit native_bar.
        assertEquals(
            RaftArtifactOpenInBrowserSurface.NativeBar,
            raftArtifactOpenInBrowserShownSurface(viewing = true, pageDrawsBar = false, webShowsOpenInBrowser = false, bodyRevealed = true),
        )
        // Non-viewing states never emit.
        assertNull(raftArtifactOpenInBrowserShownSurface(viewing = false, pageDrawsBar = false, webShowsOpenInBrowser = true, bodyRevealed = true))
    }
}
