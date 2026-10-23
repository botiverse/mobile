package build.raft.app.data

import build.raft.app.model.ServerId

data class HomeServerSwitchState(
    val selectedServerId: String? = null,
    val pendingServerId: String? = null,
    val requestGeneration: Long = 0,
    /** The server whose switch last ended without its Home loading (dropped, failed, timed out). */
    val failedServerId: String? = null,
    /** Bumped on every such failure: the one-shot key the UI shows its error on. */
    val failureCount: Long = 0
) {
    fun select(serverId: String): HomeServerSwitchState =
        copy(
            selectedServerId = serverId,
            pendingServerId = serverId,
            requestGeneration = requestGeneration + 1
        )

    fun refresh(): HomeServerSwitchState =
        copy(requestGeneration = requestGeneration + 1)

    fun isCurrent(generation: Long, serverId: ServerId): Boolean =
        generation == requestGeneration && (selectedServerId == null || selectedServerId == serverId.value)

    fun complete(generation: Long, serverId: ServerId): HomeServerSwitchState =
        if (isCurrent(generation, serverId)) copy(pendingServerId = null) else this

    /**
     * The pending switch ended without the target's Home: the list stops waiting (no pending
     * server) and the failure is counted. The selection stays, so a late load still lands.
     */
    fun fail(): HomeServerSwitchState =
        if (pendingServerId == null) {
            this
        } else {
            copy(pendingServerId = null, failedServerId = pendingServerId, failureCount = failureCount + 1)
        }
}
