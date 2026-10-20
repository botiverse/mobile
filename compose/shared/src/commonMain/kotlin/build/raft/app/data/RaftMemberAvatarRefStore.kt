package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Durable avatar references: per server, `memberId -> rendered avatar wire value` (an http(s) avatar
 * URL or a pixel/random avatar wire value), as last published by an authoritative member load.
 *
 * The member directory itself is network-only (it lives in [SharedServerMembersStore]'s memory), so
 * on a cold start every DM row / message author rendered its placeholder until the directory fetch
 * landed (Hands dd79da45: ~2-3 s after launch, much longer on a slow network). These references let
 * the avatar resolvers render the last known avatar on the first frame; the image bytes themselves
 * come from the Coil disk cache, which is keyed by the avatar's content, not by server or URL host.
 *
 * Only references are stored, never image bytes or profile details. Entries are replaced by every
 * authoritative load (a member whose authoritative avatar resolves to nothing is removed), and the
 * whole family is wiped with the auth session ([clearAll]).
 *
 * Reads are synchronous (the resolver is consulted during composition); each server's map is read
 * from the preference store once per process and kept in memory.
 */
internal class RaftMemberAvatarRefStore(
    private val store: SharedPreferenceStore,
    private val maxEntriesPerServer: Int = DefaultMaxEntriesPerServer,
) {
    private val lock = SynchronizedObject()
    private val loadedByServerId = mutableMapOf<String, RaftMemberAvatarRefPage>()

    /**
     * The stored reference, or null. [currentOwnerKey] is the current `environment|account` owner of
     * [serverId] when known: a page written by a different owner is never read. While the owner is
     * not resolved yet (cold start before the auth context is published) the page of the last
     * owner is used; it is wiped with the auth session, so it is never another account's.
     */
    fun avatarWireValue(serverId: String?, memberId: String?, currentOwnerKey: String?): String? {
        val serverKey = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val memberKey = memberId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return synchronized(lock) {
            val page = pageLocked(serverKey)
            if (currentOwnerKey != null && page.owner != currentOwnerKey) return@synchronized null
            page.refs[memberKey]
        }
    }

    /**
     * Applies authoritative avatar values for [serverId] observed by [ownerKey] (`environment|
     * account`, required: a write without a current owner binding is dropped). A null / blank value
     * means "this member has no avatar" and removes the reference; members absent from
     * [avatarsByMemberId] are kept. A different owner replaces the page. Writes only on change.
     */
    fun record(serverId: String?, ownerKey: String?, avatarsByMemberId: Map<String, String?>) {
        val serverKey = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val owner = ownerKey?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (avatarsByMemberId.isEmpty()) return
        synchronized(lock) {
            val currentPage = pageLocked(serverKey)
            val current = if (currentPage.owner == owner) currentPage.refs else emptyMap()
            val next = LinkedHashMap(current)
            avatarsByMemberId.forEach { (memberId, avatar) ->
                val memberKey = memberId.trim().takeIf { it.isNotEmpty() } ?: return@forEach
                val value = avatar?.trim()?.takeIf { it.isNotEmpty() }
                // Re-insert so the most recently confirmed references survive the size bound.
                next.remove(memberKey)
                if (value != null) next[memberKey] = value
            }
            while (next.size > maxEntriesPerServer) {
                next.remove(next.keys.first())
            }
            val nextPage = RaftMemberAvatarRefPage(owner, next)
            loadedByServerId[serverKey] = nextPage
            // Same owner and references (possibly re-ordered by recency): nothing to write.
            if (currentPage.owner == owner && next == current) return@synchronized
            runCatching {
                if (next.isEmpty()) {
                    store.putString(serverKeyFor(serverKey), null)
                } else {
                    store.putString(serverKeyFor(serverKey), RefJson.encodeToString(RaftMemberAvatarRefPage.serializer(), nextPage))
                }
                val index = indexedServerIdsLocked()
                if (next.isEmpty() && serverKey in index) {
                    writeIndexLocked(index - serverKey)
                } else if (next.isNotEmpty() && serverKey !in index) {
                    writeIndexLocked(index + serverKey)
                }
            }
        }
    }

    /** Removes every stored reference (auth session ended). */
    fun clearAll() {
        synchronized(lock) {
            runCatching {
                indexedServerIdsLocked().forEach { serverKey -> store.putString(serverKeyFor(serverKey), null) }
                store.putString(IndexKey, null)
            }
            loadedByServerId.clear()
        }
    }

    private fun pageLocked(serverKey: String): RaftMemberAvatarRefPage =
        loadedByServerId.getOrPut(serverKey) {
            runCatching { store.getString(serverKeyFor(serverKey)) }
                .getOrNull()
                ?.let { encoded ->
                    runCatching { RefJson.decodeFromString(RaftMemberAvatarRefPage.serializer(), encoded) }.getOrNull()
                }
                ?: RaftMemberAvatarRefPage()
        }

    private fun indexedServerIdsLocked(): List<String> =
        runCatching { store.getString(IndexKey) }
            .getOrNull()
            ?.let { encoded -> runCatching { RefJson.decodeFromString(IndexSerializer, encoded) }.getOrNull() }
            .orEmpty()

    private fun writeIndexLocked(serverIds: List<String>) {
        if (serverIds.isEmpty()) {
            store.putString(IndexKey, null)
        } else {
            store.putString(IndexKey, RefJson.encodeToString(IndexSerializer, serverIds.distinct()))
        }
    }

    internal companion object {
        const val DefaultMaxEntriesPerServer = 2000
        // Persisted key family: keep the spelling stable across releases.
        const val KeyPrefix = "raft:member-avatar-refs:v2:server:"
        const val IndexKey = "raft:member-avatar-refs:v2:index"

        fun serverKeyFor(serverId: String): String = KeyPrefix + serverId

        private val RefJson = Json { ignoreUnknownKeys = true }
        private val IndexSerializer = ListSerializer(String.serializer())
    }
}

@Serializable
internal data class RaftMemberAvatarRefPage(
    val owner: String = "",
    val refs: Map<String, String> = emptyMap(),
)
