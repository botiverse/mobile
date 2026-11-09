package build.raft.app.core.data.diagnostics

import java.util.Locale

internal object RaftPersistentDiagnosticsPolicy {
    private const val SlowRequestMillis = 3_000L

    fun shouldPersist(level: String, message: String): Boolean {
        val normalizedLevel = level.trim().lowercase(Locale.US)
        val normalizedMessage = message.lowercase(Locale.US)
        if (normalizedLevel == "error") return true
        if (!normalizedMessage.isHighFrequencyNetworkMessage()) return true
        return normalizedMessage.isNoteworthyNetworkMessage()
    }

    private fun String.isHighFrequencyNetworkMessage(): Boolean =
        contains("slock_networkkmm_info") ||
            contains("slock_networkkmm_execute_start") ||
            contains("slock_networkkmm_execute_finish") ||
            contains("slock_networkkmm_multipart_start") ||
            contains("slock_networkkmm_multipart_finish") ||
            // The Ktor executor's per-request pair (KtorRaftHttpExecutor). The networkkmm names above
            // predate it, so every request start/finish (about 3 per second while a thread is open,
            // 6 of the 28 MB in Hands fb-52cc0499) went to the ring. A failed, slow, cancelled or
            // non-2xx/3xx request, and anything auth, still persists through isNoteworthyNetworkMessage.
            contains("slock_http_execute_start") ||
            contains("slock_http_execute_finish") ||
            contains("slock_http_multipart_start") ||
            contains("slock_http_multipart_finish") ||
            contains("slock_http_binary_start") ||
            contains("slock_http_binary_finish") ||
            contains("endpoint category=") ||
            contains("network_prewarm")

    private fun String.isNoteworthyNetworkMessage(): Boolean =
        isAuthRelated() ||
            contains("slock_networkkmm_error") ||
            contains("slock_networkkmm_execute_failed") ||
            contains("slock_networkkmm_multipart_failed") ||
            contains("staleh2detected=true") ||
            contains("freshretry=true") ||
            contains("connectionrolloverratelimited=true") ||
            contains("transport_timing") ||
            contains("[dns]") ||
            contains("[tls]") ||
            TimeoutMarker.containsMatchIn(this) ||
            contains("timed out") ||
            contains("exception") ||
            contains(" result=failure") ||
            contains(" result=network_error") ||
            hasFailedHttpStatus() ||
            hasTransportErrorCode() ||
            hasNoHttpResponse() ||
            hasSlowDuration()

    private fun String.isAuthRelated(): Boolean =
        contains("/auth/") ||
            contains("path=auth/") ||
            contains("auth_session") ||
            contains("auth_realtime_refresh") ||
            contains("token_provider") ||
            contains("current_user") ||
            AuthRefreshMarker.containsMatchIn(this)

    private fun String.hasFailedHttpStatus(): Boolean =
        HttpStatus.findAll(this).any { match ->
            match.groupValues[1].toIntOrNull()?.let { it !in 200..399 } == true
        }

    /** `code=0`: the executor got no HTTP response (transport failure, retirement or caller cancel). */
    private fun String.hasNoHttpResponse(): Boolean = NoHttpResponseCode.containsMatchIn(this)

    private fun String.hasTransportErrorCode(): Boolean =
        TransportErrorCode.findAll(this).any { match ->
            match.groupValues[1].toIntOrNull()?.let { it != 0 } == true
        }

    private fun String.hasSlowDuration(): Boolean =
        Duration.findAll(this).any { match ->
            match.groupValues[1].toLongOrNull()?.let { it >= SlowRequestMillis } == true
        }

    private val HttpStatus = Regex("""(?:^|[\s,])(?:code|status|http)\s*[:=]\s*(\d{3})(?:[\s,]|$)""")
    private val NoHttpResponseCode = Regex("""(?:^|\s)code=0(?:\s|$)""")
    private val TransportErrorCode = Regex("""(?:^|[\s,])errcode\s*[:=]\s*(-?\d+)(?:[\s,]|$)""")
    private val Duration =
        Regex("""(?:durationms|elapsedms|totalelapsedms|totalms)\s*[:=]\s*(\d+)(?:\.\d+)?""")
    private val AuthRefreshMarker =
        Regex("""(?:^|\s)(?:refresh_start|refresh_success|refresh_failed)(?:\s|$)""")
    private val TimeoutMarker = Regex("""(?:^|[\s_])timeout(?:[\s:=,]|$)""")
}
