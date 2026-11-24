package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.contentPayloadForRoute
import build.raft.app.SharedThreadStoreTestFixtures.joinedHomeSnapshot
import build.raft.app.SharedThreadStoreTestFixtures.unjoinedHomeSnapshot
import build.raft.app.data.ChannelSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.HomeSnapshotLookup
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.TaskConversationChannelRef
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Verifies the 1.29.0 optimistic first-frame composer invariants (dm:@artin:#164):
 * - Immediate 0ms positive Home lookup does not block on an in-flight Home refresh
 *   (`homeRealtimeDirty` / `awaitSnapshotLookupFor`), while non-authoritative negative snapshots
 *   still fall through to authoritative resolution.
 * - Positive `ApplyParentDirectoryHint` during `Loading` unlocks ReplyThreads immediately instead
 *   of deferring behind `canonicalAccessResolved && initialHomeAccessLookupComplete`, without
 *   bypassing canonical `NoPermission` or letting a timed-out lookup clobber `Writable`.
 * - `threadShowsOptimisticComposer`, `optimisticComposerChannelId`, and synchronous
 *   `ensureThreadComposerDraftStarted` gates behave accurately across all surfaces.
 */
class ThreadOptimisticComposerAccessTest {
    @Test
    fun replyThreadWithRefreshingNonAuthoritativeHomeSnapshotResolvesPositiveParentImmediately() = runTest {
        // Reproduces artin's 52bdd7b9 scenario on slow VPN: homeRealtimeDirty=true leaves
        // awaitSnapshotLookupFor blocked on loadJob.join(), while snapshotLookupFor already has
        // a positive joined+canSend row for the parent channel.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload()
        val positiveNonAuthoritativeHome =
            HomeSnapshotLookup(
                snapshot = replyThreadAccessHomeSnapshot(joined = true, canSend = true),
                authoritative = false,
            )
        val neverCompletingHomeAwait = CompletableDeferred<HomeSnapshotLookup?>()
        val releaseCanonicalReply = CompletableDeferred<Unit>()
        val channelRepository = FakeChannelRepository()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = ReplyThreadAccessNetworkApi,
                sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    ReplyThreadAccessConversationRepositoryRecorder(
                        replyResult = DataResult.Content(replyThreadAccessSnapshot(body = "network reply")),
                        releaseReply = releaseCanonicalReply,
                    ),
                homeSnapshotLookupProvider = { neverCompletingHomeAwait.await() },
                immediateHomeSnapshotLookupProvider = { positiveNonAuthoritativeHome },
                channelRepository = channelRepository,
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached reply", channelId = "thread-m1").copy(
                joined = false,
                canSend = false,
                composer = route.composer.copy(enabled = false),
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(100)
        runCurrent()

        val state = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, state.accessResolution, "state=$state")
        assertEquals(ReplyParentAuthorityStatus.Writable, state.replyParentAuthority, "state=$state")
        assertTrue(state.writeActionsEnabled, "state=$state")
        assertTrue(state.payload.joined, "state=$state")
        assertTrue(state.payload.canSend, "state=$state")
        assertTrue(state.payload.composer.enabled, "state=$state")
        assertFalse(state.canonicalAccessResolved, "canonical reply fetch is still in flight; state=$state")
        assertEquals(0, channelRepository.channelsCalls, "must not hit GET /channels when immediate positive row exists")
        scope.cancel()
    }

    @Test
    fun replyThreadWithNonAuthoritativeNegativeImmediateSnapshotStillFallsThroughToAuthoritativeLookup() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload()
        val staleUnjoined =
            HomeSnapshotLookup(
                snapshot = replyThreadAccessHomeSnapshot(joined = false, canSend = false),
                authoritative = false,
            )
        val liveJoined = replyThreadAccessHomeSnapshot(joined = true, canSend = true)
        var channelLookupCount = 0
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = ReplyThreadAccessNetworkApi,
                sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    ReplyThreadAccessConversationRepositoryRecorder(
                        replyResult = DataResult.Content(replyThreadAccessSnapshot(body = "live reply")),
                    ),
                homeSnapshotLookupProvider = { staleUnjoined },
                immediateHomeSnapshotLookupProvider = { staleUnjoined },
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
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(1_500)
        runCurrent()

        val state = store.state.value
        assertEquals(1, channelLookupCount, "stale non-authoritative unjoined row must fall through; state=$state")
        assertEquals(ReplyParentAuthorityStatus.Writable, state.replyParentAuthority, "state=$state")
        assertTrue(state.payload.joined, "state=$state")
        assertTrue(state.payload.canSend, "state=$state")
        assertTrue(state.writeActionsEnabled, "state=$state")
        scope.cancel()
    }

    @Test
    fun channelAndTaskConversationResolvePositiveAccessImmediatelyWhileHomeRefreshIsInFlight() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val neverCompletingHomeAwait = CompletableDeferred<HomeSnapshotLookup?>()
        val positiveNonAuthoritativeHome =
            HomeSnapshotLookup(snapshot = joinedHomeSnapshot(), authoritative = false)

        // 1. Channel route with cached content resolves without awaiting the hung Home refresh.
        val channelRepo =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "network body", conversation = conversationSummary().copy(joined = true)))
            ).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
            }
        val channelDataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = channelRepo,
                homeSnapshotLookupProvider = { neverCompletingHomeAwait.await() },
                immediateHomeSnapshotLookupProvider = { positiveNonAuthoritativeHome },
            )
        channelDataSource.cachePayloadForTest(contentPayload("cached channel").copy(joined = true, canSend = true))
        val channelStore = SharedThreadStore(dataSource = channelDataSource, scope = scope)
        channelStore.dispatch(SharedThreadIntent.Start(basePayload()))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ThreadAccessResolution.Resolved, channelStore.state.value.accessResolution)
        assertTrue(channelStore.state.value.writeActionsEnabled)
        assertFalse(channelStore.state.value.canonicalAccessResolved)

        // 2. Task conversation resolves via immediate parent row without blocking on Home loadJob.
        val taskConversationId = "task-conv-93a91131"
        val taskRoute =
            basePayload().copy(
                instanceId = "shared-shell-thread-$taskConversationId",
                sourceThreadId = taskConversationId,
                streamChannelId = taskConversationId,
                channelKind = RaftTaskConversationChannelKind,
            )
        val taskRepo =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = taskConversationId,
                            body = "network task",
                            conversation = conversationSummary(taskConversationId).copy(
                                kind = ConversationKind.Task,
                                channelType = RaftTaskConversationChannelKind,
                                joined = true,
                            ),
                        )
                    )
            ).apply {
                getThreadStarted = CompletableDeferred()
                releaseGetThread = CompletableDeferred()
                taskConversationRefs =
                    mapOf(
                        ChannelId(taskConversationId) to
                            TaskConversationChannelRef(
                                conversationChannelId = ChannelId(taskConversationId),
                                parentChannelId = ChannelId("channel-1"),
                                taskId = TaskId("task-93a9"),
                            )
                    )
            }
        val taskDataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = taskRepo,
                homeSnapshotLookupProvider = { neverCompletingHomeAwait.await() },
                immediateHomeSnapshotLookupProvider = { positiveNonAuthoritativeHome },
            )
        taskDataSource.cachePayloadForTest(contentPayloadForRoute(taskRoute, "durable task body"))
        val taskStore = SharedThreadStore(dataSource = taskDataSource, scope = scope)
        taskStore.dispatch(SharedThreadIntent.Start(taskRoute))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ThreadAccessResolution.Resolved, taskStore.state.value.accessResolution)
        assertTrue(taskStore.state.value.writeActionsEnabled)
        assertTrue(taskStore.state.value.payload.composer.enabled)
        assertFalse(taskStore.state.value.canonicalAccessResolved)

        scope.cancel()
    }

    @Test
    fun positiveParentDirectoryHintDuringLoadingUnlocksReplyThreadImmediatelyAndRespectsCanonicalNoPermission() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload()
        val lookupDeadline = CompletableDeferred<Unit>()
        val neverCompleteChannels = CompletableDeferred<DataResult<ChannelSnapshot>>()
        val releaseCanonicalReply = CompletableDeferred<Unit>()
        val diagnostics = mutableListOf<String>()
        val conversationRepository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.NoPermission(PermissionBlocker(code = "thread_forbidden")),
                releaseReply = releaseCanonicalReply,
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = ReplyThreadAccessNetworkApi,
                sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = conversationRepository,
                channelRepository = FakeChannelRepository(channelsResult = { neverCompleteChannels.await() }),
            )
        dataSource.cachePayloadForTest(
            contentPayloadForRoute(route, "cached reply", channelId = "thread-m1").copy(
                joined = false,
                canSend = false,
                composer = route.composer.copy(enabled = false),
            )
        )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = { diagnostics.add(it) },
                initialAccessLookupDeadline = { lookupDeadline.await() },
            )

        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(50)
        runCurrent()
        assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        assertFalse(store.state.value.writeActionsEnabled)

        // Dispatch a positive parent directory hint while still Loading (canonical & initial lookup
        // both unfinished). Must apply immediately instead of deferring!
        store.dispatch(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                present = true,
                joined = true,
                canSend = true,
                authoritative = false,
            )
        )
        runCurrent()

        val unlocked = store.state.value
        assertEquals(ThreadAccessResolution.Resolved, unlocked.accessResolution, "state=$unlocked")
        assertEquals(ReplyParentAuthorityStatus.Writable, unlocked.replyParentAuthority, "state=$unlocked")
        assertTrue(unlocked.writeActionsEnabled, "state=$unlocked")
        assertTrue(unlocked.payload.joined, "state=$unlocked")
        assertTrue(unlocked.payload.canSend, "state=$unlocked")
        assertTrue(unlocked.payload.composer.enabled, "state=$unlocked")
        assertFalse(unlocked.canonicalAccessResolved, "canonical refresh has not landed yet; state=$unlocked")
        assertFalse(
            diagnostics.any { it.startsWith("thread_parent_authority_hint_deferred") },
            "positive hint must not be deferred; diagnostics=$diagnostics",
        )

        // Even if the slow initial home lookup later times out (null), it must NOT clobber Writable.
        lookupDeadline.complete(Unit)
        runCurrent()
        assertEquals(ReplyParentAuthorityStatus.Writable, store.state.value.replyParentAuthority)
        assertTrue(store.state.value.writeActionsEnabled)

        // A later canonical NoPermission must still fail closed and revoke write access.
        releaseCanonicalReply.complete(Unit)
        advanceTimeBy(50)
        runCurrent()
        val revoked = store.state.value
        assertEquals(ThreadAccessResolution.NoPermission, revoked.accessResolution, "state=$revoked")
        assertFalse(revoked.writeActionsEnabled, "state=$revoked")
        assertFalse(revoked.payload.joined, "state=$revoked")
        assertFalse(revoked.payload.canSend, "state=$revoked")
        assertFalse(revoked.payload.composer.enabled, "state=$revoked")
        scope.cancel()
    }

    @Test
    fun threadShowsOptimisticComposerGateMatrixAndOptimisticChannelId() {
        // Positive cases: Channel, DM, TaskConversation, and ReplyThread while Loading.
        assertTrue(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
            )
        )
        assertTrue(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "loading",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                joined = true,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
            )
        )
        assertTrue(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                joined = false,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
                replyParentAuthority = ReplyParentAuthorityStatus.Resolving,
            )
        )

        // Blockers: must suppress optimistic composer.
        assertFalse(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Error,
                payloadStatus = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
            )
        )
        assertFalse(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.NoPermission,
                payloadStatus = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
            )
        )
        assertFalse(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "error",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
            )
        )
        assertFalse(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "loading",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = false,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
            ),
            "known unjoined channel must not flash optimistic composer",
        )
        assertFalse(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "content",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                joined = false,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
                replyParentAuthority = ReplyParentAuthorityStatus.Denied,
            ),
            "reply thread with Denied parent must not show optimistic composer",
        )
        assertFalse(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                showComposer = true,
                selectedTab = "tasks",
                composerLocked = false,
            )
        )
        assertFalse(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = true,
            )
        )
        assertFalse(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
                channelArchived = true,
            )
        )
        assertFalse(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
                externalFrozen = true,
            )
        )
        assertFalse(
            threadShowsOptimisticComposer(
                accessResolution = ThreadAccessResolution.Loading,
                payloadStatus = "content",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                joined = true,
                showComposer = true,
                selectedTab = "chat",
                composerLocked = false,
                loungeFooterActive = true,
            )
        )

        // Optimistic channel id resolution across cold surfaces.
        val coldChannel = RaftThreadScreenPayload(status = "loading", sourceThreadId = "c1", streamChannelId = "c1")
        assertEquals("c1", coldChannel.optimisticComposerChannelId())
        val coldReplyWithStream =
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "c1",
                parentMessageId = "m1",
            )
        assertEquals("thread-m1", coldReplyWithStream.optimisticComposerChannelId())
        val coldBrandNewReply =
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "c1",
                parentMessageId = "m1",
            )
        assertEquals("c1", coldBrandNewReply.optimisticComposerChannelId())
        assertNull(RaftThreadScreenPayload(status = "loading").optimisticComposerChannelId())
    }

    @Test
    fun ensureThreadComposerDraftStartedSeedsSynchronouslyAndPreservesTypedDraftOnRecomposition() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val persistSlice = SharedThreadComposerDraftPersistSlice(persistence)
        val viewModel = SharedThreadComposerDraftViewModel(persistSlices = listOf(persistSlice))
        val composer = RaftThreadComposerPayload(instanceId = "composer-c1")
        val draftKey = ThreadComposerSurface(channelId = "c1").threadComposerDraftKey()

        assertFalse(
            threadComposerEditorReadyToMount(
                composerInstanceId = composer.instanceId,
                expectedDraftKey = draftKey,
                draftState = viewModel.state.value,
            )
        )

        ensureThreadComposerDraftStarted(viewModel, composer, draftKey)
        assertTrue(
            threadComposerEditorReadyToMount(
                composerInstanceId = composer.instanceId,
                expectedDraftKey = draftKey,
                draftState = viewModel.state.value,
            ),
            "synchronous seed must make the editor ready on Frame 1",
        )

        // Typing updates the draft; repeated ensureThreadComposerDraftStarted for the same session
        // must be a no-op and never wipe typed text.
        viewModel.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "in progress"))
        ensureThreadComposerDraftStarted(viewModel, composer, draftKey)
        assertEquals("in progress", viewModel.state.value.seedText)
    }
}
