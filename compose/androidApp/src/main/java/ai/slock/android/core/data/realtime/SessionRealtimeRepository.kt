package ai.slock.android.core.data.realtime

import ai.slock.compose.data.SlockAuthSessionStore as AuthSessionStore
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.RealtimeRepository
import ai.slock.compose.data.RealtimeSubscription
import ai.slock.compose.data.realtime.RealtimeAuth
import ai.slock.compose.data.realtime.RealtimeConnectRequest
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.state.PermissionBlocker
import kotlinx.coroutines.runBlocking

class SessionRealtimeRepository(
    private val store: AuthSessionStore,
    private val client: SocketIoRealtimeClient,
    private val tokenRefresher: AuthTokenRefresher? = null
) : RealtimeRepository {
    init {
        if (tokenRefresher != null) {
            client.setAuthRefreshProvider { reason ->
                runBlocking {
                    val refreshed =
                        if (reason == "connect_error") {
                            tokenRefresher.refreshIfCurrent(
                                failedAccessToken = store.readSession()?.accessToken,
                                clearOnUnauthorized = false
                            )
                        } else {
                            tokenRefresher.refreshIfExpiringSoon(clearOnUnauthorized = false)
                        }
                    refreshed?.toRealtimeAuthForSelectedServer()
                }
            }
        }
    }

    override suspend fun connect(serverId: ServerId, lastSeq: Long): DataResult<Unit> {
        val session = if (tokenRefresher != null) {
            store.readSession()
                ?: return DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))
            tokenRefresher.refreshIfExpiringSoon(clearOnUnauthorized = false)
                ?: return DataResult.NoPermission(PermissionBlocker(code = "auth_refresh_failed", requiredCapability = "auth_session"))
        } else {
            store.readSession()
                ?: return DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))
        }
        val selectedServerId = session.currentServerId
            ?: return DataResult.NoPermission(PermissionBlocker(code = "server_required", requiredCapability = "selected_server"))
        if (selectedServerId != serverId.value) {
            return DataResult.NoPermission(PermissionBlocker(code = "server_mismatch", requiredCapability = "selected_server"))
        }
        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(
                    token = session.accessToken,
                    serverId = serverId
                ),
                lastSeq = lastSeq
            )
        )
        return DataResult.Content(Unit)
    }

    override fun disconnect() {
        client.disconnect()
    }

    override fun joinChannel(channelId: ChannelId) {
        client.joinChannel(channelId)
    }

    override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription =
        client.observeEvents(listener)

    private fun ai.slock.compose.data.StoredAuthSession.toRealtimeAuthForSelectedServer(): RealtimeAuth? {
        val selectedServerId = currentServerId ?: return null
        return RealtimeAuth(
            token = accessToken,
            serverId = ServerId(selectedServerId)
        )
    }
}
