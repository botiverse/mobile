package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `messages/context` refusal for a message behind the plan's history window. Shape from
 * botiverse/slock@d1cdd247a: packages/server/src/routes/messages.ts `messageRouter.get("/context/:messageId")`
 * (`{ ...humanHistoryLimitedError, channelId, targetMessageId, canonicalTarget?, hiddenTarget }`),
 * services/historyWindow.ts `humanHistoryLimitedError`, services/messageService.ts `HiddenTargetPreview`
 * / `HiddenHistoryPreviewMessage`; assertions mirror routes/messages.api.test.ts ("A hidden target: still a 404").
 */
class MessageContextHistoryLimitedApiTest {
    private val upstreamBody = messageContextHistoryLimitedUpstreamBody

    @Test
    fun readsTheHiddenTargetOfTheUpstreamRefusal() {
        assertEquals(
            MessageContextHiddenTarget(
                messageId = "22222222-2222-4222-8222-222222222222",
                senderType = "user",
                senderId = "33333333-3333-4333-8333-333333333333",
                senderName = "Juno",
                preview = "Can we ship the release candidate today? The checklist is green except for the i",
                createdAt = "2020-01-02T00:00:00.578Z",
            ),
            parseMessageContextHiddenTarget(upstreamBody)
        )
    }

    @Test
    fun aTextlessOrUnnamedTargetKeepsItsIdentity() {
        val body =
            upstreamBody
                .replace("\"senderName\": \"Juno\"", "\"senderName\": null")
                .replace(
                    "\"preview\": \"Can we ship the release candidate today? The checklist is green except for the i\"",
                    "\"preview\": null"
                )
        val target = parseMessageContextHiddenTarget(body)
        assertEquals("22222222-2222-4222-8222-222222222222", target?.messageId)
        assertNull(target?.senderName)
        assertNull(target?.preview)
    }

    @Test
    fun readsSenderAvatarUrlWhenPresent() {
        val body =
            upstreamBody.replace(
                "\"senderAvatarUrl\": null",
                "\"senderAvatarUrl\": \"https://cdn.raft.build/avatars/juno.png\"",
            )
        val target = parseMessageContextHiddenTarget(body)
        assertEquals("https://cdn.raft.build/avatars/juno.png", target?.senderAvatarUrl)
    }

    @Test
    fun anyOtherBodyIsNotAHiddenTarget() {
        // The plain not found (a missing message, or one this person may not read).
        assertNull(parseMessageContextHiddenTarget("""{"error":"Message not found"}"""))
        // A by-id action's refusal: the code without a hidden target.
        assertNull(
            parseMessageContextHiddenTarget(
                """{"error":"This message is older than the 30-day history on the Free plan.","code":"history_limited"}"""
            )
        )
        // Another code with the same body, an agent-style upper-case code, a target without its seq.
        assertNull(parseMessageContextHiddenTarget(upstreamBody.replace("\"history_limited\"", "\"other_code\"")))
        assertNull(parseMessageContextHiddenTarget(upstreamBody.replace("\"history_limited\"", "\"HISTORY_LIMITED\"")))
        assertNull(parseMessageContextHiddenTarget(upstreamBody.replace("\"seq\": 42", "\"seq\": null")))
        // No guessed wrappers: the refusal is the body itself.
        assertNull(parseMessageContextHiddenTarget("""{"data":$upstreamBody}"""))
        assertNull(parseMessageContextHiddenTarget(""))
        assertNull(parseMessageContextHiddenTarget("not json"))
    }
}
