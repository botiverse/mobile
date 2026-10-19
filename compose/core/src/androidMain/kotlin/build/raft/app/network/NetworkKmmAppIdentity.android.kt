package build.raft.app.network

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

actual fun platformNetworkKmmAppIdentity(): NetworkAppIdentity? = installedIdentity
