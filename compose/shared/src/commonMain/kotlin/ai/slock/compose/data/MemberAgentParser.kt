package ai.slock.compose.data

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val memberAgentJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireServerMember(
    val userId: String,
    val email: String? = null,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null,
    val gravatarHash: String? = null,
)

@Serializable
private data class WireChannelMembers(
    val humans: List<WireChannelHuman> = emptyList(),
    val agents: List<WireAgent> = emptyList(),
)

@Serializable
private data class WireChannelHuman(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null,
    val gravatarHash: String? = null,
)

@Serializable
private data class WireAgent(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val status: String = "inactive",
    val runtime: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val creatorType: String? = null,
    val creatorId: String? = null,
    val machineId: String? = null,
    val activity: String? = null,
    val activityDetail: String? = null,
    val runtimeProfile: WireAgentRuntimeProfile? = null,
    val createdAgents: List<WireAgentCreatedSummary> = emptyList(),
)

@Serializable
private data class WireAgentCreatedSummary(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val runtime: String? = null,
    val status: String = "inactive",
)

@Serializable
private data class WireAgentRuntimeProfile(
    val machineId: String? = null,
    val workspaceRefLabel: String? = null,
    val workspaceRefPath: String? = null,
    val workspacePathRefLabel: String? = null,
    val workspacePathRefPath: String? = null,
)

fun parseSharedServerMemberSnapshot(
    bodyText: String,
    server: ServerSummary,
): MemberSnapshot? = runCatching {
    MemberSnapshot(
        server = server,
        members = memberAgentJson.decodeFromString(ListSerializer(WireServerMember.serializer()), bodyText)
            .map { it.toMemberProfile() }
            .distinctBy { it.id.value },
    )
}.getOrNull()

fun parseSharedSettingsAgentsAsMembers(bodyText: String): List<MemberProfile>? = runCatching {
    memberAgentJson.decodeFromString(ListSerializer(WireAgent.serializer()), bodyText)
        .map { it.toMemberProfile() }
        .distinctBy { it.id.value }
}.getOrNull()

fun parseSharedSettingsAgentMachineNames(bodyText: String): Map<String, String>? =
    parseSharedSettingsComputers(bodyText)?.computers?.associate { it.id to it.name }

fun parseSharedAgentProfile(bodyText: String): MemberProfile? = runCatching {
    memberAgentJson.decodeFromString(WireAgent.serializer(), bodyText).toMemberProfile()
}.getOrNull()

fun parseSharedMemberProfile(bodyText: String): MemberProfile? = runCatching {
    memberAgentJson.decodeFromString(WireServerMember.serializer(), bodyText).toMemberProfile()
}.getOrNull()

fun parseSharedAgentDirectMessages(
    bodyText: String,
    agentId: MemberId,
): AgentDmSnapshot? = runCatching {
    AgentDmSnapshot(
        agentId = agentId,
        directMessages = memberAgentJson.decodeFromString(
            ListSerializer(WireAgentDmSummary.serializer()),
            bodyText,
        ).map { it.toDomain() },
    )
}.getOrNull()

@Serializable
private data class WireAgentDmSummary(
    val id: String,
    val peerId: String? = null,
    val peerName: String,
    val peerDisplayName: String? = null,
    val peerAvatarUrl: String? = null,
    val lastMessageAt: String? = null,
    val lastMessagePreview: String? = null,
)

private fun WireAgentDmSummary.toDomain(): AgentDmSummary =
    AgentDmSummary(
        id = id,
        peerId = peerId,
        peerName = peerDisplayName?.takeIf { it.isNotBlank() } ?: peerName,
        peerAvatarUrl = peerAvatarUrl,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = lastMessagePreview,
    )

@Serializable
private data class WireAgentRemindersResponse(
    val reminders: List<WireAgentReminder> = emptyList(),
)

@Serializable
private data class WireAgentReminder(
    val reminderId: String,
    val ownerAgentId: String,
    val msgRef: String? = null,
    val msgPermalink: String? = null,
    val title: String,
    val fireAt: String,
    val firedAt: String? = null,
    val createdAt: String? = null,
    val status: String? = null,
    val recurrence: WireReminderRecurrence? = null,
)

@Serializable
private data class WireReminderRecurrence(
    val kind: String,
    val description: String,
)

fun parseSharedAgentReminders(
    bodyText: String,
    agentId: MemberId,
): AgentReminderSnapshot? = runCatching {
    AgentReminderSnapshot(
        agentId = agentId,
        reminders = memberAgentJson.decodeFromString(WireAgentRemindersResponse.serializer(), bodyText)
            .reminders
            .map { it.toDomain() },
    )
}.getOrNull()

private fun WireAgentReminder.toDomain(): AgentReminderSummary =
    AgentReminderSummary(
        id = reminderId,
        title = title,
        fireAt = fireAt,
        recurrenceDescription = recurrence?.description,
        messageRef = msgRef,
        messagePermalink = msgPermalink,
        ownerAgentId = ownerAgentId,
        status = status,
    )

@Serializable
private data class WireActivityLogEntry(
    val timestamp: Long,
    val entry: WireActivityEntry,
)

@Serializable
private data class WireActivityEntry(
    val kind: String,
    val activity: String? = null,
    val detail: String? = null,
    val summary: String? = null,
    val toolName: String? = null,
    val name: String? = null,
    val message: String? = null,
)

fun parseSharedAgentActivity(
    bodyText: String,
    agentId: MemberId,
): AgentActivitySnapshot? = runCatching {
    AgentActivitySnapshot(
        agentId = agentId,
        activity = memberAgentJson.decodeFromString(ListSerializer(WireActivityLogEntry.serializer()), bodyText)
            .mapIndexed { index, row -> row.toDomain(index) },
    )
}.getOrNull()

private fun WireActivityLogEntry.toDomain(index: Int): AgentActivityEntry {
    val title = entry.summary ?: entry.activity?.toAgentActivityTitle() ?: entry.kind.toAgentActivityTitle()
    val body = entry.detail ?: entry.message ?: entry.toolName ?: entry.name
    return AgentActivityEntry(
        id = "$timestamp:$index",
        type = entry.activity ?: entry.kind,
        title = title,
        body = body,
        timestamp = timestamp.toString(),
        details = listOfNotNull(
            body?.let { "Detail" to it },
            entry.toolName?.let { "Tool" to it },
        ),
    )
}

fun parseSharedCurrentAgentActivityEntries(
    bodyText: String,
    agentId: MemberId,
): List<AgentActivityEntry>? = runCatching {
    memberAgentJson.decodeFromString(ListSerializer(WireAgent.serializer()), bodyText)
        .filter { it.id == agentId.value }
        .mapNotNull { it.toCurrentActivityEntry() }
}.getOrNull()

private fun WireAgent.toCurrentActivityEntry(): AgentActivityEntry? {
    val type = activity ?: status
    val normalized = AgentStatus.fromWireValue(type) ?: return null
    if (normalized == AgentStatus.Offline) return null
    val detail = activityDetail?.takeIf { it.isNotBlank() } ?: normalized.label
    return AgentActivityEntry(
        id = "current:$id:$type:$detail",
        type = type,
        title = type.toAgentActivityTitle(),
        body = detail,
    )
}

data class SharedAgentWorkspaceLookup(
    val computerId: String?,
    val computerName: String?,
    val directWorkspaces: List<SettingsComputerWorkspaceSnapshot> = emptyList(),
    val fallbackWorkspaces: List<SettingsComputerWorkspaceSnapshot> = emptyList(),
)

fun parseSharedAgentWorkspaceLookup(
    bodyText: String,
    agentId: MemberId,
): SharedAgentWorkspaceLookup? = runCatching {
    val agent = memberAgentJson.decodeFromString(ListSerializer(WireAgent.serializer()), bodyText)
        .firstOrNull { it.id == agentId.value }
    agent?.let {
        SharedAgentWorkspaceLookup(
            computerId = it.runtimeProfile?.machineId ?: it.machineId,
            computerName = null,
            directWorkspaces = listOfNotNull(it.runtimeProfile?.toWorkspaceSnapshot()),
            fallbackWorkspaces = emptyList(),
        )
    }
}.getOrNull()

private fun WireAgentRuntimeProfile.toWorkspaceSnapshot(): SettingsComputerWorkspaceSnapshot? {
    val path = workspaceRefPath ?: workspacePathRefPath ?: return null
    val label = workspaceRefLabel ?: workspacePathRefLabel ?: path.substringAfterLast('/').ifBlank { path }
    return SettingsComputerWorkspaceSnapshot(
        id = path,
        name = label,
        path = path,
    )
}

@Serializable
private data class WireAgentSkillsResponse(
    val global: List<WireSkillInfo> = emptyList(),
    val workspace: List<WireSkillInfo> = emptyList(),
)

@Serializable
private data class WireSkillInfo(
    val name: String,
    val displayName: String,
    val description: String,
    val userInvocable: Boolean = true,
    val sourcePath: String? = null,
)

fun parseSharedAgentSkillsSnapshot(
    bodyText: String,
    agentId: MemberId,
): AgentSkillsSnapshot? = runCatching {
    val decoded = memberAgentJson.decodeFromString(WireAgentSkillsResponse.serializer(), bodyText)
    AgentSkillsSnapshot(
        agentId = agentId,
        groups = listOf(
            decoded.global.toSkillGroup("Global"),
            decoded.workspace.toSkillGroup("Workspace"),
        ).filter { group -> group.paths.any { it.skills.isNotEmpty() } },
    )
}.getOrNull()

private fun List<WireSkillInfo>.toSkillGroup(title: String): AgentSkillGroup =
    AgentSkillGroup(
        title = title,
        count = size,
        paths = groupBy { it.sourcePath ?: title }
            .map { (path, skills) ->
                AgentSkillPathGroup(
                    path = path,
                    count = skills.size,
                    skills = skills.map {
                        AgentSkillSummary(
                            name = it.displayName.takeIf { display -> display.isNotBlank() } ?: it.name,
                            detail = it.description.takeIf { description -> description.isNotBlank() },
                        )
                    },
                )
            },
    )

fun parseSharedChannelMemberSnapshot(
    bodyText: String,
    channelId: ChannelId,
): ChannelMemberSnapshot? = runCatching {
    val decoded = memberAgentJson.decodeFromString(WireChannelMembers.serializer(), bodyText)
    ChannelMemberSnapshot(
        channelId = channelId,
        members = (decoded.humans.map { it.toMemberProfile() } + decoded.agents.map { it.toMemberProfile() })
            .distinctBy { it.id.value },
        canAddMembers = true,
    )
}.getOrNull()

fun parseSharedChannelMemberActionResult(
    bodyText: String,
    channelId: ChannelId,
    memberId: MemberId,
    added: Boolean,
): ChannelMemberActionResult? = runCatching {
    val decoded = memberAgentJson.decodeFromString(WireChannelMemberActionResponse.serializer(), bodyText)
    if (decoded.ok) {
        ChannelMemberActionResult(channelId = channelId, memberId = memberId, member = null, added = added)
    } else {
        null
    }
}.getOrNull()

@Serializable
private data class WireChannelMemberActionResponse(
    val ok: Boolean,
)

private fun WireServerMember.toMemberProfile(): MemberProfile {
    val label = displayName?.takeIf { it.isNotBlank() } ?: name
    return MemberProfile(
        id = MemberId(userId),
        displayName = label,
        handle = name,
        kind = MemberKind.Human,
        description = description,
        roleDescription = role?.toSettingsLabel(),
        avatarUrl = avatarUrl,
        gravatarHash = gravatarHash,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = label.take(1).uppercase(),
        presence = Presence.Offline,
    )
}

private fun WireChannelHuman.toMemberProfile(): MemberProfile {
    val label = displayName?.takeIf { it.isNotBlank() } ?: name
    return MemberProfile(
        id = MemberId(id),
        displayName = label,
        handle = name,
        kind = MemberKind.Human,
        description = description,
        roleDescription = role?.toSettingsLabel(),
        avatarUrl = avatarUrl,
        gravatarHash = gravatarHash,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = label.take(1).uppercase(),
        presence = Presence.Offline,
    )
}

private fun WireAgent.toMemberProfile(): MemberProfile {
    val label = displayName?.takeIf { it.isNotBlank() } ?: name
    val statusValue = activity ?: status
    val agentStatus = AgentStatus.fromWireValue(statusValue) ?: AgentStatus.Offline
    return MemberProfile(
        id = MemberId(id),
        displayName = label,
        handle = name,
        kind = MemberKind.Agent,
        description = description,
        roleDescription = runtime,
        avatarUrl = avatarUrl,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = label.take(1).uppercase(),
        presence = agentStatus.presence,
        agentStatus = agentStatus,
        permissionBadges = listOfNotNull(
            runtime?.takeIf { it.isNotBlank() },
            machineId?.takeIf { it.isNotBlank() }?.let { "computer-id:$it" },
        ),
    )
}

private fun String.toSettingsLabel(): String =
    replace("_", " ")
        .replace("-", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun String.toAgentActivityTitle(): String =
    toSettingsLabel().ifBlank { "Agent activity" }
