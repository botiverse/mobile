package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The download foreground service promotes with [RaftDownloadNotifications.currentProgressNotification].
 * `startForeground` crashes the app (CannotPostForegroundServiceNotificationException) when that
 * notification's channel does not exist, so the channel must exist even when POST_NOTIFICATIONS is
 * denied, which is the Android 13+ default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftDownloadNotificationsChannelTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun progressNotificationChannelExistsWithoutNotificationPermission() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val notification = RaftDownloadNotifications.currentProgressNotification(context)

        assertEquals(RaftDownloadNotifications.CHANNEL_ID, notification.channelId)
        assertNotNull(
            "the service notification's channel must exist before startForeground",
            manager.getNotificationChannel(notification.channelId)
        )
    }
}
