package build.raft.app.shared

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import build.raft.app.BuildConfig
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.core.data.network.AndroidNetworkPrewarmController
import build.raft.app.core.data.realtime.SocketIoRealtimeClient
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.SharedAuthTokenProvider
import build.raft.app.data.SharedSlockAuthTransport
import build.raft.app.data.SlockAuthApiClient
import build.raft.app.data.SlockHostNetworkNotifyContract
import build.raft.app.data.updateSlockHostVisibility
import build.raft.app.data.postAndroidAppScopeEvent
import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.data.realtime.RealtimeConnectionState
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.data.realtime.SlockRealtimeAppScopePolicy
import build.raft.app.data.realtime.SlockRealtimeAppScopeRuntime
import build.raft.app.data.realtime.bootstrapRealtime
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal object AndroidAppScopeRealtimeOwner {
    private const val LOG_TAG = "SlockKuiklyRealtime"
    private const val ForegroundReconnectAfterBackgroundMillis = 5L * 60L * 1000L
    private const val RealtimeOwnerReplaceGraceMillis = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var realtimeClient: SocketIoRealtimeClient? = null
    private var realtimeRuntime: SlockRealtimeAppScopeRuntime? = null
    private var realtimeConnectJob: Job? = null
    private var multiSessionRegistry: AndroidRealtimeSessionRegistry<MultiSessionRuntime>? = null
    private val visibleShellOwners = AndroidVisibleShellOwners()
    private var appVisibility: AppVisibility = AppVisibility.Background
    private var lastHiddenAtMillis: Long? = null
    private var backgroundKeepAliveEnabled = false
    private var networkCallbackInstalled = false
    private var hostNetworkOnline: Boolean? = null

    fun onHostVisibilityChanged(
        context: Context,
        visibility: AndroidHostVisibility,
        shellInstanceId: String = "",
        source: String = "unknown"
    ) {
        applyAndroidHostVisibilityTransition(
            visibility = visibility,
            publishVisibility = { publishHostVisibility(visibility, shellInstanceId) },
            applyRuntimeVisibility = {
                when (visibility) {
                    AndroidHostVisibility.Visible -> onAppShown(context, shellInstanceId, source)
                    AndroidHostVisibility.Hidden -> onAppHidden(shellInstanceId, source)
                }
            }
        )
    }

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
                SlockAppLog.d(LOG_TAG, "app_scope_lifecycle event=network_callback_installed")
            }.onFailure { error ->
                SlockAppLog.w(
                    LOG_TAG,
                    "app_scope_lifecycle event=network_callback_install_failed message=${error.message.orEmpty()}"
                )
            }
        }
    }

    private fun onAppShown(
        context: Context,
        shellInstanceId: String = "",
        source: String = "unknown"
    ) {
        val shownAtMillis = System.currentTimeMillis()
        val multiSessionEnabled = multiServerRealtimeEnabled()
        val becameForeground = !multiSessionEnabled || visibleShellOwners.shown(shellInstanceId)
        val hiddenAtMillis = lastHiddenAtMillis.takeIf { becameForeground }
        if (becameForeground) {
            lastHiddenAtMillis = null
            appVisibility = AppVisibility.Foreground
        }
        if (multiSessionEnabled && becameForeground) {
            multiSessionRegistry?.runtimes()?.forEach { it.runtime.onAppShown() }
        } else if (!multiSessionEnabled) {
            realtimeRuntime?.onAppShown()
        }
        SlockAppLog.d(
            LOG_TAG,
            "app_scope_lifecycle event=shown source=$source joined_rooms=${joinedChannelCount()}"
        )
        AndroidNetworkPrewarmController.prewarmForegroundConnection(
            context = context.applicationContext,
            source = source
        )
        if (becameForeground && shouldForceReconnectAfterLongBackground(hiddenAtMillis, shownAtMillis)) {
            if (multiSessionEnabled) {
                multiSessionRegistry?.runtimes().orEmpty().forEach { holder ->
                    reconnectMultiSession(holder, "long_background")
                }
                return
            }
            val runtime = realtimeRuntime
            val ownedSession = runtime?.ownedSession()
            if (runtime != null && ownedSession != null) {
                SlockAppLog.d(
                    LOG_TAG,
                    "foreground_reconnect_request reason=long_background source=$source " +
                        "backgroundMs=${shownAtMillis - (hiddenAtMillis ?: shownAtMillis)} " +
                        "serverId=${ownedSession.serverId.value}"
                )
                connectFromStoredSession(
                    context = context.applicationContext,
                    requestedLastSeq = runtime.lastSeq,
                    forceReconnect = true
                )
                return
            }
        }
        healthCheckRealtimeConnection(
            context = context.applicationContext,
            allowBackground = true,
            reason = "app_shown"
        )
    }

    private fun onAppHidden(
        shellInstanceId: String = "",
        source: String = "unknown"
    ) {
        val multiSessionEnabled = multiServerRealtimeEnabled()
        if (multiSessionEnabled && !visibleShellOwners.hidden(shellInstanceId)) {
            SlockAppLog.d(
                LOG_TAG,
                "app_scope_lifecycle event=hidden_deferred source=$source " +
                    "visible_shells=${visibleShellOwners.snapshot().size}"
            )
            return
        }
        appVisibility = AppVisibility.Background
        lastHiddenAtMillis = System.currentTimeMillis()
        if (multiSessionEnabled) {
            multiSessionRegistry?.runtimes()?.forEach { it.runtime.onAppHidden() }
        } else {
            realtimeRuntime?.onAppHidden()
        }
        SlockAppLog.d(
            LOG_TAG,
            "app_scope_lifecycle event=hidden source=$source joined_rooms=${joinedChannelCount()}"
        )
        AndroidNetworkPrewarmController.cancel(reason = source)
    }

    private fun publishHostVisibility(visibility: AndroidHostVisibility, shellInstanceId: String) {
        updateSlockHostVisibility(
            shellInstanceId = shellInstanceId,
            visible = visibility == AndroidHostVisibility.Visible
        )
        postAndroidAppScopeEvent(
            eventName = SlockRealtimeNotifyContract.EventName,
            eventData =
                JSONObject()
                    .put(SlockRealtimeNotifyContract.KeyAction, SlockRealtimeNotifyContract.ActionAppLifecycle)
                    .put(SlockRealtimeNotifyContract.KeyLifecycleEvent, visibility.wireValue)
                    .put(SlockRealtimeNotifyContract.KeyPageName, SlockKuiklyActivity.PAGE_APP_SHELL)
                    .put(SlockRealtimeNotifyContract.KeyShellInstanceId, shellInstanceId)
        )
    }

    fun enableBackgroundKeepAlive(
        context: Context,
        source: String = "unknown"
    ) {
        backgroundKeepAliveEnabled = true
        SlockAppLog.d(
            LOG_TAG,
            "background_keep_alive_enabled source=$source visibility=$appVisibility joined_rooms=${joinedChannelCount()}"
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
        SlockAppLog.d(
            LOG_TAG,
            "background_keep_alive_disabled reason=$reason visibility=$appVisibility"
        )
    }

    fun connectFromStoredSession(
        context: Context,
        requestedLastSeq: Long = 0L
    ) {
        connectFromStoredSession(
            context = context,
            requestedLastSeq = requestedLastSeq,
            missingSelectedServerRetryCount = 0,
            forceReconnect = false
        )
    }

    private fun connectFromStoredSession(
        context: Context,
        requestedLastSeq: Long,
        missingSelectedServerRetryCount: Int = 0,
        forceReconnect: Boolean = false
    ) {
        val appContext = context.applicationContext
        val runtime = bootstrapRealtime(appContext)
        val sessionStore = KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext))
        val tokenProvider = appContext.sharedRealtimeAuthTokenProvider(sessionStore)
        realtimeConnectJob?.cancel()
        realtimeConnectJob =
            scope.launch {
                try {
                    var session =
                        // Foreground/lifecycle connect jobs are replaceable. Connect with the
                        // latest committed session; Socket.IO auth connect_error may trigger
                        // only the process-owned shared refresh coordinator.
                        tokenProvider.currentSession()
                    var serverId = session?.currentServerId?.let(::ServerId)
                    if (serverId == null) {
                        SlockAppLog.w(
                            LOG_TAG,
                            "connect_skip reason=missing_selected_server retry=$missingSelectedServerRetryCount"
                        )
                        if (missingSelectedServerRetryCount < 1) {
                            delay(1500L)
                            session =
                                tokenProvider.currentSession()
                            serverId = session?.currentServerId?.let(::ServerId)
                            if (serverId == null) {
                                SlockAppLog.w(
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
                                SlockAppLog.w(
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
                            forceReconnect = forceReconnect
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
        shellInstanceId: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean
    ) {
        if (serverId.value.isBlank() || accessToken.isBlank()) {
            SlockAppLog.w(
                LOG_TAG,
                "connect_skip reason=blank_request serverId=${serverId.value} has_access_token=${accessToken.isNotBlank()}"
            )
            connectFromStoredSession(context, requestedLastSeq)
            return
        }
        SlockAppLog.w(
            LOG_TAG,
            "connect_request source=shared serverId=${serverId.value} shellInstanceId=$shellInstanceId " +
                "lastSeq=$requestedLastSeq forceReconnect=$forceReconnect multiSession=${multiServerRealtimeEnabled()}"
        )
        if (multiServerRealtimeEnabled()) {
            connectMultiSession(
                context = context.applicationContext,
                serverId = serverId,
                accessToken = accessToken,
                shellInstanceId = shellInstanceId,
                requestedLastSeq = requestedLastSeq,
                forceReconnect = forceReconnect
            )
            return
        }
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
        SlockAppLog.d(
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
        if (appVisibility == AppVisibility.Foreground) {
            AndroidNetworkPrewarmController.prewarmForegroundConnection(
                context = appContext,
                source = "network_available"
            )
        }
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
        SlockAppLog.d(
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
            SlockAppLog.d(
                LOG_TAG,
                "realtime_health_check_skip reason=app_background trigger=$reason"
            )
            return
        }
        if (multiServerRealtimeEnabled()) {
            val runtimes = multiSessionRegistry?.runtimes().orEmpty()
            if (runtimes.isEmpty()) {
                SlockAppLog.d(LOG_TAG, "realtime_health_check_skip reason=no_multi_session trigger=$reason")
                return
            }
            runtimes.forEach { holder -> healthCheckMultiSession(holder, reason) }
            return
        }
        val client = realtimeClient
        val runtime = realtimeRuntime
        val ownedSession = runtime?.ownedSession()
        if (client == null || ownedSession == null) {
            SlockAppLog.d(
                LOG_TAG,
                "realtime_health_check_missing_owner_session trigger=$reason"
            )
            connectFromStoredSession(context, runtime?.lastSeq ?: 0L)
            return
        }
        val serverId = ownedSession.serverId
        if (client.checkHeartbeatSilence()) {
            SlockAppLog.d(
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
                SlockAppLog.d(
                    LOG_TAG,
                    "realtime_health_check_skip reason=already_connected trigger=$reason state=${state::class.simpleName} serverId=${serverId.value}"
                )
            }
            RealtimeConnectionState.Connecting ->
                SlockAppLog.d(
                    LOG_TAG,
                    "realtime_health_check_skip reason=connecting trigger=$reason serverId=${serverId.value}"
                )
        }
    }

    fun disconnectForSessionEnd(
        reason: String,
        serverId: ServerId? = null,
        shellInstanceId: String = "",
        explicitClose: Boolean = false
    ) {
        val cleanReason = reason.takeIf { it.isNotBlank() } ?: "shared_disconnect"
        if (!SlockRealtimeAppScopePolicy.isSessionEndDisconnectReason(cleanReason)) {
            SlockAppLog.w(
                LOG_TAG,
                "disconnect_ignored reason=$cleanReason"
            )
            return
        }
        if (multiServerRealtimeEnabled()) {
            val registry = multiSessionRegistry ?: return
            if (cleanReason in ProcessWideDisconnectReasons) {
                registry.disconnectAll(cleanReason)
                multiSessionRegistry = null
                backgroundKeepAliveEnabled = false
                return
            }
            val targetServerId = serverId ?: return
            if (cleanReason in ServerTerminalDisconnectReasons) {
                registry.disconnectServer(targetServerId, cleanReason)
            } else {
                registry.release(
                    serverId = targetServerId,
                    shellInstanceId = shellInstanceId,
                    mode =
                        if (explicitClose) {
                            AndroidRealtimeOwnerReleaseMode.ExplicitClose
                        } else {
                            AndroidRealtimeOwnerReleaseMode.ReplaceGrace
                        }
                )
            }
            backgroundKeepAliveEnabled = false
            return
        }
        realtimeConnectJob?.cancel()
        realtimeConnectJob = null
        val disconnected = realtimeRuntime?.disconnectForSessionEnd(cleanReason) ?: run {
            realtimeClient?.disconnect()
            true
        }
        if (!disconnected) return
        if (shouldDisposeRuntimeForSessionEnd(cleanReason)) {
            realtimeClient = null
            realtimeRuntime = null
        }
        backgroundKeepAliveEnabled = false
    }

    fun releaseRealtimeOwner(
        serverId: ServerId,
        shellInstanceId: String,
        explicitClose: Boolean
    ): Boolean {
        if (!multiServerRealtimeEnabled()) return false
        val registry = multiSessionRegistry ?: return false
        return registry.release(
            serverId = serverId,
            shellInstanceId = shellInstanceId,
            mode =
                if (explicitClose) {
                    AndroidRealtimeOwnerReleaseMode.ExplicitClose
                } else {
                    AndroidRealtimeOwnerReleaseMode.ReplaceGrace
                }
        )
    }

    private fun connectMultiSession(
        context: Context,
        serverId: ServerId,
        accessToken: String,
        shellInstanceId: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean
    ) {
        val registry = multiSessionRegistry(context)
        registry.releaseOwnerFromOtherServers(
            shellInstanceId = shellInstanceId,
            exceptServerId = serverId,
            mode = AndroidRealtimeOwnerReleaseMode.ReplaceGrace
        )
        val holder = registry.acquire(serverId, shellInstanceId)
        holder.connectJob?.cancel()
        holder.connectJob =
            scope.launch {
                try {
                    val result =
                        holder.runtime.connect(
                            serverId = serverId,
                            accessToken = accessToken,
                            requestedLastSeq = requestedLastSeq,
                            forceReconnect = forceReconnect
                        )
                    SlockAppLog.d(
                        LOG_TAG,
                        "multi_session_connect_result serverId=${serverId.value} shellInstanceId=$shellInstanceId " +
                            "result=${result::class.simpleName}"
                    )
                } finally {
                    holder.connectJob = null
                }
            }
    }

    private fun multiSessionRegistry(context: Context): AndroidRealtimeSessionRegistry<MultiSessionRuntime> {
        multiSessionRegistry?.let { return it }
        val appContext = context.applicationContext
        return AndroidRealtimeSessionRegistry(
            createRuntime = { serverId ->
                val hostContext =
                    AndroidAppScopeRealtimeHostContext.create(
                        context = appContext,
                        scope = scope,
                        pinnedServerId = serverId
                    )
                val runtime = bootstrapRealtime(hostContext)
                syncRuntimeVisibility(runtime, appVisibility)
                MultiSessionRuntime(serverId, hostContext.client, runtime)
            },
            disconnectRuntime = { serverId, holder, reason ->
                holder.connectJob?.cancel()
                holder.connectJob = null
                holder.runtime.disconnectForSessionEnd(GeneratedRealtimeContract.DisconnectReason.ServerRemoved)
                holder.runtime.dispose()
                SlockAppLog.d(
                    LOG_TAG,
                    "multi_session_disconnect serverId=${serverId.value} reason=$reason"
                )
            },
            releaseScheduler =
                AndroidRealtimeReleaseScheduler { delayMillis, block ->
                    val job = scope.launch {
                        delay(delayMillis)
                        block()
                    }
                    AndroidRealtimePendingRelease { job.cancel() }
                },
            replaceGraceMillis = RealtimeOwnerReplaceGraceMillis
        ).also { multiSessionRegistry = it }
    }

    private fun healthCheckMultiSession(holder: MultiSessionRuntime, reason: String) {
        val ownedSession = holder.runtime.ownedSession() ?: return
        if (holder.client.checkHeartbeatSilence()) return
        when (val state = holder.client.state) {
            RealtimeConnectionState.Disconnected,
            is RealtimeConnectionState.Failed -> {
                reconnectMultiSession(holder, "health_check_$reason")
            }
            RealtimeConnectionState.Connected,
            RealtimeConnectionState.RoomsJoined,
            RealtimeConnectionState.Connecting ->
                SlockAppLog.d(
                    LOG_TAG,
                    "multi_session_health_check_skip trigger=$reason state=${state::class.simpleName} " +
                        "serverId=${holder.serverId.value}"
                )
        }
    }

    private fun reconnectMultiSession(holder: MultiSessionRuntime, reason: String) {
        val ownedSession = holder.runtime.ownedSession() ?: return
        holder.connectJob?.cancel()
        holder.connectJob =
            scope.launch {
                try {
                    val result =
                        holder.runtime.connect(
                            serverId = ownedSession.serverId,
                            accessToken = ownedSession.accessToken,
                            requestedLastSeq = holder.runtime.lastSeq,
                            forceReconnect = true
                        )
                    SlockAppLog.d(
                        LOG_TAG,
                        "multi_session_reconnect_result reason=$reason serverId=${ownedSession.serverId.value} " +
                            "result=${result::class.simpleName}"
                    )
                } finally {
                    holder.connectJob = null
                }
            }
    }

    private fun joinedChannelCount(): Int =
        if (multiServerRealtimeEnabled()) {
            multiSessionRegistry?.runtimes()?.sumOf { it.runtime.joinedChannelCount } ?: 0
        } else {
            realtimeRuntime?.joinedChannelCount ?: 0
        }

    private fun multiServerRealtimeEnabled(): Boolean = BuildConfig.ENABLE_MULTI_SERVER_REALTIME_SESSIONS

    private fun bootstrapRealtime(context: Context): SlockRealtimeAppScopeRuntime {
        realtimeRuntime?.let { return it }
        val hostContext = AndroidAppScopeRealtimeHostContext.create(context.applicationContext, scope)
        val runtime = bootstrapRealtime(hostContext)
        syncRuntimeVisibility(runtime, appVisibility)
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
                    SlockAppLog.d(
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
        SlockAppLog.d(
            LOG_TAG,
            "app_scope_lifecycle event=host_network_status online=$online source=$source"
        )
        scope.launch {
            postAndroidAppScopeEvent(
                eventName = SlockHostNetworkNotifyContract.EventName,
                eventData =
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

    internal fun shouldForceReconnectAfterLongBackground(
        hiddenAtMillis: Long?,
        shownAtMillis: Long
    ): Boolean {
        val hiddenAt = hiddenAtMillis ?: return false
        if (shownAtMillis <= hiddenAt) return false
        return shownAtMillis - hiddenAt >= ForegroundReconnectAfterBackgroundMillis
    }

    internal fun syncRuntimeVisibility(
        runtime: SlockRealtimeAppScopeRuntime,
        visibility: AppVisibility
    ) {
        when (visibility) {
            AppVisibility.Foreground -> runtime.onAppShown()
            AppVisibility.Background -> runtime.onAppHidden()
        }
    }

    internal fun shouldDisposeRuntimeForSessionEnd(reason: String): Boolean =
        reason == GeneratedRealtimeContract.DisconnectReason.ApiEnvironmentChanged

    internal enum class AppVisibility {
        Foreground,
        Background
    }

    private data class MultiSessionRuntime(
        val serverId: ServerId,
        val client: SocketIoRealtimeClient,
        val runtime: SlockRealtimeAppScopeRuntime,
        var connectJob: Job? = null
    )

    private val ProcessWideDisconnectReasons =
        setOf(
            GeneratedRealtimeContract.DisconnectReason.AuthInvalid,
            GeneratedRealtimeContract.DisconnectReason.Logout,
            GeneratedRealtimeContract.DisconnectReason.ProcessTeardown,
            GeneratedRealtimeContract.DisconnectReason.ApiEnvironmentChanged
        )

    private val ServerTerminalDisconnectReasons =
        setOf(
            GeneratedRealtimeContract.DisconnectReason.ServerDeleted,
            GeneratedRealtimeContract.DisconnectReason.ServerMembershipRemoved,
            GeneratedRealtimeContract.DisconnectReason.ServerRemoved
        )

    private fun Context.sharedRealtimeAuthTokenProvider(
        sessionStore: KeyValueAuthSessionStore
    ): SharedAuthTokenProvider {
        val apiBaseUrl = AndroidAuthApiFactory.currentBaseUrl(this)
        return SharedAuthTokenProvider(
            sessionStore = sessionStore,
            authApi =
                SlockAuthApiClient(
                    SharedSlockAuthTransport(
                        baseUrl = apiBaseUrl,
                        warningLogger = { message -> SlockAppLog.w(LOG_TAG, message) }
                    )
                ),
            clockMillis = { System.currentTimeMillis() },
            logger = { message -> SlockAppLog.auth(LOG_TAG, message) },
            warningLogger = { message -> SlockAppLog.w(LOG_TAG, message) }
        )
    }
}

internal enum class AndroidHostVisibility(val wireValue: String) {
    Visible("resume"),
    Hidden("pause")
}

internal fun applyAndroidHostVisibilityTransition(
    visibility: AndroidHostVisibility,
    publishVisibility: () -> Unit,
    applyRuntimeVisibility: () -> Unit
) {
    when (visibility) {
        AndroidHostVisibility.Visible -> {
            applyRuntimeVisibility()
            publishVisibility()
        }
        AndroidHostVisibility.Hidden -> {
            publishVisibility()
            applyRuntimeVisibility()
        }
    }
}
