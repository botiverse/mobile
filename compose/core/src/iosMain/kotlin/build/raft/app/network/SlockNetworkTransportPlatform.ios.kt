package build.raft.app.network

import build.raft.app.SlockCoreInternalApi

@SlockCoreInternalApi
actual fun slockNetworkTransportPlatformPolicy(): SlockNetworkTransportPlatformPolicy =
    SlockNetworkTransportPlatformPolicy(
        platformLabel = "iOS",
        curlRequired = false,
        appOwnedTrustRequired = true,
        readyDetail =
            "Curl and the pinned CA bundle are ready. This lane is direct and does not inherit the iOS system PAC."
    )

@SlockCoreInternalApi
actual fun slockApplyPlatformHttp3Preference(
    enabled: Boolean,
    generation: Long
): Boolean = true
