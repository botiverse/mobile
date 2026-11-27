package build.raft.app.thread

import build.raft.app.JointChannelKindWireValue
import build.raft.app.get
import build.raft.app.model.ChannelExternalMemberProfile

/*
 * A shared (joint) channel's member list grouped by the viewed server (botiverse/slock #12853;
 * packages/web/src/features/channel/jointMemberGroups.ts and ChannelMembers.tsx at 4be606f7fd).
 * Computed joint membership marks each person and agent row `inViewerServer`: a member of the
 * viewed server with any role, guests too; an agent whose one server is the viewed one. The
 * members page then lists "Members of {server}" before "Other servers", people before agents in
 * each, and leaves an empty group out. Without the field (an older server, any other channel) the
 * page keeps its Humans and Agents sections. Slack participants are not roster rows: in both
 * layouts they keep their own section after the others (web `channelExternalMembers`).
 */

/** The member sections of the members page (web `MemberPageSection` kinds). */
internal enum class ThreadMembersRosterGroup(val key: String) {
    Humans("humans"),
    Agents("agents"),

    /** "Members of {server}" (web `ViewerServerGroupPage`, rows with `inViewerServer`). */
    ViewerServer("viewer-server"),

    /** "Other servers": everyone else in the joint channel. */
    OtherServers("other-servers"),
}

/** One section of the members page roster, in page order ([threadMembersRosterSections]). */
internal sealed interface ThreadMembersRosterSection {
    /** The roster list's key for the section's header. */
    val key: String

    /** The section's people and agents, in roster order; within a group, people before agents. */
    data class Members(
        val group: ThreadMembersRosterGroup,
        val members: List<RaftThreadMemberItem>,
    ) : ThreadMembersRosterSection {
        override val key: String get() = "section:${group.key}"
    }

    /** "Slack participants" (web `channelExternalMembers`): never inside a group, always last. */
    data class SlackParticipants(
        val participants: List<ChannelExternalMemberProfile>,
    ) : ThreadMembersRosterSection {
        override val key: String get() = "section:external"
    }
}

/**
 * Web `groupsByViewerServer`: the roster groups by the viewed server when the channel is joint
 * and its rows carry `inViewerServer` (computed joint membership). No feature flag: the web
 * retired `joint_human_membership_v1` (#14502), so the field alone decides.
 */
internal fun threadMembersGroupsByViewerServer(
    channelKind: String?,
    members: List<RaftThreadMemberItem>,
): Boolean = channelKind == JointChannelKindWireValue && members.any { it.inViewerServer != null }

/**
 * Web `splitByViewerServer`: "Members of {server}" then "Other servers", people before agents in
 * each, in roster order; an empty group is left out. A row without the field is not one of the
 * viewed server's (the web compares `inViewerServer === true`).
 */
internal fun threadMembersSplitByViewerServer(
    members: List<RaftThreadMemberItem>,
): List<ThreadMembersRosterSection.Members> =
    listOf(true, false)
        .map { inViewerServer ->
            val rows = members.filter { (it.inViewerServer == true) == inViewerServer }
            ThreadMembersRosterSection.Members(
                group = if (inViewerServer) ThreadMembersRosterGroup.ViewerServer else ThreadMembersRosterGroup.OtherServers,
                members = rows.filterNot { it.isRosterAgent() } + rows.filter { it.isRosterAgent() },
            )
        }
        .filter { it.members.isNotEmpty() }

/**
 * The members page's sections after the Invited one, in order (web ChannelMembers
 * presentation="page"): a joint channel's two groups ([threadMembersSplitByViewerServer]) or
 * Humans then Agents, then Slack participants; a section with nothing to list is left out. The
 * whole roster ([members]) decides the grouping, as the web's `groupsByViewerServer(channel.type,
 * channelHumans, channelAgents)`; what the search leaves ([visibleMembers],
 * [visibleSlackParticipants]) fills the sections.
 */
internal fun threadMembersRosterSections(
    channelKind: String?,
    members: List<RaftThreadMemberItem>,
    visibleMembers: List<RaftThreadMemberItem>,
    visibleSlackParticipants: List<ChannelExternalMemberProfile>,
): List<ThreadMembersRosterSection> {
    val memberSections =
        if (threadMembersGroupsByViewerServer(channelKind, members)) {
            threadMembersSplitByViewerServer(visibleMembers)
        } else {
            listOf(
                ThreadMembersRosterSection.Members(ThreadMembersRosterGroup.Humans, visibleMembers.filterNot { it.isRosterAgent() }),
                ThreadMembersRosterSection.Members(ThreadMembersRosterGroup.Agents, visibleMembers.filter { it.isRosterAgent() }),
            ).filter { it.members.isNotEmpty() }
        }
    return buildList<ThreadMembersRosterSection> {
        addAll(memberSections)
        if (visibleSlackParticipants.isNotEmpty()) add(ThreadMembersRosterSection.SlackParticipants(visibleSlackParticipants))
    }
}

private fun RaftThreadMemberItem.isRosterAgent(): Boolean = kind.equals("agent", ignoreCase = true)
