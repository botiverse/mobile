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

    private fun String.hasTransportErrorCode(): Boolean =
        TransportErrorCode.findAll(this).any { match ->
            match.groupValues[1].toIntOrNull()?.let { it != 0 } == true
        }

    private fun String.hasSlowDuration(): Boolean =
        Duration.findAll(this).any { match ->
            match.groupValues[1].toLongOrNull()?.let { it >= SlowRequestMillis } == true
        }

    private val HttpStatus = Regex("""(?:^|[\s,])(?:code|status|http)\s*[:=]\s*(\d{3})(?:[\s,]|$)""")
    private val TransportErrorCode = Regex("""(?:^|[\s,])errcode\s*[:=]\s*(-?\d+)(?:[\s,]|$)""")
    private val Duration =
        Regex("""(?:durationms|elapsedms|totalelapsedms|totalms)\s*[:=]\s*(\d+)(?:\.\d+)?""")
    private val AuthRefreshMarker =
        Regex("""(?:^|\s)(?:refresh_start|refresh_success|refresh_failed)(?:\s|$)""")
    private val TimeoutMarker = Regex("""(?:^|[\s_])timeout(?:[\s:=,]|$)""")
}
