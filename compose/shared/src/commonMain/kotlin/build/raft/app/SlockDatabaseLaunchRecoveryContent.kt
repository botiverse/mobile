package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalCard
import build.raft.app.data.SlockDatabaseVersionMismatch
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
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
