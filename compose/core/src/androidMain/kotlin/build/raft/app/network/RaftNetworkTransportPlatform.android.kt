package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

@RaftCoreInternalApi
actual fun raftNetworkTransportPlatformPolicy(): RaftNetworkTransportPlatformPolicy =
    RaftNetworkTransportPlatformPolicy(
        platformLabel = "Android",
        ktorEngineLabel = "OkHttp",
        appOwnedTrustRequired = true,
        readyDetail =
            "Curl and the pinned CA bundle are ready. Android system proxy resolution is evaluated for each request."
    )
