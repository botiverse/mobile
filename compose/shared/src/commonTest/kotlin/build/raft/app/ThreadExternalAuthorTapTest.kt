package build.raft.app

import build.raft.app.data.parseSharedChannelMessages
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Avatar/name tap on an External (Slack-bridge) message: it always shows the external hint and
 * never opens a profile, including for a fallback author with a synthetic sender key.
 */
class ThreadExternalAuthorTapTest {
    private val providerTemplate = "External __RAFT_VALUE__ user"
    private val genericHint = "External user"

    private fun parsedRow(senderJson: String): RaftThreadMessageItem {
        val message =
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
        return message.toRaftThreadMessageItem(currentUserId = "me")
    }

    @Test
    fun anonymousExternalAuthorTapShowsGenericHintAndNeverNavigates() {
        val row = parsedRow(""""senderType": "third_party_app", "senderId": "",""")

        assertEquals("external:message:m-ext", row.authorId)
        assertTrue(assertNotNull(row.externalAuthor).displayNameIsFallback)
        assertTrue(row.canOpenThreadAuthorProfile(shareSelectionActive = false))
        assertEquals(ThreadAuthorTapAction.ExternalHint, row.threadAuthorTapAction())
        assertEquals(genericHint, externalAuthorHintText(row, providerTemplate, genericHint))
    }

    @Test
    fun externalAuthorWithProviderTapShowsProviderHint() {
        val row =
            parsedRow(
                """"senderType": "external_projection", "senderId": "ext-1", "externalAuthor": {"provider": "slack"},""",
            )

        assertEquals(ThreadAuthorTapAction.ExternalHint, row.threadAuthorTapAction())
        assertEquals("External Slack user", externalAuthorHintText(row, providerTemplate, genericHint))
    }

    @Test
    fun memberAuthorTapStillOpensProfile() {
        val row = parsedRow(""""senderType": "user", "senderId": "u1", "senderName": "Ada",""")

        val action = assertIs<ThreadAuthorTapAction.OpenProfile>(row.threadAuthorTapAction())
        assertEquals("u1", action.identity.memberId.value)
    }
}
