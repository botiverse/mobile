package build.raft.app.shared

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a notification tap lands (slock docs/contracts/notifications.md §4.5): the conversation of
 * its legacy fields or its `route`, else its server's Activity (reminder / notice / test, agent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RaftNotificationTapTargetTest {
    @Test
    fun aReminderWithoutARouteOpensItsServersActivity() {
        val pageData =
            requireNotNull(
                RaftCmpActivity.buildNotificationTapAppShellPageData(
                    JSONObject()
                        .put("serverId", "server-2")
                        .put("category", "reminder")
                        .put("groupKey", "server-2:reminder")
                        .put("routeUrl", "/s/acme/activity")
                        .put("route", "")
                )
            )
        assertEquals("server-2", pageData.optString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID))
        assertEquals("activity", pageData.optString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_TARGET))
        assertEquals("activity", pageData.optString(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB))
    }

    @Test
    fun anAgentRouteOpensActivityOnTheRoutesServer() {
        val pageData =
            requireNotNull(
                RaftCmpActivity.buildNotificationTapAppShellPageData(
                    JSONObject()
                        .put("serverId", "server-2")
                        .put("category", "agent_attention")
                        .put("route", """{"type":"agent","serverId":"server-3","agentId":"a1"}""")
                )
            )
        assertEquals("server-3", pageData.optString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID))
        assertEquals("activity", pageData.optString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_TARGET))
    }

    @Test
    fun aRouteWithAConversationOpensThatConversation() {
        val pageData =
            requireNotNull(
                RaftCmpActivity.buildNotificationTapAppShellPageData(
                    JSONObject()
                        .put("serverId", "server-2")
                        .put("conversationId", "dm-1")
                        .put("route", """{"type":"dm","serverId":"server-2","channelId":"dm-1","messageId":"m-1"}""")
                )
            )
        assertEquals("server-2", pageData.optString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID))
        assertEquals("dm-1", pageData.optString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID))
        assertFalse(pageData.has(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_TARGET))
    }
}
