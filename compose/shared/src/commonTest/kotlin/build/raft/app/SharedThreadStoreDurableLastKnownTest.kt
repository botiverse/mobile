package build.raft.app

import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.PresenceNoopMemberRepository
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.model.ServerId
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageNodeV2
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownFrame
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #1123: the thread store's offline first frame from the durable V2 partition. The reader is
 * faked here (the real one is exercised on SQLite in `CanonicalMessageV2DurableLastKnownReaderTest`);
 * these tests pin what the store does around it: which partition it asks for, when it asks at all,
 * and how the frame yields to the network answer.
 */
class SharedThreadStoreDurableLastKnownTest {
    @Test
    fun startRendersDurableFrameOnMemoryMissAndNetworkContentReplacesIt() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val reader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body")))
        val loadingStateApplied = CompletableDeferred<KuiklyThreadScreenPayload>()
        val releaseLoadingState = CompletableDeferred<Unit>()
        val dataSource = dataSource(reader = reader, identity = { ownerA })
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                afterRefreshLoadingStateApplied = { payload ->
                    loadingStateApplied.complete(payload)
                    releaseLoadingState.await()
                },
            )

        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            val firstFrame = loadingStateApplied.await()

            assertEquals(listOf(partition(ownerA) to "channel-1"), reader.reads)
            assertEquals("content", firstFrame.status)
            assertTrue(firstFrame.durableLastKnown)
            assertTrue(firstFrame.backgroundRefreshing)
            assertEquals(listOf("durable body"), firstFrame.messages.map { it.body })
            assertEquals("v2-1", firstFrame.messages.single().id)
            assertTrue(firstFrame.messages.single().isOwn)
            assertFalse(firstFrame.canLoadMoreBefore)
            assertFalse(firstFrame.canLoadMoreAfter)
            assertNull(firstFrame.errorCode)
            assertEquals("content", store.state.value.timelineSlice.status)

            releaseLoadingState.complete(Unit)
            val settled = withTimeout(5_000L) {
                store.state.first { !it.payload.backgroundRefreshing }.payload
            }
            assertEquals("content", settled.status)
            assertFalse(settled.durableLastKnown)
            assertFalse(settled.backgroundRefreshing)
            assertEquals(listOf("network body"), settled.messages.map { it.body })
        } finally {
            releaseLoadingState.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun durableFrameSurvivesTransientFetchFailureButYieldsToCanonicalEmpty() = runBlocking {
        val failingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failingApi = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val failingStore =
            SharedThreadStore(
                dataSource = dataSource(
                    api = failingApi,
                    reader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body"))),
                    identity = { ownerA },
                ),
                scope = failingScope,
            )
        failingStore.accept(SharedThreadIntent.Start(basePayload()))
        val afterFailure = failingStore.state.value.payload
        assertEquals(1, failingApi.channelMessageCalls)
        assertEquals("content", afterFailure.status)
        assertTrue(afterFailure.durableLastKnown)
        assertFalse(afterFailure.backgroundRefreshing)
        assertEquals(listOf("durable body"), afterFailure.messages.map { it.body })
        failingScope.cancel()

        val emptyScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val emptyStore =
            SharedThreadStore(
                dataSource = dataSource(
                    api = SharedThreadStoreTest.FakeThreadApi(bodyText = """{"historyLimited": false, "messages": []}"""),
                    reader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body"))),
                    identity = { ownerA },
                ),
                scope = emptyScope,
            )
        emptyStore.accept(SharedThreadIntent.Start(basePayload()))
        val afterEmpty = emptyStore.state.value.payload
        assertEquals("empty", afterEmpty.status)
        assertFalse(afterEmpty.durableLastKnown)
        assertTrue(afterEmpty.messages.isEmpty())
        emptyScope.cancel()
    }

    @Test
    fun accountSwitchAsksForTheCurrentOwnersPartitionAndNeverRendersThePreviousOne() = runBlocking {
        // Positive control: owner A's frame renders for owner A.
        val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controlReader = FakeReader(frames = mapOf(partition(ownerA) to frame("owner-a durable body")))
        val controlStore =
            SharedThreadStore(
                dataSource = dataSource(
                    api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                    reader = controlReader,
                    identity = { ownerA },
                ),
                scope = controlScope,
            )
        controlStore.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(listOf("owner-a durable body"), controlStore.state.value.payload.messages.map { it.body })
        controlScope.cancel()

        // Same server, same device, owner B is now current: only B's partition is consulted, and
        // since B has no baseline the ordinary loading shell is shown — never A's frame.
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val reader = FakeReader(frames = mapOf(partition(ownerA) to frame("owner-a durable body")))
        val loadingStateApplied = CompletableDeferred<KuiklyThreadScreenPayload>()
        val releaseLoadingState = CompletableDeferred<Unit>()
        val store =
            SharedThreadStore(
                dataSource = dataSource(reader = reader, identity = { ownerB }),
                scope = scope,
                afterRefreshLoadingStateApplied = { payload ->
                    loadingStateApplied.complete(payload)
                    releaseLoadingState.await()
                },
            )
        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            val loadingPayload = loadingStateApplied.await()
            assertEquals(listOf(partition(ownerB) to "channel-1"), reader.reads)
            assertEquals("loading", loadingPayload.status)
            assertFalse(loadingPayload.durableLastKnown)
            assertTrue(loadingPayload.messages.isEmpty())
            assertEquals(ThreadAccessResolution.Loading, store.state.value.accessResolution)
        } finally {
            releaseLoadingState.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun noOwnerMemoryCacheAndReplyRoutesNeverConsultTheDurableReader() = runBlocking {
        // Auth generation 0 is the registry's "no session" sentinel: no owner ⇒ no partition ⇒ no read.
        val noOwnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val noOwnerReader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body")))
        val noOwnerStore =
            SharedThreadStore(
                dataSource = dataSource(reader = noOwnerReader, identity = { ownerA.copy(authGeneration = 0L) }),
                scope = noOwnerScope,
            )
        noOwnerStore.accept(SharedThreadIntent.Start(basePayload()))
        assertTrue(noOwnerReader.reads.isEmpty(), noOwnerReader.reads.toString())
        assertFalse(noOwnerStore.state.value.payload.durableLastKnown)
        noOwnerScope.cancel()

        // A memory-cached payload for the same owner wins; disk is never asked.
        val cachedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val cachedReader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body")))
        val cachedDataSource = dataSource(
            api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
            reader = cachedReader,
            identity = { ownerA },
        )
        assertNotNull(cachedDataSource.readSession())
        assertTrue(cachedDataSource.cachePayload(contentPayload("memory cached body"), serverId = "server-1"))
        val cachedStore = SharedThreadStore(dataSource = cachedDataSource, scope = cachedScope)
        cachedStore.accept(SharedThreadIntent.Start(basePayload()))
        assertTrue(cachedReader.reads.isEmpty(), cachedReader.reads.toString())
        assertEquals(listOf("memory cached body"), cachedStore.state.value.payload.messages.map { it.body })
        assertFalse(cachedStore.state.value.payload.durableLastKnown)
        cachedScope.cancel()

        // Reply threads have no V2 writer yet; the reader is not their surface.
        val replyScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val replyReader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body")))
        val replyStore =
            SharedThreadStore(
                dataSource = dataSource(reader = replyReader, identity = { ownerA }),
                scope = replyScope,
            )
        replyStore.accept(SharedThreadIntent.Start(replyRoute()))
        assertTrue(replyReader.reads.isEmpty(), replyReader.reads.toString())
        replyScope.cancel()
    }

    @Test
    fun identitySwitchWhileTheDiskReadIsSuspendedNeverRendersThePreviousOwnersFrame() = runBlocking {
        // Positive control with the same parked reader: no switch while suspended ⇒ A's frame renders
        // AND its author is seeded into the shared member-profile cache (the side effect that runs
        // before the commit gate, see below).
        val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controlReader = FakeReader(frames = mapOf(partition(ownerA) to frame("owner-a durable body")))
        controlReader.parkNextRead()
        val controlMembers = SharedServerMembersStore(PresenceNoopMemberRepository)
        val controlStore =
            SharedThreadStore(
                dataSource = dataSource(
                    api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                    reader = controlReader,
                    identity = { ownerA },
                    serverMembersStore = controlMembers,
                ),
                scope = controlScope,
            )
        controlStore.dispatch(SharedThreadIntent.Start(basePayload()))
        controlReader.readStarted.await()
        assertTrue(controlStore.state.value.payload.messages.isEmpty())
        assertNull(controlMembers.state.value.memberProfile(ServerId("server-1"), "u-a"))
        controlReader.releaseRead.complete(Unit)
        assertEquals(listOf("owner-a durable body"), controlStore.state.value.payload.messages.map { it.body })
        assertTrue(controlStore.state.value.payload.durableLastKnown)
        assertEquals("Owner A", controlMembers.state.value.memberProfile(ServerId("server-1"), "u-a")?.displayName)
        controlScope.cancel()

        // The race the post-read check guards: A is current when the read is admitted, the disk read
        // suspends, B signs in meanwhile (the authority re-captures like a real account switch), then
        // the read completes with A's rows. They must not render under the route B now owns — and,
        // separately, A's authors must not be written into the process-wide member-profile cache.
        // The render half is also refused by the commit gate in start(); the seeding half is refused
        // ONLY by the post-read provenance check, because seedThreadAuthorProfiles runs upstream of
        // that gate. This assertion is what bites if that check is removed.
        var identity = ownerA
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val reader = FakeReader(frames = mapOf(partition(ownerA) to frame("owner-a durable body")))
        reader.parkNextRead()
        val members = SharedServerMembersStore(PresenceNoopMemberRepository)
        val dataSource = dataSource(
            api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
            reader = reader,
            identity = { identity },
            serverMembersStore = members,
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        try {
            store.dispatch(SharedThreadIntent.Start(basePayload()))
            reader.readStarted.await()
            assertEquals(listOf(partition(ownerA) to "channel-1"), reader.reads)

            identity = ownerB
            assertNotNull(dataSource.readSession())
            assertEquals("u-b", dataSource.currentReplyCacheProvenance()?.principalId)

            reader.releaseRead.complete(Unit)
            val payload = store.state.value.payload
            assertTrue(payload.messages.isEmpty(), payload.messages.map { it.body }.toString())
            assertFalse(payload.durableLastKnown)
            assertEquals(listOf(partition(ownerA) to "channel-1"), reader.reads)
            assertNull(
                members.state.value.memberProfile(ServerId("server-1"), "u-a"),
                "owner A's author profile must not be seeded after the identity moved to B",
            )
        } finally {
            reader.releaseRead.complete(Unit)
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun emptyFrameStandsInAsAnEmptyChannelThroughAFailureAndYieldsToContent() = runBlocking {
        // Task #1137. Negative control: with no durable frame, an offline start ends in the error state.
        val noFrameScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val noFrameStore =
            SharedThreadStore(
                dataSource = dataSource(
                    api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                    reader = FakeReader(frames = emptyMap()),
                    identity = { ownerA },
                ),
                scope = noFrameScope,
            )
        noFrameStore.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals("error", noFrameStore.state.value.payload.status)
        noFrameScope.cancel()

        // An empty baseline: offline, the channel shows as empty (flagged last-known), never as an error.
        val logs = mutableListOf<String>()
        val offlineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val offlineStore =
            SharedThreadStore(
                dataSource = dataSource(
                    api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                    reader = FakeReader(frames = mapOf(partition(ownerA) to emptyFrame())),
                    identity = { ownerA },
                    diagnosticsLogger = logs::add,
                ),
                scope = offlineScope,
            )
        offlineStore.accept(SharedThreadIntent.Start(basePayload()))
        val offline = offlineStore.state.value.payload
        assertEquals("empty", offline.status)
        assertTrue(offline.durableLastKnown)
        assertFalse(offline.backgroundRefreshing)
        assertTrue(offline.messages.isEmpty())
        assertNotNull(offline.errorCode, "the failure is recorded, the empty stand-in is kept")
        assertTrue(threadOfflineLastKnownChrome(offline).tipVisible, "the offline tip shows on the empty channel too")
        assertTrue(logs.any { "thread_durable_last_known" in it && "outcome=empty_frame" in it }, logs.toString())
        offlineScope.cancel()

        // Online, the network answer replaces the stand-in and clears the flag.
        val onlineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val onlineStore =
            SharedThreadStore(
                dataSource = dataSource(
                    reader = FakeReader(frames = mapOf(partition(ownerA) to emptyFrame())),
                    identity = { ownerA },
                ),
                scope = onlineScope,
            )
        onlineStore.accept(SharedThreadIntent.Start(basePayload()))
        val online = onlineStore.state.value.payload
        assertEquals("content", online.status)
        assertFalse(online.durableLastKnown)
        assertEquals(listOf("network body"), online.messages.map { it.body })
        onlineScope.cancel()
    }

    @Test
    fun frameOfAnotherSurfaceKindIsRefusedAndLogged() = runBlocking {
        // Positive control: a "channel" frame renders on the channel route (same logger wiring).
        val controlLogs = mutableListOf<String>()
        val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controlStore =
            SharedThreadStore(
                dataSource = dataSource(
                    api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                    reader = FakeReader(frames = mapOf(partition(ownerA) to frame("durable body"))),
                    identity = { ownerA },
                    diagnosticsLogger = controlLogs::add,
                ),
                scope = controlScope,
            )
        controlStore.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(listOf("durable body"), controlStore.state.value.payload.messages.map { it.body })
        assertTrue(controlLogs.any { "thread_durable_last_known" in it && "outcome=frame" in it }, controlLogs.toString())
        controlScope.cancel()

        // A committed "dm" baseline under the same id must not render on a Channel route.
        val logs = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reader = FakeReader(frames = mapOf(partition(ownerA) to frame("dm body", scopeKind = "dm")))
        val store =
            SharedThreadStore(
                dataSource = dataSource(
                    api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                    reader = reader,
                    identity = { ownerA },
                    diagnosticsLogger = logs::add,
                ),
                scope = scope,
            )
        store.accept(SharedThreadIntent.Start(basePayload()))
        val payload = store.state.value.payload
        assertEquals(listOf(partition(ownerA) to "channel-1"), reader.reads)
        assertTrue(payload.messages.isEmpty(), payload.messages.map { it.body }.toString())
        assertFalse(payload.durableLastKnown)
        assertTrue(payload.status != "content", payload.status)
        val mismatch = assertNotNull(logs.singleOrNull { "outcome=surface_mismatch" in it }, logs.toString())
        assertTrue("scopeKind=dm" in mismatch, mismatch)
        assertTrue(logs.none { "outcome=frame" in it }, logs.toString())
        scope.cancel()
    }

    private class FakeReader(
        private val frames: Map<DurablePartitionKey, ThreadDurableLastKnownFrame>,
    ) : ThreadDurableLastKnownReader {
        val reads = mutableListOf<Pair<DurablePartitionKey, SyncScopeId>>()
        private var parkRead = false
        var readStarted = CompletableDeferred<Unit>()
            private set
        var releaseRead = CompletableDeferred<Unit>()
            private set

        /** The next [read] suspends until [releaseRead] completes, so a test can act mid-read. */
        fun parkNextRead() {
            parkRead = true
            readStarted = CompletableDeferred()
            releaseRead = CompletableDeferred()
        }

        override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? {
            reads += partition to channelId
            if (parkRead) {
                parkRead = false
                readStarted.complete(Unit)
                releaseRead.await()
            }
            val frame = frames[partition] ?: return null
            return frame.takeIf { it.scope.scopeId == channelId }
        }
    }

    private fun dataSource(
        api: SharedThreadStoreTest.FakeThreadApi = SharedThreadStoreTest.FakeThreadApi(),
        reader: ThreadDurableLastKnownReader,
        identity: () -> ThreadReplyCacheIdentitySnapshot,
        diagnosticsLogger: (String) -> Unit = {},
        serverMembersStore: SharedServerMembersStore? = null,
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            networkApi = api,
            sessionStore = InMemorySlockAuthSessionStore(identity().session),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = { identity() },
            durableLastKnownReader = reader,
            diagnosticsLogger = diagnosticsLogger,
            serverMembersStore = serverMembersStore,
        )

    private fun partition(identity: ThreadReplyCacheIdentitySnapshot): DurablePartitionKey =
        DurablePartitionKey(
            serverId = identity.serverId,
            principalId = identity.principalId,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = identity.environmentId,
        )

    private fun emptyFrame(): ThreadDurableLastKnownFrame =
        ThreadDurableLastKnownFrame(
            scope = SyncScopeKey(partition(ownerA), "channel-1", "channel"),
            envelopes = emptyList(),
            cursor = PersistedCursor(null, null, null, hasLoadedOldest = true),
        )

    private fun frame(body: String, scopeKind: String = "channel"): ThreadDurableLastKnownFrame {
        val scope = SyncScopeKey(partition(ownerA), "channel-1", scopeKind)
        return ThreadDurableLastKnownFrame(
            scope = scope,
            envelopes = listOf(
                CanonicalMessageEnvelopeV2(
                    fact = CanonicalMessageNodeV2(
                        id = "v2-1",
                        seq = 5,
                        senderType = "user",
                        senderId = "u-a",
                        content = body,
                        messageType = "chat",
                        createdAt = "2026-05-19T16:00:00Z",
                        senderName = "Owner A",
                    ),
                    context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-1", "channel-1")),
                ),
            ),
            cursor = PersistedCursor(5, 5, 5, hasLoadedOldest = true, epoch = "epoch-v2"),
        )
    }

    private val ownerA =
        ThreadReplyCacheIdentitySnapshot(
            session = session("u-a", "Owner A"),
            environmentId = TestThreadEnvironmentId,
            principalId = "u-a",
            serverId = "server-1",
            authGeneration = 1L,
            selectionGeneration = 1L,
        )

    private val ownerB =
        ThreadReplyCacheIdentitySnapshot(
            session = session("u-b", "Owner B"),
            environmentId = TestThreadEnvironmentId,
            principalId = "u-b",
            serverId = "server-1",
            authGeneration = 2L,
            selectionGeneration = 1L,
        )

    private fun session(userId: String, name: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@example.test", displayName = name),
            accessToken = "access-$userId",
            refreshToken = "refresh-$userId",
            selectedServerId = "server-1",
        )

    private fun basePayload(): KuiklyThreadScreenPayload =
        KuiklyThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
        )

    private fun replyRoute(): KuiklyThreadScreenPayload =
        basePayload().copy(
            surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
            sourceThreadId = "thread-parent-1",
            streamChannelId = "thread-parent-1",
            parentChannelId = "channel-1",
            parentMessageId = "parent-1",
            originParentMessageId = "parent-1",
            originStatus = "loading",
        )

    private fun contentPayload(body: String): KuiklyThreadScreenPayload =
        buildSharedThreadPayloadFromMessagesPage(
            basePayload = basePayload(),
            page = ThreadMessagesPage(
                messages = listOf(
                    WireMessage(
                        id = "m1",
                        channelId = "channel-1",
                        senderType = "user",
                        senderId = "u-a",
                        senderName = "Owner A",
                        messageType = "chat",
                        content = body,
                    ),
                ),
                canLoadMoreBefore = false,
                canLoadMoreAfter = null,
            ),
            session = ownerA.session,
        )
}
