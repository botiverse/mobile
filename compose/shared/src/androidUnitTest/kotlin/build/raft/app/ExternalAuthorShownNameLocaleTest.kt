package build.raft.app

import build.raft.app.data.buildSystemUnreadNotificationRequest
import build.raft.app.data.parseSharedChannelMessages
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.ThreadId
import build.raft.app.resources.StringDesc
import build.raft.app.shownAuthorName
import build.raft.app.thread.shownAuthorName
import build.raft.app.thread.toRaftThreadMessageItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An External sender the wire did not name (#3990 fallback author) reads as the localized
 * "External user" on every surface that shows a sender name outside the message header; a name
 * the server sent is shown as is in every language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalAuthorShownNameLocaleTest {
    private val originalLocaleType = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = originalLocaleType
    }

    private val expectedFallback = mapOf("en" to "External user", "zh-CN" to "外部用户", "ja" to "外部ユーザー")

    private fun parsed(senderJson: String): Message =
        parseSharedChannelMessages(
            """
            {
              "messages": [{
                "id": "m-ext",
                "seq": 1,
                "channelId": "c1",
                $senderJson
                "messageType": "chat",
                "content": "bridged",
                "createdAt": "2026-10-30T01:00:00.000Z"
              }]
            }
            """.trimIndent(),
            fallbackThreadId = ThreadId("c1"),
            diagnostics = {},
        ).messages.single()

    private fun notificationSender(message: Message): String? =
        buildSystemUnreadNotificationRequest(
            serverId = "s1",
            message = message,
            currentMemberId = "me",
            isVisibleThread = false,
            conversationName = null,
            conversationKind = ConversationKind.DirectMessage,
        )?.senderName

    @Test
    fun unnamedExternalSenderReadsInTheAppLanguage() {
        val message = parsed(""""senderType": "third_party_app", "senderId": "",""")
        assertTrue(assertNotNull(message.externalAuthor).displayNameIsFallback)
        val row = message.toRaftThreadMessageItem(currentUserId = "me")
        expectedFallback.forEach { (tag, expected) ->
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            assertEquals(expected, externalAuthorFallbackName(), "locale $tag")
            assertEquals(expected, message.shownAuthorName(), "message, locale $tag")
            assertEquals(expected, row.shownAuthorName(), "row, locale $tag")
            assertEquals(expected, notificationSender(message), "notification, locale $tag")
        }
    }
}
