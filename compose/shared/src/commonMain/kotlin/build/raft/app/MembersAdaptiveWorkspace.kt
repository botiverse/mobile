package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
        Box(listModifier.fillMaxHeight()) { listPane() }
        Spacer(Modifier.width(if (split) 1.dp else 0.dp).fillMaxHeight().background(LocalSlockTheme.current.line))
        Box((if (split || selected) Modifier.weight(1f) else Modifier.width(0.dp)).fillMaxHeight()) {
            if (split || selected) detailPane()
        }
    }
}

internal fun MemberProfile.matchesMemberSelection(identity: MemberDetailIdentity?, localServerId: ServerId?): Boolean =
    identity != null && id == identity.memberId && kind == identity.kind &&
        (serverId ?: localServerId) == identity.sourceServerId
