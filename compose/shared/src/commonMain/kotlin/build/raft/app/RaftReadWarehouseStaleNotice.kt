package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftPanelNotice

/**
 * Task #164, stdrc's O2: while the read warehouse is behind, the page's numbers may be old. A
 * raft-ui PanelNotice in the info tint, with the pending spinner, directly under the page head
 * (Activity: above the filters; phone Home: above the list). The numbers themselves are not
 * dimmed or marked. The notice is a polite live region, so a screen reader announces it once
 * when it appears; it leaves by itself when a fresh read clears the store's stale flag.
 */
@Composable
internal fun RaftReadWarehouseStaleNotice(message: String, modifier: Modifier = Modifier) {
    RaftPanelNotice(
        message = message,
        modifier = modifier,
        status = RaftBannerStatus.Info,
        pending = true,
    )
}
