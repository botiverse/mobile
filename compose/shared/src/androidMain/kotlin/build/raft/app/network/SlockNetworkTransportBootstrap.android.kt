package build.raft.app.network

import android.content.Context
import android.os.Build
import android.system.Os
import com.tencent.kmm.network.export.NetworkCurlCaBundleManifest
import com.tencent.kmm.network.export.NetworkCurlProxyConfiguration
import com.tencent.kmm.network.export.NetworkCurlRuntimeConfiguration
import com.tencent.kmm.network.export.NetworkCurlTrustStore
import com.tencent.kmm.network.export.VBTransportCurl
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

private const val SLOCK_NETWORK_CA_ASSET = "network/cacert-2026-05-14.pem"

/** Stages the signed app asset before any NetworkClient can select curl. */
fun installAndroidSlockNetworkTransportAtStartup(context: Context): String {
    val appContext = context.applicationContext
    val supportedAbis = Build.SUPPORTED_ABIS.joinToString(",").ifBlank { "unknown" }
    val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
    val target =
        File(appContext.filesDir, "network-curl/cacert-${NetworkCurlCaBundleManifest.VERSION}.pem")
    val staged =
        runCatching {
            target.parentFile?.mkdirs()
            if (target.isFile && target.sha256Hex() == NetworkCurlCaBundleManifest.SHA256) {
                target
            } else {
                val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
                try {
                    appContext.assets.open(SLOCK_NETWORK_CA_ASSET).use { input ->
                        FileOutputStream(temporary).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    check(temporary.sha256Hex() == NetworkCurlCaBundleManifest.SHA256) {
                        "pinned CA asset SHA-256 mismatch"
                    }
                    Os.rename(temporary.absolutePath, target.absolutePath)
                    target
                } finally {
                    temporary.delete()
                }
            }
        }

    val file = staged.getOrElse { error ->
        VBTransportCurl.clear()
        return "networkkmm_curl_bootstrap platform=android configured=false " +
            "reason=asset_stage_failed errorType=${error::class.simpleName ?: "unknown"} " +
            "supportedAbis=$supportedAbis primaryAbi=$primaryAbi " +
            "runtimePackagedAbis=arm64-v8a,x86_64"
    }
    val status =
        VBTransportCurl.configure(
            NetworkCurlRuntimeConfiguration(
                trustStore =
                    NetworkCurlTrustStore(
                        path = file.absolutePath,
                        sha256 = NetworkCurlCaBundleManifest.SHA256
                    ),
                proxy = NetworkCurlProxyConfiguration.androidSystem(),
                httpDnsEnabled = false,
                // Request-owned overrides drive the Settings toggle.
                http3Enabled = false
            )
        )
    val native = VBTransportCurl.nativeStatus
    return "networkkmm_curl_bootstrap platform=android configured=${status.configured} " +
        "failureReason=${status.failureReason} nativeLinked=${native.linked} " +
        "http3FeatureAvailable=${native.http3FeatureAvailable} " +
        "supportedAbis=$supportedAbis primaryAbi=$primaryAbi " +
        "runtimePackagedAbis=arm64-v8a,x86_64 " +
        "caVersion=${NetworkCurlCaBundleManifest.VERSION}"
}

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
