package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

@Volatile
private var installedDevice: RaftUserAgentDevice? = null

/** Main thread, at startup: iPhone or iPad for the User-Agent. */
fun installIosRaftUserAgentDevice() {
    installedDevice = iosUserAgentDevice()
}

private fun iosUserAgentDevice(): RaftUserAgentDevice =
    if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
        RaftUserAgentDevice.IPad
    } else {
        RaftUserAgentDevice.IPhone
    }

@RaftCoreInternalApi
actual fun platformRaftUserAgentDevice(): RaftUserAgentDevice? =
    installedDevice ?: iosUserAgentDevice().also { installedDevice = it }
