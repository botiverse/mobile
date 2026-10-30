package build.raft.app.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import build.raft.app.data.RaftPushDeviceStateHost

/**
 * The registration's `osPermission` on Android (slock docs/contracts/notifications.md §3.1:
 * `denied` -> the device gets only sync / retract pushes). Re-read on every resume and after the
 * permission dialog answers, so an in-app grant reaches the server before the user leaves the app.
 *
 * Android 13+ starts without POST_NOTIFICATIONS. Until the app has asked once that is not the user's
 * "no", so it reads `unknown`, as iOS's notDetermined does.
 */
internal object RaftNotificationPermissionReporter {
    private const val PREFS = "raft_notification_permission"
    private const val KEY_REQUESTED = "post_notifications_requested"

    fun markRequested(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_REQUESTED, true).apply()
        }
    }

    fun report(context: Context) {
        val value = runCatching { currentValue(context.applicationContext) }.getOrDefault("unknown")
        RaftPushDeviceStateHost.updateOsPermission(value)
    }

    internal fun currentValue(context: Context): String {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return osPermissionValue(
            notificationsEnabled = enabled,
            runtimePermissionApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            runtimePermissionGranted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
            requestedBefore =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REQUESTED, false)
        )
    }

    /** Pure decision, for tests. */
    internal fun osPermissionValue(
        notificationsEnabled: Boolean,
        runtimePermissionApi: Boolean,
        runtimePermissionGranted: Boolean,
        requestedBefore: Boolean
    ): String =
        when {
            notificationsEnabled -> "granted"
            // Never asked yet on Android 13+: no answer from the user so far.
            runtimePermissionApi && !runtimePermissionGranted && !requestedBefore -> "unknown"
            else -> "denied"
        }
}
