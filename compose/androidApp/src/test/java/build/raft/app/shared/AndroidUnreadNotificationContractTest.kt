package build.raft.app.shared

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftNotificationRetract
import build.raft.app.push.raftFcmUnreadNotificationRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The unified notification contract on the Android tray (slock docs/contracts/notifications.md
 * §4.3 / §4.5 / §5): category → channel, another workspace's push is posted, a retraction closes
 * what was read and keeps what is newer, and the account sound setting silences the post.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidUnreadNotificationContractTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val productionSummaryScheduler = AndroidUnreadNotificationPoster.summaryScheduler

    @Before
    fun clearTray() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidUnreadNotificationPoster.ensureSharedNotificationChannels(context)
        manager.cancelAll()
        AndroidUnreadNotificationPoster.resetGroupingState()
        AndroidUnreadNotificationPoster.summaryScheduler = { _, _ -> }
    }

    @After
    fun restore() {
        AndroidUnreadNotificationPoster.summaryScheduler = productionSummaryScheduler
    }

    @Test
    fun everyCategoryPostsOnItsContractChannel() {
        val dm = AndroidUnreadNotificationPoster.UNREAD_DM_NOTIFICATION_CHANNEL_ID
        val mention = AndroidUnreadNotificationPoster.UNREAD_MENTION_NOTIFICATION_CHANNEL_ID
        val channel = AndroidUnreadNotificationPoster.UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID
        val expected =
            mapOf(
                "dm" to dm,
                "mention" to mention,
                "task_assigned" to mention,
                "agent_attention" to mention,
                "reminder" to mention,
                "system_notice" to mention,
                "test" to mention,
                "task_status" to channel,
                "thread" to channel,
                "channel" to channel,
                // An older server's fixed value: the default channel.
                "RAFT_MESSAGE" to channel,
            )
        for ((category, channelId) in expected) {
            assertEquals(category, channelId, AndroidUnreadNotificationPoster.notificationChannelIdForCategory(category))
        }
        // The local realtime path of an older server: a mention still rides the HIGH channel.
        assertEquals(mention, AndroidUnreadNotificationPoster.notificationChannelIdForCategory("RAFT_MESSAGE", mentioned = true))
    }

    @Test
    fun aPushOfAnotherWorkspaceIsPostedNotDropped() {
        // No session at all here: before the contract this read "current_server_missing" and posted nothing.
        val result =
            AndroidUnreadNotificationPoster.postUnreadNotification(
                context,
                fcm(serverId = "server-other", conversationId = "channel-9", seq = "3", category = "mention")
            )
        // The poster's gates past the server one (permission, channel, launch intent) are not under
        // test here; what matters is that no server gate stops it.
        assertTrue(result.toString(), result["code"] !in setOf("server_mismatch", "current_server_missing", "server_missing"))
        if (result["code"] == "posted") {
            assertEquals(AndroidUnreadNotificationPoster.UNREAD_MENTION_NOTIFICATION_CHANNEL_ID, result["channelId"])
        }
    }

    @Test
    fun aRetractionClosesTheReadConversationAndKeepsNewerAndOtherOnes() {
        post(fcm(conversationId = "channel-1", messageId = "m-1", seq = "10"))
        post(fcm(conversationId = "channel-2", messageId = "m-2", seq = "4"))
        post(fcm(conversationId = "channel-3", messageId = "m-3", seq = "20"))
        assertEquals(3, conversations().size)

        val closedNewerKept =
            AndroidUnreadNotificationPoster.retract(context, RaftNotificationRetract("server-1", "server-1:channel-3", "channel-3", 19L))
        assertEquals("seq 20 is newer than the read position", 0, closedNewerKept)

        val closed =
            AndroidUnreadNotificationPoster.retract(context, RaftNotificationRetract("server-1", "server-1:channel-1", "channel-1", 10L))
        assertEquals(1, closed)
        assertNull(conversationOf("server-1:channel-1"))
        assertNotNull(conversationOf("server-1:channel-2"))
        assertNotNull(conversationOf("server-1:channel-3"))

        // throughSeq null: the whole group.
        assertEquals(1, AndroidUnreadNotificationPoster.retract(context, RaftNotificationRetract("server-1", "server-1:channel-2", "channel-2", null)))
        assertEquals(listOf("server-1:channel-3"), conversations().map { it.notification.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_GROUP_KEY) })
    }

    @Test
    fun aRetractionOfAPreContractPostMatchesItsConversation() {
        // No groupKey or seq (an older server, or the local path): the conversation key stands in.
        post(
            raftFcmUnreadNotificationRequest(
                mapOf("serverId" to "server-1", "channelId" to "channel-1", "messageId" to "m-1", "title" to "#general", "body" to "hi")
            )
        )
        assertEquals(1, AndroidUnreadNotificationPoster.retract(context, RaftNotificationRetract("server-1", "server-1:channel-1", "channel-1", 5L)))
        assertTrue(conversations().isEmpty())
    }

    @Test
    fun theAccountSoundSettingSilencesThePost() {
        val silent =
            AndroidUnreadNotificationPoster.buildUnreadNotification(
                context = context,
                channelId = AndroidUnreadNotificationPoster.UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID,
                request = fcm(conversationId = "channel-1", silent = "true"),
                previewMode = PushPreviewMode.Full,
                contentIntent = null
            )
        val loud =
            AndroidUnreadNotificationPoster.buildUnreadNotification(
                context = context,
                channelId = AndroidUnreadNotificationPoster.UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID,
                request = fcm(conversationId = "channel-1", silent = "false"),
                previewMode = PushPreviewMode.Full,
                contentIntent = null
            )
        assertTrue(AndroidUnreadNotificationPoster.unreadNotificationSilent(fcm(conversationId = "c", silent = "true")))
        assertTrue(!AndroidUnreadNotificationPoster.unreadNotificationSilent(fcm(conversationId = "c", silent = "false")))
        // NotificationCompat silences a child by moving its group alert to the (never-alerting) summary.
        assertEquals(NotificationCompat.GROUP_ALERT_CHILDREN, NotificationCompat.getGroupAlertBehavior(loud))
        assertTrue(NotificationCompat.getGroupAlertBehavior(silent) != NotificationCompat.GROUP_ALERT_CHILDREN)
        assertEquals(10L, silent.extras.getLong(AndroidUnreadNotificationGrouping.EXTRA_SEQ))
        assertEquals("server-1:channel-1", silent.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_GROUP_KEY))
    }

    private fun post(request: JSONObject) {
        val result =
            AndroidUnreadNotificationPoster.postGroupedUnreadNotification(
                context = context,
                channelId = AndroidUnreadNotificationPoster.UNREAD_CHANNEL_NOTIFICATION_CHANNEL_ID,
                request = request,
                previewMode = PushPreviewMode.Full,
                contentIntent = { null }
            )
        assertEquals(result.toString(), "posted", result["code"])
    }

    private fun conversations(): List<StatusBarNotification> =
        manager.activeNotifications.filter { it.tag == AndroidUnreadNotificationGrouping.CONVERSATION_TAG }

    private fun conversationOf(groupKey: String): StatusBarNotification? =
        conversations().singleOrNull { it.notification.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_GROUP_KEY) == groupKey }

    /** The FCM alert data of the unified contract (§4.3); every value is a string. */
    private fun fcm(
        serverId: String = "server-1",
        conversationId: String,
        messageId: String = "m-1",
        seq: String = "10",
        category: String = "channel",
        silent: String = "false"
    ): JSONObject =
        raftFcmUnreadNotificationRequest(
            mapOf(
                "type" to "alert",
                "serverId" to serverId,
                "channelId" to conversationId,
                "conversationId" to conversationId,
                "groupKey" to "$serverId:$conversationId",
                "seq" to seq,
                "messageId" to messageId,
                "kind" to "channel",
                "category" to category,
                "priority" to "normal",
                "silent" to silent,
                "title" to "#general",
                "body" to "Alice: hello",
                "notificationKey" to "message:$messageId"
            )
        )
}
