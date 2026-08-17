package build.raft.app

import build.raft.app.data.ChannelSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.DirectMessageSnapshot
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedThreadInitialAccessTest {
    @Test
    fun sameServerSnapshotFromPreviousAccountCannotGrantCurrentAccountWriteAccess() = runBlocking {
        val route = replyThreadInitialAccessPayload()
        val serverId = ServerId("server-1")
        val staleAccountHome =
            replyThreadAccessHomeSnapshot(
                joined = true,
                canSend = true,
                serverId = serverId,
                currentMemberId = "account-a",
            )
        val currentAccountHome =
            replyThreadAccessHomeSnapshot(
                joined = false,
                canSend = false,
                serverId = serverId,
                currentMemberId = "account-b",
            )
        val sessionStore =
            InMemorySlockAuthSessionStore(
                authSessionFixture(userId = "account-b", selectedServerId = serverId.value)
            )
        val channelRepository =
            FakeChannelRepository(
                channelsResult = { requestedServerId ->
                    assertEquals(serverId, requestedServerId)
                    DataResult.Content(
                        ChannelSnapshot(
                            server = currentAccountHome.server,
                            channels = currentAccountHome.channels,
                        )
                    )
                }
            )
        val dataSource =
            SharedThreadDataSource(
                networkApi = ReplyThreadAccessNetworkApi,
                sessionStore = sessionStore,
                channelRepository = channelRepository,
                homeSnapshotProvider = { requestedServerId ->
                    assertEquals(serverId, requestedServerId)
                    staleAccountHome
                },
                replyCacheIdentityAuthority =
                    replyThreadAccessIdentityAuthority(
                        sessionStore = sessionStore,
                        environmentId = "https://prod.example.test",
                    ),
            )

        val resolution = dataSource.initialConversationAccessResolution(route)
        val candidate = assertNotNull(resolution.accessCandidate)

        assertFalse(candidate.joined)
        assertFalse(candidate.permissions.canSend)
        assertTrue(resolution.ownerCurrent)
        assertTrue(resolution.requiresBackgroundHomeContextHydration)
        assertEquals(1, channelRepository.channelsCalls)
    }

    @Test
    fun joinedChannelParentMountsComposerBeforeBackgroundHomeFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val route = replyThreadInitialAccessPayload()
        val home = replyThreadAccessHomeSnapshot()
        val backgroundHomeStarted = CompletableDeferred<Unit>()
        val releaseBackgroundHome = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository(
                channelsResult = {
                    DataResult.Content(
                        ChannelSnapshot(
                            server = home.server,
                            channels = home.channels,
                        )
                    )
                },
                homeResult = {
                    backgroundHomeStarted.complete(Unit)
                    releaseBackgroundHome.await()
                    DataResult.Error(ScreenError(code = "unread_summary_failed", retryable = true))
                },
            )
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemorySlockAuthSessionStore(authSessionFixture(userId = "u1")),
                        conversationRepository =
                            ReplyThreadAccessConversationRepositoryRecorder(
                                replyResult = DataResult.Content(replyThreadAccessSnapshot(body = "network reply"))
                            ),
                        channelRepository = channelRepository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(route))
        backgroundHomeStarted.await()

        val resolved = store.state.value
        assertEquals(listOf("network reply"), resolved.payload.messages.map { it.body })
        assertEquals(ThreadAccessResolution.Resolved, resolved.accessResolution)
        assertTrue(resolved.payload.joined)
        assertTrue(resolved.payload.canSend)
        assertTrue(resolved.payload.composer.enabled)
        assertTrue(resolved.writeActionsEnabled)
        assertEquals(1, channelRepository.channelsCalls)
        assertEquals(1, channelRepository.homeCalls)

        releaseBackgroundHome.complete(Unit)
        assertTrue(store.state.value.payload.composer.enabled)
        scope.cancel()
    }

    @Test
    fun explicitNoSendChannelParentStaysReadOnlyWhenBackgroundHomeFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val route = replyThreadInitialAccessPayload()
        val home = replyThreadAccessHomeSnapshot(joined = false, canSend = false)
        val backgroundHomeStarted = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository(
                channelsResult = {
                    DataResult.Content(
                        ChannelSnapshot(
                            server = home.server,
                            channels = home.channels,
                        )
                    )
                },
                homeResult = {
                    backgroundHomeStarted.complete(Unit)
                    DataResult.Error(ScreenError(code = "unread_summary_failed", retryable = true))
                },
            )
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemorySlockAuthSessionStore(authSessionFixture(userId = "u1")),
                        conversationRepository =
                            ReplyThreadAccessConversationRepositoryRecorder(
                                replyResult = DataResult.Content(replyThreadAccessSnapshot())
                            ),
                        channelRepository = channelRepository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(route))
        backgroundHomeStarted.await()

        val resolved = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, resolved.accessResolution)
        assertNotNull(resolved.initialHomeAccessCandidate)
        assertFalse(resolved.payload.joined)
        assertFalse(resolved.payload.canSend)
        assertFalse(resolved.payload.composer.enabled)
        assertEquals(1, channelRepository.channelsCalls)
        assertEquals(1, channelRepository.homeCalls)
        scope.cancel()
    }

    @Test
    fun joinedDirectMessageParentMountsComposerBeforeBackgroundHomeFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val parentChannelId = "dm-1"
        val parentMessageId = "dm-parent-message"
        val threadId = "dm-thread-1"
        val route =
            replyThreadInitialAccessPayload(
                parentKind = ConversationKind.DirectMessage,
                parentChannelId = parentChannelId,
                parentMessageId = parentMessageId,
                threadId = threadId,
            )
        val home =
            replyThreadAccessHomeSnapshot(
                parentKind = ConversationKind.DirectMessage,
                parentChannelId = parentChannelId,
            )
        val backgroundHomeStarted = CompletableDeferred<Unit>()
        val channelRepository =
            FakeChannelRepository(
                directMessagesResult = {
                    DataResult.Content(
                        DirectMessageSnapshot(
                            server = home.server,
                            directMessages = home.directMessages,
                        )
                    )
                },
                homeResult = {
                    backgroundHomeStarted.complete(Unit)
                    DataResult.Error(ScreenError(code = "unread_summary_failed", retryable = true))
                },
            )
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemorySlockAuthSessionStore(authSessionFixture(userId = "u1")),
                        conversationRepository =
                            ReplyThreadAccessConversationRepositoryRecorder(
                                replyResult =
                                    DataResult.Content(
                                        replyThreadAccessSnapshot(
                                            body = "network dm reply",
                                            parentKind = ConversationKind.DirectMessage,
                                            parentChannelIdValue = parentChannelId,
                                            parentMessageIdValue = parentMessageId,
                                            streamChannelIdValue = threadId,
                                        )
                                    )
                            ),
                        channelRepository = channelRepository,
                    ),
                scope = scope,
            )

        store.accept(SharedThreadIntent.Start(route))
        backgroundHomeStarted.await()

        val resolved = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, resolved.accessResolution)
        assertTrue(resolved.payload.joined)
        assertTrue(resolved.payload.canSend)
        assertTrue(resolved.payload.composer.enabled)
        assertEquals(1, channelRepository.directMessageCalls)
        assertEquals(0, channelRepository.channelsCalls)
        assertEquals(1, channelRepository.homeCalls)
        scope.cancel()
    }

    @Test
    fun lateParentAccessAfterOwnerChangeDoesNotMutateState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val route = replyThreadInitialAccessPayload()
        val servers =
            listOf(
                StoredAuthServer(id = "server-a", name = "Server A", slug = "server-a"),
                StoredAuthServer(id = "server-b", name = "Server B", slug = "server-b"),
            )
        fun identity(
            userId: String,
            serverId: String,
            authGeneration: Long,
            selectionGeneration: Long,
        ): ThreadReplyCacheIdentitySnapshot {
            val session =
                authSessionFixture(
                    userId = userId,
                    servers = servers,
                    selectedServerId = serverId,
                )
            return ThreadReplyCacheIdentitySnapshot(
                session = session,
                environmentId = "https://prod.example.test",
                principalId = userId,
                serverId = serverId,
                authGeneration = authGeneration,
                selectionGeneration = selectionGeneration,
            )
        }

        val owner = MutableStateFlow(identity("user-a", "server-a", 1L, 1L))
        val accessStarted = CompletableDeferred<Unit>()
        val releaseAccess = CompletableDeferred<Unit>()
        val staleDrop = CompletableDeferred<Unit>()
        val home = replyThreadAccessHomeSnapshot(serverId = ServerId("server-a"))
        val channelRepository =
            FakeChannelRepository(
                channelsResult = { serverId ->
                    assertEquals("server-a", serverId.value)
                    accessStarted.complete(Unit)
                    releaseAccess.await()
                    DataResult.Content(
                        ChannelSnapshot(
                            server = ServerSummary(id = serverId, name = "Server A"),
                            channels = home.channels,
                        )
                    )
                }
            )
        val conversationRepository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot()),
                releaseReply = CompletableDeferred(),
            )
        val store =
            SharedThreadStore(
                dataSource =
                    SharedThreadDataSource(
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemorySlockAuthSessionStore(owner.value.session),
                        conversationRepository = conversationRepository,
                        channelRepository = channelRepository,
                        replyCacheIdentityAuthority = { owner.value },
                    ),
                scope = scope,
                diagnosticsLogger = { message ->
                    if (message.contains("thread_access_resolution_stale_drop")) {
                        staleDrop.complete(Unit)
                    }
                },
            )

        store.dispatch(SharedThreadIntent.Start(route))
        accessStarted.await()
        val beforeStaleAccess = store.state.value
        assertFalse(beforeStaleAccess.initialHomeAccessLookupComplete)
        assertNull(beforeStaleAccess.initialHomeAccessCandidate)

        owner.value = identity("user-b", "server-b", 2L, 2L)
        releaseAccess.complete(Unit)

        staleDrop.await()
        val frame = store.state.value
        assertEquals(beforeStaleAccess.initialHomeAccessLookupComplete, frame.initialHomeAccessLookupComplete)
        assertEquals(beforeStaleAccess.initialHomeAccessCandidate, frame.initialHomeAccessCandidate)
        assertEquals(beforeStaleAccess.accessResolution, frame.accessResolution)
        assertEquals(beforeStaleAccess.canonicalAccessResolved, frame.canonicalAccessResolved)
        assertEquals(beforeStaleAccess.payload.joined, frame.payload.joined)
        assertEquals(beforeStaleAccess.payload.canSend, frame.payload.canSend)
        assertEquals(beforeStaleAccess.payload.composer.enabled, frame.payload.composer.enabled)
        assertEquals(beforeStaleAccess.writeActionsEnabled, frame.writeActionsEnabled)
        assertEquals(1, channelRepository.channelsCalls)
        assertEquals(0, channelRepository.homeCalls)
        scope.cancel()
    }
}
