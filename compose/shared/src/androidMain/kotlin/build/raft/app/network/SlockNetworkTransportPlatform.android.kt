package build.raft.app.network

internal actual fun slockNetworkTransportPlatformPolicy(): SlockNetworkTransportPlatformPolicy =
    SlockNetworkTransportPlatformPolicy(
        platformLabel = "Android",
        defaultHttp3Enabled = false,
        curlRequired = false,
        appOwnedTrustRequired = true,
        readyDetail =
            "Curl and the pinned CA bundle are ready. Android system proxy resolution is evaluated for each request."
    )

internal actual fun slockApplyPlatformHttp3Preference(
    enabled: Boolean,
    generation: Long
): Boolean = true

// Task #153: no platform transport outside NetworkKMM needs the DoH provider list here.
internal actual fun slockPublishPlatformDohProviders(providerNames: List<String>): Boolean = true
