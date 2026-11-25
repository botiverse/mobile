package build.raft.app.data

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified Android host controls for background keep-alive in Settings › Notifications.
 *
 * Only Android installs this contract; iOS and desktop leave
 * [RaftBackgroundConnectionControlsRegistry.controls] null and render no Android keep-alive rows.
 *
 * - On Android without Google Play services, [isOffered] is true (the foreground-service fallback
 *   applies automatically).
 * - On direct/non-Play builds when Google Play services is available,
 *   [canOverrideWithForegroundService] is true so the user can opt in (default off) to keeping the
 *   foreground connection service running when FCM cannot be reached on their network.
 * - On direct/non-Play builds whose manifest declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
 *   [canRequestBatteryOptimizationExemption] is true and [requestBatteryOptimizationExemption]
 *   opens the direct exemption prompt (falling back to system battery optimization settings and
 *   then App Info).
 * - On Google Play builds (where `RaftBackgroundConnectionService` and
 *   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are removed by manifest merge), both
 *   [canOverrideWithForegroundService] and [canRequestBatteryOptimizationExemption] are always
 *   false, and [openVendorBackgroundSettings] routes through OEM auto-start / background
 *   management settings -> system battery optimization settings -> App Info without ever invoking
 *   `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
 */
/**
 * User-visible status of Android's end-to-end silent FCM reachability probe (`mobile_fcm_probe_v0`,
 * `#proj-mobile:#379`) in Settings › Notifications.
 *
 * Always [None] when `mobile_fcm_probe_v0` is off, when the server does not support probing (404),
 * on non-Android hosts, on no-GMS devices, or whenever FCM is not confirmed unreachable.
 */
enum class RaftFcmProbeUiStatus {
    None,

    /**
     * Direct-install APK with GMS where `mobile_fcm_probe_v0` confirmed FCM unreachable on the
     * current network and automatic foreground-service fallback is keeping notifications active.
     */
    DirectAutoFallbackActive,

    /**
     * Google Play build with GMS where `mobile_fcm_probe_v0` confirmed FCM unreachable on the
     * current network and the user has not dismissed the notice for this network/episode.
     */
    PlayPushUnreachableNotice,
}

interface RaftBackgroundConnectionControls {
    /** True when this device cannot receive server push and the foreground-service fallback applies. */
    fun isOffered(): Boolean

    /**
     * True when this build is a direct-install Android APK whose manifest ships the background
     * connection service and Google Play services is available, so the user may opt in (default
     * off) to keeping the foreground connection service running when FCM is unreachable. Always
     * false on Google Play builds, no-GMS devices, iOS, and desktop.
     */
    fun canOverrideWithForegroundService(): Boolean = false

    /**
     * Current opt-in state for [canOverrideWithForegroundService]. Always false unless
     * [canOverrideWithForegroundService] is true and the user opted in.
     */
    val directGmsKeepAliveOverrideState: StateFlow<Boolean>
        get() = DefaultDirectGmsKeepAliveOverrideState

    /**
     * Enables or disables the direct-install GMS foreground-service override. Ignored (no-op)
     * when [canOverrideWithForegroundService] is false (including Google Play builds).
     */
    fun setDirectGmsKeepAliveOverrideEnabled(enabled: Boolean) {}

    /**
     * Current probed FCM reachability UI status (`mobile_fcm_probe_v0`). Always emits
     * [RaftFcmProbeUiStatus.None] when the flag is off, on no-GMS devices, or on non-Android hosts.
     */
    val fcmProbeUiStatusState: StateFlow<RaftFcmProbeUiStatus>
        get() = DefaultFcmProbeUiStatusState

    /**
     * Dismisses the Google Play unreachable-push notice for the current unreachable episode/network.
     * No-op when not applicable.
     */
    fun dismissPlayFcmUnreachableNotice() {}

    /**
     * True when this build declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and may launch the
     * direct battery-exemption prompt. Always false on Google Play builds.
     */
    fun canRequestBatteryOptimizationExemption(): Boolean = isOffered()

    /** Device-specific OEM background management guidance for this Android device. */
    fun whitelistGuide(): RaftBackgroundWhitelistGuide = RaftBackgroundWhitelistGuide.AppDetails

    /** True when the OS already exempts the app from battery optimization (or has no such setting). */
    fun isBatteryOptimizationExempt(): Boolean

    /**
     * [isBatteryOptimizationExempt] as state, re-read by the host when the app resumes (the system prompt
     * returns without a callback), so Settings never has to poll.
     */
    val batteryOptimizationExemptState: StateFlow<Boolean>

    /**
     * Opens the system prompt that exempts the app from battery optimization when
     * [canRequestBatteryOptimizationExemption] is true, falling back to system battery optimization
     * settings and then App Info. Only call on a user action.
     */
    fun requestBatteryOptimizationExemption()

    /**
     * Opens OEM auto-start / background management settings, falling back to system battery
     * optimization settings and then App Info. Safe on both direct and Google Play builds.
     * Only call on a user action.
     */
    fun openVendorBackgroundSettings() = requestBatteryOptimizationExemption()
}

object RaftBackgroundConnectionControlsRegistry {
    @Volatile
    var controls: RaftBackgroundConnectionControls? = null

    /** The installed Android host controls (any Android build), or null on iOS / desktop. */
    fun installedControls(): RaftBackgroundConnectionControls? = controls

    /** The installed controls when the fallback applies on this device, otherwise null. */
    fun offeredControls(): RaftBackgroundConnectionControls? =
        controls?.takeIf { runCatching { it.isOffered() }.getOrDefault(false) }
}

private val DefaultDirectGmsKeepAliveOverrideState: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
private val DefaultFcmProbeUiStatusState: StateFlow<RaftFcmProbeUiStatus> =
    MutableStateFlow(RaftFcmProbeUiStatus.None).asStateFlow()

/**
 * Lightweight host listener bridged from `:shared` / `:shared-data` to Android's FCM reachability
 * probe controller (`mobile_fcm_probe_v0`, `#proj-mobile:#379`). Null on iOS, desktop, and tests
 * that do not install the Android probe controller.
 */
interface RaftFcmProbeHostListener {
    fun onFeatureFlagChanged()
    fun onPushRegistrationReady()
    fun onSignedOut()
}

object RaftFcmProbeHost {
    @Volatile
    var listener: RaftFcmProbeHostListener? = null

    fun onFeatureFlagChanged() {
        runCatching { listener?.onFeatureFlagChanged() }
    }

    fun onPushRegistrationReady() {
        runCatching { listener?.onPushRegistrationReady() }
    }

    fun onSignedOut() {
        runCatching { listener?.onSignedOut() }
    }
}
