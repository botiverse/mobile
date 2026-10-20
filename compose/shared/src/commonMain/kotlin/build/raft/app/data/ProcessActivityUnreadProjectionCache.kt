package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE = "slockActivityUnread"
const val RAFT_ACTIVITY_UNREAD_MMKV_STORAGE_KEY = "projections"
const val RAFT_ACTIVITY_UNREAD_MMKV_RAW_STORAGE_KEY =
    "$RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE:$RAFT_ACTIVITY_UNREAD_MMKV_STORAGE_KEY"

@Serializable
data class PersistedActivityUnreadProjection(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val totalUnreadCount: Int,
    val totalCount: Int,
    val updatedAtMillis: Long
)

/**
 * Stale-only process projection persistence. The cache never grants freshness: every restored
 * value is displayed as stale/refreshing and attach or foreground still performs canonical
 * reconciliation.
 */
class ProcessActivityUnreadProjectionCache(
    private val store: RaftKeyValueStore,
    private val nowMillis: () -> Long = ::defaultNowMillis
) {
    fun read(
        environmentId: String,
        accountId: String,
        serverId: String
    ): PersistedActivityUnreadProjection? =
        readAll().firstOrNull { entry ->
            entry.environmentId == environmentId.trim().trimEnd('/') &&
                entry.accountId == accountId.trim() &&
                entry.serverId == serverId.trim()
        }

    internal fun read(owner: ProcessActivityUnreadOwner): PersistedActivityUnreadProjection? =
        read(owner.environmentId, owner.accountId, owner.serverId)

    internal fun write(
        owner: ProcessActivityUnreadOwner,
        totalUnreadCount: Int,
        totalCount: Int
    ) {
        val replacement =
            PersistedActivityUnreadProjection(
                environmentId = owner.environmentId,
                accountId = owner.accountId,
                serverId = owner.serverId,
                totalUnreadCount = totalUnreadCount.coerceAtLeast(0),
                totalCount = totalCount.coerceAtLeast(0),
                updatedAtMillis = nowMillis()
            )
        val entries = readAll().filterNot { entry -> entry.matches(owner) } + replacement
        store.putString(
            RAFT_ACTIVITY_UNREAD_MMKV_STORAGE_KEY,
            cacheJson.encodeToString(PersistedActivityUnreadProjectionDocument(entries = entries))
        )
    }

    fun clearAll() {
        store.putString(RAFT_ACTIVITY_UNREAD_MMKV_STORAGE_KEY, null)
    }

    private fun readAll(): List<PersistedActivityUnreadProjection> =
        store.getString(RAFT_ACTIVITY_UNREAD_MMKV_STORAGE_KEY)
            ?.takeIf { encoded -> encoded.isNotBlank() }
            ?.let { encoded ->
                runCatching {
                    cacheJson.decodeFromString<PersistedActivityUnreadProjectionDocument>(encoded).entries
                }.getOrNull()
            }
            .orEmpty()
            .filter { entry ->
                entry.environmentId.isNotBlank() &&
                    entry.accountId.isNotBlank() &&
                    entry.serverId.isNotBlank()
            }

    private fun PersistedActivityUnreadProjection.matches(owner: ProcessActivityUnreadOwner): Boolean =
        environmentId == owner.environmentId && accountId == owner.accountId && serverId == owner.serverId

    private companion object {
        val cacheJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class PersistedActivityUnreadProjectionDocument(
    val version: Int = 1,
    val entries: List<PersistedActivityUnreadProjection> = emptyList()
)
