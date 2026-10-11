package build.raft.app.network

import kotlin.concurrent.Volatile

/**
 * Local preference behind the Settings switch "Enable HTTP/3". Stored as "true" / "false";
 * absent means the user never touched the switch and the server-sent transport variant decides.
 * Device-wide like the other local preferences: it survives logout and server switches.
 */
const val HTTP3_PREFERENCE_KEY: String = "network:http3_enabled"

/**
 * Raft task #3 (artin 2026-10-11): a Settings switch for HTTP/3 whose default comes from the
 * server.
 *
 * The server default already exists: the `mobile_curl_transport_v1` flag sends a transport
 * variant per server, and `curl_h3` is what asks new requests to try HTTP/3 (today the server
 * sends `curl_h2`, so HTTP/3 is off). This object adds only the user's override on top of that:
 * the effective value is the user's explicit choice when there is one, otherwise whatever the
 * server variant implies. A later server change never overrides a choice the user made.
 *
 * Only new requests are affected (NetworkKMM copies the choice into each request), and a
 * request still falls back to HTTP/2 or HTTP/1.1 when the network cannot do HTTP/3. Presigned
 * direct-upload PUTs stay pinned to HTTP/1.1 regardless.
 */
object SlockHttp3Policy {
    @Volatile
    private var userChoiceReader: (() -> Boolean?)? = null

    /** [read] returns the stored choice under [HTTP3_PREFERENCE_KEY], or null while unset. */
    fun bindUserChoice(read: () -> Boolean?) {
        userChoiceReader = read
    }

    /** The user's explicit choice, or null when the switch follows the server variant. */
    fun userChoice(): Boolean? {
        val read = userChoiceReader ?: return null
        return runCatching { read() }.getOrNull()
    }

    /** Resolves the effective request: the user's choice when set, else the server variant. */
    fun resolve(serverRequestsHttp3: Boolean): Boolean = userChoice() ?: serverRequestsHttp3

    /** Parses the stored preference: "true" / "false", anything else (or absent) is "no choice". */
    fun parseUserChoice(stored: String?): Boolean? =
        when (stored?.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    internal fun resetForTest() {
        userChoiceReader = null
    }
}
