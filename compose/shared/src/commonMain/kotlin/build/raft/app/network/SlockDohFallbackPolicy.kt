package build.raft.app.network

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
 */
internal class SlockDohFallbackPolicy(
    private val applyProviders: (List<NetworkCurlDohFallbackProvider>) -> Unit =
        VBTransportCurl::setDohFallbackProviders,
) {
    private val lock = SynchronizedObject()
    private var isEnabledForServer: (String) -> Boolean = { false }
    private val activeServerByScope = mutableMapOf<String, String>()
    private var applied: Boolean? = null

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
        if (applied == enabled) return@synchronized null
        applyProviders(if (enabled) PROVIDERS else emptyList())
        applied = enabled
        "doh_fallback source=$source enabled=$enabled providers=" +
            (if (enabled) PROVIDERS.joinToString(",") { it.name.lowercase() } else "none")
    }

    companion object {
        /** Order matters: the first provider is tried first. */
        val PROVIDERS: List<NetworkCurlDohFallbackProvider> =
            listOf(NetworkCurlDohFallbackProvider.ALIDNS, NetworkCurlDohFallbackProvider.CLOUDFLARE)
    }
}
