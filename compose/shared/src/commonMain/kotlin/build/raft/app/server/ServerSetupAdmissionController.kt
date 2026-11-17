package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.RaftServerSetupProjectionRepository
import build.raft.app.data.ScreenError
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException

/** Async admission controller kept separate from Compose so stale request behavior is testable. */
internal class ServerSetupAdmissionController(
    private val repository: RaftServerSetupProjectionRepository,
) {
    private val reducer = ServerSetupAdmissionReducer()

    fun begin(identity: ServerSetupAdmissionIdentity): ServerSetupAdmissionState = reducer.begin(identity)

    /** A thrown transport error (offline) resolves to Failed like any other failed check. */
    suspend fun load(identity: ServerSetupAdmissionIdentity): ServerSetupAdmissionState? {
        val result = try {
            repository.load(ServerId(identity.serverId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "server_setup_projection_request_failed", retryable = true))
        }
        return reducer.resolve(identity, result)
    }

    fun acceptsWake(identity: ServerSetupAdmissionIdentity): Boolean = reducer.acceptsWake(identity)
    fun claimSwitch(identity: ServerSetupAdmissionIdentity): Boolean = reducer.claimSwitch(identity)
}
