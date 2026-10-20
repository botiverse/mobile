package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val serverSetupProjectionJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Canonical server-side admission projection.
 *
 * Source: `GET /api/servers/:serverId/setup-projection`. The client must not
 * reconstruct setup readiness from Computers, runtimes, or local Web state.
 */
interface RaftServerSetupProjectionApi {
    suspend fun projection(serverId: ServerId): HomeNetworkResponse
}

class NetworkRaftServerSetupProjectionApi(
    private val networkClient: RaftNetworkClient,
) : RaftServerSetupProjectionApi {
    override suspend fun projection(serverId: ServerId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "servers/${serverId.value}/setup-projection",
            serverIdOverride = serverId,
        )
}

class RaftServerSetupProjectionRepository(
    private val api: RaftServerSetupProjectionApi,
) {
    suspend fun load(serverId: ServerId): DataResult<ServerSetupProjection> {
        val response = api.projection(serverId)
        if (!response.isSuccessful) {
            return DataResult.Error(
                ScreenError(
                    code = "server_setup_projection_request_failed",
                    retryable = response.code >= 500,
                )
            )
        }
        val body = response.bodyText?.takeIf(String::isNotBlank)
            ?: return contractMismatch()
        val wire = try {
            serverSetupProjectionJson.decodeFromString(ServerSetupProjectionWireDto.serializer(), body)
        } catch (_: SerializationException) {
            return contractMismatch()
        }
        return DataResult.Content(wire.toDomain())
    }

    private fun contractMismatch(): DataResult.Error =
        DataResult.Error(
            ScreenError(
                code = "server_setup_projection_contract_mismatch",
                retryable = false,
            )
        )
}

data class ServerSetupProjection(
    val surface: String,
    val phase: String?,
    val currentStep: String?,
    val blocksChat: Boolean,
    val allowedExits: Set<String>,
    val gateReason: String?,
)

@Serializable
private data class ServerSetupProjectionWireDto(
    val surface: String,
    val phase: String? = null,
    val currentStep: String? = null,
    val blocksChat: Boolean,
    val allowedExits: List<String> = emptyList(),
    val gateReason: String? = null,
)

private fun ServerSetupProjectionWireDto.toDomain(): ServerSetupProjection =
    ServerSetupProjection(
        surface = surface,
        phase = phase,
        currentStep = currentStep,
        blocksChat = blocksChat,
        allowedExits = allowedExits.toSet(),
        gateReason = gateReason,
    )
