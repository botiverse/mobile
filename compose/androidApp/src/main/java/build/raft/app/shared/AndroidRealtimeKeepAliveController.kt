package build.raft.app.shared

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import build.raft.app.core.data.auth.AndroidAppScopeMMKV
import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.data.NOTIFICATION_ENABLED_PREFERENCE_KEY
import build.raft.app.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import build.raft.app.data.RaftBackgroundConnectionControlsRegistry
import build.raft.app.data.SlockLocalPreferenceStore
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
            SlockAppLog.d(
                LOG_TAG,
                "start_skipped_preference_disabled source=$source"
            )
            return
        }
        if (!AndroidRealtimeSessionAvailability.hasStoredRealtimeSession(appContext)) {
            AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive("missing_session")
            RaftBackgroundConnectionService.stop(appContext, "missing_session")
            SlockAppLog.d(
                LOG_TAG,
                "start_skipped_no_session source=$source"
            )
            return
        }
        AndroidAppScopeRealtimeOwner.install(appContext)
        AndroidAppScopeRealtimeOwner.enableBackgroundKeepAlive(
            context = appContext,
            source = source
        )
        syncBackgroundConnectionService(
            context = appContext,
            fcmUnavailable = fcmUnavailable,
            keepAlivePreferenceEnabled = true,
            signedIn = true,
            source = source
        )
    }

    fun stop(context: Context, reason: String = "unknown") {
        AndroidAppScopeRealtimeOwner.disableBackgroundKeepAlive(reason)
        // Transient disconnects (server switch, page release) leave the service up: the owner re-enables
        // background keep-alive on hide while the service runs. Session ends and opt-outs stop it now.
        if (RaftBackgroundConnectionPolicy.isTerminalStopReason(reason)) {
            RaftBackgroundConnectionService.stop(context.applicationContext, reason)
        }
    }

    /** Re-evaluates the service after an input changed in the app (e.g. the message notification switch). */
    fun refreshBackgroundConnectionService(context: Context, source: String) {
        val appContext = context.applicationContext
        syncBackgroundConnectionService(
            context = appContext,
            fcmUnavailable = noPlayServicesFallbackApplies(appContext),
            keepAlivePreferenceEnabled = isForegroundKeepAliveEnabled(appContext),
            signedIn = AndroidRealtimeSessionAvailability.hasStoredRealtimeSession(appContext),
            source = source
        )
    }

    private fun syncBackgroundConnectionService(
        context: Context,
        fcmUnavailable: Boolean,
        keepAlivePreferenceEnabled: Boolean,
        signedIn: Boolean,
        source: String,
    ) {
        val decision =
            RaftBackgroundConnectionPolicy.decide(
                RaftBackgroundConnectionPolicy.Inputs(
                    fcmUnavailable = fcmUnavailable,
                    keepAlivePreferenceEnabled = keepAlivePreferenceEnabled,
                    signedIn = signedIn,
                    messageNotificationsEnabled = readLocalPreference(context, NOTIFICATION_ENABLED_PREFERENCE_KEY, "true") == "true",
                    systemNotificationsAllowed = systemNotificationsAllowed(context),
                )
            )
        when (decision) {
            RaftBackgroundConnectionPolicy.Decision.Run -> {
                if (RaftBackgroundConnectionService.running) return
                SlockAppLog.i(LOG_TAG, "background_connection_service_request source=$source")
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
            val store = MMKV.mmkvWithID(SlockLocalPreferenceStore.KuiklyNamespace)
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
            SlockAppLog.i(LOG_TAG, "keep_alive_default_seeded value=true reason=no_play_services")
        }.onFailure { error ->
            SlockAppLog.w(LOG_TAG, "keep_alive_default_seed_failed message=${error.message.orEmpty()}")
        }
    }

    fun isForegroundKeepAliveEnabled(context: Context): Boolean =
        readLocalPreference(context, NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY, "false") == "true"

    private fun readLocalPreference(context: Context, key: String, defaultValue: String): String =
        runCatching {
            val appContext = context.applicationContext
            AndroidAppScopeMMKV.ensureInitialized(appContext)
            val store = MMKV.mmkvWithID(SlockLocalPreferenceStore.KuiklyNamespace)
            store.decodeString(localPreferenceKey(key), null)
                ?: store.decodeString(key, defaultValue)
                ?: defaultValue
        }.getOrElse { error ->
            SlockAppLog.w(
                LOG_TAG,
                "preference_read_failed key=$key message=${error.message.orEmpty()}"
            )
            // Unreadable: behave as off for the keep-alive switch, as before.
            if (key == NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY) "false" else defaultValue
        }

    internal fun localPreferenceKey(key: String): String =
        "${SlockLocalPreferenceStore.KuiklyNamespace}:$key"
}
