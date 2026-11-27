package build.raft.app.members

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.MemberDetailIdentity
import build.raft.app.RaftColor
import build.raft.designsystem.components.raftConsumeSafeAreaHorizontal

/**
 * True inside the wide Members split detail pane: the member list stays visible beside it, so the
 * detail head hides the phone Back arrow (web AgentDetailPanel / HumanDetailPanel `useMobileBack`).
 */
internal val LocalRaftMembersWideSplitDetail = staticCompositionLocalOf { false }

internal fun raftMembersDetailShowsBack(sidePaneShowsBack: Boolean, inWideSplit: Boolean): Boolean =
    !inWideSplit && sidePaneShowsBack

/** Keep the list at one composition location, including while a selected detail
 * becomes full-width after resize. Search, collapse and scroll stay mounted. */
@Composable
internal fun MembersAdaptiveWorkspace(
    split: Boolean,
    selected: Boolean,
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        val listModifier = when {
            split -> Modifier.width(360.dp)
            selected -> Modifier.width(0.dp)
            else -> Modifier.weight(1f)
        }
        Box(
            listModifier
                .fillMaxHeight()
                .then(if (split) Modifier.raftConsumeSafeAreaHorizontal(WindowInsetsSides.End) else Modifier),
        ) { listPane() }
        Spacer(Modifier.width(if (split) 1.dp else 0.dp).fillMaxHeight().background(RaftColor.BorderSeam)) // raft-ui: one line-seam between regions
        Box(
            (if (split || selected) Modifier.weight(1f) else Modifier.width(0.dp))
                .fillMaxHeight()
                .then(if (split) Modifier.raftConsumeSafeAreaHorizontal(WindowInsetsSides.Start) else Modifier),
        ) {
            if (split || selected) {
                CompositionLocalProvider(LocalRaftMembersWideSplitDetail provides split) {
                    detailPane()
                }
            }
        }
    }
}

internal fun MemberProfile.matchesMemberSelection(identity: MemberDetailIdentity?, localServerId: ServerId?): Boolean =
    identity != null && id == identity.memberId && kind == identity.kind &&
        (serverId ?: localServerId) == identity.sourceServerId
