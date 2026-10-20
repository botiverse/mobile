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

    @Volatile
    private var fcmUnavailable = false

    fun start(context: Context) {
        val unavailableReason = fcmUnavailableReason(context)
        fcmUnavailable = unavailableReason != null
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
        if (fcmUnavailable) {
            SlockAppLog.w(TAG, "fcm_installation_id_ignored reason=fcm_unavailable")
            return
        }
        SlockAndroidPushRegistrationBridge.updateRegistration(
            firebaseInstallationId = firebaseInstallationId,
            applicationId = context.packageName,
            appVersion = BuildConfig.VERSION_NAME
        )
    }

    /** Null when FCM can deliver here; otherwise why it cannot. */
    private fun fcmUnavailableReason(context: Context): String? =
        runCatching {
            val code = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context)
            if (code == ConnectionResult.SUCCESS) null else "no_play_services code=$code"
        }.getOrElse { error ->
            "play_services_check_failed errorType=${error::class.simpleName ?: "unknown"}"
        }
}
