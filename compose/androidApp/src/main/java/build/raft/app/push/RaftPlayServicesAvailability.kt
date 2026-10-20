package build.raft.app.push

import android.content.Context
import build.raft.app.BuildConfig
import build.raft.app.core.data.diagnostics.SlockAppLog
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import java.io.File

/**
 * Whether this device can receive FCM at all, from Google Play services' availability check.
 *
 * Only SERVICE_MISSING (1), SERVICE_DISABLED (3) and SERVICE_INVALID (9) mean "no Play services"
 * (Huawei without GMS, HarmonyOS NEXT's Android compatibility layer). Every other code
 * (SERVICE_UPDATING, SERVICE_VERSION_UPDATE_REQUIRED, a failed check, ...) is transient or
 * user-fixable and counts as "Play services present": the no-GMS fallbacks never run on a GMS device.
 *
 * NOTE: PR #3201 (push re-registration) adds the same 1/3/9 classification as `raftFcmAvailabilityFor`
 * in AndroidPushRegistration.kt. When both land, [isFcmUnavailableCode] can delegate to it; this file
 * deliberately does not touch AndroidPushRegistration.kt so the two merge without conflicts.
 */
internal object RaftPlayServicesAvailability {
    private const val LOG_TAG = "RaftPlayServices"

    /** Debug-only switch for testing the no-GMS path on a GMS emulator: `run-as <pkg> touch files/<name>`. */
    internal const val DEBUG_FORCE_NO_GMS_FILE = "raft_debug_force_no_gms"

    @Volatile
    private var lastLoggedCode: Int? = null

    fun isFcmUnavailable(context: Context): Boolean {
        val appContext = context.applicationContext
        if (BuildConfig.DEBUG && File(appContext.filesDir, DEBUG_FORCE_NO_GMS_FILE).exists()) {
            logOnce(code = -1, message = "play_services_check forced_unavailable=true source=debug_override")
            return true
        }
        val code =
            runCatching { GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(appContext) }
                .getOrElse { error ->
                    SlockAppLog.w(
                        LOG_TAG,
                        "play_services_check_failed errorType=${error::class.simpleName ?: "unknown"}"
                    )
                    return false
                }
        val unavailable = isFcmUnavailableCode(code)
        logOnce(code = code, message = "play_services_check code=$code fcm_unavailable=$unavailable")
        return unavailable
    }

    internal fun isFcmUnavailableCode(code: Int): Boolean =
        when (code) {
            ConnectionResult.SERVICE_MISSING,
            ConnectionResult.SERVICE_DISABLED,
            ConnectionResult.SERVICE_INVALID -> true
            else -> false
        }

    private fun logOnce(code: Int, message: String) {
        if (lastLoggedCode == code) return
        lastLoggedCode = code
        SlockAppLog.i(LOG_TAG, message)
    }
}
