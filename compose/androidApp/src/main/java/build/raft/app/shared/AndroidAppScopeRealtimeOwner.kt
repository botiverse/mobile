package build.raft.app.shared

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.core.data.network.AndroidNetworkPrewarmController
import build.raft.app.core.data.realtime.SocketIoRealtimeClient
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.SharedAuthTokenProvider
import build.raft.app.data.SharedRaftAuthTransport
import build.raft.app.data.RaftAndroidPushRegistrationBridge
import build.raft.app.data.RaftAuthApiClient
import build.raft.app.data.RaftHostNetworkNotifyContract
import build.raft.app.data.updateRaftHostVisibility
import build.raft.app.data.postAndroidAppScopeEvent
import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.data.realtime.RealtimeConnectionState
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.data.realtime.RealtimeConnectionWitnessToken
import build.raft.app.data.realtime.RealtimeRuntimeCursorIdentity
import build.raft.app.data.realtime.RaftRealtimeAppScopePolicy
import build.raft.app.data.realtime.RaftRealtimeAppScopeRuntime
import build.raft.app.data.realtime.bootstrapRealtime
import build.raft.app.data.realtime.realtimeRuntimeCursorAdvanceDecision
import build.raft.app.model.ConversationKind
import build.raft.app.model.Message
import build.raft.app.model.ServerId
import build.raft.app.network.updateRaftProcessNetworkVisibility
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
    private const val ProcessRealtimeOwnerId = "process-realtime-host-owner"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var realtimeClient: SocketIoRealtimeClient? = null
    private var realtimeRuntime: RaftRealtimeAppScopeRuntime? = null
    private var realtimeConnectionWitness: RealtimeConnectionWitnessToken? = null
    private var realtimeConnectJob: Job? = null
    private var multiSessionRegistry: AndroidRealtimeSessionRegistry<MultiSessionRuntime>? = null
    private var multiSessionIdentity: MultiSessionIdentity? = null
    private var currentDesiredServerId: ServerId? = null
    private var appVisibility: AppVisibility = AppVisibility.Background
    private var installedApplicationContext: Context? = null
    private var lastHiddenAtMillis: Long? = null
    private var backgroundKeepAliveEnabled = false
    private var networkCallbackInstalled = false
    private var processLifecycleInstalled = false
    private var hostNetworkOnline: Boolean? = null
    private val processVisibilityTracker =
        AndroidProcessVisibilityTracker { foreground, foregroundEpoch ->
            val retired = updateRaftProcessNetworkVisibility(foreground)
            RaftAppLog.d(
                LOG_TAG,
                "app_scope_network_lifecycle foreground=$foreground epoch=$foregroundEpoch " +
                    "retiredRequests=$retired"
            )
            if (foreground) {
                installedApplicationContext?.let { context ->
                    onAppShown(context, source = "process_activity_started")
                }
                // Keeps the FCM delivery-status answer fresh for the background tray decision.
                RaftAndroidPushRegistrationBridge.refreshDeliveryStatus(reason = "app_foreground")
            } else {
                onAppHidden(source = "process_activity_stopped")
            }
        }

    fun onHostVisibilityChanged(
        visibility: AndroidHostVisibility,
        shellInstanceId: String = "",
        pageName: String = RaftCmpActivity.PAGE_APP_SHELL,
        source: String = "unknown"
    ) {
        val lifecycleForegroundEpoch = processVisibilityTracker.currentForegroundEpoch()
        publishHostVisibility(
            visibility = visibility,
            shellInstanceId = shellInstanceId,
            pageName = pageName,
            lifecycleForegroundEpoch = lifecycleForegroundEpoch
        )
        RaftAppLog.d(
            LOG_TAG,
            "app_scope_host_visibility event=${visibility.wireValue} source=$source " +
                "page=${normalizedAndroidLifecyclePageName(pageName)} epoch=$lifecycleForegroundEpoch"
        )
    }

    fun install(context: Context) {
        val appContext = context.applicationContext
        installedApplicationContext = appContext
        installProcessLifecycle(appContext)
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

                        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                            onNetworkDnsConfigurationMaybeChanged(appContext, linkProperties)
                        }

                        override fun onLost(network: Network) {
                            onNetworkLost(appContext)
                        }
                    }
                )
                networkCallbackInstalled = true
                publishCurrentNetworkStatus(appContext, source = "network_callback_initial")
                RaftAppLog.d(LOG_TAG, "app_scope_lifecycle event=network_callback_installed")
            }.onFailure { error ->
                RaftAppLog.w(
                    LOG_TAG,
                    "app_scope_lifecycle event=network_callback_install_failed message=${error.message.orEmpty()}"
                )
            }
        }
    }

    private fun installProcessLifecycle(context: Context) {
        val application = context as? Application ?: return
        synchronized(this) {
            if (processLifecycleInstalled) return
            application.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                    override fun onActivityStarted(activity: Activity) {
                        processVisibilityTracker.onActivityStarted()
                    }

                    override fun onActivityResumed(activity: Activity) = Unit

                    override fun onActivityPaused(activity: Activity) = Unit

                    override fun onActivityStopped(activity: Activity) {
                        processVisibilityTracker.onActivityStopped(activity.isChangingConfigurations)
                    }

                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                    override fun onActivityDestroyed(activity: Activity) = Unit
                }
            )
            processLifecycleInstalled = true
            RaftAppLog.d(LOG_TAG, "app_scope_lifecycle event=process_lifecycle_installed")
        }
    }

    private fun onAppShown(
        context: Context,
        source: String = "unknown"
    ) {
        val shownAtMillis = System.currentTimeMillis()
        val multiSessionEnabled = multiServerRealtimeEnabled()
        val hiddenAtMillis = lastHiddenAtMillis
        lastHiddenAtMillis = null
        appVisibility = AppVisibility.Foreground
        syncRealtimeReconnectPolicy()
        if (multiSessionEnabled) {
            currentMultiSessionRuntimes("app_shown").forEach { it.runtime.onAppShown() }
        } else {
            realtimeRuntime?.onAppShown()
        }
        RaftAppLog.d(
            LOG_TAG,
            "app_scope_lifecycle event=shown source=$source joined_rooms=${joinedChannelCount()}"
        )
        AndroidNetworkPrewarmController.prewarmForegroundConnection(
            context = context.applicationContext,
            source = source
        )
        if (shouldForceReconnectAfterLongBackground(hiddenAtMillis, shownAtMillis)) {
            if (multiSessionEnabled) {
                currentMultiSessionRuntimes("long_background").forEach { holder ->
                    reconnectMultiSession(holder, "long_background")
                }
                return
            }
            val runtime = realtimeRuntime
            val ownedSession = runtime?.ownedSession()
            if (runtime != null && ownedSession != null) {
                RaftAppLog.d(
                    LOG_TAG,
                    "foreground_reconnect_request reason=long_background source=$source " +
                        "backgroundMs=${shownAtMillis - (hiddenAtMillis ?: shownAtMillis)} " +
                        "serverId=${ownedSession.serverId.value}"
                )
                val witness = realtimeConnectionWitness ?: return
                reconnectOwnedSession(
                    context = context.applicationContext,
                    serverId = ownedSession.serverId,
                    accessToken = ownedSession.accessToken,
                    state = RealtimeConnectionState.Disconnected,
                    witness = witness,
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
        source: String = "unknown"
    ) {
        val multiSessionEnabled = multiServerRealtimeEnabled()
        appVisibility = AppVisibility.Background
        lastHiddenAtMillis = System.currentTimeMillis()
        syncRealtimeReconnectPolicy()
        if (multiSessionEnabled) {
            currentMultiSessionRuntimes("app_hidden").forEach { it.runtime.onAppHidden() }
        } else {
            realtimeRuntime?.onAppHidden()
        }
        RaftAppLog.d(
            LOG_TAG,
            "app_scope_lifecycle event=hidden source=$source joined_rooms=${joinedChannelCount()}"
        )
        AndroidNetworkPrewarmController.cancel(reason = source)
    }

    private fun publishHostVisibility(
        visibility: AndroidHostVisibility,
        shellInstanceId: String,
        pageName: String,
        lifecycleForegroundEpoch: Long
    ) {
        updateRaftHostVisibility(
            shellInstanceId = shellInstanceId,
            visible = visibility == AndroidHostVisibility.Visible
        )
        postAndroidAppScopeEvent(
            eventName = RaftRealtimeNotifyContract.EventName,
            eventData =
                JSONObject()
                    .put(RaftRealtimeNotifyContract.KeyAction, RaftRealtimeNotifyContract.ActionAppLifecycle)
                    .put(RaftRealtimeNotifyContract.KeyLifecycleEvent, visibility.wireValue)
                    .put(RaftRealtimeNotifyContract.KeyForegroundEpoch, lifecycleForegroundEpoch)
                    .put(
                        RaftRealtimeNotifyContract.KeyPageName,
                        normalizedAndroidLifecyclePageName(pageName)
                    )
                    .put(RaftRealtimeNotifyContract.KeyShellInstanceId, shellInstanceId)
        )
    }

    fun enableBackgroundKeepAlive(
        context: Context,
        source: String = "unknown"
    ) {
        backgroundKeepAliveEnabled = true
        syncRealtimeReconnectPolicy()
        RaftAppLog.d(
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
        syncRealtimeReconnectPolicy()
        RaftAppLog.d(
            LOG_TAG,
            "background_keep_alive_disabled reason=$reason visibility=$appVisibility"
        )
    }

    fun connectFromSharedRequest(
        context: Context,
        serverId: ServerId,
        accessToken: String,
        shellInstanceId: String,
        requestedLastSeq: Long,
        forceReconnect: Boolean,
        witness: RealtimeConnectionWitnessToken,
    ) {
        if (serverId.value.isBlank() || accessToken.isBlank()) {
            RaftAppLog.w(
                LOG_TAG,
                "connect_skip reason=blank_request serverId=${serverId.value} has_access_token=${accessToken.isNotBlank()}"
            )
            return
        }
        realtimeConnectionWitness = witness
        RaftAppLog.w(
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
                forceReconnect = forceReconnect,
                witness = witness,
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
                            forceReconnect = forceReconnect,
                            witness = witness,
                        )
                } finally {
                    realtimeConnectJob = null
                }
            }
    }

    fun reconcileFromSharedRequest(
        context: Context,
        serverIds: Set<String>,
        accessToken: String,
        environmentId: String,
        socketUrl: String,
        forceReconnect: Boolean,
        disconnectReason: String,
        witness: RealtimeConnectionWitnessToken,
    ) {
        val selection = selectAndroidCurrentRealtimeServer(serverIds)
        if (selection.rejectedMultipleServers) {
            clearMultiSessionState("multiple_current_servers")
            RaftAppLog.w(
                LOG_TAG,
                "process_reconcile result=rejected reason=multiple_current_servers " +
                    "serverCount=${selection.normalizedServerCount}"
            )
            return
        }
        val desiredServerId = selection.serverId
        if (desiredServerId == null) {
            multiSessionRegistry?.disconnectAll(disconnectReason)
            multiSessionRegistry = null
            multiSessionIdentity = null
            currentDesiredServerId = null
            realtimeConnectionWitness = null
            backgroundKeepAliveEnabled = false
            RaftAppLog.d(LOG_TAG, "process_reconcile result=cleared reason=$disconnectReason")
            return
        }
        if (
            accessToken.isBlank() ||
            environmentId.isBlank() ||
            witness.scopeLeaseId.isBlank() ||
            witness.principalId.isBlank() ||
            witness.authGeneration <= 0L ||
            witness.connectionFamilyId.isBlank()
        ) {
            RaftAppLog.w(LOG_TAG, "process_reconcile_skip reason=invalid_authority serverCount=1")
            multiSessionRegistry?.disconnectAll("invalid_process_authority")
            multiSessionRegistry = null
            multiSessionIdentity = null
            currentDesiredServerId = null
            realtimeConnectionWitness = null
            return
        }
        val nextIdentity =
            MultiSessionIdentity(
                socketUrl = socketUrl.trim(),
                environmentId = environmentId.trim().trimEnd('/'),
                principalId = witness.principalId,
                authGeneration = witness.authGeneration,
                connectionFamilyId = witness.connectionFamilyId,
                accessToken = accessToken,
            )
        val previousIdentity = multiSessionIdentity
        val identityChanged = previousIdentity != null && previousIdentity != nextIdentity
        // task #333: identity = the env/account binding (socketUrl, environmentId,
        // principalId, connectionFamilyId). A silent token rotation bumps authGeneration
        // and accessToken on the SAME binding — a credential refresh, not an identity
        // replacement. Only a real binding change may disconnect-all with
        // process_identity_replaced; a credential rotation just re-auths the socket.
        val bindingReplaced =
            previousIdentity != null && !previousIdentity.sameBindingAs(nextIdentity)
        if (bindingReplaced) {
            multiSessionRegistry?.disconnectAll("process_identity_replaced")
            multiSessionRegistry = null
        }
        multiSessionIdentity = nextIdentity
        realtimeConnectionWitness = witness
        val registry = multiSessionRegistry(context.applicationContext)
        connectMultiSession(
            context = context.applicationContext,
            serverId = desiredServerId,
            accessToken = accessToken,
            shellInstanceId = ProcessRealtimeOwnerId,
            requestedLastSeq = registry.runtime(desiredServerId)?.runtime?.lastSeq ?: 0L,
            forceReconnect = forceReconnect || identityChanged,
            witness = witness,
        )
        RaftAppLog.d(
            LOG_TAG,
            "process_reconcile result=applied serverCount=1 serverId=${desiredServerId.value} " +
                "identityChanged=$identityChanged bindingReplaced=$bindingReplaced " +
                "forceReconnect=$forceReconnect"
        )
    }

    fun advanceResumeCursorFromSharedRequest(
        serverId: ServerId,
        appliedSeq: Long,
        witness: RealtimeConnectionWitnessToken,
    ): Boolean {
        if (appliedSeq <= 0L) return false
        val holder = multiSessionRegistry?.runtime(serverId) ?: return false
        val currentWitness = holder.witness ?: return false
        val decision =
            realtimeRuntimeCursorAdvanceDecision(
                currentIdentity =
                    RealtimeRuntimeCursorIdentity(
                        serverId = serverId.value,
                        scopeLeaseId = currentWitness.scopeLeaseId,
                        principalId = currentWitness.principalId,
                        authGeneration = currentWitness.authGeneration,
                        connectionFamilyId = currentWitness.connectionFamilyId,
                    ),
                requestIdentity =
                    RealtimeRuntimeCursorIdentity(
                        serverId = serverId.value,
                        scopeLeaseId = witness.scopeLeaseId,
                        principalId = witness.principalId,
                        authGeneration = witness.authGeneration,
                        connectionFamilyId = witness.connectionFamilyId,
                    ),
                currentSeq = holder.runtime.lastSeq,
                appliedSeq = appliedSeq,
            )
        if (!decision.shouldAdvance) {
            RaftAppLog.w(
                LOG_TAG,
                "cursor_advance_skip reason=${decision.reason} serverId=${serverId.value} appliedSeq=$appliedSeq"
            )
            return false
        }
        val ownedSession = holder.runtime.ownedSession() ?: return false
        if (ownedSession.serverId != serverId) return false
        val nextSeq = holder.runtime.advanceAppliedSeqForHost(decision.resumeSeq)
        RaftAppLog.d(
            LOG_TAG,
            "cursor_advance result=applied serverId=${serverId.value} requestedSeq=$appliedSeq resumeSeq=$nextSeq"
        )
        return true
    }

    fun onNetworkAvailable(context: Context) {
        RaftAppLog.d(
            LOG_TAG,
            "app_scope_lifecycle event=network_available visibility=$appVisibility keep_alive=$backgroundKeepAliveEnabled"
        )
        val appContext = context.applicationContext
        // A new default network (Wi-Fi <-> cellular, a VPN switched on or off) is announced even
        // when the app was already online: its DNS may resolve the API host again (task #150).
        emitHostNetworkStatus(
            context = appContext,
            online = appContext.isHostNetworkOnline(),
            source = "network_available",
            force = true
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

    @Volatile
    private var lastDnsConfiguration: String? = null

    /**
     * Task #150: a DNS change on the same network (Private DNS switched, new DNS servers) can make
     * the API host resolvable again without any onAvailable. Announce it like a new network so the
     * shared reachability gate stops short-circuiting at once. Only DNS-relevant properties count,
     * so routine address/route updates do not re-announce.
     */
    fun onNetworkDnsConfigurationMaybeChanged(context: Context, linkProperties: LinkProperties) {
        val dnsConfiguration = linkProperties.dnsConfigurationSignature()
        val previous = lastDnsConfiguration
        lastDnsConfiguration = dnsConfiguration
        if (previous == null || previous == dnsConfiguration) return
        RaftAppLog.d(LOG_TAG, "app_scope_lifecycle event=network_dns_configuration_changed")
        val appContext = context.applicationContext
        emitHostNetworkStatus(
            context = appContext,
            online = appContext.isHostNetworkOnline(),
            source = "dns_configuration_changed",
            force = true
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
        RaftAppLog.d(
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
            RaftAppLog.d(
                LOG_TAG,
                "realtime_health_check_skip reason=app_background trigger=$reason"
            )
            return
        }
        if (multiServerRealtimeEnabled()) {
            val runtimes = currentMultiSessionRuntimes("health_check_$reason")
            if (runtimes.isEmpty()) {
                RaftAppLog.d(LOG_TAG, "realtime_health_check_skip reason=no_multi_session trigger=$reason")
                return
            }
            runtimes.forEach { holder -> healthCheckMultiSession(holder, reason) }
            return
        }
        val client = realtimeClient
        val runtime = realtimeRuntime
        val ownedSession = runtime?.ownedSession()
        if (client == null || ownedSession == null) {
            RaftAppLog.d(
                LOG_TAG,
                "realtime_health_check_missing_owner_session trigger=$reason"
            )
            return
        }
        val serverId = ownedSession.serverId
        if (client.checkHeartbeatSilence()) {
            RaftAppLog.d(
                LOG_TAG,
                "realtime_health_check_reconnect reason=heartbeat_silent trigger=$reason serverId=${serverId.value}"
            )
            return
        }
        when (val state = client.state) {
            RealtimeConnectionState.Disconnected,
            is RealtimeConnectionState.Failed -> {
                val witness = realtimeConnectionWitness ?: return
                reconnectOwnedSession(context, serverId, ownedSession.accessToken, state, witness)
            }
            RealtimeConnectionState.Connected,
            RealtimeConnectionState.RoomsJoined -> {
                RaftAppLog.d(
                    LOG_TAG,
                    "realtime_health_check_skip reason=already_connected trigger=$reason state=${state::class.simpleName} serverId=${serverId.value}"
                )
            }
            RealtimeConnectionState.Connecting ->
                RaftAppLog.d(
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
        if (!RaftRealtimeAppScopePolicy.isSessionEndDisconnectReason(cleanReason)) {
            RaftAppLog.w(
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
                multiSessionIdentity = null
                currentDesiredServerId = null
                backgroundKeepAliveEnabled = false
                realtimeConnectionWitness = null
                return
            }
            val targetServerId = serverId ?: return
            if (cleanReason in ServerTerminalDisconnectReasons) {
                registry.disconnectServer(targetServerId, cleanReason)
                if (currentDesiredServerId == targetServerId) currentDesiredServerId = null
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
            syncRealtimeReconnectPolicy()
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
        realtimeConnectionWitness = null
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
        forceReconnect: Boolean,
        witness: RealtimeConnectionWitnessToken,
    ) {
        val registry = multiSessionRegistry(context)
        currentDesiredServerId = serverId
        val holder =
            registry.acquireCurrent(
                serverId = serverId,
                shellInstanceId = shellInstanceId,
                disconnectReason = GeneratedRealtimeContract.DisconnectReason.ServerRemoved
            )
        val requestedIdentity =
            MultiSessionConnectIdentity(
                accessToken = accessToken,
                scopeLeaseId = witness.scopeLeaseId,
                principalId = witness.principalId,
                authGeneration = witness.authGeneration,
                connectionFamilyId = witness.connectionFamilyId,
            )
        if (!forceReconnect && holder.requestedIdentity == requestedIdentity && holder.connectJob?.isActive == true) {
            RaftAppLog.d(LOG_TAG, "multi_session_connect_skip reason=duplicate_in_flight serverId=${serverId.value}")
            return
        }
        holder.requestedIdentity = requestedIdentity
        holder.witness = witness
        holder.connectJob?.cancel()
        holder.connectJob =
            scope.launch {
                try {
                    val result =
                        holder.runtime.connect(
                            serverId = serverId,
                            accessToken = accessToken,
                            requestedLastSeq = requestedLastSeq,
                            forceReconnect = forceReconnect,
                            witness = witness,
                        )
                    RaftAppLog.d(
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
                hostContext.client.setReconnectAllowed(realtimeReconnectAllowed())
                val runtime = bootstrapRealtime(hostContext)
                syncRuntimeVisibility(runtime, appVisibility)
                MultiSessionRuntime(serverId, hostContext.client, runtime)
            },
            disconnectRuntime = { serverId, holder, reason ->
                holder.connectJob?.cancel()
                holder.connectJob = null
                holder.runtime.disconnectForSessionEnd(GeneratedRealtimeContract.DisconnectReason.ServerRemoved)
                holder.runtime.dispose()
                RaftAppLog.d(
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
                RaftAppLog.d(
                    LOG_TAG,
                    "multi_session_health_check_skip trigger=$reason state=${state::class.simpleName} " +
                        "serverId=${holder.serverId.value}"
                )
        }
    }

    private fun currentMultiSessionRuntimes(reason: String): List<MultiSessionRuntime> {
        val registry = multiSessionRegistry ?: return emptyList()
        val targetServerId = currentDesiredServerId
        if (targetServerId == null) {
            registry.disconnectAll("missing_current_server_$reason")
            multiSessionRegistry = null
            multiSessionIdentity = null
            realtimeConnectionWitness = null
            backgroundKeepAliveEnabled = false
            return emptyList()
        }
        registry.snapshots()
            .map(AndroidRealtimeSessionRegistrySnapshot::serverId)
            .filterNot { it == targetServerId }
            .forEach { staleServerId ->
                registry.disconnectServer(staleServerId, "non_current_server_$reason")
            }
        return listOfNotNull(registry.runtime(targetServerId))
    }

    private fun clearMultiSessionState(reason: String) {
        multiSessionRegistry?.disconnectAll(reason)
        multiSessionRegistry = null
        multiSessionIdentity = null
        currentDesiredServerId = null
        realtimeConnectionWitness = null
        backgroundKeepAliveEnabled = false
    }

    private fun reconnectMultiSession(holder: MultiSessionRuntime, reason: String) {
        val ownedSession = holder.runtime.ownedSession() ?: return
        val witness = holder.witness ?: return
        holder.connectJob?.cancel()
        holder.connectJob =
            scope.launch {
                try {
                    val result =
                        holder.runtime.connect(
                            serverId = ownedSession.serverId,
                            accessToken = ownedSession.accessToken,
                            requestedLastSeq = holder.runtime.lastSeq,
                            forceReconnect = true,
                            witness = witness,
                        )
                    RaftAppLog.d(
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
            currentMultiSessionRuntimes("joined_channel_count").sumOf { it.runtime.joinedChannelCount }
        } else {
            realtimeRuntime?.joinedChannelCount ?: 0
        }

    private fun multiServerRealtimeEnabled(): Boolean = true

    private fun bootstrapRealtime(context: Context): RaftRealtimeAppScopeRuntime {
        realtimeRuntime?.let { return it }
        val hostContext = AndroidAppScopeRealtimeHostContext.create(context.applicationContext, scope)
        hostContext.client.setReconnectAllowed(realtimeReconnectAllowed())
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
        state: RealtimeConnectionState,
        witness: RealtimeConnectionWitnessToken,
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
                            forceReconnect = true,
                            witness = witness,
                        )
                    RaftAppLog.d(
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
        RaftAppLog.d(
            LOG_TAG,
            "app_scope_lifecycle event=host_network_status online=$online source=$source"
        )
        scope.launch {
            postAndroidAppScopeEvent(
                eventName = RaftHostNetworkNotifyContract.EventName,
                eventData =
                    JSONObject()
                        .put(
                            RaftHostNetworkNotifyContract.KeyStatus,
                            if (online) {
                                RaftHostNetworkNotifyContract.StatusOnline
                            } else {
                                RaftHostNetworkNotifyContract.StatusOffline
                            }
                        )
                        .put(RaftHostNetworkNotifyContract.KeyOnline, online)
                        .put(RaftHostNetworkNotifyContract.KeySource, source)
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
        isAppInForeground: Boolean,
        remotePushActive: Boolean = false
    ): Boolean =
        AndroidAppScopeRealtimeHostContext.shouldPostBackgroundUnreadNotification(
            hasAttachedConsumers = hasAttachedConsumers,
            preferenceEnabled = preferenceEnabled,
            isAppInForeground = isAppInForeground,
            remotePushActive = remotePushActive
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
        runtime: RaftRealtimeAppScopeRuntime,
        visibility: AppVisibility
    ) {
        when (visibility) {
            AppVisibility.Foreground -> runtime.onAppShown()
            AppVisibility.Background -> runtime.onAppHidden()
        }
    }

    private fun realtimeReconnectAllowed(): Boolean =
        realtimeReconnectAllowed(appVisibility, backgroundKeepAliveEnabled)

    internal fun realtimeReconnectAllowed(
        visibility: AppVisibility,
        backgroundKeepAliveEnabled: Boolean,
    ): Boolean = visibility == AppVisibility.Foreground || backgroundKeepAliveEnabled

    private fun syncRealtimeReconnectPolicy() {
        applyRealtimeReconnectPolicy(
            clients =
                buildList {
                    realtimeClient?.let(::add)
                    currentMultiSessionRuntimes("reconnect_policy").forEach { add(it.client) }
                },
            visibility = appVisibility,
            backgroundKeepAliveEnabled = backgroundKeepAliveEnabled,
        )
    }

    internal fun applyRealtimeReconnectPolicy(
        clients: Iterable<SocketIoRealtimeClient>,
        visibility: AppVisibility,
        backgroundKeepAliveEnabled: Boolean,
    ) {
        val allowed = realtimeReconnectAllowed(visibility, backgroundKeepAliveEnabled)
        clients.forEach { client -> client.setReconnectAllowed(allowed) }
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
        val runtime: RaftRealtimeAppScopeRuntime,
        var connectJob: Job? = null,
        var witness: RealtimeConnectionWitnessToken? = null,
        var requestedIdentity: MultiSessionConnectIdentity? = null,
    )

    private data class MultiSessionConnectIdentity(
        val accessToken: String,
        val scopeLeaseId: String,
        val principalId: String,
        val authGeneration: Long,
        val connectionFamilyId: String,
    )

    private data class MultiSessionIdentity(
        val environmentId: String,
        val socketUrl: String,
        val principalId: String,
        val authGeneration: Long,
        val connectionFamilyId: String,
        val accessToken: String,
    ) {
        // task #333: binding = the durable env/account socket identity. authGeneration
        // and accessToken are credential state that rotate on every silent token
        // refresh on the SAME binding — they must not be treated as an identity swap.
        fun sameBindingAs(other: MultiSessionIdentity): Boolean =
            environmentId == other.environmentId &&
                socketUrl == other.socketUrl &&
                principalId == other.principalId &&
                connectionFamilyId == other.connectionFamilyId
    }

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
                RaftAuthApiClient(
                    SharedRaftAuthTransport(
                        baseUrl = apiBaseUrl,
                        warningLogger = { message -> RaftAppLog.w(LOG_TAG, message) }
                    )
                ),
            clockMillis = { System.currentTimeMillis() },
            logger = { message -> RaftAppLog.auth(LOG_TAG, message) },
            warningLogger = { message -> RaftAppLog.w(LOG_TAG, message) }
        )
    }
}

internal enum class AndroidHostVisibility(val wireValue: String) {
    Visible("resume"),
    Hidden("pause")
}

internal fun normalizedAndroidLifecyclePageName(pageName: String): String =
    pageName.trim().ifBlank { RaftCmpActivity.PAGE_APP_SHELL }

/** Started-activity accounting is process visibility, not a page/pager lifecycle signal. */
internal class AndroidProcessVisibilityTracker(
    private val onVisibilityChanged: (foreground: Boolean, foregroundEpoch: Long) -> Unit
) {
    private var startedActivityCount = 0
    private var foreground = false
    private var foregroundEpoch = 0L

    @Synchronized
    fun onActivityStarted() {
        startedActivityCount += 1
        if (!foreground) {
            foreground = true
            foregroundEpoch += 1L
            onVisibilityChanged(true, foregroundEpoch)
        }
    }

    @Synchronized
    fun onActivityStopped(changingConfigurations: Boolean) {
        if (startedActivityCount > 0) startedActivityCount -= 1
        if (startedActivityCount == 0 && foreground && !changingConfigurations) {
            foreground = false
            onVisibilityChanged(false, foregroundEpoch)
        }
    }

    @Synchronized
    fun currentForegroundEpoch(): Long = foregroundEpoch
}

/** DNS servers plus the Private DNS mode and host: what decides whether a name resolves. */
private fun LinkProperties.dnsConfigurationSignature(): String =
    "servers=${dnsServers.joinToString(",") { it.hostAddress.orEmpty() }};" +
        "private=$isPrivateDnsActive:${privateDnsServerName.orEmpty()}"
