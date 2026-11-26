package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import build.raft.app.reportUnknownTypeDropped
import build.raft.app.model.RuntimeModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
    /** `GET servers/:id/machines/:machineId/workspaces`: [refresh] sends `?refresh=1` to bypass the daemon cache (slock #16552). */
    suspend fun machineWorkspaces(
        serverId: ServerId,
        machineId: String,
        refresh: Boolean
    ): HomeNetworkResponse = machineWorkspaces(serverId, machineId)
    /** `POST servers/:id/machines/:machineId/runtimes/rescan` (slock #16477). */
    suspend fun rescanMachineRuntimes(serverId: ServerId, machineId: String): HomeNetworkResponse =
        error("rescanMachineRuntimes is not implemented")
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
    /**
     * `POST servers/:id/machines/:machineId/runtimes/:runtime/:operation` with `{respondAsync}`, for
     * [operation] `upgrade`, `install-plan` or `install` (slock #15117 / #15214, RuntimeUpgradeInstall.kt).
     */
    suspend fun runtimeOperation(serverId: ServerId, machineId: String, runtime: String, operation: String, body: String): HomeNetworkResponse =
        error("runtimeOperation is not implemented")
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
    /**
     * `GET servers/:id/machine-operations/:operationId` (slock #14842, runtimeAuthRoutes.ts): a
     * Computer wait started with `respondAsync`, pending or done (MachineOperations.kt).
     */
    suspend fun machineOperation(serverId: ServerId, operationId: String): HomeNetworkResponse =
        error("machineOperation is not implemented")
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

    /**
     * Worker v1 step 2 (slock #17011): `PUT servers/:id/machines/:machineId/worker-runtimes` with
     * body `{runtimes: string[]}`; answers `{machine}` as `GET …/machines` lists it.
     */
    suspend fun setMachineWorkerRuntimes(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse =
        error("setMachineWorkerRuntimes is not implemented")

    /**
     * `GET servers/:id/machines/:machineId/proc-jobs` (slock #15595, docs/contracts/proc-jobs.md):
     * the last snapshot of `raft proc` jobs reported by this Computer (`known` or `unknown`).
     */
    suspend fun machineProcJobs(serverId: ServerId, machineId: String): HomeNetworkResponse =
        error("machineProcJobs is not implemented")

    suspend fun machineProcJobs(
        serverId: ServerId,
        machineId: String,
        priority: DataRequestPriority
    ): HomeNetworkResponse = machineProcJobs(serverId, machineId)

    /**
     * `POST servers/:id/machines/:machineId/proc-jobs/stop` with `{ agentId, name }` (slock #15595).
     */
    suspend fun stopMachineProcJob(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse =
        error("stopMachineProcJob is not implemented")
}

@RaftCoreInternalApi class RaftComputersNetworkApi(
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

    override suspend fun setMachineWorkerRuntimes(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/worker-runtimes",
            method = "PUT",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun machineProcJobs(serverId: ServerId, machineId: String): HomeNetworkResponse =
        machineProcJobs(serverId, machineId, DataRequestPriority.Visible)

    override suspend fun machineProcJobs(
        serverId: ServerId,
        machineId: String,
        priority: DataRequestPriority
    ): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/proc-jobs",
            serverIdOverride = serverId
        )

    override suspend fun stopMachineProcJob(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/proc-jobs/stop",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = body
        )

    override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse =
        machineWorkspaces(serverId, machineId, refresh = false)

    override suspend fun machineWorkspaces(
        serverId: ServerId,
        machineId: String,
        refresh: Boolean
    ): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/workspaces",
            query = if (refresh) listOf("refresh" to "1") else emptyList(),
            serverIdOverride = serverId
        )

    override suspend fun rescanMachineRuntimes(serverId: ServerId, machineId: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtimes/rescan",
            method = "POST",
            serverIdOverride = serverId
        )

    override suspend fun runtimeOptions(serverId: ServerId, machineId: String): HomeNetworkResponse =
        networkClient.requestWithHeaders(
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtime-options",
            // Opt in to the `runtimeFormV2` marker and `raftCapabilities` on each row (slock
            // runtimeAdmissionService, #16178): only a client that asks gets them, because
            // released apps up to 1.13 decoded the rows strictly. The server marks a row when
            // the runtime has a v2 form and is available for new use or current; a reply with
            // no marked row at all is read as "unknown", not "no forms"
            // (runtimeFormV2Availability), so a dropped header cannot disable create.
            headers = RUNTIME_OPTIONS_REQUEST_HEADERS,
            serverIdOverride = serverId
        )

    override suspend fun agentRuntimeOptions(serverId: ServerId, agentId: String): HomeNetworkResponse =
        networkClient.requestWithHeaders(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.trim()}/runtime-options",
            // Same opt-in as the computer's rows: `runtimeFormV2` and `raftCapabilities`.
            headers = RUNTIME_OPTIONS_REQUEST_HEADERS,
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
            headers = RUNTIME_USAGE_CLIENT_CAPABILITY_HEADERS,
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
            headers = RUNTIME_USAGE_CLIENT_CAPABILITY_HEADERS,
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

    override suspend fun runtimeOperation(serverId: ServerId, machineId: String, runtime: String, operation: String, body: String): HomeNetworkResponse =
        networkClient.request(
            // The person pressed Upgrade or Install and waits for the outcome.
            priority = DataRequestPriority.Foreground,
            path = "servers/${serverId.value}/machines/${machineId.trim()}/runtimes/${runtime.trim()}/${operation.trim()}",
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
            // The answer carries the usage read right after it: its windows' scope and credits too.
            headers = RUNTIME_USAGE_CLIENT_CAPABILITY_HEADERS,
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

    override suspend fun machineOperation(serverId: ServerId, operationId: String): HomeNetworkResponse =
        networkClient.request(
            // The person waits on the dialog for this answer; the reads repeat every 1.5 s.
            priority = DataRequestPriority.Visible,
            path = "servers/${serverId.value}/machine-operations/${operationId.trim()}",
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

/**
 * One item of `machines` in `GET /api/servers/:id/machines`, as an incremental-sync `machine` row
 * carries it (botiverse/slock 0d030e341 routes/sync.ts buildMachineRows). The list-level
 * `latestComputerVersion` / release notes are not on the row; the caller keeps the list's.
 */
@RaftCoreInternalApi fun parseSyncedComputerRow(row: kotlinx.serialization.json.JsonObject): ComputerSummary? =
    runCatching { computersApiJson.decodeFromJsonElement(ComputerDto.serializer(), row).toSummary() }.getOrNull()

@RaftCoreInternalApi class RaftComputersApiClient(
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

    /**
     * Worker v1 step 2 (slock #17011): turn on exactly [runtimes] for Workers on [machineId]
     * (`PUT servers/:id/machines/:machineId/worker-runtimes`). Preserves server refusal codes
     * (`worker_runtimes_forbidden`, `worker_runtimes_invalid`, `machine_not_found`) and messages.
     */
    suspend fun setMachineWorkerRuntimes(
        serverId: ServerId,
        machineId: String,
        runtimes: List<String>
    ): DataResult<ComputerWorkerRuntimesChange> {
        val cleanMachineId = machineId.trim()
        if (cleanMachineId.isBlank()) {
            return DataResult.Error(ScreenError(code = "worker_runtimes_machine_required", retryable = false))
        }
        val response = transport.setMachineWorkerRuntimes(serverId, cleanMachineId, buildComputerWorkerRuntimesBody(runtimes))
        if (!response.isSuccessful) return response.toComputerWorkerRuntimesError()
        return response.mapComputersBody(
            "computer_worker_runtimes_contract_mismatch",
            ComputerWorkerRuntimesResponseDto.serializer()
        ) { wire -> ComputerWorkerRuntimesChange(machine = wire.machine?.toSummary()) }
    }

    suspend fun rotateMachineKey(serverId: ServerId, machineId: String): DataResult<ComputerApiKeyRotationSummary> =
        transport.rotateMachineKey(serverId, machineId).mapComputersBody(
            "computer_rotate_key_contract_mismatch",
            RotateComputerKeyResponseDto.serializer()
        ) { wire ->
            val apiKey = wire.apiKey.takeIf { it.isNotBlank() } ?: return@mapComputersBody null
            ComputerApiKeyRotationSummary(apiKeyPrefix = apiKey.take(12))
        }

    suspend fun machineWorkspaces(
        serverId: ServerId,
        machineId: String,
        refresh: Boolean = false
    ): DataResult<ComputerWorkspacePage> {
        val response =
            if (refresh) {
                transport.machineWorkspaces(serverId, machineId, refresh = true)
            } else {
                transport.machineWorkspaces(serverId, machineId)
            }
        return response.mapComputersBody(
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
    }

    /**
     * `POST servers/:id/machines/:machineId/runtimes/rescan` (slock #16477, #587 site 4).
     * Maps HTTP outcomes to [ComputerRuntimeRescanResult]: 2xx -> Accepted, 403 -> Forbidden,
     * 409 -> Offline, code <= 0 / network error -> NotSent, any other HTTP error -> Failed.
     */
    suspend fun rescanMachineRuntimes(serverId: ServerId, machineId: String): ComputerRuntimeRescanResult {
        val response =
            try {
                transport.rescanMachineRuntimes(serverId, machineId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return ComputerRuntimeRescanResult.NotSent
            }
        return when {
            response.isSuccessful -> ComputerRuntimeRescanResult.Accepted
            response.code == 403 -> ComputerRuntimeRescanResult.Forbidden
            response.code == 409 -> ComputerRuntimeRescanResult.Offline
            response.code <= 0 -> ComputerRuntimeRescanResult.NotSent
            else -> ComputerRuntimeRescanResult.Failed
        }
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
        val body = runtimeAuthBodyJson.encodeToString(RuntimeAuthLogoutBody.serializer(), RuntimeAuthLogoutBody(providerId, respondAsync = true))
        val started = transport.logoutRuntime(serverId, machineId, runtime.wire, body)
        return answerOf(serverId, started, RUNTIME_AUTH_LOGOUT_RELAY_BUDGET_MILLIS).runtimeAuthCall { wire ->
            (wire as? JsonObject)?.get("result")?.toRuntimeAuthLogoutResult()
        }
    }

    /** Runs the runtime's own updater on the Computer (a person pressed Upgrade; web `upgradeRuntime`). */
    suspend fun upgradeRuntime(serverId: ServerId, machineId: String, runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeUpgradeResult> =
        runtimeOperationCall(serverId, machineId, runtime, "upgrade", RUNTIME_UPGRADE_RELAY_BUDGET_MILLIS) { wire ->
            (wire as? JsonObject)?.get("result")?.toRuntimeUpgradeResult()
        }

    /** What installing the runtime would run, read on the Computer without running it (web `runtimeInstallPlan`). */
    suspend fun runtimeInstallPlan(serverId: ServerId, machineId: String, runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeInstallPlan> =
        runtimeOperationCall(serverId, machineId, runtime, "install-plan", RUNTIME_INSTALL_PLAN_RELAY_BUDGET_MILLIS) { wire ->
            (wire as? JsonObject)?.get("plan")?.toRuntimeInstallPlan()
        }

    /** Runs the runtime's own installer on the Computer (the person confirmed its plan; web `installRuntime`). */
    suspend fun installRuntime(serverId: ServerId, machineId: String, runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeInstallResult> =
        runtimeOperationCall(serverId, machineId, runtime, "install", RUNTIME_INSTALL_RELAY_BUDGET_MILLIS) { wire ->
            (wire as? JsonObject)?.get("result")?.toRuntimeInstallResult()
        }

    /** Only the runtime is sent; what runs is the Computer's to say. */
    private suspend fun <T> runtimeOperationCall(
        serverId: ServerId,
        machineId: String,
        runtime: RuntimeAuthRuntime,
        operation: String,
        budgetMillis: Long,
        read: (JsonElement) -> T?,
    ): RuntimeAuthCall<T> {
        val body = runtimeAuthBodyJson.encodeToString(RuntimeMachineOperationBody.serializer(), RuntimeMachineOperationBody(respondAsync = true))
        val started = transport.runtimeOperation(serverId, machineId, runtime.wire, operation, body)
        return answerOf(serverId, started, budgetMillis).runtimeAuthCall(read)
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
        val started =
            transport.redeemRuntimeLimitReset(
                serverId = serverId,
                machineId = machineId,
                provider = provider.wire,
                body = if (kind == null) buildRuntimeLimitResetBody(idempotencyKey) else buildClaudeLimitResetBody(idempotencyKey, kind)
            )
        val budget = if (kind == null) RUNTIME_LIMIT_RESET_RELAY_BUDGET_MILLIS else RUNTIME_LIMIT_RESET_CLAUDE_RELAY_BUDGET_MILLIS
        val response = answerOf(serverId, started, budget)
        if (!response.isSuccessful) return RuntimeLimitResetLoad.Failed(response.limitResetRefusal())
        val reply =
            response.bodyText?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { computersApiJson.parseToJsonElement(body) }.getOrNull()?.toRuntimeLimitResetReply()
            }
        return reply?.let(RuntimeLimitResetLoad::Done)
            ?: RuntimeLimitResetLoad.Failed(RuntimeLimitResetRefusal(status = response.code, code = "malformed_response", traceId = response.traceId))
    }

    /**
     * Reads which Claude limit resets the account holds (web `RuntimeAuthClient.readLimitResets`).
     * [consent]: the person accepted that, at the 5-hour limit, opening the command can use the
     * older program's 5-hour reset. No readable result is [RuntimeLimitResetsReadLoad.Failed].
     */
    suspend fun readClaudeLimitResets(serverId: ServerId, machineId: String, consent: Boolean): RuntimeLimitResetsReadLoad {
        val started = transport.readClaudeLimitResets(serverId, machineId, buildClaudeLimitResetsReadBody(consent))
        val response = answerOf(serverId, started, RUNTIME_LIMIT_RESET_CLAUDE_RELAY_BUDGET_MILLIS)
        if (!response.isSuccessful) return RuntimeLimitResetsReadLoad.Failed(response.limitResetRefusal())
        val result =
            response.bodyText?.takeIf { it.isNotBlank() }?.let { body ->
                runCatching { computersApiJson.parseToJsonElement(body) }.getOrNull()?.toRuntimeLimitResetsReadReply()
            }
        return result?.let(RuntimeLimitResetsReadLoad::Done)
            ?: RuntimeLimitResetsReadLoad.Failed(RuntimeLimitResetRefusal(status = response.code, code = "malformed_response", traceId = response.traceId))
    }

    /**
     * The answer a request that waits on the Computer would have had: [started] itself, or, when
     * the server started a machine operation for it (`respondAsync`), the operation's answer read
     * within [budgetMillis] (web `postWaitingOnComputer`, MachineOperations.kt).
     */
    private suspend fun answerOf(serverId: ServerId, started: HomeNetworkResponse, budgetMillis: Long): HomeNetworkResponse =
        awaitMachineOperation(started, budgetMillis) { operationId -> transport.machineOperation(serverId, operationId) }

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

    /**
     * `GET servers/:id/machines/:machineId/proc-jobs` (slock #15595): returns [ComputerProcJobsRead.Known]
     * or [ComputerProcJobsRead.Unknown]; non-2xx or malformed payloads return [DataResult.Error].
     */
    suspend fun machineProcJobs(
        serverId: ServerId,
        machineId: String,
        priority: DataRequestPriority = DataRequestPriority.Visible
    ): DataResult<ComputerProcJobsRead> {
        val cleanMachineId = machineId.trim()
        if (cleanMachineId.isBlank()) {
            return DataResult.Error(ScreenError("computer_proc_jobs_machine_required", retryable = false))
        }
        val response = transport.machineProcJobs(serverId, cleanMachineId, priority)
        if (!response.isSuccessful) return response.toComputersDataError()
        val payload = response.bodyText?.takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError("computer_proc_jobs_contract_mismatch", retryable = true))
        val wire =
            runCatching { computersApiJson.decodeFromString(ComputerProcJobsReadDto.serializer(), payload) }.getOrNull()
                ?: return DataResult.Error(ScreenError("computer_proc_jobs_contract_mismatch", retryable = true))
        return when (wire.status.trim()) {
            "unknown" -> DataResult.Content(ComputerProcJobsRead.Unknown)
            "known" -> {
                val rawJobs = wire.jobs.orEmpty()
                val parsedJobs = rawJobs.mapNotNull { it.toDomainOrNull() }
                if (rawJobs.isNotEmpty() && parsedJobs.isEmpty()) {
                    DataResult.Error(ScreenError("computer_proc_jobs_contract_mismatch", retryable = true))
                } else {
                    DataResult.Content(
                        ComputerProcJobsRead.Known(
                            reportedAt = wire.reportedAt.orEmpty().trim(),
                            jobs = parsedJobs
                        )
                    )
                }
            }
            else -> DataResult.Error(ScreenError("computer_proc_jobs_contract_mismatch", retryable = true))
        }
    }

    /**
     * `POST servers/:id/machines/:machineId/proc-jobs/stop` with `{ agentId, name }` (slock #15595).
     */
    suspend fun stopMachineProcJob(
        serverId: ServerId,
        machineId: String,
        agentId: String,
        name: String
    ): ComputerProcJobStopResult {
        val cleanMachineId = machineId.trim()
        val cleanAgentId = agentId.trim()
        val cleanName = name.trim()
        if (cleanMachineId.isBlank() || cleanAgentId.isBlank() || cleanName.isBlank()) {
            return ComputerProcJobStopResult.Error("proc_job_invalid")
        }
        val body = buildComputerProcJobStopBody(agentId = cleanAgentId, name = cleanName)
        val response = transport.stopMachineProcJob(serverId, cleanMachineId, body)
        val traceId = response.traceId?.trim()?.takeIf { it.isNotBlank() }
        val payload = response.bodyText?.takeIf { it.isNotBlank() } ?: response.errorBodyText?.takeIf { it.isNotBlank() }
        if (!response.isSuccessful) {
            val errCode = response.parseHttpErrorEnvelope()?.serverCode
            return if (errCode == "computer_upgrade_required") {
                ComputerProcJobStopResult.UpgradeRequired
            } else {
                ComputerProcJobStopResult.Error(code = errCode, traceId = traceId)
            }
        }
        val wire =
            payload?.let {
                runCatching { computersApiJson.decodeFromString(ComputerProcJobStopResponseDto.serializer(), it) }.getOrNull()
            }
        val outcome =
            when (wire?.outcome?.trim()) {
                "stopped" -> ComputerProcJobStopOutcome.Stopped
                "not_found" -> ComputerProcJobStopOutcome.NotFound
                "already_exited" -> ComputerProcJobStopOutcome.AlreadyExited
                "failed" -> ComputerProcJobStopOutcome.Failed
                else -> null
            }
        return outcome?.let { ComputerProcJobStopResult.Completed(it, traceId = traceId) }
            ?: ComputerProcJobStopResult.Error(code = "computer_proc_job_stop_contract_mismatch", traceId = traceId)
    }
}

enum class ComputerProcJobState {
    Running,
    Exited
}

/**
 * One `raft proc` job as returned by `GET /api/servers/:id/machines/:machineId/proc-jobs`
 * (slock #15595, `packages/shared/src/procJobsSnapshot.ts`). Command line, arguments, cwd, env
 * and log paths never cross this boundary.
 */
data class ComputerProcJobSnapshot(
    val agentId: String,
    val name: String,
    val state: ComputerProcJobState,
    val startedAt: String,
    val exitedAt: String? = null,
    val exitCode: Int? = null,
    val exitSignal: String? = null,
    val exitReason: String? = null,
    val stoppedBy: String? = null,
    val memoryMaxBytes: Long? = null,
    val rssBytes: Long? = null
)

sealed interface ComputerProcJobsRead {
    data class Known(
        val reportedAt: String,
        val jobs: List<ComputerProcJobSnapshot>
    ) : ComputerProcJobsRead

    data object Unknown : ComputerProcJobsRead
}

enum class ComputerProcJobStopOutcome {
    Stopped,
    NotFound,
    AlreadyExited,
    Failed
}

sealed interface ComputerProcJobStopResult {
    data class Completed(val outcome: ComputerProcJobStopOutcome, val traceId: String? = null) : ComputerProcJobStopResult
    data object UpgradeRequired : ComputerProcJobStopResult
    data class Error(val code: String? = null, val traceId: String? = null) : ComputerProcJobStopResult
}

@RaftCoreInternalApi data class ComputerListSummary(
    val machines: List<ComputerSummary>,
    val machineCount: Int,
    val onlineCount: Int,
    val latestDaemonVersion: String?,
    val latestComputerVersion: String?
)

@RaftCoreInternalApi data class ComputerSummary(
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
    /** Server `deviceClass` ("laptop", "server"); null when unknown or an older server. */
    val deviceClass: String? = null,
    /** Server `installerRepairHint.reason` (a known one only); null when there is nothing to hint or an older server. */
    val installerRepairHint: String? = null,
    /** Server `upgradeScope.ok == false`: the Computer refuses a web upgrade's scope (it runs as a system service). */
    val upgradeScopeRefused: Boolean = false,
    /** Server `runtimeVersions`: runtime id -> the CLI version the Computer detected ("2.1.59 (Claude Code)"). */
    val runtimeVersions: Map<String, String> = emptyMap(),
    /** Server `runtimeDetection` (slock #16477): explicit detection status; null when absent. */
    val runtimeDetection: SettingsComputerRuntimeDetection? = null,
    /** `machines.userId`: the user who registered the machine (server's "machine creator" authority). */
    val creatorUserId: String? = null,
    /** True when the requester attached the Computer backing this machine (false for raw daemons). */
    val attachedByCurrentUser: Boolean = false,
    /**
     * Server `controlledByCurrentUser` (slock #15002): the requester is the machine's controller, the
     * person the routes give rename / description / rescan / removal (it outlives a key revoke);
     * null from older servers.
     */
    val controlledByCurrentUser: Boolean? = null,
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
    val memoryStatus: ComputerSizeStatus? = null,
    /**
     * The machine logins of server `attention.items` that hold agents and need a step (slock #14602,
     * web `rackLoginStep`). Null: the row carried no `attention` (an older server, a viewer the
     * server computes none for, or an incremental-sync row, which never carries it).
     */
    val loginSteps: List<ComputerLoginStep>? = null,
    /**
     * Server `attention.count > 0` verdict (web `serverNeedsAttention`, slock #15534): true when a
     * person must act on this Computer, false when the server reported `count == 0`, null when the
     * row carried no readable `attention` summary (older server or incremental-sync row).
     */
    val serverNeedsAttention: Boolean? = null,
    /** Server `revocation` (slock #16468): set only for a machine whose Computers are all revoked. */
    val revocation: SettingsComputerRevocationSnapshot? = null,
    /** Server `versionRefusedAt` (slock #16514): when connect was last refused below the version floor. */
    val versionRefusedAt: String? = null,
    val refusedComputerVersion: String? = null,
    /**
     * Worker v1 step 2 (slock #17011, `MachineWorkerRuntimesFields`): runtimes turned on for Workers
     * on this Computer. Null when absent on older servers (section stays hidden); empty = none on.
     */
    val workerRuntimesTurnedOn: List<String>? = null,
    /** Whether this viewer may change [workerRuntimesTurnedOn] (the server's own decision). */
    val canChangeWorkerRuntimes: Boolean = false,
    /** Who can change [workerRuntimesTurnedOn]: `"owner_or_admin"` or `"any_member"`; null when unknown. */
    val workerRuntimesChangeBy: String? = null
)

/**
 * slock `packages/shared/src/index.ts` `MachineDiskStatus` (and `MachineMemoryStatus`, the same
 * shape): `{ availableBytes, totalBytes }`. Only a readable report is kept (web
 * `readableDiskStatus`): a positive total and a free size within it (not negative, not over the
 * total, web #11855).
 */
@RaftCoreInternalApi data class ComputerSizeStatus(
    val availableBytes: Double,
    val totalBytes: Double
)

/** `POST …/computer/restart` accepted: [requestId] correlates the later `computer:restart:done`. */
@RaftCoreInternalApi data class ComputerRestartSummary(val requestId: String?, val queued: Boolean)

@RaftCoreInternalApi data class ComputerRegistrationSummary(val machine: ComputerSummary, val apiKey: String, val apiKeyPrefix: String)
@RaftCoreInternalApi data class ComputerApiKeyRotationSummary(val apiKeyPrefix: String)
@RaftCoreInternalApi data class ComputerWorkspaceSummary(
    val directoryName: String,
    val totalSizeBytes: Long,
    val fileCount: Int,
    val status: String,
    val lastModified: String?,
    val agentName: String?,
    val agentStatus: String?,
    val partial: Boolean = false
)
@RaftCoreInternalApi data class ComputerWorkspacePage(
    val workspaces: List<ComputerWorkspaceSummary>,
    val itemCount: Int,
    val activeCount: Int,
    val orphanCount: Int
)
@RaftCoreInternalApi data class ComputerRuntimeModelsSummary(
    val runtime: String,
    val models: List<RuntimeModelInfo>,
    val modelCount: Int,
    val defaultModel: String?
)
@RaftCoreInternalApi data class ComputerCommandResult(val ok: Boolean)

/** `PUT …/owner` accepted: [machine] is the Computer as the list shows it (null when the server sent none). */
@RaftCoreInternalApi data class ComputerOwnerChange(val machine: ComputerSummary?)

/** `{ ownerUserId }`, null encoded as JSON null (Public). */
@RaftCoreInternalApi fun buildComputerOwnerBody(ownerUserId: String?): String =
    buildJsonObject { put("ownerUserId", ownerUserId?.trim()?.takeIf { it.isNotBlank() }) }.toString()

@Serializable
private data class ComputerOwnerResponseDto(val machine: ComputerDto? = null)

/** `PUT …/worker-runtimes` accepted: [machine] is the Computer as the list shows it (null when the server sent none). */
@RaftCoreInternalApi data class ComputerWorkerRuntimesChange(val machine: ComputerSummary?)

@Serializable
private data class ComputerWorkerRuntimesBody(val runtimes: List<String>)

@Serializable
private data class ComputerWorkerRuntimesResponseDto(val machine: ComputerDto? = null)

/** `{ "runtimes": [...] }` for `PUT servers/:id/machines/:machineId/worker-runtimes`. */
@RaftCoreInternalApi fun buildComputerWorkerRuntimesBody(runtimes: List<String>): String =
    computersApiJson.encodeToString(
        ComputerWorkerRuntimesBody.serializer(),
        ComputerWorkerRuntimesBody(
            runtimes = runtimes.map(String::trim).filter(String::isNotEmpty).distinct()
        )
    )

/**
 * Strict refusal mapping for `PUT …/worker-runtimes` (slock #17011): preserves server refusal codes
 * (`worker_runtimes_forbidden`, `worker_runtimes_invalid`, `machine_not_found`) and messages while
 * keeping 401/5xx semantics aligned with the other Computer routes.
 */
private fun <T> HomeNetworkResponse.toComputerWorkerRuntimesError(): DataResult<T> {
    if (this.code == 401) return toComputersDataError()
    val envelope = parseHttpErrorEnvelope()
    val serverCode = envelope?.serverCode
    val serverMessage = if (this.code >= 500) null else envelope?.serverMessage
    return when (this.code) {
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = serverCode ?: "worker_runtimes_forbidden",
                    requiredCapability = "manage_computer_worker_runtimes",
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
        400 ->
            DataResult.Error(
                ScreenError(
                    code = serverCode ?: "worker_runtimes_invalid",
                    retryable = false,
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
        404 ->
            DataResult.Error(
                ScreenError(
                    code = serverCode ?: "machine_not_found",
                    retryable = false,
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
        else ->
            if (serverCode != null) {
                DataResult.Error(
                    ScreenError(
                        code = serverCode,
                        retryable = this.code >= 500,
                        serverMessage = serverMessage,
                        serverCode = serverCode,
                        traceId = traceId,
                    )
                )
            } else {
                toComputersDataError()
            }
    }
}

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
    val envelope = parseHttpErrorEnvelope()
    val serverMessage = envelope?.serverMessage
    val serverCode = envelope?.serverCode
    // Registering a machine or creating a new machine key with two-step verification on: the
    // caller asks for the second step and retries (SharedSettingsStore), never this 403's English.
    secondFactorStepUpErrorOrNull<T>(serverMessage)?.let { return it }
    return when (code) {
        401 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "auth_required",
                    requiredCapability = "auth_session",
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "server_forbidden",
                    requiredCapability = "server_access",
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
        307 ->
            DataResult.Error(
                ScreenError(
                    code = "machine_offline_replay",
                    retryable = true,
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
        400 ->
            DataResult.Error(
                ScreenError(
                    code = "computer_bad_request",
                    retryable = false,
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
        409 ->
            DataResult.Error(
                ScreenError(
                    code = "computer_has_agents",
                    retryable = false,
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
        503 ->
            DataResult.Error(
                ScreenError(
                    code = "machine_offline",
                    retryable = true,
                    serverMessage = null,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
        in 500..599 ->
            DataResult.Error(
                ScreenError(
                    code = "server_unavailable",
                    retryable = true,
                    serverMessage = null,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
        else ->
            DataResult.Error(
                ScreenError(
                    code = "http_$code",
                    retryable = code != 404,
                    serverMessage = serverMessage,
                    serverCode = serverCode,
                    traceId = traceId,
                )
            )
    }
}

/** A runtime-auth answer: [read] its body, or the refusal with its status, `code`, message and trace id. */
private inline fun <T> HomeNetworkResponse.runtimeAuthCall(read: (JsonElement) -> T?): RuntimeAuthCall<T> {
    if (!isSuccessful) return RuntimeAuthCall.Refused(runtimeAuthRequestError())
    val value =
        bodyText?.takeIf { it.isNotBlank() }?.let { body -> runCatching { computersApiJson.parseToJsonElement(body) }.getOrNull() }?.let(read)
    return value?.let { RuntimeAuthCall.Done(it) }
        ?: RuntimeAuthCall.Refused(RuntimeAuthRequestError(status = code, code = "malformed_response", traceId = traceId))
}

/** Only the status, the route's `code`, refusal message, and trace id are read: never the request. */
private fun HomeNetworkResponse.runtimeAuthRequestError(): RuntimeAuthRequestError {
    val envelope = parseHttpErrorEnvelope()
    return RuntimeAuthRequestError(
        status = code.takeIf { it > 0 },
        code = envelope?.serverCode,
        traceId = traceId,
        serverMessage = envelope?.serverMessage,
    )
}

/** A limit-reset request with no outcome: the status (none without an answer), the route's `code`, message, and trace id. */
private fun HomeNetworkResponse.limitResetRefusal(): RuntimeLimitResetRefusal {
    val envelope = parseHttpErrorEnvelope()
    return RuntimeLimitResetRefusal(
        status = code.takeIf { it > 0 },
        code = envelope?.serverCode,
        traceId = traceId,
        serverMessage = envelope?.serverMessage,
    )
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
    // services/machineReadModel.ts `deviceClass: isDeviceClass(machine.deviceClass) ? machine.deviceClass : null`.
    val deviceClass: String? = null,
    // routes/servers.ts GET /:id/machines `installerRepairHint: await readInstallerRepairHint(...)`
    // (packages/shared/src/index.ts ComputerInstallerRepairHint, #proj-raft-computer task #34):
    // advisory, the installer state needs a repair run on the machine; null when nothing to hint.
    val installerRepairHint: ComputerInstallerRepairHintDto? = null,
    // routes/servers.ts GET /:id/machines `upgradeScope: await readUpgradeScope(...)` (shared
    // ComputerUpgradeScopeUnavailable `{ ok: false, reason }`, botiverse/slock #15243): the Computer's
    // systemd manager refuses the scope a web upgrade's installer needs; null = allowed or unknown.
    val upgradeScope: ComputerUpgradeScopeDto? = null,
    // services/machineReadModel.ts `runtimeVersions: Record<string, string>` (runtime id -> the version
    // string the Computer's `ready` reported, e.g. "2.1.59 (Claude Code)"); the usage sheet's version line.
    val runtimeVersions: Map<String, String> = emptyMap(),
    // services/machineReadModel.ts `runtimeDetection: MachineRuntimeDetection | null` (slock #16477).
    val runtimeDetection: ComputerRuntimeDetectionDto? = null,
    val description: String? = null,
    val lastHeartbeat: String? = null,
    val createdAt: String? = null,
    // slock packages/server/src/services/machineReadModel.ts (MachineReadModel) and the
    // GET /servers/:id/machines projection in routes/servers.ts.
    val userId: String? = null,
    val computerAttachedByCurrentUser: Boolean = false,
    // routes/servers.ts buildMachineList (slock #15002): `controlledByCurrentUser:
    // controllerUserIdFromAttachers(controllerAttachers, m) === userId`, whether the viewer is the
    // machine's controller as the routes decide it (computerControllerUserId): the live Computer's
    // attacher, else the most recently revoked one's, else machines.userId. Unlike
    // computerAttachedByCurrentUser it survives a key revoke. Absent (null) from older servers.
    val controlledByCurrentUser: Boolean? = null,
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
    val memoryStatus: MachineSizeStatusDto? = null,
    // routes/servers.ts GET /:id/machines `attention: computerAttentionForViewer(...)` (slock #14587,
    // packages/shared/src/readiness.ts `ReadinessAttention`): what a person must act on. Additive:
    // absent from older servers and when the server's readiness read failed. Kept raw and read item
    // by item ([computerLoginSteps]) so an item this build cannot read never drops the machine.
    val attention: JsonElement? = null,
    // routes/servers.ts GET /:id/machines `revocation` (slock #16468, packages/shared/src/computerRevocation.ts
    // `ComputerRevocation`): set only for a machine whose Computers are all revoked; null/absent otherwise.
    val revocation: ComputerRevocationDto? = null,
    // services/machineReadModel.ts `versionRefusedAt` (slock #16514): when the server last refused
    // this machine on connect below the Computer version floor; null/absent otherwise.
    val versionRefusedAt: String? = null,
    // services/machineReadModel.ts `refusedComputerVersion` (slock #16881): the version the server
    // refused while this Computer is offline within the 7-day refusal window; null/absent otherwise.
    val refusedComputerVersion: String? = null,
    // Worker v1 step 2 (slock #17011, packages/shared/src/computerSettings.ts `MachineWorkerRuntimesFields`):
    // absent (null) on older servers -> section stays hidden; `[]` means none turned on.
    val workerRuntimesTurnedOn: List<String>? = null,
    val canChangeWorkerRuntimes: Boolean = false,
    val workerRuntimesChangeBy: String? = null
)

/** Outcome of `POST /servers/:id/machines/:machineId/runtimes/rescan` (slock #16477). */
@RaftCoreInternalApi enum class ComputerRuntimeRescanResult {
    Accepted,
    Forbidden,
    Offline,
    NotSent,
    Failed,
}

@Serializable
private data class ComputerRuntimeDetectionDto(
    val status: String? = null,
    val unfinishedRuntimes: List<String> = emptyList(),
    val reason: String? = null,
    val updatedAtMs: Long? = null,
) {
    fun toSnapshot(): SettingsComputerRuntimeDetection? {
        val parsedStatus = SettingsComputerRuntimeDetectionStatus.fromWire(status) ?: return null
        return SettingsComputerRuntimeDetection(
            status = parsedStatus,
            unfinishedRuntimes = unfinishedRuntimes.map(String::trim).filter(String::isNotEmpty),
            reason = SettingsComputerRuntimeDetectionReason.fromWire(reason),
            updatedAtMs = updatedAtMs,
        )
    }
}

/**
 * slock packages/shared/src/computerRevocation.ts `ComputerRevocation`:
 * `{ at, via, byUserName, subjectUserName, departureReason }`.
 * Additive: an unknown `via` decodes as null rather than dropping the revocation.
 */
@Serializable
private data class ComputerRevocationDto(
    val at: String? = null,
    val via: String? = null,
    val byUserName: String? = null,
    val subjectUserName: String? = null,
    val departureReason: String? = null
) {
    fun toSnapshot(): SettingsComputerRevocationSnapshot? {
        val revokedAt = at?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanReason = departureReason?.trim()?.takeIf { it in COMPUTER_REVOCATION_DEPARTURE_REASONS }
        return SettingsComputerRevocationSnapshot(
            at = revokedAt,
            via = SettingsComputerRevokedVia.fromWire(via),
            byUserName = byUserName?.trim()?.takeIf { it.isNotBlank() },
            subjectUserName = subjectUserName?.trim()?.takeIf { it.isNotBlank() },
            departureReason = cleanReason
        )
    }
}

private val COMPUTER_REVOCATION_DEPARTURE_REASONS = setOf("left", "removed", "account_deleted")

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
    val failureHint: ComputerUpgradeFailureHintDto? = null,
    // projectComputerUpgradeRequest `diskFull: row.outcome === "failed" ? upgradeDiskFullFromReason(row.reason) : null`
    // (slock #16536, packages/shared/src/computerUpgradePrefetch.ts ComputerUpgradeDiskFull).
    val diskFull: ComputerUpgradeDiskFullDto? = null
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
            failureHint = failureHint?.toSnapshot(),
            diskFull = diskFull?.toSnapshot()
        )
    }
}

/**
 * Shared `ComputerUpgradeDiskFull` (slock #16536): `{ neededBytes?, freeBytes? }` on a failed
 * upgrade request. Empty `{}` is an unsized out-of-space failure from the installer.
 */
@Serializable
private data class ComputerUpgradeDiskFullDto(
    val neededBytes: Double? = null,
    val freeBytes: Double? = null
) {
    fun toSnapshot(): SettingsComputerUpgradeDiskFullSnapshot {
        val needed = neededBytes?.takeIf { it.isFinite() && it >= 0.0 }?.toLong()
        val free = freeBytes?.takeIf { it.isFinite() && it >= 0.0 }?.toLong()
        return SettingsComputerUpgradeDiskFullSnapshot(
            neededBytes = if (needed != null && free != null) needed else null,
            freeBytes = if (needed != null && free != null) free else null
        )
    }
}

/** Shared `COMPUTER_INSTALLER_REPAIR_HINT_REASONS`: a reason this build does not know is no hint. */
@RaftCoreInternalApi val COMPUTER_INSTALLER_REPAIR_HINT_REASONS = setOf("unrecovered_operation")

/** Shared `ComputerInstallerRepairHint`. */
@Serializable
private data class ComputerInstallerRepairHintDto(val reason: String? = null)

/** Shared `ComputerUpgradeScopeUnavailable`: only `ok: false` is a refusal (shared `parseComputerUpgradeScope`). */
@Serializable
private data class ComputerUpgradeScopeDto(val ok: Boolean? = null, val reason: String? = null)

/**
 * Shared `ComputerUpgradeFailureHint` (slock #15061): `reason` is `unsafe_kill_mode`,
 * `installation_held`, or `earlier_recovery_pending`. `unit`, `killMode`, and `scope` belong only
 * to the systemd-unit variants.
 */
@Serializable
private data class ComputerUpgradeFailureHintDto(
    val reason: String? = null,
    val unit: String? = null,
    val killMode: String? = null,
    val scope: String? = null
) {
    fun toSnapshot(): SettingsComputerUpgradeFailureHintSnapshot? {
        val known = reason?.takeIf {
            it == "unsafe_kill_mode" || it == "installation_held" || it == "earlier_recovery_pending"
        } ?: return null
        if (known == "earlier_recovery_pending") {
            return SettingsComputerUpgradeFailureHintSnapshot(reason = known)
        }
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

@RaftCoreInternalApi enum class ComputerStatus(val wire: String) {
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
        deviceClass = deviceClass?.trim()?.takeIf { it.isNotBlank() },
        installerRepairHint = installerRepairHint?.reason?.takeIf { it in COMPUTER_INSTALLER_REPAIR_HINT_REASONS },
        upgradeScopeRefused = upgradeScope?.ok == false,
        runtimeVersions =
            runtimeVersions.mapNotNull { (runtime, version) ->
                version.trim().takeIf { runtime.isNotBlank() && it.isNotEmpty() }?.let { runtime to it }
            }.toMap(),
        runtimeDetection = runtimeDetection?.toSnapshot(),
        creatorUserId = userId?.trim()?.takeIf { it.isNotBlank() },
        attachedByCurrentUser = computerAttachedByCurrentUser,
        controlledByCurrentUser = controlledByCurrentUser,
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
        memoryStatus = memoryStatus?.toStatus(),
        loginSteps = attention?.let(::computerLoginSteps),
        serverNeedsAttention = attention?.let(::computerServerNeedsAttention),
        revocation = revocation?.toSnapshot(),
        versionRefusedAt = versionRefusedAt?.trim()?.takeIf { it.isNotBlank() },
        refusedComputerVersion = refusedComputerVersion?.trim()?.takeIf { it.isNotBlank() },
        workerRuntimesTurnedOn =
            workerRuntimesTurnedOn?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct(),
        canChangeWorkerRuntimes = canChangeWorkerRuntimes,
        workerRuntimesChangeBy =
            workerRuntimesChangeBy?.trim()?.takeIf { it == "owner_or_admin" || it == "any_member" }
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

/** slock `ReadinessAction` (packages/shared/src/readiness.ts `READINESS_ACTIONS`) a usage cell shows as its step. */
enum class ComputerLoginStepAction(val wire: String) {
    VerifyAccount("verify_account"),
    SignIn("sign_in")
}

/** A machine login (`shared:<runtime>`) that holds agents and needs [action] (web `rackLoginStep`). */
data class ComputerLoginStep(val runtimeId: String, val action: ComputerLoginStepAction)

/**
 * slock `ReadinessAttentionItem`: `{ code?, resource?, actor, actorName?, action, agentIds?, agentCount }`.
 * `code` and `resource` are absent for a viewer who may not see the reason. Only the fields the
 * usage cell's step reads.
 */
@Serializable
private data class ReadinessAttentionItemDto(
    val code: String? = null,
    val resource: ReadinessResourceDto? = null,
    val action: String? = null,
    val agentCount: Int = 0
)

/** slock `ReadinessResource`, the `login` member only: `{ kind: "login", key }` (`key` "shared:<runtime>" for a machine login). */
@Serializable
private data class ReadinessResourceDto(
    val kind: String? = null,
    val key: String? = null
)

/** slock `READINESS_ACTIONS`: every action the server derives from a code. */
private val READINESS_ACTION_WIRES = setOf("sign_in", "verify_account", "update_computer", "fix")

/**
 * Web `serverNeedsAttention` (slock #15534, utils/computerUpgradeIndicator.ts): the server's
 * `attention.count > 0` verdict when the machine row carried an `attention` summary; null from
 * older servers or when `count` is absent/unreadable (callers keep their fallback rule).
 */
@RaftCoreInternalApi fun computerServerNeedsAttention(attention: JsonElement): Boolean? {
    val count =
        ((attention as? JsonObject)?.get("count") as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.doubleOrNull
            ?.takeIf { it.isFinite() }
            ?: return null
    return count > 0.0
}

/**
 * Web `rackLoginStep`'s items (slock #14602): each `attention.items` entry with a `code` (the
 * viewer may see the reason), at least one agent, a machine login resource (`shared:<runtime>`)
 * and the action "verify_account" or "sign_in", in the server's order. An item this build cannot
 * read is skipped; an action outside slock's set is logged.
 */
@RaftCoreInternalApi fun computerLoginSteps(attention: JsonElement): List<ComputerLoginStep> {
    val items = (attention as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
    return items.mapNotNull { row ->
        val item = runCatching { computersApiJson.decodeFromJsonElement(ReadinessAttentionItemDto.serializer(), row) }.getOrNull()
            ?: return@mapNotNull null
        val action = item.action
        if (action != null && action !in READINESS_ACTION_WIRES) {
            reportUnknownTypeDropped(source = "machines.attention", field = "action", value = action, fallback = "no_step")
        }
        if (item.code.isNullOrBlank() || item.agentCount <= 0) return@mapNotNull null
        val resource = item.resource ?: return@mapNotNull null
        val key = resource.key ?: return@mapNotNull null
        if (resource.kind != "login" || !key.startsWith("shared:")) return@mapNotNull null
        val runtimeId = key.removePrefix("shared:").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val step = ComputerLoginStepAction.entries.firstOrNull { it.wire == action } ?: return@mapNotNull null
        ComputerLoginStep(runtimeId, step)
    }
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
    val agentStatus: String? = null,
    val partial: Boolean = false
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
        agentStatus = agentStatus,
        partial = partial
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
        // Absent (or not a list): not reported. `[]`: the model takes no effort (slock #15162).
        runCatching { obj["supportedReasoningEfforts"]?.jsonArray }
            .getOrNull()
            ?.mapNotNull { effort ->
                runCatching { effort.jsonPrimitive.contentOrNull }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
            ?.distinct()
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
    val runtimeFormV2: RuntimeFormV2MarkerDto? = null,
    // Kept raw and decoded leniently ([decodeResolvedRuntimeRaftCapabilities]) so an unexpected
    // shape inside `raftCapabilities` falls back to bundled defaults instead of dropping the row.
    val raftCapabilities: JsonElement? = null
)

@Serializable
private data class RuntimeFormV2MarkerDto(val protocolVersion: Int)

/** Request header asking the runtime-options rows to carry the `runtimeFormV2` marker. */
@RaftCoreInternalApi const val RUNTIME_FORM_V2_MARKER_HEADER = "X-Raft-Runtime-Form-V2"

/** slock `RUNTIME_OPTIONS_CAPABILITIES_CLIENT_CAPABILITY` (#16178): this build reads `option.raftCapabilities`. */
@RaftCoreInternalApi const val RUNTIME_OPTIONS_CAPABILITIES_CLIENT_CAPABILITY = "runtime-options-capabilities@1"

/**
 * slock `RAFT_CLIENT_CAPABILITIES_HEADER` (comma-separated tokens) on every request that answers
 * with a usage snapshot (the read, the refresh, the limit reset) or runtime-options rows:
 * - `RUNTIME_OPTIONS_CAPABILITIES_CLIENT_CAPABILITY` (#16178): the server attaches per-Computer
 *   `option.raftCapabilities` only to `/runtime-options` requests that declare it.
 * - `RUNTIME_USAGE_WINDOW_SCOPE_CLIENT_CAPABILITY` (#14605, #14667): the server sends a usage
 *   window's `scope` (every kind, `models` too) only to a request that declares it reads it.
 * - `RUNTIME_USAGE_CREDITS_CLIENT_CAPABILITY` (#15374): likewise an account's `credits`.
 * - `RUNTIME_USAGE_UNSUPPORTED_REASON_CLIENT_CAPABILITY` (#15395): likewise an unsupported account's `unsupportedReason`.
 * This app's parser reads them all; a server that predates a token ignores it.
 */
@RaftCoreInternalApi const val RAFT_CLIENT_CAPABILITIES_HEADER = "X-Raft-Client-Capabilities"
@RaftCoreInternalApi val RUNTIME_OPTIONS_REQUEST_HEADERS: Map<String, String> =
    mapOf(
        RUNTIME_FORM_V2_MARKER_HEADER to "1",
        RAFT_CLIENT_CAPABILITIES_HEADER to RUNTIME_OPTIONS_CAPABILITIES_CLIENT_CAPABILITY,
    )
@RaftCoreInternalApi const val RUNTIME_USAGE_WINDOW_SCOPE_CLIENT_CAPABILITY = "runtime-usage-window-scope@2"
@RaftCoreInternalApi const val RUNTIME_USAGE_CREDITS_CLIENT_CAPABILITY = "runtime-usage-credits@1"
/** slock `RUNTIME_USAGE_ACCOUNT_FOLDERS_CLIENT_CAPABILITY` (#15348): this build reads an account's `agentIds`. */
@RaftCoreInternalApi const val RUNTIME_USAGE_ACCOUNT_FOLDERS_CLIENT_CAPABILITY = "runtime-usage-account-folders@1"
/** slock `RUNTIME_USAGE_UNSUPPORTED_REASON_CLIENT_CAPABILITY` (#15395): this build reads an account's `unsupportedReason`. */
@RaftCoreInternalApi const val RUNTIME_USAGE_UNSUPPORTED_REASON_CLIENT_CAPABILITY = "runtime-usage-unsupported-reason@1"
@RaftCoreInternalApi val RUNTIME_USAGE_CLIENT_CAPABILITY_HEADERS: Map<String, String> =
    mapOf(
        RAFT_CLIENT_CAPABILITIES_HEADER to
            listOf(
                RUNTIME_USAGE_WINDOW_SCOPE_CLIENT_CAPABILITY,
                RUNTIME_USAGE_CREDITS_CLIENT_CAPABILITY,
                RUNTIME_USAGE_ACCOUNT_FOLDERS_CLIENT_CAPABILITY,
                RUNTIME_USAGE_UNSUPPORTED_REASON_CLIENT_CAPABILITY,
            ).joinToString(", ")
    )

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
        runtimeFormV2 = runtimeFormV2?.protocolVersion == 2,
        raftCapabilities = decodeResolvedRuntimeRaftCapabilities(raftCapabilities)
    )
}

@Serializable
private data class ComputerCommandResponseDto(val ok: Boolean)

@RaftCoreInternalApi const val COMPUTER_CONTROL_RESTART = "restart"
@RaftCoreInternalApi const val COMPUTER_CONTROL_UPGRADE = "upgrade"

/** `POST …/computer/restart` takes no fields: the route rejects a `targetVersion` for restart. */
@Serializable
@RaftCoreInternalApi class ComputerRestartBody

/** `POST …/computer/upgrade` body: the exact `targetVersion` the user consented to. */
@Serializable
@RaftCoreInternalApi data class ComputerUpgradeBody(val targetVersion: String)

@RaftCoreInternalApi fun buildComputerRestartBody(): String = computersApiJson.encodeToString(ComputerRestartBody.serializer(), ComputerRestartBody())

@RaftCoreInternalApi fun buildComputerUpgradeBody(targetVersion: String): String =
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

/**
 * A refused restart/upgrade keeps the server's `code`, message, and trace id; a refusal without a
 * machine-readable code falls back to the status mapping of the other Computer routes.
 */
private fun <T> HomeNetworkResponse.toComputerControlError(): DataResult<T> {
    if (this.code == 401) return toComputersDataError()
    val envelope = parseHttpErrorEnvelope()
    val code = envelope?.serverCode ?: return toComputersDataError()
    return DataResult.Error(
        ScreenError(
            code = code,
            retryable = this.code >= 500,
            serverMessage = if (this.code >= 500) null else envelope.serverMessage,
            serverCode = code,
            traceId = traceId,
        )
    )
}

@Serializable
@RaftCoreInternalApi data class RegisterComputerBody(val name: String)

/** [respondAsync]: always true, the answer may come as a machine operation (MachineOperations.kt). */
@Serializable
private data class RuntimeLimitResetBody(val idempotencyKey: String, val respondAsync: Boolean)

/** `{idempotencyKey, respondAsync: true}`, the key passed through unchanged (slock runtimeAuthRoutes.ts). */
@RaftCoreInternalApi fun buildRuntimeLimitResetBody(idempotencyKey: RuntimeLimitResetIdempotencyKey): String =
    computersApiJson.encodeToString(RuntimeLimitResetBody(idempotencyKey = idempotencyKey.value, respondAsync = true))

/** [onboardingRequiredReason]: this client reads `unsupported: onboarding_required` (slock runtimeAuthRoutes.ts). */
@Serializable
private data class ClaudeLimitResetBody(
    val idempotencyKey: String,
    val kind: String,
    val onboardingRequiredReason: Boolean,
    val respondAsync: Boolean,
)

/**
 * `{idempotencyKey, kind, onboardingRequiredReason: true, respondAsync: true}`: a Claude redeem names
 * the kind the person chose (slock #14336) and says it reads `onboarding_required` (web `redeemLimitReset`).
 */
@RaftCoreInternalApi fun buildClaudeLimitResetBody(idempotencyKey: RuntimeLimitResetIdempotencyKey, kind: RuntimeLimitResetKind): String =
    computersApiJson.encodeToString(
        ClaudeLimitResetBody(idempotencyKey = idempotencyKey.value, kind = kind.wire, onboardingRequiredReason = true, respondAsync = true)
    )

/** [consent] is left out unless true (the route takes only `true`). */
@Serializable
private data class ClaudeLimitResetsReadBody(val consent: Boolean? = null, val onboardingRequiredReason: Boolean, val respondAsync: Boolean)

/**
 * `{consent: true, onboardingRequiredReason: true, respondAsync: true}` for a read with consent, else
 * `{onboardingRequiredReason: true, respondAsync: true}` (web `readLimitResets`).
 */
@RaftCoreInternalApi fun buildClaudeLimitResetsReadBody(consent: Boolean): String =
    runtimeAuthBodyJson.encodeToString(
        ClaudeLimitResetsReadBody.serializer(),
        ClaudeLimitResetsReadBody(consent = true.takeIf { consent }, onboardingRequiredReason = true, respondAsync = true),
    )

@Serializable
private data class RuntimeAccountUsageRefreshBody(val reason: String)

@RaftCoreInternalApi fun buildRuntimeAccountUsageRefreshBody(reason: RuntimeAccountUsageRefreshReason): String =
    computersApiJson.encodeToString(RuntimeAccountUsageRefreshBody(reason = reason.wire))

@RaftCoreInternalApi fun buildRegisterComputerBody(name: String): String =
    computersApiJson.encodeToString(RegisterComputerBody(name = name))

@RaftCoreInternalApi fun buildComputerUpdateBody(name: String? = null, description: String? = null): String =
    computersApiJson.encodeToString(
        buildJsonObject {
            name?.let { put("name", it) }
            description?.let { put("description", it) }
        }
    )

@Serializable
private data class ComputerProcJobsReadDto(
    val status: String = "",
    val reportedAt: String? = null,
    val jobs: List<ComputerProcJobDto>? = null
)

@Serializable
private data class ComputerProcJobDto(
    val agentId: String = "",
    val name: String = "",
    val state: String = "",
    val startedAt: String = "",
    val exitedAt: String? = null,
    val exitCode: Int? = null,
    val exitSignal: String? = null,
    val exitReason: String? = null,
    val stoppedBy: String? = null,
    val memoryMaxBytes: Long? = null,
    val rssBytes: Long? = null
) {
    fun toDomainOrNull(): ComputerProcJobSnapshot? {
        val cleanAgentId = agentId.trim()
        val cleanName = name.trim()
        val cleanStartedAt = startedAt.trim()
        if (cleanAgentId.isBlank() || cleanName.isBlank() || cleanStartedAt.isBlank()) return null
        val parsedState =
            when (state.trim()) {
                "running" -> ComputerProcJobState.Running
                "exited" -> ComputerProcJobState.Exited
                else -> {
                    reportUnknownTypeDropped(
                        source = "machines.proc_jobs",
                        field = "state",
                        value = state,
                        fallback = "drop"
                    )
                    return null
                }
            }
        return ComputerProcJobSnapshot(
            agentId = cleanAgentId,
            name = cleanName,
            state = parsedState,
            startedAt = cleanStartedAt,
            exitedAt = exitedAt?.trim()?.takeIf { it.isNotBlank() },
            exitCode = exitCode,
            exitSignal = exitSignal?.trim()?.takeIf { it.isNotBlank() },
            exitReason = exitReason?.trim()?.takeIf { it.isNotBlank() },
            stoppedBy = stoppedBy?.trim()?.takeIf { it.isNotBlank() },
            memoryMaxBytes = memoryMaxBytes?.takeIf { it > 0L },
            rssBytes = rssBytes?.takeIf { it >= 0L }
        )
    }
}

@Serializable
private data class ComputerProcJobStopResponseDto(val outcome: String? = null)

@Serializable
private data class ComputerProcJobStopBody(val agentId: String, val name: String)

@RaftCoreInternalApi fun buildComputerProcJobStopBody(agentId: String, name: String): String =
    computersApiJson.encodeToString(
        ComputerProcJobStopBody.serializer(),
        ComputerProcJobStopBody(agentId = agentId, name = name)
    )
