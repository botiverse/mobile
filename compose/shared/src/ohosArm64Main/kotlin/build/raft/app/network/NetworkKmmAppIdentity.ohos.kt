package build.raft.app.network

import build.raft.app.slockLogWarn
import com.tencent.kmm.network.export.NetworkAppIdentity
import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI

private const val SLOCK_OHOS_NETWORK_APP_IDENTITY_SERVICE = "slockOhosNetworkAppIdentity"

@ServiceConsumer(name = SLOCK_OHOS_NETWORK_APP_IDENTITY_SERVICE)
interface SlockOhosNetworkAppIdentityService {
    fun appName(): String
    fun versionName(): String
    fun versionCode(): String
    fun buildType(): String
}

internal actual fun platformNetworkKmmAppIdentity(): NetworkAppIdentity? {
    return runCatching {
        val service = KNOI.get<SlockOhosNetworkAppIdentityService>(SLOCK_OHOS_NETWORK_APP_IDENTITY_SERVICE)
        NetworkAppIdentity(service.appName(), service.versionName(), service.versionCode(), service.buildType())
    }.onFailure { error ->
        slockLogWarn(
            tag = "SlockNetworkKmm",
            message =
                "networkkmm_app_identity_unavailable platform=ohos " +
                    "reason=${error::class.simpleName ?: "unknown"}"
        )
    }.getOrNull()
}
