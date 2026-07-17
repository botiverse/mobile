package build.raft.app.network

internal interface AttachmentDownloadFileOps {
    fun open(path: String): Any?

    fun write(handle: Any, bytes: ByteArray, offset: Int, length: Int): Int

    fun fsync(handle: Any): Boolean

    fun close(handle: Any): Boolean

    fun rename(sourcePath: String, targetPath: String): Boolean

    fun unlink(path: String)
}

/** Shared native sink state machine; platform actuals only translate errno/FileDescriptor APIs. */
internal class AttachmentDownloadAtomicFileSink(
    private val targetPath: String,
    attemptToken: String,
    private val ops: AttachmentDownloadFileOps
) : AttachmentDownloadAtomicSink {
    internal val stagingPath = "$targetPath.part-${attemptToken.safeAttachmentPathToken()}"
    private var handle: Any? = null
    private var prepared = false
    private var published = false
    private var ended = false

    override fun begin(): Boolean {
        if (handle != null || prepared || published || ended) return false
        runCatching { ops.unlink(stagingPath) }
        handle = runCatching { ops.open(stagingPath) }.getOrNull()
        return handle != null
    }

    override fun write(bytes: ByteArray): Boolean {
        val activeHandle = handle ?: return false
        var offset = 0
        while (offset < bytes.size) {
            val written =
                runCatching { ops.write(activeHandle, bytes, offset, bytes.size - offset) }
                    .getOrDefault(-1)
            if (written <= 0 || written > bytes.size - offset) return false
            offset += written
        }
        return true
    }

    override fun prepare(): Boolean {
        val activeHandle = handle ?: return false
        if (!runCatching { ops.fsync(activeHandle) }.getOrDefault(false)) return false
        // A close attempt owns this descriptor exactly once even if the platform reports failure.
        handle = null
        if (!runCatching { ops.close(activeHandle) }.getOrDefault(false)) return false
        prepared = true
        return true
    }

    override fun publish(): Boolean {
        if (!prepared || published) return false
        if (!runCatching { ops.rename(stagingPath, targetPath) }.getOrDefault(false)) return false
        // rename success is the publication linearization point. No post-check may reverse it.
        published = true
        ended = true
        return true
    }

    override fun abort() {
        if (ended) return
        ended = true
        val activeHandle = handle
        handle = null
        if (activeHandle != null) runCatching { ops.close(activeHandle) }
        if (!published) runCatching { ops.unlink(stagingPath) }
    }
}

internal fun String.safeAttachmentPathToken(): String =
    filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80).ifBlank { "attempt" }
