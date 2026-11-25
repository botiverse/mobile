package build.raft.app.push

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftAndroidPushRegistrationBridge
import build.raft.app.data.RaftBackgroundConnectionControlsRegistry
import build.raft.app.data.RaftFcmProbeHost
import build.raft.app.data.RaftFcmProbeHostListener
import build.raft.app.data.RaftFcmProbeUiStatus
import build.raft.app.data.RaftPushProbeResult
import build.raft.app.shared.AndroidRealtimeKeepAliveController
import build.raft.app.shared.AndroidRealtimeSessionAvailability
import build.raft.app.shared.RaftAndroidBackgroundConnectionControls
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-scoped runtime owner for Android's silent FCM reachability probe (`mobile_fcm_probe_v0`,
 * `#proj-mobile:#379`).
 *
 * Bridges [AndroidFcmReachabilityStateMachine] to:
 * - [RaftAndroidPushRegistrationBridge] (`POST /api/push/registrations/:installationId/probe`)
 * - [RaftFirebaseMessagingService] (`type == "probe"` and real FCM delivery signals)
 * - [build.raft.app.shared.AndroidAppScopeRealtimeOwner] (foreground/background & active network)
 * - [AndroidRealtimeKeepAliveController] (direct-APK automatic foreground-service fallback/recovery;
 *   NEVER starts the service on Google Play builds!)
 * - [RaftAndroidBackgroundConnectionControls] (Settings › Notifications status & Play notice)
 */
internal object AndroidFcmReachabilityController : RaftFcmProbeHostListener {
    private const val LOG_TAG = "SlockFcmProbe"

    private val jobLock = Any()
    private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var isForeground: Boolean = false

    @Volatile
    private var lastNetworkSnapshot: AndroidNetworkSnapshot? = null

    @Volatile
    private var dismissedPlayNoticeEpisodeKey: String? = null

    private var missTimeoutJob: Job? = null
    private var retryJob: Job? = null
    private var dwellJob: Job? = null
    private var activeProbeJob: Job? = null

    @Volatile
    internal var clockMillis: () -> Long = { System.currentTimeMillis() }

    @Volatile
    internal var probeIdGenerator: () -> String = { UUID.randomUUID().toString() }

    @Volatile
    internal var scopeProvider: () -> CoroutineScope = { defaultScope }

    @Volatile
    internal var dispatcherForTests: CoroutineDispatcher? = null

    @Volatile
    internal var featureFlagEnabledProvider: (Context) -> Boolean = ::readServerFeatureFlagEnabled

    @Volatile
    internal var signedInProvider: (Context) -> Boolean = AndroidRealtimeSessionAvailability::hasStoredRealtimeSession

    @Volatile
    internal var fcmUnavailableProvider: (Context) -> Boolean = RaftPlayServicesAvailability::isFcmUnavailable

    @Volatile
    internal var hasRegistrationProvider: (Context) -> Boolean = {
        RaftAndroidPushRegistrationBridge.hasSettledRegistrationSnapshot()
    }

    @Volatile
    internal var directInstallAllowedProvider: (Context) -> Boolean =
        AndroidRealtimeKeepAliveController::directGmsKeepAliveOverrideAllowed

    @Volatile
    internal var serverKeyProvider: (Context) -> String? = ::readCurrentServerKey

    @Volatile
    internal var networkSnapshotProvider: (Context) -> AndroidNetworkSnapshot = ::queryActiveAndroidNetworkSnapshot

    @Volatile
    internal var probeExecutor: suspend (probeId: String) -> RaftPushProbeResult = { probeId ->
        RaftAndroidPushRegistrationBridge.sendReachabilityProbe(probeId)
    }

    @Volatile
    internal var reRegisterAction: (Context, String) -> Unit = { context, reason ->
        AndroidPushRegistration.requestReRegister(context, reason)
    }

    @Volatile
    internal var autoFallbackAppliedHandler: (Context, Boolean, String) -> Unit = ::applyAutoFallbackTransition

    @Volatile
    internal var uiRefreshHandler: () -> Unit = {
        (RaftBackgroundConnectionControlsRegistry.controls as? RaftAndroidBackgroundConnectionControls)
            ?.refreshFcmReachabilityStatus()
    }

    @Volatile
    internal var stateMachine: AndroidFcmReachabilityStateMachine = createStateMachine()

    private fun createStateMachine(): AndroidFcmReachabilityStateMachine =
        AndroidFcmReachabilityStateMachine(
            clockMillis = { clockMillis() },
            probeIdGenerator = { probeIdGenerator() },
        )

    fun install(context: Context) {
        appContextRef = context.applicationContext
        RaftFcmProbeHost.listener = this
    }

    /**
     * True only when `mobile_fcm_probe_v0` is enabled for the active server AND that server has
     * not answered HTTP 404 (unsupported) within the current TTL.
     */
    fun isFeatureEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!runCatching { featureFlagEnabledProvider(appContext) }.getOrDefault(false)) return false
        return !stateMachine.isServerUnsupported()
    }

    fun currentReachabilityStatus(): FcmReachabilityStatus =
        stateMachine.snapshot().state.status

    /**
     * Defense-in-depth: true ONLY on direct-install APKs declaring [build.raft.app.shared.RaftBackgroundConnectionService]
     * with GMS available, `mobile_fcm_probe_v0` enabled (and not 404-unsupported), and auto-fallback
     * active in [stateMachine]. ALWAYS false on Google Play builds and no-GMS devices.
     */
    fun isAutoFallbackActive(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!runCatching { directInstallAllowedProvider(appContext) }.getOrDefault(false)) return false
        if (runCatching { fcmUnavailableProvider(appContext) }.getOrDefault(true)) return false
        if (!isFeatureEnabled(appContext)) return false
        return stateMachine.snapshot().autoFallbackActive
    }

    /**
     * Computes the Settings › Notifications UI status for the current build and reachability state.
     * Always [RaftFcmProbeUiStatus.None] when the flag is off, server returned 404, or device is no-GMS.
     */
    fun currentUiStatus(context: Context): RaftFcmProbeUiStatus {
        val appContext = context.applicationContext
        if (runCatching { fcmUnavailableProvider(appContext) }.getOrDefault(true)) {
            return RaftFcmProbeUiStatus.None
        }
        if (!isFeatureEnabled(appContext)) {
            return RaftFcmProbeUiStatus.None
        }
        val snapshot = stateMachine.snapshot()
        return if (runCatching { directInstallAllowedProvider(appContext) }.getOrDefault(false)) {
            if (snapshot.autoFallbackActive) {
                RaftFcmProbeUiStatus.DirectAutoFallbackActive
            } else {
                RaftFcmProbeUiStatus.None
            }
        } else {
            val unreachable = snapshot.state as? FcmReachabilityState.Unreachable ?: return RaftFcmProbeUiStatus.None
            val episodeKey = playNoticeEpisodeKey(snapshot.networkKey, unreachable.confirmedAtMillis)
            if (dismissedPlayNoticeEpisodeKey == episodeKey) {
                RaftFcmProbeUiStatus.None
            } else {
                RaftFcmProbeUiStatus.PlayPushUnreachableNotice
            }
        }
    }

    fun dismissPlayUnreachableNotice(context: Context) {
        val snapshot = stateMachine.snapshot()
        val unreachable = snapshot.state as? FcmReachabilityState.Unreachable
        if (unreachable != null) {
            dismissedPlayNoticeEpisodeKey = playNoticeEpisodeKey(snapshot.networkKey, unreachable.confirmedAtMillis)
        }
        uiRefreshHandler()
    }

    fun onAppForegrounded(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext
        isForeground = true
        launchEvaluation(appContext, refreshNetworkSnapshot = true, source = "app_foregrounded")
    }

    fun onForegroundResume(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext
        isForeground = true
        launchEvaluation(appContext, refreshNetworkSnapshot = true, source = "activity_resume")
    }

    fun onAppBackgrounded(context: Context) {
        val appContext = context.applicationContext
        appContextRef = appContext
        isForeground = false
        launchEvaluation(appContext, refreshNetworkSnapshot = false, source = "app_backgrounded")
    }

    fun onNetworkChanged(context: Context, snapshot: AndroidNetworkSnapshot, source: String) {
        val appContext = context.applicationContext
        appContextRef = appContext
        lastNetworkSnapshot = snapshot
        launchEvaluation(appContext, refreshNetworkSnapshot = false, source = source)
    }

    override fun onFeatureFlagChanged() {
        val appContext = appContextRef ?: return
        launchEvaluation(appContext, refreshNetworkSnapshot = true, source = "feature_flag_changed")
    }

    override fun onPushRegistrationReady() {
        val appContext = appContextRef ?: return
        launchEvaluation(appContext, refreshNetworkSnapshot = true, source = "push_registration_ready")
    }

    override fun onSignedOut() {
        val appContext = appContextRef ?: return
        dismissedPlayNoticeEpisodeKey = null
        launchEvaluation(appContext, refreshNetworkSnapshot = false, source = "signed_out")
    }

    /**
     * Silent FCM probe arrived (`data["type"] == "probe"`). Runs synchronously on the state machine
     * so subsequent reads immediately see [FcmReachabilityStatus.Reachable], even before the HTTP
     * call completes or after the 15 s timeout.
     */
    fun onProbeReceived(context: Context, probeId: String) {
        val appContext = context.applicationContext
        appContextRef = appContext
        val flagEnabled = runCatching { featureFlagEnabledProvider(appContext) }.getOrDefault(false)
        if (!flagEnabled) return
        val effects = stateMachine.onProbeReceived(probeId = probeId, featureEnabled = true)
        if (effects.isNotEmpty()) {
            RaftAppLog.w(LOG_TAG, "fcm_probe_matched status=${currentReachabilityStatus()}")
            executeEffects(appContext, effects)
            uiRefreshHandler()
        }
    }

    /** Any real FCM delivery (alert, sync, retract) immediately proves FCM reachability. */
    fun onRealFcmDelivered(context: Context, source: String) {
        val appContext = context.applicationContext
        appContextRef = appContext
        val flagEnabled = runCatching { featureFlagEnabledProvider(appContext) }.getOrDefault(false)
        if (!flagEnabled) return
        val effects = stateMachine.onRealFcmDelivered(featureEnabled = true)
        if (effects.isNotEmpty()) {
            RaftAppLog.i(LOG_TAG, "fcm_real_delivery_confirmed source=$source status=${currentReachabilityStatus()}")
            executeEffects(appContext, effects)
            uiRefreshHandler()
        }
    }

    internal fun evaluateNowForTests(context: Context, refreshNetworkSnapshot: Boolean = false) {
        val appContext = context.applicationContext
        appContextRef = appContext
        evaluateSync(appContext, refreshNetworkSnapshot = refreshNetworkSnapshot, source = "test")
    }

    private fun launchEvaluation(appContext: Context, refreshNetworkSnapshot: Boolean, source: String) {
        val scope = scopeProvider()
        val dispatcher = dispatcherForTests
        val launchBlock: suspend () -> Unit = {
            evaluateSync(appContext, refreshNetworkSnapshot = refreshNetworkSnapshot, source = source)
        }
        if (dispatcher != null) {
            scope.launch(dispatcher) { launchBlock() }
        } else {
            scope.launch { launchBlock() }
        }
    }

    private fun evaluateSync(appContext: Context, refreshNetworkSnapshot: Boolean, source: String) {
        if (refreshNetworkSnapshot || lastNetworkSnapshot == null) {
            val rawFlag = runCatching { featureFlagEnabledProvider(appContext) }.getOrDefault(false)
            if (rawFlag) {
                lastNetworkSnapshot = runCatching { networkSnapshotProvider(appContext) }.getOrNull()
            }
        }
        val eligibility = currentEligibility(appContext)
        val before = stateMachine.snapshot()
        val effects = stateMachine.evaluate(eligibility)
        val after = stateMachine.snapshot()
        if (effects.isNotEmpty()) {
            RaftAppLog.d(
                LOG_TAG,
                "fcm_probe_evaluate source=$source state=${after.state.status} " +
                    "fallback=${after.autoFallbackActive} effects=${effects.size}"
            )
            executeEffects(appContext, effects)
        }
        if (before.state.status != after.state.status || before.autoFallbackActive != after.autoFallbackActive || before.serverUnsupported != after.serverUnsupported) {
            uiRefreshHandler()
        }
    }

    private fun currentEligibility(appContext: Context): ProbeEligibility {
        val flagEnabled = runCatching { featureFlagEnabledProvider(appContext) }.getOrDefault(false)
        if (!flagEnabled) {
            return ProbeEligibility(
                featureEnabled = false,
                isForeground = isForeground,
                signedIn = false,
                fcmUnavailable = false,
                hasFcmRegistration = false,
                raftApiHealthy = false,
                serverKey = null,
                networkKey = null,
            )
        }
        val snapshot = lastNetworkSnapshot ?: runCatching { networkSnapshotProvider(appContext) }.getOrNull()
        if (snapshot != null) lastNetworkSnapshot = snapshot
        return ProbeEligibility(
            featureEnabled = true,
            isForeground = isForeground,
            signedIn = runCatching { signedInProvider(appContext) }.getOrDefault(false),
            fcmUnavailable = runCatching { fcmUnavailableProvider(appContext) }.getOrDefault(true),
            hasFcmRegistration = runCatching { hasRegistrationProvider(appContext) }.getOrDefault(false),
            raftApiHealthy = snapshot?.online == true,
            serverKey = runCatching { serverKeyProvider(appContext) }.getOrNull(),
            networkKey = snapshot?.networkKey,
        )
    }

    private fun executeEffects(appContext: Context, effects: List<FcmProbeEffect>) {
        for (effect in effects) {
            when (effect) {
                is FcmProbeEffect.StartHttpProbe -> startHttpProbe(appContext, effect.probeId)
                is FcmProbeEffect.ScheduleMissTimeout -> scheduleMissTimeout(appContext, effect.probeId, effect.delayMillis)
                FcmProbeEffect.CancelMissTimeout -> cancelJob { missTimeoutJob.also { missTimeoutJob = null } }
                is FcmProbeEffect.ScheduleRetryProbe -> scheduleRetryProbe(appContext, effect.delayMillis)
                FcmProbeEffect.CancelRetryProbe -> cancelJob { retryJob.also { retryJob = null } }
                is FcmProbeEffect.ScheduleDwellExpiry -> scheduleDwellExpiry(appContext, effect.delayMillis)
                FcmProbeEffect.CancelDwellExpiry -> cancelJob { dwellJob.also { dwellJob = null } }
                is FcmProbeEffect.RequestPushReRegistration ->
                    runCatching { reRegisterAction(appContext, effect.reason) }
                is FcmProbeEffect.AutoFallbackChanged -> {
                    RaftAppLog.w(LOG_TAG, "fcm_auto_fallback_changed active=${effect.active} reason=${effect.reason}")
                    autoFallbackAppliedHandler(appContext, effect.active, effect.reason)
                    uiRefreshHandler()
                }
            }
        }
    }

    private fun startHttpProbe(appContext: Context, probeId: String) {
        val scope = scopeProvider()
        val dispatcher = dispatcherForTests
        synchronized(jobLock) {
            activeProbeJob?.cancel()
            val block: suspend () -> Unit = {
                val result =
                    try {
                        probeExecutor(probeId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        RaftPushProbeResult.Inconclusive("probe_executor_failed")
                    }
                val nextEffects = stateMachine.onProbeHttpResult(probeId, result, currentEligibility(appContext))
                if (nextEffects.isNotEmpty()) {
                    executeEffects(appContext, nextEffects)
                }
                uiRefreshHandler()
            }
            activeProbeJob =
                if (dispatcher != null) scope.launch(dispatcher) { block() } else scope.launch { block() }
        }
    }

    private fun scheduleMissTimeout(appContext: Context, probeId: String, delayMillis: Long) {
        val scope = scopeProvider()
        val dispatcher = dispatcherForTests
        synchronized(jobLock) {
            missTimeoutJob?.cancel()
            val block: suspend () -> Unit = {
                delay(delayMillis.coerceAtLeast(0L))
                val nextEffects = stateMachine.onProbeTimeout(probeId, currentEligibility(appContext))
                if (nextEffects.isNotEmpty()) {
                    RaftAppLog.w(
                        LOG_TAG,
                        "fcm_probe_timeout status=${currentReachabilityStatus()} fallback=${stateMachine.snapshot().autoFallbackActive}"
                    )
                    executeEffects(appContext, nextEffects)
                }
                uiRefreshHandler()
            }
            missTimeoutJob =
                if (dispatcher != null) scope.launch(dispatcher) { block() } else scope.launch { block() }
        }
    }

    private fun scheduleRetryProbe(appContext: Context, delayMillis: Long) {
        val scope = scopeProvider()
        val dispatcher = dispatcherForTests
        synchronized(jobLock) {
            retryJob?.cancel()
            val block: suspend () -> Unit = {
                delay(delayMillis.coerceAtLeast(0L))
                evaluateSync(appContext, refreshNetworkSnapshot = true, source = "retry_timer")
            }
            retryJob =
                if (dispatcher != null) scope.launch(dispatcher) { block() } else scope.launch { block() }
        }
    }

    private fun scheduleDwellExpiry(appContext: Context, delayMillis: Long) {
        val scope = scopeProvider()
        val dispatcher = dispatcherForTests
        synchronized(jobLock) {
            dwellJob?.cancel()
            val block: suspend () -> Unit = {
                delay(delayMillis.coerceAtLeast(0L))
                val flagEnabled = runCatching { featureFlagEnabledProvider(appContext) }.getOrDefault(false)
                val nextEffects = stateMachine.onDwellTimerExpired(featureEnabled = flagEnabled)
                if (nextEffects.isNotEmpty()) {
                    executeEffects(appContext, nextEffects)
                }
                uiRefreshHandler()
            }
            dwellJob =
                if (dispatcher != null) scope.launch(dispatcher) { block() } else scope.launch { block() }
        }
    }

    private fun cancelJob(takeJob: () -> Job?) {
        val job = synchronized(jobLock) { takeJob() }
        job?.cancel()
    }

    private fun applyAutoFallbackTransition(appContext: Context, active: Boolean, reason: String) {
        if (active) {
            // Defense-in-depth: Google Play builds NEVER start RaftBackgroundConnectionService.
            if (!runCatching { directInstallAllowedProvider(appContext) }.getOrDefault(false)) return
            AndroidRealtimeKeepAliveController.start(appContext, source = "fcm_probe_auto_fallback")
        } else {
            AndroidRealtimeKeepAliveController.refreshBackgroundConnectionService(
                appContext,
                source = "fcm_probe_$reason",
            )
        }
    }

    private fun playNoticeEpisodeKey(networkKey: String?, confirmedAtMillis: Long): String =
        "${networkKey.orEmpty()}|$confirmedAtMillis"

    internal fun resetForTests(customStateMachine: AndroidFcmReachabilityStateMachine? = null) {
        synchronized(jobLock) {
            missTimeoutJob?.cancel()
            missTimeoutJob = null
            retryJob?.cancel()
            retryJob = null
            dwellJob?.cancel()
            dwellJob = null
            activeProbeJob?.cancel()
            activeProbeJob = null
        }
        appContextRef = null
        isForeground = false
        lastNetworkSnapshot = null
        dismissedPlayNoticeEpisodeKey = null
        clockMillis = { System.currentTimeMillis() }
        probeIdGenerator = { UUID.randomUUID().toString() }
        scopeProvider = { defaultScope }
        dispatcherForTests = null
        featureFlagEnabledProvider = ::readServerFeatureFlagEnabled
        signedInProvider = AndroidRealtimeSessionAvailability::hasStoredRealtimeSession
        fcmUnavailableProvider = RaftPlayServicesAvailability::isFcmUnavailable
        hasRegistrationProvider = { RaftAndroidPushRegistrationBridge.hasSettledRegistrationSnapshot() }
        directInstallAllowedProvider = AndroidRealtimeKeepAliveController::directGmsKeepAliveOverrideAllowed
        serverKeyProvider = ::readCurrentServerKey
        networkSnapshotProvider = ::queryActiveAndroidNetworkSnapshot
        probeExecutor = { probeId -> RaftAndroidPushRegistrationBridge.sendReachabilityProbe(probeId) }
        reRegisterAction = { context, reason -> AndroidPushRegistration.requestReRegister(context, reason) }
        autoFallbackAppliedHandler = ::applyAutoFallbackTransition
        uiRefreshHandler = {
            (RaftBackgroundConnectionControlsRegistry.controls as? RaftAndroidBackgroundConnectionControls)
                ?.refreshFcmReachabilityStatus()
        }
        stateMachine = customStateMachine ?: createStateMachine()
    }
}

internal fun readServerFeatureFlagEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean =
    RaftAndroidPushRegistrationBridge.isFcmProbeFeatureFlagEnabled()

private fun readCurrentServerKey(@Suppress("UNUSED_PARAMETER") context: Context): String? =
    RaftAndroidPushRegistrationBridge.currentProbeServerKeyOrNull()

internal fun androidNetworkSnapshotFrom(network: Network, capabilities: NetworkCapabilities?): AndroidNetworkSnapshot {
    if (capabilities == null) {
        return AndroidNetworkSnapshot(networkKey = null, online = false)
    }
    val online =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val key =
        buildAndroidNetworkKey(
            networkHandle = network.networkHandle,
            hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            hasVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            hasEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        )
    return AndroidNetworkSnapshot(networkKey = key, online = online)
}

internal fun queryActiveAndroidNetworkSnapshot(context: Context): AndroidNetworkSnapshot =
    runCatching {
        val connectivityManager =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching AndroidNetworkSnapshot(networkKey = null, online = false)
        val activeNetwork = connectivityManager.activeNetwork
            ?: return@runCatching AndroidNetworkSnapshot(networkKey = null, online = false)
        androidNetworkSnapshotFrom(activeNetwork, connectivityManager.getNetworkCapabilities(activeNetwork))
    }.getOrDefault(AndroidNetworkSnapshot(networkKey = null, online = false))
