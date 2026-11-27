package build.raft.app

import build.raft.app.thread.resolveThreadComposerAttachmentRemoval
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadComposerAttachmentRemovalTest {
    private val draftKey = "send=channel-a|parent=_|mention=channel-a"

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
