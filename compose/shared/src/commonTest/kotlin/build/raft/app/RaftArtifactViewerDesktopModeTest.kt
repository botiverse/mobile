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
        // A host without the viewport-meta path (the Compose desktop app) has no Desktop Mode.
        assertFalse(raftArtifactViewerOffersDesktopMode(viaViewportMeta = false, finePointer = false, windowWidthDp = 390f))
        assertFalse(raftArtifactViewerOffersDesktopMode(viaViewportMeta = false, finePointer = false, windowWidthDp = 1024f))
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

    /** #189 step 6 (artin): the Artifact column keeps its own choice, off by default, apart from the page's. */
    @Test
    fun theColumnKeepsItsOwnChoiceOffByDefault() {
        val store = InMemoryRaftKeyValueStore()
        val page = ArtifactViewerDesktopModePreferenceRepository(store)
        val column = ArtifactViewerDesktopModePreferenceRepository(store, ArtifactViewerDesktopModePreferenceRepository.ColumnKey)
        page.write(true)
        assertFalse(column.read(), "the page's Desktop Mode doesn't lay a 480 column out as a desktop")
        column.write(true)
        assertTrue(ArtifactViewerDesktopModePreferenceRepository(store, ArtifactViewerDesktopModePreferenceRepository.ColumnKey).read())
        page.write(false)
        assertTrue(column.read(), "turning it off on the page leaves the column's")
    }

    /** #proj-workspace:#360: the Artifact viewer enables pinch-to-zoom both outside and inside Desktop Mode. */
    @Test
    fun artifactViewerConfigEnablesPinchZoomOutsideAndInsideDesktopMode() {
        val scope = RaftArtifactViewerScope(web, "https://api-aws-staging.botiverse.dev/api", "user-1", "server-1")
        val url = "$web/artifacts/pt_c82e597f-5434-4741-909b-d1c8a7a531c8"

        val defaultConfig = raftArtifactViewerWebViewConfig(url, scope, session, desktopMode = false)
        assertFalse(defaultConfig.useWideViewPort)
        assertTrue(defaultConfig.allowPinchZoom)

        val desktopConfig = raftArtifactViewerWebViewConfig(url, scope, session, desktopMode = true)
        assertTrue(desktopConfig.useWideViewPort)
        assertTrue(desktopConfig.allowPinchZoom)

        // Unrelated WebView surfaces keep pinch-zoom off by default.
        val unrelatedConfig =
            RaftWebViewConfig(
                url = "https://example.test/",
                allowScripts = true,
                allowNavigation = true,
                domStorageEnabled = true,
                useWideViewPort = false,
                cacheEnabled = true,
                ephemeral = false,
                injectedJavaScript = "",
                allowedNavigationSchemes = setOf("https"),
                hostEventsEnabled = false,
            )
        assertFalse(unrelatedConfig.allowPinchZoom)
    }
}
