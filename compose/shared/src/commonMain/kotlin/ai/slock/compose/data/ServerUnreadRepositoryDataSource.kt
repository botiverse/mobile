package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private suspend fun SlockNetworkClient.serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "servers/unread-summary")

class RealServerUnreadRepository(
    private val sessionStore: SlockAuthSessionStore,
    private val networkClient: SlockNetworkClient,
    private val throttleWindowMillis: Long = 5_000L,
    private val clockMillis: () -> Long,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : ServerUnreadRepository {
    private val throttleMutex = Mutex()
    private var cachedSummary: ThrottledUnreadSummary? = null

    override suspend fun getUnreadSummary(serverId: ServerId): DataResult<ServerUnreadSummarySnapshot> {
        val context = serverContext(serverId)
        if (context == null) {
            warningLogger("repository_skip reason=auth_or_server_mismatch serverId=${serverId.value}")
            return authRequired()
        }
        return throttleMutex.withLock {
            val now = clockMillis()
            val cached = cachedSummary
            if (cached != null &&
                cached.serverId == serverId &&
                now - cached.loadedAtMillis < throttleWindowMillis
            ) {
                logger("repository_throttle_hit serverId=${serverId.value} ageMs=${now - cached.loadedAtMillis}")
                return@withLock cached.result
            }

            logger("repository_fetch_start serverId=${serverId.value}")
            val result =
                executeServerUnread(
                    emptyReason = EmptyReason.NoInboxItems,
                    block = { networkClient.serverUnreadSummary(DataRequestPriority.Background) },
                    mapper = { bodyText ->
                        parseServerUnreadSummarySnapshot(bodyText, context.server)
                            ?: throw IllegalStateException("Server unread summary response missing servers")
                    }
                )
            when (result) {
                is DataResult.Content ->
                    logger(
                        "repository_fetch_success serverId=${serverId.value} total=${result.data.totalUnreadCount} selectedServerUnread=${result.data.selectedServerUnreadCount}"
                    )
                is DataResult.Empty ->
                    logger("repository_fetch_empty serverId=${serverId.value} empty=${result.reason}")
                is DataResult.Error ->
                    warningLogger("repository_fetch_error serverId=${serverId.value} code=${result.error.code}")
                is DataResult.NoPermission ->
                    warningLogger("repository_fetch_no_permission serverId=${serverId.value} code=${result.blocker.code}")
            }
            cachedSummary =
                ThrottledUnreadSummary(
                    serverId = serverId,
                    loadedAtMillis = now,
                    result = result
                )
            result
        }
    }

    private suspend fun serverContext(serverId: ServerId): ServerUnreadContext? {
        val session = sessionStore.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        if (selectedId != serverId.value) return null
        val server = session.servers.firstOrNull { it.id == selectedId }?.toServerSummary() ?: return null
        return ServerUnreadContext(server)
    }

    private suspend fun <T> executeServerUnread(
        emptyReason: EmptyReason,
        block: suspend () -> HomeNetworkResponse,
        mapper: (String) -> T
    ): DataResult<T> {
        return try {
            val response = block()
            if (!response.isSuccessful) return response.toServerUnreadDataError()
            val body = response.bodyText.orEmpty()
            if (body.isBlank()) return DataResult.Empty(emptyReason)
            DataResult.Content(mapper(body))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
        }
    }
}

private data class ServerUnreadContext(val server: ServerSummary)

private data class ThrottledUnreadSummary(
    val serverId: ServerId,
    val loadedAtMillis: Long,
    val result: DataResult<ServerUnreadSummarySnapshot>
)

private fun <T> authRequired(): DataResult<T> =
    DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))

private fun <T> HomeNetworkResponse.toServerUnreadDataError(): DataResult<T> =
    when (code) {
        401 -> authRequired()
        403 -> DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access"))
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        else -> DataResult.Error(ScreenError(code = "http_$code", retryable = code != 404))
    }
