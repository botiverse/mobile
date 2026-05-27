package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val settingsRepositoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private suspend fun SlockNetworkClient.settingsAccount(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "auth/me")

private suspend fun SlockNetworkClient.updateSettingsAccount(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "auth/me", method = "PATCH", bodyText = bodyText)

private suspend fun SlockNetworkClient.settingsMachines(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/machines", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.settingsMachineWorkspaces(
    priority: DataRequestPriority,
    serverId: String,
    machineId: String
): HomeNetworkResponse =
    request(
        priority = priority,
        path = "servers/$serverId/machines/$machineId/workspaces",
        serverIdOverride = ServerId(serverId)
    )

private suspend fun SlockNetworkClient.settingsAgents(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "agents")

private suspend fun SlockNetworkClient.serverMembers(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/members", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.settingsServerUsage(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/usage", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.settingsBillingSubscription(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "billing/subscription")

private suspend fun SlockNetworkClient.settingsServerOnboarding(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/onboarding-settings", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.updateSettingsServerProfile(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun SlockNetworkClient.createSettingsServerInvite(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/invites", method = "POST", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun SlockNetworkClient.deleteSettingsServer(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId", method = "DELETE", serverIdOverride = ServerId(serverId))

class RealSettingsRepository(
    private val sessionStore: SlockAuthSessionStore,
    private val networkClient: SlockNetworkClient,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : SettingsRepository {
    private val computersApiClient = SlockComputersApiClient(SlockComputersNetworkApi(networkClient))

    override suspend fun getAccount(): DataResult<AccountSettingsSnapshot> {
        val session = sessionStore.readSession() ?: return authRequired()
        return executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { networkClient.settingsAccount(DataRequestPriority.Visible) },
            mapper = { bodyText ->
                parseSharedAccountSettingsSnapshot(
                    bodyText = bodyText,
                    fallbackRole = session.servers.firstOrNull { it.id == session.currentServerId }?.role
                ) ?: throw IllegalStateException("Account settings response missing user")
            }
        )
    }

    override suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot> {
        val session = sessionStore.readSession() ?: return authRequired()
        return executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = {
                networkClient.updateSettingsAccount(
                    DataRequestPriority.Foreground,
                    buildSharedAccountProfileUpdateBody(request)
                )
            },
            mapper = { bodyText ->
                parseSharedAccountSettingsSnapshot(
                    bodyText = bodyText,
                    fallbackRole = session.servers.firstOrNull { it.id == session.currentServerId }?.role
                ) ?: throw IllegalStateException("Account settings response missing user")
            }
        )
    }

    override suspend fun getComputers(): DataResult<SettingsComputersSnapshot> {
        val serverId = selectedServerId() ?: return serverRequired()
        logger("machines_fetch_start serverId=$serverId")
        val result = settingsComputers(serverId).emptyWhen { it.computers.isEmpty() }
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
        val serverId = selectedServerId() ?: return serverRequired()
        if (machineId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        logger("machine_detail_fetch_start serverId=$serverId machineId=$machineId")
        logger("machine_detail_section_start serverId=$serverId machineId=$machineId section=machines")
        val machines = settingsComputers(serverId).emptyWhen { it.computers.isEmpty() }
        machines.logMachineDetailSection(serverId, machineId, "machines", (machines as? DataResult.Content)?.data?.computers?.size, logger, warningLogger)
        if (machines !is DataResult.Content) return machines.mapSettingsDetailError(serverId, machineId)
        val computer = machines.data.computers.firstOrNull { it.id == machineId }
            ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)

        logger("machine_detail_section_start serverId=$serverId machineId=$machineId section=workspaces")
        val workspaces = settingsMachineWorkspaces(serverId, machineId)
        workspaces.logMachineDetailSection(serverId, machineId, "workspaces", (workspaces as? DataResult.Content)?.data?.size, logger, warningLogger)

        logger("machine_detail_section_start serverId=$serverId machineId=$machineId section=agents")
        val agents = settingsComputerAgents(machineId).mapSettingsContractMismatch("settings_agents_contract_mismatch")
        agents.logMachineDetailSection(serverId, machineId, "agents", (agents as? DataResult.Content)?.data?.size, logger, warningLogger)

        val workspaceRows = (workspaces as? DataResult.Content)?.data.orEmpty()
        val agentRows = enrichComputerAgents(serverId, (agents as? DataResult.Content)?.data.orEmpty())
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

    override suspend fun registerComputer(request: SettingsComputerRegisterRequest): DataResult<SettingsComputerRegistrationResult> {
        val serverId = selectedServerId() ?: return serverRequired()
        val normalizedName = request.name.trim()
        if (normalizedName.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return computersApiClient.registerMachine(ServerId(serverId), normalizedName).mapComputerResult { summary ->
            SettingsComputerRegistrationResult(
                machine = summary.machine.toSettingsComputerSnapshot(),
                apiKeyPrefix = summary.apiKeyPrefix
            )
        }
    }

    override suspend fun renameComputer(request: SettingsComputerRenameRequest): DataResult<SettingsComputerSnapshot> {
        val serverId = selectedServerId() ?: return serverRequired()
        val machineId = request.machineId.trim()
        val normalizedName = request.name.trim()
        if (machineId.isBlank() || normalizedName.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return computersApiClient.renameMachine(ServerId(serverId), machineId, normalizedName).mapComputerResult {
            it.toSettingsComputerSnapshot()
        }
    }

    override suspend fun deleteComputer(machineId: String): DataResult<SettingsComputerActionResult> {
        val serverId = selectedServerId() ?: return serverRequired()
        val normalizedMachineId = machineId.trim()
        if (normalizedMachineId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return computersApiClient.deleteMachine(ServerId(serverId), normalizedMachineId).mapComputerResult {
            SettingsComputerActionResult(
                machineId = normalizedMachineId,
                success = it.ok,
                action = "delete"
            )
        }
    }

    override suspend fun rotateComputerKey(machineId: String): DataResult<SettingsComputerKeyRotationResult> {
        val serverId = selectedServerId() ?: return serverRequired()
        val normalizedMachineId = machineId.trim()
        if (normalizedMachineId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return computersApiClient.rotateMachineKey(ServerId(serverId), normalizedMachineId).mapComputerResult {
            SettingsComputerKeyRotationResult(
                machineId = normalizedMachineId,
                apiKeyPrefix = it.apiKeyPrefix
            )
        }
    }

    override suspend fun getComputerRuntimeModels(
        machineId: String,
        runtime: String
    ): DataResult<SettingsComputerRuntimeModelsSnapshot> {
        val serverId = selectedServerId() ?: return serverRequired()
        val normalizedMachineId = machineId.trim()
        val normalizedRuntime = runtime.trim()
        if (normalizedMachineId.isBlank() || normalizedRuntime.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return computersApiClient.runtimeModels(ServerId(serverId), normalizedMachineId, normalizedRuntime).mapComputerResult {
            SettingsComputerRuntimeModelsSnapshot(
                machineId = normalizedMachineId,
                runtime = it.runtime,
                models = it.models,
                defaultModel = it.defaultModel
            )
        }
    }

    override suspend fun deleteComputerWorkspace(request: SettingsComputerWorkspaceDeleteRequest): DataResult<SettingsComputerActionResult> {
        val serverId = selectedServerId() ?: return serverRequired()
        val machineId = request.machineId.trim()
        val directoryName = request.directoryName.trim()
        if (machineId.isBlank() || directoryName.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return computersApiClient.deleteMachineWorkspace(ServerId(serverId), machineId, directoryName).mapComputerResult {
            SettingsComputerActionResult(
                machineId = machineId,
                success = it.ok,
                action = "delete_workspace",
                directoryName = directoryName
            )
        }
    }

    override suspend fun getBilling(): DataResult<SettingsBillingSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
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
                        SettingsPlanUsageSnapshot(label = "Role", value = server.role.orEmpty().replaceFirstChar { it.uppercase() }),
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
        val server = selectedServer() ?: return serverRequired()
        val serverId = server.id
        val canManageServer = server.canManageServer
        logger("server_settings_fetch_start serverId=$serverId")

        val members = fetchServerMembers(serverId)
        val usage = fetchServerUsage(serverId)
        val billing = fetchServerBilling()
        val onboarding =
            if (server.role == "owner") {
                fetchServerOnboarding(serverId)
            } else {
                DataResult.NoPermission(
                    PermissionBlocker(
                        code = "server_owner_required",
                        requiredCapability = "manage_onboarding"
                    )
                )
            }

        val admins = (members as? DataResult.Content)?.data.orEmpty()
        val usageRows = mergeServerUsageRows(
            usage = (usage as? DataResult.Content)?.data.orEmpty(),
            billing = (billing as? DataResult.Content)?.data.orEmpty()
        )
        val onboardingSnapshot = (onboarding as? DataResult.Content)?.data
        val sections = listOf(
            SettingsServerSectionSnapshot(
                key = "admins",
                title = "Admins",
                detail = adminsSectionDetail(members, admins.size),
                state = members.sectionState(admins.isEmpty())
            ),
            SettingsServerSectionSnapshot(
                key = "billing",
                title = "Plan & Billing",
                detail = billingSectionDetail(server.plan, billing, usageRows.size),
                state = combineSectionState(billing, usage, usageRows.isEmpty())
            ),
            SettingsServerSectionSnapshot(
                key = "pending_invites",
                title = "Pending Invites",
                detail = "Invite creation is confirmed; no pending-invite listing endpoint in upstream source.",
                state = if (canManageServer) SettingsSectionAvailability.Empty else SettingsSectionAvailability.NoPermission
            ),
            SettingsServerSectionSnapshot(
                key = "join_links",
                title = "Join Links",
                detail = "Upstream source accepts join-link tokens, but no Settings join-link management endpoint is confirmed.",
                state = SettingsSectionAvailability.Empty
            ),
            SettingsServerSectionSnapshot(
                key = "onboarding_agent",
                title = "Onboarding Agent",
                detail = onboardingSectionDetail(onboarding, onboardingSnapshot),
                state = onboarding.sectionState(onboardingSnapshot == null)
            ),
            SettingsServerSectionSnapshot(
                key = "danger",
                title = "Danger Zone",
                detail = if (server.role == "owner") {
                    "Delete Server endpoint is confirmed, but Android keeps the action disabled until confirmation UI is added."
                } else {
                    "Only server owners can delete this server."
                },
                state = SettingsSectionAvailability.NoPermission
            )
        )
        logger(
            "server_settings_fetch_content serverId=$serverId admins=${admins.size} usageRows=${usageRows.size} onboarding=${onboardingSnapshot != null}"
        )
        return DataResult.Content(
            SettingsServerSnapshot(
                profile = SettingsServerProfileSnapshot(
                    name = server.name,
                    slug = server.slug ?: server.name.toSettingsSlug(),
                    canSave = canManageServer
                ),
                sections = sections,
                admins = admins,
                usageRows = usageRows,
                pendingInvites = emptyList(),
                joinLinks = emptyList(),
                onboarding = onboardingSnapshot
            )
        )
    }

    override suspend fun updateServerProfile(request: SettingsServerProfileUpdateRequest): DataResult<SettingsServerSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_server")
            )
        }
        val name = request.name.trim()
        if (name.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val update = settingsUpdateServerProfile(server.id, name)
        return when (update) {
            is DataResult.Content -> getServerSettings()
            is DataResult.Empty -> DataResult.Empty(update.reason)
            is DataResult.Error -> DataResult.Error(update.error)
            is DataResult.NoPermission -> DataResult.NoPermission(update.blocker)
        }
    }

    override suspend fun createServerInvite(request: SettingsServerInviteCreateRequest): DataResult<SettingsServerActionResult> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_invites")
            )
        }
        val email = request.email.trim()
        if (email.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsCreateServerInvite(server.id, email)
    }

    override suspend fun deleteServer(request: SettingsServerDeleteRequest): DataResult<SettingsServerActionResult> {
        val server = selectedServer() ?: return serverRequired()
        if (server.role != "owner") {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_owner_required", requiredCapability = "delete_server")
            )
        }
        if (request.confirmationName.trim() != server.name) {
            return DataResult.Error(ScreenError(code = "server_delete_confirmation_mismatch", retryable = false))
        }
        return settingsDeleteServer(server.id)
    }

    override suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot> {
        sessionStore.readSession() ?: return authRequired()
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

    private suspend fun settingsComputers(serverId: String): DataResult<SettingsComputersSnapshot> =
        computersApiClient.machines(ServerId(serverId)).mapComputerResult { summary ->
            val computers = summary.machines.map { it.toSettingsComputerSnapshot() }
            if (computers.isEmpty()) {
                warningLogger("empty_items source=settings_machines stage=list_extract root=machines")
            }
            SettingsComputersSnapshot(
                computers = computers,
                canAddComputer = false,
                addComputerUnavailableReason = null,
                latestDaemonVersion = summary.latestDaemonVersion
            )
        }

    private suspend fun settingsMachineWorkspaces(
        serverId: String,
        machineId: String
    ): DataResult<List<SettingsComputerWorkspaceSnapshot>> =
        computersApiClient.machineWorkspaces(ServerId(serverId), machineId).mapComputerResult { page ->
            val workspaces = page.workspaces.map { it.toSettingsWorkspaceSnapshot() }
            if (workspaces.isEmpty()) {
                warningLogger("empty_items source=settings_machine_workspaces stage=list_extract root=array")
            }
            workspaces
        }

    private suspend fun settingsComputerAgents(machineId: String): DataResult<List<SettingsComputerAgentSnapshot>> =
        executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { networkClient.settingsAgents(DataRequestPriority.Visible) },
            source = "settings_machine_agents",
            warningLogger = warningLogger,
            mapper = { bodyText ->
                val agents = parseSharedSettingsComputerAgents(bodyText = bodyText, machineId = machineId)
                    ?: throw IllegalStateException("Settings agents response missing agent list")
                if (agents.isEmpty()) {
                    warningLogger("empty_items source=settings_machine_agents stage=list_extract contract=agents_array")
                }
                agents
            }
        )

    private suspend fun enrichComputerAgents(
        serverId: String,
        agents: List<SettingsComputerAgentSnapshot>
    ): List<SettingsComputerAgentSnapshot> {
        if (agents.isEmpty()) return agents
        val members = executeSettings(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.serverMembers(DataRequestPriority.Visible, serverId) },
            source = "settings_machine_agent_members",
            warningLogger = warningLogger,
            mapper = { bodyText ->
                parseSharedSettingsMemberProfiles(bodyText)
                    ?: throw IllegalStateException("Settings member response missing member list")
            }
        )
        return when (members) {
            is DataResult.Content -> agents.enrichWithMembers(members.data)
            is DataResult.Empty -> agents
            is DataResult.Error -> {
                warningLogger(
                    "machine_detail_agent_member_enrich_failed serverId=$serverId code=${members.error.code} retryable=${members.error.retryable}"
                )
                agents
            }
            is DataResult.NoPermission -> {
                warningLogger(
                    "machine_detail_agent_member_enrich_failed serverId=$serverId code=${members.blocker.code}"
                )
                agents
            }
        }
    }

    private suspend fun fetchServerMembers(serverId: String): DataResult<List<SettingsServerAdminSnapshot>> =
        executeTypedSettings(
            source = "settings_server_members",
            contractCode = "settings_server_members_contract_mismatch",
            block = { networkClient.serverMembers(DataRequestPriority.Visible, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsServerAdmins(bodyText)
                    ?: settingsContractMismatch("settings_server_members_contract_mismatch")
            }
        )

    private suspend fun fetchServerUsage(serverId: String): DataResult<List<SettingsPlanUsageSnapshot>> =
        executeTypedSettings(
            source = "settings_server_usage",
            contractCode = "settings_server_usage_contract_mismatch",
            block = { networkClient.settingsServerUsage(DataRequestPriority.Visible, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsServerUsage(bodyText)
                    ?: settingsContractMismatch("settings_server_usage_contract_mismatch")
            }
        )

    private suspend fun fetchServerBilling(): DataResult<List<SettingsPlanUsageSnapshot>> =
        executeTypedSettings(
            source = "settings_billing_subscription",
            contractCode = "settings_billing_subscription_contract_mismatch",
            block = { networkClient.settingsBillingSubscription(DataRequestPriority.Visible) },
            mapper = { bodyText ->
                parseSharedSettingsBillingSubscription(bodyText)
                    ?: settingsContractMismatch("settings_billing_subscription_contract_mismatch")
            }
        )

    private suspend fun fetchServerOnboarding(serverId: String): DataResult<SettingsServerOnboardingSnapshot> =
        executeTypedSettings(
            source = "settings_server_onboarding_settings",
            contractCode = "settings_server_onboarding_contract_mismatch",
            block = { networkClient.settingsServerOnboarding(DataRequestPriority.Visible, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsServerOnboarding(bodyText)
                    ?: settingsContractMismatch("settings_server_onboarding_contract_mismatch")
            }
        )

    private suspend fun settingsUpdateServerProfile(serverId: String, name: String): DataResult<Unit> =
        executeTypedSettings(
            source = "settings_server_profile_update",
            contractCode = "settings_server_profile_contract_mismatch",
            block = {
                networkClient.updateSettingsServerProfile(
                    DataRequestPriority.Foreground,
                    serverId,
                    buildSharedSettingsServerProfileUpdateBody(name)
                )
            },
            mapper = { bodyText ->
                if (!parseSharedSettingsServerProfileUpdated(bodyText)) {
                    settingsContractMismatch("settings_server_profile_contract_mismatch")
                }
                Unit
            }
        )

    private suspend fun settingsCreateServerInvite(serverId: String, email: String): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_invite_create",
            contractCode = "settings_server_invite_contract_mismatch",
            block = {
                networkClient.createSettingsServerInvite(
                    DataRequestPriority.Foreground,
                    serverId,
                    buildSharedSettingsServerInviteCreateBody(email)
                )
            },
            mapper = { bodyText ->
                parseSharedSettingsServerInviteCreated(bodyText)
                    ?: settingsContractMismatch("settings_server_invite_contract_mismatch")
            }
        )

    private suspend fun settingsDeleteServer(serverId: String): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_delete",
            contractCode = "settings_server_delete_contract_mismatch",
            block = { networkClient.deleteSettingsServer(DataRequestPriority.Foreground, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsServerDeleted(bodyText)
                    ?: settingsContractMismatch("settings_server_delete_contract_mismatch")
            }
        )

    private suspend fun selectedServerId(): String? =
        sessionStore.readSession()?.currentServerId

    private suspend fun selectedServer(): StoredAuthServer? {
        val session = sessionStore.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.servers.firstOrNull { it.id == selectedId }
    }
}

private data class SettingsContractMismatch(val code: String) : IllegalArgumentException(code)

private fun settingsContractMismatch(code: String): Nothing = throw SettingsContractMismatch(code)

private suspend fun <T> executeTypedSettings(
    source: String,
    contractCode: String,
    block: suspend () -> HomeNetworkResponse,
    mapper: (String) -> T
): DataResult<T> {
    var statusCode: Int? = null
    return try {
        val response = block()
        statusCode = response.code
        if (!response.isSuccessful) {
            if (response.code == 404) {
                return DataResult.Error(ScreenError(code = contractCode, retryable = false))
            }
            return response.toSettingsDataError()
        }
        val body = response.bodyText.orEmpty()
        if (body.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        DataResult.Content(mapper(body))
    } catch (exception: SettingsContractMismatch) {
        val code =
            if (exception.code.endsWith("_contract_mismatch")) exception.code else contractCode
        DataResult.Error(ScreenError(code = code, retryable = false))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        warningLogSettingsParseFailure(source, statusCode, exception)
        DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
    }
}

private suspend fun <T> executeSettings(
    emptyReason: EmptyReason,
    block: suspend () -> HomeNetworkResponse,
    emptyWhen: (T) -> Boolean = { false },
    source: String? = null,
    warningLogger: (String) -> Unit = {},
    mapper: (String) -> T
): DataResult<T> {
    var statusCode: Int? = null
    return try {
        val response = block()
        statusCode = response.code
        if (!response.isSuccessful) return response.toSettingsDataError()
        val body = response.bodyText.orEmpty()
        if (body.isBlank()) return DataResult.Empty(emptyReason)
        val result = mapper(body)
        if (emptyWhen(result)) DataResult.Empty(emptyReason) else DataResult.Content(result)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        if (source != null) {
            warningLogger(
                "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} exception=${exception::class.simpleName}"
            )
        }
        DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
    }
}

private fun <T> HomeNetworkResponse.toSettingsDataError(): DataResult<T> =
    when (code) {
        401 -> authRequired()
        403 -> DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access"))
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        else -> DataResult.Error(ScreenError(code = "http_$code", retryable = code != 404))
    }

private fun <T> authRequired(): DataResult<T> =
    DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))

private fun <T> serverRequired(): DataResult<T> =
    DataResult.NoPermission(PermissionBlocker(code = "server_required", requiredCapability = "selected_server"))

private fun warningLogSettingsParseFailure(source: String, statusCode: Int?, exception: Exception) {
    @Suppress("UNUSED_VARIABLE")
    val message =
        "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} exception=${exception::class.simpleName}"
}

private fun String?.toPlanDisplayName(): String =
    when (this?.lowercase()) {
        null, "" -> "Unknown"
        "hobby", "starter" -> "Hobby"
        "team" -> "Team"
        "business", "enterprise" -> "Business"
        else -> replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private val StoredAuthServer.canManageServer: Boolean
    get() = role == "owner" || role == "admin"

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

private fun <T> DataResult<T>.emptyWhen(predicate: (T) -> Boolean): DataResult<T> =
    when {
        this is DataResult.Content && predicate(data) -> DataResult.Empty(EmptyReason.NoSettingsAvailable)
        else -> this
    }

private fun List<SettingsComputerAgentSnapshot>.enrichWithMembers(
    members: List<ai.slock.compose.model.MemberProfile>
): List<SettingsComputerAgentSnapshot> {
    if (members.isEmpty()) return this
    val membersById = members.associateBy { it.id.value }
    val membersByName = members.associateBy { it.displayName.lowercase() }
    return map { agent ->
        agent.copy(
            memberProfile = membersById[agent.id] ?: membersByName[agent.name.lowercase()]
        )
    }
}

private fun ComputerSummary.toSettingsComputerSnapshot(): SettingsComputerSnapshot =
    SettingsComputerSnapshot(
        id = id,
        name = name,
        daemonVersion = daemonVersion.orEmpty(),
        online = status == "online",
        hostname = hostname,
        platform = os,
        lastSeenLabel = null
    )

private fun ComputerWorkspaceSummary.toSettingsWorkspaceSnapshot(): SettingsComputerWorkspaceSnapshot =
    SettingsComputerWorkspaceSnapshot(
        id = directoryName,
        name = directoryName,
        path = directoryName,
        statusLabel = status,
        lastActiveLabel = lastModified
    )

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

private fun <T, R> DataResult<T>.mapComputerResult(transform: (T) -> R): DataResult<R> =
    when (this) {
        is DataResult.Content -> DataResult.Content(transform(data))
        is DataResult.Empty -> DataResult.Empty(reason)
        is DataResult.Error -> DataResult.Error(error)
        is DataResult.NoPermission -> DataResult.NoPermission(blocker)
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

private fun adminsSectionDetail(result: DataResult<List<SettingsServerAdminSnapshot>>, count: Int): String =
    when (result) {
        is DataResult.Content -> if (count == 0) "No admins yet." else "$count admin${if (count == 1) "" else "s"}"
        is DataResult.Empty -> "No admins yet."
        is DataResult.NoPermission -> "You do not have permission to view server members."
        is DataResult.Error -> "Admin list failed to load: ${result.error.code}"
    }

private fun billingSectionDetail(
    sessionPlan: String?,
    billing: DataResult<List<SettingsPlanUsageSnapshot>>,
    usageRowCount: Int
): String {
    val plan = sessionPlan.toPlanDisplayName()
    val usage = if (usageRowCount > 0) " Usage rows: $usageRowCount." else ""
    return when (billing) {
        is DataResult.NoPermission -> "Billing requires owner/admin permission."
        is DataResult.Error -> "Current plan: $plan. Billing subscription failed: ${billing.error.code}.$usage"
        else -> "Current plan: $plan.$usage"
    }
}

private fun onboardingSectionDetail(
    result: DataResult<SettingsServerOnboardingSnapshot>,
    onboarding: SettingsServerOnboardingSnapshot?
): String =
    when (result) {
        is DataResult.Content -> onboarding?.detail ?: "Onboarding preference loaded."
        is DataResult.Empty -> "No onboarding preference was returned."
        is DataResult.NoPermission -> "Only server owners can update onboarding reminder settings."
        is DataResult.Error -> "Onboarding settings failed to load: ${result.error.code}"
    }

private fun <T> DataResult<T>.sectionState(empty: Boolean): SettingsSectionAvailability =
    when (this) {
        is DataResult.Content -> if (empty) SettingsSectionAvailability.Empty else SettingsSectionAvailability.Available
        is DataResult.Empty -> SettingsSectionAvailability.Empty
        is DataResult.NoPermission -> SettingsSectionAvailability.NoPermission
        is DataResult.Error -> SettingsSectionAvailability.Unavailable
    }

private fun combineSectionState(
    billing: DataResult<List<SettingsPlanUsageSnapshot>>,
    usage: DataResult<List<SettingsPlanUsageSnapshot>>,
    empty: Boolean
): SettingsSectionAvailability =
    when {
        billing is DataResult.NoPermission || usage is DataResult.NoPermission ->
            SettingsSectionAvailability.NoPermission
        billing is DataResult.Content || usage is DataResult.Content ->
            if (empty) SettingsSectionAvailability.Empty else SettingsSectionAvailability.Available
        billing is DataResult.Empty || usage is DataResult.Empty -> SettingsSectionAvailability.Empty
        else -> SettingsSectionAvailability.Unavailable
    }

private fun mergeServerUsageRows(
    usage: List<SettingsPlanUsageSnapshot>,
    billing: List<SettingsPlanUsageSnapshot>
): List<SettingsPlanUsageSnapshot> =
    buildList {
        addAll(billing)
        addAll(usage)
    }

private fun String.toSettingsSlug(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "server" }

@Serializable
data class AccountProfileUpdateRequestBody(
    val displayName: String,
    val handle: String,
    val email: String
)

fun buildSharedAccountProfileUpdateBody(request: AccountProfileUpdateRequest): String =
    settingsRepositoryJson.encodeToString(
        AccountProfileUpdateRequestBody(
            displayName = request.displayName,
            handle = request.handle,
            email = request.email
        )
    )

@Serializable
data class SettingsServerProfileUpdateRequestBody(val name: String)

fun buildSharedSettingsServerProfileUpdateBody(name: String): String =
    settingsRepositoryJson.encodeToString(SettingsServerProfileUpdateRequestBody(name))

@Serializable
data class SettingsServerInviteCreateRequestBody(val email: String)

fun buildSharedSettingsServerInviteCreateBody(email: String): String =
    settingsRepositoryJson.encodeToString(SettingsServerInviteCreateRequestBody(email))
