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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
// Runtime-auth request bodies leave an absent field out: the server refuses a `region` or `providerId`
// that is present (even null) on a runtime that takes none (slock runtimeAuthLoginRegionFits).
private val runtimeAuthBodyJson = Json {
    encodeDefaults = false
    explicitNulls = false
}
// Runtime-options and option-source lists are additive on the server side: a new field must not
// break shipped clients (Hands c453ba5f — slock #8600 added `runtimeFormV2` and strict decoding
// failed every Create Agent runtime list).
private val runtimeFormListJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = false
}

@Serializable
private data class AgentRuntimeProviderAvailabilityDto(val available: Boolean = false)

@Serializable
private data class HostedRuntimeModelOptionDto(val id: String, val label: String)

/** `picker` is absent from older servers: they may pick. */
@Serializable
private data class HostedRuntimeModelsDto(
    val models: List<HostedRuntimeModelOptionDto> = emptyList(),
    val default: String? = null,
    val defaultLabel: String? = null,
    val locked: Boolean = false,
    val picker: Boolean = true
)

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
    /** `GET agents/:id/runtime-options`: the runtimes this agent may switch to (`existing_agent` rows). */
    suspend fun agentRuntimeOptions(serverId: ServerId, agentId: String): HomeNetworkResponse =
        error("agentRuntimeOptions is not implemented")
    /** `GET agent-runtime-providers/:kind`: the deployment has the provider and the server its flag. */
    suspend fun agentRuntimeProvider(serverId: ServerId, kind: String): HomeNetworkResponse =
        error("agentRuntimeProvider is not implemented")
    /** `GET agent-runtime-providers/:kind/models`: the models a new cloud agent can start on. */
    suspend fun agentRuntimeProviderModels(serverId: ServerId, kind: String): HomeNetworkResponse =
        error("agentRuntimeProviderModels is not implemented")
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
    /** `GET …/runtime-account-usage/:provider` (task #137). */
    suspend fun runtimeAccountUsage(
        serverId: ServerId,
        machineId: String,
        provider: String
    ): HomeNetworkResponse = error("runtimeAccountUsage is not implemented")
    /**
     * `POST …/runtime-account-usage/:provider/refresh` with body `{reason}` (`manual` or
     * `stale_or_missing`); slock packages/server/src/routes/servers.ts.
     */
    suspend fun refreshRuntimeAccountUsage(
        serverId: ServerId,
        machineId: String,
        provider: String,
        body: String,
        priority: DataRequestPriority
    ): HomeNetworkResponse = error("refreshRuntimeAccountUsage is not implemented")
    /**
     * `GET …/runtime-auth` (slock packages/server/src/routes/runtimeAuthRoutes.ts): the viewer's
     * `canAct`, the Computer's `availability` and its runtimes' sign-in status.
     */
    suspend fun runtimeAuth(serverId: ServerId, machineId: String): HomeNetworkResponse =
        error("runtimeAuth is not implemented")
    /** `POST …/runtime-auth/:runtime/login` with `{region?, providerId?}` (RuntimeAuth.kt). */
    suspend fun startRuntimeLogin(serverId: ServerId, machineId: String, runtime: String, body: String): HomeNetworkResponse =
        error("startRuntimeLogin is not implemented")
    /** `POST …/runtime-auth/:runtime/login/:loginId/code` with `{code}`. Never log [body]. */
    suspend fun submitRuntimeLoginCode(
        serverId: ServerId,
        machineId: String,
        runtime: String,
        loginId: String,
        body: String
    ): HomeNetworkResponse = error("submitRuntimeLoginCode is not implemented")
    /** `POST …/runtime-auth/:runtime/login/:loginId/cancel`. */
    suspend fun cancelRuntimeLogin(serverId: ServerId, machineId: String, runtime: String, loginId: String): HomeNetworkResponse =
        error("cancelRuntimeLogin is not implemented")
    /** `POST …/runtime-auth/:runtime/logout` with `{providerId?}`. */
    suspend fun logoutRuntime(serverId: ServerId, machineId: String, runtime: String, body: String): HomeNetworkResponse =
        error("logoutRuntime is not implemented")
    /** `GET …/runtime-auth/:runtime/providers`. */
    suspend fun runtimeAuthProviders(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse =
        error("runtimeAuthProviders is not implemented")
    /** `POST …/runtime-auth/:runtime/connect-provider` with `{providerId, apiKey}`. Never log [body]. */
    suspend fun connectRuntimeProvider(serverId: ServerId, machineId: String, runtime: String, body: String): HomeNetworkResponse =
        error("connectRuntimeProvider is not implemented")
    /**
     * `POST …/runtime-account-usage/:provider/limit-reset` with body `{idempotencyKey}` (slock #13504,
     * packages/server/src/routes/runtimeAuthRoutes.ts): spends one of the account's limit resets.
     */
    suspend fun redeemRuntimeLimitReset(
        serverId: ServerId,
        machineId: String,
        provider: String,
        body: String
    ): HomeNetworkResponse = error("redeemRuntimeLimitReset is not implemented")
    /**
     * `POST …/runtime-account-usage/claude/limit-resets/read` with body `{consent?: true}` (slock
     * #14336, packages/server/src/routes/runtimeAuthRoutes.ts): which Claude limit resets the
     * account holds, read in Claude Code on the Computer.
     */
    suspend fun readClaudeLimitResets(
        serverId: ServerId,
        machineId: String,
        body: String
    ): HomeNetworkResponse = error("readClaudeLimitResets is not implemented")
    suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse
    /**
     * `POST servers/:id/machines/:machineId/computer/:action` with `action` `restart` (body `{}`) or
     * `upgrade` (body `{targetVersion}`); slock packages/server/src/routes/servers.ts.
     */
    suspend fun controlComputer(serverId: ServerId, machineId: String, action: String, body: String): HomeNetworkResponse =
        error("controlComputer is not implemented")
    /**
     * Computer owner v1 (slock #13344): `PUT servers/:id/machines/:machineId/owner` with body
     * `{ownerUserId}` (a member's id, or null for Public); answers `{machine}` as `GET …/machines` lists it.
     */
    suspend fun setMachineOwner(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse =
        error("setMachineOwner is not implemented")
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

    override suspend fun controlComputer(serverId: ServerId, machineId: String, action: String, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/computer/${action.trim()}",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun setMachineOwner(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/owner",
            method = "PUT",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/workspaces",
            serverIdOverride = serverId
        )

    override suspend fun runtimeOptions(serverId: ServerId, machineId: String): HomeNetworkResponse =
        networkClient.requestWithHeaders(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-options",
            // Opt in to the `runtimeFormV2` marker on each row (slock runtimeAdmissionService): only a
            // client that asks gets it, because released apps up to 1.13 decoded the rows strictly.
            // The server marks a row when the runtime has a v2 form and is available for new use or
            // current; a reply with no marked row at all is read as "unknown", not "no forms"
            // (runtimeFormV2Availability), so a dropped header cannot disable create.
            headers = mapOf(RUNTIME_FORM_V2_MARKER_HEADER to "1"),
            serverIdOverride = serverId
        )

    override suspend fun agentRuntimeOptions(serverId: ServerId, agentId: String): HomeNetworkResponse =
        networkClient.requestWithHeaders(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.trim()}/runtime-options",
            // Same opt-in as the computer's rows: the `runtimeFormV2` marker on each row.
            headers = mapOf(RUNTIME_FORM_V2_MARKER_HEADER to "1"),
            serverIdOverride = serverId
        )

    override suspend fun agentRuntimeProvider(serverId: ServerId, kind: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agent-runtime-providers/$kind",
            serverIdOverride = serverId
        )

    override suspend fun agentRuntimeProviderModels(serverId: ServerId, kind: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agent-runtime-providers/$kind/models",
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
            query = runtimeFormV2ClientCapabilityQuery(),
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
                addAll(runtimeFormV2ClientCapabilityQuery())
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
        networkClient.requestWithHeaders(
            priority = DataRequestPriority.Visible,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-account-usage/${provider.trim()}",
            headers = RUNTIME_USAGE_WINDOW_SCOPE_HEADERS,
            serverIdOverride = serverId
        )

    override suspend fun refreshRuntimeAccountUsage(
        serverId: ServerId,
        machineId: String,
        provider: String,
        body: String,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        networkClient.requestWithHeaders(
            priority = priority,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-account-usage/${provider.trim()}/refresh",
            headers = RUNTIME_USAGE_WINDOW_SCOPE_HEADERS,
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun runtimeAuth(serverId: ServerId, machineId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-auth",
            serverIdOverride = serverId
        )

    private fun runtimeAuthPath(serverId: ServerId, machineId: String, runtime: String): String =
        "servers/${serverId.value}/machines/${machineId.trim()}/runtime-auth/${runtime.trim()}"

    override suspend fun startRuntimeLogin(serverId: ServerId, machineId: String, runtime: String, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "${runtimeAuthPath(serverId, machineId, runtime)}/login",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun submitRuntimeLoginCode(
        serverId: ServerId,
        machineId: String,
        runtime: String,
        loginId: String,
        body: String
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "${runtimeAuthPath(serverId, machineId, runtime)}/login/${loginId.trim()}/code",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun cancelRuntimeLogin(serverId: ServerId, machineId: String, runtime: String, loginId: String): HomeNetworkResponse =
        networkClient.request(
            // Fire-and-forget after the dialog closed: nothing waits on it.
            priority = DataRequestPriority.Visible,
            path = "${runtimeAuthPath(serverId, machineId, runtime)}/login/${loginId.trim()}/cancel",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = "{}"
        )

    override suspend fun logoutRuntime(serverId: ServerId, machineId: String, runtime: String, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "${runtimeAuthPath(serverId, machineId, runtime)}/logout",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun runtimeAuthProviders(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "${runtimeAuthPath(serverId, machineId, runtime)}/providers",
            serverIdOverride = serverId
        )

    override suspend fun connectRuntimeProvider(serverId: ServerId, machineId: String, runtime: String, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "${runtimeAuthPath(serverId, machineId, runtime)}/connect-provider",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun redeemRuntimeLimitReset(
        serverId: ServerId,
        machineId: String,
        provider: String,
        body: String
    ): HomeNetworkResponse =
        networkClient.requestWithHeaders(
            // The person confirmed it and waits for the outcome.
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-account-usage/${provider.trim()}/limit-reset",
            // The answer carries the usage read right after it: its windows' scope too.
            headers = RUNTIME_USAGE_WINDOW_SCOPE_HEADERS,
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun readClaudeLimitResets(
        serverId: ServerId,
        machineId: String,
        body: String
    ): HomeNetworkResponse =
        networkClient.request(
            // The person asked for the list and waits for it.
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-account-usage/claude/limit-resets/read",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/workspaces/${directoryName.trim()}",
            method = "DELETE",
            serverIdOverride = serverId
        )
}

/**
 * One item of `machines` in `GET /api/servers/:id/machines`, as an incremental-sync `machine` row
 * carries it (botiverse/slock 0d030e341 routes/sync.ts buildMachineRows). The list-level
 * `latestComputerVersion` / release notes are not on the row; the caller keeps the list's.
 */
internal fun parseSyncedComputerRow(row: kotlinx.serialization.json.JsonObject): ComputerSummary? =
    runCatching { computersApiJson.decodeFromJsonElement(ComputerDto.serializer(), row).toSummary() }.getOrNull()

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
            val releaseNotes = wire.latestComputerReleaseNotes?.toSnapshot()
            ComputerListSummary(
                machines =
                    wire.machines.mapNotNull { dto ->
                        dto.toSummary()?.copy(
                            latestComputerVersion = wire.latestComputerVersion,
                            latestComputerReleaseNotes = releaseNotes
                        )
                    },
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

    /** Restart a managed Computer's service; the result arrives later as `computer:restart:done`. */
    suspend fun restartComputer(serverId: ServerId, machineId: String): DataResult<ComputerRestartSummary> {
        val response = transport.controlComputer(serverId, machineId, COMPUTER_CONTROL_RESTART, buildComputerRestartBody())
        if (!response.isSuccessful) return response.toComputerControlError()
        return response.mapComputersBody("computer_restart_contract_mismatch", ComputerRestartResponseDto.serializer()) { wire ->
            if (!wire.ok) null else ComputerRestartSummary(requestId = wire.requestId?.takeIf { it.isNotBlank() }, queued = wire.queued)
        }
    }

    /** Remote upgrade v2: record one upgrade request for [targetVersion]; the reconnect settles it. */
    suspend fun upgradeComputer(
        serverId: ServerId,
        machineId: String,
        targetVersion: String
    ): DataResult<SettingsComputerUpgradeRequestSnapshot> {
        val response =
            transport.controlComputer(serverId, machineId, COMPUTER_CONTROL_UPGRADE, buildComputerUpgradeBody(targetVersion))
        if (!response.isSuccessful) return response.toComputerControlError()
        return response.mapComputersBody("computer_upgrade_contract_mismatch", ComputerUpgradeResponseDto.serializer()) { wire ->
            wire.upgradeRequest?.toSnapshot()
        }
    }

    /**
     * Computer owner v1: record [ownerUserId] as the Computer's owner, or make it Public (null). The
     * answer's machine is the row as the list shows it; an answer without one still succeeded.
     */
    suspend fun setMachineOwner(serverId: ServerId, machineId: String, ownerUserId: String?): DataResult<ComputerOwnerChange> =
        transport.setMachineOwner(serverId, machineId, buildComputerOwnerBody(ownerUserId)).mapComputersBody(
            "computer_owner_contract_mismatch",
            ComputerOwnerResponseDto.serializer()
        ) { wire -> ComputerOwnerChange(machine = wire.machine?.toSummary()) }

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

    /**
     * Asks the Computer for a new snapshot. A manual refresh waits for the Computer's answer
     * (Foreground: the person is waiting); a background one is fire-and-forget.
     */
    suspend fun refreshRuntimeAccountUsage(
        serverId: ServerId,
        machineId: String,
        provider: RuntimeAccountUsageProvider,
        reason: RuntimeAccountUsageRefreshReason
    ): DataResult<RuntimeAccountUsageRefresh> =
        transport.refreshRuntimeAccountUsage(
            serverId = serverId,
            machineId = machineId,
            provider = provider.wire,
            body = buildRuntimeAccountUsageRefreshBody(reason),
            priority =
                if (reason == RuntimeAccountUsageRefreshReason.Manual) {
                    DataRequestPriority.Foreground
                } else {
                    DataRequestPriority.Background
                }
        ).mapComputersBody(
            "computer_runtime_account_usage_refresh_contract_mismatch",
            JsonElement.serializer()
        ) { wire -> wire.toRuntimeAccountUsageRefresh() }

    /** Who may act on the Computer's runtimes (web `RuntimeAuthClient.read`). */
    suspend fun runtimeAuthView(serverId: ServerId, machineId: String): DataResult<RuntimeAuthView> =
        transport.runtimeAuth(serverId, machineId).mapComputersBody(
            "computer_runtime_auth_contract_mismatch",
            JsonElement.serializer()
        ) { wire -> wire.toRuntimeAuthView() }

    /**
     * Starts a login on the Computer (web `RuntimeAuthClient.startLogin`): the login id, checked
     * before anyone may use it. [region] for Kimi, [providerId] for Pi.
     */
    suspend fun startRuntimeLogin(
        serverId: ServerId,
        machineId: String,
        runtime: RuntimeAuthRuntime,
        region: RuntimeAuthLoginRegion? = null,
        providerId: String? = null,
        oauthMethod: String? = null,
    ): RuntimeAuthCall<String> {
        val body =
            runtimeAuthBodyJson.encodeToString(RuntimeAuthLoginStartBody.serializer(), RuntimeAuthLoginStartBody(region?.wire, providerId, oauthMethod))
        val response = transport.startRuntimeLogin(serverId, machineId, runtime.wire, body)
        return response.runtimeAuthCall { wire ->
            ((wire as? JsonObject)?.get("loginId") as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull?.takeIf(::isRuntimeAuthLoginId)
        }
    }

    /** Sends the pasted code to that Computer's login (web `submitCode`). Never log [code]. */
    suspend fun submitRuntimeLoginCode(
        serverId: ServerId,
        machineId: String,
        runtime: RuntimeAuthRuntime,
        loginId: String,
        code: String,
    ): RuntimeAuthCall<Unit> {
        val body = runtimeAuthBodyJson.encodeToString(RuntimeAuthLoginCodeBody.serializer(), RuntimeAuthLoginCodeBody(code))
        val response = transport.submitRuntimeLoginCode(serverId, machineId, runtime.wire, loginId, body)
        return if (response.isSuccessful) RuntimeAuthCall.Done(Unit) else RuntimeAuthCall.Refused(response.runtimeAuthRequestError())
    }

    /** Cancels a running login; its answer is not read (web `cancelLogin().catch(() => undefined)`). */
    suspend fun cancelRuntimeLogin(serverId: ServerId, machineId: String, runtime: RuntimeAuthRuntime, loginId: String) {
        transport.cancelRuntimeLogin(serverId, machineId, runtime.wire, loginId)
    }

    /** Signs the runtime out (Pi: [providerId]'s sign-in only). */
    suspend fun logoutRuntime(
        serverId: ServerId,
        machineId: String,
        runtime: RuntimeAuthRuntime,
        providerId: String? = null,
    ): RuntimeAuthCall<RuntimeAuthLogoutResult> {
        val body = runtimeAuthBodyJson.encodeToString(RuntimeAuthLogoutBody.serializer(), RuntimeAuthLogoutBody(providerId))
        return transport.logoutRuntime(serverId, machineId, runtime.wire, body).runtimeAuthCall { wire ->
            (wire as? JsonObject)?.get("result")?.toRuntimeAuthLogoutResult()
        }
    }

    /** The providers the runtime lists on the Computer (Pi's `/login` list), read there. */
    suspend fun runtimeAuthProviders(serverId: ServerId, machineId: String, runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeAuthProvidersResult> =
        transport.runtimeAuthProviders(serverId, machineId, runtime.wire).runtimeAuthCall { wire ->
            (wire as? JsonObject)?.get("result")?.toRuntimeAuthProvidersResult()
        }

    /** Saves a provider's API key on the Computer (Pi). The key goes into this one request body; never log it. */
    suspend fun connectRuntimeProvider(
        serverId: ServerId,
        machineId: String,
        runtime: RuntimeAuthRuntime,
        providerId: String,
        apiKey: String,
    ): RuntimeAuthCall<RuntimeAuthProviderConnectResult> {
        val body = runtimeAuthBodyJson.encodeToString(RuntimeAuthConnectProviderBody.serializer(), RuntimeAuthConnectProviderBody(providerId, apiKey))
        return transport.connectRuntimeProvider(serverId, machineId, runtime.wire, body).runtimeAuthCall { wire ->
            (wire as? JsonObject)?.get("result")?.toRuntimeAuthProviderConnectResult()
        }
    }

    /**
     * Spends one of the account's limit resets (web `RuntimeAuthClient.redeemLimitReset`). Any
     * answer without a readable outcome is [RuntimeLimitResetLoad.Failed] with the status and the
     * route's `code`: the reset may or may not have been spent, so the caller keeps the key.
     */
    suspend fun redeemRuntimeLimitReset(
        serverId: ServerId,
        machineId: String,
        provider: RuntimeAccountUsageProvider,
        idempotencyKey: RuntimeLimitResetIdempotencyKey,
        /** Claude: the one kind the person chose (required by the server for Claude, refused for others). */
        kind: RuntimeLimitResetKind? = null
    ): RuntimeLimitResetLoad {
        val response =
            transport.redeemRuntimeLimitReset(
                serverId = serverId,
                machineId = machineId,
                provider = provider.wire,
                body = if (kind == null) buildRuntimeLimitResetBody(idempotencyKey) else buildClaudeLimitResetBody(idempotencyKey, kind)
            )
        if (!response.isSuccessful) {
            return RuntimeLimitResetLoad.Failed(
                RuntimeLimitResetRefusal(status = response.code.takeIf { it > 0 }, code = response.computersRouteErrorCodeOrNull())
            )
        }
        val reply =
            response.bodyText?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { computersApiJson.parseToJsonElement(body) }.getOrNull()?.toRuntimeLimitResetReply()
            }
        return reply?.let(RuntimeLimitResetLoad::Done)
            ?: RuntimeLimitResetLoad.Failed(RuntimeLimitResetRefusal(status = response.code, code = "malformed_response"))
    }

    /**
     * Reads which Claude limit resets the account holds (web `RuntimeAuthClient.readLimitResets`).
     * [consent]: the person accepted that, at the 5-hour limit, opening the command can use the
     * older program's 5-hour reset. No readable result is [RuntimeLimitResetsReadLoad.Failed].
     */
    suspend fun readClaudeLimitResets(serverId: ServerId, machineId: String, consent: Boolean): RuntimeLimitResetsReadLoad {
        val response = transport.readClaudeLimitResets(serverId, machineId, buildClaudeLimitResetsReadBody(consent))
        if (!response.isSuccessful) {
            return RuntimeLimitResetsReadLoad.Failed(
                RuntimeLimitResetRefusal(status = response.code.takeIf { it > 0 }, code = response.computersRouteErrorCodeOrNull())
            )
        }
        val result =
            response.bodyText?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { computersApiJson.parseToJsonElement(body) }.getOrNull()?.toRuntimeLimitResetsReadReply()
            }
        return result?.let(RuntimeLimitResetsReadLoad::Done)
            ?: RuntimeLimitResetsReadLoad.Failed(RuntimeLimitResetRefusal(status = response.code, code = "malformed_response"))
    }

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

    /**
     * `GET agents/:id/runtime-options` (slock `packages/server/src/routes/agents.ts`
     * `agentRouter.get("/:id/runtime-options")`, rows from runtimeAdmissionService
     * `projectExistingAgentRuntimeOptions`): `{ context: "existing_agent", machineId: string | null,
     * options: RuntimeSelectionOption[] }`. The rows are what this agent may switch to on this server,
     * a deprecated runtime the server offers again included; its current runtime's row carries
     * `current: true` and, when this server deprecated it, `admissionReason: "deprecated"`.
     */
    suspend fun agentRuntimeOptions(serverId: ServerId, agentId: String): DataResult<ExistingAgentRuntimeOptions> {
        val cleanAgentId = agentId.trim()
        if (cleanAgentId.isBlank()) return runtimeFormContractError("agent_runtime_options_agent_required")
        return transport.agentRuntimeOptions(serverId, cleanAgentId).mapComputersBody(
            "agent_runtime_options_contract_mismatch",
            AgentRuntimeSelectionCatalogDto.serializer(),
            runtimeFormListJson
        ) { wire -> wire.toDomain(cleanAgentId) }
    }

    suspend fun agentRuntimeProviderAvailable(serverId: ServerId, kind: String): DataResult<Boolean> =
        transport.agentRuntimeProvider(serverId, kind).mapComputersBody(
            "agent_runtime_provider_contract_mismatch",
            AgentRuntimeProviderAvailabilityDto.serializer(),
            runtimeFormListJson
        ) { wire -> wire.available }

    suspend fun agentRuntimeProviderModels(serverId: ServerId, kind: String): DataResult<HostedRuntimeModels> =
        transport.agentRuntimeProviderModels(serverId, kind).mapComputersBody(
            "agent_runtime_provider_models_contract_mismatch",
            HostedRuntimeModelsDto.serializer(),
            runtimeFormListJson
        ) { wire ->
            HostedRuntimeModels(
                models = wire.models.map { HostedRuntimeModelOption(it.id, it.label) },
                defaultLabel = wire.defaultLabel?.takeIf { it.isNotBlank() },
                defaultModel = wire.default?.takeIf { it.isNotBlank() },
                locked = wire.locked,
                picker = wire.picker
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
    /** Server `osRelease`: the reported OS name and version ("Ubuntu 24.04"), Linux only; null otherwise. */
    val osRelease: String? = null,
    /** Server `hostProvider`: a shared `HOST_PROVIDER_LABELS` id ("hetzner"); null when not recognized or an older server. */
    val hostProvider: String? = null,
    /** Server `installerRepairHint.reason` (a known one only); null when there is nothing to hint or an older server. */
    val installerRepairHint: String? = null,
    /** Server `runtimeVersions`: runtime id -> the CLI version the Computer detected ("2.1.59 (Claude Code)"). */
    val runtimeVersions: Map<String, String> = emptyMap(),
    /** `machines.userId`: the user who registered the machine (server's "machine creator" authority). */
    val creatorUserId: String? = null,
    /** True when the requester attached the Computer backing this machine (false for raw daemons). */
    val attachedByCurrentUser: Boolean = false,
    /** Active agents assigned to the machine. */
    val agentCount: Int = 0,
    /** The server's policy-aware upgrade decision; null for raw daemons / older servers. */
    val computerUpgradeAvailable: Boolean? = null,
    val computerBroadcastPolicy: SettingsComputerBroadcastPolicySnapshot? = null,
    val upgradeRequest: SettingsComputerUpgradeRequestSnapshot? = null,
    val remoteUpgradeSupported: Boolean? = null,
    val versionGate: SettingsComputerVersionGateSnapshot? = null,
    /** List-level `latestComputerVersion`, copied onto each row by [RaftComputersApiClient.machines]. */
    val latestComputerVersion: String? = null,
    /** List-level `latestComputerReleaseNotes`, copied onto each row by [RaftComputersApiClient.machines]. */
    val latestComputerReleaseNotes: SettingsComputerReleaseNotesSnapshot? = null,
    /** The human who attached a managed Computer (server `creator`); null for raw daemons or a departed attacher. */
    val creator: SettingsComputerCreatorSnapshot? = null,
    /** Computer owner v1 (server `ownerUserId`): who Raft records as the owner; null = Public (or an older server). */
    val ownerUserId: String? = null,
    /** The owner's public identity (server `owner`); null when Public or the server could not resolve it. */
    val owner: SettingsComputerCreatorSnapshot? = null,
    /** Server `agentScopes`: per-agent memory scope state; null when offline, off, or from an older server/daemon. */
    val agentScopes: SettingsComputerAgentScopesSnapshot? = null,
    /** Server `diskStatus`: free and total disk while online; null when offline or never reported. */
    val diskStatus: ComputerSizeStatus? = null,
    /** Server `memoryStatus`: free and total memory while online; null when offline, never reported, or an older Computer. */
    val memoryStatus: ComputerSizeStatus? = null
)

/**
 * slock `packages/shared/src/index.ts` `MachineDiskStatus` (and `MachineMemoryStatus`, the same
 * shape): `{ availableBytes, totalBytes }`. Only a readable report is kept (web
 * `readableDiskStatus`): a positive total and a free size within it (not negative, not over the
 * total, web #11855).
 */
internal data class ComputerSizeStatus(
    val availableBytes: Double,
    val totalBytes: Double
)

/** `POST …/computer/restart` accepted: [requestId] correlates the later `computer:restart:done`. */
internal data class ComputerRestartSummary(val requestId: String?, val queued: Boolean)

internal data class ComputerRegistrationSummary(val machine: ComputerSummary, val apiKey: String, val apiKeyPrefix: String)
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
    val models: List<RuntimeModelInfo>,
    val modelCount: Int,
    val defaultModel: String?
)
internal data class ComputerCommandResult(val ok: Boolean)

/** `PUT …/owner` accepted: [machine] is the Computer as the list shows it (null when the server sent none). */
internal data class ComputerOwnerChange(val machine: ComputerSummary?)

/** `{ ownerUserId }`, null encoded as JSON null (Public). */
internal fun buildComputerOwnerBody(ownerUserId: String?): String =
    buildJsonObject { put("ownerUserId", ownerUserId?.trim()?.takeIf { it.isNotBlank() }) }.toString()

@Serializable
private data class ComputerOwnerResponseDto(val machine: ComputerDto? = null)

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
private data class ComputersRouteErrorDto(val error: String? = null, val code: String? = null)

/** The route's machine-readable `code` (`computer_offline`, …), when its error body has one. */
/** A runtime-auth answer: [read] its body, or the refusal with its status, `code` and trace id. */
private inline fun <T> HomeNetworkResponse.runtimeAuthCall(read: (JsonElement) -> T?): RuntimeAuthCall<T> {
    if (!isSuccessful) return RuntimeAuthCall.Refused(runtimeAuthRequestError())
    val value =
        bodyText?.takeIf { it.isNotBlank() }?.let { body -> runCatching { computersApiJson.parseToJsonElement(body) }.getOrNull() }?.let(read)
    return value?.let { RuntimeAuthCall.Done(it) }
        ?: RuntimeAuthCall.Refused(RuntimeAuthRequestError(status = code, code = "malformed_response", traceId = traceId))
}

/** Only the status, the route's `code` and the trace id are read: never the request. */
private fun HomeNetworkResponse.runtimeAuthRequestError(): RuntimeAuthRequestError =
    RuntimeAuthRequestError(status = code.takeIf { it > 0 }, code = computersRouteErrorCodeOrNull(), traceId = traceId)

private fun HomeNetworkResponse.computersRouteErrorCodeOrNull(): String? {
    val payload = bodyText?.takeIf { it.isNotBlank() }
        ?: errorBodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        computersApiJson.decodeFromString(ComputersRouteErrorDto.serializer(), payload).code?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

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

private fun HomeNetworkResponse.mapComputersCommand(contractCode: String): DataResult<ComputerCommandResult> =
    mapComputersBody(contractCode, ComputerCommandResponseDto.serializer()) { wire ->
        if (!wire.ok) null else ComputerCommandResult(ok = true)
    }

@Serializable
private data class ComputersListResponseDto(
    val machines: List<ComputerDto>,
    val latestDaemonVersion: String? = null,
    val latestComputerVersion: String? = null,
    // Display-only notes for exactly `latestComputerVersion` (routes/servers.ts GET /:id/machines,
    // services/computerVersionService.ts getLatestComputerReleaseNotes).
    val latestComputerReleaseNotes: ComputerReleaseNotesDto? = null
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
    // services/machineReadModel.ts `osRelease: normalizeOsRelease(machine.osRelease)` (shared
    // OS_RELEASE_MAX_LENGTH): the OS name and version the Computer read from os-release.
    val osRelease: String? = null,
    // services/machineReadModel.ts `hostProvider: isHostProvider(machine.hostProvider) ? machine.hostProvider : null`
    // (packages/shared/src/index.ts HOST_PROVIDER_LABELS ids, db/schema.ts `host_provider`): the cloud / VM
    // platform the Computer recognized; null when none and from older servers (absent).
    val hostProvider: String? = null,
    // routes/servers.ts GET /:id/machines `installerRepairHint: await readInstallerRepairHint(...)`
    // (packages/shared/src/index.ts ComputerInstallerRepairHint, #proj-raft-computer task #34):
    // advisory, the installer state needs a repair run on the machine; null when nothing to hint.
    val installerRepairHint: ComputerInstallerRepairHintDto? = null,
    // services/machineReadModel.ts `runtimeVersions: Record<string, string>` (runtime id -> the version
    // string the Computer's `ready` reported, e.g. "2.1.59 (Claude Code)"); the usage sheet's version line.
    val runtimeVersions: Map<String, String> = emptyMap(),
    val description: String? = null,
    val lastHeartbeat: String? = null,
    val createdAt: String? = null,
    // slock packages/server/src/services/machineReadModel.ts (MachineReadModel) and the
    // GET /servers/:id/machines projection in routes/servers.ts.
    val userId: String? = null,
    val computerAttachedByCurrentUser: Boolean = false,
    val agentCount: Int = 0,
    val computerUpgradeAvailable: Boolean? = null,
    // GET /:id/machines in routes/servers.ts: managed Computers carry the policy projection, the latest
    // upgrade request and remoteUpgradeSupported; every row carries the version floor verdict.
    val computerBroadcastPolicy: ComputerBroadcastPolicyDto? = null,
    val upgradeRequest: ComputerUpgradeRequestDto? = null,
    val remoteUpgradeSupported: Boolean? = null,
    val versionGate: ComputerVersionGateDto? = null,
    // GET /:id/machines in routes/servers.ts: `creator: computerLinkedMachineCreators.get(m.id) ?? null`,
    // the active attacher of a managed Computer (services/computerCredentialService.ts ComputerCreatorSummary).
    val creator: ComputerCreatorDto? = null,
    // Computer owner v1 (slock #13344): `ownerUserId` (null = a Public Computer) and the resolved
    // `owner` (the same public identity as `creator`). Not the connector; display and transfer only.
    val ownerUserId: String? = null,
    val owner: ComputerCreatorDto? = null,
    // services/machineReadModel.ts `agentScopes: MachineAgentScopesStatus | null` (packages/shared/src/agentMemoryLimit.ts),
    // null unless the machine is online and its daemon reports `machine:agent_scopes`.
    val agentScopes: MachineAgentScopesDto? = null,
    // services/machineReadModel.ts `diskStatus: MachineDiskStatus | null` and
    // `memoryStatus: MachineMemoryStatus | null` (packages/shared/src/index.ts), both null unless
    // the machine is online; memory is absent from Computers older than its report (slock #11032).
    val diskStatus: MachineSizeStatusDto? = null,
    val memoryStatus: MachineSizeStatusDto? = null
)

/**
 * slock `packages/shared/src/index.ts` `MachineDiskStatus` / `MachineMemoryStatus`:
 * `{ availableBytes: number, totalBytes: number }`. Read as doubles so a JavaScript number in any
 * spelling decodes; [toStatus] drops a report web `readableDiskStatus` would not show.
 */
@Serializable
private data class MachineSizeStatusDto(
    val availableBytes: Double? = null,
    val totalBytes: Double? = null
) {
    fun toStatus(): ComputerSizeStatus? {
        val total = totalBytes?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val available = availableBytes?.takeIf { it.isFinite() && it >= 0.0 && it <= total } ?: return null
        return ComputerSizeStatus(availableBytes = available, totalBytes = total)
    }
}

/**
 * slock packages/shared/src/agentMemoryLimit.ts `MachineAgentScopesStatus`:
 * `{ state, limitsConfigured, limitsNotApplied, reason?, memoryMax?, memoryHigh? }`.
 */
@Serializable
private data class MachineAgentScopesDto(
    val state: String? = null,
    val limitsConfigured: Boolean? = null,
    val limitsNotApplied: Boolean? = null,
    val reason: String? = null,
    val memoryMax: String? = null,
    val memoryHigh: String? = null
) {
    // Same shape check as the shared `normalizeMachineAgentScopesStatus`: a known state and both flags, else dropped.
    fun toSnapshot(): SettingsComputerAgentScopesSnapshot? {
        val cleanState = state?.takeIf { it in MACHINE_AGENT_SCOPE_STATES } ?: return null
        return SettingsComputerAgentScopesSnapshot(
            state = cleanState,
            limitsConfigured = limitsConfigured ?: return null,
            limitsNotApplied = limitsNotApplied ?: return null,
            reason = reason?.takeIf { MACHINE_AGENT_SCOPE_REASON.matches(it) },
            memoryMax = memoryMax?.takeIf { it.isNotBlank() && it.length <= 32 },
            memoryHigh = memoryHigh?.takeIf { it.isNotBlank() && it.length <= 32 }
        )
    }
}

private val MACHINE_AGENT_SCOPE_STATES = setOf("pending", "available", "unavailable", "suspended", "stopped")
private val MACHINE_AGENT_SCOPE_REASON = Regex("^[a-z][a-z0-9_]{0,63}$")

/**
 * slock packages/server/src/services/computerCredentialService.ts `ComputerCreatorSummary`:
 * `{ type: "human", id, name, displayName: string | null, avatarUrl: string | null, gravatarHash }`.
 */
@Serializable
private data class ComputerCreatorDto(
    val type: String? = null,
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val gravatarHash: String? = null
) {
    fun toSnapshot(): SettingsComputerCreatorSnapshot? {
        val cleanId = id.trim().takeIf { it.isNotBlank() } ?: return null
        val cleanName = name.trim().takeIf { it.isNotBlank() } ?: return null
        return SettingsComputerCreatorSnapshot(
            id = cleanId,
            name = cleanName,
            displayName = displayName?.trim()?.takeIf { it.isNotBlank() },
            avatarUrl = avatarUrl?.trim()?.takeIf { it.isNotBlank() },
            gravatarHash = gravatarHash?.trim()?.takeIf { it.isNotBlank() }
        )
    }
}

@Serializable
private data class ComputerBroadcastPolicyDto(
    val eligibility: String? = null,
    val targetVersion: String? = null,
    val policyRevision: String? = null,
    val reasonCode: String? = null
) {
    fun toSnapshot(): SettingsComputerBroadcastPolicySnapshot? {
        val value = eligibility?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return SettingsComputerBroadcastPolicySnapshot(
            eligibility = value,
            targetVersion = targetVersion?.trim()?.takeIf { it.isNotBlank() },
            policyRevision = policyRevision,
            reasonCode = reasonCode
        )
    }
}

@Serializable
private data class ComputerUpgradeRequestDto(
    val id: String? = null,
    val targetVersion: String? = null,
    val requestedAt: String? = null,
    val state: String? = null,
    val observedVersion: String? = null,
    val reason: String? = null,
    val resolvedAt: String? = null,
    // computerUpgradeRequestService.ts `projectComputerUpgradeRequest` (slock #13506): the request's
    // own deadline, and while its Computer waits for agents first, `phase` with this server's agents.
    val deadlineAt: String? = null,
    val phase: String? = null,
    val drainingAgentIds: List<String>? = null,
    val drainDeadlineAt: String? = null,
    // projectComputerUpgradeRequest `failureHint: row.outcome === "failed" ? upgradeFailureHintFromReason(row.reason) : null`
    // (packages/shared/src/index.ts ComputerUpgradeFailureHint).
    val failureHint: ComputerUpgradeFailureHintDto? = null
) {
    fun toSnapshot(): SettingsComputerUpgradeRequestSnapshot? {
        val requestId = id?.takeIf { it.isNotBlank() } ?: return null
        val target = targetVersion?.takeIf { it.isNotBlank() } ?: return null
        return SettingsComputerUpgradeRequestSnapshot(
            id = requestId,
            targetVersion = target,
            requestedAt = requestedAt.orEmpty(),
            state = state?.takeIf { it.isNotBlank() } ?: "pending",
            observedVersion = observedVersion,
            reason = reason,
            resolvedAt = resolvedAt,
            deadlineAt = deadlineAt?.trim()?.takeIf { it.isNotBlank() },
            phase = phase?.trim()?.takeIf { it == "draining" || it == "installing" },
            drainingAgentIds = drainingAgentIds.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            drainDeadlineAt = drainDeadlineAt?.trim()?.takeIf { it.isNotBlank() },
            failureHint = failureHint?.toSnapshot()
        )
    }
}

/** Shared `COMPUTER_INSTALLER_REPAIR_HINT_REASONS`: a reason this build does not know is no hint. */
internal val COMPUTER_INSTALLER_REPAIR_HINT_REASONS = setOf("unrecovered_operation")

/** Shared `ComputerInstallerRepairHint`. */
@Serializable
private data class ComputerInstallerRepairHintDto(val reason: String? = null)

/** Shared `ComputerUpgradeFailureHint`: `reason` is `unsafe_kill_mode` or `installation_held`. */
@Serializable
private data class ComputerUpgradeFailureHintDto(
    val reason: String? = null,
    val unit: String? = null,
    val killMode: String? = null,
    val scope: String? = null
) {
    fun toSnapshot(): SettingsComputerUpgradeFailureHintSnapshot? {
        val known = reason?.takeIf { it == "unsafe_kill_mode" || it == "installation_held" } ?: return null
        return SettingsComputerUpgradeFailureHintSnapshot(
            reason = known,
            unit = unit?.trim()?.takeIf { it.isNotBlank() },
            killMode = killMode?.trim()?.takeIf { it.isNotBlank() },
            scope = scope?.takeIf { it == "user" || it == "system" }
        )
    }
}

@Serializable
private data class ComputerVersionGateDto(
    val reason: String? = null,
    val minVersion: String? = null,
    val enforcedAt: String? = null,
    val enforced: Boolean = false
) {
    fun toSnapshot(): SettingsComputerVersionGateSnapshot? {
        val gateReason = reason?.takeIf { it.isNotBlank() } ?: return null
        return SettingsComputerVersionGateSnapshot(
            reason = gateReason,
            minVersion = minVersion.orEmpty(),
            enforcedAt = enforcedAt,
            enforced = enforced
        )
    }
}

/** packages/shared/src/computerReleaseNotes.ts `ComputerReleaseNotes`: `{version, en?, "zh-CN"?}`. */
@Serializable
private data class ComputerReleaseNotesDto(
    val version: String? = null,
    val en: String? = null,
    @SerialName("zh-CN") val zhCn: String? = null
) {
    // Same validation as the shared `normalizeComputerReleaseNotes`: a bounded version and at
    // least one non-empty locale within the per-locale cap.
    fun toSnapshot(): SettingsComputerReleaseNotesSnapshot? {
        val cleanVersion = version?.trim()?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        fun String?.note(): String? = this?.trim()?.takeIf { it.isNotBlank() && it.length <= 16_000 }
        val english = en.note()
        val chinese = zhCn.note()
        if (english == null && chinese == null) return null
        return SettingsComputerReleaseNotesSnapshot(version = cleanVersion, en = english, zhCn = chinese)
    }
}

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
        osRelease = osRelease?.trim()?.takeIf { it.isNotBlank() },
        hostProvider = hostProvider?.trim()?.takeIf { it.isNotBlank() },
        installerRepairHint = installerRepairHint?.reason?.takeIf { it in COMPUTER_INSTALLER_REPAIR_HINT_REASONS },
        runtimeVersions =
            runtimeVersions.mapNotNull { (runtime, version) ->
                version.trim().takeIf { runtime.isNotBlank() && it.isNotEmpty() }?.let { runtime to it }
            }.toMap(),
        creatorUserId = userId?.trim()?.takeIf { it.isNotBlank() },
        attachedByCurrentUser = computerAttachedByCurrentUser,
        agentCount = agentCount.coerceAtLeast(0),
        computerUpgradeAvailable = computerUpgradeAvailable,
        computerBroadcastPolicy = computerBroadcastPolicy?.toSnapshot(),
        upgradeRequest = upgradeRequest?.toSnapshot(),
        remoteUpgradeSupported = remoteUpgradeSupported,
        versionGate = versionGate?.toSnapshot(),
        creator = creator?.toSnapshot(),
        ownerUserId = ownerUserId?.trim()?.takeIf { it.isNotBlank() },
        owner = owner?.toSnapshot(),
        agentScopes = agentScopes?.toSnapshot(),
        diskStatus = diskStatus?.toStatus(),
        memoryStatus = memoryStatus?.toStatus()
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
    val runtimeFormV2: RuntimeFormV2MarkerDto? = null
)

@Serializable
private data class RuntimeFormV2MarkerDto(val protocolVersion: Int)

/** Request header asking the runtime-options rows to carry the `runtimeFormV2` marker. */
internal const val RUNTIME_FORM_V2_MARKER_HEADER = "X-Raft-Runtime-Form-V2"

/**
 * slock `RAFT_CLIENT_CAPABILITIES_HEADER` / `RUNTIME_USAGE_WINDOW_SCOPE_CLIENT_CAPABILITY` (#14605,
 * #14667): the server sends a usage window's `scope` (every kind, `models` too) only to a request
 * that declares it reads it; this app's parser does.
 */
internal const val RAFT_CLIENT_CAPABILITIES_HEADER = "X-Raft-Client-Capabilities"
internal const val RUNTIME_USAGE_WINDOW_SCOPE_CLIENT_CAPABILITY = "runtime-usage-window-scope@2"
internal val RUNTIME_USAGE_WINDOW_SCOPE_HEADERS: Map<String, String> =
    mapOf(RAFT_CLIENT_CAPABILITIES_HEADER to RUNTIME_USAGE_WINDOW_SCOPE_CLIENT_CAPABILITY)

private fun ComputerRuntimeSelectionCatalogDto.toDomain(
    expectedMachineId: String
): SharedCreateAgentRuntimeCatalog? {
    if (context != "new_agent" || machineId.trim() != expectedMachineId) return null
    return SharedCreateAgentRuntimeCatalog(machineId = expectedMachineId, options = runtimeSelectionRows(options))
}

@Serializable
private data class AgentRuntimeSelectionCatalogDto(
    val context: String,
    // Null when the agent has no computer on this server (slock agents.ts).
    val machineId: String? = null,
    val options: List<JsonElement>
)

private fun AgentRuntimeSelectionCatalogDto.toDomain(agentId: String): ExistingAgentRuntimeOptions? {
    if (context != "existing_agent") return null
    return ExistingAgentRuntimeOptions(
        agentId = agentId,
        machineId = machineId?.trim()?.takeIf { it.isNotBlank() },
        options = runtimeSelectionRows(options)
    )
}

/**
 * One row this client cannot read (missing field, wrong type, a new status value, an inconsistent
 * row) is left out on its own; it must not take every other runtime down with it. The first row
 * wins a duplicate id.
 */
private fun runtimeSelectionRows(options: List<JsonElement>): List<SharedCreateAgentRuntimeOption> =
    options
        .mapNotNull { row ->
            runCatching {
                runtimeFormListJson.decodeFromJsonElement(ComputerRuntimeSelectionOptionDto.serializer(), row)
            }.getOrNull()?.toDomain()
        }
        .distinctBy { it.runtimeId }

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
    // slock runtimeAdmissionService `projectRuntimeOption`: installed, and offered for new use or the
    // agent's own grandfathered runtime. A new-agent row is never current, so there it is
    // "available and available_for_new"; an existing agent's current row is selectable too.
    val grandfathered = admissionStatus == "grandfathered_current"
    if ((admissionReason == null) == grandfathered || availableForNew == grandfathered) return null
    val expectedSelectable = capabilityStatus == "available" && (availableForNew || (current && grandfathered))
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
        runtimeFormV2 = runtimeFormV2?.protocolVersion == 2
    )
}

@Serializable
private data class ComputerCommandResponseDto(val ok: Boolean)

internal const val COMPUTER_CONTROL_RESTART = "restart"
internal const val COMPUTER_CONTROL_UPGRADE = "upgrade"

/** `POST …/computer/restart` takes no fields: the route rejects a `targetVersion` for restart. */
@Serializable
internal class ComputerRestartBody

/** `POST …/computer/upgrade` body: the exact `targetVersion` the user consented to. */
@Serializable
internal data class ComputerUpgradeBody(val targetVersion: String)

internal fun buildComputerRestartBody(): String = computersApiJson.encodeToString(ComputerRestartBody.serializer(), ComputerRestartBody())

internal fun buildComputerUpgradeBody(targetVersion: String): String =
    computersApiJson.encodeToString(ComputerUpgradeBody.serializer(), ComputerUpgradeBody(targetVersion = targetVersion.trim()))

/** `{ ok, action, requestId, operationId? }`, or `{ ok, action, requestId, queued: true }` with 202. */
@Serializable
private data class ComputerRestartResponseDto(
    val ok: Boolean = false,
    val requestId: String? = null,
    val queued: Boolean = false
)

/** `{ upgradeRequest }` with 201 (created) or 200 (an equal pending request already exists). */
@Serializable
private data class ComputerUpgradeResponseDto(val upgradeRequest: ComputerUpgradeRequestDto? = null)

/** Computer control refusals carry a machine-readable `code` (`computer_offline`, `remote_upgrade_disabled`, …). */
@Serializable
private data class ComputerControlErrorDto(val error: String? = null, val code: String? = null)

/**
 * A refused restart/upgrade keeps the server's `code` (the page picks its copy from it, never from the
 * English `error`); a refusal without one falls back to the status mapping of the other Computer routes.
 */
private fun <T> HomeNetworkResponse.toComputerControlError(): DataResult<T> {
    val payload = bodyText?.takeIf { it.isNotBlank() } ?: errorBodyText?.takeIf { it.isNotBlank() }
    val wire =
        payload?.let {
            runCatching { computersApiJson.decodeFromString(ComputerControlErrorDto.serializer(), it) }.getOrNull()
        }
    val code = wire?.code?.trim()?.takeIf { it.isNotBlank() } ?: return toComputersDataError()
    return DataResult.Error(
        ScreenError(code = code, retryable = this.code >= 500, serverMessage = wire.error?.takeIf { it.isNotBlank() })
    )
}

@Serializable
internal data class RegisterComputerBody(val name: String)

@Serializable
private data class RuntimeLimitResetBody(val idempotencyKey: String)

/** `{idempotencyKey}`, the key passed through unchanged (slock runtimeAuthRoutes.ts). */
internal fun buildRuntimeLimitResetBody(idempotencyKey: RuntimeLimitResetIdempotencyKey): String =
    computersApiJson.encodeToString(RuntimeLimitResetBody(idempotencyKey = idempotencyKey.value))

@Serializable
private data class ClaudeLimitResetBody(val idempotencyKey: String, val kind: String)

/** `{idempotencyKey, kind}`: a Claude redeem names the kind the person chose (slock #14336). */
internal fun buildClaudeLimitResetBody(idempotencyKey: RuntimeLimitResetIdempotencyKey, kind: RuntimeLimitResetKind): String =
    computersApiJson.encodeToString(ClaudeLimitResetBody(idempotencyKey = idempotencyKey.value, kind = kind.wire))

@Serializable
private data class ClaudeLimitResetsReadConsentBody(val consent: Boolean)

/** `{consent: true}` once the person accepted the read at the 5-hour limit, else `{}` (web `readLimitResets`). */
internal fun buildClaudeLimitResetsReadBody(consent: Boolean): String =
    if (consent) computersApiJson.encodeToString(ClaudeLimitResetsReadConsentBody(consent = true)) else "{}"

@Serializable
private data class RuntimeAccountUsageRefreshBody(val reason: String)

internal fun buildRuntimeAccountUsageRefreshBody(reason: RuntimeAccountUsageRefreshReason): String =
    computersApiJson.encodeToString(RuntimeAccountUsageRefreshBody(reason = reason.wire))

internal fun buildRegisterComputerBody(name: String): String =
    computersApiJson.encodeToString(RegisterComputerBody(name = name))

internal fun buildComputerUpdateBody(name: String? = null, description: String? = null): String =
    computersApiJson.encodeToString(
        buildJsonObject {
            name?.let { put("name", it) }
            description?.let { put("description", it) }
        }
    )
