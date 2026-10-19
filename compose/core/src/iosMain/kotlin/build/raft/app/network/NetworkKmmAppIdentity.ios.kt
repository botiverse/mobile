package build.raft.app.network

import com.tencent.kmm.network.export.NetworkAppIdentity
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.Foundation.NSBundle

@OptIn(ExperimentalNativeApi::class)
actual fun platformNetworkKmmAppIdentity(): NetworkAppIdentity? {
    val info = NSBundle.mainBundle.infoDictionary ?: return null
    val name = (info["CFBundleDisplayName"] as? String)
        ?.takeIf { it.isNotBlank() }
        ?: (info["CFBundleName"] as? String)?.takeIf { it.isNotBlank() }
        ?: "Raft"
    val version = (info["CFBundleShortVersionString"] as? String).orEmpty()
    val build = (info["CFBundleVersion"] as? String).orEmpty()
    val buildType = if (Platform.isDebugBinary) "debug" else "release"
    return NetworkAppIdentity(name, version, build, buildType)
}
