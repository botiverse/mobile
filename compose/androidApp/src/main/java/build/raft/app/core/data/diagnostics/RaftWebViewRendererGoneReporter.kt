package build.raft.app.core.data.diagnostics

import android.content.Context
import android.content.SharedPreferences
import build.hands.update.HandsFeedback
import build.raft.app.BuildConfig
import build.raft.app.RaftWebViewRendererGone
import build.raft.app.RaftWebViewRendererGoneDailyStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Files the non-fatal Hands ticket for a WebView renderer death (shared `RaftWebViewRendererGone`
 * decides which deaths get one: crashes only, one per process per minute, a few a day). Same
 * transport as the system-kill tickets ([AndroidSystemKillReporter]): a [HandsFeedback] `crash`
 * ticket whose `crash_type` (`webview_renderer_gone`) and `crash_fatal=false` tell it apart.
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
        RaftWebViewRendererGone.dailyStore =
            PreferencesDailyStore(appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE))
        RaftWebViewRendererGone.ticketSink = { message, extras ->
            uploadScope.launch {
                runCatching { feedback.submit(message = message, kind = "crash", extras = extras) }
                    .onSuccess { ticket ->
                        RaftAndroidLocalDiagnostics.recordLog("info", Tag, "webview_renderer_gone_ticket ticket=${ticket.take(8)}")
                    }
                    .onFailure { error ->
                        RaftAndroidLocalDiagnostics.recordLog(
                            "warn",
                            Tag,
                            "webview_renderer_gone_ticket_failed class=${error.javaClass.name}",
                        )
                    }
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
