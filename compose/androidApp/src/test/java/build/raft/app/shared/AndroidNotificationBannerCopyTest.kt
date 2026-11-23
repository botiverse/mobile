package build.raft.app.shared

import android.app.Application
import android.app.Notification
import androidx.core.app.NotificationCompat
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.RaftNotificationLocalization
import build.raft.app.data.buildSystemUnreadNotificationRequestFromPush
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.ServerId
import build.raft.app.push.raftFcmUnreadNotificationRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Banner copy in the Android tray (slock docs/contracts/notifications.md §1.5, Android row of the copy
 * spec; FCM transport fcm.ts and mobile socket mobileSocket.ts @ fbc091209976f3d22d58f0cc65e330599c45bccf):
 * title = the conversation, subtitle = `setSubText`, body in a MessagingStyle line per message,
 * localization keys in the device language with the English fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidNotificationBannerCopyTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun aJoinedTitleIsSplitAndTheSubtitleIsTheSubText() {
        // FCM to an app of key set 1 / 2, and the mobile socket: `title · subtitle`.
        val joined = build(fcm("title" to "Thread in #general · Acme", "subtitle" to "Acme"))
        assertEquals("Thread in #general", joined.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_TITLE))
        // On Android 12+ (SDK 34), MessagingStyle ConversationLayout hides setSubText, so a non-DM
        // conversationTitle includes the subtitle to keep the server label visible on standalone cards.
        assertEquals("Thread in #general · Acme", titleOf(joined))
        assertEquals("Acme", joined.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals("Acme", joined.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_NAME))
        // FCM to key set 3: the plain title and the subtitle on its own.
        val plain = build(fcm("title" to "Thread in #general", "subtitle" to "Acme"))
        assertEquals("Thread in #general", plain.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_TITLE))
        assertEquals("Thread in #general · Acme", titleOf(plain))
        assertEquals("Acme", plain.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals("Acme", plain.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_NAME))
        // A receiver of one server: no subtitle or server name at all.
        val singleServer = build(fcm())
        assertEquals("#general", titleOf(singleServer))
        assertNull(singleServer.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertNull(singleServer.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_NAME))
    }

    @Test
    fun serverNameExtractsFromTaskSubtitlesWithoutMisreadingSingleServerChannel() {
        val dmTaskMulti =
            build(
                fcm(
                    "category" to "task_status",
                    "title" to "#4 Stamp record",
                    "body" to "Edgar changed title",
                    "subtitle" to "DM · Raft",
                    "subtitleKey" to "push.subtitle.dm_server",
                    "subtitleArgs" to """["Raft"]"""
                )
            )
        assertEquals("Raft", dmTaskMulti.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_NAME))

        val channelTaskMulti =
            build(
                fcm(
                    "category" to "task_status",
                    "title" to "#4 Stamp record",
                    "body" to "Edgar changed title",
                    "subtitle" to "#general · Raft"
                )
            )
        assertEquals("Raft", channelTaskMulti.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_NAME))

        val dmTaskSingle =
            build(
                fcm(
                    "category" to "task_status",
                    "title" to "#4 Stamp record",
                    "body" to "Edgar changed title",
                    "subtitle" to "DM",
                    "subtitleKey" to "push.subtitle.dm"
                )
            )
        assertNull(dmTaskSingle.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_NAME))

        val channelTaskSingle =
            build(
                fcm(
                    "category" to "task_status",
                    "title" to "#4 Stamp record",
                    "body" to "Edgar changed title",
                    "subtitle" to "#general"
                )
            )
        assertNull(channelTaskSingle.extras.getString(AndroidUnreadNotificationGrouping.EXTRA_SERVER_NAME))
    }

    private fun baseFcm(): Map<String, String> =
        mapOf(
            "serverId" to "server-1",
            "channelId" to "channel-1",
            "messageId" to "message-1",
            "kind" to "channel",
            "category" to "channel",
            "title" to "#general",
            "body" to "Alice: hello",
            "notificationKey" to "server-1:channel-1",
            "groupKey" to "server-1:channel-1"
        )

    private fun fcm(vararg pairs: Pair<String, String>): JSONObject = raftFcmUnreadNotificationRequest(baseFcm() + pairs.toMap())

    private fun build(request: JSONObject, mode: PushPreviewMode = PushPreviewMode.Full): Notification =
        AndroidUnreadNotificationPoster.buildUnreadNotification(
            context = context,
            channelId = "test-channel",
            request = request,
            previewMode = mode,
            contentIntent = null,
            whenMillis = 5_000L
        )

    private fun titleOf(notification: Notification): String =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?.let { it.conversationTitle ?: it.messages.last().person?.name }
            ?.toString()
            ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
}
