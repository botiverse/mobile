package build.raft.app.shared

import java.util.concurrent.atomic.AtomicBoolean

internal const val HANDS_DELTA_ALPHA_APPLICATION_ID = "build.raft.app.alpha"
internal const val HANDS_DELTA_ALPHA_APP_SLUG = "raft-android-alpha-delta"
internal const val HANDS_DELTA_ALPHA_CHANNEL = "alpha-delta"
internal const val HANDS_UPDATE_MODE_ALPHA_DELTA = "alpha_delta"
internal const val HANDS_UPDATE_MODE_FULL_APK = "full_apk"

internal data class HandsUpdateRoute(
    val mode: String,
    val appSlug: String,
    val channel: String,
    val deltaEnabled: Boolean
)

internal fun resolveHandsUpdateRoute(
    alphaDeltaEnabled: Boolean,
    defaultAppSlug: String,
    defaultChannel: String,
    deltaAppSlug: String,
    deltaChannel: String
): HandsUpdateRoute =
    if (alphaDeltaEnabled) {
        HandsUpdateRoute(
            mode = HANDS_UPDATE_MODE_ALPHA_DELTA,
            appSlug = deltaAppSlug,
            channel = deltaChannel,
            deltaEnabled = true
        )
    } else {
        HandsUpdateRoute(
            mode = HANDS_UPDATE_MODE_FULL_APK,
            appSlug = defaultAppSlug,
            channel = defaultChannel,
            deltaEnabled = false
        )
    }

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
