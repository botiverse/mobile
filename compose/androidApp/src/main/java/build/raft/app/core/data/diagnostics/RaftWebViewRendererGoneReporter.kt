package build.raft.app.core.data.diagnostics

import android.content.Context
import android.content.SharedPreferences
import build.hands.update.HandsFeedback
import build.raft.app.BuildConfig
import build.raft.app.RaftWebViewRendererGone
import build.raft.app.RaftWebViewRendererGoneDailyStore
import build.raft.app.data.RaftServicesPrivacyGate
import build.raft.app.data.RaftTelemetryPrivacy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Files the non-fatal Hands ticket for a WebView renderer death (shared `RaftWebViewRendererGone`
 * decides which deaths get one: crashes only, one per process per minute, a few a day). Same
 * transport as the system-kill tickets ([AndroidSystemKillReporter]): a [HandsFeedback] `crash`
 * ticket whose `crash_type` (`webview_renderer_gone`) and `crash_fatal=false` tell it apart.
 *
 * The shared gate counts a ticket against its quota only when this reporter says Hands accepted
 * it ([submitRendererGoneTicket]); a failure gives the quota back.
 */
internal object RaftWebViewRendererGoneReporter {
    private const val Tag = "SlockWebViewRendererGone"
    private const val PreferencesName = "raft-webview-renderer-gone"
    private const val DayKey = "ticket-epoch-day"
    private const val CountKey = "ticket-count"
    private val installed = AtomicBoolean(false)
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(context: Context, baseUrl: String, appSlug: String, channel: String?, clientKey: String?) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val feedback by lazy {
            HandsFeedback(
                context = appContext,
                baseUrl = baseUrl,
                appSlug = appSlug,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                channel = channel,
                clientKey = clientKey,
            )
        }
        RaftWebViewRendererGone.appContext = appContext
        RaftWebViewRendererGone.dailyStore =
            PreferencesDailyStore(appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE))
        RaftWebViewRendererGone.ticketSink = ticketSink@{ message, extras, done ->
            // RAFT_PRIVACY_MODE: sent only while every signed-in server answers privacy mode off;
            // otherwise nothing leaves the device and the quota is given back.
            if (!RaftServicesPrivacyGate.allowsAutomatic()) {
                RaftAndroidLocalDiagnostics.recordLog(
                    "info",
                    Tag,
                    "renderer_gone_ticket_skipped reason=privacy_mode mode=${RaftTelemetryPrivacy.acrossSignedInServers.value.name.lowercase()}",
                )
                done(false)
                return@ticketSink
            }
            // One id for every attempt of this ticket. It is also the Hands `submission_id`, so a
            // retry after a lost response gets the first ticket back instead of filing a second.
            val eventId = UUID.randomUUID().toString()
            val withId = extras + ("crash_exit_event_id" to eventId)
            uploadScope.launch {
                val accepted =
                    submitRendererGoneTicket(
                        submit = { feedback.submit(message = message, kind = "crash", extras = withId, submissionId = eventId) },
                        log = { level, line -> RaftAndroidLocalDiagnostics.recordLog(level, Tag, line) },
                    )
                done(accepted)
            }
        }
    }

    /** Today's ticket count, across processes (the daily cap must hold through a crash loop). */
    internal class PreferencesDailyStore(private val preferences: SharedPreferences) : RaftWebViewRendererGoneDailyStore {
        override fun load(): Pair<Long, Int>? {
            if (!preferences.contains(DayKey)) return null
            return preferences.getLong(DayKey, 0L) to preferences.getInt(CountKey, 0)
        }

        override fun save(epochDay: Long, count: Int) {
            preferences.edit().putLong(DayKey, epochDay).putInt(CountKey, count).apply()
        }
    }
}

/** The wait before the one retry of a renderer-gone ticket. */
internal const val RendererGoneTicketRetryDelayMillis = 30_000L

/**
 * Submits one renderer-gone ticket; true when Hands accepted it. [HandsFeedback.submit] has no
 * offline queue (hands-android-sdk 0.13.2), so a retryable failure (network, 5xx, 401/403/404/408/429)
 * is retried once after [retryDelayMillis], with the same [submit] (same extras, same
 * `crash_exit_event_id`, sent as the Hands `submission_id`, so Hands answers it with the first ticket); a permanent rejection ([isPermanentHandsRejection]) is not retried.
 * Nothing is kept for a later launch.
 */
internal suspend fun submitRendererGoneTicket(
    submit: suspend () -> String,
    log: (level: String, line: String) -> Unit,
    retryDelayMillis: Long = RendererGoneTicketRetryDelayMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    for (attempt in 1..2) {
        val result = runCatching { submit() }
        val ticket = result.getOrNull()
        if (ticket != null) {
            log("info", "webview_renderer_gone_ticket ticket=${ticket.take(8)} attempt=$attempt")
            return true
        }
        val error = result.exceptionOrNull()
        val retry = attempt == 1 && error != null && !isPermanentHandsRejection(error)
        log(
            "warn",
            "webview_renderer_gone_ticket_failed class=${error?.javaClass?.name} attempt=$attempt " +
                "retry=${if (retry) "once" else "none"}",
        )
        if (!retry) return false
        wait(retryDelayMillis)
    }
    return false
}
