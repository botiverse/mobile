package build.raft.app.data

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.StateFlow

/**
 * Host controls for the "keep connection in background" fallback.
 *
 * Only an Android host without Google Play services installs these: there FCM cannot deliver, so the
 * only way to keep posting message notifications is to keep the realtime connection alive from a
 * foreground service. Every other host (Android with Play services, iOS, HarmonyOS) leaves the
 * registry empty and Settings keeps its ordinary background realtime switch.
 */
interface RaftBackgroundConnectionControls {
    /** True when this device cannot receive server push and the foreground-service fallback applies. */
    fun isOffered(): Boolean

    /** True when the OS already exempts the app from battery optimization (or has no such setting). */
    fun isBatteryOptimizationExempt(): Boolean

    /**
     * [isBatteryOptimizationExempt] as state, re-read by the host when the app resumes (the system prompt
     * returns without a callback), so Settings never has to poll.
     */
    val batteryOptimizationExemptState: StateFlow<Boolean>

    /** Opens the system prompt that exempts the app from battery optimization. Only call on a user action. */
    fun requestBatteryOptimizationExemption()
}

object RaftBackgroundConnectionControlsRegistry {
    @Volatile
    var controls: RaftBackgroundConnectionControls? = null

    /** The installed controls when the fallback applies on this device, otherwise null. */
    fun offeredControls(): RaftBackgroundConnectionControls? =
        controls?.takeIf { runCatching { it.isOffered() }.getOrDefault(false) }
}
