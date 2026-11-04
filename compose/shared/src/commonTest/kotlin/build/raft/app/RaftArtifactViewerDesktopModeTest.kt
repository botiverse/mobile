package build.raft.app

import build.raft.app.data.ArtifactViewerDesktopModePreferenceRepository
import build.raft.app.data.InMemoryRaftKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Artifact viewer's Desktop Mode (artin 11-03): where it is offered, what it injects, what it keeps. */
class RaftArtifactViewerDesktopModeTest {
    private val web = "https://raft-app-staging.botiverse.dev"
    private val scope = RaftArtifactViewerScope(web, "staging", "user-maya", "server-northwind")
    private val session = "localStorage.setItem('slock_access_token', 't')"

    @Test
    fun offeredOnTouchScreensNarrowerThanADesktop() {
        assertTrue(raftArtifactViewerOffersDesktopMode(viaViewportMeta = true, finePointer = false, windowWidthDp = 390f))
        // A tablet, either way round, is still narrower than the desktop width.
        assertTrue(raftArtifactViewerOffersDesktopMode(viaViewportMeta = true, finePointer = false, windowWidthDp = 1024f))
        assertTrue(raftArtifactViewerOffersDesktopMode(viaViewportMeta = true, finePointer = false, windowWidthDp = RaftFilePreviewDesktopViewportWidth - 1f))
        // Already as wide as the desktop layout: nothing to widen.
        assertFalse(raftArtifactViewerOffersDesktopMode(viaViewportMeta = true, finePointer = false, windowWidthDp = RaftFilePreviewDesktopViewportWidth.toFloat()))
        assertFalse(raftArtifactViewerOffersDesktopMode(viaViewportMeta = true, finePointer = false, windowWidthDp = 1366f))
        // The Compose app (mouse and keyboard) is a desktop at any window width.
        assertFalse(raftArtifactViewerOffersDesktopMode(viaViewportMeta = true, finePointer = true, windowWidthDp = 390f))
        // Not measured yet.
        assertFalse(raftArtifactViewerOffersDesktopMode(viaViewportMeta = true, finePointer = false, windowWidthDp = 0f))
    }

    @Test
    fun notOfferedWhereTheHostCannotWidenThroughTheViewportMeta() {
        // iOS (pageZoom path): a blank ~0.3x strip on device (1.19.2 TestFlight), so no menu item.
        assertFalse(raftArtifactViewerOffersDesktopMode(viaViewportMeta = false, finePointer = false, windowWidthDp = 390f))
        assertFalse(raftArtifactViewerOffersDesktopMode(viaViewportMeta = false, finePointer = false, windowWidthDp = 1024f))
    }

    @Test
    fun offLeavesTheSessionScriptAsItIs() {
        assertEquals(session, raftArtifactViewerInjectedScript(web, session, desktopMode = false, viaViewportMeta = true))
        assertEquals(session, raftArtifactViewerInjectedScript(web, session, desktopMode = false, viaViewportMeta = false))
    }

    @Test
    fun onAddsTheGuardedDesktopViewportWhereTheHostWidensThroughTheMeta() {
        val script = raftArtifactViewerInjectedScript(web, session, desktopMode = true, viaViewportMeta = true)
        assertTrue(script.startsWith(session))
        val viewport = script.substringAfter(session)
        assertTrue("width=$RaftFilePreviewDesktopViewportWidth" in viewport)
        // Width only: the WebView's overview fits it, and the reader can still zoom.
        assertFalse("initial-scale" in viewport)
        assertFalse("user-scalable" in viewport)
        // Rule 1: like the session, only in a top-level document of the web origin.
        assertTrue("window.top !== window" in viewport)
        assertTrue("window.location.origin !== \"$web\"" in viewport)
        assertTrue(viewport.indexOf("window.top !== window") < viewport.indexOf("meta[name=viewport]"))
        // Before the session is read (a kept document): the viewport alone.
        assertEquals(viewport.trim(), raftArtifactViewerInjectedScript(web, "", desktopMode = true, viaViewportMeta = true))
    }

    @Test
    fun viewportScriptWaitsForTheDocumentAtDocumentStart() {
        // Chromium runs document-start scripts before <html> exists (measured): the script must not
        // throw there, and it watches the document itself so the page's own meta is rewritten later.
        val script = raftFilePreviewDesktopViewportScript()
        assertTrue("if(!r)return;" in script)
        assertTrue(".observe(document,{childList:true,subtree:true})" in script)
        assertFalse("(document.head||document.documentElement).appendChild" in script)
    }

    @Test
    fun onLeavesTheScriptAloneWhereTheHostHasNoViewportMetaPath() {
        // The Compose app (desktop): no Desktop Mode, so no viewport rewrite either.
        assertEquals(session, raftArtifactViewerInjectedScript(web, session, desktopMode = true, viaViewportMeta = false))
    }

    @Test
    fun configWidensTheViewportOnlyInDesktopMode() {
        val url = raftArtifactViewerWarmUrl(web)
        assertFalse(raftArtifactViewerWebViewConfig(url, scope, session).useWideViewPort)
        assertTrue(raftArtifactViewerWebViewConfig(url, scope, session, desktopMode = true).useWideViewPort)
        // The rest of Tenny's rules hold either way.
        val desktop = raftArtifactViewerWebViewConfig(url, scope, session, desktopMode = true)
        assertEquals(web, desktop.mainFrameOrigin)
        assertFalse(desktop.hostEventsEnabled)
        assertEquals(scope.keepAliveKey, desktop.keepAliveKey)
    }

    @Test
    fun preferenceIsKeptOnTheDeviceAndOffRemovesIt() {
        val store = InMemoryRaftKeyValueStore()
        val preference = ArtifactViewerDesktopModePreferenceRepository(store)
        assertFalse(preference.read())
        preference.write(true)
        assertTrue(ArtifactViewerDesktopModePreferenceRepository(store).read())
        preference.write(false)
        assertFalse(preference.read())
        assertNull(store.getString(ArtifactViewerDesktopModePreferenceRepository.Key))
    }

    @Test
    fun anythingElseStoredReadsAsOff() {
        val store = InMemoryRaftKeyValueStore(mapOf(ArtifactViewerDesktopModePreferenceRepository.Key to "true"))
        assertFalse(ArtifactViewerDesktopModePreferenceRepository(store).read())
    }
}
