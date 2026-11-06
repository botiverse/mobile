package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val READ_RECEIPT_PENDING_STORAGE_KEY: String = "readReceiptPending.v1"

/** Reads wait here only until acknowledged, so this is reached only by very long offline stretches. */
private const val READ_RECEIPT_PENDING_MAX_ENTRIES = 512

private val readReceiptPendingJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

data class ReadReceiptPendingEntry(
    val owner: ReadReceiptOwner,
    val channelId: ChannelId,
    val seq: Long
)

@Serializable
private data class PersistedReadReceiptPending(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val channelId: String,
    val seq: Long
)

@Serializable
private data class PersistedReadReceiptPendingDocument(
    val version: Int = 1,
    val entries: List<PersistedReadReceiptPending> = emptyList()
)

/**
 * Reads the server has not acknowledged yet, one per (environment, account, server, channel), at the
 * highest seq read. Only "read to seq" is ever stored: replaying "read all" later would also read
 * messages that arrived after the person looked.
 *
 * Sign-out keeps an account's entries (owner decision 2026-10-27). They are only ever sent under
 * their own owner, which [ReadReceiptCoordinator] checks with its owner validator before each send.
 */
class ReadReceiptPendingStore(
    private val store: SharedPreferenceStore,
    private val maxEntries: Int = READ_RECEIPT_PENDING_MAX_ENTRIES
) {
    private val lock = SynchronizedObject()
    private var cached: List<ReadReceiptPendingEntry>? = null

    fun entries(): List<ReadReceiptPendingEntry> = synchronized(lock) { loadLocked() }

    fun seq(owner: ReadReceiptOwner, channelId: ChannelId): Long? =
        synchronized(lock) {
            loadLocked().firstOrNull { it.owner == owner && it.channelId == channelId }?.seq
        }

    fun seqsFor(owner: ReadReceiptOwner): Map<ChannelId, Long> =
        synchronized(lock) {
            loadLocked().filter { it.owner == owner }.associate { it.channelId to it.seq }
        }

    /** Stores [seq] unless a higher one is already waiting. [seq] must be positive. */
    fun record(owner: ReadReceiptOwner, channelId: ChannelId, seq: Long) {
        if (seq <= 0L) return
        synchronized(lock) {
            val current = loadLocked()
            val existing = current.firstOrNull { it.owner == owner && it.channelId == channelId }
            if (existing != null && existing.seq >= seq) return@synchronized
            val next = current.filterNot { it === existing } + ReadReceiptPendingEntry(owner, channelId, seq)
            saveLocked(next.takeLast(maxEntries.coerceAtLeast(1)))
        }
    }

    /** The server acknowledged through [seq]: forgets the entry unless a higher read is waiting. */
    fun clearThrough(owner: ReadReceiptOwner, channelId: ChannelId, seq: Long) {
        synchronized(lock) {
            val current = loadLocked()
            val next = current.filterNot { it.owner == owner && it.channelId == channelId && it.seq <= seq }
            if (next.size != current.size) saveLocked(next)
        }
    }

    fun remove(owner: ReadReceiptOwner, channelId: ChannelId) {
        synchronized(lock) {
            val current = loadLocked()
            val next = current.filterNot { it.owner == owner && it.channelId == channelId }
            if (next.size != current.size) saveLocked(next)
        }
    }

    /** Removed from (or deleted) [serverId]: [accountId]'s entries go (null = every account's). */
    fun dropServer(serverId: String, accountId: String?) {
        synchronized(lock) {
            val current = loadLocked()
            val next =
                current.filterNot { entry ->
                    entry.owner.serverId == serverId && (accountId == null || entry.owner.accountId == accountId)
                }
            if (next.size != current.size) saveLocked(next)
        }
    }

    private fun loadLocked(): List<ReadReceiptPendingEntry> =
        cached ?: decode(store.getString(READ_RECEIPT_PENDING_STORAGE_KEY)).also { cached = it }

    private fun saveLocked(next: List<ReadReceiptPendingEntry>) {
        cached = next
        store.putString(READ_RECEIPT_PENDING_STORAGE_KEY, if (next.isEmpty()) null else encode(next))
    }

    private fun encode(entries: List<ReadReceiptPendingEntry>): String =
        readReceiptPendingJson.encodeToString(
            PersistedReadReceiptPendingDocument.serializer(),
            PersistedReadReceiptPendingDocument(
                entries =
                    entries.map { entry ->
                        PersistedReadReceiptPending(
                            environmentId = entry.owner.environmentId,
                            accountId = entry.owner.accountId,
                            serverId = entry.owner.serverId,
                            channelId = entry.channelId.value,
                            seq = entry.seq
                        )
                    }
            )
        )

    private fun decode(raw: String?): List<ReadReceiptPendingEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val document =
            runCatching {
                readReceiptPendingJson.decodeFromString(PersistedReadReceiptPendingDocument.serializer(), raw)
            }.getOrNull() ?: return emptyList()
        return document.entries.mapNotNull { entry ->
            val valid =
                entry.environmentId.isNotBlank() &&
                    entry.accountId.isNotBlank() &&
                    entry.serverId.isNotBlank() &&
                    entry.channelId.isNotBlank() &&
                    entry.seq > 0L
            if (!valid) return@mapNotNull null
            ReadReceiptPendingEntry(
                owner = ReadReceiptOwner(entry.environmentId, entry.accountId, entry.serverId),
                channelId = ChannelId(entry.channelId),
                seq = entry.seq
            )
        }
    }
}
