package ai.slock.android.shared

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import ai.slock.android.core.data.auth.androidMMKVAuthKeyValueStore
import ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger
import ai.slock.android.core.data.realtime.SocketIoRealtimeClient
import ai.slock.compose.model.Message
import ai.slock.compose.model.ServerId
import ai.slock.compose.data.KuiklyMMKVAuthSessionStore
import ai.slock.compose.data.SlockHostNetworkNotifyContract
import ai.slock.compose.data.realtime.RealtimeConnectionState
import ai.slock.compose.data.realtime.SlockRealtimeAppScopePolicy
import ai.slock.compose.data.realtime.SlockRealtimeAppScopeRuntime
import ai.slock.compose.data.realtime.bootstrapRealtime
import ai.slock.compose.model.ConversationKind
import com.tencent.kuikly.core.render.android.expand.module.sendKuiklyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal object AndroidAppScopeRealtimeOwner {
    private const val LOG_TAG = "SlockKuiklyRealtime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var realtimeClient: SocketIoRealtimeClient? = null
    private var realtimeRuntime: SlockRealtimeAppScopeRuntime? = null
    private var realtimeConnectJob: Job? = null
    private var appVisibility: AppVisibility = AppVisibility.Background
    private var backgroundKeepAliveEnabled = false
    private var networkCallbackInstalled = false
    private var hostNetworkOnline: Boolean? = null

    fun install(context: Context) {
        val appContext = context.applicationContext
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

                        override fun onCapabilitiesChanged(
                            network: Network,
                            _networkCapabilities: NetworkCapabilities
                        ) {
                            onNetworkCapabilitiesChanged(appContext)
                        }

                        override fun onLost(network: Network) {
                            onNetworkLost(appContext)
                        }
                    }
                )
                networkCallbackInstalled = true
                publishCurrentNetworkStatus(appContext, source = "network_callback_initial")
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
        realtimeRuntime?.onAppShown()
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "app_scope_lifecycle event=shown source=$source joined_rooms=${realtimeRuntime?.joinedChannelCount ?: 0}"
        )
        healthCheckRealtimeConnection(
            context = context.applicationContext,
            allowBackground = true,
            reason = "app_shown"
        )
    }

    fun onAppHidden(source: String = "unknown") {
        appVisibility = AppVisibility.Background
        realtimeRuntime?.onAppHidden()
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "app_scope_lifecycle event=hidden source=$source joined_rooms=${realtimeRuntime?.joinedChannelCount ?: 0}"
        )
    }

    fun enableBackgroundKeepAlive(
        context: Context,
        source: String = "unknown"
    ) {
        backgroundKeepAliveEnabled = true
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "background_keep_alive_enabled source=$source visibility=$appVisibility joined_rooms=${realtimeRuntime?.joinedChannelCount ?: 0}"
        )
        healthCheckRealtimeConnection(
            context = context.applicationContext,
            allowBackground = true,
            reason = "background_keep_alive"
        )
    }

    fun publishCurrentNetworkStatus(
        context: Context,
        source: String = "unknown"
    ) {
        val appContext = context.applicationContext
        emitHostNetworkStatus(
            context = appContext,
            online = appContext.isHostNetworkOnline(),
            source = source,
            force = true
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
        val runtime = bootstrapRealtime(appContext)
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
                        runtime.connect(
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
        val runtime = bootstrapRealtime(context.applicationContext)
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
            scope.launch {
                try {
                    val result =
                        runtime.connect(
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
        val appContext = context.applicationContext
        emitHostNetworkStatus(
            context = appContext,
            online = appContext.isHostNetworkOnline(),
            source = "network_available"
        )
        healthCheckRealtimeConnection(
            context = appContext,
            allowBackground = backgroundKeepAliveEnabled,
            reason = "network_available"
        )
    }

    fun onNetworkCapabilitiesChanged(context: Context) {
        val appContext = context.applicationContext
        emitHostNetworkStatus(
            context = appContext,
            online = appContext.isHostNetworkOnline(),
            source = "network_capabilities_changed"
        )
    }

    fun onNetworkLost(context: Context) {
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "app_scope_lifecycle event=network_lost visibility=$appVisibility"
        )
        val appContext = context.applicationContext
        emitHostNetworkStatus(
            context = appContext,
            online = appContext.isHostNetworkOnline(),
            source = "network_lost"
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
        val runtime = realtimeRuntime
        val ownedSession = runtime?.ownedSession()
        if (client == null || ownedSession == null) {
            SlockDiagnosticsLogger.d(
                LOG_TAG,
                "realtime_health_check_missing_owner_session trigger=$reason"
            )
            connectFromStoredSession(context, runtime?.lastSeq ?: 0L)
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
        val disconnected = realtimeRuntime?.disconnectForSessionEnd(cleanReason) ?: run {
            realtimeClient?.disconnect()
            true
        }
        if (!disconnected) return
        backgroundKeepAliveEnabled = false
    }

    private fun bootstrapRealtime(context: Context): SlockRealtimeAppScopeRuntime {
        realtimeRuntime?.let { return it }
        val hostContext = AndroidAppScopeRealtimeHostContext.create(context.applicationContext, scope)
        val runtime = bootstrapRealtime(hostContext)
        realtimeClient = hostContext.client
        realtimeRuntime = runtime
        return runtime
    }

    private fun reconnectOwnedSession(
        context: Context,
        serverId: ServerId,
        accessToken: String,
        state: RealtimeConnectionState
    ) {
        val runtime = bootstrapRealtime(context.applicationContext)
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
            scope.launch {
                try {
                    val result =
                        runtime.connect(
                            serverId = serverId,
                            accessToken = accessToken,
                            requestedLastSeq = runtime.lastSeq,
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

    private fun emitHostNetworkStatus(
        context: Context,
        online: Boolean,
        source: String,
        force: Boolean = false
    ) {
        if (!force && hostNetworkOnline == online) return
        hostNetworkOnline = online
        SlockDiagnosticsLogger.d(
            LOG_TAG,
            "app_scope_lifecycle event=host_network_status online=$online source=$source"
        )
        scope.launch {
            context.applicationContext.sendKuiklyEvent(
                SlockHostNetworkNotifyContract.EventName,
                JSONObject()
                    .put(
                        SlockHostNetworkNotifyContract.KeyStatus,
                        if (online) {
                            SlockHostNetworkNotifyContract.StatusOnline
                        } else {
                            SlockHostNetworkNotifyContract.StatusOffline
                        }
                    )
                    .put(SlockHostNetworkNotifyContract.KeyOnline, online)
                    .put(SlockHostNetworkNotifyContract.KeySource, source)
            )
        }
    }

    private fun Context.isHostNetworkOnline(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    internal fun shouldPostBackgroundUnreadNotification(
        hasAttachedConsumers: Boolean,
        preferenceEnabled: Boolean,
        isAppInForeground: Boolean
    ): Boolean =
        AndroidAppScopeRealtimeHostContext.shouldPostBackgroundUnreadNotification(
            hasAttachedConsumers = hasAttachedConsumers,
            preferenceEnabled = preferenceEnabled,
            isAppInForeground = isAppInForeground
        )

    internal fun backgroundUnreadNotificationConversationKind(message: Message): ConversationKind? =
        AndroidAppScopeRealtimeHostContext.backgroundUnreadNotificationConversationKind(message)

    private enum class AppVisibility {
        Foreground,
        Background
    }

}
