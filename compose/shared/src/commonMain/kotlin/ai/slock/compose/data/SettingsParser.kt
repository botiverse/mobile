package ai.slock.compose.data

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Presence
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

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
                provider = "Email password",
                identifier = user.email,
                status = ConnectedAccountStatus.Connected,
            )
        ),
        canEditProfile = true,
    )
}.getOrNull()

@Serializable
private data class WireSettingsAgent(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val status: String = "inactive",
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
    val gravatarHash: String? = null,
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
        gravatarHash = gravatarHash,
        avatarLabel = label.take(1).uppercase(),
        presence = Presence.Offline,
        agentStatus = AgentStatus.Offline,
    )
}

@Serializable
private data class WireSettingsComputersResponse(
    val machines: List<WireSettingsComputer>,
    val latestDaemonVersion: String? = null,
)

@Serializable
private data class WireSettingsComputer(
    val id: String,
    val name: String,
    val hostname: String? = null,
    val os: String? = null,
    val daemonVersion: String? = null,
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
    )
}.getOrNull()

private fun WireSettingsComputer.toSettingsComputerSnapshot(): SettingsComputerSnapshot {
    val label = name.ifBlank { hostname ?: id }
    val normalizedStatus = status?.lowercase()
    return SettingsComputerSnapshot(
        id = id.ifBlank { label },
        name = label.ifBlank { "Unknown computer" },
        daemonVersion = daemonVersion ?: "daemon unavailable",
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
        .filter { it.role?.lowercase() == "admin" }
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

fun parseSharedSettingsServerUsage(bodyText: String): List<SettingsPlanUsageSnapshot>? = runCatching {
    val usage = settingsJson.decodeFromString(WireSettingsServerUsage.serializer(), bodyText)
    listOf(
        SettingsPlanUsageSnapshot("Agents", usage.agents.settingsDisplayValue() ?: return null),
        SettingsPlanUsageSnapshot("Computers", usage.machines.settingsDisplayValue() ?: return null),
        SettingsPlanUsageSnapshot("Channels", usage.channels.settingsDisplayValue() ?: return null),
    )
}.getOrNull()

@Serializable
private data class WireSettingsBillingSubscription(
    val subscription: WireSettingsSubscription? = null,
    val stripeConfigured: Boolean,
)

@Serializable
private data class WireSettingsSubscription(
    val status: String? = null,
    val currentPeriodEnd: String? = null,
)

fun parseSharedSettingsBillingSubscription(bodyText: String): List<SettingsPlanUsageSnapshot>? = runCatching {
    val billing = settingsJson.decodeFromString(WireSettingsBillingSubscription.serializer(), bodyText)
    buildList {
        add(
            SettingsPlanUsageSnapshot(
                label = "Billing Status",
                value = billing.subscription?.status?.toSettingsRoleLabel() ?: "No subscription",
            )
        )
        billing.subscription?.currentPeriodEnd?.let { currentPeriodEnd ->
            add(SettingsPlanUsageSnapshot(label = "Current Period End", value = currentPeriodEnd))
        }
        add(SettingsPlanUsageSnapshot(label = "Stripe Configured", value = billing.stripeConfigured.toString()))
    }
}.getOrNull()

@Serializable
private data class WireSettingsNotificationSettings(
    val serverPushMuted: Boolean,
)

fun parseSharedSettingsServerNotificationSettings(bodyText: String): Boolean? = runCatching {
    settingsJson.decodeFromString(WireSettingsNotificationSettings.serializer(), bodyText).serverPushMuted
}.getOrNull()

@Serializable
private data class WireSettingsServerOnboarding(
    val setupModalReminderOptOut: Boolean,
)

fun parseSharedSettingsServerOnboarding(bodyText: String): SettingsServerOnboardingSnapshot? = runCatching {
    val onboarding = settingsJson.decodeFromString(WireSettingsServerOnboarding.serializer(), bodyText)
    SettingsServerOnboardingSnapshot(
        setupModalReminderOptOut = onboarding.setupModalReminderOptOut,
        statusLabel = if (onboarding.setupModalReminderOptOut) "Reminder muted" else "Reminder active",
        detail = if (onboarding.setupModalReminderOptOut) {
            "The onboarding setup reminder is muted for this server."
        } else {
            "The onboarding setup reminder can appear when the server has no computer or agent."
        },
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
private data class WireSettingsSidebarOrder(
    val channelOrder: List<String> = emptyList(),
    val agentOrder: List<String> = emptyList(),
    val dmOrder: List<String> = emptyList(),
    val channelSortMode: String = "manual",
    val dmSortMode: String = "manual",
    val pinnedSortMode: String = "manual",
    val pinnedChannelIds: List<String> = emptyList(),
    val pinnedAgentIds: List<String> = emptyList(),
    val pinnedOrder: List<String> = emptyList(),
    val hiddenDmIds: List<String> = emptyList(),
    val channelPanelTabOrder: List<String> = emptyList(),
    val agentPanelTabOrder: List<String> = emptyList(),
)

fun parseSharedSettingsSidebarOrder(bodyText: String): SettingsServerSidebarOrderSnapshot? = runCatching {
    val order = settingsJson.decodeFromString(WireSettingsSidebarOrder.serializer(), bodyText)
    SettingsServerSidebarOrderSnapshot(
        channelOrder = order.channelOrder,
        agentOrder = order.agentOrder,
        dmOrder = order.dmOrder,
        channelSortMode = order.channelSortMode,
        dmSortMode = order.dmSortMode,
        pinnedSortMode = order.pinnedSortMode,
        pinnedChannelIds = order.pinnedChannelIds,
        pinnedAgentIds = order.pinnedAgentIds,
        pinnedOrder = order.pinnedOrder,
        hiddenDmIds = order.hiddenDmIds,
        channelPanelTabOrder = order.channelPanelTabOrder,
        agentPanelTabOrder = order.agentPanelTabOrder,
    )
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

private fun String.toSettingsRoleLabel(): String =
    replace("_", " ")
        .replace("-", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
