package build.raft.app.push

import android.content.Context
import build.raft.app.BuildConfig
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.data.SlockAndroidPushRegistrationBridge
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

/**
 * Android host half of RFC 045 push registration. The shared bridge owns
 * persistence, auth-gated upsert, dedupe and logout unbind; this file only
 * supplies the Firebase Installation ID and the installed application id.
 */
internal object AndroidPushRegistration {
    private const val TAG = "SlockFCM"

    fun start(context: Context) {
        runCatching {
            SlockAndroidPushRegistrationBridge.install(UUID.randomUUID().toString())
        }.onFailure { error ->
            SlockAppLog.w(TAG, "push_registration_install_failed errorType=${error::class.simpleName ?: "unknown"}")
            return
        }
        // Registration by Firebase Installation ID: the FID arrives through
        // FirebaseMessagingService.onRegistered, now and whenever it changes.
        runCatching {
            FirebaseMessaging.getInstance().register()
        }.onFailure { error ->
            // Builds without google-services.json have no Firebase app.
            SlockAppLog.i(TAG, "fcm_register_unavailable errorType=${error::class.simpleName ?: "unknown"}")
        }
    }

    fun onFirebaseInstallationId(context: Context, firebaseInstallationId: String) {
        SlockAndroidPushRegistrationBridge.updateRegistration(
            firebaseInstallationId = firebaseInstallationId,
            applicationId = context.packageName,
            appVersion = BuildConfig.VERSION_NAME
        )
    }
}
