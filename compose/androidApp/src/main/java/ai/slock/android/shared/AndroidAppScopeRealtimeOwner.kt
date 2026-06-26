package ai.slock.android.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.compose.data.KuiklyMMKVAuthSessionStore
import ai.slock.compose.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import ai.slock.compose.data.RealtimeSubscription
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.SlockLocalPreferenceStore
import ai.slock.compose.data.buildSystemUnreadNotificationRequest
import ai.slock.compose.data.realtime.RealtimeAuth
import ai.slock.compose.data.realtime.RealtimeConnectionState
import ai.slock.compose.data.realtime.RealtimeConnectRequest
import ai.slock.compose.data.realtime.SlockRealtimeAppScopeConnection
import ai.slock.compose.data.realtime.SlockRealtimeAppScopeCoordinator
import ai.slock.compose.data.realtime.SlockRealtimeAppScopePolicy
import ai.slock.compose.data.realtime.SlockRealtimeAppScopeSink
import ai.slock.compose.data.realtime.SlockRealtimeNotifyContract
import ai.slock.compose.model.ConversationKind
import com.tencent.kuikly.core.render.android.expand.module.getKuiklyEventName
import com.tencent.kuikly.core.render.android.expand.module.getKuiklyEventParams
import com.tencent.kuikly.core.render.android.expand.module.registerKuiklyBroadcastReceiver
import com.tencent.kuikly.core.render.android.expand.module.sendKuiklyEvent
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal object AndroidAppScopeRealtimeOwner {
    private const val LOG_TAG = "SlockKuiklyRealtime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var realtimeClient: SocketIoRealtimeClient? = null
    private var realtimeCoordinator: SlockRealtimeAppScopeCoordinator? = null
    private var realtimeSubscription: RealtimeSubscription? = null
    private var realtimeRawSubscription: RealtimeSubscription? = null
    private var realtimeConnectJob: Job? = null
    private var appVisibility: AppVisibility = AppVisibility.Background
    private var backgroundKeepAliveEnabled = false
    private var networkCallbackInstalled = false
    private var realtimeRoomsNotifyReceiver: BroadcastReceiver? = null

    fun install(context: Context) {
        val appContext = context.applicationContext
        installRealtimeRoomsNotifyReceiver(appContext)
        if (networkCallbackInstalled) return
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

    private fun installRealtimeRoomsNotifyReceiver(appContext: Context) {
        synchronized(this) {
            if (realtimeRoomsNotifyReceiver != null) return
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val eventIntent = intent ?: return
                        if (eventIntent.getKuiklyEventName() != SlockRealtimeNotifyContract.RoomsEventName) return
                        handleRealtimeRoomsNotify(eventIntent.getKuiklyEventParams())
                    }
                }
            runCatching {
                appContext.registerKuiklyBroadcastReceiver(receiver)
                realtimeRoomsNotifyReceiver = receiver
                SlockDiagnosticsLogger.d(LOG_TAG, "room_notify_receiver_installed")
            }.onFailure { error ->
                SlockDiagnosticsLogger.w(
                    LOG_TAG,
                    "room_notify_receiver_install_failed message=${error.message.orEmpty()}"
                )
            }
        }
    }

    fun onAppShown(
        context: Context,
        source: String = "unknown"
    ) {
        appVisibility = AppVisibility.Foreground
        realtimeCoordinator?.onAppShown()
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "app_scope_lifecycle event=shown source=$source joined_rooms=${realtimeCoordinator?.joinedChannelCount ?: 0}"
        )
        healthCheckRealtimeConnection(
            context = context.applicationContext,
            allowBackground = true,
            reason = "app_shown"
        )
    }

    fun onAppHidden(source: String = "unknown") {
        appVisibility = AppVisibility.Background
        realtimeCoordinator?.onAppHidden()
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "app_scope_lifecycle event=hidden source=$source joined_rooms=${realtimeCoordinator?.joinedChannelCount ?: 0}"
        )
    }

    fun enableBackgroundKeepAlive(
        context: Context,
        source: String = "unknown"
    ) {
        backgroundKeepAliveEnabled = true
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "background_keep_alive_enabled source=$source visibility=$appVisibility joined_rooms=${realtimeCoordinator?.joinedChannelCount ?: 0}"
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
        val coordinator = ensureRealtimeCoordinator(appContext)
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
                        coordinator.connect(
                            serverId = serverId,
                            accessToken = accessToken,
                            requestedLastSeq = requestedLastSeq,
                            forceReconnect = false
                        )
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
        val coordinator = ensureRealtimeCoordinator(context.applicationContext)
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
            scope.launch {
                try {
                    val result =
                        coordinator.connect(
                            serverId = serverId,
                            accessToken = accessToken,
                            requestedLastSeq = requestedLastSeq,
                            forceReconnect = forceReconnect
                        )
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
        val coordinator = realtimeCoordinator
        val ownedSession = coordinator?.ownedSession()
        if (client == null || ownedSession == null) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "realtime_health_check_missing_owner_session trigger=$reason"
            )
            connectFromStoredSession(context, coordinator?.lastSeq ?: 0L)
            return
        }
        val serverId = ownedSession.serverId
        if (client.checkHeartbeatSilence()) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "realtime_health_check_reconnect reason=heartbeat_silent trigger=$reason serverId=${serverId.value}"
            )
            return
        }
        when (val state = client.state) {
            RealtimeConnectionState.Disconnected,
            is RealtimeConnectionState.Failed -> reconnectOwnedSession(context, serverId, ownedSession.accessToken, state)
            RealtimeConnectionState.Connected,
            RealtimeConnectionState.RoomsJoined -> {
                SlockDiagnosticsLogger.d(
                    LOG_TAG,
                    "realtime_health_check_skip reason=already_connected trigger=$reason state=${state::class.simpleName} serverId=${serverId.value}"
                )
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
        if (!SlockRealtimeAppScopePolicy.isSessionEndDisconnectReason(cleanReason)) {
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "disconnect_ignored reason=$cleanReason"
            )
            return
        }
        realtimeConnectJob?.cancel()
        realtimeConnectJob = null
        val disconnected = realtimeCoordinator?.disconnectForSessionEnd(cleanReason) ?: run {
            realtimeClient?.disconnect()
            true
        }
        if (!disconnected) return
        backgroundKeepAliveEnabled = false
    }

    fun joinChannel(channelId: ChannelId) {
        if (channelId.value.isBlank()) return
        val coordinator =
            realtimeCoordinator
                ?: run {
                    SlockDiagnosticsLogger.d(
                        LOG_TAG,
                        "room_join_skip reason=missing_coordinator channelId=${channelId.value}"
                    )
                    return
                }
        coordinator.joinChannel(channelId, reason = "direct")
    }

    private fun handleRealtimeRoomsNotify(eventData: JSONObject) {
        when (eventData.optString(SlockRealtimeNotifyContract.KeyAction)) {
            SlockRealtimeNotifyContract.ActionJoinChannel -> {
                val channelId =
                    eventData.optString(SlockRealtimeNotifyContract.KeyChannelId).takeIf { it.isNotBlank() }
                if (channelId == null) {
                    SlockDiagnosticsLogger.w(LOG_TAG, "room_notify_skip reason=missing_channel_id")
                    return
                }
                SlockDiagnosticsLogger.d(LOG_TAG, "room_notify_join channelId=$channelId")
                joinChannel(ChannelId(channelId))
            }
        }
    }

    private fun ensureRealtimeCoordinator(context: Context): SlockRealtimeAppScopeCoordinator {
        realtimeCoordinator?.let { return it }
        val appContext = context.applicationContext
        val sessionStore = KuiklyMMKVAuthSessionStore(androidMMKVAuthKeyValueStore(appContext))
        val apiBaseUrl = AndroidAuthApiFactory.currentBaseUrl(appContext)
        val client =
            SocketIoRealtimeClient(
                transport =
                    SocketIoSocketTransport(
                        socketUrl = AndroidAuthApiFactory.currentSocketBaseUrl(appContext)
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
        val connection =
            object : SlockRealtimeAppScopeConnection {
                override val state: RealtimeConnectionState
                    get() = client.state

                override suspend fun connect(
                    serverId: ServerId,
                    accessToken: String,
                    lastSeq: Long
                ): ai.slock.compose.data.DataResult<Unit> {
                    client.connect(
                        RealtimeConnectRequest(
                            auth = RealtimeAuth(
                                token = accessToken,
                                serverId = serverId
                            ),
                            lastSeq = lastSeq
                        )
                    )
                    return ai.slock.compose.data.DataResult.Content(Unit)
                }

                override fun disconnect() {
                    repository.disconnect()
                }

                override fun joinChannel(channelId: ChannelId) {
                    repository.joinChannel(channelId)
                }

                override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription =
                    repository.observeEvents(listener)

                override fun observeRawEvents(listener: (eventName: String, rawPayload: String) -> Unit): RealtimeSubscription =
                    client.observeRawEvents { eventName, rawPayload ->
                        listener(eventName, rawPayload.toRealtimeRawPayloadString())
                    }
            }
        val sink =
            object : SlockRealtimeAppScopeSink {
                override fun log(message: String) {
                    SlockDiagnosticsLogger.d(LOG_TAG, message)
                }

                override fun fanOutRawRealtimeEvent(
                    serverId: ServerId,
                    eventName: String,
                    rawPayload: String
                ) {
                    appContext.sendKuiklyEvent(
                        SlockRealtimeNotifyContract.EventName,
                        realtimeNotifyPayload(serverId, eventName, rawPayload)
                    )
                }

                override suspend fun maybePostBackgroundUnreadNotification(
                    serverId: ServerId,
                    event: MessageNew,
                    isAppInForeground: Boolean
                ) {
                    maybePostBackgroundUnreadNotification(
                        appContext = appContext,
                        sessionStore = sessionStore,
                        serverId = serverId,
                        event = event,
                        isAppInForeground = isAppInForeground
                    )
                }
            }
        val coordinator = SlockRealtimeAppScopeCoordinator(connection, sink)
        realtimeClient = client
        realtimeCoordinator = coordinator
        realtimeSubscription =
            connection.observeEvents { event ->
                scope.launch {
                    coordinator.onMappedEvent(event)
                }
            }
        realtimeRawSubscription =
            connection.observeRawEvents { eventName, rawPayload ->
                scope.launch {
                    coordinator.onRawEvent(eventName, rawPayload)
                }
            }
        return coordinator
    }

    private fun reconnectOwnedSession(
        context: Context,
        serverId: ServerId,
        accessToken: String,
        state: RealtimeConnectionState
    ) {
        val coordinator = ensureRealtimeCoordinator(context.applicationContext)
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
            scope.launch {
                try {
                    val result =
                        coordinator.connect(
                            serverId = serverId,
                            accessToken = accessToken,
                            requestedLastSeq = coordinator.lastSeq,
                            forceReconnect = true
                        )
                    SlockDiagnosticsLogger.d(
                        LOG_TAG,
                        "foreground_reconnect_result state=${state::class.simpleName} serverId=${serverId.value} result=${result::class.simpleName}"
                    )
                } finally {
                    realtimeConnectJob = null
                }
            }
    }

    private fun realtimeNotifyPayload(serverId: ServerId, eventName: String, rawPayload: Any?): JSONObject =
        JSONObject()
            .put(SlockRealtimeNotifyContract.KeyServerId, serverId.value)
            .put(SlockRealtimeNotifyContract.KeyEventName, eventName)
            .put(SlockRealtimeNotifyContract.KeyRawPayload, rawPayload.toRealtimeRawPayloadString())

    private fun Any?.toRealtimeRawPayloadString(): String =
        when (this) {
            is String -> this
            is JSONObject -> toString()
            is JSONArray -> toString()
            else -> this?.toString() ?: "{}"
        }

    private suspend fun maybePostBackgroundUnreadNotification(
        appContext: Context,
        sessionStore: SlockAuthSessionStore,
        serverId: ServerId,
        event: MessageNew,
        isAppInForeground: Boolean
    ) {
        val preferenceEnabled = isUnreadNotificationPreferenceEnabled(appContext)
        val hasAttachedConsumers = false
        SlockDiagnosticsLogger.w(
            LOG_TAG,
            "background_unread_notification_candidate serverId=${serverId.value} messageId=${event.message.id.value} preference_enabled=$preferenceEnabled foreground=$isAppInForeground"
        )
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
        SlockRealtimeAppScopePolicy.shouldPostBackgroundUnreadNotification(
            hasAttachedConsumers = hasAttachedConsumers,
            preferenceEnabled = preferenceEnabled,
            isAppInForeground = isAppInForeground
        )

    internal fun backgroundUnreadNotificationConversationKind(message: Message): ConversationKind? =
        SlockRealtimeAppScopePolicy.backgroundUnreadNotificationConversationKind(message)

    private fun Message.backgroundNotificationConversationKind(): ConversationKind? =
        backgroundUnreadNotificationConversationKind(this)

    private fun backgroundUnreadNotificationSkipReason(
        hasAttachedConsumers: Boolean,
        preferenceEnabled: Boolean,
        isAppInForeground: Boolean
    ): String =
        SlockRealtimeAppScopePolicy.backgroundUnreadNotificationSkipReason(
            hasAttachedConsumers = hasAttachedConsumers,
            preferenceEnabled = preferenceEnabled,
            isAppInForeground = isAppInForeground
        )

    private fun isUnreadNotificationPreferenceEnabled(context: Context): Boolean =
        runCatching {
            SlockKuiklyMMKVModule.ensureInitialized(context.applicationContext)
            val store = MMKV.mmkvWithID(SlockLocalPreferenceStore.KuiklyNamespace)
            val value =
                store.decodeString(
                    AndroidRealtimeKeepAliveController.localPreferenceKey(NOTIFICATION_ENABLED_PREFERENCE_KEY),
                    null
                ) ?: store.decodeString(NOTIFICATION_ENABLED_PREFERENCE_KEY, "true")
            value == "true"
        }.getOrElse { error ->
            SlockDiagnosticsLogger.w(
                LOG_TAG,
                "background_unread_notification_preference_failed message=${error.message.orEmpty()}"
            )
            false
        }

    private enum class AppVisibility {
        Foreground,
        Background
    }

}
