package build.raft.app.shared

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.data.RaftBackgroundConnectionControls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings controls for the no-Play-services background connection (see [RaftBackgroundConnectionService]). */
internal class RaftAndroidBackgroundConnectionControls(context: Context) : RaftBackgroundConnectionControls {
    private val appContext = context.applicationContext

    override fun isOffered(): Boolean = AndroidRealtimeKeepAliveController.noPlayServicesFallbackApplies(appContext)

    override fun isBatteryOptimizationExempt(): Boolean =
        runCatching {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        }.getOrDefault(false)

    private val exemptState = MutableStateFlow(isBatteryOptimizationExempt())

    override val batteryOptimizationExemptState: StateFlow<Boolean> = exemptState.asStateFlow()

    /** Called on activity resume. */
    fun refreshBatteryOptimizationStatus() {
        exemptState.value = isBatteryOptimizationExempt()
    }

    /**
     * User-initiated only (the Settings switch or the battery row). Falls back to the system list of
     * battery optimizations, then to the app's details page, when a ROM hides the direct prompt.
     */
    override fun requestBatteryOptimizationExemption() {
        val packageUri = Uri.parse("package:${appContext.packageName}")
        val candidates =
            listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            )
        for (intent in candidates) {
            val launched =
                try {
                    appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            if (launched) {
                SlockAppLog.i(LOG_TAG, "battery_optimization_request action=${intent.action}")
                return
            }
        }
        SlockAppLog.w(LOG_TAG, "battery_optimization_request_unavailable")
    }

    private companion object {
        const val LOG_TAG = "RaftBackgroundConnection"
    }
}
