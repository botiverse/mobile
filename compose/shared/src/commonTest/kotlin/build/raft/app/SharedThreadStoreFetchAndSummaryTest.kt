package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.agentMessagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.replySummaries
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFakes.FakeMemberRepository
import build.raft.app.SharedThreadStoreTestFixtures.directMessagePayload
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.replyHintRepository
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.SharedThreadStoreTestFixtures.unresolvedReplyPayload
import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.toHomeChannelRow
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.ThreadRepliesSyncHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class SharedThreadStoreFetchAndSummaryTest {
    @Test
    fun persistentCachedThreadSnapshotIsIgnoredOnVisibleFetchFailure() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("cached-1"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            author = member("u2", "Stdrc"),
                            timestampLabel = "2026-05-19T15:00:00Z",
                            body = "persistent cached body"
                        )
                    )
            )
        )
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals("error", payload.status)
        assertEquals(emptyList(), payload.messages.map { it.body })
        assertEquals("thread_network", payload.errorCode)
        assertEquals(1, api.channelMessageCalls)
        scope.cancel()
    }
    @Test
    fun channelPersistentCachedSnapshotIsNotReadBeforeLoadingFrame() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("cached-1"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            author = member("u2", "Stdrc"),
                            timestampLabel = "2026-05-19T15:00:00Z",
                            body = "first visible cached body"
                        )
                    )
            )
        )
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertFalse(logs.any { it.contains("thread_persistent_cache_result") })
        assertTrue(logs.any { it.contains("thread_visible_payload") && it.contains("status=loading") })
        assertEquals(emptyList(), store.state.value.payload.messages.map { it.body })
        scope.cancel()
    }
    @Test
    fun directMessagePersistentSnapshotIsIgnored() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("dm-1"),
                        channelId = ChannelId("dm-1"),
                        kind = ConversationKind.DirectMessage,
                        name = "b2cddd71-75a9-4cfc-b3e0-02485b50900d"
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("cached-1"),
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            author = member("u2", "Stdrc"),
                            timestampLabel = "2026-06-21T15:32:00Z",
                            body = "cached dm body"
                        )
                    )
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(directMessagePayload()))

        val payload = store.state.value.payload
        val headerDisplay = payload.threadHeaderFallbackDisplayModel()
        assertEquals("error", payload.status)
        assertEquals("artin", payload.title)
        assertEquals("Direct Message", headerDisplay.title)
        assertEquals("artin", payload.dmPeerName)
        assertEquals("pixel:crown", payload.dmPeerAvatarUrl)
        assertEquals("AR", payload.dmPeerAvatarLabel)
        assertEquals(emptyList(), payload.messages.map { it.body })
        scope.cancel()
    }
    @Test
    fun agentDirectMessagePersistentSnapshotIsIgnored() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("dm-agent-1"),
                        channelId = ChannelId("dm-agent-1"),
                        kind = ConversationKind.DirectMessage,
                        name = "Product-UX-Designer",
                        directMessagePeerType = "agent",
                        presenceMemberId = MemberId("agent-1")
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("cached-1"),
                            threadId = ThreadId("dm-agent-1"),
                            channelId = ChannelId("dm-agent-1"),
                            author =
                                MemberProfile(
                                    id = MemberId("agent-1"),
                                    displayName = "Product UX",
                                    handle = "product-ux",
                                    kind = MemberKind.Agent,
                                    avatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                                ),
                            timestampLabel = "2026-06-21T15:32:00Z",
                            body = "cached agent dm body"
                        )
                    )
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gravatarSeed =
            directMessagePayload().copy(
                sourceThreadId = "dm-agent-1",
                streamChannelId = "dm-agent-1",
                title = "Product-UX-Designer",
                dmPeerName = "Product-UX-Designer",
                dmPeerAvatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                dmPeerAvatarLabel = "PU",
            )

        store.accept(SharedThreadIntent.Start(gravatarSeed))

        val payload = store.state.value.payload
        assertEquals("error", payload.status)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("Product-UX-Designer", payload.dmPeerName)
        assertEquals("https://www.gravatar.com/avatar/agent-hash?s=52&d=404", payload.dmPeerAvatarUrl)
        assertEquals(emptyList(), payload.messages.map { it.body })
        scope.cancel()
    }
    @Test
    fun persistentCachedThreadSnapshotWindowIsIgnored() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(
            ThreadSnapshot(
                conversation = conversationSummary(),
                messages = (1..35).map { index -> message(id = "cached-$index", seq = index.toLong(), body = "cached $index") }
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val payload = store.state.value.payload
        assertEquals(emptyList(), payload.messages.map { it.body })
        assertEquals(false, payload.canLoadMoreBefore)
        assertEquals("thread_network", payload.errorCode)
        scope.cancel()
    }
    @Test
    fun successfulFetchCachesThreadTimelineInMemoryWithoutPersistentSnapshot() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )

        dataSource.fetchThreadPayload(basePayload())

        val cached = localStore.readThread(ThreadId("channel-1"))
        assertNull(cached)
        assertEquals("network body", dataSource.cachedPayload("channel-1")?.messages?.single()?.body)
        assertEquals("Artin", dataSource.cachedPayload("channel-1")?.messages?.single()?.authorName)
    }
    @Test
    fun focusedReplyContextRestoresOriginFromCachedParentMessage() = runTest {
        val repository =
            replyHintRepository().also { fake ->
                fake.messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "channel-1",
                            messages = emptyList(),
                            parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body")
                        )
                    )
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
        )
        val firstOpen = dataSource.fetchThreadPayload(unresolvedReplyPayload(parentMessageId = "m1"))
        val firstPayload = (firstOpen as? DataResult.Content)?.data

        assertNotNull(firstPayload)
        assertEquals("parent body", firstPayload.originMessage?.body)

        repository.messageContextResult =
            DataResult.Content(
                threadSnapshot(
                    threadId = "thread-m1",
                    messages =
                        listOf(
                            message(
                                threadId = "thread-m1",
                                channelId = "thread-m1",
                                id = "reply-1",
                                seq = 2,
                                body = "focused reply"
                            )
                        ),
                    parentMessage = null,
                    conversation =
                        conversationSummary("thread-m1").copy(
                            kind = ConversationKind.Thread,
                            channelId = ChannelId("thread-m1"),
                            thread =
                                ThreadConversation(
                                    parentChannelId = ChannelId("channel-1"),
                                    parentMessageId = MessageId("m1"),
                                    streamChannelId = ChannelId("thread-m1")
                                )
                        )
                )
            )

        val secondOpen =
            dataSource.fetchThreadPayload(
                replyPayload(parentMessageId = "m1", threadId = "thread-m1")
                    .copy(focusedMessageId = "reply-1")
            )
        val secondPayload = (secondOpen as? DataResult.Content)?.data

        assertNotNull(secondPayload)
        assertEquals("loaded", secondPayload.originStatus)
        assertEquals("m1", secondPayload.originMessage?.id)
        assertEquals("parent body", secondPayload.originMessage?.body)
        assertEquals(listOf("focused reply"), secondPayload.messages.map { it.body })
    }
    @Test
    fun fetchedThreadPayloadUsesHomeDirectMessageAvatarBeforeMemoryCacheWrite() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                currentMember = member("u1", "Artin"),
                channels = emptyList(),
                directMessages =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("dm-agent-1"),
                            channelId = ChannelId("dm-agent-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "Product-UX-Designer",
                            presenceMemberId = MemberId("agent-1")
                        ).toHomeDirectMessageRow()
                    ),
                unreadTotal = 0
            )
        )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals(null, payload.messages.single().avatarWireValue)
        val cachedAuthor = localStore.readThread(ThreadId("channel-1"))?.messages?.single()?.author
        assertNull(cachedAuthor)
        val cachedMessage = dataSource.cachedPayload("channel-1")?.messages?.single()
        assertEquals(null, cachedMessage?.avatarWireValue)
    }
    @Test
    fun fetchedThreadPayloadDoesNotBlockOnMemberProfileAvatarBeforeFirstRender() = runTest {
        val memberRepository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Product UX",
                            handle = "product-ux",
                            kind = MemberKind.Agent,
                            avatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                            avatar = AvatarReference.Pixel("tree")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals(emptyList(), memberRepository.serverMemberRequests)
        val message = payload.messages.single()
        assertEquals("Product-UX-Designer", message.authorName)
        assertEquals(null, message.avatarWireValue)
    }
    @Test
    fun secondaryRefreshDoesNotFetchOrPatchSenderProfileAvatars() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val memberRepository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Product UX",
                            handle = "product-ux",
                            kind = MemberKind.Agent,
                            avatar = AvatarReference.Pixel("tree")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                memberRepository = memberRepository,
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        assertEquals(emptyList(), memberRepository.serverMemberRequests)
        val message = store.state.value.payload.messages.single()
        assertEquals("Product-UX-Designer", message.authorName)
        assertEquals(null, message.avatarWireValue)
        assertNull(localStore.readThread(ThreadId("channel-1")))
        assertEquals(null, dataSource.cachedPayload("channel-1")?.messages?.single()?.avatarWireValue)
        scope.cancel()
    }
    @Test
    fun taskReplyThreadPayloadDoesNotBlockOnMemberProfileAvatarForOriginBeforeFirstRender() = runTest {
        val agentParent =
            message(threadId = "channel-1", id = "m1", seq = 1, body = "agent task parent")
                .copy(
                    author =
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Product UX",
                            handle = "product-ux",
                            kind = MemberKind.Agent,
                            avatarUrl = "https://www.gravatar.com/avatar/agent-hash?s=52&d=404",
                        )
                )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages =
                                listOf(
                                    message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")
                                ),
                            parentMessage = agentParent,
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val memberRepository =
            FakeMemberRepository(
                serverMembers =
                    listOf(
                        MemberProfile(
                            id = MemberId("agent-1"),
                            displayName = "Product UX",
                            handle = "product-ux",
                            kind = MemberKind.Agent,
                            avatar = AvatarReference.Pixel("tree")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                memberRepository = memberRepository
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(emptyList(), memberRepository.serverMemberRequests)
        assertEquals("agent task parent", payload.originMessage?.body)
        assertEquals("pixel:robot", payload.originMessage?.avatarWireValue)
    }
    @Test
    fun taskReplyThreadPayloadFallsBackToStreamChannelNameWithoutHomeSnapshot() = runTest {
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                currentMember = member("u1", "Artin"),
                channels =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            kind = ConversationKind.Channel,
                            name = "general",
                            subtitle = "#general"
                        ).toHomeChannelRow()
                    ),
                directMessages = emptyList(),
                unreadTotal = 0
            )
        )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")),
                            parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body"),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    name = "thread-m1",
                                    subtitle = null,
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val base =
            basePayload().copy(
                title = ThreadReplyPageTitle,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                showModeTabs = false,
                originStatus = "loading"
            )

        val payload = (dataSource.fetchThreadPayload(base, persistResult = false) as DataResult.Content).data

        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(ThreadReplyPageTitle, threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        // fb845a19 removed the durable HomeSnapshot blob cache, so the parent
        // channel name can no longer be resolved from the local store here —
        // the subtitle falls back to the thread's own channel name; live
        // parent resolution happens through the runtime conversation
        // identity provider instead.
        assertEquals("#thread-m1", payload.subtitle)
    }
    @Test
    fun fetchedThreadPayloadNormalizesAgentAvatarBeforeFirstRender() = runTest {
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = agentMessagesResponse()),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        val message = payload.messages.single()
        assertEquals(null, message.avatarWireValue)
    }
    @Test
    fun fetchThreadPayloadWithoutSessionReturnsAuthRequiredNoPermission() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )

        val result = dataSource.fetchThreadPayload(basePayload())
        val blocker = (result as DataResult.NoPermission).blocker

        assertEquals("auth_required", blocker.code)
        assertEquals("auth_session", blocker.requiredCapability)
        assertEquals(0, api.channelMessageCalls)
    }
    @Test
    fun openMessageContextWithoutSessionReturnsAuthRequiredNoPermission() = runTest {
        val repository =
            FakeConversationRepository(
                messageContextResult = DataResult.Content(threadSnapshot())
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val result = dataSource.openMessageContext("message-1", basePayload())
        val blocker = (result as DataResult.NoPermission).blocker

        assertEquals("auth_required", blocker.code)
        assertEquals("auth_session", blocker.requiredCapability)
        assertEquals(0, repository.getMessageContextRequests.size)
    }
    @Test
    fun openMessageContextKeepsExplicitDirectMessageParentSurfaceWhenSnapshotLooksThread() = runTest {
        val timelineMessage =
            message(
                threadId = "dm-1",
                id = "parent-1",
                seq = 12,
                body = "Parent DM message"
            )
        val parentMessage =
            timelineMessage.copy(
                threadChannelId = ChannelId("reply-thread-1"),
                replyCount = 3,
                unreadReplyCount = 1
            )
        val repository =
            FakeConversationRepository(
                messageContextResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "reply-thread-1",
                            messages = listOf(timelineMessage),
                            parentMessage = parentMessage,
                            conversation =
                                ConversationSummary(
                                    threadId = ThreadId("reply-thread-1"),
                                    channelId = ChannelId("dm-1"),
                                    kind = ConversationKind.Thread,
                                    name = "Thread",
                                    subtitle = "#Cindy",
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("dm-1"),
                                            parentMessageId = MessageId("parent-1"),
                                            streamChannelId = ChannelId("reply-thread-1")
                                        )
                                )
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val parentRoute =
            directMessagePayload().copy(
                title = "Cindy",
                dmPeerName = "Cindy",
                dmPeerAvatarUrl = "pixel:cindy",
                dmPeerAvatarLabel = "CI",
                focusedMessageId = "parent-1"
            )

        val payload = (dataSource.openMessageContext("parent-1", parentRoute) as DataResult.Content).data

        assertEquals(listOf(MessageId("parent-1")), repository.getMessageContextRequests)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.surfaceKind)
        assertEquals("dm-1", payload.sourceThreadId)
        assertEquals("dm-1", payload.streamChannelId)
        assertEquals(null, payload.parentChannelId)
        assertEquals(null, payload.parentMessageId)
        val headerDisplay =
            payload.threadHeaderDisplayModelFromDirectory(
                ConversationSummary(
                    threadId = ThreadId("dm-1"),
                    channelId = ChannelId("dm-1"),
                    kind = ConversationKind.DirectMessage,
                    name = "Cindy"
                ).toConversationHeaderProjection()
            )

        assertEquals("Thread", payload.title)
        assertEquals("Cindy", headerDisplay.title)
        assertEquals(null, headerDisplay.dmPeerAvatarWireValue)
        assertEquals(null, payload.subtitle)
        assertEquals("Thread", payload.dmPeerName)
        assertEquals(null, payload.dmPeerAvatarUrl)
        assertEquals("TH", payload.dmPeerAvatarLabel)
        assertEquals("reply-thread-1", payload.messages.single().threadChannelId)
        assertEquals(3, payload.messages.single().replyCount)
        assertEquals(1, payload.messages.single().unreadReplyCount)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, payload.resolveThreadComposer().surfaceKind)
    }
    @Test
    fun fetchThreadPayloadPrefersConversationRepositorySnapshotWhenAvailable() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository = FakeConversationRepository(
            threadResult = DataResult.Content(threadSnapshot(body = "repository body"))
        )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )

        val payload = (dataSource.fetchThreadPayload(basePayload()) as DataResult.Content).data

        assertEquals(listOf(ThreadId("channel-1")), repository.getThreadRequests)
        assertEquals(listOf(false), repository.getThreadIncludeFollowedSummaries)
        assertEquals(0, api.channelMessageCalls)
        assertEquals("repository body", payload.messages.single().body)
    }
    @Test
    fun initialMessagePageSummaryAdmitsInlinePreviewBeforeFirstContentReady() = runTest {
        val firstFrameSummary =
            ThreadReplySummariesSnapshot(
                threads =
                    listOf(
                        InboxItem(
                            key = "thread:m1",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("thread-m1"),
                            channelName = "reply thread",
                            threadChannelId = ChannelId("thread-m1"),
                            parentChannelId = ChannelId("channel-1"),
                            parentMessageId = MessageId("m1"),
                            replyCount = 5,
                            unreadCount = 1,
                            firstUnreadMessageId = MessageId("reply-5"),
                            latestReplies =
                                listOf(
                                    ReplyPreview(
                                        messageId = "reply-5",
                                        seq = 5,
                                        content = "first-frame preview",
                                        senderId = "agent-1",
                                        senderType = "agent",
                                        senderName = "MingQi",
                                        senderDisplayName = "明启",
                                        senderAvatarUrl = null,
                                        createdAt = "2026-07-25T14:01:00Z",
                                    )
                                ),
                        )
                    )
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m1",
                                        seq = 10,
                                        body = "initial body",
                                    )
                                ),
                            replySummaries = firstFrameSummary,
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val firstVisibleMessage = store.state.value.payload.messages.single()
        assertEquals("thread-m1", firstVisibleMessage.threadChannelId)
        assertEquals(5, firstVisibleMessage.replyCount)
        assertEquals(1, firstVisibleMessage.unreadReplyCount)
        assertEquals("reply-5", firstVisibleMessage.firstUnreadReplyMessageId)
        assertEquals(listOf("reply-5"), firstVisibleMessage.inlineReplies?.replies?.map { it.messageId })
        assertEquals("明启", firstVisibleMessage.inlineReplies?.replies?.single()?.senderDisplayName)
        delay(1L)
        assertEquals(
            listOf(ThreadId("channel-1") to listOf(MessageId("m1"))),
            repository.getThreadReplySummariesRequests
        )
        scope.cancel()
    }
    @Test
    fun secondaryRefreshBackfillsFollowedSummariesWithoutBlockingInitialFetch() = runTest {
        val api = FakeThreadApi(bodyText = messagesResponse("network body"))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "initial body",
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m1",
                                        seq = 10,
                                        body = "initial body",
                                        threadChannelId = "thread-m1",
                                        replyCount = 0
                                    )
                                )
                        )
                    ),
                threadReplySummariesResult =
                    DataResult.Content(
                        ThreadReplySummariesSnapshot(
                            threads =
                                listOf(
                                    InboxItem(
                                        key = "thread:m1",
                                        kind = InboxItemKind.Thread,
                                        channelId = ChannelId("thread-m1"),
                                        channelName = "reply thread",
                                        threadChannelId = ChannelId("thread-m1"),
                                        parentChannelId = ChannelId("channel-1"),
                                        parentMessageId = MessageId("m1"),
                                        replyCount = 7,
                                        unreadCount = 2,
                                        firstUnreadMessageId = MessageId("reply-6"),
                                        latestReplies =
                                            listOf(
                                                ReplyPreview(
                                                    messageId = "reply-6",
                                                    seq = 6,
                                                    content = "first unread",
                                                    senderId = "u2",
                                                    senderType = "human",
                                                    senderName = "cindy",
                                                    senderAvatarUrl = null,
                                                    createdAt = "2026-05-19T16:06:00Z",
                                                    senderDisplayName = "Cindy",
                                                )
                                            )
                                    )
                                ),
                            followedThreadKeys = setOf("thread:m1")
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        delay(1L)

        assertEquals(listOf(false), repository.getThreadIncludeFollowedSummaries)
        assertEquals(
            listOf(ThreadId("channel-1") to listOf(MessageId("m1"))),
            repository.getThreadReplySummariesRequests
        )
        val message = store.state.value.payload.messages.single()
        assertEquals("initial body", message.body)
        assertEquals(7, message.replyCount)
        assertEquals(2, message.unreadReplyCount)
        assertEquals("reply-6", message.firstUnreadReplyMessageId)
        assertEquals(listOf("reply-6"), message.inlineReplies?.replies?.map { it.messageId })
        assertEquals("Cindy", message.inlineReplies?.replies?.single()?.senderDisplayName)
        assertTrue(message.isThreadFollowed)
        assertEquals(0, api.channelMessageCalls)
        scope.cancel()
    }
    @Test
    fun delayedSecondaryProjectionDoesNotReplaceCommittedNewerPage() = runTest {
        val initialMessages =
            (1L..16L).map { seq ->
                message(
                    id = "m$seq",
                    seq = seq,
                    body = "message $seq",
                    threadChannelId = if (seq == 1L) "thread-m1" else null,
                )
            }
        val newerMessages =
            (17L..46L).map { seq ->
                message(id = "m$seq", seq = seq, body = "message $seq")
            }
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(messages = initialMessages)),
                threadReplySummariesResult = replySummaries(replyCount = 7),
                newerResult = DataResult.Content(threadSnapshot(messages = newerMessages)),
            )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        lateinit var store: SharedThreadStore
        var projectionCommitCount = 0
        var maxSeqAfterNewerCommit: Long? = null
        val repliesSyncHost =
            ThreadRepliesSyncHost(
                beforeProjectionCommit = {
                    projectionCommitCount += 1
                    if (projectionCommitCount == 2) {
                        // Not a wait: the hook is synchronous by contract, and this re-enters the store
                        // INSIDE the projection commit. The store runs on the unconfined test
                        // dispatcher, so the nested load completes on this thread without a clock.
                        runBlocking {
                            assertTrue(store.loadNewer() is ThreadNewerPageLoadOutcome.Applied)
                            maxSeqAfterNewerCommit = store.state.value.payload.messages.mapNotNull { it.seq }.maxOrNull()
                            store.accept(SharedThreadIntent.ApplyMessageSaved("m1", saved = true))
                        }
                    }
                },
            )
        store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
                threadRepliesSyncHost = repliesSyncHost,
            )

        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))
        store.accept(
            SharedThreadIntent.SetPagingAvailability(
                canLoadMoreBefore = false,
                canLoadMoreAfter = true,
            ),
        )
        store.accept(SharedThreadIntent.FirstContentReady)

        assertEquals(46L, maxSeqAfterNewerCommit)
        assertEquals(2, projectionCommitCount)
        assertEquals(46L, store.state.value.payload.messages.mapNotNull { it.seq }.maxOrNull())
        assertEquals((1L..46L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals(listOf(ThreadId("channel-1") to 16L), repository.newerRequests)
        assertEquals(7, store.state.value.payload.messages.first { it.id == "m1" }.replyCount)
        assertTrue(store.state.value.payload.messages.first { it.id == "m1" }.isSaved)
        scope.cancel()
    }
    @Test
    fun lateSummaryFromPreviousRouteCannotMutateCurrentCanonicalMessages() = runTest {
        val repository =
            FakeConversationRepository(
                threadResultsById =
                    mapOf(
                        ThreadId("channel-1") to
                            DataResult.Content(threadSnapshot(threadId = "channel-1", body = "route one")),
                        ThreadId("channel-2") to
                            DataResult.Content(threadSnapshot(threadId = "channel-2", body = "route two")),
                    ),
                threadReplySummariesResult = replySummaries(replyCount = 7),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        repository.threadReplySummariesStarted?.await()
        store.accept(
            SharedThreadIntent.Start(
                basePayload().copy(
                    instanceId = "shared-shell-thread-channel-2",
                    sourceThreadId = "channel-2",
                    streamChannelId = "channel-2",
                ),
            ),
        )
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()

        val current = store.state.value.payload
        assertEquals("channel-2", current.sourceThreadId)
        assertEquals(listOf("route two"), current.messages.map { it.body })
        assertEquals(0, current.messages.single().replyCount)
        scope.cancel()
    }
    @Test
    fun channelSummaryBackfillWithNoChangesDoesNotRefetchTimelineOrEmitState() = runTest {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "stable body")),
                threadReplySummariesResult =
                    DataResult.Content(ThreadReplySummariesSnapshot(threads = emptyList()))
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val logs = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        delay(1L)

        assertEquals(listOf(false), repository.getThreadIncludeFollowedSummaries)
        assertEquals(
            listOf(ThreadId("channel-1") to listOf(MessageId("m1"))),
            repository.getThreadReplySummariesRequests
        )
        assertEquals("stable body", store.state.value.payload.messages.single().body)
        assertTrue(
            logs.any {
                it.startsWith("thread_secondary_refresh_result") &&
                    it.contains("emitted=false")
            }
        )
        scope.cancel()
    }
    @Test
    fun earlySummaryResultStashesUntilFirstContentReady() = runTest {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "stable body")),
                threadReplySummariesResult = replySummaries(replyCount = 7),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(basePayload()))
        repository.threadReplySummariesStarted?.await()
        assertEquals(
            listOf(ThreadId("channel-1") to listOf(MessageId("m1"))),
            repository.getThreadReplySummariesRequests
        )
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()
        assertEquals(0, store.state.value.payload.messages.single().replyCount)

        store.accept(SharedThreadIntent.FirstContentReady)
        yield()
        delay(1L)
        assertEquals(7, store.state.value.payload.messages.single().replyCount)
        scope.cancel()
    }
    @Test
    fun lateSummaryResultAppliesImmediatelyAfterFirstContentReady() = runTest {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "stable body")),
                threadReplySummariesResult = replySummaries(replyCount = 7),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                        sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = repository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        repository.threadReplySummariesStarted?.await()
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()
        delay(1L)
        assertEquals(7, store.state.value.payload.messages.single().replyCount)
        scope.cancel()
    }
    @Test
    fun summarySnapshotDoesNotOverwriteNewerRealtimeFact() = runTest {
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "stable body")),
                threadReplySummariesResult = replySummaries(replyCount = 15),
            ).also {
                it.threadReplySummariesStarted = CompletableDeferred()
                it.releaseThreadReplySummaries = CompletableDeferred()
                it.ignoreThreadReplySummariesCancellation = true
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.FirstContentReady)
        repository.threadReplySummariesStarted?.await()
        // A realtime reply-count fact lands while the summaries request is in
        // flight; its newer observedAt must survive the older server snapshot.
        dataSource.recordReplyCountRealtimeFact(
            threadId = "channel-1",
            parentMessageId = "m1",
            fact =
                SharedThreadReplyCountFact(
                    threadChannelId = "thread-m1",
                    replyCount = 17,
                    observedAt = TimeSource.Monotonic.markNow(),
                ),
        )
        repository.releaseThreadReplySummaries?.complete(Unit)
        yield()
        delay(1L)
        assertEquals(17, store.state.value.payload.messages.single().replyCount)
        scope.cancel()
    }
}
