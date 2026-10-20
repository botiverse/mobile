package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.optBoolean
import build.raft.app.optString
import build.raft.app.toRaftJsonString
import build.raft.app.withEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RaftAvatarCacheRuntimeTest {
    @Test
    fun typedRequestCarriesOpaqueManagedIdentityPolicyValidatorsAndGeneration() = runBlocking {
        val store = FakeMetadataStore()
        val key = key()
        store.metadataBySlot[key.slot] = metadata(key, eTag = "etag-v1", lastModified = "yesterday")
        val native = FakeNativeCache().apply {
            inspection = RaftAvatarNativeInspection.Supported(true, true, managedBytes = 4_096L)
        }
        val runtime = runtime(store, nowMillis = { 200L })

        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(
                    subject = key.subject,
                    remoteUrl = RemoteUrl,
                    serverId = key.owner.serverId,
                    nativeCache = native
                )
            )

        val request = prepared.request
        assertEquals(RemoteUrl, request.remoteUrl)
        assertEquals(key.owner.nativePartitionKey, request.nativePartitionKey)
        assertEquals(key.nativeSubjectKey, request.nativeSubjectKey)
        assertEquals(key.nativeBytesKey, request.nativeBytesKey)
        assertEquals(RaftAvatarNativeReadMode.RenderFresh, request.readMode)
        assertEquals("etag-v1", request.eTag)
        assertEquals("yesterday", request.lastModified)
        assertEquals(store.metadataBySlot.getValue(key.slot).commitIdentity, request.expectedCommitIdentity)
        assertEquals(2L, request.commitGeneration)
        assertEquals(1L, request.expectedModelRevision)
        assertEquals(2L, request.newModelRevision)

        val wire = request.toJson()
        assertEquals(RAFT_AVATAR_NATIVE_CACHE_PROTOCOL, wire.optString("protocol"))
        assertEquals(RAFT_AVATAR_NATIVE_MANAGED_SET, wire.optString("managedSet"))
        assertEquals(RAFT_AVATAR_NATIVE_INDEX_CONTRACT, wire.optString("managedIndex"))
        assertEquals(RAFT_AVATAR_NATIVE_BUDGET_SCOPE, wire.optString("budgetScope"))
        assertTrue(wire.optBoolean("indexedRetirementRequired", false))
        assertEquals(RaftAvatarCachePolicy.MaxEntryBytes.toString(), wire.optString("maxEntryBytes"))
        assertEquals(RaftAvatarCachePolicy.MaxPlatformBytes.toString(), wire.optString("maxManagedBytes"))
        assertEquals(request.committedToCommitIdentity.nonce, wire.optString("commitNonce"))
        assertEquals("1", wire.optString("expectedCommitGeneration"))
        assertEquals("1", wire.optString("expectedModelRevision"))
        assertEquals("2", wire.optString("modelRevision"))

        val serialized = wire.toRaftJsonString()
        listOf(
            key.owner.environmentId,
            key.owner.accountId,
            key.owner.serverId,
            key.subject.subjectId,
            "file://",
            "nativePath",
            "bodyBase64",
            "decodedImage"
        ).forEach { forbidden -> assertFalse(serialized.contains(forbidden)) }
    }

    @Test
    fun legacyValidatorRowForcesFullFetchAndOmitsEveryConditionalValidator() = runBlocking {
        val key = key()
        val store = FakeMetadataStore()
        store.metadataBySlot[key.slot] =
            metadata(key, eTag = "legacy-etag", lastModified = "legacy-last")
                .copy(commitIdentity = null, modelRevision = 0L)
        val native = FakeNativeCache().apply {
            inspection = RaftAvatarNativeInspection.Supported(true, true, managedBytes = 512L)
        }

        val request =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime(store).prepare(key.subject, RemoteUrl, nativeCache = native)
            ).request

        assertEquals(RaftAvatarNativeReadMode.Fetch, request.readMode)
        assertNull(request.expectedCommitIdentity)
        assertNull(request.eTag)
        assertNull(request.lastModified)
        assertEquals(0L, request.expectedModelRevision)
        assertEquals(1L, request.commitGeneration)
    }

    @Test
    fun restartGenerationReuseCannotReplaceDurableCommitIdentityOrNonce() = runBlocking {
        val key = key()
        val store = FakeMetadataStore()
        val firstRuntime =
            runtime(
                store,
                generationAuthority =
                    RaftAvatarCacheGenerationAuthority { "1".repeat(64) }
            )
        val native = FakeNativeCache().apply {
            awaitBlock = {
                RaftAvatarNativeLoadResult.SuccessfulBytes(256L, "v1", null, "image/webp")
            }
        }
        val first =
            assertIs<RaftAvatarPreparedRender.Native>(
                firstRuntime.prepare(key.subject, RemoteUrl, nativeCache = native)
            )
        assertIs<RaftAvatarCacheCompletionReceipt.Accepted>(firstRuntime.observeAndCommit(first))

        val restarted =
            runtime(
                store,
                generationAuthority =
                    RaftAvatarCacheGenerationAuthority { "2".repeat(64) }
            )
        val next =
            assertIs<RaftAvatarPreparedRender.Native>(
                restarted.prepare(key.subject, RemoteUrl, nativeCache = native)
            )

        assertEquals(1L, next.token.generation)
        assertEquals(first.request.committedToCommitIdentity, next.request.expectedCommitIdentity)
        assertEquals(2L, next.request.commitGeneration)
        assertEquals("2".repeat(64), next.request.committedToCommitIdentity.nonce)
        assertNotEquals(next.token.generation, next.request.commitGeneration)
    }

    @Test
    fun managedSetAcknowledgementFailsClosedForMissingIndexGlobalBudgetOrOverflow() {
        fun response(): JsonObject =
            buildJsonObject {
                put("managedSet", RAFT_AVATAR_NATIVE_MANAGED_SET)
                put("managedIndex", RAFT_AVATAR_NATIVE_INDEX_CONTRACT)
                put("budgetScope", RAFT_AVATAR_NATIVE_BUDGET_SCOPE)
                put("indexedRetirement", true)
                put("managedBytes", "1024")
            }

        assertTrue(response().acknowledgesRaftAvatarManagedSet())
        assertFalse(response().withEntry("managedIndex", "none").acknowledgesRaftAvatarManagedSet())
        assertFalse(response().withEntry("budgetScope", "global_image_cache").acknowledgesRaftAvatarManagedSet())
        assertFalse(response().withEntry("indexedRetirement", false).acknowledgesRaftAvatarManagedSet())
        assertFalse(
            response()
                .withEntry("managedBytes", (RaftAvatarCachePolicy.MaxPlatformBytes + 1L).toString())
                .acknowledgesRaftAvatarManagedSet()
        )
    }

    @Test
    fun unsupportedNativeCapabilityUsesTheExistingUrlRenderer() = runBlocking {
        val runtime = runtime(FakeMetadataStore())
        val prepared =
            runtime.prepare(
                subject = subject(),
                remoteUrl = RemoteUrl,
                nativeCache = UnsupportedRaftAvatarNativeCache
            )

        assertEquals(RemoteUrl, assertIs<RaftAvatarPreparedRender.Legacy>(prepared).imageModel)
    }

    @Test
    fun missingNativeInspectionCallbackTimesOutToLegacyRendering() = runBlocking {
        val native = FakeNativeCache().apply { inspectBlock = { awaitCancellation() } }
        val runtime =
            runtime(
                store = FakeMetadataStore(),
                nativeInspectionTimeoutMillis = 1L
            )

        val prepared =
            runtime.prepare(
                subject = subject(),
                remoteUrl = RemoteUrl,
                nativeCache = native
            )

        assertEquals(RemoteUrl, assertIs<RaftAvatarPreparedRender.Legacy>(prepared).imageModel)
    }

    @Test
    fun readDecisionAndValidatorsAreForwardedWithoutInventingNativePolicy() = runBlocking {
        val key = key()
        val store = FakeMetadataStore()
        store.metadataBySlot[key.slot] =
            metadata(
                key = key,
                eTag = "etag-v1",
                lastModified = "last-v1",
                freshUntilMs = 200L,
                staleUntilMs = 500L
            )
        store.failureByKey[key] =
            RaftAvatarCacheFailure(
                key = key,
                kind = RaftAvatarCacheFailureKind.TemporaryTransport,
                consecutiveCount = 1,
                failedAtMs = 210L,
                retryAtMs = 400L
            )
        val native = FakeNativeCache().apply {
            inspection = RaftAvatarNativeInspection.Supported(true, true, managedBytes = 0L)
        }

        val stale =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime(store, nowMillis = { 250L }).prepare(
                    subject = key.subject,
                    remoteUrl = RemoteUrl,
                    nativeCache = native
                )
            ).request
        assertEquals(RaftAvatarNativeReadMode.RenderStaleSuppressed, stale.readMode)
        assertEquals(400L, stale.retryAtMillis)
        assertEquals("etag-v1", stale.eTag)
        assertEquals("last-v1", stale.lastModified)

        native.inspection = RaftAvatarNativeInspection.Supported(false, false, managedBytes = 0L)
        val suppressed =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime(store, nowMillis = { 250L }).prepare(
                    subject = key.subject,
                    remoteUrl = RemoteUrl,
                    nativeCache = native
                )
            ).request
        assertEquals(RaftAvatarNativeReadMode.Suppressed, suppressed.readMode)
        assertEquals(400L, suppressed.retryAtMillis)
    }

    @Test
    fun suppressedReadCompletesWithoutMetadataFinalizeRollbackOrLegacyEscape() = runBlocking {
        val key = key()
        val store = FakeMetadataStore().apply {
            failureByKey[key] =
                RaftAvatarCacheFailure(
                    key = key,
                    kind = RaftAvatarCacheFailureKind.ResourceAbsent,
                    consecutiveCount = 1,
                    failedAtMs = 100L,
                    retryAtMs = 10_000L
                )
        }
        val native = FakeNativeCache().apply {
            inspection = RaftAvatarNativeInspection.Supported(false, false, managedBytes = 0L)
            awaitBlock = { RaftAvatarNativeLoadResult.Suppressed }
        }
        val runtime = runtime(store, nowMillis = { 200L })
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )
        assertEquals(RaftAvatarNativeReadMode.Suppressed, prepared.request.readMode)

        assertIs<RaftAvatarCacheCompletionReceipt.Suppressed>(runtime.observeAndCommit(prepared))
        assertEquals(0, store.commitSuccessCalls)
        assertEquals(0, store.commitFailureCalls)
        assertTrue(native.finalized.isEmpty())
        assertTrue(native.rolledBack.isEmpty())
    }

    @Test
    fun sameSubjectAndContentReuseGenerationWhileChangedContentRotatesIt() = runBlocking {
        val native = FakeNativeCache()
        val runtime = runtime(FakeMetadataStore())
        val first =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )
        val same =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )
        val changed =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), "$RemoteUrl&v=2", nativeCache = native)
            )

        assertEquals(first.token, same.token)
        assertNotEquals(first.token.generation, changed.token.generation)
        assertNotEquals(first.request.nativeBytesKey, changed.request.nativeBytesKey)
        assertEquals(first.request.nativeSubjectKey, changed.request.nativeSubjectKey)
    }

    @Test
    fun lateOldSuccessNotModifiedAndFailureRollbackWithoutMetadataMutation() = runBlocking {
        val oldResults =
            listOf<RaftAvatarNativeLoadResult>(
                RaftAvatarNativeLoadResult.SuccessfulBytes(512L, "new", null, "image/webp"),
                RaftAvatarNativeLoadResult.NotModified("new", null),
                RaftAvatarNativeLoadResult.Failure(RaftAvatarCacheFailureKind.ResourceAbsent)
            )
        oldResults.forEach { lateResult ->
            val store = FakeMetadataStore()
            val native = FakeNativeCache()
            val runtime = runtime(store)
            val old =
                assertIs<RaftAvatarPreparedRender.Native>(
                    runtime.prepare(subject(), RemoteUrl, nativeCache = native)
                )
            native.awaitBlock = {
                runtime.prepare(subject(), "$RemoteUrl&v=next", nativeCache = native)
                lateResult
            }

            val receipt = runtime.observeAndCommit(old)

            assertIs<RaftAvatarCacheCompletionReceipt.RejectedStaleGeneration>(receipt)
            assertEquals(0, store.commitSuccessCalls)
            assertEquals(0, store.commitFailureCalls)
            assertEquals(listOf(old.request), native.rolledBack)
            assertTrue(native.finalized.isEmpty())
        }
    }

    @Test
    fun acceptedSuccessfulBytesCommitExactMetadataThenFinalizeNativeGeneration() = runBlocking {
        val events = mutableListOf<String>()
        val store = FakeMetadataStore(events)
        val native = FakeNativeCache(events).apply {
            awaitBlock = {
                RaftAvatarNativeLoadResult.SuccessfulBytes(
                    sizeBytes = 1_024L,
                    eTag = "etag-v2",
                    lastModified = "today",
                    contentType = "image/webp"
                )
            }
        }
        val runtime = runtime(store, nowMillis = { 1_000L })
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )

        assertIs<RaftAvatarCacheCompletionReceipt.Accepted>(runtime.observeAndCommit(prepared))

        val stored = store.metadataBySlot.getValue(prepared.token.key.slot)
        assertEquals(prepared.token.key, stored.key)
        assertEquals(1_024L, stored.sizeBytes)
        assertEquals("etag-v2", stored.eTag)
        assertEquals("today", stored.lastModified)
        assertEquals(prepared.request.committedToCommitIdentity, stored.commitIdentity)
        assertEquals(prepared.request.newModelRevision, stored.modelRevision)
        assertEquals(
            listOf("metadata_success", "native_finalize:successful_bytes"),
            events.filter { it.startsWith("metadata_") || it.startsWith("native_finalize") }
        )
        assertTrue(native.rolledBack.isEmpty())
    }

    @Test
    fun acceptedNotModifiedRefreshesExistingMetadataAndFinalizes304() = runBlocking {
        val key = key()
        val store = FakeMetadataStore()
        store.metadataBySlot[key.slot] =
            metadata(key, eTag = "etag-v1", freshUntilMs = 150L, staleUntilMs = 500L)
        val native = FakeNativeCache().apply {
            inspection = RaftAvatarNativeInspection.Supported(true, true, managedBytes = 1_024L)
            awaitBlock = { RaftAvatarNativeLoadResult.NotModified("etag-v2", "today") }
        }
        val runtime = runtime(store, nowMillis = { 200L })
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(key.subject, RemoteUrl, nativeCache = native)
            )

        assertIs<RaftAvatarCacheCompletionReceipt.Accepted>(runtime.observeAndCommit(prepared))

        val refreshed = store.metadataBySlot.getValue(key.slot)
        assertEquals(100L, refreshed.fetchedAtMs)
        assertEquals(200L, refreshed.validatedAtMs)
        assertEquals("etag-v2", refreshed.eTag)
        assertEquals(prepared.request.expectedCommitIdentity, refreshed.commitIdentity)
        assertEquals(prepared.request.expectedModelRevision, refreshed.modelRevision)
        assertEquals(RaftAvatarNativeFinalizeOutcome.NotModified, native.finalized.single().second)
    }

    @Test
    fun notModifiedCannotRefreshAReplacedDurableNonce() = runBlocking {
        val key = key()
        val original = metadata(key, eTag = "etag-v1", freshUntilMs = 150L, staleUntilMs = 500L)
        val store = FakeMetadataStore().apply { metadataBySlot[key.slot] = original }
        val native = FakeNativeCache().apply {
            inspection = RaftAvatarNativeInspection.Supported(true, true, managedBytes = 512L)
            awaitBlock = {
                store.metadataBySlot[key.slot] =
                    original.copy(
                        commitIdentity = RaftAvatarCommitIdentity(1L, "b".repeat(64)),
                        eTag = "replacement"
                    )
                RaftAvatarNativeLoadResult.NotModified("must-not-commit", null)
            }
        }
        val runtime = runtime(store, nowMillis = { 200L })
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(key.subject, RemoteUrl, nativeCache = native)
            )

        val receipt = assertIs<RaftAvatarCacheCompletionReceipt.Failed>(runtime.observeAndCommit(prepared))

        assertEquals("avatar_cache_metadata_missing", receipt.code)
        assertEquals("replacement", store.metadataBySlot.getValue(key.slot).eTag)
        assertEquals(100L, store.metadataBySlot.getValue(key.slot).validatedAtMs)
        assertEquals(listOf(prepared.request), native.rolledBack)
        assertTrue(native.finalized.isEmpty())
    }

    @Test
    fun successfulBytesCannotOverwriteAReplacedDurableNonce() = runBlocking {
        val key = key()
        val original = metadata(key, eTag = "etag-v1")
        val replacementIdentity = RaftAvatarCommitIdentity(1L, "c".repeat(64))
        val store = FakeMetadataStore().apply { metadataBySlot[key.slot] = original }
        val native = FakeNativeCache().apply {
            inspection = RaftAvatarNativeInspection.Supported(true, true, managedBytes = 512L)
            awaitBlock = {
                store.metadataBySlot[key.slot] =
                    original.copy(commitIdentity = replacementIdentity, eTag = "replacement")
                RaftAvatarNativeLoadResult.SuccessfulBytes(
                    sizeBytes = 512L,
                    eTag = "must-not-commit",
                    lastModified = null,
                    contentType = "image/webp"
                )
            }
        }
        val runtime = runtime(store)
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(key.subject, RemoteUrl, nativeCache = native)
            )

        assertIs<RaftAvatarCacheCompletionReceipt.RejectedStaleGeneration>(
            runtime.observeAndCommit(prepared)
        )
        assertEquals(replacementIdentity, store.metadataBySlot.getValue(key.slot).commitIdentity)
        assertEquals("replacement", store.metadataBySlot.getValue(key.slot).eTag)
        assertEquals(listOf(prepared.request), native.rolledBack)
        assertTrue(native.finalized.isEmpty())
    }

    @Test
    fun acceptedFailureCommitsExactBoundedFailureAndFinalizesItsKind() = runBlocking {
        val store = FakeMetadataStore()
        val native = FakeNativeCache().apply {
            awaitBlock = {
                RaftAvatarNativeLoadResult.Failure(RaftAvatarCacheFailureKind.TemporaryTransport)
            }
        }
        val runtime = runtime(store, nowMillis = { 5_000L })
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )

        assertIs<RaftAvatarCacheCompletionReceipt.Accepted>(runtime.observeAndCommit(prepared))

        val failure = store.failureByKey.getValue(prepared.token.key)
        assertEquals(RaftAvatarCacheFailureKind.TemporaryTransport, failure.kind)
        assertEquals(5_000L + RaftAvatarCachePolicy.InitialTemporaryBackoffMillis, failure.retryAtMs)
        assertEquals(RaftAvatarNativeFinalizeOutcome.TemporaryTransport, native.finalized.single().second)
    }

    @Test
    fun oversizedSuccessAnd304WithoutMetadataBothRollbackAndNeverFinalize() = runBlocking {
        val results =
            listOf<RaftAvatarNativeLoadResult>(
                RaftAvatarNativeLoadResult.SuccessfulBytes(
                    RaftAvatarCachePolicy.MaxEntryBytes + 1L,
                    null,
                    null,
                    "image/webp"
                ),
                RaftAvatarNativeLoadResult.NotModified(null, null)
            )
        results.forEach { result ->
            val store = FakeMetadataStore()
            val native = FakeNativeCache().apply { awaitBlock = { result } }
            val runtime = runtime(store)
            val prepared =
                assertIs<RaftAvatarPreparedRender.Native>(
                    runtime.prepare(subject(), RemoteUrl, nativeCache = native)
                )

            assertIs<RaftAvatarCacheCompletionReceipt.Failed>(runtime.observeAndCommit(prepared))
            assertEquals(listOf(prepared.request), native.rolledBack)
            assertTrue(native.finalized.isEmpty())
            assertTrue(store.metadataBySlot.isEmpty())
        }
    }

    @Test
    fun nativeResultWaitIsBoundedAndRollsBack() = runBlocking {
        val native = FakeNativeCache().apply { awaitBlock = { awaitCancellation() } }
        val runtime = runtime(FakeMetadataStore(), nativeResultTimeoutMillis = 1L)
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )

        val receipt = assertIs<RaftAvatarCacheCompletionReceipt.Failed>(runtime.observeAndCommit(prepared))
        assertEquals("native_result", receipt.stage)
        assertEquals("avatar_cache_native_result_timeout", receipt.code)
        assertEquals(listOf(prepared.request), native.rolledBack)
    }

    @Test
    fun callerCancellationCannotAbandonAnAdmittedNativeResultOrHalfCommitMetadata() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<RaftAvatarNativeLoadResult>()
        val store = FakeMetadataStore()
        val native = FakeNativeCache().apply {
            awaitBlock = {
                entered.complete(Unit)
                release.await()
            }
        }
        val runtime = runtime(store)
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )
        val observer = launch { runtime.observeAndCommit(prepared) }
        entered.await()

        observer.cancel()
        release.complete(
            RaftAvatarNativeLoadResult.SuccessfulBytes(512L, "etag", null, "image/webp")
        )
        observer.join()

        assertEquals(1, store.commitSuccessCalls)
        assertEquals(1, native.finalized.size)
        assertTrue(native.rolledBack.isEmpty())
    }

    @Test
    fun failedNativeFinalizeRollsBackStagingAndRestoresThePreviousMetadata() = runBlocking {
        val key = key()
        val previousMetadata = metadata(key, eTag = "etag-old", lastModified = "yesterday")
        val store = FakeMetadataStore().apply { metadataBySlot[key.slot] = previousMetadata }
        val native = FakeNativeCache().apply {
            awaitBlock = {
                RaftAvatarNativeLoadResult.SuccessfulBytes(512L, "etag-new", null, "image/webp")
            }
            finalizeResult = RaftAvatarNativeMutationResult.Failed("finalize_failed")
        }
        val runtime = runtime(store, nowMillis = { 200L })
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )

        val receipt = assertIs<RaftAvatarCacheCompletionReceipt.Failed>(runtime.observeAndCommit(prepared))

        assertEquals("native_finalize", receipt.stage)
        assertEquals("finalize_failed", receipt.code)
        assertEquals(listOf(prepared.request), native.rolledBack)
        assertEquals(previousMetadata, store.metadataBySlot[key.slot])
    }

    @Test
    fun reusedGenerationRunsOneMetadataAndFinalizeMutation() = runBlocking {
        val store = FakeMetadataStore()
        val native = FakeNativeCache().apply {
            awaitBlock = {
                RaftAvatarNativeLoadResult.SuccessfulBytes(512L, "etag", null, "image/webp")
            }
        }
        val runtime = runtime(store)
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )

        val first = async { runtime.observeAndCommit(prepared) }
        val second = async { runtime.observeAndCommit(prepared) }

        assertIs<RaftAvatarCacheCompletionReceipt.Accepted>(first.await())
        assertIs<RaftAvatarCacheCompletionReceipt.Accepted>(second.await())
        assertEquals(1, native.awaited.size)
        assertEquals(1, store.commitSuccessCalls)
        assertEquals(1, native.finalized.size)
    }

    @Test
    fun completedGenerationClosesForNewPrepareSoLaterStaleReadCanRevalidate() = runBlocking {
        val store = FakeMetadataStore()
        val native = FakeNativeCache().apply {
            awaitBlock = {
                RaftAvatarNativeLoadResult.SuccessfulBytes(512L, "etag", null, "image/webp")
            }
        }
        val runtime = runtime(store)
        val first =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )

        assertIs<RaftAvatarCacheCompletionReceipt.Accepted>(runtime.observeAndCommit(first))

        val later =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )
        assertNotEquals(first.token.generation, later.token.generation)
        assertEquals(first.request.nativeBytesKey, later.request.nativeBytesKey)
    }

    @Test
    fun retirementDuringFinalizeRejectsAndRollsBackTheNowStaleGeneration() = runBlocking {
        val store = FakeMetadataStore()
        val native = FakeNativeCache().apply {
            awaitBlock = {
                RaftAvatarNativeLoadResult.SuccessfulBytes(512L, "etag", null, "image/webp")
            }
        }
        lateinit var runtime: RaftAvatarCacheRuntime
        runtime = runtime(store)
        native.finalizeBlock = { _, _ ->
            runtime.clearAll { true }
            RaftAvatarNativeMutationResult.Accepted
        }
        val prepared =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(subject(), RemoteUrl, nativeCache = native)
            )

        assertIs<RaftAvatarCacheCompletionReceipt.RejectedStaleGeneration>(
            runtime.observeAndCommit(prepared)
        )

        assertEquals(listOf(prepared.request), native.rolledBack)
        assertTrue(store.metadataBySlot.isEmpty())
        assertEquals(1, store.clearAllCalls)
    }

    @Test
    fun settingsClearAlwaysAttemptsNativeAndSharedAndOnlyBothSuccessIsSuccess() = runBlocking {
        data class Case(val nativeSuccess: Boolean, val metadataSuccess: Boolean)
        listOf(
            Case(true, true),
            Case(false, true),
            Case(true, false),
            Case(false, false)
        ).forEach { case ->
            val store = FakeMetadataStore().apply { throwOnClearAll = !case.metadataSuccess }
            val runtime = runtime(store)
            var nativeCalls = 0

            val receipt =
                runtime.clearAll {
                    nativeCalls += 1
                    case.nativeSuccess
                }

            assertEquals(1, nativeCalls)
            assertEquals(1, store.clearAllCalls)
            if (case.nativeSuccess && case.metadataSuccess) {
                assertIs<RaftAvatarCacheClearReceipt.Success>(receipt)
            } else {
                val failed = assertIs<RaftAvatarCacheClearReceipt.Failed>(receipt)
                assertEquals(!case.nativeSuccess, RaftAvatarCacheClearStage.Native in failed.stages)
                assertEquals(!case.metadataSuccess, RaftAvatarCacheClearStage.Metadata in failed.stages)
            }
        }
    }

    @Test
    fun hangingSettingsNativeClearIsBoundedAndSharedClearStillRuns() = runBlocking {
        val store = FakeMetadataStore()
        val runtime = runtime(store, nativeMutationTimeoutMillis = 1L)

        val receipt = runtime.clearAll { awaitCancellation() }

        val failed = assertIs<RaftAvatarCacheClearReceipt.Failed>(receipt)
        assertEquals(setOf(RaftAvatarCacheClearStage.Native), failed.stages)
        assertEquals(1, store.clearAllCalls)
    }

    @Test
    fun terminalRetirementUsesIndexedNativeOwnerBeforeSharedMetadataAndServerSelectionNeverRetires() = runBlocking {
        val events = mutableListOf<String>()
        val store = FakeMetadataStore(events)
        val native = FakeNativeCache(events)
        val owner = owner()
        val runtime = runtime(store, ownerProvider = { requested -> owner.copy(serverId = requested ?: owner.serverId) })

        runtime.prepare(subject(), RemoteUrl, serverId = "server-b", nativeCache = native)
        assertTrue(native.retiredOwners.isEmpty())
        assertTrue(native.retiredSubjects.isEmpty())

        val receipt =
            runtime.retireOwners(
                RaftAvatarOwnerRetirementCapture(setOf(owner)),
                native
            )
        assertIs<RaftAvatarCacheClearReceipt.Success>(receipt)
        assertEquals(listOf(owner.nativePartitionKey), native.retiredOwners)
        assertEquals(listOf("native_retire_owner", "metadata_clear_owner"), events.takeLast(2))
    }

    @Test
    fun sessionRetirementCaptureKeepsOldEnvironmentAccountAndServersAfterSessionMutation() = runBlocking {
        val runtime = runtime(FakeMetadataStore())
        var session: StoredAuthSession? =
            StoredAuthSession(
                user = StoredAuthUser("old-account", "old@example.test"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-selected",
                servers =
                    listOf(
                        StoredAuthServer("server-a", "A"),
                        StoredAuthServer("server-b", "B")
                    )
            )
        val capture = runtime.captureSessionRetirement("https://old.example/", session)

        session = null

        assertNull(session)
        assertEquals(
            setOf("server-a", "server-b", "server-selected"),
            capture.owners.mapTo(mutableSetOf()) { it.serverId }
        )
        capture.owners.forEach { captured ->
            assertEquals("https://old.example", captured.environmentId)
            assertEquals("old-account", captured.accountId)
        }
    }

    @Test
    fun networkRecoveryClearsOnlyCurrentOwnersTemporaryBackoffAndAdmitsImmediateRetry() = runBlocking {
        val currentOwner = owner()
        val otherOwner = owner(accountId = "account-b")
        val currentKey = key(owner = currentOwner)
        val otherKey = key(owner = otherOwner)
        val permanentKey = key(owner = currentOwner, subject = subject("member-permanent"))
        val store = FakeMetadataStore().apply {
            failureByKey[currentKey] = temporaryFailure(currentKey)
            failureByKey[otherKey] = temporaryFailure(otherKey)
            failureByKey[permanentKey] =
                temporaryFailure(permanentKey).copy(kind = RaftAvatarCacheFailureKind.ResourceAbsent)
        }
        val native = FakeNativeCache()
        val runtime =
            runtime(
                store = store,
                nowMillis = { 250L },
                ownerProvider = { requested ->
                    currentOwner.takeIf { requested == null || requested == currentOwner.serverId }
                }
            )

        val suppressed =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(currentKey.subject, RemoteUrl, currentOwner.serverId, native)
            )
        assertEquals(RaftAvatarNativeReadMode.Suppressed, suppressed.request.readMode)

        assertTrue(runtime.recoverCurrentOwnerAfterNetworkAvailable())
        val retry =
            assertIs<RaftAvatarPreparedRender.Native>(
                runtime.prepare(currentKey.subject, RemoteUrl, currentOwner.serverId, native)
            )
        assertEquals(RaftAvatarNativeReadMode.Fetch, retry.request.readMode)
        assertNull(store.failureByKey[currentKey])
        assertEquals(otherKey, store.failureByKey[otherKey]?.key)
        assertEquals(permanentKey, store.failureByKey[permanentKey]?.key)

        assertTrue(runtime.recoverCurrentOwnerAfterNetworkAvailable())
        assertEquals(listOf(currentOwner, currentOwner), store.clearedTemporaryFailureOwners)
        assertEquals(otherKey, store.failureByKey[otherKey]?.key)
        assertEquals(permanentKey, store.failureByKey[permanentKey]?.key)
    }

    @Test
    fun networkRecoveryFailsClosedWithoutAStableCurrentOwner() = runBlocking {
        val oldOwner = owner()
        val newOwner = owner(accountId = "account-b", serverId = "server-b")
        val oldKey = key(owner = oldOwner)
        val newKey = key(owner = newOwner)
        val store = FakeMetadataStore().apply {
            failureByKey[oldKey] = temporaryFailure(oldKey)
            failureByKey[newKey] = temporaryFailure(newKey)
        }
        var ownerRead = 0
        val changingRuntime =
            runtime(
                store = store,
                ownerProvider = {
                    ownerRead += 1
                    if (ownerRead == 1) oldOwner else newOwner
                }
            )

        assertFalse(changingRuntime.recoverCurrentOwnerAfterNetworkAvailable())
        assertTrue(store.clearedTemporaryFailureOwners.isEmpty())
        assertEquals(oldKey, store.failureByKey[oldKey]?.key)
        assertEquals(newKey, store.failureByKey[newKey]?.key)

        val signedOutRuntime = runtime(store, ownerProvider = { null })
        assertFalse(signedOutRuntime.recoverCurrentOwnerAfterNetworkAvailable())
        val failedOwnerRuntime = runtime(store, ownerProvider = { error("owner unavailable") })
        assertFalse(failedOwnerRuntime.recoverCurrentOwnerAfterNetworkAvailable())
        assertTrue(store.clearedTemporaryFailureOwners.isEmpty())
    }

    @Test
    fun requestedServerAllowsSparseCurrentButRejectsUnrelatedServer() {
        val session =
            StoredAuthSession(
                user = StoredAuthUser("account-a", "a@example.test"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-current",
                servers = emptyList(),
                allowImplicitServerFallback = false
            )

        assertEquals(
            "server-current",
            raftCurrentAvatarCacheOwner("https://api.example", session, "server-current")?.serverId
        )
        assertNull(raftCurrentAvatarCacheOwner("https://api.example", session, "server-other"))
        assertNull(raftCurrentAvatarCacheOwner(" ", session))
        assertNull(
            raftCurrentAvatarCacheOwner(
                "https://api.example",
                session.copy(
                    selectedServerId = null,
                    servers = emptyList(),
                    allowImplicitServerFallback = false
                )
            )
        )
        assertNull(
            raftCurrentAvatarCacheOwner(
                "https://api.example",
                session.copy(user = session.user.copy(id = " "))
            )
        )
    }

    private fun runtime(
        store: FakeMetadataStore,
        nowMillis: () -> Long = { 100L },
        nativeInspectionTimeoutMillis: Long = RAFT_AVATAR_NATIVE_INSPECTION_TIMEOUT_MILLIS,
        nativeMutationTimeoutMillis: Long = RAFT_AVATAR_NATIVE_MUTATION_TIMEOUT_MILLIS,
        nativeResultTimeoutMillis: Long = RAFT_AVATAR_NATIVE_RESULT_TIMEOUT_MILLIS,
        generationAuthority: RaftAvatarCacheGenerationAuthority = RaftAvatarCacheGenerationAuthority(),
        ownerProvider: suspend (String?) -> RaftAvatarCacheOwner? = { requested ->
            owner(serverId = requested ?: "server-a")
        }
    ): RaftAvatarCacheRuntime =
        RaftAvatarCacheRuntime(
            coordinator = RaftAvatarCacheCoordinator(store, nowMillis),
            currentOwner = ownerProvider,
            generationAuthority = generationAuthority,
            nativeInspectionTimeoutMillis = nativeInspectionTimeoutMillis,
            nativeMutationTimeoutMillis = nativeMutationTimeoutMillis,
            nativeResultTimeoutMillis = nativeResultTimeoutMillis
        )

    private fun owner(
        environmentId: String = "https://api.example",
        accountId: String = "account-a",
        serverId: String = "server-a"
    ) = RaftAvatarCacheOwner(environmentId, accountId, serverId)

    private fun subject(memberId: String = "member-a") =
        RaftAvatarCacheSubject.Member(MemberKind.Agent, memberId)

    private fun key(
        owner: RaftAvatarCacheOwner = owner(),
        subject: RaftAvatarCacheSubject = subject(),
        url: String = RemoteUrl
    ) = RaftAvatarCacheKey(owner, subject, raftAvatarContentIdentity(url))

    private fun metadata(
        key: RaftAvatarCacheKey,
        eTag: String? = null,
        lastModified: String? = null,
        freshUntilMs: Long = 300L,
        staleUntilMs: Long = 600L
    ) = RaftAvatarCacheMetadata(
        key = key,
        eTag = eTag,
        lastModified = lastModified,
        fetchedAtMs = 100L,
        validatedAtMs = 100L,
        freshUntilMs = freshUntilMs,
        staleUntilMs = staleUntilMs,
        sizeBytes = 512L,
        contentType = "image/webp",
        commitIdentity = RaftAvatarCommitIdentity(1L, "a".repeat(64)),
        modelRevision = 1L
    )

    private fun temporaryFailure(key: RaftAvatarCacheKey) =
        RaftAvatarCacheFailure(
            key = key,
            kind = RaftAvatarCacheFailureKind.TemporaryTransport,
            consecutiveCount = 1,
            failedAtMs = 200L,
            retryAtMs = 400L
        )

    private val RaftAvatarCacheKey.slot: Pair<RaftAvatarCacheOwner, RaftAvatarCacheSubject>
        get() = owner to subject

    private class FakeMetadataStore(
        private val events: MutableList<String> = mutableListOf()
    ) : RaftAvatarCacheMetadataStore {
        val metadataBySlot = mutableMapOf<Pair<RaftAvatarCacheOwner, RaftAvatarCacheSubject>, RaftAvatarCacheMetadata>()
        val failureByKey = mutableMapOf<RaftAvatarCacheKey, RaftAvatarCacheFailure>()
        var commitSuccessCalls = 0
        var commitFailureCalls = 0
        var clearAllCalls = 0
        var throwOnClearAll = false
        val clearedTemporaryFailureOwners = mutableListOf<RaftAvatarCacheOwner>()

        override suspend fun readCommittedForOwner(
            owner: RaftAvatarCacheOwner,
            nowMs: Long,
            limit: Long
        ): List<RaftAvatarCacheMetadata> =
            metadataBySlot.values
                .filter { it.key.owner == owner && it.hasExactCommitIdentity }
                .filter { row -> failureByKey[row.key]?.let { it.retryAtMs > nowMs } != true }
                .sortedByDescending { it.validatedAtMs }
                .take(limit.toInt())

        override suspend fun read(key: RaftAvatarCacheKey): RaftAvatarCacheStoredState =
            RaftAvatarCacheStoredState(
                metadata = metadataBySlot[key.owner to key.subject],
                failure = failureByKey[key]
            )

        override suspend fun commitSuccess(
            metadata: RaftAvatarCacheMetadata,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult {
            commitSuccessCalls += 1
            if (!guard.allows(metadata.key)) return RaftAvatarCacheCommitResult.RejectedStaleGeneration
            guard.expectedCommit?.let { expected ->
                val current = metadataBySlot[metadata.key.owner to metadata.key.subject]
                if (
                    current?.key?.let { it != expected.key } == true ||
                    current?.commitIdentity != expected.commitIdentity ||
                    (current?.modelRevision ?: 0L) != expected.modelRevision
                ) {
                    return RaftAvatarCacheCommitResult.RejectedStaleGeneration
                }
            }
            metadataBySlot[metadata.key.owner to metadata.key.subject] = metadata
            failureByKey.keys
                .filter { it.owner == metadata.key.owner && it.subject == metadata.key.subject }
                .forEach(failureByKey::remove)
            if (!guard.allows(metadata.key)) {
                metadataBySlot.remove(metadata.key.owner to metadata.key.subject)
                return RaftAvatarCacheCommitResult.RejectedStaleGeneration
            }
            events += "metadata_success"
            return RaftAvatarCacheCommitResult.Accepted
        }

        override suspend fun commitFailure(
            failure: RaftAvatarCacheFailure,
            retireMatchingMetadata: Boolean,
            guard: RaftAvatarCacheCommitGuard
        ): RaftAvatarCacheCommitResult {
            commitFailureCalls += 1
            if (!guard.allows(failure.key)) return RaftAvatarCacheCommitResult.RejectedStaleGeneration
            val slot = failure.key.owner to failure.key.subject
            if (retireMatchingMetadata && metadataBySlot[slot]?.key == failure.key) {
                metadataBySlot.remove(slot)
            }
            failureByKey[failure.key] = failure
            if (!guard.allows(failure.key)) {
                failureByKey.remove(failure.key)
                return RaftAvatarCacheCommitResult.RejectedStaleGeneration
            }
            events += "metadata_failure"
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
            applyState(key, previousState)
            if (!guard.allows(key)) {
                applyState(key, expectedCommittedState)
                return RaftAvatarCacheCommitResult.RejectedStaleGeneration
            }
            events += "metadata_restore"
            return RaftAvatarCacheCommitResult.Accepted
        }

        override suspend fun clearFailure(key: RaftAvatarCacheKey) {
            failureByKey.remove(key)
        }

        override suspend fun clearTemporaryFailures(owner: RaftAvatarCacheOwner) {
            clearedTemporaryFailureOwners += owner
            failureByKey.entries.removeAll {
                it.key.owner == owner && it.value.kind == RaftAvatarCacheFailureKind.TemporaryTransport
            }
        }

        override suspend fun clearSubject(owner: RaftAvatarCacheOwner, subject: RaftAvatarCacheSubject) {
            metadataBySlot.remove(owner to subject)
            failureByKey.keys.filter { it.owner == owner && it.subject == subject }.forEach(failureByKey::remove)
            events += "metadata_clear_subject"
        }

        override suspend fun clearOwner(owner: RaftAvatarCacheOwner) {
            metadataBySlot.keys.filter { it.first == owner }.forEach(metadataBySlot::remove)
            failureByKey.keys.filter { it.owner == owner }.forEach(failureByKey::remove)
            events += "metadata_clear_owner"
        }

        override suspend fun clearAll() {
            clearAllCalls += 1
            if (throwOnClearAll) error("metadata clear failed")
            metadataBySlot.clear()
            failureByKey.clear()
            events += "metadata_clear_all"
        }

        private fun applyState(key: RaftAvatarCacheKey, state: RaftAvatarCacheStoredState) {
            val slot = key.owner to key.subject
            state.metadata?.let { metadataBySlot[slot] = it } ?: metadataBySlot.remove(slot)
            state.failure?.let { failureByKey[key] = it } ?: failureByKey.remove(key)
        }
    }

    private class FakeNativeCache(
        private val events: MutableList<String> = mutableListOf()
    ) : RaftAvatarNativeCache {
        var inspection: RaftAvatarNativeInspection =
            RaftAvatarNativeInspection.Supported(false, false, managedBytes = 0L)
        var inspectBlock: suspend () -> RaftAvatarNativeInspection = { inspection }
        var awaitBlock: suspend (RaftAvatarNativeRequest) -> RaftAvatarNativeLoadResult = {
            RaftAvatarNativeLoadResult.Rendered
        }
        var finalizeResult: RaftAvatarNativeMutationResult = RaftAvatarNativeMutationResult.Accepted
        var finalizeBlock:
            suspend (RaftAvatarNativeRequest, RaftAvatarNativeFinalizeOutcome) -> RaftAvatarNativeMutationResult =
            { _, _ -> finalizeResult }
        var rollbackResult: RaftAvatarNativeMutationResult = RaftAvatarNativeMutationResult.Accepted
        var retireResult: RaftAvatarNativeMutationResult = RaftAvatarNativeMutationResult.Accepted
        val inspected = mutableListOf<Triple<String, String, String>>()
        val awaited = mutableListOf<RaftAvatarNativeRequest>()
        val finalized = mutableListOf<Pair<RaftAvatarNativeRequest, RaftAvatarNativeFinalizeOutcome>>()
        val rolledBack = mutableListOf<RaftAvatarNativeRequest>()
        val retiredOwners = mutableListOf<String>()
        val retiredSubjects = mutableListOf<Pair<String, String>>()

        override suspend fun inspect(
            nativePartitionKey: String,
            nativeSubjectKey: String,
            nativeBytesKey: String
        ): RaftAvatarNativeInspection {
            inspected += Triple(nativePartitionKey, nativeSubjectKey, nativeBytesKey)
            return inspectBlock()
        }

        override suspend fun awaitResult(request: RaftAvatarNativeRequest): RaftAvatarNativeLoadResult {
            awaited += request
            return awaitBlock(request)
        }

        override suspend fun finalize(
            request: RaftAvatarNativeRequest,
            outcome: RaftAvatarNativeFinalizeOutcome
        ): RaftAvatarNativeMutationResult {
            finalized += request to outcome
            events += "native_finalize:${outcome.wireValue}"
            return finalizeBlock(request, outcome)
        }

        override suspend fun rollback(request: RaftAvatarNativeRequest): RaftAvatarNativeMutationResult {
            rolledBack += request
            events += "native_rollback"
            return rollbackResult
        }

        override suspend fun retireOwner(nativePartitionKey: String): RaftAvatarNativeMutationResult {
            retiredOwners += nativePartitionKey
            events += "native_retire_owner"
            return retireResult
        }

        override suspend fun retireSubject(
            nativePartitionKey: String,
            nativeSubjectKey: String
        ): RaftAvatarNativeMutationResult {
            retiredSubjects += nativePartitionKey to nativeSubjectKey
            events += "native_retire_subject"
            return retireResult
        }
    }

    private companion object {
        const val RemoteUrl = "https://images.example/avatar.png?v=1"
    }
}
