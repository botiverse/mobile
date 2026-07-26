package build.raft.app.data

import com.tencent.kmm.network.export.NetworkByteStream
import kotlinx.atomicfu.atomic

interface SlockPlatformFileResolver {
    suspend fun readFile(fileRef: String): SlockPlatformFileReadResult

    /** Opens and closes the real file handle without materializing its bytes. */
    suspend fun statFile(fileRef: String): SlockPlatformFileStatResult

    /** Every call owns a fresh platform source for one HTTP attempt. */
    suspend fun openFileStream(fileRef: String, expectedSizeBytes: Long): SlockPlatformFileChunkSource

    suspend fun persistImmutableFile(
        ownerKey: String,
        scopeKey: String,
        index: Int,
        bytes: ByteArray
    ): SlockPlatformFilePersistResult =
        SlockPlatformFilePersistResult(501, errorCode = "immutable_file_persist_unsupported")

    suspend fun retireImmutableScope(ownerKey: String, scopeKey: String): Boolean = false

    suspend fun retireImmutableOwner(ownerKey: String): Boolean = false

    suspend fun retireAllImmutableOwners(): Boolean = false
}

data class SlockPlatformFilePersistResult(
    val code: Int,
    val fileRef: String? = null,
    val errorCode: String = "",
    val message: String = ""
) {
    val success: Boolean
        get() = code in 200..299 && !fileRef.isNullOrBlank()
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

internal class ProductFeedbackImmutableCacheLifecycle(
    private val resolver: SlockPlatformFileResolver?,
    private val cleanupStore: ProductFeedbackCleanupStore = productFeedbackCleanupStore()
) {
    private var previousAccountId: String? = null

    suspend fun initialize(accountId: String?) {
        previousAccountId = accountId
        cleanupStore.drain(resolver)
        if (accountId == null) cleanupStore.retireAllDraftOwners(resolver)
    }

    suspend fun onSessionEvent(event: AuthSessionEvent, currentAccountId: String?) {
        when (event) {
            is AuthSessionEvent.Authenticated -> {
                val previous = previousAccountId
                if (previous != null && previous != currentAccountId) {
                    val ownerKey = productFeedbackImmutableOwnerKey(previous)
                    cleanupStore.retireOwner(previous, ownerKey) {
                        resolver?.retireImmutableOwner(ownerKey) ?: true
                    }
                }
                previousAccountId = currentAccountId
            }
            is AuthSessionEvent.Cleared -> {
                cleanupStore.retireAllDraftOwners(resolver)
                previousAccountId = null
            }
            is AuthSessionEvent.BindingMetadataChanged,
            is AuthSessionEvent.TokensRotated -> Unit
        }
    }
}

internal expect fun createDefaultPlatformFileResolver(): SlockPlatformFileResolver?
