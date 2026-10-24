package build.raft.app.shared

import build.hands.update.HandsFeedback
import build.hands.update.HandsFeedbackException
import build.raft.app.format.RaftFormat
import build.raft.app.format.RaftFormatLocale
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

internal object HandsFeedbackAttachmentPolicy {
    val maxAttachments: Int
        get() = HandsFeedback.MAX_ATTACHMENTS

    val multipartMaxBytes: Long
        get() = HandsFeedback.MULTIPART_MAX_BYTES

    val maxAttachmentBytes: Long
        get() = HandsFeedback.PRESIGN_MAX_BYTES

    fun canAttachDiagnostics(userFiles: List<File>): Boolean {
        val selectedUsers = userFiles.take(maxAttachments)
        return selectedUsers.size < maxAttachments &&
            selectedUsers.none { file -> file.length() > multipartMaxBytes }
    }

    fun selectAttachments(userFiles: List<File>, diagnosticsFile: File?): List<File> {
        val selectedUsers = userFiles.take(maxAttachments)
        return if (diagnosticsFile != null && canAttachDiagnostics(selectedUsers)) {
            selectedUsers + diagnosticsFile
        } else {
            selectedUsers
        }
    }

    fun requireValidFile(file: File): File {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) {
            throw FeedbackAttachmentReadException("Unable to read feedback attachment")
        }
        if (file.length() > maxAttachmentBytes) {
            throw FeedbackAttachmentTooLargeException(maxAttachmentBytes)
        }
        return file
    }

    fun copyBounded(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = maxAttachmentBytes
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) {
                throw FeedbackAttachmentTooLargeException(maxBytes)
            }
            output.write(buffer, 0, read)
        }
        if (total <= 0L) {
            throw FeedbackAttachmentReadException("Feedback attachment is empty")
        }
        return total
    }

    fun estimatedDecodedBase64Bytes(encoded: String): Long {
        if (encoded.isEmpty()) return 0L
        var symbolCount = 0L
        var trailingPadding = 0L
        encoded.forEach { character ->
            if (!character.isWhitespace()) {
                symbolCount += 1L
                trailingPadding = if (character == '=') trailingPadding + 1L else 0L
            }
        }
        return ((symbolCount * 3L) / 4L - trailingPadding).coerceAtLeast(0L)
    }
}

internal class Base64StringInputStream(
    private val encoded: String
) : InputStream() {
    private var position = 0

    override fun read(): Int {
        if (position >= encoded.length) return -1
        val character = encoded[position++]
        if (character.code > ASCII_MAX_CODE_POINT) {
            throw IOException("Feedback attachment base64 contains non-ASCII data")
        }
        return character.code
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (length == 0) return 0
        if (position >= encoded.length) return -1
        val count = minOf(length, encoded.length - position)
        for (index in 0 until count) {
            val character = encoded[position++]
            if (character.code > ASCII_MAX_CODE_POINT) {
                throw IOException("Feedback attachment base64 contains non-ASCII data")
            }
            buffer[offset + index] = character.code.toByte()
        }
        return count
    }

    private companion object {
        const val ASCII_MAX_CODE_POINT = 0x7F
    }
}

internal class FeedbackAttachmentTooLargeException(
    val maxBytes: Long
) : IOException("Feedback attachment exceeds ${RaftFormat.byteLimit(maxBytes, RaftFormatLocale.English)}")

internal class FeedbackAttachmentReadException(message: String) : IOException(message)

internal fun feedbackAttachmentPrepareErrorCode(throwable: Throwable): String =
    if (throwable is FeedbackAttachmentTooLargeException) {
        "settings_feedback_attachment_too_large"
    } else {
        "settings_feedback_attachment_read_failed"
    }

internal fun handsFeedbackSubmitErrorCode(throwable: Throwable): String {
    val httpCode = (throwable as? HandsFeedbackException)?.code
    val normalizedMessage = throwable.message.orEmpty().lowercase(Locale.US)
    return when {
        throwable is FeedbackAttachmentTooLargeException -> "settings_feedback_attachment_too_large"
        throwable is FeedbackAttachmentReadException -> "settings_feedback_attachment_read_failed"
        httpCode == 401 || httpCode == 403 -> "settings_feedback_auth_failed"
        httpCode == 429 -> "settings_feedback_rate_limited"
        httpCode == 413 -> "settings_feedback_attachment_too_large"
        throwable is IllegalArgumentException &&
            "attachment" in normalizedMessage &&
            ("exceed" in normalizedMessage || "too large" in normalizedMessage) ->
            "settings_feedback_attachment_too_large"
        httpCode == 400 && "too large" in normalizedMessage ->
            "settings_feedback_attachment_too_large"
        httpCode in 500..599 -> "settings_feedback_server_unavailable"
        throwable is IOException -> "settings_feedback_network_failed"
        else -> "settings_feedback_submit_failed"
    }
}

internal fun shouldRetryHandsFeedbackWithoutDiagnostics(
    throwable: Throwable,
    diagnosticsFile: File
): Boolean {
    val message = throwable.message.orEmpty()
    val httpCode = (throwable as? HandsFeedbackException)?.code
    val diagnosticsUnavailable =
        !diagnosticsFile.isFile ||
            !diagnosticsFile.canRead() ||
            diagnosticsFile.length() !in 1L..HandsFeedbackAttachmentPolicy.maxAttachmentBytes
    val attachmentPreparationFailure =
        throwable is FeedbackAttachmentReadException ||
            throwable is FeedbackAttachmentTooLargeException
    return diagnosticsFile.name in message ||
        (diagnosticsUnavailable && attachmentPreparationFailure) ||
        httpCode == 400 ||
        httpCode == 413
}
