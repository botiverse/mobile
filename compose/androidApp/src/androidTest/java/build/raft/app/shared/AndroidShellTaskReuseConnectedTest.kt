package build.raft.app.shared

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class AndroidShellTaskReuseConnectedTest {
    @Test
    fun defaultAppShellHostUsesStandardTaskManifestPolicy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, SlockAppShellActivity::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(ActivityInfo.DOCUMENT_LAUNCH_NONE, activityInfo.documentLaunchMode)
    }

    @Test
    fun notificationReusesExistingServerShellWithoutStackingAnotherActivity() {
        assertNotificationReusesExistingShell(
            AndroidShellLaunchTarget(
                shellInstanceId = "android-default-app-shell",
                serverId = "server-notification-reuse",
                documentShell = false,
            )
        )
    }

    @Test
    fun notificationReusesExistingServerDocumentTaskWithoutCreatingAnotherDocument() {
        assertNotificationReusesExistingShell(
            AndroidShellLaunchTarget(
                shellInstanceId = "server-shell:server-notification-reuse",
                serverId = "server-notification-reuse",
                documentShell = true,
            )
        )
    }

    private fun assertNotificationReusesExistingShell(initialTarget: AndroidShellLaunchTarget) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AndroidShellWindowRegistry.clearForTest()
        val initialPageData =
            JSONObject()
                .put("instanceId", "notification-reuse-initial")
                .put(SlockKuiklyActivity.KEY_PAGE_NAME, SlockKuiklyActivity.PAGE_APP_SHELL)
                .put("slockSocialAuthProviders", "google,github")
                .put("slockSuppressRestoreLanding", true)
                .put("localOffsetMinutes", 0)
                .put("todayEpochDay", 0)
                .put("nowEpochMillis", System.currentTimeMillis())
                .withAndroidShellLaunchTarget(initialTarget)
        val initialIntent =
            Intent(context, SlockAppShellActivity::class.java)
                .putExtra(SlockKuiklyActivity.KEY_PAGE_NAME, SlockKuiklyActivity.PAGE_APP_SHELL)
                .putExtra(
                    SlockKuiklyActivity.KEY_PAGE_DATA,
                    SlockKuiklyPageData.fromJson(initialPageData).text,
                )
                .apply {
                    if (initialTarget.documentShell) {
                        data = Uri.parse(
                            "raft-shell://window/${Uri.encode(initialTarget.shellInstanceId)}"
                        )
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                                Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS
                        )
                    } else {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                }
        context.startActivity(initialIntent)
        val initialActivity = waitForResumedAppShell(instrumentation)
        val taskId = initialActivity.taskId
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            val notificationIntent =
                AndroidUnreadNotificationPoster.unreadNotificationLaunchIntent(
                    context,
                    notificationRequest(messageId = "message-notification-reuse"),
                )
            context.startActivity(requireNotNull(notificationIntent))
            waitForReplacementAppShell(instrumentation, initialActivity)

            assertEquals(1, waitForTaskActivityCount(activityManager, taskId, expectedCount = 1))
        } finally {
            activityManager.appTasks.firstOrNull { it.taskInfo.taskId == taskId }?.finishAndRemoveTask()
            AndroidShellWindowRegistry.clearForTest()
        }
    }

    private fun waitForResumedAppShell(
        instrumentation: android.app.Instrumentation,
        timeoutMillis: Long = 5_000,
    ): SlockAppShellActivity {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val resumedActivity = AtomicReference<SlockAppShellActivity?>()
            instrumentation.runOnMainSync {
                resumedActivity.set(
                    ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<SlockAppShellActivity>()
                        .firstOrNull()
                )
            }
            resumedActivity.get()?.let { return it }
            SystemClock.sleep(10)
        }
        error("Timed out waiting for SlockAppShellActivity to resume")
    }

    private fun waitForReplacementAppShell(
        instrumentation: android.app.Instrumentation,
        initialActivity: SlockAppShellActivity,
        timeoutMillis: Long = 5_000,
    ): SlockAppShellActivity {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val resumedActivity = AtomicReference<SlockAppShellActivity?>()
            instrumentation.runOnMainSync {
                resumedActivity.set(
                    ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<SlockAppShellActivity>()
                        .firstOrNull { it !== initialActivity }
                )
            }
            resumedActivity.get()?.let { return it }
            SystemClock.sleep(10)
        }
        error("Timed out waiting for notification intent to recreate SlockAppShellActivity")
    }

    private fun waitForTaskActivityCount(
        activityManager: ActivityManager,
        taskId: Int,
        expectedCount: Int,
        timeoutMillis: Long = 1_000,
    ): Int {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var lastCount = -1
        while (SystemClock.uptimeMillis() < deadline) {
            lastCount = activityManager.appTasks
                .map { it.taskInfo }
                .firstOrNull { it.taskId == taskId }
                ?.numActivities
                ?: -1
            if (lastCount == expectedCount) return lastCount
            SystemClock.sleep(10)
        }
        return lastCount
    }

    private fun notificationRequest(messageId: String): JSONObject =
        JSONObject()
            .put("serverId", "server-notification-reuse")
            .put("threadId", "channel-notification-reuse")
            .put("channelId", "channel-notification-reuse")
            .put("messageId", messageId)
            .put("kind", "channel")
            .put("category", "channel_unread")
}
