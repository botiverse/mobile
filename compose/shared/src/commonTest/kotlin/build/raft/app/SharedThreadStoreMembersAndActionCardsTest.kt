package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.SharedThreadStoreTestFakes.ParkableReadSessionStore
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
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
import build.raft.app.data.RaftComputersApi
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RaftIntegrationsActionsPushApi
import build.raft.app.data.RaftIntegrationsActionsPushApiClient
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.SharedCreateAgentCoordinator
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadActionCardMetadataItem
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
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
    @Test
    fun loadMembersDoesNotBackfillAvatarsFromServerProfile() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val uploadedAvatar = "https://cdn.slock.ai/avatars/users/stdrc.webp"
        val memberRepository =
            FakeMemberRepository(
                serverMembers = listOf(member("u1", "Artin").copy(avatarUrl = uploadedAvatar)),
                channelMembers = listOf(member("u1", "Artin"))
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

        assertNull(store.state.value.payload.members.single().avatarUrl)
        scope.cancel()
    }
    @Test
    fun threadDataSourceWritesFullAndRealtimeAuthorSnapshotsIntoMemberStore() {
        val memberRepository = FakeMemberRepository()
        val memberStore = SharedServerMembersStore(memberRepository)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                serverMembersStore = memberStore
            )
        val uploadedAvatar =
            "https://cdn.raft.build/avatars/agents/deadbeefdeadbeefdeadbeefdeadbeef.webp"
        val fullHistoryRow =
            RaftThreadMessageItem(
                id = "history-message",
                authorId = "agent-1",
                authorName = "Argus",
                authorKind = "agent",
                avatarWireValue = uploadedAvatar
            )

        dataSource.seedThreadAuthorProfiles(
            serverId = "server-1",
            payload = basePayload().copy(status = "content", messages = listOf(fullHistoryRow)),
            source = "initial"
        )
        assertEquals(
            uploadedAvatar,
            memberStore.state.value.memberProfile(ServerId("server-1"), "agent-1")?.avatar?.wireValue
        )

        dataSource.seedRealtimeThreadAuthorProfiles(
            serverId = "server-1",
            event =
                MessageNew(
                    Message(
                        id = MessageId("realtime-message"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author =
                            member("agent-1", "Argus").copy(
                                kind = MemberKind.Agent,
                                avatar = AvatarReference.fromWireValue(uploadedAvatar),
                                avatarUrl = uploadedAvatar
                            ),
                        timestampLabel = "2026-08-02T00:00:00Z",
                        body = "realtime"
                    )
                ),
            currentUserId = "u1"
        )
        assertEquals(
            uploadedAvatar,
            memberStore.state.value.memberProfile(ServerId("server-1"), "agent-1")?.avatar?.wireValue
        )
    }
    @Test
    fun acceptedCachedPayloadSeedsAvatarResolverBeforeFailedRefresh() = runTest {
        val payloadStore = SharedThreadPayloadStore(backgroundScope)
        val uploadedAvatar =
            "https://cdn.raft.build/avatars/users/0123456789abcdef0123456789abcdef.webp"
        val cached =
            contentPayload("cached body").let { payload ->
                payload.copy(
                    messages =
                        listOf(
                            payload.messages.single().copy(
                                authorId = "cached-user",
                                authorName = "Cached User",
                                authorKind = "human",
                                avatarWireValue = uploadedAvatar
                            )
                        )
                )
            }
        val memberStore = testServerMembersStore(FakeMemberRepository(), scope = backgroundScope)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                serverMembersStore = memberStore,
                payloadStore = payloadStore
            )
        dataSource.cachePayloadForTest(cached)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals("cached body", store.state.value.payload.messages.single().body)
        assertEquals(
            uploadedAvatar,
            memberStore.state.value.memberProfile(ServerId("server-1"), "cached-user")?.avatar?.wireValue
        )
        scope.cancel()
    }
    @Test
    fun serverSwitchCacheHitsSeedOnlyTheirSelectedServerInEitherDirection() = runTest {
        for (serverOrder in listOf(listOf("server-a", "server-b"), listOf("server-b", "server-a"))) {
            val payloadStore = SharedThreadPayloadStore(backgroundScope)
            val memberStore = testServerMembersStore(FakeMemberRepository(), scope = backgroundScope)
            val avatars =
                mapOf(
                    "server-a" to "https://cdn.raft.build/avatars/users/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.webp",
                    "server-b" to "https://cdn.raft.build/avatars/users/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.webp"
                )
            serverOrder.forEach { serverId ->
                val cached =
                    contentPayload("cached $serverId").let { payload ->
                        payload.copy(
                            messages =
                                listOf(
                                    payload.messages.single().copy(
                                        authorId = "shared-user",
                                        authorName = "Shared User",
                                        authorKind = "human",
                                        avatarWireValue = avatars.getValue(serverId)
                                    )
                                )
                        )
                    }
                val session =
                    sampleSession().copy(
                        selectedServerId = serverId,
                        servers =
                            listOf(
                                StoredAuthServer(id = "server-a", name = "Server A", slug = "server-a"),
                                StoredAuthServer(id = "server-b", name = "Server B", slug = "server-b")
                            )
                    )
                val dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(failChannelMessages = true),
                        sessionStore = InMemoryRaftAuthSessionStore(session),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        serverMembersStore = memberStore,
                        payloadStore = payloadStore
                    )
                assertNotNull(dataSource.readSession())
                assertTrue(dataSource.cachePayload(cached, serverId = serverId))
                val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
                val store = SharedThreadStore(dataSource = dataSource, scope = scope)

                store.accept(SharedThreadIntent.Start(basePayload()))

                assertEquals("cached $serverId", store.state.value.payload.messages.single().body)
                assertEquals(
                    avatars.getValue(serverId),
                    memberStore.state.value.memberProfile(ServerId(serverId), "shared-user")?.avatar?.wireValue
                )
                val otherServer = if (serverId == "server-a") "server-b" else "server-a"
                val otherAvatar =
                    memberStore.state.value.memberProfile(ServerId(otherServer), "shared-user")?.avatar?.wireValue
                if (otherAvatar != null) {
                    assertEquals(avatars.getValue(otherServer), otherAvatar)
                }
                scope.cancel()
            }
        }
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
    /**
     * Hands 4155f83a: the same live reply on a Joint channel page. messageService.ts emits the
     * joint `thread:updated` per projection with `parentScopeKind: "joint"`, the projection's
     * local parent channel and its local thread channel.
     */
    @Test
    fun jointRealtimeThreadUpdatedPatchesColdCachedInlineReplyRows() =
        assertRealtimeThreadUpdatedPatchesColdCachedInlineReplyRows(
            route =
                basePayload().copy(
                    instanceId = "shared-shell-thread-joint-1",
                    sourceThreadId = "joint-1",
                    streamChannelId = "joint-1",
                    channelKind = JointChannelKindWireValue,
                ),
            parentScopeKind = "joint",
            threadChannelId = "joint-thread-local-1",
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
    fun acceptedRealtimeInlineReplySeedsResolverWhileRejectedScopeWritesNothing() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val memberRepository = FakeMemberRepository()
        val memberStore = testServerMembersStore(memberRepository, scope = backgroundScope)
        val sessionStore = ParkableReadSessionStore(sampleSession())
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                serverMembersStore = memberStore
            )
        dataSource.cachePayloadForTest(contentPayload("reply parent"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        val acceptedAvatar =
            "https://cdn.raft.build/avatars/agents/77777777777777777777777777777777.webp"
        val rejectedAvatar =
            "https://cdn.raft.build/avatars/agents/88888888888888888888888888888888.webp"
        val staleAvatar =
            "https://cdn.raft.build/avatars/agents/99999999999999999999999999999999.webp"
        fun replyEvent(
            senderId: String,
            avatarUrl: String,
            seq: Long,
            envelopeServerId: String
        ) =
            ThreadUpdated(
                parentMessageId = MessageId("m1"),
                threadChannelId = ChannelId("reply-thread"),
                replyCount = seq.toInt(),
                lastReplyAt = "2026-08-02T00:00:00Z",
                participantIds = listOf(MemberId(senderId)),
                unreadCount = 1,
                firstUnreadMessageId = MessageId("reply-$seq"),
                latestReply =
                    ReplyPreview(
                        messageId = "reply-$seq",
                        seq = seq,
                        content = "live reply $seq",
                        senderId = senderId,
                        senderType = "agent",
                        senderName = senderId,
                        senderAvatarUrl = avatarUrl,
                        createdAt = "2026-08-02T00:00:00Z"
                    ),
                syncCoreReplyWindow =
                    build.raft.app.network.sync.ThreadRepliesSyncWindowEnvelope(
                        producer = build.raft.app.network.sync.ThreadRepliesSyncWindowProducer,
                        rootKind = "message",
                        rootServerId = envelopeServerId,
                        rootMessageId = "m1",
                        relationKind = "replies",
                        parentScopeServerId = envelopeServerId,
                        parentScopeKind = "channel",
                        parentScopeId = "channel-1",
                        backing = "sync-scope",
                        windowKind = "sync-scope-window"
                    )
            )

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                replyEvent(
                    senderId = "agent-live",
                    avatarUrl = acceptedAvatar,
                    seq = 1L,
                    envelopeServerId = "server-1"
                )
            )
        )
        val resolver = raftMemberAvatarResolver(memberStore, "server-1")
        assertEquals(acceptedAvatar, resolver.current("agent-live"))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                replyEvent(
                    senderId = "agent-rejected",
                    avatarUrl = rejectedAvatar,
                    seq = 2L,
                    envelopeServerId = "other-server"
                )
            )
        )
        assertNull(resolver.current("agent-rejected"))
        assertNull(memberStore.state.value.memberProfile(ServerId("server-1"), "agent-rejected"))

        sessionStore.parkNextRead()
        val staleEvent =
            launch {
                store.accept(
                    SharedThreadIntent.ApplyRealtimeEvent(
                        replyEvent(
                            senderId = "agent-stale",
                            avatarUrl = staleAvatar,
                            seq = 3L,
                            envelopeServerId = "server-1"
                        )
                    )
                )
            }
        sessionStore.nextReadStarted.await()
        store.accept(
            SharedThreadIntent.Start(
                basePayload().copy(
                    sourceThreadId = "channel-2",
                    streamChannelId = "channel-2",
                    title = "Other channel"
                )
            )
        )
        sessionStore.releaseNextRead.complete(Unit)
        staleEvent.join()
        assertNull(resolver.current("agent-stale"))
        assertNull(memberStore.state.value.memberProfile(ServerId("server-1"), "agent-stale"))
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
    fun threadDataSourceRetainsCreateAgentCoordinatorForRouteInjection() {
        val coordinator =
            SharedCreateAgentCoordinator(
                agentsApiClient = RaftAgentsApiClient(RecordingAgentsApi()),
                computersApiClient = RaftComputersApiClient(RecordingComputersApi())
            )
        val dataSource =
            testSharedThreadDataSource(
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                createAgentCoordinator = coordinator
            )

        assertTrue(dataSource.createAgentCoordinatorOrNull === coordinator)
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
    fun actionCardCreateDoesNotReturnExecutedCardWhenMarkExecutedFails() = runTest {
        val agentsApi = RecordingAgentsApi()
        val actionCardsApi = RecordingActionCardsApi(
            markResponse = HomeNetworkResponse(code = 500, bodyText = """{"error":"server_unavailable"}""")
        )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                agentsApiClient = RaftAgentsApiClient(agentsApi),
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card = ThreadActionCard(type = "agent:create", name = "Noel")

        val result = dataSource.commitAgentCreateActionCard("m-card", card)

        assertEquals(1, agentsApi.createBodies.size)
        assertEquals(1, actionCardsApi.calls.size)
        assertTrue(result is DataResult.Error)
        assertEquals("action_mark_executed_contract_mismatch", result.error.code)
    }
    @Test
    fun channelAddMembersActionCardUsesTypedRepositoryAndMarksExecuted() = runTest {
        val memberRepository = FakeMemberRepository()
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "channel:add_member",
                channel = "channel-2",
                humans = listOf("u3", "u3"),
                agents = listOf("agent-3"),
                draftHint = "Bring owners in."
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-card", card)
        val executedCard = (result as DataResult.Content).data

        // task #352: ONE atomic batch for the whole card (mixed humans + agents),
        // not a per-member loop.
        assertEquals(
            listOf(
                AddChannelMembersBatchRequest(
                    channelId = ChannelId("channel-2"),
                    userIds = listOf(MemberId("u3")),
                    agentIds = listOf(MemberId("agent-3"))
                )
            ),
            memberRepository.batchRequests
        )
        assertEquals(emptyList(), memberRepository.addRequests)
        assertEquals("executed", executedCard.state)
        assertEquals("channel-members", executedCard.resultKind)
        assertEquals("channel-2", executedCard.resultId)
        assertEquals(listOf("u3"), executedCard.resultAddedHumanIds)
        assertEquals(listOf("agent-3"), executedCard.resultAddedAgentIds)
        assertEquals(
            listOf(
                """POST actions/m-card/mark-executed body={"result":{"kind":"channel-members","id":null,"name":null,"channelId":"channel-2","channelName":"channel-2","addedHumanIds":["u3"],"addedAgentIds":["agent-3"]}}"""
            ),
            actionCardsApi.calls
        )
    }
    @Test
    fun channelAddMembersActionCardAlreadyMembersOnlyIsIdempotentSuccess() = runTest {
        // task #352 / #361 blocker: the server defines an all-already-members batch
        // as an idempotent SUCCESS (added:[], ids under alreadyMembers). The action
        // card must therefore advance to executed, not fail — so a re-send never
        // blocks card completion.
        val memberRepository =
            FakeMemberRepository(
                channelMembers = listOf(member("u3", "Existing Human"), member("agent-3", "Existing Agent"))
            )
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )
        val card =
            ThreadActionCard(
                type = "channel:add_member",
                channel = "channel-2",
                humans = listOf("u3"),
                agents = listOf("agent-3"),
                draftHint = "Bring owners in."
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-card", card)
        val executedCard = (result as DataResult.Content).data
        assertEquals(
            listOf(
                AddChannelMembersBatchRequest(
                    channelId = ChannelId("channel-2"),
                    userIds = listOf(MemberId("u3")),
                    agentIds = listOf(MemberId("agent-3"))
                )
            ),
            memberRepository.batchRequests
        )
        assertEquals("executed", executedCard.state)
        // Nothing new was added; the card still completes with empty added-ids.
        assertEquals(listOf(), executedCard.resultAddedHumanIds)
        assertEquals(listOf(), executedCard.resultAddedAgentIds)
    }
    @Test
    fun integrationApproveLoginActionCardExecutesInlineAction() = runTest {
        val actionCardsApi = RecordingActionCardsApi()
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
                type = "integration:approve_agent_login",
                requestId = "request-1",
                agentId = "agent-1",
                agentName = "KMP-专家",
                clientId = "client-1",
                clientKey = "hands",
                clientName = "Hands",
                scopes = listOf("identity", "profile")
            )

        val result = dataSource.commitThreadActionCard(basePayload(), "m-card", card)
        val executedCard = (result as DataResult.Content).data

        assertEquals("executed", executedCard.state)
        assertEquals("agent-integration-login", executedCard.resultKind)
        assertEquals("grant-1", executedCard.resultId)
        assertEquals("Hands", executedCard.resultName)
        assertEquals("artin", executedCard.executedByUserName)
        assertEquals(
            listOf("""POST actions/m-card/execute body={"expectedState":"prepared"}"""),
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
    fun retiredUpdateRegistrationCardDoesNotCallGenericExecute() = runTest {
        val actionCardsApi = RecordingActionCardsApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                actionCardsApiClient = RaftIntegrationsActionsPushApiClient(actionCardsApi)
            )

        val result =
            dataSource.commitThreadActionCard(
                basePayload(),
                "m-update",
                ThreadActionCard(type = "integration:update_app_registration")
            )

        assertTrue(result is DataResult.Error)
        assertEquals("action_card_unsupported", result.error.code)
        assertTrue(actionCardsApi.calls.isEmpty())
    }
    @Test
    fun removeChannelMemberUsesTypedRepositoryAndRefreshesMembers() = runTest {
        val serverMembers = listOf(member("u1", "Artin"), member("u2", "Stdrc"))
        val memberRepository =
            FakeMemberRepository(
                serverMembers = serverMembers,
                channelMembers = listOf(member("u1", "Artin"), member("u2", "Stdrc"))
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
                networkApi = FakeThreadApi(),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore
            )

        val result = dataSource.removeChannelMember(basePayload(), "u2", "human")
        val payload = (result as DataResult.Content).data

        assertEquals(
            listOf(RemoveChannelMemberRequest(ChannelId("channel-1"), MemberId("u2"), MemberKind.Human)),
            memberRepository.removeRequests
        )
        assertEquals(listOf("u1"), payload.members.map { it.id })
        assertEquals(listOf("u2"), payload.memberCandidates.map { it.id })
    }
    @Test
    fun startHydratesThreadMembersInBackgroundAfterRefresh() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val serverMembers = listOf(member("u1", "Artin"), member("u2", "Stdrc"))
        val memberRepository =
            FakeMemberRepository(
                serverMembers = serverMembers,
                channelMembers = listOf(member("u1", "Artin"))
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
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
                serverMembersStore = serverMembersStore
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals("content", store.state.value.payload.status)
        assertTrue(memberRepository.channelMemberRequests.isEmpty())
        store.accept(SharedThreadIntent.FirstContentReady)
        assertEquals(listOf(ChannelId("channel-1")), memberRepository.channelMemberRequests)
        assertEquals(listOf("u1"), store.state.value.payload.members.map { it.id })
        assertEquals(listOf("u2"), store.state.value.payload.memberCandidates.map { it.id })
        scope.cancel()
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
                avatarUrl = "https://example.com/remote.png"
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
    private class RecordingComputersApi : RaftComputersApi {
        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
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
            )
    ) : RaftIntegrationsActionsPushApi {
        val calls: MutableList<String> = mutableListOf()

        override suspend fun integrationsOverview(): HomeNetworkResponse = error("unused")
        override suspend fun integrationClients(): HomeNetworkResponse = error("unused")
        override suspend fun createIntegrationClient(body: String): HomeNetworkResponse = error("unused")
        override suspend fun approveIntegrationRequest(requestId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun denyIntegrationRequest(requestId: String): HomeNetworkResponse = error("unused")
        override suspend fun revokeIntegrationGrant(grantId: String): HomeNetworkResponse = error("unused")
        override suspend fun agentIntegrations(agentId: MemberId): HomeNetworkResponse = error("unused")
        override suspend fun executeAction(messageId: MessageId, body: String): HomeNetworkResponse {
            calls += "POST actions/${messageId.value}/execute body=$body"
            return executeResponse
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
