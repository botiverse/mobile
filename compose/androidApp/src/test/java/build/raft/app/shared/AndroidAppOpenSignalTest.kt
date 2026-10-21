package build.raft.app.shared

import android.content.Intent
import build.raft.app.data.RaftAppOpenSignal
import org.junit.Assert.assertEquals
import org.junit.Test

/** `app_opened.via` on Android: the launch intent's action/categories/flags and notification marks. */
class AndroidAppOpenSignalTest {
    private class Case(
        val name: String,
        val action: String? = Intent.ACTION_MAIN,
        val categories: Set<String>? = setOf(Intent.CATEGORY_LAUNCHER),
        val flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK,
        val openSource: String? = null,
        val opensNotificationTarget: Boolean = false,
        val expected: RaftAppOpenSignal,
    )

    @Test
    fun launchIntentMapsToTheOpenSignal() {
        val cases =
            listOf(
                Case("launcher icon", expected = RaftAppOpenSignal.Launcher),
                Case(
                    "push notification (shell page data)",
                    action = null,
                    categories = null,
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    openSource = RAFT_APP_OPEN_SOURCE_PUSH_NOTIFICATION,
                    opensNotificationTarget = true,
                    expected = RaftAppOpenSignal.Notification,
                ),
                Case(
                    "push notification posted before the source extra",
                    action = null,
                    categories = null,
                    opensNotificationTarget = true,
                    expected = RaftAppOpenSignal.Notification,
                ),
                // The fallback content intent is the launcher intent: the source extra tells them apart.
                Case(
                    "push notification on the launcher intent",
                    openSource = RAFT_APP_OPEN_SOURCE_PUSH_NOTIFICATION,
                    expected = RaftAppOpenSignal.Notification,
                ),
                Case(
                    "background-connection notification",
                    openSource = RAFT_APP_OPEN_SOURCE_APP_NOTIFICATION,
                    expected = RaftAppOpenSignal.Unknown,
                ),
                Case(
                    "recents relaunch with the launcher intent",
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
                    expected = RaftAppOpenSignal.Unknown,
                ),
                Case("link", action = Intent.ACTION_VIEW, categories = setOf(Intent.CATEGORY_BROWSABLE), expected = RaftAppOpenSignal.Unknown),
                Case("share", action = Intent.ACTION_SEND, categories = null, expected = RaftAppOpenSignal.Unknown),
                Case("in-app page", action = null, categories = null, flags = 0, expected = RaftAppOpenSignal.Unknown),
            )
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                androidAppOpenSignal(
                    action = case.action,
                    categories = case.categories,
                    flags = case.flags,
                    openSource = case.openSource,
                    opensNotificationTarget = case.opensNotificationTarget,
                ),
            )
        }
    }
}
