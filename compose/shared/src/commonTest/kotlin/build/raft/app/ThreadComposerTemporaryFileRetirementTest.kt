package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadComposerTemporaryFileRetirementTest {
    @BeforeTest
    fun resetRegistryBeforeTest() {
        ThreadComposerTemporaryFileRetirement.resetForTests()
    }

    @AfterTest
    fun resetRegistryAfterTest() {
        ThreadComposerTemporaryFileRetirement.resetForTests()
    }

    @Test
    fun missingHandlerFailsClosedAndLeavesOriginalUnchanged() {
        val result =
            ThreadComposerTemporaryFileRetirement.retire(
                sessionId = "session-a",
                fileRefs = setOf("cache/a.jpg")
            )

        assertEquals(setOf("cache/a.jpg"), result.requestedFileRefs)
        assertEquals(setOf("cache/a.jpg"), result.failedFileRefs)
        assertEquals(0, result.admittedCount)
        assertEquals(0, result.quarantinedCount)
        assertEquals(
            ThreadComposerTemporaryFileRetirementResult.MissingHandlerCode,
            result.errorCode
        )
        assertTrue(result.failClosed)
    }

    @Test
    fun handlerIsCalledSynchronouslyAndReturnsPerRefQuarantineReceipt() {
        val calls = mutableListOf<Pair<String, Set<String>>>()
        ThreadComposerTemporaryFileRetirement.install { sessionId, refs ->
            calls += sessionId to refs
            ThreadComposerTemporaryFileRetirementResult.quarantined(sessionId, refs)
        }

        val result =
            ThreadComposerTemporaryFileRetirement.retire(
                sessionId = "session-sync",
                fileRefs = linkedSetOf("cache/a.jpg", "cache/b.jpg")
            )

        // The call list is observed immediately after retire returns: no queued
        // coroutine/renderer dispatch is involved in the ownership transition.
        assertEquals(listOf("session-sync" to setOf("cache/a.jpg", "cache/b.jpg")), calls)
        assertEquals(setOf("cache/a.jpg", "cache/b.jpg"), result.admittedFileRefs)
        assertEquals(setOf("cache/a.jpg", "cache/b.jpg"), result.quarantinedFileRefs)
        assertTrue(result.succeeded)
    }

    @Test
    fun delayedTombstoneDrainCannotDeleteSuccessorUsingSamePath() {
        val fake = FakeRetirementHandler()
        val ref = "cache/reused.jpg"
        fake.files[ref] = "owner-a"
        ThreadComposerTemporaryFileRetirement.install(fake)

        val retired = ThreadComposerTemporaryFileRetirement.retire("session-a", setOf(ref))

        assertTrue(retired.succeeded)
        assertFalse(ref in fake.files)
        assertEquals("owner-a", fake.tombstones.single().second)

        // A successor is allowed to allocate the now-vacant path.  Drain is
        // scoped to the exact tombstone receipt, never to the original ref.
        fake.files[ref] = "owner-b"
        fake.drainTombstones()

        assertEquals("owner-b", fake.files[ref])
        assertTrue(fake.tombstones.isEmpty())
    }

    @Test
    fun failedRenameReportsFailureAndPreservesOriginal() {
        val fake = FakeRetirementHandler(failingRefs = setOf("cache/keep.jpg"))
        val ref = "cache/keep.jpg"
        fake.files[ref] = "bytes"
        ThreadComposerTemporaryFileRetirement.install(fake)

        val result = ThreadComposerTemporaryFileRetirement.retire("session-failure", setOf(ref))

        assertEquals(setOf(ref), result.requestedFileRefs)
        assertEquals(setOf(ref), result.admittedFileRefs)
        assertEquals(emptySet(), result.quarantinedFileRefs)
        assertEquals(setOf(ref), result.failedFileRefs)
        assertEquals(ThreadComposerTemporaryFileRetirementResult.HandlerFailureCode, result.errorCode)
        assertEquals("bytes", fake.files[ref])
        assertTrue(fake.tombstones.isEmpty())
    }

    @Test
    fun retirementFenceRunsCallbackOutsideLockAndRejectsSiblingClaimsThenReleases() = runBlocking {
        val storage = InMemoryRaftKeyValueStore()
        val fake = FakeRetirementHandler()
        val ref = "cache/shared.jpg"
        fake.files[ref] = "shared"
        val firstKey = "send=first|parent=_|mention=first"
        val secondKey = "send=second|parent=_|mention=second"
        val unrelatedKey = "send=unrelated|parent=_|mention=unrelated"
        val attachment = ownedAttachment(ref)
        val externalSource = SharedThreadComposerExternalShareSource("token", "account", "server")
        val externalAttachment =
            externalShareAttachment(status = "queued").copy(fileRef = ref)
        val callbackEntered = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        val second =
            SharedThreadComposerDraftPersistence(
                store = storage,
                temporaryFileRetirement = fake
            )
        val first =
            SharedThreadComposerDraftPersistence(
                store = storage,
                temporaryFileRetirement = fake,
                beforeTemporaryFileRefsRetirementFence = {
                    // Hold the synchronous platform callback open while the test exercises
                    // another thread. If the preference lock were still held here, that thread
                    // could not complete the unrelated save below.
                    callbackEntered.complete(Unit)
                    runBlocking { releaseCallback.await() }
                }
            )
        first.save(firstKey, "first", asTask = false, uploadedAttachments = listOf(attachment))

        val clearJob = async(Dispatchers.Default) { runCatching { first.clear(firstKey) } }
        assertTrue(
            withTimeoutOrNull(5_000) { callbackEntered.await(); true } == true,
            "retirement callback did not enter"
        )
        val unrelatedSaveJob =
            async(Dispatchers.Default) {
                runCatching {
                    second.save(unrelatedKey, "unrelated", asTask = false)
                }
            }
        val saveJob =
            async(Dispatchers.Default) {
                runCatching {
                    second.save(
                        secondKey,
                        "successor",
                        asTask = false,
                        uploadedAttachments = listOf(attachment)
                    )
                }
            }
        val stageJob =
            async(Dispatchers.Default) {
                runCatching {
                    second.stageExternalShare(
                        secondKey,
                        "incoming",
                        listOf(externalAttachment),
                        externalSource
                    )
                }
            }
        try {
            assertTrue(
                withTimeoutOrNull(5_000) { unrelatedSaveJob.await().isSuccess } == true,
                "retirement callback held the preference lock"
            )
            assertTrue(
                withTimeoutOrNull(5_000) {
                    saveJob.await().exceptionOrNull()
                        ?.let { assertIs<SharedThreadComposerDraftPersistenceFailure>(it) }
                        ?.code == "temporary_file_retirement_in_progress"
                } == true,
                "sibling save was not fenced during retirement callback"
            )
            assertTrue(
                withTimeoutOrNull(5_000) {
                    stageJob.await().exceptionOrNull()
                        ?.let { assertIs<SharedThreadComposerDraftPersistenceFailure>(it) }
                        ?.code == "temporary_file_retirement_in_progress"
                } == true,
                "external-share claim was not fenced during retirement callback"
            )
        } finally {
            releaseCallback.complete(Unit)
        }
        assertTrue(clearJob.await().isSuccess)
        assertEquals(1, fake.calls.size)
        assertFalse(ref in fake.files)
        assertNull(second.read(secondKey))

        // The reservation is released after the callback/handler returns. A successor may now
        // claim the vacant path, and that claim is not removed by the old tombstone drain.
        fake.files[ref] = "successor"
        second.save(secondKey, "successor", asTask = false, uploadedAttachments = listOf(attachment))
        assertEquals(ref, second.read(secondKey)?.uploadedAttachments?.single()?.fileRef)
        assertEquals("successor", fake.files[ref])
    }

    @Test
    fun retirementCallbackFailureRollsBackWithoutRestoringDanglingReference() {
        val storage = InMemoryRaftKeyValueStore()
        val fake = FakeRetirementHandler()
        val ref = "cache/callback-failure.jpg"
        fake.files[ref] = "shared"
        val firstKey = "send=callback-failure|parent=_|mention=callback-failure"
        val secondKey = "send=callback-successor|parent=_|mention=callback-successor"
        val attachment = ownedAttachment(ref)
        val first =
            SharedThreadComposerDraftPersistence(
                store = storage,
                temporaryFileRetirement = fake,
                beforeTemporaryFileRefsRetirementFence = {
                    error("retirement callback failed")
                }
            )
        val second = SharedThreadComposerDraftPersistence(store = storage, temporaryFileRetirement = fake)
        first.save(firstKey, "first", asTask = false, uploadedAttachments = listOf(attachment))

        val failure = assertFailsWith<SharedThreadComposerDraftPersistenceFailure> {
            first.clear(firstKey)
        }

        assertEquals("temporary_file_retirement_handler_failed", failure.code)
        assertTrue(fake.calls.isEmpty())
        assertEquals("shared", fake.files[ref])
        val restored = first.read(firstKey)
        assertEquals("first", restored?.text)
        assertTrue(restored?.uploadedAttachments.orEmpty().none { it.fileRef == ref })

        // The fence is released even when the callback throws; a new owner can safely claim the
        // same path after rollback.
        second.save(secondKey, "successor", asTask = false, uploadedAttachments = listOf(attachment))
        assertEquals(ref, second.read(secondKey)?.uploadedAttachments?.single()?.fileRef)
    }

    @Test
    fun partialRetirementFailureRollsBackWithoutDanglingMovedOrMissingReferences() {
        val storage = InMemoryRaftKeyValueStore()
        val refMoved = "cache/partial-moved.jpg"
        val refMissing = "cache/partial-missing.jpg"
        val fake = FakeRetirementHandler()
        fake.files[refMoved] = "moved"
        ThreadComposerTemporaryFileRetirement.install(fake)
        val key = "send=partial|parent=_|mention=partial"
        val persistence =
            SharedThreadComposerDraftPersistence(
                store = storage,
                temporaryFileRetirement = fake
            )
        persistence.save(
            key,
            "body",
            asTask = false,
            uploadedAttachments = listOf(ownedAttachment(refMoved), ownedAttachment(refMissing))
        )

        val failure = assertFailsWith<SharedThreadComposerDraftPersistenceFailure> {
            persistence.clear(key)
        }

        assertEquals("temporary_file_retirement_handler_failed", failure.code)
        assertTrue(fake.calls.single().second == setOf(refMoved, refMissing))
        assertFalse(refMoved in fake.files)
        assertFalse(refMissing in fake.files)
        val restored = persistence.read(key)
        assertEquals("body", restored?.text)
        assertTrue(restored?.uploadedAttachments.orEmpty().isEmpty())
    }

    @Test
    fun lateClearUsesDurableOwnershipAndAllowsSuccessorRefReuse() {
        val storage = InMemoryRaftKeyValueStore()
        val fake = FakeRetirementHandler()
        val ref = "cache/late.jpg"
        fake.files[ref] = "owner-a"
        val keyA = "send=a|parent=_|mention=a"
        val keyB = "send=b|parent=_|mention=b"
        val attachment = ownedAttachment(ref)
        val persistence =
            SharedThreadComposerDraftPersistence(
                store = storage,
                temporaryFileRetirement = fake
            )
        persistence.save(keyA, "a", asTask = false, uploadedAttachments = listOf(attachment))
        persistence.clear(keyA)
        assertEquals(1, fake.calls.size)

        // The original path is now free and may be reused by a new durable
        // owner.  A repeated late clear for A has no durable row to retire.
        fake.files[ref] = "owner-b"
        persistence.save(keyB, "b", asTask = false, uploadedAttachments = listOf(attachment))
        persistence.clear(keyA)
        fake.drainTombstones()

        assertEquals(1, fake.calls.size)
        assertEquals("owner-b", fake.files[ref])
    }

    private fun ownedAttachment(fileRef: String): SharedThreadComposerAttachment =
        SharedThreadComposerAttachment(
            id = "owned-${fileRef.substringAfterLast('/')}",
            fileName = "image.jpg",
            mimeType = "image/jpeg",
            status = "ready",
            fileRef = fileRef,
            source = "composer_image_compressor"
        )

    private fun externalShareAttachment(status: String): SharedThreadComposerAttachment =
        SharedThreadComposerAttachment(
            id = "external-${status}",
            fileName = "image.jpg",
            sizeBytes = 100L,
            mimeType = "image/jpeg",
            status = status,
            fileRef = "private://image",
            source = "external_share",
            externalShareToken = "token",
            externalShareAccountId = "account",
            externalShareServerId = "server"
        )

    private class FakeRetirementHandler(
        private val failingRefs: Set<String> = emptySet()
    ) : ThreadComposerTemporaryFileRetirementHandler {
        val files = linkedMapOf<String, String>()
        val tombstones = mutableListOf<Pair<String, String>>()
        val calls = mutableListOf<Pair<String, Set<String>>>()

        override fun retire(
            sessionId: String,
            fileRefs: Set<String>
        ): ThreadComposerTemporaryFileRetirementResult {
            calls += sessionId to fileRefs
            val admitted = fileRefs.toSet()
            val failed = fileRefs intersect failingRefs
            val quarantined = admitted - failed
            val tombstoneByRef = linkedMapOf<String, String>()
            quarantined.forEachIndexed { index, ref ->
                val bytes = files.remove(ref) ?: return@forEachIndexed
                val tombstone = "$ref.retired-${tombstones.size + index}"
                tombstones += tombstone to bytes
                tombstoneByRef[ref] = tombstone
            }
            val missing = quarantined.filterTo(linkedSetOf()) { ref -> ref !in tombstoneByRef }
            return ThreadComposerTemporaryFileRetirementResult(
                sessionId = sessionId,
                requestedFileRefs = fileRefs,
                admittedFileRefs = admitted,
                quarantinedFileRefs = quarantined - missing,
                failedFileRefs = failed + missing,
                tombstoneByFileRef = tombstoneByRef,
                errorCode = (failed + missing).takeIf { it.isNotEmpty() }?.let {
                    ThreadComposerTemporaryFileRetirementResult.HandlerFailureCode
                }
            )
        }

        fun drainTombstones() {
            tombstones.clear()
        }
    }
}
