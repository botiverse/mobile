package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

@OptIn(ExperimentalNativeApi::class)
@RaftCoreInternalApi
actual fun platformRaftAppIdentity(): RaftAppIdentity? {
    val info = NSBundle.mainBundle.infoDictionary ?: return null
    val name = (info["CFBundleDisplayName"] as? String)
        ?.takeIf { it.isNotBlank() }
        ?: (info["CFBundleName"] as? String)?.takeIf { it.isNotBlank() }
        ?: "Raft"
    val version = (info["CFBundleShortVersionString"] as? String).orEmpty()
    val build = (info["CFBundleVersion"] as? String).orEmpty()
    val buildType = if (Platform.isDebugBinary) "debug" else "release"
    return RaftAppIdentity(name, version, build, buildType)
}

@RaftCoreInternalApi
actual fun platformRaftUserAgentPlatformName(): String = "iOS ${UIDevice.currentDevice.systemVersion}"
