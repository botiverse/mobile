package build.raft.app.network

import com.tencent.kmm.network.export.NetworkAppIdentity
import com.tencent.kmm.network.export.NetworkUserAgent

/** Installs the host application's identity before the first NetworkKMM request. */
internal fun installNetworkKmmAppIdentity(identity: NetworkAppIdentity?) {
    NetworkUserAgent.appIdentity = identity
}

internal expect fun platformNetworkKmmAppIdentity(): NetworkAppIdentity?

internal fun installPlatformNetworkKmmAppIdentity() {
    installNetworkKmmAppIdentity(platformNetworkKmmAppIdentity())
}
