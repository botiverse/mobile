package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.repository.AccountProfileUpdateRequest
import ai.slock.android.core.data.repository.AccountSettingsSnapshot
import ai.slock.android.core.data.repository.SettingsBillingCurrentPlanSnapshot
import ai.slock.android.core.data.repository.SettingsBillingPlanSnapshot
import ai.slock.android.core.data.repository.SettingsBillingSnapshot
import ai.slock.android.core.data.repository.SettingsPlanUsageSnapshot
import ai.slock.compose.data.DataResult
import ai.slock.android.core.data.repository.SettingsComputerAgentSnapshot
import ai.slock.android.core.data.repository.SettingsComputerDetailSnapshot
import ai.slock.android.core.data.repository.SettingsComputerSnapshot
import ai.slock.android.core.data.repository.SettingsComputerWorkspaceSnapshot
import ai.slock.android.core.data.repository.SettingsComputersSnapshot
import ai.slock.android.core.data.repository.SettingsMobileSnapshot
import ai.slock.android.core.data.repository.SettingsMobileToggleSnapshot
import ai.slock.android.core.data.repository.SettingsRepository
import ai.slock.android.core.data.repository.SettingsSectionErrorSnapshot
import ai.slock.android.core.data.repository.SettingsSectionAvailability
import ai.slock.android.core.data.repository.SettingsServerActionResult
import ai.slock.android.core.data.repository.SettingsServerAdminSnapshot
import ai.slock.android.core.data.repository.SettingsServerDeleteRequest
import ai.slock.android.core.data.repository.SettingsServerInviteCreateRequest
import ai.slock.android.core.data.repository.SettingsServerInviteSnapshot
import ai.slock.android.core.data.repository.SettingsServerJoinLinkSnapshot
import ai.slock.android.core.data.repository.SettingsServerOnboardingSnapshot
import ai.slock.android.core.data.repository.SettingsServerProfileSnapshot
import ai.slock.android.core.data.repository.SettingsServerProfileUpdateRequest
import ai.slock.android.core.data.repository.SettingsServerSectionSnapshot
import ai.slock.android.core.data.repository.SettingsServerSnapshot
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class RealSettingsRepository(
    private val store: AuthSessionStore,
    private val api: SlockApi,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : SettingsRepository {
    private val settingsApi: SlockSettingsServerApi = SlockSettingsServerApiClient(api, warningLogger)
    override suspend fun getAccount(): DataResult<AccountSettingsSnapshot> {
        val session = store.readSession() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.settingsAccount(currentSlockApiPriority()) },
            mapper = { json ->
                ai.slock.compose.data.parseSharedAccountSettingsSnapshot(
                    bodyText = json.toString(),
                    fallbackRole = session.servers.firstOrNull { it.id == session.currentServerId }?.role
                ) ?: throw IllegalStateException("Account settings response missing user")
            }
        )
    }

    override suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot> {
        val session = store.readSession() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { api.updateSettingsAccount(currentSlockApiPriority(), request.toJsonBody()) },
            mapper = { json ->
                ai.slock.compose.data.parseSharedAccountSettingsSnapshot(
                    bodyText = json.toString(),
                    fallbackRole = session.servers.firstOrNull { it.id == session.currentServerId }?.role
                ) ?: throw IllegalStateException("Account settings response missing user")
            }
        )
    }

    override suspend fun getComputers(): DataResult<SettingsComputersSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val serverId = session.currentServerId
            ?: return DataResult.NoPermission(
                PermissionBlocker(code = "server_required", requiredCapability = "selected_server")
            )
        logger("machines_fetch_start serverId=$serverId")
        val result = settingsApi.computers(currentSlockApiPriority(), serverId)
            .emptyWhen { it.computers.isEmpty() }
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
        val machines = settingsApi.computers(currentSlockApiPriority(), serverId)
            .emptyWhen { it.computers.isEmpty() }
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
        val workspaces = settingsApi.machineWorkspaces(currentSlockApiPriority(), serverId, machineId)
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
            block = { api.settingsAgents(currentSlockApiPriority()) },
            source = "settings_machine_agents",
            warningLogger = warningLogger,
            mapper = { json ->
                val agents = ai.slock.compose.data.parseSharedSettingsComputerAgents(
                    bodyText = json.getAsJsonArray("items")?.toString() ?: json.toString(),
                    machineId = machineId
                ) ?: throw IllegalStateException("Settings agents response missing agent list")
                if (agents.isEmpty()) {
                    warningLogger("empty_items source=settings_machine_agents stage=list_extract contract=agents_array")
                }
                agents
            }
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
        val agentRows = enrichComputerAgents(
            serverId = serverId,
            agents = (agents as? DataResult.Content)?.data.orEmpty()
        )
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

    private suspend fun enrichComputerAgents(
        serverId: String,
        agents: List<SettingsComputerAgentSnapshot>
    ): List<SettingsComputerAgentSnapshot> {
        if (agents.isEmpty()) return agents
        val members = execute(
            emptyReason = EmptyReason.NoMembers,
            block = { api.serverMembers(currentSlockApiPriority(), serverId) },
            source = "settings_machine_agent_members",
            warningLogger = warningLogger,
            mapper = { json ->
                ai.slock.compose.data.parseSharedSettingsMemberProfiles(
                    json.getAsJsonArray("items")?.toString() ?: json.toString()
                )
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
                        SettingsPlanUsageSnapshot(label = "Role", value = server.role.replaceFirstChar { it.uppercase() }),
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
            ?: return DataResult.NoPermission(
                PermissionBlocker(code = "server_required", requiredCapability = "selected_server")
            )
        val serverId = server.id
        val canManageServer = server.role == "owner" || server.role == "admin"
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

    private suspend fun fetchServerMembers(
        serverId: String
    ): DataResult<List<SettingsServerAdminSnapshot>> =
        settingsApi.serverAdmins(currentSlockApiPriority(), serverId)

    private suspend fun fetchServerUsage(serverId: String): DataResult<List<SettingsPlanUsageSnapshot>> =
        settingsApi.serverUsage(currentSlockApiPriority(), serverId)

    private suspend fun fetchServerBilling(): DataResult<List<SettingsPlanUsageSnapshot>> =
        settingsApi.billingSubscription(currentSlockApiPriority())

    private suspend fun fetchServerOnboarding(
        serverId: String
    ): DataResult<SettingsServerOnboardingSnapshot> =
        settingsApi.serverOnboarding(currentSlockApiPriority(), serverId)

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

    override suspend fun updateServerProfile(
        request: SettingsServerProfileUpdateRequest
    ): DataResult<SettingsServerSnapshot> {
        val session = store.readSession() ?: return authRequired()
        val server = session.servers.firstOrNull { it.id == session.currentServerId }
            ?: return DataResult.NoPermission(
                PermissionBlocker(code = "server_required", requiredCapability = "selected_server")
            )
        if (server.role != "owner" && server.role != "admin") {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_server")
            )
        }
        val name = request.name.trim()
        if (name.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val update =
            settingsApi.updateServerProfile(
                currentSlockApiPriority(),
                server.id,
                mapOf("name" to name).toJsonBody()
            )
        return when (update) {
            is DataResult.Content -> getServerSettings()
            is DataResult.Empty -> DataResult.Empty(update.reason)
            is DataResult.Error -> DataResult.Error(update.error)
            is DataResult.NoPermission -> DataResult.NoPermission(update.blocker)
        }
    }

    override suspend fun createServerInvite(
        request: SettingsServerInviteCreateRequest
    ): DataResult<SettingsServerActionResult> {
        val session = store.readSession() ?: return authRequired()
        val server = session.servers.firstOrNull { it.id == session.currentServerId }
            ?: return DataResult.NoPermission(
                PermissionBlocker(code = "server_required", requiredCapability = "selected_server")
            )
        if (server.role != "owner" && server.role != "admin") {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_invites")
            )
        }
        val email = request.email.trim()
        if (email.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsApi.createServerInvite(
            currentSlockApiPriority(),
            server.id,
            mapOf("email" to email).toJsonBody()
        )
    }

    override suspend fun deleteServer(
        request: SettingsServerDeleteRequest
    ): DataResult<SettingsServerActionResult> {
        val session = store.readSession() ?: return authRequired()
        val server = session.servers.firstOrNull { it.id == session.currentServerId }
            ?: return DataResult.NoPermission(
                PermissionBlocker(code = "server_required", requiredCapability = "selected_server")
            )
        if (server.role != "owner") {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_owner_required", requiredCapability = "delete_server")
            )
        }
        if (request.confirmationName.trim() != server.name) {
            return DataResult.Error(ScreenError(code = "server_delete_confirmation_mismatch", retryable = false))
        }
        return settingsApi.deleteServer(currentSlockApiPriority(), server.id)
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

private fun <T> DataResult<T>.emptyWhen(predicate: (T) -> Boolean): DataResult<T> =
    when {
        this is DataResult.Content && predicate(data) -> DataResult.Empty(EmptyReason.NoSettingsAvailable)
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

private fun Map<String, Any?>.toJsonBody() =
    Gson().toJson(this).toRequestBody(jsonMediaType)

private fun List<SettingsComputerAgentSnapshot>.enrichWithMembers(
    members: List<MemberProfile>
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

private fun String.toSettingsSlug(): String =
    lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "server" }
