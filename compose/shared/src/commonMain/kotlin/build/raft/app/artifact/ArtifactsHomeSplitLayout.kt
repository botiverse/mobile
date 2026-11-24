package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.cmp.host.LocalRaftHost
import build.raft.app.compat.pager.RaftPaneRole
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftPaneGridExpand
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import kotlinx.serialization.json.JsonObject

/*
 * Wide split-column Artifact viewer layout for the Artifacts home page (web `ArtifactsRoute.tsx` +
 * `ArtifactsDashboard.tsx`, botiverse/slock #16418):
 * - On wide windows (`wide == true`), clicking an Artifact tile or row opens the viewer in a split
 *   column right of the list using the shared `RaftPaneGridColumns.Artifact` width (480dp default,
 *   480dp contentMin, 160dp min, persisted under `raftPaneGrid:width:artifact` shared with the
 *   conversation's Artifact column). The list keeps its own main-pane bounds (`ArtifactsList`,
 *   contentMin 360dp) and stays interactive beside the viewer.
 * - Focus Mode / Expand (#189 rule 5, [expand]): expanding the `"artifact"` region fills the row,
 *   leaving the list at 160dp. First Back restores side-by-side without closing the viewer; second
 *   Back closes the viewer column (`RaftArtifactColumnCell`). Opening another Artifact while
 *   expanded restores side-by-side so the list and viewer stay in view.
 * - On narrow / phone viewports (`wide == false`), no split column is mounted and the caller pushes
 *   the full-screen viewer page as before while keeping the list mounted underneath.
 */

internal object ArtifactsHomeSplitTags {
    const val Viewer = "artifacts-dashboard-viewer"
}

/** The Artifact id currently shown in this split column, or null when closed. */
internal fun RaftArtifactColumnPane.currentArtifactIdOrNull(): String? =
    entry?.pageData?.toKuiklyJson()?.raftArtifactViewerTargetOrNull()?.id

@Composable
internal fun ArtifactsHomeSplitLayout(
    wide: Boolean,
    artifact: () -> RaftArtifactColumnPane,
    onArtifactChange: (RaftArtifactColumnPane) -> Unit,
    active: Boolean,
    expand: RaftPaneGridExpand = remember { RaftPaneGridExpand() },
    modifier: Modifier = Modifier,
    page: @Composable (entry: RaftPaneEntry, active: Boolean) -> Unit = { entry, pageActive ->
        val pageData = remember(entry) { entry.pageData.toKuiklyJson() }
        RaftChildPage(pageName = entry.pageName, pageData = pageData, modifier = Modifier.fillMaxSize(), active = pageActive)
    },
    list: @Composable () -> Unit,
) {
    if (!wide) {
        Box(modifier) {
            CompositionLocalProvider(LocalRaftArtifactOpenInColumn provides false) {
                list()
            }
        }
        return
    }
    // Rule 5: Back restores an expanded row before the Artifact column's own Back closes the viewer.
    RaftBackHandler(enabled = active && expand.region != null, label = "artifacts_home_row_expand", blocksBackSwipe = false) {
        expand.restore()
    }
    val currentArtifact by rememberUpdatedState(artifact)
    val currentOnArtifactChange by rememberUpdatedState(onArtifactChange)
    val changeArtifact: (RaftArtifactColumnPane) -> Unit =
        remember(expand) {
            { next: RaftArtifactColumnPane ->
                val opened = next.entry != null && next.entry.id != currentArtifact().entry?.id
                if (opened) expand.restore()
                currentOnArtifactChange(next)
            }
        }
    val openArtifact: (String, JsonObject) -> Unit =
        remember(changeArtifact) {
            { name: String, data: JsonObject ->
                changeArtifact(currentArtifact().open(name, data))
            }
        }
    val shellHost = LocalRaftHost.current
    val artifactOpen = artifact().isOpen
    val artifactColumn =
        if (artifactOpen) {
            raftArtifactColumn {
                CompositionLocalProvider(LocalRaftHost provides shellHost) {
                    RaftFocusRegionHost(
                        region = RaftFocusRegion.SupportingPane,
                        modifier = Modifier.fillMaxSize().testTag(ArtifactsHomeSplitTags.Viewer),
                    ) {
                        RaftArtifactColumnCell(
                            pane = artifact,
                            active = active,
                            backOn = { expand.region == null },
                            onChange = changeArtifact,
                            openArtifact = openArtifact,
                            openSupporting = { name, data -> shellHost.navigator.openPage(name, data) },
                            page = page,
                        )
                    }
                }
            }
        } else {
            null
        }
    val mainTabLabel = stringResource(Res.string.artifacts_dashboard_title)
    RaftPaneHost(
        wide = true,
        openInPane = { role, pageName, pageData ->
            if (role == RaftPaneRole.Artifact) openArtifact(pageName, pageData) else shellHost.navigator.openPage(pageName, pageData)
        },
    ) {
        CompositionLocalProvider(LocalRaftArtifactOpenInColumn provides true) {
            RaftPaneGrid(
                columns = listOfNotNull(artifactColumn),
                modifier = modifier,
                mainBounds = RaftPaneGridColumns.ArtifactsList,
                mainTabLabel = mainTabLabel,
                expand = expand,
                main = list,
            )
        }
    }
}
