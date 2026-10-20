package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import com.tencent.kmm.network.export.NetworkAppIdentity

private var installedIdentity: NetworkAppIdentity? = null

fun installAndroidNetworkKmmAppIdentity(
    name: String,
    version: String,
    buildNumber: String,
    buildType: String
) {
    installedIdentity = NetworkAppIdentity(name, version, buildNumber, buildType)
}

@RaftCoreInternalApi
actual fun platformNetworkKmmAppIdentity(): NetworkAppIdentity? = installedIdentity
