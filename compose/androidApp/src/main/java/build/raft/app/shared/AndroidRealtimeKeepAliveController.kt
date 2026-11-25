package build.raft.app.shared

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import build.raft.app.BuildConfig
import build.raft.app.core.data.auth.AndroidAppScopeMMKV
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY
import build.raft.app.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import build.raft.app.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RaftBackgroundConnectionControlsRegistry
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.push.AndroidFcmReachabilityController
import build.raft.app.push.RaftPlayServicesAvailability
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object AndroidRealtimeKeepAliveController {
    private const val LOG_TAG = "SlockRealtimeKeepAlive"
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    internal var directInstallApkProvider: () -> Boolean = { BuildConfig.HANDS_APK_UPDATE_ENABLED }

    @Volatile
    internal var serviceDeclaredProvider: (Context) -> Boolean = RaftBackgroundConnectionService::isDeclared

    @Volatile
    internal var localPreferenceReader: (Context, String, String) -> String = ::readLocalPreferenceFromMmkv

    @Volatile
    internal var localPreferenceWriter: (Context, String, String) -> Boolean = ::writeLocalPreferenceToMmkv

    @Volatile
    internal var storedSessionProvider: (Context) -> Boolean = AndroidRealtimeSessionAvailability::hasStoredRealtimeSession

    @Volatile
    internal var systemNotificationsAllowedProvider: (Context) -> Boolean = ::readSystemNotificationsAllowed

    @Volatile
    internal var fcmAutoFallbackProvider: (Context) -> Boolean = AndroidFcmReachabilityController::isAutoFallbackActive

    /**
     * Process start: wires the unified Settings controls synchronously (no IPC) and moves the Play
     * services, service-declaration, permission, default-seeding, override-read, and battery-exemption
     * probes onto IO.
     */
    fun installNoPlayServicesFallback(context: Context) {
        val appContext = context.applicationContext
        AndroidFcmReachabilityController.install(appContext)
        val controls = RaftAndroidBackgroundConnectionControls(appContext)
        RaftBackgroundConnectionControlsRegistry.controls = controls
        startupScope.launch {
            val canRequestBattery = controls.canRequestBatteryOptimizationExemption()
            if (noPlayServicesFallbackApplies(appContext)) {
                seedNoPlayServicesDefault(appContext, fcmUnavailable = true)
            }
            if (canRequestBattery) {
                controls.refreshBatteryOptimizationStatus()
            }
            controls.refreshDirectGmsKeepAliveOverrideStatus()
            controls.refreshFcmReachabilityStatus()
        }
    }

    /** Play services missing/disabled/invalid and this build ships the background connection service. */
    fun noPlayServicesFallbackApplies(context: Context): Boolean =
        RaftPlayServicesAvailability.isFcmUnavailable(context) && serviceDeclaredProvider(context)

    /**
     * True only on direct-install Android APKs whose merged manifest ships
     * [RaftBackgroundConnectionService]. Always false on Google Play builds.
     */
    fun directGmsKeepAliveOverrideAllowed(context: Context): Boolean =
        directInstallApkProvider() && serviceDeclaredProvider(context)

    /**
     * Persisted opt-in override for direct-install APKs on GMS devices (default false). Always
     * returns false on Google Play builds regardless of any persisted value.
     */
    fun isDirectGmsKeepAliveOverrideEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!directGmsKeepAliveOverrideAllowed(appContext)) return false
        return localPreferenceReader(
            appContext,
            NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY,
            "false",
        ) == "true"
    }

    /**
     * True when the direct-install GMS foreground-service override is actively in effect on this
     * device (direct APK, GMS present, user opted in). Always false on Google Play builds and on
     * no-GMS devices.
     */
    fun isDirectGmsKeepAliveOverrideActive(context: Context): Boolean {
        val appContext = context.applicationContext
        return RaftBackgroundConnectionPolicy.isDirectGmsOverrideActive(
            fcmUnavailable = noPlayServicesFallbackApplies(appContext),
            directGmsKeepAliveOverrideAllowed = directGmsKeepAliveOverrideAllowed(appContext),
            directGmsKeepAliveOverrideEnabled = isDirectGmsKeepAliveOverrideEnabled(appContext),
        )
    }

    /**
     * True when the direct-install automatic FCM-unreachable fallback (`mobile_fcm_probe_v0`) is
     * actively in effect on this device. ALWAYS false on Google Play builds and on no-GMS devices.
     */
    fun isFcmAutoFallbackActive(context: Context): Boolean {
        val appContext = context.applicationContext
        val allowed = directGmsKeepAliveOverrideAllowed(appContext)
        if (!allowed) return false
        return RaftBackgroundConnectionPolicy.isDirectGmsAutoFallbackActive(
            fcmUnavailable = noPlayServicesFallbackApplies(appContext),
            directGmsKeepAliveOverrideAllowed = allowed,
            fcmAutoFallbackRequested = runCatching { fcmAutoFallbackProvider(appContext) }.getOrDefault(false),
        )
    }

    /**
     * True when [RaftBackgroundConnectionService] applies to this process: Play services is
     * unavailable (automatic no-GMS fallback), the user enabled the direct-install GMS override,
     * or direct-install automatic FCM-unreachable fallback (`mobile_fcm_probe_v0`) is active.
     */
    fun backgroundConnectionServiceApplies(context: Context): Boolean =
        noPlayServicesFallbackApplies(context) ||
            isDirectGmsKeepAliveOverrideActive(context) ||
            isFcmAutoFallbackActive(context)

    /**
     * Persists and applies the direct-install GMS foreground-service override. Hard no-op on
     * Google Play builds (never writes to storage and ensures the service is stopped).
     */
    fun setDirectGmsKeepAliveOverrideEnabled(context: Context, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        if (!directGmsKeepAliveOverrideAllowed(appContext)) {
            RaftBackgroundConnectionService.stop(appContext, "play_services_available")
            return false
        }
        val persisted =
            localPreferenceWriter(
                appContext,
                NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY,
                if (enabled) "true" else "false",
            )
        if (!persisted) return false
        RaftAppLog.w(LOG_TAG, "direct_gms_override_changed enabled=$enabled")
        start(
            context = appContext,
            source = if (enabled) "direct_gms_override_enabled" else "direct_gms_override_disabled",
        )
        return true
    }

    fun start(context: Context, source: String = "unknown") {
        val appContext = context.applicationContext
        val fcmUnavailable = noPlayServicesFallbackApplies(appContext)
        seedNoPlayServicesDefault(appContext, fcmUnavailable)
        val keepAlivePrefEnabled = isForegroundKeepAliveEnabled(appContext)
        val directOverrideAllowed = directGmsKeepAliveOverrideAllowed(appContext)
        val directOverrideEnabled =
            if (directOverrideAllowed) isDirectGmsKeepAliveOverrideEnabled(appContext) else false
        val fcmAutoFallbackRequested =
            if (directOverrideAllowed && !fcmUnavailable) {
                runCatching { fcmAutoFallbackProvider(appContext) }.getOrDefault(false)
            } else {
                false
            }
        val gmsOverrideActive =
            RaftBackgroundConnectionPolicy.isDirectGmsOverrideActive(
                fcmUnavailable = fcmUnavailable,
                directGmsKeepAliveOverrideAllowed = directOverrideAllowed,
                directGmsKeepAliveOverrideEnabled = directOverrideEnabled,
            )
        val gmsAutoFallbackActive =
            RaftBackgroundConnectionPolicy.isDirectGmsAutoFallbackActive(
                fcmUnavailable = fcmUnavailable,
                directGmsKeepAliveOverrideAllowed = directOverrideAllowed,
                fcmAutoFallbackRequested = fcmAutoFallbackRequested,
            )
        if (!keepAlivePrefEnabled && !gmsOverrideActive && !gmsAutoFallbackActive) {
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("preference_disabled")
            RaftBackgroundConnectionService.stop(appContext, "preference_disabled")
            RaftAppLog.d(
                LOG_TAG,
                "start_skipped_preference_disabled source=$source"
            )
            return
        }
        if (!storedSessionProvider(appContext)) {
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("missing_session")
            RaftBackgroundConnectionService.stop(appContext, "missing_session")
            RaftAppLog.d(
                LOG_TAG,
                "start_skipped_no_session source=$source"
            )
            return
        }
        AndroidAppScopeRealtimeOwner.install(appContext)
        val decision =
            backgroundConnectionDecision(
                context = appContext,
                fcmUnavailable = fcmUnavailable,
                keepAlivePreferenceEnabled = keepAlivePrefEnabled,
                signedIn = true,
                directGmsKeepAliveOverrideAllowed = directOverrideAllowed,
                directGmsKeepAliveOverrideEnabled = directOverrideEnabled,
                fcmAutoFallbackActive = gmsAutoFallbackActive,
            )
        if (
            RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(
                fallbackApplies = fcmUnavailable,
                decision = decision,
                keepAlivePreferenceEnabled = keepAlivePrefEnabled,
            )
        ) {
            AndroidAppScopeRealtimeOwner.enableBackgroundKeepAlive(
                context = appContext,
                source = source
            )
        } else {
            // No Play services (or GMS override) and the service cannot run (e.g. no notification
            // permission): without the service the OS freezes the background process anyway.
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive(
                (decision as? RaftBackgroundConnectionPolicy.Decision.Stop)?.reason ?: "background_connection_stopped"
            )
        }
        applyBackgroundConnectionDecision(appContext, decision, source)
    }

    fun stop(context: Context, reason: String = "unknown") {
        val appContext = context.applicationContext
        // Turning off the separate "Background realtime" switch on a direct-install GMS device must
        // not tear down the foreground service while either the user's explicit GMS override OR
        // automatic FCM-unreachable fallback is active.
        if (
            reason == "preference_disabled" &&
            (isDirectGmsKeepAliveOverrideActive(appContext) || isFcmAutoFallbackActive(appContext))
        ) {
            start(appContext, source = "foreground_keep_alive_disabled")
            return
        }
        AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive(reason)
        // Transient disconnects (server switch, page release) leave the service up; see
        // [restoreBackgroundKeepAliveAfterDisconnect]. Session ends and opt-outs stop it now.
        if (RaftBackgroundConnectionPolicy.isTerminalStopReason(reason)) {
            RaftBackgroundConnectionService.stop(appContext, reason)
        }
    }

    /**
     * Call after a realtime disconnect has been fully applied (both [stop] and the owner's disconnect, which
     * each clear background keep-alive). While the background connection service runs or is starting, a
     * non-terminal disconnect must leave background reconnects allowed, otherwise the "receiving messages"
     * notification stays up with reconnect suspended until the next foreground.
     */
    fun restoreBackgroundKeepAliveAfterDisconnect(context: Context, reason: String) {
        if (!RaftBackgroundConnectionPolicy.shouldRestoreBackgroundKeepAliveAfterDisconnect(
                reason = reason,
                serviceActive = RaftBackgroundConnectionService.active
            )
        ) {
            return
        }
        AndroidAppScopeRealtimeOwner.enableBackgroundKeepAlive(
            context = context.applicationContext,
            source = "background_connection_service_after_disconnect_$reason"
        )
    }

    /**
     * The process just went to the background (its last started activity stopped). Activity resume used to be
     * the only place the service was requested, so any input that became true after that resume (sign-in or
     * session restore completing, a server switch racing the resume-time start, a service the system had
     * stopped) left a no-Play-services device without the service, and the OS froze or killed the process
     * once it was hidden. Re-evaluate here, still inside Android's foreground-to-background start grace.
     */
    fun onAppBackgrounded(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            backgroundTransitionHandler(appContext).onAppBackgrounded()
        }.onFailure { error ->
            RaftAppLog.w(
                LOG_TAG,
                "background_transition_failed errorType=${error::class.simpleName ?: "unknown"} " +
                    "message=${error.message.orEmpty()}"
            )
        }
    }

    private fun backgroundTransitionHandler(appContext: Context): BackgroundTransitionHandler =
        BackgroundTransitionHandler(
            fallbackApplies = { backgroundConnectionServiceApplies(appContext) },
            decision = { currentBackgroundConnectionDecision(appContext) },
            serviceActive = { RaftBackgroundConnectionService.active },
            stopQueued = { RaftBackgroundConnectionService.stopQueued },
            requestStart = { source ->
                AndroidAppScopeRealtimeOwner.enableBackgroundKeepAlive(context = appContext, source = source)
                RaftAppLog.w(LOG_TAG, "background_connection_service_request source=$source")
                RaftBackgroundConnectionService.start(appContext, source)
            },
            requestStop = { reason ->
                if (
                    !RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(
                        fallbackApplies = noPlayServicesFallbackApplies(appContext),
                        decision = RaftBackgroundConnectionPolicy.Decision.Stop(reason),
                        keepAlivePreferenceEnabled = isForegroundKeepAliveEnabled(appContext),
                    )
                ) {
                    AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive(reason)
                }
                RaftBackgroundConnectionService.stop(appContext, reason)
            },
            log = { message -> RaftAppLog.w(LOG_TAG, message) },
        )

    /** Background-transition wiring with its platform calls injected (unit-tested without Android). */
    internal class BackgroundTransitionHandler(
        private val fallbackApplies: () -> Boolean,
        private val decision: () -> RaftBackgroundConnectionPolicy.Decision,
        private val serviceActive: () -> Boolean,
        private val stopQueued: () -> Boolean,
        private val requestStart: (source: String) -> Unit,
        private val requestStop: (reason: String) -> Unit,
        private val log: (String) -> Unit,
    ) {
        fun onAppBackgrounded() {
            val applies = fallbackApplies()
            val active = serviceActive()
            // Play services devices without the direct-install override: read nothing else, change nothing.
            if (!applies && !active) return
            val current = decision()
            val action =
                RaftBackgroundConnectionPolicy.backgroundTransitionAction(
                    fallbackApplies = applies,
                    decision = current,
                    serviceActive = active,
                    stopQueued = stopQueued(),
                )
            log("background_transition decision=$current action=$action")
            when (action) {
                RaftBackgroundConnectionPolicy.BackgroundTransitionAction.None -> Unit
                RaftBackgroundConnectionPolicy.BackgroundTransitionAction.RequestStart ->
                    requestStart(SOURCE_APP_BACKGROUND)
                is RaftBackgroundConnectionPolicy.BackgroundTransitionAction.Stop -> requestStop(action.reason)
            }
        }
    }

    internal const val SOURCE_APP_BACKGROUND = "app_background"

    /** Called from activity resume: re-reads inputs that can change while the app was away. */
    fun onHostResumed(context: Context) {
        val appContext = context.applicationContext
        RaftPlayServicesAvailability.refresh(appContext)
        val controls = RaftBackgroundConnectionControlsRegistry.controls as? RaftAndroidBackgroundConnectionControls
        if (controls != null) {
            if (controls.canRequestBatteryOptimizationExemption()) {
                controls.refreshBatteryOptimizationStatus()
            }
            controls.refreshDirectGmsKeepAliveOverrideStatus()
            controls.refreshFcmReachabilityStatus()
        }
        AndroidFcmReachabilityController.onForegroundResume(appContext)
    }

    /** The policy decision from the current stored inputs (used by the service when its command runs). */
    fun currentBackgroundConnectionDecision(context: Context): RaftBackgroundConnectionPolicy.Decision {
        val appContext = context.applicationContext
        val fcmUnavailable = noPlayServicesFallbackApplies(appContext)
        val directOverrideAllowed = directGmsKeepAliveOverrideAllowed(appContext)
        val fcmAutoFallbackActive =
            if (directOverrideAllowed && !fcmUnavailable) {
                runCatching { fcmAutoFallbackProvider(appContext) }.getOrDefault(false)
            } else {
                false
            }
        return backgroundConnectionDecision(
            context = appContext,
            fcmUnavailable = fcmUnavailable,
            keepAlivePreferenceEnabled = isForegroundKeepAliveEnabled(appContext),
            signedIn = storedSessionProvider(appContext),
            directGmsKeepAliveOverrideAllowed = directOverrideAllowed,
            directGmsKeepAliveOverrideEnabled =
                if (directOverrideAllowed) isDirectGmsKeepAliveOverrideEnabled(appContext) else false,
            fcmAutoFallbackActive = fcmAutoFallbackActive,
        )
    }

    /** Re-evaluates the service after an input changed in the app (e.g. the message notification switch). */
    fun refreshBackgroundConnectionService(context: Context, source: String) {
        val appContext = context.applicationContext
        val fcmUnavailable = noPlayServicesFallbackApplies(appContext)
        val keepAlivePrefEnabled = isForegroundKeepAliveEnabled(appContext)
        val decision = currentBackgroundConnectionDecision(appContext)
        if (
            RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(
                fallbackApplies = fcmUnavailable,
                decision = decision,
                keepAlivePreferenceEnabled = keepAlivePrefEnabled,
            )
        ) {
            if (decision == RaftBackgroundConnectionPolicy.Decision.Run) {
                AndroidAppScopeRealtimeOwner.enableBackgroundKeepAlive(context = appContext, source = source)
            }
        } else {
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive(
                (decision as? RaftBackgroundConnectionPolicy.Decision.Stop)?.reason ?: "background_connection_stopped"
            )
        }
        applyBackgroundConnectionDecision(appContext, decision, source)
    }

    private fun backgroundConnectionDecision(
        context: Context,
        fcmUnavailable: Boolean,
        keepAlivePreferenceEnabled: Boolean,
        signedIn: Boolean,
        directGmsKeepAliveOverrideAllowed: Boolean,
        directGmsKeepAliveOverrideEnabled: Boolean,
        fcmAutoFallbackActive: Boolean = false,
    ): RaftBackgroundConnectionPolicy.Decision =
        RaftBackgroundConnectionPolicy.decide(
            RaftBackgroundConnectionPolicy.Inputs(
                fcmUnavailable = fcmUnavailable,
                keepAlivePreferenceEnabled = keepAlivePreferenceEnabled,
                signedIn = signedIn,
                messageNotificationsEnabled = localPreferenceReader(context, NOTIFICATION_ENABLED_PREFERENCE_KEY, "true") == "true",
                systemNotificationsAllowed = systemNotificationsAllowedProvider(context),
                directGmsKeepAliveOverrideAllowed = directGmsKeepAliveOverrideAllowed,
                directGmsKeepAliveOverrideEnabled = directGmsKeepAliveOverrideEnabled,
                fcmAutoFallbackActive = fcmAutoFallbackActive,
            )
        )

    private fun applyBackgroundConnectionDecision(
        context: Context,
        decision: RaftBackgroundConnectionPolicy.Decision,
        source: String,
    ) {
        when (decision) {
            RaftBackgroundConnectionPolicy.Decision.Run -> {
                if (RaftBackgroundConnectionService.active && !RaftBackgroundConnectionService.stopQueued) return
                // warn level: RaftAppLog.i is dropped in release builds, and this is the line that proves the
                // service was requested when reading a feedback diagnostics zip.
                RaftAppLog.w(LOG_TAG, "background_connection_service_request source=$source")
                RaftBackgroundConnectionService.start(context, source)
            }
            is RaftBackgroundConnectionPolicy.Decision.Stop ->
                RaftBackgroundConnectionService.stop(context, decision.reason)
        }
    }

    private fun readSystemNotificationsAllowed(context: Context): Boolean =
        runCatching {
            AndroidUnreadNotificationPoster.hasNotificationPermission(context) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)

    private fun seedNoPlayServicesDefault(context: Context, fcmUnavailable: Boolean) {
        if (!fcmUnavailable) return
        runCatching {
            AndroidAppScopeMMKV.ensureInitialized(context)
            val store = MMKV.mmkvWithID(RaftLocalPreferenceStore.KuiklyNamespace)
            val key = localPreferenceKey(NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY)
            val shouldSeed =
                RaftBackgroundConnectionPolicy.shouldSeedKeepAliveDefault(
                    fcmUnavailable = true,
                    storedValue = store.decodeString(key, null),
                    legacyStoredValue = store.decodeString(NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY, null),
                )
            if (!shouldSeed) return
            // Written straight to MMKV (not through the observed store) so seeding never starts anything by
            // itself; the next foreground resume applies it.
            store.encode(key, "true")
            RaftAppLog.i(LOG_TAG, "keep_alive_default_seeded value=true reason=no_play_services")
        }.onFailure { error ->
            RaftAppLog.w(LOG_TAG, "keep_alive_default_seed_failed message=${error.message.orEmpty()}")
        }
    }

    fun isForegroundKeepAliveEnabled(context: Context): Boolean =
        localPreferenceReader(context, NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY, "false") == "true"

    private fun readLocalPreferenceFromMmkv(context: Context, key: String, defaultValue: String): String =
        runCatching {
            val appContext = context.applicationContext
            AndroidAppScopeMMKV.ensureInitialized(appContext)
            val store = MMKV.mmkvWithID(RaftLocalPreferenceStore.KuiklyNamespace)
            store.decodeString(localPreferenceKey(key), null)
                ?: store.decodeString(key, defaultValue)
                ?: defaultValue
        }.getOrElse { error ->
            RaftAppLog.w(
                LOG_TAG,
                "preference_read_failed key=$key message=${error.message.orEmpty()}"
            )
            // Unreadable: behave as off for the keep-alive switches, as before.
            if (
                key == NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY ||
                key == NOTIFICATION_DIRECT_GMS_KEEP_ALIVE_OVERRIDE_PREFERENCE_KEY
            ) {
                "false"
            } else {
                defaultValue
            }
        }

    private fun writeLocalPreferenceToMmkv(context: Context, key: String, value: String): Boolean =
        runCatching {
            val appContext = context.applicationContext
            AndroidAppScopeMMKV.ensureInitialized(appContext)
            val store = MMKV.mmkvWithID(RaftLocalPreferenceStore.KuiklyNamespace)
            store.encode(localPreferenceKey(key), value)
        }.onFailure { error ->
            RaftAppLog.w(
                LOG_TAG,
                "preference_write_failed key=$key message=${error.message.orEmpty()}"
            )
        }.isSuccess

    internal fun resetForTests() {
        directInstallApkProvider = { BuildConfig.HANDS_APK_UPDATE_ENABLED }
        serviceDeclaredProvider = RaftBackgroundConnectionService::isDeclared
        localPreferenceReader = ::readLocalPreferenceFromMmkv
        localPreferenceWriter = ::writeLocalPreferenceToMmkv
        storedSessionProvider = AndroidRealtimeSessionAvailability::hasStoredRealtimeSession
        systemNotificationsAllowedProvider = ::readSystemNotificationsAllowed
        fcmAutoFallbackProvider = AndroidFcmReachabilityController::isAutoFallbackActive
    }

    internal fun localPreferenceKey(key: String): String =
        "${RaftLocalPreferenceStore.KuiklyNamespace}:$key"
}
