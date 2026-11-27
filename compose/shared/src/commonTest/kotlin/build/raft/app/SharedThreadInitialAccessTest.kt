package build.raft.app

import build.raft.app.data.ChannelSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.thread.ReplyParentAuthorityStatus
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadAccessResolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
            InMemoryRaftAuthSessionStore(
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
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
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
                    testSharedThreadDataSource(
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
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
                    testSharedThreadDataSource(
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
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
    fun parentAccessLookupTimeoutMarksAuthorityUnknownAndHintRecoversComposer() = runBlocking {
        // task #243: this case waits for a state that ONLY the lookup-timeout path can produce,
        // and that timeout used to be a real second of wall clock
        // (ThreadInitialAccessLookupTimeoutMillis). On a loaded CI runner the wait could outlive
        // the 60s watchdog, which killed the JVM (exit 73) - intermittent by construction, and
        // green locally every time, which is why it survived.
        //
        // Virtual time is not available here: commonTest avoids
        // kotlinx-coroutines-test. So the deadline is injected as an explicit barrier
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
                    testSharedThreadDataSource(
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
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
    fun authoritativeParentAbsenceMarksParentDeletedAndNonAuthoritativeAbsenceStaysUnknown() =
        runBlocking {
            // task #339: opening a reply thread inside a deleted channel (e.g.
            // from Saved) must not sit on "checking permissions" forever. Only
            // a network-authoritative directory snapshot may attest the parent
            // is gone; a cache/refresh-pending miss stays Unknown.
            //
            // CI watchdog note: same shape as parentDeletedRecomputesToWritable
            // - injected lookup deadline plus bounded waits that dump state on
            // timeout, so a regression fails fast and readably.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val lookupDeadline = CompletableDeferred<Unit>()
            // The directory-lookup apply pass must run AFTER the canonical
            // reply refresh applies: it never recomputes replyParentAuthority
            // itself, and hints are gated on canonicalAccessResolved. Releasing
            // the lookup only once canonical access is resolved mirrors the
            // ordering hintsForOtherParentsOrBeforeLookupCompletionAreIgnored
            // relies on; the deadline stays injected as a backstop only.
            val releaseLookup = CompletableDeferred<Unit>()
            var firstLookupCall = true
            val diagnostics = mutableListOf<String>()
            val route = replyThreadInitialAccessPayload()
            val home = replyThreadAccessHomeSnapshot(joined = true, canSend = true)
            val channelRepository =
                FakeChannelRepository(
                    channelsResult = { serverId ->
                        if (firstLookupCall) {
                            firstLookupCall = false
                            releaseLookup.await()
                        }
                        DataResult.Content(
                            ChannelSnapshot(
                                server = ServerSummary(id = serverId, name = "One"),
                                channels = emptyList(),
                            )
                        )
                    },
                )
            val store =
                SharedThreadStore(
                    dataSource =
                        testSharedThreadDataSource(
                            networkApi = ReplyThreadAccessNetworkApi,
                            sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
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

            fun dumpState(reason: String): Nothing {
                val s = store.state.value
                throw AssertionError(
                    "$reason\nlastState: authority=${s.replyParentAuthority} " +
                        "lookupComplete=${s.initialHomeAccessLookupComplete} " +
                        "canonicalResolved=${s.canonicalAccessResolved} " +
                        "accessResolution=${s.accessResolution} " +
                        "parentChannelId=${s.payload.parentChannelId} " +
                        "parentMessageId=${s.payload.parentMessageId} " +
                        "joined=${s.payload.joined} canSend=${s.payload.canSend} " +
                        "composerEnabled=${s.payload.composer.enabled} " +
                        "writeActions=${s.writeActionsEnabled}\n" +
                        "diagnostics:\n" + diagnostics.joinToString("\n") { "  $it" }
                )
            }
            suspend fun awaitAuthority(
                expected: ReplyParentAuthorityStatus,
                requireLookupComplete: Boolean = false,
            ) {
                try {
                    withTimeout(10_000) {
                        store.state.filter { state ->
                            (!requireLookupComplete || state.initialHomeAccessLookupComplete) &&
                                state.replyParentAuthority == expected
                        }.first()
                    }
                } catch (_: TimeoutCancellationException) {
                    dumpState("timed out waiting for authority=$expected")
                }
            }

            suspend fun awaitCanonicalResolved() {
                try {
                    withTimeout(10_000) {
                        store.state.filter { it.canonicalAccessResolved }.first()
                    }
                } catch (_: TimeoutCancellationException) {
                    dumpState("timed out waiting for canonicalAccessResolved")
                }
            }

            store.accept(SharedThreadIntent.Start(route))
            lookupDeadline.complete(Unit)
            // The directory lookup parks inside getChannels until canonical
            // reply access resolves; only then is the lookup released so the
            // apply pass (and every hint gate after it) sees the real order.
            awaitCanonicalResolved()
            releaseLookup.complete(Unit)
            // Directory read completed but has no parent row and no candidate:
            // canonical reply content is readable, so authority is Unknown.
            awaitAuthority(ReplyParentAuthorityStatus.Unknown, requireLookupComplete = true)
            assertFalse(store.state.value.writeActionsEnabled)

            // A missing row in a non-authoritative snapshot (durable identity
            // cache or refresh pending) cannot upgrade anything.
            store.accept(
                SharedThreadIntent.ApplyParentDirectoryHint(
                    parentChannelId = "channel-1",
                    parentMessageId = "m1",
                    present = false,
                    joined = false,
                    canSend = false,
                    authoritative = false,
                )
            )
            assertEquals(ReplyParentAuthorityStatus.Unknown, store.state.value.replyParentAuthority)
            assertFalse(store.state.value.writeActionsEnabled)

            // A missing row in a network-authoritative snapshot means the
            // channel was deleted.
            store.accept(
                SharedThreadIntent.ApplyParentDirectoryHint(
                    parentChannelId = "channel-1",
                    parentMessageId = "m1",
                    present = false,
                    joined = false,
                    canSend = false,
                    authoritative = true,
                )
            )
            awaitAuthority(ReplyParentAuthorityStatus.ParentDeleted)
            assertFalse(store.state.value.writeActionsEnabled)
            assertFalse(store.state.value.payload.composer.enabled)
            scope.cancel()
        }
}
