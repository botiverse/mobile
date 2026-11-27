package build.raft.app.shell

import build.raft.app.data.RaftLastCountsPersistStorage
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.defaultNowMillis
import build.raft.app.thread.key
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * The device's storage of what was open beside each conversation (RaftBesideMemory.kt; web besideMemory.ts's storage
 * half). One entry per person and server in the shared preference store (AGENTS.md "Local Preferences"):
 *
 *   besideMemory:v1:user:<accountId>:server:<serverId>
 *   {"v":1,"conversations":{"<conversationId>":{"thread":"<messageId>","artifact":{"id":"<id>","version":3},
 *    "profile":"agent:<id>","agentTab":"activity","front":"artifact","at":1794480000000}}}
 *
 * - At most RAFT_BESIDE_MEMORY_MAX_CONVERSATIONS conversations per entry, the least recently written dropped first.
 * - Data that does not parse, or of another version, reads as nothing and is replaced by the next write. A storage
 *   that throws reads as nothing and is never written. Ids are checked (isRaftBesideId): no one signed in, no server,
 *   or an id that is no id reads and writes nothing.
 * - Every entry written is listed in an index (RaftLastCountsPersistStorage, as the last-known copies are: the store
 *   offers get and put only), so losing access to a server drops that server's entries (AccountLocalData). Sign-out
 *   keeps them, as every account-keyed copy (owner decision 2026-10-27; the web forgets them): another account never
 *   reads them, the key is the account's.
 */

/** At most this many conversations are remembered per person and server (web `BESIDE_MEMORY_MAX_CONVERSATIONS`). */
internal const val RAFT_BESIDE_MEMORY_MAX_CONVERSATIONS: Int = 200

/** Servers whose entry is kept per person (the index's bound, as for the notification preference): more than anyone uses. */
private const val RAFT_BESIDE_MEMORY_MAX_SERVERS: Int = 64

private const val RAFT_BESIDE_MEMORY_VERSION: Int = 1

/** Lists every entry written ([RaftLastCountsPersistStorage]). */
internal const val RAFT_BESIDE_MEMORY_INDEX_KEY: String = "besideMemory:v1:index"

/** One person's entry on one server; the index reads its `:user:<id>:server:<id>` scope. */
internal fun raftBesideMemoryKey(accountId: String, serverId: String): String = "besideMemory:v1:user:$accountId:server:$serverId"

/** One conversation's remembered state, and when it was last written ([at], ms): the oldest go first. */
internal data class RaftBesideStoredEntry(val state: RaftBesideState, val at: Long)

/** The entries kept: the [max] most recently written (web `pruneBesideEntries`). */
internal fun raftPruneBesideEntries(
    conversations: Map<String, RaftBesideStoredEntry>,
    max: Int = RAFT_BESIDE_MEMORY_MAX_CONVERSATIONS,
): Map<String, RaftBesideStoredEntry> {
    if (conversations.size <= max) return conversations
    return conversations.entries.sortedByDescending { it.value.at }.take(maxOf(0, max)).associate { it.key to it.value }
}

/**
 * The conversations of one stored entry (web `readStored`), each read as [normalized] does: an entry that does not
 * parse, or of another version, is no conversation; a conversation whose id is no id, or with nothing well-formed
 * open, is left out. An `at` that is no number reads as 0.
 */
internal fun decodeRaftBesideMemory(raw: String?): Map<String, RaftBesideStoredEntry> {
    if (raw.isNullOrBlank()) return emptyMap()
    val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyMap()
    val version = (root["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    val conversations = root["conversations"] as? JsonObject
    if (version != RAFT_BESIDE_MEMORY_VERSION || conversations == null) return emptyMap()
    val kept = LinkedHashMap<String, RaftBesideStoredEntry>()
    for ((id, element) in conversations) {
        if (!isRaftBesideId(id)) continue
        val entry = element as? JsonObject ?: continue
        val state = entry.toRaftBesideState()
        if (state.isEmpty) continue
        kept[id] = RaftBesideStoredEntry(state, entry.besideWholeNumber("at") ?: 0L)
    }
    return kept
}

/** [conversations] as one stored entry (the shape [decodeRaftBesideMemory] reads). */
internal fun encodeRaftBesideMemory(conversations: Map<String, RaftBesideStoredEntry>): String =
    buildJsonObject {
        put("v", RAFT_BESIDE_MEMORY_VERSION)
        putJsonObject("conversations") {
            for ((id, entry) in conversations) {
                val state = entry.state
                putJsonObject(id) {
                    state.thread?.let { put("thread", it) }
                    state.artifact?.let { artifact ->
                        putJsonObject("artifact") {
                            put("id", artifact.id)
                            put("version", artifact.version)
                        }
                    }
                    state.profile?.let { put("profile", it.wireValue) }
                    state.agentTab?.let { put("agentTab", it) }
                    state.front?.let { put("front", it.wireName) }
                    put("at", entry.at)
                }
            }
        }
    }.toString()

/** A stored conversation as a state (web `normalizeBeside` on a stored value): strings only where it wants strings. */
private fun JsonObject.toRaftBesideState(): RaftBesideState {
    // A version that is no positive whole number is the latest.
    val artifact =
        (this["artifact"] as? JsonObject)?.let { value ->
            value.besideString("id")?.let { id ->
                RaftBesideArtifact(id, value.besideWholeNumber("version")?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt())
            }
        }
    return RaftBesideState(
        thread = besideString("thread"),
        artifact = artifact,
        profile = RaftBesideProfile.fromWireValue(besideString("profile")),
        agentTab = besideString("agentTab"),
        front = RaftBesideKind.fromWireName(besideString("front")),
    ).normalized()
}

private fun JsonObject.besideString(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** A whole number (a JSON number, not a string), or null. */
private fun JsonObject.besideWholeNumber(name: String): Long? {
    val value = (this[name] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    return value.longOrNull ?: value.doubleOrNull?.takeIf { it.isFinite() && it == kotlin.math.floor(it) }?.toLong()
}

/**
 * What was open beside each conversation, per person ([accountId], the signed-in user) and server, on this device
 * (the file comment above). Safe from any thread: each call reads and writes the entry under one lock.
 */
internal class RaftBesideMemoryRepository(
    store: SharedPreferenceStore,
    private val nowMillis: () -> Long = ::defaultNowMillis,
) {
    private val lock = SynchronizedObject()
    private val storage =
        RaftLastCountsPersistStorage(
            store = store,
            indexKey = RAFT_BESIDE_MEMORY_INDEX_KEY,
            nowMillis = nowMillis,
            // What was open, not a copy of server data: it does not age out.
            maxAgeMillis = Long.MAX_VALUE,
            maxServersPerUser = RAFT_BESIDE_MEMORY_MAX_SERVERS,
        )

    /**
     * What was open beside [conversationId] for this person on this server (web `recallBeside`); null for nothing (or
     * nothing readable).
     */
    fun read(accountId: String?, serverId: String?, conversationId: String?): RaftBesideState? {
        val key = keyOf(accountId, serverId) ?: return null
        val conversation = conversationId?.takeIf { isRaftBesideId(it) } ?: return null
        return runCatching { synchronized(lock) { decodeRaftBesideMemory(storage.read(key))[conversation]?.state } }.getOrNull()
    }

    /**
     * Remembers [state] for the conversation, written at [atMillis] (web `rememberBeside`); nothing open forgets it, and
     * the person's entry on that server goes with its last conversation. Keeps the entry within its bound.
     */
    fun write(
        accountId: String?,
        serverId: String?,
        conversationId: String?,
        state: RaftBesideState,
        atMillis: Long = nowMillis(),
    ) {
        val key = keyOf(accountId, serverId) ?: return
        val conversation = conversationId?.takeIf { isRaftBesideId(it) } ?: return
        runCatching {
            synchronized(lock) {
                val stored = LinkedHashMap(decodeRaftBesideMemory(storage.read(key)))
                val next = state.normalized()
                val previous = stored.remove(conversation)
                // Nothing to forget writes nothing.
                if (next.isEmpty && previous == null) return@synchronized
                if (!next.isEmpty) stored[conversation] = RaftBesideStoredEntry(next, atMillis)
                val kept = raftPruneBesideEntries(stored)
                if (kept.isEmpty()) storage.remove(key) else storage.write(key, encodeRaftBesideMemory(kept))
            }
        }
    }

    /** Lost access to [serverId]: its entries of [accountId] (null: of every person) go; the other servers' stay. */
    fun dropServer(serverId: String, accountId: String?) {
        runCatching { synchronized(lock) { storage.dropServer(serverId, accountId) } }
    }

    private fun keyOf(accountId: String?, serverId: String?): String? {
        val account = accountId?.takeIf { isRaftBesideId(it) } ?: return null
        val server = serverId?.takeIf { isRaftBesideId(it) } ?: return null
        return raftBesideMemoryKey(account, server)
    }
}
