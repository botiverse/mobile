package build.raft.app

import build.raft.app.data.SlockServerSetupProjectionRepository
import build.raft.app.model.ServerId

/** Async admission controller kept separate from Compose so stale request behavior is testable. */
internal class ServerSetupAdmissionController(
    private val repository: SlockServerSetupProjectionRepository,
) {
    private val reducer = ServerSetupAdmissionReducer()

    fun begin(identity: ServerSetupAdmissionIdentity): ServerSetupAdmissionState = reducer.begin(identity)

    suspend fun load(identity: ServerSetupAdmissionIdentity): ServerSetupAdmissionState? =
        reducer.resolve(identity, repository.load(ServerId(identity.serverId)))

    fun acceptsWake(identity: ServerSetupAdmissionIdentity): Boolean = reducer.acceptsWake(identity)
    fun claimSwitch(identity: ServerSetupAdmissionIdentity): Boolean = reducer.claimSwitch(identity)
}
