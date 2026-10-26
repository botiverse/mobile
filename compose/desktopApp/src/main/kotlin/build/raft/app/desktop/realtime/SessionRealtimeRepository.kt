package build.raft.app.desktop.realtime

import build.raft.app.data.RaftAuthSessionStore as AuthSessionStore
import build.raft.app.data.DataResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.RealtimeRepository
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SharedAuthTokenProvider
import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.model.ChannelId
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking

class SessionRealtimeRepository(
    private val store: AuthSessionStore,
    private val client: SocketIoRealtimeClient,
    private val pinnedServerId: ServerId? = null,
    private val tokenProvider: SharedAuthTokenProvider? = null
) : RealtimeRepository {
    init {
        if (tokenProvider != null) {
            client.setAuthRefreshProvider { reason, failedAuth ->
                runBlocking {
                    val refreshed =
                        if (reason == "connect_error") {
                            tokenProvider.refreshSession(
                                failedAccessToken = failedAuth?.token,
                                clearOnUnauthorized = false,
                                reason = reason,
                                caller = "desktop_socket"
                            )
                        } else {
                            // A Socket.IO reconnect attempt is not proof that auth expired.
                            // Refresh tokens rotate, so proactively refreshing from the
                            // synchronous EventThread callback can consume a successor that a
                            // later lifecycle reconnect cancels before it is persisted. Reuse
                            // the latest committed session here; an explicit auth connect_error
                            // remains the single native realtime refresh trigger.
                            tokenProvider.currentSession()
                        }
                    refreshed?.toRealtimeAuth(pinnedServerId)
                }
            }
        }
    }

    override suspend fun connect(serverId: ServerId, lastSeq: Long): DataResult<Unit> {
        val session = if (tokenProvider != null) {
            store.readSession()
                ?: return DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))
            // Connecting consumes the latest committed session. Only an explicit
            // Socket.IO auth connect_error may trigger the shared refresh coordinator.
            tokenProvider.currentSession()
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

    override fun leaveChannel(channelId: ChannelId) {
        client.leaveChannel(channelId)
    }

    override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription =
        client.observeEvents(listener)

    private fun build.raft.app.data.StoredAuthSession.toRealtimeAuth(pinnedServerId: ServerId?): RealtimeAuth? {
        val selectedServerId = pinnedServerId?.value ?: currentServerId ?: return null
        return RealtimeAuth(
            token = accessToken,
            serverId = ServerId(selectedServerId)
        )
    }
}
