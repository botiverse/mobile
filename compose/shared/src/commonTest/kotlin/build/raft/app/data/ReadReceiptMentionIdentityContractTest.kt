package build.raft.app.data

import build.raft.app.model.MessageMention
import build.raft.app.model.ThreadId
import build.raft.app.toRaftThreadMessageItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two identity inputs of the read_receipts_v0 mention badge (web `MentionLink.tsx` /
 * `MessageItem.tsx`), shapes from botiverse/slock staging dac70954080092d54811442e24f900b2bb5df0bc:
 *  - `managedByAppInstallId` on `GET /agents` rows (full agent row spread in `routes/agents.ts`
 *    `agentRouter.get("/")`; asserted on `GET /agents/:id` in `routes/integrations.api.test.ts`);
 *  - `mentions: [{ type, id, name }]` on REST message rows (`services/messageService.ts`
 *    `ResolvedMentionFact`, `enrichWithSenderNames`; asserted in `routes/messages.api.test.ts`).
 */
class ReadReceiptMentionIdentityContractTest {
    @Test fun agentsListCarriesTheAppManagedInstallId() {
        val members = parseSharedSettingsAgentsAsMembers(
            """[
              {"id":"agent-bot","serverId":"srv-1","name":"release-bot","displayName":"Release Bot","status":"active",
               "runtime":"external","managedByAppInstallId":"install-1",
               "managedByApp":{"clientId":"client-1","name":"Attributed Bot App","logoUrl":null,"description":null,"botStatusText":null}},
              {"id":"agent-plain","serverId":"srv-1","name":"alpha","status":"active","runtime":"claude","managedByAppInstallId":null},
              {"id":"agent-guest-view","serverId":"srv-1","name":"beta","status":"active","profileProjection":"channel_summary"}
            ]""",
        )!!
        assertEquals("install-1", members.single { it.id.value == "agent-bot" }.managedByAppInstallId)
        assertNull(members.single { it.id.value == "agent-plain" }.managedByAppInstallId)
        assertNull(members.single { it.id.value == "agent-guest-view" }.managedByAppInstallId)
    }

    @Test fun restMessageRowsCarryResolvedMentionsToTheTimelineRow() {
        val payload = parseSharedChannelMessages(
            """{"messages":[{"id":"m-1","seq":7,"channelId":"c1","senderType":"user","senderId":"user-me",
                "senderName":"me","messageType":"chat","content":"@alpha and @carol please look",
                "createdAt":"2026-10-22T09:00:00.000Z",
                "mentions":[{"type":"agent","id":"agent-alpha","name":"alpha"},{"type":"user","id":"user-carol","name":"carol"},
                            {"type":"external","id":"","name":"ada"}]}]}""",
            fallbackThreadId = ThreadId("c1"),
        )
        val expected = listOf(MessageMention("agent", "agent-alpha", "alpha"), MessageMention("user", "user-carol", "carol"))
        val message = payload.messages.single()
        assertEquals(expected, message.mentions)
        assertEquals(expected, message.toRaftThreadMessageItem("user-me").mentions)
    }
}
