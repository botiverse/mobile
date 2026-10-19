package build.raft.app.data

import build.raft.app.host.HostAvatarCacheCapability
import build.raft.app.optBoolean
import build.raft.app.optLong
import build.raft.app.optString
import build.raft.app.slockSha256Hex
import build.raft.app.toSlockJsonString
import build.raft.app.withEntry
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val SLOCK_AVATAR_NATIVE_CACHE_PROTOCOL = "slock_avatar_cache_v1"
internal const val SLOCK_AVATAR_NATIVE_MANAGED_SET = "typed_avatar_only"
internal const val SLOCK_AVATAR_NATIVE_INDEX_CONTRACT = "opaque_partition_subject_bytes_v1"
internal const val SLOCK_AVATAR_NATIVE_BUDGET_SCOPE = "avatar_only"
private const val SLOCK_AVATAR_NATIVE_IMAGE_MODEL_PREFIX = "slock-avatar-cache-v1:"
internal const val SLOCK_AVATAR_NATIVE_INSPECTION_TIMEOUT_MILLIS = 1_500L
internal const val SLOCK_AVATAR_NATIVE_MUTATION_TIMEOUT_MILLIS = 5_000L
internal const val SLOCK_AVATAR_NATIVE_RESULT_TIMEOUT_MILLIS = 45_000L

internal enum class SlockAvatarNativeReadMode(val wireValue: String) {
    RenderFresh("render_fresh"),
    RenderStaleAndRevalidate("render_stale_revalidate"),
    RenderStaleSuppressed("render_stale_suppressed"),
    Fetch("fetch"),
    Suppressed("suppressed")
}

/**
 * The sole shared-to-native avatar image request.
 *
 * Raw environment/account/server/member identity never crosses this boundary. Native sees only
 * opaque partition/subject/bytes keys plus the remote transport URL and shared policy decision.
 * It must not return a local file path to shared code.
 */
internal data class SlockAvatarNativeRequest(
    val remoteUrl: String,
    val nativePartitionKey: String,
    val nativeSubjectKey: String,
    val nativeBytesKey: String,
    val readMode: SlockAvatarNativeReadMode,
    val retryAtMillis: Long?,
    val eTag: String?,
    val lastModified: String?,
    val expectedCommitIdentity: SlockAvatarCommitIdentity?,
    val committedToCommitIdentity: SlockAvatarCommitIdentity,
    val expectedModelRevision: Long,
    val newModelRevision: Long
) {
    init {
        require(remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://"))
        require(nativePartitionKey.isSlockAvatarOpaqueKey())
        require(nativeSubjectKey.isSlockAvatarOpaqueKey())
        require(nativeBytesKey.isSlockAvatarOpaqueKey())
        require((expectedCommitIdentity == null) == (expectedModelRevision == 0L))
        require(newModelRevision > expectedModelRevision)
        require(
            expectedCommitIdentity == null ||
                committedToCommitIdentity.commitGeneration > expectedCommitIdentity.commitGeneration
        )
        require(retryAtMillis == null || retryAtMillis >= 0L)
        require(expectedCommitIdentity != null || (eTag == null && lastModified == null))
    }

    val commitGeneration: Long
        get() = committedToCommitIdentity.commitGeneration

    @OptIn(ExperimentalEncodingApi::class)
    val imageModel: String
        get() =
            SLOCK_AVATAR_NATIVE_IMAGE_MODEL_PREFIX +
                Base64.encode(toJson().toSlockJsonString().encodeToByteArray())

    fun toJson(): JsonObject =
        buildJsonObject {
            put("protocol", SLOCK_AVATAR_NATIVE_CACHE_PROTOCOL)
            putSlockAvatarManagedSetContract()
            put("remoteUrl", remoteUrl)
            put("nativePartitionKey", nativePartitionKey)
            put("nativeSubjectKey", nativeSubjectKey)
            put("nativeBytesKey", nativeBytesKey)
            put("readMode", readMode.wireValue)
            put("commitGeneration", committedToCommitIdentity.commitGeneration.toString())
            put("commitNonce", committedToCommitIdentity.nonce)
            put("expectedModelRevision", expectedModelRevision.toString())
            put("modelRevision", newModelRevision.toString())
            retryAtMillis?.let { put("retryAtMillis", it.toString()) }
            eTag?.let { put("eTag", it) }
            lastModified?.let { put("lastModified", it) }
            expectedCommitIdentity?.let { expected ->
                put("expectedCommitGeneration", expected.commitGeneration.toString())
                put("expectedCommitNonce", expected.nonce)
            }
        }
}

/**
 * The platform's existing image owner may keep bytes wherever it already does, but every admitted
 * typed-avatar request must be recorded in this avatar-only opaque index. Owner/subject retirement
 * and the 64 MiB budget operate only on that managed set; the contract never authorizes scanning,
 * sizing, clearing, or limiting a platform's global image cache.
 */
internal fun JsonObjectBuilder.putSlockAvatarManagedSetContract() {
    put("managedSet", SLOCK_AVATAR_NATIVE_MANAGED_SET)
    put("managedIndex", SLOCK_AVATAR_NATIVE_INDEX_CONTRACT)
    put("budgetScope", SLOCK_AVATAR_NATIVE_BUDGET_SCOPE)
    put("indexedRetirementRequired", true)
    put("maxEntryBytes", SlockAvatarCachePolicy.MaxEntryBytes.toString())
    put("maxManagedBytes", SlockAvatarCachePolicy.MaxPlatformBytes.toString())
}

internal fun JsonObject.acknowledgesSlockAvatarManagedSet(): Boolean {
    if (optString("managedSet") != SLOCK_AVATAR_NATIVE_MANAGED_SET) return false
    if (optString("managedIndex") != SLOCK_AVATAR_NATIVE_INDEX_CONTRACT) return false
    if (optString("budgetScope") != SLOCK_AVATAR_NATIVE_BUDGET_SCOPE) return false
    if (!optBoolean("indexedRetirement", false)) return false
    val managedBytes =
        optString("managedBytes").toLongOrNull()
            ?: optLong("managedBytes", -1L)
    return managedBytes in 0L..SlockAvatarCachePolicy.MaxPlatformBytes
}

private fun String.isSlockAvatarOpaqueKey(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

internal sealed interface SlockAvatarNativeInspection {
    data object Unsupported : SlockAvatarNativeInspection
    data class Supported(
        val bytesAvailable: Boolean,
        val bytesDecodable: Boolean,
        val managedBytes: Long
    ) : SlockAvatarNativeInspection
}

internal sealed interface SlockAvatarNativeLoadResult {
    /** No network mutation was needed; native rendered the admitted bytes. */
    data object Rendered : SlockAvatarNativeLoadResult

    /** Shared suppression was honored while no decodable stale bytes existed. */
    data object Suppressed : SlockAvatarNativeLoadResult

    /** A 2xx candidate is staged under [SlockAvatarNativeRequest.committedToCommitIdentity]. */
    data class SuccessfulBytes(
        val sizeBytes: Long,
        val eTag: String?,
        val lastModified: String?,
        val contentType: String?
    ) : SlockAvatarNativeLoadResult

    /** A conditional request returned 304 and native retained the admitted bytes. */
    data class NotModified(
        val eTag: String?,
        val lastModified: String?
    ) : SlockAvatarNativeLoadResult

    data class Failure(val kind: SlockAvatarCacheFailureKind) : SlockAvatarNativeLoadResult
    data object Unsupported : SlockAvatarNativeLoadResult
}

internal enum class SlockAvatarNativeFinalizeOutcome(val wireValue: String) {
    SuccessfulBytes("successful_bytes"),
    NotModified("not_modified"),
    ResourceAbsent("resource_absent"),
    InvalidContent("invalid_content"),
    TemporaryTransport("temporary_transport")
}

internal sealed interface SlockAvatarNativeMutationResult {
    data object Accepted : SlockAvatarNativeMutationResult
    data object Unsupported : SlockAvatarNativeMutationResult
    data class Failed(val code: String) : SlockAvatarNativeMutationResult
}

/** Implemented once by the common bridge; Android/iOS/OHOS consume the same wire actions. */
internal interface SlockAvatarNativeCache {
    suspend fun inspect(
        nativePartitionKey: String,
        nativeSubjectKey: String,
        nativeBytesKey: String
    ): SlockAvatarNativeInspection

    /**
     * Registration is race-safe: if the image adapter completed first, native returns the already
     * recorded result. Unsupported/failure paths must also complete; common adds a bounded timeout
     * so a broken host cannot suspend the render observer forever.
     */
    suspend fun awaitResult(request: SlockAvatarNativeRequest): SlockAvatarNativeLoadResult

    suspend fun finalize(
        request: SlockAvatarNativeRequest,
        outcome: SlockAvatarNativeFinalizeOutcome
    ): SlockAvatarNativeMutationResult

    suspend fun rollback(request: SlockAvatarNativeRequest): SlockAvatarNativeMutationResult

    suspend fun retireOwner(nativePartitionKey: String): SlockAvatarNativeMutationResult

    suspend fun retireSubject(
        nativePartitionKey: String,
        nativeSubjectKey: String
    ): SlockAvatarNativeMutationResult
}

internal object UnsupportedSlockAvatarNativeCache : SlockAvatarNativeCache {
    override suspend fun inspect(
        nativePartitionKey: String,
        nativeSubjectKey: String,
        nativeBytesKey: String
    ): SlockAvatarNativeInspection = SlockAvatarNativeInspection.Unsupported

    override suspend fun awaitResult(request: SlockAvatarNativeRequest): SlockAvatarNativeLoadResult =
        SlockAvatarNativeLoadResult.Unsupported

    override suspend fun finalize(
        request: SlockAvatarNativeRequest,
        outcome: SlockAvatarNativeFinalizeOutcome
    ): SlockAvatarNativeMutationResult = SlockAvatarNativeMutationResult.Unsupported

    override suspend fun rollback(request: SlockAvatarNativeRequest): SlockAvatarNativeMutationResult =
        SlockAvatarNativeMutationResult.Unsupported

    override suspend fun retireOwner(nativePartitionKey: String): SlockAvatarNativeMutationResult =
        SlockAvatarNativeMutationResult.Unsupported

    override suspend fun retireSubject(
        nativePartitionKey: String,
        nativeSubjectKey: String
    ): SlockAvatarNativeMutationResult = SlockAvatarNativeMutationResult.Unsupported
}

internal class BridgeSlockAvatarNativeCache(
    private val host: HostAvatarCacheCapability,
    private val bridgeCallContext: CoroutineContext
) : SlockAvatarNativeCache {
    init {
        require(bridgeCallContext != EmptyCoroutineContext) {
            "avatar cache bridge calls require an explicit Kuikly call context"
        }
    }

    override suspend fun inspect(
        nativePartitionKey: String,
        nativeSubjectKey: String,
        nativeBytesKey: String
    ): SlockAvatarNativeInspection {
        val response =
            call(
                buildJsonObject {
                    put("action", "inspect")
                    putSlockAvatarManagedSetContract()
                    put("nativePartitionKey", nativePartitionKey)
                    put("nativeSubjectKey", nativeSubjectKey)
                    put("nativeBytesKey", nativeBytesKey)
                }
            )
        if (response?.optString("status") != "ok" || !response.acknowledgesSlockAvatarManagedSet()) {
            return SlockAvatarNativeInspection.Unsupported
        }
        return SlockAvatarNativeInspection.Supported(
            bytesAvailable = response.optBoolean("bytesAvailable", false),
            bytesDecodable = response.optBoolean("bytesDecodable", false),
            managedBytes =
                response.optString("managedBytes").toLongOrNull()
                    ?: response.optLong("managedBytes", 0L)
        )
    }

    override suspend fun awaitResult(request: SlockAvatarNativeRequest): SlockAvatarNativeLoadResult {
        val response = call(request.toJson().withEntry("action", "awaitResult"))
            ?: return SlockAvatarNativeLoadResult.Unsupported
        if (!response.acknowledgesSlockAvatarManagedSet()) {
            return SlockAvatarNativeLoadResult.Unsupported
        }
        return when (response.optString("status")) {
            "rendered" -> SlockAvatarNativeLoadResult.Rendered
            "suppressed" -> SlockAvatarNativeLoadResult.Suppressed
            "success" ->
                SlockAvatarNativeLoadResult.SuccessfulBytes(
                    sizeBytes = response.optString("sizeBytes").toLongOrNull()
                        ?: response.optLong("sizeBytes", 0L),
                    eTag = response.optString("eTag").trim().takeIf(String::isNotEmpty),
                    lastModified = response.optString("lastModified").trim().takeIf(String::isNotEmpty),
                    contentType = response.optString("contentType").trim().takeIf(String::isNotEmpty)
                )
            "not_modified" ->
                SlockAvatarNativeLoadResult.NotModified(
                    eTag = response.optString("eTag").trim().takeIf(String::isNotEmpty),
                    lastModified = response.optString("lastModified").trim().takeIf(String::isNotEmpty)
                )
            "resource_absent" ->
                SlockAvatarNativeLoadResult.Failure(SlockAvatarCacheFailureKind.ResourceAbsent)
            "invalid_content" ->
                SlockAvatarNativeLoadResult.Failure(SlockAvatarCacheFailureKind.InvalidContent)
            "temporary_transport" ->
                SlockAvatarNativeLoadResult.Failure(SlockAvatarCacheFailureKind.TemporaryTransport)
            else -> SlockAvatarNativeLoadResult.Unsupported
        }
    }

    override suspend fun finalize(
        request: SlockAvatarNativeRequest,
        outcome: SlockAvatarNativeFinalizeOutcome
    ): SlockAvatarNativeMutationResult =
        mutation(
            request.toJson()
                .withEntry("action", "finalize")
                .withEntry("outcome", outcome.wireValue)
        )

    override suspend fun rollback(request: SlockAvatarNativeRequest): SlockAvatarNativeMutationResult =
        mutation(request.toJson().withEntry("action", "rollback"))

    override suspend fun retireOwner(nativePartitionKey: String): SlockAvatarNativeMutationResult =
        mutation(
            buildJsonObject {
                put("action", "retireOwner")
                putSlockAvatarManagedSetContract()
                put("nativePartitionKey", nativePartitionKey)
            }
        )

    override suspend fun retireSubject(
        nativePartitionKey: String,
        nativeSubjectKey: String
    ): SlockAvatarNativeMutationResult =
        mutation(
            buildJsonObject {
                put("action", "retireSubject")
                putSlockAvatarManagedSetContract()
                put("nativePartitionKey", nativePartitionKey)
                put("nativeSubjectKey", nativeSubjectKey)
            }
        )

    private suspend fun mutation(command: JsonObject): SlockAvatarNativeMutationResult {
        val response = call(command) ?: return SlockAvatarNativeMutationResult.Unsupported
        if (!response.acknowledgesSlockAvatarManagedSet()) {
            return SlockAvatarNativeMutationResult.Unsupported
        }
        return when (response.optString("status")) {
            "ok" -> SlockAvatarNativeMutationResult.Accepted
            "unsupported" -> SlockAvatarNativeMutationResult.Unsupported
            else ->
                SlockAvatarNativeMutationResult.Failed(
                    response.optString("code").ifBlank { "avatar_cache_native_failed" }
                )
        }
    }

    private suspend fun call(command: JsonObject): JsonObject? {
        val stamped = command.withEntry("protocol", SLOCK_AVATAR_NATIVE_CACHE_PROTOCOL)
        return withContext(bridgeCallContext) {
            host.avatarCacheCapability(stamped)
        }
    }
}

internal data class SlockAvatarCacheRequestToken(
    val key: SlockAvatarCacheKey,
    val generation: Long,
    val nonce: String
) {
    init {
        require(generation > 0L)
        SlockAvatarCommitIdentity(generation, nonce)
    }
}

private data class SlockAvatarCacheRequestSlot(
    val owner: SlockAvatarCacheOwner,
    val subject: SlockAvatarCacheSubject
)

internal class SlockAvatarCacheGenerationAuthority(
    private val nonceGenerator: () -> String = ::slockAvatarCommitNonce
) {
    private val lock = SynchronizedObject()
    private var nextGeneration = 0L
    private val selected = mutableMapOf<SlockAvatarCacheRequestSlot, SlockAvatarCacheRequestToken>()
    private val completions = mutableMapOf<SlockAvatarCacheRequestToken, CompletionSlot>()

    fun select(key: SlockAvatarCacheKey): SlockAvatarCacheRequestToken =
        synchronized(lock) {
            val slot = SlockAvatarCacheRequestSlot(key.owner, key.subject)
            selected[slot]?.takeIf { token ->
                token.key == key && completions[token]?.closedForSelection != true
            } ?: run {
                selected.remove(slot)?.let(completions::remove)
                SlockAvatarCacheRequestToken(key, ++nextGeneration, nonceGenerator()).also {
                    selected[slot] = it
                }
            }
        }

    fun isCurrent(token: SlockAvatarCacheRequestToken): Boolean =
        synchronized(lock) {
            selected[SlockAvatarCacheRequestSlot(token.key.owner, token.key.subject)] == token
        }

    /** At most one shared metadata/finalize mutation may execute for a reused request generation. */
    suspend fun completeOnce(
        token: SlockAvatarCacheRequestToken,
        block: suspend () -> SlockAvatarCacheCompletionReceipt
    ): SlockAvatarCacheCompletionReceipt? {
        val completion =
            synchronized(lock) {
                val slot = SlockAvatarCacheRequestSlot(token.key.owner, token.key.subject)
                if (selected[slot] != token) return@synchronized null
                completions.getOrPut(token) { CompletionSlot() }
            } ?: return null
        return completion.mutex.withLock {
            completion.receipt?.let { return@withLock it }
            if (!isCurrent(token)) return@withLock null
            val receipt = block()
            completion.receipt = receipt
            val reusable =
                receipt is SlockAvatarCacheCompletionReceipt.Accepted ||
                    receipt is SlockAvatarCacheCompletionReceipt.Rendered ||
                    receipt is SlockAvatarCacheCompletionReceipt.Suppressed
            synchronized(lock) {
                if (reusable) {
                    // Concurrent consumers that already selected this token still share the
                    // receipt. A later prepare starts a fresh operation so a stale read can
                    // actually revalidate and commit new validators under a new generation.
                    completion.closedForSelection = true
                } else {
                    val slot = SlockAvatarCacheRequestSlot(token.key.owner, token.key.subject)
                    if (selected[slot] == token) selected.remove(slot)
                    if (completions[token] === completion) completions.remove(token)
                }
            }
            receipt
        }
    }

    fun revokeOwner(owner: SlockAvatarCacheOwner) {
        synchronized(lock) {
            selected.keys.filter { it.owner == owner }.forEach { slot ->
                selected.remove(slot)?.let(completions::remove)
            }
        }
    }

    fun revokeSubject(owner: SlockAvatarCacheOwner, subject: SlockAvatarCacheSubject) {
        synchronized(lock) {
            selected.remove(SlockAvatarCacheRequestSlot(owner, subject))?.let(completions::remove)
        }
    }

    fun revokeAll() {
        synchronized(lock) {
            selected.clear()
            completions.clear()
        }
    }

    private class CompletionSlot {
        val mutex = Mutex()
        var receipt: SlockAvatarCacheCompletionReceipt? = null
        var closedForSelection: Boolean = false
    }
}

internal sealed interface SlockAvatarPreparedRender {
    val imageModel: String

    data class Legacy(override val imageModel: String) : SlockAvatarPreparedRender

    class Native internal constructor(
        val token: SlockAvatarCacheRequestToken,
        val request: SlockAvatarNativeRequest,
        internal val nativeCache: SlockAvatarNativeCache
    ) : SlockAvatarPreparedRender {
        override val imageModel: String = request.imageModel
    }
}

internal sealed interface SlockAvatarCacheCompletionReceipt {
    data object Accepted : SlockAvatarCacheCompletionReceipt
    data object Rendered : SlockAvatarCacheCompletionReceipt
    data object Suppressed : SlockAvatarCacheCompletionReceipt
    data object RejectedStaleGeneration : SlockAvatarCacheCompletionReceipt
    data object NativeUnsupported : SlockAvatarCacheCompletionReceipt
    data class Failed(val stage: String, val code: String) : SlockAvatarCacheCompletionReceipt
}

internal enum class SlockAvatarCacheClearStage {
    Native,
    Metadata
}

internal sealed interface SlockAvatarCacheClearReceipt {
    data object Success : SlockAvatarCacheClearReceipt
    data class Failed(val stages: Set<SlockAvatarCacheClearStage>) : SlockAvatarCacheClearReceipt
}

internal data class SlockAvatarOwnerRetirementCapture(
    val owners: Set<SlockAvatarCacheOwner>
)

internal data class SlockAvatarSubjectRetirementCapture(
    val owner: SlockAvatarCacheOwner,
    val subject: SlockAvatarCacheSubject,
    val nativeSubjectKey: String
)

internal class SlockAvatarCacheRuntime(
    private val coordinator: SlockAvatarCacheCoordinator,
    private val currentOwner: suspend (String?) -> SlockAvatarCacheOwner?,
    private val mountedBinding: SlockAvatarMountedBinding? = null,
    private val generationAuthority: SlockAvatarCacheGenerationAuthority = SlockAvatarCacheGenerationAuthority(),
    private val nativeInspectionTimeoutMillis: Long = SLOCK_AVATAR_NATIVE_INSPECTION_TIMEOUT_MILLIS,
    private val nativeMutationTimeoutMillis: Long = SLOCK_AVATAR_NATIVE_MUTATION_TIMEOUT_MILLIS,
    private val nativeResultTimeoutMillis: Long = SLOCK_AVATAR_NATIVE_RESULT_TIMEOUT_MILLIS,
    private val logger: (String) -> Unit = {}
) {
    init {
        require(nativeInspectionTimeoutMillis > 0L)
        require(nativeMutationTimeoutMillis > 0L)
        require(nativeResultTimeoutMillis > 0L)
    }

    suspend fun prepare(
        subject: SlockAvatarCacheSubject,
        remoteUrl: String,
        serverId: String? = null,
        nativeCache: SlockAvatarNativeCache
    ): SlockAvatarPreparedRender {
        val normalizedUrl = remoteUrl.trim()
        val owner =
            runCatchingPreservingCancellation { currentOwner(serverId) }
                .getOrElse {
                    logger("avatar_cache_owner_read_failed")
                    null
                }
                ?: return SlockAvatarPreparedRender.Legacy(normalizedUrl)
        val key =
            runCatching {
                SlockAvatarCacheKey(owner, subject, slockAvatarContentIdentity(normalizedUrl))
            }.getOrElse {
                return SlockAvatarPreparedRender.Legacy(normalizedUrl)
            }
        val token = generationAuthority.select(key)
        val inspection =
            runCatchingPreservingCancellation {
                withTimeoutOrNull(nativeInspectionTimeoutMillis) {
                    nativeCache.inspect(
                        nativePartitionKey = key.owner.nativePartitionKey,
                        nativeSubjectKey = key.nativeSubjectKey,
                        nativeBytesKey = key.nativeBytesKey
                    )
                } ?: SlockAvatarNativeInspection.Unsupported
            }.getOrElse {
                logger("avatar_cache_native_inspect_failed")
                SlockAvatarNativeInspection.Unsupported
            }
        if (inspection !is SlockAvatarNativeInspection.Supported) {
            return SlockAvatarPreparedRender.Legacy(normalizedUrl)
        }
        if (!generationAuthority.isCurrent(token)) {
            return SlockAvatarPreparedRender.Legacy(normalizedUrl)
        }
        val read =
            runCatchingPreservingCancellation {
                coordinator.prepareRead(
                    key = key,
                    bytesAvailable = inspection.bytesAvailable,
                    bytesDecodable = inspection.bytesDecodable
                )
            }.getOrElse {
                logger("avatar_cache_metadata_read_failed")
                return SlockAvatarPreparedRender.Legacy(normalizedUrl)
            }
        if (!generationAuthority.isCurrent(token)) {
            return SlockAvatarPreparedRender.Legacy(normalizedUrl)
        }
        val (mode, retryAtMillis) = read.decision.toNativeReadMode()
        val committedToIdentity =
            SlockAvatarCommitIdentity(
                commitGeneration = nextAvatarCommitGeneration(read.commitIdentity),
                nonce = token.nonce
            )
        val newModelRevision = nextAvatarModelRevision(read.modelRevision)
        return SlockAvatarPreparedRender.Native(
            token = token,
            request =
                SlockAvatarNativeRequest(
                    remoteUrl = normalizedUrl,
                    nativePartitionKey = key.owner.nativePartitionKey,
                    nativeSubjectKey = key.nativeSubjectKey,
                    nativeBytesKey = key.nativeBytesKey,
                    readMode = mode,
                    retryAtMillis = retryAtMillis,
                    eTag = read.validators?.eTag,
                    lastModified = read.validators?.lastModified,
                    expectedCommitIdentity = read.commitIdentity,
                    committedToCommitIdentity = committedToIdentity,
                    expectedModelRevision = read.modelRevision,
                    newModelRevision = newModelRevision
                ),
            nativeCache = nativeCache
        )
    }

    suspend fun observeAndCommit(
        prepared: SlockAvatarPreparedRender.Native
    ): SlockAvatarCacheCompletionReceipt = withContext(NonCancellable) {
        val completion =
            generationAuthority.completeOnce(prepared.token) {
                observeAndCommitOnce(prepared)
            }
        if (completion != null) return@withContext completion
        rollbackNative(prepared.nativeCache, prepared.request)
        SlockAvatarCacheCompletionReceipt.RejectedStaleGeneration
    }

    private suspend fun observeAndCommitOnce(
        prepared: SlockAvatarPreparedRender.Native
    ): SlockAvatarCacheCompletionReceipt {
        val nativeCache = prepared.nativeCache
        if (!generationAuthority.isCurrent(prepared.token)) {
            rollbackNative(nativeCache, prepared.request)
            return SlockAvatarCacheCompletionReceipt.RejectedStaleGeneration
        }
        val result =
            runCatching {
                withTimeoutOrNull(nativeResultTimeoutMillis) {
                    nativeCache.awaitResult(prepared.request)
                }
            }
                .getOrElse {
                    rollbackNative(nativeCache, prepared.request)
                    return SlockAvatarCacheCompletionReceipt.Failed("native_result", "avatar_cache_native_result_failed")
                }
                ?: run {
                    rollbackNative(nativeCache, prepared.request)
                    return SlockAvatarCacheCompletionReceipt.Failed(
                        "native_result",
                        "avatar_cache_native_result_timeout"
                    )
                }
        if (!generationAuthority.isCurrent(prepared.token)) {
            rollbackNative(nativeCache, prepared.request)
            return SlockAvatarCacheCompletionReceipt.RejectedStaleGeneration
        }
        if (result is SlockAvatarNativeLoadResult.Rendered) {
            return SlockAvatarCacheCompletionReceipt.Rendered
        }
        if (result is SlockAvatarNativeLoadResult.Suppressed) {
            return SlockAvatarCacheCompletionReceipt.Suppressed
        }
        if (result is SlockAvatarNativeLoadResult.Unsupported) {
            rollbackNative(nativeCache, prepared.request)
            return SlockAvatarCacheCompletionReceipt.NativeUnsupported
        }
        val guard =
            SlockAvatarCacheCommitGuard(
                key = prepared.token.key,
                expectedCommit =
                    SlockAvatarCacheCommitExpectation(
                        key = prepared.token.key,
                        commitIdentity = prepared.request.expectedCommitIdentity,
                        modelRevision = prepared.request.expectedModelRevision
                    )
            ) {
                generationAuthority.isCurrent(prepared.token)
            }
        val previousState =
            runCatching {
                coordinator.storedState(prepared.token.key)
            }.getOrElse {
                rollbackNative(nativeCache, prepared.request)
                return SlockAvatarCacheCompletionReceipt.Failed(
                    "metadata",
                    "avatar_cache_metadata_read_failed"
                )
            }
        val mutation =
            runCatching {
                when (result) {
                    is SlockAvatarNativeLoadResult.SuccessfulBytes ->
                        coordinator.reportSuccessfulBytes(
                            key = prepared.token.key,
                            sizeBytes = result.sizeBytes,
                            expectedCommitIdentity = prepared.request.expectedCommitIdentity,
                            expectedModelRevision = prepared.request.expectedModelRevision,
                            commitIdentity = prepared.request.committedToCommitIdentity,
                            modelRevision = prepared.request.newModelRevision,
                            eTag = result.eTag,
                            lastModified = result.lastModified,
                            contentType = result.contentType,
                            guard = guard
                        )
                    is SlockAvatarNativeLoadResult.NotModified ->
                        coordinator.reportNotModified(
                            key = prepared.token.key,
                            expectedCommitIdentity = prepared.request.expectedCommitIdentity,
                            expectedModelRevision = prepared.request.expectedModelRevision,
                            eTag = result.eTag,
                            lastModified = result.lastModified,
                            guard = guard
                        )
                    is SlockAvatarNativeLoadResult.Failure ->
                        coordinator.reportFailure(
                            key = prepared.token.key,
                            kind = result.kind,
                            guard = guard
                        )
                    SlockAvatarNativeLoadResult.Rendered,
                    SlockAvatarNativeLoadResult.Suppressed,
                    SlockAvatarNativeLoadResult.Unsupported -> error("handled above")
                }
            }.getOrElse {
                rollbackNative(nativeCache, prepared.request)
                return SlockAvatarCacheCompletionReceipt.Failed(
                    "metadata",
                    "avatar_cache_metadata_commit_failed"
                )
            }
        if (mutation != SlockAvatarCacheMutationResult.Accepted || !generationAuthority.isCurrent(prepared.token)) {
            rollbackNative(nativeCache, prepared.request)
            return when (mutation) {
                SlockAvatarCacheMutationResult.RejectedStaleGeneration ->
                    SlockAvatarCacheCompletionReceipt.RejectedStaleGeneration
                SlockAvatarCacheMutationResult.MissingCurrentMetadata ->
                    SlockAvatarCacheCompletionReceipt.Failed("metadata", "avatar_cache_metadata_missing")
                is SlockAvatarCacheMutationResult.RejectedTooLarge ->
                    SlockAvatarCacheCompletionReceipt.Failed("metadata", "avatar_cache_entry_too_large")
                SlockAvatarCacheMutationResult.Accepted ->
                    SlockAvatarCacheCompletionReceipt.RejectedStaleGeneration
            }
        }
        val nativeOutcome = result.toNativeFinalizeOutcome()
        val finalized = mutateNative { nativeCache.finalize(prepared.request, nativeOutcome) }
        if (
            finalized == SlockAvatarNativeMutationResult.Accepted &&
            generationAuthority.isCurrent(prepared.token)
        ) {
            return SlockAvatarCacheCompletionReceipt.Accepted
        }
        rollbackNative(nativeCache, prepared.request)
        val expectedCommittedState =
            runCatching {
                coordinator.storedState(prepared.token.key)
            }.getOrNull()
        val compensation =
            expectedCommittedState?.let { expected ->
                runCatching {
                    coordinator.restoreAfterNativeFinalizeFailure(
                        key = prepared.token.key,
                        expectedCommittedState = expected,
                        previousState = previousState,
                        guard = guard
                    )
                }.getOrNull()
            }
        if (
            compensation != SlockAvatarCacheMutationResult.Accepted &&
            generationAuthority.isCurrent(prepared.token)
        ) {
            logger("avatar_cache_metadata_compensation_failed")
        }
        if (finalized == SlockAvatarNativeMutationResult.Accepted) {
            return SlockAvatarCacheCompletionReceipt.RejectedStaleGeneration
        }
        return when (finalized) {
            SlockAvatarNativeMutationResult.Unsupported ->
                SlockAvatarCacheCompletionReceipt.Failed("native_finalize", "avatar_cache_native_unsupported")
            is SlockAvatarNativeMutationResult.Failed ->
                SlockAvatarCacheCompletionReceipt.Failed("native_finalize", finalized.code)
            SlockAvatarNativeMutationResult.Accepted -> error("handled above")
            null ->
                SlockAvatarCacheCompletionReceipt.Failed(
                    "native_finalize",
                    "avatar_cache_native_finalize_timeout"
                )
        }
    }

    suspend fun seedMountedSession(
        session: SlockAvatarMountedSession,
        token: SlockAvatarMountToken
    ): Boolean = coordinator.seedMountedSession(session, token)

    /**
     * Clears transient download suppression only for the exact process owner that is still current
     * when the host reports restored connectivity. The host supplies no owner identity: shared
     * resolves environment/account/current-server twice so a queued signal cannot recover a
     * departed owner after an auth or server transition.
     */
    suspend fun recoverCurrentOwnerAfterNetworkAvailable(): Boolean {
        val capturedOwner = currentNetworkRecoveryOwner() ?: return false
        val confirmedOwner = currentNetworkRecoveryOwner() ?: return false
        if (confirmedOwner != capturedOwner) {
            logger("avatar_cache_network_recovery_owner_changed")
            return false
        }
        return runCatchingPreservingCancellation {
            coordinator.onNetworkChanged(capturedOwner)
            true
        }.getOrElse {
            logger("avatar_cache_network_recovery_failed")
            false
        }
    }

    fun captureSessionRetirement(
        environmentId: String,
        session: StoredAuthSession?
    ): SlockAvatarOwnerRetirementCapture =
        SlockAvatarOwnerRetirementCapture(slockAvatarCacheOwners(environmentId, session))

    suspend fun captureCurrentSubjectRetirement(
        subject: SlockAvatarCacheSubject,
        serverId: String? = null
    ): SlockAvatarSubjectRetirementCapture? =
        runCatchingPreservingCancellation { currentOwner(serverId) }
            .getOrElse {
                logger("avatar_cache_owner_read_failed")
                null
            }?.let { owner ->
                SlockAvatarSubjectRetirementCapture(
                    owner = owner,
                    subject = subject,
                    nativeSubjectKey = slockAvatarNativeSubjectKey(owner, subject)
                )
            }

    suspend fun retireOwners(
        capture: SlockAvatarOwnerRetirementCapture,
        nativeCache: SlockAvatarNativeCache
    ): SlockAvatarCacheClearReceipt = withContext(NonCancellable) {
        if (capture.owners.isEmpty()) return@withContext SlockAvatarCacheClearReceipt.Success
        val failed = mutableSetOf<SlockAvatarCacheClearStage>()
        capture.owners.forEach { owner ->
            if (!mutateMounted { it.retireOwner(owner) }) {
                failed += SlockAvatarCacheClearStage.Native
            }
            generationAuthority.revokeOwner(owner)
            if (mutateNative { nativeCache.retireOwner(owner.nativePartitionKey) } != SlockAvatarNativeMutationResult.Accepted) {
                failed += SlockAvatarCacheClearStage.Native
            }
            if (runCatching { coordinator.clearOwner(owner) }.isFailure) {
                failed += SlockAvatarCacheClearStage.Metadata
            }
        }
        failed.toClearReceipt()
    }

    suspend fun retireSubject(
        capture: SlockAvatarSubjectRetirementCapture,
        nativeCache: SlockAvatarNativeCache
    ): SlockAvatarCacheClearReceipt = withContext(NonCancellable) {
        val failed = mutableSetOf<SlockAvatarCacheClearStage>()
        if (!mutateMounted { it.retireSubject(capture.owner, capture.subject) }) {
            failed += SlockAvatarCacheClearStage.Native
        }
        generationAuthority.revokeSubject(capture.owner, capture.subject)
        if (
            mutateNative {
                nativeCache.retireSubject(
                    nativePartitionKey = capture.owner.nativePartitionKey,
                    nativeSubjectKey = capture.nativeSubjectKey
                )
            } != SlockAvatarNativeMutationResult.Accepted
        ) {
            failed += SlockAvatarCacheClearStage.Native
        }
        if (runCatching { coordinator.clearSubject(capture.owner, capture.subject) }.isFailure) {
            failed += SlockAvatarCacheClearStage.Metadata
        }
        failed.toClearReceipt()
    }

    private suspend fun rollbackNative(
        nativeCache: SlockAvatarNativeCache,
        request: SlockAvatarNativeRequest
    ) {
        mutateNative { nativeCache.rollback(request) }
    }

    private suspend fun mutateNative(
        block: suspend () -> SlockAvatarNativeMutationResult
    ): SlockAvatarNativeMutationResult? =
        runCatching {
            withTimeoutOrNull(nativeMutationTimeoutMillis) { block() }
        }.getOrNull()

    private suspend fun mutateMounted(
        block: suspend (SlockAvatarMountedBinding) -> Boolean
    ): Boolean =
        mountedBinding?.let { binding ->
            runCatching {
                withTimeoutOrNull(nativeMutationTimeoutMillis) { block(binding) } ?: false
            }.getOrDefault(false)
        } ?: true

    private suspend fun currentNetworkRecoveryOwner(): SlockAvatarCacheOwner? =
        runCatchingPreservingCancellation { currentOwner(null) }
            .getOrElse {
                logger("avatar_cache_network_recovery_owner_read_failed")
                null
            }

    /** Settings image clear: native and shared are both attempted, and partial work is never success. */
    suspend fun clearAll(nativeClear: suspend () -> Boolean): SlockAvatarCacheClearReceipt =
        withContext(NonCancellable) {
            val failed = mutableSetOf<SlockAvatarCacheClearStage>()
            if (!mutateMounted { it.clearAll() }) {
                failed += SlockAvatarCacheClearStage.Native
            }
            generationAuthority.revokeAll()
            if (
                runCatching {
                    withTimeoutOrNull(nativeMutationTimeoutMillis) { nativeClear() } ?: false
                }.getOrDefault(false).not()
            ) {
                failed += SlockAvatarCacheClearStage.Native
            }
            if (runCatching { coordinator.clearAll() }.isFailure) {
                failed += SlockAvatarCacheClearStage.Metadata
            }
            failed.toClearReceipt()
        }
}

private fun slockAvatarCommitNonce(): String =
    slockSha256Hex(Random.Default.nextBytes(ByteArray(32)))

private fun nextAvatarCommitGeneration(expected: SlockAvatarCommitIdentity?): Long {
    val current = expected?.commitGeneration ?: 0L
    check(current < Long.MAX_VALUE) { "avatar commit generation exhausted" }
    return current + 1L
}

private fun nextAvatarModelRevision(current: Long): Long {
    check(current < Long.MAX_VALUE) { "avatar model revision exhausted" }
    return current + 1L
}

internal fun slockAvatarCacheOwners(
    environmentId: String,
    session: StoredAuthSession?
): Set<SlockAvatarCacheOwner> {
    val normalizedEnvironment = environmentId.trim().trimEnd('/')
    val accountId = session?.user?.id?.trim().orEmpty()
    if (normalizedEnvironment.isBlank() || accountId.isBlank()) return emptySet()
    val serverIds =
        buildSet {
            session?.servers.orEmpty().forEach { server ->
                server.id.trim().takeIf(String::isNotEmpty)?.let(::add)
            }
            session?.currentServerId?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
        }
    return serverIds.mapTo(linkedSetOf()) { serverId ->
        SlockAvatarCacheOwner(normalizedEnvironment, accountId, serverId)
    }
}

internal fun slockCurrentAvatarCacheOwner(
    environmentId: String,
    session: StoredAuthSession?,
    requestedServerId: String? = null
): SlockAvatarCacheOwner? {
    val activeSession = session ?: return null
    val requested = requestedServerId?.trim()?.takeIf(String::isNotEmpty)
    val current = activeSession.currentServerId?.trim()?.takeIf(String::isNotEmpty)
    if (
        requested != null &&
        requested != current &&
        activeSession.servers.none { it.id.trim() == requested }
    ) {
        return null
    }
    val serverId = requested ?: current ?: return null
    val normalizedEnvironment = environmentId.trim().trimEnd('/')
    val accountId = activeSession.user.id.trim()
    if (normalizedEnvironment.isBlank() || accountId.isBlank()) return null
    return SlockAvatarCacheOwner(normalizedEnvironment, accountId, serverId)
}

private fun SlockAvatarCacheReadDecision.toNativeReadMode(): Pair<SlockAvatarNativeReadMode, Long?> =
    when (this) {
        is SlockAvatarCacheReadDecision.RenderFresh -> SlockAvatarNativeReadMode.RenderFresh to null
        is SlockAvatarCacheReadDecision.RenderStaleAndRevalidate ->
            SlockAvatarNativeReadMode.RenderStaleAndRevalidate to null
        is SlockAvatarCacheReadDecision.RenderStaleSuppressed ->
            SlockAvatarNativeReadMode.RenderStaleSuppressed to retryAtMs
        is SlockAvatarCacheReadDecision.Fetch -> SlockAvatarNativeReadMode.Fetch to null
        is SlockAvatarCacheReadDecision.Suppressed -> SlockAvatarNativeReadMode.Suppressed to retryAtMs
    }

private fun SlockAvatarNativeLoadResult.toNativeFinalizeOutcome(): SlockAvatarNativeFinalizeOutcome =
    when (this) {
        is SlockAvatarNativeLoadResult.SuccessfulBytes -> SlockAvatarNativeFinalizeOutcome.SuccessfulBytes
        is SlockAvatarNativeLoadResult.NotModified -> SlockAvatarNativeFinalizeOutcome.NotModified
        is SlockAvatarNativeLoadResult.Failure ->
            when (kind) {
                SlockAvatarCacheFailureKind.ResourceAbsent -> SlockAvatarNativeFinalizeOutcome.ResourceAbsent
                SlockAvatarCacheFailureKind.InvalidContent -> SlockAvatarNativeFinalizeOutcome.InvalidContent
                SlockAvatarCacheFailureKind.TemporaryTransport -> SlockAvatarNativeFinalizeOutcome.TemporaryTransport
            }
        SlockAvatarNativeLoadResult.Rendered,
        SlockAvatarNativeLoadResult.Suppressed,
        SlockAvatarNativeLoadResult.Unsupported -> error("result has no finalize outcome")
    }

private fun Set<SlockAvatarCacheClearStage>.toClearReceipt(): SlockAvatarCacheClearReceipt =
    if (isEmpty()) SlockAvatarCacheClearReceipt.Success else SlockAvatarCacheClearReceipt.Failed(this)
