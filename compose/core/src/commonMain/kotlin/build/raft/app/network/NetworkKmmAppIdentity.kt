package build.raft.app.network

import build.raft.app.SlockCoreInternalApi
import com.tencent.kmm.network.export.NetworkAppIdentity
import com.tencent.kmm.network.export.NetworkUserAgent

/** Installs the host application's identity before the first NetworkKMM request. */
@SlockCoreInternalApi
fun installNetworkKmmAppIdentity(identity: NetworkAppIdentity?) {
    NetworkUserAgent.appIdentity = identity
}

@SlockCoreInternalApi
expect fun platformNetworkKmmAppIdentity(): NetworkAppIdentity?

@SlockCoreInternalApi
fun installPlatformNetworkKmmAppIdentity() {
    installNetworkKmmAppIdentity(platformNetworkKmmAppIdentity())
}
