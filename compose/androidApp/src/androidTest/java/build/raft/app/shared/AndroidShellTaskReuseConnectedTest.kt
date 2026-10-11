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
import org.json.JSONException
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

        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, activityInfo.launchMode)
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
    fun legacyDocumentPayloadStillReusesSingleAppShellTask() {
        assertNotificationReusesExistingShell(
            AndroidShellLaunchTarget(
                shellInstanceId = "server-shell:server-notification-reuse",
                serverId = "server-notification-reuse",
                documentShell = true,
            )
        )
    }

    @Test
    fun secondServerLegacyDocumentLaunchCannotCreateAnotherAppShell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AndroidShellWindowRegistry.clearForTest()
        val firstIntent = appShellIntent(
            context = context,
            target = AndroidShellLaunchTarget(
                shellInstanceId = "android-default-app-shell",
                serverId = "server-a",
                documentShell = false,
            ),
            instanceId = "single-shell-server-a",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(firstIntent)
        val firstActivity = waitForResumedAppShell(instrumentation)
        val firstTaskId = firstActivity.taskId
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            val legacyDocumentIntent = rawLegacyAppShellIntent(
                context = context,
                shellInstanceId = "server-shell:server-b",
                serverId = "server-b",
                instanceId = "single-shell-server-b",
            ).apply {
                data = Uri.parse("raft-shell://window/server-shell%3Aserver-b")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                        Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS
                )
            }
            context.startActivity(legacyDocumentIntent)
            val replacement = waitForReplacementAppShell(instrumentation, firstActivity)

            assertEquals(firstTaskId, replacement.taskId)
            assertEquals(1, waitForTaskActivityCount(activityManager, firstTaskId, expectedCount = 1))
            assertEquals(1, appShellTaskCount(activityManager, context.packageName))
            val replacementPageData = readCurrentPageData(instrumentation, replacement)
            assertEquals("android-default-app-shell", replacementPageData.getString(ANDROID_SHELL_INSTANCE_ID_KEY))
            assertEquals("server-b", replacementPageData.getString(ANDROID_SHELL_SERVER_ID_KEY))
            assertEquals(false, replacementPageData.getBoolean(ANDROID_DOCUMENT_SHELL_KEY))
            assertEquals(
                "thread-server-b",
                replacementPageData.getString(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID),
            )
            assertEquals(
                "message-server-b",
                replacementPageData.getString(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID),
            )
        } finally {
            activityManager.appTasks.firstOrNull { it.taskInfo.taskId == firstTaskId }?.finishAndRemoveTask()
            AndroidShellWindowRegistry.clearForTest()
        }
    }

    private fun assertNotificationReusesExistingShell(initialTarget: AndroidShellLaunchTarget) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AndroidShellWindowRegistry.clearForTest()
        val initialIntent = appShellIntent(context, initialTarget, "notification-reuse-initial")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
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

    private fun appShellIntent(
        context: Context,
        target: AndroidShellLaunchTarget,
        instanceId: String,
    ): Intent {
        val pageData =
            JSONObject()
                .put("instanceId", instanceId)
                .put(SlockKuiklyActivity.KEY_PAGE_NAME, SlockKuiklyActivity.PAGE_APP_SHELL)
                .put("slockSocialAuthProviders", "google,github")
                .put("slockSuppressRestoreLanding", true)
                .put("localOffsetMinutes", 0)
                .put("todayEpochDay", 0)
                .put("nowEpochMillis", System.currentTimeMillis())
                .withAndroidShellLaunchTarget(target)
        return Intent(context, SlockAppShellActivity::class.java)
            .putExtra(SlockKuiklyActivity.KEY_PAGE_NAME, SlockKuiklyActivity.PAGE_APP_SHELL)
            .putExtra(
                SlockKuiklyActivity.KEY_PAGE_DATA,
                SlockKuiklyPageData.fromJson(pageData).text,
            )
    }

    private fun rawLegacyAppShellIntent(
        context: Context,
        shellInstanceId: String,
        serverId: String,
        instanceId: String,
    ): Intent {
        val pageData =
            JSONObject()
                .put("instanceId", instanceId)
                .put(SlockKuiklyActivity.KEY_PAGE_NAME, SlockKuiklyActivity.PAGE_APP_SHELL)
                .put("slockSocialAuthProviders", "google,github")
                .put("slockSuppressRestoreLanding", true)
                .put("localOffsetMinutes", 0)
                .put("todayEpochDay", 0)
                .put("nowEpochMillis", System.currentTimeMillis())
                .put(ANDROID_SHELL_INSTANCE_ID_KEY, shellInstanceId)
                .put(ANDROID_SHELL_SERVER_ID_KEY, serverId)
                .put(ANDROID_DOCUMENT_SHELL_KEY, true)
                .put(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID, serverId)
                .put(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID, "thread-server-b")
                .put(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_MESSAGE_ID, "message-server-b")
        return Intent(context, SlockAppShellActivity::class.java)
            .putExtra(SlockKuiklyActivity.KEY_PAGE_NAME, SlockKuiklyActivity.PAGE_APP_SHELL)
            .putExtra(SlockKuiklyActivity.KEY_PAGE_DATA, pageData.toString())
    }

    private fun readCurrentPageData(
        instrumentation: android.app.Instrumentation,
        activity: SlockAppShellActivity,
        timeoutMillis: Long = 5_000,
    ): JSONObject {
        // The replacement activity can reach RESUMED before its page spec is
        // applied on the main thread, so an immediate snapshot read sees an
        // empty string ("End of input at character 0" on the CI emulator).
        // Poll until the snapshot parses.
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var lastFailure: Exception? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val pageData = AtomicReference<JSONObject?>()
            instrumentation.runOnMainSync {
                pageData.set(
                    try {
                        activity.currentPageDataSnapshotForTest()
                    } catch (failure: JSONException) {
                        lastFailure = failure
                        null
                    }
                )
            }
            pageData.get()?.let { return it }
            SystemClock.sleep(10)
        }
        error("Timed out waiting for the page data snapshot to parse: ${lastFailure?.message}")
    }

    private fun appShellTaskCount(activityManager: ActivityManager, packageName: String): Int =
        activityManager.appTasks.count { appTask ->
            appTask.taskInfo.baseActivity?.let { component ->
                component.packageName == packageName &&
                    component.className == SlockAppShellActivity::class.java.name
            } == true
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
