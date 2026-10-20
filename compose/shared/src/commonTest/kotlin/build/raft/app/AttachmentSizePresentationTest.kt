package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentSizePresentationTest {

    @Test
    fun uiFormatsRawBytesAtRenderBoundary() {
        val attachment = RaftThreadAttachmentItem(
            id = "a1",
            name = "file.bin",
            sizeBytes = 1536,
            sizeLabel = "legacy-label-must-not-win",
        )

        assertEquals("1.5 KB", attachment.displaySizeLabel())
        assertEquals("1.5 KB", attachment.genericAttachmentMetaLine())
    }

    @Test
    fun compatibilityLabelRemainsForOldSnapshotsWithoutRawBytes() {
        val attachment = RaftThreadAttachmentItem(
            id = "a1",
            name = "file.bin",
            sizeLabel = "12 KB",
        )

        assertEquals("12 KB", attachment.displaySizeLabel())
    }
}
