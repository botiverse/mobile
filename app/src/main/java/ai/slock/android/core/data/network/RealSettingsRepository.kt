package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.repository.AccountProfileSnapshot
import ai.slock.android.core.data.repository.AccountProfileUpdateRequest
import ai.slock.android.core.data.repository.AccountSettingsSnapshot
import ai.slock.android.core.data.repository.SettingsBillingCurrentPlanSnapshot
import ai.slock.android.core.data.repository.SettingsBillingPlanSnapshot
import ai.slock.android.core.data.repository.SettingsBillingSnapshot
import ai.slock.android.core.data.repository.SettingsPlanUsageSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountSnapshot
import ai.slock.android.core.data.repository.ConnectedAccountStatus
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.SessionActionResult
import ai.slock.android.core.data.repository.SettingsComputerAgentSnapshot
import ai.slock.android.core.data.repository.SettingsComputerDetailSnapshot
import ai.slock.android.core.data.repository.SettingsComputerSnapshot
import ai.slock.android.core.data.repository.SettingsComputerWorkspaceSnapshot
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsMobileSnapshot
import ai.slock.android.core.data.repository.SettingsMobileToggleSnapshot
import ai.slock.android.core.data.repository.SessionSettingsSnapshot
import ai.slock.android.core.data.repository.SettingsRepository
import ai.slock.android.core.data.repository.SettingsSectionErrorSnapshot
import ai.slock.android.core.data.repository.SettingsSectionAvailability
import ai.slock.android.core.data.repository.SettingsServerProfileSnapshot
import ai.slock.android.core.data.repository.SettingsServerSectionSnapshot
import ai.slock.android.core.data.repository.SettingsServerSnapshot
import ai.slock.android.core.data.repository.SettingsSessionSnapshot
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class RealSettingsRepository(
    private val store: AuthSessionStore,
    private val api: SlockApi,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : SettingsRepository {
    override suspend fun getAccount(): DataResult<AccountSettingsSnapshot> {
        val session = store.readSession() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.settingsAccount() },
            mapper = { json -> json.toAccountSettingsSnapshot(session) }
        )
    }

    override suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot> {
        val session = store.readSession() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.updateSettingsAccount(request.toJsonBody()) },
            mapper = { json -> json.toAccountSettingsSnapshot(session) }
        )
    }

    override suspend fun getSessions(): DataResult<SessionSettingsSnapshot> {
        store.readSession() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.settingsSessions() },
            emptyWhen = { it.sessions.isEmpty() },
            mapper = { json -> json.toSessionSettingsSnapshot() }
        ).mapSettingsContractMismatch("settings_sessions_contract_mismatch")
    }

    override suspend fun revokeSession(sessionId: String): DataResult<SessionActionResult> {
        if (sessionId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        store.readSession() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.revokeSettingsSession(sessionId) },
            mapper = { json ->
                SessionActionResult(
                    sessionId = json.string("sessionId", "id") ?: sessionId,
                    revoked = json.boolean("revoked", "ok", "success") ?: true
                )
            }
        ).mapSettingsContractMismatch("settings_session_revoke_contract_mismatch")
    }

    override suspend fun getComputers(): DataResult<SettingsComputersSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val serverId = session.currentServerId
            ?: return DataResult.NoPermission(
                PermissionBlocker(code = "server_required", requiredCapability = "selected_server")
            )
        logger("machines_fetch_start serverId=$serverId")
        val result = execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.settingsMachines(serverId) },
            emptyWhen = { it.computers.isEmpty() },
            source = "settings_machines",
            warningLogger = warningLogger,
            mapper = { json -> json.toSettingsComputersSnapshot(warningLogger) }
        ).mapSettingsContractMismatch("settings_machines_contract_mismatch")
        when (result) {
            is DataResult.Content -> logger(
                "machines_fetch_content serverId=$serverId count=${result.data.computers.size} canAdd=${result.data.canAddComputer}"
            )
            is DataResult.Empty -> logger("machines_fetch_empty serverId=$serverId empty=${result.reason}")
            is DataResult.Error -> warningLogger("machines_fetch_error serverId=$serverId code=${result.error.code}")
            is DataResult.NoPermission -> warningLogger("machines_fetch_no_permission serverId=$serverId code=${result.blocker.code}")
        }
        return result
    }

    override suspend fun getComputerDetail(machineId: String): DataResult<SettingsComputerDetailSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val serverId = session.currentServerId
            ?: return DataResult.NoPermission(
                PermissionBlocker(code = "server_required", requiredCapability = "selected_server")
            )
        if (machineId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        logger("machine_detail_fetch_start serverId=$serverId machineId=$machineId")
        logger("machine_detail_section_start serverId=$serverId machineId=$machineId section=machines")
        val machines = execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.settingsMachines(serverId) },
            emptyWhen = { it.computers.isEmpty() },
            source = "settings_machine_detail_machines",
            warningLogger = warningLogger,
            mapper = { json -> json.toSettingsComputersSnapshot(warningLogger) }
        ).mapSettingsContractMismatch("settings_machines_contract_mismatch")
        machines.logMachineDetailSection(
            serverId = serverId,
            machineId = machineId,
            section = "machines",
            count = (machines as? DataResult.Content)?.data?.computers?.size,
            logger = logger,
            warningLogger = warningLogger
        )
        if (machines !is DataResult.Content) return machines.mapSettingsDetailError(serverId, machineId)
        val computer = machines.data.computers.firstOrNull { it.id == machineId }
            ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        logger("machine_detail_section_start serverId=$serverId machineId=$machineId section=workspaces")
        val workspaces = execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.settingsMachineWorkspaces(serverId, machineId) },
            source = "settings_machine_workspaces",
            warningLogger = warningLogger,
            mapper = { json -> json.toSettingsComputerWorkspaces(warningLogger) }
        ).mapSettingsContractMismatch("settings_machine_workspaces_contract_mismatch")
        workspaces.logMachineDetailSection(
            serverId = serverId,
            machineId = machineId,
            section = "workspaces",
            count = (workspaces as? DataResult.Content)?.data?.size,
            logger = logger,
            warningLogger = warningLogger
        )
        logger("machine_detail_section_start serverId=$serverId machineId=$machineId section=agents")
        val agents = execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.settingsAgents() },
            source = "settings_machine_agents",
            warningLogger = warningLogger,
            mapper = { json -> json.toSettingsComputerAgents(machineId, warningLogger) }
        ).mapSettingsContractMismatch("settings_agents_contract_mismatch")
        agents.logMachineDetailSection(
            serverId = serverId,
            machineId = machineId,
            section = "agents",
            count = (agents as? DataResult.Content)?.data?.size,
            logger = logger,
            warningLogger = warningLogger
        )
        val workspaceRows = (workspaces as? DataResult.Content)?.data.orEmpty()
        val agentRows = (agents as? DataResult.Content)?.data.orEmpty()
        val workspacesError = workspaces.toSettingsSectionErrorSnapshot()
        val agentsError = agents.toSettingsSectionErrorSnapshot()
        logger(
            "machine_detail_fetch_content serverId=$serverId machineId=$machineId workspaces=${workspaceRows.size} agents=${agentRows.size} workspacesError=${workspacesError?.code ?: "none"} agentsError=${agentsError?.code ?: "none"}"
        )
        return DataResult.Content(
            SettingsComputerDetailSnapshot(
                computer = computer,
                workspaces = workspaceRows,
                agents = agentRows,
                sections = listOf(
                    SettingsServerSectionSnapshot(
                        key = "machine_status_events",
                        title = "Status Events",
                        detail = "Web updates status through realtime machine:status and daemon:status events; no Settings detail REST contract is confirmed yet."
                    ),
                    SettingsServerSectionSnapshot(
                        key = "machine_logs",
                        title = "Logs",
                        detail = "Machine logs or heartbeat history endpoint is not confirmed for Android yet."
                    )
                ),
                workspacesError = workspacesError,
                agentsError = agentsError
            )
        )
    }

    override suspend fun getBilling(): DataResult<SettingsBillingSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val server = session.servers.firstOrNull { it.id == session.currentServerId }
            ?: return DataResult.NoPermission(
                PermissionBlocker(code = "server_required", requiredCapability = "selected_server")
            )
        val canViewBilling = server.role == "owner" || server.role == "admin"
        if (!canViewBilling) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "billing_owner_required", requiredCapability = "manage_billing")
            )
        }
        val currentPlanName = server.plan.toPlanDisplayName()
        return DataResult.Content(
            SettingsBillingSnapshot(
                currentPlan = SettingsBillingCurrentPlanSnapshot(
                    planName = currentPlanName,
                    usageRows = listOf(
                        SettingsPlanUsageSnapshot(label = "Workspace", value = server.name),
                        SettingsPlanUsageSnapshot(label = "Role", value = server.role?.replaceFirstChar { it.uppercase() } ?: "Member"),
                        SettingsPlanUsageSnapshot(label = "Plan source", value = "Selected server")
                    ),
                    trialActive = false
                ),
                plans = listOf(
                    SettingsBillingPlanSnapshot(
                        name = "Starter",
                        price = "$0",
                        features = listOf("Core chat", "Inbox and thread access")
                    ),
                    SettingsBillingPlanSnapshot(
                        name = "Team",
                        price = "$12 / user",
                        features = listOf("Advanced permissions", "Billing controls"),
                        current = currentPlanName.equals("Team", ignoreCase = true)
                    ),
                    SettingsBillingPlanSnapshot(
                        name = "Enterprise",
                        price = "Contact sales",
                        features = listOf("SAML", "Audit log", "Priority support"),
                        comingSoon = true
                    )
                )
            )
        )
    }

    override suspend fun getServerSettings(): DataResult<SettingsServerSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val server = session.servers.firstOrNull { it.id == session.currentServerId }
        val name = server?.name ?: "Slock"
        return DataResult.Content(
            SettingsServerSnapshot(
                profile = SettingsServerProfileSnapshot(
                    name = name,
                    slug = name.toSettingsSlug(),
                    canSave = false
                ),
                sections = listOf(
                    SettingsServerSectionSnapshot(
                        key = "billing",
                        title = "Plan & Billing",
                        detail = "Current plan: ${server?.plan.toPlanDisplayName()}. Open Billing for readable plan and usage state.",
                        state = SettingsSectionAvailability.Available
                    ),
                    SettingsServerSectionSnapshot(
                        key = "danger",
                        title = "Danger Zone",
                        detail = "Delete Server is disabled in this Android build.",
                        state = SettingsSectionAvailability.NoPermission
                    )
                )
            )
        )
    }

    override suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot> {
        store.readSession() ?: return authRequired()
        return DataResult.Content(
            SettingsMobileSnapshot(
                pushNotifications = SettingsMobileToggleSnapshot(
                    enabled = false,
                    available = true,
                    statusLabel = "Disabled",
                    detail = "Notifications can arrive while Slock is in the background. This Android build can preview the local state before the push API is connected."
                ),
                muteServer = SettingsMobileToggleSnapshot(
                    enabled = false,
                    available = false,
                    statusLabel = "Not muted",
                    detail = "Server mute is unavailable until the mobile settings endpoint is confirmed."
                ),
                sendTestEnabled = true
            )
        )
    }
}

private fun String?.toPlanDisplayName(): String =
    when (this?.lowercase()) {
        null, "" -> "Unknown"
        "hobby", "starter" -> "Hobby"
        "team" -> "Team"
        "business", "enterprise" -> "Business"
        else -> replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private fun <T> DataResult<T>.mapSettingsContractMismatch(code: String): DataResult<T> =
    when (this) {
        is DataResult.Error -> {
            if (error.code == "http_404") {
                DataResult.Error(ScreenError(code = code, retryable = false))
            } else {
                this
            }
        }
        else -> this
    }

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

private fun AccountProfileUpdateRequest.toJsonBody() =
    Gson().toJson(
        mapOf(
            "displayName" to displayName,
            "handle" to handle,
            "email" to email
        )
    ).toRequestBody(jsonMediaType)

private fun JsonObject.toAccountSettingsSnapshot(session: AuthSession): AccountSettingsSnapshot {
    val user = objectValue("user", "account", "profile") ?: this
    val displayName = user.string("displayName", "name")
        ?: session.user.bestDisplayName
    val email = user.string("email") ?: session.user.email
    val handle = user.string("handle", "name", "username")
        ?: email.takeIf { it.isNotBlank() }
        ?: displayName
    val role = user.string("role", "roleLabel")
        ?: session.servers.firstOrNull { it.id == session.currentServerId }?.role
    val connectedAccounts = items("connectedAccounts", "providers", "accounts")
        .map { it.asObject().toConnectedAccount() }
        .ifEmpty {
            listOf(
                ConnectedAccountSnapshot(
                    provider = "Email password",
                    identifier = email,
                    status = ConnectedAccountStatus.Connected
                )
            )
        }
    return AccountSettingsSnapshot(
        profile = AccountProfileSnapshot(
            userId = user.string("id", "userId") ?: session.user.id,
            displayName = displayName,
            handle = handle,
            email = email,
            avatarLabel = displayName.take(2).uppercase(),
            avatarUrl = user.string("avatarUrl", "avatar"),
            verified = user.boolean("emailVerified", "verified") ?: session.user.emailVerified ?: false,
            roleLabel = role?.toRoleLabel()
        ),
        connectedAccounts = connectedAccounts,
        canEditProfile = boolean("canEditProfile", "canEdit") ?: true
    )
}

private fun JsonObject.toConnectedAccount(): ConnectedAccountSnapshot {
    val provider = string("provider", "name", "type") ?: "Account"
    return ConnectedAccountSnapshot(
        provider = provider,
        identifier = string("identifier", "email", "username", "url") ?: provider,
        status = string("status")?.toConnectedAccountStatus()
            ?: if (boolean("connected", "enabled") == false) {
                ConnectedAccountStatus.NotConnected
            } else {
                ConnectedAccountStatus.Connected
            }
    )
}

private fun JsonObject.toSessionSettingsSnapshot(): SessionSettingsSnapshot {
    val sessions = items("sessions", "items").map { it.asObject().toSessionSnapshot() }
    return SessionSettingsSnapshot(
        sessions = sessions,
        canRevokeOtherSessions = boolean("canRevokeOtherSessions", "canRevokeOthers") ?: true
    )
}

private fun JsonObject.toSessionSnapshot(): SettingsSessionSnapshot {
    val current = boolean("current", "isCurrent") ?: false
    return SettingsSessionSnapshot(
        id = string("id", "sessionId") ?: "unknown-session",
        deviceName = string("deviceName", "device", "name") ?: "Unknown device",
        browserLabel = string("browserLabel", "browser", "client", "userAgent") ?: "Unknown client",
        locationLabel = string("locationLabel", "location", "ipLabel") ?: "Unknown location",
        lastActiveLabel = string("lastActiveLabel", "lastActiveAt", "updatedAt", "createdAt") ?: "Unknown activity",
        current = current,
        revokeEnabled = boolean("revokeEnabled", "canRevoke") ?: !current
    )
}

private fun JsonObject.toSettingsComputersSnapshot(warningLogger: (String) -> Unit): SettingsComputersSnapshot {
    val computers = machineItems()
        .map { it.asObject().toSettingsComputerSnapshot() }
        .filter { it.id.isNotBlank() || it.name.isNotBlank() }
    if (computers.isEmpty()) {
        warningLogger("empty_items source=settings_machines stage=list_extract rootKeys=${safeKeyList()}")
    }
    return SettingsComputersSnapshot(
        computers = computers,
        canAddComputer = boolean("canAddComputer", "canCreate", "canRegisterMachine") ?: false,
        addComputerUnavailableReason = string("addComputerUnavailableReason", "unavailableReason")
    )
}

private fun JsonObject.toSettingsComputerSnapshot(): SettingsComputerSnapshot {
    val id = string("id", "machineId", "computerId", "uuid") ?: ""
    val hostname = string("hostname", "hostName")
    val name = string("name", "displayName", "deviceName") ?: hostname
        ?: id.ifBlank { "Unknown computer" }
    val version = string("daemonVersion", "daemon_version", "version", "agentVersion", "clientVersion")
    return SettingsComputerSnapshot(
        id = id.ifBlank { name },
        name = name,
        daemonVersion = version ?: "daemon unavailable",
        online = machineOnline(),
        hostname = hostname,
        platform = string("platform", "os", "operatingSystem"),
        lastSeenLabel = string("lastSeen", "lastSeenAt", "lastActiveAt", "updatedAt")
    )
}

private fun JsonObject.machineOnline(): Boolean {
    boolean("online", "isOnline", "is_online", "connected", "active", "isActive")?.let { return it }
    val status = string("status", "state", "connectionStatus", "connection_state")?.lowercase() ?: return false
    return status in setOf("online", "connected", "active", "ready", "running")
}

private fun JsonObject.machineItems() =
    items("machines", "computers", "items", "nodes", "records", "rows", "list", "results", "data")

private fun JsonObject.safeKeyList(): String =
    entrySet().map { it.key }.sorted().joinToString("|").ifBlank { "none" }

private fun JsonObject.toSettingsComputerWorkspaces(
    warningLogger: (String) -> Unit
): List<SettingsComputerWorkspaceSnapshot> {
    val workspaces = items("workspaces", "items", "nodes", "records", "rows", "list", "results", "data")
        .map { it.asObject().toSettingsComputerWorkspaceSnapshot() }
        .filter { it.id.isNotBlank() || it.path.isNotBlank() }
    if (workspaces.isEmpty()) {
        warningLogger("empty_items source=settings_machine_workspaces stage=list_extract rootKeys=${safeKeyList()}")
    }
    return workspaces
}

private fun JsonObject.toSettingsComputerWorkspaceSnapshot(): SettingsComputerWorkspaceSnapshot {
    val path = string("path", "workspacePath", "directory", "rootPath") ?: ""
    val name = string("name", "workspaceName", "directoryName")
        ?: path.substringAfterLast('/').ifBlank { path.ifBlank { "Workspace" } }
    val id = string("id", "workspaceId", "directoryName") ?: path.ifBlank { name }
    return SettingsComputerWorkspaceSnapshot(
        id = id,
        name = name,
        path = path.ifBlank { name },
        statusLabel = string("status", "state"),
        lastActiveLabel = string("lastActiveAt", "lastActivityAt", "updatedAt")
    )
}

private fun JsonObject.toSettingsComputerAgents(
    machineId: String,
    warningLogger: (String) -> Unit
): List<SettingsComputerAgentSnapshot> {
    val agents = items("agents", "items", "nodes", "records", "rows", "list", "results", "data")
        .map { it.asObject() }
        .filter { it.belongsToMachine(machineId) }
        .map { it.toSettingsComputerAgentSnapshot() }
    if (agents.isEmpty()) {
        warningLogger("empty_items source=settings_machine_agents stage=list_extract rootKeys=${safeKeyList()}")
    }
    return agents
}

private fun JsonObject.belongsToMachine(machineId: String): Boolean =
    string("machineId", "machine_id", "computerId", "computer_id", "daemonId", "daemon_id") == machineId

private fun JsonObject.toSettingsComputerAgentSnapshot(): SettingsComputerAgentSnapshot {
    val id = string("id", "agentId", "userId") ?: ""
    val name = string("name", "displayName", "agentName") ?: id.ifBlank { "Agent" }
    return SettingsComputerAgentSnapshot(
        id = id.ifBlank { name },
        name = name,
        statusLabel = string("status", "activity", "state"),
        ownerLabel = string("ownerName", "assigneeName", "userName", "createdByName")
    )
}

private fun <T> DataResult<T>.mapSettingsDetailError(
    serverId: String,
    machineId: String
): DataResult<SettingsComputerDetailSnapshot> =
    when (this) {
        is DataResult.Content -> error("Cannot map detail content as error.")
        is DataResult.Empty -> {
            DataResult.Content(
                SettingsComputerDetailSnapshot(
                    computer = SettingsComputerSnapshot(
                        id = machineId,
                        name = machineId,
                        daemonVersion = "daemon unavailable",
                        online = false
                    ),
                    workspaces = emptyList(),
                    agents = emptyList(),
                    sections = listOf(
                        SettingsServerSectionSnapshot(
                            key = "machine_detail_unavailable",
                            title = "Computer Detail",
                            detail = "No detail rows were returned for server $serverId."
                        )
                    )
                )
            )
        }
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
    }

private fun <T> DataResult<T>.toSettingsSectionErrorSnapshot(): SettingsSectionErrorSnapshot? =
    when (this) {
        is DataResult.Content, is DataResult.Empty -> null
        is DataResult.Error -> SettingsSectionErrorSnapshot(code = error.code, retryable = error.retryable)
        is DataResult.NoPermission -> SettingsSectionErrorSnapshot(code = blocker.code, retryable = false)
    }

private fun <T> DataResult<T>.logMachineDetailSection(
    serverId: String,
    machineId: String,
    section: String,
    count: Int?,
    logger: (String) -> Unit,
    warningLogger: (String) -> Unit
) {
    when (this) {
        is DataResult.Content -> logger(
            "machine_detail_section_result serverId=$serverId machineId=$machineId section=$section result=content count=${count ?: 0}"
        )
        is DataResult.Empty -> logger(
            "machine_detail_section_result serverId=$serverId machineId=$machineId section=$section result=empty code=${reason.name}"
        )
        is DataResult.Error -> warningLogger(
            "machine_detail_section_result serverId=$serverId machineId=$machineId section=$section result=error code=${error.code} retryable=${error.retryable}"
        )
        is DataResult.NoPermission -> warningLogger(
            "machine_detail_section_result serverId=$serverId machineId=$machineId section=$section result=noPermission code=${blocker.code}"
        )
    }
}

private fun JsonObject.objectValue(vararg names: String): JsonObject? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonObject) return value.asJsonObject
    }
    val data = get("data")
    if (data != null && data.isJsonObject) return data.asJsonObject.objectValue(*names) ?: data.asJsonObject
    return null
}

private fun String.toConnectedAccountStatus(): ConnectedAccountStatus =
    when (lowercase()) {
        "connected", "enabled", "active" -> ConnectedAccountStatus.Connected
        "not_connected", "notconnected", "disconnected", "disabled" -> ConnectedAccountStatus.NotConnected
        "loading", "pending" -> ConnectedAccountStatus.Loading
        "failed", "error" -> ConnectedAccountStatus.Failed
        else -> ConnectedAccountStatus.Connected
    }

private fun String.toRoleLabel(): String =
    replace("_", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun String.toSettingsSlug(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "server" }
