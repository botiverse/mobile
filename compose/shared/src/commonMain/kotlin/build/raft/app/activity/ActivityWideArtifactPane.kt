package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedAdaptiveWorkspaceStore
import build.raft.app.data.closeDetailCompletely
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import kotlinx.serialization.json.JsonObject

/*
 * Wide split-pane Artifact viewer for Activity (web `MainLayout.tsx` `InboxContentRoute` +
 * `ArtifactContentSlot` + `ArtifactActivityViewer.tsx`):
 * - On wide screens (`dualPane` + desktop mode on large screens), clicking an Artifact row in the
 *   Activity list opens the Artifact viewer in the right detail pane beside the Activity list,
 *   keeping the Activity list visible and interactive on the left instead of pushing a full-screen
 *   window page.
 * - Switching to another Artifact replaces the right pane in place; switching to a conversation or
 *   thread row closes the Artifact pane and opens that conversation in the same right detail slot
 *   (and vice versa).
 * - Closing the Artifact (its Close button, embedded `#host=close`, or Back/Esc) returns cleanly
 *   to the Activity list with no dangling detail selection or SupportingPane focus.
 * - On phone / narrow viewports (or with desktop mode off), no split viewer is used and clicking
 *   an Artifact pushes the full-screen viewer page as before; narrowing while an Artifact is open
 *   in the wide Activity pane promotes it onto the full-screen window stack.
 */

internal object ActivityWideArtifactTags {
    const val Viewer = "activity-split-artifact-viewer"
}

@Stable
internal class ActivityWideArtifactState {
    var pane by mutableStateOf(RaftArtifactColumnPane())

    val isOpen: Boolean
        get() = pane.isOpen

    /** Identity of the open Artifact, or null when closed. */
    val currentTarget: ArtifactLinkTarget?
        get() = pane.entry?.pageData?.toKuiklyJson()?.raftArtifactViewerTargetOrNull()

    /** Id of the open Artifact, or null when closed. */
    val currentArtifactId: String?
        get() = pane.currentArtifactIdOrNull()

    fun open(pageData: JsonObject) {
        pane = pane.open(RAFT_ARTIFACT_VIEWER_PAGE, pageData)
    }

    fun open(target: ArtifactLinkTarget, storeScope: RaftShellStoreScope, conversationId: String? = null) {
        val data = raftArtifactViewerPageData(target, conversationId).withRaftShellStoreScope(storeScope).toJsonObject()
        open(data)
    }

    /** Closes the open Artifact if any; returns true when one was open. */
    fun close(): Boolean {
        if (!pane.isOpen) return false
        pane = pane.cleared()
        return true
    }

    fun close(id: Long) {
        pane = pane.close(id)
    }

    /** Window narrowed out of wide split: promote the open viewer onto the platform stack. */
    fun promoteToWindow(navigator: RaftCmpNavigator): Boolean {
        val entry = pane.entry ?: return false
        pane = pane.cleared()
        navigator.openPage(entry.pageName, entry.pageData)
        return true
    }
}

/**
 * Lifecycle for [ActivityWideArtifactState] (kept out of `SettingsHomePage`'s
 * `RaftAppShellReadyContent` to stay within the JVM method-size budget):
 * - Server switches close the previous server's Artifact.
 * - Leaving the Activity surface (`!surfaceSelected`) closes the right-pane Artifact so its
 *   WebView parks cleanly and never contends with Chat's Artifact column or Artifacts Home over
 *   the shared `keepAliveKey`.
 * - Narrowing out of wide split (`!wide`) while Activity is visible promotes the open Artifact
 *   onto the full-screen window stack.
 */
@Composable
internal fun ActivityWideArtifactLifecycleEffect(
    state: ActivityWideArtifactState,
    wide: Boolean,
    surfaceSelected: Boolean,
    active: Boolean,
    serverId: String?,
) {
    val shellHost = LocalRaftHost.current
    LaunchedEffect(serverId) {
        state.close()
    }
    LaunchedEffect(surfaceSelected) {
        if (!surfaceSelected) state.close()
    }
    LaunchedEffect(wide, active, state.pane.entry?.id) {
        if (!wide && active && state.isOpen) {
            state.promoteToWindow(shellHost.navigator)
        }
    }
}

/**
 * Opens [target] in Activity's right detail pane, clearing any conversation currently held in
 * [adaptiveWorkspaceStore] so closing the Artifact returns cleanly to the Activity list rather
 * than reviving a previously replaced conversation.
 */
internal fun openActivityWideArtifactInShell(
    state: ActivityWideArtifactState,
    target: ArtifactLinkTarget,
    scopeOverride: RaftShellStoreScope?,
    fallbackScope: RaftShellStoreScope,
    conversationId: String?,
    sharedStores: RaftSharedStoreProvider,
    adaptiveWorkspaceStore: SharedAdaptiveWorkspaceStore,
    releaseLease: (Long) -> Unit,
    clearSelectedConversation: () -> Unit,
): Boolean {
    val selection = adaptiveWorkspaceStore.snapshot().workspace?.selection
    if (selection != null) {
        adaptiveWorkspaceStore.update { it.closeDetailCompletely() }
        releaseLease(selection.generation)
        clearSelectedConversation()
    }
    val resolvedScope = sharedStores.currentNavigationStoreScope(scopeOverride ?: fallbackScope)
    state.open(target, resolvedScope, conversationId)
    return true
}

/**
 * Activity's right-pane Artifact viewer (beside the Activity list in [RaftAdaptiveListDetailSplit]
 * or `HomeActivityRouterContent`): reuses [RaftArtifactColumnCell] so `LocalRaftArtifactViewerInColumn`,
 * `LocalRaftSidePanePage`, `LocalRaftArtifactViewerColumnWidthDp`, `RaftPaneHost` (in-place switch
 * for Artifact links and `closePage` closing this pane), and `RaftBackHandler` all match the wide
 * viewer contract, wrapped in [RaftFocusRegion.SupportingPane] so closing releases focus cleanly.
 */
@Composable
internal fun RaftActivityArtifactDetailPane(
    state: ActivityWideArtifactState,
    active: Boolean,
    modifier: Modifier = Modifier,
    onOpenSupporting: ((String, JsonObject) -> Unit)? = null,
    page: @Composable (entry: RaftPaneEntry, active: Boolean) -> Unit = { entry, pageActive ->
        val pageData = remember(entry) { entry.pageData.toKuiklyJson() }
        RaftChildPage(pageName = entry.pageName, pageData = pageData, modifier = Modifier.fillMaxSize(), active = pageActive)
    },
) {
    val shellHost = LocalRaftHost.current
    val currentOnOpenSupporting by rememberUpdatedState(onOpenSupporting)
    CompositionLocalProvider(LocalRaftHost provides shellHost) {
        RaftFocusRegionHost(
            region = RaftFocusRegion.SupportingPane,
            modifier = modifier.testTag(ActivityWideArtifactTags.Viewer),
        ) {
            RaftArtifactColumnCell(
                pane = { state.pane },
                active = active,
                backOn = { true },
                onChange = { state.pane = it },
                openArtifact = { _, data -> state.open(data) },
                openSupporting = { name, data ->
                    currentOnOpenSupporting?.invoke(name, data) ?: shellHost.navigator.openPage(name, data)
                },
                page = page,
            )
        }
    }
}
