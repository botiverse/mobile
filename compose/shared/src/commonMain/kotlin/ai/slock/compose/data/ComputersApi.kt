package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val computersApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * Typed Computers/Machines boundary.
 *
 * Upstream still calls the resource `machines` on the wire while product copy
 * calls it Computers. Keep route and field names exact here; UI/repository
 * code can expose the user-facing Computer naming without guessing aliases.
 */
interface SlockComputersApi {
    suspend fun machines(serverId: ServerId): HomeNetworkResponse
    suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse
    suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse
    suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse
    suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse
    suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse
    suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse
    suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse
}

internal class SlockComputersNetworkApi(
    private val networkClient: SlockNetworkClient
) : SlockComputersApi {
    override suspend fun machines(serverId: ServerId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "servers/${serverId.value}/machines",
            serverIdOverride = serverId
        )

    override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}",
            method = "PATCH",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}",
            method = "DELETE",
            serverIdOverride = serverId
        )

    override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/rotate-key",
            method = "POST",
            serverIdOverride = serverId
        )

    override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/workspaces",
            serverIdOverride = serverId
        )

    override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-models/${runtime.trim()}",
            serverIdOverride = serverId
        )

    override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/workspaces/${directoryName.trim()}",
            method = "DELETE",
            serverIdOverride = serverId
        )
}

internal class SlockComputersApiClient(
    private val transport: SlockComputersApi
) {
    suspend fun machines(serverId: ServerId): DataResult<ComputerListSummary> =
        transport.machines(serverId).mapComputersBody(
            "computers_list_contract_mismatch",
            ComputersListResponseDto.serializer()
        ) { wire ->
            ComputerListSummary(
                machines = wire.machines.mapNotNull { it.toSummary() },
                machineCount = wire.machines.size,
                latestDaemonVersion = wire.latestDaemonVersion,
                onlineCount = wire.machines.count { it.status == "online" }
            )
        }

    suspend fun registerMachine(serverId: ServerId, name: String): DataResult<ComputerRegistrationSummary> =
        transport.registerMachine(serverId, buildRegisterComputerBody(name)).mapComputersBody(
            "computer_register_contract_mismatch",
            ComputerRegistrationResponseDto.serializer()
        ) { wire ->
            val machine = wire.machine.toSummary() ?: return@mapComputersBody null
            val apiKey = wire.apiKey.takeIf { it.isNotBlank() } ?: return@mapComputersBody null
            ComputerRegistrationSummary(machine = machine, apiKeyPrefix = apiKey.take(12))
        }

    suspend fun renameMachine(serverId: ServerId, machineId: String, name: String): DataResult<ComputerSummary> =
        transport.renameMachine(serverId, machineId, buildRenameComputerBody(name)).mapComputersBody(
            "computer_rename_contract_mismatch",
            MachineRenameDto.serializer()
        ) { it.toSummary() }

    suspend fun deleteMachine(serverId: ServerId, machineId: String): DataResult<ComputerCommandResult> =
        transport.deleteMachine(serverId, machineId).mapComputersCommand("computer_delete_contract_mismatch")

    suspend fun rotateMachineKey(serverId: ServerId, machineId: String): DataResult<ComputerApiKeyRotationSummary> =
        transport.rotateMachineKey(serverId, machineId).mapComputersBody(
            "computer_rotate_key_contract_mismatch",
            RotateComputerKeyResponseDto.serializer()
        ) { wire ->
            val apiKey = wire.apiKey.takeIf { it.isNotBlank() } ?: return@mapComputersBody null
            ComputerApiKeyRotationSummary(apiKeyPrefix = apiKey.take(12))
        }

    suspend fun machineWorkspaces(serverId: ServerId, machineId: String): DataResult<ComputerWorkspacePage> =
        transport.machineWorkspaces(serverId, machineId).mapComputersBody(
            "computer_workspaces_contract_mismatch",
            kotlinx.serialization.builtins.ListSerializer(ComputerWorkspaceDto.serializer())
        ) { wire ->
            ComputerWorkspacePage(
                workspaces = wire.mapNotNull { it.toSummary() },
                itemCount = wire.size,
                activeCount = wire.count { it.status == "active" },
                orphanCount = wire.count { it.status == "orphan" }
            )
        }

    suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): DataResult<ComputerRuntimeModelsSummary> =
        transport.runtimeModels(serverId, machineId, runtime).mapComputersBody(
            "computer_runtime_models_contract_mismatch",
            ComputerRuntimeModelsResponseDto.serializer()
        ) { wire ->
            ComputerRuntimeModelsSummary(
                models = wire.models,
                runtime = runtime,
                modelCount = wire.models.size,
                defaultModel = wire.defaultModel
            )
        }

    suspend fun deleteMachineWorkspace(
        serverId: ServerId,
        machineId: String,
        directoryName: String
    ): DataResult<ComputerCommandResult> =
        transport.deleteMachineWorkspace(serverId, machineId, directoryName)
            .mapComputersCommand("computer_workspace_delete_contract_mismatch")
}

internal data class ComputerListSummary(
    val machines: List<ComputerSummary>,
    val machineCount: Int,
    val onlineCount: Int,
    val latestDaemonVersion: String?
)

internal data class ComputerSummary(
    val id: String,
    val name: String,
    val status: ComputerStatus,
    val statusVersion: Long,
    val apiKeyPrefix: String?,
    val runtimes: List<String>,
    val daemonVersion: String?,
    val hostname: String?,
    val os: String?,
    val lastHeartbeat: String?,
    val createdAt: String?
)

internal data class ComputerRegistrationSummary(val machine: ComputerSummary, val apiKeyPrefix: String)
internal data class ComputerApiKeyRotationSummary(val apiKeyPrefix: String)
internal data class ComputerWorkspaceSummary(
    val directoryName: String,
    val totalSizeBytes: Long,
    val fileCount: Int,
    val status: String,
    val lastModified: String?,
    val agentName: String?,
    val agentStatus: String?
)
internal data class ComputerWorkspacePage(
    val workspaces: List<ComputerWorkspaceSummary>,
    val itemCount: Int,
    val activeCount: Int,
    val orphanCount: Int
)
internal data class ComputerRuntimeModelsSummary(
    val runtime: String,
    val models: List<String>,
    val modelCount: Int,
    val defaultModel: String?
)
internal data class ComputerCommandResult(val ok: Boolean)

private inline fun <Wire, T> HomeNetworkResponse.mapComputersBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    crossinline transform: (Wire) -> T?,
): DataResult<T> {
    if (!isSuccessful) {
        return toComputersDataError()
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val wire = try {
        computersApiJson.decodeFromString(serializer, body)
    } catch (_: SerializationException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
    val value = transform(wire)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private fun <T> HomeNetworkResponse.toComputersDataError(): DataResult<T> =
    when (code) {
        401 -> DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))
        403 -> DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access"))
        307 -> DataResult.Error(ScreenError(code = "machine_offline_replay", retryable = true))
        400 -> DataResult.Error(ScreenError(code = "computer_bad_request", retryable = false))
        409 -> DataResult.Error(ScreenError(code = "computer_has_agents", retryable = false))
        503 -> DataResult.Error(ScreenError(code = "machine_offline", retryable = true))
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        else -> DataResult.Error(ScreenError(code = "http_$code", retryable = code != 404))
    }

private fun HomeNetworkResponse.mapComputersCommand(contractCode: String): DataResult<ComputerCommandResult> =
    mapComputersBody(contractCode, ComputerCommandResponseDto.serializer()) { wire ->
        if (!wire.ok) null else ComputerCommandResult(ok = true)
    }

@Serializable
private data class ComputersListResponseDto(
    val machines: List<ComputerDto>,
    val latestDaemonVersion: String? = null
)

@Serializable
private data class ComputerDto(
    val id: String,
    val name: String,
    val status: String,
    val statusVersion: Long = 0L,
    val apiKeyPrefix: String? = null,
    val runtimes: List<String> = emptyList(),
    val daemonVersion: String? = null,
    val hostname: String? = null,
    val os: String? = null,
    val lastHeartbeat: String? = null,
    val createdAt: String? = null
)

/**
 * Server returns the raw `machines` row for `PATCH /machines/:id` (it does NOT
 * enrich the response with `status` / `statusVersion` / `runtimes` defaults).
 * Use this DTO for rename so we don't mis-classify the missing status field
 * as a contract mismatch. Other rename-derived fields (apiKeyPrefix /
 * lastHeartbeat / createdAt) are kept optional for the same reason.
 */
@Serializable
private data class MachineRenameDto(
    val id: String,
    val name: String,
    val status: String? = null,
    val statusVersion: Long? = null,
    val apiKeyPrefix: String? = null,
    val runtimes: List<String> = emptyList(),
    val daemonVersion: String? = null,
    val hostname: String? = null,
    val os: String? = null,
    val lastHeartbeat: String? = null,
    val createdAt: String? = null
)

internal enum class ComputerStatus(val wire: String) {
    Online("online"),
    Offline("offline"),
    Unknown("");

    companion object {
        fun fromWire(value: String?): ComputerStatus = when (value) {
            "online" -> Online
            "offline" -> Offline
            else -> Unknown
        }
    }
}

private fun ComputerDto.toSummary(): ComputerSummary? {
    if (id.isBlank() || name.isBlank()) return null
    return ComputerSummary(
        id = id,
        name = name,
        status = ComputerStatus.fromWire(status),
        statusVersion = statusVersion,
        apiKeyPrefix = apiKeyPrefix,
        runtimes = runtimes,
        daemonVersion = daemonVersion,
        hostname = hostname,
        os = os,
        lastHeartbeat = lastHeartbeat,
        createdAt = createdAt
    )
}

private fun MachineRenameDto.toSummary(): ComputerSummary? {
    if (id.isBlank() || name.isBlank()) return null
    return ComputerSummary(
        id = id,
        name = name,
        status = ComputerStatus.fromWire(status),
        statusVersion = statusVersion ?: 0L,
        apiKeyPrefix = apiKeyPrefix,
        runtimes = runtimes,
        daemonVersion = daemonVersion,
        hostname = hostname,
        os = os,
        lastHeartbeat = lastHeartbeat,
        createdAt = createdAt
    )
}

@Serializable
private data class ComputerRegistrationResponseDto(
    val machine: ComputerDto,
    val apiKey: String
)

@Serializable
private data class RotateComputerKeyResponseDto(val apiKey: String)

@Serializable
private data class ComputerWorkspaceDto(
    val directoryName: String,
    val totalSizeBytes: Long,
    val fileCount: Int,
    val status: String,
    val lastModified: String? = null,
    val agentName: String? = null,
    val agentStatus: String? = null
)

private fun ComputerWorkspaceDto.toSummary(): ComputerWorkspaceSummary? {
    if (directoryName.isBlank() || status.isBlank()) return null
    return ComputerWorkspaceSummary(
        directoryName = directoryName,
        totalSizeBytes = totalSizeBytes,
        fileCount = fileCount,
        status = status,
        lastModified = lastModified,
        agentName = agentName,
        agentStatus = agentStatus
    )
}

@Serializable
private data class ComputerRuntimeModelsResponseDto(
    val models: List<String>,
    @SerialName("default")
    val defaultModel: String? = null
)

@Serializable
private data class ComputerCommandResponseDto(val ok: Boolean)

@Serializable
internal data class RegisterComputerBody(val name: String)

@Serializable
internal data class RenameComputerBody(val name: String)

internal fun buildRegisterComputerBody(name: String): String =
    computersApiJson.encodeToString(RegisterComputerBody(name = name))

internal fun buildRenameComputerBody(name: String): String =
    computersApiJson.encodeToString(RenameComputerBody(name = name))
