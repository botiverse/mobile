package build.raft.app.network

import com.tencent.kmm.network.export.NetworkCurlCaBundleManifest
import com.tencent.kmm.network.export.NetworkCurlProxyConfiguration
import com.tencent.kmm.network.export.NetworkCurlRuntimeConfiguration
import com.tencent.kmm.network.export.NetworkCurlTrustStore
import com.tencent.kmm.network.export.VBTransportCurl

/** Called by the iOS host with the signed bundle resource's absolute path. */
fun installIosSlockNetworkTransportAtStartup(caBundlePath: String): String {
    val status =
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore =
                    NetworkCurlTrustStore(
                        path = caBundlePath,
                        sha256 = NetworkCurlCaBundleManifest.SHA256
                    ),
                // NetworkKMM currently has no CFNetwork/PAC resolver. Keep this
                // cohort explicitly direct and report that boundary in Settings.
                proxy = NetworkCurlProxyConfiguration.direct(),
                httpDnsEnabled = false,
                http3Enabled = false
            )
        )
    val native = VBTransportCurl.nativeStatus
    return "networkkmm_curl_bootstrap platform=ios configured=${status.configured} " +
        "failureReason=${status.failureReason} nativeLinked=${native.linked} " +
        "http3FeatureAvailable=${native.http3FeatureAvailable} caVersion=${NetworkCurlCaBundleManifest.VERSION} " +
        "proxyMode=direct"
}
