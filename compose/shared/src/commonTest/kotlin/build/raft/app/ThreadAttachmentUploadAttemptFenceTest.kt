package build.raft.app

import build.raft.app.data.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadAttachmentUploadAttemptFenceTest {
    @Test
    fun compositeHandleMapKeepsSameLocalIdIsolatedAcrossDrafts() {
        val aIdentity = requireNotNull(threadAttachmentUploadAttemptIdentity(" reused-id ", " draft-a "))
        val bIdentity = requireNotNull(threadAttachmentUploadAttemptIdentity("reused-id", "draft-b"))
        val aFile = platformFile("file:///cache/a.jpg")
        val bFile = platformFile("file:///cache/b.jpg")
        val handles = mapOf(aIdentity to aFile, bIdentity to bFile)

        assertEquals(2, handles.size)
        assertEquals(aFile, handles.threadAttachmentUploadFile("reused-id", "draft-a"))
        assertEquals(bFile, handles.threadAttachmentUploadFile("reused-id", "draft-b"))
        assertNull(handles.threadAttachmentUploadFile("reused-id", "draft-c"))
        assertNull(handles.threadAttachmentUploadFile("reused-id", null))
    }

    @Test
    fun attachmentAndDraftRetirementPreserveSameIdHandleOwnedByDraftB() {
        val aIdentity = requireNotNull(threadAttachmentUploadAttemptIdentity("reused-id", "draft-a"))
        val bIdentity = requireNotNull(threadAttachmentUploadAttemptIdentity("reused-id", "draft-b"))
        val aSiblingIdentity = requireNotNull(threadAttachmentUploadAttemptIdentity("a-sibling", "draft-a"))
        val aFile = platformFile("file:///cache/a.jpg")
        val bFile = platformFile("file:///cache/b.jpg")
        val handles =
            mapOf(
                aIdentity to aFile,
                bIdentity to bFile,
                aSiblingIdentity to platformFile("file:///cache/a-sibling.jpg")
            )

        val oneRemoved = handles.withoutThreadAttachmentUploadFile("reused-id", "draft-a")
        assertNull(oneRemoved.threadAttachmentUploadFile("reused-id", "draft-a"))
        assertEquals(bFile, oneRemoved.threadAttachmentUploadFile("reused-id", "draft-b"))
        assertTrue(aSiblingIdentity in oneRemoved)

        val draftRetired = handles.withoutThreadAttachmentUploadDraft(" draft-a ")
        assertEquals(mapOf(bIdentity to bFile), draftRetired)
        assertEquals(handles, handles.withoutThreadAttachmentUploadDraft(" "))
    }

    @Test
    fun currentAttemptIsAcceptedUntilItCompletes() {
        val admission =
            requireNotNull(
                ThreadAttachmentUploadAttemptFence().begin(
                    localAttachmentId = " picked-local-1 ",
                    draftKey = " draft-a "
                )
            )

        assertEquals("picked-local-1", admission.localAttachmentId)
        assertEquals("draft-a", admission.draftKey)
        assertTrue(admission.fence.accepts("picked-local-1", "draft-a", admission.attemptToken))
        assertFalse(
            admission.fence
                .complete("picked-local-1", "draft-a", admission.attemptToken)
                .accepts("picked-local-1", "draft-a", admission.attemptToken)
        )
    }

    @Test
    fun nullOrBlankDraftKeyCannotBegin() {
        val fence = ThreadAttachmentUploadAttemptFence()

        assertNull(fence.begin("picked-local-1", null))
        assertNull(fence.begin("picked-local-1", "   "))
        assertNull(fence.begin("   ", "draft-a"))
        assertEquals(emptySet(), fence.activeAttemptIds())
        assertEquals(0L, fence.nextAttemptToken)
    }

    @Test
    fun failedAttemptRejectsLateCompletionAndRetryUsesANewToken() {
        val first = requireNotNull(ThreadAttachmentUploadAttemptFence().begin("picked-local-1", "draft-a"))
        val failed = first.fence.terminate("draft-a", setOf("picked-local-1"))

        assertFalse(failed.accepts("picked-local-1", "draft-a", first.attemptToken))

        val retry = requireNotNull(failed.begin("picked-local-1", "draft-a"))
        assertNotEquals(first.attemptToken, retry.attemptToken)
        assertTrue(retry.fence.accepts("picked-local-1", "draft-a", retry.attemptToken))
        assertFalse(retry.fence.accepts("picked-local-1", "draft-a", first.attemptToken))
    }

    @Test
    fun sameAttachmentIdInDifferentDraftsDoesNotCrossWire() {
        val first = requireNotNull(ThreadAttachmentUploadAttemptFence().begin("reused-id", "draft-a"))
        val second = requireNotNull(first.fence.begin("reused-id", "draft-b"))

        assertTrue(second.fence.accepts("reused-id", "draft-a", first.attemptToken))
        assertTrue(second.fence.accepts("reused-id", "draft-b", second.attemptToken))
        assertFalse(second.fence.accepts("reused-id", "draft-a", second.attemptToken))
        assertFalse(second.fence.accepts("reused-id", "draft-b", first.attemptToken))
    }

    @Test
    fun retireDraftOnlyRetiresDraftAAndLeavesDraftBActive() {
        val first = requireNotNull(ThreadAttachmentUploadAttemptFence().begin("reused-id", "draft-a"))
        val second = requireNotNull(first.fence.begin("reused-id", "draft-b"))
        val retired = second.fence.retireDraft(" draft-a ")

        assertFalse(retired.accepts("reused-id", "draft-a", first.attemptToken))
        assertTrue(retired.accepts("reused-id", "draft-b", second.attemptToken))
        assertEquals(setOf("reused-id"), retired.activeAttemptIds("draft-b"))
        assertEquals(emptySet(), retired.activeAttemptIds("draft-a"))
    }

    @Test
    fun siblingFailureTerminatesEveryActiveAttemptInOneDraft() {
        val first = requireNotNull(ThreadAttachmentUploadAttemptFence().begin("picked-local-1", "draft-a"))
        val second = requireNotNull(first.fence.begin("picked-local-2", "draft-a"))
        val failedBatch = second.fence.terminate("draft-a", second.fence.activeAttemptIds("draft-a"))

        assertFalse(failedBatch.accepts("picked-local-1", "draft-a", first.attemptToken))
        assertFalse(failedBatch.accepts("picked-local-2", "draft-a", second.attemptToken))
    }

    @Test
    fun routeDisposalCannotContaminateASeparateDraftUpload() {
        val old = requireNotNull(ThreadAttachmentUploadAttemptFence().begin("picked-old-draft", "draft-a"))
        val disposed = old.fence.retireDraft("draft-a")
        val fresh = requireNotNull(disposed.begin("picked-new-draft", "draft-b"))

        assertFalse(fresh.fence.accepts("picked-old-draft", "draft-a", old.attemptToken))
        assertTrue(fresh.fence.accepts("picked-new-draft", "draft-b", fresh.attemptToken))
    }

    @Test
    fun removeTerminalRejectsCompletionWithoutAReplacementAttempt() {
        val admission = requireNotNull(ThreadAttachmentUploadAttemptFence().begin("picked-local-1", "draft-a"))
        val removed = admission.fence.terminate("draft-a", setOf("picked-local-1"))

        assertFalse(removed.accepts("picked-local-1", "draft-a", admission.attemptToken))
        assertFalse(
            removed
                .complete("picked-local-1", "draft-a", admission.attemptToken)
                .accepts("picked-local-1", "draft-a", admission.attemptToken)
        )
    }

    @Test
    fun lateProgressUsesBothDraftAndTokenFence() {
        val first = requireNotNull(ThreadAttachmentUploadAttemptFence().begin("external-share-local", "draft-a"))
        val second = requireNotNull(first.fence.begin("external-share-local", "draft-b"))

        assertFalse(second.fence.accepts("external-share-local", "draft-b", first.attemptToken))
        assertFalse(second.fence.accepts("external-share-local", "draft-a", second.attemptToken))
        assertTrue(second.fence.accepts("external-share-local", "draft-b", second.attemptToken))
    }

    private fun platformFile(fileRef: String): PlatformFile =
        PlatformFile(
            id = "reused-id",
            fileName = fileRef.substringAfterLast('/'),
            mimeType = "image/jpeg",
            fileRef = fileRef,
            source = "platform_picker"
        )
}
