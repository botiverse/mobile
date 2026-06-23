package ai.slock.android.badge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ai.slock.android.MainActivity
import ai.slock.android.R

/**
 * Android implementation of [BadgeApplier]. Posts a headless notification with
 * `setNumber(count)` to a dedicated badge channel. The Android system derives the
 * launcher icon badge number from the visible notifications on this channel.
 *
 * **Why headless?** The user already gets per-message system tray notifications via
 * `postUnreadNotification` (separate channels per category). This badge channel is
 * a separate, `IMPORTANCE_MIN`, `setShowBadge(true)` channel used solely to drive
 * the launcher icon number — the user sees a small status-bar icon but no heads-up
 * and no per-message body. On app resume / sign-out the channel is cleared so the
 * tray doesn't accumulate ghosts.
 *
 * **Killed-app path:** This same channel and posting code will be reused by the
 * FCM data message handler (per #93 spec). The FCM payload will call into this
 * applier with the server-pushed count.
 *
 * @param appContext The application context used to access `NotificationManager`.
 */
internal class AndroidNotificationBadgeApplier(
    private val appContext: Context,
) : BadgeApplier {

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(appContext)

    init {
        ensureBadgeChannel()
    }

    override fun apply(count: Int, source: String, isStale: Boolean) {
        // Surface source/isStale as diagnostics only. Host must not branch on these.
        android.util.Log.i(
            BADGE_TAG,
            "apply count=$count source=$source isStale=$isStale",
        )
        if (!hasNotificationPermission()) {
            android.util.Log.w(
                BADGE_TAG,
                "apply skipped: notification permission not granted",
            )
            return
        }
        if (count <= 0) {
            clear()
            return
        }
        if (isChannelBlocked()) {
            android.util.Log.w(
                BADGE_TAG,
                "apply skipped: badge channel is blocked by user",
            )
            return
        }
        val notification = buildBadgeNotification(count)
        runCatching {
            notificationManager.notify(BADGE_NOTIFICATION_ID, notification)
        }.onFailure { error ->
            android.util.Log.w(
                BADGE_TAG,
                "apply notify failed: ${error.javaClass.simpleName} ${error.message}",
            )
        }
    }

    override fun clear() {
        android.util.Log.i(BADGE_TAG, "clear")
        runCatching {
            notificationManager.cancel(BADGE_NOTIFICATION_ID)
        }.onFailure { error ->
            android.util.Log.w(
                BADGE_TAG,
                "clear failed: ${error.javaClass.simpleName} ${error.message}",
            )
        }
    }

    private fun buildBadgeNotification(count: Int): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            appContext,
            BADGE_NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(appContext, BADGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(appContext.getString(R.string.app_badge_notification_title))
            .setContentText(appContext.getString(R.string.app_badge_notification_text, count))
            .setNumber(count)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureBadgeChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(BADGE_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            BADGE_CHANNEL_ID,
            appContext.getString(R.string.app_badge_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = appContext.getString(R.string.app_badge_channel_description)
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun isChannelBlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(BADGE_CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val BADGE_CHANNEL_ID = "slock-app-badge-v1"
        const val BADGE_NOTIFICATION_ID = 0x5BAD6E
        const val BADGE_TAG = "SlockAppBadge"
    }
}
