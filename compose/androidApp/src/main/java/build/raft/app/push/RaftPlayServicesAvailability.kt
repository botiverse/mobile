package build.raft.app.push

import android.content.Context
import build.raft.app.BuildConfig
import build.raft.app.core.data.diagnostics.RaftAppLog
import com.google.android.gms.common.GoogleApiAvailabilityLight
import java.io.File

/**
 * Whether this device can receive FCM at all, from Google Play services' availability check.
 *
 * Only SERVICE_MISSING (1), SERVICE_DISABLED (3) and SERVICE_INVALID (9) mean "no Play services"
 * (Huawei without GMS, HarmonyOS NEXT's Android compatibility layer). Every other code
 * (SERVICE_UPDATING, SERVICE_VERSION_UPDATE_REQUIRED, a failed check, ...) is transient or
 * user-fixable and counts as "Play services present": the no-GMS fallbacks never run on a GMS device.
 * The classification is [raftFcmAvailabilityFor]'s, shared with push registration.
 */
internal object RaftPlayServicesAvailability {
    private const val LOG_TAG = "RaftPlayServices"

    /** Debug-only switch for testing the no-GMS path on a GMS emulator: `run-as <pkg> touch files/<name>`. */
    internal const val DEBUG_FORCE_NO_GMS_FILE = "raft_debug_force_no_gms"

    @Volatile
    private var lastLoggedCode: Int? = null

    /** Per-process cache; [refresh] re-checks on app resume (Play services can be enabled meanwhile). */
    @Volatile
    private var cachedUnavailable: Boolean? = null

    fun isFcmUnavailable(context: Context): Boolean =
        cachedUnavailable ?: check(context).also { cachedUnavailable = it }

    /** Re-runs the check (activity resume) and returns the fresh result. */
    fun refresh(context: Context): Boolean = check(context).also { cachedUnavailable = it }

    private fun check(context: Context): Boolean {
        val appContext = context.applicationContext
        if (BuildConfig.DEBUG && File(appContext.filesDir, DEBUG_FORCE_NO_GMS_FILE).exists()) {
            logOnce(code = -1, message = "play_services_check forced_unavailable=true source=debug_override")
            return true
        }
        val code =
            runCatching { GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(appContext) }
                .getOrElse { error ->
                    RaftAppLog.w(
                        LOG_TAG,
                        "play_services_check_failed errorType=${error::class.simpleName ?: "unknown"}"
                    )
                    return false
                }
        val unavailable = isFcmUnavailableCode(code)
        logOnce(code = code, message = "play_services_check code=$code fcm_unavailable=$unavailable")
        return unavailable
    }

    internal fun isFcmUnavailableCode(code: Int): Boolean = raftFcmAvailabilityFor(code) is RaftFcmAvailability.Unavailable

    private fun logOnce(code: Int, message: String) {
        if (lastLoggedCode == code) return
        lastLoggedCode = code
        RaftAppLog.i(LOG_TAG, message)
    }
}
