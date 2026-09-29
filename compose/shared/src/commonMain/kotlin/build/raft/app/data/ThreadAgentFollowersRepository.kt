package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// botiverse/slock 94d8ec1da8680ff3c94b0ae7200beb8fe933300e:
// routes/channels.ts and services/channelService.ts (managed thread followers).
private val followerJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class ThreadAgentFollower(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val status: String,
    val avatarUrl: String? = null,
    val serverId: String,
    val serverName: String? = null,
    val serverSlug: String? = null,
    val isCurrentServer: Boolean,
    val canRemove: Boolean,
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
internal data class ThreadAgentFollowerRoster(
    val threadChannelId: String,
    val canManage: Boolean,
    val agents: List<ThreadAgentFollower>,
)

@Serializable
private data class ThreadAgentFollowerResponse(val threads: List<ThreadAgentFollowerRoster>)
@Serializable
internal data class ThreadAgentFollowerRemoval(val ok: Boolean, val removed: Boolean, val undoToken: String? = null)
@Serializable
private data class ThreadAgentFollowerRestore(val ok: Boolean, val restored: Boolean)
@Serializable
private data class ThreadAgentFollowerRestoreBody(val undoToken: String)
@Serializable
private data class ThreadAgentFollowerError(val code: String? = null)

internal fun parseSharedThreadAgentFollowers(body: String, threadId: String): ThreadAgentFollowerRoster? =
    runCatching {
        val roster = followerJson.decodeFromString<ThreadAgentFollowerResponse>(body).threads.single()
        require(roster.threadChannelId == threadId)
        require(roster.agents.all { it.id.isNotBlank() && it.name.isNotBlank() && it.serverId.isNotBlank() })
        require(roster.agents.map { it.id }.distinct().size == roster.agents.size)
        roster
    }.getOrNull()

internal fun parseSharedThreadAgentFollowerRemoval(body: String): ThreadAgentFollowerRemoval? =
    runCatching { followerJson.decodeFromString<ThreadAgentFollowerRemoval>(body) }
        .getOrNull()?.takeIf { it.ok && (!it.removed || !it.undoToken.isNullOrBlank()) }

internal interface ThreadAgentFollowersRepository {
    suspend fun load(): DataResult<ThreadAgentFollowerRoster>
    suspend fun remove(agentId: String): DataResult<ThreadAgentFollowerRemoval>
    suspend fun restore(agentId: String, undoToken: String): DataResult<Boolean>
}

/** One repository per account/environment/server/thread route; every request is authority bound. */
internal class RealThreadAgentFollowersRepository(
    private val network: SlockNetworkClient,
    private val auth: SlockAuthSessionStore,
    private val accountId: String,
    private val serverId: String,
    private val threadId: String,
    private val environmentId: String,
    private val currentEnvironment: () -> String,
) : ThreadAgentFollowersRepository {
    override suspend fun load(): DataResult<ThreadAgentFollowerRoster> = request(
        path = "channels/threads/followers", priority = DataRequestPriority.Visible,
        query = listOf("threadChannelIds" to threadId),
    ) { parseSharedThreadAgentFollowers(it, threadId) }

    override suspend fun remove(agentId: String): DataResult<ThreadAgentFollowerRemoval> = request(
        path = agentPath(agentId), method = "DELETE", priority = DataRequestPriority.Foreground,
    ) { parseSharedThreadAgentFollowerRemoval(it) }

    override suspend fun restore(agentId: String, undoToken: String): DataResult<Boolean> = request(
        path = "${agentPath(agentId)}/restore", method = "POST", priority = DataRequestPriority.Foreground,
        body = followerJson.encodeToString(ThreadAgentFollowerRestoreBody(undoToken)),
    ) {
        runCatching { followerJson.decodeFromString<ThreadAgentFollowerRestore>(it) }
            .getOrNull()?.takeIf { it.ok }?.restored
    }

    private fun agentPath(id: String) = "channels/threads/$threadId/followers/agents/$id"

    private suspend fun <T> request(
        path: String, priority: DataRequestPriority, method: String = "GET",
        query: List<Pair<String, String>> = emptyList(), body: String? = null,
        parse: (String) -> T?,
    ): DataResult<T> {
        val session = auth.readSession() ?: return authSessionRequiredResult()
        if (session.user.id != accountId || session.currentServerId != serverId || currentEnvironment() != environmentId) {
            return DataResult.Error(ScreenError("thread_followers_scope_changed", retryable = false))
        }
        return try {
            val bound = network.boundTo(SlockExpectedAuthBinding(
                accountId, session.accessToken, session.refreshToken, serverId, environmentId,
            ))
            val response = bound.request(priority, path, method, query, ServerId(serverId), body)
            val after = auth.readSession()
            if (after?.user?.id != accountId || after?.currentServerId != serverId || currentEnvironment() != environmentId) {
                return DataResult.Error(ScreenError("thread_followers_scope_changed", retryable = false))
            }
            val code = runCatching {
                followerJson.decodeFromString<ThreadAgentFollowerError>(response.errorBodyText ?: response.bodyText.orEmpty()).code
            }.getOrNull()
            when {
                response.code == 401 -> authSessionRequiredResult()
                !response.isSuccessful -> DataResult.Error(ScreenError(code ?: "http_${response.code}"))
                else -> parse(response.bodyText.orEmpty())?.let { DataResult.Content(it) }
                    ?: DataResult.Error(ScreenError("thread_followers_contract_mismatch"))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DataResult.Error(ScreenError("thread_followers_network_error"))
        }
    }
}
