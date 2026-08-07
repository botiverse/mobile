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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockAvatarMountedBindingTest {
    @Test
    fun unsupportedProviderUsesLegacyUrlBeforeTransport() = runBlocking {
        val downloader = RecordingDownloader()
        val binding = binding(provider = UnsupportedSlockAvatarMountedNativeProvider, downloader = downloader)

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

        val descriptor = assertNotNull(parseSlockAvatarMountedRenderModel(handle.imageModel.value.orEmpty()))
        assertEquals(1L, descriptor.commitIdentity.commitGeneration)
        assertEquals(1L, descriptor.modelRevision)
        assertEquals(owner().nativePartitionKey, descriptor.slot.nativePartitionKey)
        assertEquals(0, downloader.calls)
        assertEquals(0, provider.reserveCalls)
    }

    @Test
    fun managedRenderParserRejectsOrdinaryAndMalformedModels() {
        assertNull(parseSlockAvatarMountedRenderModel(RemoteUrl))
        assertNull(parseSlockAvatarMountedRenderModel("data:image/png;base64,AAAA"))
        assertNull(parseSlockAvatarMountedRenderModel("slock-avatar-render-v1:not-base64"))
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

        val reloaded = assertNotNull(parseSlockAvatarMountedRenderModel(handle.imageModel.value.orEmpty()))
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

        val descriptor = assertNotNull(parseSlockAvatarMountedRenderModel(handle.imageModel.value.orEmpty()))
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
        assertNotNull(parseSlockAvatarMountedRenderModel(handle.imageModel.value.orEmpty()))
        Unit
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
                stageFinish = SlockAvatarNativeStageFinish.InvalidContent,
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
        val provider = FakeNativeProvider(finalizeResult = SlockAvatarNativeOperationResult.StorageFailure)
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
        val provider = FakeNativeProvider(terminalResult = SlockAvatarNativeOperationResult.StorageFailure)
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

    private fun binding(
        store: FakeMetadataStore = FakeMetadataStore(),
        provider: SlockAvatarMountedNativeProvider,
        downloader: SlockAvatarConditionalDownloader
    ): SlockAvatarMountedBinding {
        var nonce = 0
        return SlockAvatarMountedBinding(
            coordinator = SlockAvatarCacheCoordinator(store) { 100L },
            currentOwner = { owner() },
            nativeProvider = { provider },
            downloader = downloader,
            session =
                SlockAvatarMountedSession {
                    (++nonce).toString(16).padStart(64, '0')
                }
        )
    }

    private fun owner() = SlockAvatarCacheOwner("https://api.example", "account-a", "server-a")
    private fun subject() = SlockAvatarCacheSubject.Member(MemberKind.Agent, "member-a")
    private fun key() = SlockAvatarCacheKey(owner(), subject(), slockAvatarContentIdentity(RemoteUrl))

    private fun metadata(freshUntilMs: Long): SlockAvatarCacheMetadata =
        SlockAvatarCacheMetadata(
            key = key(),
            eTag = "\"v1\"",
            fetchedAtMs = 10L,
            validatedAtMs = 10L,
            freshUntilMs = freshUntilMs,
            staleUntilMs = 1_000L,
            sizeBytes = 3L,
            contentType = "image/webp",
            commitIdentity = SlockAvatarCommitIdentity(1L, "a".repeat(64)),
            modelRevision = 1L
        )

    private class RecordingDownloader(
        private val action: suspend (
            onChunk: (ByteArray, Long, Long?) -> Unit
        ) -> SharedBinaryConditionalDownloadResult
    ) : SlockAvatarConditionalDownloader {
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
        private val stageFinish: SlockAvatarNativeStageFinish? = null,
        private val stageWriteSucceeds: Boolean = true,
        private val finalizeResult: SlockAvatarNativeOperationResult = SlockAvatarNativeOperationResult.Accepted,
        private val terminalResult: SlockAvatarNativeOperationResult = SlockAvatarNativeOperationResult.Accepted,
        private val bytesAvailable: Boolean = true,
        private val bytesDecodable: Boolean = true,
        val events: MutableList<String> = mutableListOf()
    ) : SlockAvatarMountedNativeProvider {
        var reserveCalls = 0
        var retireExactCalls = 0
        var retireSubjectCalls = 0
        private val reservations = mutableMapOf<String, SlockAvatarNativeReservation>()

        override suspend fun inspect(slot: SlockAvatarNativeSlot) =
            SlockAvatarMountedNativeInspection(true, bytesAvailable, bytesDecodable)

        override fun beginStage(fence: SlockAvatarNativeStageFence): SlockAvatarNativeStageOpen {
            events += "begin"
            return SlockAvatarNativeStageOpen.Opened(
                object : SlockAvatarNativeStageSink {
                    private var size = 0L
                    override fun write(bytes: ByteArray): Boolean {
                        events += "write"
                        size += bytes.size
                        return stageWriteSucceeds
                    }

                    override fun finish(): SlockAvatarNativeStageFinish {
                        events += "finish"
                        return stageFinish
                            ?: SlockAvatarNativeStageFinish.Ready(
                                SlockAvatarNativeStagedCandidate(fence.requestNonce, size, "image/webp")
                            )
                    }

                    override fun abort() { events += "abort" }
                }
            )
        }

        override suspend fun reserve(request: SlockAvatarNativeReservationRequest): SlockAvatarNativeReserveResult {
            events += "reserve"
            reserveCalls += 1
            val reservation =
                reservations.getOrPut(request.fence.requestNonce) {
                    highWater += 1L
                    SlockAvatarNativeReservation(
                        request.fence.requestNonce,
                        SlockAvatarCommitIdentity(highWater, highWater.toString(16).padStart(64, '0')),
                        request.candidateModelRevision
                    )
                }
            return SlockAvatarNativeReserveResult.Reserved(reservation)
        }

        override suspend fun bindStage(
            staged: SlockAvatarNativeStagedCandidate,
            reservation: SlockAvatarNativeReservation
        ): SlockAvatarNativeOperationResult {
            events += "bind"
            return SlockAvatarNativeOperationResult.Accepted
        }

        override suspend fun abortStage(staged: SlockAvatarNativeStagedCandidate): SlockAvatarNativeOperationResult {
            events += "abort-stage"
            return SlockAvatarNativeOperationResult.Accepted
        }

        override suspend fun finalize(reservation: SlockAvatarNativeReservation): SlockAvatarNativeOperationResult {
            events += "finalize"
            return finalizeResult
        }

        override suspend fun rollback(reservation: SlockAvatarNativeReservation): SlockAvatarNativeOperationResult {
            events += "rollback"
            return SlockAvatarNativeOperationResult.Accepted
        }

        override suspend fun retireExact(
            slot: SlockAvatarNativeSlot,
            expectedCommitIdentity: SlockAvatarCommitIdentity?
        ): SlockAvatarNativeOperationResult {
            events += "retire"
            retireExactCalls += 1
            return SlockAvatarNativeOperationResult.Accepted
        }

        override suspend fun retireSubject(slot: SlockAvatarNativeSlot): SlockAvatarNativeOperationResult {
            events += "retire-subject"
            retireSubjectCalls += 1
            return terminalResult
        }

        override suspend fun retireOwner(nativePartitionKey: String): SlockAvatarNativeOperationResult {
            events += "retire-owner"
            return terminalResult
        }

        override suspend fun clearAll() = terminalResult
    }

    private class FakeMetadataStore(
        private val events: MutableList<String> = mutableListOf()
    ) : SlockAvatarCacheMetadataStore {
        var metadata: SlockAvatarCacheMetadata? = null
        var failure: SlockAvatarCacheFailure? = null
        var rejectNextSuccess = false
        var restoreCalls = 0

        override suspend fun read(key: SlockAvatarCacheKey) =
            SlockAvatarCacheStoredState(metadata?.takeIf { it.key == key }, failure?.takeIf { it.key == key })

        override suspend fun commitSuccess(
            metadata: SlockAvatarCacheMetadata,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult {
            if (rejectNextSuccess) {
                rejectNextSuccess = false
                return SlockAvatarCacheCommitResult.RejectedStaleGeneration
            }
            if (!guard.allows(metadata.key) || !expectedMatches(guard.expectedCommit)) {
                return SlockAvatarCacheCommitResult.RejectedStaleGeneration
            }
            this.metadata = metadata
            failure = null
            return SlockAvatarCacheCommitResult.Accepted
        }

        override suspend fun commitFailure(
            failure: SlockAvatarCacheFailure,
            retireMatchingMetadata: Boolean,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult {
            if (!guard.allows(failure.key) || !expectedMatches(guard.expectedCommit)) {
                return SlockAvatarCacheCommitResult.RejectedStaleGeneration
            }
            this.failure = failure
            if (retireMatchingMetadata) {
                events += "metadata-clear"
                metadata = null
            }
            return SlockAvatarCacheCommitResult.Accepted
        }

        override suspend fun restoreAfterNativeFinalizeFailure(
            key: SlockAvatarCacheKey,
            expectedCommittedState: SlockAvatarCacheStoredState,
            previousState: SlockAvatarCacheStoredState,
            guard: SlockAvatarCacheCommitGuard
        ): SlockAvatarCacheCommitResult {
            if (!guard.allows(key) || read(key) != expectedCommittedState) {
                return SlockAvatarCacheCommitResult.RejectedStaleGeneration
            }
            restoreCalls += 1
            metadata = previousState.metadata
            failure = previousState.failure
            return SlockAvatarCacheCommitResult.Accepted
        }

        override suspend fun clearFailure(key: SlockAvatarCacheKey) { failure = null }
        override suspend fun clearTemporaryFailures(owner: SlockAvatarCacheOwner) {
            if (failure?.key?.owner == owner && failure?.kind == SlockAvatarCacheFailureKind.TemporaryTransport) {
                failure = null
            }
        }
        override suspend fun clearSubject(owner: SlockAvatarCacheOwner, subject: SlockAvatarCacheSubject) {
            if (metadata?.key?.owner == owner && metadata?.key?.subject == subject) metadata = null
        }
        override suspend fun clearOwner(owner: SlockAvatarCacheOwner) {
            if (metadata?.key?.owner == owner) metadata = null
        }
        override suspend fun clearAll() { metadata = null; failure = null }

        private fun expectedMatches(expected: SlockAvatarCacheCommitExpectation?): Boolean {
            if (expected == null) return true
            return metadata?.commitIdentity == expected.commitIdentity &&
                (metadata?.modelRevision ?: 0L) == expected.modelRevision
        }
    }

    private companion object {
        const val RemoteUrl = "https://cdn.example/avatar.webp"
    }
}
