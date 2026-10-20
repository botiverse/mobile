package build.raft.app.shared

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import build.raft.app.core.data.auth.AndroidAppScopeMMKV
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import build.raft.app.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RaftBackgroundConnectionControlsRegistry
import build.raft.app.data.RaftLocalPreferenceStore
import build.raft.app.push.RaftPlayServicesAvailability
import com.tencent.mmkv.MMKV

internal object AndroidRealtimeKeepAliveController {
    private const val LOG_TAG = "SlockRealtimeKeepAlive"

    /**
     * Process start: on a device without Play services, default the keep-alive preference to on (only if the
     * user never chose) and expose the Settings controls for the background connection.
     */
    fun installNoPlayServicesFallback(context: Context) {
        val appContext = context.applicationContext
        RaftBackgroundConnectionControlsRegistry.controls = RaftAndroidBackgroundConnectionControls(appContext)
        seedNoPlayServicesDefault(appContext, noPlayServicesFallbackApplies(appContext))
    }

    /** Play services missing/disabled/invalid and this build ships the background connection service. */
    fun noPlayServicesFallbackApplies(context: Context): Boolean =
        RaftPlayServicesAvailability.isFcmUnavailable(context) && RaftBackgroundConnectionService.isDeclared(context)

    fun start(context: Context, source: String = "unknown") {
        val appContext = context.applicationContext
        val fcmUnavailable = noPlayServicesFallbackApplies(appContext)
        seedNoPlayServicesDefault(appContext, fcmUnavailable)
        if (!isForegroundKeepAliveEnabled(appContext)) {
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("preference_disabled")
            RaftBackgroundConnectionService.stop(appContext, "preference_disabled")
            RaftAppLog.d(
                LOG_TAG,
                "start_skipped_preference_disabled source=$source"
            )
            return
        }
        if (!AndroidRealtimeSessionAvailability.hasStoredRealtimeSession(appContext)) {
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
                keepAlivePreferenceEnabled = true,
                signedIn = true,
            )
        if (RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(fcmUnavailable, decision)) {
            AndroidAppScopeRealtimeOwner.enableBackgroundKeepAlive(
                context = appContext,
                source = source
            )
        } else {
            // No Play services and the service cannot run (e.g. no notification permission): a seeded
            // preference alone must not hold a background connection the OS will freeze anyway.
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive(
                (decision as? RaftBackgroundConnectionPolicy.Decision.Stop)?.reason ?: "background_connection_stopped"
            )
        }
        applyBackgroundConnectionDecision(appContext, decision, source)
    }

    fun stop(context: Context, reason: String = "unknown") {
        AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive(reason)
        // Transient disconnects (server switch, page release) leave the service up; see
        // [restoreBackgroundKeepAliveAfterDisconnect]. Session ends and opt-outs stop it now.
        if (RaftBackgroundConnectionPolicy.isTerminalStopReason(reason)) {
            RaftBackgroundConnectionService.stop(context.applicationContext, reason)
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

    /** Called from activity resume: re-reads inputs that can change while the app was away. */
    fun onHostResumed(context: Context) {
        val appContext = context.applicationContext
        RaftPlayServicesAvailability.refresh(appContext)
        (RaftBackgroundConnectionControlsRegistry.controls as? RaftAndroidBackgroundConnectionControls)
            ?.refreshBatteryOptimizationStatus()
    }

    /** The policy decision from the current stored inputs (used by the service when its command runs). */
    fun currentBackgroundConnectionDecision(context: Context): RaftBackgroundConnectionPolicy.Decision {
        val appContext = context.applicationContext
        return backgroundConnectionDecision(
            context = appContext,
            fcmUnavailable = noPlayServicesFallbackApplies(appContext),
            keepAlivePreferenceEnabled = isForegroundKeepAliveEnabled(appContext),
            signedIn = AndroidRealtimeSessionAvailability.hasStoredRealtimeSession(appContext),
        )
    }

    /** Re-evaluates the service after an input changed in the app (e.g. the message notification switch). */
    fun refreshBackgroundConnectionService(context: Context, source: String) {
        val appContext = context.applicationContext
        applyBackgroundConnectionDecision(appContext, currentBackgroundConnectionDecision(appContext), source)
    }

    private fun backgroundConnectionDecision(
        context: Context,
        fcmUnavailable: Boolean,
        keepAlivePreferenceEnabled: Boolean,
        signedIn: Boolean,
    ): RaftBackgroundConnectionPolicy.Decision =
            RaftBackgroundConnectionPolicy.decide(
                RaftBackgroundConnectionPolicy.Inputs(
                    fcmUnavailable = fcmUnavailable,
                    keepAlivePreferenceEnabled = keepAlivePreferenceEnabled,
                    signedIn = signedIn,
                    messageNotificationsEnabled = readLocalPreference(context, NOTIFICATION_ENABLED_PREFERENCE_KEY, "true") == "true",
                    systemNotificationsAllowed = systemNotificationsAllowed(context),
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
                RaftAppLog.i(LOG_TAG, "background_connection_service_request source=$source")
                RaftBackgroundConnectionService.start(context, source)
            }
            is RaftBackgroundConnectionPolicy.Decision.Stop ->
                RaftBackgroundConnectionService.stop(context, decision.reason)
        }
    }

    private fun systemNotificationsAllowed(context: Context): Boolean =
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
        readLocalPreference(context, NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY, "false") == "true"

    private fun readLocalPreference(context: Context, key: String, defaultValue: String): String =
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
            // Unreadable: behave as off for the keep-alive switch, as before.
            if (key == NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY) "false" else defaultValue
        }

    internal fun localPreferenceKey(key: String): String =
        "${RaftLocalPreferenceStore.KuiklyNamespace}:$key"
}
