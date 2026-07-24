package build.raft.app.network

import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI

private const val SlockOhosImageTransportPolicyService = "slockOhosImageTransportPolicy"

@ServiceConsumer(name = SlockOhosImageTransportPolicyService)
interface SlockOhosImageTransportPolicySink {
    fun setHttp3Enabled(enabled: Boolean): Boolean
}

internal actual fun slockNetworkTransportPlatformPolicy(): SlockNetworkTransportPlatformPolicy =
    SlockNetworkTransportPlatformPolicy(
        platformLabel = "OHOS",
        defaultHttp3Enabled = true,
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
