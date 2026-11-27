package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import build.raft.app.MainActivity
import build.raft.app.MainActivityDownloadCenterLaunchPlan
import build.raft.app.settings.SETTINGS_STORAGE_PAGE
import build.raft.app.dispatchMainActivityLaunchIntent
import build.raft.app.downloads.RaftDownloadEntry
import build.raft.app.downloads.RaftDownloadStatus
import build.raft.app.isOpenDownloadCenterIntent
import build.raft.app.resolveDownloadCenterLaunchPlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun progressNotificationTargetsMainActivityOpenDownloadsAndCachesPendingIntent() {
        val notification = RaftDownloadNotifications.currentProgressNotification(context)
        val contentIntent = notification.contentIntent
        assertNotNull("progress notification must carry a contentIntent", contentIntent)

        val savedIntent = shadowOf(contentIntent).savedIntent
        assertEquals(ACTION_RAFT_OPEN_DOWNLOADS, savedIntent.action)
        assertEquals(MainActivity::class.java.name, savedIntent.component?.className)
        assertEquals(
            RAFT_APP_OPEN_SOURCE_APP_NOTIFICATION,
            savedIntent.getStringExtra(EXTRA_RAFT_APP_OPEN_SOURCE),
        )
        assertTrue(savedIntent.isOpenDownloadCenterIntent())
        assertSame(contentIntent, RaftDownloadNotifications.progressLaunchAppIntentForTest(context))
    }

    @Test
    fun completedNotificationOpensFileDirectlyAndFallsBackToDownloadCenterWhenUnavailableOrFailed() {
        val pdfEntry =
            sampleEntry(
                id = "dl-pdf",
                fileName = "quarterly-report.pdf",
                mimeType = "application/pdf",
                localUri = "content://media/external/downloads/42",
                status = RaftDownloadStatus.Completed,
            )

        // With a viewer app available, tapping a completed notification opens the file directly.
        val openFileNotif =
            RaftDownloadNotifications.finishedNotificationForTest(
                context = context,
                entry = pdfEntry,
                failed = false,
                canResolveViewIntent = { true },
            )
        val openFileIntent = shadowOf(openFileNotif.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, openFileIntent.action)
        assertEquals("content://media/external/downloads/42", openFileIntent.dataString)
        assertEquals("application/pdf", openFileIntent.type)
        assertTrue((openFileIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        // When no external viewer resolves the intent, falls back to the in-app Download Center.
        val noViewerNotif =
            RaftDownloadNotifications.finishedNotificationForTest(
                context = context,
                entry = pdfEntry,
                failed = false,
                canResolveViewIntent = { false },
            )
        assertEquals(ACTION_RAFT_OPEN_DOWNLOADS, shadowOf(noViewerNotif.contentIntent).savedIntent.action)

        // A failed download always opens the in-app Download Center so the user can retry.
        val failedNotif =
            RaftDownloadNotifications.finishedNotificationForTest(
                context = context,
                entry = pdfEntry.copy(status = RaftDownloadStatus.Failed),
                failed = true,
                canResolveViewIntent = { true },
            )
        assertEquals(ACTION_RAFT_OPEN_DOWNLOADS, shadowOf(failedNotif.contentIntent).savedIntent.action)
    }

    @Test
    fun completedApkNotificationLaunchesInstallerOnlyWhenPermittedElseOpensDownloadCenter() {
        val apkEntry =
            sampleEntry(
                id = "dl-apk",
                fileName = "raft-1.29.0.apk",
                mimeType = "application/octet-stream",
                localUri = "content://media/external/downloads/99",
                status = RaftDownloadStatus.Completed,
            )

        assertEquals(
            "application/vnd.android.package-archive",
            resolveDownloadNotificationMimeType(apkEntry.mimeType, apkEntry.fileName),
        )
        assertEquals(
            "application/vnd.android.package-archive",
            resolveDownloadNotificationMimeType("application/zip", "raft-1.29.0.apk"),
        )
        assertEquals(
            "application/vnd.android.package-archive",
            resolveDownloadNotificationMimeType("application/vnd.android.package-archive; name=app.apk", "download"),
        )
        assertEquals(
            "application/pdf",
            resolveDownloadNotificationMimeType("application/pdf; charset=utf-8", "report.pdf"),
        )
        assertTrue(isDownloadNotificationAndroidPackage("application/vnd.android.package-archive; name=app.apk", "download"))

        // Play build / unknown-sources off -> falls back to Download Center instead of silent installer no-op.
        val blockedApkNotif =
            RaftDownloadNotifications.finishedNotificationForTest(
                context = context,
                entry = apkEntry.copy(mimeType = "application/zip"),
                failed = false,
                canLaunchApkInstaller = { false },
                canResolveViewIntent = { true },
            )
        assertEquals(ACTION_RAFT_OPEN_DOWNLOADS, shadowOf(blockedApkNotif.contentIntent).savedIntent.action)

        // Direct build with unknown-sources permission -> opens installer directly with APK MIME type
        // even when the server reported application/zip for an .apk file.
        val allowedApkNotif =
            RaftDownloadNotifications.finishedNotificationForTest(
                context = context,
                entry = apkEntry.copy(mimeType = "application/zip"),
                failed = false,
                canLaunchApkInstaller = { true },
                canResolveViewIntent = { true },
            )
        val allowedIntent = shadowOf(allowedApkNotif.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, allowedIntent.action)
        assertEquals("application/vnd.android.package-archive", allowedIntent.type)
        assertEquals("content://media/external/downloads/99", allowedIntent.dataString)
    }

    @Test
    fun downloadCenterLaunchPlanClearsCoveringActivityAndDeduplicatesStandaloneStoragePage() {
        assertEquals(
            MainActivityDownloadCenterLaunchPlan.StartAppShell,
            resolveDownloadCenterLaunchPlan(
                liveShellInstanceId = null,
                topHostIsShell = true,
                topHostPageName = null,
            ),
        )
        assertEquals(
            MainActivityDownloadCenterLaunchPlan.RevealLiveShell,
            resolveDownloadCenterLaunchPlan(
                liveShellInstanceId = DEFAULT_ANDROID_SHELL_INSTANCE_ID,
                topHostIsShell = true,
                topHostPageName = RaftCmpActivity.PAGE_APP_SHELL,
            ),
        )
        assertEquals(
            MainActivityDownloadCenterLaunchPlan.RevealAlreadyOnTop,
            resolveDownloadCenterLaunchPlan(
                liveShellInstanceId = DEFAULT_ANDROID_SHELL_INSTANCE_ID,
                topHostIsShell = false,
                topHostPageName = SETTINGS_STORAGE_PAGE,
            ),
        )
        assertEquals(
            MainActivityDownloadCenterLaunchPlan.ClearCoveringActivityAndRevealShell,
            resolveDownloadCenterLaunchPlan(
                liveShellInstanceId = DEFAULT_ANDROID_SHELL_INSTANCE_ID,
                topHostIsShell = false,
                topHostPageName = "compose_in_app_browser",
            ),
        )
        assertTrue(shouldOpenDownloadCenterFromPageData("""{"${RaftCmpActivity.KEY_APP_SHELL_OPEN_DOWNLOAD_CENTER}":true}"""))
        assertFalse(shouldOpenDownloadCenterFromPageData("""{"${RaftCmpActivity.KEY_APP_SHELL_OPEN_DOWNLOAD_CENTER}":false}"""))
        assertFalse(shouldOpenDownloadCenterFromPageData(""))
    }

    @Test
    fun mainActivityDispatchRoutesOpenDownloadsAndSkipsOnHistoryRevival() {
        val openDownloads = Intent(context, MainActivity::class.java).apply { action = ACTION_RAFT_OPEN_DOWNLOADS }
        var downloadCenterHandled = 0
        var historyRevived = 0
        var shellOpened = 0

        dispatchMainActivityLaunchIntent(
            intent = openDownloads,
            onHistoryRevival = { historyRevived++ },
            onExternalShare = { false },
            onDownloadCenterLaunch = { incoming ->
                if (incoming.isOpenDownloadCenterIntent()) {
                    downloadCenterHandled++
                    true
                } else {
                    false
                }
            },
            onAppLoginCallback = { false },
            onMobileOAuthCallback = { false },
            onEmailVerificationLink = { false },
            onRaftMessageLink = { false },
            onUnclaimedAppSchemeLink = { false },
            onUnsupportedRaftWebLink = { false },
            onOpenSharedShell = { shellOpened++ },
        )
        assertEquals(1, downloadCenterHandled)
        assertEquals(0, historyRevived)
        assertEquals(0, shellOpened)

        // Reviving from Recents must never replay the one-shot download notification action.
        val historyIntent =
            Intent(openDownloads).apply { addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) }
        dispatchMainActivityLaunchIntent(
            intent = historyIntent,
            onHistoryRevival = { historyRevived++ },
            onExternalShare = { false },
            onDownloadCenterLaunch = {
                downloadCenterHandled++
                true
            },
            onAppLoginCallback = { false },
            onMobileOAuthCallback = { false },
            onEmailVerificationLink = { false },
            onRaftMessageLink = { false },
            onUnclaimedAppSchemeLink = { false },
            onUnsupportedRaftWebLink = { false },
            onOpenSharedShell = { shellOpened++ },
        )
        assertEquals(1, downloadCenterHandled)
        assertEquals(1, historyRevived)
        assertEquals(0, shellOpened)
        assertFalse(Intent(Intent.ACTION_MAIN).isOpenDownloadCenterIntent())
    }

    private fun sampleEntry(
        id: String,
        fileName: String,
        mimeType: String?,
        localUri: String?,
        status: RaftDownloadStatus,
    ): RaftDownloadEntry =
        RaftDownloadEntry(
            id = id,
            ownerKey = "prod|user-1|server-1",
            attachmentId = "att-1",
            fileName = fileName,
            mimeType = mimeType,
            status = status,
            localUri = localUri,
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L,
        )
}
