package build.raft.app.shared

import java.util.concurrent.atomic.AtomicBoolean

internal const val HANDS_DELTA_ALPHA_APPLICATION_ID = "build.raft.app.alpha"
internal const val HANDS_DELTA_ALPHA_APP_SLUG = "raft-android-alpha-delta"
internal const val HANDS_DELTA_ALPHA_CHANNEL = "alpha-delta"

internal class HandsDeltaUpdateCallOwner {
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    suspend fun <T : Any> runIfEligible(
        eligible: Boolean,
        block: suspend () -> T
    ): T? {
        if (!eligible || !running.compareAndSet(false, true)) return null
        return try {
            block()
        } finally {
            running.set(false)
        }
    }
}

internal fun handsDeltaUpdateAllowed(
    configuredEnabled: Boolean,
    buildApplicationId: String,
    runtimePackageName: String,
    appSlug: String,
    channel: String
): Boolean =
    configuredEnabled &&
        buildApplicationId == HANDS_DELTA_ALPHA_APPLICATION_ID &&
        runtimePackageName == HANDS_DELTA_ALPHA_APPLICATION_ID &&
        appSlug == HANDS_DELTA_ALPHA_APP_SLUG &&
        channel == HANDS_DELTA_ALPHA_CHANNEL
