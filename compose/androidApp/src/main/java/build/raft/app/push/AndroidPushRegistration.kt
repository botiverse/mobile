package build.raft.app.push

import android.content.Context
import build.raft.app.BuildConfig
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftAndroidPushRegistrationBridge
import com.google.android.gms.common.ConnectionResult
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android host half of RFC 045 push registration. The shared bridge owns
 * persistence, auth-gated upsert, dedupe and logout unbind; this file only
 * supplies the Firebase Installation ID and the installed application id.
 */
internal object AndroidPushRegistration {
    private const val TAG = "SlockFCM"
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()

    /** Non-null once this process decided not to register: "fcm_unavailable" or "fcm_deferred". */
    @Volatile
    private var registrationSkippedReason: String? = null

    private var startInFlight = false
    private var pendingFirebaseInstallationId: String? = null

    @Volatile
    internal var startupDispatcherForTests: CoroutineDispatcher? = null

    @Volatile
    internal var reportPermissionForTests: ((Context) -> Unit)? = null

    @Volatile
    internal var installBridgeForTests: ((candidateInstallationId: String, fcmUnavailableReason: String?) -> Unit)? = null

    @Volatile
    internal var updateRegistrationForTests: ((firebaseInstallationId: String, applicationId: String, appVersion: String) -> Unit)? = null

    @Volatile
    internal var registerFirebaseForTests: ((Context) -> Unit)? = null

    fun start(context: Context) {
        startAsync(context)
    }

    internal fun startAsync(context: Context): Job? {
        val appContext = context.applicationContext
        synchronized(stateLock) {
            if (startInFlight) return null
            startInFlight = true
        }
        // Keep Play services, NotificationManager/SharedPreferences, and Firebase initialization
        // off the main thread during Application.onCreate. Any FID arriving while startup is in
        // flight is buffered and delivered once the bridge is installed.
        return startupScope.launch(startupDispatcherForTests ?: EmptyCoroutineContext) {
            startSync(appContext)
        }
    }

    internal fun startSync(context: Context) {
        val appContext = context.applicationContext
        var installed = false
        var registerFirebase = false
        try {
            val availability = RaftPlayServicesAvailability.fcmAvailability(appContext)
            val unavailableReason = (availability as? RaftFcmAvailability.Unavailable)?.reason
            registrationSkippedReason =
                when (availability) {
                    RaftFcmAvailability.Available -> null
                    is RaftFcmAvailability.Unavailable -> "fcm_unavailable"
                    is RaftFcmAvailability.Deferred -> "fcm_deferred"
                }
            // Before the first upsert, so the registration starts with the real OS permission.
            val permissionReporter = reportPermissionForTests
            if (permissionReporter != null) {
                permissionReporter(appContext)
            } else {
                build.raft.app.shared.RaftNotificationPermissionReporter.report(appContext)
            }
            installed =
                runCatching {
                    val candidateId = UUID.randomUUID().toString()
                    val installer = installBridgeForTests
                    if (installer != null) {
                        installer(candidateId, unavailableReason)
                    } else {
                        RaftAndroidPushRegistrationBridge.install(
                            candidateInstallationId = candidateId,
                            fcmUnavailableReason = unavailableReason
                        )
                    }
                }.onFailure { error ->
                    RaftAppLog.w(TAG, "push_registration_install_failed errorType=${error::class.simpleName ?: "unknown"}")
                }.isSuccess
            if (!installed) return
            if (unavailableReason != null) {
                // Without Google Play services FCM cannot deliver to this device (Huawei without GMS,
                // including HarmonyOS's Android compatibility layer). The Firebase Installation ID is still
                // readable over plain HTTPS, but uploading it would register a token FCM rejects, so nothing
                // is registered; the realtime path keeps posting this device's notifications.
                RaftAppLog.w(TAG, "fcm_unavailable reason=$unavailableReason")
                return
            }
            if (availability is RaftFcmAvailability.Deferred) {
                // Google Play services is present but not usable right now (updating, update required, a
                // transient check failure). FCM may well work in a later process, so nothing is cleared and the
                // provider is not marked unavailable: the binding, pending payload and delivery-status answer
                // stay; this process only skips registering.
                RaftAppLog.w(TAG, "fcm_deferred reason=${availability.reason}")
                return
            }
            registerFirebase = true
        } finally {
            try {
                if (installed) {
                    // Drain buffered FIDs while startInFlight stays true so a newer concurrent
                    // onFirebaseInstallationId cannot overtake an older buffered FID.
                    while (true) {
                        val nextFid =
                            synchronized(stateLock) {
                                val buffered = pendingFirebaseInstallationId
                                if (buffered == null) {
                                    startInFlight = false
                                    null
                                } else {
                                    pendingFirebaseInstallationId = null
                                    buffered
                                }
                            } ?: break
                        deliverFirebaseInstallationId(appContext, nextFid)
                    }
                }
            } finally {
                synchronized(stateLock) {
                    startInFlight = false
                    pendingFirebaseInstallationId = null
                }
            }
        }
        if (!registerFirebase) return
        val firebaseRegistrar = registerFirebaseForTests
        if (firebaseRegistrar != null) {
            firebaseRegistrar(appContext)
            return
        }
        // Registration by Firebase Installation ID: the FID arrives through
        // FirebaseMessagingService.onRegistered, now and whenever it changes.
        runCatching {
            FirebaseMessaging.getInstance().register()
            // onRegistered is not guaranteed to fire again for an FID the SDK
            // already registered (e.g. an upgrade from a build that ignored it),
            // so also read the current FID directly. The shared coordinator
            // dedupes, so delivering the same FID twice costs nothing.
            FirebaseInstallations.getInstance().id
                .addOnSuccessListener { fid ->
                    if (!fid.isNullOrBlank()) onFirebaseInstallationId(appContext, fid)
                }
                .addOnFailureListener { error ->
                    RaftAppLog.w(TAG, "fcm_installation_id_unavailable errorType=${error::class.simpleName ?: "unknown"}")
                }
        }.onFailure { error ->
            // Builds without google-services.json have no Firebase app.
            RaftAppLog.i(TAG, "fcm_register_unavailable errorType=${error::class.simpleName ?: "unknown"}")
        }
    }

    fun onFirebaseInstallationId(context: Context, firebaseInstallationId: String) {
        synchronized(stateLock) {
            if (startInFlight) {
                pendingFirebaseInstallationId = firebaseInstallationId
                return
            }
        }
        deliverFirebaseInstallationId(context, firebaseInstallationId)
    }

    /**
     * Re-triggers Firebase registration / FID delivery after the server's reachability probe
     * answered `not_sent` with `reRegister == true` (e.g. `token_unregistered`). Safe no-op when
     * FCM is unavailable or deferred on this device.
     */
    fun requestReRegister(context: Context, reason: String) {
        val appContext = context.applicationContext
        if (registrationSkippedReason != null) return
        RaftAppLog.w(TAG, "fcm_reregister_requested reason=$reason")
        val firebaseRegistrar = registerFirebaseForTests
        if (firebaseRegistrar != null) {
            firebaseRegistrar(appContext)
            return
        }
        runCatching {
            FirebaseMessaging.getInstance().register()
            FirebaseInstallations.getInstance().id
                .addOnSuccessListener { fid ->
                    if (!fid.isNullOrBlank()) onFirebaseInstallationId(appContext, fid)
                }
                .addOnFailureListener { error ->
                    RaftAppLog.w(TAG, "fcm_reregister_fid_unavailable errorType=${error::class.simpleName ?: "unknown"}")
                }
        }.onFailure { error ->
            RaftAppLog.i(TAG, "fcm_reregister_unavailable errorType=${error::class.simpleName ?: "unknown"}")
        }
    }

    private fun deliverFirebaseInstallationId(context: Context, firebaseInstallationId: String) {
        registrationSkippedReason?.let { skipped ->
            RaftAppLog.w(TAG, "fcm_installation_id_ignored reason=$skipped")
            return
        }
        val updater = updateRegistrationForTests
        if (updater != null) {
            updater(firebaseInstallationId, context.packageName, BuildConfig.VERSION_NAME)
            return
        }
        RaftAndroidPushRegistrationBridge.updateRegistration(
            firebaseInstallationId = firebaseInstallationId,
            applicationId = context.packageName,
            appVersion = BuildConfig.VERSION_NAME
        )
    }

    internal fun resetForTests() {
        synchronized(stateLock) {
            startInFlight = false
            pendingFirebaseInstallationId = null
        }
        registrationSkippedReason = null
        startupDispatcherForTests = null
        reportPermissionForTests = null
        installBridgeForTests = null
        updateRegistrationForTests = null
        registerFirebaseForTests = null
    }
}

/** Whether this device can receive FCM, from Google Play services' availability check. */
internal sealed interface RaftFcmAvailability {
    data object Available : RaftFcmAvailability

    /** No usable Google Play services on this device: register nothing and drop any earlier registration. */
    data class Unavailable(val reason: String) : RaftFcmAvailability

    /** Play services exists but cannot be used right now: skip registering this process, keep all state. */
    data class Deferred(val reason: String) : RaftFcmAvailability
}

/**
 * Only codes meaning Play services is missing, disabled or not genuine mark FCM unavailable (which wipes the
 * binding, pending payload and delivery status). Every other non-success code (SERVICE_UPDATING,
 * SERVICE_VERSION_UPDATE_REQUIRED, ...) is transient or user-fixable and only defers.
 */
internal fun raftFcmAvailabilityFor(code: Int): RaftFcmAvailability =
    when (code) {
        ConnectionResult.SUCCESS -> RaftFcmAvailability.Available
        ConnectionResult.SERVICE_MISSING,
        ConnectionResult.SERVICE_DISABLED,
        ConnectionResult.SERVICE_INVALID -> RaftFcmAvailability.Unavailable("no_play_services code=$code")
        else -> RaftFcmAvailability.Deferred("gms_code_$code")
    }
