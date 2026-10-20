package build.raft.app.data

import build.raft.app.toRaftAvatarLabel
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private val settingsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireAccountUser(
    val id: String,
    val email: String,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val emailVerified: Boolean = false,
    val preferredLanguage: String? = null,
    val targetLanguage: String? = null,
    val preferredTimezone: String? = null,
    val timezone: String? = null,
    val autoTranslationEnabled: Boolean? = null,
    val preferredTimeFormat: String? = null,
    val timeFormat: String? = null,
    val preferredMessageBodyFontSize: String? = null,
    val preferredTranslationDisplay: String? = null,
)

@Serializable
private data class WireAuthProvidersResponse(
    val providers: List<WireAuthProvider> = emptyList()
)

@Serializable
private data class WireAuthProvider(
    val id: String,
    val label: String? = null,
    val name: String? = null,
    val enabled: Boolean = false
)

@Serializable
private data class WireAuthIdentitiesResponse(
    val identities: List<WireAuthIdentity> = emptyList()
)

@Serializable
private data class WireAuthIdentity(
    val provider: String,
    val providerEmail: String? = null,
    val email: String? = null
)

@Serializable
private data class WireAccountProviderLinkStartResponse(
    val url: String
)

fun parseSharedAccountSettingsSnapshot(
    bodyText: String,
    fallbackRole: String?,
): AccountSettingsSnapshot? = runCatching {
    val user = settingsJson.decodeFromString(WireAccountUser.serializer(), bodyText)
    val displayName = user.displayName?.takeIf { it.isNotBlank() } ?: user.name
    AccountSettingsSnapshot(
        profile = AccountProfileSnapshot(
            userId = user.id,
            displayName = displayName,
            handle = user.name,
            email = user.email,
            avatarLabel = displayName.take(2).uppercase(),
            avatarUrl = user.avatarUrl,
            verified = user.emailVerified,
            roleLabel = fallbackRole?.toSettingsRoleLabel(),
        ),
        connectedAccounts = listOf(
            ConnectedAccountSnapshot(
                providerId = "password",
                provider = "Email password",
                identifier = user.email,
                status = ConnectedAccountStatus.Connected,
            )
        ),
        canEditProfile = true,
    )
}.getOrNull()

fun parseSharedAccountProviderLinkStartResult(
    providerId: String,
    bodyText: String
): AccountProviderLinkStartResult? = runCatching {
    val response = settingsJson.decodeFromString(WireAccountProviderLinkStartResponse.serializer(), bodyText)
    AccountProviderLinkStartResult(providerId = providerId, url = response.url.takeIf { it.isNotBlank() } ?: return@runCatching null)
}.getOrNull()

fun parseSharedAccountConnectedAccounts(
    providersBodyText: String,
    identitiesBodyText: String,
    fallbackEmail: String
): List<ConnectedAccountSnapshot>? = runCatching {
    val providers = settingsJson.decodeFromString(WireAuthProvidersResponse.serializer(), providersBodyText).providers
    val identities =
        settingsJson
            .decodeFromString(WireAuthIdentitiesResponse.serializer(), identitiesBodyText)
            .identities
            .associateBy { it.provider.trim().lowercase() }
    buildList {
        add(
            ConnectedAccountSnapshot(
                providerId = "password",
                provider = "Email password",
                identifier = fallbackEmail,
                status = ConnectedAccountStatus.Connected,
                canConnect = false
            )
        )
        providers
            .filter { it.enabled }
            .forEach { provider ->
                val providerId = provider.id.trim().lowercase()
                val identity = identities[providerId]
                add(
                    ConnectedAccountSnapshot(
                        providerId = providerId,
                        provider = (provider.label ?: provider.name).orEmpty().ifBlank { providerId.titleLabel() },
                        identifier = identity?.providerEmail?.takeIf { it.isNotBlank() }
                            ?: identity?.email?.takeIf { it.isNotBlank() }
                            ?: "Not connected",
                        status = if (identity != null) ConnectedAccountStatus.Connected else ConnectedAccountStatus.NotConnected
                    )
                )
            }
    }
}.getOrNull()

fun parseSharedLanguageRegionAccountPreferences(
    bodyText: String
): SettingsLanguageRegionAccountPreferences? = runCatching {
    val user = settingsJson.decodeFromString(WireAccountUser.serializer(), bodyText)
    SettingsLanguageRegionAccountPreferences(
        preferredLanguage = normalizeSharedLanguageRegionLanguage(user.preferredLanguage ?: user.targetLanguage),
        preferredTimezone = normalizeSharedLanguageRegionTimezone(user.preferredTimezone ?: user.timezone),
        autoTranslationEnabled = user.autoTranslationEnabled ?: true,
        preferredTimeFormat = normalizeSharedLanguageRegionTimeFormat(user.preferredTimeFormat ?: user.timeFormat),
        preferredTranslationDisplay = user.preferredTranslationDisplay ?: "translated"
    )
}.getOrNull()

fun parseSharedAppearanceSettingsSnapshot(bodyText: String): SettingsAppearanceSnapshot? = runCatching {
    val user = settingsJson.decodeFromString(WireAccountUser.serializer(), bodyText)
    user.preferredMessageBodyFontSize.toSettingsAppearanceSnapshot()
}.getOrNull()

private fun String.titleLabel(): String =
    replaceFirstChar { it.uppercase() }

internal fun String?.toSettingsAppearanceSnapshot(): SettingsAppearanceSnapshot {
    val selectedKey = normalizeSettingsMessageBodyFontSize(this)
    return SettingsAppearanceSnapshot(
        messageBodyFontSize =
            SettingsMobileSelectionSnapshot(
                selectedKey = selectedKey,
                selectedLabel = settingsMessageBodyFontSizeLabel(selectedKey),
                statusLabel = settingsMessageBodyFontSizeLabel(selectedKey),
                detail = "Adjusts message body text only. Buttons, labels, sidebars, and code blocks keep fixed UI sizes.",
                options =
                    listOf(
                        SettingsMobileSelectionOptionSnapshot("sm", "Small"),
                        SettingsMobileSelectionOptionSnapshot("md", "Medium"),
                        SettingsMobileSelectionOptionSnapshot("lg", "Large")
                    )
            )
    )
}

internal fun normalizeSettingsMessageBodyFontSize(value: String?): String =
    when (value?.trim()?.lowercase()) {
        "sm", "lg" -> value.trim().lowercase()
        else -> "md"
    }

private fun settingsMessageBodyFontSizeLabel(value: String): String =
    when (value) {
        "sm" -> "Small"
        "lg" -> "Large"
        else -> "Medium"
    }

private fun normalizeSharedLanguageRegionLanguage(value: String?): String? =
    TranslationTargetLanguages.normalize(value)

private fun normalizeSharedLanguageRegionTimezone(value: String?): String? =
    value?.trim()?.takeIf { it.isNotBlank() }

private fun normalizeSharedLanguageRegionTimeFormat(value: String?): String? =
    value?.trim()?.lowercase()?.takeIf { it == "12h" || it == "24h" }

@Serializable
private data class WireConnectedAppClient(
    val id: String,
    val serverId: String? = null,
    val clientId: String,
    val appType: String = "server_local",
    val publishStatus: String = "private",
    val category: String = "Other",
    val dataAccessSummary: String? = null,
    val publishRejectionReason: String? = null,
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val returnUrl: String? = null,
    val agentManifestUrl: String? = null,
    val logoUrl: String? = null,
    val installedAt: String? = null,
    val publisherName: String? = null
)

@Serializable
private data class WireConnectedAppBuiltIn(
    val id: String,
    val clientId: String,
    val appType: String = "slock_builtin",
    val name: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val agentManifestUrl: String? = null,
    val humanMarketplaceVisible: Boolean = false
)

@Serializable
private data class WireConnectedAppCreateResponse(
    val client: WireConnectedAppClient,
    val clientSecret: String? = null
)

fun parseSharedSettingsConnectedAppClients(bodyText: String): List<SettingsConnectedAppClientSnapshot>? = runCatching {
    settingsJson.decodeFromString(ListSerializer(WireConnectedAppClient.serializer()), bodyText)
        .map { it.toConnectedAppClientSnapshot() }
}.getOrNull()

fun parseSharedSettingsConnectedAppClient(bodyText: String): SettingsConnectedAppClientSnapshot? = runCatching {
    settingsJson.decodeFromString(WireConnectedAppClient.serializer(), bodyText).toConnectedAppClientSnapshot()
}.getOrNull()

fun parseSharedSettingsConnectedAppCreateResult(
    bodyText: String
): Pair<SettingsConnectedAppClientSnapshot, SettingsConnectedAppSecretSnapshot?>? = runCatching {
    val wire = settingsJson.decodeFromString(WireConnectedAppCreateResponse.serializer(), bodyText)
    val client = wire.client.toConnectedAppClientSnapshot()
    val secret =
        wire.clientSecret
            ?.takeIf { it.isNotBlank() }
            ?.let { SettingsConnectedAppSecretSnapshot(clientId = client.clientId, clientSecret = it) }
    client to secret
}.getOrNull()

fun parseSharedSettingsConnectedAppBuiltIns(bodyText: String): List<SettingsConnectedAppBuiltInSnapshot>? = runCatching {
    settingsJson.decodeFromString(ListSerializer(WireConnectedAppBuiltIn.serializer()), bodyText)
        .map { it.toConnectedAppBuiltInSnapshot() }
}.getOrNull()

fun parseSharedSettingsConnectedAppMarketplace(
    bodyText: String
): List<SettingsConnectedAppMarketplaceSnapshot>? = runCatching {
    settingsJson.decodeFromString(ListSerializer(WireConnectedAppClient.serializer()), bodyText)
        .map { it.toConnectedAppMarketplaceSnapshot() }
}.getOrNull()

fun buildSettingsConnectedAppsSnapshot(
    clients: List<SettingsConnectedAppClientSnapshot>,
    builtIn: List<SettingsConnectedAppBuiltInSnapshot>,
    marketplace: List<SettingsConnectedAppMarketplaceSnapshot>,
    canManage: Boolean,
    oneTimeClientSecret: SettingsConnectedAppSecretSnapshot? = null
): SettingsConnectedAppsSnapshot {
    val installed =
        marketplace.filter { it.installed }.map {
            SettingsConnectedAppInstalledSnapshot(
                id = it.id,
                clientId = it.clientId,
                name = it.name,
                developer = it.developer,
                description = it.description,
                homepageDomain = it.homepageDomain,
                callbackDomain = it.callbackDomain,
                logoUrl = it.logoUrl,
                origin = "marketplace",
                editable = false
            )
        } +
            clients.filter { it.isPrivateInstalledConnectedAppClient() }.map {
                SettingsConnectedAppInstalledSnapshot(
                    id = it.id,
                    clientId = it.clientId,
                    name = it.name,
                    developer = "This server",
                    description = it.description,
                    homepageDomain = it.homepageDomain,
                    callbackDomain = it.callbackDomain,
                    logoUrl = it.logoUrl,
                    origin = "private",
                    editable = true
                )
            }
    return SettingsConnectedAppsSnapshot(
        marketplace = marketplace,
        builtIn = builtIn,
        installed = installed,
        registered = clients,
        canManage = canManage,
        oneTimeClientSecret = oneTimeClientSecret
    )
}

private fun SettingsConnectedAppClientSnapshot.isPrivateInstalledConnectedAppClient(): Boolean {
    val type = appType.trim().lowercase()
    return type == "server_local"
}

private fun WireConnectedAppClient.toConnectedAppClientSnapshot(): SettingsConnectedAppClientSnapshot {
    val homepage = homepageUrl?.takeIf { it.isNotBlank() }
    val callback = returnUrl?.takeIf { it.isNotBlank() }
    return SettingsConnectedAppClientSnapshot(
        id = id,
        serverId = serverId.orEmpty(),
        clientId = clientId,
        appType = appType,
        publishStatus = publishStatus,
        category = category,
        dataAccessSummary = dataAccessSummary,
        publishRejectionReason = publishRejectionReason,
        name = name,
        description = description,
        homepageUrl = homepage,
        homepageDomain = homepage.toSettingsDomainOrNull(),
        returnUrl = callback,
        callbackDomain = callback.toSettingsDomainOrNull(),
        agentManifestUrl = agentManifestUrl?.takeIf { it.isNotBlank() },
        logoUrl = logoUrl?.takeIf { it.isNotBlank() }
    )
}

private fun WireConnectedAppClient.toConnectedAppMarketplaceSnapshot(): SettingsConnectedAppMarketplaceSnapshot {
    val homepage = homepageUrl?.takeIf { it.isNotBlank() }
    val callback = returnUrl?.takeIf { it.isNotBlank() }
    return SettingsConnectedAppMarketplaceSnapshot(
        id = id,
        clientId = clientId,
        name = name,
        category = category,
        developer = publisherName?.takeIf { it.isNotBlank() } ?: "Third-party developer",
        description = description,
        homepageUrl = homepage,
        homepageDomain = homepage.toSettingsDomainOrNull() ?: "Not configured",
        returnUrl = callback,
        callbackDomain = callback.toSettingsDomainOrNull() ?: "Not configured",
        logoUrl = logoUrl?.takeIf { it.isNotBlank() },
        installed = !installedAt.isNullOrBlank(),
        dataAccessSummary = dataAccessSummary?.takeIf { it.isNotBlank() } ?: "Profile and identity"
    )
}

private fun WireConnectedAppBuiltIn.toConnectedAppBuiltInSnapshot(): SettingsConnectedAppBuiltInSnapshot {
    val homepage = homepageUrl?.takeIf { it.isNotBlank() }
    return SettingsConnectedAppBuiltInSnapshot(
        id = id,
        clientId = clientId,
        name = name,
        description = description,
        homepageUrl = homepage,
        homepageDomain = homepage.toSettingsDomainOrNull(),
        agentManifestUrl = agentManifestUrl?.takeIf { it.isNotBlank() }
    )
}

private fun String?.toSettingsDomainOrNull(): String? {
    val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val withoutScheme = value.substringAfter("://", value)
    return withoutScheme.substringBefore('/').substringBefore('?').takeIf { it.isNotBlank() }
}

@Serializable
private data class WireSettingsAgent(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val description: String? = null,
    val status: String = "inactive",
    val runtime: String? = null,
    val machineId: String? = null,
    val creatorType: String? = null,
    val creatorId: String? = null,
)

fun parseSharedSettingsComputerAgents(
    bodyText: String,
    machineId: String,
): List<SettingsComputerAgentSnapshot>? = runCatching {
    settingsJson.decodeFromString(ListSerializer(WireSettingsAgent.serializer()), bodyText)
        .filter { it.machineId == machineId }
        .map { it.toSettingsComputerAgentSnapshot() }
}.getOrNull()

private fun WireSettingsAgent.toSettingsComputerAgentSnapshot(): SettingsComputerAgentSnapshot {
    val label = displayName?.takeIf { it.isNotBlank() } ?: name
    return SettingsComputerAgentSnapshot(
        id = id,
        name = label,
        statusLabel = status,
        ownerLabel = creatorId,
        runtime = runtime,
        avatarUrl = avatarUrl,
        description = description,
    )
}

@Serializable
private data class WireServerMemberProfile(
    val userId: String,
    val email: String? = null,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null,
)

fun parseSharedSettingsMemberProfiles(bodyText: String): List<MemberProfile>? = runCatching {
    settingsJson.decodeFromString(ListSerializer(WireServerMemberProfile.serializer()), bodyText)
        .map { it.toMemberProfile() }
}.getOrNull()

private fun WireServerMemberProfile.toMemberProfile(): MemberProfile {
    val label = displayName?.takeIf { it.isNotBlank() } ?: name
    return MemberProfile(
        id = MemberId(userId),
        displayName = label,
        handle = name,
        kind = MemberKind.Human,
        description = description,
        roleDescription = role?.toSettingsRoleLabel(),
        avatarUrl = avatarUrl,
        avatarLabel = label.toRaftAvatarLabel(),
        presence = Presence.Offline,
        agentStatus = AgentStatus.Offline,
    )
}

@Serializable
private data class WireSettingsComputersResponse(
    val machines: List<WireSettingsComputer>,
    val latestDaemonVersion: String? = null,
    val latestComputerVersion: String? = null,
)

@Serializable
private data class WireSettingsComputer(
    val id: String,
    val name: String,
    val hostname: String? = null,
    val os: String? = null,
    val daemonVersion: String? = null,
    val computerVersion: String? = null,
    val isComputer: Boolean = false,
    val status: String? = null,
    val lastHeartbeat: String? = null,
)

fun parseSharedSettingsComputers(bodyText: String): SettingsComputersSnapshot? = runCatching {
    val response = settingsJson.decodeFromString(WireSettingsComputersResponse.serializer(), bodyText)
    SettingsComputersSnapshot(
        computers = response.machines
            .map { it.toSettingsComputerSnapshot() }
            .filter { it.id.isNotBlank() || it.name.isNotBlank() },
        canAddComputer = false,
        latestDaemonVersion = response.latestDaemonVersion,
        latestComputerVersion = response.latestComputerVersion,
    )
}.getOrNull()

private fun WireSettingsComputer.toSettingsComputerSnapshot(): SettingsComputerSnapshot {
    val label = name.ifBlank { hostname ?: id }
    val normalizedStatus = status?.lowercase()
    return SettingsComputerSnapshot(
        id = id.ifBlank { label },
        name = label.ifBlank { "Unknown computer" },
        daemonVersion = daemonVersion ?: "daemon unavailable",
        computerVersion = computerVersion,
        isComputer = isComputer,
        online = normalizedStatus == "online",
        hostname = hostname,
        platform = os,
        lastSeenLabel = lastHeartbeat,
    )
}

@Serializable
private data class WireSettingsMachineWorkspace(
    val directoryName: String,
    val status: String? = null,
    val lastModified: String? = null,
)

fun parseSharedSettingsMachineWorkspaces(
    bodyText: String,
): List<SettingsComputerWorkspaceSnapshot>? = runCatching {
    settingsJson.decodeFromString(ListSerializer(WireSettingsMachineWorkspace.serializer()), bodyText)
        .map { it.toSettingsComputerWorkspaceSnapshot() }
        .filter { it.id.isNotBlank() || it.path.isNotBlank() }
}.getOrNull()

private fun WireSettingsMachineWorkspace.toSettingsComputerWorkspaceSnapshot(): SettingsComputerWorkspaceSnapshot =
    SettingsComputerWorkspaceSnapshot(
        id = directoryName,
        name = directoryName,
        path = directoryName,
        statusLabel = status,
        lastActiveLabel = lastModified,
    )

@Serializable
private data class WireSettingsServerMember(
    val userId: String? = null,
    val name: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val role: String? = null,
)

fun parseSharedSettingsServerAdmins(bodyText: String): List<SettingsServerAdminSnapshot>? = runCatching {
    settingsJson.decodeFromString(ListSerializer(WireSettingsServerMember.serializer()), bodyText)
        .mapNotNull { it.toSettingsServerAdminSnapshot() }
}.getOrNull()

private fun WireSettingsServerMember.toSettingsServerAdminSnapshot(): SettingsServerAdminSnapshot? {
    val id = userId?.takeIf { it.isNotBlank() } ?: return null
    return SettingsServerAdminSnapshot(
        id = id,
        name = displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: email?.takeIf { it.isNotBlank() }
            ?: id,
        detail = email,
        role = role?.toSettingsRoleLabel(),
    )
}

@Serializable
private data class WireSettingsServerUsage(
    val agents: JsonElement,
    val machines: JsonElement,
    val channels: JsonElement,
)

fun parseSharedSettingsServerUsage(bodyText: String): SettingsServerUsageSnapshot? = runCatching {
    val usage = settingsJson.decodeFromString(WireSettingsServerUsage.serializer(), bodyText)
    SettingsServerUsageSnapshot(
        agents = usage.agents.settingsIntValue() ?: return null,
        machines = usage.machines.settingsIntValue() ?: return null,
        channels = usage.channels.settingsIntValue() ?: return null,
    )
}.getOrNull()

@Serializable
private data class WireSettingsBillingSubscription(
    val plan: String? = null,
    val displayName: String? = null,
    val subscription: WireSettingsSubscription? = null,
    val stripeConfigured: Boolean,
    val permissions: WireSettingsBillingPermissions = WireSettingsBillingPermissions()
)

@Serializable
private data class WireSettingsSubscription(
    val status: String? = null,
    val billingInterval: String? = null,
    val currentPeriodEnd: String? = null,
    val cancelAtPeriodEnd: Boolean = false,
)

@Serializable
private data class WireSettingsBillingPermissions(
    val canReadBillingSummary: Boolean = false,
    val canManageBilling: Boolean = false
)

fun parseSharedSettingsBillingSubscription(bodyText: String): SettingsBillingSubscriptionSnapshot? = runCatching {
    val billing = settingsJson.decodeFromString(WireSettingsBillingSubscription.serializer(), bodyText)
    SettingsBillingSubscriptionSnapshot(
        status = billing.subscription?.status?.takeIf { it.isNotBlank() },
        billingInterval = billing.subscription?.billingInterval?.takeIf { it.isNotBlank() },
        currentPeriodEnd = billing.subscription?.currentPeriodEnd?.takeIf { it.isNotBlank() },
        cancelAtPeriodEnd = billing.subscription?.cancelAtPeriodEnd == true,
        stripeConfigured = billing.stripeConfigured,
        canManageBilling = billing.permissions.canManageBilling,
        displayName = billing.displayName?.takeIf { it.isNotBlank() },
        effectivePlan = billing.plan?.takeIf { it.isNotBlank() }
    )
}.getOrNull()

@Serializable
private data class WireSettingsNotificationSettings(
    val serverPushMuted: Boolean,
    val serverPushMentionsOnly: Boolean = false,
    val serverPushMode: String? = null,
    val pushPreviewMode: String? = null,
)

data class SettingsServerNotificationPreferences(
    val mode: ServerPushMode,
    val pushPreviewMode: PushPreviewMode,
    val legacyMuted: Boolean,
    val canonicalModeSupported: Boolean,
    val pushPreviewModeSupported: Boolean
)

fun parseSharedSettingsServerNotificationSettings(
    bodyText: String,
    diagnostics: (String) -> Unit = {}
): SettingsServerNotificationPreferences? = runCatching {
    val root = settingsJson.parseToJsonElement(bodyText).jsonObject
    val wire = settingsJson.decodeFromString(WireSettingsNotificationSettings.serializer(), bodyText)
    val canonicalModePresent = "serverPushMode" in root
    val pushPreviewModePresent = "pushPreviewMode" in root
    val mode = if (canonicalModePresent) {
        ServerPushMode.fromWire(wire.serverPushMode)
            ?: run {
                diagnostics("projection_value_rejected source=settings_server_notification field=server_push_mode value=unknown")
                return@runCatching null
            }
    } else {
        when {
                wire.serverPushMuted -> ServerPushMode.None
                wire.serverPushMentionsOnly -> ServerPushMode.Mentions
                else -> ServerPushMode.All
            }
    }
    val pushPreviewMode = if (pushPreviewModePresent) {
        PushPreviewMode.fromWire(wire.pushPreviewMode)
            ?: run {
                diagnostics("projection_value_rejected source=settings_server_notification field=push_preview_mode value=unknown")
                return@runCatching null
            }
    } else {
        PushPreviewMode.Full
    }
    SettingsServerNotificationPreferences(
        mode = mode,
        pushPreviewMode = pushPreviewMode,
        legacyMuted = wire.serverPushMuted,
        canonicalModeSupported = canonicalModePresent,
        pushPreviewModeSupported = pushPreviewModePresent
    )
}.getOrNull()

@Serializable
private data class WireSettingsServerOnboarding(
    val setupModalReminderOptOut: Boolean = false,
    val onboardingAgentId: String? = null,
    val agentAllChannelGreetingEnabled: Boolean? = null,
)

fun parseSharedSettingsServerOnboarding(bodyText: String): SettingsServerOnboardingSnapshot? = runCatching {
    val onboarding = settingsJson.decodeFromString(WireSettingsServerOnboarding.serializer(), bodyText)
    val agentStatus =
        onboarding.onboardingAgentId
            ?.takeIf { it.isNotBlank() }
            ?.let { "Agent selected" }
            ?: "No onboarding agent"
    val greetingStatus = if (onboarding.agentAllChannelGreetingEnabled != false) "new-agent greeting on" else "new-agent greeting off"
    SettingsServerOnboardingSnapshot(
        setupModalReminderOptOut = onboarding.setupModalReminderOptOut,
        onboardingAgentId = onboarding.onboardingAgentId?.takeIf { it.isNotBlank() },
        agentAllChannelGreetingEnabled = onboarding.agentAllChannelGreetingEnabled != false,
        statusLabel = agentStatus,
        detail = "$agentStatus; $greetingStatus.",
    )
}.getOrNull()

fun parseSharedSettingsServerOnboardingAgents(bodyText: String): List<SettingsServerOnboardingAgentSnapshot>? =
    parseSharedSettingsAgentsAsMembers(bodyText)
        ?.filter { it.kind == MemberKind.Agent }
        ?.map {
            SettingsServerOnboardingAgentSnapshot(
                id = it.id.value,
                name = it.handle,
                displayName = it.displayName
            )
        }

@Serializable
private data class WireSettingsServerAgreementResponse(
    val enabled: Boolean = false,
    val agreement: WireSettingsServerAgreement? = null
)

@Serializable
private data class WireSettingsServerAgreement(
    val title: String? = null,
    val bodyMarkdown: String? = null,
    val enabled: Boolean? = null
)

fun parseSharedSettingsServerAgreement(
    bodyText: String,
    canSave: Boolean
): SettingsServerAgreementSnapshot? = runCatching {
    val response = settingsJson.decodeFromString(WireSettingsServerAgreementResponse.serializer(), bodyText)
    val title = response.agreement?.title?.takeIf { it.isNotBlank() }.orEmpty()
    val body = response.agreement?.bodyMarkdown.orEmpty()
    val enabled = response.enabled || response.agreement?.enabled == true
    SettingsServerAgreementSnapshot(
        enabled = enabled,
        title = title,
        bodyMarkdown = body,
        statusLabel = if (enabled) "Required" else "Disabled",
        detail =
            if (enabled) {
                "New joiners must accept the pre-join agreement before joining."
            } else {
                "New joiners can enter without accepting an agreement."
            },
        canSave = canSave
    )
}.getOrNull()

@Serializable
private data class WireSettingsTranslationSettings(
    val translationEnabled: Boolean,
    val translationAvailable: Boolean,
    val canManageTranslation: Boolean,
)

fun parseSharedSettingsTranslation(bodyText: String): SettingsServerTranslationSnapshot? = runCatching {
    val settings = settingsJson.decodeFromString(WireSettingsTranslationSettings.serializer(), bodyText)
    SettingsServerTranslationSnapshot(
        translationEnabled = settings.translationEnabled,
        translationAvailable = settings.translationAvailable,
        canManageTranslation = settings.canManageTranslation,
        statusLabel =
            when {
                !settings.translationAvailable -> "Unavailable"
                settings.translationEnabled -> "Enabled"
                else -> "Disabled"
            },
        detail =
            when {
                !settings.translationAvailable -> "Translation provider is not configured for this deployment."
                settings.translationEnabled -> "Server message translation is enabled."
                else -> "Server message translation is disabled."
            }
    )
}.getOrNull()

@Serializable
private data class WireSettingsSidebarCustomSection(
    val id: String? = null,
    val name: String? = null,
    val emoji: String? = null,
    val sortMode: String? = null,
)

@Serializable
private data class WireSettingsSidebarSectionPlacement(
    val kind: String? = null,
    val id: String? = null,
    val sectionId: String? = null,
    val position: Int? = null,
)

@Serializable
private data class WireSettingsSidebarOrder(
    val channelOrder: List<String> = emptyList(),
    val agentOrder: List<String> = emptyList(),
    val dmOrder: List<String> = emptyList(),
    val channelSortMode: String = "manual",
    val jointChannelSortMode: String = "manual",
    val dmSortMode: String = "manual",
    val pinnedSortMode: String = "manual",
    val pinnedChannelIds: List<String> = emptyList(),
    val pinnedAgentIds: List<String> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val pinned: List<SettingsServerSidebarPinnedItem> = emptyList(),
    val hiddenDmIds: List<String> = emptyList(),
    val channelPanelTabOrder: List<String> = emptyList(),
    val agentPanelTabOrder: List<String> = emptyList(),
    val customSections: List<WireSettingsSidebarCustomSection> = emptyList(),
    val sectionOrder: List<String> = emptyList(),
    val sectionPlacements: List<WireSettingsSidebarSectionPlacement> = emptyList(),
    val sectionsVersion: Long = 0L,
)

fun parseSharedSettingsSidebarOrder(bodyText: String): SettingsServerSidebarOrderSnapshot? = runCatching {
    val order = settingsJson.decodeFromString(WireSettingsSidebarOrder.serializer(), bodyText)
    // task #512: custom-section fields mirror web's normalize* helpers
    // (packages/web/src/store/sidebarSections.ts): drop malformed entries,
    // then repair the section order against the surviving sections.
    val customSections =
        normalizeSidebarCustomSections(
            order.customSections.mapNotNull { section ->
                val id = section.id?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = section.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sortMode = section.sortMode?.trim()?.lowercase() ?: return@mapNotNull null
                SettingsServerSidebarCustomSection(
                    id = id,
                    name = name,
                    emoji = section.emoji?.takeIf { it.isNotBlank() },
                    sortMode = sortMode,
                )
            }
        )
    SettingsServerSidebarOrderSnapshot(
        channelOrder = order.channelOrder,
        agentOrder = order.agentOrder,
        dmOrder = order.dmOrder,
        channelSortMode = order.channelSortMode,
        jointChannelSortMode = order.jointChannelSortMode,
        dmSortMode = order.dmSortMode,
        pinnedSortMode = order.pinnedSortMode,
        pinnedChannelIds = order.pinnedChannelIds,
        pinnedAgentIds = order.pinnedAgentIds,
        pinnedOrder = order.pinnedOrder,
        pinned =
            order.pinned
                .mapNotNull { item ->
                    val kind = item.kind.trim().lowercase().takeIf { it == "channel" || it == "agent" || it == "human" }
                    val id = item.id.trim().takeIf { it.isNotBlank() }
                    if (kind != null && id != null) SettingsServerSidebarPinnedItem(kind = kind, id = id) else null
                },
        hiddenDmIds = order.hiddenDmIds,
        channelPanelTabOrder = order.channelPanelTabOrder,
        agentPanelTabOrder = order.agentPanelTabOrder,
        customSections = customSections,
        sectionOrder = normalizeSidebarSectionOrder(order.sectionOrder, customSections),
        sectionPlacements =
            normalizeSidebarSectionPlacements(
                order.sectionPlacements.mapNotNull { placement ->
                    val kind = placement.kind?.trim()?.lowercase() ?: return@mapNotNull null
                    val id = placement.id?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val sectionId = placement.sectionId?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val position = placement.position ?: return@mapNotNull null
                    SettingsServerSidebarSectionPlacement(kind = kind, id = id, sectionId = sectionId, position = position)
                }
            ),
        sectionsVersion = order.sectionsVersion,
    )
}.getOrNull()

@Serializable
private data class WireSettingsServerOrder(
    val serverOrder: List<String> = emptyList()
)

fun parseSharedSettingsServerOrder(bodyText: String): SettingsServerOrderSnapshot? = runCatching {
    val order = settingsJson.decodeFromString(WireSettingsServerOrder.serializer(), bodyText)
    SettingsServerOrderSnapshot(serverOrder = order.serverOrder)
}.getOrNull()

@Serializable
private data class WireSettingsServerProfileUpdate(
    val id: String,
    val name: String,
)

fun parseSharedSettingsServerProfileUpdated(bodyText: String): Boolean = runCatching {
    val profile = settingsJson.decodeFromString(WireSettingsServerProfileUpdate.serializer(), bodyText)
    profile.id.isNotBlank() && profile.name.isNotBlank()
}.getOrDefault(false)

@Serializable
private data class WireSettingsServerInvite(
    val id: String? = null,
    val email: String? = null,
)

fun parseSharedSettingsServerInviteCreated(bodyText: String): SettingsServerActionResult? = runCatching {
    val invite = settingsJson.decodeFromString(WireSettingsServerInvite.serializer(), bodyText)
    val email = invite.email?.takeIf { it.isNotBlank() }
    val id = invite.id?.takeIf { it.isNotBlank() }
    if (email == null && id == null) return null
    SettingsServerActionResult(
        success = true,
        message = email?.let { "Invite created for $it" },
    )
}.getOrNull()

@Serializable
private data class WireSettingsPendingInvite(
    val id: String? = null,
    val invitedEmail: String? = null,
    val email: String? = null,
    val createdAt: String? = null,
    val inviterName: String? = null,
)

fun parseSharedSettingsPendingInvites(bodyText: String): List<SettingsServerInviteSnapshot>? = runCatching {
    settingsJson.decodeFromString(ListSerializer(WireSettingsPendingInvite.serializer()), bodyText)
        .mapNotNull { invite ->
            val id = invite.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val email =
                invite.invitedEmail?.takeIf { it.isNotBlank() }
                    ?: invite.email?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
            SettingsServerInviteSnapshot(
                id = id,
                email = email,
                inviterName = invite.inviterName?.takeIf { it.isNotBlank() },
                createdAtLabel = invite.createdAt?.takeIf { it.isNotBlank() }
            )
        }
}.getOrNull()

@Serializable
private data class WireSettingsJoinLinkEnvelope(
    val token: String,
    val link: WireSettingsJoinLink,
)

@Serializable
private data class WireSettingsJoinLink(
    val id: String? = null,
    val token: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val maxUses: Int? = null,
    val useCount: Int? = null,
    val revokedAt: String? = null,
)

fun parseSharedSettingsJoinLinks(bodyText: String): List<SettingsServerJoinLinkSnapshot>? = runCatching {
    settingsJson.decodeFromString(ListSerializer(WireSettingsJoinLink.serializer()), bodyText)
        .mapNotNull { it.toSettingsServerJoinLinkSnapshot() }
}.getOrNull()

fun parseSharedSettingsCreatedJoinLink(bodyText: String): SettingsServerJoinLinkSnapshot? = runCatching {
    val envelope = settingsJson.decodeFromString(WireSettingsJoinLinkEnvelope.serializer(), bodyText)
    envelope.link.copy(token = envelope.token).toSettingsServerJoinLinkSnapshot()
}.getOrNull()

private fun WireSettingsJoinLink.toSettingsServerJoinLinkSnapshot(): SettingsServerJoinLinkSnapshot? {
    val id = id?.takeIf { it.isNotBlank() } ?: return null
    val tokenValue = token?.takeIf { it.isNotBlank() }
    val label =
        tokenValue?.let { token ->
            if (token.length <= 8) token else "${token.take(8)}…"
        } ?: "Join link"
    return SettingsServerJoinLinkSnapshot(
        id = id,
        label = label,
        url = tokenValue?.let { "/join/$it" },
        maxUsesLabel =
            maxUses?.let { uses ->
                if (uses <= 0) null else "$uses max uses"
            },
        usageLabel = useCount?.let { "$it uses" },
        expiresAtLabel = expiresAt?.takeIf { it.isNotBlank() },
        maxUses = maxUses?.takeIf { it > 0 },
        expiresAt = expiresAt?.takeIf { it.isNotBlank() },
        useCount = useCount,
    )
}

@Serializable
private data class WireSettingsServerDelete(
    val ok: Boolean,
)

fun parseSharedSettingsServerDeleted(bodyText: String): SettingsServerActionResult? = runCatching {
    parseSharedSettingsServerAction(bodyText)
}.getOrNull()

fun parseSharedSettingsServerAction(bodyText: String): SettingsServerActionResult? = runCatching {
    val deleted = settingsJson.decodeFromString(WireSettingsServerDelete.serializer(), bodyText)
    if (!deleted.ok) return null
    SettingsServerActionResult(success = true)
}.getOrNull()

private fun JsonElement.settingsDisplayValue(): String? =
    (this as? JsonPrimitive)?.let { primitive ->
        primitive.contentOrNull ?: primitive.intOrNull?.toString() ?: primitive.booleanOrNull?.toString()
    }

private fun JsonElement.settingsIntValue(): Int? =
    (this as? JsonPrimitive)?.let { primitive ->
        primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }

private fun String.toSettingsRoleLabel(): String =
    replace("_", " ")
        .replace("-", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

fun parseSharedSystemChannelSnapshot(bodyText: String): SettingsServerSystemChannelSnapshot? = runCatching {
    val parsed = settingsJson.parseToJsonElement(bodyText)
    val obj = parsed as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
    val channels = obj["channels"] as? kotlinx.serialization.json.JsonArray ?: return@runCatching null
    val allChannel = channels
        .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
        .firstOrNull { c ->
            (c["name"] as? JsonPrimitive)?.contentOrNull == "all"
        }
    if (allChannel == null) return@runCatching null
    val id = (allChannel["id"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
    // Server channel types: "channel" / "private" / "joint" / "dm" / "thread".
    // React hides #all by patching channel type to "private"; current type == "private" means hidden.
    val type = (allChannel["type"] as? JsonPrimitive)?.contentOrNull
    val hidden = type == "private"
    SettingsServerSystemChannelSnapshot(
        allChannelId = id,
        allChannelHidden = hidden,
        canManage = true
    )
}.getOrNull()
