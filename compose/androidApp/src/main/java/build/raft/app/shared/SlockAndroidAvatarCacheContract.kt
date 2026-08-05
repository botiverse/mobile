package build.raft.app.shared

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONObject

internal object SlockAndroidAvatarCacheContract {
    const val Protocol = "slock_avatar_cache_v1"
    const val ImageModelPrefix = "slock-avatar-cache-v1:"
    const val ManagedSet = "typed_avatar_only"
    const val ManagedIndex = "opaque_partition_subject_bytes_v1"
    const val BudgetScope = "avatar_only"
    const val MaxEntryBytes = 5 * 1024 * 1024
    const val MaxManagedBytes = 64L * 1024L * 1024L
    const val ResultTimeoutMillis = 44_000L
    const val RollbackGraceMillis = 60_000L
    const val MaxRetainedOperations = 256
    private const val MaxDescriptorCharacters = 24 * 1024

    fun isManagedImageModel(source: String): Boolean = source.startsWith(ImageModelPrefix)

    fun parseImageModel(source: String): SlockAndroidAvatarDescriptorParse {
        if (!isManagedImageModel(source)) return SlockAndroidAvatarDescriptorParse.NotManaged
        if (source.length > ImageModelPrefix.length + MaxDescriptorCharacters) {
            return SlockAndroidAvatarDescriptorParse.Invalid
        }
        val encoded = source.substring(ImageModelPrefix.length)
        val json =
            runCatching {
                val decoded = Base64.getDecoder().decode(encoded)
                JSONObject(String(decoded, StandardCharsets.UTF_8))
            }.getOrNull() ?: return SlockAndroidAvatarDescriptorParse.Invalid
        return parseRequest(json)?.let(SlockAndroidAvatarDescriptorParse::Managed)
            ?: SlockAndroidAvatarDescriptorParse.Invalid
    }

    fun parseRequest(json: JSONObject): SlockAndroidAvatarRequest? {
        if (!acknowledgesRequestContract(json)) return null
        val remoteUrl = json.optString("remoteUrl").trim()
        if (!remoteUrl.isAdmittedRemoteImageUrl()) return null
        val partitionKey = json.optString("nativePartitionKey")
        val subjectKey = json.optString("nativeSubjectKey")
        val bytesKey = json.optString("nativeBytesKey")
        if (!partitionKey.isOpaqueKey() || !subjectKey.isOpaqueKey() || !bytesKey.isOpaqueKey()) return null
        val readMode = SlockAndroidAvatarReadMode.fromWire(json.optString("readMode")) ?: return null
        val generation = parseCommitGeneration(json) ?: return null
        val retryAtMillis =
            json.takeIf { it.has("retryAtMillis") }
                ?.flexibleLong("retryAtMillis")
                ?.takeIf { it >= 0L }
                ?: if (json.has("retryAtMillis")) return null else null
        return SlockAndroidAvatarRequest(
            remoteUrl = remoteUrl,
            nativePartitionKey = partitionKey,
            nativeSubjectKey = subjectKey,
            nativeBytesKey = bytesKey,
            readMode = readMode,
            retryAtMillis = retryAtMillis,
            eTag = json.optionalHeader("eTag"),
            lastModified = json.optionalHeader("lastModified"),
            commitGeneration = generation
        )
    }

    fun acknowledgesRequestContract(json: JSONObject): Boolean =
        json.optString("protocol") == Protocol &&
            json.optString("managedSet") == ManagedSet &&
            json.optString("managedIndex") == ManagedIndex &&
            json.optString("budgetScope") == BudgetScope &&
            json.optBoolean("indexedRetirementRequired", false) &&
            json.flexibleLong("maxEntryBytes") == MaxEntryBytes.toLong() &&
            json.flexibleLong("maxManagedBytes") == MaxManagedBytes &&
            json.keys().asSequence().none { it in forbiddenPayloadFields }

    private val forbiddenPayloadFields =
        setOf("path", "localPath", "filePath", "blob", "bytes", "data")

    fun parseOpaqueKey(json: JSONObject, field: String): String? =
        json.optString(field).takeIf { it.isOpaqueKey() }

    fun parseCommitGeneration(json: JSONObject): Long? =
        json.flexibleLong("commitGeneration")?.takeIf { it > 0L }

    fun responseContract(managedBytes: Long): MutableMap<String, Any> =
        linkedMapOf(
            "managedSet" to ManagedSet,
            "managedIndex" to ManagedIndex,
            "budgetScope" to BudgetScope,
            "indexedRetirement" to true,
            "managedBytes" to managedBytes.coerceIn(0L, MaxManagedBytes)
        )

    private fun JSONObject.optionalHeader(field: String): String? =
        optString(field)
            .trim()
            .takeIf { it.isNotEmpty() && it.length <= 2048 && it.none(Char::isISOControl) }

    private fun JSONObject.flexibleLong(field: String): Long? {
        val value = opt(field) ?: return null
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun String.isAdmittedRemoteImageUrl(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null
    }

    internal fun String.isOpaqueKey(): Boolean =
        length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
}

internal sealed interface SlockAndroidAvatarDescriptorParse {
    data object NotManaged : SlockAndroidAvatarDescriptorParse
    data object Invalid : SlockAndroidAvatarDescriptorParse
    data class Managed(val request: SlockAndroidAvatarRequest) : SlockAndroidAvatarDescriptorParse
}

internal enum class SlockAndroidAvatarReadMode(val wireValue: String) {
    RenderFresh("render_fresh"),
    RenderStaleRevalidate("render_stale_revalidate"),
    RenderStaleSuppressed("render_stale_suppressed"),
    Fetch("fetch"),
    Suppressed("suppressed");

    companion object {
        fun fromWire(value: String): SlockAndroidAvatarReadMode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

internal data class SlockAndroidAvatarEntryKey(
    val partitionKey: String,
    val subjectKey: String,
    val bytesKey: String
)

internal data class SlockAndroidAvatarSlotKey(
    val partitionKey: String,
    val subjectKey: String
)

internal data class SlockAndroidAvatarOperationKey(
    val entryKey: SlockAndroidAvatarEntryKey,
    val generation: Long
)

internal data class SlockAndroidAvatarRequest(
    val remoteUrl: String,
    val nativePartitionKey: String,
    val nativeSubjectKey: String,
    val nativeBytesKey: String,
    val readMode: SlockAndroidAvatarReadMode,
    val retryAtMillis: Long?,
    val eTag: String?,
    val lastModified: String?,
    val commitGeneration: Long
) {
    val entryKey =
        SlockAndroidAvatarEntryKey(
            partitionKey = nativePartitionKey,
            subjectKey = nativeSubjectKey,
            bytesKey = nativeBytesKey
        )
    val slotKey = SlockAndroidAvatarSlotKey(nativePartitionKey, nativeSubjectKey)
    val operationKey = SlockAndroidAvatarOperationKey(entryKey, commitGeneration)
}

internal data class SlockAndroidAvatarBytePayload(
    val bytes: ByteArray,
    val contentType: String?
)

internal data class SlockAndroidAvatarNetworkResponse(
    val statusCode: Int,
    val bytes: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val eTag: String? = null,
    val lastModified: String? = null,
    val exceededMaxBytes: Boolean = false
)

internal fun interface SlockAndroidAvatarNetworkFetcher {
    fun fetch(request: SlockAndroidAvatarRequest, hasAdmittedBytes: Boolean): SlockAndroidAvatarNetworkResponse
}

internal data class SlockAndroidAvatarLoadResult(
    val status: String,
    val sizeBytes: Long? = null,
    val eTag: String? = null,
    val lastModified: String? = null,
    val contentType: String? = null
) {
    companion object {
        val Rendered = SlockAndroidAvatarLoadResult("rendered")
        val Suppressed = SlockAndroidAvatarLoadResult("suppressed")
        val ResourceAbsent = SlockAndroidAvatarLoadResult("resource_absent")
        val InvalidContent = SlockAndroidAvatarLoadResult("invalid_content")
        val TemporaryTransport = SlockAndroidAvatarLoadResult("temporary_transport")

        fun success(response: SlockAndroidAvatarNetworkResponse): SlockAndroidAvatarLoadResult =
            SlockAndroidAvatarLoadResult(
                status = "success",
                sizeBytes = response.bytes.size.toLong(),
                eTag = response.eTag,
                lastModified = response.lastModified,
                contentType = response.contentType
            )

        fun notModified(response: SlockAndroidAvatarNetworkResponse): SlockAndroidAvatarLoadResult =
            SlockAndroidAvatarLoadResult(
                status = "not_modified",
                eTag = response.eTag,
                lastModified = response.lastModified
            )
    }

    fun toBridgeMap(managedBytes: Long): Map<String, Any> =
        SlockAndroidAvatarCacheContract.responseContract(managedBytes).apply {
            put("status", status)
            sizeBytes?.let { put("sizeBytes", it) }
            eTag?.let { put("eTag", it) }
            lastModified?.let { put("lastModified", it) }
            contentType?.let { put("contentType", it) }
        }
}

internal data class SlockAndroidAvatarInspection(
    val bytesAvailable: Boolean,
    val bytesDecodable: Boolean,
    val managedBytes: Long
)

internal sealed interface SlockAndroidAvatarMutation {
    data object Accepted : SlockAndroidAvatarMutation
    data class Failed(val code: String) : SlockAndroidAvatarMutation
}

internal data class SlockAndroidAvatarDecodeKey(
    val entryKey: SlockAndroidAvatarEntryKey,
    val requestWidth: Int,
    val requestHeight: Int,
    val needResize: Boolean,
    val scaleType: String
)

/** Small process-memory LRU used by the existing image adapter for decoded avatars only. */
internal class SlockAvatarDecodedMemoryCache<V : Any>(
    private val maxWeight: Int,
    private val weightOf: (V) -> Int
) {
    private val lock = Any()
    private val entries = LinkedHashMap<SlockAndroidAvatarDecodeKey, V>(16, 0.75f, true)
    private var currentWeight = 0

    init {
        require(maxWeight > 0)
    }

    fun get(key: SlockAndroidAvatarDecodeKey): V? = synchronized(lock) { entries[key] }

    fun put(key: SlockAndroidAvatarDecodeKey, value: V) {
        val weight = weightOf(value).coerceAtLeast(1)
        synchronized(lock) {
            entries.remove(key)?.let { currentWeight -= weightOf(it).coerceAtLeast(1) }
            if (weight > maxWeight) return
            entries[key] = value
            currentWeight += weight
            val iterator = entries.entries.iterator()
            while (currentWeight > maxWeight && iterator.hasNext()) {
                currentWeight -= weightOf(iterator.next().value).coerceAtLeast(1)
                iterator.remove()
            }
        }
    }

    fun removeEntry(entryKey: SlockAndroidAvatarEntryKey) = removeWhere { it.entryKey == entryKey }

    fun removeOwner(partitionKey: String) = removeWhere { it.entryKey.partitionKey == partitionKey }

    fun removeSubject(partitionKey: String, subjectKey: String) =
        removeWhere { it.entryKey.partitionKey == partitionKey && it.entryKey.subjectKey == subjectKey }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            currentWeight = 0
        }
    }

    internal fun size(): Int = synchronized(lock) { entries.size }

    private fun removeWhere(predicate: (SlockAndroidAvatarDecodeKey) -> Boolean) {
        synchronized(lock) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (predicate(entry.key)) {
                    currentWeight -= weightOf(entry.value).coerceAtLeast(1)
                    iterator.remove()
                }
            }
        }
    }
}

internal fun interface SlockAvatarCacheWorkExecutor {
    fun execute(block: () -> Unit)
}

internal fun interface SlockAvatarCacheCancellation {
    fun cancel()
}

internal fun interface SlockAvatarCacheTimeoutScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): SlockAvatarCacheCancellation
}
