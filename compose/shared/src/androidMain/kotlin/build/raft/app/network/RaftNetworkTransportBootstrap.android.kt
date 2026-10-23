package build.raft.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
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
import java.util.concurrent.atomic.AtomicBoolean

private const val RAFT_NETWORK_CA_ASSET = "network/cacert-2026-05-14.pem"

/** Stages the signed app asset before any NetworkClient can select curl. */
fun installAndroidRaftNetworkTransportAtStartup(context: Context): String {
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
                    appContext.assets.open(RAFT_NETWORK_CA_ASSET).use { input ->
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
    val environmentObserver = AndroidCurlNetworkEnvironmentObserver.install(appContext)
    // Task #154: the Android realtime host can carry Socket.IO over curl, so Settings shows its switch.
    if (status.configured && native.linked) RaftCurlSocketIoPolicy.markPlatformSupported()
    // Curl now serves only the Developer override (and realtime curl Socket.IO while that switch exists).
    return "networkkmm_curl_bootstrap platform=android role=override_only configured=${status.configured} " +
        "failureReason=${status.failureReason} nativeLinked=${native.linked} " +
        "http3FeatureAvailable=${native.http3FeatureAvailable} " +
        "supportedAbis=$supportedAbis primaryAbi=$primaryAbi " +
        "runtimePackagedAbis=arm64-v8a,x86_64 " +
        "caVersion=${NetworkCurlCaBundleManifest.VERSION} environmentObserver=$environmentObserver"
}

private object AndroidCurlNetworkEnvironmentObserver {
    private val installed = AtomicBoolean(false)

    fun install(context: Context): String {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unavailable"
        if (!installed.compareAndSet(false, true)) return "ready"
        return runCatching {
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        publishCurrentSafely(connectivityManager)
                    }

                    override fun onLost(network: Network) {
                        // Clear a latch even if activeNetwork has not switched by
                        // the time this callback is delivered.
                        runCatching {
                            VBTransportCurl.updateNetworkEnvironment(
                                "android-lost:${androidCurlNetworkEnvironmentFingerprint(network.toString())}"
                            )
                        }
                        publishCurrentSafely(connectivityManager)
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        publishCurrentSafely(connectivityManager)
                    }

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties
                    ) {
                        publishCurrentSafely(connectivityManager)
                    }
                }
            )
            publishCurrentSafely(connectivityManager)
            "ready"
        }.getOrElse {
            installed.set(false)
            "registration_failed"
        }
    }

    private fun publishCurrentSafely(connectivityManager: ConnectivityManager) {
        runCatching {
            VBTransportCurl.updateNetworkEnvironment(currentIdentity(connectivityManager))
        }
    }

    internal fun currentIdentity(connectivityManager: ConnectivityManager): String {
        val network = connectivityManager.activeNetwork ?: return "network=none"
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProperties = connectivityManager.getLinkProperties(network)
        val proxy = linkProperties?.httpProxy
        return androidCurlNetworkEnvironmentIdentity(
            network = network.toString(),
            wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            ethernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
            vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            interfaceName = linkProperties?.interfaceName,
            proxyHost = proxy?.host,
            proxyPort = proxy?.port,
            proxyPacUrl = proxy?.pacFileUrl?.toString()
        )
    }
}

internal fun androidCurlNetworkEnvironmentIdentity(
    network: String,
    wifi: Boolean,
    cellular: Boolean,
    ethernet: Boolean,
    vpn: Boolean,
    interfaceName: String?,
    proxyHost: String?,
    proxyPort: Int?,
    proxyPacUrl: String?
): String {
    val transports = buildList {
        if (wifi) add("wifi")
        if (cellular) add("cellular")
        if (ethernet) add("ethernet")
        if (vpn) add("vpn")
    }.sorted().joinToString(",").ifBlank { "other" }
    val proxyEndpoint = listOfNotNull(
        proxyHost?.takeIf(String::isNotBlank),
        proxyPort?.takeIf { it > 0 }?.toString()
    ).joinToString(":").ifBlank { "none" }
    val canonical = "network=$network|transports=$transports|" +
        "interface=${interfaceName ?: "none"}|proxy=$proxyEndpoint|pac=${proxyPacUrl ?: "none"}"
    return "android:${androidCurlNetworkEnvironmentFingerprint(canonical)}"
}

internal fun androidCurlNetworkEnvironmentFingerprint(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .take(16)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

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
