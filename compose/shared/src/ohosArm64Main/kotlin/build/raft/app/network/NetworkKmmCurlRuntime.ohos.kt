package build.raft.app.network

import com.tencent.kmm.network.export.NetworkCurlCaBundleManifest
import com.tencent.kmm.network.export.NetworkCurlProxyConfiguration
import com.tencent.kmm.network.export.NetworkCurlRuntimeConfiguration
import com.tencent.kmm.network.export.NetworkCurlTrustStore
import com.tencent.kmm.network.export.VBTransportCurl
import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI
import kotlin.concurrent.Volatile

private const val SlockOhosCurlRuntimeService = "slockOhosCurlRuntimeConfig"

@ServiceConsumer(name = SlockOhosCurlRuntimeService)
interface SlockOhosCurlRuntimeConfig {
    fun caBundlePath(mode: String): String
}

@Volatile
private var curlRuntimeInstallAttempted = false

/**
 * task #252: the OHOS transport runs on libcurl, which refuses every request
 * until the app installs a verified CA bundle and an explicit proxy decision.
 *
 * The bundle ships in the HAP's resfile (package-managed, read-only, same
 * sandbox path in debug and release), the host exposes its absolute path via
 * KNOI, and configure() verifies the file's SHA-256 against the pinned
 * NetworkCurlCaBundleManifest on every install attempt — no debug-only
 * branch. Proxy scope is explicitly DIRECT: the OHOS system proxy is NOT
 * consulted (follow-up task if needed); NetworkKMM's per-request preparation
 * re-applies CA/proxy onto every curl handle for both execute and stream, so
 * a configured state can never outlive a valid runtime.
 *
 * Idempotent and cheap once configured; on failure it logs a structured
 * reason and retries on the next request while NetworkKMM keeps failing
 * closed.
 */
internal actual fun ensurePlatformCurlRuntimeInstalled(warningLogger: (String) -> Unit): Boolean {
    if (VBTransportCurl.configured) return true
    val mode = if (curlRuntimeInstallAttempted) "retry" else "initial"
    curlRuntimeInstallAttempted = true
    val path =
        runCatching {
            KNOI.get<SlockOhosCurlRuntimeConfig>(SlockOhosCurlRuntimeService).caBundlePath(mode)
        }.getOrElse { error ->
            warningLogger(
                "slock_curl_runtime_service_unavailable mode=$mode message=${error.message.orEmpty()}"
            )
            return false
        }
    if (path.isBlank()) {
        warningLogger("slock_curl_runtime_ca_bundle_unavailable mode=$mode")
        return false
    }
    val status =
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore =
                    NetworkCurlTrustStore(
                        path = path,
                        sha256 = NetworkCurlCaBundleManifest.SHA256
                    ),
                proxy = NetworkCurlProxyConfiguration.direct()
            )
        )
    if (status.configured) {
        warningLogger(
            "slock_curl_runtime_configured mode=$mode caVersion=${NetworkCurlCaBundleManifest.VERSION}"
        )
    } else {
        warningLogger(
            "slock_curl_runtime_configure_failed mode=$mode " +
                "reason=${status.failureReason} detail=${status.detail.orEmpty()}"
        )
    }
    return status.configured
}
