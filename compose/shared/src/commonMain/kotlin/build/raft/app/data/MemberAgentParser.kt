package build.raft.app.data

import build.raft.app.toRaftAvatarLabel
import build.raft.app.model.isExternalAgent
import build.raft.app.model.AgentStatus
import build.raft.app.model.AgentProfile
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelExternalMemberProfile
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val memberAgentJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
private data class WireServerMember(
    val userId: String,
    val serverId: String? = null,
    val serverName: String? = null,
    val serverSlug: String? = null,
    val email: String? = null,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val gravatarHash: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null,
    val joinedAt: String? = null,
    val membershipStatus: String? = null,
    val createdAgents: List<WireAgent> = emptyList(),
)

@Serializable
private data class WireServerMembersResponse(
    val members: List<WireServerMember> = emptyList(),
    val items: List<WireServerMember> = emptyList(),
    val results: List<WireServerMember> = emptyList(),
)

@Serializable
private data class WireServerMembersDataListResponse(
    val data: List<WireServerMember> = emptyList(),
)

@Serializable
private data class WireServerMembersDataResponse(
    val data: WireServerMembersResponse = WireServerMembersResponse(),
)

@Serializable
private data class WireChannelMembers(
    val humans: List<WireChannelHuman> = emptyList(),
    val agents: List<WireAgent> = emptyList(),
    val externalMembers: List<WireChannelExternalMember> = emptyList(),
)

@Serializable
private data class WireChannelHuman(
    val id: String,
    val serverId: String? = null,
    val serverName: String? = null,
    val serverSlug: String? = null,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val gravatarHash: String? = null,
    val avatarUrl: String? = null,
    val role: String? = null,
    val effectiveChannelRole: String? = null,
    val channelRole: String? = null,
    val channelAdminBasis: String? = null,
    val canChangeChannelRole: Boolean? = null,
    val serverRole: String? = null,
)

@Serializable
private data class WireChannelExternalMember(
    val id: String,
    val provider: String? = null,
    val displayName: String? = null,
    val handles: List<String> = emptyList(),
    val actorKind: String? = null,
    val avatarUrl: String? = null,
    val lastSeenAt: String? = null,
)

@Serializable
private data class WireAgent(
    val id: String,
    val serverId: String? = null,
    val serverName: String? = null,
    val serverSlug: String? = null,
    val userId: String? = null,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val status: String = "inactive",
    val runtime: String? = null,
    val external: Boolean? = null,
    val serverRole: String? = null,
    val effectiveChannelRole: String? = null,
    val channelRole: String? = null,
    val channelAdminBasis: String? = null,
    val canChangeChannelRole: Boolean? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val executionMode: String? = null,
    val computerId: String? = null,
    val computerName: String? = null,
    val computerHostname: String? = null,
    val daemonVersion: String? = null,
    val creatorType: String? = null,
    val creatorId: String? = null,
    val machineId: String? = null,
    val activity: String? = null,
    val activityDetail: String? = null,
    val deletedAt: String? = null,
    /**
     * External agents only (server sends it on `GET /agents` when
     * `runtime === "external"`): ISO-8601 `lastSeenAt` — the credential's
     * last use. Drives the online window; realtime `agent:seen` supersedes it.
     */
    val lastSeenAt: String? = null,
    /** slock `agents.managedByAppInstallId` (app-managed bot); null/absent for ordinary agents and guest projections. */
    val managedByAppInstallId: String? = null,
    /**
     * App Bots only (botiverse/slock #11273): the owning App, joined install -> OAuth client on
     * `GET /agents` and `GET /agents/:id` for non-guests; `null` when the join found no row.
     */
    val managedByApp: WireManagedByApp? = null,
    /**
     * App Bots only (botiverse/slock #11295): `false` while the Bot is removed from this server.
     * The directory keeps it so its old messages still resolve; absent on ordinary agents.
     */
    val inMemberLists: Boolean? = null,
    val runtimeProfile: WireAgentRuntimeProfileSummary? = null,
    val createdAgents: List<WireAgentCreatedSummary> = emptyList(),
    /**
     * Personal assistants only (botiverse/slock #10654): when a server owner or admin paused it, as
     * an ISO-8601 string; `null` while not paused, absent for other agents. `GET /agents` and
     * `GET /agents/:id` send it to everyone who sees the agent (`services/assistantLifecycleService.ts`
     * `projectAssistantPause`); who paused it (`assistantPausedBy`) is not read here.
     */
    val assistantPausedAt: String? = null,
)

/** slock `routes/agents.ts` `managedByApp`: `{ clientId, name, logoUrl, description, botStatusText }`. */
@Serializable
private data class WireManagedByApp(
    val clientId: String? = null,
    val name: String? = null,
    val logoUrl: String? = null,
)

@Serializable
private data class WireAgentsResponse(
    val agents: List<WireAgent> = emptyList(),
    val items: List<WireAgent> = emptyList(),
    val results: List<WireAgent> = emptyList(),
)

@Serializable
private data class WireAgentsDataListResponse(
    val data: List<WireAgent> = emptyList(),
)

@Serializable
private data class WireAgentsDataResponse(
    val data: WireAgentsResponse = WireAgentsResponse(),
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
private data class WireAgentRuntimeProfileSummary(
    val current: WireAgentRuntimeProfileSnapshot? = null,
    val migrationStatus: String? = null,
    val pending: JsonElement? = null,
)

@Serializable
private data class WireAgentRuntimeProfileSnapshot(
    val machineId: String? = null,
    val machineName: String? = null,
    val daemonVersion: String? = null,
    val runtime: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val executionMode: String? = null,
    val workspaceRef: JsonElement? = null,
    val workspacePathRef: JsonElement? = null,
)

fun parseSharedServerMemberSnapshot(
    bodyText: String,
    server: ServerSummary,
): MemberSnapshot? = runCatching {
    MemberSnapshot(
        server = server,
        members = decodeServerMembers(bodyText)
            .filter { member -> member.belongsToServerRoster(server.id) }
            .map { it.withSourceServer(server.id, server.name, server.slug) }
            .distinctBy { it.id.value },
    )
}.getOrNull()

fun parseSharedSettingsAgentsAsMembers(bodyText: String): List<MemberProfile>? = runCatching {
    decodeAgents(bodyText)
        .distinctBy { it.id.value }
}.getOrNull()

/**
 * One `GET /api/servers/:id/members` item, as an incremental-sync `server_member` row carries it
 * (botiverse/slock 0d030e341 routes/sync.ts: the list builder's own item). Null when the row does
 * not decode or names another server's roster.
 */
fun parseSharedServerMemberRow(row: JsonObject, server: ServerSummary): MemberProfile? = runCatching {
    memberAgentJson.decodeFromJsonElement(WireServerMember.serializer(), row).toMemberProfile()
}.getOrNull()
    ?.takeIf { it.belongsToServerRoster(server.id) }
    ?.withSourceServer(server.id, server.name, server.slug)

/** The outcome of one incremental-sync `agent` row (a `GET /api/agents` item). */
sealed interface SharedSyncedAgentRow {
    data class Member(val profile: MemberProfile) : SharedSyncedAgentRow

    /** The list filters deleted agents out; a row of one is a delete. */
    data object Deleted : SharedSyncedAgentRow

    data object Malformed : SharedSyncedAgentRow
}

fun parseSharedAgentRow(row: JsonObject): SharedSyncedAgentRow {
    val wire =
        runCatching { memberAgentJson.decodeFromJsonElement(WireAgent.serializer(), row) }.getOrNull()
            ?: return SharedSyncedAgentRow.Malformed
    if (wire.isDeletedAgent) return SharedSyncedAgentRow.Deleted
    return runCatching { SharedSyncedAgentRow.Member(wire.toMemberProfile()) }.getOrDefault(SharedSyncedAgentRow.Malformed)
}

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

fun parseSharedAgentChannels(
    bodyText: String,
    agentId: MemberId,
): AgentChannelSnapshot? = runCatching {
    AgentChannelSnapshot(
        agentId = agentId,
        channels = memberAgentJson.decodeFromString(
            ListSerializer(WireAgentChannelMembership.serializer()),
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
private data class WireAgentChannelMembership(
    val id: String,
    val name: String,
    val description: String? = null,
    val type: String = "channel",
    val createdAt: String? = null,
    val archivedAt: String? = null,
    val activityMuted: Boolean = false,
    val muteFromSeq: Long? = null,
)

private fun WireAgentChannelMembership.toDomain(): AgentChannelSummary =
    AgentChannelSummary(
        id = id,
        name = name,
        description = description,
        type = type,
        createdAt = createdAt,
        archivedAt = archivedAt,
        activityMuted = activityMuted,
        muteFromSeq = muteFromSeq,
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
    val activityKind: String? = null,
    val detail: String? = null,
    val detailKind: String? = null,
    val summary: String? = null,
    val toolName: String? = null,
    val toolInput: JsonElement? = null,
    val name: String? = null,
    val message: String? = null,
    val title: String? = null,
    val text: String? = null,
    val subagent: WireSubagentLineage? = null,
)

@Serializable
private data class WireSubagentLineage(
    val subagentType: String? = null,
)

fun parseSharedAgentActivity(
    bodyText: String,
    agentId: MemberId,
): AgentActivitySnapshot? = runCatching {
    AgentActivitySnapshot(
        agentId = agentId,
        activity = memberAgentJson.decodeFromString(ListSerializer(WireActivityLogEntry.serializer()), bodyText)
            .mapIndexedNotNull { index, row -> row.toDomain(index) },
    )
}.getOrNull()

private fun WireActivityLogEntry.toDomain(index: Int): AgentActivityEntry? =
    when (entry.kind) {
        "thinking" ->
            trajectoryEntry(
                index = index,
                type = "thinking",
                title = "Thinking",
                body = entry.text,
                isSubagent = entry.subagent != null,
                subagentType = entry.subagent?.subagentType?.takeIf { it.isNotBlank() },
                expandable = true
            )
        "tool_start" -> {
            val toolName = entry.toolName.orEmpty()
            if (toolName.shouldHideToolStartInActivityLog()) {
                null
            } else {
                val input = entry.toolInput.toToolInputPreview()
                trajectoryEntry(
                    index = index,
                    type = "tool_start",
                    title = toolName.toToolLogLabel(),
                    body = input,
                    details = listOfNotNull(
                        toolName.takeIf { it.isNotBlank() }?.let { "Tool" to it },
                        input?.let { "Input" to it },
                    ),
                    toolName = toolName.takeIf { it.isNotBlank() },
                    toolInput = input,
                    isSubagent = entry.subagent != null,
                    subagentType = entry.subagent?.subagentType?.takeIf { it.isNotBlank() }
                )
            }
        }
        "text" ->
            trajectoryEntry(
                index = index,
                type = "text",
                title = "Output",
                body = entry.text,
                isSubagent = entry.subagent != null,
                subagentType = entry.subagent?.subagentType?.takeIf { it.isNotBlank() },
                expandable = true
            )
        "slock_action" ->
            trajectoryEntry(
                index = index,
                type = "slock_action",
                title = entry.title?.takeIf { it.isNotBlank() } ?: "Raft action",
                body = entry.text ?: entry.message,
                expandable = true,
                refTextEnabled = true
            )
        "system" ->
            trajectoryEntry(
                index = index,
                type = "system",
                title = entry.title?.takeIf { it.isNotBlank() } ?: "System",
                body = entry.text ?: entry.message,
                expandable = true,
                refTextEnabled = true
            )
        "compaction_started" ->
            trajectoryEntry(
                index = index,
                type = "compaction_started",
                title = "Context Compaction Started"
            )
        "compaction_finished" ->
            trajectoryEntry(
                index = index,
                type = "compaction_finished",
                title = "Context Compaction Finished"
            )
        "status", "agent_status" -> {
            val display = resolveTrajectoryStatusDisplay(
                activity = entry.activityKind ?: entry.activity,
                detail = entry.detail.orEmpty(),
                detailKind = entry.detailKind,
            )
            trajectoryEntry(
                index = index,
                type = "status",
                title = display.first,
                body = display.second.takeIf { it.isNotBlank() },
                statusActivity = entry.activityKind ?: entry.activity,
                statusDetail = entry.detail,
                statusDetailKind = entry.detailKind,
                isSubagent = entry.subagent != null,
                subagentType = entry.subagent?.subagentType?.takeIf { it.isNotBlank() },
                refTextEnabled = true
            )
        }
        else ->
            trajectoryEntry(
                index = index,
                type = entry.kind,
                title = entry.summary ?: entry.activity?.toAgentActivityTitle() ?: entry.kind.toAgentActivityTitle(),
                body = entry.detail ?: entry.message ?: entry.text ?: entry.toolName ?: entry.name,
                details = listOfNotNull(
                    (entry.detail ?: entry.message ?: entry.text)?.let { "Detail" to it },
                    entry.toolName?.let { "Tool" to it },
                ),
            )
    }

private fun WireActivityLogEntry.trajectoryEntry(
    index: Int,
    type: String,
    title: String,
    body: String? = null,
    details: List<Pair<String, String>> = body?.let { listOf("Detail" to it) } ?: emptyList(),
    toolName: String? = null,
    toolInput: String? = null,
    statusActivity: String? = null,
    statusDetail: String? = null,
    statusDetailKind: String? = null,
    isSubagent: Boolean = false,
    subagentType: String? = null,
    expandable: Boolean = false,
    refTextEnabled: Boolean = false
): AgentActivityEntry =
    AgentActivityEntry(
        id = "$timestamp:$index",
        type = type,
        title = title,
        body = body?.sanitizeActivityText(),
        timestamp = timestamp.toString(),
        details = details.map { (label, value) -> label to value.sanitizeActivityText() },
        toolName = toolName,
        toolInput = toolInput?.sanitizeActivityText(),
        statusActivity = statusActivity,
        statusDetail = statusDetail,
        statusDetailKind = statusDetailKind,
        isSubagent = isSubagent,
        subagentType = subagentType,
        expandable = expandable,
        refTextEnabled = refTextEnabled
    )

fun parseSharedCurrentAgentActivityEntries(
    bodyText: String,
    agentId: MemberId,
): List<AgentActivityEntry>? = runCatching {
    memberAgentJson.decodeFromString(ListSerializer(WireAgent.serializer()), bodyText)
        .filter { it.id == agentId.value }
        .mapNotNull { it.toCurrentActivityEntry() }
}.getOrNull()

private fun WireAgent.toCurrentActivityEntry(): AgentActivityEntry? {
    return currentAgentActivityEntry(
        agentId = MemberId(id),
        activity = activity,
        status = status,
        activityDetail = activityDetail
    )
}

internal fun currentAgentActivityEntry(
    agentId: MemberId,
    activity: String?,
    status: String?,
    activityDetail: String?
): AgentActivityEntry? {
    val type = activity ?: status
    val normalized = normalizedAgentRuntimeStatus(activity = activity, lifecycleStatus = status) ?: return null
    if (normalized == AgentStatus.Offline) return null
    val detail = activityDetail?.takeIf { it.isNotBlank() } ?: normalized.label
    return AgentActivityEntry(
        id = "current:${agentId.value}:$type:$detail",
        type = "status",
        title = resolveTrajectoryStatusDisplay(type, detail).first,
        body = detail,
        statusActivity = type,
        statusDetail = detail,
        refTextEnabled = true
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
            computerId = firstNonBlank(it.computerId, it.machineId, it.runtimeProfile?.current?.machineId),
            computerName = firstNonBlank(it.computerName, it.computerHostname, it.runtimeProfile?.current?.machineName),
            directWorkspaces = listOfNotNull(it.runtimeProfile?.current?.toWorkspaceSnapshot()),
            fallbackWorkspaces = emptyList(),
        )
    }
}.getOrNull()

private fun WireAgentRuntimeProfileSnapshot.toWorkspaceSnapshot(): SettingsComputerWorkspaceSnapshot? =
    workspaceRef.toWorkspaceSnapshot() ?: workspacePathRef.toWorkspaceSnapshot()

private fun JsonElement?.toWorkspaceSnapshot(): SettingsComputerWorkspaceSnapshot? =
    when (this) {
        is JsonObject -> {
            val path = stringValue("path") ?: return null
            val label = stringValue("label") ?: path.substringAfterLast('/').ifBlank { path }
            SettingsComputerWorkspaceSnapshot(
                id = path,
                name = label,
                path = path,
            )
        }
        is JsonPrimitive -> {
            val path = contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            SettingsComputerWorkspaceSnapshot(
                id = path,
                name = path.substringAfterLast('/').ifBlank { path },
                path = path,
            )
        }
        else -> null
    }

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

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
        externalMembers = decoded.externalMembers.map { it.toModel() },
        canAddMembers = false,
        liveActivityAgentIds =
            decoded.agents
                // Only activity that resolves to a runtime status: the lifecycle word
                // `active` (or an unknown value) writes no fact, so it must not count.
                .filter { normalizedAgentRuntimeStatus(activity = it.activity, lifecycleStatus = null) != null }
                .mapNotNull { it.id.trim().takeIf(String::isNotBlank) }
                .toSet(),
    )
}.getOrNull()

private fun WireChannelExternalMember.toModel(): ChannelExternalMemberProfile {
    val label = displayName?.takeIf { it.isNotBlank() } ?: handles.firstOrNull()?.let { "@$it" } ?: id
    return ChannelExternalMemberProfile(
        id = id,
        provider = provider?.takeIf { it.isNotBlank() },
        displayName = label,
        handles = handles.filter { it.isNotBlank() },
        actorKind = actorKind?.takeIf { it.isNotBlank() },
        avatarUrl = avatarUrl,
        avatarLabel = label.toRaftAvatarLabel(),
        lastSeenAt = lastSeenAt?.takeIf { it.isNotBlank() },
    )
}

private fun decodeServerMembers(bodyText: String): List<MemberProfile> =
    decodeCandidates(
        { memberAgentJson.decodeFromString(ListSerializer(WireServerMember.serializer()), bodyText) },
        { memberAgentJson.decodeFromString(WireServerMembersResponse.serializer(), bodyText).allMembers },
        { memberAgentJson.decodeFromString(WireServerMembersDataListResponse.serializer(), bodyText).data },
        { memberAgentJson.decodeFromString(WireServerMembersDataResponse.serializer(), bodyText).data.allMembers },
        { listOf(memberAgentJson.decodeFromString(WireServerMember.serializer(), bodyText)) }
    ).map { it.toMemberProfile() }

private fun decodeAgents(bodyText: String): List<MemberProfile> =
    decodeCandidates(
        { memberAgentJson.decodeFromString(ListSerializer(WireAgent.serializer()), bodyText) },
        { memberAgentJson.decodeFromString(WireAgentsResponse.serializer(), bodyText).allAgents },
        { memberAgentJson.decodeFromString(WireAgentsDataListResponse.serializer(), bodyText).data },
        { memberAgentJson.decodeFromString(WireAgentsDataResponse.serializer(), bodyText).data.allAgents },
        { listOf(memberAgentJson.decodeFromString(WireAgent.serializer(), bodyText)) }
    )
        .filterNot { it.isDeletedAgent }
        .map { it.toMemberProfile() }

private val WireServerMembersResponse.allMembers: List<WireServerMember>
    get() = members + items + results

private val WireAgentsResponse.allAgents: List<WireAgent>
    get() = agents + items + results

private fun <T> decodeCandidates(vararg candidates: () -> List<T>): List<T> {
    candidates.forEach { candidate ->
        val decoded = runCatching(candidate).getOrNull().orEmpty()
        if (decoded.isNotEmpty()) return decoded
    }
    return emptyList()
}

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
        serverId = serverId.toServerIdOrNull(),
        serverName = serverName?.takeIf { it.isNotBlank() },
        serverSlug = serverSlug?.takeIf { it.isNotBlank() },
        description = description,
        roleDescription = role?.toSettingsLabel(),
        serverRole = role?.normalizedServerRoleOrNull(),
        email = email?.takeIf { it.isNotBlank() },
        gravatarHash = gravatarHash?.takeIf { it.isNotBlank() },
        avatarUrl = avatarUrl,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = label.toRaftAvatarLabel(),
        presence = Presence.Offline,
        joinedAt = joinedAt,
        membershipStatus = membershipStatus?.takeIf { it.isNotBlank() }
            ?: joinedAt?.let { "active" },
        createdAgents = createdAgents.filterNot { it.isDeletedAgent }.map { it.toAgentProfile() },
    )
}

private fun WireChannelHuman.toMemberProfile(): MemberProfile {
    val label = displayName?.takeIf { it.isNotBlank() } ?: name
    return MemberProfile(
        id = MemberId(id),
        displayName = label,
        handle = name,
        kind = MemberKind.Human,
        serverId = serverId.toServerIdOrNull(),
        serverName = serverName?.takeIf { it.isNotBlank() },
        serverSlug = serverSlug?.takeIf { it.isNotBlank() },
        description = description,
        roleDescription = (role ?: serverRole)?.toSettingsLabel(),
        serverRole = (serverRole ?: role)?.normalizedServerRoleOrNull(),
        effectiveChannelRole = effectiveChannelRole?.takeIf { it.isNotBlank() },
        channelRole = channelRole?.takeIf { it.isNotBlank() },
        channelAdminBasis = channelAdminBasis?.takeIf { it.isNotBlank() },
        canChangeChannelRole = canChangeChannelRole,
        gravatarHash = gravatarHash?.takeIf { it.isNotBlank() },
        avatarUrl = avatarUrl,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = label.toRaftAvatarLabel(),
        presence = Presence.Offline,
    )
}

private fun WireAgent.toMemberProfile(): MemberProfile {
    val label = displayName?.takeIf { it.isNotBlank() } ?: name
    val agentStatus = normalizedAgentRuntimeStatus(activity = activity, lifecycleStatus = status)
    return MemberProfile(
        id = MemberId(id),
        displayName = label,
        handle = name,
        kind = MemberKind.Agent,
        serverId = serverId.toServerIdOrNull(),
        serverName = serverName?.takeIf { it.isNotBlank() },
        serverSlug = serverSlug?.takeIf { it.isNotBlank() },
        description = description,
        roleDescription = runtime,
        isExternal = isExternalAgent(external, runtime),
        serverRole = serverRole?.normalizedServerRoleOrNull(),
        effectiveChannelRole = effectiveChannelRole?.takeIf { it.isNotBlank() },
        channelRole = channelRole?.takeIf { it.isNotBlank() },
        channelAdminBasis = channelAdminBasis?.takeIf { it.isNotBlank() },
        canChangeChannelRole = canChangeChannelRole,
        avatarUrl = avatarUrl,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = label.toRaftAvatarLabel(),
        presence =
            if (deletedAt.isNullOrBlank()) {
                agentStatus?.presence ?: Presence.Offline
            } else {
                Presence.Offline
            },
        agentStatus = agentStatus,
        agentLifecycleStatus = status.trim().lowercase().takeIf { it.isNotBlank() },
        membershipStatus = if (deletedAt.isNullOrBlank()) null else "deleted",
        agentDeletedAt = deletedAt?.takeIf { it.isNotBlank() },
        lastSeenAt = lastSeenAt?.takeIf { it.isNotBlank() },
        assistantPausedAt = assistantPausedAt?.takeIf { it.isNotBlank() },
        managedByAppInstallId = managedByAppInstallId?.takeIf { it.isNotBlank() },
        managedByAppName = managedByApp?.name?.takeIf { it.isNotBlank() },
        inMemberLists = inMemberLists,
        model = firstNonBlank(model, runtimeProfile?.current?.model),
        permissionBadges = listOfNotNull(
            runtime?.takeIf { it.isNotBlank() },
            firstNonBlank(computerName, computerHostname, runtimeProfile?.current?.machineName)
                ?.let { "computer:$it" },
            firstNonBlank(computerId, machineId, runtimeProfile?.current?.machineId)
                ?.let { "computer-id:$it" },
        ),
    )
}

private fun String?.toServerIdOrNull(): ServerId? =
    this?.takeIf { it.isNotBlank() }?.let(::ServerId)

private fun String?.normalizedServerRoleOrNull(): String? =
    this?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

private fun MemberProfile.belongsToServerRoster(serverId: ServerId): Boolean {
    val sourceServerId = this.serverId?.value?.trim().orEmpty()
    return sourceServerId.isEmpty() || sourceServerId == serverId.value.trim()
}

private fun MemberProfile.withSourceServer(
    serverId: ServerId,
    serverName: String,
    serverSlug: String?
): MemberProfile =
    copy(
        serverId = this.serverId ?: serverId,
        serverName = this.serverName ?: serverName.takeIf { it.isNotBlank() },
        serverSlug = this.serverSlug ?: serverSlug?.takeIf { it.isNotBlank() }
    )

private fun WireAgent.toAgentProfile(): AgentProfile {
    val label = displayName?.takeIf { it.isNotBlank() } ?: name
    val agentStatus = normalizedAgentRuntimeStatus(activity = activity, lifecycleStatus = status)
    return AgentProfile(
        id = MemberId(id),
        userId = userId ?: id,
        displayName = label,
        handle = name,
        description = description,
        roleDescription = runtime,
        avatarUrl = avatarUrl,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = label.toRaftAvatarLabel(),
        presence = agentStatus?.presence ?: Presence.Offline,
        agentStatus = agentStatus,
        statusLabel = agentStatus?.label,
        runtime = runtime,
        isExternal = isExternalAgent(external, runtime),
        model = model,
        reasoning = reasoningEffort,
        permissionBadges = listOfNotNull(
            runtime?.takeIf { it.isNotBlank() },
            firstNonBlank(computerName, computerHostname, runtimeProfile?.current?.machineName)
                ?.let { "computer:$it" },
            firstNonBlank(computerId, machineId, runtimeProfile?.current?.machineId)
                ?.let { "computer-id:$it" },
        ),
    )
}

// task #135: liveness may only come from a runtime `activity` fact. The wire
// `status` field is the ACCOUNT lifecycle ("active" = not archived/stopped) —
// it stays "active" while the agent's machine is offline, so mapping it to a
private val WireAgent.isDeletedAgent: Boolean
    get() = !deletedAt.isNullOrBlank()

private fun resolveTrajectoryStatusDisplay(
    activity: String?,
    detail: String,
    detailKind: String? = null,
): Pair<String, String> {
    if (activity == "working" && (detailKind == "starting" || detail == "Starting…")) {
        return "Starting" to ""
    }
    if (activity == "online") {
        val lifecycleTitle = when (detailKind) {
            "computer_started" -> "Started"
            "computer_restarted" -> "Restarted"
            "computer_upgraded" -> "Upgraded"
            else -> null
        }
        if (lifecycleTitle != null) return lifecycleTitle to detail
    }
    if (activity == "error" && detailKind == "computer_operation_failed") {
        return "Computer operation failed" to detail
    }
    if (activity == "offline") {
        val primary =
            when {
                detailKind == "stopped" -> "Stopped"
                detailKind == "machine_disconnected" -> "Disconnected"
                detailKind == "runtime_crashed" -> "Crashed"
                detail == "Stopped" -> "Stopped"
                detail == "Machine disconnected" -> "Disconnected"
                detail.startsWith("Crashed") -> "Crashed"
                else -> "Offline"
            }
        val secondary = when {
            primary == "Crashed" -> detail.removePrefix("Crashed").trim().removeSurrounding("(", ")")
            primary == "Stopped" && detail.isNotBlank() && detail != "Stopped" -> detail
            else -> ""
        }
        return primary to secondary
    }
    val primary =
        when (activity) {
            "online" -> "Idle"
            "thinking" -> "Thinking"
            "working" -> "Working"
            "error" -> "Error"
            else -> activity?.toAgentActivityTitle() ?: "Status"
        }
    return primary to detail
}

private fun String.shouldHideToolStartInActivityLog(): Boolean =
    normalizedToolName() == "send_message"

private fun String.toToolLogLabel(): String {
    val normalized = normalizedToolName()
    return when (normalized) {
        "read_file" -> "Read file"
        "write_file" -> "Write file"
        "upload_file" -> "Upload file"
        "view_file" -> "View file"
        "schedule_reminder" -> "Schedule reminder"
        "list_reminders" -> "List reminders"
        "cancel_reminder" -> "Cancel reminder"
        "run_command" -> "Run command"
        else -> normalized.toAgentActivityTitle()
    }
}

private fun String.normalizedToolName(): String =
    trim()
        .substringAfterLast("__")
        .substringAfterLast("/")
        .substringAfterLast(".")
        .ifBlank { trim() }

private fun JsonElement?.toToolInputPreview(): String? {
    if (this == null || this is JsonNull) return null
    val raw =
        when (this) {
            is JsonPrimitive -> contentOrNull.orEmpty()
            is JsonObject -> {
                val keys =
                    keys
                        .filterNot { it.isSensitiveActivityKey() }
                        .take(4)
                        .joinToString(", ")
                if (keys.isBlank()) "Structured input" else "Structured input: $keys"
            }
            else -> "Structured input"
        }
    return raw.sanitizeActivityText().takeIf { it.isNotBlank() }
}

private fun String.isSensitiveActivityKey(): Boolean {
    val lower = lowercase()
    return lower == "authorization" ||
        lower == "cookie" ||
        lower.contains("token") ||
        lower.contains("password") ||
        lower.contains("secret") ||
        lower.contains("credential")
}

private fun String.sanitizeActivityText(): String {
    val trimmed = trim()
    if (trimmed.isBlank()) return ""
    val lower = trimmed.lowercase()
    val credentialLike =
        "authorization" in lower ||
            "bearer " in lower ||
            "cookie" in lower ||
            "access_token" in lower ||
            "refresh_token" in lower ||
            "password" in lower ||
            "sk_agent_" in lower ||
            "sk_machine_" in lower
    if (credentialLike) return "[redacted]"
    return if (trimmed.length > 1200) trimmed.take(1200) + "…" else trimmed
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }

private fun String.toSettingsLabel(): String =
    replace("_", " ")
        .replace("-", " ")
        .replace(".", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun String.toAgentActivityTitle(): String =
    toSettingsLabel().ifBlank { "Agent activity" }
