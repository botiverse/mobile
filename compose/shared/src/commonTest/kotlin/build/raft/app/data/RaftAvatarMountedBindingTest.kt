package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.network.SharedBinaryConditionalDownloadResult
import build.raft.app.network.SharedBinaryConditionalHttpException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftAvatarMountedBindingTest {
    @Test
    fun coldStartPrewarmLetsTheFirstFramePeekADurableAvatar() = runBlocking {
        // task #343: a fresh process has an empty mounted session, so the first frame's peek
        // missed and painted the default icon until the per-row mount finished.
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 500L) }
        val binding = binding(store, FakeNativeProvider(), RecordingDownloader())
        assertNull(binding.peekInitialManagedModel(subject(), RemoteUrl))

        assertEquals(1, binding.prewarmOwner(serverId = null))

        val peeked = assertNotNull(binding.peekInitialManagedModel(subject(), RemoteUrl))
        assertNotNull(parseRaftAvatarMountedRenderModel(peeked))
        Unit
    }

    @Test
    fun prewarmSkipsRowsWhoseNativeBytesAreGone() = runBlocking {
        // Same rule as task #289: metadata that outlived its bytes must not be rendered or revalidated.
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 500L) }
        val binding = binding(store, FakeNativeProvider(bytesAvailable = false), RecordingDownloader())

        assertEquals(0, binding.prewarmOwner(serverId = null))
        assertNull(binding.peekInitialManagedModel(subject(), RemoteUrl))
    }

    @Test
    fun prewarmRunsOncePerOwnerUntilTheOwnerIsRetired() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 500L) }
        val binding = binding(store, FakeNativeProvider(), RecordingDownloader())

        assertEquals(1, binding.prewarmOwner(serverId = null))
        assertEquals(0, binding.prewarmOwner(serverId = null))
        assertEquals(1, store.committedForOwnerCalls)

        binding.retireOwner(owner())
        assertNull(binding.peekInitialManagedModel(subject(), RemoteUrl))
        assertEquals(1, binding.prewarmOwner(serverId = null))
        assertEquals(2, store.committedForOwnerCalls)
    }

    @Test
    fun prewarmNeverDisplacesALiveMount() = runBlocking {
        val store = FakeMetadataStore()
        val binding = binding(store, FakeNativeProvider(), RecordingDownloader())
        val handle = binding.mount(subject(), RemoteUrl)
        store.metadata = metadata(freshUntilMs = 500L)

        assertEquals(0, binding.prewarmOwner(serverId = null))
        assertTrue(binding.dispose(handle), "prewarm must leave the live mount current")
    }

    @Test
    fun unsupportedProviderUsesLegacyUrlBeforeTransport() = runBlocking {
        val downloader = RecordingDownloader()
        val binding = binding(provider = UnsupportedRaftAvatarMountedNativeProvider, downloader = downloader)

        val handle = binding.mount(subject(), RemoteUrl)
        binding.refresh(handle)

        assertEquals(RemoteUrl, handle.imageModel.value)
        assertEquals(0, downloader.calls)
    }

    @Test
    fun renderFreshUsesDiskModelWithoutNetworkOrReservation() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 500L) }
        val provider = FakeNativeProvider()
        val downloader = RecordingDownloader()
        val binding = binding(store, provider, downloader)

        val handle = binding.mount(subject(), RemoteUrl)
        binding.refresh(handle)

        val descriptor = assertNotNull(parseRaftAvatarMountedRenderModel(handle.imageModel.value.orEmpty()))
        assertEquals(1L, descriptor.commitIdentity.commitGeneration)
        assertEquals(1L, descriptor.modelRevision)
        assertEquals(owner().nativePartitionKey, descriptor.slot.nativePartitionKey)
        assertEquals(0, downloader.calls)
        assertEquals(0, provider.reserveCalls)
    }

    @Test
    fun anotherAccountDurableEntryCannotReachEarlyRenderAdmission() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 500L) }
        val otherAccountOwner = owner(accountId = "account-b")
        val binding =
            binding(store, FakeNativeProvider(), RecordingDownloader()) {
                otherAccountOwner
            }

        val handle = binding.mount(subject(), RemoteUrl, serverId = otherAccountOwner.serverId)

        assertNull(handle.imageModel.value)
    }

    @Test
    fun cancelledRefreshStillCommitsDownloadAndWritesNoFailure() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val provider = FakeNativeProvider()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val downloader = RecordingDownloader.modified(byteArrayOf(7, 8, 9), gate)
        val binding = binding(store, provider, downloader)
        val handle = binding.mount(subject(), RemoteUrl)
        val staleModel = handle.imageModel.value

        val refresh = async { binding.refresh(handle) }
        yield()
        assertEquals(1, downloader.calls)

        // Composable churn cancels the awaiting refresh; the detached transport must still commit.
        // refresh.await() would rethrow JobCancellationException, so only join completion.
        refresh.cancel()
        gate.complete(Unit)
        refresh.join()

        val descriptor = assertNotNull(parseRaftAvatarMountedRenderModel(handle.imageModel.value.orEmpty()))
        assertNotEquals(staleModel, handle.imageModel.value)
        assertEquals(store.metadata?.commitIdentity, descriptor.commitIdentity)
        assertNull(store.failure, "a caller-cancelled refresh must not write a durable failure row")
        Unit
    }

    @Test
    fun cancelledTransportAbandonsFlightWithoutDurableFailure() = runBlocking {
        // A cancelled transport (downloader-level cancellation, not just a cancelled waiter) must
        // retire its generation so the slot is clean: no durable failure row, and no permanent
        // activeFlights entry for a generation nobody will resolve.
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val provider = FakeNativeProvider()
        val cancelledDownloader = RecordingDownloader { throw kotlinx.coroutines.CancellationException("row disposed") }
        val binding = binding(store, provider, cancelledDownloader)
        val handle = binding.mount(subject(), RemoteUrl)

        binding.refresh(handle)

        assertNull(store.failure, "a cancelled download is not transport evidence")
        // A follow-up refresh on a still-stale slot must be able to start a brand-new generation
        // instead of joining the abandoned flight. Drive it with a healthy downloader.
        val recovered = RecordingDownloader.modified(byteArrayOf(3, 4))
        val binding2 = binding(store, provider, recovered)
        val handle2 = binding2.mount(subject(), RemoteUrl)
        binding2.refresh(handle2)
        assertNotNull(handle2.imageModel.value)
        Unit
    }

    @Test
    fun serverSubjectUsesRequestedOwnerAndFreshDurableModelWithoutNetwork() = runBlocking {
        val cacheOwner = owner()
        val cacheSubject = RaftAvatarCacheSubject.Server(cacheOwner.serverId)
        val cacheKey = key(cacheSubject)
        val store =
            FakeMetadataStore().apply {
                metadata = metadata(freshUntilMs = 500L, key = cacheKey)
            }
        val provider = FakeNativeProvider()
        val downloader = RecordingDownloader()
        val requestedServerIds = mutableListOf<String?>()
        val binding =
            binding(store, provider, downloader) { requestedServerId ->
                requestedServerIds += requestedServerId
                cacheOwner
            }

        val handle = binding.mount(cacheSubject, RemoteUrl, serverId = cacheOwner.serverId)
        binding.refresh(handle)

        val descriptor = assertNotNull(parseRaftAvatarMountedRenderModel(handle.imageModel.value.orEmpty()))
        assertEquals(cacheOwner.serverId, assertNotNull(requestedServerIds.single()))
        assertEquals(cacheOwner.nativePartitionKey, descriptor.slot.nativePartitionKey)
        assertEquals(cacheKey.nativeSubjectKey, descriptor.slot.nativeSubjectKey)
        assertEquals(cacheKey.nativeBytesKey, descriptor.slot.nativeBytesKey)
        assertEquals(0, downloader.calls)
        assertEquals(0, provider.reserveCalls)
    }

    @Test
    fun managedRenderParserRejectsOrdinaryAndMalformedModels() {
        assertNull(parseRaftAvatarMountedRenderModel(RemoteUrl))
        assertNull(parseRaftAvatarMountedRenderModel("data:image/png;base64,AAAA"))
        assertNull(parseRaftAvatarMountedRenderModel("slock-avatar-render-v1:not-base64"))
    }

    @Test
    fun stale2xxCommitsNativeReservedIdentityThenReloadsDistinctModel() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val provider = FakeNativeProvider(highWater = 2L)
        val downloader = RecordingDownloader.modified(byteArrayOf(1, 2, 3))
        val binding = binding(store, provider, downloader)
        val handle = binding.mount(subject(), RemoteUrl)
        val staleModel = handle.imageModel.value

        binding.refresh(handle)

        val reloaded = assertNotNull(parseRaftAvatarMountedRenderModel(handle.imageModel.value.orEmpty()))
        assertNotEquals(staleModel, handle.imageModel.value)
        assertEquals(3L, store.metadata?.commitIdentity?.commitGeneration)
        assertEquals(2L, store.metadata?.modelRevision)
        assertEquals(store.metadata?.commitIdentity, reloaded.commitIdentity)
        assertEquals(store.metadata?.modelRevision, reloaded.modelRevision)
        assertEquals(1, provider.reserveCalls)
        assertEquals(listOf("begin", "write", "finish", "reserve", "bind", "finalize"), provider.events)
    }

    @Test
    fun cacheMiss2xxPublishesManagedModelOnlyAfterCommit() = runBlocking {
        val store = FakeMetadataStore()
        val provider = FakeNativeProvider(highWater = 7L)
        val binding = binding(store, provider, RecordingDownloader.modified(byteArrayOf(1, 2)))
        val handle = binding.mount(subject(), RemoteUrl)

        assertNull(handle.imageModel.value)
        binding.refresh(handle)

        val descriptor = assertNotNull(parseRaftAvatarMountedRenderModel(handle.imageModel.value.orEmpty()))
        assertEquals(8L, descriptor.commitIdentity.commitGeneration)
        assertEquals(1L, descriptor.modelRevision)
        assertEquals(descriptor.commitIdentity, store.metadata?.commitIdentity)
    }

    @Test
    fun modelRevisionOverflowAbortsWithoutReservationOrReload() = runBlocking {
        val store =
            FakeMetadataStore().apply {
                metadata = metadata(freshUntilMs = 50L).copy(modelRevision = Long.MAX_VALUE)
            }
        val provider = FakeNativeProvider()
        val binding = binding(store, provider, RecordingDownloader.modified(byteArrayOf(1)))
        val handle = binding.mount(subject(), RemoteUrl)
        val staleModel = handle.imageModel.value

        binding.refresh(handle)

        assertEquals(staleModel, handle.imageModel.value)
        assertEquals(0, provider.reserveCalls)
        assertTrue("abort-stage" in provider.events)
    }

    @Test
    fun reservationMustAdvanceExpectedCommitGeneration() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val provider = FakeNativeProvider(highWater = 0L)
        val binding = binding(store, provider, RecordingDownloader.modified(byteArrayOf(1)))
        val handle = binding.mount(subject(), RemoteUrl)
        val staleModel = handle.imageModel.value

        binding.refresh(handle)

        assertEquals(staleModel, handle.imageModel.value)
        assertEquals(1L, store.metadata?.commitIdentity?.commitGeneration)
        assertTrue("rollback" in provider.events)
        assertTrue("abort-stage" in provider.events)
    }

    @Test
    fun notModifiedKeepsModelAndDoesNotReserve() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val provider = FakeNativeProvider()
        val downloader = RecordingDownloader.notModified()
        val binding = binding(store, provider, downloader)
        val handle = binding.mount(subject(), RemoteUrl)
        val staleModel = handle.imageModel.value

        binding.refresh(handle)

        assertEquals(staleModel, handle.imageModel.value)
        assertEquals(0, provider.reserveCalls)
        assertTrue("abort" in provider.events)
    }

    @Test
    fun missingNativeBytesForcesFullBodyWithoutDurableValidators() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 500L) }
        val provider = FakeNativeProvider(bytesAvailable = false, bytesDecodable = false)
        val downloader = RecordingDownloader.modified(byteArrayOf(1))
        val binding = binding(store, provider, downloader)
        val handle = binding.mount(subject(), RemoteUrl)

        assertNull(handle.imageModel.value)
        binding.refresh(handle)

        assertNull(downloader.lastValidators)
        assertNotNull(parseRaftAvatarMountedRenderModel(handle.imageModel.value.orEmpty()))
        Unit
    }

    @Test
    fun missingNativeBytesDropsCoordinatorValidatorsSoRevalidationCannotEchoStaleDescriptor() = runBlocking {
        // task #289: metadata with an eTag can outlive cacheDir bytes (system clear-cache or
        // owner retirement clearing the file first). prepareRead must not surface validators
        // then, or a 304 Keep would re-publish a descriptor whose bytes are gone forever.
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 500L) }
        val coordinator = RaftAvatarCacheCoordinator(store) { 100L }

        val missingBytes = coordinator.prepareRead(key(), bytesAvailable = false, bytesDecodable = true)
        assertNull(missingBytes.validators)
        assertIs<RaftAvatarCacheReadDecision.Fetch>(missingBytes.decision)

        val undecodable = coordinator.prepareRead(key(), bytesAvailable = true, bytesDecodable = false)
        assertNull(undecodable.validators)

        val usable = coordinator.prepareRead(key(), bytesAvailable = true, bytesDecodable = true)
        assertEquals("\"v1\"", usable.validators?.eTag)
    }

    @Test
    fun only404And410RetireAndClearWhileOtherHttpFailuresKeep() = runBlocking {
        listOf(404, 410).forEach { status ->
            val events = mutableListOf<String>()
            val store = FakeMetadataStore(events).apply { metadata = metadata(freshUntilMs = 50L) }
            val provider = FakeNativeProvider(events = events)
            val binding = binding(store, provider, RecordingDownloader.httpFailure(status))
            val handle = binding.mount(subject(), RemoteUrl)

            binding.refresh(handle)

            assertNull(handle.imageModel.value)
            assertEquals(1, provider.retireExactCalls)
            assertTrue(events.indexOf("retire") < events.indexOf("metadata-clear"))
        }
        listOf(401, 403, 429, 500).forEach { status ->
            val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
            val provider = FakeNativeProvider()
            val binding = binding(store, provider, RecordingDownloader.httpFailure(status))
            val handle = binding.mount(subject(), RemoteUrl)
            val staleModel = handle.imageModel.value

            binding.refresh(handle)

            assertEquals(staleModel, handle.imageModel.value)
            assertEquals(0, provider.retireExactCalls)
        }
    }

    @Test
    fun invalid2xxRetiresBeforeClearWithoutReservation() = runBlocking {
        val events = mutableListOf<String>()
        val store = FakeMetadataStore(events).apply { metadata = metadata(freshUntilMs = 50L) }
        val provider =
            FakeNativeProvider(
                stageFinish = RaftAvatarNativeStageFinish.InvalidContent,
                events = events
            )
        val binding = binding(store, provider, RecordingDownloader.modified(byteArrayOf(9)))
        val handle = binding.mount(subject(), RemoteUrl)

        binding.refresh(handle)

        assertNull(handle.imageModel.value)
        assertEquals(1, provider.retireExactCalls)
        assertEquals(0, provider.reserveCalls)
        assertTrue(events.indexOf("retire") < events.indexOf("metadata-clear"))
    }

    @Test
    fun stageWriteFailureAbortsAndKeepsPriorModelWithoutReservation() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val provider = FakeNativeProvider(stageWriteSucceeds = false)
        val binding = binding(store, provider, RecordingDownloader.modified(byteArrayOf(1)))
        val handle = binding.mount(subject(), RemoteUrl)
        val staleModel = handle.imageModel.value

        binding.refresh(handle)

        assertEquals(staleModel, handle.imageModel.value)
        assertEquals(0, provider.reserveCalls)
        assertTrue("abort" in provider.events)
    }

    @Test
    fun rejectedSqlCasRollsBackAndKeepsPriorModel() = runBlocking {
        val store =
            FakeMetadataStore().apply {
                metadata = metadata(freshUntilMs = 50L)
                rejectNextSuccess = true
            }
        val provider = FakeNativeProvider()
        val binding = binding(store, provider, RecordingDownloader.modified(byteArrayOf(1)))
        val handle = binding.mount(subject(), RemoteUrl)
        val staleModel = handle.imageModel.value

        binding.refresh(handle)

        assertEquals(staleModel, handle.imageModel.value)
        assertEquals(1L, store.metadata?.commitIdentity?.commitGeneration)
        assertTrue("rollback" in provider.events)
        assertEquals(0, store.restoreCalls)
    }

    @Test
    fun finalizeFailureRestoresSqlAndKeepsPriorModel() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val provider = FakeNativeProvider(finalizeResult = RaftAvatarNativeOperationResult.StorageFailure)
        val binding = binding(store, provider, RecordingDownloader.modified(byteArrayOf(1)))
        val handle = binding.mount(subject(), RemoteUrl)
        val staleModel = handle.imageModel.value

        binding.refresh(handle)

        assertEquals(staleModel, handle.imageModel.value)
        assertEquals(1L, store.metadata?.commitIdentity?.commitGeneration)
        assertEquals(1, store.restoreCalls)
        assertTrue(provider.events.indexOf("finalize") < provider.events.indexOf("rollback"))
    }

    @Test
    fun disposeBeforeLateChunkAbortsWithoutCasOrReservation() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val provider = FakeNativeProvider()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val binding = binding(store, provider, RecordingDownloader.modified(byteArrayOf(1), gate))
        val handle = binding.mount(subject(), RemoteUrl)

        val refresh = async { binding.refresh(handle) }
        yield()
        assertTrue(binding.dispose(handle))
        gate.complete(Unit)
        refresh.await()

        assertEquals(1L, store.metadata?.commitIdentity?.commitGeneration)
        assertEquals(0, provider.reserveCalls)
        assertTrue("abort" in provider.events)
    }

    @Test
    fun contentReplacementClearsOldHandleAndRetiresOldNativeSubject() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 500L) }
        val provider = FakeNativeProvider()
        val binding = binding(store, provider, RecordingDownloader.notModified())
        val old = binding.mount(subject(), RemoteUrl)

        val replacement = binding.mount(subject(), "https://cdn.example/avatar-v2.webp")

        assertNull(old.imageModel.value)
        assertNull(replacement.imageModel.value)
        assertEquals(1, provider.retireSubjectCalls)
    }

    @Test
    fun terminalClosesMountedHandleEvenWhenNativeCleanupFails() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 500L) }
        val provider = FakeNativeProvider(terminalResult = RaftAvatarNativeOperationResult.StorageFailure)
        val binding = binding(store, provider, RecordingDownloader.notModified())
        val handle = binding.mount(subject(), RemoteUrl)

        assertFalse(binding.retireOwner(owner()))

        assertNull(handle.imageModel.value)
        assertFalse(binding.dispose(handle))
    }

    @Test
    fun twoMountsJoinOneFlightAndDisposingOneDoesNotClearTheOther() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val provider = FakeNativeProvider()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val downloader = RecordingDownloader.modified(byteArrayOf(1), gate)
        val binding = binding(store, provider, downloader)
        val first = binding.mount(subject(), RemoteUrl)
        val second = binding.mount(subject(), RemoteUrl)

        val firstRefresh = async { binding.refresh(first) }
        yield()
        val secondRefresh = async { binding.refresh(second) }
        yield()
        assertTrue(binding.dispose(first))
        gate.complete(Unit)
        firstRefresh.await()
        secondRefresh.await()

        assertEquals(1, downloader.calls)
        assertEquals(1, provider.reserveCalls)
        assertNotEquals(first.imageModel.value, second.imageModel.value)
        assertFalse(binding.dispose(first))
        assertTrue(second.imageModel.value.orEmpty().startsWith("slock-avatar-render-v1:"))
    }

    @Test
    fun peekInitialManagedModelReturnsModelAcrossMountDisposeAndReopen() = runBlocking {
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 500L) }
        val provider = FakeNativeProvider()
        val binding = binding(store, provider, RecordingDownloader())

        // 1. Before mount, no snapshot exists yet
        assertNull(binding.peekInitialManagedModel(subject(), RemoteUrl))

        // 2. Mount and refresh to commit model into session
        val handle = binding.mount(subject(), RemoteUrl)
        binding.refresh(handle)
        val model = assertNotNull(handle.imageModel.value)
        assertTrue(model.startsWith("slock-avatar-render-v1:"))

        // 3. Peek returns the exact committed model synchronously
        val peeked = assertNotNull(binding.peekInitialManagedModel(subject(), RemoteUrl))
        assertEquals(model, peeked)

        // 4. Dispose handle (simulating leaving page / closing server switcher)
        assertTrue(binding.dispose(handle))

        // 5. Peek still returns the model synchronously (session remembers metadata)
        val peekedAfterDispose = assertNotNull(binding.peekInitialManagedModel(subject(), RemoteUrl))
        assertEquals(model, peekedAfterDispose)

        // 6. Mismatched URL returns null
        assertNull(binding.peekInitialManagedModel(subject(), "https://other.test/avatar.png"))
    }

    private fun binding(
        store: FakeMetadataStore = FakeMetadataStore(),
        provider: RaftAvatarMountedNativeProvider,
        downloader: RaftAvatarConditionalDownloader,
        diagnostics: (String) -> Unit = {},
        currentOwner: suspend (String?) -> RaftAvatarCacheOwner? = { owner() }
    ): RaftAvatarMountedBinding {
        var nonce = 0
        return RaftAvatarMountedBinding(
            coordinator = RaftAvatarCacheCoordinator(store) { 100L },
            currentOwner = currentOwner,
            nativeProvider = { provider },
            downloader = downloader,
            diagnostics = diagnostics,
            session =
                RaftAvatarMountedSession {
                    (++nonce).toString(16).padStart(64, '0')
                },
            // Tests run refresh transports unconfined so runBlocking callers keep the same
            // single-threaded interleaving the suite was written against.
            flightScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    private fun owner(
        accountId: String = "account-a",
        serverId: String = "server-a"
    ) = RaftAvatarCacheOwner("https://api.example", accountId, serverId)
    private fun subject() = RaftAvatarCacheSubject.Member(MemberKind.Agent, "member-a")
    private fun key(subject: RaftAvatarCacheSubject = subject()) =
        RaftAvatarCacheKey(owner(), subject, raftAvatarContentIdentity(RemoteUrl))

    private fun metadata(
        freshUntilMs: Long,
        key: RaftAvatarCacheKey = key()
    ): RaftAvatarCacheMetadata =
        RaftAvatarCacheMetadata(
            key = key,
            eTag = "\"v1\"",
            fetchedAtMs = 10L,
            validatedAtMs = 10L,
            freshUntilMs = freshUntilMs,
            staleUntilMs = 1_000L,
            sizeBytes = 3L,
            contentType = "image/webp",
            commitIdentity = RaftAvatarCommitIdentity(1L, "a".repeat(64)),
            modelRevision = 1L
        )

    private class RecordingDownloader(
        private val action: suspend (
            onChunk: (ByteArray, Long, Long?) -> Unit
        ) -> SharedBinaryConditionalDownloadResult
    ) : RaftAvatarConditionalDownloader {
        var calls = 0
        var lastValidators: build.raft.app.network.SharedBinaryConditionalValidators? = null

        override suspend fun download(
            url: String,
            validators: build.raft.app.network.SharedBinaryConditionalValidators?,
            onStart: (Long?) -> Unit,
            beforeRead: () -> Unit,
            onChunk: (ByteArray, Long, Long?) -> Unit
        ): SharedBinaryConditionalDownloadResult {
            calls += 1
            lastValidators = validators
            beforeRead()
            return action(onChunk)
        }

        constructor() : this({ SharedBinaryConditionalDownloadResult.NotModified(null, null) })

        companion object {
            fun modified(
                bytes: ByteArray,
                gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
            ) = RecordingDownloader { onChunk ->
                gate?.await()
                onChunk(bytes, bytes.size.toLong(), bytes.size.toLong())
                SharedBinaryConditionalDownloadResult.Modified(200, "image/webp", "\"v2\"", null)
            }

            fun notModified() = RecordingDownloader {
                SharedBinaryConditionalDownloadResult.NotModified("\"v1\"", null)
            }

            fun httpFailure(status: Int) = RecordingDownloader {
                throw SharedBinaryConditionalHttpException(status, "Conditional download failed: HTTP $status")
            }
        }
    }

    private class FakeNativeProvider(
        private var highWater: Long = 1L,
        private val stageFinish: RaftAvatarNativeStageFinish? = null,
        private val stageWriteSucceeds: Boolean = true,
        private val finalizeResult: RaftAvatarNativeOperationResult = RaftAvatarNativeOperationResult.Accepted,
        private val terminalResult: RaftAvatarNativeOperationResult = RaftAvatarNativeOperationResult.Accepted,
        private val bytesAvailable: Boolean = true,
        private val bytesDecodable: Boolean = true,
        val events: MutableList<String> = mutableListOf()
    ) : RaftAvatarMountedNativeProvider {
        var reserveCalls = 0
        var retireExactCalls = 0
        var retireSubjectCalls = 0
        private val reservations = mutableMapOf<String, RaftAvatarNativeReservation>()

        override suspend fun inspect(slot: RaftAvatarNativeSlot) =
            RaftAvatarMountedNativeInspection(true, bytesAvailable, bytesDecodable)

        override fun beginStage(fence: RaftAvatarNativeStageFence): RaftAvatarNativeStageOpen {
            events += "begin"
            return RaftAvatarNativeStageOpen.Opened(
                object : RaftAvatarNativeStageSink {
                    private var size = 0L
                    override fun write(bytes: ByteArray): Boolean {
                        events += "write"
                        size += bytes.size
                        return stageWriteSucceeds
                    }

                    override fun finish(): RaftAvatarNativeStageFinish {
                        events += "finish"
                        return stageFinish
                            ?: RaftAvatarNativeStageFinish.Ready(
                                RaftAvatarNativeStagedCandidate(fence.requestNonce, size, "image/webp")
                            )
                    }

                    override fun abort() { events += "abort" }
                }
            )
        }

        override suspend fun reserve(request: RaftAvatarNativeReservationRequest): RaftAvatarNativeReserveResult {
            events += "reserve"
            reserveCalls += 1
            val reservation =
                reservations.getOrPut(request.fence.requestNonce) {
                    highWater += 1L
                    RaftAvatarNativeReservation(
                        request.fence.requestNonce,
                        RaftAvatarCommitIdentity(highWater, highWater.toString(16).padStart(64, '0')),
                        request.candidateModelRevision
                    )
                }
            return RaftAvatarNativeReserveResult.Reserved(reservation)
        }

        override suspend fun bindStage(
            staged: RaftAvatarNativeStagedCandidate,
            reservation: RaftAvatarNativeReservation
        ): RaftAvatarNativeOperationResult {
            events += "bind"
            return RaftAvatarNativeOperationResult.Accepted
        }

        override suspend fun abortStage(staged: RaftAvatarNativeStagedCandidate): RaftAvatarNativeOperationResult {
            events += "abort-stage"
            return RaftAvatarNativeOperationResult.Accepted
        }

        override suspend fun finalize(reservation: RaftAvatarNativeReservation): RaftAvatarNativeOperationResult {
            events += "finalize"
            return finalizeResult
        }

        override suspend fun rollback(reservation: RaftAvatarNativeReservation): RaftAvatarNativeOperationResult {
            events += "rollback"
            return RaftAvatarNativeOperationResult.Accepted
        }

        override suspend fun retireExact(
            slot: RaftAvatarNativeSlot,
            expectedCommitIdentity: RaftAvatarCommitIdentity?
        ): RaftAvatarNativeOperationResult {
            events += "retire"
            retireExactCalls += 1
            return RaftAvatarNativeOperationResult.Accepted
        }

        override suspend fun retireSubject(slot: RaftAvatarNativeSlot): RaftAvatarNativeOperationResult {
            events += "retire-subject"
            retireSubjectCalls += 1
            return terminalResult
        }

        override suspend fun retireOwner(nativePartitionKey: String): RaftAvatarNativeOperationResult {
            events += "retire-owner"
            return terminalResult
        }

        override suspend fun clearAll() = terminalResult
    }

    private class FakeMetadataStore(
        private val events: MutableList<String> = mutableListOf()
    ) : RaftAvatarCacheMetadataStore {
        var metadata: RaftAvatarCacheMetadata? = null
        var failure: RaftAvatarCacheFailure? = null
        var rejectNextSuccess = false
        var restoreCalls = 0

        override suspend fun read(key: RaftAvatarCacheKey) =
            RaftAvatarCacheStoredState(metadata?.takeIf { it.key == key }, failure?.takeIf { it.key == key })

        var committedForOwnerCalls = 0

        override suspend fun readCommittedForOwner(
            owner: RaftAvatarCacheOwner,
            nowMs: Long,
            limit: Long
        ): List<RaftAvatarCacheMetadata> {
            committedForOwnerCalls += 1
            val row = metadata?.takeIf { it.key.owner == owner && it.hasExactCommitIdentity }
                ?: return emptyList()
            if (failure?.let { it.key == row.key && it.retryAtMs > nowMs } == true) return emptyList()
            return listOf(row)
        }

        override suspend fun commitSuccess(
            metadata: RaftAvatarCacheMetadata,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult {
            if (rejectNextSuccess) {
                rejectNextSuccess = false
                return RaftAvatarCacheCommitResult.RejectedStaleGeneration
            }
            if (!guard.allows(metadata.key) || !expectedMatches(guard.expectedCommit)) {
                return RaftAvatarCacheCommitResult.RejectedStaleGeneration
            }
            this.metadata = metadata
            failure = null
            return RaftAvatarCacheCommitResult.Accepted
        }

        override suspend fun commitFailure(
            failure: RaftAvatarCacheFailure,
            retireMatchingMetadata: Boolean,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult {
            if (!guard.allows(failure.key) || !expectedMatches(guard.expectedCommit)) {
                return RaftAvatarCacheCommitResult.RejectedStaleGeneration
            }
            this.failure = failure
            if (retireMatchingMetadata) {
                events += "metadata-clear"
                metadata = null
            }
            return RaftAvatarCacheCommitResult.Accepted
        }

        override suspend fun restoreAfterNativeFinalizeFailure(
            key: RaftAvatarCacheKey,
            expectedCommittedState: RaftAvatarCacheStoredState,
            previousState: RaftAvatarCacheStoredState,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult {
            if (!guard.allows(key) || read(key) != expectedCommittedState) {
                return RaftAvatarCacheCommitResult.RejectedStaleGeneration
            }
            restoreCalls += 1
            metadata = previousState.metadata
            failure = previousState.failure
            return RaftAvatarCacheCommitResult.Accepted
        }

        override suspend fun clearFailure(key: RaftAvatarCacheKey) { failure = null }
        override suspend fun clearTemporaryFailures(owner: RaftAvatarCacheOwner) {
            if (failure?.key?.owner == owner && failure?.kind == RaftAvatarCacheFailureKind.TemporaryTransport) {
                failure = null
            }
        }
        override suspend fun clearSubject(owner: RaftAvatarCacheOwner, subject: RaftAvatarCacheSubject) {
            if (metadata?.key?.owner == owner && metadata?.key?.subject == subject) metadata = null
        }
        override suspend fun clearOwner(owner: RaftAvatarCacheOwner) {
            if (metadata?.key?.owner == owner) metadata = null
        }
        override suspend fun clearAll() { metadata = null; failure = null }

        private fun expectedMatches(expected: RaftAvatarCacheCommitExpectation?): Boolean {
            if (expected == null) return true
            return metadata?.commitIdentity == expected.commitIdentity &&
                (metadata?.modelRevision ?: 0L) == expected.modelRevision
        }
    }

    private companion object {
        const val RemoteUrl = "https://cdn.example/avatar.webp"
    }

    @Test
    fun mountLegacyEmitsBucketedReasonWithoutRawIds() = runBlocking {
        val lines = mutableListOf<String>()
        val binding =
            binding(
                provider = FakeNativeProvider(),
                downloader = RecordingDownloader(),
                currentOwner = { null },
                diagnostics = { lines += it }
            )

        binding.mount(subject(), RemoteUrl, serverId = "server-b")

        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("avatar_mount result=legacy reason=owner_null"))
        assertFalse(lines[0].contains("server-b"))
        assertFalse(lines[0].contains("member-a"))
    }

    @Test
    fun mountManagedEmitsNoLegacyLine() = runBlocking {
        val lines = mutableListOf<String>()
        val binding =
            binding(
                provider = FakeNativeProvider(),
                downloader = RecordingDownloader(),
                diagnostics = { lines += it }
            )

        binding.mount(subject(), RemoteUrl)

        assertTrue(lines.none { it.contains("result=legacy") })
    }

    @Test
    fun refreshFailureEmitsHttpKindAndDurationWithoutRawIds() = runBlocking {
        val lines = mutableListOf<String>()
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val binding =
            binding(
                store = store,
                provider = FakeNativeProvider(),
                downloader = RecordingDownloader.httpFailure(500),
                diagnostics = { lines += it }
            )
        val handle = binding.mount(subject(), RemoteUrl)

        binding.refresh(handle)

        val failure = assertNotNull(lines.firstOrNull { it.startsWith("avatar_refresh_failure") })
        assertTrue(failure.contains("failure=http_500"))
        assertTrue(failure.contains("ms="))
        assertFalse(failure.contains("server-a"))
        assertFalse(failure.contains("member-a"))
        assertFalse(failure.contains(RemoteUrl))
    }

    @Test
    fun refreshFailureEmitsTransportKindForNonHttpThrowables() = runBlocking {
        val lines = mutableListOf<String>()
        val store = FakeMetadataStore().apply { metadata = metadata(freshUntilMs = 50L) }
        val binding =
            binding(
                store = store,
                provider = FakeNativeProvider(),
                downloader =
                    RecordingDownloader {
                        throw IllegalStateException("socket broke")
                    },
                diagnostics = { lines += it }
            )
        val handle = binding.mount(subject(), RemoteUrl)

        binding.refresh(handle)

        val failure = assertNotNull(lines.firstOrNull { it.startsWith("avatar_refresh_failure") })
        assertTrue(failure.contains("failure=transport"))
    }
}
