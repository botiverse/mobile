package build.raft.app.shared

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DMs move from slock-unread-dm-v1 (DEFAULT) to slock-unread-dm-v2 (HIGH): the user's choices on v1
 * carry over, and v1 is deleted only once none of its notifications is in the tray.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftDmChannelMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun clean() {
        manager.cancelAll()
        manager.notificationChannels.forEach { manager.deleteNotificationChannel(it.id) }
    }

    @Test
    fun importanceKeepsTheUsersChoiceAndRaisesOnlyTheUntouchedDefault() {
        val poster = AndroidUnreadNotificationPoster
        assertEquals(NotificationManager.IMPORTANCE_HIGH, poster.dmChannelImportance(null))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, poster.dmChannelImportance(NotificationManager.IMPORTANCE_DEFAULT))
        assertEquals(NotificationManager.IMPORTANCE_LOW, poster.dmChannelImportance(NotificationManager.IMPORTANCE_LOW))
        assertEquals(NotificationManager.IMPORTANCE_NONE, poster.dmChannelImportance(NotificationManager.IMPORTANCE_NONE))
    }

    @Test
    fun aSilencedV1BecomesASilentV2AndStaysUntilItsNotificationsAreGone() {
        manager.createNotificationChannel(
            NotificationChannel(AndroidUnreadNotificationPoster.LEGACY_UNREAD_DM_NOTIFICATION_CHANNEL_ID, "DMs", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        val showing: Notification =
            NotificationCompat.Builder(context, AndroidUnreadNotificationPoster.LEGACY_UNREAD_DM_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Ada")
                .build()
        manager.notify(7, showing)

        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(context)

        val v2 = assertNotNullChannel(AndroidUnreadNotificationPoster.UNREAD_DM_NOTIFICATION_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, v2.importance)
        assertNull(v2.sound)
        assertEquals(false, v2.shouldVibrate())
        // The DM still in the tray keeps v1 alive.
        assertNotNull(manager.getNotificationChannel(AndroidUnreadNotificationPoster.LEGACY_UNREAD_DM_NOTIFICATION_CHANNEL_ID))
        assertEquals(1, manager.activeNotifications.size)

        manager.cancel(7)
        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(context)
        assertNull(manager.getNotificationChannel(AndroidUnreadNotificationPoster.LEGACY_UNREAD_DM_NOTIFICATION_CHANNEL_ID))
        // Created once: a later call does not raise the user's importance.
        assertEquals(NotificationManager.IMPORTANCE_LOW, assertNotNullChannel(AndroidUnreadNotificationPoster.UNREAD_DM_NOTIFICATION_CHANNEL_ID).importance)
    }

    @Test
    fun aFreshInstallGetsAHighDmChannel() {
        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(context)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            assertNotNullChannel(AndroidUnreadNotificationPoster.UNREAD_DM_NOTIFICATION_CHANNEL_ID).importance
        )
    }

    private fun assertNotNullChannel(id: String): NotificationChannel =
        requireNotNull(manager.getNotificationChannel(id)) { "channel $id missing" }
}
