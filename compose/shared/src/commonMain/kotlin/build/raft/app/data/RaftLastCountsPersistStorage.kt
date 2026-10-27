package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Task #164: a saved last-known copy older than this is not shown (web `lastCounts.ts`, 7 days). */
internal const val RaftLastCountsMaxAgeMillis: Long = 7L * 24 * 60 * 60 * 1_000

/** Task #164: servers whose last-known copy is kept per user (web `lastCounts.ts`, 5, least recently saved go first). */
internal const val RaftLastCountsMaxServersPerUser: Int = 5

/**
 * Durable storage for the last-known copies shown before the network answers (the Home row
 * counts and previews, the Activity first pages): the MVI persist keys of one family, with the
 * limits of the web's `lastCounts.ts` (slock #11156):
 * - every key it writes is recorded in an index with its save time, so [clearAll]
 *   removes the whole family (sign-out keeps the per-user copies (owner decision 2026-10-27, see AuthTerminalLocalDataReset)) without key enumeration (MMKV access is get/put only);
 * - a copy older than [maxAgeMillis] reads as absent and is removed;
 * - per user, the copies of at most [maxServersPerUser] servers are kept; a save drops every key
 *   of that user's least recently saved servers. Other users' copies are never touched.
 *
 * Keys carry `:user:<id>:server:<id>` (every MVI persist key of these families does). A key written
 * before the index existed is adopted, as saved now, the first time it is read.
 */
internal open class RaftLastCountsPersistStorage(
    private val store: SharedPreferenceStore,
    private val indexKey: String,
    private val nowMillis: () -> Long = ::defaultNowMillis,
    private val maxAgeMillis: Long = RaftLastCountsMaxAgeMillis,
    private val maxServersPerUser: Int = RaftLastCountsMaxServersPerUser,
) : MviPersistStorage {
    override fun read(key: String): String? {
        val value = store.getString(key) ?: return null
        val entries = indexEntries()
        val entry = entries.firstOrNull { it.key == key }
        if (entry == null) {
            writeIndex(entries + IndexEntry(key, nowMillis()))
            return value
        }
        if (nowMillis() - entry.savedAtMillis > maxAgeMillis) {
            remove(key)
            return null
        }
        return value
    }

    override fun write(key: String, value: String) {
        store.putString(key, value)
        val now = nowMillis()
        val entries = indexEntries().filterNot { it.key == key } + IndexEntry(key, now)
        writeIndex(pruned(entries, now, writtenKey = key))
    }

    override fun remove(key: String) {
        store.putString(key, null)
        val entries = indexEntries()
        if (entries.any { it.key == key }) writeIndex(entries.filterNot { it.key == key })
    }

    /** Removes every copy written on this device (only one account is signed in at a time). */
    fun clearAll() {
        indexEntries().forEach { store.putString(it.key, null) }
        store.putString(indexKey, null)
    }

    /** Drops expired copies and, for [writtenKey]'s user, the servers past [maxServersPerUser]. */
    private fun pruned(entries: List<IndexEntry>, now: Long, writtenKey: String): List<IndexEntry> {
        val expired = entries.filter { now - it.savedAtMillis > maxAgeMillis }
        val user = scopeOf(writtenKey)?.first
        val evictedServers =
            if (user == null) {
                emptySet()
            } else {
                entries
                    .mapNotNull { entry -> scopeOf(entry.key)?.takeIf { it.first == user }?.let { it.second to entry.savedAtMillis } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, saved) -> saved.max() }
                    .entries
                    .sortedByDescending { it.value }
                    .drop(maxServersPerUser)
                    .mapTo(mutableSetOf()) { it.key }
            }
        val dropped =
            entries.filter { entry ->
                entry in expired ||
                    scopeOf(entry.key)?.let { (entryUser, server) -> entryUser == user && server in evictedServers } == true
            }
        dropped.forEach { store.putString(it.key, null) }
        return entries - dropped.toSet()
    }

    private fun indexEntries(): List<IndexEntry> {
        val encoded = store.getString(indexKey) ?: return emptyList()
        runCatching { IndexJson.decodeFromString(IndexSerializer, encoded) }.getOrNull()?.let { return it }
        // The first index held keys only (task #166): adopt them as saved now.
        val legacyKeys = runCatching { IndexJson.decodeFromString(LegacyIndexSerializer, encoded) }.getOrNull().orEmpty()
        val now = nowMillis()
        return legacyKeys.distinct().map { IndexEntry(it, now) }
    }

    private fun writeIndex(entries: List<IndexEntry>) {
        if (entries.isEmpty()) {
            store.putString(indexKey, null)
        } else {
            store.putString(indexKey, IndexJson.encodeToString(IndexSerializer, entries.distinctBy { it.key }))
        }
    }

    @Serializable
    internal data class IndexEntry(val key: String, val savedAtMillis: Long)

    private companion object {
        val IndexJson = Json { ignoreUnknownKeys = true }
        val IndexSerializer = ListSerializer(IndexEntry.serializer())
        val LegacyIndexSerializer = ListSerializer(String.serializer())
        val ScopePattern = Regex(""":user:([^:]+):server:([^:]+)""")

        fun scopeOf(key: String): Pair<String, String>? =
            ScopePattern.find(key)?.destructured?.let { (user, server) -> user to server }
    }
}

/** The Activity first pages persisted per user, server and filter (`SharedActivityPersistSpec`). */
internal class SharedActivityPersistStorage(
    store: SharedPreferenceStore,
    nowMillis: () -> Long = ::defaultNowMillis,
) : RaftLastCountsPersistStorage(store, indexKey = "mvi:persist:activity:index", nowMillis = nowMillis)
