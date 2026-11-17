package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.ServerSetupProjection

internal data class ServerSetupAdmissionIdentity(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val generation: String,
) {
    val valid: Boolean
        get() =
            environmentId.isNotBlank() &&
                accountId.isNotBlank() &&
                serverId.isNotBlank() &&
                generation.isValidServerSetupGeneration()
}

internal fun String.isValidServerSetupGeneration(): Boolean =
    length in 1..128 && all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._:-" }

internal sealed interface ServerSetupAdmissionState {
    data class Loading(val identity: ServerSetupAdmissionIdentity) : ServerSetupAdmissionState
    data class Blocked(
        val identity: ServerSetupAdmissionIdentity,
        val projection: ServerSetupProjection,
    ) : ServerSetupAdmissionState
    data class Admitted(
        val identity: ServerSetupAdmissionIdentity,
        val projection: ServerSetupProjection,
    ) : ServerSetupAdmissionState
    data class Failed(
        val identity: ServerSetupAdmissionIdentity,
        val error: ScreenError,
        /**
         * The check failed while setup was already on screen for this server. Only then does a
         * failure keep the screen (with Retry); any other failure lets Home through.
         */
        val duringSetup: Boolean = false,
    ) : ServerSetupAdmissionState
}

/**
 * Generation fence for setup reads and Web completion wake events. A callback is never completion
 * authority: it can only request another read for the currently active identity.
 */
internal class ServerSetupAdmissionReducer {
    private var activeIdentity: ServerSetupAdmissionIdentity? = null
    private var switchClaimed = false

    fun begin(identity: ServerSetupAdmissionIdentity): ServerSetupAdmissionState {
        activeIdentity = identity.takeIf(ServerSetupAdmissionIdentity::valid)
        switchClaimed = false
        return ServerSetupAdmissionState.Loading(identity)
    }

    fun resolve(
        identity: ServerSetupAdmissionIdentity,
        result: DataResult<ServerSetupProjection>,
    ): ServerSetupAdmissionState? {
        if (activeIdentity != identity || !identity.valid) return null
        return when (result) {
            is DataResult.Content ->
                if (result.data.blocksChat) {
                    ServerSetupAdmissionState.Blocked(identity, result.data)
                } else {
                    ServerSetupAdmissionState.Admitted(identity, result.data)
                }
            is DataResult.Error -> ServerSetupAdmissionState.Failed(identity, result.error)
            is DataResult.Empty ->
                ServerSetupAdmissionState.Failed(
                    identity,
                    ScreenError("server_setup_projection_empty", retryable = false),
                )
            is DataResult.NoPermission ->
                ServerSetupAdmissionState.Failed(
                    identity,
                    ScreenError(
                        code = result.blocker.code,
                        retryable = false,
                        serverMessage = result.blocker.serverMessage,
                    ),
                )
        }
    }

    fun acceptsWake(identity: ServerSetupAdmissionIdentity): Boolean =
        identity.valid && activeIdentity == identity && !switchClaimed

    fun claimSwitch(identity: ServerSetupAdmissionIdentity): Boolean {
        if (!acceptsWake(identity)) return false
        switchClaimed = true
        return true
    }
}
