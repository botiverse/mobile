package build.raft.app.shared

/**
 * Pure decisions for the Android background connection foreground service (keeps the app-scope
 * realtime connection alive so local message notifications keep working on no-GMS devices and on
 * direct-install APKs where the user explicitly opted into the GMS override).
 */
internal object RaftBackgroundConnectionPolicy {
    internal data class Inputs(
        /** Play services is missing, disabled or invalid: FCM can never deliver to this device. */
        val fcmUnavailable: Boolean,
        /** The "keep connection in background" preference (`notifications:foreground_keep_alive_enabled`). */
        val keepAlivePreferenceEnabled: Boolean,
        /** A stored session with an access token and a selected server. */
        val signedIn: Boolean,
        /** The in-app "Message notifications" preference. */
        val messageNotificationsEnabled: Boolean,
        /** POST_NOTIFICATIONS granted and the app's notifications not blocked in system settings. */
        val systemNotificationsAllowed: Boolean,
        /**
         * True only on direct-install Android APKs that ship [RaftBackgroundConnectionService];
         * always false on Google Play builds so a persisted override can never take effect there.
         */
        val directGmsKeepAliveOverrideAllowed: Boolean = false,
        /**
         * User opt-in override (`notifications:direct_gms_keep_alive_override_enabled`) allowing
         * the foreground connection service on GMS devices when FCM is unreachable. Default false.
         */
        val directGmsKeepAliveOverrideEnabled: Boolean = false,
        /**
         * Automatic FCM reachability fallback (`mobile_fcm_probe_v0`, `#proj-mobile:#379`): true
         * when silent end-to-end FCM probing confirmed FCM unreachable on the current network (and
         * until FCM is proven reachable again past the anti-flap dwell time). Always ignored unless
         * [directGmsKeepAliveOverrideAllowed] is true (direct-install APKs only; never on Play).
         */
        val fcmAutoFallbackActive: Boolean = false,
    )

    internal sealed interface Decision {
        data object Run : Decision

        data class Stop(val reason: String) : Decision
    }

    /**
     * True when a direct-install Android APK user on a GMS device has opted into keeping the
     * foreground connection service running despite Play services being installed. Always false on
     * no-GMS devices (where the automatic fallback governs) and on Google Play builds.
     */
    fun isDirectGmsOverrideActive(
        fcmUnavailable: Boolean,
        directGmsKeepAliveOverrideAllowed: Boolean,
        directGmsKeepAliveOverrideEnabled: Boolean,
    ): Boolean = !fcmUnavailable && directGmsKeepAliveOverrideAllowed && directGmsKeepAliveOverrideEnabled

    /**
     * Defense-in-depth gate for automatic FCM-unreachable fallback (`mobile_fcm_probe_v0`).
     * True ONLY on direct-install APKs whose manifest ships [RaftBackgroundConnectionService] when
     * GMS is available and probing confirmed FCM unreachable. Always false on Google Play builds
     * and on no-GMS devices.
     */
    fun isDirectGmsAutoFallbackActive(
        fcmUnavailable: Boolean,
        directGmsKeepAliveOverrideAllowed: Boolean,
        fcmAutoFallbackRequested: Boolean,
    ): Boolean = !fcmUnavailable && directGmsKeepAliveOverrideAllowed && fcmAutoFallbackRequested

    /**
     * The service only runs when Play services is unavailable (automatic no-GMS fallback with the
     * keep-alive switch on), a direct-install APK user explicitly enabled the GMS override, OR
     * direct-install automatic FCM-unreachable fallback (`mobile_fcm_probe_v0`) is active, and
     * only while signed in with notifications allowed (without notification permission the
     * service's own notification would be hidden too). Google Play builds always stop with
     * `play_services_available`.
     */
    fun decide(inputs: Inputs): Decision {
        val gmsOverrideActive =
            isDirectGmsOverrideActive(
                fcmUnavailable = inputs.fcmUnavailable,
                directGmsKeepAliveOverrideAllowed = inputs.directGmsKeepAliveOverrideAllowed,
                directGmsKeepAliveOverrideEnabled = inputs.directGmsKeepAliveOverrideEnabled,
            )
        val gmsAutoFallbackActive =
            isDirectGmsAutoFallbackActive(
                fcmUnavailable = inputs.fcmUnavailable,
                directGmsKeepAliveOverrideAllowed = inputs.directGmsKeepAliveOverrideAllowed,
                fcmAutoFallbackRequested = inputs.fcmAutoFallbackActive,
            )
        return when {
            !inputs.fcmUnavailable && !gmsOverrideActive && !gmsAutoFallbackActive -> Decision.Stop("play_services_available")
            inputs.fcmUnavailable && !inputs.keepAlivePreferenceEnabled -> Decision.Stop("preference_disabled")
            !inputs.signedIn -> Decision.Stop("signed_out")
            !inputs.messageNotificationsEnabled -> Decision.Stop("message_notifications_disabled")
            !inputs.systemNotificationsAllowed -> Decision.Stop("system_notifications_blocked")
            else -> Decision.Run
        }
    }

    /**
     * Default ON for devices without Play services: the preference is seeded to "true" once, only while the
     * user never chose a value (neither the namespaced nor the legacy key exists). An explicit "false" stays.
     * The service itself still waits for notification permission (see [decide]).
     */
    fun shouldSeedKeepAliveDefault(
        fcmUnavailable: Boolean,
        storedValue: String?,
        legacyStoredValue: String?,
    ): Boolean = fcmUnavailable && storedValue == null && legacyStoredValue == null

    /** Session-ending disconnects stop the service at once; anything else is re-evaluated from inputs. */
    fun isTerminalStopReason(reason: String): Boolean = reason in TerminalStopReasons

    /**
     * A system restart of a killed sticky service (null intent) comes back in a fresh process whose realtime
     * connection is only opened by the UI. Keeping a "receiving messages" notification without a connection
     * would lie to the user, so such a restart stops unless the owner already holds a session.
     */
    fun shouldKeepRunningAfterServiceStart(isSystemRestart: Boolean, hasRealtimeSession: Boolean): Boolean =
        !isSystemRestart || hasRealtimeSession

    internal sealed interface StartCommandOutcome {
        /** Sticky restart (no foreground-service start pending): stop without ever posting the notification. */
        data object StopWithoutForeground : StartCommandOutcome

        /**
         * A `startForegroundService` start is pending: Android crashes the app unless `startForeground` is
         * called, even when the service is about to stop. Promote first, then stop.
         */
        data class PromoteThenStop(val reason: String) : StartCommandOutcome

        data object PromoteAndRun : StartCommandOutcome
    }

    /**
     * What `onStartCommand` must do. [isSystemRestart] is a sticky restart (null intent); [stopRequested] is
     * the explicit stop action sent while a start was still pending; [decision] is the policy evaluated now,
     * since inputs can change between the start request and the (possibly late, cold-start) command.
     */
    fun startCommandOutcome(
        isSystemRestart: Boolean,
        hasRealtimeSession: Boolean,
        stopRequested: Boolean,
        decision: Decision,
    ): StartCommandOutcome =
        when {
            isSystemRestart && !shouldKeepRunningAfterServiceStart(true, hasRealtimeSession) ->
                StartCommandOutcome.StopWithoutForeground
            isSystemRestart && decision is Decision.Stop -> StartCommandOutcome.StopWithoutForeground
            stopRequested -> StartCommandOutcome.PromoteThenStop("stop_requested")
            decision is Decision.Stop -> StartCommandOutcome.PromoteThenStop(decision.reason)
            else -> StartCommandOutcome.PromoteAndRun
        }

    /**
     * After a realtime disconnect: while the background connection service is running (or its start is
     * pending) a non-terminal disconnect (server switch, page release, ...) must not leave reconnects
     * suspended in the background, or the "receiving messages" notification would lie.
     */
    fun shouldRestoreBackgroundKeepAliveAfterDisconnect(reason: String, serviceActive: Boolean): Boolean =
        serviceActive && !isTerminalStopReason(reason)

    /**
     * On a no-Play-services device the keep-alive preference is seeded on by default, so it alone must not
     * keep a background connection: without the service (e.g. no notification permission) the process gets
     * frozen anyway and nothing could be shown. When the foreground connection service should run (either
     * no-GMS fallback or direct-install GMS override), socket background keep-alive must be enabled too.
     * Otherwise Play services devices keep the plain preference behaviour.
     */
    fun shouldEnableBackgroundKeepAlive(
        fallbackApplies: Boolean,
        decision: Decision,
        keepAlivePreferenceEnabled: Boolean = true,
    ): Boolean =
        when {
            decision == Decision.Run -> true
            fallbackApplies -> false
            else -> keepAlivePreferenceEnabled
        }

    internal sealed interface BackgroundTransitionAction {
        /** Nothing to do: a Play services device without the override, or the service is already up (or stopped) as it should be. */
        data object None : BackgroundTransitionAction

        /** The service should run but is not up: request it now, inside the foreground-to-background grace. */
        data object RequestStart : BackgroundTransitionAction

        data class Stop(val reason: String) : BackgroundTransitionAction
    }

    /**
     * The app just left the foreground (last activity stopped). This is the last moment Android 12+ still
     * lets the app start a foreground service, and the moment the service starts to matter, so the decision is
     * re-read from storage here: it catches every input that changed after the last activity resume (sign-in
     * or session restore finishing after resume, a server switch racing the resume-time start, a service the
     * system stopped). Play services devices without the direct-install override are never touched.
     */
    fun backgroundTransitionAction(
        fallbackApplies: Boolean,
        decision: Decision,
        serviceActive: Boolean,
        stopQueued: Boolean,
    ): BackgroundTransitionAction =
        when {
            !fallbackApplies ->
                if (serviceActive && !stopQueued) {
                    val reason = (decision as? Decision.Stop)?.reason ?: "play_services_available"
                    BackgroundTransitionAction.Stop(reason)
                } else {
                    BackgroundTransitionAction.None
                }
            decision is Decision.Stop ->
                if (serviceActive) BackgroundTransitionAction.Stop(decision.reason) else BackgroundTransitionAction.None
            serviceActive && !stopQueued -> BackgroundTransitionAction.None
            else -> BackgroundTransitionAction.RequestStart
        }

    private val TerminalStopReasons =
        setOf(
            "logout",
            "auth_invalid",
            "api_environment_changed",
            "process_teardown",
            "preference_disabled",
            "missing_session",
        )
}
