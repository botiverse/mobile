package build.raft.app.network

internal actual fun slockNetworkTransportPlatformPolicy(): SlockNetworkTransportPlatformPolicy =
    SlockNetworkTransportPlatformPolicy(
        platformLabel = "iOS",
        defaultHttp3Enabled = false,
        curlRequired = false,
        appOwnedTrustRequired = true,
        readyDetail =
            "Curl and the pinned CA bundle are ready. This lane is direct and does not inherit the iOS system PAC."
    )

internal actual fun slockApplyPlatformHttp3Preference(
    enabled: Boolean,
    generation: Long
): Boolean = true
