package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Every channel exists after [RaftNotificationChannels.ensureAll], even with POST_NOTIFICATIONS denied. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftNotificationChannelsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun ensureAllCreatesEveryChannelWithoutNotificationPermission() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        RaftNotificationChannels.ensureAll(context)
        RaftNotificationChannels.ensureAll(context)

        val expected =
            setOf(
                AndroidUnreadNotificationPoster.SHARED_NOTIFICATION_CHANNEL_ID,
                AndroidUnreadNotificationPoster.UNREAD_GENERAL_NOTIFICATION_CHANNEL_ID,
                AndroidUnreadNotificationPoster.UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID,
                AndroidUnreadNotificationPoster.UNREAD_DM_NOTIFICATION_CHANNEL_ID,
                AndroidUnreadNotificationPoster.UNREAD_MENTION_NOTIFICATION_CHANNEL_ID,
                RaftDownloadNotifications.CHANNEL_ID,
                RaftBackgroundConnectionService.CHANNEL_ID,
            )
        assertEquals(expected, manager.notificationChannels.map { it.id }.toSet())
    }
}
