package build.raft.app.data

import com.tencent.kmm.network.export.NetworkByteStreamSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SlockPlatformFileStreamTest {
    @Test
    fun exactFileReadsOnlyBoundedChunksAndClosesOnce() = runBlocking {
        val source = FakeChunkSource(mutableListOf(ByteArray(PlatformFileStreamChunkBytes), byteArrayOf(1, 2), ByteArray(0)))
        val received = mutableListOf<Int>()
        val stream = boundedPlatformFileNetworkStream(PlatformFileStreamChunkBytes.toLong() + 2L, source)

        stream.readChunks(
            object : NetworkByteStreamSink {
                override suspend fun write(bytes: ByteArray) {
                    received += bytes.size
                }
            }
        )
        stream.cancel()

        assertEquals(listOf(PlatformFileStreamChunkBytes, 2), received)
        assertTrue(source.requestedMaxBytes.all { it in 1..PlatformFileStreamChunkBytes })
        assertEquals(1, source.closeCount)
    }

    @Test
    fun shortAndGrowingFilesFailClosedAndCloseOnce() = runBlocking {
        val short = FakeChunkSource(mutableListOf(byteArrayOf(1), ByteArray(0)))
        assertIs<IllegalStateException>(failureOf {
            boundedPlatformFileNetworkStream(2, short).readChunks(DiscardingSink)
        })
        assertEquals(1, short.closeCount)

        val growing = FakeChunkSource(mutableListOf(byteArrayOf(1, 2), byteArrayOf(3)))
        assertIs<IllegalStateException>(failureOf {
            boundedPlatformFileNetworkStream(2, growing).readChunks(DiscardingSink)
        })
        assertEquals(1, growing.closeCount)
    }

    @Test
    fun oversizedChunkAndWholeFileFallbackFailWithoutAggregation() = runBlocking {
        val oversizedChunk = FakeChunkSource(mutableListOf(byteArrayOf(1, 2)))
        assertIs<IllegalStateException>(failureOf {
            boundedPlatformFileNetworkStream(1, oversizedChunk).readChunks(DiscardingSink)
        })
        assertEquals(1, oversizedChunk.closeCount)

        val wholeRead = FakeChunkSource(mutableListOf(byteArrayOf(1)))
        val stream = boundedPlatformFileNetworkStream(1, wholeRead)
        assertIs<IllegalStateException>(failureOf { stream.readAll() })
        assertEquals(1, wholeRead.closeCount)
    }

    @Test
    fun oldAttemptCancelCannotCloseOrAdvanceFreshAttempt() = runBlocking {
        val oldSource = FakeChunkSource(mutableListOf(byteArrayOf(7), ByteArray(0)))
        val freshSource = FakeChunkSource(mutableListOf(byteArrayOf(8), ByteArray(0)))
        val oldAttempt = boundedPlatformFileNetworkStream(1, oldSource)
        val freshAttempt = boundedPlatformFileNetworkStream(1, freshSource)

        oldAttempt.cancel()
        freshAttempt.readChunks(DiscardingSink)
        oldAttempt.cancel()

        assertEquals(1, oldSource.closeCount)
        assertEquals(1, freshSource.closeCount)
        assertEquals(listOf(1, 1), freshSource.requestedMaxBytes)
    }

    @Test
    fun frozenLargeLengthIsMetadataOnlyUntilTransportPullsChunks() {
        val source = FakeChunkSource(mutableListOf())
        val stream = boundedPlatformFileNetworkStream(199_229_440L, source)

        assertEquals(199_229_440L, stream.contentLength)
        assertTrue(source.requestedMaxBytes.isEmpty())
        assertEquals(0, source.closeCount)
        stream.cancel()
        assertEquals(1, source.closeCount)
    }

    @Test
    fun feedbackCacheLifecycleRetiresAnonymousLogoutAndPreviousAccountOnly() = runBlocking {
        val anonymousPreferences = InMemorySlockKeyValueStore()
        val anonymousResolver = RecordingRetirementResolver()
        ProductFeedbackImmutableCacheLifecycle(
            anonymousResolver,
            ProductFeedbackCleanupStore(anonymousPreferences)
        ).initialize(null)
        assertEquals(1, anonymousResolver.retireAllCalls)

        val preferences = InMemorySlockKeyValueStore()
        val draftStore = SettingsQuiverFeedbackDraftStore(preferences)
        draftStore.save("account-a", "A private draft")
        draftStore.save("account-b", "B private draft")
        val cleanupStore = ProductFeedbackCleanupStore(preferences, draftStore)
        val resolver = RecordingRetirementResolver()
        val lifecycle = ProductFeedbackImmutableCacheLifecycle(resolver, cleanupStore)
        lifecycle.initialize("account-a")
        lifecycle.onSessionEvent(AuthSessionEvent.TokensRotated(1), "account-a")
        lifecycle.onSessionEvent(AuthSessionEvent.Authenticated(2), "account-b")

        assertEquals(listOf(productFeedbackImmutableOwnerKey("account-a")), resolver.retiredOwners)
        assertEquals(0, resolver.retireAllCalls)
        assertEquals(SettingsQuiverFeedbackDraftSnapshot(), draftStore.read("account-a"))
        assertEquals("B private draft", draftStore.read("account-b").text)

        lifecycle.onSessionEvent(AuthSessionEvent.Cleared(3, AuthSessionClearReason.Logout), null)
        assertEquals(1, resolver.retireAllCalls)
        assertEquals(SettingsQuiverFeedbackDraftSnapshot(), draftStore.read("account-b"))
        draftStore.save("account-b", "stale dispose write")
        assertEquals(SettingsQuiverFeedbackDraftSnapshot(), draftStore.read("account-b"))
        cleanupStore.activateAccount("account-b")
        draftStore.save("account-b", "fresh login draft")
        assertEquals("fresh login draft", draftStore.read("account-b").text)
    }

    @Test
    fun feedbackCacheLifecyclePersistsFailedLogoutAndRetriesBeforeClearingDraft() = runBlocking {
        val preferences = InMemorySlockKeyValueStore()
        val draftStore = SettingsQuiverFeedbackDraftStore(preferences)
        draftStore.save("account-a", "Keep until bytes are deleted")
        val cleanupStore = ProductFeedbackCleanupStore(preferences, draftStore)
        val resolver = RecordingRetirementResolver(retirementSucceeds = false)
        val lifecycle = ProductFeedbackImmutableCacheLifecycle(resolver, cleanupStore)
        lifecycle.initialize("account-a")

        lifecycle.onSessionEvent(AuthSessionEvent.Cleared(1, AuthSessionClearReason.Logout), null)

        assertEquals(SettingsQuiverFeedbackDraftSnapshot(), draftStore.read("account-a"))
        assertEquals(1, cleanupStore.pendingOwnerCount())
        assertTrue(!cleanupStore.activateAccount("account-a"))
        draftStore.save("account-a", "must not resurrect")
        assertEquals(SettingsQuiverFeedbackDraftSnapshot(), draftStore.read("account-a"))

        resolver.retirementSucceeds = true
        lifecycle.initialize(null)

        assertEquals(SettingsQuiverFeedbackDraftSnapshot(), draftStore.read("account-a"))
        assertEquals(0, cleanupStore.pendingOwnerCount())
        assertTrue(cleanupStore.activateAccount("account-a"))
        assertEquals(SettingsQuiverFeedbackDraftSnapshot(), draftStore.read("account-a"))
        draftStore.save("account-a", "fresh after cleanup")
        assertEquals("fresh after cleanup", draftStore.read("account-a").text)
    }

    @Test
    fun ownerJournalSurvivesCrashBeforeDraftBlockWrite() = runBlocking {
        val preferences = InMemorySlockKeyValueStore()
        SettingsQuiverFeedbackDraftStore(preferences).save("account-a", "private")
        val interruptedStore = FailAtWriteStore(preferences, failAtWrite = 2)
        val interruptedCleanup = ProductFeedbackCleanupStore(interruptedStore)

        assertIs<SimulatedPreferenceCrash>(failureOf {
            interruptedCleanup.retireOwner(
                "account-a",
                productFeedbackImmutableOwnerKey("account-a")
            ) { true }
        })

        val resolver = RecordingRetirementResolver()
        val restartedDraftStore = SettingsQuiverFeedbackDraftStore(preferences)
        val restartedCleanup = ProductFeedbackCleanupStore(preferences, restartedDraftStore)
        assertEquals(1, restartedCleanup.pendingOwnerCount())
        restartedCleanup.drain(resolver)

        assertEquals(listOf(productFeedbackImmutableOwnerKey("account-a")), resolver.retiredOwners)
        assertEquals(0, restartedCleanup.pendingOwnerCount())
        assertTrue(restartedCleanup.activateAccount("account-a"))
        assertEquals(SettingsQuiverFeedbackDraftSnapshot(), restartedDraftStore.read("account-a"))
    }

    @Test
    fun draftIndexSurvivesCrashBeforeDraftRecordWrite() = runBlocking {
        val preferences = InMemorySlockKeyValueStore()
        val interruptedStore = FailAtWriteStore(preferences, failAtWrite = 2)

        assertIs<SimulatedPreferenceCrash>(failureOf {
            SettingsQuiverFeedbackDraftStore(interruptedStore).save("account-a", "private")
        })

        val restartedDraftStore = SettingsQuiverFeedbackDraftStore(preferences)
        assertEquals(setOf("account-a"), restartedDraftStore.accountIds())
        val resolver = RecordingRetirementResolver()
        ProductFeedbackImmutableCacheLifecycle(
            resolver,
            ProductFeedbackCleanupStore(preferences, restartedDraftStore)
        ).initialize(null)
        assertEquals(listOf(productFeedbackImmutableOwnerKey("account-a")), resolver.retiredOwners)
        assertTrue(restartedDraftStore.accountIds().isEmpty())
    }

    private object DiscardingSink : NetworkByteStreamSink {
        override suspend fun write(bytes: ByteArray) = Unit
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        error("Expected failure")
    }

    private class FakeChunkSource(
        private val chunks: MutableList<ByteArray>
    ) : SlockPlatformFileChunkSource {
        val requestedMaxBytes = mutableListOf<Int>()
        var closeCount: Int = 0
            private set

        override suspend fun read(maxBytes: Int): ByteArray {
            requestedMaxBytes += maxBytes
            return if (chunks.isEmpty()) ByteArray(0) else chunks.removeAt(0)
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class RecordingRetirementResolver(
        var retirementSucceeds: Boolean = true
    ) : SlockPlatformFileResolver {
        val retiredOwners = mutableListOf<String>()
        var retireAllCalls = 0

        override suspend fun readFile(fileRef: String): SlockPlatformFileReadResult = error("unused")

        override suspend fun statFile(fileRef: String): SlockPlatformFileStatResult = error("unused")

        override suspend fun openFileStream(
            fileRef: String,
            expectedSizeBytes: Long
        ): SlockPlatformFileChunkSource = error("unused")

        override suspend fun retireImmutableOwner(ownerKey: String): Boolean {
            if (!retirementSucceeds) return false
            retiredOwners += ownerKey
            return true
        }

        override suspend fun retireAllImmutableOwners(): Boolean {
            if (!retirementSucceeds) return false
            retireAllCalls += 1
            return true
        }
    }

    private class FailAtWriteStore(
        private val delegate: SharedPreferenceStore,
        private val failAtWrite: Int
    ) : SharedPreferenceStore {
        private var writes = 0

        override fun getString(key: String): String? = delegate.getString(key)

        override fun putString(key: String, value: String?) {
            writes += 1
            if (writes == failAtWrite) throw SimulatedPreferenceCrash()
            delegate.putString(key, value)
        }
    }

    private class SimulatedPreferenceCrash : RuntimeException()
}
