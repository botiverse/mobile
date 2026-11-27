package build.raft.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.SecurityRevokePreview
import build.raft.app.data.SharedPasswordDisconnectPreviewStore
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme

/*
 * Disconnect preview UI for security actions (`botiverse/slock` #17069 / #17081, web
 * `components/auth/DisconnectPreview.tsx`):
 * - [SettingsDisconnectPreviewList]: named list of Computers that will disconnect (with server
 *   name + stopped agent count) and reconnect instructions. Agent keys are never shown; an answer
 *   with zero Computers renders nothing.
 * - [SettingsPasswordDisconnectNotice]: Settings › Account › Change password banner above submit.
 */

internal const val SettingsDisconnectPreviewTag = "disconnect-preview"
internal const val SettingsDisconnectPreviewRowsTag = "disconnect-preview-rows"
internal const val SettingsDisconnectPreviewComputerTag = "disconnect-preview-computer"
internal const val SettingsDisconnectPreviewReconnectTag = "disconnect-preview-reconnect"

internal const val SettingsPasswordDisconnectGeneralTag = "password-disconnect-general"
internal const val SettingsPasswordDisconnectNoneTag = "password-disconnect-none"
internal const val SettingsPasswordDisconnectNamedTag = "password-disconnect-named"

/** Password-change disconnect preview wiring passed into [SettingsAccountContent]. */
internal class SettingsPasswordDisconnectPreviewUi(
    val preview: SecurityRevokePreview?,
    val onFormOpenChanged: (Boolean) -> Unit,
)

@Composable
internal fun rememberSettingsPasswordDisconnectPreviewStore(
    networkClient: RaftNetworkClient,
): SharedPasswordDisconnectPreviewStore {
    val scope = rememberCoroutineScope()
    return remember(networkClient, scope) { SharedPasswordDisconnectPreviewStore(networkClient, scope) }
}

/**
 * Named disconnect preview list rendered inside [SettingsSignOutDeviceDialog] when `lost_device`
 * is active (or for leftovers). Renders nothing when [preview] has no Computers (`slock#17081`).
 */
@Composable
internal fun SettingsDisconnectPreviewList(
    preview: SecurityRevokePreview,
    modifier: Modifier = Modifier,
) {
    if (preview.computers.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().testTag(SettingsDisconnectPreviewTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftText(
            text = stringResource(Res.string.auth_disconnect_preview_title),
            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
        )
        SettingsDisconnectPreviewRowsAndReconnect(preview, inBanner = false)
    }
}

@Composable
private fun SettingsDisconnectPreviewRowsAndReconnect(
    preview: SecurityRevokePreview,
    inBanner: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RaftListRowGroup(Modifier.fillMaxWidth().testTag(SettingsDisconnectPreviewRowsTag)) {
            preview.computers.forEachIndexed { index, computer ->
                val detail =
                    when {
                        computer.agentCount <= 0 ->
                            stringResource(Res.string.auth_disconnect_preview_computer_detail_none, computer.serverName)
                        computer.agentCount == 1 ->
                            stringResource(
                                Res.string.auth_disconnect_preview_computer_detail_one,
                                computer.serverName,
                                computer.agentCount,
                            )
                        else ->
                            stringResource(
                                Res.string.auth_disconnect_preview_computer_detail_other,
                                computer.serverName,
                                computer.agentCount,
                            )
                    }
                RaftListRow(
                    title = computer.name,
                    modifier = Modifier.testTag(SettingsDisconnectPreviewComputerTag),
                    variant = RaftListRowVariant.Plain,
                    divider = index > 0,
                    subtitle = detail,
                )
            }
        }
        RaftText(
            text = stringResource(Res.string.auth_disconnect_preview_reconnect),
            modifier = Modifier.testTag(SettingsDisconnectPreviewReconnectTag),
            style = if (inBanner) RaftTheme.typography.callout else RaftTheme.typography.footnote,
            color = if (inBanner) LocalRaftContentColor.current else RaftTheme.colors.foregroundMuted,
        )
    }
}

/**
 * Change-password warning above submit (web `PasswordDisconnectNotice`, `slock#17069` / `#17081`):
 * - `preview == null` (loading or failed): info banner with the general notice.
 * - `preview.computers.isEmpty()` (including keys-only): info banner with "signs you out everywhere".
 * - `preview.computers.isNotEmpty()`: warning banner (`live = false`) with "signs you out
 *   everywhere" title, "This will disconnect:" lead, Computer rows, and reconnect guidance.
 */
@Composable
internal fun SettingsPasswordDisconnectNotice(
    preview: SecurityRevokePreview?,
    modifier: Modifier = Modifier,
) {
    when {
        preview == null ->
            RaftBanner(
                description = stringResource(Res.string.settings_account_device_login_notice),
                status = RaftBannerStatus.Info,
                modifier = modifier.fillMaxWidth().testTag(SettingsPasswordDisconnectGeneralTag),
                size = RaftBannerSize.Sm,
            )
        preview.computers.isEmpty() ->
            RaftBanner(
                description = stringResource(Res.string.settings_account_password_signs_out_everywhere),
                status = RaftBannerStatus.Info,
                modifier = modifier.fillMaxWidth().testTag(SettingsPasswordDisconnectNoneTag),
                size = RaftBannerSize.Sm,
            )
        else ->
            RaftBanner(
                description =
                    AnnotatedString(
                        text = stringResource(Res.string.auth_disconnect_preview_title),
                        spanStyle = SpanStyle(fontWeight = FontWeight.SemiBold),
                    ),
                status = RaftBannerStatus.Warning,
                modifier = modifier.fillMaxWidth().testTag(SettingsPasswordDisconnectNamedTag),
                title = stringResource(Res.string.settings_account_password_signs_out_everywhere),
                size = RaftBannerSize.Sm,
                live = false,
                content = { SettingsDisconnectPreviewRowsAndReconnect(preview, inBanner = true) },
            )
    }
}
