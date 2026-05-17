package ai.slock.android.core.data.realtime

import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.compose.data.DataResult
import ai.slock.android.core.data.repository.RealtimeRepository
import ai.slock.android.core.data.repository.RealtimeSubscription
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.state.PermissionBlocker

class SessionRealtimeRepository(
    private val store: AuthSessionStore,
    private val client: SocketIoRealtimeClient,
    private val tokenRefresher: AuthTokenRefresher? = null
) : RealtimeRepository {
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
}
