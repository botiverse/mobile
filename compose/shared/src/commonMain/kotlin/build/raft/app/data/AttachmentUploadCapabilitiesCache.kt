package build.raft.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Default freshness window for cached upload capabilities (plan limits change rarely). */
internal const val ATTACHMENT_UPLOAD_CAPABILITIES_TTL_MS: Long = 5L * 60L * 1000L

/**
 * Per-authority (account + server + environment) cache of `attachments/upload-capabilities`.
 *
 * Picking an attachment must not wait on this request: on a weak network it can take many
 * seconds (DNS + connect + TLS), and the composer chip is admitted before it is needed. Callers
 * use [peek] on the pick path (never suspends, never touches the network) and [getOrFetch] from the
 * background upload task. The composer prefetches on open so the first pick usually hits the cache.
 * Only successful results are cached; errors are always retried by the next caller.
 */
internal class AttachmentUploadCapabilitiesCache(
    private val nowMillis: () -> Long,
    private val ttlMillis: Long = ATTACHMENT_UPLOAD_CAPABILITIES_TTL_MS,
) {
    private data class Entry(
        val key: String,
        val capabilities: AttachmentUploadCapabilities,
        val fetchedAtMillis: Long,
    )

    private var entry: Entry? = null
    private val fetchLock = Mutex()

    /** Fresh cached value for [authority], or null. Never suspends. */
    fun peek(authority: RaftExpectedAuthBinding): AttachmentUploadCapabilities? =
        freshEntry(keyFor(authority))?.capabilities

    /**
     * Cached value if fresh, otherwise [fetch] (single-flight: concurrent callers queue behind the
     * first request and then read its result instead of issuing their own).
     */
    suspend fun getOrFetch(
        authority: RaftExpectedAuthBinding,
        fetch: suspend () -> DataResult<AttachmentUploadCapabilities>,
    ): DataResult<AttachmentUploadCapabilities> {
        val key = keyFor(authority)
        freshEntry(key)?.let { return DataResult.Content(it.capabilities) }
        return fetchLock.withLock {
            freshEntry(key)?.let { return@withLock DataResult.Content(it.capabilities) }
            val result = fetch()
            if (result is DataResult.Content) {
                entry = Entry(key, result.data, nowMillis())
            }
            result
        }
    }

    fun clear() {
        entry = null
    }

    private fun freshEntry(key: String): Entry? {
        val current = entry ?: return null
        if (current.key != key) return null
        val age = nowMillis() - current.fetchedAtMillis
        return current.takeIf { age in 0L until ttlMillis }
    }

    private fun keyFor(authority: RaftExpectedAuthBinding): String =
        "${authority.environmentId}|${authority.serverId}|${authority.accountId}"
}
