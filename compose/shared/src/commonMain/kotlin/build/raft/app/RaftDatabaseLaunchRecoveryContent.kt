package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import androidx.compose.runtime.Composable
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftIconTileVariant
import build.raft.app.data.RaftDatabaseVersionMismatch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RaftDatabaseLaunchRecoveryContent(
    mismatch: RaftDatabaseVersionMismatch,
    onRetry: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceCream)
                .padding(RaftAppSpacing.Xl),
        contentAlignment = Alignment.Center,
    ) {
        RaftCard(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
                ) {
                    RaftIconTile(variant = RaftIconTileVariant.Warning, size = RaftIconTileSize.Lg) {
                        RaftLucideIcon(
                            name = RaftLucideIconName.TriangleAlert,
                            modifier = Modifier.size(RaftIconTileSize.Lg.icon),
                            color = raftArgbHex(RaftColor.TextBlack),
                        )
                    }
                    Text(
                        text = stringResource(Res.string.database_recovery_title),
                        modifier = Modifier.weight(1f),
                        style = RaftType.PageTitle,
                        color = RaftColor.TextBlack,
                    )
                }
                Text(
                    text =
                        stringResource(
                            Res.string.database_recovery_detail,
                            mismatch.databaseVersion,
                            mismatch.supportedVersion,
                        ),
                    style = RaftType.Body,
                    color = RaftColor.TextMutedGray,
                )
                RaftButton(
                    text = stringResource(Res.string.database_recovery_action_retry),
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) },
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                )
            }
        }
    }
}
