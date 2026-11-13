package build.raft.app.compat.pager

import build.raft.app.CHANNEL_SETTINGS_PAGE
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

    /**
     * The profile column right of the open conversation, in its row (web channelSideLayout's profile / details
     * column; RaftWidePanes.kt), the conversation staying beside it.
     */
    Supporting,

    /**
     * The main conversation slot. No registry entry has it: only a page opened from inside the
     * profile column is routed there (raftProfileColumnRole), as the web's profile Message
     * closes the panel and opens the conversation.
     */
    Detail,
}

/** The pages that do not open as a [RaftPaneRole.Window]; every other page does. */
internal val raftPagePaneRoles: Map<String, RaftPaneRole> =
    mapOf(
        // Web ChannelDetailsSurface: the channel details are a column right of the chat, with no
        // scrim; the member list opens inside it, with Back.
        CHANNEL_SETTINGS_PAGE to RaftPaneRole.Supporting,
        THREAD_MEMBERS_PAGE to RaftPaneRole.Supporting,
        // Web ProfilePanel / AgentDetailPanel: the profile opened from a chat (avatar, mention,
        // channel members) is a column right of the conversation. The Members rail root shows its
        // own inline detail (MembersAdaptiveWorkspace) and does not go through a pane.
        MEMBER_DETAIL_PAGE to RaftPaneRole.Supporting,
    )
