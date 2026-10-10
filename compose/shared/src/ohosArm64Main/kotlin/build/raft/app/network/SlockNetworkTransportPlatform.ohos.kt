package build.raft.app.network

import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI

private const val SlockOhosImageTransportPolicyService = "slockOhosImageTransportPolicy"
private const val SlockOhosRealtimeDohPolicyService = "slockOhosRealtimeDohPolicy"

@ServiceConsumer(name = SlockOhosImageTransportPolicyService)
interface SlockOhosImageTransportPolicySink {
    fun setHttp3Enabled(enabled: Boolean): Boolean
}

internal actual fun slockNetworkTransportPlatformPolicy(): SlockNetworkTransportPlatformPolicy =
    SlockNetworkTransportPlatformPolicy(
        platformLabel = "OHOS",
        defaultHttp3Enabled = false,
        curlRequired = true,
        appOwnedTrustRequired = false,
        readyDetail =
            "OHOS always uses curl. Remote transport policy controls whether new requests prefer HTTP/3; fallback remains HTTP/2 or HTTP/1.1."
    )

internal actual fun slockApplyPlatformHttp3Preference(
    enabled: Boolean,
    generation: Long
): Boolean =
    runCatching {
        KNOI.get<SlockOhosImageTransportPolicySink>(SlockOhosImageTransportPolicyService)
            .setHttp3Enabled(enabled)
    }.getOrDefault(false)

@ServiceConsumer(name = SlockOhosRealtimeDohPolicyService)
interface SlockOhosRealtimeDohPolicySink {
    /** Comma-separated provider names in order ("alidns,cloudflare"); empty = off. */
    fun setDohProviders(providerNames: String): Boolean
}

// Task #153: the ArkTS realtime socket (@ohos.net.webSocket) applies the same DoH switch.
internal actual fun slockPublishPlatformDohProviders(providerNames: List<String>): Boolean =
    runCatching {
        KNOI.get<SlockOhosRealtimeDohPolicySink>(SlockOhosRealtimeDohPolicyService)
            .setDohProviders(providerNames.joinToString(","))
    }.getOrDefault(false)
