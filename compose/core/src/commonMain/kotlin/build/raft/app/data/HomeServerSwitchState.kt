package build.raft.app.data

import build.raft.app.model.ServerId

data class HomeServerSwitchState(
    val selectedServerId: String? = null,
    val pendingServerId: String? = null,
    val requestGeneration: Long = 0
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
}
