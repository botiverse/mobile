package build.raft.app.data

import build.raft.app.resources.*
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.TimeSource

private val settingsRepositoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private const val PRE_JOIN_AGREEMENT_BODY_MAX_LENGTH = 5_000

private suspend fun RaftNetworkClient.settingsAccount(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "auth/me")

private suspend fun RaftNetworkClient.updateSettingsAccount(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "auth/me", method = "PATCH", bodyText = bodyText)

private suspend fun RaftNetworkClient.settingsAccountAuthProviders(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "auth/providers")

private suspend fun RaftNetworkClient.settingsAccountAuthIdentities(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "auth/identities")

private suspend fun RaftNetworkClient.startSettingsAccountProviderLink(
    priority: DataRequestPriority,
    providerId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "auth/${providerId.sanitizeAccountProviderId()}/link/start", method = "POST", bodyText = bodyText)

/** `DELETE /api/auth/identities/:provider` (slock `packages/server/src/routes/auth.ts`): unlink a social sign-in. */
private suspend fun RaftNetworkClient.disconnectSettingsAccountProvider(
    priority: DataRequestPriority,
    providerId: String
): HomeNetworkResponse =
    request(priority, "auth/identities/${providerId.sanitizeAccountProviderId()}", method = "DELETE")

private suspend fun RaftNetworkClient.settingsConnectedAppClients(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "integrations/clients")

private suspend fun RaftNetworkClient.createSettingsConnectedAppClient(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "integrations/clients", method = "POST", bodyText = bodyText)

private suspend fun RaftNetworkClient.updateSettingsConnectedAppClient(
    priority: DataRequestPriority,
    clientRecordId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "integrations/clients/$clientRecordId", method = "PATCH", bodyText = bodyText)

private suspend fun RaftNetworkClient.clearSettingsConnectedAppLogo(
    priority: DataRequestPriority,
    clientRecordId: String
): HomeNetworkResponse =
    request(priority, "integrations/clients/$clientRecordId/logo", method = "DELETE")

private suspend fun RaftNetworkClient.deleteSettingsConnectedAppClient(
    priority: DataRequestPriority,
    clientRecordId: String
): HomeNetworkResponse =
    request(priority, "integrations/clients/$clientRecordId", method = "DELETE")

private suspend fun RaftNetworkClient.requestSettingsConnectedAppPublish(
    priority: DataRequestPriority,
    clientRecordId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "integrations/clients/$clientRecordId/request-publish", method = "POST", bodyText = bodyText)

private suspend fun RaftNetworkClient.settingsConnectedAppBuiltIns(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "integrations/built-in")

private suspend fun RaftNetworkClient.settingsConnectedAppMarketplace(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "integrations/marketplace")

private suspend fun RaftNetworkClient.installSettingsConnectedAppMarketplace(
    priority: DataRequestPriority,
    clientRecordId: String
): HomeNetworkResponse =
    request(priority, "integrations/marketplace/$clientRecordId/install", method = "POST")

private suspend fun RaftNetworkClient.uninstallSettingsConnectedAppMarketplace(
    priority: DataRequestPriority,
    clientRecordId: String
): HomeNetworkResponse =
    request(priority, "integrations/marketplace/$clientRecordId/install", method = "DELETE")

private suspend fun RaftNetworkClient.settingsMachines(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/machines", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.settingsMachineWorkspaces(
    priority: DataRequestPriority,
    serverId: String,
    machineId: String
): HomeNetworkResponse =
    request(
        priority = priority,
        path = "servers/$serverId/machines/$machineId/workspaces",
        serverIdOverride = ServerId(serverId)
    )

private suspend fun RaftNetworkClient.settingsAgents(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "agents")

private suspend fun RaftNetworkClient.serverMembers(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/members", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.settingsServerUsage(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/usage", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.settingsBillingSubscription(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "billing/subscription")

private suspend fun RaftNetworkClient.settingsServerOnboarding(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/onboarding-settings", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.updateSettingsServerOnboarding(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/onboarding-settings", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun RaftNetworkClient.settingsServerAgreement(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/agreement", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.updateSettingsServerAgreement(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/agreement", method = "PUT", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun RaftNetworkClient.settingsServerTranslation(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/translation-settings", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.updateSettingsServerTranslation(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/translation-settings", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun RaftNetworkClient.settingsServerSidebarOrder(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/sidebar-order", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.updateSettingsServerSidebarOrder(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/sidebar-order", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun RaftNetworkClient.settingsServerOrder(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "servers/order")

private suspend fun RaftNetworkClient.updateSettingsServerOrder(
    priority: DataRequestPriority,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/order", method = "PATCH", bodyText = bodyText)

private suspend fun RaftNetworkClient.updateSettingsServerMemberRole(
    priority: DataRequestPriority,
    serverId: String,
    memberId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/members/$memberId", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

internal suspend fun RaftNetworkClient.settingsServerChannels(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "channels")

private suspend fun RaftNetworkClient.deleteSettingsServerMember(
    priority: DataRequestPriority,
    serverId: String,
    memberId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/members/$memberId", method = "DELETE", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.leaveSettingsServer(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/leave", method = "POST", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.updateSettingsServerProfile(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun RaftNetworkClient.createSettingsServerInvite(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/invites", method = "POST", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun RaftNetworkClient.settingsServerInvites(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/invites", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.deleteSettingsServerInvite(
    priority: DataRequestPriority,
    serverId: String,
    inviteId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/invites/$inviteId", method = "DELETE", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.createSettingsServerJoinLink(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/join-links", method = "POST", serverIdOverride = ServerId(serverId), bodyText = bodyText)

private suspend fun RaftNetworkClient.settingsServerJoinLinks(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/join-links", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.deleteSettingsServerJoinLink(
    priority: DataRequestPriority,
    serverId: String,
    linkId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/join-links/$linkId", method = "DELETE", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.deleteSettingsServer(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId", method = "DELETE", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.settingsNotificationSettings(
    priority: DataRequestPriority,
    serverId: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/notification-settings", serverIdOverride = ServerId(serverId))

private suspend fun RaftNetworkClient.updateSettingsNotificationSettings(
    priority: DataRequestPriority,
    serverId: String,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "servers/$serverId/notification-settings", method = "PATCH", serverIdOverride = ServerId(serverId), bodyText = bodyText)

class RealSettingsRepository(
    private val sessionStore: RaftAuthSessionStore,
    private val networkClient: RaftNetworkClient,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {},
    private val channelRepository: ChannelRepository? = null,
    private val onAppearanceSnapshot: suspend (ownerId: String, snapshot: SettingsAppearanceSnapshot) -> Unit = { _, _ -> },
    /**
     * Durable last-known workspace notification preference per (user, server). Null only for repositories
     * that never serve the notification settings screen (sidebar order, members, thread pages).
     */
    private val serverNotificationLastKnownStore: SettingsServerNotificationLastKnownStore? = null,
) : SettingsRepository, SettingsServerActionRepository, SettingsServerManagementRepository {
    private val computersApiClient = RaftComputersApiClient(RaftComputersNetworkApi(networkClient))
    private val secondFactorStepUpRepository = SecondFactorStepUpRepository(networkClient)

    override suspend fun stepUpSecondFactor(answer: SecondFactorStepUpAnswer): SecondFactorStepUpResult =
        secondFactorStepUpRepository.stepUp(answer)

    override suspend fun getAccount(): DataResult<AccountSettingsSnapshot> {
        val session = sessionStore.readSession() ?: return authRequired()
        val accountResult =
            executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { networkClient.settingsAccount(DataRequestPriority.Visible) },
            errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedAccountSettingsSnapshot(
                    bodyText = bodyText,
                    fallbackRole = session.servers.firstOrNull { it.id == session.currentServerId }?.role
                ) ?: throw IllegalStateException("Account settings response missing user")
            }
        )
        return accountResult.withConnectedAccountProviders(session.user.email)
    }

    override suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot> {
        val session = sessionStore.readSession() ?: return authRequired()
        val accountResult =
            executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = {
                networkClient.updateSettingsAccount(
                    DataRequestPriority.Foreground,
                    buildSharedAccountProfileUpdateBody(request)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedAccountSettingsSnapshot(
                    bodyText = bodyText,
                    fallbackRole = session.servers.firstOrNull { it.id == session.currentServerId }?.role
                ) ?: throw IllegalStateException("Account settings response missing user")
            }
        )
        return accountResult.withConnectedAccountProviders(session.user.email)
    }

    override suspend fun updateShareUsageData(enabled: Boolean): DataResult<AccountUsageDataSnapshot> {
        sessionStore.readSession() ?: return authRequired()
        return executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = {
                networkClient.updateSettingsAccount(
                    DataRequestPriority.Foreground,
                    buildSharedShareUsageDataUpdateBody(enabled)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedAccountUsageDataSnapshot(bodyText)
                    ?: throw IllegalStateException("Share usage data response missing user")
            }
        )
    }

    override suspend fun changeAccountPassword(request: AccountPasswordChangeRequest): DataResult<AccountSettingsSnapshot> {
        val session = sessionStore.readSession() ?: return authRequired()
        return executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = {
                networkClient.updateSettingsAccount(
                    DataRequestPriority.Foreground,
                    buildSharedAccountPasswordChangeBody(request)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedAccountSettingsSnapshot(
                    bodyText = bodyText,
                    fallbackRole = session.servers.firstOrNull { it.id == session.currentServerId }?.role
                ) ?: throw IllegalStateException("Account settings response missing user")
            }
        ).withConnectedAccountProviders(session.user.email)
    }

    override suspend fun uploadAccountAvatar(request: AccountAvatarUploadRequest): DataResult<AccountSettingsSnapshot> {
        val session = sessionStore.readSession() ?: return authRequired()
        return executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = {
                networkClient.uploadAccountAvatar(
                    DataRequestPriority.Foreground,
                    request.file.toAccountAvatarMultipartFile()
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedAccountSettingsSnapshot(
                    bodyText = bodyText,
                    fallbackRole = session.servers.firstOrNull { it.id == session.currentServerId }?.role
                ) ?: throw IllegalStateException("Account avatar response missing user")
            }
        ).withConnectedAccountProviders(session.user.email)
    }

    override suspend fun startAccountProviderLink(request: AccountProviderLinkStartRequest): DataResult<AccountProviderLinkStartResult> {
        sessionStore.readSession() ?: return authRequired()
        val providerId = request.providerId.sanitizeAccountProviderId().takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError("account_provider_invalid", retryable = false))
        return executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = {
                networkClient.startSettingsAccountProviderLink(
                    DataRequestPriority.Foreground,
                    providerId,
                    buildSharedAccountProviderLinkStartBody(request.copy(providerId = providerId))
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedAccountProviderLinkStartResult(providerId, bodyText)
                    ?: throw IllegalStateException("Account provider link response missing url")
            }
        )
    }

    /**
     * Web SettingsPanel `handleDisconnectProvider`: `DELETE /api/auth/identities/:provider`, then the
     * account is read again so the row shows "Not connected". A 409 `PASSWORD_CREDENTIAL_REQUIRED`
     * (the last sign-in and no password) comes back as an error carrying the server's sentence.
     */
    override suspend fun disconnectAccountProvider(providerId: String): DataResult<AccountSettingsSnapshot> {
        sessionStore.readSession() ?: return authRequired()
        val id = providerId.sanitizeAccountProviderId().takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError("account_provider_invalid", retryable = false))
        val unlinked =
            executeSettings(
                emptyReason = EmptyReason.NoSettingsAvailable,
                block = { networkClient.disconnectSettingsAccountProvider(DataRequestPriority.Foreground, id) },
                errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
                mapper = { bodyText ->
                    parseSharedAccountProviderUnlinkResult(bodyText)
                        ?: throw IllegalStateException("Account provider unlink response missing identities")
                }
            )
        return when (unlinked) {
            is DataResult.Content -> getAccount()
            is DataResult.Empty -> DataResult.Empty(unlinked.reason)
            is DataResult.Error -> DataResult.Error(unlinked.error)
            is DataResult.NoPermission -> DataResult.NoPermission(unlinked.blocker)
        }
    }

    private suspend fun DataResult<AccountSettingsSnapshot>.withConnectedAccountProviders(
        fallbackEmail: String
    ): DataResult<AccountSettingsSnapshot> {
        if (this !is DataResult.Content) return this
        val providers = networkClient.settingsAccountAuthProviders(DataRequestPriority.Visible)
        val identities = networkClient.settingsAccountAuthIdentities(DataRequestPriority.Visible)
        if (!providers.isSuccessful || !identities.isSuccessful) {
            warningLogger(
                "settings_account_connected_accounts_unavailable " +
                    "providers=${providers.code} identities=${identities.code}"
            )
            return this
        }
        val accounts =
            parseSharedAccountConnectedAccounts(
                providersBodyText = providers.bodyText.orEmpty(),
                identitiesBodyText = identities.bodyText.orEmpty(),
                fallbackEmail = fallbackEmail
            )
        if (accounts == null) {
            warningLogger("settings_account_connected_accounts_parse_failed")
            return this
        }
        return DataResult.Content(data.copy(connectedAccounts = accounts))
    }

    override suspend fun getConnectedApps(): DataResult<SettingsConnectedAppsSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_integrations")
            )
        }
        return fetchConnectedAppsSnapshot(server.canManageServer)
    }

    override suspend fun registerConnectedApp(
        request: SettingsConnectedAppRegisterRequest
    ): DataResult<SettingsConnectedAppsSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_integrations")
            )
        }
        val name = request.name.trim()
        if (name.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val create =
            settingsCreateConnectedApp(
                request.copy(
                    name = name,
                    clientId = request.clientId?.trim()?.takeIf { it.isNotBlank() },
                    description = request.description?.trim()?.takeIf { it.isNotBlank() },
                    homepageUrl = request.homepageUrl?.trim()?.takeIf { it.isNotBlank() },
                    returnUrl = request.returnUrl?.trim()?.takeIf { it.isNotBlank() },
                    agentManifestUrl = request.agentManifestUrl?.trim()?.takeIf { it.isNotBlank() }
                )
            )
        return when (create) {
            is DataResult.Content -> fetchConnectedAppsSnapshot(server.canManageServer, create.data.second)
            is DataResult.Empty -> DataResult.Empty(create.reason)
            is DataResult.Error -> DataResult.Error(create.error)
            is DataResult.NoPermission -> DataResult.NoPermission(create.blocker)
        }
    }

    override suspend fun updateConnectedApp(
        request: SettingsConnectedAppUpdateRequest
    ): DataResult<SettingsConnectedAppsSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_integrations")
            )
        }
        val clientRecordId = request.id.trim()
        val name = request.name.trim()
        if (clientRecordId.isBlank() || name.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val update =
            settingsUpdateConnectedApp(
                request.copy(
                    id = clientRecordId,
                    name = name,
                    description = request.description?.trim()?.takeIf { it.isNotBlank() },
                    homepageUrl = request.homepageUrl?.trim()?.takeIf { it.isNotBlank() },
                    returnUrl = request.returnUrl?.trim()?.takeIf { it.isNotBlank() },
                    agentManifestUrl = request.agentManifestUrl?.trim()?.takeIf { it.isNotBlank() }
                )
            )
        return update.reloadConnectedAppsSnapshot(server.canManageServer)
    }

    override suspend fun uploadConnectedAppLogo(
        request: SettingsConnectedAppLogoUploadRequest
    ): DataResult<SettingsConnectedAppsSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_integrations")
            )
        }
        val clientRecordId = request.id.trim()
        if (clientRecordId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsUploadConnectedAppLogo(request.copy(id = clientRecordId)).reloadConnectedAppsSnapshot(server.canManageServer)
    }

    override suspend fun clearConnectedAppLogo(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_integrations")
            )
        }
        val id = clientRecordId.trim()
        if (id.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsClearConnectedAppLogo(id).reloadConnectedAppsSnapshot(server.canManageServer)
    }

    override suspend fun deleteConnectedApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_integrations")
            )
        }
        val id = clientRecordId.trim()
        if (id.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsDeleteConnectedApp(id).reloadConnectedAppsSnapshot(server.canManageServer)
    }

    override suspend fun requestConnectedAppPublish(
        request: SettingsConnectedAppPublishRequest
    ): DataResult<SettingsConnectedAppsSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_integrations")
            )
        }
        val id = request.id.trim()
        val category = request.category.trim().takeIf { it.isNotBlank() } ?: "Other"
        if (id.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsRequestConnectedAppPublish(
            SettingsConnectedAppPublishRequest(
                id = id,
                category = category,
                dataAccessSummary = request.dataAccessSummary?.trim()?.takeIf { it.isNotBlank() }
            )
        ).reloadConnectedAppsSnapshot(server.canManageServer)
    }

    override suspend fun installMarketplaceApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_integrations")
            )
        }
        val id = clientRecordId.trim()
        if (id.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsInstallMarketplaceApp(id).reloadConnectedAppsSnapshot(server.canManageServer)
    }

    override suspend fun uninstallMarketplaceApp(clientRecordId: String): DataResult<SettingsConnectedAppsSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_integrations")
            )
        }
        val id = clientRecordId.trim()
        if (id.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsUninstallMarketplaceApp(id).reloadConnectedAppsSnapshot(server.canManageServer)
    }

    override suspend fun getComputers(): DataResult<SettingsComputersSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        val serverId = server.id
        logger("machines_fetch_start serverId=$serverId")
        val result = settingsComputers(serverId, canManageServer = server.canManageServer)
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
        val machines = settingsComputers(serverId, canManageServer = false).emptyWhen { it.computers.isEmpty() }
        machines.logMachineDetailSection(serverId, machineId, "machines", (machines as? DataResult.Content)?.data?.computers?.size, logger, warningLogger)
        if (machines !is DataResult.Content) return machines.mapSettingsDetailError(serverId, machineId)
        val computer = machines.data.computers.firstOrNull { it.id == machineId }
            ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return getComputerDetailForServerComputer(serverId, computer)
    }

    override suspend fun getComputerDetailForComputer(computer: SettingsComputerSnapshot): DataResult<SettingsComputerDetailSnapshot> {
        val serverId = selectedServerId() ?: return serverRequired()
        if (computer.id.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        logger("machine_detail_fetch_start serverId=$serverId machineId=${computer.id} source=shared_computers")
        return getComputerDetailForServerComputer(serverId, computer)
    }

    private suspend fun getComputerDetailForServerComputer(
        serverId: String,
        computer: SettingsComputerSnapshot
    ): DataResult<SettingsComputerDetailSnapshot> {
        val machineId = computer.id
        // Workspace discovery is a remote recursive filesystem scan. Keep it behind the explicit Scan action.
        logger("machine_detail_section_start serverId=$serverId machineId=$machineId section=agents")
        val agents = settingsComputerAgents(machineId).mapSettingsContractMismatch("settings_agents_contract_mismatch")
        agents.logMachineDetailSection(serverId, machineId, "agents", (agents as? DataResult.Content)?.data?.size, logger, warningLogger)

        val agentRows = enrichComputerAgents(serverId, (agents as? DataResult.Content)?.data.orEmpty())
        val agentsError = agents.toSettingsSectionErrorSnapshot()
        logger(
            "machine_detail_fetch_content serverId=$serverId machineId=$machineId workspaces=not_scanned agents=${agentRows.size} agentsError=${agentsError?.code ?: "none"}"
        )
        return DataResult.Content(
            SettingsComputerDetailSnapshot(
                computer = computer,
                workspaces = emptyList(),
                agents = agentRows,
                sections = listOf(
                    SettingsServerSectionSnapshot(
                        key = "machine_status_events",
                        title = Res.string.settings_section_status_events_title.desc(),
                        detail = "Web updates status through realtime machine status events; no Settings detail REST contract is confirmed yet."
                    ),
                    SettingsServerSectionSnapshot(
                        key = "machine_logs",
                        title = Res.string.settings_section_logs_title.desc(),
                        detail = "Machine logs or heartbeat history endpoint is not confirmed for Android yet."
                    )
                ),
                workspacesScanned = false,
                agentsError = agentsError
            )
        )
    }

    override suspend fun scanComputerWorkspaces(machineId: String): DataResult<List<SettingsComputerWorkspaceSnapshot>> {
        val serverId = selectedServerId() ?: return serverRequired()
        if (machineId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        logger("machine_detail_section_start serverId=$serverId machineId=$machineId section=workspaces trigger=user_scan")
        val result = settingsMachineWorkspaces(serverId, machineId)
        result.logMachineDetailSection(
            serverId,
            machineId,
            "workspaces",
            (result as? DataResult.Content)?.data?.size,
            logger,
            warningLogger
        )
        return result
    }

    override suspend fun registerComputer(request: SettingsComputerRegisterRequest): DataResult<SettingsComputerRegistrationResult> {
        val serverId = selectedServerId() ?: return serverRequired()
        val normalizedName = request.name.trim()
        if (normalizedName.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return computersApiClient.registerMachine(ServerId(serverId), normalizedName).mapComputerResult { summary ->
            SettingsComputerRegistrationResult(
                machine = summary.machine.toSettingsComputerSnapshot(),
                apiKey = summary.apiKey,
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

    override suspend fun updateComputerDescription(request: SettingsComputerDescriptionUpdateRequest): DataResult<SettingsComputerSnapshot> {
        val serverId = selectedServerId() ?: return serverRequired()
        val machineId = request.machineId.trim()
        if (machineId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return computersApiClient.updateMachineDescription(ServerId(serverId), machineId, request.description.trim()).mapComputerResult {
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

    override suspend fun restartComputer(machineId: String): DataResult<SettingsComputerRestartResult> {
        val serverId = selectedServerId() ?: return serverRequired()
        val normalizedMachineId = machineId.trim()
        if (normalizedMachineId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        logger("machine_control_start serverId=$serverId machineId=$normalizedMachineId action=restart")
        return computersApiClient.restartComputer(ServerId(serverId), normalizedMachineId).mapComputerResult {
            SettingsComputerRestartResult(machineId = normalizedMachineId, requestId = it.requestId, queued = it.queued)
        }
    }

    override suspend fun upgradeComputer(
        request: SettingsComputerUpgradeRequest
    ): DataResult<SettingsComputerUpgradeRequestSnapshot> {
        val serverId = selectedServerId() ?: return serverRequired()
        val machineId = request.machineId.trim()
        val targetVersion = request.targetVersion.trim()
        if (machineId.isBlank() || targetVersion.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        logger("machine_control_start serverId=$serverId machineId=$machineId action=upgrade target=$targetVersion")
        return computersApiClient.upgradeComputer(ServerId(serverId), machineId, targetVersion)
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
                models = it.models.map { model -> model.id },
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
        val currentPlan = (subscription?.effectivePlan ?: server.plan).toBillingPlanConfig()
        val trialActive = currentPlan.id == "free" && isSettingsTrialActive()
        return DataResult.Content(
            SettingsBillingSnapshot(
                currentPlan = SettingsBillingCurrentPlanSnapshot(
                    planName = subscription?.displayName ?: currentPlan.displayName,
                    usageRows = currentPlanUsageRows(currentPlan, usage, trialActive),
                    trialActive = trialActive,
                    description = currentPlanDescription(currentPlan, subscription),
                    includedFeatures = currentPlanIncludedFeatures(currentPlan),
                    excludedFeatures = currentPlanExcludedFeatures(currentPlan)
                ),
                plans = settingsDisplayPlanConfigs.map { it.toBillingPlanSnapshot(currentPlan, trialActive, subscription) },
                notices = billingNotices(server, currentPlan, usage, trialActive, subscription),
                actions = billingActions(subscription)
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
        val agreement =
            if (canManageServer) {
                fetchServerAgreement(serverId, canManageServer)
            } else {
                DataResult.NoPermission(PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_server"))
            }
        val onboardingAgents =
            if (canManageServer) {
                fetchServerOnboardingAgents()
            } else {
                DataResult.NoPermission(PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_onboarding"))
            }
        val onboarding =
            if (canManageServer) {
                fetchServerOnboarding(serverId, (onboardingAgents as? DataResult.Content)?.data.orEmpty(), canManageServer)
            } else {
                DataResult.NoPermission(
                    PermissionBlocker(
                        code = "server_admin_required",
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
        val adminCount = admins.count { it.isPrivilegedServerRole() }
        val usageRows = mergeServerUsageRows(
            usage = (usage as? DataResult.Content)?.data,
            billing = (billing as? DataResult.Content)?.data
        )
        val pendingInvites = (invites as? DataResult.Content)?.data.orEmpty()
        val joinLinkRows = (joinLinks as? DataResult.Content)?.data.orEmpty()
        val agreementSnapshot = (agreement as? DataResult.Content)?.data
        val onboardingSnapshot = (onboarding as? DataResult.Content)?.data
        val translationSnapshot = (translation as? DataResult.Content)?.data
        val sidebarOrderSnapshot = (sidebarOrder as? DataResult.Content)?.data
        val sections = listOf(
            SettingsServerSectionSnapshot(
                key = "admins",
                title = Res.string.settings_section_admins_title.desc(),
                detail = adminsSectionDetail(members, adminCount),
                state = members.sectionState(admins.isEmpty()),
                errorPresentation = members.errorPresentationOrNull()
            ),
            SettingsServerSectionSnapshot(
                key = "billing",
                title = Res.string.settings_section_billing_title.desc(),
                detail = billingSectionDetail(server.plan, billing, usageRows.size),
                state = combineSectionState(billing, usage, usageRows.isEmpty()),
                errorPresentation = billing.errorPresentationOrNull() ?: usage.errorPresentationOrNull()
            ),
            SettingsServerSectionSnapshot(
                key = "pending_invites",
                title = Res.string.settings_section_pending_invites_title.desc(),
                detail = invitesSectionDetail(invites, pendingInvites.size),
                state = invites.sectionState(pendingInvites.isEmpty()),
                errorPresentation = invites.errorPresentationOrNull()
            ),
            SettingsServerSectionSnapshot(
                key = "join_links",
                title = Res.string.settings_section_join_links_title.desc(),
                detail = joinLinksSectionDetail(joinLinks, joinLinkRows.size),
                state = joinLinks.sectionState(joinLinkRows.isEmpty()),
                errorPresentation = joinLinks.errorPresentationOrNull()
            ),
            SettingsServerSectionSnapshot(
                key = "pre_join_agreement",
                title = Res.string.settings_section_pre_join_agreement_title.desc(),
                detail = agreementSectionDetail(agreement, agreementSnapshot),
                state = agreement.sectionState(agreementSnapshot == null),
                errorPresentation = agreement.errorPresentationOrNull()
            ),
            SettingsServerSectionSnapshot(
                key = "onboarding_agent",
                title = Res.string.settings_section_onboarding_title.desc(),
                detail = onboardingSectionDetail(onboarding, onboardingSnapshot),
                state = onboarding.sectionState(onboardingSnapshot == null),
                errorPresentation = onboarding.errorPresentationOrNull()
            ),
            SettingsServerSectionSnapshot(
                key = "member_permissions",
                title = Res.string.settings_section_member_permissions_title.desc(),
                detail = if (canManageServer) {
                    "Control whether members can see the human directory and counts."
                } else {
                    "Only server owners and admins can change member permissions."
                },
                state = if (canManageServer) SettingsSectionAvailability.Available else SettingsSectionAvailability.NoPermission
            ),
            SettingsServerSectionSnapshot(
                key = "translation",
                title = Res.string.settings_section_translation_title.desc(),
                detail = translationSectionDetail(translation, translationSnapshot),
                state = translation.sectionState(translationSnapshot == null),
                errorPresentation = translation.errorPresentationOrNull()
            ),
            SettingsServerSectionSnapshot(
                key = "sidebar_order",
                title = Res.string.settings_section_sidebar_order_title.desc(),
                detail = sidebarOrderSectionDetail(sidebarOrder, sidebarOrderSnapshot),
                state = sidebarOrder.sectionState(sidebarOrderSnapshot == null),
                errorPresentation = sidebarOrder.errorPresentationOrNull()
            ),
            SettingsServerSectionSnapshot(
                key = "danger",
                title = Res.string.settings_section_danger_zone_title.desc(),
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
                    hideHumansFromMembers = server.hideHumansFromMembers,
                    canSave = canManageServer
                ),
                sections = sections,
                admins = admins,
                usageRows = usageRows,
                pendingInvites = pendingInvites,
                joinLinks = joinLinkRows,
                agreement = agreementSnapshot,
                onboarding = onboardingSnapshot,
                translation = translationSnapshot,
                sidebarOrder = sidebarOrderSnapshot,
                systemChannels = fetchSystemChannel(canManageServer)
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
        val name = request.name?.trim()?.takeIf { it.isNotBlank() }
        if (name == null && request.hideHumansFromMembers == null) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val update = settingsUpdateServerProfile(server.id, name, request.hideHumansFromMembers)
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
        return settingsCreateServerInvite(server.id, email, request.role)
    }

    /**
     * Task #43: read-only list of server join links. Exposed on
     * [SettingsRepository] so callers that need a single link
     * (e.g. the Members page humans "+" invite flow) don't have to
     * fetch the full server settings snapshot.
     *
     * Permission: requires `canManageServer` (same gate as
     * [createServerInvite] / [createServerJoinLinkForInvite]). Without the
     * capability we return NoPermission so the dialog surfaces a
     * clear "no permission" error instead of an empty list.
     */
    override suspend fun listServerJoinLinks(serverId: String): DataResult<List<SettingsServerJoinLinkSnapshot>> {
        val server = selectedServer() ?: return serverRequired()
        if (server.id != serverId) {
            // Caller asked for a different server than the active session;
            // treat as server-required to keep state simple.
            return serverRequired()
        }
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_invites")
            )
        }
        return fetchServerJoinLinks(serverId)
    }

    /**
     * Task #43: thin pass-through to [createServerJoinLink] with the
     * permission gate. Required so the [SettingsRepository] interface
     * exposes the create operation to non-`SettingsServerActionRepository`
     * callers (Members page data source) without forcing them to
     * separately obtain a `SettingsServerActionRepository` handle.
     */
    override suspend fun createServerJoinLinkForInvite(
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
        val expectedSlug = server.slug?.trim().orEmpty()
        if (expectedSlug.isBlank() || request.confirmationName.trim() != expectedSlug) {
            return DataResult.Error(ScreenError(code = "server_delete_confirmation_mismatch", retryable = false))
        }
        return settingsDeleteServer(server.id)
    }

    override suspend fun updateServerAgreement(
        request: SettingsServerAgreementUpdateRequest
    ): DataResult<SettingsServerAgreementSnapshot> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_server")
            )
        }
        if (request.title.length > 160 || request.bodyMarkdown.length > PRE_JOIN_AGREEMENT_BODY_MAX_LENGTH) {
            return DataResult.Error(ScreenError(code = "settings_agreement_limit_exceeded", retryable = false))
        }
        return settingsUpdateServerAgreement(server.id, request, server.canManageServer)
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

    internal suspend fun getServerSidebarOrder(
        serverId: ServerId
    ): DataResult<SettingsServerSidebarOrderSnapshot> =
        fetchServerSidebarOrder(serverId.value)

    override suspend fun updateServerSidebarOrder(
        request: SettingsServerSidebarOrderUpdateRequest
    ): DataResult<SettingsServerSidebarOrderSnapshot> {
        val serverId = selectedServerId() ?: return serverRequired()
        return updateServerSidebarOrder(ServerId(serverId), request)
    }

    internal suspend fun updateServerSidebarOrder(
        serverId: ServerId,
        request: SettingsServerSidebarOrderUpdateRequest,
    ): DataResult<SettingsServerSidebarOrderSnapshot> {
        if (!request.hasAtLeastOneField()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsUpdateServerSidebarOrder(serverId.value, request)
    }

    override suspend fun getServerOrder(): DataResult<SettingsServerOrderSnapshot> =
        fetchServerOrder()

    override suspend fun updateServerOrder(
        request: SettingsServerOrderUpdateRequest
    ): DataResult<SettingsServerOrderSnapshot> {
        if (request.serverOrder.isEmpty()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return settingsUpdateServerOrder(request)
    }

    override suspend fun updateSystemChannelVisibility(
        allChannelId: String,
        hide: Boolean
    ): DataResult<SettingsServerActionResult> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_channels")
            )
        }
        if (allChannelId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val channelId = ChannelId(allChannelId)
        val repo = channelRepository
            ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        return when (
            val result = repo.updateChannel(channelId, if (hide) ChannelVisibility.Private else ChannelVisibility.Public)
        ) {
            is DataResult.Content -> DataResult.Content(SettingsServerActionResult(success = true))
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
    }

    override suspend fun restoreSystemChannel(): DataResult<SettingsServerActionResult> {
        val server = selectedServer() ?: return serverRequired()
        if (!server.canManageServer) {
            return DataResult.NoPermission(
                PermissionBlocker(code = "server_admin_required", requiredCapability = "manage_channels")
            )
        }
        // Caller is expected to look up #all channel id from systemChannels snapshot, then re-call updateSystemChannelVisibility(hide=false).
        return DataResult.Empty(EmptyReason.NoSettingsAvailable)
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

    override suspend fun getAppearanceSettings(): DataResult<SettingsAppearanceSnapshot> {
        val session = sessionStore.readSession() ?: return authRequired()
        return executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            source = "settings_appearance_account",
            warningLogger = warningLogger,
            block = { networkClient.settingsAccount(DataRequestPriority.Visible) },
            errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedAppearanceSettingsSnapshot(bodyText)
                    ?: throw IllegalStateException("Appearance settings response missing preferences")
            }
        ).also { result ->
            (result as? DataResult.Content)?.data?.let { snapshot ->
                onAppearanceSnapshot(session.user.id, snapshot)
            }
        }
    }

    override suspend fun updateAppearanceSettings(
        request: SettingsAppearanceUpdateRequest
    ): DataResult<SettingsAppearanceSnapshot> {
        val session = sessionStore.readSession() ?: return authRequired()
        return executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            source = "settings_appearance_update",
            warningLogger = warningLogger,
            block = {
                networkClient.updateSettingsAccount(
                    DataRequestPriority.Foreground,
                    buildSharedAppearanceUpdateBody(request)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedAppearanceSettingsSnapshot(bodyText)
                    ?: throw IllegalStateException("Appearance update response missing preferences")
            }
        ).also { result ->
            (result as? DataResult.Content)?.data?.let { snapshot ->
                onAppearanceSnapshot(session.user.id, snapshot)
            }
        }
    }

    override suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot> {
        val session = sessionStore.readSession() ?: return authRequired()
        val serverId = session.currentServerId
        val serverNotificationPreferences =
            serverId?.let { fetchServerNotificationPreferences(session.user.id, it) }
                ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val live = serverNotificationPreferences is DataResult.Content
        // Shown value: the live answer, else this user's last-known copy for this server. Never a default:
        // with neither, the mode is unknown and both controls stay unavailable (no value to flip).
        val shownPreferences =
            (serverNotificationPreferences as? DataResult.Content)?.data
                ?: serverId
                    ?.takeIf { serverNotificationPreferences !is DataResult.NoPermission }
                    ?.let { serverNotificationLastKnownStore?.read(session.user.id, it) }
        val serverNotificationMode = shownPreferences?.mode
        return DataResult.Content(
            SettingsMobileSnapshot(
                pushNotifications = SettingsMobileToggleSnapshot(
                    enabled = false,
                    available = true,
                    statusLabel = "Disabled",
                    detail = "Notifications can arrive while Raft is in the background. Use a test notification to verify this device."
                ),
                muteServer = SettingsMobileToggleSnapshot(
                    enabled = serverNotificationMode == ServerPushMode.None,
                    available = live,
                    statusLabel =
                        when (serverNotificationMode) {
                            null -> "Unavailable"
                            ServerPushMode.None -> "Muted"
                            else -> "Not muted"
                        },
                    detail =
                        when (serverNotificationPreferences) {
                            is DataResult.Content -> "Stops push notifications from the currently selected workspace."
                            is DataResult.NoPermission,
                            is DataResult.Error -> ""
                            is DataResult.Empty -> "Workspace notification settings are unavailable."
                        },
                    errorPresentation = serverNotificationPreferences.errorPresentationOrNull()
                ),
                serverNotificationMode =
                    serverNotificationMode?.toSettingsMobileSelectionSnapshot(
                        available = live,
                        mentionsSupported = shownPreferences.canonicalModeSupported,
                        detail = "Choose all messages, only direct mentions, or no push notifications for this workspace."
                    ) ?: unknownServerNotificationModeSelectionSnapshot(),
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
        val session = sessionStore.readSession() ?: return authRequired()
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
                errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
                mapper = { bodyText ->
                    parseSharedLanguageRegionAccountPreferences(bodyText)
                        ?: throw IllegalStateException("Language region account response missing preferences")
                }
            )) {
            is DataResult.Content -> {
                val snapshot =
                    result.data.toLanguageRegionSnapshot(
                        fetchServerTranslation(server.id),
                        server.canManageServer,
                    )
                persistUpdatedTimeFormat(
                    expectedAccountId = session.user.id,
                    request = request,
                    snapshot = snapshot,
                )
                DataResult.Content(snapshot)
            }
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
    }

    private suspend fun persistUpdatedTimeFormat(
        expectedAccountId: String,
        request: SettingsLanguageRegionPreferenceUpdateRequest,
        snapshot: SettingsLanguageRegionSnapshot,
    ) {
        if (request.preferredTimeFormat == null && !request.clearPreferredTimeFormat) return
        val effectiveTimeFormat =
            snapshot.timeFormat.selectedKey.takeIf { it == "12h" || it == "24h" }
                ?: return
        sessionStore.updateMetadata(expectedAccountId) { latest ->
            latest.copy(
                user =
                    latest.user.copy(
                        preferredTimeFormat =
                            effectiveTimeFormat.takeUnless { request.clearPreferredTimeFormat },
                        timeFormat = effectiveTimeFormat,
                    )
            )
        }
    }

    override suspend fun setServerNotificationMode(mode: ServerPushMode): DataResult<SettingsMobileSnapshot> {
        val session = sessionStore.readSession() ?: return authRequired()
        val serverId = session.currentServerId ?: return serverRequired()
        // Writes always start from the live value, never the last-known copy: offline the write fails here.
        val current = fetchServerNotificationPreferences(session.user.id, serverId)
        val preferences = (current as? DataResult.Content)?.data
            ?: return when (current) {
                is DataResult.Empty -> current
                is DataResult.Error -> current
                is DataResult.NoPermission -> current
                is DataResult.Content -> error("unreachable")
            }
        if (mode == ServerPushMode.Mentions && !preferences.canonicalModeSupported) {
            return DataResult.Error(
                ScreenError(code = "settings_server_notification_mentions_unsupported", retryable = false)
            )
        }
        return when (
            val result = settingsUpdateServerNotificationMode(
                serverId = serverId,
                mode = mode,
                canonicalModeSupported = preferences.canonicalModeSupported
            )
        ) {
            is DataResult.Content -> {
                serverNotificationLastKnownStore?.write(session.user.id, serverId, result.data)
                getMobileSettings()
            }
            is DataResult.Empty -> DataResult.Empty(EmptyReason.NoSettingsAvailable)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }
    }

    override suspend fun setServerMute(enabled: Boolean): DataResult<SettingsMobileSnapshot> =
        setServerNotificationMode(if (enabled) ServerPushMode.None else ServerPushMode.All)

    private suspend fun settingsComputers(
        serverId: String,
        canManageServer: Boolean
    ): DataResult<SettingsComputersSnapshot> =
        computersApiClient.machines(ServerId(serverId)).mapComputerResult { summary ->
            val computers = summary.machines.map { it.toSettingsComputerSnapshot() }
            if (computers.isEmpty()) {
                warningLogger("empty_items source=settings_machines stage=list_extract root=machines")
            }
            SettingsComputersSnapshot(
                computers = computers,
                canAddComputer = canManageServer,
                addComputerUnavailableReason = if (canManageServer) null else "server_admin_required",
                latestDaemonVersion = summary.latestDaemonVersion,
                latestComputerVersion = summary.latestComputerVersion
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

    private suspend fun settingsComputerAgents(machineId: String): DataResult<List<SettingsComputerAgentSnapshot>> {
        val result = executeSettings(
            emptyReason = EmptyReason.NoSettingsAvailable,
            block = { networkClient.settingsAgents(DataRequestPriority.Visible) },
            source = "settings_machine_agents",
            warningLogger = warningLogger,
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
            mapper = { bodyText ->
                val agents = parseSharedSettingsComputerAgents(bodyText = bodyText, machineId = machineId)
                    ?: throw IllegalStateException("Settings agents response missing agent list")
                agents
            }
        )
        if ((result as? DataResult.Content)?.data?.isEmpty() == true) {
            warningLogger("empty_items source=settings_machine_agents stage=list_extract contract=agents_array")
        }
        return result
    }

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
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsBillingRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsBillingSubscription(bodyText)
                    ?: settingsContractMismatch("settings_billing_subscription_contract_mismatch")
            }
        )

    private suspend fun fetchConnectedAppsSnapshot(
        canManage: Boolean,
        oneTimeClientSecret: SettingsConnectedAppSecretSnapshot? = null
    ): DataResult<SettingsConnectedAppsSnapshot> {
        val clients = fetchConnectedAppClients()
        val builtIn = fetchConnectedAppBuiltIns()
        val marketplace = fetchConnectedAppMarketplace()
        val clientRows =
            when (clients) {
                is DataResult.Content -> clients.data
                is DataResult.Empty -> emptyList()
                is DataResult.Error -> return DataResult.Error(clients.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(clients.blocker)
            }
        val builtInRows =
            when (builtIn) {
                is DataResult.Content -> builtIn.data
                is DataResult.Empty -> emptyList()
                is DataResult.Error -> return DataResult.Error(builtIn.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(builtIn.blocker)
            }
        val marketplaceRows =
            when (marketplace) {
                is DataResult.Content -> marketplace.data
                is DataResult.Empty -> emptyList()
                is DataResult.Error -> return DataResult.Error(marketplace.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(marketplace.blocker)
            }
        return DataResult.Content(
            buildSettingsConnectedAppsSnapshot(
                clients = clientRows,
                builtIn = builtInRows,
                marketplace = marketplaceRows,
                canManage = canManage,
                oneTimeClientSecret = oneTimeClientSecret
            )
        )
    }

    private suspend fun fetchConnectedAppClients(): DataResult<List<SettingsConnectedAppClientSnapshot>> =
        executeTypedSettings(
            source = "settings_connected_apps_clients",
            contractCode = "settings_connected_apps_clients_contract_mismatch",
            block = { networkClient.settingsConnectedAppClients(DataRequestPriority.Visible) },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsConnectedAppClients(bodyText)
                    ?: settingsContractMismatch("settings_connected_apps_clients_contract_mismatch")
            }
        )

    private suspend fun fetchConnectedAppBuiltIns(): DataResult<List<SettingsConnectedAppBuiltInSnapshot>> =
        executeTypedSettings(
            source = "settings_connected_apps_built_in",
            contractCode = "settings_connected_apps_built_in_contract_mismatch",
            block = { networkClient.settingsConnectedAppBuiltIns(DataRequestPriority.Visible) },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsConnectedAppBuiltIns(bodyText)
                    ?: settingsContractMismatch("settings_connected_apps_built_in_contract_mismatch")
            }
        )

    private suspend fun fetchConnectedAppMarketplace(): DataResult<List<SettingsConnectedAppMarketplaceSnapshot>> =
        executeTypedSettings(
            source = "settings_connected_apps_marketplace",
            contractCode = "settings_connected_apps_marketplace_contract_mismatch",
            block = { networkClient.settingsConnectedAppMarketplace(DataRequestPriority.Visible) },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsConnectedAppMarketplace(bodyText)
                    ?: settingsContractMismatch("settings_connected_apps_marketplace_contract_mismatch")
            }
        )

    private suspend fun settingsCreateConnectedApp(
        request: SettingsConnectedAppRegisterRequest
    ): DataResult<Pair<SettingsConnectedAppClientSnapshot, SettingsConnectedAppSecretSnapshot?>> =
        executeTypedSettings(
            source = "settings_connected_apps_client_create",
            contractCode = "settings_connected_apps_client_contract_mismatch",
            block = {
                networkClient.createSettingsConnectedAppClient(
                    DataRequestPriority.Foreground,
                    buildSharedSettingsConnectedAppRegisterBody(request)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsConnectedAppCreateResult(bodyText)
                    ?: settingsContractMismatch("settings_connected_apps_client_contract_mismatch")
            }
        )

    private suspend fun settingsUpdateConnectedApp(
        request: SettingsConnectedAppUpdateRequest
    ): DataResult<Unit> =
        executeTypedSettings(
            source = "settings_connected_apps_client_update",
            contractCode = "settings_connected_apps_client_contract_mismatch",
            block = {
                networkClient.updateSettingsConnectedAppClient(
                    DataRequestPriority.Foreground,
                    request.id,
                    buildSharedSettingsConnectedAppUpdateBody(request)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsConnectedAppClient(bodyText)
                    ?: settingsContractMismatch("settings_connected_apps_client_contract_mismatch")
                Unit
            }
        )

    private suspend fun settingsUploadConnectedAppLogo(
        request: SettingsConnectedAppLogoUploadRequest
    ): DataResult<Unit> =
        executeTypedSettings(
            source = "settings_connected_apps_client_logo_upload",
            contractCode = "settings_connected_apps_client_logo_contract_mismatch",
            block = {
                networkClient.uploadConnectedAppLogo(
                    DataRequestPriority.Foreground,
                    request.id,
                    request.file.toConnectedAppLogoMultipartFile()
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsConnectedAppClient(bodyText)
                    ?: settingsContractMismatch("settings_connected_apps_client_logo_contract_mismatch")
                Unit
            }
        )

    private suspend fun settingsClearConnectedAppLogo(clientRecordId: String): DataResult<Unit> =
        executeTypedSettings(
            source = "settings_connected_apps_client_logo_clear",
            contractCode = "settings_connected_apps_client_logo_contract_mismatch",
            block = { networkClient.clearSettingsConnectedAppLogo(DataRequestPriority.Foreground, clientRecordId) },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsConnectedAppClient(bodyText)
                    ?: settingsContractMismatch("settings_connected_apps_client_logo_contract_mismatch")
                Unit
            }
        )

    private suspend fun settingsDeleteConnectedApp(clientRecordId: String): DataResult<Unit> =
        executeTypedSettings(
            source = "settings_connected_apps_client_delete",
            contractCode = "settings_connected_apps_client_contract_mismatch",
            block = { networkClient.deleteSettingsConnectedAppClient(DataRequestPriority.Foreground, clientRecordId) },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsConnectedAppClient(bodyText)
                    ?: settingsContractMismatch("settings_connected_apps_client_contract_mismatch")
                Unit
            }
        )

    private suspend fun settingsRequestConnectedAppPublish(
        request: SettingsConnectedAppPublishRequest
    ): DataResult<Unit> =
        executeTypedSettings(
            source = "settings_connected_apps_client_request_publish",
            contractCode = "settings_connected_apps_publish_contract_mismatch",
            block = {
                networkClient.requestSettingsConnectedAppPublish(
                    DataRequestPriority.Foreground,
                    request.id,
                    buildSharedSettingsConnectedAppPublishBody(request)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsConnectedAppClient(bodyText)
                    ?: settingsContractMismatch("settings_connected_apps_publish_contract_mismatch")
                Unit
            }
        )

    private suspend fun settingsInstallMarketplaceApp(clientRecordId: String): DataResult<Unit> =
        executeTypedSettings(
            source = "settings_connected_apps_marketplace_install",
            contractCode = "settings_connected_apps_marketplace_install_contract_mismatch",
            block = { networkClient.installSettingsConnectedAppMarketplace(DataRequestPriority.Foreground, clientRecordId) },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsConnectedAppClient(bodyText)
                    ?: settingsContractMismatch("settings_connected_apps_marketplace_install_contract_mismatch")
                Unit
            }
        )

    private suspend fun settingsUninstallMarketplaceApp(clientRecordId: String): DataResult<Unit> =
        executeTypedSettings(
            source = "settings_connected_apps_marketplace_uninstall",
            contractCode = "settings_connected_apps_marketplace_uninstall_contract_mismatch",
            block = { networkClient.uninstallSettingsConnectedAppMarketplace(DataRequestPriority.Foreground, clientRecordId) },
            errorTextDecoder = HomeNetworkResponse::settingsConnectedAppsRouteErrorTextOrNull,
            mapper = { bodyText ->
                settingsRepositoryJson.decodeFromString(JsonObject.serializer(), bodyText)
                Unit
            }
        )

    private suspend fun DataResult<Unit>.reloadConnectedAppsSnapshot(
        canManage: Boolean
    ): DataResult<SettingsConnectedAppsSnapshot> =
        when (this) {
            is DataResult.Content -> fetchConnectedAppsSnapshot(canManage)
            is DataResult.Empty -> DataResult.Empty(reason)
            is DataResult.Error -> DataResult.Error(error)
            is DataResult.NoPermission -> DataResult.NoPermission(blocker)
        }

    private suspend fun fetchServerOnboarding(
        serverId: String,
        agents: List<SettingsServerOnboardingAgentSnapshot>,
        canSave: Boolean
    ): DataResult<SettingsServerOnboardingSnapshot> =
        executeTypedSettings(
            source = "settings_server_onboarding_settings",
            contractCode = "settings_server_onboarding_contract_mismatch",
            block = { networkClient.settingsServerOnboarding(DataRequestPriority.Visible, serverId) },
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsServerOnboarding(bodyText)
                    ?.copy(agents = agents, canSave = canSave)
                    ?: settingsContractMismatch("settings_server_onboarding_contract_mismatch")
            }
        )

    private suspend fun fetchServerOnboardingAgents(): DataResult<List<SettingsServerOnboardingAgentSnapshot>> =
        executeTypedSettings(
            source = "settings_server_onboarding_agents",
            contractCode = "settings_server_onboarding_agents_contract_mismatch",
            block = { networkClient.settingsAgents(DataRequestPriority.Visible) },
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsServerOnboardingAgents(bodyText)
                    ?: settingsContractMismatch("settings_server_onboarding_agents_contract_mismatch")
            }
        )

    private suspend fun fetchServerAgreement(
        serverId: String,
        canSave: Boolean
    ): DataResult<SettingsServerAgreementSnapshot> =
        executeTypedSettings(
            source = "settings_server_agreement",
            contractCode = "settings_server_agreement_contract_mismatch",
            block = { networkClient.settingsServerAgreement(DataRequestPriority.Visible, serverId) },
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsServerAgreement(bodyText, canSave)
                    ?: settingsContractMismatch("settings_server_agreement_contract_mismatch")
            }
        )

    private suspend fun settingsUpdateServerAgreement(
        serverId: String,
        request: SettingsServerAgreementUpdateRequest,
        canSave: Boolean
    ): DataResult<SettingsServerAgreementSnapshot> =
        executeTypedSettings(
            source = "settings_server_agreement_update",
            contractCode = "settings_server_agreement_contract_mismatch",
            block = {
                networkClient.updateSettingsServerAgreement(
                    DataRequestPriority.Foreground,
                    serverId,
                    buildSharedSettingsServerAgreementUpdateBody(request)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsServerAgreement(bodyText, canSave)
                    ?: settingsContractMismatch("settings_server_agreement_contract_mismatch")
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
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsServerOnboarding(bodyText)
                    ?.copy(canSave = true)
                    ?: settingsContractMismatch("settings_server_onboarding_contract_mismatch")
            }
        )

    private suspend fun fetchServerTranslation(serverId: String): DataResult<SettingsServerTranslationSnapshot> =
        executeTypedSettings(
            source = "settings_server_translation_settings",
            contractCode = "settings_server_translation_contract_mismatch",
            block = { networkClient.settingsServerTranslation(DataRequestPriority.Visible, serverId) },
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsSidebarOrder(bodyText)
                    ?: settingsContractMismatch("settings_server_sidebar_order_contract_mismatch")
            }
        )

    private suspend fun fetchServerOrder(): DataResult<SettingsServerOrderSnapshot> =
        executeTypedSettings(
            source = "settings_server_order",
            contractCode = "settings_server_order_contract_mismatch",
            block = { networkClient.settingsServerOrder(DataRequestPriority.Visible) },
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsServerOrder(bodyText)
                    ?: settingsContractMismatch("settings_server_order_contract_mismatch")
            }
        )

    private suspend fun settingsUpdateServerOrder(
        request: SettingsServerOrderUpdateRequest
    ): DataResult<SettingsServerOrderSnapshot> =
        executeTypedSettings(
            source = "settings_server_order_update",
            contractCode = "settings_server_order_contract_mismatch",
            block = {
                networkClient.updateSettingsServerOrder(
                    DataRequestPriority.Foreground,
                    buildSharedSettingsServerOrderUpdateBody(request)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsServerOrder(bodyText)
                    ?: settingsContractMismatch("settings_server_order_contract_mismatch")
            }
        )

    private suspend fun fetchServerInvites(serverId: String): DataResult<List<SettingsServerInviteSnapshot>> =
        executeTypedSettings(
            source = "settings_server_invites",
            contractCode = "settings_server_invites_contract_mismatch",
            block = { networkClient.settingsServerInvites(DataRequestPriority.Visible, serverId) },
            errorTextDecoder = HomeNetworkResponse::settingsServerSectionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsInviteRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsJoinLinks(bodyText)
                    ?: settingsContractMismatch("settings_server_join_links_contract_mismatch")
            }
        )

    private suspend fun fetchSystemChannel(canManage: Boolean): SettingsServerSystemChannelSnapshot? {
        if (!canManage) return null
        return runCatching {
            executeTypedSettings(
                source = "settings_system_channel",
                contractCode = "settings_system_channel_contract_mismatch",
                block = { networkClient.settingsServerChannels(DataRequestPriority.Visible) },
                mapper = { bodyText -> parseSharedSystemChannelSnapshot(bodyText) }
            )
        }.getOrNull()?.let { result ->
            when (result) {
                is DataResult.Content -> result.data
                else -> null
            }
        }
    }

    /**
     * Realtime refresh (`notification_prefs:updated` with a server mode): refetches [serverId]'s workspace
     * notification preference for the signed-in account at background priority. The fetch writes the
     * last-known copy on success. A session that no longer belongs to [expectedUserId] (the account the
     * event arrived for) is not refreshed.
     */
    suspend fun refreshServerNotificationPreferences(
        expectedUserId: String,
        serverId: String,
    ): DataResult<SettingsServerNotificationPreferences> {
        val session = sessionStore.readSession() ?: return authRequired()
        val cleanServerId = serverId.trim().takeIf { it.isNotBlank() }
        if (session.user.id != expectedUserId || cleanServerId == null) {
            return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        }
        return fetchServerNotificationPreferences(session.user.id, cleanServerId, DataRequestPriority.Background)
    }

    private suspend fun fetchServerNotificationPreferences(
        userId: String,
        serverId: String,
        priority: DataRequestPriority = DataRequestPriority.Visible,
    ): DataResult<SettingsServerNotificationPreferences> =
        executeTypedSettings(
            source = "settings_server_notification_settings",
            contractCode = "settings_server_notification_settings_contract_mismatch",
            block = { networkClient.settingsNotificationSettings(priority, serverId) },
            errorTextDecoder = HomeNetworkResponse::settingsServerNotificationRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsServerNotificationSettings(bodyText, warningLogger)
                    ?: settingsContractMismatch("settings_server_notification_settings_contract_mismatch")
            }
        ).also { result ->
            (result as? DataResult.Content)?.data?.let { preferences ->
                serverNotificationLastKnownStore?.write(userId, serverId, preferences)
            }
        }

    private suspend fun settingsUpdateServerProfile(
        serverId: String,
        name: String?,
        hideHumansFromMembers: Boolean?
    ): DataResult<Unit> =
        executeTypedSettings(
            source = "settings_server_profile_update",
            contractCode = "settings_server_profile_contract_mismatch",
            block = {
                networkClient.updateSettingsServerProfile(
                    DataRequestPriority.Foreground,
                    serverId,
                    buildSharedSettingsServerProfileUpdateBody(name, hideHumansFromMembers)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
            mapper = { bodyText ->
                if (!parseSharedSettingsServerProfileUpdated(bodyText)) {
                    settingsContractMismatch("settings_server_profile_contract_mismatch")
                }
                Unit
            }
        )

    private suspend fun settingsCreateServerInvite(
        serverId: String,
        email: String,
        role: String? = null
    ): DataResult<SettingsServerActionResult> =
        executeTypedSettings(
            source = "settings_server_invite_create",
            contractCode = "settings_server_invite_contract_mismatch",
            block = {
                networkClient.createSettingsServerInvite(
                    DataRequestPriority.Foreground,
                    serverId,
                    buildSharedSettingsServerInviteCreateBody(email, role)
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsInviteRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsInviteRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsMemberMutationErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsServerActionRouteErrorTextOrNull,
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
            errorTextDecoder = HomeNetworkResponse::settingsAccountRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedLanguageRegionAccountPreferences(bodyText)
                    ?: throw IllegalStateException("Language region account response missing preferences")
            }
        )

    /**
     * The server level is written through the unified `PUT notification-levels` (slock
     * docs/contracts/notifications.md §2.2); a server without that route (404) still takes the
     * legacy `PATCH servers/:id/notification-settings`, which writes the same value.
     */
    private suspend fun settingsUpdateServerNotificationMode(
        serverId: String,
        mode: ServerPushMode,
        canonicalModeSupported: Boolean
    ): DataResult<SettingsServerNotificationPreferences> {
        if (canonicalModeSupported) {
            val level = RaftNotificationLevel.fromWire(mode.wireName)
            if (level != null) {
                val unified =
                    executeTypedSettings(
                        source = "settings_server_notification_level_update",
                        contractCode = "settings_server_notification_level_contract_mismatch",
                        block = {
                            networkClient.request(
                                priority = DataRequestPriority.Foreground,
                                path = "notification-levels",
                                method = "PUT",
                                serverIdOverride = ServerId(serverId),
                                bodyText =
                                    buildRaftNotificationLevelBody(
                                        RaftNotificationLevelScope.Server,
                                        serverId,
                                        RaftNotificationLevelSetting.of(level)
                                    )
                            )
                        },
                        mapper = { bodyText ->
                            val update =
                                parseRaftNotificationLevelResponse(bodyText)
                                    ?.takeIf { it.scope == RaftNotificationLevelScope.Server && it.id == serverId }
                                    ?: settingsContractMismatch("settings_server_notification_level_response_contract_mismatch")
                            val saved =
                                ServerPushMode.fromWire(update.level.wireName)
                                    ?: settingsContractMismatch("settings_server_notification_level_response_contract_mismatch")
                            RaftNotificationLevel.fromWire(saved.wireName)?.let { savedLevel ->
                                RaftNotificationLevelStore.applyServerLevel(serverId, savedLevel, update.prefsVersion)
                            }
                            SettingsServerNotificationPreferences(
                                mode = saved,
                                pushPreviewMode = PushPreviewMode.Full,
                                legacyMuted = saved == ServerPushMode.None,
                                canonicalModeSupported = true
                            )
                        }
                    )
                // Only a bare 404 maps to this code (a server that predates the unified route); a
                // response that does not parse uses the `_response_` code and is not retried as legacy.
                val routeMissing =
                    unified is DataResult.Error && unified.error.code == "settings_server_notification_level_contract_mismatch"
                if (!routeMissing) return unified
            }
        }
        return settingsUpdateLegacyServerNotificationMode(serverId, mode, canonicalModeSupported)
    }

    private suspend fun settingsUpdateLegacyServerNotificationMode(
        serverId: String,
        mode: ServerPushMode,
        canonicalModeSupported: Boolean
    ): DataResult<SettingsServerNotificationPreferences> =
        executeTypedSettings(
            source = "settings_server_notification_settings_update",
            contractCode = "settings_server_notification_settings_contract_mismatch",
            block = {
                networkClient.updateSettingsNotificationSettings(
                    DataRequestPriority.Foreground,
                    serverId,
                    if (canonicalModeSupported) {
                        buildSharedSettingsServerNotificationModeBody(mode)
                    } else {
                        buildSharedSettingsLegacyServerMuteBody(mode == ServerPushMode.None)
                    }
                )
            },
            errorTextDecoder = HomeNetworkResponse::settingsServerNotificationRouteErrorTextOrNull,
            mapper = { bodyText ->
                parseSharedSettingsServerNotificationSettings(bodyText, warningLogger)
                    ?: settingsContractMismatch("settings_server_notification_settings_contract_mismatch")
            }
        )

    private suspend fun <T> executeTypedSettings(
        source: String,
        contractCode: String,
        block: suspend () -> HomeNetworkResponse,
        errorTextDecoder: (HomeNetworkResponse) -> String? = { null },
        mapper: (String) -> T
    ): DataResult<T> =
        executeTypedSettingsWithDiagnostics(
            source = source,
            contractCode = contractCode,
            block = block,
            diagnosticsLogger = warningLogger,
            errorTextDecoder = errorTextDecoder,
            mapper = mapper
        )

    private suspend fun <T> executeSettings(
        emptyReason: EmptyReason,
        block: suspend () -> HomeNetworkResponse,
        emptyWhen: (T) -> Boolean = { false },
        source: String? = null,
        warningLogger: (String) -> Unit = this.warningLogger,
        errorTextDecoder: (HomeNetworkResponse) -> String? = { null },
        mapper: (String) -> T
    ): DataResult<T> =
        executeSettingsWithDiagnostics(
            emptyReason = emptyReason,
            block = block,
            emptyWhen = emptyWhen,
            source = source,
            warningLogger = warningLogger,
            errorTextDecoder = errorTextDecoder,
            mapper = mapper
        )
}

private data class SettingsContractMismatch(val code: String) : IllegalArgumentException(code)

private fun settingsContractMismatch(code: String): Nothing = throw SettingsContractMismatch(code)

private suspend fun <T> executeTypedSettingsWithDiagnostics(
    source: String,
    contractCode: String,
    block: suspend () -> HomeNetworkResponse,
    diagnosticsLogger: (String) -> Unit = {},
    errorTextDecoder: (HomeNetworkResponse) -> String? = { null },
    mapper: (String) -> T
): DataResult<T> {
    var statusCode: Int? = null
    return try {
        val response = block()
        statusCode = response.code
        if (!response.isSuccessful) {
            val serverMessage = errorTextDecoder(response)
            if (response.code == 404 && serverMessage == null) {
                return DataResult.Error(ScreenError(code = contractCode, retryable = false))
            }
            return response.toSettingsDataError(serverMessage)
        }
        val body = response.bodyText.orEmpty()
        if (body.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val responseBytes = body.encodeToByteArray().size
        diagnosticsLogger(
            "settings_network_body_received source=$source code=${response.code} " +
                "responseBytes=$responseBytes hasBody=${response.bodyText != null}"
        )
        val parseStartedAt = TimeSource.Monotonic.markNow()
        diagnosticsLogger(
            "settings_parse_start source=$source responseBytes=$responseBytes " +
                "dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
        )
        val result =
            withContext(RaftJsonParseDispatcher) {
                mapper(body)
            }
        val dataResult = DataResult.Content(result)
        diagnosticsLogger(
            "settings_parse_result source=$source ${dataResult.settingsDiagnosticsResult()} " +
                "durationMs=${parseStartedAt.elapsedNow().inWholeMilliseconds} " +
                "dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
        )
        dataResult
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

private suspend fun <T> executeSettingsWithDiagnostics(
    emptyReason: EmptyReason,
    block: suspend () -> HomeNetworkResponse,
    emptyWhen: (T) -> Boolean = { false },
    source: String? = null,
    warningLogger: (String) -> Unit = {},
    errorTextDecoder: (HomeNetworkResponse) -> String? = { null },
    mapper: (String) -> T
): DataResult<T> {
    var statusCode: Int? = null
    return try {
        val response = block()
        statusCode = response.code
        if (!response.isSuccessful) return response.toSettingsDataError(errorTextDecoder(response))
        val body = response.bodyText.orEmpty()
        if (body.isBlank()) return DataResult.Empty(emptyReason)
        val sourceLabel = source ?: "settings"
        val responseBytes = body.encodeToByteArray().size
        warningLogger(
            "settings_network_body_received source=$sourceLabel code=${response.code} " +
                "responseBytes=$responseBytes hasBody=${response.bodyText != null}"
        )
        val parseStartedAt = TimeSource.Monotonic.markNow()
        warningLogger(
            "settings_parse_start source=$sourceLabel responseBytes=$responseBytes " +
                "dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
        )
        val result =
            withContext(RaftJsonParseDispatcher) {
                mapper(body)
            }
        val dataResult = if (emptyWhen(result)) DataResult.Empty(emptyReason) else DataResult.Content(result)
        warningLogger(
            "settings_parse_result source=$sourceLabel ${dataResult.settingsDiagnosticsResult()} " +
                "durationMs=${parseStartedAt.elapsedNow().inWholeMilliseconds} " +
                "dispatcher=$RAFT_JSON_PARSE_DISPATCHER_LABEL"
        )
        dataResult
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

private fun DataResult<*>.settingsDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "result=content"
        is DataResult.Empty -> "result=empty:${reason.name}"
        is DataResult.Error -> "result=error:${error.code}"
        is DataResult.NoPermission -> "result=no_permission:${blocker.code}"
    }

private fun <T> HomeNetworkResponse.toSettingsDataError(serverMessage: String? = null): DataResult<T> {
    secondFactorStepUpChallengeOrNull()?.let { challenge ->
        return DataResult.Error(
            ScreenError(
                code = SECOND_FACTOR_STEP_UP_REQUIRED_CODE,
                retryable = false,
                serverMessage = challenge.serverMessage ?: serverMessage,
                serverCode = SECOND_FACTOR_STEP_UP_REQUIRED_CODE,
                secondFactorStepUp = challenge
            )
        )
    }
    return when (code) {
        401 -> authRequired(serverMessage)
        403 ->
            DataResult.NoPermission(
                PermissionBlocker(
                    code = "server_forbidden",
                    requiredCapability = "server_access",
                    serverMessage = serverMessage
                )
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
private data class SettingsAccountRouteErrorDto(val error: String? = null)

@Serializable
private data class SettingsConnectedAppsRouteErrorDto(val error: String? = null)

@Serializable
private data class SettingsServerSectionRouteErrorDto(val error: String? = null)

@Serializable
private data class SettingsBillingRouteErrorDto(val error: String? = null)

@Serializable
private data class SettingsServerActionRouteErrorDto(val error: String? = null)

@Serializable
private data class SettingsServerNotificationRouteErrorDto(val error: String? = null)

@Serializable
private data class SettingsInviteRouteErrorDto(val error: String? = null)

@Serializable
private data class SettingsMemberMutationErrorDto(val error: String? = null)

internal fun HomeNetworkResponse.settingsAccountRouteErrorTextOrNull(): String? =
    settingsErrorTextOrNull(SettingsAccountRouteErrorDto.serializer()) { it.error }

internal fun HomeNetworkResponse.settingsConnectedAppsRouteErrorTextOrNull(): String? =
    settingsErrorTextOrNull(SettingsConnectedAppsRouteErrorDto.serializer()) { it.error }

internal fun HomeNetworkResponse.settingsServerSectionRouteErrorTextOrNull(): String? =
    settingsErrorTextOrNull(SettingsServerSectionRouteErrorDto.serializer()) { it.error }

internal fun HomeNetworkResponse.settingsBillingRouteErrorTextOrNull(): String? =
    settingsErrorTextOrNull(SettingsBillingRouteErrorDto.serializer()) { it.error }

internal fun HomeNetworkResponse.settingsServerActionRouteErrorTextOrNull(): String? =
    settingsErrorTextOrNull(SettingsServerActionRouteErrorDto.serializer()) { it.error }

internal fun HomeNetworkResponse.settingsServerNotificationRouteErrorTextOrNull(): String? =
    settingsErrorTextOrNull(SettingsServerNotificationRouteErrorDto.serializer()) { it.error }

private fun HomeNetworkResponse.settingsInviteRouteErrorTextOrNull(): String? =
    settingsErrorTextOrNull(SettingsInviteRouteErrorDto.serializer()) { it.error }

private fun HomeNetworkResponse.settingsMemberMutationErrorTextOrNull(): String? =
    settingsErrorTextOrNull(SettingsMemberMutationErrorDto.serializer()) { it.error }

private fun <T> HomeNetworkResponse.settingsErrorTextOrNull(
    serializer: kotlinx.serialization.KSerializer<T>,
    text: (T) -> String?
): String? {
    val payload = bodyText?.takeIf { it.isNotBlank() }
        ?: errorBodyText?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching {
        text(settingsRepositoryJson.decodeFromString(serializer, payload))
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun authRequired(): DataResult.NoPermission =
    authSessionRequiredResult()

private fun authRequired(serverMessage: String?): DataResult.NoPermission =
    authSessionRequiredResult(serverMessage)

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
        comingSoon = comingSoon,
        badgeLabel =
            when {
                id == currentPlan.id -> "Current"
                comingSoon -> "Coming soon"
                else -> null
            }
    )

private fun currentPlanDescription(
    plan: SettingsBillingPlanConfig,
    subscription: SettingsBillingSubscriptionSnapshot?
): String =
    when {
        plan.id == "founder" -> "Grandfathered unlimited access."
        plan.id == "pro" && subscription?.status == "active" -> "For teams scaling agent collaboration."
        plan.id == "pro" -> "Pro workspace capacity and collaboration controls."
        plan.id == "free" -> "Start building with agents."
        else -> "Workspace plan details."
    }

private fun currentPlanIncludedFeatures(plan: SettingsBillingPlanConfig): List<String> =
    when (plan.id) {
        "founder" ->
            listOf("Everything without any limitations")
        "pro", "max" ->
            listOf(
                "Unlimited message history",
                "Higher file upload limits",
                "Use Joint Channels",
                "More professional features coming soon"
            )
        else ->
            listOf(
                "Channels",
                "Tasks",
                "Agents on your own computers",
                "Agent reminders",
                "Basic observability",
                "30 days of message history",
                "100 MB file uploads/month",
                "Use Joint Channels (each Joint Channel has 2 free-server slots)"
            )
    }

private fun currentPlanExcludedFeatures(plan: SettingsBillingPlanConfig): List<String> =
    if (plan.id == "free") {
        listOf(
            "Higher file upload limits",
            "Unlimited message history",
            "More professional features coming soon"
        )
    } else {
        emptyList()
    }

private fun billingNotices(
    server: StoredAuthServer,
    plan: SettingsBillingPlanConfig,
    usage: SettingsServerUsageSnapshot,
    trialActive: Boolean,
    subscription: SettingsBillingSubscriptionSnapshot?
): List<SettingsBillingNoticeSnapshot> =
    buildList {
        server.planDowngradedAt?.takeIf { it.isNotBlank() }?.let { downgradedAt ->
            add(
                SettingsBillingNoticeSnapshot(
                    key = "downgrade",
                    title = "Plan Downgraded",
                    detail = "Free-plan grace period started $downgradedAt. Resources above free limits may be stopped after the grace period.",
                    tone = "warning",
                    rows = downgradeUsageRows(usage)
                )
            )
        }
        if (plan.id == "free" && trialActive) {
            add(
                SettingsBillingNoticeSnapshot(
                    key = "trial",
                    title = "Final Trial Period",
                    detail = "All features are currently unlocked during the free trial.",
                    tone = "trial"
                )
            )
        }
        if (plan.id == "founder") {
            add(
                SettingsBillingNoticeSnapshot(
                    key = "founder",
                    title = "Founder Plan",
                    detail = "This workspace keeps grandfathered unlimited access.",
                    tone = "success"
                )
            )
        }
        if (subscription?.cancelAtPeriodEnd == true) {
            add(
                SettingsBillingNoticeSnapshot(
                    key = "cancel-scheduled",
                    title = "Cancellation Scheduled",
                    detail = subscription.currentPeriodEnd
                        ?.let { "Pro capacity stays active until $it, then returns to the free plan." }
                        ?: "Pro capacity stays active until the current billing period ends.",
                    tone = "warning"
                )
            )
        }
    }

private fun downgradeUsageRows(usage: SettingsServerUsageSnapshot): List<SettingsPlanUsageSnapshot> =
    buildList {
        val freeLimits = hobbyPlanLimits
        if (usage.agents > freeLimits.maxAgents) {
            add(SettingsPlanUsageSnapshot("Agents over limit", "${usage.agents - freeLimits.maxAgents} (${usage.agents}/${freeLimits.maxAgents})"))
        }
        if (usage.machines > freeLimits.maxMachines) {
            add(SettingsPlanUsageSnapshot("Computers over limit", "${usage.machines - freeLimits.maxMachines} (${usage.machines}/${freeLimits.maxMachines})"))
        }
        if (freeLimits.maxChannels != -1 && usage.channels > freeLimits.maxChannels) {
            add(SettingsPlanUsageSnapshot("Channels over limit", "${usage.channels - freeLimits.maxChannels} (${usage.channels}/${freeLimits.maxChannels})"))
        }
        if (isEmpty()) {
            add(SettingsPlanUsageSnapshot("Free limits", "All resources are within free limits."))
        }
    }

private fun billingActions(subscription: SettingsBillingSubscriptionSnapshot?): List<SettingsBillingActionSnapshot> {
    val canManage = subscription?.canManageBilling == true
    val billingEnabled = subscription?.stripeConfigured == true
    val status = subscription?.status
    val cancelAtPeriodEnd = subscription?.cancelAtPeriodEnd == true
    val disabledReason =
        when {
            subscription == null -> "Billing controls are unavailable."
            !canManage -> "Only server owners can change billing."
            !billingEnabled -> "Paid plans are not available yet."
            else -> ""
        }
    return listOf(
        SettingsBillingActionSnapshot(
            key = "portal",
            label = "Billing portal",
            detail = disabledReason.ifBlank { "Open Stripe billing portal." },
            enabled = canManage && billingEnabled && status != null
        ),
        SettingsBillingActionSnapshot(
            key = "cancel",
            label = if (cancelAtPeriodEnd) "Cancellation scheduled" else "Cancel subscription",
            detail = disabledReason.ifBlank { "Cancel the whole Pro subscription at period end." },
            enabled = canManage && billingEnabled && status == "active" && !cancelAtPeriodEnd,
            style = "danger"
        )
    )
}

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
    members: List<build.raft.app.model.MemberProfile>
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

internal fun ComputerSummary.toSettingsComputerSnapshot(): SettingsComputerSnapshot =
    SettingsComputerSnapshot(
        id = id,
        name = name,
        daemonVersion = daemonVersion.orEmpty(),
        computerVersion = computerVersion,
        isComputer = isComputer,
        online = status == ComputerStatus.Online,
        hostname = hostname,
        platform = os,
        description = description,
        lastSeenLabel = lastHeartbeat,
        runtimes = runtimes,
        createdAt = createdAt,
        lastHeartbeat = lastHeartbeat,
        apiKeyPrefix = apiKeyPrefix,
        statusVersion = statusVersion,
        creatorUserId = creatorUserId,
        attachedByCurrentUser = attachedByCurrentUser,
        agentCount = agentCount,
        upgradeAvailable = isComputer && computerUpgradeAvailable == true,
        broadcastPolicy = computerBroadcastPolicy,
        upgradeRequest = upgradeRequest,
        remoteUpgradeSupported = remoteUpgradeSupported,
        versionGate = versionGate,
        latestComputerVersion = latestComputerVersion,
        latestComputerReleaseNotes = latestComputerReleaseNotes,
        creator = creator,
        agentScopes = agentScopes,
        osRelease = osRelease,
        runtimeVersions = runtimeVersions,
        diskStatus = diskStatus?.let { SettingsComputerSizeStatusSnapshot(it.availableBytes, it.totalBytes) },
        memoryStatus = memoryStatus?.let { SettingsComputerSizeStatusSnapshot(it.availableBytes, it.totalBytes) }
    )

private fun ComputerWorkspaceSummary.toSettingsWorkspaceSnapshot(): SettingsComputerWorkspaceSnapshot =
    SettingsComputerWorkspaceSnapshot(
        id = directoryName,
        name = agentName?.takeIf { it.isNotBlank() } ?: directoryName,
        path = "~/.slock/agents/$directoryName/",
        statusLabel = status,
        lastActiveLabel = lastModified,
        totalSizeBytes = totalSizeBytes,
        fileCount = fileCount
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
                            title = Res.string.settings_section_computer_detail_title.desc(),
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
        is DataResult.Error ->
            SettingsSectionErrorSnapshot(
                code = error.code,
                retryable = error.retryable,
                screenError = error
            )
        is DataResult.NoPermission ->
            SettingsSectionErrorSnapshot(
                code = blocker.code,
                retryable = false,
                permissionBlocker = blocker
            )
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
        is DataResult.Content -> if (count == 0) "No owners or admins yet." else "$count owner/admin member${if (count == 1) "" else "s"}"
        is DataResult.Empty -> "No admins yet."
        is DataResult.NoPermission -> "You do not have permission to view server members."
        is DataResult.Error -> ""
    }

private fun SettingsServerAdminSnapshot.isPrivilegedServerRole(): Boolean {
    val normalizedRole = role?.lowercase().orEmpty()
    return normalizedRole == "owner" || normalizedRole == "admin"
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
        is DataResult.Error -> ""
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
        is DataResult.NoPermission -> "Only server owners and admins can update onboarding settings."
        is DataResult.Error -> ""
    }

private fun agreementSectionDetail(
    result: DataResult<SettingsServerAgreementSnapshot>,
    agreement: SettingsServerAgreementSnapshot?
): String =
    when (result) {
        is DataResult.Content -> agreement?.detail ?: "Pre-join agreement loaded."
        is DataResult.Empty -> "No pre-join agreement was returned."
        is DataResult.NoPermission -> "Only server owners and admins can manage the pre-join agreement."
        is DataResult.Error -> ""
    }

private fun translationSectionDetail(
    result: DataResult<SettingsServerTranslationSnapshot>,
    translation: SettingsServerTranslationSnapshot?
): String =
    when (result) {
        is DataResult.Content -> translation?.detail ?: "Translation settings loaded."
        is DataResult.Empty -> "No translation settings were returned."
        is DataResult.NoPermission -> "Only server owners and admins can update translation settings."
        is DataResult.Error -> ""
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
        is DataResult.Error -> ""
    }

private fun invitesSectionDetail(result: DataResult<List<SettingsServerInviteSnapshot>>, count: Int): String =
    when (result) {
        is DataResult.Content -> if (count == 0) "No pending invites." else "$count pending invite${if (count == 1) "" else "s"}"
        is DataResult.Empty -> "No pending invites."
        is DataResult.NoPermission -> "Only server owners and admins can view invites."
        is DataResult.Error -> ""
    }

private fun joinLinksSectionDetail(result: DataResult<List<SettingsServerJoinLinkSnapshot>>, count: Int): String =
    when (result) {
        is DataResult.Content -> if (count == 0) "No active join links." else "$count join link${if (count == 1) "" else "s"}"
        is DataResult.Empty -> "No active join links."
        is DataResult.NoPermission -> "Only server owners and admins can view join links."
        is DataResult.Error -> ""
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
        jointChannelSortMode != null ||
        dmSortMode != null ||
        pinnedSortMode != null ||
        pinned != null ||
        pinnedChannelIds != null ||
        pinnedAgentIds != null ||
        pinnedOrder != null ||
        hiddenDmIds != null ||
        channelPanelTabOrder != null ||
        agentPanelTabOrder != null ||
        // task #512: section fields make a PATCH meaningful on their own
        // (sectionsVersion alone is OCC metadata, not a payload).
        customSections != null ||
        sectionOrder != null ||
        sectionPlacements != null

@Serializable
data class AccountProfileUpdateRequestBody(
    val displayName: String,
    val handle: String,
    val email: String
)

@Serializable
data class SettingsAppearanceUpdateRequestBody(
    val preferredMessageBodyFontSize: String
)

@Serializable
data class AccountPasswordChangeRequestBody(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
data class AccountProviderLinkStartRequestBody(
    val returnTo: String? = null
)

fun buildSharedAccountProfileUpdateBody(request: AccountProfileUpdateRequest): String =
    settingsRepositoryJson.encodeToString(
        AccountProfileUpdateRequestBody(
            displayName = request.displayName,
            handle = request.handle,
            email = request.email
        )
    )

/**
 * slock `routes/auth.ts` `PATCH /me` → `parseShareUsageData`: a boolean (null would mean "back to not
 * chosen", which this client never sends).
 */
@Serializable
data class ShareUsageDataUpdateRequestBody(
    val shareUsageData: Boolean
)

fun buildSharedShareUsageDataUpdateBody(enabled: Boolean): String =
    settingsRepositoryJson.encodeToString(ShareUsageDataUpdateRequestBody(shareUsageData = enabled))

fun buildSharedAppearanceUpdateBody(request: SettingsAppearanceUpdateRequest): String =
    settingsRepositoryJson.encodeToString(
        SettingsAppearanceUpdateRequestBody(
            preferredMessageBodyFontSize = normalizeSettingsMessageBodyFontSize(request.preferredMessageBodyFontSize)
        )
    )

fun buildSharedAccountPasswordChangeBody(request: AccountPasswordChangeRequest): String =
    settingsRepositoryJson.encodeToString(
        AccountPasswordChangeRequestBody(
            currentPassword = request.currentPassword,
            newPassword = request.newPassword
        )
    )

fun buildSharedAccountProviderLinkStartBody(request: AccountProviderLinkStartRequest): String =
    settingsRepositoryJson.encodeToString(
        AccountProviderLinkStartRequestBody(
            returnTo = request.returnTo.takeIf { it.isNotBlank() }
        )
    )

private fun PlatformFile.toAccountAvatarMultipartFile(): MultipartFile =
    MultipartFile(
        name = "avatar",
        fileName = fileName,
        mimeType = mimeType ?: "application/octet-stream",
        bytes = bytes,
        fileRef = fileRef,
        sizeBytes = sizeBytes
    )

private fun PlatformFile.toConnectedAppLogoMultipartFile(): MultipartFile =
    MultipartFile(
        name = "logo",
        fileName = fileName,
        mimeType = mimeType ?: "application/octet-stream",
        bytes = bytes,
        fileRef = fileRef,
        sizeBytes = sizeBytes
    )

private fun String.sanitizeAccountProviderId(): String =
    trim()
        .lowercase()
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

fun buildSharedSettingsConnectedAppRegisterBody(request: SettingsConnectedAppRegisterRequest): String =
    buildJsonObject {
        put("name", JsonPrimitive(request.name))
        request.clientId?.let { put("clientId", JsonPrimitive(it)) }
        request.description?.let { put("description", JsonPrimitive(it)) }
        request.homepageUrl?.let { put("homepageUrl", JsonPrimitive(it)) }
        request.returnUrl?.let { put("returnUrl", JsonPrimitive(it)) }
        request.agentManifestUrl?.let { put("agentManifestUrl", JsonPrimitive(it)) }
    }.toString()

fun buildSharedSettingsConnectedAppUpdateBody(request: SettingsConnectedAppUpdateRequest): String =
    buildJsonObject {
        put("name", JsonPrimitive(request.name))
        put("description", request.description?.let { JsonPrimitive(it) } ?: JsonNull)
        put("homepageUrl", request.homepageUrl?.let { JsonPrimitive(it) } ?: JsonNull)
        put("returnUrl", request.returnUrl?.let { JsonPrimitive(it) } ?: JsonNull)
        put("agentManifestUrl", request.agentManifestUrl?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()

fun buildSharedSettingsConnectedAppPublishBody(request: SettingsConnectedAppPublishRequest): String =
    buildJsonObject {
        put("category", JsonPrimitive(request.category))
        request.dataAccessSummary?.let { put("dataAccessSummary", JsonPrimitive(it)) }
    }.toString()

fun buildSharedSettingsServerProfileUpdateBody(
    name: String?,
    hideHumansFromMembers: Boolean?
): String =
    buildJsonObject {
        name?.let { put("name", JsonPrimitive(it)) }
        hideHumansFromMembers?.let { put("hideHumansFromMembers", JsonPrimitive(it)) }
    }.toString()

@Serializable
data class SettingsServerInviteCreateRequestBody(val email: String, val role: String? = null)

fun buildSharedSettingsServerInviteCreateBody(email: String, role: String? = null): String =
    settingsRepositoryJson.encodeToString(SettingsServerInviteCreateRequestBody(email, role))

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
data class SettingsServerAgreementUpdateRequestBody(
    val enabled: Boolean,
    val title: String,
    val bodyMarkdown: String
)

fun buildSharedSettingsServerAgreementUpdateBody(request: SettingsServerAgreementUpdateRequest): String =
    settingsRepositoryJson.encodeToString(
        SettingsServerAgreementUpdateRequestBody(
            enabled = request.enabled,
            title = request.title,
            bodyMarkdown = request.bodyMarkdown
        )
    )

fun buildSharedSettingsServerOnboardingUpdateBody(request: SettingsServerOnboardingUpdateRequest): String =
    buildJsonObject {
        request.setupModalReminderOptOut?.let { put("setupModalReminderOptOut", JsonPrimitive(it)) }
        when {
            request.clearOnboardingAgent -> put("onboardingAgentId", JsonNull)
            request.onboardingAgentId != null -> put("onboardingAgentId", JsonPrimitive(request.onboardingAgentId))
        }
        request.agentAllChannelGreetingEnabled?.let { put("agentAllChannelGreetingEnabled", JsonPrimitive(it)) }
    }.toString()

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
    val jointChannelSortMode: String? = null,
    val dmSortMode: String? = null,
    val pinnedSortMode: String? = null,
    val pinned: List<SettingsServerSidebarPinnedItem>? = null,
    val pinnedChannelIds: List<String>? = null,
    val pinnedAgentIds: List<String>? = null,
    val pinnedOrder: List<String>? = null,
    val hiddenDmIds: List<String>? = null,
    val channelPanelTabOrder: List<String>? = null,
    val agentPanelTabOrder: List<String>? = null,
    val customSections: List<SettingsServerSidebarCustomSection>? = null,
    val sectionOrder: List<String>? = null,
    val sectionPlacements: List<SettingsServerSidebarSectionPlacement>? = null,
    val sectionsVersion: Long? = null
)

@Serializable
data class SettingsServerOrderUpdateRequestBody(
    val serverOrder: List<String>
)

fun buildSharedSettingsServerSidebarOrderUpdateBody(request: SettingsServerSidebarOrderUpdateRequest): String =
    settingsRepositoryJson.encodeToString(
        SettingsServerSidebarOrderUpdateRequestBody(
            channelOrder = request.channelOrder,
            agentOrder = request.agentOrder,
            dmOrder = request.dmOrder,
            channelSortMode = request.channelSortMode,
            jointChannelSortMode = request.jointChannelSortMode,
            dmSortMode = request.dmSortMode,
            pinnedSortMode = request.pinnedSortMode,
            pinned = request.pinned,
            pinnedChannelIds = request.pinnedChannelIds,
            pinnedAgentIds = request.pinnedAgentIds,
            pinnedOrder = request.pinnedOrder,
            hiddenDmIds = request.hiddenDmIds,
            channelPanelTabOrder = request.channelPanelTabOrder,
            agentPanelTabOrder = request.agentPanelTabOrder,
            customSections = request.customSections,
            sectionOrder = request.sectionOrder,
            sectionPlacements = request.sectionPlacements,
            sectionsVersion = request.sectionsVersion
        )
    )

fun buildSharedSettingsServerOrderUpdateBody(request: SettingsServerOrderUpdateRequest): String =
    settingsRepositoryJson.encodeToString(
        SettingsServerOrderUpdateRequestBody(serverOrder = request.serverOrder)
    )

@Serializable
data class SettingsServerMemberRoleUpdateRequestBody(
    val role: String
)

fun buildSharedSettingsServerMemberRoleUpdateBody(role: String): String =
    settingsRepositoryJson.encodeToString(SettingsServerMemberRoleUpdateRequestBody(role = role))

@Serializable
data class SettingsServerNotificationModeRequestBody(
    val serverPushMode: String
)

fun buildSharedSettingsServerNotificationModeBody(mode: ServerPushMode): String =
    settingsRepositoryJson.encodeToString(SettingsServerNotificationModeRequestBody(serverPushMode = mode.wireName))

fun buildSharedSettingsServerMuteBody(enabled: Boolean): String =
    buildSharedSettingsServerNotificationModeBody(if (enabled) ServerPushMode.None else ServerPushMode.All)

@Serializable
private data class SettingsLegacyServerMuteRequestBody(
    val serverPushMuted: Boolean
)

private fun buildSharedSettingsLegacyServerMuteBody(enabled: Boolean): String =
    settingsRepositoryJson.encodeToString(SettingsLegacyServerMuteRequestBody(serverPushMuted = enabled))

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
        request.preferredTranslationDisplay?.let { put("preferredTranslationDisplay", JsonPrimitive(it)) }
    }.toString()

private fun SettingsLanguageRegionPreferenceUpdateRequest.hasAtLeastOneField(): Boolean =
    preferredLanguage != null ||
        clearPreferredLanguage ||
        preferredTimezone != null ||
        clearPreferredTimezone ||
        autoTranslationEnabled != null ||
        preferredTimeFormat != null ||
        clearPreferredTimeFormat ||
        preferredTranslationDisplay != null

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
                is DataResult.Error -> ""
                is DataResult.NoPermission -> "You do not have permission to manage server translation settings."
                is DataResult.Content -> "Server message translation setting loaded."
            }
    val languageKey = preferredLanguage ?: TranslationTargetLanguages.Browser
    val languageLabel =
        languageRegionLanguageOptions.firstOrNull { it.key == languageKey }?.label
            ?: TranslationTargetLanguages.options.first { it.key == TranslationTargetLanguages.Browser }.label
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
                detail = translationDetail,
                errorPresentation = translationResult.errorPresentationOrNull()
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
            ),
        translationDisplay =
            SettingsMobileSelectionSnapshot(
                selectedKey = preferredTranslationDisplay ?: "translated",
                selectedLabel = if (preferredTranslationDisplay == "original") "Original" else "Translated",
                statusLabel = if (preferredTranslationDisplay == "original") "Original" else "Translated",
                detail = "Choose whether messages display translated or original text in servers that support translation.",
                options =
                    listOf(
                        SettingsMobileSelectionOptionSnapshot("translated", "Translated"),
                        SettingsMobileSelectionOptionSnapshot("original", "Original")
                    ),
                available = true
            )
    )
}

private val languageRegionLanguageOptions =
    TranslationTargetLanguages.options.map { option ->
        SettingsMobileSelectionOptionSnapshot(option.key, option.label)
    }

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
