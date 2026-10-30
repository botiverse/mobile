package build.raft.app

import build.raft.app.data.buildSystemUnreadNotificationRequest
import build.raft.app.data.parseSharedChannelMessages
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.ThreadId
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun serverSentNamesAreShownAsIsInEveryLanguage() {
        val named =
            listOf(
                parsed(""""senderType": "external_projection", "senderId": "e1", "externalAuthor": {"displayName": "Grace"},"""),
                parsed(""""senderType": "external_projection", "senderId": "e2", "senderName": "Linus","""),
                // A server-sent "Unknown" (or even "External user") is a real name: no client-side filtering.
                parsed(""""senderType": "external_projection", "senderId": "e3", "externalAuthor": {"displayName": "Unknown"},"""),
                parsed(""""senderType": "external_projection", "senderId": "e4", "senderName": "External user","""),
                parsed(""""senderType": "user", "senderId": "u1", "senderName": "Ada","""),
            )
        val expectedNames = listOf("Grace", "Linus", "Unknown", "External user", "Ada")
        expectedFallback.keys.forEach { tag ->
            StringDesc.localeType = StringDesc.LocaleType.Custom(tag)
            named.zip(expectedNames).forEach { (message, expected) ->
                assertFalse(message.externalAuthor?.displayNameIsFallback == true, "${message.author.id.value} flagged")
                assertEquals(expected, message.shownAuthorName(), "message ${message.author.id.value}, locale $tag")
                assertEquals(
                    expected,
                    message.toRaftThreadMessageItem(currentUserId = "me").shownAuthorName(),
                    "row ${message.author.id.value}, locale $tag",
                )
                assertEquals(expected, notificationSender(message), "notification ${message.author.id.value}, locale $tag")
            }
        }
    }
}
