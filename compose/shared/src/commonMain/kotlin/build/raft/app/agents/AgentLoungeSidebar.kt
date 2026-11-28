package build.raft.app

import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeFollowedThreadUnread
import build.raft.app.data.HomeStandaloneLoungeRow
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.agentLoungeSidebarEntry
import build.raft.app.data.loungeAgentDisplayName
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.thread.key

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
 * from the Lounge's own row ([RaftAgentLounge]). [title] names it ("{Name}'s Lounge", or the bare
 * "Lounge" for a null name): its agent's DM row's name, else the name the client knows it by, never
 * its channel name (web useSidebarItems, slock #16282).
 */
internal fun homeSidebarAgentLoungeItems(
    enabled: Boolean,
    directMessages: List<HomeDirectMessageRow>,
    known: Map<String, RaftAgentLounge>,
    markedUnreadIds: Set<String>,
    title: (agentName: String?) -> String,
    standaloneLounges: List<HomeStandaloneLoungeRow> = emptyList(),
    followedThreadUnread: Map<String, HomeFollowedThreadUnread> = emptyMap(),
    useLiveFollowedThreadUnread: Boolean = false,
): List<RaftHomeConversationItem> {
    if (!enabled) return emptyList()
    val agentDmByAgentId =
        directMessages
            .filter { it.directMessagePeerType.equals("agent", ignoreCase = true) }
            .mapNotNull { dm -> dm.presenceMemberId?.value?.takeIf { it.isNotBlank() }?.let { it to dm } }
            .toMap()
    val standaloneByAgentId =
        standaloneLounges
            .filter { it.agentId.isNotBlank() }
            .associateBy { it.agentId }
    val knownByAgentId = known.values.associateBy { it.agentId }
    val agentIds = LinkedHashSet<String>()
    agentDmByAgentId.forEach { (agentId, dm) -> if (dm.lounge != null) agentIds += agentId }
    standaloneByAgentId.keys.forEach { agentIds += it }
    agentIds += knownByAgentId.keys
    val liveThreadUnreadByParent: Map<String, Pair<Int, Boolean>> =
        if (useLiveFollowedThreadUnread) {
            val acc = LinkedHashMap<String, Pair<Int, Boolean>>()
            for (thread in followedThreadUnread.values) {
                if (thread.unreadCount <= 0) continue
                val key = thread.parentChannelId.value
                val prev = acc[key]
                acc[key] = ((prev?.first ?: 0) + thread.unreadCount) to (prev?.second == true || thread.hasMention)
            }
            acc
        } else {
            emptyMap()
        }
    return agentIds.mapNotNull { agentId ->
        val dm = agentDmByAgentId[agentId]
        val standalone = standaloneByAgentId[agentId]
        // When an agent has both a DM row and a standalone entry, the DM row wins (#proj-chat:#44).
        val summary = dm?.lounge ?: standalone?.lounge
        val lounge = knownByAgentId[agentId] ?: summary?.let { known[it.channelId.value] }
        val targetChannelId = (lounge?.channelId ?: summary?.channelId)?.value
        val livePair = if (useLiveFollowedThreadUnread && targetChannelId != null) liveThreadUnreadByParent[targetChannelId] else null
        val entry =
            agentLoungeSidebarEntry(
                summary = summary,
                known = lounge,
                followedThreadUnreadCount = if (useLiveFollowedThreadUnread) (livePair?.first ?: 0) else null,
                followedThreadHasMention = if (useLiveFollowedThreadUnread) (livePair?.second == true) else null,
            ) ?: return@mapNotNull null
        val channelId = entry.channelId.value
        val agentName =
            loungeAgentDisplayName(dm?.name)
                ?: loungeAgentDisplayName(standalone?.agentName)
                ?: lounge?.displayAgentName
        RaftHomeConversationItem(
            id = channelId,
            name = title(agentName),
            kind = "channel",
            channelType = "channel",
            unreadCount = entry.unreadCount,
            lastMessageAt = lounge?.lastMessageAt,
            avatarLabel = agentName.orEmpty().take(2).uppercase(),
            avatarWireValue = dm?.directMessagePeerAvatarWireValue ?: standalone?.avatarWireValue,
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
                    agentName = agentName.orEmpty(),
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
    val uniqueDirectMessages = directMessages.distinctBy { it.id }
    if (lounges.isEmpty()) return uniqueDirectMessages
    val dmIds = uniqueDirectMessages.mapTo(HashSet()) { it.id }
    val uniqueLounges = lounges.distinctBy { it.id }.filterNot { it.id in dmIds }
    if (uniqueLounges.isEmpty()) return uniqueDirectMessages
    if (mode == RaftSidebarSortMode.Manual) {
        return uniqueDirectMessages + uniqueLounges.sortedForSidebar(RaftSidebarSortMode.Recent) { it.name }
    }
    return (uniqueDirectMessages + uniqueLounges).sortedForSidebar(mode) { it.name }
}

/**
 * Output of [homeSidebarMergeAgentLounges]: [directMessages] with eligible agent DM rows merged
 * with their Lounge, and [agentLounges] holding only unmerged standalone / pinned-only Lounges.
 */
internal data class HomeSidebarMergedAgentConversations(
    val directMessages: List<RaftHomeConversationItem>,
    val agentLounges: List<RaftHomeConversationItem>,
)

/**
 * Agent Lounge v0 sidebar merge (web slock #15990): collapse an eligible agent's DM row and Lounge
 * row into a single sidebar row so the agent appears once in Direct Messages (or Pinned / Custom
 * Section).
 *
 * - Combined unread = DM unread + (0 if Lounge muted else Lounge unread); combined `@` mention,
 *   combined marked-unread state, and latest activity timestamp across both sides.
 * - Clicking the merged row opens the Lounge ([RaftHomeConversationItem.opensAgentLoungeOnClick]).
 * - Selection highlights the row while either the DM or the Lounge channel is open
 *   ([RaftHomeConversationItem.mergedAgentLounge]).
 * - Duplicate separate Lounge rows are suppressed unless ONLY the Lounge is pinned while its agent
 *   DM is NOT pinned (preserving an explicit Lounge pin without double-counting unread on the DM).
 * - Standalone Lounges (no DM row), personal assistants, App Bots, deleted agents, human DMs, and
 *   flag-off / Guest viewers keep their existing unmerged behavior.
 */
internal fun homeSidebarMergeAgentLounges(
    enabled: Boolean,
    directMessages: List<RaftHomeConversationItem>,
    agentLounges: List<RaftHomeConversationItem>,
    rawDirectMessages: List<HomeDirectMessageRow>,
    knownLounges: Map<String, RaftAgentLounge>,
    markedUnreadIds: Set<String>,
    pinnedChannelIds: Set<String> = emptySet(),
    pinnedAgentIds: Set<String> = emptySet(),
    personalAssistantAgentIds: Set<String> = emptySet(),
    appBotAgentIds: Set<String> = emptySet(),
    deletedAgentIds: Set<String> = emptySet(),
): HomeSidebarMergedAgentConversations {
    if (!enabled) {
        return HomeSidebarMergedAgentConversations(directMessages, agentLounges)
    }
    val loungeItemByAgentId =
        buildMap<String, RaftHomeConversationItem> {
            agentLounges.forEach { lounge ->
                val agentId = lounge.agentLounge?.agentId ?: lounge.presenceMemberId
                if (!agentId.isNullOrBlank() && !containsKey(agentId)) {
                    put(agentId, lounge)
                }
            }
        }
    val knownByAgentId =
        buildMap<String, RaftAgentLounge> {
            knownLounges.values.forEach { lounge ->
                if (lounge.agentId.isNotBlank() && !containsKey(lounge.agentId)) {
                    put(lounge.agentId, lounge)
                }
            }
        }
    val rawDmById = rawDirectMessages.associateBy { it.threadId.value }
    val suppressedLoungeIds = HashSet<String>()
    val mergedDirectMessages =
        directMessages.map { dm ->
            val agentId = dm.presenceMemberId?.trim()?.takeIf { it.isNotEmpty() }
            val eligible =
                dm.peerType.equals("agent", ignoreCase = true) &&
                    agentId != null &&
                    agentId !in personalAssistantAgentIds &&
                    agentId !in appBotAgentIds &&
                    agentId !in deletedAgentIds
            if (!eligible || agentId == null) return@map dm
            val loungeItem = loungeItemByAgentId[agentId]
            val rawSummary = rawDmById[dm.id]?.lounge
            val known = knownByAgentId[agentId] ?: rawSummary?.let { knownLounges[it.channelId.value] }
            val loungeChannelId = loungeItem?.id ?: known?.channelId?.value ?: rawSummary?.channelId?.value
            val dmIsPinned = dm.id in pinnedChannelIds || agentId in pinnedAgentIds
            val loungeIsPinned = loungeChannelId != null && loungeChannelId in pinnedChannelIds
            val keepPinnedLoungeSeparate = loungeItem != null && loungeIsPinned && !dmIsPinned
            val absorbLounge = !keepPinnedLoungeSeparate
            if (loungeItem != null && absorbLounge) {
                suppressedLoungeIds += loungeItem.id
            }
            val loungeMuted = known?.activityMuted == true || loungeItem?.activityMuted == true
            val dmUnread = dm.unreadCount.coerceAtLeast(0)
            val loungeUnread = if (absorbLounge && !loungeMuted) (loungeItem?.unreadCount ?: 0).coerceAtLeast(0) else 0
            val loungeMentioned = absorbLounge && !loungeMuted && (loungeItem?.mentioned == true)
            val loungeMarkedUnread = absorbLounge && loungeChannelId != null && loungeChannelId in markedUnreadIds
            val loungeLastMessageAt = if (absorbLounge) (loungeItem?.lastMessageAt ?: known?.lastMessageAt) else null
            val mergedLoungeRow =
                if (absorbLounge && loungeChannelId != null) {
                    RaftHomeAgentLoungeRow(
                        channelId = loungeChannelId,
                        unreadCount = loungeItem?.unreadCount ?: 0,
                        mentioned = loungeItem?.mentioned == true,
                        agentId = agentId,
                        agentName = dm.name,
                        joined = loungeItem?.agentLounge?.joined == true || known?.joined == true || rawSummary?.joined == true,
                    )
                } else {
                    null
                }
            dm.copy(
                unreadCount = dmUnread + loungeUnread,
                dmSideUnread = dmUnread,
                loungeSideUnread = loungeUnread,
                mentioned = dm.mentioned || loungeMentioned,
                markedUnread = dm.markedUnread || loungeMarkedUnread,
                lastMessageAt = newerSidebarTimestamp(dm.lastMessageAt, loungeLastMessageAt),
                mergedAgentLounge = mergedLoungeRow,
                opensAgentLoungeOnClick = true,
            )
        }
    val remainingLounges = agentLounges.filterNot { it.id in suppressedLoungeIds }
    return HomeSidebarMergedAgentConversations(
        directMessages = mergedDirectMessages,
        agentLounges = remainingLounges,
    )
}

/**
 * Per-side unread display for an agent's merged sidebar row (`botiverse/slock#17269` + `#17277`,
 * web `SidebarPeerRowBody.tsx` `dmSideUnread` / `loungeSideUnread` / `NoSideCount`):
 *
 * - `null`: plain row (not standing for both DM and Lounge, or total unread <= 0).
 * - [Sides]: one count per side with unread > 0, Private (lock) first then Lounge (sofa). Private
 *   follows the DM's muted state (quiet grey when muted, loud pink otherwise); Lounge is always
 *   loud (a muted Lounge contributes 0 unread).
 * - [TotalFallback]: `#17277` — row has `unreadCount > 0` while both side counts are 0 (unread
 *   belongs to neither side): draws the plain total with no side icon (`"N unread"`), quiet grey
 *   when muted like the other counts, never nothing.
 */
internal sealed interface HomeSidebarAgentUnreadDisplay {
    data class Sides(
        val dmCount: Int = 0,
        val dmQuiet: Boolean = false,
        val loungeCount: Int = 0,
    ) : HomeSidebarAgentUnreadDisplay

    data class TotalFallback(
        val count: Int,
        val quiet: Boolean = false,
    ) : HomeSidebarAgentUnreadDisplay
}

internal fun homeSidebarAgentUnreadDisplay(
    unreadCount: Int,
    dmSideUnread: Int?,
    loungeSideUnread: Int?,
    muted: Boolean,
): HomeSidebarAgentUnreadDisplay? {
    if (unreadCount <= 0 || dmSideUnread == null || loungeSideUnread == null) return null
    val dm = dmSideUnread.coerceAtLeast(0)
    val lounge = loungeSideUnread.coerceAtLeast(0)
    return if (dm > 0 || lounge > 0) {
        HomeSidebarAgentUnreadDisplay.Sides(
            dmCount = dm,
            dmQuiet = muted,
            loungeCount = lounge,
        )
    } else {
        HomeSidebarAgentUnreadDisplay.TotalFallback(
            count = unreadCount,
            quiet = muted,
        )
    }
}

private fun newerSidebarTimestamp(first: String?, second: String?): String? {
    val a = first?.takeIf { it.isNotBlank() }
    val b = second?.takeIf { it.isNotBlank() }
    if (a == null) return b
    if (b == null) return a
    val aMs = parseRaftTimestampEpochMillisOrNull(a)
    val bMs = parseRaftTimestampEpochMillisOrNull(b)
    return when {
        aMs != null && bMs != null -> if (bMs > aMs) b else a
        bMs != null -> b
        else -> a
    }
}
