package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// The hover toolbar's reaction button shows the person's most-used reaction on this device
// (botiverse/slock #13955, Feishu-style): clicking toggles it, resting the pointer opens
// every reaction. Usage is counted per user/account on this device in SharedPreferenceStore,
// best effort: any storage error or corrupt payload falls back to 👍 without breaking reacting.

const val RaftDefaultFavoriteReaction: String = "👍"

/** Kept emoji per account; past it the least-used (then oldest) other emoji is evicted. */
const val RaftReactionUsageLimit: Int = 32

const val RaftReactionUsageStoragePrefix: String = "raft:reaction-usage:"

data class RaftReactionUsageEntry(
    val count: Int,
    /** Order of the last use (epoch millis), higher is more recent. */
    val lastUsedAt: Long,
)

typealias RaftReactionUsage = Map<String, RaftReactionUsageEntry>

fun raftReactionUsageStorageKey(ownerKey: String): String = "$RaftReactionUsageStoragePrefix$ownerKey"

/** Most count wins; a tie goes to the most recent; no usage means 👍. */
fun pickFavoriteReaction(usage: RaftReactionUsage): String {
    var favorite = RaftDefaultFavoriteReaction
    var best: RaftReactionUsageEntry? = null
    for ((emoji, entry) in usage) {
        if (emoji.isBlank() || entry.count <= 0) continue
        if (
            best == null ||
            entry.count > best.count ||
            (entry.count == best.count && entry.lastUsedAt > best.lastUsedAt)
        ) {
            favorite = emoji
            best = entry
        }
    }
    return favorite
}

/**
 * Counts one more use of [emoji] at [now]. Past [RaftReactionUsageLimit] emoji, the least-used
 * other emoji (oldest among ties) is dropped so a newly used emoji can always enter.
 */
fun addReactionUsage(
    usage: RaftReactionUsage,
    emoji: String,
    now: Long,
): RaftReactionUsage {
    val cleanEmoji = emoji.trim()
    if (cleanEmoji.isEmpty()) return usage
    val next = linkedMapOf<String, RaftReactionUsageEntry>()
    usage.forEach { (key, entry) ->
        if (key.isNotBlank() && entry.count > 0) {
            next[key] = entry
        }
    }
    val previousCount = next[cleanEmoji]?.count ?: 0
    next[cleanEmoji] = RaftReactionUsageEntry(count = previousCount + 1, lastUsedAt = now)
    val others = next.entries.filter { it.key != cleanEmoji }
    if (others.size < RaftReactionUsageLimit) return next
    val toDropCount = others.size - RaftReactionUsageLimit + 1
    others
        .sortedWith(
            compareBy<Map.Entry<String, RaftReactionUsageEntry>>(
                { it.value.count },
                { it.value.lastUsedAt },
            )
        )
        .take(toDropCount)
        .forEach { next.remove(it.key) }
    return next
}

fun encodeReactionUsage(usage: RaftReactionUsage): String =
    buildJsonObject {
        for ((emoji, entry) in usage) {
            if (emoji.isBlank() || entry.count <= 0) continue
            put(
                emoji,
                buildJsonObject {
                    put("count", entry.count)
                    put("lastUsedAt", entry.lastUsedAt)
                },
            )
        }
    }.toString()

/** Reads [encodeReactionUsage]'s JSON; corrupt payloads or invalid entries are skipped safely. */
fun decodeReactionUsage(raw: String?): RaftReactionUsage {
    if (raw.isNullOrBlank()) return emptyMap()
    val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyMap()
    val decoded = linkedMapOf<String, RaftReactionUsageEntry>()
    for ((emoji, element) in root) {
        if (emoji.isBlank()) continue
        val obj = element as? JsonObject ?: continue
        val countPrimitive = obj["count"] as? JsonPrimitive ?: continue
        if (countPrimitive.isString) continue
        val count = countPrimitive.intOrNull ?: continue
        if (count <= 0) continue
        val lastUsedPrimitive = obj["lastUsedAt"] as? JsonPrimitive ?: continue
        if (lastUsedPrimitive.isString) continue
        val lastUsedAt = lastUsedPrimitive.longOrNull ?: continue
        decoded[emoji] = RaftReactionUsageEntry(count = count, lastUsedAt = lastUsedAt)
    }
    return decoded
}

/**
 * Per-account favorite reaction usage backed by [SharedPreferenceStore] with an in-memory cache so
 * showing a hover toolbar stays fast and storage failures never break reacting.
 */
class RaftReactionFavoriteStore(
    private val storeProvider: () -> SharedPreferenceStore? = {
        runCatching { raftSharedPreferenceStore() }.getOrNull()
    },
    private val nowEpochMillis: () -> Long = { raftNowEpochMillis() },
) {
    private val lock = SynchronizedObject()
    private val usageCache = mutableMapOf<String, RaftReactionUsage>()

    fun readUsage(ownerKey: String?): RaftReactionUsage {
        val owner = normalizeOwner(ownerKey) ?: return emptyMap()
        return synchronized(lock) {
            usageCache[owner] ?: (readFromStoreOrNull(owner) ?: emptyMap()).also { loaded ->
                usageCache[owner] = loaded
            }
        }
    }

    /**
     * The favorite reaction as of now for [ownerKey]. A hover toolbar reads this once when it shows
     * and keeps the snapshot while shown so the button never changes under the pointer.
     */
    fun currentFavoriteReaction(ownerKey: String?): String {
        val owner = normalizeOwner(ownerKey) ?: return RaftDefaultFavoriteReaction
        return pickFavoriteReaction(readUsage(owner))
    }

    /** Counts one addition of [emoji] for [ownerKey] and persists it best-effort. */
    fun recordReactionUsage(ownerKey: String?, emoji: String): RaftReactionUsage {
        val owner = normalizeOwner(ownerKey) ?: return emptyMap()
        val cleanEmoji = emoji.trim()
        if (cleanEmoji.isEmpty()) return readUsage(owner)
        val now = nowEpochMillis()
        return synchronized(lock) {
            val stored = readFromStoreOrNull(owner)
            val base =
                if (!stored.isNullOrEmpty()) {
                    stored
                } else {
                    usageCache[owner] ?: emptyMap()
                }
            val next = addReactionUsage(base, cleanEmoji, now)
            usageCache[owner] = next
            runCatching {
                storeProvider()?.putString(raftReactionUsageStorageKey(owner), encodeReactionUsage(next))
            }
            next
        }
    }

    /** Clears this process's cached counts (used in tests to simulate a fresh launch). */
    fun clearCache() {
        synchronized(lock) {
            usageCache.clear()
        }
    }

    private fun readFromStoreOrNull(owner: String): RaftReactionUsage? =
        runCatching {
            val raw = storeProvider()?.getString(raftReactionUsageStorageKey(owner))
            decodeReactionUsage(raw)
        }.getOrNull()

    private fun normalizeOwner(ownerKey: String?): String? =
        ownerKey?.trim()?.takeIf { it.isNotEmpty() }
}

/** Process-wide [RaftReactionFavoriteStore] backed by [raftSharedPreferenceStore]. */
object RaftReactionFavorites {
    /** Tests swap or replace this with an in-memory store. */
    var store: RaftReactionFavoriteStore = RaftReactionFavoriteStore()
        @RaftCoreInternalApi set
}
