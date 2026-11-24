package build.raft.app.network.realtime

import build.raft.app.model.ServerId

/**
 * Authentication credentials for realtime connection
 */
data class RealtimeAuth(
    val token: String,
    val serverId: ServerId
)
