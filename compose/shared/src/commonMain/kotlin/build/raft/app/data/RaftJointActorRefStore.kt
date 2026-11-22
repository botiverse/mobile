package build.raft.app.data

import build.raft.app.model.MemberKind
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * #proj-mobile:#290: durable resolved joint-channel actor directory (`actorId -> ResolvedActor`),
 * mirroring [RaftMemberAvatarRefStore]'s synchronous-read, owner-scoped per-server preference
 * layout.
 *
 * Joint-channel senders from other servers are not in this server's member roster, so without
 * persistence every cold start started with an empty [SharedJointActorDirectory] and waited for
 * `POST /api/actors/resolve` before knowing each remote agent's avatar URL. Persisting resolved
 * rows lets frame 1 paint the cached avatar immediately; `actor:updated` and subsequent resolves
 * update entries in place, and sign-out ([AccountLocalData.onSignOut]) / server access loss clear
 * them cleanly.
 */
internal class RaftJointActorRefStore(
    private val store: SharedPreferenceStore,
    private val maxEntriesPerServer: Int = DefaultMaxEntriesPerServer,
) {
    private val lock = SynchronizedObject()
    private val loadedByServerId = mutableMapOf<String, RaftJointActorRefPage>()

    /** Synchronous read of all persisted actors for [serverId] matching [ownerKey]. */
    fun readServer(serverId: String?, ownerKey: String?): Map<String, ResolvedActor> {
        val serverKey = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        val owner = ownerKey?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return synchronized(lock) {
            val page = pageLocked(serverKey)
            if (page.owner != owner) return@synchronized emptyMap()
            page.actors.mapNotNull { (id, row) -> row.toResolvedActorOrNull()?.let { id to it } }.toMap()
        }
    }

    /** Upserts [actors] for [serverId] under [ownerKey], keeping the most recent entries. */
    fun record(serverId: String?, ownerKey: String?, actors: Collection<ResolvedActor>) {
        val serverKey = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val owner = ownerKey?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (actors.isEmpty()) return
        synchronized(lock) {
            val currentPage = pageLocked(serverKey)
            val current = if (currentPage.owner == owner) currentPage.actors else emptyMap()
            val next = LinkedHashMap(current)
            actors.forEach { actor ->
                val actorId = actor.id.trim().takeIf { it.isNotEmpty() } ?: return@forEach
                val persisted = PersistedResolvedActor.from(actor) ?: return@forEach
                next.remove(actorId)
                next[actorId] = persisted
            }
            while (next.size > maxEntriesPerServer) {
                next.remove(next.keys.first())
            }
            val nextPage = RaftJointActorRefPage(owner, next)
            loadedByServerId[serverKey] = nextPage
            if (currentPage.owner == owner && next == current) return@synchronized
            runCatching {
                if (next.isEmpty()) {
                    store.putString(serverKeyFor(serverKey), null)
                } else {
                    store.putString(serverKeyFor(serverKey), RefJson.encodeToString(RaftJointActorRefPage.serializer(), nextPage))
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

    /** Most recently recorded http(s) avatar URLs across persisted servers matching [ownerKey] (for startup disk-to-memory prewarm). */
    fun recentHttpAvatarUrls(maxCount: Int, ownerKey: String? = null): List<String> {
        if (maxCount <= 0) return emptyList()
        val expectedOwner = ownerKey?.trim()?.takeIf { it.isNotEmpty() }
        return synchronized(lock) {
            val urls = LinkedHashSet<String>()
            val serverIds = indexedServerIdsLocked().asReversed()
            for (serverKey in serverIds) {
                val page = pageLocked(serverKey)
                if (expectedOwner != null && page.owner != expectedOwner) continue
                val entries = page.actors.values.toList()
                for (index in entries.lastIndex downTo 0) {
                    val url = entries[index].avatarUrl?.trim()?.takeIf { it.isHttpOrRelativeAvatarUrl() } ?: continue
                    urls += url
                    if (urls.size >= maxCount) return@synchronized urls.toList()
                }
            }
            urls.toList()
        }
    }

    fun forgetServer(serverId: String?) {
        val serverKey = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            loadedByServerId[serverKey] = RaftJointActorRefPage()
            runCatching {
                store.putString(serverKeyFor(serverKey), null)
                val index = indexedServerIdsLocked()
                if (serverKey in index) writeIndexLocked(index - serverKey)
            }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            runCatching {
                indexedServerIdsLocked().forEach { serverKey -> store.putString(serverKeyFor(serverKey), null) }
                store.putString(IndexKey, null)
            }
            loadedByServerId.clear()
        }
    }

    private fun pageLocked(serverKey: String): RaftJointActorRefPage =
        loadedByServerId.getOrPut(serverKey) {
            runCatching { store.getString(serverKeyFor(serverKey)) }
                .getOrNull()
                ?.let { encoded ->
                    runCatching { RefJson.decodeFromString(RaftJointActorRefPage.serializer(), encoded) }.getOrNull()
                }
                ?: RaftJointActorRefPage()
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
        const val DefaultMaxEntriesPerServer = 1000
        const val KeyPrefix = "raft:joint-actor-refs:v1:server:"
        const val IndexKey = "raft:joint-actor-refs:v1:index"

        fun serverKeyFor(serverId: String): String = KeyPrefix + serverId

        private val RefJson = Json { ignoreUnknownKeys = true }
        private val IndexSerializer = ListSerializer(String.serializer())
    }
}

private fun String.isHttpOrRelativeAvatarUrl(): Boolean =
    startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true) ||
        startsWith("/api/avatars/", ignoreCase = true) ||
        startsWith("/avatars/", ignoreCase = true)

@Serializable
internal data class PersistedResolvedActor(
    val kind: String,
    val id: String,
    val homeServerId: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val gravatarHash: String? = null,
) {
    fun toResolvedActorOrNull(): ResolvedActor? {
        val memberKind =
            when (kind) {
                "agent" -> MemberKind.Agent
                "human" -> MemberKind.Human
                else -> return null
            }
        val cleanId = id.trim().takeIf { it.isNotEmpty() } ?: return null
        return ResolvedActor(
            kind = memberKind,
            id = cleanId,
            homeServerId = homeServerId,
            name = name,
            displayName = displayName,
            avatarUrl = avatarUrl,
            gravatarHash = gravatarHash,
        )
    }

    companion object {
        fun from(actor: ResolvedActor): PersistedResolvedActor? {
            val wireKind =
                when (actor.kind) {
                    MemberKind.Agent -> "agent"
                    MemberKind.Human -> "human"
                    else -> return null
                }
            return PersistedResolvedActor(
                kind = wireKind,
                id = actor.id.trim(),
                homeServerId = actor.homeServerId,
                name = actor.name,
                displayName = actor.displayName,
                avatarUrl = actor.avatarUrl,
                gravatarHash = actor.gravatarHash,
            )
        }
    }
}

@Serializable
internal data class RaftJointActorRefPage(
    val owner: String = "",
    val actors: Map<String, PersistedResolvedActor> = emptyMap(),
)
