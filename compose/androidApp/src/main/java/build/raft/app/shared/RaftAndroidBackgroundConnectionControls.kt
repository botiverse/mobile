package build.raft.app.shared

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftBackgroundConnectionControls
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Settings controls for the no-Play-services background connection (see [RaftBackgroundConnectionService]). */
internal class RaftAndroidBackgroundConnectionControls(
    context: Context,
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val batteryOptimizationExemptProvider: (Context) -> Boolean = ::readBatteryOptimizationExempt,
) : RaftBackgroundConnectionControls {
    private val appContext = context.applicationContext

    override fun isOffered(): Boolean = AndroidRealtimeKeepAliveController.noPlayServicesFallbackApplies(appContext)

    override fun isBatteryOptimizationExempt(): Boolean =
        runCatching {
            batteryOptimizationExemptProvider(appContext)
        }.getOrDefault(false)

    // Avoid calling PowerManager.isIgnoringBatteryOptimizations() synchronously in the constructor
    // on Main (especially on GMS devices where these controls are never offered). Initialized
    // asynchronously on IO when the fallback applies, on resume, or on first observation.
    private val exemptState = MutableStateFlow(false)
    private val exemptStateView = exemptState.asStateFlow()
    private val initialRefreshScheduled = AtomicBoolean(false)

    override val batteryOptimizationExemptState: StateFlow<Boolean>
        get() {
            if (initialRefreshScheduled.compareAndSet(false, true)) {
                refreshScope.launch { refreshBatteryOptimizationStatus() }
            }
            return exemptStateView
        }

    /** Called on activity resume (or cold-start IO when the no-Play-services fallback applies). */
    fun refreshBatteryOptimizationStatus() {
        initialRefreshScheduled.set(true)
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
                RaftAppLog.i(LOG_TAG, "battery_optimization_request action=${intent.action}")
                return
            }
        }
        RaftAppLog.w(LOG_TAG, "battery_optimization_request_unavailable")
    }

    private companion object {
        const val LOG_TAG = "RaftBackgroundConnection"
    }
}

private fun readBatteryOptimizationExempt(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
