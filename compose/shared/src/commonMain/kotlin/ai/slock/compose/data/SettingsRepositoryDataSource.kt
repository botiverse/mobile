package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val settingsRepositoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
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

private suspend fun SlockNetworkClient.updateSettingsServerOnboarding(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/onboarding-settings", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun SlockNetworkClient.settingsServerTranslation(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/translation-settings", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.updateSettingsServerTranslation(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/translation-settings", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun SlockNetworkClient.settingsServerSidebarOrder(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/sidebar-order", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.updateSettingsServerSidebarOrder(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/sidebar-order", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun SlockNetworkClient.updateSettingsServerMemberRole(
    priority: DataRequestPriority,
    serverId: String,
    memberId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/members/$memberId", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun SlockNetworkClient.deleteSettingsServerMember(
    priority: DataRequestPriority,
    serverId: String,
    memberId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/members/$memberId", method = "DELETE", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.leaveSettingsServer(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/leave", method = "POST", serverIdOverride = ServerId(serverId))

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

private suspend fun SlockNetworkClient.settingsServerInvites(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/invites", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.deleteSettingsServerInvite(
    priority: DataRequestPriority,
    serverId: String,
    inviteId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/invites/$inviteId", method = "DELETE", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.createSettingsServerJoinLink(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/join-links", method = "POST", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun SlockNetworkClient.settingsServerJoinLinks(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/join-links", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.deleteSettingsServerJoinLink(
    priority: DataRequestPriority,
    serverId: String,
    linkId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/join-links/$linkId", method = "DELETE", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.deleteSettingsServer(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId", method = "DELETE", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.settingsNotificationSettings(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/notification-settings", serverIdOverride = ServerId(serverId))

private suspend fun SlockNetworkClient.updateSettingsNotificationSettings(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/notification-settings", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

class RealSettingsRepository(
    private val sessionStore: SlockAuthSessionStore,
    private val networkClient: SlockNetworkClient,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : SettingsRepository, SettingsServerActionRepository, SettingsServerManagementRepository {
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
                        detail = "Web updates status through realtime machine status events; no Settings detail REST contract is confirmed yet."
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
        if (server.role != "owner") {
            return DataResult.NoPermission(
                PermissionBlocker(code = "billing_owner_required", requiredCapability = "manage_billing")
            )
        }
        val currentPlan = server.plan.toBillingPlanConfig()
        val usage =
            when (val result = fetchServerUsage(server.id)) {
                is DataResult.Content -> result.data
                is DataResult.Empty -> return DataResult.Empty(result.reason)
                is DataResult.Error -> return DataResult.Error(result.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(result.blocker)
            }
        val billing = fetchServerBilling()
        val subscription =
            when (billing) {
                is DataResult.Content -> billing.data
                is DataResult.Empty -> null
                is DataResult.Error -> return DataResult.Error(billing.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(billing.blocker)
            }
        val trialActive = currentPlan.id == "free" && isSettingsTrialActive()
        return DataResult.Content(
            SettingsBillingSnapshot(
                currentPlan = SettingsBillingCurrentPlanSnapshot(
                    planName = currentPlan.displayName,
                    usageRows = currentPlanUsageRows(currentPlan, usage, trialActive),
                    trialActive = trialActive
                ),
                plans = settingsDisplayPlanConfigs.map { it.toBillingPlanSnapshot(currentPlan, trialActive, subscription) }
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
        val invites =
            if (canManageServer) {
                fetchServerInvites(serverId)
            } else {
                DataResult.NoPermission(PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_invites"))
            }
        val joinLinks =
            if (canManageServer) {
                fetchServerJoinLinks(serverId)
            } else {
                DataResult.NoPermission(PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_invites"))
            }
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
        val translation =
            if (canManageServer) {
                fetchServerTranslation(serverId)
            } else {
                DataResult.NoPermission(PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_server"))
            }
        val sidebarOrder =
            if (canManageServer) {
                fetchServerSidebarOrder(serverId)
            } else {
                DataResult.NoPermission(PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_server"))
            }

        val admins = (members as? DataResult.Content)?.data.orEmpty()
        val usageRows = mergeServerUsageRows(
            usage = (usage as? DataResult.Content)?.data,
            billing = (billing as? DataResult.Content)?.data
        )
        val pendingInvites = (invites as? DataResult.Content)?.data.orEmpty()
        val joinLinkRows = (joinLinks as? DataResult.Content)?.data.orEmpty()
        val onboardingSnapshot = (onboarding as? DataResult.Content)?.data
        val translationSnapshot = (translation as? DataResult.Content)?.data
        val sidebarOrderSnapshot = (sidebarOrder as? DataResult.Content)?.data
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
                detail = invitesSectionDetail(invites, pendingInvites.size),
                state = invites.sectionState(pendingInvites.isEmpty())
            ),
            SettingsServerSectionSnapshot(
                key = "join_links",
                title = "Join Links",
                detail = joinLinksSectionDetail(joinLinks, joinLinkRows.size),
                state = joinLinks.sectionState(joinLinkRows.isEmpty())
            ),
            SettingsServerSectionSnapshot(
                key = "onboarding_agent",
                title = "Onboarding Agent",
                detail = onboardingSectionDetail(onboarding, onboardingSnapshot),
                state = onboarding.sectionState(onboardingSnapshot == null)
            ),
            SettingsServerSectionSnapshot(
                key = "translation",
                title = "Translation",
                detail = translationSectionDetail(translation, translationSnapshot),
                state = translation.sectionState(translationSnapshot == null)
            ),
            SettingsServerSectionSnapshot(
                key = "sidebar_order",
                title = "Sidebar Order",
                detail = sidebarOrderSectionDetail(sidebarOrder, sidebarOrderSnapshot),
                state = sidebarOrder.sectionState(sidebarOrderSnapshot == null)
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
                pendingInvites = pendingInvites,
                joinLinks = joinLinkRows,
                onboarding = onboardingSnapshot,
                translation = translationSnapshot,
                sidebarOrder = sidebarOrderSnapshot
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

    override suspend fun revokeServerInvite(inviteId: String): DataResult<SettingsServerActionResult> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_invites")
            )
        }
        val normalizedInviteId = inviteId.trim()
        if (normalizedInviteId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsDeleteServerInvite(server.id, normalizedInviteId)
    }

    override suspend fun createServerJoinLink(
        request: SettingsServerJoinLinkCreateRequest
    ): DataResult<SettingsServerJoinLinkSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_invites")
            )
        }
        return settingsCreateServerJoinLink(server.id, request)
    }

    override suspend fun revokeServerJoinLink(linkId: String): DataResult<SettingsServerActionResult> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_invites")
            )
        }
        val normalizedLinkId = linkId.trim()
        if (normalizedLinkId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsDeleteServerJoinLink(server.id, normalizedLinkId)
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

    override suspend fun updateServerOnboarding(
        request: SettingsServerOnboardingUpdateRequest
    ): DataResult<SettingsServerOnboardingSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_server")
            )
        }
        return settingsUpdateServerOnboarding(server.id, request)
    }

    override suspend fun getServerTranslationSettings(): DataResult<SettingsServerTranslationSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        return fetchServerTranslation(server.id)
    }

    override suspend fun updateServerTranslationSettings(
        request: SettingsServerTranslationUpdateRequest
    ): DataResult<SettingsServerTranslationSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_server")
            )
        }
        return settingsUpdateServerTranslation(server.id, request)
    }

    override suspend fun getServerSidebarOrder(): DataResult<SettingsServerSidebarOrderSnapshot> {
        val serverId = selectedServerId() ?: return serverRequired()
        return fetchServerSidebarOrder(serverId)
    }

    override suspend fun updateServerSidebarOrder(
        request: SettingsServerSidebarOrderUpdateRequest
    ): DataResult<SettingsServerSidebarOrderSnapshot> {
        val serverId = selectedServerId() ?: return serverRequired()
        if (!request.hasAtLeastOneField()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsUpdateServerSidebarOrder(serverId, request)
    }

    override suspend fun updateServerMemberRole(
        request: SettingsServerMemberRoleUpdateRequest
    ): DataResult<SettingsServerActionResult> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "change_member_roles")
            )
        }
        val memberId = request.memberId.trim()
        val role = request.role.trim()
        if (memberId.isBlank() || role.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsUpdateServerMemberRole(server.id, memberId, role)
    }

    override suspend fun removeServerMember(memberId: String): DataResult<SettingsServerActionResult> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_members")
            )
        }
        val normalizedMemberId = memberId.trim()
        if (normalizedMemberId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsRemoveServerMember(server.id, normalizedMemberId)
    }

    override suspend fun leaveServer(): DataResult<SettingsServerActionResult> {
        val serverId = selectedServerId() ?: return serverRequired()
        return settingsLeaveServer(serverId)
    }

    override suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot> {
        sessionStore.readSession() ?: return authRequired()
        val serverMute =
            selectedServerId()?.let { serverId ->
                fetchServerMute(serverId)
            } ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return DataResult.Content(
            SettingsMobileSnapshot(
                pushNotifications = SettingsMobileToggleSnapshot(
                    enabled = false,
                    available = true,
                    statusLabel = "Disabled",
                    detail = "Notifications can arrive while Raft is in the background. Use a test notification to verify this device."
                ),
                muteServer = SettingsMobileToggleSnapshot(
                    enabled = (serverMute as? DataResult.Content)?.data ?: false,
                    available = serverMute !is DataResult.NoPermission && serverMute !is DataResult.Error && serverMute !is DataResult.Empty,
                    statusLabel =
                        if ((serverMute as? DataResult.Content)?.data == true) {
                            "Muted"
                        } else {
                            "Not muted"
                        },
                    detail =
                        when (serverMute) {
                            is DataResult.Content -> "Stops push notifications from the currently selected workspace."
                            is DataResult.NoPermission -> "You do not have permission to read workspace notification settings."
                            is DataResult.Error -> "Workspace notification settings failed to load: ${serverMute.error.code}"
                            is DataResult.Empty -> "Workspace notification settings are unavailable."
                        }
                ),
                sendTestEnabled = true
            )
        )
    }

    override suspend fun getLanguageRegionSettings(): DataResult<SettingsLanguageRegionSnapshot> {
        sessionStore.readSession() ?: return authRequired()
        val server = selectedServer() ?: return serverRequired()
        val account = fetchLanguageRegionAccountPreferences(DataRequestPriority.Visible)
        val translation = fetchServerTranslation(server.id)
        return when (account) {
            is DataResult.Content -> DataResult.Content(account.data.toLanguageRegionSnapshot(translation, server.canManageServer))
            is DataResult.Empty -> DataResult.Empty(account.reason)
            is DataResult.Error -> DataResult.Error(account.error)
            is DataResult.NoPermission -> DataResult.NoPermission(account.blocker)
        }
    }

    override suspend fun updateLanguageRegionPreference(
        request: SettingsLanguageRegionPreferenceUpdateRequest
    ): DataResult<SettingsLanguageRegionSnapshot> {
        sessionStore.readSession() ?: return authRequired()
        val server = selectedServer() ?: return serverRequired()
        if (!request.hasAtLeastOneField()) {
            return DataResult.Error(ScreenError("settings_language_region_empty_update", retryable = false))
        }
        return when (val result =
            executeSettings(
                emptyReason = EmptyReason.NoSettingsAvailable,
                source = "settings_language_region_account_update",
                warningLogger = warningLogger,
                block = {
                    networkClient.updateSettingsAccount(
                        DataRequestPriority.Foreground,
                        buildSharedLanguageRegionPreferenceUpdateBody(request)
                    )
                },
                mapper = { bodyText ->
                    parseSharedLanguageRegionAccountPreferences(bodyText)
                        ?: throw IllegalStateException("Language region account response missing preferences")
                }
            )) {
            is DataResult.Content ->
                DataResult.Content(result.data.toLanguageRegionSnapshot(fetchServerTranslation(server.id), server.canManageServer))
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
    }

    override suspend fun setServerMute(enabled: Boolean): DataResult<SettingsMobileSnapshot> {
        val serverId = selectedServerId() ?: return serverRequired()
        return when (val result = settingsUpdateServerMute(serverId, enabled)) {
            is DataResult.Content -> getMobileSettings()
            is DataResult.Empty -> DataResult.Empty(EmptyReason.NoSettingsAvailable)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
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

    private suspend fun fetchServerUsage(serverId: String): DataResult<SettingsServerUsageSnapshot> =
        executeTypedSettings(
            source = "settings_server_usage",
            contractCode = "settings_server_usage_contract_mismatch",
            block = { networkClient.settingsServerUsage(DataRequestPriority.Visible, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsServerUsage(bodyText)
                    ?: settingsContractMismatch("settings_server_usage_contract_mismatch")
            }
        )

    private suspend fun fetchServerBilling(): DataResult<SettingsBillingSubscriptionSnapshot> =
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

    private suspend fun settingsUpdateServerOnboarding(
        serverId: String,
        request: SettingsServerOnboardingUpdateRequest
    ): DataResult<SettingsServerOnboardingSnapshot> =
        executeTypedSettings(
            source = "settings_server_onboarding_settings_update",
            contractCode = "settings_server_onboarding_contract_mismatch",
            block = {
                networkClient.updateSettingsServerOnboarding(
                    DataRequestPriority.Foreground,
                    serverId,
                    buildSharedSettingsServerOnboardingUpdateBody(request)
                )
            },
            mapper = { bodyText ->
                parseSharedSettingsServerOnboarding(bodyText)
                    ?: settingsContractMismatch("settings_server_onboarding_contract_mismatch")
            }
        )

    private suspend fun fetchServerTranslation(serverId: String): DataResult<SettingsServerTranslationSnapshot> =
        executeTypedSettings(
            source = "settings_server_translation_settings",
            contractCode = "settings_server_translation_contract_mismatch",
            block = { networkClient.settingsServerTranslation(DataRequestPriority.Visible, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsTranslation(bodyText)
                    ?: settingsContractMismatch("settings_server_translation_contract_mismatch")
            }
        )

    private suspend fun settingsUpdateServerTranslation(
        serverId: String,
        request: SettingsServerTranslationUpdateRequest
    ): DataResult<SettingsServerTranslationSnapshot> =
        executeTypedSettings(
            source = "settings_server_translation_settings_update",
            contractCode = "settings_server_translation_contract_mismatch",
            block = {
                networkClient.updateSettingsServerTranslation(
                    DataRequestPriority.Foreground,
                    serverId,
                    buildSharedSettingsServerTranslationUpdateBody(request)
                )
            },
            mapper = { bodyText ->
                parseSharedSettingsTranslation(bodyText)
                    ?: settingsContractMismatch("settings_server_translation_contract_mismatch")
            }
        )

    private suspend fun fetchServerSidebarOrder(serverId: String): DataResult<SettingsServerSidebarOrderSnapshot> =
        executeTypedSettings(
            source = "settings_server_sidebar_order",
            contractCode = "settings_server_sidebar_order_contract_mismatch",
            block = { networkClient.settingsServerSidebarOrder(DataRequestPriority.Visible, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsSidebarOrder(bodyText)
                    ?: settingsContractMismatch("settings_server_sidebar_order_contract_mismatch")
            }
        )

    private suspend fun settingsUpdateServerSidebarOrder(
        serverId: String,
        request: SettingsServerSidebarOrderUpdateRequest
    ): DataResult<SettingsServerSidebarOrderSnapshot> =
        executeTypedSettings(
            source = "settings_server_sidebar_order_update",
            contractCode = "settings_server_sidebar_order_contract_mismatch",
            block = {
                networkClient.updateSettingsServerSidebarOrder(
                    DataRequestPriority.Foreground,
                    serverId,
                    buildSharedSettingsServerSidebarOrderUpdateBody(request)
                )
            },
            mapper = { bodyText ->
                parseSharedSettingsSidebarOrder(bodyText)
                    ?: settingsContractMismatch("settings_server_sidebar_order_contract_mismatch")
            }
        )

    private suspend fun fetchServerInvites(serverId: String): DataResult<List<SettingsServerInviteSnapshot>> =
        executeTypedSettings(
            source = "settings_server_invites",
            contractCode = "settings_server_invites_contract_mismatch",
            block = { networkClient.settingsServerInvites(DataRequestPriority.Visible, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsPendingInvites(bodyText)
                    ?: settingsContractMismatch("settings_server_invites_contract_mismatch")
            }
        )

    private suspend fun fetchServerJoinLinks(serverId: String): DataResult<List<SettingsServerJoinLinkSnapshot>> =
        executeTypedSettings(
            source = "settings_server_join_links",
            contractCode = "settings_server_join_links_contract_mismatch",
            block = { networkClient.settingsServerJoinLinks(DataRequestPriority.Visible, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsJoinLinks(bodyText)
                    ?: settingsContractMismatch("settings_server_join_links_contract_mismatch")
            }
        )

    private suspend fun fetchServerMute(serverId: String): DataResult<Boolean> =
        executeTypedSettings(
            source = "settings_server_notification_settings",
            contractCode = "settings_server_notification_settings_contract_mismatch",
            block = { networkClient.settingsNotificationSettings(DataRequestPriority.Visible, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsServerNotificationSettings(bodyText)
                    ?: settingsContractMismatch("settings_server_notification_settings_contract_mismatch")
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

    private suspend fun settingsDeleteServerInvite(serverId: String, inviteId: String): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_invite_delete",
            contractCode = "settings_server_invite_delete_contract_mismatch",
            block = { networkClient.deleteSettingsServerInvite(DataRequestPriority.Foreground, serverId, inviteId) },
            mapper = { bodyText ->
                parseSharedSettingsServerAction(bodyText)
                    ?: settingsContractMismatch("settings_server_invite_delete_contract_mismatch")
            }
        )

    private suspend fun settingsCreateServerJoinLink(
        serverId: String,
        request: SettingsServerJoinLinkCreateRequest
    ): DataResult<SettingsServerJoinLinkSnapshot> =
        executeTypedSettings(
            source = "settings_server_join_link_create",
            contractCode = "settings_server_join_link_create_contract_mismatch",
            block = {
                networkClient.createSettingsServerJoinLink(
                    DataRequestPriority.Foreground,
                    serverId,
                    buildSharedSettingsServerJoinLinkCreateBody(request)
                )
            },
            mapper = { bodyText ->
                parseSharedSettingsCreatedJoinLink(bodyText)
                    ?: settingsContractMismatch("settings_server_join_link_create_contract_mismatch")
            }
        )

    private suspend fun settingsDeleteServerJoinLink(serverId: String, linkId: String): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_join_link_delete",
            contractCode = "settings_server_join_link_delete_contract_mismatch",
            block = { networkClient.deleteSettingsServerJoinLink(DataRequestPriority.Foreground, serverId, linkId) },
            mapper = { bodyText ->
                parseSharedSettingsServerAction(bodyText)
                    ?: settingsContractMismatch("settings_server_join_link_delete_contract_mismatch")
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

    private suspend fun settingsUpdateServerMemberRole(
        serverId: String,
        memberId: String,
        role: String
    ): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_member_role_update",
            contractCode = "settings_server_member_role_contract_mismatch",
            block = {
                networkClient.updateSettingsServerMemberRole(
                    DataRequestPriority.Foreground,
                    serverId,
                    memberId,
                    buildSharedSettingsServerMemberRoleUpdateBody(role)
                )
            },
            mapper = { bodyText ->
                parseSharedSettingsServerAction(bodyText)
                    ?: settingsContractMismatch("settings_server_member_role_contract_mismatch")
            }
        )

    private suspend fun settingsRemoveServerMember(
        serverId: String,
        memberId: String
    ): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_member_remove",
            contractCode = "settings_server_member_remove_contract_mismatch",
            block = { networkClient.deleteSettingsServerMember(DataRequestPriority.Foreground, serverId, memberId) },
            mapper = { bodyText ->
                parseSharedSettingsServerAction(bodyText)
                    ?: settingsContractMismatch("settings_server_member_remove_contract_mismatch")
            }
        )

    private suspend fun settingsLeaveServer(serverId: String): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_leave",
            contractCode = "settings_server_leave_contract_mismatch",
            block = { networkClient.leaveSettingsServer(DataRequestPriority.Foreground, serverId) },
            mapper = { bodyText ->
                parseSharedSettingsServerAction(bodyText)
                    ?: settingsContractMismatch("settings_server_leave_contract_mismatch")
            }
        )

    private suspend fun selectedServerId(): String? =
        sessionStore.readSession()?.currentServerId

    private suspend fun selectedServer(): StoredAuthServer? {
        val session = sessionStore.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.servers.firstOrNull { it.id == selectedId }
    }

    private suspend fun fetchLanguageRegionAccountPreferences(
        priority: DataRequestPriority
    ): DataResult<SettingsLanguageRegionAccountPreferences> =
        executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            source = "settings_language_region_account",
            warningLogger = warningLogger,
            block = { networkClient.settingsAccount(priority) },
            mapper = { bodyText ->
                parseSharedLanguageRegionAccountPreferences(bodyText)
                    ?: throw IllegalStateException("Language region account response missing preferences")
            }
        )

    private suspend fun settingsUpdateServerMute(serverId: String, enabled: Boolean): DataResult<Boolean> =
        executeTypedSettings(
            source = "settings_server_notification_settings_update",
            contractCode = "settings_server_notification_settings_contract_mismatch",
            block = {
                networkClient.updateSettingsNotificationSettings(
                    DataRequestPriority.Foreground,
                    serverId,
                    buildSharedSettingsServerMuteBody(enabled)
                )
            },
            mapper = { bodyText ->
                parseSharedSettingsServerNotificationSettings(bodyText)
                    ?: settingsContractMismatch("settings_server_notification_settings_contract_mismatch")
            }
        )
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
        "founder" -> "Founder"
        "team" -> "Team"
        "business", "enterprise" -> "Business"
        else -> replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private fun String.toSettingsStatusLabel(): String =
    replace("_", " ")
        .replace("-", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private data class SettingsPlanLimits(
    val maxMachines: Int,
    val maxAgents: Int,
    val maxChannels: Int,
    val messageHistoryDays: Int
)

private data class SettingsBillingPlanConfig(
    val id: String,
    val displayName: String,
    val priceLabel: String,
    val limits: SettingsPlanLimits? = null,
    val comingSoon: Boolean = false
)

private val hobbyPlanLimits = SettingsPlanLimits(maxMachines = 2, maxAgents = 5, maxChannels = 5, messageHistoryDays = 30)
private val founderPlanLimits = SettingsPlanLimits(maxMachines = -1, maxAgents = -1, maxChannels = -1, messageHistoryDays = -1)
private val teamPlanLimits = SettingsPlanLimits(maxMachines = 8, maxAgents = 40, maxChannels = 20, messageHistoryDays = -1)
private val businessPlanLimits = SettingsPlanLimits(maxMachines = 40, maxAgents = 200, maxChannels = -1, messageHistoryDays = -1)

private val hobbyBillingPlanConfig = SettingsBillingPlanConfig(id = "free", displayName = "Hobby", priceLabel = "$0 /mo", limits = hobbyPlanLimits)
private val founderBillingPlanConfig = SettingsBillingPlanConfig(id = "founder", displayName = "Founder", priceLabel = "$0 /mo", limits = founderPlanLimits)
private val teamBillingPlanConfig = SettingsBillingPlanConfig(id = "pro", displayName = "Team", priceLabel = "$20 /mo", limits = teamPlanLimits, comingSoon = true)
private val businessBillingPlanConfig = SettingsBillingPlanConfig(id = "max", displayName = "Business", priceLabel = "$200 /mo", limits = businessPlanLimits, comingSoon = true)
private val settingsDisplayPlanConfigs = listOf(hobbyBillingPlanConfig, teamBillingPlanConfig, businessBillingPlanConfig)
private const val settingsTrialStartMillis = 1_776_470_400_000L // 2026-04-18T00:00:00Z
private const val settingsTrialDurationMillis = 37L * 24L * 60L * 60L * 1000L
private const val settingsTrialEndMillis = settingsTrialStartMillis + settingsTrialDurationMillis

private fun String?.toBillingPlanConfig(): SettingsBillingPlanConfig =
    when (this?.lowercase()) {
        "free", "hobby", "starter" -> hobbyBillingPlanConfig
        "founder" -> founderBillingPlanConfig
        "team", "pro" -> teamBillingPlanConfig.copy(comingSoon = false)
        "business", "enterprise", "max" -> businessBillingPlanConfig.copy(comingSoon = false)
        null, "" -> SettingsBillingPlanConfig(id = "unknown", displayName = "Unknown", priceLabel = "Unavailable")
        else -> SettingsBillingPlanConfig(id = lowercase(), displayName = toPlanDisplayName(), priceLabel = "Unavailable")
    }

private fun isSettingsTrialActive(nowMillis: Long = settingsTrialEndMillis): Boolean =
    nowMillis >= settingsTrialStartMillis && nowMillis < settingsTrialEndMillis

private fun currentPlanUsageRows(
    plan: SettingsBillingPlanConfig,
    usage: SettingsServerUsageSnapshot,
    trialActive: Boolean
): List<SettingsPlanUsageSnapshot> {
    val limits = plan.limits
    return listOf(
        SettingsPlanUsageSnapshot("Computers", formatCurrentPlanUsageValue(usage.machines, limits?.maxMachines, trialActive)),
        SettingsPlanUsageSnapshot("Agents", formatCurrentPlanUsageValue(usage.agents, limits?.maxAgents, trialActive)),
        SettingsPlanUsageSnapshot("Channels", formatCurrentPlanUsageValue(usage.channels, limits?.maxChannels, trialActive)),
        SettingsPlanUsageSnapshot("History", formatHistoryUsageValue(limits?.messageHistoryDays, trialActive))
    )
}

private fun formatCurrentPlanUsageValue(current: Int, limit: Int?, trialActive: Boolean): String =
    when {
        trialActive && limit != null && limit != -1 -> "$current (Unlimited limited time)"
        limit == null -> current.toString()
        limit == -1 -> "$current (Unlimited)"
        else -> "$current / $limit"
    }

private fun formatHistoryUsageValue(limit: Int?, trialActive: Boolean): String =
    when {
        trialActive && limit != null && limit != -1 -> "Unlimited (limited time)"
        limit == null -> "Unavailable"
        limit == -1 -> "Unlimited"
        else -> "$limit days"
    }

private fun SettingsBillingPlanConfig.toBillingPlanSnapshot(
    currentPlan: SettingsBillingPlanConfig,
    trialActive: Boolean,
    subscription: SettingsBillingSubscriptionSnapshot?
): SettingsBillingPlanSnapshot =
    SettingsBillingPlanSnapshot(
        name = displayName,
        price = priceLabel,
        features = planFeatureRows(this, trialActive, subscription),
        current = id == currentPlan.id,
        comingSoon = comingSoon
    )

private fun planFeatureRows(
    plan: SettingsBillingPlanConfig,
    trialActive: Boolean,
    subscription: SettingsBillingSubscriptionSnapshot?
): List<String> {
    val limits = plan.limits ?: return emptyList()
    val rows =
        mutableListOf(
            formatPlanLimitFeature(limits.maxMachines, "computer"),
            formatPlanLimitFeature(limits.maxAgents, "agent"),
            formatPlanLimitFeature(limits.maxChannels, "channel"),
            formatPlanHistoryFeature(limits.messageHistoryDays)
        )
    if (plan.id == "free" && trialActive) {
        rows += "Unlimited during current free trial"
    }
    if (subscription?.status == "active" && plan.id == "pro") {
        rows += "Stripe subscription active"
    }
    return rows
}

private fun formatPlanLimitFeature(limit: Int, label: String): String =
    when (limit) {
        -1 -> "Unlimited ${label}s"
        1 -> "1 $label"
        else -> "$limit ${label}s"
    }

private fun formatPlanHistoryFeature(limit: Int): String =
    when (limit) {
        -1 -> "Unlimited history"
        1 -> "1 day history"
        else -> "$limit days history"
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
    billing: DataResult<SettingsBillingSubscriptionSnapshot>,
    usageRowCount: Int
): String {
    val plan = sessionPlan.toPlanDisplayName()
    val usage = if (usageRowCount > 0) " Usage rows: $usageRowCount." else ""
    return when (billing) {
        is DataResult.Content -> {
            val detail =
                billing.data.status?.toSettingsStatusLabel()?.let { status ->
                    " Billing status: $status."
                }.orEmpty()
            "Current plan: $plan.$detail$usage"
        }
        is DataResult.NoPermission -> "Billing requires owner permission."
        is DataResult.Error -> "Current plan: $plan. Billing subscription failed: ${billing.error.code}.$usage"
        is DataResult.Empty -> "Current plan: $plan.$usage"
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

private fun translationSectionDetail(
    result: DataResult<SettingsServerTranslationSnapshot>,
    translation: SettingsServerTranslationSnapshot?
): String =
    when (result) {
        is DataResult.Content -> translation?.detail ?: "Translation settings loaded."
        is DataResult.Empty -> "No translation settings were returned."
        is DataResult.NoPermission -> "Only server owners and admins can update translation settings."
        is DataResult.Error -> "Translation settings failed to load: ${result.error.code}"
    }

private fun sidebarOrderSectionDetail(
    result: DataResult<SettingsServerSidebarOrderSnapshot>,
    sidebarOrder: SettingsServerSidebarOrderSnapshot?
): String =
    when (result) {
        is DataResult.Content -> {
            val channelMode = sidebarOrder?.channelSortMode?.takeIf { it.isNotBlank() } ?: "manual"
            val dmMode = sidebarOrder?.dmSortMode?.takeIf { it.isNotBlank() } ?: "manual"
            "Channel sort: $channelMode. DM sort: $dmMode."
        }
        is DataResult.Empty -> "No sidebar order settings were returned."
        is DataResult.NoPermission -> "Only server owners and admins can update sidebar order."
        is DataResult.Error -> "Sidebar order failed to load: ${result.error.code}"
    }

private fun invitesSectionDetail(result: DataResult<List<SettingsServerInviteSnapshot>>, count: Int): String =
    when (result) {
        is DataResult.Content -> if (count == 0) "No pending invites." else "$count pending invite${if (count == 1) "" else "s"}"
        is DataResult.Empty -> "No pending invites."
        is DataResult.NoPermission -> "Only server owners and admins can view invites."
        is DataResult.Error -> "Pending invites failed to load: ${result.error.code}"
    }

private fun joinLinksSectionDetail(result: DataResult<List<SettingsServerJoinLinkSnapshot>>, count: Int): String =
    when (result) {
        is DataResult.Content -> if (count == 0) "No active join links." else "$count join link${if (count == 1) "" else "s"}"
        is DataResult.Empty -> "No active join links."
        is DataResult.NoPermission -> "Only server owners and admins can view join links."
        is DataResult.Error -> "Join links failed to load: ${result.error.code}"
    }

private fun <T> DataResult<T>.sectionState(empty: Boolean): SettingsSectionAvailability =
    when (this) {
        is DataResult.Content -> if (empty) SettingsSectionAvailability.Empty else SettingsSectionAvailability.Available
        is DataResult.Empty -> SettingsSectionAvailability.Empty
        is DataResult.NoPermission -> SettingsSectionAvailability.NoPermission
        is DataResult.Error -> SettingsSectionAvailability.Unavailable
    }

private fun combineSectionState(
    billing: DataResult<SettingsBillingSubscriptionSnapshot>,
    usage: DataResult<SettingsServerUsageSnapshot>,
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
    usage: SettingsServerUsageSnapshot?,
    billing: SettingsBillingSubscriptionSnapshot?
): List<SettingsPlanUsageSnapshot> =
    buildList {
        billing?.let { addAll(it.toServerUsageRows()) }
        usage?.let { addAll(it.toServerUsageRows()) }
    }

private fun SettingsBillingSubscriptionSnapshot.toServerUsageRows(): List<SettingsPlanUsageSnapshot> =
    buildList {
        add(SettingsPlanUsageSnapshot(label = "Billing Status", value = status?.toSettingsStatusLabel() ?: "No subscription"))
        currentPeriodEnd?.let { add(SettingsPlanUsageSnapshot(label = "Current Period End", value = it)) }
        if (cancelAtPeriodEnd) {
            add(SettingsPlanUsageSnapshot(label = "Cancel At Period End", value = "Enabled"))
        }
        add(SettingsPlanUsageSnapshot(label = "Stripe Configured", value = stripeConfigured.toString()))
    }

private fun SettingsServerUsageSnapshot.toServerUsageRows(): List<SettingsPlanUsageSnapshot> =
    listOf(
        SettingsPlanUsageSnapshot("Computers", machines.toString()),
        SettingsPlanUsageSnapshot("Agents", agents.toString()),
        SettingsPlanUsageSnapshot("Channels", channels.toString())
    )

private fun String.toSettingsSlug(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "server" }

private fun SettingsServerSidebarOrderUpdateRequest.hasAtLeastOneField(): Boolean =
    channelOrder != null ||
        agentOrder != null ||
        dmOrder != null ||
        channelSortMode != null ||
        dmSortMode != null ||
        pinnedSortMode != null ||
        pinnedChannelIds != null ||
        pinnedAgentIds != null ||
        pinnedOrder != null ||
        hiddenDmIds != null ||
        channelPanelTabOrder != null ||
        agentPanelTabOrder != null

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

@Serializable
data class SettingsServerJoinLinkCreateRequestBody(
    val expiresAt: String? = null,
    val maxUses: Int? = null
)

fun buildSharedSettingsServerJoinLinkCreateBody(request: SettingsServerJoinLinkCreateRequest): String =
    settingsRepositoryJson.encodeToString(
        SettingsServerJoinLinkCreateRequestBody(
            expiresAt = request.expiresAt,
            maxUses = request.maxUses
        )
    )

@Serializable
data class SettingsServerOnboardingUpdateRequestBody(
    val setupModalReminderOptOut: Boolean
)

fun buildSharedSettingsServerOnboardingUpdateBody(request: SettingsServerOnboardingUpdateRequest): String =
    settingsRepositoryJson.encodeToString(
        SettingsServerOnboardingUpdateRequestBody(
            setupModalReminderOptOut = request.setupModalReminderOptOut
        )
    )

@Serializable
data class SettingsServerTranslationUpdateRequestBody(
    val translationEnabled: Boolean
)

fun buildSharedSettingsServerTranslationUpdateBody(request: SettingsServerTranslationUpdateRequest): String =
    settingsRepositoryJson.encodeToString(
        SettingsServerTranslationUpdateRequestBody(
            translationEnabled = request.translationEnabled
        )
    )

@Serializable
data class SettingsServerSidebarOrderUpdateRequestBody(
    val channelOrder: List<String>? = null,
    val agentOrder: List<String>? = null,
    val dmOrder: List<String>? = null,
    val channelSortMode: String? = null,
    val dmSortMode: String? = null,
    val pinnedSortMode: String? = null,
    val pinnedChannelIds: List<String>? = null,
    val pinnedAgentIds: List<String>? = null,
    val pinnedOrder: List<String>? = null,
    val hiddenDmIds: List<String>? = null,
    val channelPanelTabOrder: List<String>? = null,
    val agentPanelTabOrder: List<String>? = null
)

fun buildSharedSettingsServerSidebarOrderUpdateBody(request: SettingsServerSidebarOrderUpdateRequest): String =
    settingsRepositoryJson.encodeToString(
        SettingsServerSidebarOrderUpdateRequestBody(
            channelOrder = request.channelOrder,
            agentOrder = request.agentOrder,
            dmOrder = request.dmOrder,
            channelSortMode = request.channelSortMode,
            dmSortMode = request.dmSortMode,
            pinnedSortMode = request.pinnedSortMode,
            pinnedChannelIds = request.pinnedChannelIds,
            pinnedAgentIds = request.pinnedAgentIds,
            pinnedOrder = request.pinnedOrder,
            hiddenDmIds = request.hiddenDmIds,
            channelPanelTabOrder = request.channelPanelTabOrder,
            agentPanelTabOrder = request.agentPanelTabOrder
        )
    )

@Serializable
data class SettingsServerMemberRoleUpdateRequestBody(
    val role: String
)

fun buildSharedSettingsServerMemberRoleUpdateBody(role: String): String =
    settingsRepositoryJson.encodeToString(SettingsServerMemberRoleUpdateRequestBody(role = role))

@Serializable
data class SettingsServerMuteRequestBody(
    val serverPushMuted: Boolean
)

fun buildSharedSettingsServerMuteBody(enabled: Boolean): String =
    settingsRepositoryJson.encodeToString(SettingsServerMuteRequestBody(serverPushMuted = enabled))

fun buildSharedLanguageRegionPreferenceUpdateBody(
    request: SettingsLanguageRegionPreferenceUpdateRequest
): String =
    buildJsonObject {
        if (request.clearPreferredLanguage) {
            put("preferredLanguage", JsonNull)
        } else {
            request.preferredLanguage?.let { put("preferredLanguage", JsonPrimitive(it)) }
        }
        if (request.clearPreferredTimezone) {
            put("preferredTimezone", JsonNull)
        } else {
            request.preferredTimezone?.let { put("preferredTimezone", JsonPrimitive(it)) }
        }
        request.autoTranslationEnabled?.let { put("autoTranslationEnabled", JsonPrimitive(it)) }
        if (request.clearPreferredTimeFormat) {
            put("preferredTimeFormat", JsonNull)
        } else {
            request.preferredTimeFormat?.let { put("preferredTimeFormat", JsonPrimitive(it)) }
        }
    }.toString()

private fun SettingsLanguageRegionPreferenceUpdateRequest.hasAtLeastOneField(): Boolean =
    preferredLanguage != null ||
        clearPreferredLanguage ||
        preferredTimezone != null ||
        clearPreferredTimezone ||
        autoTranslationEnabled != null ||
        preferredTimeFormat != null ||
        clearPreferredTimeFormat

private fun SettingsLanguageRegionAccountPreferences.toLanguageRegionSnapshot(
    translationResult: DataResult<SettingsServerTranslationSnapshot>,
    canManageServer: Boolean
): SettingsLanguageRegionSnapshot {
    val translation = (translationResult as? DataResult.Content)?.data
    val translationEnabled = translation?.translationEnabled ?: false
    val providerAvailable = translation?.translationAvailable ?: false
    val canManageTranslation = translation?.canManageTranslation ?: canManageServer
    val translationStatus =
        translation?.statusLabel
            ?: when (translationResult) {
                is DataResult.Empty -> "Unavailable"
                is DataResult.Error -> "Error"
                is DataResult.NoPermission -> "No permission"
                is DataResult.Content -> "Ready"
            }
    val translationDetail =
        translation?.detail
            ?: when (translationResult) {
                is DataResult.Empty -> "Server translation settings are unavailable."
                is DataResult.Error -> "Server translation settings failed to load: ${translationResult.error.code}"
                is DataResult.NoPermission -> "You do not have permission to manage server translation settings."
                is DataResult.Content -> "Server message translation setting loaded."
            }
    val languageKey = preferredLanguage ?: "other"
    val languageLabel = languageRegionLanguageOptions.firstOrNull { it.key == languageKey }?.label ?: "Others"
    val timezoneKey = preferredTimezone ?: "UTC"
    val timezoneOptions =
        if (languageRegionTimezoneOptions.any { it.key == timezoneKey }) {
            languageRegionTimezoneOptions
        } else {
            languageRegionTimezoneOptions + SettingsMobileSelectionOptionSnapshot(timezoneKey, timezoneKey)
        }
    val timezoneLabel = timezoneOptions.firstOrNull { it.key == timezoneKey }?.label ?: timezoneKey
    val timeFormatKey = preferredTimeFormat ?: "24h"
    val timeFormatLabel = if (timeFormatKey == "12h") "12-hour" else "24-hour"
    return SettingsLanguageRegionSnapshot(
        serverTranslation =
            SettingsMobileToggleSnapshot(
                enabled = translationEnabled,
                available = canManageTranslation && translationResult !is DataResult.NoPermission,
                statusLabel = translationStatus,
                detail = translationDetail
            ),
        autoTranslation =
            SettingsMobileToggleSnapshot(
                enabled = autoTranslationEnabled,
                available = translationEnabled && providerAvailable,
                statusLabel =
                    when {
                        !translationEnabled -> "Server translation disabled"
                        !providerAvailable -> "Provider unavailable"
                        autoTranslationEnabled -> "Enabled"
                        else -> "Disabled"
                    },
                detail =
                    when {
                        !translationEnabled -> "Enable message translation for this server before auto-translation can run."
                        !providerAvailable -> "Translation provider is not configured for this deployment."
                        autoTranslationEnabled -> "Incoming messages can be translated automatically."
                        else -> "Automatic message translation is disabled."
                    }
            ),
        browserLanguage =
            SettingsMobileSelectionSnapshot(
                selectedKey = languageKey,
                selectedLabel = languageLabel,
                statusLabel = languageLabel,
                detail = "Used as the target language for automatic translation.",
                options = languageRegionLanguageOptions,
                available = true
            ),
        timezone =
            SettingsMobileSelectionSnapshot(
                selectedKey = timezoneKey,
                selectedLabel = timezoneLabel,
                statusLabel = timezoneLabel,
                detail =
                    if (preferredTimezone.isNullOrBlank()) {
                        "No account timezone is saved yet; UTC is used until you choose one."
                    } else {
                        "Stored on your account and used for Settings date and time labels."
                    },
                options = timezoneOptions,
                available = true
            ),
        timeFormat =
            SettingsMobileSelectionSnapshot(
                selectedKey = timeFormatKey,
                selectedLabel = timeFormatLabel,
                statusLabel = timeFormatLabel,
                detail = "Controls whether times render with 12-hour or 24-hour clocks.",
                options = languageRegionTimeFormatOptions,
                available = true
            )
    )
}

private val languageRegionLanguageOptions =
    listOf(
        SettingsMobileSelectionOptionSnapshot("other", "Others"),
        SettingsMobileSelectionOptionSnapshot("en", "English")
    )

private val languageRegionTimeFormatOptions =
    listOf(
        SettingsMobileSelectionOptionSnapshot("12h", "12-hour"),
        SettingsMobileSelectionOptionSnapshot("24h", "24-hour")
    )

private val languageRegionTimezoneOptions =
    listOf(
        SettingsMobileSelectionOptionSnapshot("UTC", "UTC+00:00 UTC"),
        SettingsMobileSelectionOptionSnapshot("America/Anchorage", "UTC-09:00 America/Anchorage"),
        SettingsMobileSelectionOptionSnapshot("America/Los_Angeles", "UTC-08:00 America/Los_Angeles"),
        SettingsMobileSelectionOptionSnapshot("America/Denver", "UTC-07:00 America/Denver"),
        SettingsMobileSelectionOptionSnapshot("America/Phoenix", "UTC-07:00 America/Phoenix"),
        SettingsMobileSelectionOptionSnapshot("America/Chicago", "UTC-06:00 America/Chicago"),
        SettingsMobileSelectionOptionSnapshot("America/New_York", "UTC-05:00 America/New_York"),
        SettingsMobileSelectionOptionSnapshot("America/Toronto", "UTC-05:00 America/Toronto"),
        SettingsMobileSelectionOptionSnapshot("America/Mexico_City", "UTC-06:00 America/Mexico_City"),
        SettingsMobileSelectionOptionSnapshot("America/Bogota", "UTC-05:00 America/Bogota"),
        SettingsMobileSelectionOptionSnapshot("America/Lima", "UTC-05:00 America/Lima"),
        SettingsMobileSelectionOptionSnapshot("America/Sao_Paulo", "UTC-03:00 America/Sao_Paulo"),
        SettingsMobileSelectionOptionSnapshot("America/Argentina/Buenos_Aires", "UTC-03:00 America/Argentina/Buenos_Aires"),
        SettingsMobileSelectionOptionSnapshot("Europe/London", "UTC+00:00 Europe/London"),
        SettingsMobileSelectionOptionSnapshot("Europe/Dublin", "UTC+00:00 Europe/Dublin"),
        SettingsMobileSelectionOptionSnapshot("Europe/Paris", "UTC+01:00 Europe/Paris"),
        SettingsMobileSelectionOptionSnapshot("Europe/Berlin", "UTC+01:00 Europe/Berlin"),
        SettingsMobileSelectionOptionSnapshot("Europe/Madrid", "UTC+01:00 Europe/Madrid"),
        SettingsMobileSelectionOptionSnapshot("Europe/Rome", "UTC+01:00 Europe/Rome"),
        SettingsMobileSelectionOptionSnapshot("Europe/Amsterdam", "UTC+01:00 Europe/Amsterdam"),
        SettingsMobileSelectionOptionSnapshot("Europe/Zurich", "UTC+01:00 Europe/Zurich"),
        SettingsMobileSelectionOptionSnapshot("Europe/Stockholm", "UTC+01:00 Europe/Stockholm"),
        SettingsMobileSelectionOptionSnapshot("Europe/Warsaw", "UTC+01:00 Europe/Warsaw"),
        SettingsMobileSelectionOptionSnapshot("Europe/Istanbul", "UTC+03:00 Europe/Istanbul"),
        SettingsMobileSelectionOptionSnapshot("Europe/Moscow", "UTC+03:00 Europe/Moscow"),
        SettingsMobileSelectionOptionSnapshot("Africa/Cairo", "UTC+02:00 Africa/Cairo"),
        SettingsMobileSelectionOptionSnapshot("Africa/Johannesburg", "UTC+02:00 Africa/Johannesburg"),
        SettingsMobileSelectionOptionSnapshot("Asia/Dubai", "UTC+04:00 Asia/Dubai"),
        SettingsMobileSelectionOptionSnapshot("Asia/Kolkata", "UTC+05:30 Asia/Kolkata"),
        SettingsMobileSelectionOptionSnapshot("Asia/Bangkok", "UTC+07:00 Asia/Bangkok"),
        SettingsMobileSelectionOptionSnapshot("Asia/Singapore", "UTC+08:00 Asia/Singapore"),
        SettingsMobileSelectionOptionSnapshot("Asia/Shanghai", "UTC+08:00 Asia/Shanghai"),
        SettingsMobileSelectionOptionSnapshot("Asia/Hong_Kong", "UTC+08:00 Asia/Hong_Kong"),
        SettingsMobileSelectionOptionSnapshot("Asia/Taipei", "UTC+08:00 Asia/Taipei"),
        SettingsMobileSelectionOptionSnapshot("Asia/Tokyo", "UTC+09:00 Asia/Tokyo"),
        SettingsMobileSelectionOptionSnapshot("Asia/Seoul", "UTC+09:00 Asia/Seoul"),
        SettingsMobileSelectionOptionSnapshot("Australia/Sydney", "UTC+10:00 Australia/Sydney"),
        SettingsMobileSelectionOptionSnapshot("Pacific/Auckland", "UTC+12:00 Pacific/Auckland")
    )
