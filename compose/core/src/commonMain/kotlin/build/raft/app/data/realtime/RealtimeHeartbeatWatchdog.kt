package build.raft.app.data.realtime

data class RealtimeHeartbeatWatchdogSnapshot(
    val connected: Boolean,
    val lastHeartbeatAtMillis: Long?,
    val lastInboundEventAtMillis: Long?
)

object RealtimeHeartbeatWatchdog {
    const val HEARTBEAT_SILENCE_MILLIS: Long = 90_000L
    const val INBOUND_SILENCE_MILLIS: Long = 120_000L
    const val CHECK_INTERVAL_MILLIS: Long = 30_000L

    fun shouldForceReconnect(
        snapshot: RealtimeHeartbeatWatchdogSnapshot,
        nowMillis: Long
    ): Boolean {
        if (!snapshot.connected) return false
        val heartbeatAt = snapshot.lastHeartbeatAtMillis ?: return false
        val inboundAt = snapshot.lastInboundEventAtMillis ?: return false
        return nowMillis - heartbeatAt > HEARTBEAT_SILENCE_MILLIS &&
            nowMillis - inboundAt > INBOUND_SILENCE_MILLIS
    }
}
