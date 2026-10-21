package build.raft.app.network

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import build.raft.app.RaftCoreInternalApi

@Volatile
private var installedDevice: RaftUserAgentDevice? = null

/**
 * Records whether this device is a phone or a tablet for the User-Agent, from the smallest
 * screen width (kept current across configuration changes, e.g. a foldable opening).
 */
fun installAndroidRaftUserAgentDevice(context: Context) {
    val appContext = context.applicationContext ?: context
    installedDevice = raftAndroidUserAgentDevice(appContext.resources.configuration.smallestScreenWidthDp)
    appContext.registerComponentCallbacks(
        object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                installedDevice = raftAndroidUserAgentDevice(newConfig.smallestScreenWidthDp)
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        }
    )
}

@RaftCoreInternalApi
actual fun platformRaftUserAgentDevice(): RaftUserAgentDevice? = installedDevice
