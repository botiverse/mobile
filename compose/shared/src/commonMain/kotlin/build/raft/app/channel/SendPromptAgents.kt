package build.raft.app

import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile

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

/** One read-only message row in the Send-to-agent sheet's DM preview (web `PreviewMessage`). */
internal data class SendPromptPreviewMessage(
    val id: String,
    val senderName: String,
    val content: String,
)

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
