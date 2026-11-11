package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * Whether an in-app Raft web page came up (dm #102: a Settings page stayed blank white on iOS 1.22
 * when its scripts never loaded after a DNS / CDN switch; nothing timed out, nothing was logged).
 *
 * A page is **ready** when the platform reported its load finished ([RaftWebViewEvents.onLoadSuccess],
 * or the navigation state reached `!isLoading && progress == 100`) and the document is not blank
 * ([RaftWebViewContentProbeScript], polled until it says so), or when the page posts the host bridge's
 * `ready` message itself. A page that is not ready [RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS] after its
 * load started has **stalled**; a web content / render process that died before the page was ready
 * is **process gone**. Both are terminal for the attempt: the host replaces the page with its error
 * state and Retry starts a new attempt in a new WebView. A process that dies after the page was
 * ready is recovered by the platform host (it rebuilds and reloads), so the watchdog re-arms
 * instead of showing an error.
 */
internal enum class RaftWebViewLoadPhase {
    Loading,
    Ready,
    /** The platform reported a load failure; the host shows its own failure state. */
    Failed,
    Stalled,
    ProcessGone,
}

internal data class RaftWebViewLoadState(
    val phase: RaftWebViewLoadPhase = RaftWebViewLoadPhase.Loading,
    /** Load attempts in this WebView (a re-arm after a later navigation or a recovered crash adds one). */
    val attempt: Int = 1,
    val startedAtMs: Long = 0L,
    /** The platform reported the load finished; readiness now waits for the content probe. */
    val finished: Boolean = false,
    /** The last content probe found a blank document. */
    val probedBlank: Boolean = false,
) {
    val showsError: Boolean
        get() = phase == RaftWebViewLoadPhase.Stalled || phase == RaftWebViewLoadPhase.ProcessGone

    /** Whether the host should keep asking the page for content. */
    val probing: Boolean
        get() = phase == RaftWebViewLoadPhase.Loading && finished
}

internal sealed interface RaftWebViewLoadEvent {
    data object Finished : RaftWebViewLoadEvent
    /** [hasContent] null: the probe could not run or answered nothing readable (fail open: ready). */
    data class Probed(val hasContent: Boolean?) : RaftWebViewLoadEvent
    data object PageReadyMessage : RaftWebViewLoadEvent
    /** A new main-frame load started (navigation state's isLoading rose). */
    data object NavigationStarted : RaftWebViewLoadEvent
    data class Deadline(val attempt: Int) : RaftWebViewLoadEvent
    data class ProcessGone(val reason: String) : RaftWebViewLoadEvent
    data object LoadFailed : RaftWebViewLoadEvent
}

/** One diagnostics line the transition produced (page/host are added by [RaftWebViewLoadWatchdog]). */
internal data class RaftWebViewLoadLogEntry(val warn: Boolean, val message: String)

internal data class RaftWebViewLoadTransition(
    val state: RaftWebViewLoadState,
    val log: RaftWebViewLoadLogEntry? = null,
)

/** The watchdog's state machine, pure (clock passed in) so it can be unit tested. */
internal fun RaftWebViewLoadState.reduce(
    event: RaftWebViewLoadEvent,
    nowMs: Long,
    stallTimeoutMs: Long = RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS,
): RaftWebViewLoadTransition {
    val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
    fun rearm(reason: String, warn: Boolean = false, message: String? = null): RaftWebViewLoadTransition {
        val next = RaftWebViewLoadState(attempt = attempt + 1, startedAtMs = nowMs)
        return RaftWebViewLoadTransition(
            next,
            RaftWebViewLoadLogEntry(warn, message ?: "webview_load_started reason=$reason attempt=${next.attempt}"),
        )
    }
    return when (event) {
        RaftWebViewLoadEvent.Finished ->
            if (phase == RaftWebViewLoadPhase.Loading && !finished) {
                RaftWebViewLoadTransition(
                    copy(finished = true),
                    RaftWebViewLoadLogEntry(false, "webview_load_finished elapsedMs=$elapsed"),
                )
            } else {
                RaftWebViewLoadTransition(this)
            }
        is RaftWebViewLoadEvent.Probed ->
            when {
                !probing -> RaftWebViewLoadTransition(this)
                event.hasContent == false -> RaftWebViewLoadTransition(copy(probedBlank = true))
                else ->
                    RaftWebViewLoadTransition(
                        copy(phase = RaftWebViewLoadPhase.Ready),
                        RaftWebViewLoadLogEntry(
                            false,
                            "webview_load_ready elapsedMs=$elapsed signal=${if (event.hasContent == true) "content" else "probe_unavailable"}",
                        ),
                    )
            }
        RaftWebViewLoadEvent.PageReadyMessage ->
            if (phase == RaftWebViewLoadPhase.Loading) {
                RaftWebViewLoadTransition(
                    copy(phase = RaftWebViewLoadPhase.Ready, finished = true),
                    RaftWebViewLoadLogEntry(false, "webview_load_ready elapsedMs=$elapsed signal=page_message"),
                )
            } else {
                RaftWebViewLoadTransition(this)
            }
        RaftWebViewLoadEvent.NavigationStarted ->
            when (phase) {
                RaftWebViewLoadPhase.Ready, RaftWebViewLoadPhase.Failed -> rearm("navigation")
                // Another document of the same load (a redirect, Android's bootstrap document):
                // same budget, but its finish has to come again.
                RaftWebViewLoadPhase.Loading -> RaftWebViewLoadTransition(copy(finished = false, probedBlank = false))
                else -> RaftWebViewLoadTransition(this)
            }
        is RaftWebViewLoadEvent.Deadline ->
            if (phase == RaftWebViewLoadPhase.Loading && event.attempt == attempt) {
                RaftWebViewLoadTransition(
                    copy(phase = RaftWebViewLoadPhase.Stalled),
                    RaftWebViewLoadLogEntry(
                        true,
                        "webview_load_stalled afterMs=${maxOf(elapsed, stallTimeoutMs)} timeoutMs=$stallTimeoutMs " +
                            "finished=$finished blank=$probedBlank attempt=$attempt",
                    ),
                )
            } else {
                RaftWebViewLoadTransition(this)
            }
        is RaftWebViewLoadEvent.ProcessGone ->
            when (phase) {
                // The platform host rebuilds and reloads the page it showed: guard that load.
                RaftWebViewLoadPhase.Ready ->
                    rearm(
                        "process_gone",
                        warn = true,
                        message = "webview_process_gone reason=${event.reason.forLogToken()} phase=ready recovering=true attempt=${attempt + 1}",
                    )
                RaftWebViewLoadPhase.Loading ->
                    RaftWebViewLoadTransition(
                        copy(phase = RaftWebViewLoadPhase.ProcessGone),
                        RaftWebViewLoadLogEntry(
                            true,
                            "webview_process_gone reason=${event.reason.forLogToken()} phase=loading recovering=false elapsedMs=$elapsed",
                        ),
                    )
                else -> RaftWebViewLoadTransition(this)
            }
        RaftWebViewLoadEvent.LoadFailed ->
            if (phase == RaftWebViewLoadPhase.Loading) {
                RaftWebViewLoadTransition(
                    copy(phase = RaftWebViewLoadPhase.Failed),
                    RaftWebViewLoadLogEntry(true, "webview_load_failed elapsedMs=$elapsed"),
                )
            } else {
                RaftWebViewLoadTransition(this)
            }
    }
}

/**
 * The watchdog of one WebView attempt: created (and armed) together with the WebView it guards,
 * dropped with it. Hosts pass it to [RaftSandboxWebView] / [RaftWebView] (which wire the platform
 * events and run [RaftWebViewLoadWatchdogEffect]) and read [state] to show their error state.
 *
 * [page] is a fixed diagnostics name (`settings_billing`, `artifact_viewer`, ...); of the URL only
 * its host reaches the log ([raftWebViewLogHost]): Settings URLs can carry tokens.
 */
@Stable
internal class RaftWebViewLoadWatchdog(
    page: String,
    url: String,
    retry: Int = 0,
    val stallTimeoutMs: Long = RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS,
    private val clock: () -> Long = raftWebViewMonotonicClock(),
    private val logger: (RaftWebViewLoadLogEntry) -> Unit = ::raftWebViewLoadLog,
) {
    private val prefix = "page=${page.forLogToken()} host=${raftWebViewLogHost(url)}"

    /** The page name of the white-screen lines ([RaftFirstContentWatch]): `webview:<page>`. */
    val whiteScreenPage: String = "webview:${page.forLogToken()}"

    var state: RaftWebViewLoadState by mutableStateOf(RaftWebViewLoadState(startedAtMs = clock()))
        private set

    init {
        logger(RaftWebViewLoadLogEntry(false, "webview_load_started reason=${if (retry > 0) "retry" else "open"} attempt=1 retry=$retry $prefix"))
    }

    val showsError: Boolean get() = state.showsError

    fun onLoadFinished() = dispatch(RaftWebViewLoadEvent.Finished)

    fun onContentProbe(hasContent: Boolean?) = dispatch(RaftWebViewLoadEvent.Probed(hasContent))

    fun onPageReadyMessage() = dispatch(RaftWebViewLoadEvent.PageReadyMessage)

    fun onLoadFailed() = dispatch(RaftWebViewLoadEvent.LoadFailed)

    fun onProcessGone(reason: String) = dispatch(RaftWebViewLoadEvent.ProcessGone(reason))

    fun onDeadline(attempt: Int) = dispatch(RaftWebViewLoadEvent.Deadline(attempt))

    private var lastLoading: Boolean = true

    fun onNavigationState(navigation: RaftWebViewNavigationState) {
        val loading = navigation.isLoading
        if (loading && !lastLoading) dispatch(RaftWebViewLoadEvent.NavigationStarted)
        lastLoading = loading
        if (!loading && navigation.progress >= 100) dispatch(RaftWebViewLoadEvent.Finished)
    }

    /** Time left of the current attempt's stall budget. */
    fun remainingMs(): Long = (stallTimeoutMs - (clock() - state.startedAtMs)).coerceIn(0L, stallTimeoutMs)

    private fun dispatch(event: RaftWebViewLoadEvent) {
        val transition = state.reduce(event, clock(), stallTimeoutMs)
        if (transition.state != state) state = transition.state
        transition.log?.let { logger(it.copy(message = "${it.message} $prefix")) }
    }
}

/**
 * Runs the stall timer and the content probe of [watchdog]. Called by [RaftSandboxWebView] for
 * the WebView it composes, so the timer lives exactly as long as the WebView.
 */
@Composable
internal fun RaftWebViewLoadWatchdogEffect(watchdog: RaftWebViewLoadWatchdog, controller: RaftWebViewController) {
    val state = watchdog.state
    // The same load, seen as a white-screen watch: first_content when it is ready, a
    // white_screen_suspect (stage webview_loading) a second in; the stall stays this watchdog's.
    RaftFirstContentWatch(
        page = watchdog.whiteScreenPage,
        stage = raftWhiteScreenStage(webViewLoading = state.phase != RaftWebViewLoadPhase.Ready),
    )
    LaunchedEffect(watchdog, state.attempt) {
        if (watchdog.state.phase != RaftWebViewLoadPhase.Loading) return@LaunchedEffect
        delay(watchdog.remainingMs())
        watchdog.onDeadline(state.attempt)
    }
    LaunchedEffect(watchdog, state.attempt, state.probing) {
        if (!state.probing) return@LaunchedEffect
        while (true) {
            // Unbound (no native view yet): nothing to ask; the next round asks again.
            controller.probeDocumentContent()
            delay(RAFT_WEB_VIEW_CONTENT_PROBE_INTERVAL_MS)
        }
    }
}

internal const val RAFT_WEB_VIEW_LOAD_STALL_TIMEOUT_MS = 15_000L
internal const val RAFT_WEB_VIEW_CONTENT_PROBE_INTERVAL_MS = 500L
internal const val RAFT_WEB_VIEW_LOAD_DIAGNOSTICS_TAG = "RaftWebViewLoad"

/**
 * Whether the top-level document shows anything: rendered text, or a media / form element. A
 * page whose bundle never ran has only its empty mount point and `<script>` tags (not rendered,
 * so not in `innerText`). Answers `'1'` / `'0'` as a string (the one result type every engine
 * returns the same way); a script error answers `'1'` (fail open: never an error state because
 * the probe itself broke).
 */
internal const val RaftWebViewContentProbeScript =
    "(function(){try{var b=document.body;if(!b)return '0';" +
        "if((b.innerText||'').trim().length>0)return '1';" +
        "return b.querySelector('img,svg,canvas,video,iframe,input,button,textarea,select')?'1':'0'}catch(e){return '1'}})()"

/** The probe's answer as the engine returned it (`1`, `"1"`, ...): true / false, or null when unreadable. */
internal fun raftWebViewDecodeContentProbe(raw: String?): Boolean? =
    when (raw?.trim()?.removeSurrounding("\"")) {
        "1" -> true
        "0" -> false
        else -> null
    }

/** The host of [url] for a log line, lower case; never the path, query, fragment or user info. */
internal fun raftWebViewLogHost(url: String): String {
    val afterScheme = url.trim().substringAfter("://", "")
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    val host = authority.lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' || it == '[' || it == ']' }
    return host.take(80).ifEmpty { "-" }
}

/** [url] without its query and fragment (tokens), for a log line. */
internal fun raftWebViewUrlForLog(url: String): String =
    url.trim().substringBefore('#').substringBefore('?').let { stripped ->
        val scheme = stripped.substringBefore("://", "")
        if (scheme.isEmpty()) return@let raftWebViewLogHost(stripped)
        val rest = stripped.substringAfter("://")
        "$scheme://${raftWebViewLogHost(stripped)}${rest.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }}"
    }.take(160)

private fun String.forLogToken(): String =
    trim().replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64).ifEmpty { "-" }

private fun raftWebViewLoadLog(entry: RaftWebViewLoadLogEntry) {
    if (entry.warn) {
        raftLogWarn(RAFT_WEB_VIEW_LOAD_DIAGNOSTICS_TAG, entry.message)
    } else {
        raftLogInfo(RAFT_WEB_VIEW_LOAD_DIAGNOSTICS_TAG, entry.message)
    }
}

private fun raftWebViewMonotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
