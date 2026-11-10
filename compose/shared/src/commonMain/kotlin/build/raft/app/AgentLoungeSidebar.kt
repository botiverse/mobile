package build.raft.app

import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.agentLoungeSidebarEntry
import build.raft.app.data.loungeAgentDisplayName

/*
 * Agent Lounge v0: a Lounge's own row in the Home sidebar (web slock #13875 / #13876 / #13880:
 * features/sidebar/hooks/useSidebarItems.ts `loungeEntryById`, useSidebarProjection.ts
 * `dmSectionLounges` / the Direct Messages rows, components/layout/AgentLoungeRow.tsx,
 * menus/SidebarRowContextMenu.tsx `lounge`).
 *
 * - Listed once you joined it or someone mentioned you there ([agentLoungeSidebarEntry]),
 *   whether or not its agent's DM row is shown.
 * - In Direct Messages, sorted by the section's mode together with the DMs; with Manual order the
 *   Lounges follow every DM, latest activity first, and cannot be dragged (they have no place in
 *   `dmOrder`, `channelOrder` or a custom section).
 * - Pinned (`{kind: "channel", id: <lounge id>}`): only under Pinned.
 * - Closed (its id in `hiddenDmIds`, as a closed DM's): shown again while it has unread or an @
 *   for you (display only); opening it takes it off `hiddenDmIds`
 *   ([build.raft.app.data.SharedHomeIntent.ReopenClosedConversation]), and so does your own post
 *   there (the server drops it on send; Home follows locally).
 */

/**
 * The Lounges with a sidebar row here, as Home rows ([RaftHomeConversationItem.agentLounge] set):
 * every Lounge an agent DM row carries or this client knows ([known], this server's), listed per
 * [agentLoungeSidebarEntry]. Its unread and @ are the DM row's summary; its mute and recency come
 * from the Lounge's own row ([RaftAgentLounge]). [title] names it ("{Name}'s Lounge").
 */
internal fun homeSidebarAgentLoungeItems(
    enabled: Boolean,
    directMessages: List<HomeDirectMessageRow>,
    known: Map<String, RaftAgentLounge>,
    markedUnreadIds: Set<String>,
    title: (agentName: String) -> String,
): List<RaftHomeConversationItem> {
    if (!enabled) return emptyList()
    val agentDmByAgentId =
        directMessages
            .filter { it.directMessagePeerType.equals("agent", ignoreCase = true) }
            .mapNotNull { dm -> dm.presenceMemberId?.value?.takeIf { it.isNotBlank() }?.let { it to dm } }
            .toMap()
    val knownByAgentId = known.values.associateBy { it.agentId }
    val agentIds = LinkedHashSet<String>()
    agentDmByAgentId.forEach { (agentId, dm) -> if (dm.lounge != null) agentIds += agentId }
    agentIds += knownByAgentId.keys
    return agentIds.mapNotNull { agentId ->
        val dm = agentDmByAgentId[agentId]
        val summary = dm?.lounge
        val lounge = knownByAgentId[agentId] ?: summary?.let { known[it.channelId.value] }
        val entry = agentLoungeSidebarEntry(summary, lounge) ?: return@mapNotNull null
        val channelId = entry.channelId.value
        val agentName = loungeAgentDisplayName(dm?.name ?: lounge?.agentName, lounge?.channelName.orEmpty())
        RaftHomeConversationItem(
            id = channelId,
            name = title(agentName),
            kind = "channel",
            channelType = "channel",
            unreadCount = entry.unreadCount,
            lastMessageAt = lounge?.lastMessageAt,
            avatarLabel = agentName.take(2).uppercase(),
            avatarWireValue = dm?.directMessagePeerAvatarWireValue,
            presenceMemberId = agentId,
            // A Lounge's unread reads like a DM's (web getDmUnreadIndicatorState), joined or not.
            joined = true,
            archived = lounge?.archived == true,
            activityMuted = lounge?.activityMuted == true,
            activityMuteSupported = lounge?.canToggleActivityMute == true,
            mentioned = entry.mentioned,
            markedUnread = channelId in markedUnreadIds,
            agentLounge =
                RaftHomeAgentLoungeRow(
                    channelId = channelId,
                    unreadCount = entry.unreadCount,
                    mentioned = entry.mentioned,
                    agentId = agentId,
                    agentName = agentName,
                    joined = lounge?.joined == true || summary?.joined == true,
                ),
        )
    }
}

/**
 * The Lounge rows Direct Messages shows: not the ones Pinned shows ([shownUnderPinned]), and a
 * closed one ([hiddenDmIds]) only while it has unread or an @ for you (web #13880
 * `closedLoungeUnreadIds`: display only, nothing is written).
 */
internal fun homeSidebarVisibleAgentLounges(
    lounges: List<RaftHomeConversationItem>,
    shownUnderPinned: Set<String>,
    hiddenDmIds: Set<String>,
): List<RaftHomeConversationItem> =
    lounges.filter { lounge ->
        lounge.id !in shownUnderPinned &&
            (lounge.id !in hiddenDmIds || lounge.unreadCount > 0 || lounge.mentioned)
    }

/**
 * The Direct Messages rows: [directMessages] (already in the section's order) and [lounges],
 * sorted together by [mode]. Manual order is the DMs' own: the Lounges follow them, latest
 * activity first (web useSidebarProjection `dmSectionRows`).
 */
internal fun homeSidebarDirectMessageSectionRows(
    directMessages: List<RaftHomeConversationItem>,
    lounges: List<RaftHomeConversationItem>,
    mode: RaftSidebarSortMode,
): List<RaftHomeConversationItem> {
    if (lounges.isEmpty()) return directMessages
    if (mode == RaftSidebarSortMode.Manual) {
        return directMessages + lounges.sortedForSidebar(RaftSidebarSortMode.Recent) { it.name }
    }
    return (directMessages + lounges).sortedForSidebar(mode) { it.name }
}
