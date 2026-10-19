package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalCard
import build.raft.app.data.SlockDatabaseVersionMismatch
import build.raft.app.resources.MR
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
import com.tencent.tmm.kmmresource.compose.stringResource

@Composable
internal fun SlockDatabaseLaunchRecoveryContent(
    mismatch: SlockDatabaseVersionMismatch,
    onRetry: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceCream)
                .padding(SlockSpacing.Xl),
        contentAlignment = Alignment.Center,
    ) {
        SlockBrutalCard(
            modifier = Modifier.widthIn(max = 480.dp),
            contentPadding = 20.dp,
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .background(SlockColor.BrandYellow),
                    contentAlignment = Alignment.Center,
                ) {
                    SlockLucideIcon(
                        name = SlockLucideIconName.TriangleAlert,
                        modifier = Modifier.size(22.dp),
                        color = SlockColor.TextBlackArgb,
                    )
                }
                Text(
                    text = stringResource(MR.strings.database_recovery_title),
                    modifier = Modifier.weight(1f),
                    style = SlockType.PageTitle,
                    color = SlockColor.TextBlack,
                )
            }
            Text(
                text =
                    stringResource(
                        MR.strings.database_recovery_detail,
                        mismatch.databaseVersion,
                        mismatch.supportedVersion,
                    ),
                style = SlockType.Body,
                color = SlockColor.TextMutedGray,
            )
            SlockBrutalActionButton(
                label = stringResource(MR.strings.database_recovery_action_retry),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = SlockLucideIconName.RefreshCw,
                backgroundColor = SlockColor.BrandYellow,
            )
        }
    }
}
