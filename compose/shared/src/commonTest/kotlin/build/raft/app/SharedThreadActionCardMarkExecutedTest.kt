package build.raft.app

import build.raft.app.SharedThreadStoreMembersAndActionCardsTest.RecordingActionCardsApi
import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ChannelRepository
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.CreateChannelResult
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftIntegrationsActionsPushApi
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.toHomeChannelRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MessageId
import build.raft.app.model.MessageUpdated
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Device test B2 (Android, staging 2026-10-26): Create Channel / Add Members run the App's own
 * create call, then POST /actions/:id/mark-executed. The server emits the card's realtime
 * `message:updated` before it answers (botiverse/slock actionCardsService.markActionCardExecuted
 * -> emitActionCardMessageUpdated, then routes/actions.ts `res.json`), so the done card with its
 * committed-by line and result is on screen when the HTTP answer lands. Applying that answer must
 * keep it.
 */
class SharedThreadActionCardMarkExecutedTest {
    private val channelId = "4f1c2b3a-5d6e-4f70-8a9b-0c1d2e3f4a5b"
    private val agentId = "7b2e8f3a-0c5d-4e4f-9a3b-2c3d4e5f6071"
    private val executedAt = "2026-10-26T13:23:15.301Z"

    // routes/actions.ts mark-executed: `{ messageId, metadata: withStoredActionCardSpec(out.metadata) }`,
    // where metadata is actionCardsService's `nextMeta` (the same object the realtime row carries).
    private val serverMetadata =
        """
        {
          "kind":"action-card",
          "sourceServerId":"server-1",
          "action":{"type":"channel:create","name":"a4-created-room","visibility":"public","initialAgents":["$agentId"]},
          "presentation":{
            "title":"Create #a4-created-room",
            "confirmLabel":"Create Channel",
            "card":{
              "catalog":"action-card.v1",
              "icon":"hash",
              "title":{"key":"actionCard.title.createChannel","text":{"en":"Create #{channel}"},"params":{"channel":"a4-created-room"}},
              "spec":{"root":"root","elements":{
                "root":{"type":"Stack","props":{},"children":["name","create"]},
                "name":{"type":"Field","props":{"label":{"key":"actionCard.field.name","text":{"en":"Name"}},"value":"#a4-created-room"}},
                "create":{"type":"Button","props":{"label":{"key":"actionCard.createChannel.create","text":{"en":"Create Channel"}},"emphasis":"primary","action":{"kind":"open","intent":"create_channel"}}}
              }},
              "result":{"root":"root","elements":{
                "root":{"type":"Stack","props":{},"children":["channel","openChannel"]},
                "channel":{"type":"Field","props":{"label":{"key":"actionCard.field.channel","text":{"en":"Channel"}},"value":[{"kind":"channel","id":"$channelId","fallback":"a4-created-room"}]}},
                "openChannel":{"type":"Button","props":{"label":{"key":"actionCard.channel.edit.returnToChannel","text":{"en":"Open Channel"}},"emphasis":"secondary","action":{"kind":"navigate","target":{"kind":"channel","id":"$channelId"}}}}
              }}
            }
          },
          "state":"executed",
          "executedAt":"$executedAt",
          "executedByUserId":"u1",
          "executedByUserName":"Lagoon Push Test",
          "result":{"kind":"channel","id":"$channelId","name":"a4-created-room"}
        }
        """.trimIndent()

    private val realtimeFrame =
        """
        {
          "id":"m-card",
          "channelId":"channel-1",
          "seq":1,
          "senderType":"agent",
          "senderId":"$agentId",
          "senderName":"cardbot",
          "messageType":"chat",
          "content":"",
          "createdAt":"2026-10-26T13:20:00.000Z",
          "updatedAt":"$executedAt",
          "actionMetadata":$serverMetadata
        }
        """.trimIndent()

    @Test
    fun markExecutedAnswerAfterRealtimeKeepsTheServersDoneCard() = runTest {
        // The current server answers with the stored card; an older one answered without it,
        // and then only the App's own rebuilt card is left, which must not replace the realtime one.
        val answers =
            mapOf(
                "server card" to """{"messageId":"m-card","metadata":$serverMetadata}""",
                "no card" to """{"messageId":"m-card","metadata":{"kind":"action-card"}}""",
            )
        for ((case, answer) in answers) {
            val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            lateinit var store: SharedThreadStore
            val realtime =
                assertIs<MessageUpdated>(RealtimeEventMapper(diagnosticsLogger = {}).map(RealtimeSocketEvents.MessageUpdated, realtimeFrame))
            val recording = RecordingActionCardsApi(markResponse = HomeNetworkResponse(code = 200, bodyText = answer))
            val actionsApi =
                object : RaftIntegrationsActionsPushApi by recording {
                    override suspend fun markActionExecuted(messageId: MessageId, body: String): HomeNetworkResponse {
                        // The realtime row lands while the HTTP call is still out.
                        store.accept(SharedThreadIntent.ApplyRealtimeEvent(realtime))
                        return recording.markActionExecuted(messageId, body)
                    }
                }
            val dataSource =
                testSharedThreadDataSource(
                    storeScope = backgroundScope,
                    networkApi = FakeThreadApi(),
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    channelRepository = createdChannelRepository(),
                    actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionsApi),
                )
            val prepared =
                ThreadActionCard(type = "channel:create", name = "a4-created-room", visibility = "public", initialAgents = listOf(agentId))
            dataSource.cachePayloadForTest(contentPayload("", id = "m-card").withActionCard("m-card", prepared))
            store = SharedThreadStore(dataSource = dataSource, scope = scope)
            store.accept(SharedThreadIntent.Start(basePayload()))

            val committed =
                assertIs<DataResult.Content<ThreadActionCard>>(
                    dataSource.commitThreadActionCard(store.state.value.payload, "m-card", prepared),
                    case,
                )
            // ThreadPage applies what the commit returns.
            store.accept(SharedThreadIntent.ApplyActionCard("m-card", committed.data))

            val shown = assertNotNull(store.state.value.payload.messages.single { it.id == "m-card" }.threadActionCard(), case)
            assertEquals("executed", shown.state, case)
            assertEquals("Lagoon Push Test", shown.executedByUserName, case)
            assertEquals(executedAt, shown.executedAt, case)
            assertEquals("channel" to channelId, shown.resultKind to shown.resultId, case)
            val result = assertNotNull(shown.presentationCard?.result, "$case: the card's result subtree")
            val open = assertIs<ActionCardSpecButton>(result.element("openChannel"), case)
            assertIs<ActionCardSpecAction.Navigate>(open.action, case)
            assertEquals(1, recording.calls.count { it.startsWith("POST actions/m-card/mark-executed") }, case)
            scope.cancel()
        }
    }

    private fun createdChannelRepository(): ChannelRepository =
        object : ChannelRepository by FakeChannelRepository() {
            override suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult> =
                DataResult.Content(
                    CreateChannelResult(
                        channel =
                            ConversationSummary(
                                threadId = ThreadId(channelId),
                                channelId = ChannelId(channelId),
                                kind = ConversationKind.Channel,
                                name = request.name,
                            ).toHomeChannelRow(),
                        addedAgentMemberIds = request.agentMemberIds,
                        addedUserMemberIds = request.userMemberIds,
                    )
                )
        }
}
