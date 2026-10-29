package build.raft.app.compat.pager

import build.raft.app.CHANNEL_SETTINGS_PAGE
import build.raft.app.JOINT_CHANNEL_CONNECTIONS_PAGE
import build.raft.app.MEMBER_DETAIL_PAGE
import build.raft.app.THREAD_MEMBERS_PAGE

/**
 * Where a page opens on the wide layout (rail shown, plan option C, artin 2026-10-29). The
 * vocabulary is material3-adaptive's (detail / supporting pane); the shell decides the layout.
 * Only the wide shell reads it: on the phone and every compact layout every page opens on the
 * platform stack as before.
 */
enum class RaftPaneRole {
    /** A page of its own on the platform stack (Activity, view controller, desktop page). */
    Window,

    /** A pane right of the open conversation, the conversation staying beside it (web drawer). */
    Supporting,

    /**
     * The main conversation slot. No registry entry has it: only a page opened from inside the
     * supporting pane is routed there (raftSupportingPaneRole), as the web's profile Message
     * closes the panel and opens the conversation.
     */
    Detail,
}

/** The pages that do not open as a [RaftPaneRole.Window]; every other page does. */
internal val raftPagePaneRoles: Map<String, RaftPaneRole> =
    mapOf(
        // Web ChannelSettingsSheet: a drawer right of the chat; members and joint connections are
        // views inside it (ChannelOverflowMenu).
        CHANNEL_SETTINGS_PAGE to RaftPaneRole.Supporting,
        THREAD_MEMBERS_PAGE to RaftPaneRole.Supporting,
        JOINT_CHANNEL_CONNECTIONS_PAGE to RaftPaneRole.Supporting,
        // Web ProfilePanel / AgentDetailPanel: the profile opened from a chat (avatar, mention,
        // channel members) is a column right of the conversation. The Members rail root shows its
        // own inline detail (MembersAdaptiveWorkspace) and does not go through a pane.
        MEMBER_DETAIL_PAGE to RaftPaneRole.Supporting,
    )
