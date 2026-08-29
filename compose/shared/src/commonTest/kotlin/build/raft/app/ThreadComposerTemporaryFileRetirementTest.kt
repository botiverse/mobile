package build.raft.app

import build.raft.app.data.InMemorySlockKeyValueStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun siblingClaimPublishedBeforeRetirementFenceSkipsHandler() {
        val storage = InMemorySlockKeyValueStore()
        val fake = FakeRetirementHandler()
        val ref = "cache/shared.jpg"
        fake.files[ref] = "shared"
        val firstKey = "send=first|parent=_|mention=first"
        val secondKey = "send=second|parent=_|mention=second"
        val attachment = ownedAttachment(ref)
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
                    second.save(secondKey, "successor", asTask = false, uploadedAttachments = listOf(attachment))
                }
            )
        first.save(firstKey, "first", asTask = false, uploadedAttachments = listOf(attachment))

        first.clear(firstKey)

        assertTrue(fake.calls.isEmpty())
        assertEquals(ref, second.read(secondKey)?.uploadedAttachments?.single()?.fileRef)
        assertEquals("shared", fake.files[ref])
    }

    @Test
    fun lateClearUsesDurableOwnershipAndAllowsSuccessorRefReuse() {
        val storage = InMemorySlockKeyValueStore()
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
