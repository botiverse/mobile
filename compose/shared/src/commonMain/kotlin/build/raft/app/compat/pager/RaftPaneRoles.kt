package build.raft.app.compat.pager

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
}

/** The pages that do not open as a [RaftPaneRole.Window]; every other page does. */
internal val raftPagePaneRoles: Map<String, RaftPaneRole> = emptyMap()
