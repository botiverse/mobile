package build.raft.app.core.data.realtime

import build.raft.app.network.SlockDohEndpoint
import build.raft.app.network.slockActiveDohEndpoints
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * Task #153: DNS for the realtime socket (socket.io-java on OkHttp), with the same DNS-over-HTTPS
 * fallback NetworkKMM gives the API. The system resolver always goes first; only when it cannot
 * resolve the host ([UnknownHostException]) and the fallback is on are the built-in DoH endpoints
 * tried in order. A successful system resolution is never replaced. The DoH hostnames are pinned to
 * their built-in addresses (no system lookup), and TLS to both the DoH server and the realtime host
 * is verified as usual.
 */
internal class SlockRealtimeDns(
    private val system: Dns = Dns.SYSTEM,
    private val activeEndpoints: () -> List<SlockDohEndpoint> = ::slockActiveDohEndpoints,
    private val dohLookup: (SlockDohEndpoint, String) -> List<InetAddress> = ::lookupThroughDoh,
    private val log: (String) -> Unit = { build.raft.app.core.data.diagnostics.SlockAppLog.record("SlockRealtime", it) },
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val systemFailure =
            try {
                return system.lookup(hostname)
            } catch (failure: UnknownHostException) {
                failure
            }
        val endpoints = activeEndpoints()
        if (endpoints.isEmpty()) throw systemFailure
        for (endpoint in endpoints) {
            val addresses = runCatching { dohLookup(endpoint, hostname) }.getOrNull().orEmpty()
            if (addresses.isNotEmpty()) {
                log("realtime_dns doh_fallback_success provider=${endpoint.name} addresses=${addresses.size}")
                return addresses
            }
        }
        log("realtime_dns doh_fallback_failure providers=${endpoints.joinToString(",") { it.name }}")
        throw systemFailure
    }

    companion object {
        private val dohClients = ConcurrentHashMap<String, DnsOverHttps>()

        private val bootstrapClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        }

        private fun lookupThroughDoh(endpoint: SlockDohEndpoint, hostname: String): List<InetAddress> =
            dohClients.getOrPut(endpoint.url) {
                DnsOverHttps.Builder()
                    .client(bootstrapClient)
                    .url(endpoint.url.toHttpUrl())
                    // Pinned: the DoH hostname never goes through the failing system resolver.
                    .bootstrapDnsHosts(endpoint.addresses.map(InetAddress::getByName))
                    .build()
            }.lookup(hostname)
    }
}

/** OkHttp client for the realtime socket; the same defaults socket.io-java uses, plus [SlockRealtimeDns]. */
internal val slockRealtimeOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .readTimeout(1, TimeUnit.MINUTES)
        .dns(SlockRealtimeDns())
        .build()
}
