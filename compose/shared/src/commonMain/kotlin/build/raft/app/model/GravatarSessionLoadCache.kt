package build.raft.app.model

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal class GravatarSessionLoadCache(
    private val maxEntries: Int = 256,
    private val failureCooldownMillis: Long = DEFAULT_FAILURE_COOLDOWN_MILLIS,
    private val nowMillis: () -> Long = { processOrigin.elapsedNow().inWholeMilliseconds }
) {
    private val lock = SynchronizedObject()
    private val failedUntilByHash = linkedMapOf<String, Long>()
    private val successfulHashes = linkedSetOf<String>()

    init {
        require(maxEntries > 0)
        require(failureCooldownMillis > 0L)
    }

    fun retryDelayMillis(hash: String): Long {
        val normalized = hash.normalizedGravatarHashOrNull() ?: return 0L
        return synchronized(lock) {
            if (normalized in successfulHashes) return@synchronized 0L
            val failedUntil = failedUntilByHash[normalized] ?: return@synchronized 0L
            val remaining = failedUntil - nowMillis()
            if (remaining <= 0L) {
                failedUntilByHash.remove(normalized)
                0L
            } else {
                remaining
            }
        }
    }

    fun recordFailure(hash: String) {
        val normalized = hash.normalizedGravatarHashOrNull() ?: return
        synchronized(lock) {
            if (normalized in successfulHashes) return@synchronized
            failedUntilByHash.remove(normalized)
            failedUntilByHash[normalized] = nowMillis() + failureCooldownMillis
            trimToSize(failedUntilByHash)
        }
    }

    fun recordSuccess(hash: String) {
        val normalized = hash.normalizedGravatarHashOrNull() ?: return
        synchronized(lock) {
            failedUntilByHash.remove(normalized)
            successfulHashes.remove(normalized)
            successfulHashes.add(normalized)
            while (successfulHashes.size > maxEntries) {
                successfulHashes.remove(successfulHashes.first())
            }
        }
    }

    internal fun failureCount(): Int = synchronized(lock) { failedUntilByHash.size }

    private fun trimToSize(entries: MutableMap<String, Long>) {
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    internal companion object {
        const val DEFAULT_FAILURE_COOLDOWN_MILLIS = 5 * 60 * 1_000L
        private val processOrigin = kotlin.time.TimeSource.Monotonic.markNow()
    }
}

internal val raftGravatarSessionLoadCache = GravatarSessionLoadCache()
