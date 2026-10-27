package build.raft.app.data

import kotlin.concurrent.Volatile

/**
 * Raft's own app hosts from the current environment's client config (`GET /api/public/client-config`
 * `appHosts`, [SharedClientConfigStore]), for readers that run without the shared stores (link
 * ingress, Markdown permalinks). Empty until an answer (or this environment's cached one) is known;
 * the canonical host is never needed here, as every reader already accepts it.
 *
 * Message-permalink readers take the UNION of their own hosts and these (as the web does): this
 * list only ever adds hosts to theirs, it never replaces them.
 */
object RaftClientConfigAppHosts {
    @Volatile
    private var hosts: Set<String> = emptySet()

    val current: Set<String>
        get() = hosts

    internal fun publish(appHosts: Collection<String>) {
        hosts = appHosts.mapTo(linkedSetOf()) { it.trim().lowercase().removeSuffix(".") }.filterTo(linkedSetOf()) { it.isNotEmpty() }
    }
}
