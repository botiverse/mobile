package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * White-screen diagnostics (Hands f0be77a8: "we have many white screens, why no logs?").
 *
 * Every watched surface (a window, a page route, an in-app web page) writes one `first_frame` /
 * `first_content` info line with the milliseconds from its open to its first frame of real
 * content, and, when that frame has not come [RAFT_WHITE_SCREEN_SUSPECT_MS] after the open, one
 * warn line `white_screen_suspect page=.. ms=.. stage=..` naming what it is still waiting on. Both
 * reach the feedback zip (Android JSONL, iOS diagnostics) and are searchable in Hands. Lines carry
 * fixed page names and stage tokens only, never ids, URLs or content.
 */
const val RAFT_WHITE_SCREEN_SUSPECT_MS: Long = 1_000L

/** Diagnostics tag of the white-screen lines. */
const val RAFT_WHITE_SCREEN_TAG: String = "RaftWhiteScreen"

/** What a surface with no real content yet is waiting on, most fundamental first. */
enum class RaftWhiteScreenStage(val wireName: String) {
    /** The window is not shown (stopped, not yet started, or its view is not attached / visible). */
    WindowNotVisible("window_not_visible"),

    /** The stored session / auth gate is still restoring. */
    Session("session"),

    /** An in-app web page has not reported it loaded with content. */
    WebViewLoading("webview_loading"),

    /** The page's data (its first snapshot) is not there yet: it shows its loading state. */
    Data("data"),

    /** Everything is ready but no frame has been drawn. */
    NoDraw("no_draw"),
}

/**
 * The stage a surface is stuck at, or null when its real content can show. Checked in the order of
 * [RaftWhiteScreenStage]: a hidden window explains everything after it.
 */
fun raftWhiteScreenStage(
    windowVisible: Boolean = true,
    sessionReady: Boolean = true,
    webViewLoading: Boolean = false,
    dataReady: Boolean = true,
    drawn: Boolean = true,
): RaftWhiteScreenStage? =
    when {
        !windowVisible -> RaftWhiteScreenStage.WindowNotVisible
        !sessionReady -> RaftWhiteScreenStage.Session
        webViewLoading -> RaftWhiteScreenStage.WebViewLoading
        !dataReady -> RaftWhiteScreenStage.Data
        !drawn -> RaftWhiteScreenStage.NoDraw
        else -> null
    }

/** `first_frame` (a window's first draw) or `first_content` (a page's first real content frame). */
fun raftFirstFrameLine(event: String, page: String, ms: Long, scope: String): String =
    "$event page=${page.raftWhiteScreenToken()} ms=${ms.coerceAtLeast(0L)} scope=$scope"

/** The warn line of a surface with no real content [ms] after its open. */
fun raftWhiteScreenSuspectLine(page: String, ms: Long, stage: RaftWhiteScreenStage, scope: String): String =
    "white_screen_suspect page=${page.raftWhiteScreenToken()} ms=${ms.coerceAtLeast(0L)} stage=${stage.wireName} scope=$scope"

internal fun String.raftWhiteScreenToken(): String =
    trim().replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(64).ifEmpty { "-" }

/**
 * Watches one page route from its first composition: [stage] is what it still waits on, null once
 * it shows real content (not a placeholder). Logs `first_content` on the first frame after [stage]
 * turned null and `white_screen_suspect` once if that has not happened [suspectAfterMs] after the
 * open, with the stage at that moment. Keyed on [page]: a new route is a new watch.
 */
@Composable
internal fun RaftFirstContentWatch(
    page: String,
    stage: RaftWhiteScreenStage?,
    suspectAfterMs: Long = RAFT_WHITE_SCREEN_SUSPECT_MS,
    log: (warn: Boolean, message: String) -> Unit = ::raftWhiteScreenLog,
) {
    val opened = remember(page) { TimeSource.Monotonic.markNow() }
    var contentShown by remember(page) { mutableStateOf(false) }
    val latestStage by rememberUpdatedState(stage)
    val latestLog by rememberUpdatedState(log)
    LaunchedEffect(page, stage == null) {
        if (stage != null || contentShown) return@LaunchedEffect
        withFrameNanos {}
        contentShown = true
        latestLog(false, raftFirstFrameLine("first_content", page, opened.elapsedNow().inWholeMilliseconds, scope = "page"))
    }
    LaunchedEffect(page) {
        delay(suspectAfterMs)
        val waiting = latestStage
        if (!contentShown && waiting != null) {
            latestLog(true, raftWhiteScreenSuspectLine(page, opened.elapsedNow().inWholeMilliseconds, waiting, scope = "page"))
        }
    }
}

/**
 * The app shell's stage: restoring the session with nothing cached to show is [RaftWhiteScreenStage.Session];
 * Home still without its first snapshot is [RaftWhiteScreenStage.Data]; other tabs own their loading
 * state and count as shown once the shell is.
 */
internal fun appShellWhiteScreenStage(
    restoring: Boolean,
    cachedHomePreview: Boolean,
    selectedTab: String,
    homeContentReady: Boolean,
): RaftWhiteScreenStage? =
    raftWhiteScreenStage(
        sessionReady = !restoring || cachedHomePreview,
        dataReady = retainedAppShellSelectedTab(selectedTab) != "home" || homeContentReady,
    )

private fun raftWhiteScreenLog(warn: Boolean, message: String) {
    if (warn) raftLogWarn(RAFT_WHITE_SCREEN_TAG, message) else raftLogInfo(RAFT_WHITE_SCREEN_TAG, message)
}
