package build.raft.app

import build.raft.designsystem.components.RaftPaneGridColumnBounds

@RaftCoreInternalApi object RaftPaneGridColumns {
    /** Web `COLUMN_BOUNDS.thread`: 400, at least 360, growing until the main pane is at 360. */
    val Thread = RaftPaneGridColumnBounds(id = "thread", defaultWidthDp = 400f, minWidthDp = 360f)

    /** Web `COLUMN_BOUNDS.sideCards`: the Tasks / Files column, 360, at least 280, its left seam included. */
    val SideCards = RaftPaneGridColumnBounds(id = "sideCards", defaultWidthDp = 360f, minWidthDp = 280f)

    /**
     * Web `COLUMN_BOUNDS.detail`: the right-hand detail column, here the Tasks page's task beside
     * its list (web TasksRoute); 400, at least 320 (web's content minimum).
     */
    val Detail = RaftPaneGridColumnBounds(id = "detail", defaultWidthDp = 400f, minWidthDp = 320f)

    /**
     * Web channelSideLayout's `[profile, context, details]` column (#189 step 3): a member's profile over the channel
     * details, 400 by default, at least 320 (`CHANNEL_SIDE_SECTION_MIN_WIDTH`); the row's last column.
     */
    val Profile = RaftPaneGridColumnBounds(id = "profile", defaultWidthDp = 400f, minWidthDp = 320f)

    /**
     * Web channelSideLayout's `[artifact]` column (#189 step 4; F41): the Artifact viewer, 480 by default and at least
     * 480 (`CHANNEL_SIDE_SECTION_MIN_WIDTH.artifact`); by default between Tasks / Files and the profile column.
     */
    val Artifact = RaftPaneGridColumnBounds(id = "artifact", defaultWidthDp = 480f, minWidthDp = 480f)

    /**
     * The main pane, the conversation (web rowLayout `slock:conversationWidth`, #189): none kept until a seam beside
     * it is let go (then the plan gives it what the columns leave, at least 400), kept at least its content's 360.
     */
    val Conversation: RaftPaneGridColumnBounds = build.raft.designsystem.components.RaftPaneGridConversationBounds

    /**
     * The Tasks page's list, the main pane beside the task's column: its own kept width, so letting the task
     * column's seam go never saves the list's width as the conversation's (PR #4661 review). Same rules as the
     * conversation's: none kept until then, at least 360.
     */
    val TasksList = RaftPaneGridColumnBounds(id = "tasksList", defaultWidthDp = 400f, minWidthDp = 360f)

    /**
     * The Artifacts home page's list, the main pane beside its split Artifact column (web `ArtifactsRoute`,
     * slock #16418): keeps its own width (`contentMin = 360`) so resizing its seam never overwrites the
     * conversation's width while the right column shares [Artifact]'s saved width (480dp default).
     */
    val ArtifactsList = RaftPaneGridColumnBounds(id = "artifactsList", defaultWidthDp = 400f, minWidthDp = 360f)
}
