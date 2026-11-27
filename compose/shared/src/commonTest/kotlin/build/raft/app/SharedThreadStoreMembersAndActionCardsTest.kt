package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.directMessagePayload
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.data.AddChannelMemberRequest
import build.raft.app.data.AddChannelMembersBatchRequest
import build.raft.app.data.CreateChannelRequest
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.MemberSnapshot
import build.raft.app.data.RaftAgentsApi
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftIntegrationsActionsPushApi
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardMetadataItem
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadReplyMetadata
import build.raft.app.thread.key
import build.raft.app.thread.threadInlineReplyPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class SharedThreadStoreMembersAndActionCardsTest {
    @Test
    fun loadMembersEnrichesThreadMembersFromRepository() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val serverMembers =
            listOf(
                member("u1", "Artin"),
                MemberProfile(
                    id = MemberId("agent-2"),
                    displayName = "Cindy",
                    handle = "cindy",
                    kind = MemberKind.Agent,
                    presence = Presence.Active
                )
            )
        val memberRepository =
            FakeMemberRepository(
                serverMembers = serverMembers,
                channelMembers =
                    listOf(
                        member("u1", "Artin"),
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Android Bot",
                            handle = "android-bot",
                            kind = MemberKind.Agent,
                            presence = Presence.Active
                        )
                    )
            )
        val serverMembersStore = testServerMembersStore(memberRepository, scope = backgroundScope)
        serverMembersStore.seed(
            MemberSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                members = serverMembers
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        val beforeMessages = store.state.value.payload.messages
        assertTrue(memberRepository.channelMemberRequests.isEmpty())
        assertTrue(store.state.value.payload.members.isEmpty())

        store.accept(SharedThreadIntent.FirstContentReady)
        assertEquals(2, store.state.value.payload.membersCount)
        assertEquals(listOf(ChannelId("channel-1")), memberRepository.channelMemberRequests)
        assertEquals(listOf("u1", "agent-1"), store.state.value.payload.members.map { it.id })
        assertEquals(listOf("agent-2"), store.state.value.payload.memberCandidates.map { it.id })

        store.accept(SharedThreadIntent.LoadMembers)
        assertEquals(
            listOf(ChannelId("channel-1")),
            memberRepository.channelMemberRequests
        )

        val payload = store.state.value.payload
        assertTrue(beforeMessages === payload.messages)
        assertEquals(2, payload.membersCount)
        assertEquals(listOf("u1", "agent-1"), payload.members.map { it.id })
        assertEquals(listOf("agent-2"), payload.memberCandidates.map { it.id })
        store.accept(SharedThreadIntent.LoadMembers)
        assertEquals(
            listOf(ChannelId("channel-1")),
            memberRepository.channelMemberRequests
        )
        scope.cancel()
    }
    @Test
    fun loadMembersRetriesAfterTransientFailure() = runTest {
        // Verifies the loadMembers idempotency contract: a transient
        // failure on the first call must NOT set status=Loaded, so the
        // next modal open / explicit LoadMembers re-fetches.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val flakyMemberRepository =
            FakeMemberRepository(
                serverMembers = listOf(member("u1", "Artin")),
                channelMembers = listOf(member("u1", "Artin")),
                failChannelMembersFirstCalls = 1
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = flakyMemberRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(0, flakyMemberRepository.channelMemberCalls, "members must wait for first content readiness")

        store.accept(SharedThreadIntent.FirstContentReady)
        assertEquals(1, flakyMemberRepository.channelMemberCalls, "first content readiness starts background members")

        store.accept(SharedThreadIntent.LoadMembers)
        val callsAfterFirstLoad = flakyMemberRepository.channelMemberCalls
        assertEquals(2, callsAfterFirstLoad, "First explicit LoadMembers should retry after background failure")

        store.accept(SharedThreadIntent.LoadMembers)
        val callsAfterRetry = flakyMemberRepository.channelMemberCalls
        assertEquals(
            2,
            callsAfterRetry,
            "After successful retry, the next LoadMembers should not fetch again"
        )
        // And on the second attempt the request succeeded, so the
        // payload should now have the channel member.
        assertEquals(listOf("u1"), store.state.value.payload.members.map { it.id })
        // A third LoadMembers should now be a no-op (Loaded).
        store.accept(SharedThreadIntent.LoadMembers)
        assertEquals(
            2,
            flakyMemberRepository.channelMemberCalls,
            "After successful retry, status should be Loaded and no further fetch should happen"
        )
        scope.cancel()
    }
    /**
     * Hands b6068b41: on a DM page, replies posted from another device arrived as `thread:updated`
     * and moved the inline preview's count while its rows stayed stale. The DM route carries no
     * channel kind, so the replies page scope was derived as "channel" and every upstream frame
     * (parentScopeKey.scopeKind = "dm") was rejected. Also covers the cold-cache path: the first
     * live frame must fold into the cached rows instead of replacing them with a one-row window.
     */
    @Test
    fun dmRealtimeThreadUpdatedPatchesColdCachedInlineReplyRows() =
        assertRealtimeThreadUpdatedPatchesColdCachedInlineReplyRows(
            route = directMessagePayload(),
            parentScopeKind = "dm",
            threadChannelId = "dm-thread-1",
        )
    private fun assertRealtimeThreadUpdatedPatchesColdCachedInlineReplyRows(
        route: RaftThreadScreenPayload,
        parentScopeKind: String,
        threadChannelId: String,
    ) = runTest {
        val parentScopeId = requireNotNull(route.sourceThreadId)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        fun reply(seq: Long, senderId: String, senderType: String) =
            ReplyPreview(
                messageId = "reply-$seq",
                seq = seq,
                content = "reply $seq",
                senderId = senderId,
                senderType = senderType,
                senderName = senderId,
                createdAt = "2026-10-20T01:2$seq:00.000Z",
            )
        val cachedReplies =
            build.raft.app.network.sync.initialThreadRepliesReadModel(
                replies =
                    listOf(
                        reply(1, "sindresorhus", "agent"),
                        reply(2, "u1", "user"),
                        reply(3, "sindresorhus", "agent"),
                    ),
                replyCount = 3,
                limit = build.raft.app.network.sync.InlineThreadReplyPreviewLimit,
            )
        val cached =
            contentPayloadForRoute(route, "parent").let { payload ->
                payload.copy(
                    messages =
                        listOf(
                            payload.messages.single().copy(
                                threadChannelId = threadChannelId,
                                replyCount = 3,
                                inlineReplies = cachedReplies,
                            )
                        )
                )
            }
        dataSource.cachePayloadForTest(cached)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(route))
        assertEquals(
            listOf("reply-1", "reply-2", "reply-3"),
            store.state.value.payload.messages.single().inlineReplies?.replies?.map { it.messageId },
        )

        // Upstream shape: messageService.ts `thread:updated`
        // ({ parentMessageId, ...getThreadInfo, threadChannelId, syncCoreReplyWindow, latestReply }).
        fun threadUpdatedJson(seq: Long, replyCount: Int): String =
            """
            {
              "parentMessageId": "m1",
              "threadChannelId": "$threadChannelId",
              "replyCount": $replyCount,
              "lastReplyAt": "2026-10-20T01:2$seq:30.000Z",
              "participantIds": ["sindresorhus", "u1"],
              "syncCoreReplyWindow": {
                "producer": "message-service.thread-replies-window.v1",
                "discussion": {
                  "root": { "kind": "message", "serverId": "server-1", "id": "m1" },
                  "relation": { "kind": "replies" },
                  "parentScopeKey": { "serverId": "server-1", "scopeKind": "$parentScopeKind", "scopeId": "$parentScopeId" },
                  "backing": "sync-scope"
                },
                "window": { "kind": "sync-scope-window", "scopeCursor": null, "epoch": null }
              },
              "latestReply": {
                "id": "reply-$seq",
                "channelId": "$threadChannelId",
                "seq": $seq,
                "content": "desktop reply $seq",
                "senderId": "u1",
                "senderType": "user",
                "senderName": "artin",
                "senderDisplayName": "artin",
                "senderAvatarUrl": null,
                "messageType": "chat",
                "createdAt": "2026-10-20T01:2$seq:30.000Z",
                "conversationContext": {
                  "channelType": "thread",
                  "parentMessageId": "m1",
                  "parentChannelId": "$parentScopeId",
                  "parentChannelType": "$parentScopeKind"
                }
              }
            }
            """.trimIndent()
        val mapper = build.raft.app.data.realtime.RealtimeEventMapper(diagnosticsLogger = {})
        fun frame(seq: Long, replyCount: Int): ThreadUpdated =
            assertIs<ThreadUpdated>(mapper.map("thread:updated", threadUpdatedJson(seq, replyCount)))
        fun parent() = store.state.value.payload.messages.single()

        store.accept(SharedThreadIntent.ApplyRealtimeEvent(frame(seq = 6, replyCount = 6)))
        assertEquals(listOf("reply-2", "reply-3", "reply-6"), parent().inlineReplies?.replies?.map { it.messageId })
        assertEquals("desktop reply 6", parent().inlineReplies?.replies?.last()?.content)
        assertEquals(6, parent().replyCount)

        // Replay is idempotent (dedupe by message id), and a late reply lands in its seq position.
        val afterFirst = parent().inlineReplies
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(frame(seq = 6, replyCount = 6)))
        assertSame(afterFirst, parent().inlineReplies)
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(frame(seq = 5, replyCount = 6)))
        assertEquals(listOf("reply-3", "reply-5", "reply-6"), parent().inlineReplies?.replies?.map { it.messageId })
        assertEquals(6, parent().replyCount)

        val presentation =
            threadInlineReplyPresentation(
                model = parent().inlineReplies,
                replyMetadata =
                    ThreadReplyMetadata(
                        parentMessageId = parent().id,
                        threadChannelId = parent().threadChannelId,
                        replyCount = parent().replyCount,
                        unreadReplyCount = parent().unreadReplyCount,
                        firstUnreadReplyMessageId = parent().firstUnreadReplyMessageId,
                        isThreadFollowed = parent().isThreadFollowed,
                    ),
                hasDraft = false,
            )
        assertEquals(listOf("reply 3", "desktop reply 5", "desktop reply 6"), presentation?.replies?.map { it.content })
        scope.cancel()
    }
    @Test
    fun addChannelMemberUsesTypedRepositoryAndRefreshesMembers() = runTest {
        val memberRepository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        member("u1", "Artin"),
                        MemberProfile(
                            id = MemberId("agent-2"),
                            displayName = "Cindy",
                            handle = "cindy",
                            kind = MemberKind.Agent,
                            presence = Presence.Active
                        )
                    ),
                channelMembers = listOf(member("u1", "Artin"))
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository
            )

        val result = dataSource.addChannelMember(basePayload(), "agent-2", "agent")
        val payload = (result as DataResult.Content).data

        assertEquals(
            listOf(AddChannelMemberRequest(ChannelId("channel-1"), MemberId("agent-2"), MemberKind.Agent)),
            memberRepository.addRequests
        )
        assertEquals(listOf("u1", "agent-2"), payload.members.map { it.id })
        assertEquals(emptyList(), payload.memberCandidates.map { it.id })
    }
    @Test
    fun channelCreateActionCardHandsOverTheNewChannelEvenWhenMarkingFails() = runTest {
        listOf(200, 500).forEach { markCode ->
            val dataSource =
                testSharedThreadDataSource(
                    storeScope = backgroundScope,
                    networkApi = FakeThreadApi(),
                    sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    channelRepository = FakeChannelRepository(),
                    actionCardsApiClient =
                        RaftIntegrationsActionsPushApiClient(
                            RecordingActionCardsApi(
                                markResponse =
                                    HomeNetworkResponse(
                                        code = markCode,
                                        bodyText = """{"messageId":"m-card","metadata":{"kind":"action-card"}}""",
                                    ),
                            ),
                        ),
                )
            val created = mutableListOf<String>()

            val result =
                dataSource.commitThreadActionCard(
                    basePayload(),
                    "m-card",
                    ThreadActionCard(type = "channel:create", name = "design-review"),
                    onChannelCreated = { created += it.channelId.value },
                )

            assertEquals(listOf("channel-created"), created, "mark $markCode")
            assertEquals(markCode == 200, result is DataResult.Content, "mark $markCode")
        }
    }

    @Test
    fun channelCreateActionCardUsesTypedRepositoryAndMarksExecuted() = runTest {
        val channelRepository = FakeChannelRepository()
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                channelRepository = channelRepository,
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "channel:create",
                name = "design-review",
                description = "Coordinate visual review.",
                visibility = "private",
                initialHumans = listOf("u2", "u2"),
                initialAgents = listOf("agent-2")
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-card", card)
        val executedCard = (result as DataResult.Content).data

        assertEquals(
            listOf(
                CreateChannelRequest(
                    name = "design-review",
                    description = "Coordinate visual review.",
                    visibility = "private",
                    agentMemberIds = listOf("agent-2"),
                    userMemberIds = listOf("u2")
                )
            ),
            channelRepository.createRequests
        )
        assertEquals("executed", executedCard.state)
        assertEquals("channel", executedCard.resultKind)
        assertEquals("channel-created", executedCard.resultId)
        assertEquals("design-review", executedCard.resultName)
        assertEquals(
            listOf(
                """POST actions/m-card/mark-executed body={"result":{"kind":"channel","id":"channel-created","name":"design-review","channelId":null,"channelName":null,"addedHumanIds":null,"addedAgentIds":null}}"""
            ),
            actionCardsApi.calls
        )
    }
    @Test
    fun agentCreateActionCardRequiresDialogInsteadOfDirectCommit() = runTest {
        val agentsApi = RecordingAgentsApi()
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                agentsApiClient = RaftAgentsApiClient(agentsApi),
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "agent:create",
                name = "@Noel",
                description = "Helps with release review.",
                suggestedComputer = "machine-2",
                draftHint = "Use the release machine."
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-card", card)

        assertTrue(result is DataResult.Error)
        assertEquals("action_card_agent_create_dialog_required", result.error.code)
        assertTrue(agentsApi.createBodies.isEmpty())
        assertTrue(actionCardsApi.calls.isEmpty())
    }
    @Test
    fun agentCreateActionCardDialogSubmitUsesTypedApiAndMarksExecuted() = runTest {
        val agentsApi = RecordingAgentsApi()
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                agentsApiClient = RaftAgentsApiClient(agentsApi),
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "agent:create",
                name = "@Noel",
                description = "Helps with release review.",
                requiredComputer = "machine-2",
                draftHint = "Use the release machine."
            )

        val result = dataSource.commitAgentCreateActionCard("m-card", card)
        val executedCard = (result as DataResult.Content).data

        assertEquals(1, agentsApi.createBodies.size)
        assertTrue(agentsApi.createBodies.single().contains(""""name":"Noel""""))
        assertTrue(agentsApi.createBodies.single().contains(""""description":"Helps with release review.""""))
        assertTrue(agentsApi.createBodies.single().contains(""""machineId":"machine-2""""))
        assertFalse(agentsApi.createBodies.single().contains("@Noel"))
        assertEquals("executed", executedCard.state)
        assertEquals("agent", executedCard.resultKind)
        assertEquals("agent-created", executedCard.resultId)
        assertEquals("Noel", executedCard.resultName)
        assertEquals(
            listOf(
                """POST actions/m-card/mark-executed body={"result":{"kind":"agent","id":"agent-created","name":"Noel","channelId":null,"channelName":null,"addedHumanIds":null,"addedAgentIds":null}}"""
            ),
            actionCardsApi.calls
        )
    }
    @Test
    fun reconfirmCardReconfirmsAndAFrozenCardRunsNothing() = runTest {
        // web ActionCard: a `reconfirm_required` card's button POSTs /actions/:id/reconfirm (no
        // execute); a `frozen` card's button is disabled. The prepared card carries its version.
        val actionCardsApi =
            RecordingActionCardsApi(
                executeResponse =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """{"messageId":"m-card","confirmationVersion":5,"metadata":{"kind":"action-card","state":"prepared","confirmationVersion":5,"action":{"type":"channel:create","name":"launch"}}}"""
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val reconfirm = ThreadActionCard(type = "channel:create", name = "launch", state = "reconfirm_required", confirmationVersion = 4)

        val reconfirmed = (dataSource.commitThreadActionCard(basePayload(), "m-card", reconfirm) as DataResult.Content).data
        assertEquals("prepared", reconfirmed.state)
        assertEquals(5, reconfirmed.confirmationVersion)
        assertEquals(listOf("POST actions/m-card/reconfirm"), actionCardsApi.calls)

        val frozen = reconfirm.copy(state = "frozen")
        assertIs<DataResult.Error>(dataSource.commitThreadActionCard(basePayload(), "m-card", frozen))
        assertEquals(listOf("POST actions/m-card/reconfirm"), actionCardsApi.calls)
    }
    @Test
    fun genericRegisterAppActionCardExecutesThroughServerAuthority() = runTest {
        val actionCardsApi =
            RecordingActionCardsApi(
                executeResponse =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """
                            {
                              "messageId":"m-register",
                              "metadata":{
                                "kind":"action-card",
                                "state":"executed",
                                "executedByUserName":"artin",
                                "action":{
                                  "type":"integration:register_app",
                                  "name":"Build Monitor",
                                  "returnUrl":"https://build.example/oauth/callback",
                                  "scopes":["identity","profile"]
                                },
                                "result":{
                                  "kind":"integration-app-registration",
                                  "mode":"register",
                                  "clientId":"client-1",
                                  "clientKey":"build-monitor",
                                  "clientName":"Build Monitor",
                                  "returnUrl":"https://build.example/oauth/callback",
                                  "scopes":["identity","profile"]
                                }
                              }
                            }
                            """.trimIndent()
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "integration:register_app",
                name = "Build Monitor",
                metadataItems =
                    listOf(
                        ThreadActionCardMetadataItem(
                            key = "returnUrl",
                            value = "https://build.example/oauth/callback"
                        )
                    )
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-register", card)
        val executedCard = (result as DataResult.Content).data

        assertEquals("executed", executedCard.state)
        assertEquals("integration-app-registration", executedCard.resultKind)
        assertEquals("client-1", executedCard.resultId)
        assertEquals("Build Monitor", executedCard.resultName)
        assertEquals("artin", executedCard.executedByUserName)
        assertEquals(
            listOf("""POST actions/m-register/execute body={"expectedState":"prepared"}"""),
            actionCardsApi.calls
        )
    }
    @Test
    fun threadMembersPreserveRemoteSourceServerAndDisableRemoteRemove() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val remoteMember =
            MemberProfile(
                id = MemberId("remote-agent"),
                displayName = "Remote Agent",
                handle = "remote-agent",
                kind = MemberKind.Agent,
                serverId = ServerId("server-2"),
                serverName = "Remote Lab",
                serverSlug = "remote-lab",
                avatar = AvatarReference.fromWireValue("https://example.com/remote.png")
            )
        val memberRepository =
            FakeMemberRepository(
                serverMembers = listOf(member("u1", "Artin")),
                channelMembers = listOf(member("u1", "Artin"), remoteMember)
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.LoadMembers)

        val remote = store.state.value.payload.members.single { it.id == "remote-agent" }
        assertEquals("server-2", remote.sourceServerId)
        assertEquals("Remote Lab", remote.sourceServerLabel)
        assertEquals(false, remote.canRemove)
        assertEquals("https://example.com/remote.png", remote.avatarUrl)
        scope.cancel()
    }
    private class RecordingAgentsApi : RaftAgentsApi {
        val createBodies: MutableList<String> = mutableListOf()

        override suspend fun agents(): HomeNetworkResponse = error("unused")
        override suspend fun agent(agentId: MemberId): HomeNetworkResponse = error("unused")

        override suspend fun createAgent(body: String): HomeNetworkResponse {
            createBodies += body
            return HomeNetworkResponse(code = 200, bodyText = """{"id":"agent-created","name":"Noel","status":"active"}""")
        }

        override suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun startAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteAgent(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun agentActivityLog(agentId: MemberId, limit: Int): HomeNetworkResponse = error("unused")
        override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?): HomeNetworkResponse = error("unused")
        override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse = error("unused")
        override suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse = error("unused")

        override suspend fun agentExternalStatus(agentId: MemberId): HomeNetworkResponse = error("unused")

        override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse = error("unused")
    }
    internal class RecordingActionCardsApi(
        private val markResponse: HomeNetworkResponse =
            HomeNetworkResponse(code = 200, bodyText = """{"messageId":"m-card","metadata":{"kind":"action-card"}}"""),
        private val executeResponse: HomeNetworkResponse =
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    {
                      "messageId":"m-card",
                      "metadata":{
                        "kind":"action-card",
                        "state":"executed",
                        "executedByUserName":"artin",
                        "action":{
                          "type":"integration:approve_agent_login",
                          "requestId":"request-1",
                          "agentId":"agent-1",
                          "agentName":"KMP-专家",
                          "clientId":"client-1",
                          "clientKey":"hands",
                          "clientName":"Hands",
                          "scopes":["identity","profile"]
                        },
                        "result":{
                          "kind":"agent-integration-login",
                          "id":"grant-1",
                          "name":"Hands"
                        }
                      }
                    }
                    """.trimIndent()
            ),
        /** Answers for the first executes, in order; [executeResponse] after them. */
        firstExecuteResponses: List<HomeNetworkResponse> = emptyList(),
    ) : RaftIntegrationsActionsPushApi {
        val calls: MutableList<String> = mutableListOf()
        private val queuedExecuteResponses = ArrayDeque(firstExecuteResponses)

        override suspend fun integrationsOverview(): HomeNetworkResponse = error("unused")
        override suspend fun integrationClients(): HomeNetworkResponse = error("unused")
        override suspend fun createIntegrationClient(body: String): HomeNetworkResponse = error("unused")
        override suspend fun approveIntegrationRequest(requestId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun denyIntegrationRequest(requestId: String): HomeNetworkResponse = error("unused")
        override suspend fun revokeIntegrationGrant(grantId: String): HomeNetworkResponse = error("unused")
        override suspend fun agentIntegrations(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse {
            calls += "POST actions/${messageId.value}/execute body=$body"
            return queuedExecuteResponses.removeFirstOrNull() ?: executeResponse
        }
        override suspend fun emitActionEvent(messageId: MessageId, body: String): HomeNetworkResponse = error("unused")

        override suspend fun markActionExecuted(messageId: MessageId, body: String): HomeNetworkResponse {
            calls += "POST actions/${messageId.value}/mark-executed body=$body"
            return markResponse
        }
        override suspend fun reconfirmAction(messageId: MessageId): HomeNetworkResponse {
            calls += "POST actions/${messageId.value}/reconfirm"
            return executeResponse
        }

        override suspend fun pushVapidKey(): HomeNetworkResponse = error("unused")
        override suspend fun recordPushPromptEvent(body: String): HomeNetworkResponse = error("unused")
        override suspend fun subscribePush(body: String): HomeNetworkResponse = error("unused")
        override suspend fun unsubscribePush(body: String): HomeNetworkResponse = error("unused")
        override suspend fun testPush(): HomeNetworkResponse = error("unused")
    }
}
