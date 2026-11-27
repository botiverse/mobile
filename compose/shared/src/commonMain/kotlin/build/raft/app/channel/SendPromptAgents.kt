package build.raft.app

import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.thread.ThreadDateDividerDay
import build.raft.app.thread.threadLocalEpochDay

/**
 * How [defaultSendPromptAgent] chose the suggested agent (matches web
 * `SEND_PROMPT_DEFAULT_AGENTS` in botiverse/slock #16226).
 */
internal enum class SendPromptDefaultAgentKind(val wire: String) {
    OfficialOnboarding("official_onboarding"),
    RecentAdmin("recent_admin"),
    RecentMember("recent_member"),
    FirstAgent("first_agent"),
    None("none"),
}

internal data class SendPromptAgentSelection<A>(
    val agent: A?,
    val kind: SendPromptDefaultAgentKind,
)

/** One read-only message row in the Send-to-agent dialog's DM look-back (web `SendPromptDialog`, slock #16396). */
internal data class SendPromptPreviewMessage(
    val id: String,
    val senderName: String,
    val content: String,
    val senderId: String = "",
    val senderKind: MemberKind = MemberKind.Human,
    val senderAvatarWireValue: String? = null,
    val senderAvatarLabel: String = senderName,
    val createdAtEpochMillis: Long? = null,
    val timestampLabel: String = "",
    val isSystem: Boolean = false,
)

/** State of the Send-to-agent DM look-back (null/Loading, Loaded, or Failed; slock #16396). */
internal sealed interface SendPromptDmPreviewResult {
    data object Loading : SendPromptDmPreviewResult
    data class Loaded(val messages: List<SendPromptPreviewMessage>) : SendPromptDmPreviewResult
    data object Failed : SendPromptDmPreviewResult
}

/** Per-row grouping & day divider state for the Send-to-agent DM look-back (web `computeMessageGrouping`). */
internal data class SendPromptPreviewRowState(
    val message: SendPromptPreviewMessage,
    val showDayDivider: Boolean,
    val day: ThreadDateDividerDay?,
    val showHeader: Boolean,
)

/**
 * Groups consecutive same-sender messages on the same local calendar day and marks day boundaries
 * (matches web `computeMessageGrouping` in `packages/web/src/components/message/messageGrouping.ts`).
 */
internal fun computeSendPromptPreviewGrouping(
    messages: List<SendPromptPreviewMessage>,
    timeContext: RaftTimeContext? = null,
): List<SendPromptPreviewRowState> {
    if (messages.isEmpty()) return emptyList()
    val offsetMinutes = timeContext?.localOffsetMinutes ?: 0
    val todayEpochDay = timeContext?.todayEpochDay
    var prevDay: Long? = null
    return messages.mapIndexed { index, message ->
        val prev = if (index > 0) messages[index - 1] else null
        val epochDay = message.createdAtEpochMillis?.let { threadLocalEpochDay(it, offsetMinutes) }
        val showDayDivider = epochDay != null && (prevDay == null || epochDay != prevDay)
        if (epochDay != null) prevDay = epochDay
        val continuesPrev =
            prev != null &&
                !showDayDivider &&
                !message.isSystem &&
                !prev.isSystem &&
                message.senderId.isNotEmpty() &&
                message.senderId == prev.senderId &&
                message.senderKind == prev.senderKind
        SendPromptPreviewRowState(
            message = message,
            showDayDivider = showDayDivider,
            day = if (showDayDivider && epochDay != null) ThreadDateDividerDay(epochDay, todayEpochDay) else null,
            showHeader = !continuesPrev && !message.isSystem,
        )
    }
}

/**
 * Agents a person can send a prompt to (matches web `sendPromptRecipients` in
 * `packages/web/src/features/sendPrompt/sendPromptAgents.ts`, slock #16226):
 * live (`deletedAt == null`), not a `raftbenchService` agent, and either not a personal
 * assistant (`assistantOwnerUserId == null`) or owned by [currentUserId].
 */
internal fun sendPromptRecipients(
    members: List<MemberProfile>,
    currentUserId: String?,
): List<MemberProfile> {
    val cleanUserId = currentUserId?.trim()?.takeIf(String::isNotEmpty)
    return members.filter { member ->
        member.kind == MemberKind.Agent &&
            member.agentDeletedAt.isNullOrBlank() &&
            member.membershipStatus != "deleted" &&
            !member.raftbenchService &&
            (member.assistantOwnerUserId.isNullOrBlank() ||
                (cleanUserId != null && member.assistantOwnerUserId == cleanUserId))
    }
}

/**
 * Agent peer IDs ordered most recently active first from Home's DM rows (matches web
 * `SendPromptDialog.tsx` `recentAgentIds`: agent DMs with non-blank activity timestamp,
 * sorted descending).
 */
internal fun recentSendPromptAgentIds(directMessages: List<HomeDirectMessageRow>): List<String> =
    directMessages
        .asSequence()
        .filter { dm ->
            dm.kind == ConversationKind.DirectMessage &&
                dm.directMessagePeerType.parsedMemberKindOrNull(MemberKindWireContract.InternalActor) == MemberKind.Agent &&
                !dm.presenceMemberId?.value.isNullOrBlank() &&
                !dm.lastMessageAt.isNullOrBlank()
        }
        .sortedByDescending { it.lastMessageAt.orEmpty() }
        .mapNotNull { it.presenceMemberId?.value?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .toList()

/** Maps each agent peer ID to its existing DM channel ID when Home lists one. */
internal fun sendPromptDmChannelByAgent(directMessages: List<HomeDirectMessageRow>): Map<String, ChannelId> =
    buildMap {
        for (dm in directMessages) {
            if (
                dm.kind == ConversationKind.DirectMessage &&
                dm.directMessagePeerType.parsedMemberKindOrNull(MemberKindWireContract.InternalActor) == MemberKind.Agent &&
                dm.channelId.value.isNotBlank()
            ) {
                val peerId = dm.presenceMemberId?.value?.trim()?.takeIf(String::isNotEmpty) ?: continue
                if (peerId !in this) put(peerId, dm.channelId)
            }
        }
    }

/**
 * The agent the sheet suggests (matches web `defaultSendPromptAgent` in
 * `packages/web/src/features/sendPrompt/sendPromptAgents.ts`, slock #16226):
 * 1. The server's [officialOnboardingAgentId] (if in [recipients])
 * 2. Else the `admin` agent DM'd most recently
 * 3. Else the agent DM'd most recently
 * 4. Else the first recipient in [recipients]
 * 5. Else null (`None`)
 *
 * [recentAgentIds] is ordered most recent first.
 */
internal fun defaultSendPromptAgent(
    recipients: List<MemberProfile>,
    officialOnboardingAgentId: String?,
    recentAgentIds: List<String>,
): SendPromptAgentSelection<MemberProfile> {
    val byId = recipients.associateBy { it.id.value }
    val official = officialOnboardingAgentId?.trim()?.takeIf(String::isNotEmpty)?.let(byId::get)
    if (official != null) {
        return SendPromptAgentSelection(official, SendPromptDefaultAgentKind.OfficialOnboarding)
    }
    val recent = recentAgentIds.mapNotNull(byId::get)
    val recentAdmin = recent.firstOrNull { it.serverRole.equals("admin", ignoreCase = true) }
    if (recentAdmin != null) {
        return SendPromptAgentSelection(recentAdmin, SendPromptDefaultAgentKind.RecentAdmin)
    }
    val recentMember = recent.firstOrNull()
    if (recentMember != null) {
        return SendPromptAgentSelection(recentMember, SendPromptDefaultAgentKind.RecentMember)
    }
    val first = recipients.firstOrNull()
    if (first != null) {
        return SendPromptAgentSelection(first, SendPromptDefaultAgentKind.FirstAgent)
    }
    return SendPromptAgentSelection(null, SendPromptDefaultAgentKind.None)
}

/**
 * The picker's order (matches web `orderSendPromptAgents` in botiverse/slock #16396):
 * the suggested agent first (no label on it — its place says it), then the agents DM'd most
 * recently, then the rest in their listed order.
 */
internal fun orderSendPromptAgents(
    recipients: List<MemberProfile>,
    suggestedId: String?,
    recentAgentIds: List<String>,
): List<MemberProfile> {
    val recency = recentAgentIds.withIndex().associate { (index, id) -> id to index }
    return recipients.sortedBy { agent ->
        when {
            suggestedId != null && agent.id.value == suggestedId -> -1
            else -> recency[agent.id.value] ?: recentAgentIds.size
        }
    }
}

/**
 * Whether [AppShellSendPromptHost] should wait before mounting the sheet so the initial default
 * agent selection sees the server's agent list (matches web `SendPromptLinkIntent.tsx` waiting on
 * `agentsLoading`). Never stalls once loaded or errored.
 */
internal fun shouldWaitForSendPromptAgents(entry: SharedServerMembersEntry): Boolean =
    when (entry.status) {
        SharedServerMembersLoadStatus.Idle -> true
        SharedServerMembersLoadStatus.InFlight -> entry.members.isEmpty()
        SharedServerMembersLoadStatus.Loaded -> entry.seededFromDurable && entry.members.isEmpty()
        SharedServerMembersLoadStatus.Error -> false
    }
