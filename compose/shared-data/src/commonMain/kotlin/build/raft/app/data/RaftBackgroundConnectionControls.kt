package build.raft.app.data

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.StateFlow

/**
 * Unified Android host controls for background keep-alive in Settings › Notifications.
 *
 * Only Android installs this contract; iOS and desktop leave
 * [RaftBackgroundConnectionControlsRegistry.controls] null and render no Android keep-alive rows.
 *
 * - On Android without Google Play services, [isOffered] is true (the foreground-service fallback
 *   applies).
 * - On direct/non-Play builds whose manifest declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
 *   [canRequestBatteryOptimizationExemption] is true and [requestBatteryOptimizationExemption]
 *   opens the direct exemption prompt (falling back to system battery optimization settings and
 *   then App Info).
 * - On Google Play builds (where `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is removed by manifest
 *   merge), [canRequestBatteryOptimizationExemption] is false and [openVendorBackgroundSettings]
 *   routes through OEM auto-start / background management settings -> system battery optimization
 *   settings -> App Info without ever invoking `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
 */
interface RaftBackgroundConnectionControls {
    /** True when this device cannot receive server push and the foreground-service fallback applies. */
    fun isOffered(): Boolean

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
