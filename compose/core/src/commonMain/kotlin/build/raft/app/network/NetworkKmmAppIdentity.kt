package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import com.tencent.kmm.network.export.NetworkAppIdentity
import com.tencent.kmm.network.export.NetworkUserAgent

/** Installs the host application's identity before the first NetworkKMM request. */
@RaftCoreInternalApi
fun installNetworkKmmAppIdentity(identity: NetworkAppIdentity?) {
    NetworkUserAgent.appIdentity = identity
}

@RaftCoreInternalApi
expect fun platformNetworkKmmAppIdentity(): NetworkAppIdentity?

@RaftCoreInternalApi
fun installPlatformNetworkKmmAppIdentity() {
    installNetworkKmmAppIdentity(platformNetworkKmmAppIdentity())
}
