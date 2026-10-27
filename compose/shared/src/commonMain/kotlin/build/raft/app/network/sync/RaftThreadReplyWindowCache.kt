package build.raft.app.network.sync

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Durable key/value backing of [RaftThreadReplyWindowCache]; one value per replies page. */
internal interface RaftThreadReplyWindowStorage {
    suspend fun read(pageKey: String): String?

    suspend fun write(pageKey: String, value: String)

    suspend fun clearAll()
}

/** One cached latest-replies window of a parent message. */
internal data class RaftCachedThreadReplyWindow(
    val threadChannelId: String,
    val model: ThreadRepliesReadModel,
)

@Serializable
private data class RaftStoredThreadReplyWindowPage(
    val parents: List<RaftStoredThreadReplyWindow> = emptyList(),
)

@Serializable
private data class RaftStoredThreadReplyWindow(
    val parentMessageId: String,
    val threadChannelId: String,
    val model: ThreadRepliesReadModel,
)

/**
 * Last accepted reply-preview windows (the replies block under a message: count + latest previews),
 * persisted per replies page ([ThreadRepliesPageIdentity]: server + principal + parent scope).
 *
 * Before this cache the windows lived only in the per-screen [ThreadRepliesSyncHost] and the
 * in-memory thread payload cache ([build.raft.app.RaftThreadMessageItem.inlineReplies] is
 * `@Transient`), so after a cold start every channel rendered its messages from the durable V2
 * frame with no replies block, and the block popped in only after the network page AND the
 * secondary summary fetch (Hands dd79da45). The store now seeds the sync host from here when a
 * screen opens, so the first frame shows the last known windows; the authoritative fetch still
 * replaces them.
 *
 * Reads are memoized per page for the process; writes are write-behind on [scope] and always
 * persist the merged (disk + memory) page so a partial in-memory view never clobbers older
 * entries. The principal is part of the page key, so on sign-out only the memory
 * is dropped ([clearMemory]) and the same account reads its pages again (owner decision 2026-10-27, see AuthTerminalLocalDataReset).
 */
internal class RaftThreadReplyWindowCache(
    private val storage: RaftThreadReplyWindowStorage,
    private val scope: CoroutineScope,
    private val maxParentsPerPage: Int = DefaultMaxParentsPerPage,
    private val diagnosticsLogger: (String) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private val pages = mutableMapOf<String, LinkedHashMap<String, RaftCachedThreadReplyWindow>>()
    private val loadedPageKeys = mutableSetOf<String>()
    private val dirtyPageKeys = mutableSetOf<String>()
    private val ioMutex = Mutex()
    private var clearGeneration = 0L
    private var diskWipePending = false

    /** The cached windows of [page] keyed by parent message id (reads disk once per page). */
    suspend fun load(page: ThreadRepliesPageIdentity): Map<String, RaftCachedThreadReplyWindow> {
        if (!page.isValid()) return emptyMap()
        val pageKey = page.key
        synchronized(lock) {
            if (pageKey in loadedPageKeys) return pages[pageKey].orEmpty().toMap()
        }
        ioMutex.withLock { ensureLoadedLocked(pageKey) }
        return synchronized(lock) { pages[pageKey].orEmpty().toMap() }
    }

    /**
     * Records accepted windows (authoritative summaries and realtime frames). A window with no
     * conversation replies left is dropped from the page instead of being stored.
     */
    fun record(projections: Collection<ThreadRepliesAcceptedProjection>) {
        if (projections.isEmpty()) return
        val changedPageKeys = mutableSetOf<String>()
        synchronized(lock) {
            projections.forEach { projection ->
                val identity = projection.identity
                if (!identity.isValid()) return@forEach
                val pageKey = identity.page.key
                val windows = pages.getOrPut(pageKey) { LinkedHashMap() }
                val existing = windows[identity.parentMessageId]
                val next = RaftCachedThreadReplyWindow(identity.threadChannelId, projection.model)
                if (projection.model.replies.isEmpty()) {
                    if (windows.remove(identity.parentMessageId) != null) changedPageKeys += pageKey
                    return@forEach
                }
                if (existing == next) return@forEach
                // Re-insert so the most recently confirmed parents survive the size bound.
                windows.remove(identity.parentMessageId)
                windows[identity.parentMessageId] = next
                while (windows.size > maxParentsPerPage) {
                    windows.remove(windows.keys.first())
                }
                changedPageKeys += pageKey
            }
            dirtyPageKeys += changedPageKeys
        }
        changedPageKeys.forEach { pageKey -> scope.launch { flush(pageKey) } }
    }

    /**
     * Drops every cached window (auth session ended). Memory is cleared synchronously, so it can
     * run inside the auth terminal-revision gate; pending write-behind flushes of the old
     * generation are discarded, and the disk wipe is queued behind any flush already writing.
     */
    fun clearAll() {
        synchronized(lock) {
            clearGeneration += 1
            pages.clear()
            loadedPageKeys.clear()
            dirtyPageKeys.clear()
            diskWipePending = true
        }
        scope.launch { ioMutex.withLock { wipeDiskIfPendingLocked() } }
    }

    /**
     * Sign-out: drops the in-memory windows (and the old session's pending write-behind flushes) but keeps
     * the disk pages; their keys carry the principal, so only the same account reads them again.
     */
    fun clearMemory() {
        synchronized(lock) {
            clearGeneration += 1
            pages.clear()
            loadedPageKeys.clear()
            dirtyPageKeys.clear()
        }
    }

    /** Must run under [ioMutex]: every disk read or write first completes a pending wipe. */
    private suspend fun wipeDiskIfPendingLocked() {
        val pending = synchronized(lock) { diskWipePending.also { diskWipePending = false } }
        if (pending) runCatchingStorage("clear") { storage.clearAll() }
    }

    private suspend fun flush(pageKey: String) {
        ioMutex.withLock {
            ensureLoadedLocked(pageKey)
            val (generation, encoded) =
                synchronized(lock) {
                    if (!dirtyPageKeys.remove(pageKey)) return
                    val parents =
                        pages[pageKey].orEmpty().map { (parentMessageId, window) ->
                            RaftStoredThreadReplyWindow(parentMessageId, window.threadChannelId, window.model)
                        }
                    clearGeneration to
                        StorageJson.encodeToString(
                            RaftStoredThreadReplyWindowPage.serializer(),
                            RaftStoredThreadReplyWindowPage(parents),
                        )
                }
            if (synchronized(lock) { generation != clearGeneration }) return
            runCatchingStorage("write") { storage.write(pageKey, encoded) }
        }
    }

    /** Must run under [ioMutex]. Merges the disk page under the in-memory entries (memory wins). */
    private suspend fun ensureLoadedLocked(pageKey: String) {
        wipeDiskIfPendingLocked()
        val generation = synchronized(lock) {
            if (pageKey in loadedPageKeys) return
            clearGeneration
        }
        val encoded = runCatchingStorage("read") { storage.read(pageKey) }
        val stored =
            encoded?.let { value ->
                runCatching {
                    StorageJson.decodeFromString(RaftStoredThreadReplyWindowPage.serializer(), value)
                }.getOrNull()
            }
        synchronized(lock) {
            if (generation != clearGeneration) return
            val memory = pages[pageKey]
            val merged = LinkedHashMap<String, RaftCachedThreadReplyWindow>()
            stored?.parents?.forEach { parent ->
                if (parent.parentMessageId.isNotBlank() && parent.threadChannelId.isNotBlank()) {
                    merged[parent.parentMessageId] = RaftCachedThreadReplyWindow(parent.threadChannelId, parent.model)
                }
            }
            memory?.forEach { (parentMessageId, window) ->
                merged.remove(parentMessageId)
                merged[parentMessageId] = window
            }
            while (merged.size > maxParentsPerPage) {
                merged.remove(merged.keys.first())
            }
            pages[pageKey] = merged
            loadedPageKeys += pageKey
        }
    }

    private suspend fun <T> runCatchingStorage(operation: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            diagnosticsLogger(
                "thread_reply_window_cache operation=$operation result=failure " +
                    "type=${failure::class.simpleName.orEmpty()}"
            )
            null
        }

    internal companion object {
        const val DefaultMaxParentsPerPage = 64
        private val StorageJson = Json { ignoreUnknownKeys = true }
    }
}
