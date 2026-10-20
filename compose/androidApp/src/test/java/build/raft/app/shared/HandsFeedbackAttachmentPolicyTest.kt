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
    fun policyUsesHandsSdkLimits() {
        assertEquals(HandsFeedback.MAX_ATTACHMENTS, HandsFeedbackAttachmentPolicy.maxAttachments)
        assertEquals(HandsFeedback.MULTIPART_MAX_BYTES, HandsFeedbackAttachmentPolicy.multipartMaxBytes)
        assertEquals(HandsFeedback.PRESIGN_MAX_BYTES, HandsFeedbackAttachmentPolicy.maxAttachmentBytes)
    }

    @Test
    fun largeFileWithinPresignLimitIsNotRejectedByHost() {
        val directory = tmp.newFolder("hands-feedback-policy")
        val file = File(directory, "recording.mp4")
        RandomAccessFile(file, "rw").use { it.setLength(22_566_817L) }

        assertSame(file, HandsFeedbackAttachmentPolicy.requireValidFile(file))
    }

    @Test
    fun exactPresignBoundaryIsAcceptedAndOverLimitIsSizeFailure() {
        val directory = tmp.newFolder("hands-feedback-boundary")
        val exact = File(directory, "exact.mp4")
        val over = File(directory, "over.mp4")
        RandomAccessFile(exact, "rw").use { it.setLength(HandsFeedback.PRESIGN_MAX_BYTES) }
        RandomAccessFile(over, "rw").use { it.setLength(HandsFeedback.PRESIGN_MAX_BYTES + 1L) }

        assertSame(exact, HandsFeedbackAttachmentPolicy.requireValidFile(exact))
        val failure = expectFailure<FeedbackAttachmentTooLargeException> {
            HandsFeedbackAttachmentPolicy.requireValidFile(over)
        }
        assertEquals("settings_feedback_attachment_too_large", feedbackAttachmentPrepareErrorCode(failure))
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
    fun diagnosticsIsOmittedWhenAUserAttachmentUsesPresign() {
        val directory = tmp.newFolder("hands-feedback-diagnostics")
        val largeUserFile = File(directory, "recording.mp4")
        val diagnostics = File(directory, "diagnostics.zip")
        RandomAccessFile(largeUserFile, "rw").use {
            it.setLength(HandsFeedback.MULTIPART_MAX_BYTES + 1L)
        }
        diagnostics.writeText("diagnostics")

        assertEquals(
            listOf(largeUserFile),
            HandsFeedbackAttachmentPolicy.selectAttachments(listOf(largeUserFile), diagnostics)
        )
        assertFalse(HandsFeedbackAttachmentPolicy.canAttachDiagnostics(listOf(largeUserFile)))
    }

    @Test
    fun boundedCopyStreamsAndPreservesSizeSemantics() {
        val output = ByteArrayOutputStream()
        val copied = HandsFeedbackAttachmentPolicy.copyBounded(
            input = ByteArrayInputStream(ByteArray(1024) { 7 }),
            output = output,
            maxBytes = 1024L
        )
        assertEquals(1024L, copied)
        assertEquals(1024, output.size())

        val failure = expectFailure<FeedbackAttachmentTooLargeException> {
            HandsFeedbackAttachmentPolicy.copyBounded(
                input = ByteArrayInputStream(ByteArray(1025)),
                output = ByteArrayOutputStream(),
                maxBytes = 1024L
            )
        }
        assertEquals(1024L, failure.maxBytes)
    }

    @Test
    fun sdkAttachmentLimitErrorsRemainSizeErrors() {
        val failure = IllegalArgumentException("attachment recording.mp4 exceeds the 200 MB limit")
        assertEquals("settings_feedback_attachment_too_large", handsFeedbackSubmitErrorCode(failure))
        assertTrue(HandsFeedbackAttachmentPolicy.maxAttachmentBytes > HandsFeedbackAttachmentPolicy.multipartMaxBytes)
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

    @Test
    fun base64SizeEstimateHonorsPaddingAtTheBoundary() {
        assertEquals(1L, HandsFeedbackAttachmentPolicy.estimatedDecodedBase64Bytes("YQ=="))
        assertEquals(2L, HandsFeedbackAttachmentPolicy.estimatedDecodedBase64Bytes("YWI="))
        assertEquals(3L, HandsFeedbackAttachmentPolicy.estimatedDecodedBase64Bytes("YWJj"))
        assertEquals(3L, HandsFeedbackAttachmentPolicy.estimatedDecodedBase64Bytes("YW\nJj\r\n"))
    }

    @Test
    fun base64SourceStreamsWithoutMaterializingAnEncodedByteArray() {
        val source = Base64StringInputStream("YWJj")
        val buffer = ByteArray(3)

        assertEquals(3, source.read(buffer, 0, buffer.size))
        assertEquals("YWJ", buffer.decodeToString())
        assertEquals('j'.code, source.read())
        assertEquals(-1, source.read())
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (throwable: Throwable) {
            if (throwable is T) return throwable
            throw throwable
        }
        fail("Expected ${T::class.java.simpleName}")
        error("unreachable")
    }
}
