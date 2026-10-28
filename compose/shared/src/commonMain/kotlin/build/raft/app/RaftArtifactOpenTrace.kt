package build.raft.app

import build.raft.app.data.defaultNowMillis
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Timing of one Artifact open, from the tap to the content on screen, one logcat line per
 * segment (tag [RAFT_ARTIFACT_OPEN_TRACE_TAG]):
 * `artifact_open seg=<segment> open=<n> sinceTapMs=<ms> epochMs=<wall clock> [detail]`.
 *
 * Segments: `tap` (an opener's tap), `page_shown` (the viewer page's first composition),
 * `webview_created`, `webview_page_started`, `webview_page_finished` (the web viewer document),
 * `content_frame` (the content frame is in the document), `content_paint` (the content frame
 * has drawn the Artifact) and, for a cached or card header, `header_painted`.
 *
 * Never logs an Artifact's title, a URL's query or any credential: ids and timings only.
 */
internal object RaftArtifactOpenTrace {
    private var openSequence = 0
    private var currentOpen = 0
    private var tapMark: TimeMark? = null

    /** A new open starts at an opener's tap ([source]: `chat_card`, `activity_row`, `chip`). */
    fun tap(source: String, artifactId: String) {
        openSequence += 1
        currentOpen = openSequence
        tapMark = TimeSource.Monotonic.markNow()
        emit("tap", "source=$source artifact=${artifactId.traceSafeId()}")
    }

    fun mark(segment: String, detail: String = "") {
        if (tapMark == null) return
        emit(segment, detail)
    }

    /** Elapsed time since the current open's tap, or null when no open is being traced. */
    fun sinceTapMillis(): Long? = tapMark?.elapsedNow()?.inWholeMilliseconds

    private fun emit(segment: String, detail: String) {
        val since = sinceTapMillis() ?: 0L
        val suffix = if (detail.isBlank()) "" else " $detail"
        raftTraceToPlatformLog(
            RAFT_ARTIFACT_OPEN_TRACE_TAG,
            "artifact_open seg=$segment open=$currentOpen sinceTapMs=$since epochMs=${defaultNowMillis()}$suffix",
        )
    }

    private fun String.traceSafeId(): String = filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(48)
}

internal const val RAFT_ARTIFACT_OPEN_TRACE_TAG = "RaftArtifactOpen"
