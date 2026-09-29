package build.raft.app.data

import com.tencent.kmm.network.export.NetworkByteStream
import kotlinx.atomicfu.atomic

interface SlockPlatformFileResolver {
    suspend fun readFile(fileRef: String): SlockPlatformFileReadResult

    /** Opens and closes the real file handle without materializing its bytes. */
    suspend fun statFile(fileRef: String): SlockPlatformFileStatResult

    /** Every call owns a fresh platform source for one HTTP attempt. */
    suspend fun openFileStream(fileRef: String, expectedSizeBytes: Long): SlockPlatformFileChunkSource
}

/**
 * Result of the authority-bound, pre-create file admission used by direct attachment sessions.
 * The server create request must carry the length observed by the platform resolver; callers are
 * not allowed to substitute a picker hint or a partially materialized byte array for a
 * file-reference length.
 */
sealed interface SlockPlatformFilePreparationResult {
    data class Ready(val sizeBytes: Long) : SlockPlatformFilePreparationResult

    data class Rejected(
        val code: String,
        val retryable: Boolean = false,
    ) : SlockPlatformFilePreparationResult
}

data class SlockPlatformFileReadResult(
    val code: Int,
    val bytes: ByteArray,
    val sizeBytes: Long? = null,
    val errorCode: String = "",
    val message: String = ""
) {
    val success: Boolean
        get() = code in 200..299 && bytes.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as SlockPlatformFileReadResult
        return code == other.code &&
            bytes.contentEquals(other.bytes) &&
            sizeBytes == other.sizeBytes &&
            errorCode == other.errorCode &&
            message == other.message
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + (sizeBytes?.hashCode() ?: 0)
        result = 31 * result + errorCode.hashCode()
        result = 31 * result + message.hashCode()
        return result
    }
}

data class SlockPlatformFileStatResult(
    val code: Int,
    val sizeBytes: Long? = null,
    val errorCode: String = "",
    val message: String = ""
) {
    val success: Boolean
        get() = code in 200..299 && sizeBytes != null && sizeBytes > 0L
}

const val PlatformFileStreamChunkBytes: Int = 64 * 1024

interface SlockPlatformFileChunkSource {
    suspend fun read(maxBytes: Int): ByteArray
    fun close()
}

fun boundedPlatformFileNetworkStream(
    expectedSizeBytes: Long,
    source: SlockPlatformFileChunkSource
): NetworkByteStream {
    val closed = atomic(false)
    fun closeOnce() {
        if (closed.compareAndSet(expect = false, update = true)) {
            runCatching { source.close() }
        }
    }
    return NetworkByteStream(
        contentLength = expectedSizeBytes,
        readAllBlock = {
            closeOnce()
            error("Whole-file attachment reads are disabled")
        },
        cancelBlock = ::closeOnce,
        attemptCancelBlock = ::closeOnce,
        readChunksBlock = { sink ->
            var transferred = 0L
            try {
                while (transferred < expectedSizeBytes) {
                    val remaining = expectedSizeBytes - transferred
                    val requested = minOf(PlatformFileStreamChunkBytes.toLong(), remaining).toInt()
                    val bytes = source.read(requested)
                    if (bytes.isEmpty()) error("Attachment ended before its declared size")
                    if (bytes.size > requested) error("Attachment chunk exceeded its bounded read size")
                    transferred += bytes.size.toLong()
                    sink.write(bytes)
                }
                if (source.read(1).isNotEmpty()) {
                    error("Attachment exceeded its declared size")
                }
            } finally {
                closeOnce()
            }
        }
    )
}

object SlockSharedPlatformFileResolverProvider {
    private var appScopeResolverFactory: (() -> SlockPlatformFileResolver)? = null

    fun installAppScopeResolver(factory: () -> SlockPlatformFileResolver) {
        appScopeResolverFactory = factory
    }

    internal fun createBinding(): SlockPlatformFileResolverBinding {
        val appScopeResolver = appScopeResolverFactory?.invoke()
        if (appScopeResolver != null) {
            return SlockPlatformFileResolverBinding(appScopeResolver, "app")
        }
        val platformResolver = createDefaultPlatformFileResolver()
        return SlockPlatformFileResolverBinding(platformResolver, if (platformResolver == null) "missing" else "platform")
    }

    internal fun clearAppScopeResolverForTests() {
        appScopeResolverFactory = null
    }
}

internal data class SlockPlatformFileResolverBinding(
    val resolver: SlockPlatformFileResolver?,
    val scope: String
)

internal expect fun createDefaultPlatformFileResolver(): SlockPlatformFileResolver?
