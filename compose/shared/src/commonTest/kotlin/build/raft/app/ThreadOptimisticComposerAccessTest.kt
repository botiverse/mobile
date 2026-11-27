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
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadPendingSendContext
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.ReplyParentAuthorityStatus
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadState
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadAccessResolution
import build.raft.app.thread.ThreadMessageDeliveryFailed
import build.raft.app.thread.ThreadMessageDeliverySending
import build.raft.app.thread.ThreadTransientLocalSendIdPrefix
import build.raft.app.thread.allowsOptimisticSendWhileAccessLoading
import build.raft.app.thread.canAdmitLocalSend
import build.raft.app.thread.canDeferOptimisticSendWhileAccessLoading
import build.raft.app.thread.captureThreadLocalSendAuthority
import build.raft.app.thread.dispatch
import build.raft.app.thread.ensureThreadComposerDraftStarted
import build.raft.app.thread.optimisticComposerChannelId
import build.raft.app.thread.threadComposerDraftKey
import build.raft.app.thread.threadComposerEditorReadyToMount
import build.raft.app.thread.threadShowsOptimisticComposer
import build.raft.app.threadComposerDraftKey
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

    @Test
    fun optimisticSendWhileLoadingAdmitsKnownSendTargetAndDefersOnlyWhenStreamIdMissing() {
        val knownReplyPayload =
            replyThreadInitialAccessPayload().copy(
                status = "content",
                joined = false,
                canSend = false,
            )
        val loadingKnownReply =
            SharedThreadState(
                payload = knownReplyPayload,
                accessResolution = ThreadAccessResolution.Loading,
                replyParentAuthority = ReplyParentAuthorityStatus.Resolving,
                writeActionsEnabled = false,
            )
        assertTrue(loadingKnownReply.allowsOptimisticSendWhileAccessLoading(sendChannelId = "thread-m1"))
        assertTrue(loadingKnownReply.canAdmitLocalSend())
        assertFalse(
            loadingKnownReply.allowsOptimisticSendWhileAccessLoading(sendChannelId = "channel-1"),
            "must refuse mismatched parent channel id for reply thread",
        )
        assertFalse(loadingKnownReply.allowsOptimisticSendWhileAccessLoading(sendChannelId = "thread-m1", limitLocked = true))
        assertFalse(loadingKnownReply.canAdmitLocalSend(limitLocked = true))

        // Early positive parent hint while still Loading remains directly sendable.
        val writableHintReply = loadingKnownReply.copy(replyParentAuthority = ReplyParentAuthorityStatus.Writable)
        assertTrue(writableHintReply.allowsOptimisticSendWhileAccessLoading(sendChannelId = "thread-m1"))
        assertTrue(writableHintReply.canAdmitLocalSend())

        // Brand-new reply thread with no server streamChannelId yet: cannot send directly (must not
        // post to parentChannelId), but CAN defer until the stream channel id resolves.
        val brandNewReply =
            SharedThreadState(
                payload = unresolvedReplyThreadAccessPayload().copy(status = "empty", joined = false, canSend = false),
                accessResolution = ThreadAccessResolution.Loading,
                replyParentAuthority = ReplyParentAuthorityStatus.Resolving,
                writeActionsEnabled = false,
            )
        assertFalse(brandNewReply.allowsOptimisticSendWhileAccessLoading(sendChannelId = null))
        assertFalse(brandNewReply.canAdmitLocalSend())
        assertTrue(brandNewReply.canDeferOptimisticSendWhileAccessLoading())

        // Known-denied / deleted / undetermined-after-lookup reply parent must neither send nor defer.
        for (blockedAuthority in listOf(
            ReplyParentAuthorityStatus.Denied,
            ReplyParentAuthorityStatus.ParentDeleted,
            ReplyParentAuthorityStatus.Unknown,
        )) {
            val blocked = loadingKnownReply.copy(replyParentAuthority = blockedAuthority)
            assertFalse(blocked.allowsOptimisticSendWhileAccessLoading(sendChannelId = "thread-m1"), "authority=$blockedAuthority")
            assertFalse(blocked.canDeferOptimisticSendWhileAccessLoading(), "authority=$blockedAuthority")
            assertFalse(blocked.canAdmitLocalSend(), "authority=$blockedAuthority")
        }

        // Joined Channel / DM during Loading sends directly; known-unjoined Channel does not.
        val joinedChannelLoading =
            SharedThreadState(
                payload = basePayload().copy(status = "content", joined = true, canSend = true),
                accessResolution = ThreadAccessResolution.Loading,
                writeActionsEnabled = false,
            )
        assertTrue(joinedChannelLoading.allowsOptimisticSendWhileAccessLoading(sendChannelId = "channel-1"))
        assertTrue(joinedChannelLoading.canAdmitLocalSend())

        val unjoinedChannelLoading =
            joinedChannelLoading.copy(payload = joinedChannelLoading.payload.copy(joined = false, canSend = false))
        assertFalse(unjoinedChannelLoading.allowsOptimisticSendWhileAccessLoading(sendChannelId = "channel-1"))
        assertFalse(unjoinedChannelLoading.canDeferOptimisticSendWhileAccessLoading())
        assertFalse(unjoinedChannelLoading.canAdmitLocalSend())

        // Archived, error, NoPermission, Error, and Resolved read-only states remain fail-closed.
        val archivedLoading = joinedChannelLoading.copy(payload = joinedChannelLoading.payload.copy(channelArchived = true))
        assertFalse(archivedLoading.allowsOptimisticSendWhileAccessLoading(sendChannelId = "channel-1"))
        assertFalse(archivedLoading.canDeferOptimisticSendWhileAccessLoading())
        assertFalse(archivedLoading.canAdmitLocalSend())

        val noPermission = loadingKnownReply.copy(accessResolution = ThreadAccessResolution.NoPermission)
        assertFalse(noPermission.allowsOptimisticSendWhileAccessLoading(sendChannelId = "thread-m1"))
        assertFalse(noPermission.canAdmitLocalSend())

        val errorAccess = loadingKnownReply.copy(accessResolution = ThreadAccessResolution.Error)
        assertFalse(errorAccess.allowsOptimisticSendWhileAccessLoading(sendChannelId = "thread-m1"))
        assertFalse(errorAccess.canAdmitLocalSend())

        val resolvedReadOnly =
            loadingKnownReply.copy(
                accessResolution = ThreadAccessResolution.Resolved,
                replyParentAuthority = ReplyParentAuthorityStatus.Unknown,
                writeActionsEnabled = false,
            )
        assertFalse(resolvedReadOnly.allowsOptimisticSendWhileAccessLoading(sendChannelId = "thread-m1"))
        assertFalse(resolvedReadOnly.canAdmitLocalSend())
    }

    @Test
    fun replyThreadAdmitsLocalSendDuringLoadingWhileHomeLookupIsSuspendedAndSurfacesServerFailure() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val route = replyThreadInitialAccessPayload().copy(joined = false, canSend = false)
        val homeStarted = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val lookupDeadline = CompletableDeferred<Unit>()
        val joinedHome = replyThreadAccessHomeSnapshot(joined = true, canSend = true)
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot(body = "existing reply")),
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
                initialAccessLookupDeadline = { lookupDeadline.await() },
            )
        store.dispatch(SharedThreadIntent.Start(route))
        advanceTimeBy(50)
        runCurrent()

        val loadingState = store.state.value
        assertTrue(homeStarted.isCompleted, "Home lookup should still be suspended")
        assertEquals("content", loadingState.payload.status, "state=$loadingState")
        assertEquals(ThreadAccessResolution.Loading, loadingState.accessResolution, "state=$loadingState")
        assertFalse(loadingState.writeActionsEnabled, "state=$loadingState")
        assertFalse(loadingState.payload.canSend, "state=$loadingState")
        assertTrue(
            loadingState.allowsOptimisticSendWhileAccessLoading(sendChannelId = "thread-m1"),
            "state=$loadingState",
        )

        // Sending while Home lookup is still suspended must capture send authority and admit the
        // optimistic local message immediately instead of stalling in a client-side timeout.
        val mountedProvenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        val authority =
            dataSource.captureThreadLocalSendAuthority(
                state = loadingState,
                mountedOwnerProvenance = mountedProvenance,
            )
        assertTrue(authority != null, "expected send authority while Loading with known streamChannelId; state=$loadingState")
        val localId = "${ThreadTransientLocalSendIdPrefix}loading-reply-1"
        val pendingMessage =
            RaftThreadMessageItem(
                id = localId,
                authorId = "u1",
                authorName = "Artin",
                isOwn = true,
                body = "sent while loading",
                threadChannelId = "thread-m1",
                deliveryState = ThreadMessageDeliverySending,
                pendingSendContext =
                    RaftThreadPendingSendContext(
                        channelId = "thread-m1",
                        body = "sent while loading",
                        asTask = false,
                        attachmentIds = emptyList(),
                        attachments = emptyList(),
                        mentionChannelId = "channel-1",
                        parentMessageId = "m1",
                        streamChannelId = "thread-m1",
                        memberScopedMentions = false,
                        randomId = "random-loading-reply-1",
                    ),
            )
        assertTrue(
            store.admitLocalSend(
                message = pendingMessage,
                expectedProvenance = authority.provenance,
                expectedRoute = authority.route,
            ),
            "admitLocalSend must succeed during Loading when concrete reply stream id is known",
        )
        assertTrue(
            store.state.value.payload.messages.any { it.id == localId && it.deliveryState == ThreadMessageDeliverySending },
            "optimistic local send must appear immediately on timeline; state=${store.state.value}",
        )

        // If the server responds 403 (user not joined / forbidden), failure surfaces cleanly on the
        // local row without losing text.
        store.accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = localId,
                errorCode = "http_403",
                errorMessage = "Join this channel to send messages",
                expectedProvenance = authority.provenance,
                expectedRoute = authority.route,
                randomId = "random-loading-reply-1",
            )
        )
        val failedRow = store.state.value.payload.messages.first { it.id == localId }
        assertEquals(ThreadMessageDeliveryFailed, failedRow.deliveryState)
        assertEquals("http_403", failedRow.pendingSendErrorCode)
        assertEquals("Join this channel to send messages", failedRow.pendingSendErrorMessage)
        scope.cancel()
    }
}
