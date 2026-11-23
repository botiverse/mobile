package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.unjoinedHomeSnapshot
import build.raft.app.data.ChannelSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshotLookup
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.TaskConversationChannelRef
import build.raft.app.data.authSessionFixture
import build.raft.app.data.withJoinedChannel
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression suite for #wg-mobile-feedback:#275: returning from background while on a reply thread
 * or task conversation must never turn a live composer into the "Join channel" prompt due to stale
 * pre-join candidates, non-authoritative Home caches, partial join mutations, transient parent
 * detail failures, or synthetic `type = "thread"` channel details.
 */
class SharedThreadMembershipResumeRegressionTest {
    @Test
    fun replyThreadJoinFollowedByRefreshKeepsComposerAndDoesNotRevertToJoin() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload()
        val unjoinedHome = replyThreadAccessHomeSnapshot(joined = false, canSend = false)
        val channelRepository =
            FakeChannelRepository(
                channelsResult = {
                    DataResult.Content(
                        ChannelSnapshot(
                            server = unjoinedHome.server,
                            channels = unjoinedHome.channels,
                        )
                    )
                },
                homeResult = { DataResult.Content(unjoinedHome) },
            )
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository =
                            ReplyThreadAccessConversationRepositoryRecorder(
                                replyResult = DataResult.Content(replyThreadAccessSnapshot(body = "first reply"))
                            ),
                        channelRepository = channelRepository,
                    ),
                scope = scope,
            )

        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(1_500)
        runCurrent()
        val beforeJoin = store.state.value
        assertTrue(beforeJoin.canonicalAccessResolved, "expected canonical access resolved; state=$beforeJoin")
        assertTrue(beforeJoin.initialHomeAccessLookupComplete, "expected home lookup complete; state=$beforeJoin")
        assertFalse(beforeJoin.payload.joined, "expected initial unjoined state; state=$beforeJoin")
        assertTrue(threadFooterShowsJoin(beforeJoin.payload.joined, conversationLounge = null))

        // User taps Join in the reply thread footer.
        store.dispatch(SharedThreadIntent.ApplyChannelJoined("channel-1"))
        runCurrent()
        val afterJoin = store.state.value
        assertTrue(afterJoin.payload.joined, "state=$afterJoin")
        assertTrue(afterJoin.payload.canSend, "state=$afterJoin")
        assertTrue(afterJoin.payload.composer.enabled, "state=$afterJoin")
        assertEquals(ReplyParentAuthorityStatus.Writable, afterJoin.replyParentAuthority, "state=$afterJoin")
        assertTrue(afterJoin.writeActionsEnabled, "state=$afterJoin")
        assertTrue(afterJoin.initialHomeAccessCandidate?.joined == true, "state=$afterJoin")
        assertTrue(afterJoin.initialHomeAccessCandidate?.permissions?.canSend == true, "state=$afterJoin")

        // A later Refresh (e.g. id-only channel:updated, offline recovery, or jump-to-latest)
        // must not re-apply the pre-join candidate and flip the composer back to Join.
        store.dispatch(SharedThreadIntent.Refresh)
        advanceTimeBy(1_500)
        runCurrent()
        val afterRefresh = store.state.value
        assertTrue(afterRefresh.payload.joined, "state=$afterRefresh")
        assertTrue(afterRefresh.payload.canSend, "state=$afterRefresh")
        assertTrue(afterRefresh.payload.composer.enabled, "state=$afterRefresh")
        assertEquals(ReplyParentAuthorityStatus.Writable, afterRefresh.replyParentAuthority, "state=$afterRefresh")
        assertTrue(afterRefresh.writeActionsEnabled, "state=$afterRefresh")
        assertFalse(threadFooterShowsJoin(afterRefresh.payload.joined, conversationLounge = null))
        scope.cancel()
    }

    @Test
    fun homeWithJoinedChannelUpdatesPermissionsSoReplyThreadHintStaysWritable() {
        val unjoined = unjoinedHomeSnapshot()
        val beforeRow = assertNotNull(unjoined.channels.firstOrNull { it.channelId == ChannelId("channel-1") })
        assertFalse(beforeRow.joined)
        assertFalse(beforeRow.permissions.canSend)

        val joined = unjoined.withJoinedChannel(ChannelId("channel-1"), rowIfMissing = null)
        val afterRow = assertNotNull(joined.channels.firstOrNull { it.channelId == ChannelId("channel-1") })
        assertTrue(afterRow.joined)
        assertTrue(afterRow.permissions.canSend)
        assertTrue(afterRow.permissions.canAttach)
        assertTrue(afterRow.permissions.canCreateTask)

        // Calling withJoinedChannel when joined=true but canCreateTask=false must still repair canCreateTask.
        val partialJoined =
            joined.copy(
                channels =
                    joined.channels.map { row ->
                        if (row.channelId == ChannelId("channel-1")) {
                            row.copy(permissions = row.permissions.copy(canCreateTask = false))
                        } else {
                            row
                        }
                    }
            )
        val repaired = partialJoined.withJoinedChannel(ChannelId("channel-1"), rowIfMissing = null)
        assertTrue(
            repaired.channels.first { it.channelId == ChannelId("channel-1") }.permissions.canCreateTask,
            "withJoinedChannel must repair stale canCreateTask=false",
        )

        val hint =
            assertNotNull(
                resolveReplyParentDirectoryHint(
                    payload = replyThreadInitialAccessPayload(),
                    homeState =
                        SharedHomeState(
                            content =
                                SharedHomeContentState.Content(
                                    snapshot = joined,
                                    session = sampleSession(),
                                    baseSnapshot = joined,
                                    isDurableIdentityCache = false,
                                    needsNetworkRefresh = false,
                                )
                        ),
                    expectedServerId = "server-1",
                    loungeLookup = { _, _ -> null },
                )
            )
        assertTrue(hint.present)
        assertTrue(hint.joined)
        assertTrue(hint.canSend)
        assertTrue(hint.authoritative)
    }

    @Test
    fun nonAuthoritativeNegativeParentHintCannotDowngradeWritableReplyThreadToJoin() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload()
        val joinedHome = replyThreadAccessHomeSnapshot(joined = true, canSend = true)
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository =
                            ReplyThreadAccessConversationRepositoryRecorder(
                                replyResult = DataResult.Content(replyThreadAccessSnapshot(body = "live reply"))
                            ),
                        channelRepository =
                            FakeChannelRepository(
                                channelsResult = {
                                    DataResult.Content(
                                        ChannelSnapshot(
                                            server = joinedHome.server,
                                            channels = joinedHome.channels,
                                        )
                                    )
                                },
                                homeResult = { DataResult.Content(joinedHome) },
                            ),
                    ),
                scope = scope,
            )

        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(1_500)
        runCurrent()
        val writable = store.state.value
        assertTrue(writable.canonicalAccessResolved, "state=$writable")
        assertTrue(writable.initialHomeAccessLookupComplete, "state=$writable")
        assertEquals(ReplyParentAuthorityStatus.Writable, writable.replyParentAuthority, "state=$writable")
        assertTrue(writable.payload.joined, "state=$writable")
        assertTrue(writable.payload.composer.enabled, "state=$writable")

        // A stale durable-cache / refresh-pending Home snapshot reporting joined=false must not
        // downgrade the open writable thread to the Join CTA.
        store.dispatch(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                present = true,
                joined = false,
                canSend = false,
                authoritative = false,
            )
        )
        runCurrent()
        val afterStaleHint = store.state.value
        assertEquals(ReplyParentAuthorityStatus.Writable, afterStaleHint.replyParentAuthority, "state=$afterStaleHint")
        assertTrue(afterStaleHint.payload.joined, "state=$afterStaleHint")
        assertTrue(afterStaleHint.payload.canSend, "state=$afterStaleHint")
        assertTrue(afterStaleHint.payload.composer.enabled, "state=$afterStaleHint")
        assertTrue(afterStaleHint.writeActionsEnabled, "state=$afterStaleHint")

        // Once a network-authoritative Home snapshot confirms the user left, Denied + Join applies.
        store.dispatch(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                present = true,
                joined = false,
                canSend = false,
                authoritative = true,
            )
        )
        runCurrent()
        val afterAuthoritativeLeave = store.state.value
        assertEquals(ReplyParentAuthorityStatus.Denied, afterAuthoritativeLeave.replyParentAuthority, "state=$afterAuthoritativeLeave")
        assertFalse(afterAuthoritativeLeave.payload.joined, "state=$afterAuthoritativeLeave")
        assertFalse(afterAuthoritativeLeave.payload.canSend, "state=$afterAuthoritativeLeave")
        assertFalse(afterAuthoritativeLeave.payload.composer.enabled, "state=$afterAuthoritativeLeave")
        assertTrue(afterAuthoritativeLeave.writeActionsEnabled, "state=$afterAuthoritativeLeave")
        scope.cancel()
    }

    @Test
    fun nonAuthoritativeDurableHomeUnjoinedSnapshotDoesNotLockReplyThreadIntoJoin() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload()
        val staleDurableUnjoined = replyThreadAccessHomeSnapshot(joined = false, canSend = false)
        val liveJoined = replyThreadAccessHomeSnapshot(joined = true, canSend = true)
        var channelLookupCount = 0
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository =
                            ReplyThreadAccessConversationRepositoryRecorder(
                                replyResult = DataResult.Content(replyThreadAccessSnapshot(body = "resumed reply"))
                            ),
                        homeSnapshotLookupProvider = {
                            HomeSnapshotLookup(snapshot = staleDurableUnjoined, authoritative = false)
                        },
                        channelRepository =
                            FakeChannelRepository(
                                channelsResult = {
                                    channelLookupCount += 1
                                    DataResult.Content(
                                        ChannelSnapshot(
                                            server = liveJoined.server,
                                            channels = liveJoined.channels,
                                        )
                                    )
                                },
                                homeResult = { DataResult.Content(liveJoined) },
                            ),
                    ),
                scope = scope,
            )

        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(1_500)
        runCurrent()

        val state = store.state.value
        assertEquals(1, channelLookupCount, "stale durable unjoined snapshot must fall through to live channels lookup; state=$state")
        assertEquals(ReplyParentAuthorityStatus.Writable, state.replyParentAuthority, "state=$state")
        assertTrue(state.payload.joined, "state=$state")
        assertTrue(state.payload.canSend, "state=$state")
        assertTrue(state.payload.composer.enabled, "state=$state")
        assertTrue(state.writeActionsEnabled, "state=$state")
        assertFalse(threadFooterShowsJoin(state.payload.joined, conversationLounge = null))
        scope.cancel()
    }

    @Test
    fun staleNegativeHomeCandidateOnKeptResolvedAccessDoesNotFlashJoin() = runTest {
        val route = basePayload()
        val channelId = requireNotNull(route.sourceThreadId)
        val releaseCanonical = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = channelId,
                            body = "fresh body",
                            conversation =
                                conversationSummary(channelId).copy(
                                    joined = true,
                                    membershipKnown = true,
                                    permissions = ConversationPermissions(canSend = true, canAttach = true, canCreateTask = true),
                                ),
                        )
                    )
            ).apply {
                releaseGetThread = releaseCanonical
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository =
                    FakeChannelRepository(
                        homeResult = {
                            releaseHome.await()
                            DataResult.Content(unjoinedHomeSnapshot())
                        },
                    ),
            )
        // Warm a read-only cached joined frame AND remembered resolved access so reopening starts in
        // keptResolvedAccess (accessResolution=Resolved, canonicalAccessResolved=false, writeActionsEnabled=false)
        // and refreshOnce launches initial access resolution via retryingReadOnlyParentAccess.
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached body").copy(
                joined = true,
                canSend = false,
                composer = route.composer.copy(enabled = false),
            )
        )
        val resolvedProvenance = assertNotNull(dataSource.captureReplyCacheProducerContext()?.provenance)
        dataSource.rememberResolvedThreadAccess(channelId, resolvedProvenance, resolved = true)

        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(route))
        runCurrent()

        // Before refreshOnce launches initial access resolution, keptResolvedAccess holds Resolved + joined=true.
        val keptState = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, keptState.accessResolution, "expected keptResolvedAccess=Resolved; state=$keptState")
        assertFalse(keptState.canonicalAccessResolved, "expected canonicalAccessResolved=false; state=$keptState")
        assertTrue(keptState.payload.joined, "expected cached joined=true before Home lookup; state=$keptState")
        assertFalse(keptState.writeActionsEnabled, "expected read-only writeActionsEnabled=false before refresh; state=$keptState")

        // Advance past SharedThreadFirstFrameDelayMillis (16ms) so refreshOnce launches initial
        // access resolution (retryingReadOnlyParentAccess), without hitting the 1000ms lookup timeout.
        advanceTimeBy(50)
        runCurrent()

        // Home lookup returns a stale unjoined row while canonical fetch is still in flight:
        // must NOT keep Resolved with joined=false (which would flash Join).
        releaseHome.complete(Unit)
        runCurrent()
        val waitingState = store.state.value
        assertEquals(
            ThreadAccessResolution.Loading,
            waitingState.accessResolution,
            "negative Home row must wait for canonical instead of flashing Join; state=$waitingState",
        )
        assertFalse(
            waitingState.writeActionsEnabled,
            "writeActionsEnabled must stay false while waiting on canonical over stale unjoined Home row; state=$waitingState",
        )
        assertTrue(
            waitingState.initialHomeAccessLookupComplete,
            "expected Home lookup to complete before asserting Loading; state=$waitingState",
        )

        // Canonical fetch confirms membership -> composer mounts cleanly.
        releaseCanonical.complete(Unit)
        advanceTimeBy(500)
        runCurrent()
        val resolvedState = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, resolvedState.accessResolution, "state=$resolvedState")
        assertTrue(resolvedState.canonicalAccessResolved, "state=$resolvedState")
        assertTrue(resolvedState.payload.joined, "state=$resolvedState")
        assertTrue(resolvedState.payload.composer.enabled, "state=$resolvedState")
        assertTrue(resolvedState.writeActionsEnabled, "state=$resolvedState")
        scope.cancel()
    }

    @Test
    fun resolveReplyParentDirectoryHintIgnoresWrongServerNullServerAndDurableCache() {
        val route = replyThreadInitialAccessPayload()
        val otherServerHome = replyThreadAccessHomeSnapshot(serverId = ServerId("server-other"))
        val wrongServerHint =
            assertNotNull(
                resolveReplyParentDirectoryHint(
                    payload = route,
                    homeState =
                        SharedHomeState(
                            content =
                                SharedHomeContentState.Content(
                                    snapshot = otherServerHome,
                                    session = sampleSession(),
                                    baseSnapshot = otherServerHome,
                                    isDurableIdentityCache = false,
                                    needsNetworkRefresh = false,
                                )
                        ),
                    expectedServerId = "server-1",
                    loungeLookup = { _, _ -> null },
                )
            )
        assertFalse(wrongServerHint.present)
        assertFalse(wrongServerHint.authoritative)

        val nullServerHint =
            assertNotNull(
                resolveReplyParentDirectoryHint(
                    payload = route,
                    homeState =
                        SharedHomeState(
                            content =
                                SharedHomeContentState.Content(
                                    snapshot = otherServerHome,
                                    session = sampleSession(),
                                    baseSnapshot = otherServerHome,
                                    isDurableIdentityCache = false,
                                    needsNetworkRefresh = false,
                                )
                        ),
                    expectedServerId = null,
                    loungeLookup = { _, _ -> null },
                )
            )
        assertFalse(nullServerHint.present)
        assertFalse(nullServerHint.authoritative, "null expectedServerId must never claim authoritative directory match")

        val durableUnjoinedHome = replyThreadAccessHomeSnapshot(joined = false, canSend = false)
        val durableHint =
            assertNotNull(
                resolveReplyParentDirectoryHint(
                    payload = route,
                    homeState =
                        SharedHomeState(
                            content =
                                SharedHomeContentState.Content(
                                    snapshot = durableUnjoinedHome,
                                    session = sampleSession(),
                                    baseSnapshot = durableUnjoinedHome,
                                    isDurableIdentityCache = true,
                                    needsNetworkRefresh = false,
                                )
                        ),
                    expectedServerId = "server-1",
                    loungeLookup = { _, _ -> null },
                )
            )
        assertTrue(durableHint.present)
        assertFalse(durableHint.joined)
        assertFalse(durableHint.authoritative)
    }

    @Test
    fun taskConversationUnknownParentMembershipPreservesHomeAccessAndNeverFlashesJoin() = runTest {
        val conversationId = "task-conv-1"
        val taskRoute =
            basePayload().copy(
                instanceId = "shared-shell-thread-$conversationId",
                title = "",
                sourceThreadId = conversationId,
                streamChannelId = conversationId,
                channelKind = RaftTaskConversationChannelKind,
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = conversationId,
                            body = "task reply",
                            conversation =
                                conversationSummary(conversationId).copy(
                                    kind = ConversationKind.Task,
                                    channelType = RaftTaskConversationChannelKind,
                                    joined = false,
                                    membershipKnown = false,
                                    permissions = ConversationPermissions(canSend = false, canAttach = false, canCreateTask = false),
                                ),
                        )
                    )
            ).apply {
                taskConversationRefs =
                    mapOf(
                        ChannelId(conversationId) to
                            TaskConversationChannelRef(
                                conversationChannelId = ChannelId(conversationId),
                                parentChannelId = ChannelId("channel-1"),
                                taskId = TaskId("task-1"),
                            )
                    )
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = FakeChannelRepository(homeResult = { DataResult.Content(joinedHomeSnapshot()) }),
            )
        dataSource.cachePayloadForTest(contentPayloadForRoute(taskRoute, "cached body"))

        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(taskRoute))
        advanceTimeBy(1_500)
        runCurrent()

        val state = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, state.accessResolution, "state=$state")
        assertFalse(state.canonicalAccessResolved, "unknown parent detail must not lock canonical access")
        assertTrue(state.payload.joined, "must preserve Home-attested joined=true; state=$state")
        assertTrue(state.payload.canSend, "must preserve Home-attested canSend=true; state=$state")
        assertTrue(state.payload.composer.enabled, "composer must stay enabled; state=$state")
        assertTrue(state.writeActionsEnabled, "state=$state")
        assertFalse(threadFooterShowsJoin(state.payload.joined, conversationLounge = null))
        scope.cancel()
    }

    @Test
    fun taskConversationUnknownParentFollowedByErrorRefreshKeepsWriteActionsDisabled() = runTest {
        val conversationId = "task-conv-2"
        val taskRoute =
            basePayload().copy(
                instanceId = "shared-shell-thread-$conversationId",
                title = "",
                sourceThreadId = conversationId,
                streamChannelId = conversationId,
                channelKind = RaftTaskConversationChannelKind,
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = conversationId,
                            body = "task reply",
                            conversation =
                                conversationSummary(conversationId).copy(
                                    kind = ConversationKind.Task,
                                    channelType = RaftTaskConversationChannelKind,
                                    joined = false,
                                    membershipKnown = false,
                                    permissions = ConversationPermissions(canSend = false, canAttach = false, canCreateTask = false),
                                ),
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = FakeChannelRepository(homeResult = { DataResult.Content(unjoinedHomeSnapshot()) }),
            )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(taskRoute))
        advanceTimeBy(2_000)
        runCurrent()

        val afterUnknown = store.state.value
        assertEquals(listOf("task reply"), afterUnknown.payload.messages.map { it.body })
        assertEquals(ThreadAccessResolution.Resolved, afterUnknown.accessResolution, "messages must stay readable; state=$afterUnknown")
        assertFalse(afterUnknown.canonicalAccessResolved, "state=$afterUnknown")
        assertFalse(afterUnknown.writeActionsEnabled, "unknown parent membership must not enable Join CTA; state=$afterUnknown")

        // Subsequent offline DataResult.Error refresh must NOT flip writeActionsEnabled to true on unattested defaults.
        repository.threadResult = DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
        store.dispatch(SharedThreadIntent.Refresh)
        advanceTimeBy(1_500)
        runCurrent()

        val afterOffline = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, afterOffline.accessResolution, "state=$afterOffline")
        assertFalse(afterOffline.canonicalAccessResolved, "state=$afterOffline")
        assertFalse(
            afterOffline.writeActionsEnabled,
            "subsequent DataResult.Error must not flip writeActionsEnabled=true without attested membership; state=$afterOffline",
        )
        scope.cancel()
    }

    @Test
    fun taskConversationUnknownParentAcceptsLatePositiveTaskRefCandidate() = runTest {
        val conversationId = "task-conv-late"
        val taskRoute =
            basePayload().copy(
                instanceId = "shared-shell-thread-$conversationId",
                title = "",
                sourceThreadId = conversationId,
                streamChannelId = conversationId,
                channelKind = RaftTaskConversationChannelKind,
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = conversationId,
                            body = "task reply",
                            conversation =
                                conversationSummary(conversationId).copy(
                                    kind = ConversationKind.Task,
                                    channelType = RaftTaskConversationChannelKind,
                                    joined = false,
                                    membershipKnown = false,
                                    permissions = ConversationPermissions(canSend = false, canAttach = false, canCreateTask = false),
                                ),
                        )
                    )
            ).apply {
                taskConversationRefDelayMillis = 1_900L
                taskConversationRefs =
                    mapOf(
                        ChannelId(conversationId) to
                            TaskConversationChannelRef(
                                conversationChannelId = ChannelId(conversationId),
                                parentChannelId = ChannelId("channel-1"),
                                taskId = TaskId("task-late"),
                            )
                    )
            }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository = FakeChannelRepository(homeResult = { DataResult.Content(joinedHomeSnapshot()) }),
            )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(taskRoute))
        // Past the 1000ms initial access deadline, canonical unknown-parent content is shown fail-closed.
        advanceTimeBy(1_200)
        runCurrent()
        val beforeLateRef = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, beforeLateRef.accessResolution, "state=$beforeLateRef")
        assertFalse(beforeLateRef.canonicalAccessResolved, "state=$beforeLateRef")
        assertFalse(beforeLateRef.writeActionsEnabled, "state=$beforeLateRef")

        // Late task-ref lookup completes at 1900ms and finds joined parent in Home -> composer mounts.
        advanceTimeBy(1_500)
        runCurrent()
        val afterLateRef = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, afterLateRef.accessResolution, "state=$afterLateRef")
        assertTrue(afterLateRef.writeActionsEnabled, "late positive task-ref candidate must unlock composer; state=$afterLateRef")
        assertTrue(afterLateRef.payload.joined, "state=$afterLateRef")
        assertTrue(afterLateRef.payload.canSend, "state=$afterLateRef")
        assertTrue(afterLateRef.payload.composer.enabled, "state=$afterLateRef")
        scope.cancel()
    }

    @Test
    fun threadTypeCanonicalDetailIsNotPublishedIntoConversationIdentityHub() = runTest {
        val published = mutableListOf<ConversationSummary>()
        val syntheticThreadDetail =
            conversationSummary("thread-stream-1").copy(
                kind = ConversationKind.Thread,
                channelType = "thread",
                joined = false,
                membershipKnown = false,
                permissions = ConversationPermissions(canSend = false, canAttach = false, canCreateTask = false),
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-stream-1",
                            body = "reply body",
                            conversation = syntheticThreadDetail.copy(joined = true),
                        ).copy(canonicalConversationDetail = syntheticThreadDetail)
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                canonicalConversationIdentityPublisher = { _, detail ->
                    published += detail
                    true
                },
            )
        val route =
            basePayload().copy(
                instanceId = "reply-thread-1",
                sourceThreadId = "thread-stream-1",
                streamChannelId = "thread-stream-1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "channel",
            )
        val result = dataSource.fetchThreadPayload(route)
        assertIs<DataResult.Content<RaftThreadScreenPayload>>(result)
        assertTrue(published.isEmpty(), "synthetic thread detail with unknown membership must not pollute identity hub: $published")
    }

    @Test
    fun archivedParentReplyThreadMissingFromHomeDirectoryIsNotMarkedParentDeleted() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload().copy(channelArchived = true)
        val joinedHome = replyThreadAccessHomeSnapshot(joined = true, canSend = true)
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        storeScope = backgroundScope,
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository =
                            ReplyThreadAccessConversationRepositoryRecorder(
                                replyResult =
                                    DataResult.Content(
                                        replyThreadAccessSnapshot(body = "archived reply").let { snapshot ->
                                            snapshot.copy(conversation = snapshot.conversation.copy(archived = true))
                                        }
                                    )
                            ),
                        channelRepository =
                            FakeChannelRepository(
                                channelsResult = {
                                    DataResult.Content(
                                        ChannelSnapshot(
                                            server = joinedHome.server,
                                            channels = joinedHome.channels,
                                        )
                                    )
                                },
                                homeResult = { DataResult.Content(joinedHome) },
                            ),
                    ),
                scope = scope,
            )

        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(store.state.value.canonicalAccessResolved, "state=${store.state.value}")
        assertTrue(store.state.value.initialHomeAccessLookupComplete, "state=${store.state.value}")
        assertTrue(store.state.value.payload.channelArchived, "state=${store.state.value}")

        // GET /channels omits archived channels, so Home reports present=false, authoritative=true.
        store.dispatch(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                present = false,
                joined = false,
                canSend = false,
                authoritative = true,
            )
        )
        runCurrent()
        val afterHint = store.state.value
        assertNotEquals(
            ReplyParentAuthorityStatus.ParentDeleted,
            afterHint.replyParentAuthority,
            "archived parent channel missing from Home list must keep archived banner, not ParentDeleted; state=$afterHint",
        )
        assertTrue(
            afterHint.payload.channelArchived && threadFooterShowsArchived(afterHint.payload),
            "archived reply thread must continue showing archived footer; state=$afterHint",
        )
        scope.cancel()
    }
}
