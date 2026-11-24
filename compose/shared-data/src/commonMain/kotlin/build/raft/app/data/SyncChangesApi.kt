package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Page size of a pull: the server default (slock services/syncChanges.ts SYNC_CHANGES_DEFAULT_LIMIT). */
@RaftCoreInternalApi const val SYNC_CHANGES_PAGE_LIMIT: Int = 500

/** The result of one `GET /api/sync/changes` request. */
@RaftCoreInternalApi sealed interface RaftSyncChangesFetch {
    data class Page(val page: RaftSyncChangesPage) : RaftSyncChangesFetch

    /** 400 `invalid_cursor`: the stored cursor is not one this server issued; start over. */
    data object InvalidCursor : RaftSyncChangesFetch

    /** Anything else (network, 5xx, auth, a body that does not match the contract). */
    data class Failed(val code: String) : RaftSyncChangesFetch
}

@RaftCoreInternalApi fun interface RaftSyncChangesApi {
    /** [since] null asks for the current cursor only. */
    suspend fun changes(serverId: ServerId, since: String?, limit: Int): RaftSyncChangesFetch
}

@Serializable
private data class SyncChangesErrorWire(val code: String? = null)

private val syncChangesErrorJson = Json { ignoreUnknownKeys = true }

/** The `capabilities` query value (comma-separated, slock routes/sync.ts `parseCapabilities`), or null for none. */
@RaftCoreInternalApi fun syncChangesCapabilitiesQuery(capabilities: Set<String>): String? =
    capabilities.map(String::trim).filter(String::isNotEmpty).sorted().takeIf { it.isNotEmpty() }?.joinToString(",")

/**
 * `GET sync/changes?since=&limit=[&capabilities=]` through the shared network client, bound to [serverId].
 * [capabilities]: what to ask for on [serverId] now (`inbox_row` while `sync_inbox_rows_v0` is on); none
 * sends no parameter, and the response is exactly as before the capability.
 */
@RaftCoreInternalApi class RaftSyncChangesNetworkApi(
    private val networkClient: RaftNetworkClient,
    private val priority: DataRequestPriority = DataRequestPriority.Background,
    private val capabilities: (ServerId) -> Set<String> = { emptySet() },
) : RaftSyncChangesApi {
    override suspend fun changes(serverId: ServerId, since: String?, limit: Int): RaftSyncChangesFetch {
        val response =
            networkClient.request(
                priority = priority,
                path = "sync/changes",
                query =
                    listOfNotNull(
                        since?.let { "since" to it },
                        "limit" to limit.toString(),
                        syncChangesCapabilitiesQuery(capabilities(serverId))?.let { "capabilities" to it },
                    ),
                serverIdOverride = serverId,
            )
        return syncChangesFetchOf(response)
    }
}

@RaftCoreInternalApi fun syncChangesFetchOf(response: HomeNetworkResponse): RaftSyncChangesFetch {
    if (!response.isSuccessful) {
        if (response.code == 400) {
            val code =
                response.errorBodyText?.let { body ->
                    runCatching { syncChangesErrorJson.decodeFromString(SyncChangesErrorWire.serializer(), body).code }
                        .getOrNull()
                }
            if (code == "invalid_cursor") return RaftSyncChangesFetch.InvalidCursor
        }
        return RaftSyncChangesFetch.Failed("http_${response.code}")
    }
    val body = response.bodyText?.takeIf { it.isNotBlank() } ?: return RaftSyncChangesFetch.Failed("empty_body")
    val page = parseSyncChangesPage(body) ?: return RaftSyncChangesFetch.Failed("contract_mismatch")
    return RaftSyncChangesFetch.Page(page)
}
