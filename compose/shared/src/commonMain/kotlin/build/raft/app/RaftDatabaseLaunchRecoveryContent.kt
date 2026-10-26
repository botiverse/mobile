package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import androidx.compose.runtime.Composable
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalCard
import build.raft.app.data.RaftDatabaseVersionMismatch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
        RaftBrutalCard(
            modifier = Modifier.widthIn(max = 480.dp),
            contentPadding = 20.dp,
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .background(RaftColor.BrandYellow),
                    contentAlignment = Alignment.Center,
                ) {
                    RaftLucideIcon(
                        name = RaftLucideIconName.TriangleAlert,
                        modifier = Modifier.size(22.dp),
                        color = raftArgbHex(RaftColor.StickerInk),
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
