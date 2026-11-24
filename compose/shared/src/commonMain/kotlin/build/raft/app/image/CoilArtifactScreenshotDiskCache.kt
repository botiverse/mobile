package build.raft.app.data

import build.raft.app.image.raftCurrentImageLoader
import build.raft.app.raftSha256Hex
import coil3.disk.DiskCache
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
private data class ArtifactScreenshotDiskIndexEntry(
    val environmentId: String = "",
    val accountId: String,
    val diskKey: String,
)

/**
 * Stores Artifact screenshot bytes inside Coil's shared 128 MiB [DiskCache], keyed by
 * `sha256(env|account|server|cardId|fileId)` with a small per-server index in [preferences] so
 * server access loss can evict one server's entries cleanly while sign-out wipes the disk cache
 * and clears tracked index keys.
 *
 * Uses a synchronous monotonic fence clock (`fenceLock`) + serialized `Dispatchers.Default` I/O
 * (`ioMutex`) so write-after-evict and sign-out/re-login races are impossible, and records pending
 * Coil removals in [preferences] if `clearServer`/`remove`/`clearAll` runs before Coil's singleton
 * [DiskCache] is built.
 *
 * Lives under `image/` (UI/Coil layer) so `:shared-data` stays Coil-free.
 */
internal class CoilArtifactScreenshotDiskCache(
    private val preferences: SharedPreferenceStore,
    private val diskCacheProvider: () -> DiskCache? = { raftCurrentImageLoader()?.diskCache },
    private val maxEntriesPerServer: Int = DefaultMaxEntriesPerServer,
) : ArtifactScreenshotDiskCache {
    private val fenceLock = SynchronizedObject()
    private val ioMutex = Mutex()
    private var clock = 0L
    private var clearedAllAt = 0L
    private var pendingClearAll = false
    private val serverClearedAt = HashMap<String, Long>()
    private val accountServerClearedAt = HashMap<Pair<String, String>, Long>()

    override fun captureWriteToken(): Long = synchronized(fenceLock) { ++clock }

    override fun fenceServer(serverId: String, accountId: String?) {
        val serverKey = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val accountFilter = accountId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(fenceLock) {
            val tick = ++clock
            if (accountFilter == null) {
                serverClearedAt[serverKey] = tick
            } else {
                accountServerClearedAt[accountFilter to serverKey] = tick
            }
        }
    }

    override fun fenceAll() {
        synchronized(fenceLock) {
            clearedAllAt = ++clock
        }
    }

    private fun enqueueClearAll() {
        synchronized(fenceLock) {
            clearedAllAt = ++clock
            pendingClearAll = true
        }
    }

    /**
     * Non-suspending sign-out helper (mirrors `RaftOfflineResourceCache.wipeAllAsync`): synchronously
     * fences prior writes and queues the full clear so any subsequent session operation drains it
     * first under [ioMutex], then kicks off background execution without ever re-fencing later writes.
     */
    fun clearAllAsync(scope: CoroutineScope) {
        enqueueClearAll()
        scope.launch { drainPendingClearAllOnDefault() }
    }

    private fun isFenced(serverKey: String, accountId: String, writeToken: Long): Boolean =
        synchronized(fenceLock) {
            writeToken <= clearedAllAt ||
                writeToken <= (serverClearedAt[serverKey] ?: 0L) ||
                writeToken <= (accountServerClearedAt[accountId to serverKey] ?: 0L)
        }

    internal fun diskKey(owner: ArtifactScreenshotDiskOwner, key: ArtifactScreenshotKey): String {
        val normalized = owner.normalizedOrNull() ?: owner
        val raw =
            "${normalized.environmentId}|${normalized.accountId}|" +
                "${key.serverId.trim()}|${key.cardId.trim()}|${key.fileId.trim()}"
        return "$DiskKeyPrefix${raftSha256Hex(raw.encodeToByteArray())}"
    }

    override suspend fun read(owner: ArtifactScreenshotDiskOwner, key: ArtifactScreenshotKey): ByteArray? {
        val normalized = owner.normalizedOrNull() ?: return null
        val entryKey = diskKey(normalized, key)
        return withContext(Dispatchers.Default) {
            ioMutex.withLock {
                val cache = diskCacheProvider()
                drainPendingClearAllLocked(cache)
                if (cache == null) return@withLock null
                drainPendingEvictionsLocked(cache)
                val snapshot = runCatching { cache.openSnapshot(entryKey) }.getOrNull() ?: return@withLock null
                try {
                    runCatching { cache.fileSystem.read(snapshot.data) { readByteArray() } }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                } finally {
                    runCatching { snapshot.close() }
                }
            }
        }
    }

    override suspend fun write(
        owner: ArtifactScreenshotDiskOwner,
        key: ArtifactScreenshotKey,
        bytes: ByteArray,
        writeToken: Long,
    ) {
        val normalized = owner.normalizedOrNull() ?: return
        if (bytes.isEmpty() || bytes.size > ARTIFACT_SCREENSHOT_MAX_FILE_BYTES) return
        val serverKey = key.serverId.trim().takeIf { it.isNotEmpty() } ?: return
        if (isFenced(serverKey, normalized.accountId, writeToken)) return
        val entryKey = diskKey(normalized, key)
        withContext(Dispatchers.Default) {
            ioMutex.withLock {
                val cache = diskCacheProvider()
                drainPendingClearAllLocked(cache)
                if (cache == null) return@withLock
                if (isFenced(serverKey, normalized.accountId, writeToken)) return@withLock
                drainPendingEvictionsLocked(cache)
                if (isFenced(serverKey, normalized.accountId, writeToken)) return@withLock
                val editor = runCatching { cache.openEditor(entryKey) }.getOrNull() ?: return@withLock
                val committed =
                    runCatching {
                        cache.fileSystem.write(editor.data) { write(bytes) }
                        editor.commit()
                        true
                    }.getOrElse {
                        runCatching { editor.abort() }
                        false
                    }
                if (!committed) return@withLock
                if (isFenced(serverKey, normalized.accountId, writeToken)) {
                    runCatching { cache.remove(entryKey) }
                    return@withLock
                }
                val evictedKeys = recordIndexEntryLocked(serverKey, normalized.environmentId, normalized.accountId, entryKey)
                evictedKeys.forEach { victim -> runCatching { cache.remove(victim) } }
            }
        }
    }

    override suspend fun remove(
        owner: ArtifactScreenshotDiskOwner,
        key: ArtifactScreenshotKey,
        writeToken: Long,
    ) {
        val normalized = owner.normalizedOrNull() ?: return
        val serverKey = key.serverId.trim().takeIf { it.isNotEmpty() } ?: return
        if (isFenced(serverKey, normalized.accountId, writeToken)) return
        val entryKey = diskKey(normalized, key)
        withContext(Dispatchers.Default) {
            ioMutex.withLock {
                val cache = diskCacheProvider()
                drainPendingClearAllLocked(cache)
                if (isFenced(serverKey, normalized.accountId, writeToken)) return@withLock
                if (cache != null) {
                    drainPendingEvictionsLocked(cache)
                    runCatching { cache.remove(entryKey) }
                } else {
                    appendPendingEvictionsLocked(listOf(entryKey))
                }
                removeIndexKeyLocked(serverKey, entryKey)
            }
        }
    }

    override suspend fun clearServer(serverId: String, accountId: String?, environmentId: String?) {
        val serverKey = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val accountFilter = accountId?.trim()?.takeIf { it.isNotEmpty() }
        val envFilter = environmentId?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        fenceServer(serverKey, accountFilter)
        withContext(Dispatchers.Default) {
            ioMutex.withLock {
                val cache = diskCacheProvider()
                drainPendingClearAllLocked(cache)
                val entries = readServerIndexLocked(serverKey)
                val (drop, keep) =
                    entries.partition { entry ->
                        (accountFilter == null || entry.accountId == accountFilter) &&
                            (envFilter == null || entry.environmentId.isEmpty() || entry.environmentId == envFilter)
                    }
                writeServerIndexLocked(serverKey, keep)
                val victims = drop.map { it.diskKey }
                if (cache != null) {
                    drainPendingEvictionsLocked(cache)
                    victims.forEach { victim -> runCatching { cache.remove(victim) } }
                } else if (victims.isNotEmpty()) {
                    appendPendingEvictionsLocked(victims)
                }
            }
        }
    }

    override suspend fun clearAll() {
        enqueueClearAll()
        drainPendingClearAllOnDefault()
    }

    private suspend fun drainPendingClearAllOnDefault() {
        withContext(Dispatchers.Default) {
            ioMutex.withLock {
                drainPendingClearAllLocked(diskCacheProvider())
            }
        }
    }

    private fun drainPendingClearAllLocked(cache: DiskCache?) {
        val shouldClear =
            synchronized(fenceLock) {
                if (pendingClearAll) {
                    pendingClearAll = false
                    true
                } else {
                    false
                }
            }
        if (!shouldClear) return
        val trackedServers = readStringListLocked(TrackedServersKey)
        val victims = LinkedHashSet<String>()
        for (serverKey in trackedServers) {
            readServerIndexLocked(serverKey).forEach { victims += it.diskKey }
            runCatching { preferences.putString(ServerIndexKeyPrefix + serverKey, null) }
        }
        runCatching { preferences.putString(TrackedServersKey, null) }
        if (cache != null) {
            drainPendingEvictionsLocked(cache)
            victims.forEach { victim -> runCatching { cache.remove(victim) } }
        } else if (victims.isNotEmpty()) {
            appendPendingEvictionsLocked(victims.toList())
        }
    }

    private fun drainPendingEvictionsLocked(cache: DiskCache) {
        val pending = readStringListLocked(PendingEvictKey)
        if (pending.isEmpty()) return
        pending.forEach { victim -> runCatching { cache.remove(victim) } }
        runCatching { preferences.putString(PendingEvictKey, null) }
    }

    private fun appendPendingEvictionsLocked(victims: List<String>) {
        if (victims.isEmpty()) return
        val merged = LinkedHashSet<String>(readStringListLocked(PendingEvictKey))
        merged.addAll(victims)
        writeStringListLocked(PendingEvictKey, merged.toList())
    }

    private fun recordIndexEntryLocked(
        serverKey: String,
        environmentId: String,
        accountId: String,
        entryKey: String,
    ): List<String> {
        val current = readServerIndexLocked(serverKey).filterNot { it.diskKey == entryKey }.toMutableList()
        current += ArtifactScreenshotDiskIndexEntry(environmentId = environmentId, accountId = accountId, diskKey = entryKey)
        // Enforce cap per (environmentId, accountId) within this server so accounts/envs don't evict each other.
        val victims = mutableListOf<String>()
        var sameScopeCount = current.count { it.environmentId == environmentId && it.accountId == accountId }
        val iter = current.iterator()
        while (sameScopeCount > maxEntriesPerServer && iter.hasNext()) {
            val candidate = iter.next()
            if (candidate.environmentId == environmentId && candidate.accountId == accountId) {
                victims += candidate.diskKey
                iter.remove()
                sameScopeCount -= 1
            }
        }
        writeServerIndexLocked(serverKey, current)
        return victims
    }

    private fun removeIndexKeyLocked(serverKey: String, entryKey: String) {
        val current = readServerIndexLocked(serverKey)
        val next = current.filterNot { it.diskKey == entryKey }
        if (next.size != current.size) writeServerIndexLocked(serverKey, next)
    }

    private fun readServerIndexLocked(serverKey: String): List<ArtifactScreenshotDiskIndexEntry> =
        runCatching { preferences.getString(ServerIndexKeyPrefix + serverKey) }
            .getOrNull()
            ?.let { encoded -> runCatching { IndexJson.decodeFromString(EntryListSerializer, encoded) }.getOrNull() }
            .orEmpty()

    private fun writeServerIndexLocked(serverKey: String, entries: List<ArtifactScreenshotDiskIndexEntry>) {
        runCatching {
            val tracked = readStringListLocked(TrackedServersKey).toMutableList()
            if (entries.isEmpty()) {
                preferences.putString(ServerIndexKeyPrefix + serverKey, null)
                if (tracked.remove(serverKey)) {
                    writeStringListLocked(TrackedServersKey, tracked)
                }
            } else {
                preferences.putString(
                    ServerIndexKeyPrefix + serverKey,
                    IndexJson.encodeToString(EntryListSerializer, entries),
                )
                if (serverKey !in tracked) {
                    tracked += serverKey
                    writeStringListLocked(TrackedServersKey, tracked)
                }
            }
        }
    }

    private fun readStringListLocked(prefKey: String): List<String> =
        runCatching { preferences.getString(prefKey) }
            .getOrNull()
            ?.let { encoded -> runCatching { IndexJson.decodeFromString(StringListSerializer, encoded) }.getOrNull() }
            .orEmpty()

    private fun writeStringListLocked(prefKey: String, items: List<String>) {
        runCatching {
            if (items.isEmpty()) {
                preferences.putString(prefKey, null)
            } else {
                preferences.putString(prefKey, IndexJson.encodeToString(StringListSerializer, items))
            }
        }
    }

    internal companion object {
        const val DiskKeyPrefix = "raft-artifact-shot-v1:"
        const val ServerIndexKeyPrefix = "raft:artifact-screenshots:v1:server:"
        const val TrackedServersKey = "raft:artifact-screenshots:v1:servers"
        const val PendingEvictKey = "raft:artifact-screenshots:v1:pending-evict"
        const val DefaultMaxEntriesPerServer = 256
        private val IndexJson = Json { ignoreUnknownKeys = true }
        private val EntryListSerializer = ListSerializer(ArtifactScreenshotDiskIndexEntry.serializer())
        private val StringListSerializer = ListSerializer(String.serializer())
    }
}
