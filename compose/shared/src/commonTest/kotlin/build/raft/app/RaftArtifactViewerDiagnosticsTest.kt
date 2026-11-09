package build.raft.app

import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummaryAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun theOpenLineSaysLatestAndNoHostWhenUnknown() {
        assertEquals(
            "artifact_viewer_open artifact=$id version=latest scope=unknown webHost=<none>",
            raftArtifactViewerOpenLogLine(ArtifactLinkTarget(id), scopeKnown = false, webBaseUrl = "not a url"),
        )
    }

    @Test
    fun theSummaryTokenNamesTheAnswer() {
        assertEquals("unavailable", raftArtifactViewerSummaryLogToken(ArtifactSummaryAnswer.Unavailable))
        assertEquals("failed", raftArtifactViewerSummaryLogToken(ArtifactSummaryAnswer.Failed))
        assertEquals("none", raftArtifactViewerSummaryLogToken(null))
    }
}
