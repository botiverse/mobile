package build.raft.app

import build.raft.app.data.SharedAdaptiveWorkspaceSession
import build.raft.app.settings.retainedAppShellSelectedTab

/*
 * Where a rail click lands on the wide layout (web docs/frontend/layout-memory.md, slock #15815). Every rail tab
 * shows the one shared conversation slot of the wide workspace, so a tab switch used to keep whatever another tab
 * had opened: an open from Activity replaced the channel open in Chat, and closing it there left Chat on "Select a
 * conversation".
 *
 * One remembered place per server and rail tab. A place is a page with only its own state, never a pane or a
 * one-shot open:
 * - Chat's place is the conversation open in it. A reply thread open over that conversation is a pane of it (its
 *   beside memory comes later): the conversation stays the place. A focused message is a one-shot open: not kept.
 * - The list pages (Activity, Members) land on their list with nothing open beside it. Activity then reopens the
 *   conversation opened from it last time (ActivityArrivalReopen.kt, web activityArrivalOpen) when nothing it opened
 *   itself is showing: that conversation is a reading pane, not a place.
 * - Tasks already comes back on its list (its task column closes when Tasks is left) and Settings keeps its own page:
 *   a click leaves them as they are, as it leaves the surface you are on (re-selecting Chat keeps its conversation).
 *
 * Places hold identity only, as the history entries they are (RaftWideNavigationHistory.kt), and live in memory per
 * shell. The conversation Chat shows also persists as its server's last conversation (ServerLastConversation.kt).
 * Phones push conversations as pages: nothing here runs there.
 */

/** The rail tab ([RaftWideNavEntry.surface]) whose place is the conversation open in it: Chat. */
internal const val RaftRailChatTab = "home"

/** The rail tabs that land on their list with nothing open beside it. */
internal val RaftRailListTabs: Set<String> = setOf("activity", "members")

/** What a rail click does to what the wide layout shows. */
internal sealed interface RaftRailLanding {
    /** What shows stays. */
    data object Keep : RaftRailLanding

    /** [place] reopens through the history's restore path: its conversation, or its page with nothing open. */
    data class Restore(val place: RaftWideNavEntry) : RaftRailLanding

    /**
     * Chat has no place on this server yet (the shell has not shown it there), or only the conversation its default
     * landing picked: what another tab left open closes, and the default landing picks again, as on arrival (the
     * Home row, the server's last conversation, #all). Its pick stays automatic, dropped again when the window
     * leaves the wide layout.
     */
    data object DefaultLanding : RaftRailLanding
}

/**
 * Each rail tab's place, per server ([byServer]: server id, then [RaftWideNavEntry.surface]). [chatLandedByDefault]:
 * the servers whose Chat place is the conversation Chat's default landing picked, not one the user opened.
 */
internal data class RaftRailPlaces(
    val byServer: Map<String, Map<String, RaftWideNavEntry>> = emptyMap(),
    val chatLandedByDefault: Set<String> = emptySet(),
) {
    /** [tab]'s place on [serverId]: Chat's remembered one (null while it has none), any other tab's page. */
    fun placeFor(serverId: String, tab: String): RaftWideNavEntry? =
        if (tab == RaftRailChatTab) byServer[serverId]?.get(tab) else RaftWideNavEntry(serverId, tab)

    /**
     * The wide layout shows [shown]: it becomes its tab's place ([raftRailPlaceOf]). [landingSettled]: Chat's default
     * landing has settled on that server, so Chat showing nothing is a close, not a landing still to come.
     * [landedByDefault]: the conversation shown is the default landing's pick.
     */
    fun remember(shown: RaftWideNavEntry, landingSettled: Boolean, landedByDefault: Boolean = false): RaftRailPlaces {
        val places = byServer[shown.serverId].orEmpty()
        val place = raftRailPlaceOf(shown, places[shown.surface], landingSettled) ?: return this
        val defaults =
            if (landedByDefault && place.detail != null) {
                chatLandedByDefault + shown.serverId
            } else {
                chatLandedByDefault - shown.serverId
            }
        if (places[shown.surface] == place && defaults == chatLandedByDefault) return this
        return copy(
            byServer = byServer + (shown.serverId to (places + (shown.surface to place))),
            chatLandedByDefault = defaults,
        )
    }

    /**
     * Where a click on rail [tab] lands. [shown] is what the wide layout showed before the click (null off it),
     * [selectedTab] the shell's tab after it (another one when the click selected nothing), [open] the conversation
     * the shared detail holds now and [openedOn] the surface that opened it (rememberRaftSelectionOpenedOn).
     */
    fun landing(
        shown: RaftWideNavEntry?,
        tab: String,
        selectedTab: String,
        open: RaftAdaptiveDetailIdentity?,
        openedOn: String?,
    ): RaftRailLanding {
        val target = retainedAppShellSelectedTab(tab)
        if (shown == null || shown.surface == target || retainedAppShellSelectedTab(selectedTab) != target) {
            return RaftRailLanding.Keep
        }
        return when (target) {
            RaftRailChatTab -> {
                val place = placeFor(shown.serverId, target) ?: return RaftRailLanding.DefaultLanding
                val conversation = (place.detail as? RaftWideNavDetail.Conversation)?.identity
                // Still open (Tasks and Members leave the detail as it is): nothing to reopen. A reply thread over
                // it stays only when Chat opened it: one Activity opened is Activity's.
                val stillOpen =
                    conversation != null && open != null &&
                        (open.isConversation(conversation) ||
                            (openedOn == RaftRailChatTab && open.isReplyThreadUnder(conversation)))
                when {
                    stillOpen -> RaftRailLanding.Keep
                    shown.serverId in chatLandedByDefault -> RaftRailLanding.DefaultLanding
                    else -> RaftRailLanding.Restore(place)
                }
            }
            in RaftRailListTabs -> RaftRailLanding.Restore(RaftWideNavEntry(shown.serverId, target))
            else -> RaftRailLanding.Keep
        }
    }
}

/**
 * What of [shown] is its tab's place, or null to leave the places as they are. Only Chat keeps one here (another
 * tab's place is its page): its conversation without a focused message; nothing open, once its default landing has
 * settled ([landingSettled]); a reply thread over the conversation that is already the place ([previous]) keeps it.
 */
internal fun raftRailPlaceOf(
    shown: RaftWideNavEntry,
    previous: RaftWideNavEntry?,
    landingSettled: Boolean,
): RaftWideNavEntry? {
    if (shown.surface != RaftRailChatTab) return null
    val conversation = shown.detail as? RaftWideNavDetail.Conversation
        ?: return shown.copy(detail = null).takeIf { landingSettled }
    val placed = (previous?.detail as? RaftWideNavDetail.Conversation)?.identity
    if (placed != null && conversation.identity.isReplyThreadUnder(placed)) return previous
    return shown.copy(
        detail =
            RaftWideNavDetail.Conversation(
                identity = conversation.identity.copy(focusedMessageId = null),
                route = conversation.route.copy(focusedMessageId = null, focusedMessageRequestNonce = 0),
            ),
    )
}

/** The conversation Chat shows in this entry; null for another tab's entry, or Chat showing none. */
internal fun RaftWideNavEntry.chatConversation(): RaftAdaptiveDetailIdentity? =
    (detail as? RaftWideNavDetail.Conversation)?.identity?.takeIf { surface == RaftRailChatTab }

/**
 * Whether Chat itself shows (its list and detail, no Home page, inline list or page over them): only there does
 * Chat's default landing fill the shared detail. Elsewhere it put Chat's conversation beside another tab's list
 * (Activity after a server switch), where it read as that tab's.
 */
internal fun appShellChatDetailShown(
    selectedTab: String,
    selectedHomeDestination: String?,
    railInlineSecondaryDestination: String?,
): Boolean =
    retainedAppShellSelectedTab(selectedTab) == RaftRailChatTab &&
        selectedHomeDestination == null &&
        railInlineSecondaryDestination == null

/**
 * Lets Chat's default landing pick again on [serverId] ([RaftRailLanding.DefaultLanding]). Any wide open settles it
 * (an open from Activity too), and closing that open keeps it settled, which left Chat on "Select a conversation".
 * Only an empty detail of that server is let go.
 */
internal fun SharedAdaptiveWorkspaceSession.withDefaultLandingRearmed(serverId: String): SharedAdaptiveWorkspaceSession {
    val current = workspace ?: return this
    return if (defaultLandingServerId == serverId && current.serverId == serverId && current.selection == null) {
        copy(defaultLandingServerId = null)
    } else {
        this
    }
}

/** A reply thread under [place], a channel or DM (its parent conversation): a pane over it. */
private fun RaftAdaptiveDetailIdentity.isReplyThreadUnder(place: RaftAdaptiveDetailIdentity): Boolean =
    surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
        place.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
        parentChannelId != null &&
        parentChannelId == place.threadId

/** Whether this is the conversation [place] names, whatever message it focuses. */
private fun RaftAdaptiveDetailIdentity.isConversation(place: RaftAdaptiveDetailIdentity): Boolean =
    copy(focusedMessageId = null) == place.copy(focusedMessageId = null)
