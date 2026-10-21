package build.raft.app.data

import build.raft.app.host.HostAvatarCacheCapability
import build.raft.app.optBoolean
import build.raft.app.optLong
import build.raft.app.optString
import build.raft.app.raftSha256Hex
import build.raft.app.toRaftJsonString
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

internal const val RAFT_AVATAR_NATIVE_CACHE_PROTOCOL = "slock_avatar_cache_v1"
internal const val RAFT_AVATAR_NATIVE_MANAGED_SET = "typed_avatar_only"
internal const val RAFT_AVATAR_NATIVE_INDEX_CONTRACT = "opaque_partition_subject_bytes_v1"
internal const val RAFT_AVATAR_NATIVE_BUDGET_SCOPE = "avatar_only"
private const val RAFT_AVATAR_NATIVE_IMAGE_MODEL_PREFIX = "slock-avatar-cache-v1:"
internal const val RAFT_AVATAR_NATIVE_INSPECTION_TIMEOUT_MILLIS = 1_500L
internal const val RAFT_AVATAR_NATIVE_MUTATION_TIMEOUT_MILLIS = 5_000L
internal const val RAFT_AVATAR_NATIVE_RESULT_TIMEOUT_MILLIS = 45_000L

internal enum class RaftAvatarNativeReadMode(val wireValue: String) {
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
internal data class RaftAvatarNativeRequest(
    val remoteUrl: String,
    val nativePartitionKey: String,
    val nativeSubjectKey: String,
    val nativeBytesKey: String,
    val readMode: RaftAvatarNativeReadMode,
    val retryAtMillis: Long?,
    val eTag: String?,
    val lastModified: String?,
    val expectedCommitIdentity: RaftAvatarCommitIdentity?,
    val committedToCommitIdentity: RaftAvatarCommitIdentity,
    val expectedModelRevision: Long,
    val newModelRevision: Long
) {
    init {
        require(remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://"))
        require(nativePartitionKey.isRaftAvatarOpaqueKey())
        require(nativeSubjectKey.isRaftAvatarOpaqueKey())
        require(nativeBytesKey.isRaftAvatarOpaqueKey())
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
            RAFT_AVATAR_NATIVE_IMAGE_MODEL_PREFIX +
                Base64.encode(toJson().toRaftJsonString().encodeToByteArray())

    fun toJson(): JsonObject =
        buildJsonObject {
            put("protocol", RAFT_AVATAR_NATIVE_CACHE_PROTOCOL)
            putRaftAvatarManagedSetContract()
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
internal fun JsonObjectBuilder.putRaftAvatarManagedSetContract() {
    put("managedSet", RAFT_AVATAR_NATIVE_MANAGED_SET)
    put("managedIndex", RAFT_AVATAR_NATIVE_INDEX_CONTRACT)
    put("budgetScope", RAFT_AVATAR_NATIVE_BUDGET_SCOPE)
    put("indexedRetirementRequired", true)
    put("maxEntryBytes", RaftAvatarCachePolicy.MaxEntryBytes.toString())
    put("maxManagedBytes", RaftAvatarCachePolicy.MaxPlatformBytes.toString())
}

internal fun JsonObject.acknowledgesRaftAvatarManagedSet(): Boolean {
    if (optString("managedSet") != RAFT_AVATAR_NATIVE_MANAGED_SET) return false
    if (optString("managedIndex") != RAFT_AVATAR_NATIVE_INDEX_CONTRACT) return false
    if (optString("budgetScope") != RAFT_AVATAR_NATIVE_BUDGET_SCOPE) return false
    if (!optBoolean("indexedRetirement", false)) return false
    val managedBytes =
        optString("managedBytes").toLongOrNull()
            ?: optLong("managedBytes", -1L)
    return managedBytes in 0L..RaftAvatarCachePolicy.MaxPlatformBytes
}

private fun String.isRaftAvatarOpaqueKey(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

internal sealed interface RaftAvatarNativeInspection {
    data object Unsupported : RaftAvatarNativeInspection
    data class Supported(
        val bytesAvailable: Boolean,
        val bytesDecodable: Boolean,
        val managedBytes: Long
    ) : RaftAvatarNativeInspection
}

internal sealed interface RaftAvatarNativeLoadResult {
    /** No network mutation was needed; native rendered the admitted bytes. */
    data object Rendered : RaftAvatarNativeLoadResult

    /** Shared suppression was honored while no decodable stale bytes existed. */
    data object Suppressed : RaftAvatarNativeLoadResult

    /** A 2xx candidate is staged under [RaftAvatarNativeRequest.committedToCommitIdentity]. */
    data class SuccessfulBytes(
        val sizeBytes: Long,
        val eTag: String?,
        val lastModified: String?,
        val contentType: String?
    ) : RaftAvatarNativeLoadResult

    /** A conditional request returned 304 and native retained the admitted bytes. */
    data class NotModified(
        val eTag: String?,
        val lastModified: String?
    ) : RaftAvatarNativeLoadResult

    data class Failure(val kind: RaftAvatarCacheFailureKind) : RaftAvatarNativeLoadResult
    data object Unsupported : RaftAvatarNativeLoadResult
}

internal enum class RaftAvatarNativeFinalizeOutcome(val wireValue: String) {
    SuccessfulBytes("successful_bytes"),
    NotModified("not_modified"),
    ResourceAbsent("resource_absent"),
    InvalidContent("invalid_content"),
    TemporaryTransport("temporary_transport")
}

internal sealed interface RaftAvatarNativeMutationResult {
    data object Accepted : RaftAvatarNativeMutationResult
    data object Unsupported : RaftAvatarNativeMutationResult
    data class Failed(val code: String) : RaftAvatarNativeMutationResult
}

/** Implemented once by the common bridge; Android/iOS consume the same wire actions. */
internal interface RaftAvatarNativeCache {
    suspend fun inspect(
        nativePartitionKey: String,
        nativeSubjectKey: String,
        nativeBytesKey: String
    ): RaftAvatarNativeInspection

    /**
     * Registration is race-safe: if the image adapter completed first, native returns the already
     * recorded result. Unsupported/failure paths must also complete; common adds a bounded timeout
     * so a broken host cannot suspend the render observer forever.
     */
    suspend fun awaitResult(request: RaftAvatarNativeRequest): RaftAvatarNativeLoadResult

    suspend fun finalize(
        request: RaftAvatarNativeRequest,
        outcome: RaftAvatarNativeFinalizeOutcome
    ): RaftAvatarNativeMutationResult

    suspend fun rollback(request: RaftAvatarNativeRequest): RaftAvatarNativeMutationResult

    suspend fun retireOwner(nativePartitionKey: String): RaftAvatarNativeMutationResult

    suspend fun retireSubject(
        nativePartitionKey: String,
        nativeSubjectKey: String
    ): RaftAvatarNativeMutationResult
}

internal object UnsupportedRaftAvatarNativeCache : RaftAvatarNativeCache {
    override suspend fun inspect(
        nativePartitionKey: String,
        nativeSubjectKey: String,
        nativeBytesKey: String
    ): RaftAvatarNativeInspection = RaftAvatarNativeInspection.Unsupported

    override suspend fun awaitResult(request: RaftAvatarNativeRequest): RaftAvatarNativeLoadResult =
        RaftAvatarNativeLoadResult.Unsupported

    override suspend fun finalize(
        request: RaftAvatarNativeRequest,
        outcome: RaftAvatarNativeFinalizeOutcome
    ): RaftAvatarNativeMutationResult = RaftAvatarNativeMutationResult.Unsupported

    override suspend fun rollback(request: RaftAvatarNativeRequest): RaftAvatarNativeMutationResult =
        RaftAvatarNativeMutationResult.Unsupported

    override suspend fun retireOwner(nativePartitionKey: String): RaftAvatarNativeMutationResult =
        RaftAvatarNativeMutationResult.Unsupported

    override suspend fun retireSubject(
        nativePartitionKey: String,
        nativeSubjectKey: String
    ): RaftAvatarNativeMutationResult = RaftAvatarNativeMutationResult.Unsupported
}

internal class BridgeRaftAvatarNativeCache(
    private val host: HostAvatarCacheCapability,
    private val bridgeCallContext: CoroutineContext
) : RaftAvatarNativeCache {
    init {
        require(bridgeCallContext != EmptyCoroutineContext) {
            "avatar cache bridge calls require an explicit Kuikly call context"
        }
    }

    override suspend fun inspect(
        nativePartitionKey: String,
        nativeSubjectKey: String,
        nativeBytesKey: String
    ): RaftAvatarNativeInspection {
        val response =
            call(
                buildJsonObject {
                    put("action", "inspect")
                    putRaftAvatarManagedSetContract()
                    put("nativePartitionKey", nativePartitionKey)
                    put("nativeSubjectKey", nativeSubjectKey)
                    put("nativeBytesKey", nativeBytesKey)
                }
            )
        if (response?.optString("status") != "ok" || !response.acknowledgesRaftAvatarManagedSet()) {
            return RaftAvatarNativeInspection.Unsupported
        }
        return RaftAvatarNativeInspection.Supported(
            bytesAvailable = response.optBoolean("bytesAvailable", false),
            bytesDecodable = response.optBoolean("bytesDecodable", false),
            managedBytes =
                response.optString("managedBytes").toLongOrNull()
                    ?: response.optLong("managedBytes", 0L)
        )
    }

    override suspend fun awaitResult(request: RaftAvatarNativeRequest): RaftAvatarNativeLoadResult {
        val response = call(request.toJson().withEntry("action", "awaitResult"))
            ?: return RaftAvatarNativeLoadResult.Unsupported
        if (!response.acknowledgesRaftAvatarManagedSet()) {
            return RaftAvatarNativeLoadResult.Unsupported
        }
        return when (response.optString("status")) {
            "rendered" -> RaftAvatarNativeLoadResult.Rendered
            "suppressed" -> RaftAvatarNativeLoadResult.Suppressed
            "success" ->
                RaftAvatarNativeLoadResult.SuccessfulBytes(
                    sizeBytes = response.optString("sizeBytes").toLongOrNull()
                        ?: response.optLong("sizeBytes", 0L),
                    eTag = response.optString("eTag").trim().takeIf(String::isNotEmpty),
                    lastModified = response.optString("lastModified").trim().takeIf(String::isNotEmpty),
                    contentType = response.optString("contentType").trim().takeIf(String::isNotEmpty)
                )
            "not_modified" ->
                RaftAvatarNativeLoadResult.NotModified(
                    eTag = response.optString("eTag").trim().takeIf(String::isNotEmpty),
                    lastModified = response.optString("lastModified").trim().takeIf(String::isNotEmpty)
                )
            "resource_absent" ->
                RaftAvatarNativeLoadResult.Failure(RaftAvatarCacheFailureKind.ResourceAbsent)
            "invalid_content" ->
                RaftAvatarNativeLoadResult.Failure(RaftAvatarCacheFailureKind.InvalidContent)
            "temporary_transport" ->
                RaftAvatarNativeLoadResult.Failure(RaftAvatarCacheFailureKind.TemporaryTransport)
            else -> RaftAvatarNativeLoadResult.Unsupported
        }
    }

    override suspend fun finalize(
        request: RaftAvatarNativeRequest,
        outcome: RaftAvatarNativeFinalizeOutcome
    ): RaftAvatarNativeMutationResult =
        mutation(
            request.toJson()
                .withEntry("action", "finalize")
                .withEntry("outcome", outcome.wireValue)
        )

    override suspend fun rollback(request: RaftAvatarNativeRequest): RaftAvatarNativeMutationResult =
        mutation(request.toJson().withEntry("action", "rollback"))

    override suspend fun retireOwner(nativePartitionKey: String): RaftAvatarNativeMutationResult =
        mutation(
            buildJsonObject {
                put("action", "retireOwner")
                putRaftAvatarManagedSetContract()
                put("nativePartitionKey", nativePartitionKey)
            }
        )

    override suspend fun retireSubject(
        nativePartitionKey: String,
        nativeSubjectKey: String
    ): RaftAvatarNativeMutationResult =
        mutation(
            buildJsonObject {
                put("action", "retireSubject")
                putRaftAvatarManagedSetContract()
                put("nativePartitionKey", nativePartitionKey)
                put("nativeSubjectKey", nativeSubjectKey)
            }
        )

    private suspend fun mutation(command: JsonObject): RaftAvatarNativeMutationResult {
        val response = call(command) ?: return RaftAvatarNativeMutationResult.Unsupported
        if (!response.acknowledgesRaftAvatarManagedSet()) {
            return RaftAvatarNativeMutationResult.Unsupported
        }
        return when (response.optString("status")) {
            "ok" -> RaftAvatarNativeMutationResult.Accepted
            "unsupported" -> RaftAvatarNativeMutationResult.Unsupported
            else ->
                RaftAvatarNativeMutationResult.Failed(
                    response.optString("code").ifBlank { "avatar_cache_native_failed" }
                )
        }
    }

    private suspend fun call(command: JsonObject): JsonObject? {
        val stamped = command.withEntry("protocol", RAFT_AVATAR_NATIVE_CACHE_PROTOCOL)
        return withContext(bridgeCallContext) {
            host.avatarCacheCapability(stamped)
        }
    }
}

internal data class RaftAvatarCacheRequestToken(
    val key: RaftAvatarCacheKey,
    val generation: Long,
    val nonce: String
) {
    init {
        require(generation > 0L)
        RaftAvatarCommitIdentity(generation, nonce)
    }
}

private data class RaftAvatarCacheRequestSlot(
    val owner: RaftAvatarCacheOwner,
    val subject: RaftAvatarCacheSubject
)

internal class RaftAvatarCacheGenerationAuthority(
    private val nonceGenerator: () -> String = ::raftAvatarCommitNonce
) {
    private val lock = SynchronizedObject()
    private var nextGeneration = 0L
    private val selected = mutableMapOf<RaftAvatarCacheRequestSlot, RaftAvatarCacheRequestToken>()
    private val completions = mutableMapOf<RaftAvatarCacheRequestToken, CompletionSlot>()

    fun select(key: RaftAvatarCacheKey): RaftAvatarCacheRequestToken =
        synchronized(lock) {
            val slot = RaftAvatarCacheRequestSlot(key.owner, key.subject)
            selected[slot]?.takeIf { token ->
                token.key == key && completions[token]?.closedForSelection != true
            } ?: run {
                selected.remove(slot)?.let(completions::remove)
                RaftAvatarCacheRequestToken(key, ++nextGeneration, nonceGenerator()).also {
                    selected[slot] = it
                }
            }
        }

    fun isCurrent(token: RaftAvatarCacheRequestToken): Boolean =
        synchronized(lock) {
            selected[RaftAvatarCacheRequestSlot(token.key.owner, token.key.subject)] == token
        }

    /** At most one shared metadata/finalize mutation may execute for a reused request generation. */
    suspend fun completeOnce(
        token: RaftAvatarCacheRequestToken,
        block: suspend () -> RaftAvatarCacheCompletionReceipt
    ): RaftAvatarCacheCompletionReceipt? {
        val completion =
            synchronized(lock) {
                val slot = RaftAvatarCacheRequestSlot(token.key.owner, token.key.subject)
                if (selected[slot] != token) return@synchronized null
                completions.getOrPut(token) { CompletionSlot() }
            } ?: return null
        return completion.mutex.withLock {
            completion.receipt?.let { return@withLock it }
            if (!isCurrent(token)) return@withLock null
            val receipt = block()
            completion.receipt = receipt
            val reusable =
                receipt is RaftAvatarCacheCompletionReceipt.Accepted ||
                    receipt is RaftAvatarCacheCompletionReceipt.Rendered ||
                    receipt is RaftAvatarCacheCompletionReceipt.Suppressed
            synchronized(lock) {
                if (reusable) {
                    // Concurrent consumers that already selected this token still share the
                    // receipt. A later prepare starts a fresh operation so a stale read can
                    // actually revalidate and commit new validators under a new generation.
                    completion.closedForSelection = true
                } else {
                    val slot = RaftAvatarCacheRequestSlot(token.key.owner, token.key.subject)
                    if (selected[slot] == token) selected.remove(slot)
                    if (completions[token] === completion) completions.remove(token)
                }
            }
            receipt
        }
    }

    fun revokeOwner(owner: RaftAvatarCacheOwner) {
        synchronized(lock) {
            selected.keys.filter { it.owner == owner }.forEach { slot ->
                selected.remove(slot)?.let(completions::remove)
            }
        }
    }

    fun revokeSubject(owner: RaftAvatarCacheOwner, subject: RaftAvatarCacheSubject) {
        synchronized(lock) {
            selected.remove(RaftAvatarCacheRequestSlot(owner, subject))?.let(completions::remove)
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
        var receipt: RaftAvatarCacheCompletionReceipt? = null
        var closedForSelection: Boolean = false
    }
}

internal sealed interface RaftAvatarPreparedRender {
    val imageModel: String

    data class Legacy(override val imageModel: String) : RaftAvatarPreparedRender

    class Native internal constructor(
        val token: RaftAvatarCacheRequestToken,
        val request: RaftAvatarNativeRequest,
        internal val nativeCache: RaftAvatarNativeCache
    ) : RaftAvatarPreparedRender {
        override val imageModel: String = request.imageModel
    }
}

internal sealed interface RaftAvatarCacheCompletionReceipt {
    data object Accepted : RaftAvatarCacheCompletionReceipt
    data object Rendered : RaftAvatarCacheCompletionReceipt
    data object Suppressed : RaftAvatarCacheCompletionReceipt
    data object RejectedStaleGeneration : RaftAvatarCacheCompletionReceipt
    data object NativeUnsupported : RaftAvatarCacheCompletionReceipt
    data class Failed(val stage: String, val code: String) : RaftAvatarCacheCompletionReceipt
}

internal enum class RaftAvatarCacheClearStage {
    Native,
    Metadata
}

internal sealed interface RaftAvatarCacheClearReceipt {
    data object Success : RaftAvatarCacheClearReceipt
    data class Failed(val stages: Set<RaftAvatarCacheClearStage>) : RaftAvatarCacheClearReceipt
}

internal data class RaftAvatarOwnerRetirementCapture(
    val owners: Set<RaftAvatarCacheOwner>
)

internal data class RaftAvatarSubjectRetirementCapture(
    val owner: RaftAvatarCacheOwner,
    val subject: RaftAvatarCacheSubject,
    val nativeSubjectKey: String
)

internal class RaftAvatarCacheRuntime(
    private val coordinator: RaftAvatarCacheCoordinator,
    private val currentOwner: suspend (String?) -> RaftAvatarCacheOwner?,
    private val mountedBinding: RaftAvatarMountedBinding? = null,
    private val generationAuthority: RaftAvatarCacheGenerationAuthority = RaftAvatarCacheGenerationAuthority(),
    private val nativeInspectionTimeoutMillis: Long = RAFT_AVATAR_NATIVE_INSPECTION_TIMEOUT_MILLIS,
    private val nativeMutationTimeoutMillis: Long = RAFT_AVATAR_NATIVE_MUTATION_TIMEOUT_MILLIS,
    private val nativeResultTimeoutMillis: Long = RAFT_AVATAR_NATIVE_RESULT_TIMEOUT_MILLIS,
    private val logger: (String) -> Unit = {}
) {
    init {
        require(nativeInspectionTimeoutMillis > 0L)
        require(nativeMutationTimeoutMillis > 0L)
        require(nativeResultTimeoutMillis > 0L)
    }

    suspend fun prepare(
        subject: RaftAvatarCacheSubject,
        remoteUrl: String,
        serverId: String? = null,
        nativeCache: RaftAvatarNativeCache
    ): RaftAvatarPreparedRender {
        val normalizedUrl = remoteUrl.trim()
        val owner =
            runCatchingPreservingCancellation { currentOwner(serverId) }
                .getOrElse {
                    logger("avatar_cache_owner_read_failed")
                    null
                }
                ?: return RaftAvatarPreparedRender.Legacy(normalizedUrl)
        val key =
            runCatching {
                RaftAvatarCacheKey(owner, subject, raftAvatarContentIdentity(normalizedUrl))
            }.getOrElse {
                return RaftAvatarPreparedRender.Legacy(normalizedUrl)
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
                } ?: RaftAvatarNativeInspection.Unsupported
            }.getOrElse {
                logger("avatar_cache_native_inspect_failed")
                RaftAvatarNativeInspection.Unsupported
            }
        if (inspection !is RaftAvatarNativeInspection.Supported) {
            return RaftAvatarPreparedRender.Legacy(normalizedUrl)
        }
        if (!generationAuthority.isCurrent(token)) {
            return RaftAvatarPreparedRender.Legacy(normalizedUrl)
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
                return RaftAvatarPreparedRender.Legacy(normalizedUrl)
            }
        if (!generationAuthority.isCurrent(token)) {
            return RaftAvatarPreparedRender.Legacy(normalizedUrl)
        }
        val (mode, retryAtMillis) = read.decision.toNativeReadMode()
        val committedToIdentity =
            RaftAvatarCommitIdentity(
                commitGeneration = nextAvatarCommitGeneration(read.commitIdentity),
                nonce = token.nonce
            )
        val newModelRevision = nextAvatarModelRevision(read.modelRevision)
        return RaftAvatarPreparedRender.Native(
            token = token,
            request =
                RaftAvatarNativeRequest(
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
        prepared: RaftAvatarPreparedRender.Native
    ): RaftAvatarCacheCompletionReceipt = withContext(NonCancellable) {
        val completion =
            generationAuthority.completeOnce(prepared.token) {
                observeAndCommitOnce(prepared)
            }
        if (completion != null) return@withContext completion
        rollbackNative(prepared.nativeCache, prepared.request)
        RaftAvatarCacheCompletionReceipt.RejectedStaleGeneration
    }

    private suspend fun observeAndCommitOnce(
        prepared: RaftAvatarPreparedRender.Native
    ): RaftAvatarCacheCompletionReceipt {
        val nativeCache = prepared.nativeCache
        if (!generationAuthority.isCurrent(prepared.token)) {
            rollbackNative(nativeCache, prepared.request)
            return RaftAvatarCacheCompletionReceipt.RejectedStaleGeneration
        }
        val result =
            runCatching {
                withTimeoutOrNull(nativeResultTimeoutMillis) {
                    nativeCache.awaitResult(prepared.request)
                }
            }
                .getOrElse {
                    rollbackNative(nativeCache, prepared.request)
                    return RaftAvatarCacheCompletionReceipt.Failed("native_result", "avatar_cache_native_result_failed")
                }
                ?: run {
                    rollbackNative(nativeCache, prepared.request)
                    return RaftAvatarCacheCompletionReceipt.Failed(
                        "native_result",
                        "avatar_cache_native_result_timeout"
                    )
                }
        if (!generationAuthority.isCurrent(prepared.token)) {
            rollbackNative(nativeCache, prepared.request)
            return RaftAvatarCacheCompletionReceipt.RejectedStaleGeneration
        }
        if (result is RaftAvatarNativeLoadResult.Rendered) {
            return RaftAvatarCacheCompletionReceipt.Rendered
        }
        if (result is RaftAvatarNativeLoadResult.Suppressed) {
            return RaftAvatarCacheCompletionReceipt.Suppressed
        }
        if (result is RaftAvatarNativeLoadResult.Unsupported) {
            rollbackNative(nativeCache, prepared.request)
            return RaftAvatarCacheCompletionReceipt.NativeUnsupported
        }
        val guard =
            RaftAvatarCacheCommitGuard(
                key = prepared.token.key,
                expectedCommit =
                    RaftAvatarCacheCommitExpectation(
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
                return RaftAvatarCacheCompletionReceipt.Failed(
                    "metadata",
                    "avatar_cache_metadata_read_failed"
                )
            }
        val mutation =
            runCatching {
                when (result) {
                    is RaftAvatarNativeLoadResult.SuccessfulBytes ->
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
                    is RaftAvatarNativeLoadResult.NotModified ->
                        coordinator.reportNotModified(
                            key = prepared.token.key,
                            expectedCommitIdentity = prepared.request.expectedCommitIdentity,
                            expectedModelRevision = prepared.request.expectedModelRevision,
                            eTag = result.eTag,
                            lastModified = result.lastModified,
                            guard = guard
                        )
                    is RaftAvatarNativeLoadResult.Failure ->
                        coordinator.reportFailure(
                            key = prepared.token.key,
                            kind = result.kind,
                            guard = guard
                        )
                    RaftAvatarNativeLoadResult.Rendered,
                    RaftAvatarNativeLoadResult.Suppressed,
                    RaftAvatarNativeLoadResult.Unsupported -> error("handled above")
                }
            }.getOrElse {
                rollbackNative(nativeCache, prepared.request)
                return RaftAvatarCacheCompletionReceipt.Failed(
                    "metadata",
                    "avatar_cache_metadata_commit_failed"
                )
            }
        if (mutation != RaftAvatarCacheMutationResult.Accepted || !generationAuthority.isCurrent(prepared.token)) {
            rollbackNative(nativeCache, prepared.request)
            return when (mutation) {
                RaftAvatarCacheMutationResult.RejectedStaleGeneration ->
                    RaftAvatarCacheCompletionReceipt.RejectedStaleGeneration
                RaftAvatarCacheMutationResult.MissingCurrentMetadata ->
                    RaftAvatarCacheCompletionReceipt.Failed("metadata", "avatar_cache_metadata_missing")
                is RaftAvatarCacheMutationResult.RejectedTooLarge ->
                    RaftAvatarCacheCompletionReceipt.Failed("metadata", "avatar_cache_entry_too_large")
                RaftAvatarCacheMutationResult.Accepted ->
                    RaftAvatarCacheCompletionReceipt.RejectedStaleGeneration
            }
        }
        val nativeOutcome = result.toNativeFinalizeOutcome()
        val finalized = mutateNative { nativeCache.finalize(prepared.request, nativeOutcome) }
        if (
            finalized == RaftAvatarNativeMutationResult.Accepted &&
            generationAuthority.isCurrent(prepared.token)
        ) {
            return RaftAvatarCacheCompletionReceipt.Accepted
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
            compensation != RaftAvatarCacheMutationResult.Accepted &&
            generationAuthority.isCurrent(prepared.token)
        ) {
            logger("avatar_cache_metadata_compensation_failed")
        }
        if (finalized == RaftAvatarNativeMutationResult.Accepted) {
            return RaftAvatarCacheCompletionReceipt.RejectedStaleGeneration
        }
        return when (finalized) {
            RaftAvatarNativeMutationResult.Unsupported ->
                RaftAvatarCacheCompletionReceipt.Failed("native_finalize", "avatar_cache_native_unsupported")
            is RaftAvatarNativeMutationResult.Failed ->
                RaftAvatarCacheCompletionReceipt.Failed("native_finalize", finalized.code)
            RaftAvatarNativeMutationResult.Accepted -> error("handled above")
            null ->
                RaftAvatarCacheCompletionReceipt.Failed(
                    "native_finalize",
                    "avatar_cache_native_finalize_timeout"
                )
        }
    }

    suspend fun seedMountedSession(
        session: RaftAvatarMountedSession,
        token: RaftAvatarMountToken
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
    ): RaftAvatarOwnerRetirementCapture =
        RaftAvatarOwnerRetirementCapture(raftAvatarCacheOwners(environmentId, session))

    suspend fun captureCurrentSubjectRetirement(
        subject: RaftAvatarCacheSubject,
        serverId: String? = null
    ): RaftAvatarSubjectRetirementCapture? =
        runCatchingPreservingCancellation { currentOwner(serverId) }
            .getOrElse {
                logger("avatar_cache_owner_read_failed")
                null
            }?.let { owner ->
                RaftAvatarSubjectRetirementCapture(
                    owner = owner,
                    subject = subject,
                    nativeSubjectKey = raftAvatarNativeSubjectKey(owner, subject)
                )
            }

    suspend fun retireOwners(
        capture: RaftAvatarOwnerRetirementCapture,
        nativeCache: RaftAvatarNativeCache
    ): RaftAvatarCacheClearReceipt = withContext(NonCancellable) {
        if (capture.owners.isEmpty()) return@withContext RaftAvatarCacheClearReceipt.Success
        val failed = mutableSetOf<RaftAvatarCacheClearStage>()
        capture.owners.forEach { owner ->
            if (!mutateMounted { it.retireOwner(owner) }) {
                failed += RaftAvatarCacheClearStage.Native
            }
            generationAuthority.revokeOwner(owner)
            if (mutateNative { nativeCache.retireOwner(owner.nativePartitionKey) } != RaftAvatarNativeMutationResult.Accepted) {
                failed += RaftAvatarCacheClearStage.Native
            }
            if (runCatching { coordinator.clearOwner(owner) }.isFailure) {
                failed += RaftAvatarCacheClearStage.Metadata
            }
        }
        failed.toClearReceipt()
    }

    suspend fun retireSubject(
        capture: RaftAvatarSubjectRetirementCapture,
        nativeCache: RaftAvatarNativeCache
    ): RaftAvatarCacheClearReceipt = withContext(NonCancellable) {
        val failed = mutableSetOf<RaftAvatarCacheClearStage>()
        if (!mutateMounted { it.retireSubject(capture.owner, capture.subject) }) {
            failed += RaftAvatarCacheClearStage.Native
        }
        generationAuthority.revokeSubject(capture.owner, capture.subject)
        if (
            mutateNative {
                nativeCache.retireSubject(
                    nativePartitionKey = capture.owner.nativePartitionKey,
                    nativeSubjectKey = capture.nativeSubjectKey
                )
            } != RaftAvatarNativeMutationResult.Accepted
        ) {
            failed += RaftAvatarCacheClearStage.Native
        }
        if (runCatching { coordinator.clearSubject(capture.owner, capture.subject) }.isFailure) {
            failed += RaftAvatarCacheClearStage.Metadata
        }
        failed.toClearReceipt()
    }

    private suspend fun rollbackNative(
        nativeCache: RaftAvatarNativeCache,
        request: RaftAvatarNativeRequest
    ) {
        mutateNative { nativeCache.rollback(request) }
    }

    private suspend fun mutateNative(
        block: suspend () -> RaftAvatarNativeMutationResult
    ): RaftAvatarNativeMutationResult? =
        runCatching {
            withTimeoutOrNull(nativeMutationTimeoutMillis) { block() }
        }.getOrNull()

    private suspend fun mutateMounted(
        block: suspend (RaftAvatarMountedBinding) -> Boolean
    ): Boolean =
        mountedBinding?.let { binding ->
            runCatching {
                withTimeoutOrNull(nativeMutationTimeoutMillis) { block(binding) } ?: false
            }.getOrDefault(false)
        } ?: true

    private suspend fun currentNetworkRecoveryOwner(): RaftAvatarCacheOwner? =
        runCatchingPreservingCancellation { currentOwner(null) }
            .getOrElse {
                logger("avatar_cache_network_recovery_owner_read_failed")
                null
            }

    /** Settings image clear: native and shared are both attempted, and partial work is never success. */
    suspend fun clearAll(nativeClear: suspend () -> Boolean): RaftAvatarCacheClearReceipt =
        withContext(NonCancellable) {
            val failed = mutableSetOf<RaftAvatarCacheClearStage>()
            if (!mutateMounted { it.clearAll() }) {
                failed += RaftAvatarCacheClearStage.Native
            }
            generationAuthority.revokeAll()
            if (
                runCatching {
                    withTimeoutOrNull(nativeMutationTimeoutMillis) { nativeClear() } ?: false
                }.getOrDefault(false).not()
            ) {
                failed += RaftAvatarCacheClearStage.Native
            }
            if (runCatching { coordinator.clearAll() }.isFailure) {
                failed += RaftAvatarCacheClearStage.Metadata
            }
            failed.toClearReceipt()
        }
}

private fun raftAvatarCommitNonce(): String =
    raftSha256Hex(Random.Default.nextBytes(ByteArray(32)))

private fun nextAvatarCommitGeneration(expected: RaftAvatarCommitIdentity?): Long {
    val current = expected?.commitGeneration ?: 0L
    check(current < Long.MAX_VALUE) { "avatar commit generation exhausted" }
    return current + 1L
}

private fun nextAvatarModelRevision(current: Long): Long {
    check(current < Long.MAX_VALUE) { "avatar model revision exhausted" }
    return current + 1L
}

internal fun raftAvatarCacheOwners(
    environmentId: String,
    session: StoredAuthSession?
): Set<RaftAvatarCacheOwner> {
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
        RaftAvatarCacheOwner(normalizedEnvironment, accountId, serverId)
    }
}

internal fun raftCurrentAvatarCacheOwner(
    environmentId: String,
    session: StoredAuthSession?,
    requestedServerId: String? = null
): RaftAvatarCacheOwner? {
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
    return RaftAvatarCacheOwner(normalizedEnvironment, accountId, serverId)
}

private fun RaftAvatarCacheReadDecision.toNativeReadMode(): Pair<RaftAvatarNativeReadMode, Long?> =
    when (this) {
        is RaftAvatarCacheReadDecision.RenderFresh -> RaftAvatarNativeReadMode.RenderFresh to null
        is RaftAvatarCacheReadDecision.RenderStaleAndRevalidate ->
            RaftAvatarNativeReadMode.RenderStaleAndRevalidate to null
        is RaftAvatarCacheReadDecision.RenderStaleSuppressed ->
            RaftAvatarNativeReadMode.RenderStaleSuppressed to retryAtMs
        is RaftAvatarCacheReadDecision.Fetch -> RaftAvatarNativeReadMode.Fetch to null
        is RaftAvatarCacheReadDecision.Suppressed -> RaftAvatarNativeReadMode.Suppressed to retryAtMs
    }

private fun RaftAvatarNativeLoadResult.toNativeFinalizeOutcome(): RaftAvatarNativeFinalizeOutcome =
    when (this) {
        is RaftAvatarNativeLoadResult.SuccessfulBytes -> RaftAvatarNativeFinalizeOutcome.SuccessfulBytes
        is RaftAvatarNativeLoadResult.NotModified -> RaftAvatarNativeFinalizeOutcome.NotModified
        is RaftAvatarNativeLoadResult.Failure ->
            when (kind) {
                RaftAvatarCacheFailureKind.ResourceAbsent -> RaftAvatarNativeFinalizeOutcome.ResourceAbsent
                RaftAvatarCacheFailureKind.InvalidContent -> RaftAvatarNativeFinalizeOutcome.InvalidContent
                RaftAvatarCacheFailureKind.TemporaryTransport -> RaftAvatarNativeFinalizeOutcome.TemporaryTransport
            }
        RaftAvatarNativeLoadResult.Rendered,
        RaftAvatarNativeLoadResult.Suppressed,
        RaftAvatarNativeLoadResult.Unsupported -> error("result has no finalize outcome")
    }

private fun Set<RaftAvatarCacheClearStage>.toClearReceipt(): RaftAvatarCacheClearReceipt =
    if (isEmpty()) RaftAvatarCacheClearReceipt.Success else RaftAvatarCacheClearReceipt.Failed(this)
