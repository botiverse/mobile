package build.raft.app.shared

/**
 * Pure decisions for the no-Google-Play-services background connection (foreground service that keeps
 * the app-scope realtime connection alive so local message notifications keep working).
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
    )

    internal sealed interface Decision {
        data object Run : Decision

        data class Stop(val reason: String) : Decision
    }

    /**
     * The service only runs when it is the device's only notification path and it can actually deliver:
     * never on a Play services device, never signed out, never when message notifications cannot be shown
     * (without notification permission the service's own notification would be hidden too).
     */
    fun decide(inputs: Inputs): Decision =
        when {
            !inputs.fcmUnavailable -> Decision.Stop("play_services_available")
            !inputs.keepAlivePreferenceEnabled -> Decision.Stop("preference_disabled")
            !inputs.signedIn -> Decision.Stop("signed_out")
            !inputs.messageNotificationsEnabled -> Decision.Stop("message_notifications_disabled")
            !inputs.systemNotificationsAllowed -> Decision.Stop("system_notifications_blocked")
            else -> Decision.Run
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
     * frozen anyway and nothing could be shown. Play services devices keep the plain preference behaviour.
     */
    fun shouldEnableBackgroundKeepAlive(fallbackApplies: Boolean, decision: Decision): Boolean =
        !fallbackApplies || decision == Decision.Run

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
