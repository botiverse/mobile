package build.raft.app.shared

import android.app.Application
import build.raft.app.data.RaftInstallChannelUpdates

/**
 * Build type `debug` (every build type except `play` has this same copy): no Google Play channel and no
 * Play In-App Update library. Hands is the channel of the APK builds ([RaftAndroidInstallChannels]).
 */
internal object RaftAndroidPlayInstallChannel {
    @Suppress("UNUSED_PARAMETER")
    fun create(application: Application): RaftInstallChannelUpdates? = null
}
