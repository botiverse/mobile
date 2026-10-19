package build.raft.app.network

import com.tencent.kmm.network.export.NetworkAppIdentity
import com.tencent.kmm.network.export.NetworkUserAgent

/** Installs the host application's identity before the first NetworkKMM request. */
fun installNetworkKmmAppIdentity(identity: NetworkAppIdentity?) {
    NetworkUserAgent.appIdentity = identity
}

expect fun platformNetworkKmmAppIdentity(): NetworkAppIdentity?

fun installPlatformNetworkKmmAppIdentity() {
    installNetworkKmmAppIdentity(platformNetworkKmmAppIdentity())
}
