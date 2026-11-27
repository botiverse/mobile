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
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ReplyParentAuthorityStatus
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadAccessResolution
import build.raft.app.thread.dispatch
import build.raft.app.thread.resolveReplyParentDirectoryHint
import build.raft.app.thread.threadFooterShowsArchived
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
        val homeStarted = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val lookupDeadline = CompletableDeferred<Unit>()
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = channelId,
                            body = "unknown-access body",
                            conversation =
                                conversationSummary(channelId).copy(
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
                channelRepository =
                    FakeChannelRepository(
                        homeResult = {
                            homeStarted.complete(Unit)
                            releaseHome.await()
                            DataResult.Content(unjoinedHomeSnapshot())
                        },
                    ),
            )

        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                initialAccessLookupDeadline = { lookupDeadline.await() },
            )
        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(50)
        runCurrent()

        // First canonical load returned renderable content without attested membership
        // (canonicalAccessKnown=false): accessResolution=Resolved, canonicalAccessResolved=false,
        // writeActionsEnabled=false, while the initial Home lookup is still in flight.
        assertTrue(homeStarted.isCompleted, "expected initial Home access lookup to be in flight")
        val unresolvedState = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, unresolvedState.accessResolution, "state=$unresolvedState")
        assertFalse(unresolvedState.canonicalAccessResolved, "state=$unresolvedState")
        assertFalse(unresolvedState.writeActionsEnabled, "state=$unresolvedState")

        // Home lookup returns a stale unjoined row while canonical membership is still unattested:
        // must transition to Loading instead of keeping Resolved with joined=false (which would flash Join).
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

        // Subsequent canonical refresh attests membership -> composer mounts cleanly.
        repository.threadResult =
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
        store.dispatch(SharedThreadIntent.Refresh)
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

    @Test
    fun coldReplyThreadLoadedViaGetThreadWithSyntheticMembershipUnknownUnlocksWrites() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload()
        val joinedHome = replyThreadAccessHomeSnapshot(joined = true, canSend = true)
        val releaseThread = CompletableDeferred<Unit>()
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Error(ScreenError(code = "unexpected_get_reply_thread", retryable = false)),
                threadResult = DataResult.Content(syntheticGetThreadReplySnapshot(body = "first reply")),
                releaseThread = releaseThread,
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = ReplyThreadAccessNetworkApi,
                sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
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
            )
        seedKnownReplyThreadStream(dataSource)

        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(50)
        runCurrent()

        assertTrue(repository.threadStarted.isCompleted, "known threadChannelId must load via getThread")
        assertTrue(repository.replyRequests.isEmpty(), "must not fall back to getReplyThread: ${repository.replyRequests}")
        assertEquals("loading", store.state.value.payload.status, "state=${store.state.value}")

        // Early positive parent directory hint while the reply thread is still loading.
        store.dispatch(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                present = true,
                joined = true,
                canSend = true,
                authoritative = true,
            )
        )
        runCurrent()

        releaseThread.complete(Unit)
        advanceTimeBy(1_500)
        runCurrent()

        val state = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, state.accessResolution, "state=$state")
        assertTrue(state.canonicalAccessResolved, "reply stream load must resolve canonical access; state=$state")
        assertEquals(ReplyParentAuthorityStatus.Writable, state.replyParentAuthority, "state=$state")
        assertTrue(state.writeActionsEnabled, "writes must unlock once reply stream and parent authority resolve; state=$state")
        assertTrue(state.payload.joined, "state=$state")
        assertTrue(state.payload.canSend, "state=$state")
        assertTrue(state.payload.composer.enabled, "state=$state")
        assertEquals(listOf("first reply"), state.payload.messages.map { it.body }, "state=$state")
        scope.cancel()
    }

    @Test
    fun coldReplyThreadViaGetThreadWithUnjoinedParentResolvesToJoinCtaInsteadOfLoading() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload()
        val unjoinedHome = replyThreadAccessHomeSnapshot(joined = false, canSend = false)
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Error(ScreenError(code = "unexpected_get_reply_thread", retryable = false)),
                threadResult = DataResult.Content(syntheticGetThreadReplySnapshot(body = "public reply")),
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = ReplyThreadAccessNetworkApi,
                sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository =
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
                    ),
            )
        seedKnownReplyThreadStream(dataSource)

        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(1_500)
        runCurrent()

        assertTrue(repository.threadStarted.isCompleted, "known threadChannelId must load via getThread")
        assertTrue(repository.replyRequests.isEmpty(), "must not fall back to getReplyThread: ${repository.replyRequests}")

        val state = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, state.accessResolution, "unjoined reply thread must not stay Loading; state=$state")
        assertTrue(state.canonicalAccessResolved, "state=$state")
        assertEquals(ReplyParentAuthorityStatus.Denied, state.replyParentAuthority, "state=$state")
        assertFalse(state.payload.joined, "state=$state")
        assertFalse(state.payload.canSend, "state=$state")
        assertFalse(state.payload.composer.enabled, "state=$state")
        assertTrue(threadFooterShowsJoin(state.payload.joined, conversationLounge = null), "Join CTA must be shown; state=$state")
        scope.cancel()
    }

    @Test
    fun coldEmptyReplyThreadViaGetThreadWithSyntheticMembershipUnknownResolvesWrites() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload()
        val joinedHome = replyThreadAccessHomeSnapshot(joined = true, canSend = true)
        val emptySnapshot =
            syntheticGetThreadReplySnapshot(body = "unused").copy(
                messages = emptyList(),
            )
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Error(ScreenError(code = "unexpected_get_reply_thread", retryable = false)),
                threadResult = DataResult.Content(emptySnapshot),
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = ReplyThreadAccessNetworkApi,
                sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
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
            )
        seedKnownReplyThreadStream(dataSource)

        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(1_500)
        runCurrent()

        assertTrue(repository.threadStarted.isCompleted, "known threadChannelId must load via getThread")
        assertTrue(repository.replyRequests.isEmpty(), "must not fall back to getReplyThread: ${repository.replyRequests}")

        val state = store.state.value
        assertEquals("empty", state.payload.status, "empty reply thread must project status=empty; state=$state")
        assertEquals(ThreadAccessResolution.Resolved, state.accessResolution, "state=$state")
        assertTrue(state.canonicalAccessResolved, "state=$state")
        assertEquals(ReplyParentAuthorityStatus.Writable, state.replyParentAuthority, "state=$state")
        assertTrue(state.writeActionsEnabled, "empty reply thread with joined parent must unlock writes; state=$state")
        assertTrue(state.payload.joined, "state=$state")
        assertTrue(state.payload.canSend, "state=$state")
        assertTrue(state.payload.composer.enabled, "state=$state")
        scope.cancel()
    }

    @Test
    fun coldReplyThreadViaGetThreadResolvingBeforeHomeLookupCompletesUnlocksWhenHomeLands() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload()
        val joinedHome = replyThreadAccessHomeSnapshot(joined = true, canSend = true)
        val homeStarted = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val lookupDeadline = CompletableDeferred<Unit>()
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Error(ScreenError(code = "unexpected_get_reply_thread", retryable = false)),
                threadResult = DataResult.Content(syntheticGetThreadReplySnapshot(body = "fast reply")),
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = ReplyThreadAccessNetworkApi,
                sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                channelRepository =
                    FakeChannelRepository(
                        channelsResult = {
                            homeStarted.complete(Unit)
                            releaseHome.await()
                            DataResult.Content(
                                ChannelSnapshot(
                                    server = joinedHome.server,
                                    channels = joinedHome.channels,
                                )
                            )
                        },
                        homeResult = {
                            homeStarted.complete(Unit)
                            releaseHome.await()
                            DataResult.Content(joinedHome)
                        },
                    ),
            )
        seedKnownReplyThreadStream(dataSource)

        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                initialAccessLookupDeadline = { lookupDeadline.await() },
            )
        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(50)
        runCurrent()

        assertTrue(homeStarted.isCompleted, "expected Home parent lookup to be in flight")
        assertTrue(repository.threadStarted.isCompleted, "known threadChannelId must load via getThread")
        assertTrue(repository.replyRequests.isEmpty(), "must not fall back to getReplyThread: ${repository.replyRequests}")

        val beforeHome = store.state.value
        assertEquals(ThreadAccessResolution.Loading, beforeHome.accessResolution, "must wait for parent Home lookup; state=$beforeHome")
        assertTrue(beforeHome.canonicalAccessResolved, "getThread must already mark canonicalAccessResolved=true; state=$beforeHome")
        assertFalse(beforeHome.initialHomeAccessLookupComplete, "state=$beforeHome")
        assertFalse(beforeHome.writeActionsEnabled, "writes must remain locked until Home lookup lands; state=$beforeHome")

        releaseHome.complete(Unit)
        runCurrent()

        val afterHome = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, afterHome.accessResolution, "state=$afterHome")
        assertTrue(afterHome.canonicalAccessResolved, "state=$afterHome")
        assertTrue(afterHome.initialHomeAccessLookupComplete, "state=$afterHome")
        assertEquals(ReplyParentAuthorityStatus.Writable, afterHome.replyParentAuthority, "state=$afterHome")
        assertTrue(afterHome.writeActionsEnabled, "positive Home lookup must unlock writes; state=$afterHome")
        assertTrue(afterHome.payload.joined, "state=$afterHome")
        assertTrue(afterHome.payload.canSend, "state=$afterHome")
        assertTrue(afterHome.payload.composer.enabled, "state=$afterHome")
        scope.cancel()
    }

    private fun syntheticGetThreadReplySnapshot(body: String): build.raft.app.data.ThreadSnapshot {
        val base = replyThreadAccessSnapshot(body = body, joined = false, canSend = false)
        val syntheticThreadConversation =
            base.conversation.copy(
                kind = ConversationKind.Thread,
                channelType = "thread",
                joined = false,
                membershipKnown = false,
                permissions = ConversationPermissions(canRead = true, canSend = false, canAttach = false, canCreateTask = false),
            )
        return base.copy(
            conversation = syntheticThreadConversation,
            canonicalConversationDetail = syntheticThreadConversation,
        )
    }

    private suspend fun seedKnownReplyThreadStream(
        dataSource: SharedThreadDataSource,
        parentChannelId: String = "channel-1",
        parentMessageId: String = "m1",
        threadChannelId: String = "thread-m1",
    ) {
        val provenance = requireNotNull(dataSource.captureReplyCacheProducerContext()).provenance
        val remembered =
            dataSource.rememberReplyParentCandidate(
                provenance = provenance,
                serverId = provenance.serverId,
                parentChannelId = parentChannelId,
                parentMessage =
                    RaftThreadMessageItem(
                        id = parentMessageId,
                        authorId = "u1",
                        authorName = "Artin",
                        authorKind = "human",
                        seq = 1,
                        body = "parent body",
                        threadChannelId = threadChannelId,
                        replyCount = 1,
                    ),
                threadChannelId = threadChannelId,
                replyCount = 1,
            )
        assertTrue(remembered, "expected reply parent candidate to seed known threadChannelId")
    }
}
