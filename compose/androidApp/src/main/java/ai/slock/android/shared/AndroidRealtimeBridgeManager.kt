package ai.slock.android.shared

import android.content.Context
import ai.slock.android.core.data.auth.AndroidAuthApiFactory
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.EncryptedAuthSessionStore
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import ai.slock.android.core.data.realtime.SessionRealtimeRepository
import ai.slock.android.core.data.realtime.SocketIoRealtimeClient
import ai.slock.android.core.data.realtime.SocketIoSocketTransport
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.DirectMessageCreated
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import ai.slock.compose.data.RealtimeRepository
import ai.slock.compose.data.RealtimeSubscription
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.SlockLocalPreferenceStore
import ai.slock.compose.data.buildSystemUnreadNotificationRequest
import ai.slock.compose.data.realtime.RealtimeAuth
import ai.slock.compose.data.realtime.RealtimeConnectionState
import ai.slock.compose.data.realtime.RealtimeCursorPolicy
import ai.slock.compose.data.realtime.RealtimeConnectRequest
import ai.slock.compose.data.realtime.RealtimeSocketEvents
import ai.slock.compose.model.ConversationKind
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object AndroidRealtimeBridgeManager {
    private const val LOG_TAG = "SlockKuiklyRealtime"
    private const val STORED_SESSION_CONNECT_RETRY_DELAY_MS = 1_500L

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
            "consumer_attach surfaceId=$surfaceId consumer_count=${consumers.size} declared_room_surfaces=${surfaceRooms.size}"
        )
    }

    fun detachConsumer(surfaceId: String, retainDeclaredRooms: Boolean = false) {
        consumers.remove(surfaceId)
        if (!retainDeclaredRooms) {
            surfaceRooms.remove(surfaceId)
        }
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "consumer_detach surfaceId=$surfaceId consumer_count=${consumers.size} retain_declared_rooms=$retainDeclaredRooms declared_room_surfaces=${surfaceRooms.size}"
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
                    var restoredSession = sessionStore.readSession()
                    var serverId = restoredSession?.currentServerId?.let(::ServerId)
                    if (serverId == null) {
                        SlockDiagnosticsLogger.w(
                            LOG_TAG,
                            "connect_skip reason=missing_selected_server"
                        )
                        delay(STORED_SESSION_CONNECT_RETRY_DELAY_MS)
                        restoredSession = sessionStore.readSession()
                        serverId = restoredSession?.currentServerId?.let(::ServerId)
                            ?: run {
                                SlockDiagnosticsLogger.w(
                                    LOG_TAG,
                                    "connect_skip reason=missing_selected_server_after_retry delayMs=$STORED_SESSION_CONNECT_RETRY_DELAY_MS"
                                )
                                return@launch
                            }
                    }
                    val session = restoredSession ?: return@launch
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
        if (serverId.value.isBlank() || accessToken.isBlank()) {
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "connect_skip reason=blank_request serverId=${serverId.value} has_access_token=${accessToken.isNotBlank()}"
            )
            ensureConnectedFromStoredSession(context, requestedLastSeq)
            return
        }
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "connect_request source=shared serverId=${serverId.value} lastSeq=$requestedLastSeq forceReconnect=$forceReconnect"
        )
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
            ensureConnectedFromStoredSession(context, realtimeLastSeq)
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
        val apiBaseUrl = AndroidAuthApiFactory.currentBaseUrl(context.applicationContext)
        val client =
            SocketIoRealtimeClient(
                transport =
                    SocketIoSocketTransport(
                        socketUrl = AndroidAuthApiFactory.currentSocketBaseUrl(context.applicationContext)
                    ),
                logger = { message ->
                    SlockDiagnosticsLogger.d(LOG_TAG, message)
                }
            )
        val repository =
            SessionRealtimeRepository(
                store = sessionStore,
                client = client,
                tokenRefresher = AuthTokenRefresher(sessionStore, AndroidAuthApiFactory.createAuthApi(apiBaseUrl))
            )
        realtimeClient = client
        realtimeRepository = repository
        realtimeSubscription =
            repository.observeEvents { event ->
                scope.launch {
                    val serverId =
                        realtimeServerId
                            ?: run {
                                SlockDiagnosticsLogger.d(
                                    LOG_TAG,
                                    "native_event_skip reason=missing_server event=${event.nativeRealtimeEventName()} consumer_count=${consumers.size}"
                                )
                                return@launch
                            }
                    SlockDiagnosticsLogger.d(
                        LOG_TAG,
                        "native_event_received event=${event.nativeRealtimeEventName()} serverId=${serverId.value} consumer_count=${consumers.size} declared_room_surfaces=${surfaceRooms.size} joined_rooms=${joinedRooms.size}"
                    )
                    realtimeLastSeq = realtimeLastSeq.updateFrom(event)
                    if (event is DirectMessageCreated) {
                        joinRoom(event.channelId, reason = "direct_message_created")
                    }
                    if (event is MessageNew) {
                        maybePostBackgroundUnreadNotification(appContext = context.applicationContext, sessionStore, serverId, event)
                    }
                }
            }
        client.observeRawEvents { eventName, rawPayload ->
            scope.launch {
                val serverId =
                    realtimeServerId
                        ?: run {
                            SlockDiagnosticsLogger.d(
                                LOG_TAG,
                                "native_raw_event_skip reason=missing_server eventName=$eventName consumer_count=${consumers.size} payloadType=${rawPayload?.javaClass?.simpleName.orEmpty()}"
                            )
                            return@launch
                        }
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
        if (eventName == RealtimeSocketEvents.Heartbeat) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "raw_event_fanout_skip eventName=$eventName serverId=${serverId.value} reason=native_heartbeat"
            )
            return
        }
        val snapshot = consumers.values.toList()
        snapshot.forEach { consumer ->
            consumer.sink(serverId, eventName, rawPayload)
        }
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "raw_event_fanout eventName=$eventName serverId=${serverId.value} consumer_count=${snapshot.size}"
        )
    }

    private suspend fun maybePostBackgroundUnreadNotification(
        appContext: Context,
        sessionStore: EncryptedAuthSessionStore,
        serverId: ServerId,
        event: MessageNew
    ) {
        val preferenceEnabled = isUnreadNotificationPreferenceEnabled(appContext)
        val hasAttachedConsumers = consumers.isNotEmpty()
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "background_unread_notification_candidate serverId=${serverId.value} messageId=${event.message.id.value} consumer_count=${consumers.size} preference_enabled=$preferenceEnabled"
        )
        if (!shouldPostBackgroundUnreadNotification(hasAttachedConsumers, preferenceEnabled)) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "background_unread_notification_skip reason=${backgroundUnreadNotificationSkipReason(hasAttachedConsumers, preferenceEnabled)} serverId=${serverId.value} messageId=${event.message.id.value}"
            )
            return
        }
        val session = sessionStore.readSession()
        val request =
            buildSystemUnreadNotificationRequest(
                serverId = serverId.value,
                message = event.message,
                currentMemberId = session?.user?.id,
                isVisibleThread = false,
                conversationName = null,
                conversationKind = event.message.backgroundNotificationConversationKind(),
                currentMemberHandle = session?.user?.email,
                isAppInForeground = false
            )
        if (request == null) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "background_unread_notification_skip reason=policy serverId=${serverId.value} messageId=${event.message.id.value}"
            )
            return
        }
        val result = AndroidUnreadNotificationPoster.postUnreadNotification(appContext, request)
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "background_unread_notification_result code=${result["code"]} status=${result["status"]} serverId=${serverId.value} messageId=${event.message.id.value}"
        )
    }

    internal fun shouldPostBackgroundUnreadNotification(hasAttachedConsumers: Boolean, preferenceEnabled: Boolean): Boolean =
        !hasAttachedConsumers && preferenceEnabled

    internal fun backgroundUnreadNotificationConversationKind(message: Message): ConversationKind? =
        when {
            message.parentMessageId != null || message.threadChannelId != null -> ConversationKind.Thread
            message.channelId != null -> ConversationKind.Channel
            else -> null
        }

    private fun Message.backgroundNotificationConversationKind(): ConversationKind? =
        backgroundUnreadNotificationConversationKind(this)

    private fun backgroundUnreadNotificationSkipReason(hasAttachedConsumers: Boolean, preferenceEnabled: Boolean): String =
        when {
            hasAttachedConsumers -> "foreground_consumer"
            !preferenceEnabled -> "preference_disabled"
            else -> "unknown"
        }

    private fun RealtimeEvent.nativeRealtimeEventName(): String =
        when (this) {
            is DirectMessageCreated -> "direct_message_created"
            is MessageNew -> "message_new"
            else -> this::class.simpleName ?: "unknown"
        }

    private fun isUnreadNotificationPreferenceEnabled(context: Context): Boolean =
        runCatching {
            SlockKuiklyMMKVModule.ensureInitialized(context.applicationContext)
            MMKV.mmkvWithID(SlockLocalPreferenceStore.KuiklyNamespace)
                .decodeString(NOTIFICATION_ENABLED_PREFERENCE_KEY, "false") == "true"
        }.getOrElse { error ->
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "background_unread_notification_preference_failed message=${error.message.orEmpty()}"
            )
            false
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
