package build.raft.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Renderer deaths of the app's WebViews.
 *
 * Android runs all of an app's WebViews in one renderer process. When it dies (a page crashed
 * it, or the system reclaimed it), every WebView on it gets `onRenderProcessGone`, one callback
 * per WebView, and Chromium kills the whole app ("Render process's crash wasn't handled by all
 * associated webviews, triggering application crash") unless every one of them returns true.
 * So every `WebView(...)` the app constructs sets a client that handles it
 * ([RaftAndroidSandboxWebView] for product pages, [RaftDetachedWebViewClient] for the warm-up),
 * and `RaftWebViewClientRenderGoneGuardTest` fails on a construction site it does not know.
 *
 * Each of those callbacks cleans up its own WebView and calls [record]. Callbacks that arrive in
 * the same main-thread turn are one death event (Chromium notifies the WebViews of a renderer in
 * one go), so a death that takes down several WebViews is one [RaftWebViewRendererGoneEvent] and
 * at most one Hands ticket ([RaftWebViewRendererGoneTicketGate]).
 */
object RaftWebViewRendererGone {
    internal const val LOG_TAG = RAFT_WEBVIEW_DIAGNOSTICS_TAG

    /**
     * Files a non-fatal ticket (`message`, `extras`). Installed by the Android host (androidApp
     * `RaftWebViewRendererGoneReporter`, through Hands); null: deaths are only logged.
     */
    @Volatile
    var ticketSink: ((message: String, extras: Map<String, Any?>) -> Unit)? = null

    /** The daily ticket count; the host persists it across processes. In memory until installed. */
    @Volatile
    var dailyStore: RaftWebViewRendererGoneDailyStore = RaftWebViewRendererGoneDailyStore.InMemory()
        set(value) {
            field = value
            gate = null
        }

    /** Test seam: runs the event flush after the current main-thread turn. */
    internal var post: (() -> Unit) -> Unit = { task -> Handler(Looper.getMainLooper()).post(task) }

    /** Test seam: wall clock for the ticket gate. */
    internal var clock: () -> Long = System::currentTimeMillis

    private var gate: RaftWebViewRendererGoneTicketGate? = null
    private var pending: MutableList<RaftWebViewRendererGoneSignal>? = null
    private var pendingProvider: String = "unavailable"

    /**
     * One WebView's `onRenderProcessGone`. Main thread (where WebView callbacks run). Logs the
     * callback and joins it to the current death event; never throws.
     */
    fun record(context: Context?, surface: String, detail: RenderProcessGoneDetail?) {
        runCatching {
            // onRenderProcessGone exists from API 26 only; the check keeps lint's NewApi quiet.
            val api26 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            val signal =
                RaftWebViewRendererGoneSignal(
                    surface = surface,
                    didCrash = api26 && detail != null && detail.didCrash(),
                    rendererPriorityAtExit =
                        if (api26 && detail != null) detail.rendererPriorityAtExit() else RENDERER_PRIORITY_UNKNOWN,
                )
            raftLogWarn(
                LOG_TAG,
                "webview_renderer_gone surface=${signal.surface} didCrash=${signal.didCrash} " +
                    "priority=${raftRendererPriorityLabel(signal.rendererPriorityAtExit)}",
            )
            val batch = pending
            if (batch != null) {
                batch += signal
                return
            }
            pending = mutableListOf(signal)
            pendingProvider = context?.let { RaftAndroidWebViewProvider.label(it) } ?: "unavailable"
            post(::flush)
        }
    }

    /** Ends the current death event: one log line, and the ticket when the gate lets it through. */
    internal fun flush() {
        val batch = pending ?: return
        pending = null
        val event = RaftWebViewRendererGoneEvent.of(batch, pendingProvider)
        val decision = ticketGate().decide(event, clock())
        raftLogWarn(
            LOG_TAG,
            "webview_renderer_gone_event surfaces=${event.surfaces.joinToString(",")} webViews=${event.webViewCount} " +
                "didCrash=${event.didCrash} priority=${raftRendererPriorityLabel(event.rendererPriorityAtExit)} " +
                "ticket=${decision.label}",
        )
        if (decision != RaftWebViewRendererGoneTicketDecision.File) return
        val sink = ticketSink ?: return
        runCatching { sink(raftWebViewRendererGoneTicketMessage(event), raftWebViewRendererGoneTicketExtras(event)) }
            .onFailure { error ->
                raftLogWarn(LOG_TAG, "webview_renderer_gone_ticket_failed class=${error::class.simpleName}")
            }
    }

    private fun ticketGate(): RaftWebViewRendererGoneTicketGate =
        gate ?: RaftWebViewRendererGoneTicketGate(dailyStore = dailyStore).also { gate = it }

    /** Test-only: forget the pending event, the gate and the installed hooks. */
    internal fun resetForTest() {
        pending = null
        pendingProvider = "unavailable"
        ticketSink = null
        dailyStore = RaftWebViewRendererGoneDailyStore.InMemory()
        post = { task -> Handler(Looper.getMainLooper()).post(task) }
        clock = System::currentTimeMillis
    }
}

/**
 * Client of a WebView the app keeps without a page (the startup warm-up). It never navigates
 * and runs no app code; it exists so the app survives the shared renderer's death: it reports
 * the death as [surface], hands the WebView to [onGone] (which drops and destroys it) and
 * returns true.
 */
class RaftDetachedWebViewClient(
    private val surface: String,
    private val onGone: (view: WebView?, detail: RenderProcessGoneDetail?) -> Unit,
) : WebViewClient() {
    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        RaftWebViewRendererGone.record(view?.context, surface, detail)
        runCatching { onGone(view, detail) }
        return true
    }
}

/** One WebView's view of a renderer death. */
internal data class RaftWebViewRendererGoneSignal(
    val surface: String,
    val didCrash: Boolean,
    val rendererPriorityAtExit: Int,
)

/** One renderer death: every WebView that reported it in the same main-thread turn. */
internal data class RaftWebViewRendererGoneEvent(
    /** True when the renderer crashed; false when the system killed it (memory, background). */
    val didCrash: Boolean,
    val rendererPriorityAtExit: Int,
    /** Distinct surfaces, sorted (e.g. `artifact_viewer`, `warmup`). */
    val surfaces: List<String>,
    val webViewCount: Int,
    /** `<package> <version>` of the WebView implementation ([RaftAndroidWebViewProvider.label]). */
    val webViewProvider: String,
) {
    companion object {
        fun of(signals: List<RaftWebViewRendererGoneSignal>, webViewProvider: String): RaftWebViewRendererGoneEvent =
            RaftWebViewRendererGoneEvent(
                didCrash = signals.any { it.didCrash },
                rendererPriorityAtExit = signals.maxOf { it.rendererPriorityAtExit },
                surfaces = signals.map { it.surface }.distinct().sorted(),
                webViewCount = signals.size,
                webViewProvider = webViewProvider,
            )
    }
}

internal enum class RaftWebViewRendererGoneTicketDecision(val label: String) {
    File("filed"),
    /**
     * The system reclaimed the renderer (`didCrash=false`): Android does this to background apps
     * under memory pressure, and the WebViews rebuild themselves. Logged, no ticket.
     */
    SystemReclaim("log_only_system_reclaim"),
    /** A ticket was filed by this process less than a minute ago. */
    RecentTicket("deduped_recent"),
    /** [RaftWebViewRendererGoneTicketGate.maxPerDay] tickets were filed today (UTC). */
    DailyCap("capped_daily"),
}

/** The day ([epochDay], UTC) and how many tickets were filed on it. */
interface RaftWebViewRendererGoneDailyStore {
    fun load(): Pair<Long, Int>?

    fun save(epochDay: Long, count: Int)

    class InMemory : RaftWebViewRendererGoneDailyStore {
        private var value: Pair<Long, Int>? = null

        override fun load(): Pair<Long, Int>? = value

        override fun save(epochDay: Long, count: Int) {
            value = epochDay to count
        }
    }
}

/**
 * Which death events become a ticket: crashes only, at most one per process per
 * [minIntervalMillis] and [maxPerDay] per UTC day (persisted through [dailyStore]).
 */
internal class RaftWebViewRendererGoneTicketGate(
    private val dailyStore: RaftWebViewRendererGoneDailyStore,
    private val minIntervalMillis: Long = 60_000L,
    private val maxPerDay: Int = 5,
) {
    private var lastFiledAtMillis: Long? = null

    fun decide(event: RaftWebViewRendererGoneEvent, nowMillis: Long): RaftWebViewRendererGoneTicketDecision {
        if (!event.didCrash) return RaftWebViewRendererGoneTicketDecision.SystemReclaim
        val last = lastFiledAtMillis
        // A clock set back counts as recent, never as a free slot.
        if (last != null && nowMillis - last < minIntervalMillis) return RaftWebViewRendererGoneTicketDecision.RecentTicket
        val day = Math.floorDiv(nowMillis, MILLIS_PER_DAY)
        val stored = runCatching { dailyStore.load() }.getOrNull()
        val today = if (stored?.first == day) stored.second else 0
        if (today >= maxPerDay) return RaftWebViewRendererGoneTicketDecision.DailyCap
        runCatching { dailyStore.save(day, today + 1) }
        lastFiledAtMillis = nowMillis
        return RaftWebViewRendererGoneTicketDecision.File
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

/** Ticket text: no URL, no page content. */
internal fun raftWebViewRendererGoneTicketMessage(event: RaftWebViewRendererGoneEvent): String =
    "WebView renderer ${if (event.didCrash) "crashed" else "was killed"} " +
        "(${event.webViewCount} WebView${if (event.webViewCount == 1) "" else "s"}: ${event.surfaces.joinToString(", ")}); " +
        "the app kept running and rebuilt its pages. WebView ${event.webViewProvider}."

/** Ticket metadata, in the `crash_*` vocabulary of the system-kill tickets. No URL. */
internal fun raftWebViewRendererGoneTicketExtras(event: RaftWebViewRendererGoneEvent): Map<String, Any?> =
    linkedMapOf(
        "crash_type" to "webview_renderer_gone",
        "crash_fatal" to false,
        "crash_did_crash" to event.didCrash,
        "crash_renderer_priority_at_exit" to event.rendererPriorityAtExit,
        "crash_renderer_priority" to raftRendererPriorityLabel(event.rendererPriorityAtExit),
        "webview_surfaces" to event.surfaces.joinToString(","),
        "webview_count" to event.webViewCount,
        "webview_package" to event.webViewProvider,
    )

/** `RENDERER_PRIORITY_*` as a word. */
internal fun raftRendererPriorityLabel(priority: Int): String =
    when (priority) {
        WebView.RENDERER_PRIORITY_WAIVED -> "waived"
        WebView.RENDERER_PRIORITY_BOUND -> "bound"
        WebView.RENDERER_PRIORITY_IMPORTANT -> "important"
        else -> "unknown"
    }

private const val RENDERER_PRIORITY_UNKNOWN = -1
