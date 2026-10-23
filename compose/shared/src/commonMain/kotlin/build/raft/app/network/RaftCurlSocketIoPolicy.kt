package build.raft.app.network

import build.raft.app.data.MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY
import kotlin.concurrent.Volatile

/**
 * Local preference behind the Settings switch "realtime over the new network stack (curl)".
 * Stored as "true" / "false"; absent means the user never touched the switch and the server flag
 * decides. Device-wide like the other local preferences: it survives logout and server switches.
 */
const val REALTIME_CURL_SOCKETIO_PREFERENCE_KEY: String = "network:realtime_curl_socketio_enabled"

/**
 * Task #154: whether a realtime connection to a server uses NetworkKMM's curl Socket.IO client.
 *
 * artin 2026-10-10: the Settings switch decides; while the user has not touched it, it followed the
 * `mobile_curl_socketio_v0` server flag. Since 2026-10-23 an untouched switch means off (see [isEnabledFor]). So the effective value is the user's choice
 * when there is one, otherwise the server flag. A later server flag change does not override a choice
 * the user made. The platform realtime transport asks at every connect and reconnect, so a change of
 * either applies from the next connection.
 *
 * [bindServerFlag] receives the feature-flag store's read (online value, then internal-build Local
 * Feature Overrides); [bindUserChoice] reads [REALTIME_CURL_SOCKETIO_PREFERENCE_KEY]. Unbound or failing
 * reads count as "no choice" / off, so the existing Socket.IO library stays in use.
 */
object RaftCurlSocketIoPolicy {
    const val FLAG_KEY: String = MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY

    /**
     * True once a platform realtime host can actually switch to curl (Android today). The Settings
     * switch is shown only then, so iOS does not show a switch that does nothing.
     */
    @Volatile
    var platformSupported: Boolean = false
        private set

    fun markPlatformSupported() {
        platformSupported = true
    }

    @Volatile
    private var serverFlagReader: ((String) -> Boolean)? = null

    @Volatile
    private var userChoiceReader: (() -> Boolean?)? = null

    fun bindServerFlag(isEnabledForServer: (String) -> Boolean) {
        serverFlagReader = isEnabledForServer
    }

    fun bindUserChoice(read: () -> Boolean?) {
        userChoiceReader = read
    }

    /** The server flag for [serverId]: the switch's value while the user has not touched it. */
    fun serverDefaultFor(serverId: String?): Boolean {
        val read = serverFlagReader ?: return false
        if (serverId.isNullOrBlank()) return false
        return runCatching { read(serverId) }.getOrDefault(false)
    }

    /** The user's explicit choice, or null when the switch follows the server. */
    fun userChoice(): Boolean? {
        val read = userChoiceReader ?: return null
        return runCatching { read() }.getOrNull()
    }

    /**
     * artin 2026-10-23 (Ktor switch): realtime runs on the platform Socket.IO stack (socket.io-client-java
     * over OkHttp on Android, Socket.IO-Client-Swift on iOS) unless the user explicitly turned curl on.
     * The `mobile_curl_socketio_v0` server flag no longer selects curl; [serverDefaultFor] is kept only
     * for diagnostics until curl Socket.IO is removed.
     */
    fun isEnabledFor(@Suppress("UNUSED_PARAMETER") serverId: String?): Boolean = userChoice() ?: false

    /** Parses the stored preference: "true" / "false", anything else (or absent) is "no choice". */
    fun parseUserChoice(stored: String?): Boolean? =
        when (stored?.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    internal fun resetForTest() {
        platformSupported = false
        serverFlagReader = null
        userChoiceReader = null
    }
}
