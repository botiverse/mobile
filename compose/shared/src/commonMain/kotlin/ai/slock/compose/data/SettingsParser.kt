package ai.slock.compose.data

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Presence
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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

private fun String.toSettingsRoleLabel(): String =
    replace("_", " ")
        .replace("-", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
