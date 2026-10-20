package build.raft.app

import build.raft.app.data.PushPreviewMode
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The in-app foreground banner follows the device push preview choice like the tray notification. */
class RaftForegroundBannerPushPreviewTest {
    private val strings =
        RaftPushPreviewBannerStrings(
            hiddenTitle = "Raft",
            hiddenMessage = "You have a new message",
            hiddenMention = "You were mentioned",
            senderOnlyMessageTemplate = "${RaftPushPreviewBannerStrings.SENDER_PLACEHOLDER} sent a message",
            senderOnlyMentionTemplate = "${RaftPushPreviewBannerStrings.SENDER_PLACEHOLDER} mentioned you"
        )

    @Test
    fun fullModeLeavesTheBannerUntouched() {
        val banner = banner()
        assertSame(banner, banner.redactedForPushPreview(PushPreviewMode.Full, SENDER, mentioned = false, strings))
    }

    @Test
    fun senderOnlyKeepsTheConversationAndNamesOnlyTheSender() {
        val shown = banner().redactedForPushPreview(PushPreviewMode.SenderOnly, SENDER, mentioned = true, strings)
        assertEquals(TITLE, shown.title)
        assertEquals("$SENDER mentioned you", shown.body)
        assertNull(shown.threadContext)
        assertNoMessageText(shown)

        val unnamed = banner().redactedForPushPreview(PushPreviewMode.SenderOnly, null, mentioned = false, strings)
        assertEquals("You have a new message", unnamed.body)
    }

    @Test
    fun hiddenDropsConversationServerSenderAndAvatar() {
        val shown = banner().redactedForPushPreview(PushPreviewMode.Hidden, SENDER, mentioned = false, strings)
        assertEquals("Raft", shown.title)
        assertEquals("", shown.context)
        assertEquals("You have a new message", shown.body)
        assertNull(shown.threadContext)
        assertNull(shown.senderMemberId)
        assertNull(shown.avatarWireValue)
        for (text in listOf(shown.title, shown.context, shown.body, shown.avatarLabel)) {
            for (secret in listOf(MESSAGE_TEXT, SENDER, "secret-channel", "Secret Server")) {
                assertFalse(text.contains(secret), "'$secret' in '$text'")
            }
        }
        // Tapping still opens the conversation.
        assertEquals(banner().openRequest, shown.openRequest)
    }

    private fun assertNoMessageText(banner: RaftForegroundMessageBanner) {
        for (text in listOfNotNull(banner.title, banner.context, banner.threadContext, banner.body)) {
            assertFalse(text.contains(MESSAGE_TEXT), text)
        }
    }

    private fun banner(): RaftForegroundMessageBanner {
        val conversation =
            ThreadLaunchConversationIdentity(
                threadId = ThreadId("thread-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel
            )
        return RaftForegroundMessageBanner(
            id = "push:server-1:message-1",
            serverId = "server-1",
            focusedMessageId = "message-1",
            conversation = conversation,
            openRequest = HomeMessageOpenRequest(conversation, "message-1"),
            title = TITLE,
            context = "secret-channel",
            threadContext = "Replying to $MESSAGE_TEXT",
            body = "$SENDER: $MESSAGE_TEXT",
            iconKind = RaftForegroundMessageBannerIconKind.Channel,
            iconName = null,
            senderMemberId = "member-1",
            avatarWireValue = "avatar",
            avatarLabel = "AL"
        )
    }

    private companion object {
        const val MESSAGE_TEXT = "SECRET launch code 4242"
        const val SENDER = "Alice"
        const val TITLE = "#secret-channel · Secret Server"
    }
}
