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
    private val upstreamBody =
        """
        {
          "error": "This message is older than the 30-day history on the Free plan.",
          "code": "history_limited",
          "channelId": "11111111-1111-4111-8111-111111111111",
          "targetMessageId": "22222222-2222-4222-8222-222222222222",
          "hiddenTarget": {
            "target": {
              "id": "22222222-2222-4222-8222-222222222222",
              "senderType": "user",
              "senderId": "33333333-3333-4333-8333-333333333333",
              "senderName": "Juno",
              "senderAvatarUrl": null,
              "senderGravatarHash": "",
              "createdAt": "2020-01-02T00:00:00.578Z",
              "preview": "Can we ship the release candidate today? The checklist is green except for the i",
              "seq": 42
            },
            "before": [
              {
                "id": "44444444-4444-4444-8444-444444444444",
                "senderType": "agent",
                "senderId": "55555555-5555-4555-8555-555555555555",
                "senderName": "Scout",
                "senderAvatarUrl": "https://example.invalid/a.png",
                "senderGravatarHash": null,
                "createdAt": "2020-01-01T00:00:00.000Z",
                "preview": "Earlier message"
              }
            ],
            "after": [],
            "thread": null,
            "replies": {
              "count": 2,
              "latest": {
                "id": "66666666-6666-4666-8666-666666666666",
                "senderType": "agent",
                "senderId": "55555555-5555-4555-8555-555555555555",
                "senderName": "Scout",
                "senderAvatarUrl": null,
                "senderGravatarHash": null,
                "createdAt": "2026-11-16T00:00:00.000Z",
                "preview": "A reply inside the window"
              }
            }
          }
        }
        """.trimIndent()

    @Test
    fun readsTheHiddenTargetOfTheUpstreamRefusal() {
        assertEquals(
            MessageContextHiddenTarget(
                messageId = "22222222-2222-4222-8222-222222222222",
                senderType = "user",
                senderId = "33333333-3333-4333-8333-333333333333",
                senderName = "Juno",
                preview = "Can we ship the release candidate today? The checklist is green except for the i",
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
