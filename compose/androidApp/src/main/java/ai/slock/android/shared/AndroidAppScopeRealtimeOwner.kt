package ai.slock.android.shared

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import ai.slock.android.core.data.auth.AndroidAuthApiFactory
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.androidMMKVAuthKeyValueStore
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
import ai.slock.compose.data.KuiklyMMKVAuthSessionStore
import ai.slock.compose.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import ai.slock.compose.data.RealtimeRepository
import ai.slock.compose.data.RealtimeSubscription
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.SlockAuthSessionStore
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

internal object AndroidAppScopeRealtimeOwner {
    private const val LOG_TAG = "SlockKuiklyRealtime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rawConsumers = linkedMapOf<String, RealtimeConsumer>()
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
    private var appVisibility: AppVisibility = AppVisibility.Background
    private var backgroundKeepAliveEnabled = false
    private var networkCallbackInstalled = false

    fun install(context: Context) {
        if (networkCallbackInstalled) return
        val appContext = context.applicationContext
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
        synchronized(this) {
            if (networkCallbackInstalled) return
            runCatching {
                connectivityManager.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            onNetworkAvailable(appContext)
                        }

                        override fun onLost(network: Network) {
                            onNetworkLost()
                        }
                    }
                )
                networkCallbackInstalled = true
                SlockDiagnosticsLogger.d(LOG_TAG, "app_scope_lifecycle event=network_callback_installed")
            }.onFailure { error ->
                SlockDiagnosticsLogger.w(
                    LOG_TAG,
                    "app_scope_lifecycle event=network_callback_install_failed message=${error.message.orEmpty()}"
                )
            }
        }
    }

    fun onAppShown(
        context: Context,
        source: String = "unknown"
    ) {
        appVisibility = AppVisibility.Foreground
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "app_scope_lifecycle event=shown source=$source consumer_count=${rawConsumers.size} declared_room_surfaces=${surfaceRooms.size}"
        )
        healthCheckRealtimeConnection(
            context = context.applicationContext,
            allowBackground = true,
            reason = "app_shown"
        )
    }

    fun onAppHidden(source: String = "unknown") {
        appVisibility = AppVisibility.Background
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "app_scope_lifecycle event=hidden source=$source consumer_count=${rawConsumers.size} declared_room_surfaces=${surfaceRooms.size}"
        )
    }

    fun enableBackgroundKeepAlive(
        context: Context,
        source: String = "unknown"
    ) {
        backgroundKeepAliveEnabled = true
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "background_keep_alive_enabled source=$source visibility=$appVisibility declared_room_surfaces=${surfaceRooms.size}"
        )
        healthCheckRealtimeConnection(
            context = context.applicationContext,
            allowBackground = true,
            reason = "background_keep_alive"
        )
    }

    fun disableBackgroundKeepAlive(reason: String = "unknown") {
        if (!backgroundKeepAliveEnabled) return
        backgroundKeepAliveEnabled = false
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "background_keep_alive_disabled reason=$reason visibility=$appVisibility"
        )
    }

    fun attachKuiklyRawConsumer(
        surfaceId: String,
        sink: (serverId: ServerId, eventName: String, rawPayload: Any?) -> Unit
    ) {
        rawConsumers[surfaceId] = RealtimeConsumer(surfaceId, sink)
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "kuikly_raw_consumer_attach surfaceId=$surfaceId consumer_count=${rawConsumers.size} declared_room_surfaces=${surfaceRooms.size}"
        )
    }

    fun detachKuiklyRawConsumer(surfaceId: String, retainDeclaredRooms: Boolean = false) {
        rawConsumers.remove(surfaceId)
        if (!retainDeclaredRooms) {
            surfaceRooms.remove(surfaceId)
        }
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "kuikly_raw_consumer_detach surfaceId=$surfaceId consumer_count=${rawConsumers.size} retain_declared_rooms=$retainDeclaredRooms declared_room_surfaces=${surfaceRooms.size}"
        )
    }

    fun connectFromStoredSession(
        context: Context,
        requestedLastSeq: Long = 0L
    ) {
        connectFromStoredSession(context, requestedLastSeq, missingSelectedServerRetryCount = 0)
    }

    private fun connectFromStoredSession(
        context: Context,
        requestedLastSeq: Long,
        missingSelectedServerRetryCount: Int
    ) {
        val appContext = context.applicationContext
        val repository = ensureRealtimeRepository(appContext)
        val sessionStore = KuiklyMMKVAuthSessionStore(androidMMKVAuthKeyValueStore(appContext))
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
            scope.launch {
                try {
                    var session = sessionStore.readSession()
                    var serverId = session?.currentServerId?.let(::ServerId)
                    if (serverId == null) {
                        SlockDiagnosticsLogger.w(
                            LOG_TAG,
                            "connect_skip reason=missing_selected_server retry=$missingSelectedServerRetryCount"
                        )
                        if (missingSelectedServerRetryCount < 1) {
                            delay(1500L)
                            session = sessionStore.readSession()
                            serverId = session?.currentServerId?.let(::ServerId)
                            if (serverId == null) {
                                SlockDiagnosticsLogger.w(
                                    LOG_TAG,
                                    "connect_skip reason=missing_selected_server retry=${missingSelectedServerRetryCount + 1}"
                                )
                            }
                        }
                        if (serverId == null) return@launch
                    }
                    val accessToken =
                        session?.accessToken?.takeIf { it.isNotBlank() }
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

    fun connectFromSharedRequest(
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
            connectFromStoredSession(context, requestedLastSeq)
            return
        }
        SlockDiagnosticsLogger.w(
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

    fun onNetworkAvailable(context: Context) {
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "app_scope_lifecycle event=network_available visibility=$appVisibility keep_alive=$backgroundKeepAliveEnabled"
        )
        healthCheckRealtimeConnection(
            context = context.applicationContext,
            allowBackground = backgroundKeepAliveEnabled,
            reason = "network_available"
        )
    }

    fun onNetworkLost() {
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "app_scope_lifecycle event=network_lost visibility=$appVisibility"
        )
    }

    private fun healthCheckRealtimeConnection(
        context: Context,
        allowBackground: Boolean,
        reason: String
    ) {
        if (appVisibility != AppVisibility.Foreground && !allowBackground) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "realtime_health_check_skip reason=app_background trigger=$reason"
            )
            return
        }
        val client = realtimeClient
        val serverId = realtimeServerId
        val accessToken = realtimeAccessToken
        if (client == null || serverId == null || accessToken.isNullOrBlank()) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "realtime_health_check_missing_owner_session trigger=$reason"
            )
            connectFromStoredSession(context, realtimeLastSeq)
            return
        }
        if (client.checkHeartbeatSilence()) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "realtime_health_check_reconnect reason=heartbeat_silent trigger=$reason serverId=${serverId.value}"
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
                    "realtime_health_check_skip reason=already_connected trigger=$reason state=${state::class.simpleName} serverId=${serverId.value}"
                )
                joinDeclaredRooms()
            }
            RealtimeConnectionState.Connecting ->
                SlockDiagnosticsLogger.d(
                    LOG_TAG,
                    "realtime_health_check_skip reason=connecting trigger=$reason serverId=${serverId.value}"
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
        backgroundKeepAliveEnabled = false
        surfaceRooms.clear()
        joinedRooms.clear()
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "disconnect_requested reason=$cleanReason visibility=$appVisibility"
        )
    }

    fun declareKuiklySurfaceRooms(surfaceId: String, rooms: Set<ChannelId>) {
        if (rooms.isEmpty()) {
            surfaceRooms.remove(surfaceId)
        } else {
            surfaceRooms[surfaceId] = rooms
        }
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "kuikly_surface_rooms_declared surfaceId=$surfaceId rooms=${rooms.size} roomIds=${rooms.joinToString(",") { it.value }} consumer_count=${rawConsumers.size}"
        )
        joinDeclaredRooms()
    }

    fun joinChannel(channelId: ChannelId) {
        if (channelId.value.isBlank()) return
        joinRoom(channelId, reason = "direct")
    }

    private fun ensureRealtimeRepository(context: Context): RealtimeRepository {
        realtimeRepository?.let { return it }
        val sessionStore = KuiklyMMKVAuthSessionStore(androidMMKVAuthKeyValueStore(context.applicationContext))
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
                                    "native_event_skip reason=missing_server event=${event.nativeRealtimeEventName()} consumer_count=${rawConsumers.size}"
                                )
                                return@launch
                            }
                    SlockDiagnosticsLogger.d(
                        LOG_TAG,
                        "native_event_received event=${event.nativeRealtimeEventName()} serverId=${serverId.value} consumer_count=${rawConsumers.size} declared_room_surfaces=${surfaceRooms.size} joined_rooms=${joinedRooms.size}"
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
                                "native_raw_event_skip reason=missing_server eventName=$eventName consumer_count=${rawConsumers.size} payloadType=${rawPayload?.javaClass?.simpleName.orEmpty()}"
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
            when (val state = client.state) {
                RealtimeConnectionState.Connected,
                RealtimeConnectionState.RoomsJoined,
                RealtimeConnectionState.Connecting -> return DataResult.Content(Unit)
                RealtimeConnectionState.Disconnected,
                is RealtimeConnectionState.Failed ->
                    SlockDiagnosticsLogger.d(
                        LOG_TAG,
                        "connect_same_session_reconnect state=${state::class.simpleName} serverId=${serverId.value}"
                    )
            }
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
        SlockDiagnosticsLogger.w(
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
        val snapshot = rawConsumers.values.toList()
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
        sessionStore: SlockAuthSessionStore,
        serverId: ServerId,
        event: MessageNew
    ) {
        val preferenceEnabled = isUnreadNotificationPreferenceEnabled(appContext)
        val hasAttachedConsumers = rawConsumers.isNotEmpty()
        SlockDiagnosticsLogger.w(
            LOG_TAG,
            "background_unread_notification_candidate serverId=${serverId.value} messageId=${event.message.id.value} consumer_count=${rawConsumers.size} preference_enabled=$preferenceEnabled visibility=$appVisibility"
        )
        val isAppInForeground = appVisibility == AppVisibility.Foreground
        if (!shouldPostBackgroundUnreadNotification(hasAttachedConsumers, preferenceEnabled, isAppInForeground)) {
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "background_unread_notification_skip reason=${backgroundUnreadNotificationSkipReason(hasAttachedConsumers, preferenceEnabled, isAppInForeground)} serverId=${serverId.value} messageId=${event.message.id.value}"
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
                isAppInForeground = isAppInForeground
            )
        if (request == null) {
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "background_unread_notification_skip reason=policy serverId=${serverId.value} messageId=${event.message.id.value}"
            )
            return
        }
        val result = AndroidUnreadNotificationPoster.postUnreadNotification(appContext, request)
        SlockDiagnosticsLogger.w(
            LOG_TAG,
            "background_unread_notification_result code=${result["code"]} status=${result["status"]} serverId=${serverId.value} messageId=${event.message.id.value}"
        )
    }

    internal fun shouldPostBackgroundUnreadNotification(
        hasAttachedConsumers: Boolean,
        preferenceEnabled: Boolean,
        isAppInForeground: Boolean
    ): Boolean =
        !hasAttachedConsumers && preferenceEnabled && !isAppInForeground

    internal fun backgroundUnreadNotificationConversationKind(message: Message): ConversationKind? =
        when {
            message.parentMessageId != null || message.threadChannelId != null -> ConversationKind.Thread
            message.channelId != null -> ConversationKind.Channel
            else -> null
        }

    private fun Message.backgroundNotificationConversationKind(): ConversationKind? =
        backgroundUnreadNotificationConversationKind(this)

    private fun backgroundUnreadNotificationSkipReason(
        hasAttachedConsumers: Boolean,
        preferenceEnabled: Boolean,
        isAppInForeground: Boolean
    ): String =
        when {
            hasAttachedConsumers -> "foreground_consumer"
            isAppInForeground -> "app_foreground"
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
                .decodeString(NOTIFICATION_ENABLED_PREFERENCE_KEY, "true") == "true"
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

    private enum class AppVisibility {
        Foreground,
        Background
    }

    private data class RealtimeConsumer(
        val surfaceId: String,
        val sink: (serverId: ServerId, eventName: String, rawPayload: Any?) -> Unit
    )
}
