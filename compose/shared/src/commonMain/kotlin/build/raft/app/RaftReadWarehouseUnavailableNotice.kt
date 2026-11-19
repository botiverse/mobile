package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftPanelNotice

/**
 * botiverse/slock #12066: the server has no read warehouse (`503 rw_unconfigured`, a self-hosted
 * server without RisingWave), so the reads will not catch up on their own. A warning PanelNotice
 * directly under the page head (Activity: above the filters; phone Home: above the list), web
 * StaleCountsNotice's `rw_unconfigured` case.
 *
 * A read warehouse that is only behind (task #164's stale flag) shows no notice (artin
 * 2026-11-19): the page keeps its last good result and updates silently on recovery.
 */
@Composable
internal fun RaftReadWarehouseUnavailableNotice(message: String, modifier: Modifier = Modifier) {
    RaftPanelNotice(
        message = message,
        modifier = modifier,
        status = RaftBannerStatus.Warning,
    )
}
