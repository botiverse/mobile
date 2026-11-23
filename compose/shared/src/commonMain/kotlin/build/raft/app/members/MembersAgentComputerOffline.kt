package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.components.rememberRaftCopyAction
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.delay

/*
 * The agent profile's 'Computer offline' card (web `AgentComputerOfflineCard`,
 * `useAgentComputerOffline`, `agentComputerCommands`, botiverse/slock #13076): which Computer,
 * since when, and the same two recovery steps for everyone — turn that computer on and keep it
 * from sleeping, then open Raft Desktop on it or run the Computer page's copyable restart command.
 */

internal object MembersAgentComputerOfflineTestTags {
    const val Card = "agent-computer-offline-card"
    const val Title = "agent-computer-offline-card-title"
    const val StepPower = "agent-computer-offline-step-power"
    const val StepRun = "agent-computer-offline-step-run"
    const val Command = "agent-computer-offline-command"
    const val CopyCommand = "agent-computer-offline-copy-command"
}

/**
 * Inputs from the computers store and server context needed to resolve the profile's
 * [MembersAgentComputerOfflineCardState]. Plain holder, matching the other Members detail inputs.
 */
internal class MembersAgentOfflineComputerInputs(
    val machines: List<ComputerSummary> = emptyList(),
    val offlineObservedAtMs: Map<String, Long> = emptyMap(),
    val commandContext: SettingsComputerCommandContext? = null,
    /** Machine IDs marked offline only from a refused Start (no known offline time). */
    val offlineFromRefusalIds: Set<String> = emptySet(),
)

/** Resolved state for [MembersAgentComputerOfflineCard]; null means do not show the card. */
internal data class MembersAgentComputerOfflineCardState(
    val machineId: String,
    val computerName: String,
    /** Owner's display name for step 1 ("Turn on <owner>'s computer ..."); null when unknown. */
    val ownerName: String?,
    /** Epoch millis when this session observed the Computer go offline; wins over [sinceIso]. */
    val sinceEpochMillis: Long?,
    /** Server-reported `lastHeartbeat` ISO timestamp; used when [sinceEpochMillis] is null. */
    val sinceIso: String?,
    /** Copyable `raft-computer restart /<slug>` command; null without a server slug. */
    val restartCommand: String?,
)

/**
 * Who a Computer belongs to (web `computerOwnerId` in `useAgentComputerOffline.ts`): the machine
 * row's owner (`machines.userId`, carried on [ComputerSummary.creatorUserId]), else the person who
 * attached it (`creator`). Not the server's Owner role.
 */
internal fun membersComputerOwnerId(machine: ComputerSummary): String? =
    machine.creatorUserId?.trim()?.takeIf { it.isNotBlank() }
        ?: machine.creator?.id?.trim()?.takeIf { it.isNotBlank() }

/**
 * Resolves the owner name for an offline Computer (web `useAgentComputerOffline`): the server
 * member's display name (or handle) matching [membersComputerOwnerId], else the machine's
 * `creator` when the creator is that same person; null when departed or unknown.
 */
internal fun membersComputerOwnerName(
    machine: ComputerSummary,
    serverMembers: List<MemberProfile>,
): String? {
    val ownerId = membersComputerOwnerId(machine) ?: return null
    val ownerMember =
        serverMembers.firstOrNull { member ->
            member.kind == MemberKind.Human && member.id.value.trim() == ownerId
        }
    if (ownerMember != null) {
        return ownerMember.displayName.trim().ifBlank { ownerMember.handle.trim().removePrefix("@") }
            .takeIf { it.isNotBlank() }
    }
    val creator = machine.creator?.takeIf { it.id.trim() == ownerId } ?: return null
    return creator.displayName?.trim()?.takeIf { it.isNotBlank() }
        ?: creator.name.trim().takeIf { it.isNotBlank() }
}

/**
 * Web `agentComputerRestartCommand` (`features/agent/agentComputerCommands.ts`): the Computer
 * page's recovery restart command (`raft-computer restart /<slug>`), null without a server slug.
 */
internal fun membersAgentComputerRestartCommand(
    machine: ComputerSummary,
    commandContext: SettingsComputerCommandContext?,
): String? =
    raftComputerCommands(
        context = commandContext,
        platform = raftComputerCommandPlatform(machine.os),
        machineId = machine.id.takeUnless { machine.isComputer },
    )?.restart

/**
 * Resolves [MembersAgentComputerOfflineCardState] with the exact web gating of
 * `AgentDetailPanel` + `AgentComputerOfflineCard` + `useAgentComputerOffline`:
 * hidden for deleted, external, and bounded-public-projection agents, and whenever the agent has no
 * known offline Computer in [machines].
 */
internal fun resolveMembersAgentComputerOfflineCardState(
    machineId: String?,
    machines: List<ComputerSummary>,
    offlineObservedAtMs: Map<String, Long> = emptyMap(),
    serverMembers: List<MemberProfile> = emptyList(),
    commandContext: SettingsComputerCommandContext? = null,
    deleted: Boolean = false,
    isExternalAgent: Boolean = false,
    isBoundedPublicProjection: Boolean = false,
    offlineFromRefusalIds: Set<String> = emptySet(),
): MembersAgentComputerOfflineCardState? {
    if (deleted || isExternalAgent || isBoundedPublicProjection) return null
    val id = machineId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val machine = machines.firstOrNull { it.id == id } ?: return null
    if (machine.status != ComputerStatus.Offline) return null
    val fromRefusal = id in offlineFromRefusalIds
    val observedAt = if (fromRefusal) null else offlineObservedAtMs[id]
    val lastHeartbeat =
        if (fromRefusal || observedAt != null) {
            null
        } else {
            machine.lastHeartbeat?.trim()?.takeIf { it.isNotBlank() }
        }
    return MembersAgentComputerOfflineCardState(
        machineId = machine.id,
        computerName = machine.name.trim().ifBlank { machine.id },
        ownerName = membersComputerOwnerName(machine, serverMembers),
        sinceEpochMillis = observedAt,
        sinceIso = lastHeartbeat,
        restartCommand = membersAgentComputerRestartCommand(machine, commandContext),
    )
}

/**
 * Formats the card's "offline since <time>" timestamp through [RaftMessageTimeFormat] (web
 * `useTimeFormatter().formatMessageTime`). Null when the timestamp is unknown or unparseable.
 */
internal fun MembersAgentComputerOfflineCardState.formattedSince(
    timeContext: RaftTimeContext?,
    messageTime: RaftMessageTimeFormat,
): String? =
    (sinceEpochMillis?.let { messageTime.formatEpochMillis(it, timeContext) }
        ?: sinceIso?.let { messageTime.format(it, timeContext) })
        ?.trim()
        ?.takeIf { it.isNotBlank() }

/**
 * Builds an [AnnotatedString] by replacing [sentinel] inside a localized [template] with
 * [onSentinel], preserving the locale's exact word order across all 8 languages.
 */
internal fun buildMembersOfflineCardAnnotatedString(
    template: String,
    sentinel: String,
    onSentinel: AnnotatedString.Builder.() -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        val index = template.indexOf(sentinel)
        if (index < 0) {
            append(template)
            return@buildAnnotatedString
        }
        append(template.substring(0, index))
        onSentinel()
        append(template.substring(index + sentinel.length))
    }

private const val MembersOfflineCardComputerSentinel = "\uE000RAFT_COMPUTER\uE001"
private const val MembersOfflineCardOwnerSentinel = "\uE000RAFT_OWNER\uE001"
private const val MembersOfflineCardCopiedResetMillis = 2_000L

/**
 * Agent profile 'Computer offline' card (web `AgentComputerOfflineCard`, slock #13076):
 * offline status dot + "{computer} has been offline since {time}" (or "{computer} is offline")
 * with the computer name linking to its Computer page, followed by two numbered recovery steps
 * and the copyable restart command.
 */
@Composable
internal fun MembersAgentComputerOfflineCard(
    state: MembersAgentComputerOfflineCardState,
    timeContext: RaftTimeContext?,
    onOpenComputer: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onCopyCommand: ((String) -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val effectiveTimeContext = currentRaftEffectiveDateTimeContext(timeContext)
    val messageTime = rememberRaftMessageTimeFormat()
    val formattedSince = state.formattedSince(effectiveTimeContext, messageTime)
    val titleTemplate =
        if (formattedSince != null) {
            stringResource(
                Res.string.agent_computer_offline_card_title,
                MembersOfflineCardComputerSentinel,
                formattedSince,
            )
        } else {
            stringResource(
                Res.string.agent_computer_offline_card_title_no_time,
                MembersOfflineCardComputerSentinel,
            )
        }
    val titleText =
        buildMembersOfflineCardAnnotatedString(titleTemplate, MembersOfflineCardComputerSentinel) {
            if (onOpenComputer != null) {
                appendRaftTextLink(
                    text = state.computerName,
                    onClick = onOpenComputer,
                    variant = RaftTextLinkVariant.Strong,
                )
            } else {
                append(state.computerName)
            }
        }
    val powerStepText =
        if (state.ownerName != null) {
            val template =
                stringResource(
                    Res.string.agent_computer_offline_step_power_owner,
                    MembersOfflineCardOwnerSentinel,
                    state.computerName,
                )
            buildMembersOfflineCardAnnotatedString(template, MembersOfflineCardOwnerSentinel) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(state.ownerName)
                }
            }
        } else {
            AnnotatedString(stringResource(Res.string.agent_computer_offline_step_power, state.computerName))
        }
    val copyAction = onCopyCommand ?: rememberRaftCopyAction()
    val copyLabel = stringResource(Res.string.settings_computers_copy_command)
    val copiedLabel = stringResource(Res.string.common_copied)

    RaftCard(modifier = modifier.fillMaxWidth().testTag(MembersAgentComputerOfflineTestTags.Card)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftStatusDot(presence = RaftPresence.Offline)
                RaftText(
                    text = titleText,
                    modifier = Modifier.weight(1f).testTag(MembersAgentComputerOfflineTestTags.Title),
                    style = typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MembersAgentComputerOfflineStepRow(
                    number = 1,
                    modifier = Modifier.testTag(MembersAgentComputerOfflineTestTags.StepPower),
                ) {
                    RaftText(
                        text = powerStepText,
                        style = typography.subhead,
                        color = colors.foreground,
                    )
                }
                MembersAgentComputerOfflineStepRow(
                    number = 2,
                    modifier = Modifier.testTag(MembersAgentComputerOfflineTestTags.StepRun),
                ) {
                    RaftText(
                        text = stringResource(Res.string.agent_computer_offline_step_run),
                        style = typography.subhead,
                        color = colors.foreground,
                    )
                    val command = state.restartCommand?.takeIf { it.isNotBlank() }
                    if (command != null) {
                        Spacer(Modifier.height(6.dp))
                        MembersAgentComputerOfflineCommandBlock(
                            command = command,
                            copyLabel = copyLabel,
                            copiedLabel = copiedLabel,
                            onCopy = copyAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MembersAgentComputerOfflineStepRow(
    number: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftText(
            text = "$number.",
            style = typography.subhead,
            color = colors.foreground,
            modifier = Modifier.width(14.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
        Column(
            modifier = Modifier.weight(1f),
            content = content,
        )
    }
}

@Composable
private fun MembersAgentComputerOfflineCommandBlock(
    command: String,
    copyLabel: String,
    copiedLabel: String,
    onCopy: (String) -> Unit,
) {
    var copied by remember(command) { mutableStateOf(false) }
    LaunchedEffect(copied, command) {
        if (!copied) return@LaunchedEffect
        delay(MembersOfflineCardCopiedResetMillis)
        copied = false
    }
    RaftCodeBlock(
        text = command,
        modifier = Modifier.testTag(MembersAgentComputerOfflineTestTags.Command),
        action = {
            RaftIconButton(
                onClick = {
                    onCopy(command)
                    copied = true
                },
                tooltip = if (copied) copiedLabel else copyLabel,
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Md,
                modifier = Modifier.testTag(MembersAgentComputerOfflineTestTags.CopyCommand),
            ) {
                RaftComputersIcon(if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy)
            }
        },
    )
}
