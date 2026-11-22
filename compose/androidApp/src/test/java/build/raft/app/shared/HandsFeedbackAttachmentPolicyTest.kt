package build.raft.app.shared

import build.hands.update.HandsFeedback
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HandsFeedbackAttachmentPolicyTest {
    // The boundary case allocates two ~200 MB files; let JUnit delete them so local
    // runs do not fill a tmpfs /tmp.
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun largeFileWithinPresignLimitIsNotRejectedByHost() {
        val directory = tmp.newFolder("hands-feedback-policy")
        val file = File(directory, "recording.mp4")
        RandomAccessFile(file, "rw").use { it.setLength(22_566_817L) }

        assertSame(file, HandsFeedbackAttachmentPolicy.requireValidFile(file))
    }

    @Test
    fun diagnosticsNeverDisplacesAUserAttachment() {
        val users = (1..HandsFeedback.MAX_ATTACHMENTS).map { File("user-$it.txt") }
        val diagnostics = File("diagnostics.zip")

        assertEquals(users, HandsFeedbackAttachmentPolicy.selectAttachments(users, diagnostics))
        assertEquals(
            users.take(HandsFeedback.MAX_ATTACHMENTS - 1) + diagnostics,
            HandsFeedbackAttachmentPolicy.selectAttachments(users.dropLast(1), diagnostics)
        )
    }

    @Test
    fun missingDiagnosticsFileAllowsRetryWithoutDiagnostics() {
        val missingDiagnostics = File("missing-diagnostics.zip")

        assertTrue(
            shouldRetryHandsFeedbackWithoutDiagnostics(
                FeedbackAttachmentReadException("Unable to read feedback attachment"),
                missingDiagnostics
            )
        )
    }
}
