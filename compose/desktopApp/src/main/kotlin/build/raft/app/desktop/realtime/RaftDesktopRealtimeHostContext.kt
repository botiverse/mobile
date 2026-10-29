package build.raft.app.desktop.realtime

import build.raft.app.data.DataResult
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.RaftAuthApiClient
import build.raft.app.data.postDesktopAppScopeEvent
import build.raft.app.data.RAFT_AUTH_SESSION_MMKV_NAMESPACE
import build.raft.app.data.raftDesktopAppScopeKeyValueStore
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SharedAuthTokenProvider
import build.raft.app.data.SharedRaftAuthTransport
import build.raft.app.data.realtime.RaftRealtimeHostContext
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.RealtimeConnectionState
import build.raft.app.data.realtime.RealtimeConnectionWitnessToken
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.desktop.RaftDesktopLog
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The desktop [RaftRealtimeHostContext] (port of the Android host's
 * AndroidAppScopeRealtimeHostContext): one Socket.IO client per server, whose frames and
 * lifecycle signals go to the shared app-scope event bus that the app shell's stores consume.
 * TODO(desktop): no push / background unread notifications.
 */
internal class RaftDesktopRealtimeHostContext private constructor(
    private val scope: CoroutineScope,
    private val repository: SessionRealtimeRepository,
    val client: SocketIoRealtimeClient,
) : RaftRealtimeHostContext {
    @Volatile private var currentPresenceTransportEpoch = 0L

    private val connectedSubscription = client.observeConnected { context -> postPresenceTransportConnected(context) }

    private val roomsJoinedSubscription =
        client.observeRoomsJoined { context ->
            postLifecycle(context, RaftRealtimeNotifyContract.ActionRealtimeRoomsJoined, channelId = null)
        }

    private val channelJoinAckSubscription =
        client.observeChannelJoinAck { context, channelId, joined ->
            postLifecycle(
                context,
                if (joined) RaftRealtimeNotifyContract.ActionRealtimeChannelJoined else RaftRealtimeNotifyContract.ActionRealtimeChannelJoinDenied,
                channelId,
            )
        }

    private fun postPresenceTransportConnected(context: RealtimeSocketConnectionContext) {
        currentPresenceTransportEpoch = 0L
        if (context.witness.scopeLeaseId.isBlank()) return
        currentPresenceTransportEpoch = presenceTransportEpochs.incrementAndGet()
        log("presence_transport_connected serverId=${context.serverId.value} presenceEpoch=$currentPresenceTransportEpoch")
        postLifecycle(context, RaftRealtimeNotifyContract.ActionPresenceTransportConnected, channelId = null)
    }

    private fun postLifecycle(
        context: RealtimeSocketConnectionContext,
        action: String,
        channelId: String?,
    ) {
        // Every signal carries this socket's epoch; none when the connected signal was skipped.
        val epoch = currentPresenceTransportEpoch.takeIf { it > 0L } ?: return
        val witness = context.witness
        val payload =
            JSONObject()
                .put(RaftRealtimeNotifyContract.KeyAction, action)
                .put(RaftRealtimeNotifyContract.KeyServerId, context.serverId.value)
                .put(RaftRealtimeNotifyContract.KeyAuthorityScopeLeaseId, witness.scopeLeaseId)
                .put(RaftRealtimeNotifyContract.KeyAuthorityPrincipalId, witness.principalId)
                .put(RaftRealtimeNotifyContract.KeyAuthorityAuthGeneration, witness.authGeneration)
                .put(RaftRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, witness.connectionFamilyId)
                .put(RaftRealtimeNotifyContract.KeyPresenceTransportEpoch, epoch)
        if (channelId != null) payload.put(RaftRealtimeNotifyContract.KeyChannelId, channelId)
        postAppScopeEvent(RaftRealtimeNotifyContract.EventName, payload)
    }

    override val state: RealtimeConnectionState
        get() = client.state

    override suspend fun connect(
        serverId: ServerId,
        accessToken: String,
        lastSeq: Long,
        witness: RealtimeConnectionWitnessToken,
    ): DataResult<Unit> {
        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = accessToken, serverId = serverId),
                lastSeq = lastSeq,
                witness = witness,
            )
        )
        return DataResult.Content(Unit)
    }

    override fun disconnect() = repository.disconnect()

    override fun emitRoomCommand(eventName: String, payload: String, force: Boolean) {
        client.emitRoomCommand(eventName = eventName, payload = payload, force = force)
    }

    override fun emitAgentActivitySubscription(eventName: String, agentIds: List<String>) {
        client.emitAgentActivitySubscription(eventName, agentIds)
    }

    override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription = repository.observeEvents(listener)

    override fun observeRawEvents(
        listener: (
            context: RealtimeSocketConnectionContext,
            eventName: String,
            rawPayload: String,
            transportEpoch: Long?,
        ) -> Unit
    ): RealtimeSubscription =
        client.observeRawEvents { context, eventName, rawPayload ->
            // Bound on the socket thread, where this socket's connected listener has already run.
            listener(context, eventName, rawPayload.toRawPayloadString(), currentPresenceTransportEpoch.takeIf { it > 0L })
        }

    override fun log(message: String) = RaftDesktopLog.d(LOG_TAG, message)

    override fun fanOutRawRealtimeEvent(
        serverId: ServerId,
        witness: RealtimeConnectionWitnessToken,
        eventName: String,
        rawPayload: String,
        transportEpoch: Long?,
    ) {
        val payload =
            JSONObject()
                .put(RaftRealtimeNotifyContract.KeyServerId, serverId.value)
                .put(RaftRealtimeNotifyContract.KeyAuthorityScopeLeaseId, witness.scopeLeaseId)
                .put(RaftRealtimeNotifyContract.KeyAuthorityPrincipalId, witness.principalId)
                .put(RaftRealtimeNotifyContract.KeyAuthorityAuthGeneration, witness.authGeneration)
                .put(RaftRealtimeNotifyContract.KeyAuthorityConnectionFamilyId, witness.connectionFamilyId)
                .put(RaftRealtimeNotifyContract.KeyEventName, eventName)
                .put(RaftRealtimeNotifyContract.KeyRawPayload, rawPayload)
        transportEpoch?.takeIf { it > 0L }?.let { payload.put(RaftRealtimeNotifyContract.KeyPresenceTransportEpoch, it) }
        postAppScopeEvent(RaftRealtimeNotifyContract.RawIngestEventName, payload)
    }

    // TODO(desktop): background unread notifications (macOS notification center).
    override suspend fun maybePostBackgroundUnreadNotification(
        serverId: ServerId,
        event: NotificationPushReceived,
        isAppInForeground: Boolean,
    ) = Unit

    override fun dispatchRealtime(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    fun close() {
        connectedSubscription.close()
        roomsJoinedSubscription.close()
        channelJoinAckSubscription.close()
    }

    companion object {
        private const val LOG_TAG = "SlockKuiklyRealtime"

        // Process-wide, like Android: a rebuilt context must not reuse an epoch.
        private val presenceTransportEpochs = AtomicLong(0L)

        fun create(
            scope: CoroutineScope,
            apiBaseUrl: String,
            socketUrl: String,
            pinnedServerId: ServerId,
        ): RaftDesktopRealtimeHostContext {
            val sessionStore =
                KeyValueAuthSessionStore(
                    store = raftDesktopAppScopeKeyValueStore(RAFT_AUTH_SESSION_MMKV_NAMESPACE),
                    diagnosticsLogger = { message -> RaftDesktopLog.w(LOG_TAG, "shared_auth_session $message") },
                )
            val client =
                SocketIoRealtimeClient(
                    transport = SocketIoSocketTransport(socketUrl, RealtimeSocketGenerationFence()),
                    logger = { message -> RaftDesktopLog.d(LOG_TAG, message) },
                )
            val repository =
                SessionRealtimeRepository(
                    store = sessionStore,
                    client = client,
                    pinnedServerId = pinnedServerId,
                    tokenProvider =
                        SharedAuthTokenProvider(
                            sessionStore = sessionStore,
                            authApi =
                                RaftAuthApiClient(
                                    SharedRaftAuthTransport(
                                        baseUrl = apiBaseUrl,
                                        warningLogger = { message -> RaftDesktopLog.w(LOG_TAG, message) },
                                    )
                                ),
                            clockMillis = { System.currentTimeMillis() },
                            logger = { message -> RaftDesktopLog.d(LOG_TAG, message) },
                            warningLogger = { message -> RaftDesktopLog.w(LOG_TAG, message) },
                            criticalLogger = { message -> RaftDesktopLog.w(LOG_TAG, message) },
                        ),
                )
            return RaftDesktopRealtimeHostContext(scope, repository, client)
        }

        /** Posts on the UI thread, as the Android host posts on the main looper. */
        private fun postAppScopeEvent(eventName: String, payload: JSONObject) =
            postDesktopAppScopeEvent(eventName, payload.toString())

        private fun Any?.toRawPayloadString(): String =
            when (this) {
                is String -> this
                is JSONObject -> toString()
                is JSONArray -> toString()
                else -> this?.toString() ?: "{}"
            }
    }
}
