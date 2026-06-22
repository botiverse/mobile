package ai.slock.android.shared

import android.content.Context
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.EncryptedAuthSessionStore
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import ai.slock.android.core.data.realtime.SessionRealtimeRepository
import ai.slock.android.core.data.realtime.SocketIoRealtimeClient
import ai.slock.android.core.data.realtime.SocketIoSocketTransport
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.DirectMessageCreated
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.RealtimeRepository
import ai.slock.compose.data.RealtimeSubscription
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.realtime.RealtimeAuth
import ai.slock.compose.data.realtime.RealtimeConnectionState
import ai.slock.compose.data.realtime.RealtimeCursorPolicy
import ai.slock.compose.data.realtime.RealtimeConnectRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object AndroidRealtimeBridgeManager {
    private const val LOG_TAG = "SlockKuiklyRealtime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consumers = linkedMapOf<String, RealtimeConsumer>()
    private val surfaceRooms = linkedMapOf<String, Set<ChannelId>>()
    private val joinedRooms = linkedSetOf<ChannelId>()

    private var realtimeRepository: RealtimeRepository? = null
    private var realtimeClient: SocketIoRealtimeClient? = null
    private var realtimeSubscription: RealtimeSubscription? = null
    private var realtimeRawSubscription: RealtimeSubscription? = null
    private var realtimeConnectJob: Job? = null
    private var realtimeServerId: ServerId? = null
    private var realtimeSessionKey: String? = null
    private var realtimeAccessToken: String? = null
    private var realtimeLastSeq: Long = 0L

    fun attachConsumer(
        surfaceId: String,
        sink: (serverId: ServerId, eventName: String, rawPayload: Any?) -> Unit
    ) {
        consumers[surfaceId] = RealtimeConsumer(surfaceId, sink)
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "consumer_attach surfaceId=$surfaceId consumer_count=${consumers.size}"
        )
    }

    fun detachConsumer(surfaceId: String) {
        consumers.remove(surfaceId)
        surfaceRooms.remove(surfaceId)
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "consumer_detach surfaceId=$surfaceId consumer_count=${consumers.size}"
        )
    }

    fun ensureConnectedFromStoredSession(
        context: Context,
        requestedLastSeq: Long = 0L
    ) {
        val appContext = context.applicationContext
        val repository = ensureRealtimeRepository(appContext)
        val sessionStore = EncryptedAuthSessionStore(appContext)
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
            scope.launch {
                try {
                    val session = sessionStore.readSession()
                    val serverId =
                        session?.currentServerId?.let(::ServerId)
                            ?: run {
                                SlockDiagnosticsLogger.w(
                                    LOG_TAG,
                                    "connect_skip reason=missing_selected_server"
                                )
                                return@launch
                            }
                    val accessToken =
                        session.accessToken.takeIf { it.isNotBlank() }
                            ?: run {
                                SlockDiagnosticsLogger.w(
                                    LOG_TAG,
                                    "connect_skip reason=missing_access_token serverId=${serverId.value}"
                                )
                                return@launch
                            }
                    val result =
                        connectRealtimeRepository(
                            repository = repository,
                            serverId = serverId,
                            accessToken = accessToken,
                            requestedLastSeq = requestedLastSeq,
                            forceReconnect = false
                        )
                    if (result is DataResult.Content) {
                        joinDeclaredRooms()
                    }
                } finally {
                    realtimeConnectJob = null
                }
            }
    }

    fun ensureConnected(
        context: Context,
        serverId: ServerId,
        accessToken: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean
    ) {
        if (serverId.value.isBlank() || accessToken.isBlank()) return
        val repository = ensureRealtimeRepository(context.applicationContext)
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
            scope.launch {
                try {
                    val result =
                        connectRealtimeRepository(
                            repository = repository,
                            serverId = serverId,
                            accessToken = accessToken,
                            requestedLastSeq = requestedLastSeq,
                            forceReconnect = forceReconnect
                        )
                    if (result is DataResult.Content) {
                        joinDeclaredRooms()
                    }
                } finally {
                    realtimeConnectJob = null
                }
            }
    }

    fun handleForeground(context: Context) {
        val client = realtimeClient
        val serverId = realtimeServerId
        val accessToken = realtimeAccessToken
        if (client == null || serverId == null || accessToken.isNullOrBlank()) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "foreground_reconnect_skip reason=missing_owner_session"
            )
            return
        }
        if (client.checkHeartbeatSilence()) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "foreground_reconnect reason=heartbeat_silent serverId=${serverId.value}"
            )
            joinDeclaredRooms()
            return
        }
        when (val state = client.state) {
            RealtimeConnectionState.Disconnected,
            is RealtimeConnectionState.Failed -> reconnectOwnedSession(context, serverId, accessToken, state)
            RealtimeConnectionState.Connected,
            RealtimeConnectionState.RoomsJoined -> {
                SlockDiagnosticsLogger.d(
                    LOG_TAG,
                    "foreground_reconnect_skip reason=already_connected state=${state::class.simpleName} serverId=${serverId.value}"
                )
                joinDeclaredRooms()
            }
            RealtimeConnectionState.Connecting ->
                SlockDiagnosticsLogger.d(
                    LOG_TAG,
                    "foreground_reconnect_skip reason=connecting serverId=${serverId.value}"
                )
        }
    }

    fun disconnectForSessionEnd(reason: String) {
        val cleanReason = reason.takeIf { it.isNotBlank() } ?: "shared_disconnect"
        if (!cleanReason.isSessionEndRealtimeDisconnectReason()) {
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "disconnect_ignored reason=$cleanReason"
            )
            return
        }
        realtimeConnectJob?.cancel()
        realtimeConnectJob = null
        realtimeRepository?.disconnect()
        realtimeServerId = null
        realtimeSessionKey = null
        realtimeAccessToken = null
        realtimeLastSeq = 0L
        surfaceRooms.clear()
        joinedRooms.clear()
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "disconnect_requested reason=$cleanReason"
        )
    }

    fun declareRooms(surfaceId: String, rooms: Set<ChannelId>) {
        if (rooms.isEmpty()) {
            surfaceRooms.remove(surfaceId)
        } else {
            surfaceRooms[surfaceId] = rooms
        }
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "room_declared surfaceId=$surfaceId rooms=${rooms.size} roomIds=${rooms.joinToString(",") { it.value }} consumer_count=${consumers.size}"
        )
        joinDeclaredRooms()
    }

    fun joinChannel(channelId: ChannelId) {
        if (channelId.value.isBlank()) return
        joinRoom(channelId, reason = "direct")
    }

    private fun ensureRealtimeRepository(context: Context): RealtimeRepository {
        realtimeRepository?.let { return it }
        val sessionStore = EncryptedAuthSessionStore(context.applicationContext)
        val client =
            SocketIoRealtimeClient(
                transport = SocketIoSocketTransport(),
                logger = { message ->
                    SlockDiagnosticsLogger.d(LOG_TAG, message)
                }
            )
        val repository =
            SessionRealtimeRepository(
                store = sessionStore,
                client = client,
                tokenRefresher = AuthTokenRefresher(sessionStore)
            )
        realtimeClient = client
        realtimeRepository = repository
        realtimeSubscription =
            repository.observeEvents { event ->
                scope.launch {
                    realtimeServerId ?: return@launch
                    realtimeLastSeq = realtimeLastSeq.updateFrom(event)
                    if (event is DirectMessageCreated) {
                        joinRoom(event.channelId, reason = "direct_message_created")
                    }
                }
            }
        client.observeRawEvents { eventName, rawPayload ->
            scope.launch {
                val serverId = realtimeServerId ?: return@launch
                fanOutRawRealtimeEvent(serverId, eventName, rawPayload)
            }
        }.also { realtimeRawSubscription = it }
        return repository
    }

    private suspend fun connectRealtimeRepository(
        repository: RealtimeRepository,
        serverId: ServerId,
        accessToken: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean
    ): DataResult<Unit> {
        val client =
            realtimeClient
                ?: return DataResult.Error(ScreenError(code = "realtime_client_missing"))
        val nextSessionKey = "$accessToken|${serverId.value}"
        val previousSessionKey = realtimeSessionKey
        val previousServerId = realtimeServerId
        val resumeSeq =
            if (previousSessionKey != null && previousSessionKey != nextSessionKey) {
                realtimeLastSeq = 0L
                joinedRooms.clear()
                if (previousServerId != null && previousServerId != serverId) {
                    surfaceRooms.clear()
                }
                0L
            } else {
                maxOf(requestedLastSeq, realtimeLastSeq)
            }
        if (!forceReconnect && realtimeServerId == serverId && realtimeSessionKey == nextSessionKey) {
            return DataResult.Content(Unit)
        }
        realtimeSessionKey = nextSessionKey
        realtimeServerId = serverId
        realtimeAccessToken = accessToken
        if (resumeSeq == 0L) {
            realtimeLastSeq = 0L
        }
        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(
                    token = accessToken,
                    serverId = serverId
                ),
                lastSeq = resumeSeq
            )
        )
        val result = DataResult.Content(Unit)
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "connect_result serverId=${serverId.value} result=${result::class.simpleName} lastSeq=$resumeSeq"
        )
        return result
    }

    private fun reconnectOwnedSession(
        context: Context,
        serverId: ServerId,
        accessToken: String,
        state: RealtimeConnectionState
    ) {
        val repository = ensureRealtimeRepository(context.applicationContext)
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
            scope.launch {
                try {
                    val result =
                        connectRealtimeRepository(
                            repository = repository,
                            serverId = serverId,
                            accessToken = accessToken,
                            requestedLastSeq = realtimeLastSeq,
                            forceReconnect = true
                        )
                    SlockDiagnosticsLogger.d(
                        LOG_TAG,
                        "foreground_reconnect_result state=${state::class.simpleName} serverId=${serverId.value} result=${result::class.simpleName}"
                    )
                    if (result is DataResult.Content) {
                        joinDeclaredRooms()
                    }
                } finally {
                    realtimeConnectJob = null
                }
            }
    }

    private fun joinDeclaredRooms() {
        surfaceRooms.values.flatten().distinct().forEach { channelId ->
            joinRoom(channelId, reason = "declared")
        }
    }

    private fun joinRoom(channelId: ChannelId, reason: String) {
        val repository = realtimeRepository ?: run {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "room_join_skip reason=missing_repository channelId=${channelId.value}"
            )
            return
        }
        if (!joinedRooms.add(channelId)) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "room_join_skip reason=already_joined channelId=${channelId.value}"
            )
            return
        }
        repository.joinChannel(channelId)
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "room_join_emit reason=$reason channelId=${channelId.value}"
        )
    }

    private fun fanOutRawRealtimeEvent(serverId: ServerId, eventName: String, rawPayload: Any?) {
        val snapshot = consumers.values.toList()
        snapshot.forEach { consumer ->
            consumer.sink(serverId, eventName, rawPayload)
        }
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "raw_event_fanout eventName=$eventName serverId=${serverId.value} consumer_count=${snapshot.size}"
        )
    }

    private fun Long.updateFrom(event: RealtimeEvent): Long =
        RealtimeCursorPolicy.updateAppliedSeq(this, event)

    private fun String.isSessionEndRealtimeDisconnectReason(): Boolean =
        this in setOf(
            "auth_invalid",
            "logout",
            "process_teardown",
            "server_deleted",
            "server_membership_removed",
            "server_removed",
            "server_switch"
        )

    private data class RealtimeConsumer(
        val surfaceId: String,
        val sink: (serverId: ServerId, eventName: String, rawPayload: Any?) -> Unit
    )
}
