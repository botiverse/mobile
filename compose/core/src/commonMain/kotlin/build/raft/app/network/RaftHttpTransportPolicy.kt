package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile

/**
 * Local Developer setting that routes app HTTP back through NetworkKMM's curl engine. Stored as
 * "curl"; anything else (or absent) means the default Ktor transport. Device-wide like the other
 * local preferences: it survives logout and server switches. It exists only to compare the two
 * stacks and to roll back on a device; curl and this switch are removed in a follow-up.
 */
const val HTTP_TRANSPORT_OVERRIDE_PREFERENCE_KEY: String = "network:http_transport_override"

private const val CURL_OVERRIDE_VALUE = "curl"

/** The HTTP stack that carries new app requests. */
enum class RaftHttpTransport(val diagnosticsValue: String) {
    /** Ktor: OkHttp on Android, NSURLSession (Darwin engine) on iOS. The default. */
    Ktor("ktor"),

    /** NetworkKMM curl over HTTP/2. Developer override only. */
    Curl("curl")
}

/**
 * artin 2026-10-23: the app's HTTP goes through Ktor (OkHttp on Android, NSURLSession on iOS).
 * Curl stays reachable only through the Developer override, read live by every new request, so a
 * flip applies to the next request without a restart. Requests already in flight keep their stack.
 *
 * Replaces the Settings "Enable HTTP/3" switch: neither OkHttp nor the Ktor Darwin engine speaks
 * HTTP/3, and the curl override always runs curl over HTTP/2.
 */
object RaftHttpTransportPolicy {
    @Volatile
    private var overrideReader: (() -> String?)? = null

    @Volatile
    private var overrideWriter: ((String?) -> Unit)? = null

    /** [read] returns the raw value under [HTTP_TRANSPORT_OVERRIDE_PREFERENCE_KEY]; [write] stores it (null clears). */
    fun bindOverride(read: () -> String?, write: (String?) -> Unit) {
        overrideReader = read
        overrideWriter = write
    }

    /** True only when the Developer override asks for curl. Unbound or failing reads mean Ktor. */
    fun curlOverrideRequested(): Boolean {
        val read = overrideReader ?: return false
        return parseOverride(runCatching { read() }.getOrNull()) == RaftHttpTransport.Curl
    }

    /** The transport the policy asks for; [RaftNetworkTransportController] may still fall back from curl. */
    fun requested(): RaftHttpTransport =
        if (curlOverrideRequested()) RaftHttpTransport.Curl else RaftHttpTransport.Ktor

    /** Stores the Developer override. Returns false when no store is bound or the write failed. */
    fun setCurlOverride(enabled: Boolean): Boolean {
        val write = overrideWriter ?: return false
        return runCatching { write(if (enabled) CURL_OVERRIDE_VALUE else null) }.isSuccess
    }

    fun parseOverride(stored: String?): RaftHttpTransport =
        if (stored?.trim()?.lowercase() == CURL_OVERRIDE_VALUE) RaftHttpTransport.Curl else RaftHttpTransport.Ktor

    @RaftCoreInternalApi
    fun resetForTest() {
        overrideReader = null
        overrideWriter = null
    }
}
