package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi
actual fun raftNetworkTransportPlatformPolicy(): RaftNetworkTransportPlatformPolicy =
    RaftNetworkTransportPlatformPolicy(
        platformLabel = "iOS",
        ktorEngineLabel = "NSURLSession",
        appOwnedTrustRequired = true,
        readyDetail =
            "Curl and the pinned CA bundle are ready. This lane is direct and does not inherit the iOS system PAC."
    )
