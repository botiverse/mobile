package build.raft.app

import build.raft.app.data.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AttachmentUploadLimitPolicyTest {
    @Test
    fun splitsFilesAtServerIssuedBoundary() {
        val decision =
            applyServerAttachmentUploadLimit(
                files =
                    listOf(
                        PlatformFile(id = "within", fileName = "within.bin", sizeBytes = 137L),
                        PlatformFile(id = "over", fileName = "over.bin", sizeBytes = 138L),
                        PlatformFile(id = "unknown", fileName = "unknown.bin", sizeBytes = null)
                    ),
                maxBytes = 137L
            )

        assertEquals(listOf("within", "unknown"), decision.accepted.map { it.id })
        assertEquals(listOf("over"), decision.rejected.map { it.id })
    }

    @Test
    fun servedBoundaryIsNotReplacedByAPlanConstant() {
        val decision =
            applyServerAttachmentUploadLimit(
                files = listOf(PlatformFile(id = "file", fileName = "file.bin", sizeBytes = 138L)),
                maxBytes = 137L
            )

        assertEquals(emptyList(), decision.accepted)
        assertEquals(138L, decision.largestRejectedBytes)
    }

    @Test
    fun nonPositiveServerBoundaryCannotEnterTheUploadPolicy() {
        assertFailsWith<IllegalArgumentException> {
            applyServerAttachmentUploadLimit(
                files = listOf(PlatformFile(id = "file", fileName = "file.bin", sizeBytes = 1L)),
                maxBytes = 0L
            )
        }
    }
}
