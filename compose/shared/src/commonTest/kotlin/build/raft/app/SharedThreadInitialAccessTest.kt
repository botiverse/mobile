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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
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

    @Test
    fun parentAccessLookupTimeoutMarksAuthorityUnknownAndHintRecoversComposer() = runBlocking {
        // task #243: this case waits for a state that ONLY the lookup-timeout path can produce,
        // and that timeout used to be a real second of wall clock
        // (ThreadInitialAccessLookupTimeoutMillis). On a loaded CI runner the wait could outlive
        // the 60s watchdog, which killed the JVM (exit 73) - intermittent by construction, and
        // green locally every time, which is why it survived.
        //
        // Virtual time is not available here: commonTest compiles for OHOS arm64 and there is no
        // kotlinx-coroutines-test KLIB for it. So the deadline is injected as an explicit barrier
        // the test completes itself - the same shape SharedReplyThreadAccessIntegrationTest uses.
        // The timeout branch is now reached because this test released it, not because a second
        // elapsed. Shortening the duration was rejected: that shrinks the window instead of
        // removing the nondeterminism, which is the silent-retry family the card's SCOPE excludes.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lookupDeadline = CompletableDeferred<Unit>()
        val route = replyThreadInitialAccessPayload()
        val home = replyThreadAccessHomeSnapshot()
        val neverCompleteChannels = CompletableDeferred<DataResult<ChannelSnapshot>>()
        val channelRepository =
            FakeChannelRepository(
                channelsResult = { _ -> neverCompleteChannels.await() },
                homeResult = { _ -> DataResult.Content(home) },
            )
        val diagnostics = mutableListOf<String>()
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
                diagnosticsLogger = { diagnostics.add(it) },
                initialAccessLookupDeadline = { lookupDeadline.await() },
            )

        store.accept(SharedThreadIntent.Start(route))
        // The lookup is in flight and cannot finish on its own (channelsResult never completes);
        // releasing the barrier is what drives it into the timeout branch.
        lookupDeadline.complete(Unit)
        store.state.filter { state ->
            state.initialHomeAccessLookupComplete &&
                state.accessResolution == ThreadAccessResolution.Resolved &&
                !state.payload.composer.enabled
        }.first()
        // The timeout must land in an explicit Unknown authority state, not a
        // silently frozen read-only fallback.
        assertEquals(ReplyParentAuthorityStatus.Unknown, store.state.value.replyParentAuthority)
        assertFalse(store.state.value.payload.composer.enabled)
        assertFalse(store.state.value.writeActionsEnabled)

        // Event-driven recovery (direction A): the shared Home directory row for
        // the exact parent arrives; no network, no retry counter.
        store.accept(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                present = true,
                joined = true,
                canSend = true,
            )
        )
        store.state.filter { state -> state.payload.composer.enabled }.first()
        val recovered = store.state.value
        assertEquals(ReplyParentAuthorityStatus.Writable, recovered.replyParentAuthority)
        assertTrue(recovered.payload.joined)
        assertTrue(recovered.payload.canSend)
        assertTrue(recovered.payload.composer.enabled)
        assertTrue(recovered.writeActionsEnabled)
        assertEquals(1, channelRepository.channelsCalls)
        scope.cancel()
    }

    @Test
    fun explicitNoSendParentIsDeniedAndHintCannotSilentlyReopenWithoutGrant() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val route = replyThreadInitialAccessPayload()
        val home = replyThreadAccessHomeSnapshot(joined = true, canSend = false)
        val channelRepository =
            FakeChannelRepository(
                channelsResult = { serverId ->
                    DataResult.Content(
                        ChannelSnapshot(
                            server = ServerSummary(id = serverId, name = "One"),
                            channels = home.channels,
                        )
                    )
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
        store.state.filter { state ->
            state.initialHomeAccessLookupComplete &&
                state.replyParentAuthority == ReplyParentAuthorityStatus.Denied
        }.first()
        assertFalse(store.state.value.payload.composer.enabled)
        assertFalse(store.state.value.payload.canSend)

        // A hint that still denies send keeps the composer closed.
        store.accept(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                present = true,
                joined = true,
                canSend = false,
            )
        )
        assertEquals(ReplyParentAuthorityStatus.Denied, store.state.value.replyParentAuthority)
        assertFalse(store.state.value.payload.composer.enabled)

        // A live grant from the directory re-evaluates event-driven (join or
        // permission changes while the thread is open).
        store.accept(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                present = true,
                joined = true,
                canSend = true,
            )
        )
        store.state.filter { state -> state.replyParentAuthority == ReplyParentAuthorityStatus.Writable }.first()
        assertTrue(store.state.value.payload.composer.enabled)
        assertTrue(store.state.value.writeActionsEnabled)
        scope.cancel()
    }

    @Test
    fun hintsForOtherParentsOrBeforeLookupCompletionAreIgnored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val route = replyThreadInitialAccessPayload()
        val home = replyThreadAccessHomeSnapshot()
        val releaseLookup = CompletableDeferred<Unit>()
        var firstCall = true
        val channelRepository =
            FakeChannelRepository(
                channelsResult = { serverId ->
                    if (firstCall) {
                        firstCall = false
                        releaseLookup.await()
                    }
                    DataResult.Content(
                        ChannelSnapshot(
                            server = ServerSummary(id = serverId, name = "One"),
                            channels = home.channels,
                        )
                    )
                },
            )
        val diagnostics = mutableListOf<String>()
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
                diagnosticsLogger = { diagnostics.add(it) },
            )

        fun assertNotWritable(stage: String) {
            val state = store.state.value
            // payload.composer.enabled alone is not the write gate pre-lookup;
            // the store-level authority gate is writeActionsEnabled.
            if (state.writeActionsEnabled) {
                throw AssertionError(
                    stage + ": composer=" + state.payload.composer.enabled +
                        " writeActions=" + state.writeActionsEnabled +
                        " authority=" + state.replyParentAuthority +
                        " lookupComplete=" + state.initialHomeAccessLookupComplete +
                        " canonical=" + state.canonicalAccessResolved +
                        " diagnostics=" + diagnostics
                )
            }
        }

        store.accept(SharedThreadIntent.Start(route))
        // Before the initial lookup completes: hints must not pre-grant write access.
        store.accept(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                present = true,
                joined = true,
                canSend = true,
            )
        )
        assertNotWritable("hint before lookup complete")

        // Wrong parent id: ignored.
        store.accept(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-other",
                parentMessageId = "m1",
                present = true,
                joined = true,
                canSend = true,
            )
        )
        assertNotWritable("wrong parent id")

        // Wrong parent message id: ignored.
        store.accept(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-1",
                parentMessageId = "other-message",
                present = true,
                joined = true,
                canSend = true,
            )
        )
        assertNotWritable("wrong parent message id")

        releaseLookup.complete(Unit)
        store.state.filter { state ->
            state.initialHomeAccessLookupComplete && state.payload.composer.enabled
        }.first()
        assertEquals(ReplyParentAuthorityStatus.Writable, store.state.value.replyParentAuthority)
        assertTrue(store.state.value.writeActionsEnabled)
        scope.cancel()
    }
}
