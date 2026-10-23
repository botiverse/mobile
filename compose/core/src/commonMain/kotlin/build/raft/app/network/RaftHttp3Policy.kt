package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile

/**
 * Local preference behind the Settings switch "Enable HTTP/3". Stored as "true" / "false";
 * absent means the user never touched the switch, and HTTP/3 stays off.
 * Device-wide like the other local preferences: it survives logout and server switches.
 */
const val HTTP3_PREFERENCE_KEY: String = "network:http3_enabled"

/**
 * Raft task #3 (artin 2026-10-11): a Settings switch for HTTP/3.
 *
 * New requests use curl over HTTP/2. The effective value is the user's explicit choice when there
 * is one, otherwise off. (The default used to come from the retired `mobile_curl_transport_v1`
 * server variant, which production always sent as `curl_h2`, so HTTP/3 was off there too.)
 *
 * Only new requests are affected (NetworkKMM copies the choice into each request), and a
 * request still falls back to HTTP/2 or HTTP/1.1 when the network cannot do HTTP/3. Presigned
 * direct-upload PUTs stay pinned to HTTP/1.1 regardless.
 */
object RaftHttp3Policy {
    @Volatile
    private var userChoiceReader: (() -> Boolean?)? = null

    /** [read] returns the stored choice under [HTTP3_PREFERENCE_KEY], or null while unset. */
    fun bindUserChoice(read: () -> Boolean?) {
        userChoiceReader = read
    }

    /** The user's explicit choice, or null when the user never touched the switch. */
    fun userChoice(): Boolean? {
        val read = userChoiceReader ?: return null
        return runCatching { read() }.getOrNull()
    }

    /** Resolves the effective request: the user's choice when set, else off. */
    fun resolve(): Boolean = userChoice() ?: false

    /** Parses the stored preference: "true" / "false", anything else (or absent) is "no choice". */
    fun parseUserChoice(stored: String?): Boolean? =
        when (stored?.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    @RaftCoreInternalApi
    fun resetForTest() {
        userChoiceReader = null
    }
}
