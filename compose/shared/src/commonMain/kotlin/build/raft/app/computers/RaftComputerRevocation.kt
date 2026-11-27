package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.data.SettingsComputerRevocationSnapshot
import build.raft.app.data.SettingsComputerRevokedVia
import build.raft.app.members.membersLocalizedCreatedDateOrNull
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Revoked Computer copy and detail notice (botiverse/slock #16468 / #603:
 * features/computer/computerRevocationCopy.ts and ComputerRevokedNotice.tsx).
 * Used on both the Computer detail page and the Agent detail page's revoked note.
 */

internal object RaftComputerRevocationTestTags {
    const val Root = "computer-revoked"
    const val Banner = "computer-revoked-banner"
    const val Recovery = "computer-revoked-recovery"
    const val SetupCommand = "computer-revoked-setup-command"
}

/** Web `computerRevokedTitle`: "Disconnected on Nov 20, 2026". */
@Composable
internal fun raftComputerRevokedTitle(
    revocation: SettingsComputerRevocationSnapshot,
    timeContext: RaftTimeContext? = null,
): String {
    val effective = currentRaftEffectiveDateTimeContext(timeContext)
    val date = revocation.at.membersLocalizedCreatedDateOrNull(effective) ?: revocation.at
    return stringResource(Res.string.settings_computers_revoked_title, date)
}

/** Web `computerRevokedCause`: why its key was revoked, one sentence. */
@Composable
internal fun raftComputerRevokedCause(revocation: SettingsComputerRevocationSnapshot): String {
    val name = revocation.subjectUserName?.trim()?.takeIf { it.isNotBlank() }
    val by = revocation.byUserName?.trim()?.takeIf { it.isNotBlank() }
    return when (revocation.via) {
        SettingsComputerRevokedVia.MemberLeft ->
            if (name != null) {
                stringResource(Res.string.settings_computers_revoked_cause_member_left, name)
            } else {
                stringResource(Res.string.settings_computers_revoked_cause_member_left_unnamed)
            }
        SettingsComputerRevokedVia.MemberRemoved ->
            when {
                name != null && by != null ->
                    stringResource(Res.string.settings_computers_revoked_cause_member_removed_by, by, name)
                name != null ->
                    stringResource(Res.string.settings_computers_revoked_cause_member_removed, name)
                else ->
                    stringResource(Res.string.settings_computers_revoked_cause_member_removed_unnamed)
            }
        SettingsComputerRevokedVia.AccountDeleted ->
            if (name != null) {
                stringResource(Res.string.settings_computers_revoked_cause_account_deleted, name)
            } else {
                stringResource(Res.string.settings_computers_revoked_cause_account_deleted_unnamed)
            }
        SettingsComputerRevokedVia.KeyRevokedManually ->
            if (by != null) {
                stringResource(Res.string.settings_computers_revoked_cause_revoked_by, by)
            } else {
                stringResource(Res.string.settings_computers_revoked_cause_revoked_manually)
            }
        SettingsComputerRevokedVia.RevokeLink ->
            stringResource(Res.string.settings_computers_revoked_cause_revoke_link)
        SettingsComputerRevokedVia.ComputerDetached ->
            stringResource(Res.string.settings_computers_revoked_cause_detached)
        SettingsComputerRevokedVia.SessionRevoked ->
            stringResource(Res.string.settings_computers_revoked_cause_session_revoked)
        SettingsComputerRevokedVia.ApproverSessionRevoked ->
            stringResource(Res.string.settings_computers_revoked_cause_approver_session_revoked)
        SettingsComputerRevokedVia.SetupReset ->
            stringResource(Res.string.settings_computers_revoked_cause_setup_reset)
        null ->
            stringResource(Res.string.settings_computers_revoked_cause_unknown)
    }
}

/** Web `machine.revoked.agentsOffline`: omitted when zero agents are on this Computer. */
@Composable
internal fun raftComputerRevokedAgentsOffline(agentCount: Int): String? =
    when {
        agentCount <= 0 -> null
        agentCount == 1 -> stringResource(Res.string.settings_computers_revoked_agents_offline_one, agentCount)
        else -> stringResource(Res.string.settings_computers_revoked_agents_offline_other, agentCount)
    }

/**
 * Web `ComputerRevokedNotice` (slock #16468): replaces the legacy-daemon migrate block for a
 * machine whose Computers are all revoked — warning banner with when, why, and offline agent count,
 * followed by "Bring it back" with the `--machine <id>` setup command and the move-agents option.
 */
@Composable
internal fun RaftComputerRevokedNotice(
    revocation: SettingsComputerRevocationSnapshot,
    agentCount: Int,
    setupCommand: String?,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    timeContext: RaftTimeContext? = null,
) {
    val title = raftComputerRevokedTitle(revocation, timeContext)
    val cause = raftComputerRevokedCause(revocation)
    val agentsOffline = raftComputerRevokedAgentsOffline(agentCount)
    val description = if (agentsOffline != null) "$cause $agentsOffline" else cause
    val setupLabel = stringResource(Res.string.settings_computers_setup_step)
    Column(
        modifier = modifier.fillMaxWidth().testTag(RaftComputerRevocationTestTags.Root),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RaftBanner(
            description = description,
            status = RaftBannerStatus.Warning,
            title = title,
            modifier = Modifier.testTag(RaftComputerRevocationTestTags.Banner),
        )
        Column(
            modifier = Modifier.fillMaxWidth().testTag(RaftComputerRevocationTestTags.Recovery),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RaftSectionEyebrow(text = stringResource(Res.string.settings_computers_revoked_recover_title))
            RaftText(
                text = stringResource(Res.string.settings_computers_revoked_reattach),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
            )
            setupCommand?.takeIf { it.isNotBlank() }?.let { command ->
                RaftComputerCommandLine(
                    command = command,
                    onCopy = onCopy,
                    label = setupLabel,
                    modifier = Modifier.testTag(RaftComputerRevocationTestTags.SetupCommand),
                )
            }
            RaftText(
                text = stringResource(Res.string.settings_computers_revoked_move_agents),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
            )
        }
    }
}
