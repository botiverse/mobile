package build.raft.app.members

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsComputerRevocationSnapshot
import build.raft.app.model.RaftTimeContext
import build.raft.app.raftComputerCommandPlatform
import build.raft.app.raftComputerCommands
import build.raft.app.raftComputerRevokedCause
import build.raft.app.raftComputerRevokedTitle
import build.raft.app.resources.*
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * Under the agent page's "Computer access revoked" status notice (botiverse/slock #16468 / #603,
 * features/agent/AgentComputerRevokedNote.tsx): when and why its Computer was revoked, and both
 * ways back — reattach that machine with its `--machine <id>` setup command (approved by an owner
 * or admin) or move the agent to another computer.
 */

internal object MembersAgentComputerRevokedTestTags {
    const val Note = "agent-computer-revoked-note"
    const val Title = "agent-computer-revoked-title"
    const val Cause = "agent-computer-revoked-cause"
    const val Reattach = "agent-computer-revoked-reattach"
    const val Move = "agent-computer-revoked-move"
}

/** Resolved state for [MembersAgentComputerRevokedNote]; null means do not show the note. */
internal data class MembersAgentComputerRevokedNoteState(
    val machineId: String,
    val computerName: String,
    val revocation: SettingsComputerRevocationSnapshot,
    /** Copyable `raft-computer setup /<slug> --machine <id>` command; null without a server slug. */
    val setupCommand: String?,
)

/**
 * Resolves [MembersAgentComputerRevokedNoteState] with the exact web gating of
 * `AgentComputerRevokedNote`: hidden for deleted, external, and bounded-public-projection agents,
 * for live Computers (`isComputer == true`), and whenever the machine list carries no `revocation`
 * (an older server or a viewer who may not list Computers).
 */
internal fun resolveMembersAgentComputerRevokedNoteState(
    machineId: String?,
    machines: List<ComputerSummary>,
    commandContext: SettingsComputerCommandContext? = null,
    deleted: Boolean = false,
    isExternalAgent: Boolean = false,
    isBoundedPublicProjection: Boolean = false,
): MembersAgentComputerRevokedNoteState? {
    if (deleted || isExternalAgent || isBoundedPublicProjection) return null
    val id = machineId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val machine = machines.firstOrNull { it.id == id } ?: return null
    if (machine.isComputer) return null
    val revocation = machine.revocation ?: return null
    val setupCommand =
        raftComputerCommands(
            context = commandContext,
            platform = raftComputerCommandPlatform(machine.os),
            machineId = machine.id,
        )?.setup
    return MembersAgentComputerRevokedNoteState(
        machineId = machine.id,
        computerName = machine.name.trim().ifBlank { machine.id },
        revocation = revocation,
        setupCommand = setupCommand,
    )
}

private const val MembersRevokedCommandSentinel = "\uE000RAFT_REVOKED_CMD\uE001"

/**
 * Agent detail page note under the "Computer access revoked" status (web `AgentComputerRevokedNote`).
 */
@Composable
internal fun MembersAgentComputerRevokedNote(
    state: MembersAgentComputerRevokedNoteState,
    timeContext: RaftTimeContext? = null,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val title = raftComputerRevokedTitle(state.revocation, timeContext)
    val cause = raftComputerRevokedCause(state.revocation)
    Column(
        modifier = modifier.fillMaxWidth().testTag(MembersAgentComputerRevokedTestTags.Note),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RaftText(
            text = title,
            style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            modifier = Modifier.testTag(MembersAgentComputerRevokedTestTags.Title),
        )
        RaftText(
            text = cause,
            style = typography.callout,
            color = colors.foreground,
            modifier = Modifier.testTag(MembersAgentComputerRevokedTestTags.Cause),
        )
        val setup = state.setupCommand?.takeIf { it.isNotBlank() }
        if (setup != null) {
            val template = stringResource(Res.string.agent_revoked_reattach, state.computerName, MembersRevokedCommandSentinel)
            val mono = typography.monoMeta.toSpanStyle().copy(background = colors.fillMuted)
            val annotated =
                buildMembersOfflineCardAnnotatedString(template, MembersRevokedCommandSentinel) {
                    withStyle(mono) {
                        append(setup)
                    }
                }
            RaftText(
                text = annotated,
                style = typography.callout,
                color = colors.foreground,
                modifier = Modifier.testTag(MembersAgentComputerRevokedTestTags.Reattach),
            )
        }
        RaftText(
            text = stringResource(Res.string.agent_revoked_move),
            style = typography.callout,
            color = colors.foreground,
            modifier = Modifier.testTag(MembersAgentComputerRevokedTestTags.Move),
        )
    }
}
