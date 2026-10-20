package build.raft.app.push

import android.content.Context
import build.raft.app.BuildConfig
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.data.SlockAndroidPushRegistrationBridge
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

/**
 * Android host half of RFC 045 push registration. The shared bridge owns
 * persistence, auth-gated upsert, dedupe and logout unbind; this file only
 * supplies the Firebase Installation ID and the installed application id.
 */
internal object AndroidPushRegistration {
    private const val TAG = "SlockFCM"

    /** Non-null once this process decided not to register: "fcm_unavailable" or "fcm_deferred". */
    @Volatile
    private var registrationSkippedReason: String? = null

    fun start(context: Context) {
        val availability = checkFcmAvailability(context)
        val unavailableReason = (availability as? RaftFcmAvailability.Unavailable)?.reason
        registrationSkippedReason =
            when (availability) {
                RaftFcmAvailability.Available -> null
                is RaftFcmAvailability.Unavailable -> "fcm_unavailable"
                is RaftFcmAvailability.Deferred -> "fcm_deferred"
            }
        runCatching {
            SlockAndroidPushRegistrationBridge.install(
                candidateInstallationId = UUID.randomUUID().toString(),
                fcmUnavailableReason = unavailableReason
            )
        }.onFailure { error ->
            SlockAppLog.w(TAG, "push_registration_install_failed errorType=${error::class.simpleName ?: "unknown"}")
            return
        }
        if (unavailableReason != null) {
            // Without Google Play services FCM cannot deliver to this device (Huawei without GMS,
            // including HarmonyOS's Android compatibility layer). The Firebase Installation ID is still
            // readable over plain HTTPS, but uploading it would register a token FCM rejects, so nothing
            // is registered; the realtime path keeps posting this device's notifications.
            SlockAppLog.w(TAG, "fcm_unavailable reason=$unavailableReason")
            return
        }
        if (availability is RaftFcmAvailability.Deferred) {
            // Google Play services is present but not usable right now (updating, update required, a
            // transient check failure). FCM may well work in a later process, so nothing is cleared and the
            // provider is not marked unavailable: the binding, pending payload and delivery-status answer
            // stay; this process only skips registering.
            SlockAppLog.w(TAG, "fcm_deferred reason=${availability.reason}")
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
                    if (!fid.isNullOrBlank()) onFirebaseInstallationId(context, fid)
                }
                .addOnFailureListener { error ->
                    SlockAppLog.w(TAG, "fcm_installation_id_unavailable errorType=${error::class.simpleName ?: "unknown"}")
                }
        }.onFailure { error ->
            // Builds without google-services.json have no Firebase app.
            SlockAppLog.i(TAG, "fcm_register_unavailable errorType=${error::class.simpleName ?: "unknown"}")
        }
    }

    fun onFirebaseInstallationId(context: Context, firebaseInstallationId: String) {
        registrationSkippedReason?.let { skipped ->
            SlockAppLog.w(TAG, "fcm_installation_id_ignored reason=$skipped")
            return
        }
        SlockAndroidPushRegistrationBridge.updateRegistration(
            firebaseInstallationId = firebaseInstallationId,
            applicationId = context.packageName,
            appVersion = BuildConfig.VERSION_NAME
        )
    }

    private fun checkFcmAvailability(context: Context): RaftFcmAvailability =
        runCatching {
            raftFcmAvailabilityFor(GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context))
        }.getOrElse { error ->
            RaftFcmAvailability.Deferred("play_services_check_failed errorType=${error::class.simpleName ?: "unknown"}")
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
