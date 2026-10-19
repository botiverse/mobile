package build.raft.app.network

import build.raft.app.SlockCoreInternalApi

@SlockCoreInternalApi
actual fun slockNetworkTransportPlatformPolicy(): SlockNetworkTransportPlatformPolicy =
    SlockNetworkTransportPlatformPolicy(
        platformLabel = "Android",
        curlRequired = false,
        appOwnedTrustRequired = true,
        readyDetail =
            "Curl and the pinned CA bundle are ready. Android system proxy resolution is evaluated for each request."
    )

@SlockCoreInternalApi
actual fun slockApplyPlatformHttp3Preference(
    enabled: Boolean,
    generation: Long
): Boolean = true
