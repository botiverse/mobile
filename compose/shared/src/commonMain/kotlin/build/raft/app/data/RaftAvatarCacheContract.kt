package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.raftCanonicalIdentityBytes
import build.raft.app.raftSha256Hex
import kotlinx.atomicfu.atomic

internal data class RaftAvatarCacheOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String
) {
    init {
        require(environmentId.isNotBlank() && environmentId == environmentId.trim().trimEnd('/'))
        require(accountId.isNotBlank() && accountId == accountId.trim())
        require(serverId.isNotBlank() && serverId == serverId.trim())
    }

    /**
     * Opaque native-cache partition identity. Platform adapters may use it as a directory/cache
     * namespace so owner removal can physically clear bytes without persisting raw owner fields.
     */
    val nativePartitionKey: String =
        raftSha256Hex(
            raftCanonicalIdentityBytes(
                listOf("slock-avatar-owner-v1", environmentId, accountId, serverId)
            )
        )
}

internal sealed interface RaftAvatarCacheSubject {
    val subjectKind: String
    val subjectId: String
    val memberKind: String

    data class Member(
        val kind: MemberKind,
        val memberId: String
    ) : RaftAvatarCacheSubject {
        init {
            require(kind == MemberKind.Human || kind == MemberKind.Agent)
            require(memberId.isNotBlank() && memberId == memberId.trim())
        }

        override val subjectKind: String = "member"
        override val subjectId: String = memberId
        override val memberKind: String = kind.name.lowercase()
    }

    data class Server(
        val serverId: String
    ) : RaftAvatarCacheSubject {
        init {
            require(serverId.isNotBlank() && serverId == serverId.trim())
        }

        override val subjectKind: String = "server"
        override val subjectId: String = serverId
        override val memberKind: String = ""
    }
}

internal enum class RaftAvatarContentKind(val storageValue: String) {
    RaftImmutable("raft_immutable"),
    Gravatar("gravatar"),
    ExternalUrl("external_url");

    companion object {
        fun fromStorage(value: String): RaftAvatarContentKind =
            entries.firstOrNull { it.storageValue == value }
                ?: error("unknown avatar content kind")
    }
}

internal data class RaftAvatarContentIdentity(
    val kind: RaftAvatarContentKind,
    val fingerprint: String
) {
    init {
        require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

internal data class RaftAvatarCacheKey(
    val owner: RaftAvatarCacheOwner,
    val subject: RaftAvatarCacheSubject,
    val content: RaftAvatarContentIdentity
) {
    init {
        if (subject is RaftAvatarCacheSubject.Server) {
            require(subject.serverId == owner.serverId)
        }
    }

    /**
     * Opaque owner-scoped subject identity used only for terminal native retirement. Native byte
     * stores may index every content version beneath this key without learning the account,
     * server, member kind, or member id that produced it.
     */
    val nativeSubjectKey: String = raftAvatarNativeSubjectKey(owner, subject)

    val nativeBytesKey: String =
        raftSha256Hex(
            raftCanonicalIdentityBytes(
                listOf(
                    "slock-avatar-bytes-v1",
                    owner.environmentId,
                    owner.accountId,
                    owner.serverId,
                    subject.subjectKind,
                    subject.memberKind,
                    subject.subjectId,
                    content.kind.storageValue,
                    content.fingerprint
                )
            )
        )
}

internal fun raftAvatarNativeSubjectKey(
    owner: RaftAvatarCacheOwner,
    subject: RaftAvatarCacheSubject
): String =
    raftSha256Hex(
        raftCanonicalIdentityBytes(
            listOf(
                "slock-avatar-subject-v1",
                owner.environmentId,
                owner.accountId,
                owner.serverId,
                subject.subjectKind,
                subject.memberKind,
                subject.subjectId
            )
        )
    )

internal fun raftAvatarContentIdentity(source: String): RaftAvatarContentIdentity {
    val normalized = source.trim()
    require(normalized.startsWith("http://") || normalized.startsWith("https://"))
    val canonical =
        GravatarHttpSource.matchEntire(normalized)?.let { match ->
            val hash = match.groupValues[1].lowercase()
            val responseQuery = match.groupValues[2].takeIf { it.isNotBlank() }
            // Unlike host/scheme changes, every Gravatar query parameter is resource identity:
            // d/r/f and unknown parameters can change availability or representation, while
            // s/size/sz change the returned bytes. Native adapters do not have a separate
            // canonical fetch-size axis in this shared key, so size must remain here as well.
            RaftAvatarContentKind.Gravatar to
                buildString {
                    append("gravatar:")
                    append(hash)
                    responseQuery?.let {
                        append('?')
                        append(it)
                    }
                }
        } ?: run {
            // The host-free immutable key is only granted to Raft's own avatar hosts: any other
            // host serving a look-alike `/avatars/<ns>/<hash>.webp` path would otherwise poison the
            // shared, never-expiring entry of the real avatar. Such URLs keep the URL identity.
            if (!RaftAvatarTrustedHosts.isTrusted(raftAvatarUrlHostOrNull(normalized))) return@run null
            val path = normalized.substringAfter("://").substringAfter('/', "").let { "/$it" }
            RaftAvatarPath.matchEntire(path.substringBefore('?').substringBefore('#'))?.let { match ->
                val namespace = match.groupValues[1].lowercase()
                val filename = match.groupValues[2].lowercase()
                RaftAvatarContentKind.RaftImmutable to "raft-avatar:$namespace:$filename"
            }
        } ?: (RaftAvatarContentKind.ExternalUrl to normalized.substringBefore('#'))
    return RaftAvatarContentIdentity(
        kind = canonical.first,
        fingerprint =
            raftSha256Hex(
                raftCanonicalIdentityBytes(
                    listOf("slock-avatar-content-v1", canonical.second)
                )
            )
    )
}

private val GravatarHttpSource =
    Regex(
        "^https?://(?:[^/]+\\.)?gravatar\\.com/avatar/([0-9A-Fa-f]{32}|[0-9A-Fa-f]{64})(?:\\?([^#]*))?(?:#.*)?$",
        RegexOption.IGNORE_CASE
    )

private val RaftAvatarPath =
    Regex(
        "^/(?:api/)?avatars/([A-Za-z0-9_-]+)/([0-9A-Fa-f]{32,64}\\.webp)$",
        RegexOption.IGNORE_CASE
    )

internal data class RaftAvatarCacheMetadata(
    val key: RaftAvatarCacheKey,
    val eTag: String? = null,
    val lastModified: String? = null,
    val fetchedAtMs: Long,
    val validatedAtMs: Long,
    val freshUntilMs: Long,
    val staleUntilMs: Long,
    val sizeBytes: Long,
    val contentType: String? = null,
    val commitIdentity: RaftAvatarCommitIdentity? = null,
    val modelRevision: Long = 0L
) {
    init {
        require(fetchedAtMs >= 0L)
        require(validatedAtMs >= fetchedAtMs)
        require(freshUntilMs >= validatedAtMs)
        require(staleUntilMs >= freshUntilMs)
        require(sizeBytes in 1..RaftAvatarCachePolicy.MaxEntryBytes)
        require(contentType == null || contentType.startsWith("image/", ignoreCase = true))
        require(modelRevision >= 0L)
        require((commitIdentity == null) == (modelRevision == 0L)) {
            "avatar commit identity and positive model revision must be persisted together"
        }
    }

    val hasExactCommitIdentity: Boolean
        get() = commitIdentity != null
}

internal enum class RaftAvatarCacheFailureKind(val storageValue: String) {
    ResourceAbsent("resource_absent"),
    InvalidContent("invalid_content"),
    TemporaryTransport("temporary_transport");

    companion object {
        fun fromStorage(value: String): RaftAvatarCacheFailureKind =
            entries.firstOrNull { it.storageValue == value }
                ?: error("unknown avatar failure kind")
    }
}

internal data class RaftAvatarCacheFailure(
    val key: RaftAvatarCacheKey,
    val kind: RaftAvatarCacheFailureKind,
    val consecutiveCount: Int,
    val failedAtMs: Long,
    val retryAtMs: Long
) {
    init {
        require(consecutiveCount > 0)
        require(failedAtMs >= 0L)
        require(retryAtMs >= failedAtMs)
    }

    fun blocksAt(nowMs: Long): Boolean = nowMs < retryAtMs
}

internal data class RaftAvatarCacheStoredState(
    val metadata: RaftAvatarCacheMetadata?,
    val failure: RaftAvatarCacheFailure?
)

internal sealed interface RaftAvatarCacheReadDecision {
    data class RenderFresh(val nativeBytesKey: String) : RaftAvatarCacheReadDecision
    data class RenderStaleAndRevalidate(val nativeBytesKey: String) : RaftAvatarCacheReadDecision
    data class RenderStaleSuppressed(
        val nativeBytesKey: String,
        val retryAtMs: Long
    ) : RaftAvatarCacheReadDecision
    data class Fetch(val nativeBytesKey: String) : RaftAvatarCacheReadDecision
    data class Suppressed(val nativeBytesKey: String, val retryAtMs: Long) : RaftAvatarCacheReadDecision
}

internal sealed interface RaftAvatarCacheWriteDecision {
    data class Store(val metadata: RaftAvatarCacheMetadata) : RaftAvatarCacheWriteDecision
    data class RejectTooLarge(val sizeBytes: Long, val maxBytes: Long) : RaftAvatarCacheWriteDecision
}

internal object RaftAvatarCachePolicy {
    const val MaxEntryBytes: Long = 5L * 1024L * 1024L
    const val MaxPlatformBytes: Long = 64L * 1024L * 1024L
    const val FreshTtlMillis: Long = 24L * 60L * 60L * 1000L
    const val StaleRetentionMillis: Long = 30L * 24L * 60L * 60L * 1000L
    const val InitialTemporaryBackoffMillis: Long = 5L * 60L * 1000L
    const val MaxTemporaryBackoffMillis: Long = 60L * 60L * 1000L
    const val ResourceAbsentBackoffMillis: Long = 24L * 60L * 60L * 1000L
    const val InvalidContentBackoffMillis: Long = 60L * 60L * 1000L

    fun decideRead(
        key: RaftAvatarCacheKey,
        stored: RaftAvatarCacheStoredState,
        bytesAvailable: Boolean,
        bytesDecodable: Boolean,
        nowMs: Long
    ): RaftAvatarCacheReadDecision {
        require(nowMs >= 0L)
        val metadata = stored.metadata?.takeIf { it.key == key }
        val failure = stored.failure?.takeIf { it.key == key && it.blocksAt(nowMs) }
        val usableBytes =
            metadata?.hasExactCommitIdentity == true && bytesAvailable && bytesDecodable
        if (!usableBytes) {
            return failure?.let {
                RaftAvatarCacheReadDecision.Suppressed(key.nativeBytesKey, it.retryAtMs)
            } ?: RaftAvatarCacheReadDecision.Fetch(key.nativeBytesKey)
        }
        checkNotNull(metadata)
        return when {
            nowMs < metadata.freshUntilMs ->
                RaftAvatarCacheReadDecision.RenderFresh(key.nativeBytesKey)
            nowMs < metadata.staleUntilMs && failure != null ->
                RaftAvatarCacheReadDecision.RenderStaleSuppressed(key.nativeBytesKey, failure.retryAtMs)
            nowMs < metadata.staleUntilMs ->
                RaftAvatarCacheReadDecision.RenderStaleAndRevalidate(key.nativeBytesKey)
            failure != null ->
                RaftAvatarCacheReadDecision.Suppressed(key.nativeBytesKey, failure.retryAtMs)
            else -> RaftAvatarCacheReadDecision.Fetch(key.nativeBytesKey)
        }
    }

    fun successfulResponse(
        key: RaftAvatarCacheKey,
        nowMs: Long,
        sizeBytes: Long,
        commitIdentity: RaftAvatarCommitIdentity,
        modelRevision: Long,
        eTag: String? = null,
        lastModified: String? = null,
        contentType: String? = null
    ): RaftAvatarCacheWriteDecision {
        require(nowMs >= 0L)
        if (sizeBytes !in 1..MaxEntryBytes) {
            return RaftAvatarCacheWriteDecision.RejectTooLarge(sizeBytes, MaxEntryBytes)
        }
        return RaftAvatarCacheWriteDecision.Store(
            RaftAvatarCacheMetadata(
                key = key,
                eTag = eTag?.trim()?.takeIf(String::isNotEmpty),
                lastModified = lastModified?.trim()?.takeIf(String::isNotEmpty),
                fetchedAtMs = nowMs,
                validatedAtMs = nowMs,
                freshUntilMs = saturatingAdd(nowMs, FreshTtlMillis),
                staleUntilMs = saturatingAdd(nowMs, StaleRetentionMillis),
                sizeBytes = sizeBytes,
                contentType = contentType?.trim()?.takeIf(String::isNotEmpty),
                commitIdentity = commitIdentity,
                modelRevision = modelRevision
            )
        )
    }

    fun notModified(
        existing: RaftAvatarCacheMetadata,
        nowMs: Long,
        eTag: String? = existing.eTag,
        lastModified: String? = existing.lastModified
    ): RaftAvatarCacheMetadata {
        require(nowMs >= existing.validatedAtMs)
        return existing.copy(
            eTag = eTag?.trim()?.takeIf(String::isNotEmpty),
            lastModified = lastModified?.trim()?.takeIf(String::isNotEmpty),
            validatedAtMs = nowMs,
            freshUntilMs = saturatingAdd(nowMs, FreshTtlMillis),
            staleUntilMs = saturatingAdd(nowMs, StaleRetentionMillis)
        )
    }

    fun failure(
        key: RaftAvatarCacheKey,
        kind: RaftAvatarCacheFailureKind,
        previous: RaftAvatarCacheFailure?,
        nowMs: Long
    ): RaftAvatarCacheFailure {
        require(nowMs >= 0L)
        val previousCount = previous?.takeIf { it.key == key && it.kind == kind }?.consecutiveCount ?: 0
        val count = if (previousCount == Int.MAX_VALUE) Int.MAX_VALUE else previousCount + 1
        val delay =
            when (kind) {
                RaftAvatarCacheFailureKind.ResourceAbsent -> ResourceAbsentBackoffMillis
                RaftAvatarCacheFailureKind.InvalidContent -> InvalidContentBackoffMillis
                RaftAvatarCacheFailureKind.TemporaryTransport -> temporaryBackoff(count)
            }
        return RaftAvatarCacheFailure(
            key = key,
            kind = kind,
            consecutiveCount = count,
            failedAtMs = nowMs,
            retryAtMs = saturatingAdd(nowMs, delay)
        )
    }

    private fun temporaryBackoff(consecutiveCount: Int): Long {
        var delay = InitialTemporaryBackoffMillis
        repeat((consecutiveCount - 1).coerceIn(0, 31)) {
            delay = (delay * 2L).coerceAtMost(MaxTemporaryBackoffMillis)
        }
        return delay
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}

/**
 * Hosts allowed to serve content-addressed Raft avatars (`/avatars/<ns>/<hash>.webp`), whose cache
 * identity drops the host. Built in: the production CDN / API / web hosts (current and legacy
 * Slock). Configured: the active environment's API and web hosts plus the `cdn.` host of the API
 * host's parent domain ([configure], called by the shared store provider on environment changes).
 */
internal object RaftAvatarTrustedHosts {
    private val BuiltIn =
        setOf(
            "cdn.raft.build",
            "api.raft.build",
            "app.raft.build",
            "cdn.slock.ai",
            "api.slock.ai",
            "app.slock.ai",
        )
    private val configured = atomic<Set<String>>(emptySet())

    fun configure(apiBaseUrl: String?, webUrl: String?) {
        val hosts = mutableSetOf<String>()
        val apiHost = apiBaseUrl?.let(::raftAvatarUrlHostOrNull)
        apiHost?.let { host ->
            hosts += host
            host.substringAfter('.', "").takeIf { it.contains('.') }?.let { parent -> hosts += "cdn.$parent" }
        }
        webUrl?.let(::raftAvatarUrlHostOrNull)?.let { hosts += it }
        configured.value = hosts
    }

    fun isTrusted(host: String?): Boolean = host != null && (host in BuiltIn || host in configured.value)
}

/** Lower-case host of an http(s) URL; null for anything with user info or an unparsable authority. */
internal fun raftAvatarUrlHostOrNull(url: String): String? {
    val trimmed = url.trim()
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
    val authority =
        trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isEmpty() || '@' in authority || '\\' in authority) return null
    val host = authority.substringBefore(':').lowercase().trimEnd('.')
    return host.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }
}
