package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.designsystem.components.RaftPaneGridExpand
import kotlinx.serialization.json.JsonObject

/*
 * The shell's side columns on the wide layout (RaftWidePanes.kt; #189 steps 3 and 4), kept out of SettingsHomePage's
 * RaftAppShellReadyContent, whose method is at the JVM's 64 KB limit: the pages the conversation opened in the profile
 * column (a member's profile over the channel details) and the Artifact it opened in the Artifact column. Both belong
 * to that conversation (web: its page's address), not to what its detail shows: jumping to a message, and a reply
 * thread replacing the conversation in the detail, folding into it or going back to it, keep them; picking another
 * conversation closes them. When the window leaves the wide layout they reopen as pages on the platform stack, as they
 * would have opened there.
 */

/**
 * The conversation a detail belongs to, by server and id: a channel's or a DM's own, a reply thread's parent (its
 * thread is a pane of that conversation, as the side thread is). The side columns are this conversation's.
 */
internal data class RaftDetailConversation(val serverId: String, val conversationId: String)

internal fun RaftAdaptiveDetailIdentity.detailConversation(): RaftDetailConversation =
    RaftDetailConversation(serverId, parentChannelId?.takeIf { surfaceKind == RaftThreadSurfaceKind.ReplyThread } ?: threadId)

/**
 * The side columns' pages, whether each column is on screen right now (Back goes by that, not the pages alone), and
 * the row's Expand (#189 rule 5, [expand]; it replaced the thread's Focus mode).
 */
@Stable
internal class RaftWideSideColumns {
    var profile by mutableStateOf(RaftProfileColumnPanes())
    var profileShown by mutableStateOf(false)
    var artifact by mutableStateOf(RaftArtifactColumnPane())
    var artifactShown by mutableStateOf(false)
    val expand = RaftPaneGridExpand()

    /** The conversation the columns' pages belong to ([moveTo]); null before any. */
    var conversation by mutableStateOf<RaftDetailConversation?>(null)
        private set

    /** Another conversation was picked: its columns close and the row is restored. */
    fun cleared() {
        profile = profile.cleared()
        artifact = artifact.cleared()
        expand.restore()
    }

    /** [next] is the conversation now: the columns close for it ([cleared]) and are its from now on. */
    fun moveTo(next: RaftDetailConversation?) {
        cleared()
        conversation = next
    }

    /**
     * The window left the wide layout: every page reopens on [navigator] in Back's order, the one opened last on top:
     * the profile pages open before the Artifact, the Artifact, then those opened after it.
     */
    fun reopenOn(navigator: RaftCmpNavigator) {
        val artifactEntry = artifact.entry
        val keys = profile.pagesWithKeys
        if (artifactEntry != null) keys.filter { it.first in artifact.below }.forEach { navigator.openPage(it.second.pageName, it.second.pageData) }
        if (artifactEntry != null) navigator.openPage(artifactEntry.pageName, artifactEntry.pageData)
        keys.filter { artifactEntry == null || it.first !in artifact.below }.forEach { navigator.openPage(it.second.pageName, it.second.pageData) }
        cleared()
    }

    /**
     * The shell's history Back (Cmd+[, the top bar) on the wide layout: restores an expanded row first (rule 5, as Esc
     * and system Back do), else closes the column opened last ([raftWideBackStep]), and returns true; else false (the
     * history steps). [overlayOpen]: the Tasks page's task column.
     */
    fun back(overlayOpen: Boolean, source: String, log: (String) -> Unit): Boolean {
        if (!overlayOpen && expand.restore()) {
            log("wide_history_back source=$source pops=row_expand")
            return true
        }
        return when (raftWideBackStep(profile, profileShown, overlayOpen = overlayOpen, artifact = artifact, artifactShown = artifactShown)) {
            RaftWideBackStep.PopArtifactColumn -> {
                log("wide_history_back source=$source pops=artifact_column")
                artifact = artifact.cleared()
                true
            }
            RaftWideBackStep.PopProfileColumn -> {
                log("wide_history_back source=$source pops=profile_column cell=${profile.backCell?.id}")
                profile = profile.back()
                true
            }
            RaftWideBackStep.Existing -> false
        }
    }
}

/**
 * The shell's [RaftWideSideColumns], cleared when the conversation of [selection] (the detail picked,
 * [detailConversation]) changes, not when the detail alone does, and reopened on the platform stack when
 * [navRailActive] goes off, in that order. Leaving [tab] restores an expanded row: Cmd+[ on another tab must not
 * restore a row nobody sees (PR #4678 review).
 */
@Composable
internal fun rememberRaftWideSideColumns(
    selection: RaftAdaptiveDetailIdentity?,
    navRailActive: Boolean,
    tab: Any? = null,
): RaftWideSideColumns {
    val columns = remember { RaftWideSideColumns() }
    val navigator = LocalRaftHost.current.navigator
    val conversation = selection?.detailConversation()
    LaunchedEffect(conversation) { columns.moveTo(conversation) }
    LaunchedEffect(tab) { columns.expand.restore() }
    LaunchedEffect(navRailActive) { if (!navRailActive) columns.reopenOn(navigator) }
    return columns
}

/**
 * SettingsHomePage's [RaftWorkspaceDetailGrid] over the shell's [columns]: [columnHost] is where the columns may stand
 * (the wide layout with room for a side column; it also reports whether they are shown), [detailAccepted] whether the
 * pane shows the selection. [openDetail]: a conversation opened from the profile column (the profile's Message); true
 * when it opened.
 */
@Composable
internal fun RaftWorkspaceDetailRow(
    modifier: Modifier,
    columns: RaftWideSideColumns,
    columnHost: Boolean,
    detailAccepted: Boolean,
    tabSelected: Boolean,
    active: Boolean,
    sideThreadColumn: RaftPaneGridColumn?,
    openDetail: (JsonObject) -> Boolean,
    main: @Composable () -> Unit,
) {
    RaftWorkspaceDetailGrid(
        modifier = modifier,
        wide = columnHost,
        panes = { columns.profile },
        onPanesChange = { columns.profile = it },
        columnOpen = columnHost && detailAccepted && columns.profile.isOpen,
        tabSelected = tabSelected,
        active = active,
        sideThreadColumn = sideThreadColumn,
        // Only the main detail pane reports whether the columns are shown.
        reportHost = columnHost,
        onShown = { columns.profileShown = it },
        openDetail = { _, pageData -> openDetail(pageData) },
        // The Artifact column (#189 step 4): an Artifact opened here shows beside the conversation.
        artifact = { columns.artifact },
        onArtifactChange = { columns.artifact = it },
        artifactOpen = columnHost && detailAccepted && columns.artifact.isOpen,
        onArtifactShown = { columns.artifactShown = it },
        expand = columns.expand,
        main = main,
    )
}
