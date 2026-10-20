package build.raft.app

import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageUpdated
import build.raft.app.model.Presence
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedThreadRealtimeTimestampTest {
    @Test
    fun messageUpdatedCannotRewriteCreationTimeOrTimelineOrder() = runPortableCoroutineTest {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val dataSource =
                testSharedThreadDataSource(
                    networkApi = SharedThreadStoreTest.FakeThreadApi(bodyText = canonicalMessagesResponse()),
                    sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
                )
            val store = SharedThreadStore(dataSource = dataSource, scope = storeScope)
            store.accept(
                SharedThreadIntent.Start(
                    RaftThreadScreenPayload(
                        instanceId = "task906-thread-channel-1",
                        title = "general",
                        sourceThreadId = "channel-1",
                        status = "loading",
                        canSend = true
                    )
                )
            )
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageUpdated(
                        Message(
                            id = MessageId("m1"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            seq = 30,
                            author = member("u1", "Artin"),
                            timestampLabel = "2026-07-28T19:32:00+08:00",
                            createdAtEpochMillis = 1_785_238_320_000L,
                            updatedAtLabel = "2026-07-28T19:32:00+08:00",
                            body = "first updated"
                        )
                    )
                )
            )

            assertEquals(listOf("m1", "m2"), store.state.value.payload.messages.map { it.id })
            assertEquals(
                listOf("2026-07-28T19:30:00+08:00", "2026-07-28T19:31:00+08:00"),
                store.state.value.payload.messages.map { it.timestampLabel }
            )
            assertEquals(
                listOf(1_785_238_200_000L, 1_785_238_260_000L),
                store.state.value.payload.messages.map { it.createdAtEpochMillis }
            )
            assertEquals("first updated", store.state.value.payload.messages.first().body)
        } finally {
            storeScope.cancel()
        }
    }

    private fun member(id: String, name: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = name.lowercase(),
            kind = MemberKind.Human,
            presence = Presence.Active
        )

    private fun canonicalMessagesResponse(): String =
        // Canonical GET /api/messages/channel/:channelId shape verified at
        // server 263f3b9d: packages/server/src/routes/messages.ts returns
        // {messages, historyLimited}; listMessages projects these exact row fields.
        """
        {
          "historyLimited": false,
          "messages": [
            {
              "id": "m1",
              "seq": 30,
              "channelId": "channel-1",
              "content": "first",
              "createdAt": "2026-07-28T19:30:00+08:00",
              "senderId": "u1",
              "senderName": "Artin",
              "senderType": "user",
              "messageType": "chat"
            },
            {
              "id": "m2",
              "seq": 31,
              "channelId": "channel-1",
              "content": "second",
              "createdAt": "2026-07-28T19:31:00+08:00",
              "senderId": "u1",
              "senderName": "Artin",
              "senderType": "user",
              "messageType": "chat"
            }
          ]
        }
        """.trimIndent()
}
