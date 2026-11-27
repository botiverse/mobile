package build.raft.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import build.raft.app.label
import build.raft.app.thread.label
import java.util.concurrent.atomic.AtomicBoolean

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
     * Files a non-fatal ticket (`message`, `extras`) and calls `done` once, on any thread: true
     * when Hands accepted it, false when the host gave up (after its own retry). Only an accepted
     * ticket uses the daily quota ([RaftWebViewRendererGoneTicketGate]). Installed by the Android
     * host (androidApp `RaftWebViewRendererGoneReporter`, through Hands); null: deaths are only
     * logged and use no quota.
     */
    @Volatile
    var ticketSink: ((message: String, extras: Map<String, Any?>, done: (accepted: Boolean) -> Unit) -> Unit)? = null

    /** The daily ticket count; the host persists it across processes. In memory until installed. */
    @Volatile
    var dailyStore: RaftWebViewRendererGoneDailyStore = RaftWebViewRendererGoneDailyStore.InMemory()
        set(value) {
            field = value
            gate = null
        }

    /** Application context cached when [RaftWebViewRendererGoneReporter] installs, for WebView provider labels. */
    @Volatile
    var appContext: Context? = null

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
            enqueueSignal(
                context = context,
                signal =
                    RaftWebViewRendererGoneSignal(
                        surface = surface,
                        didCrash = api26 && detail != null && detail.didCrash(),
                        rendererPriorityAtExit =
                            if (api26 && detail != null) detail.rendererPriorityAtExit() else RENDERER_PRIORITY_UNKNOWN,
                    ),
            )
        }
    }

    /**
     * Records a renderer-gone signal from a decoupled WebView container (`compose/web-container`)
     * into the same single-turn coalescer and Hands ticket gate as legacy WebViews. Must be called
     * on the Android main thread (where `WebViewClient.onRenderProcessGone` runs); never throws.
     */
    fun recordSignal(
        surface: String,
        didCrash: Boolean,
        rendererPriorityAtExit: Int = RENDERER_PRIORITY_UNKNOWN,
        context: Context? = appContext,
    ) {
        runCatching {
            enqueueSignal(
                context = context,
                signal =
                    RaftWebViewRendererGoneSignal(
                        surface = surface,
                        didCrash = didCrash,
                        rendererPriorityAtExit = rendererPriorityAtExit,
                    ),
            )
        }
    }

    private fun enqueueSignal(
        context: Context?,
        signal: RaftWebViewRendererGoneSignal,
    ) {
        raftLogWarn(
            LOG_TAG,
            "webview_renderer_gone surface=${signal.surface} didCrash=${signal.didCrash} " +
                "priority=${raftRendererPriorityLabel(signal.rendererPriorityAtExit)}",
        )
        val effectiveContext = context ?: appContext
        val batch = pending
        if (batch != null) {
            batch += signal
            if (pendingProvider == "unavailable" && effectiveContext != null) {
                pendingProvider = RaftAndroidWebViewProvider.label(effectiveContext)
            }
            return
        }
        pending = mutableListOf(signal)
        pendingProvider = effectiveContext?.let { RaftAndroidWebViewProvider.label(it) } ?: "unavailable"
        post(::flush)
    }

    /** Ends the current death event: one log line, and the ticket when the gate lets it through. */
    internal fun flush() {
        val batch = pending ?: return
        pending = null
        val event = RaftWebViewRendererGoneEvent.of(batch, pendingProvider)
        val sink = ticketSink
        val gate = ticketGate()
        // No sink, no ticket: the death is logged and takes nothing from the quota.
        val reservation = if (sink == null) null else gate.reserve(event, clock())
        val decision = reservation?.decision ?: RaftWebViewRendererGoneTicketDecision.NoSink
        raftLogWarn(
            LOG_TAG,
            "webview_renderer_gone_event surfaces=${event.surfaces.joinToString(",")} webViews=${event.webViewCount} " +
                "didCrash=${event.didCrash} priority=${raftRendererPriorityLabel(event.rendererPriorityAtExit)} " +
                "ticket=${decision.label}",
        )
        if (sink == null || reservation == null || decision != RaftWebViewRendererGoneTicketDecision.File) return
        // The sink may call back on any thread, or throw before it does; the reservation settles once.
        val settled = AtomicBoolean(false)
        val done = { accepted: Boolean ->
            if (settled.compareAndSet(false, true)) {
                gate.settle(reservation, accepted)
                if (!accepted) raftLogWarn(LOG_TAG, "webview_renderer_gone_ticket_quota_returned")
            }
        }
        runCatching { sink(raftWebViewRendererGoneTicketMessage(event), raftWebViewRendererGoneTicketExtras(event), done) }
            .onFailure { error ->
                raftLogWarn(LOG_TAG, "webview_renderer_gone_ticket_failed class=${error::class.simpleName}")
                done(false)
            }
    }

    private fun ticketGate(): RaftWebViewRendererGoneTicketGate =
        gate ?: RaftWebViewRendererGoneTicketGate(dailyStore = dailyStore).also { gate = it }

    /** Test-only: forget the pending event, the gate and the installed hooks. */
    internal fun resetForTest() {
        pending = null
        pendingProvider = "unavailable"
        appContext = null
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
    /** A ticket was filed (or is being filed) by this process less than a minute ago. */
    RecentTicket("deduped_recent"),
    /** [RaftWebViewRendererGoneTicketGate.maxPerDay] tickets were accepted (or are in flight) today (UTC). */
    DailyCap("capped_daily"),
    /** No ticket sink is installed: logged only, no quota used. */
    NoSink("log_only_no_sink"),
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
 *
 * The quota is used only by a ticket Hands accepted. [reserve] holds a slot for a ticket being
 * sent (in this process, in memory) so a burst cannot overshoot the limits while uploads are in
 * flight; [settle] then writes the daily count on acceptance, or gives the slot back (the
 * one-minute interval included) on failure. A ticket still in flight when the process dies is
 * not counted: the daily count is of confirmed tickets only.
 */
internal class RaftWebViewRendererGoneTicketGate(
    private val dailyStore: RaftWebViewRendererGoneDailyStore,
    private val minIntervalMillis: Long = 60_000L,
    private val maxPerDay: Int = 5,
) {
    /** One decision; for [RaftWebViewRendererGoneTicketDecision.File], a held slot to [settle]. */
    class Reservation internal constructor(
        val decision: RaftWebViewRendererGoneTicketDecision,
        internal val atMillis: Long,
        internal val epochDay: Long,
        internal val previousLastFiledAtMillis: Long?,
    )

    /** The last accepted or in-flight ticket of this process. */
    private var lastFiledAtMillis: Long? = null
    private val inFlight = mutableListOf<Reservation>()

    @Synchronized
    fun reserve(event: RaftWebViewRendererGoneEvent, nowMillis: Long): Reservation {
        fun no(decision: RaftWebViewRendererGoneTicketDecision) = Reservation(decision, nowMillis, -1L, lastFiledAtMillis)
        if (!event.didCrash) return no(RaftWebViewRendererGoneTicketDecision.SystemReclaim)
        val last = lastFiledAtMillis
        // A clock set back counts as recent, never as a free slot.
        if (last != null && nowMillis - last < minIntervalMillis) return no(RaftWebViewRendererGoneTicketDecision.RecentTicket)
        val day = Math.floorDiv(nowMillis, MILLIS_PER_DAY)
        if (acceptedOn(day) + inFlight.count { it.epochDay == day } >= maxPerDay) {
            return no(RaftWebViewRendererGoneTicketDecision.DailyCap)
        }
        val reservation = Reservation(RaftWebViewRendererGoneTicketDecision.File, nowMillis, day, last)
        inFlight += reservation
        lastFiledAtMillis = nowMillis
        return reservation
    }

    /**
     * Ends [reservation]: [accepted] counts it on its UTC day (unless the stored count already
     * moved to a later day); otherwise its slot is free again and, unless a later ticket was
     * reserved since, so is the one-minute interval. A reservation settles once; repeats and
     * non-[RaftWebViewRendererGoneTicketDecision.File] ones do nothing.
     */
    @Synchronized
    fun settle(reservation: Reservation, accepted: Boolean) {
        if (!inFlight.remove(reservation)) return
        if (accepted) {
            val stored = runCatching { dailyStore.load() }.getOrNull()
            when {
                stored == null || stored.first < reservation.epochDay -> runCatching { dailyStore.save(reservation.epochDay, 1) }
                stored.first == reservation.epochDay -> runCatching { dailyStore.save(reservation.epochDay, stored.second + 1) }
                else -> Unit
            }
        } else if (lastFiledAtMillis == reservation.atMillis) {
            lastFiledAtMillis = reservation.previousLastFiledAtMillis
        }
    }

    private fun acceptedOn(day: Long): Int {
        val stored = runCatching { dailyStore.load() }.getOrNull()
        return if (stored?.first == day) stored.second else 0
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
