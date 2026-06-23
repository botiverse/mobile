package ai.slock.android.badge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ai.slock.android.MainActivity

/**
 * Android v1 implementation of [BadgeApplier] (per #100, v1.1 per @artin 2026-06-24
 * 02:33).
 *
 * **Approach:** reuse the existing `slock-unread-general-v1` channel (created by
 * `SlockKuiklyActivity.ensureSharedNotificationChannels()`) and post a single
 * headless notification on it with `setNumber(count)`. The launcher icon badge
 * number on Pixel/AOSP launchers and on launchers that respect `setNumber` is
 * taken from the most recent such notification on the channel.
 *
 * We picked the **general** channel (not channel/dm/mention) so the badge sits
 * in a single place independent of message category, matching the
 * cross-category nature of "unread count".
 *
 * **Why reuse an existing channel?** Per @artin 2026-06-24 02:33: "用现有的其他
 * 三个已有的 channel, setNumber 试一下". Creating a new `slock-app-badge-v1`
 * channel was deemed redundant — the existing unread channels are already
 * created and already drive the launcher badge dot. Reusing the general channel
 * for the badge notification keeps the channel count small and matches the
 * convention of "badge lives in an existing notification channel".
 *
 * **Trade-off:** per-message notifications on `slock-unread-general-v1` coexist
 * with this single badge notification. The user may see one or two icons in the
 * status bar. The badge notification has `setOngoing(false)` and is replaced on
 * each `apply()` so it doesn't accumulate. Per-message notifications on the
 * same channel still get their title/body and contribute to the launcher
 * notification count separately.
 *
 * **Why keep the [BadgeApplier] interface at all?** Per @artin 2026-06-24
 * 02:33: "BadgeApplier 这些先留着呗，毕竟还要兼容 ohos, oppo 各种系统呢".
 * OHOS will need its own impl (no NotificationManager; push badges go through
 * OHOS push or a live view), and OPPO/小米/华为 launchers may need their own
 * badge intent dispatching. Keeping the interface lets the bridge
 * `call("appBadge", ...)` switch be platform-agnostic and the parser stay
 * testable in isolation.
 *
 * **Boundary rule (per #81, 2026-06-15 12:59):** we only use [count] to drive
 * host behavior. [source] and [isStale] are diagnostics-only.
 */
internal class AndroidNotificationBadgeApplier(
    private val appContext: Context,
) : BadgeApplier {

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(appContext)

    override fun apply(count: Int, source: String, isStale: Boolean) {
        android.util.Log.i(
            BADGE_TAG,
            "apply count=$count source=$source isStale=$isStale",
        )
        if (count <= 0) {
            clear()
            return
        }
        if (!hasNotificationPermission()) {
            android.util.Log.w(BADGE_TAG, "apply skipped: notification permission not granted")
            return
        }
        if (isChannelBlocked(BADGE_CHANNEL_ID)) {
            android.util.Log.w(
                BADGE_TAG,
                "apply skipped: channel $BADGE_CHANNEL_ID is blocked by user",
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

    private fun buildBadgeNotification(count: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            appContext,
            BADGE_NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(appContext, BADGE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Unread")
            .setContentText("$count unread")
            .setNumber(count)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun isChannelBlocked(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(channelId)?.importance ==
            NotificationManager.IMPORTANCE_NONE
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        // Reuse the existing general unread channel created by
        // SlockKuiklyActivity.ensureSharedNotificationChannels() — no new
        // channel is created by this applier.
        const val BADGE_CHANNEL_ID = "slock-unread-general-v1"
        const val BADGE_NOTIFICATION_ID = 0x5BAD6E
        const val BADGE_TAG = "SlockAppBadge"
    }
}
