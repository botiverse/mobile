package build.raft.app.network

import build.raft.kuiklybase.datetime.SystemTimeZone
import com.tencent.kmm.network.export.NetworkCurlDohFallbackProvider
import com.tencent.kmm.network.export.VBTransportCurl
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Task #153: process-wide DNS-over-HTTPS fallback for the curl transport, gated by the
 * `mobile_doh_fallback_v0` server flag. Default ON (artin 2026-10-10); the server turns it off.
 *
 * DoH is on while any active server selection has the flag enabled, and also while no server is
 * selected yet (sign-in and the first requests of a cold start). [isEnabledForServer] is the
 * feature-flag store's read: before an online evaluation lands (a cold start during a DNS outage,
 * exactly when DoH matters) the stored last-known value decides, no decision at all reads ON, and
 * internal Android builds can force it through Local Feature Overrides. NetworkKMM only ever falls back to the built-in
 * providers after the system resolver failed; it never replaces a successful system resolution.
 *
 * Provider order follows the device's system time zone (artin 2026-10-10): mainland China zones try
 * AliDNS first, every other zone Cloudflare first; the other provider is still tried next. A DoH
 * resolver answers geo-DNS from its own location, so the nearer one gets the nearer CDN edge, and
 * the first provider is the one most likely to answer at all on that network.
 */
internal class SlockDohFallbackPolicy(
    private val applyProviders: (List<NetworkCurlDohFallbackProvider>) -> Unit =
        VBTransportCurl::setDohFallbackProviders,
    private val systemZoneId: () -> String? = { runCatching { SystemTimeZone.snapshot().zoneId }.getOrNull() },
) {
    private val lock = SynchronizedObject()
    private var isEnabledForServer: (String) -> Boolean = { false }
    private val activeServerByScope = mutableMapOf<String, String>()
    private var applied: List<NetworkCurlDohFallbackProvider>? = null

    fun bind(isEnabledForServer: (String) -> Boolean): String? {
        synchronized(lock) { this.isEnabledForServer = isEnabledForServer }
        return reevaluate("bind")
    }

    fun updateActiveSelection(scopeId: String, serverId: String?): String? {
        val changed = synchronized(lock) {
            val scope = scopeId.trim()
            if (scope.isBlank()) return null
            val server = serverId?.trim()?.takeIf(String::isNotBlank)
            val previous = if (server == null) activeServerByScope.remove(scope) else activeServerByScope.put(scope, server)
            previous != server
        }
        return if (changed) reevaluate("selection") else null
    }

    /** Re-reads the flag for every active server; returns a log line when the setting changed. */
    fun reevaluate(source: String): String? = synchronized(lock) {
        val activeServers = activeServerByScope.values.toSet()
        // A failing read is "no decision": the flag is default-on.
        val enabled = activeServers.isEmpty() || activeServers.any { serverId ->
            runCatching { isEnabledForServer(serverId) }.getOrDefault(true)
        }
        val zoneId = runCatching { systemZoneId() }.getOrNull()
        val providers = if (enabled) providersFor(zoneId) else emptyList()
        if (applied == providers) return@synchronized null
        applyProviders(providers)
        applied = providers
        "doh_fallback source=$source enabled=$enabled zone=${zoneId.orEmpty()} providers=" +
            (if (enabled) providers.joinToString(",") { it.name.lowercase() } else "none")
    }

    companion object {
        private val MAINLAND_CHINA_ZONES =
            setOf("Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin", "Asia/Urumqi", "Asia/Kashgar", "PRC")

        /** Order matters: the first provider is tried first. Unknown zone keeps AliDNS first. */
        fun providersFor(zoneId: String?): List<NetworkCurlDohFallbackProvider> =
            if (zoneId.isNullOrBlank() || zoneId.trim() in MAINLAND_CHINA_ZONES) {
                listOf(NetworkCurlDohFallbackProvider.ALIDNS, NetworkCurlDohFallbackProvider.CLOUDFLARE)
            } else {
                listOf(NetworkCurlDohFallbackProvider.CLOUDFLARE, NetworkCurlDohFallbackProvider.ALIDNS)
            }
    }
}
