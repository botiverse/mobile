package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.RaftFieldItem
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * Web `SameAdminJointNotice.tsx` (RFC 075 §4.5): shown when the inviter invites themselves on a
 * server they own or administer. The connection is made directly, without an email, so it must
 * be confirmed before the invite is submitted.
 */
@Composable
internal fun JointSameAdminNotice(
    sourceServerName: String,
    targetServerName: String,
    confirmed: Boolean,
    onConfirmedChange: (Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftBanner(
            description = stringResource(MR.strings.joint_same_admin_notice, sourceServerName, targetServerName),
            status = RaftBannerStatus.Info,
            size = RaftBannerSize.Sm,
        )
        RaftFieldItem(label = stringResource(MR.strings.joint_same_admin_confirm), enabled = enabled) {
            RaftCheckbox(checked = confirmed, onCheckedChange = onConfirmedChange, enabled = enabled)
        }
    }
}
