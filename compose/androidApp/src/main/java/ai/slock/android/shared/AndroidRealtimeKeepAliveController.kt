package ai.slock.android.shared

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger

internal object AndroidRealtimeKeepAliveController {
    private const val LOG_TAG = "SlockRealtimeKeepAlive"

    fun start(context: Context, source: String = "unknown") {
        val appContext = context.applicationContext
        if (!AndroidRealtimeSessionAvailability.hasStoredRealtimeSession(appContext)) {
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("missing_session")
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "start_skipped_no_session source=$source"
            )
            return
        }
        val intent =
            Intent(appContext, SlockRealtimeKeepAliveService::class.java)
                .setAction(SlockRealtimeKeepAliveService.ACTION_START)
                .putExtra(SlockRealtimeKeepAliveService.EXTRA_SOURCE, source)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "start_failed source=$source message=${error.message.orEmpty()}"
            )
        }
    }

    fun stop(context: Context, reason: String = "unknown") {
        val appContext = context.applicationContext
        val intent =
            Intent(appContext, SlockRealtimeKeepAliveService::class.java)
                .setAction(SlockRealtimeKeepAliveService.ACTION_STOP)
                .putExtra(SlockRealtimeKeepAliveService.EXTRA_REASON, reason)
        runCatching {
            appContext.startService(intent)
        }.onFailure { error ->
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "stop_failed reason=$reason message=${error.message.orEmpty()}"
            )
        }
    }
}
