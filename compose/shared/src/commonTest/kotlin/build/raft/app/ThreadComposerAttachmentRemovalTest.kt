package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadComposerAttachmentRemovalTest {
    private val draftKey = "send=channel-a|parent=_|mention=channel-a"

    @Test
    fun blankIdentityOrUnknownRowFailsClosed() {
        val attachment = ownedAttachment("local-1", "file:///cache/local-1.jpg")

        assertRejected(
            resolveThreadComposerAttachmentRemoval(
                attachments = listOf(attachment),
                removedAttachmentIds = emptySet(),
                attachmentId = " ",
                draftKey = draftKey
            ),
            "blank_attachment_id"
        )
        assertRejected(
            resolveThreadComposerAttachmentRemoval(
                attachments = listOf(attachment),
                removedAttachmentIds = emptySet(),
                attachmentId = "missing",
                draftKey = draftKey
            ),
            "attachment_not_found"
        )
        assertRejected(
            resolveThreadComposerAttachmentRemoval(
                attachments = listOf(attachment),
                removedAttachmentIds = setOf(" local-1 "),
                attachmentId = "local-1",
                draftKey = draftKey
            ),
            "already_removed"
        )
        assertRejected(
            resolveThreadComposerAttachmentRemoval(
                attachments = listOf(attachment),
                removedAttachmentIds = emptySet(),
                attachmentId = "local-1",
                draftKey = " "
            ),
            "missing_draft_key"
        )
    }

    @Test
    fun nonComposerOwnedSourceRemovesRowWithoutRetiringRemoteRef() {
        val remote =
            ownedAttachment(
                id = "remote-1",
                fileRef = "https://cdn.example/remote.jpg",
                source = "server_attachment"
            )
        val external =
            ownedAttachment(
                id = "external-1",
                fileRef = "content://share/external.jpg",
                source = "external_share"
            )

        listOf(remote, external).forEach { attachment ->
            val result = resolveThreadComposerAttachmentRemoval(
                attachments = listOf(attachment),
                removedAttachmentIds = emptySet(),
                attachmentId = attachment.id,
                draftKey = draftKey
            )
            assertTrue(result.accepted, attachment.source)
            assertNull(result.fileRefToRetire)
        }
    }

    @Test
    fun malformedOwnedRefAndMediaContradictionAreRejected() {
        val malformedRefs =
            listOf(
                "",
                "   ",
                "content://picker/image.jpg",
                "https://example.test/image.jpg",
                "relative/image.jpg",
                "file://host/cache/image.jpg",
                "file:///cache/../outside.jpg",
                "file:///cache/image.jpg?token=1",
                "file:///cache/image\u0000.jpg"
            )
        malformedRefs.forEachIndexed { index, ref ->
            val result = resolveThreadComposerAttachmentRemoval(
                attachments = listOf(ownedAttachment("bad-$index", ref)),
                removedAttachmentIds = emptySet(),
                attachmentId = "bad-$index",
                draftKey = draftKey
            )
            assertFalse(result.accepted, "malformed ref $ref")
            assertNull(result.fileRefToRetire)
        }

        val contradictory =
            ownedAttachment(
                id = "video-mislabeled",
                fileRef = "file:///cache/clip.mp4",
                fileName = "clip.jpg",
                mimeType = "image/jpeg"
            )
        val result = resolveThreadComposerAttachmentRemoval(
            attachments = listOf(contradictory),
            removedAttachmentIds = emptySet(),
            attachmentId = contradictory.id,
            draftKey = draftKey
        )
        assertFalse(result.accepted)
        assertNull(result.fileRefToRetire)
    }

    @Test
    fun videoRowsRemainRemovableButNeverEnterImageRetirement() {
        val video =
            ownedAttachment(
                id = "video",
                fileRef = "file:///cache/clip.mp4",
                fileName = "clip.mp4",
                mimeType = "video/mp4"
            )
        val result = resolveThreadComposerAttachmentRemoval(
            attachments = listOf(video),
            removedAttachmentIds = emptySet(),
            attachmentId = video.id,
            draftKey = draftKey
        )
        assertTrue(result.accepted)
        assertNull(result.fileRefToRetire)
    }

    @Test
    fun siblingRefPreventsRetirementButStillAllowsCurrentRowRemoval() {
        val first = ownedAttachment("first", "file:///cache/shared.jpg")
        val sibling = ownedAttachment("sibling", " file:///cache/shared.jpg ")
        val result = resolveThreadComposerAttachmentRemoval(
            attachments = listOf(first, sibling),
            removedAttachmentIds = emptySet(),
            attachmentId = first.id,
            draftKey = draftKey
        )
        assertTrue(result.accepted)
        assertNull(result.fileRefToRetire)
    }

    @Test
    fun validOwnedImageReturnsNormalizedRefForRetirement() {
        val attachment = ownedAttachment("local-1", " file:///cache/local-1.jpg ")
        val result = resolveThreadComposerAttachmentRemoval(
            attachments = listOf(attachment),
            removedAttachmentIds = emptySet(),
            attachmentId = " local-1 ",
            draftKey = " $draftKey "
        )
        assertTrue(result.accepted)
        assertEquals("file:///cache/local-1.jpg", result.fileRefToRetire)
    }

    private fun assertRejected(result: ThreadComposerAttachmentRemoval, reason: String) {
        assertFalse(result.accepted, reason)
        assertEquals(reason, result.reason)
        assertNull(result.fileRefToRetire)
    }

    private fun ownedAttachment(
        id: String,
        fileRef: String,
        source: String = "platform_picker",
        fileName: String = "$id.jpg",
        mimeType: String = "image/jpeg"
    ): SharedThreadComposerAttachment =
        SharedThreadComposerAttachment(
            id = id,
            fileName = fileName,
            fileRef = fileRef,
            mimeType = mimeType,
            source = source,
            status = "uploading"
        )
}
