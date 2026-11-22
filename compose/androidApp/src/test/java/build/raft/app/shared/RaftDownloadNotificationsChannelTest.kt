package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    @After
    fun resetCaches() {
        RaftDownloadNotifications.resetCachesForTests()
        RaftDownloadForegroundService.resetForTests()
    }

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

    @Test
    fun stopIfIdleClearsInstanceSoBackToBackDownloadStartsServiceAgain() {
        val app = context as Application
        RaftDownloadNotifications.setHasActiveDownloadsForTests(true)

        RaftDownloadForegroundService.ensureRunning(context)
        assertEquals(
            RaftDownloadForegroundService::class.java.name,
            shadowOf(app).nextStartedService?.component?.className,
        )
        // A second ensureRunning while startPending is true must not issue a duplicate start.
        RaftDownloadForegroundService.ensureRunning(context)
        assertNull(shadowOf(app).nextStartedService)

        val controller = Robolectric.buildService(RaftDownloadForegroundService::class.java)
        val service = controller.create().get()
        service.onStartCommand(Intent(context, RaftDownloadForegroundService::class.java), 0, 1)

        // While running, ensureRunning stays a no-op.
        RaftDownloadForegroundService.ensureRunning(context)
        assertNull(shadowOf(app).nextStartedService)

        // Download 1 finishes: stopIfIdle clears instance immediately before onDestroy runs.
        RaftDownloadNotifications.setHasActiveDownloadsForTests(false)
        RaftDownloadForegroundService.stopIfIdle()
        assertTrue(shadowOf(service).isStoppedBySelf)

        // Download 2 starts before onDestroy() runs: must request a fresh foreground service start.
        RaftDownloadNotifications.setHasActiveDownloadsForTests(true)
        RaftDownloadForegroundService.ensureRunning(context)
        assertEquals(
            RaftDownloadForegroundService::class.java.name,
            shadowOf(app).nextStartedService?.component?.className,
        )

        // Old service's onDestroy() must not disturb the new pending start.
        controller.destroy()
        RaftDownloadForegroundService.ensureRunning(context)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun onStartCommandStopsItselfWhenDownloadsAlreadyFinishedBeforePromotion() {
        val app = context as Application
        RaftDownloadNotifications.setHasActiveDownloadsForTests(true)
        RaftDownloadForegroundService.ensureRunning(context)
        assertNotNull(shadowOf(app).nextStartedService)

        // Download finished before onStartCommand ran on the main thread.
        RaftDownloadNotifications.setHasActiveDownloadsForTests(false)
        val service = Robolectric.buildService(RaftDownloadForegroundService::class.java).create().get()
        service.onStartCommand(Intent(context, RaftDownloadForegroundService::class.java), 0, 1)
        assertTrue(shadowOf(service).isStoppedBySelf)

        // A later download must see both instance and startPending cleared and start cleanly.
        RaftDownloadNotifications.setHasActiveDownloadsForTests(true)
        RaftDownloadForegroundService.ensureRunning(context)
        assertEquals(
            RaftDownloadForegroundService::class.java.name,
            shadowOf(app).nextStartedService?.component?.className,
        )
    }
}
