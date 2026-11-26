package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The viewer's persistent diagnostics lines: ids and the web host only, never a URL, query or token. */
class RaftArtifactViewerDiagnosticsTest {
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"

    @Test
    fun theOpenLineCarriesTheIdVersionAndWebHostOnly() {
        val line =
            raftArtifactViewerOpenLogLine(
                ArtifactLinkTarget(id, 3),
                scopeKnown = true,
                webBaseUrl = "https://raft.build/some/path?token=secret",
            )
        assertEquals("artifact_viewer_open artifact=$id version=3 scope=known webHost=raft.build", line)
        assertFalse("secret" in line)
        assertFalse("/some/path" in line)
    }

    @Test
    fun platformMainFrameLoadErrorClearsSplashEvenWhenWatchdogShowsErrorIsFalse() {
        var now = 1_000L
        val watchdog =
            RaftWebViewLoadWatchdog(
                page = "artifact_viewer",
                url = "https://app.raft.build/artifacts/$id",
                clock = { now },
                logger = {},
            )
        assertFalse(raftArtifactViewerHasLoadError(platformLoadError = null, watchdogShowsError = watchdog.showsError))

        // Main-frame network failure (e.g. Android ERROR_CONNECT -6): watchdog moves to Failed
        // (showsError stays false by design and stall deadline never fires).
        now += 4_618L
        watchdog.onLoadFailed()
        assertEquals(RaftWebViewLoadPhase.Failed, watchdog.state.phase)
        assertFalse(watchdog.showsError)

        val loadError =
            raftArtifactViewerHasLoadError(
                platformLoadError = "net::ERR_CONNECTION_REFUSED",
                watchdogShowsError = watchdog.showsError,
            )
        assertTrue(loadError)
        assertEquals(
            RaftArtifactViewerLoadingIndicator.None,
            raftArtifactViewerLoadingIndicator(
                RaftArtifactViewerOpenState(
                    sessionRead = true,
                    documentShown = false,
                    reusesKeptPage = false,
                    loadError = loadError,
                    sinceDocumentReadyMs = null,
                )
            ),
        )
    }
}
