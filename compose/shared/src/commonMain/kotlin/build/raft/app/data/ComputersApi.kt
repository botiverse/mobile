package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.RuntimeModelInfo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val computersApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}
// Runtime-options and option-source lists are additive on the server side: a new field must not
// break shipped clients (Hands c453ba5f — slock #8600 added `runtimeFormV2` and strict decoding
// failed every Create Agent runtime list).
private val runtimeFormListJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = false
}

// Form definitions stay strict: an unknown keyword inside a versioned schema can change what the form
// means, so it fails closed for that one runtime instead of rendering a guess.
private val runtimeFormApiJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    coerceInputValues = false
}

/**
 * Typed Computers/Machines boundary.
 *
 * Upstream still calls the resource `machines` on the wire while product copy
 * calls it Computers. Keep route and field names exact here; UI/repository
 * code can expose the user-facing Computer naming without guessing aliases.
 */
interface RaftComputersApi {
    suspend fun machines(serverId: ServerId): HomeNetworkResponse
    suspend fun machines(
        serverId: ServerId,
        priority: DataRequestPriority
    ): HomeNetworkResponse = machines(serverId)
    suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse
    suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse
    suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse
    suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse
    suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse
    suspend fun runtimeOptions(serverId: ServerId, machineId: String): HomeNetworkResponse =
        error("runtimeOptions is not implemented")
    suspend fun runtimeFormDefinition(
        serverId: ServerId,
        machineId: String,
        ref: SharedRuntimeFormDefinitionRef
    ): HomeNetworkResponse = error("runtimeFormDefinition is not implemented")
    suspend fun runtimeFormOptionSource(
        serverId: ServerId,
        machineId: String,
        ref: SharedRuntimeFormDefinitionRef,
        sourceId: String
    ): HomeNetworkResponse = error("runtimeFormOptionSource is not implemented")
    /** Runtime form protocol v2: `GET …/runtime-forms/v2/:runtimeId`, the current form with no schemaVersion pin. */
    suspend fun runtimeFormV2(
        serverId: ServerId,
        machineId: String,
        runtimeId: String
    ): HomeNetworkResponse = error("runtimeFormV2 is not implemented")
    /**
     * [refresh] (`option_source.status` retry): `?refresh=1`, the server bypasses any cached probe.
     * [formRevision] (`provider.saved_connection`): the form's Definition.revision, sent as `?formRevision=`.
     */
    suspend fun runtimeFormV2OptionSource(
        serverId: ServerId,
        machineId: String,
        runtimeId: String,
        sourceId: String,
        refresh: Boolean = false,
        formRevision: String? = null
    ): HomeNetworkResponse = error("runtimeFormV2OptionSource is not implemented")
    suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse
    /** Read-only `GET …/runtime-account-usage/:provider` (task #137); refresh is not exposed. */
    suspend fun runtimeAccountUsage(
        serverId: ServerId,
        machineId: String,
        provider: String
    ): HomeNetworkResponse = error("runtimeAccountUsage is not implemented")
    suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse
}

internal class RaftComputersNetworkApi(
    private val networkClient: RaftNetworkClient,
    private val runtimeFormLanguageTag: () -> String? = { runtimeFormRequestLanguageTag() }
) : RaftComputersApi {
    override suspend fun machines(serverId: ServerId): HomeNetworkResponse =
        machines(serverId, DataRequestPriority.Visible)

    override suspend fun machines(
        serverId: ServerId,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
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

    override suspend fun runtimeOptions(serverId: ServerId, machineId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-options",
            serverIdOverride = serverId
        )

    override suspend fun runtimeFormDefinition(
        serverId: ServerId,
        machineId: String,
        ref: SharedRuntimeFormDefinitionRef
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-form-definitions/${ref.runtimeId}",
            query = listOf("schemaVersion" to ref.schemaVersion),
            serverIdOverride = serverId
        )

    override suspend fun runtimeFormOptionSource(
        serverId: ServerId,
        machineId: String,
        ref: SharedRuntimeFormDefinitionRef,
        sourceId: String
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path =
                "servers/${serverId.value}/machines/${machineId.trim()}/runtime-form-definitions/" +
                    "${ref.runtimeId}/option-sources/${sourceId.trim()}",
            query = listOf("schemaVersion" to ref.schemaVersion),
            serverIdOverride = serverId
        )

    override suspend fun runtimeFormV2(
        serverId: ServerId,
        machineId: String,
        runtimeId: String
    ): HomeNetworkResponse =
        networkClient.requestWithHeaders(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-forms/v2/${runtimeId.trim()}",
            headers = runtimeFormLanguageHeaders(runtimeFormLanguageTag()),
            serverIdOverride = serverId
        )

    override suspend fun runtimeFormV2OptionSource(
        serverId: ServerId,
        machineId: String,
        runtimeId: String,
        sourceId: String,
        refresh: Boolean,
        formRevision: String?
    ): HomeNetworkResponse =
        networkClient.requestWithHeaders(
            priority = DataRequestPriority.Foreground,
            path =
                "servers/${serverId.value}/machines/${machineId.trim()}/runtime-forms/v2/" +
                    "${runtimeId.trim()}/option-sources/${sourceId.trim()}",
            headers = runtimeFormLanguageHeaders(runtimeFormLanguageTag()),
            query = buildList {
                if (refresh) add("refresh" to "1")
                formRevision?.takeIf { it.isNotEmpty() }?.let { add("formRevision" to it) }
            },
            serverIdOverride = serverId
        )

    override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-models/${runtime.trim()}",
            serverIdOverride = serverId
        )

    override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-account-usage/${provider.trim()}",
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

internal class RaftComputersApiClient(
    private val transport: RaftComputersApi
) {
    suspend fun machines(
        serverId: ServerId,
        priority: DataRequestPriority = DataRequestPriority.Visible
    ): DataResult<ComputerListSummary> =
        transport.machines(serverId, priority).mapComputersBody(
            "computers_list_contract_mismatch",
            ComputersListResponseDto.serializer()
        ) { wire ->
            ComputerListSummary(
                machines = wire.machines.mapNotNull { it.toSummary() },
                machineCount = wire.machines.size,
                latestDaemonVersion = wire.latestDaemonVersion,
                latestComputerVersion = wire.latestComputerVersion,
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
            ComputerRegistrationSummary(machine = machine, apiKey = apiKey, apiKeyPrefix = apiKey.take(12))
        }

    suspend fun renameMachine(serverId: ServerId, machineId: String, name: String): DataResult<ComputerSummary> =
        transport.renameMachine(serverId, machineId, buildComputerUpdateBody(name = name)).mapComputersBody(
            "computer_rename_contract_mismatch",
            MachineRenameDto.serializer()
        ) { it.toSummary() }

    suspend fun updateMachineDescription(serverId: ServerId, machineId: String, description: String): DataResult<ComputerSummary> =
        transport.renameMachine(serverId, machineId, buildComputerUpdateBody(description = description)).mapComputersBody(
            "computer_update_contract_mismatch",
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
            ComputerApiKeyRotationSummary(apiKeyPrefix = computerApiKeyPrefix(apiKey), apiKey = SettingsComputerApiKey(apiKey))
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
                models = wire.models.mapNotNull { it.toRuntimeModelInfo() },
                runtime = runtime,
                modelCount = wire.models.size,
                defaultModel = wire.defaultModel
            )
        }

    suspend fun runtimeAccountUsage(
        serverId: ServerId,
        machineId: String,
        provider: RuntimeAccountUsageProvider
    ): DataResult<RuntimeAccountUsageRead> =
        transport.runtimeAccountUsage(serverId, machineId, provider.wire).mapComputersBody(
            "computer_runtime_account_usage_contract_mismatch",
            JsonElement.serializer()
        ) { wire -> wire.toRuntimeAccountUsageRead() }

    suspend fun runtimeOptions(
        serverId: ServerId,
        machineId: String
    ): DataResult<SharedCreateAgentRuntimeCatalog> {
        val normalizedMachineId = machineId.trim()
        if (normalizedMachineId.isBlank()) return runtimeFormContractError("runtime_options_machine_required")
        return transport.runtimeOptions(serverId, normalizedMachineId).mapComputersBody(
            "computer_runtime_options_contract_mismatch",
            ComputerRuntimeSelectionCatalogDto.serializer(),
            runtimeFormListJson
        ) { wire -> wire.toDomain(normalizedMachineId) }
    }

    suspend fun runtimeFormDefinition(
        serverId: ServerId,
        machineId: String,
        ref: SharedRuntimeFormDefinitionRef
    ): DataResult<SharedAgentCreateFormDefinition> {
        val normalizedMachineId = machineId.trim()
        if (normalizedMachineId.isBlank() || !ref.isSupportedRuntimeForm) {
            return runtimeFormContractError("runtime_form_ref_unsupported")
        }
        val response = transport.runtimeFormDefinition(serverId, normalizedMachineId, ref)
        if (!response.isSuccessful) return response.toRuntimeFormDataError()
        return response.mapComputersBody(
            "runtime_form_definition_contract_mismatch",
            AgentCreateFormDefinitionDto.serializer(),
            runtimeFormApiJson
        ) { wire -> wire.toDomain(ref) }
    }

    suspend fun runtimeFormOptionSource(
        serverId: ServerId,
        machineId: String,
        ref: SharedRuntimeFormDefinitionRef,
        sourceRef: SharedAgentCreateFormOptionSourceRef
    ): DataResult<SharedAgentCreateFormOptionSource> {
        val normalizedMachineId = machineId.trim()
        if (
            normalizedMachineId.isBlank() ||
            !ref.isSupportedRuntimeForm ||
            sourceRef.ref != ref ||
            sourceRef.sourceId.isBlank()
        ) {
            return runtimeFormContractError("runtime_form_source_ref_unsupported")
        }
        val response =
            transport.runtimeFormOptionSource(
                serverId = serverId,
                machineId = normalizedMachineId,
                ref = ref,
                sourceId = sourceRef.sourceId
            )
        if (!response.isSuccessful) return response.toRuntimeFormDataError()
        return response.mapComputersBody(
            "runtime_form_option_source_contract_mismatch",
            AgentCreateFormOptionSourceDto.serializer(),
            runtimeFormListJson
        ) { wire -> wire.toDomain(ref, sourceRef) }
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
    val latestDaemonVersion: String?,
    val latestComputerVersion: String?
)

internal data class ComputerSummary(
    val id: String,
    val name: String,
    val status: ComputerStatus,
    val statusVersion: Long,
    val apiKeyPrefix: String?,
    val runtimes: List<String>,
    val daemonVersion: String?,
    val computerVersion: String?,
    val isComputer: Boolean,
    val hostname: String?,
    val os: String?,
    val description: String?,
    val lastHeartbeat: String?,
    val createdAt: String?,
    /** `machines.userId`: the user who registered the machine (server's "machine creator" authority). */
    val creatorUserId: String? = null,
    /** True when the requester attached the Computer backing this machine (false for raw daemons). */
    val attachedByCurrentUser: Boolean = false,
    /** Active agents assigned to the machine. */
    val agentCount: Int = 0,
    /** The server's policy-aware upgrade decision; null for raw daemons / older servers. */
    val computerUpgradeAvailable: Boolean? = null
)

internal data class ComputerRegistrationSummary(val machine: ComputerSummary, val apiKey: String, val apiKeyPrefix: String)
/** `POST .../rotate-key`: the new key in full (a redacting [SettingsComputerApiKey]) and its stored prefix. */
internal data class ComputerApiKeyRotationSummary(val apiKeyPrefix: String, val apiKey: SettingsComputerApiKey)

/** The server's `extractApiKeyPrefix` (slock packages/server/src/services/machineService.ts): the first 20 characters. */
internal fun computerApiKeyPrefix(apiKey: String): String = apiKey.take(20)
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
    val models: List<RuntimeModelInfo>,
    val modelCount: Int,
    val defaultModel: String?
)
internal data class ComputerCommandResult(val ok: Boolean)

private fun <T> runtimeFormContractError(code: String): DataResult<T> =
    DataResult.Error(ScreenError(code = code, retryable = false))

private inline fun <Wire, T> HomeNetworkResponse.mapComputersBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    json: Json = computersApiJson,
    crossinline transform: (Wire) -> T?,
): DataResult<T> {
    if (!isSuccessful) {
        return toComputersDataError()
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val wire = try {
        json.decodeFromString(serializer, body)
    } catch (_: SerializationException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
    val value = transform(wire)
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    return DataResult.Content(value)
}

private fun <T> HomeNetworkResponse.toComputersDataError(): DataResult<T> {
    val serverMessage = computersRouteErrorTextOrNull()
    return when (code) {
        401 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "auth_required",
                    requiredCapability = "auth_session",
                    serverMessage = serverMessage
                )
            )
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "server_forbidden",
                    requiredCapability = "server_access",
                    serverMessage = serverMessage
                )
            )
        307 ->
            DataResult.Error(
                ScreenError(code = "machine_offline_replay", retryable = true, serverMessage = serverMessage)
            )
        400 ->
            DataResult.Error(
                ScreenError(code = "computer_bad_request", retryable = false, serverMessage = serverMessage)
            )
        409 ->
            DataResult.Error(
                ScreenError(code = "computer_has_agents", retryable = false, serverMessage = serverMessage)
            )
        503 ->
            DataResult.Error(
                ScreenError(code = "machine_offline", retryable = true, serverMessage = serverMessage)
            )
        in 500..599 ->
            DataResult.Error(
                ScreenError(code = "server_unavailable", retryable = true, serverMessage = serverMessage)
            )
        else ->
            DataResult.Error(
                ScreenError(code = "http_$code", retryable = code != 404, serverMessage = serverMessage)
            )
    }
}

@Serializable
private data class ComputersRouteErrorDto(val error: String? = null)

private fun HomeNetworkResponse.computersRouteErrorTextOrNull(): String? {
    val payload = bodyText?.takeIf { it.isNotBlank() }
        ?: errorBodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        computersApiJson.decodeFromString(ComputersRouteErrorDto.serializer(), payload)
            .error
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun <T> HomeNetworkResponse.toRuntimeFormDataError(): DataResult<T> {
    val serverMessage = computersRouteErrorTextOrNull()
    return when (code) {
        401 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "auth_required",
                    requiredCapability = "auth_session",
                    serverMessage = serverMessage
                )
            )
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "server_forbidden",
                    requiredCapability = "manage_agents",
                    serverMessage = serverMessage
                )
            )
        404 -> DataResult.Error(ScreenError(code = "runtime_form_not_found", retryable = false, serverMessage = serverMessage))
        409 -> DataResult.Error(ScreenError(code = "runtime_form_stale", retryable = false, serverMessage = serverMessage))
        in 500..599 ->
            DataResult.Error(ScreenError(code = "server_unavailable", retryable = true, serverMessage = serverMessage))
        else -> DataResult.Error(ScreenError(code = "http_$code", retryable = false, serverMessage = serverMessage))
    }
}

private fun HomeNetworkResponse.mapComputersCommand(contractCode: String): DataResult<ComputerCommandResult> =
    mapComputersBody(contractCode, ComputerCommandResponseDto.serializer()) { wire ->
        if (!wire.ok) null else ComputerCommandResult(ok = true)
    }

@Serializable
private data class ComputersListResponseDto(
    val machines: List<ComputerDto>,
    val latestDaemonVersion: String? = null,
    val latestComputerVersion: String? = null
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
    val computerVersion: String? = null,
    val isComputer: Boolean = false,
    val hostname: String? = null,
    val os: String? = null,
    val description: String? = null,
    val lastHeartbeat: String? = null,
    val createdAt: String? = null,
    // slock packages/server/src/services/machineReadModel.ts (MachineReadModel) and the
    // GET /servers/:id/machines projection in routes/servers.ts.
    val userId: String? = null,
    val computerAttachedByCurrentUser: Boolean = false,
    val agentCount: Int = 0,
    val computerUpgradeAvailable: Boolean? = null
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
    val computerVersion: String? = null,
    val isComputer: Boolean = false,
    val hostname: String? = null,
    val os: String? = null,
    val description: String? = null,
    val lastHeartbeat: String? = null,
    val createdAt: String? = null,
    val userId: String? = null
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
        computerVersion = computerVersion,
        isComputer = isComputer,
        hostname = hostname,
        os = os,
        description = description,
        lastHeartbeat = lastHeartbeat,
        createdAt = createdAt,
        creatorUserId = userId?.trim()?.takeIf { it.isNotBlank() },
        attachedByCurrentUser = computerAttachedByCurrentUser,
        agentCount = agentCount.coerceAtLeast(0),
        computerUpgradeAvailable = computerUpgradeAvailable
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
        computerVersion = computerVersion,
        isComputer = isComputer,
        hostname = hostname,
        os = os,
        description = description,
        lastHeartbeat = lastHeartbeat,
        createdAt = createdAt,
        creatorUserId = userId?.trim()?.takeIf { it.isNotBlank() }
    )
}

@Serializable
private data class ComputerRegistrationResponseDto(
    val machine: ComputerDto,
    val apiKey: String
)

/**
 * slock packages/server/src/routes/servers.ts `POST /:id/machines/:machineId/rotate-key` answers
 * `res.json({ apiKey })`. Not a data class: its [toString] must not print the key.
 */
@Serializable
private class RotateComputerKeyResponseDto(val apiKey: String) {
    override fun toString(): String = "RotateComputerKeyResponseDto(apiKey=<redacted>)"
}

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
    val models: List<JsonElement>,
    @SerialName("default")
    val defaultModel: String? = null
)

private fun JsonElement.toRuntimeModelInfo(): RuntimeModelInfo? {
    val primitive = runCatching { jsonPrimitive }.getOrNull()
    if (primitive != null && primitive.isString) {
        val id = primitive.contentOrNull?.trim().orEmpty()
        return id.takeIf { it.isNotBlank() }?.let { RuntimeModelInfo(id = it, label = it) }
    }
    val obj = runCatching { jsonObject }.getOrNull() ?: return null
    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (id.isBlank()) return null
    val label = obj["label"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: id
    val verified = obj["verified"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    val supportedReasoningEfforts =
        runCatching { obj["supportedReasoningEfforts"]?.jsonArray }
            .getOrNull()
            .orEmpty()
            .mapNotNull { effort ->
                runCatching { effort.jsonPrimitive.contentOrNull }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            .distinct()
    val defaultReasoningEffort =
        obj["defaultReasoningEffort"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    return RuntimeModelInfo(
        id = id,
        label = label,
        verified = verified,
        supportedReasoningEfforts = supportedReasoningEfforts,
        defaultReasoningEffort = defaultReasoningEffort
    )
}

@Serializable
private data class RuntimeFormDefinitionRefDto(
    val protocolVersion: Int,
    val runtimeId: String,
    val schemaVersion: String
)

private fun RuntimeFormDefinitionRefDto.toDomain(): SharedRuntimeFormDefinitionRef? {
    val ref =
        SharedRuntimeFormDefinitionRef(
            protocolVersion = protocolVersion,
            runtimeId = runtimeId.trim(),
            schemaVersion = schemaVersion.trim()
        )
    return ref.takeIf { it.runtimeId.isNotBlank() && it.schemaVersion.isNotBlank() }
}

@Serializable
private data class ComputerRuntimeSelectionCatalogDto(
    val context: String,
    val machineId: String,
    // Raw rows, decoded one by one in toDomain: a row with a missing field or a wrong JSON type is
    // left out on its own instead of failing the whole response.
    val options: List<JsonElement>
)

@Serializable
private data class ComputerRuntimeSelectionOptionDto(
    val runtimeId: String,
    val capabilityStatus: String,
    val admissionStatus: String,
    val admissionReason: String? = null,
    val current: Boolean,
    val availableForNew: Boolean,
    val manageableForCurrentAgent: Boolean,
    val canSelectInThisContext: Boolean,
    val formDefinitionRef: RuntimeFormDefinitionRefDto? = null
)

private fun ComputerRuntimeSelectionCatalogDto.toDomain(
    expectedMachineId: String
): SharedCreateAgentRuntimeCatalog? {
    if (context != "new_agent" || machineId.trim() != expectedMachineId) return null
    // One row this client cannot read (missing field, wrong type, a new status value, an inconsistent
    // row) is left out on its own; it must not take every other runtime down with it. The first row
    // wins a duplicate id.
    val mapped =
        options
            .mapNotNull { row ->
                runCatching {
                    runtimeFormListJson.decodeFromJsonElement(ComputerRuntimeSelectionOptionDto.serializer(), row)
                }.getOrNull()?.toDomain()
            }
            .distinctBy { it.runtimeId }
    return SharedCreateAgentRuntimeCatalog(machineId = expectedMachineId, options = mapped)
}

private fun ComputerRuntimeSelectionOptionDto.toDomain(): SharedCreateAgentRuntimeOption? {
    val cleanRuntimeId = runtimeId.trim()
    if (
        cleanRuntimeId.isBlank() ||
        capabilityStatus !in setOf("available", "not_installed", "update_required") ||
        admissionStatus !in setOf("available_for_new", "grandfathered_current") ||
        admissionReason !in setOf(null, "feature_flag_off", "deprecated")
    ) {
        return null
    }
    val ref = formDefinitionRef?.toDomain()
    if (formDefinitionRef != null && (ref == null || ref.runtimeId != cleanRuntimeId)) return null
    val expectedSelectable =
        capabilityStatus == "available" &&
            admissionStatus == "available_for_new" &&
            admissionReason == null &&
            availableForNew &&
            !current
    if (canSelectInThisContext != expectedSelectable) return null
    return SharedCreateAgentRuntimeOption(
        runtimeId = cleanRuntimeId,
        capabilityStatus = capabilityStatus,
        admissionStatus = admissionStatus,
        admissionReason = admissionReason,
        current = current,
        availableForNew = availableForNew,
        manageableForCurrentAgent = manageableForCurrentAgent,
        canSelectInThisContext = canSelectInThisContext,
        formDefinitionRef = ref
    )
}

@Serializable
private data class AgentCreateStringMapAdditionalPropertiesDto(val type: String)

@Serializable
private data class AgentCreateFieldSchemaDto(
    val type: String,
    val title: String,
    val minLength: Int? = null,
    val format: String? = null,
    val writeOnly: Boolean = false,
    val additionalProperties: AgentCreateStringMapAdditionalPropertiesDto? = null
)

private fun AgentCreateFieldSchemaDto.toDomain(): SharedAgentCreateFieldSchema? {
    val cleanTitle = title.trim()
    if (cleanTitle.isBlank()) return null
    return when (type) {
        "string" -> {
            if (minLength != null && minLength < 0) return null
            if (format !in setOf(null, "uri")) return null
            if (additionalProperties != null) return null
            SharedAgentCreateFieldSchema.StringField(
                title = cleanTitle,
                minLength = minLength,
                format = format,
                writeOnly = writeOnly
            )
        }
        "object" -> {
            if (additionalProperties?.type != "string" || writeOnly || minLength != null || format != null) return null
            SharedAgentCreateFieldSchema.StringMapField(title = cleanTitle)
        }
        "boolean" -> {
            if (additionalProperties != null || writeOnly || minLength != null || format != null) return null
            SharedAgentCreateFieldSchema.BooleanField(title = cleanTitle)
        }
        else -> null
    }
}

@Serializable
private data class AgentCreateDataSchemaDto(
    val type: String,
    val additionalProperties: Boolean,
    val required: List<String>,
    val properties: Map<String, AgentCreateFieldSchemaDto>
)

@Serializable
private data class AgentCreateUiLayoutDto(val advanced: List<String>)

@Serializable
private data class AgentCreateVisibilityWhenDto(
    val pointer: String,
    @SerialName("in") val allowedValues: List<String>
)

@Serializable
private data class AgentCreateVisibilityDto(
    val pointer: String,
    @SerialName("when") val condition: AgentCreateVisibilityWhenDto
)

@Serializable
private data class AgentCreateFieldCopyDto(
    val label: String,
    val hint: String? = null,
    val placeholder: String? = null
)

@Serializable
private data class AgentCreateUiSchemaDto(
    val order: List<String>,
    val layout: AgentCreateUiLayoutDto,
    val visibility: List<AgentCreateVisibilityDto>,
    val localization: Map<String, AgentCreateFieldCopyDto>
)

@Serializable
private data class AgentCreateFormCapabilitiesDto(
    val providerKinds: List<String>,
    val writeOnlyPointers: List<String>,
    val forbiddenPointers: List<String>
)

@Serializable
private data class AgentCreateFormOptionSourceRefDto(
    val protocolVersion: Int,
    val runtimeId: String,
    val schemaVersion: String,
    val sourceId: String,
    val pointer: String,
    val kind: String,
    val dependsOn: String? = null
)

@Serializable
private data class AgentCreateFormDefinitionDto(
    val protocolVersion: Int,
    val runtimeId: String,
    val schemaVersion: String,
    val dataSchema: AgentCreateDataSchemaDto,
    val uiSchema: AgentCreateUiSchemaDto,
    val capabilities: AgentCreateFormCapabilitiesDto,
    val optionSources: Map<String, AgentCreateFormOptionSourceRefDto>
)

private fun AgentCreateFormDefinitionDto.toDomain(
    expectedRef: SharedRuntimeFormDefinitionRef
): SharedAgentCreateFormDefinition? {
    val actualRef = RuntimeFormDefinitionRefDto(protocolVersion, runtimeId, schemaVersion).toDomain()
    if (actualRef != expectedRef) return null
    return when {
        expectedRef.isSupportedBuiltInPi -> toBuiltInPiDomain(expectedRef)
        expectedRef.isSupportedKimiSdk -> toKimiSdkDomain(expectedRef)
        else -> null
    }
}

private fun SharedAgentCreateFieldSchema?.matchesIgnoringTitle(
    expected: SharedAgentCreateFieldSchema
): Boolean =
    when (expected) {
        is SharedAgentCreateFieldSchema.StringField ->
            this is SharedAgentCreateFieldSchema.StringField &&
                minLength == expected.minLength &&
                format == expected.format &&
                writeOnly == expected.writeOnly
        is SharedAgentCreateFieldSchema.StringMapField ->
            this is SharedAgentCreateFieldSchema.StringMapField
        is SharedAgentCreateFieldSchema.BooleanField ->
            this is SharedAgentCreateFieldSchema.BooleanField
    }

private fun AgentCreateFormDefinitionDto.toBuiltInPiDomain(
    expectedRef: SharedRuntimeFormDefinitionRef
): SharedAgentCreateFormDefinition? {
    if (dataSchema.type != "object" || dataSchema.additionalProperties) return null
    val properties = dataSchema.properties.mapValues { (_, field) -> field.toDomain() ?: return null }
    if (properties.keys != setOf("providerId", "apiKey", "baseUrl", "supportsImageInput", "model", "loadLocalPlugins", "envVars")) return null
    // Field titles are display copy owned by the server (e.g. the 09-24 "Load
    // local Pi plugins" -> "extensions" rename) and are already covered by
    // uiSchema.localization below; compare structure only so copy changes do
    // not knock the whole form out.
    if (!properties["providerId"].matchesIgnoringTitle(SharedAgentCreateFieldSchema.StringField("", 1, null, false))) return null
    if (!properties["apiKey"].matchesIgnoringTitle(SharedAgentCreateFieldSchema.StringField("", 1, null, true))) return null
    if (!properties["baseUrl"].matchesIgnoringTitle(SharedAgentCreateFieldSchema.StringField("", 1, "uri", false))) return null
    if (!properties["supportsImageInput"].matchesIgnoringTitle(SharedAgentCreateFieldSchema.BooleanField(""))) return null
    if (!properties["loadLocalPlugins"].matchesIgnoringTitle(SharedAgentCreateFieldSchema.BooleanField(""))) return null
    if (!properties["model"].matchesIgnoringTitle(SharedAgentCreateFieldSchema.StringField("", 1, null, false))) return null
    if (!properties["envVars"].matchesIgnoringTitle(SharedAgentCreateFieldSchema.StringMapField(""))) return null
    val required = dataSchema.required.map { it.trim() }
    if (required.any { it.isBlank() || it !in properties } || required.distinct().size != required.size) return null
    if (required.toSet() != setOf("providerId", "apiKey", "model")) return null
    val order = uiSchema.order.map { it.trim() }
    if (order.any { it.isBlank() } || order.distinct().size != order.size || order.toSet() != properties.keys) return null
    if (order != listOf("providerId", "apiKey", "baseUrl", "supportsImageInput", "model", "loadLocalPlugins", "envVars")) return null
    val advancedPointers = uiSchema.layout.advanced.map { it.trim() }
    if (advancedPointers.any { !it.isPropertyPointerTo(properties.keys) }) return null
    if (advancedPointers != listOf("/loadLocalPlugins", "/envVars")) return null
    val visibility =
        uiSchema.visibility.map { rule ->
            val pointer = rule.pointer.trim()
            val dependsOn = rule.condition.pointer.trim()
            val values = rule.condition.allowedValues.map { it.trim() }.toSet()
            if (
                !pointer.isPropertyPointerTo(properties.keys) ||
                !dependsOn.isPropertyPointerTo(properties.keys) ||
                values.isEmpty() ||
                values.any { it.isBlank() }
            ) {
                return null
            }
            SharedAgentCreateVisibilityRule(pointer, dependsOn, values)
        }
    if (uiSchema.localization.keys != properties.keys) return null
    val localization =
        uiSchema.localization.mapValues { (_, copy) ->
            val label = copy.label.trim()
            if (label.isBlank()) return null
            SharedAgentCreateFieldCopy(
                label = label,
                hint = copy.hint?.takeIf { it.isNotBlank() },
                placeholder = copy.placeholder?.takeIf { it.isNotBlank() }
            )
        }
    val providerKinds = capabilities.providerKinds.map { it.trim() }.toSet()
    if (providerKinds != setOf("preset", "gateway")) return null
    val writeOnlyPointers = capabilities.writeOnlyPointers.map { it.trim() }.toSet()
    val forbiddenPointers = capabilities.forbiddenPointers.map { it.trim() }.toSet()
    if (
        writeOnlyPointers.any { !it.isPropertyPointerTo(properties.keys) } ||
        forbiddenPointers != setOf("/hostUserState") ||
        writeOnlyPointers.intersect(forbiddenPointers).isNotEmpty()
    ) {
        return null
    }
    val schemaWriteOnlyPointers =
        properties.mapNotNull { (name, field) ->
            name.takeIf { (field as? SharedAgentCreateFieldSchema.StringField)?.writeOnly == true }?.let { "/$it" }
        }.toSet()
    if (schemaWriteOnlyPointers != writeOnlyPointers) return null
    val optionSources =
        optionSources.mapValues { (key, source) ->
            source.toDomain(expectedRef)?.takeIf { it.sourceId == key } ?: return null
        }
    if (optionSources.keys != setOf("provider", "model") || optionSources.values.any { !it.pointer.isPropertyPointerTo(properties.keys) }) return null
    if (optionSources["provider"] != SharedAgentCreateFormOptionSourceRef.Select(expectedRef, "provider", "/providerId")) return null
    if (optionSources["model"] != SharedAgentCreateFormOptionSourceRef.DependentSelect(expectedRef, "model", "/model", "/providerId")) return null
    val expectedGatewayVisibility = setOf("openai-compatible", "anthropic-compatible")
    if (
        visibility.toSet() != setOf(
            SharedAgentCreateVisibilityRule("/baseUrl", "/providerId", expectedGatewayVisibility),
            SharedAgentCreateVisibilityRule("/supportsImageInput", "/providerId", expectedGatewayVisibility)
        )
    ) return null
    return SharedAgentCreateFormDefinition(
        ref = expectedRef,
        dataSchema = SharedAgentCreateDataSchema(required.toSet(), properties),
        uiSchema = SharedAgentCreateUiSchema(order, advancedPointers.toSet(), visibility, localization),
        capabilities = SharedAgentCreateFormCapabilities(providerKinds, writeOnlyPointers, forbiddenPointers),
        optionSources = optionSources
    )
}

/**
 * kimi-sdk.create.v1 shape (server: botiverse/slock #7147): fields are exactly
 * `model` (required string), `reasoningEffort` (optional string), `envVars`
 * (string map); the only option source is the flat `model` select. Structural
 * invariants mirror the Built-in Pi branch; reasoning-effort values are never
 * read from this form — live per-model catalogs stay the only authority.
 */
private fun AgentCreateFormDefinitionDto.toKimiSdkDomain(
    expectedRef: SharedRuntimeFormDefinitionRef
): SharedAgentCreateFormDefinition? {
    if (dataSchema.type != "object" || dataSchema.additionalProperties) return null
    val properties = dataSchema.properties.mapValues { (_, field) -> field.toDomain() ?: return null }
    if (properties.keys != setOf("model", "reasoningEffort", "envVars")) return null
    val modelField = properties["model"] as? SharedAgentCreateFieldSchema.StringField ?: return null
    if (modelField.writeOnly) return null
    val effortField = properties["reasoningEffort"] as? SharedAgentCreateFieldSchema.StringField ?: return null
    if (effortField.writeOnly) return null
    if (properties["envVars"] !is SharedAgentCreateFieldSchema.StringMapField) return null
    val required = dataSchema.required.map { it.trim() }
    if (required.any { it.isBlank() || it !in properties } || required.distinct().size != required.size) return null
    if (required.toSet() != setOf("model")) return null
    val order = uiSchema.order.map { it.trim() }
    if (order.any { it.isBlank() } || order.distinct().size != order.size || order.toSet() != properties.keys) return null
    val advancedPointers = uiSchema.layout.advanced.map { it.trim() }
    if (advancedPointers.any { !it.isPropertyPointerTo(properties.keys) }) return null
    val visibility =
        uiSchema.visibility.map { rule ->
            val pointer = rule.pointer.trim()
            val dependsOn = rule.condition.pointer.trim()
            val values = rule.condition.allowedValues.map { it.trim() }.toSet()
            if (
                !pointer.isPropertyPointerTo(properties.keys) ||
                !dependsOn.isPropertyPointerTo(properties.keys) ||
                values.isEmpty() ||
                values.any { it.isBlank() }
            ) {
                return null
            }
            SharedAgentCreateVisibilityRule(pointer, dependsOn, values)
        }
    if (uiSchema.localization.keys != properties.keys) return null
    val localization =
        uiSchema.localization.mapValues { (_, copy) ->
            val label = copy.label.trim()
            if (label.isBlank()) return null
            SharedAgentCreateFieldCopy(
                label = label,
                hint = copy.hint?.takeIf { it.isNotBlank() },
                placeholder = copy.placeholder?.takeIf { it.isNotBlank() }
            )
        }
    val providerKinds = capabilities.providerKinds.map { it.trim() }.toSet()
    if (providerKinds.any { it.isBlank() }) return null
    val writeOnlyPointers = capabilities.writeOnlyPointers.map { it.trim() }.toSet()
    val forbiddenPointers = capabilities.forbiddenPointers.map { it.trim() }.toSet()
    if (
        writeOnlyPointers.any { !it.isPropertyPointerTo(properties.keys) } ||
        writeOnlyPointers.intersect(forbiddenPointers).isNotEmpty()
    ) {
        return null
    }
    val schemaWriteOnlyPointers =
        properties.mapNotNull { (name, field) ->
            name.takeIf { (field as? SharedAgentCreateFieldSchema.StringField)?.writeOnly == true }?.let { "/$it" }
        }.toSet()
    if (schemaWriteOnlyPointers != writeOnlyPointers) return null
    val optionSources =
        optionSources.mapValues { (key, source) ->
            source.toDomain(expectedRef)?.takeIf { it.sourceId == key } ?: return null
        }
    if (optionSources.keys != setOf("model")) return null
    if (optionSources["model"] != SharedAgentCreateFormOptionSourceRef.Select(expectedRef, "model", "/model")) return null
    return SharedAgentCreateFormDefinition(
        ref = expectedRef,
        dataSchema = SharedAgentCreateDataSchema(required.toSet(), properties),
        uiSchema = SharedAgentCreateUiSchema(order, advancedPointers.toSet(), visibility, localization),
        capabilities = SharedAgentCreateFormCapabilities(providerKinds, writeOnlyPointers, forbiddenPointers),
        optionSources = optionSources
    )
}

private fun AgentCreateFormOptionSourceRefDto.toDomain(
    expectedRef: SharedRuntimeFormDefinitionRef
): SharedAgentCreateFormOptionSourceRef? {
    val actualRef = RuntimeFormDefinitionRefDto(protocolVersion, runtimeId, schemaVersion).toDomain()
    if (actualRef != expectedRef || sourceId.isBlank() || pointer.isBlank()) return null
    return when (kind) {
        "select" -> {
            if (dependsOn != null) null
            else SharedAgentCreateFormOptionSourceRef.Select(expectedRef, sourceId.trim(), pointer.trim())
        }
        "dependent_select" -> {
            val cleanDependsOn = dependsOn?.trim().orEmpty()
            if (cleanDependsOn.isBlank()) null
            else SharedAgentCreateFormOptionSourceRef.DependentSelect(
                expectedRef,
                sourceId.trim(),
                pointer.trim(),
                cleanDependsOn
            )
        }
        else -> null
    }
}

@Serializable
private data class AgentCreateFormOptionDto(
    val value: String,
    val label: String,
    val providerKind: String? = null,
    // Wire-tolerant: the kimi-sdk model source may annotate entries with
    // reasoning metadata. Parsed so strict decoding does not reject them, but
    // deliberately NOT mapped into the domain — reasoning-effort authority is
    // the live runtime-models catalog only (see kimiSdkLiveReasoningCapability).
    val supportedReasoningEfforts: List<String>? = null,
    val defaultReasoningEffort: String? = null
)

private fun AgentCreateFormOptionDto.toDomain(): SharedAgentCreateFormOption? {
    val cleanValue = value.trim()
    val cleanLabel = label.trim()
    if (cleanValue.isBlank() || cleanLabel.isBlank() || providerKind !in setOf(null, "preset", "gateway")) return null
    return SharedAgentCreateFormOption(cleanValue, cleanLabel, providerKind)
}

@Serializable
private data class AgentCreateFormOptionSourceDto(
    val protocolVersion: Int,
    val runtimeId: String,
    val schemaVersion: String,
    val sourceId: String,
    val pointer: String,
    val kind: String,
    val dependsOn: String? = null,
    val options: List<AgentCreateFormOptionDto> = emptyList(),
    val defaultValue: String? = null,
    val optionsByValue: Map<String, List<AgentCreateFormOptionDto>> = emptyMap(),
    val defaultValueByValue: Map<String, String> = emptyMap(),
    val customValueAllowedByValue: Map<String, Boolean> = emptyMap()
)

private fun AgentCreateFormOptionSourceDto.toDomain(
    expectedRef: SharedRuntimeFormDefinitionRef,
    sourceRef: SharedAgentCreateFormOptionSourceRef
): SharedAgentCreateFormOptionSource? {
    val actualRef = RuntimeFormDefinitionRefDto(protocolVersion, runtimeId, schemaVersion).toDomain()
    if (
        actualRef != expectedRef ||
        sourceId.trim() != sourceRef.sourceId ||
        pointer.trim() != sourceRef.pointer
    ) {
        return null
    }
    return when {
        kind == "select" && sourceRef is SharedAgentCreateFormOptionSourceRef.Select -> {
            if (
                dependsOn != null ||
                optionsByValue.isNotEmpty() ||
                defaultValueByValue.isNotEmpty() ||
                customValueAllowedByValue.isNotEmpty()
            ) {
                return null
            }
            val mapped = options.mapNotNull { it.toDomain() }
            val cleanDefault = defaultValue?.trim().orEmpty()
            if (
                mapped.size != options.size ||
                mapped.map { it.value }.distinct().size != mapped.size ||
                cleanDefault !in mapped.map { it.value }
            ) {
                return null
            }
            SharedAgentCreateFormOptionSource.Select(expectedRef, sourceId.trim(), pointer.trim(), mapped, cleanDefault)
        }
        kind == "dependent_select" && sourceRef is SharedAgentCreateFormOptionSourceRef.DependentSelect -> {
            if (dependsOn?.trim() != sourceRef.dependsOn || options.isNotEmpty() || defaultValue != null) return null
            val mapped =
                optionsByValue.mapValues { (_, value) ->
                    val optionsForValue = value.mapNotNull { it.toDomain() }
                    if (
                        optionsForValue.size != value.size ||
                        optionsForValue.map { it.value }.distinct().size != optionsForValue.size
                    ) {
                        return null
                    }
                    optionsForValue
                }
            val providerValues = mapped.keys
            if (
                defaultValueByValue.keys.any { it !in providerValues } ||
                customValueAllowedByValue.keys != providerValues ||
                defaultValueByValue.any { (provider, value) -> value !in mapped[provider].orEmpty().map { it.value } }
            ) {
                return null
            }
            SharedAgentCreateFormOptionSource.DependentSelect(
                expectedRef,
                sourceId.trim(),
                pointer.trim(),
                sourceRef.dependsOn,
                mapped,
                defaultValueByValue.mapValues { it.value.trim() },
                customValueAllowedByValue
            )
        }
        else -> null
    }
}

private fun String.isPropertyPointerTo(properties: Set<String>): Boolean =
    startsWith("/") && substring(1) in properties && drop(1).contains('/').not()

@Serializable
private data class ComputerCommandResponseDto(val ok: Boolean)

@Serializable
internal data class RegisterComputerBody(val name: String)

internal fun buildRegisterComputerBody(name: String): String =
    computersApiJson.encodeToString(RegisterComputerBody(name = name))

internal fun buildComputerUpdateBody(name: String? = null, description: String? = null): String =
    computersApiJson.encodeToString(
        buildJsonObject {
            name?.let { put("name", it) }
            description?.let { put("description", it) }
        }
    )
