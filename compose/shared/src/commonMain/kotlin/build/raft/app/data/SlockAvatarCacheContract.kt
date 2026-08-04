package build.raft.app.data

import build.raft.app.model.MemberKind
import build.raft.app.slockCanonicalIdentityBytes
import build.raft.app.slockSha256Hex

internal data class SlockAvatarCacheOwner(
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
        slockSha256Hex(
            slockCanonicalIdentityBytes(
                listOf("slock-avatar-owner-v1", environmentId, accountId, serverId)
            )
        )
}

internal sealed interface SlockAvatarCacheSubject {
    val subjectKind: String
    val subjectId: String
    val memberKind: String

    data class Member(
        val kind: MemberKind,
        val memberId: String
    ) : SlockAvatarCacheSubject {
        init {
            require(memberId.isNotBlank() && memberId == memberId.trim())
        }

        override val subjectKind: String = "member"
        override val subjectId: String = memberId
        override val memberKind: String = kind.name.lowercase()
    }

    data class Server(
        val serverId: String
    ) : SlockAvatarCacheSubject {
        init {
            require(serverId.isNotBlank() && serverId == serverId.trim())
        }

        override val subjectKind: String = "server"
        override val subjectId: String = serverId
        override val memberKind: String = ""
    }
}

internal enum class SlockAvatarContentKind(val storageValue: String) {
    RaftImmutable("raft_immutable"),
    Gravatar("gravatar"),
    ExternalUrl("external_url");

    companion object {
        fun fromStorage(value: String): SlockAvatarContentKind =
            entries.firstOrNull { it.storageValue == value }
                ?: error("unknown avatar content kind")
    }
}

internal data class SlockAvatarContentIdentity(
    val kind: SlockAvatarContentKind,
    val fingerprint: String
) {
    init {
        require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

internal data class SlockAvatarCacheKey(
    val owner: SlockAvatarCacheOwner,
    val subject: SlockAvatarCacheSubject,
    val content: SlockAvatarContentIdentity
) {
    init {
        if (subject is SlockAvatarCacheSubject.Server) {
            require(subject.serverId == owner.serverId)
        }
    }

    val nativeBytesKey: String =
        slockSha256Hex(
            slockCanonicalIdentityBytes(
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

internal fun slockAvatarContentIdentity(source: String): SlockAvatarContentIdentity {
    val normalized = source.trim()
    require(normalized.startsWith("http://") || normalized.startsWith("https://"))
    val canonical =
        GravatarHttpSource.matchEntire(normalized)?.groupValues?.get(1)?.lowercase()?.let {
            SlockAvatarContentKind.Gravatar to "gravatar:$it"
        } ?: run {
            val path = normalized.substringAfter("://").substringAfter('/', "").let { "/$it" }
            RaftAvatarPath.matchEntire(path.substringBefore('?').substringBefore('#'))?.let { match ->
                val namespace = match.groupValues[1].lowercase()
                val filename = match.groupValues[2].lowercase()
                SlockAvatarContentKind.RaftImmutable to "raft-avatar:$namespace:$filename"
            }
        } ?: (SlockAvatarContentKind.ExternalUrl to normalized.substringBefore('#'))
    return SlockAvatarContentIdentity(
        kind = canonical.first,
        fingerprint =
            slockSha256Hex(
                slockCanonicalIdentityBytes(
                    listOf("slock-avatar-content-v1", canonical.second)
                )
            )
    )
}

private val GravatarHttpSource =
    Regex(
        "^https?://(?:[^/]+\\.)?gravatar\\.com/avatar/([0-9A-Fa-f]{32}|[0-9A-Fa-f]{64})(?:[?#].*)?$",
        RegexOption.IGNORE_CASE
    )

private val RaftAvatarPath =
    Regex(
        "^/(?:api/)?avatars/([A-Za-z0-9_-]+)/([0-9A-Fa-f]{32,64}\\.webp)$",
        RegexOption.IGNORE_CASE
    )

internal data class SlockAvatarCacheMetadata(
    val key: SlockAvatarCacheKey,
    val eTag: String? = null,
    val lastModified: String? = null,
    val fetchedAtMs: Long,
    val validatedAtMs: Long,
    val freshUntilMs: Long,
    val staleUntilMs: Long,
    val sizeBytes: Long,
    val contentType: String? = null
) {
    init {
        require(fetchedAtMs >= 0L)
        require(validatedAtMs >= fetchedAtMs)
        require(freshUntilMs >= validatedAtMs)
        require(staleUntilMs >= freshUntilMs)
        require(sizeBytes in 1..SlockAvatarCachePolicy.MaxEntryBytes)
        require(contentType == null || contentType.startsWith("image/", ignoreCase = true))
    }
}

internal enum class SlockAvatarCacheFailureKind(val storageValue: String) {
    ResourceAbsent("resource_absent"),
    InvalidContent("invalid_content"),
    TemporaryTransport("temporary_transport");

    companion object {
        fun fromStorage(value: String): SlockAvatarCacheFailureKind =
            entries.firstOrNull { it.storageValue == value }
                ?: error("unknown avatar failure kind")
    }
}

internal data class SlockAvatarCacheFailure(
    val key: SlockAvatarCacheKey,
    val kind: SlockAvatarCacheFailureKind,
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

internal data class SlockAvatarCacheStoredState(
    val metadata: SlockAvatarCacheMetadata?,
    val failure: SlockAvatarCacheFailure?
)

internal sealed interface SlockAvatarCacheReadDecision {
    data class RenderFresh(val nativeBytesKey: String) : SlockAvatarCacheReadDecision
    data class RenderStaleAndRevalidate(val nativeBytesKey: String) : SlockAvatarCacheReadDecision
    data class RenderStaleSuppressed(
        val nativeBytesKey: String,
        val retryAtMs: Long
    ) : SlockAvatarCacheReadDecision
    data class Fetch(val nativeBytesKey: String) : SlockAvatarCacheReadDecision
    data class Suppressed(val nativeBytesKey: String, val retryAtMs: Long) : SlockAvatarCacheReadDecision
}

internal sealed interface SlockAvatarCacheWriteDecision {
    data class Store(val metadata: SlockAvatarCacheMetadata) : SlockAvatarCacheWriteDecision
    data class RejectTooLarge(val sizeBytes: Long, val maxBytes: Long) : SlockAvatarCacheWriteDecision
}

internal object SlockAvatarCachePolicy {
    const val MaxEntryBytes: Long = 5L * 1024L * 1024L
    const val MaxPlatformBytes: Long = 64L * 1024L * 1024L
    const val FreshTtlMillis: Long = 24L * 60L * 60L * 1000L
    const val StaleRetentionMillis: Long = 30L * 24L * 60L * 60L * 1000L
    const val InitialTemporaryBackoffMillis: Long = 5L * 60L * 1000L
    const val MaxTemporaryBackoffMillis: Long = 60L * 60L * 1000L
    const val ResourceAbsentBackoffMillis: Long = 24L * 60L * 60L * 1000L
    const val InvalidContentBackoffMillis: Long = 60L * 60L * 1000L

    fun decideRead(
        key: SlockAvatarCacheKey,
        stored: SlockAvatarCacheStoredState,
        bytesAvailable: Boolean,
        bytesDecodable: Boolean,
        nowMs: Long
    ): SlockAvatarCacheReadDecision {
        require(nowMs >= 0L)
        val metadata = stored.metadata?.takeIf { it.key == key }
        val failure = stored.failure?.takeIf { it.key == key && it.blocksAt(nowMs) }
        val usableBytes = metadata != null && bytesAvailable && bytesDecodable
        if (!usableBytes) {
            return failure?.let {
                SlockAvatarCacheReadDecision.Suppressed(key.nativeBytesKey, it.retryAtMs)
            } ?: SlockAvatarCacheReadDecision.Fetch(key.nativeBytesKey)
        }
        checkNotNull(metadata)
        return when {
            nowMs < metadata.freshUntilMs ->
                SlockAvatarCacheReadDecision.RenderFresh(key.nativeBytesKey)
            nowMs < metadata.staleUntilMs && failure != null ->
                SlockAvatarCacheReadDecision.RenderStaleSuppressed(key.nativeBytesKey, failure.retryAtMs)
            nowMs < metadata.staleUntilMs ->
                SlockAvatarCacheReadDecision.RenderStaleAndRevalidate(key.nativeBytesKey)
            failure != null ->
                SlockAvatarCacheReadDecision.Suppressed(key.nativeBytesKey, failure.retryAtMs)
            else -> SlockAvatarCacheReadDecision.Fetch(key.nativeBytesKey)
        }
    }

    fun successfulResponse(
        key: SlockAvatarCacheKey,
        nowMs: Long,
        sizeBytes: Long,
        eTag: String? = null,
        lastModified: String? = null,
        contentType: String? = null
    ): SlockAvatarCacheWriteDecision {
        require(nowMs >= 0L)
        if (sizeBytes !in 1..MaxEntryBytes) {
            return SlockAvatarCacheWriteDecision.RejectTooLarge(sizeBytes, MaxEntryBytes)
        }
        return SlockAvatarCacheWriteDecision.Store(
            SlockAvatarCacheMetadata(
                key = key,
                eTag = eTag?.trim()?.takeIf(String::isNotEmpty),
                lastModified = lastModified?.trim()?.takeIf(String::isNotEmpty),
                fetchedAtMs = nowMs,
                validatedAtMs = nowMs,
                freshUntilMs = saturatingAdd(nowMs, FreshTtlMillis),
                staleUntilMs = saturatingAdd(nowMs, StaleRetentionMillis),
                sizeBytes = sizeBytes,
                contentType = contentType?.trim()?.takeIf(String::isNotEmpty)
            )
        )
    }

    fun notModified(
        existing: SlockAvatarCacheMetadata,
        nowMs: Long,
        eTag: String? = existing.eTag,
        lastModified: String? = existing.lastModified
    ): SlockAvatarCacheMetadata {
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
        key: SlockAvatarCacheKey,
        kind: SlockAvatarCacheFailureKind,
        previous: SlockAvatarCacheFailure?,
        nowMs: Long
    ): SlockAvatarCacheFailure {
        require(nowMs >= 0L)
        val previousCount = previous?.takeIf { it.key == key && it.kind == kind }?.consecutiveCount ?: 0
        val count = if (previousCount == Int.MAX_VALUE) Int.MAX_VALUE else previousCount + 1
        val delay =
            when (kind) {
                SlockAvatarCacheFailureKind.ResourceAbsent -> ResourceAbsentBackoffMillis
                SlockAvatarCacheFailureKind.InvalidContent -> InvalidContentBackoffMillis
                SlockAvatarCacheFailureKind.TemporaryTransport -> temporaryBackoff(count)
            }
        return SlockAvatarCacheFailure(
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
