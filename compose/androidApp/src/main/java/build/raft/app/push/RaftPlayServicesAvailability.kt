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

    private val checkLock = Any()

    @Volatile
    private var lastLoggedCode: Int? = null

    /** Per-process cache shared with [AndroidPushRegistration]; [refresh] re-checks on app resume. */
    @Volatile
    private var cachedAvailability: RaftFcmAvailability? = null

    @Volatile
    internal var availabilityCodeProvider: (Context) -> Int = { appContext ->
        GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(appContext)
    }

    @Volatile
    internal var debugForceNoGmsProvider: (Context) -> Boolean = { appContext ->
        BuildConfig.DEBUG && File(appContext.filesDir, DEBUG_FORCE_NO_GMS_FILE).exists()
    }

    /**
     * Full FCM availability classification, cached per process so push registration and
     * no-Play-services fallback share one [GoogleApiAvailabilityLight.isGooglePlayServicesAvailable]
     * Binder IPC instead of probing twice back-to-back at cold start.
     */
    fun fcmAvailability(context: Context): RaftFcmAvailability =
        cachedAvailability ?: synchronized(checkLock) {
            cachedAvailability ?: check(context).also { cachedAvailability = it }
        }

    fun isFcmUnavailable(context: Context): Boolean =
        fcmAvailability(context) is RaftFcmAvailability.Unavailable

    /** Re-runs the check (activity resume) and returns the fresh result. */
    fun refresh(context: Context): Boolean =
        synchronized(checkLock) {
            check(context).also { cachedAvailability = it } is RaftFcmAvailability.Unavailable
        }

    private fun check(context: Context): RaftFcmAvailability {
        val appContext = context.applicationContext
        if (debugForceNoGmsProvider(appContext)) {
            logOnce(code = -1, message = "play_services_check forced_unavailable=true source=debug_override")
            return RaftFcmAvailability.Unavailable("debug_override")
        }
        val code =
            runCatching { availabilityCodeProvider(appContext) }
                .getOrElse { error ->
                    val errorType = error::class.simpleName ?: "unknown"
                    RaftAppLog.w(
                        LOG_TAG,
                        "play_services_check_failed errorType=$errorType"
                    )
                    return RaftFcmAvailability.Deferred("play_services_check_failed errorType=$errorType")
                }
        val availability = raftFcmAvailabilityFor(code)
        val unavailable = availability is RaftFcmAvailability.Unavailable
        logOnce(code = code, message = "play_services_check code=$code fcm_unavailable=$unavailable")
        return availability
    }

    internal fun resetForTests() {
        synchronized(checkLock) {
            cachedAvailability = null
            lastLoggedCode = null
        }
        availabilityCodeProvider = { appContext ->
            GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(appContext)
        }
        debugForceNoGmsProvider = { appContext ->
            BuildConfig.DEBUG && File(appContext.filesDir, DEBUG_FORCE_NO_GMS_FILE).exists()
        }
    }

    internal fun isFcmUnavailableCode(code: Int): Boolean = raftFcmAvailabilityFor(code) is RaftFcmAvailability.Unavailable

    private fun logOnce(code: Int, message: String) {
        if (lastLoggedCode == code) return
        lastLoggedCode = code
        RaftAppLog.i(LOG_TAG, message)
    }
}
