package build.raft.app

import androidx.compose.ui.platform.testTag
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.app.data.AgentBlockedState
import build.raft.app.agents.AgentVoiceSection
import build.raft.app.assistant.AssistantOwnerPausedNotice
import build.raft.app.assistant.AssistantOwnerSection
import build.raft.app.badgeVariant
import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.thread.badgeVariant
import build.raft.app.thread.dispatch
import build.raft.app.thread.icon
import build.raft.app.thread.localizedLabel
import androidx.compose.runtime.setValue
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDescriptionListScope
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.DataResult
import build.raft.app.data.PROFILE_DESCRIPTION_INVALID_CODE
import build.raft.app.data.PROFILE_DESCRIPTION_MAX_LENGTH
import build.raft.app.data.profileDescriptionTooLong
import build.raft.app.iconName
import build.raft.app.localizedLabel
import build.raft.app.data.SharedAgentDetailActions
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailState
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.ScreenError
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.model.AgentAttention
import build.raft.app.model.AgentDeliveryHoldReason
import build.raft.app.model.AgentToolStuck
import build.raft.app.model.AgentStatus
import build.raft.app.data.AgentActivityDetailKindWakeCrashLoopBlocked
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.RaftTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import build.raft.app.components.rememberRaftDesignSystemEditor
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.layout
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftRuntimeChip
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.LocalRaftListRowIconSize
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftRiskTone
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftGlyph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import build.raft.app.model.isExternalAgentRuntime
import build.raft.designsystem.components.raftClickable

@Composable
private fun MembersAgentLifecycleAction.localizedBusyLabel(): String =
    when (this) {
        MembersAgentLifecycleAction.Start -> stringResource(Res.string.member_action_starting)
        MembersAgentLifecycleAction.Stop -> stringResource(Res.string.member_action_stopping)
        MembersAgentLifecycleAction.Delete -> stringResource(Res.string.member_action_deleting)
        MembersAgentLifecycleAction.Restart -> stringResource(Res.string.member_action_restarting)
        MembersAgentLifecycleAction.ResetSession,
        MembersAgentLifecycleAction.ResetFull -> stringResource(Res.string.member_action_resetting)
        MembersAgentLifecycleAction.UnassignMachine -> stringResource(Res.string.member_action_unassigning)
    }

/**
 * Title-cases a role name we do not recognise so it sits beside "Admin"/"Member" without
 * looking like a different kind of value. The parser lowercases every role
 * (`normalizedServerRoleOrNull`), so the server's original casing is already gone by here --
 * this restores presentation only, it does not recover the wire form.
 */
private fun String.membersRoleDisplayName(): String =
    replace('_', ' ')
        .replace('-', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun MembersAgentServerRoleDisplay.rememberKey(): String =
    when (this) {
        is MembersAgentServerRoleDisplay.Known -> role.wireValue
        is MembersAgentServerRoleDisplay.Unrecognized -> "unrecognized:$rawName"
        MembersAgentServerRoleDisplay.Hidden -> "hidden"
    }

@Composable
internal fun MembersAgentServerRoleChip(display: MembersAgentServerRoleDisplay) {
    // Hidden renders nothing: no membership row, or a deleted agent. Any value here would
    // assert a membership that does not exist (#261).
    if (display is MembersAgentServerRoleDisplay.Hidden) return
    val label =
        when (display) {
            is MembersAgentServerRoleDisplay.Known -> display.role.localizedLabel()
            // Shown as sent, only title-cased to match the recognised roles.
            is MembersAgentServerRoleDisplay.Unrecognized -> display.rawName.membersRoleDisplayName()
            MembersAgentServerRoleDisplay.Hidden -> ""
        }
    // Web AgentDetailPanel InfoRow: the role is the row's plain value (`<span>`), no chip.
    Text(
        text = label,
        style = RaftType.Body.copy(fontSize = MembersInfoValueFontSize, lineHeight = MembersInfoValueLineHeight),
        color = RaftColor.TextBlack,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun MembersAgentRoleHelpDialog(onDismiss: () -> Unit) {
    RaftUnifiedDialog(
        title = stringResource(Res.string.member_title_agent_roles).raftDisplayUppercase(),
        onDismiss = onDismiss,
        // Web RolePermissionHelpDialog: DialogCard max-w-md, rows space-y-3.
        maxWidth = 448.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MembersAgentRoleHelpRow(
                role = stringResource(Res.string.member_role_admin),
                summary = stringResource(Res.string.member_roles_help_admin_summary),
                details = stringResource(Res.string.member_roles_help_agent_admin_details)
            )
            MembersAgentRoleHelpRow(
                role = stringResource(Res.string.member_role_member),
                summary = stringResource(Res.string.member_roles_help_member_summary),
                details = stringResource(Res.string.member_roles_help_agent_member_details)
            )
        }
    }
}

@Composable
internal fun MembersAgentRoleHelpRow(
    role: String,
    summary: String,
    details: String
) {
    RaftCard(
        modifier = Modifier.fillMaxWidth(),
        title = role,
        description = summary
    ) {
        RaftText(
            text = details,
            style = RaftTheme.typography.callout,
            color = RaftTheme.colors.foregroundMuted
        )
    }
}

// Web AgentProfileInfo spacings (section paddings). Kept as separate constants on purpose so one
// can change without the others (task #257).
internal val MembersInfoStatusGapX = 6.dp // web gap-1.5: term to its icons, dot to the status word

internal val MembersInfoItemGap = 10.dp // between a section's blocks

internal val MembersProfileSectionPaddingY = 16.dp // web section py-4, separator to eyebrow

internal val MembersProfileEyebrowGap = 12.dp // web SectionEyebrow mb-3

// web hero `py-5` + description `py-3` = 32dp, less the hero's own 8dp and the profile column's 16dp
private val MembersProfileHeroToDescriptionGap = 8.dp

// web Recent activity `py-4` under the hero's `py-5` = 36dp, less the hero's own 8dp and the column's 16dp
private val MembersProfileRecentActivityTopGap = 12.dp

internal val MembersProfileEnvVarsGap = 12.dp // web runtime config `mt-3` above Environment Variables

/**
 * Label -> string resource pairing, kept out of the Composable so the six pairs are unit-testable
 * (MembersMachineStatusRowTest). Three of the six branches have no visual fixture until Batch 3,
 * so without this a swapped resource would compile, pass the logic tests and only show in pixels.
 */
internal fun MembersMachineRunLabel.resource(): StringResource =
    when (this) {
        is MembersMachineRunLabel.ComputerVersion -> Res.string.member_machine_run_computer_version
        is MembersMachineRunLabel.DaemonVersion -> Res.string.member_machine_run_daemon_version
        MembersMachineRunLabel.ComputerOnline -> Res.string.member_machine_run_computer_online
        MembersMachineRunLabel.DaemonOnline -> Res.string.member_machine_run_daemon_online
        MembersMachineRunLabel.ComputerOffline -> Res.string.member_machine_run_computer_offline
        MembersMachineRunLabel.DaemonOffline -> Res.string.member_machine_run_daemon_offline
    }

/**
 * Web `agentHeldByOfflineComputer` (#triage:#206): the agent's Computer is offline and it was not
 * stopped by a person, is not in error and is not paused by the server for its own reasons, so it
 * reads "Computer offline" (raft-design identity-and-presence). A held agent (its Computer revoked
 * or too old) and an external agent keep their own words. Read from the profile's Computer row;
 * the lists get it once the server's agent verdict carries it.
 */
internal fun membersAgentHeldByOfflineComputer(
    computerOffline: Boolean,
    indicator: RaftAgentStatusIndicatorState?,
    lifecycleStatus: String?,
    external: Boolean,
): Boolean {
    if (!computerOffline || external || indicator?.blocked != null) return false
    if (lifecycleStatus?.trim()?.lowercase() == "stopped") return false
    if (indicator?.status == AgentStatus.Error && indicator.attention == null) return false
    return indicator?.detailKind?.trim() !in MembersOwnOfflineDetailKinds
}

/** Web `OWN_OFFLINE_DETAIL_KINDS`: offline states the server paused, which keep their own words. */
private val MembersOwnOfflineDetailKinds =
    setOf(AgentActivityDetailKindWakeCrashLoopBlocked, AgentActivityDetailKindTerminalFailurePaused)

/** The agent profile's Computer version row (web #14585): "v{version}" while online, else "—". */
@Composable
internal fun membersAgentComputerVersionText(runLabel: MembersMachineRunLabel): String {
    val version =
        when (runLabel) {
            is MembersMachineRunLabel.ComputerVersion -> runLabel.version
            is MembersMachineRunLabel.DaemonVersion -> runLabel.version
            else -> null
        } ?: return "—"
    return stringResource(Res.string.settings_computers_rack_version, version.removePrefix("v"))
}

/** The single `%1$s` argument for the two version labels; null for the four argument-free ones. */
internal fun MembersMachineRunLabel.formatArg(): String? =
    when (this) {
        is MembersMachineRunLabel.ComputerVersion -> version
        is MembersMachineRunLabel.DaemonVersion -> version
        MembersMachineRunLabel.ComputerOnline,
        MembersMachineRunLabel.DaemonOnline,
        MembersMachineRunLabel.ComputerOffline,
        MembersMachineRunLabel.DaemonOffline -> null
    }

@Composable
internal fun MembersMachineRunLabel.localized(): String {
    val arg = formatArg()
    return if (arg != null) stringResource(resource(), arg) else stringResource(resource())
}

// Value text is 14sp/black; the label is 12sp/muted. The previous rows used 13sp
// values and 11sp labels with an 86dp label gutter.
private val MembersInfoValueFontSize = 14.sp

private val MembersInfoValueLineHeight = 20.sp

/**
 * One property of the agent profile (web InfoRow on a raft-ui DescriptionList): the label, its icon
 * actions 6dp after it, and the value. [copyText]: the raw value a long press on the value copies
 * (never the label).
 *
 * Every Info and Runtime config row goes through here, so a label change cannot land on some
 * rows and miss one.
 */
@Composable
internal fun RaftDescriptionListScope.MembersAgentInfoField(
    label: String,
    copyText: String? = null,
    labelActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Item(
        term = label,
        termAccessory =
            labelActions?.let { actions ->
                {
                    Row(
                        // The list pulls a ghost icon button 5dp towards the label; these have no padding to pull.
                        modifier = Modifier.padding(start = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(MembersInfoStatusGapX),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions
                    )
                }
            }
    ) {
        Column(Modifier.raftCopyable(copyText), content = content)
    }
}

/**
 * A profile body section (web AgentProfileInfo `<Separator/>` + `px-5 py-4`): the hairline, 16dp,
 * the eyebrow row (with its optional pencil) and 12dp (`mb-3`) above its content. The 16dp under
 * the last block is the profile column's own spacing.
 */
@Composable
internal fun MembersProfileSection(
    title: String,
    modifier: Modifier = Modifier,
    titleAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MembersProfileFullBleedDivider()
        Row(
            modifier = Modifier.padding(top = MembersProfileSectionPaddingY, bottom = MembersProfileEyebrowGap),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MembersDetailSectionTitle(title)
            titleAction?.invoke()
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MembersInfoItemGap),
            content = content
        )
    }
}

/** Web INFO_ICON_BUTTON_CLASS with a 14px Lucide icon in `foreground-muted` (the role help "?"). */
@Composable
private fun MembersProfileIconAction(
    iconName: RaftLucideIconName,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .raftClickable(onClick = onClick, shape = RoundedCornerShape(RaftAppRadius.Chip))
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            iconName,
            modifier = Modifier.size(14.dp),
            color = RaftColor.TextMutedGrayArgb
        )
    }
}

/**
 * The hero status chip's soft wash, as web AgentDetailPanel `PRESENCE_CHIP_VARIANT`
 * (botiverse/slock d1285e24): online lime (`success`), thinking/working yellow (`busy`, raft-ui
 * 0.39 `presence-busy-soft`, yellow in every theme), error orange (`warning`), offline stone
 * (`muted`). Idle is web's `online` activity and
 * Disconnected its `offline`. Null (not yet observed) has no web activity; it keeps the
 * neutral `default` wash so the chip does not claim Offline (task #256).
 */
private fun AgentStatus?.membersStatusBadgeVariant(): RaftBadgeVariant =
    when (this) {
        AgentStatus.Working, AgentStatus.Thinking -> RaftBadgeVariant.Busy
        AgentStatus.Online, AgentStatus.Idle -> RaftBadgeVariant.Success
        AgentStatus.Error -> RaftBadgeVariant.Warning
        AgentStatus.Offline, AgentStatus.Disconnected -> RaftBadgeVariant.Muted
        null -> RaftBadgeVariant.Default
    }

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MembersAgentProfileHeader(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    canEdit: Boolean,
    agentStatusObservedState: RaftAgentStatusIndicatorState?,
    agentStatusIndicatorState: RaftAgentStatusIndicatorState?,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    onChangeAvatar: () -> Unit,
    /** The agent is stuck on one tool call ([membersAgentToolStuck]); null when not. */
    toolStuck: AgentToolStuck? = null,
    onStopTurn: (() -> Unit)? = null,
    stopTurnInProgress: Boolean = false,
    /** Web AgentHeaderName pencil: opens the display-name editor; null when the viewer may not edit. */
    onEditDisplayName: (() -> Unit)? = null,
    /**
     * Web AgentStatusBadge `showDetail` (= canViewPrivateAgentSurfaces): the status chip carries
     * the agent's current activity text ("Capturing …"), not just the coarse "Working".
     */
    showActivityDetail: Boolean = false,
    /** The failed start the status shows ([membersAgentStatusSpawnFailure]); null when none. */
    spawnFailure: AgentStatusSpawnFailure? = null,
    /** Change model beside a start failure another model gets out of; null when the viewer may not. */
    onChangeModel: (() -> Unit)? = null,
    timeContext: RaftTimeContext? = null,
    /**
     * Web AgentStatusBadge Retry (botiverse/slock #13345): the Actions section's Restart, for
     * whoever may restart the agent now (its Computer online); null otherwise. Shown only while
     * the agent cannot run until someone restarts it ([agentNeedsManualRestart]).
     */
    onRetry: (() -> Unit)? = null,
    retryInProgress: Boolean = false,
    /**
     * Opens the agent's Computer: the link beside "Computer update required" (raft-design
     * identity-and-presence, the step links to the computer); null when there is none to open.
     */
    onOpenComputer: (() -> Unit)? = null,
    /** Opens an App bot's owning App installed detail (`?installed_app=`, slock #14286); null when unavailable. */
    onOpenManagedApp: (() -> Unit)? = null,
    /**
     * Web AgentDetailPanel: the status sits under the tags unless the profile shows the Recent
     * activity card, whose first line it is then ([MembersAgentProfileStatus]).
     */
    showStatus: Boolean = true,
    /** Web `AgentComputerRevokedNote` (#16468): shown under the revoked status notice when present. */
    computerRevokedNote: MembersAgentComputerRevokedNoteState? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = RaftAppSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)
    ) {
        // raft-ui agent page hero: the 99dp sticker avatar with the hard shadow, the name
        // (PanelTitle: `text-headline` bold), the handle (`font-mono text-mono-meta
        // text-foreground-hint`), the `Agent` tag and the runtime chip, then the status chip.
        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        val runtimeId = summary?.runtime?.takeIf { it.isNotBlank() }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = RaftAppSpacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
            verticalAlignment = Alignment.Top
        ) {
            // Web `agent-change-avatar`: the hero sticker is the change-avatar button. An App's
            // bot wears the App's logo, changed only in the App's config, so it has none.
            MembersAgentAvatarEditTrigger(
                enabled = canEdit && member.managedByAppInstallId.isNullOrBlank(),
                onClick = onChangeAvatar
            ) {
                MembersRaftAvatar(
                    member = member,
                    size = RaftAvatarSize.Xl,
                    memberAvatarResolver = memberAvatarResolver
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(top = RaftAppSpacing.Xs)
            ) {
                // Web PanelTitle (cindyz 10-10): the name reads the same here as in the head.
                // Web AgentHeaderName: the display name is edited from the pencil beside it
                // (`flex items-center gap-2`), not from a section of its own.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RaftText(
                        text = member.displayName,
                        style = typography.headline,
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .semantics { heading() }
                            .raftCopyable(member.displayName)
                    )
                    if (onEditDisplayName != null) {
                        MembersProfilePencilButton(
                            onClick = onEditDisplayName,
                            contentDescription = stringResource(Res.string.member_action_edit_display_name)
                        )
                    }
                }
                RaftText(
                    text = member.detailHandle,
                    style = typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.raftCopyable(member.detailHandle)
                )
                // Web `mt-2 flex flex-wrap items-center gap-1.5`: a long runtime name wraps to
                // its own line instead of being cut.
                FlowRow(
                    modifier = Modifier.padding(top = RaftAppSpacing.Sm),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    val appBot = member.appBotIdentityOrNull()
                    if (appBot != null) {
                        // botiverse/slock #11273 / #14286 profile hero: an App Bot is a Bot from its App,
                        // not an "Agent", and runs on no runtime; "From <logo> <App>" links to App details.
                        RaftAppBotBadge(identity = appBot, botName = appBot.appName)
                        appBot.appName?.let { appName ->
                            RaftAppBotFromAppLine(
                                appName = appName,
                                logoUrl = appBot.appLogoUrl ?: summary?.managedByAppLogoUrl,
                                onOpenManagedApp = onOpenManagedApp,
                            )
                        }
                    } else {
                        MemberKindBadge(member.kind)
                    }
                    if (runtimeId != null && appBot == null) {
                        // Web `agent-profile-runtime-chip`: the DS runtime chip led by Lucide `square-terminal`.
                        val runtimeLabel = runtimeLabelWithStatus(runtimeId, member.serverId?.value)
                        RaftRuntimeChip(
                            text = runtimeLabel,
                            modifier = Modifier.raftCopyable(runtimeLabel),
                            leadingGlyph = RaftGlyph.SquareTerminal
                        )
                    }
                }
                if (showStatus) {
                    MembersAgentProfileStatus(
                        member = member,
                        summary = summary,
                        agentStatusObservedState = agentStatusObservedState,
                        agentStatusIndicatorState = agentStatusIndicatorState,
                        toolStuck = toolStuck,
                        onStopTurn = onStopTurn,
                        stopTurnInProgress = stopTurnInProgress,
                        showActivityDetail = showActivityDetail,
                        spawnFailure = spawnFailure,
                        onChangeModel = onChangeModel,
                        timeContext = timeContext,
                        onRetry = onRetry,
                        retryInProgress = retryInProgress,
                        onOpenComputer = onOpenComputer,
                        computerRevokedNote = computerRevokedNote,
                    )
                }
            }
        }
    }
}

/**
 * The agent's status as the profile shows it (web AgentStatusBadge): the status chip, with Retry
 * or the Computer link beside it, then a held agent's sentence, a failed start and a tool call it
 * is stuck on, each on its own lines. It sits in the hero, or first in the Recent activity card
 * when the profile shows one ([MembersAgentRecentActivity]). Each part is [itemTop] below the one
 * above it.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MembersAgentProfileStatus(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    agentStatusObservedState: RaftAgentStatusIndicatorState?,
    agentStatusIndicatorState: RaftAgentStatusIndicatorState?,
    toolStuck: AgentToolStuck? = null,
    onStopTurn: (() -> Unit)? = null,
    stopTurnInProgress: Boolean = false,
    showActivityDetail: Boolean = false,
    spawnFailure: AgentStatusSpawnFailure? = null,
    onChangeModel: (() -> Unit)? = null,
    timeContext: RaftTimeContext? = null,
    onRetry: (() -> Unit)? = null,
    retryInProgress: Boolean = false,
    onOpenComputer: (() -> Unit)? = null,
    itemTop: Dp = 6.dp,
    computerRevokedNote: MembersAgentComputerRevokedNoteState? = null,
) {
    val appBot = !member.managedByAppInstallId.isNullOrBlank()
    // Web AgentDisplayState.blocked (botiverse/slock #13575): held agents say why, not their activity.
    val blocked = (agentStatusIndicatorState ?: agentStatusObservedState)?.blocked?.takeIf { !appBot }
    val external = member.isExternal || summary?.isExternal == true || agentStatusIndicatorState?.external == true
    // Web AgentStatusBadge (#proj-raft-computer:#68): server-composed readiness (already trimmed per viewer)
    // takes precedence over ordinary idle/online activity when the agent is held (e.g. quota_exhausted / rate_limited).
    val holdingReadiness =
        effectiveHoldingReadiness(
            readiness = (agentStatusIndicatorState ?: agentStatusObservedState)?.readiness ?: member.agentReadiness,
            blocked = blocked,
            external = external,
            paused = !member.assistantPausedAt.isNullOrBlank(),
            appBot = appBot,
        )
    val readinessCopy =
        rememberAgentReadinessDisplayCopy(
            readiness = holdingReadiness,
            fallbackRuntimeId = summary?.runtime ?: agentStatusIndicatorState?.runtime ?: member.roleDescription,
            timeContext = timeContext,
        )
    if (readinessCopy != null) {
        val openUrl = LocalMembersRuntimeAccountUsageAccess.current?.auth?.openUrl
        val uriHandler = LocalUriHandler.current
        FlowRow(
            modifier = Modifier.padding(top = itemTop),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            RaftBadge(
                text = readinessCopy.title,
                appearance = RaftBadgeAppearance.Soft,
                variant = readinessCopy.light.badgeVariant(),
                modifier =
                    Modifier
                        .testTag(AgentReadinessTestTags.ProfileChip)
                        .semantics { contentDescription = readinessCopy.fullDetail },
                icon = { RaftStatusIndicator(color = readinessCopy.light.dotColor()) },
            )
            val portalUrl = readinessCopy.verificationPortalUrl
            if (showActivityDetail && portalUrl != null) {
                AgentReadinessVerificationLink(
                    onOpenUrl = {
                        if (openUrl != null) openUrl(portalUrl) else runCatching { uriHandler.openUri(portalUrl) }
                    },
                )
            }
            if (showActivityDetail && readinessCopy.offersChangeModel && onChangeModel != null) {
                RaftTextLink(
                    text = stringResource(Res.string.agent_status_change_model),
                    onClick = onChangeModel,
                    variant = RaftTextLinkVariant.Strong,
                    style = RaftTheme.typography.footnote,
                    modifier = Modifier.testTag(AgentSpawnFailureStatusTestTags.ChangeModel),
                )
            }
            if (readinessCopy.offersRetry && onRetry != null) {
                AgentStatusRetryLink(inProgress = retryInProgress, onRetry = onRetry)
            }
        }
        AgentReadinessStatusNotice(
            text = readinessCopy.fullDetail,
            modifier = Modifier.padding(top = itemTop),
        )
        return
    }
    // Web AgentStatusBadge `heldView` (botiverse/slock #16333): the daemon holds the agent's
    // deliveries on every frame, whatever the activity. Every member sees why (detailed badge:
    // "Can't respond: <reason>"; compact badge: "Can't respond" with the reason in semantics).
    val deliveryHoldView =
        rememberMembersAgentDeliveryHoldView(
            indicator = agentStatusIndicatorState,
            summary = summary,
            external = external,
            blocked = blocked,
            timeContext = timeContext,
            appBot = appBot,
        )
    val deliveryHold = deliveryHoldView?.hold
    // Web AgentStatusBadge `credentialReason` (botiverse/slock #13276, #16333): an unusable
    // sign-in, plan or balance reads as the error light; also falls back to a credential-invalid
    // deliveryHold's detail so managers still see Retry.
    val credentialReason =
        if (appBot) {
            null
        } else {
            membersAgentCredentialInvalidReason(
                indicator = agentStatusIndicatorState,
                summary = summary,
                external = external,
                blocked = blocked,
            ) ?: deliveryHold?.takeIf { it.reason == AgentDeliveryHoldReason.CredentialInvalid }?.credentialDetail
        }
    // Web `runtimeSignInNeededOf` (slock #15282): the runtime is not signed in on the Computer, so
    // the agent cannot start. The error light and "Sign-in needed for {runtime}"; the chip opens
    // the Computer, where the runtime is signed in.
    val runtimeRetired =
        if (appBot) {
            null
        } else {
            rememberAgentRuntimeRetiredState(
                state = agentStatusIndicatorState ?: agentStatusObservedState,
                blockedOverride = blocked,
                fallbackRuntime = summary?.runtime ?: agentStatusIndicatorState?.runtime ?: member.roleDescription,
                fallbackMachineId = summary?.machineId ?: agentStatusIndicatorState?.machineId,
            )
        }
    val rawRuntimeSignInNeeded =
        !appBot &&
            runtimeRetired == null &&
            credentialReason == null &&
            membersAgentRuntimeSignInNeeded(
                indicator = agentStatusIndicatorState,
                summary = summary,
                external = external,
                blocked = blocked,
                liveSpawnFailureReason = spawnFailure?.spawnFailure?.reason ?: agentStatusIndicatorState?.spawnFailureReason,
                paused = !member.assistantPausedAt.isNullOrBlank(),
            )
    val runtimeSignInResolution =
        rememberAgentRuntimeSignInResolution(
            state = agentStatusIndicatorState,
            neededOverride = rawRuntimeSignInNeeded,
            fallbackRuntime = summary?.runtime ?: agentStatusIndicatorState?.runtime,
            fallbackName = member.displayName.takeIf { it.isNotBlank() } ?: summary?.displayName ?: summary?.name,
            fallbackMachineId = summary?.machineId ?: agentStatusIndicatorState?.machineId,
            fallbackMachineName = summary?.machineName ?: agentStatusIndicatorState?.machineName,
        )
    val runtimeSignIn = runtimeSignInResolution.state
    val runtimeSignInNeeded = runtimeSignIn != null
    val effectiveIndicator =
        agentStatusIndicatorState?.withoutClearedLoginRequiredAttention(runtimeSignInResolution.clearedByLiveLogin)
    val effectiveObserved =
        agentStatusObservedState?.withoutClearedLoginRequiredAttention(runtimeSignInResolution.clearedByLiveLogin)
    // Web AgentStatusBadge (#13460, #17133): a failed start's typed copy stands in for the
    // daemon's detail, falling back to `attention.spawnFailure` before live activity loads. It
    // gets its own lines under the chip (as the stuck-tool notice does), so the chip keeps the
    // short status words and never cuts the copy.
    val effectiveSpawnFailure =
        spawnFailure?.takeUnless {
            runtimeSignInResolution.clearedByLiveLogin && it.spawnFailure.reason == RuntimeLoginRequiredSpawnFailure
        }
            ?: if (effectiveIndicator?.attention is AgentAttention.StartFailure) {
                membersAgentStatusSpawnFailure(
                    indicator = effectiveIndicator,
                    summaryDetailKind = summary?.activityDetailKind,
                    summaryActivityDetail = summary?.activityDetail,
                    summarySpawnFailure = null,
                )
            } else {
                null
            }
    val spawnFailureStatus =
        effectiveSpawnFailure?.takeIf {
            showActivityDetail &&
                !appBot &&
                blocked == null &&
                agentSpawnFailureCopy(it.spawnFailure, it.daemonDetail, nowMs = Long.MIN_VALUE) != null
        }
    val showRetry =
        onRetry != null &&
            !appBot &&
            agentNeedsManualRestart(
                status = effectiveIndicator?.status,
                detailKind =
                    membersAgentStatusDetailKind(
                        indicator = effectiveIndicator,
                        summaryStatus = summary?.status,
                        summaryActivity = summary?.activity,
                        summaryDetailKind = summary?.activityDetailKind,
                    ),
                spawnFailure = effectiveSpawnFailure?.spawnFailure,
                runtimeErrorClass = summary?.lastRuntimeErrorClass,
                external = external,
                blocked = blocked,
                likelyOnline = effectiveIndicator?.likelyOnline == true,
                credentialReason = credentialReason,
            )
    // Web: held or not, an invalid credential or delivery hold cannot respond: the error light.
    val baseErrorLight = credentialReason != null || runtimeSignInNeeded || deliveryHold != null
    // Web AgentStatusBadge `workersBusy` (botiverse/slock #16819): online with running Workers
    // shows busy yellow and "Working through a Worker / N Workers" (or presence-only for peers).
    val workersBusy =
        if (appBot || baseErrorLight || blocked != null || effectiveIndicator?.attention != null) {
            null
        } else {
            membersAgentRunningWorkersDisplay(
                indicator = effectiveIndicator,
                summary = summary,
                external = external,
                blocked = blocked,
                paused = !member.assistantPausedAt.isNullOrBlank(),
                nowMs = timeContext?.nowEpochMillis ?: build.raft.app.data.defaultNowMillis(),
                appBot = appBot,
                presenceOnly = member.inViewerServer == false,
                hasResolvedDeliveryHold = true,
                resolvedDeliveryHold = deliveryHold,
            )
        }
    // Web AgentStatusBadge `startFailureAttention` (botiverse/slock #17133): a non-retryable
    // stored failed start shows its short title in the chip and the full sentence below.
    val startFailureAttention =
        if (
            !appBot &&
            runtimeRetired == null &&
            runtimeSignIn == null &&
            deliveryHoldView == null &&
            credentialReason == null &&
            blocked == null &&
            effectiveIndicator?.likelyOnline != true &&
            workersBusy == null &&
            effectiveIndicator?.attention is AgentAttention.StartFailure
        ) {
            rememberAgentAttentionParts(effectiveIndicator, timeContext = timeContext)
        } else {
            null
        }
    val errorLight = baseErrorLight || startFailureAttention != null
    val rawStatusLabel = membersHeroAgentStatusLabel(
        indicatorState = effectiveIndicator,
        summaryStatus = summary?.status,
        summaryActivity = summary?.activity,
        summaryActivityDetail = summary?.activityDetail,
        showActivityDetail = showActivityDetail && spawnFailureStatus == null
    )
    // SSOT audit: the badge words localize like every other live-status caption. A held agent's
    // sentence does not fit the one-line chip: the chip reads Error (its light) and the sentence
    // follows under it ([AgentBlockedStatusNotice]). "Computer update required" is already the
    // short form (raft-design identity-and-presence): the chip says it, with no sentence under it.
    val statusLabel =
        if (runtimeRetired != null) {
            runtimeRetired.fullText
        } else if (blocked == AgentBlockedState.ComputerUpdateRequired) {
            AgentBlockedState.ComputerUpdateRequired.localizedStatusLabel()
        } else if (blocked != null) {
            stringResource(Res.string.member_row_status_error)
        } else if (runtimeSignIn != null) {
            runtimeSignIn.fullText
        } else if (deliveryHoldView != null) {
            if (showActivityDetail) {
                deliveryHoldView.text
            } else {
                deliveryHoldView.shortText
            }
        } else if (credentialReason != null) {
            if (showActivityDetail) {
                agentCredentialInvalidText(
                    reason = credentialReason,
                    runtime = summary?.runtime ?: effectiveIndicator?.runtime,
                    model = summary?.model,
                )
            } else {
                stringResource(Res.string.member_row_status_error)
            }
        } else if (workersBusy != null) {
            workersBusy.localizedText()
        } else if (startFailureAttention != null) {
            startFailureAttention.short
        } else {
            effectiveIndicator
                ?.copy(label = rawStatusLabel, runningWorkers = null)
                ?.localizedMemberStatusLabel()
                ?: rawStatusLabel
        }

    // A null resolution is Unknown/not-ready, and PresenceOffline is the
    // Offline/Disconnected colour — falling back to it makes the dot claim
    // offline while the label beside it says Unknown, the disagreement
    // RaftAgentStatusIndicator's own contract forbids. Render no light until
    // something authoritative arrives (task #253/#256).
    // An App Bot renders no presence (web: always-offline on a signal that does
    // not apply to it).
    val chipStatus = if (errorLight) AgentStatus.Error else effectiveObserved?.status
    val chipVariant =
        when {
            errorLight -> AgentStatus.Error.membersStatusBadgeVariant()
            workersBusy != null -> RaftBadgeVariant.Busy
            else -> chipStatus.membersStatusBadgeVariant()
        }
    val chipColor =
        when {
            errorLight -> RaftColor.AccentOrange
            workersBusy != null -> RaftColor.BrandYellow
            else -> effectiveObserved?.color
        }
    val statusChip: @Composable (Modifier) -> Unit = { chipModifier ->
        RaftBadge(
            text = statusLabel,
            appearance = RaftBadgeAppearance.Soft,
            variant = chipVariant,
            modifier = chipModifier.testTag(AgentReadinessTestTags.ProfileChip),
            icon = if (chipColor != null) {
                {
                    // raft-ui Status in a Badge (`lead-dot`): the 10dp light, never an animation.
                    RaftStatusIndicator(color = chipColor)
                }
            } else {
                null
            }
        )
    }
    val openComputer = onOpenComputer?.takeIf { blocked == AgentBlockedState.ComputerUpdateRequired || runtimeSignInNeeded }
    val chipDetailSemantics =
        when {
            runtimeRetired != null -> Modifier.semantics { contentDescription = runtimeRetired.detail }
            runtimeSignIn != null -> Modifier.semantics { contentDescription = runtimeSignIn.detail }
            deliveryHoldView != null -> Modifier.semantics { contentDescription = deliveryHoldView.text }
            startFailureAttention != null ->
                Modifier.semantics { contentDescription = startFailureAttention.detail.ifBlank { startFailureAttention.short } }
            else -> Modifier
        }
    // Nothing known about its presence (e.g. a joint channel's agent of another server
    // whose profile read failed): no chip, never an empty "—" one.
    if (!appBot && statusLabel != MembersUnknownAgentStatusLabel) {
        if (openComputer != null) {
            // The step is on the Computer: the chip itself opens it (web #14585
            // AgentStatusBadge `agent-status-computer-too-old`); when the viewer can sign in from
            // here (slock #15282), offer the quiet Sign in action beside it.
            val chipModifier =
                chipDetailSemantics.raftClickable(
                    onClick = openComputer,
                    onClickLabel = stringResource(Res.string.agent_status_open_computer),
                    shape = RoundedCornerShape(RaftAppRadius.Control)
                )
            if (runtimeSignIn?.offered == true) {
                Row(
                    modifier = Modifier.padding(top = itemTop),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    statusChip(Modifier.weight(1f, fill = false).then(chipModifier))
                    AgentRuntimeSignInAction(signIn = runtimeSignIn, testTag = AgentRuntimeSignInTestTags.ProfileAction)
                }
            } else {
                statusChip(Modifier.padding(top = itemTop).then(chipModifier))
            }
        } else if (runtimeSignIn?.offered == true) {
            Row(
                modifier = Modifier.padding(top = itemTop),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                statusChip(Modifier.weight(1f, fill = false).then(chipDetailSemantics))
                AgentRuntimeSignInAction(signIn = runtimeSignIn, testTag = AgentRuntimeSignInTestTags.ProfileAction)
            }
        } else if (showRetry && onRetry != null) {
            // Web `flex items-center gap-1.5`: the quiet Retry right after the chip.
            Row(
                modifier = Modifier.padding(top = itemTop),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                statusChip(Modifier.weight(1f, fill = false).then(chipDetailSemantics))
                AgentStatusRetryLink(inProgress = retryInProgress, onRetry = onRetry)
            }
        } else {
            statusChip(Modifier.padding(top = itemTop).then(chipDetailSemantics))
        }
    }
    if (runtimeRetired != null && showActivityDetail) {
        AgentRuntimeRetiredNotice(
            retired = runtimeRetired,
            canChangeRuntime = onChangeModel != null,
            onChangeRuntime = onChangeModel,
            onOpenComputer = onOpenComputer,
            modifier = Modifier.padding(top = itemTop),
        )
    } else if (blocked != null && blocked != AgentBlockedState.ComputerUpdateRequired && blocked != AgentBlockedState.RuntimeRetired) {
        AgentBlockedStatusNotice(blocked = blocked, modifier = Modifier.padding(top = itemTop))
        if (blocked == AgentBlockedState.ComputerAccessRevoked && computerRevokedNote != null) {
            MembersAgentComputerRevokedNote(
                state = computerRevokedNote,
                timeContext = timeContext,
                modifier = Modifier.padding(top = itemTop),
            )
        }
    }
    // Web #10446, #17133: failed-start sentence (or Change model action) and stuck-tool notice sit
    // on their own lines under the status chip so the chip never truncates them.
    if (spawnFailureStatus != null) {
        AgentSpawnFailureStatusNotice(
            status = spawnFailureStatus,
            timeContext = timeContext,
            onChangeModel = onChangeModel,
            modifier = Modifier.padding(top = itemTop)
        )
    } else if (showActivityDetail && startFailureAttention != null && startFailureAttention.detail.isNotBlank()) {
        AgentStartFailureDetailNotice(
            text = startFailureAttention.detail,
            modifier = Modifier.padding(top = itemTop),
        )
    }
    if (toolStuck != null && member.managedByAppInstallId.isNullOrBlank()) {
        AgentToolStuckNotice(
            toolStuck = toolStuck,
            onStopTurn = onStopTurn,
            stopTurnEnabled = !stopTurnInProgress,
            modifier = Modifier.padding(top = itemTop)
        )
    }
    // Web AgentStatusBadge (#17127): while a warm session is still live on a removed model,
    // readiness is advisory so the chip stays online/working with an owner-only note + Change model.
    val modelAdvisory =
        if (showActivityDetail && !appBot && runtimeRetired == null && blocked == null) {
            rememberAgentModelUnavailableAdvisoryCopy(
                readiness = (effectiveIndicator ?: effectiveObserved)?.readiness ?: member.agentReadiness,
                fallbackRuntimeId = summary?.runtime ?: effectiveIndicator?.runtime ?: member.roleDescription,
                timeContext = timeContext,
            )
        } else {
            null
        }
    if (modelAdvisory != null) {
        AgentReadinessAdvisoryNotice(
            text = modelAdvisory.detail,
            onChangeModel = onChangeModel,
            modifier = Modifier.padding(top = itemTop),
        )
    }
}

/**
 * Parameter holders for [MembersAgentProfileTab]. They are deliberate and must stay.
 *
 * Why: the tab used to take 36 parameters, past the Compose compiler's 31-parameters-per-int
 * packing boundary for the generated `$changed` / `$default` masks (Composer + 4 `$changed`
 * + 2 `$default` ints), and its body is ~1070 lines, which compiles to one method with
 * ~270 registers and ~5.6k dex instructions. On release 1.11.0 Android alpha builds this
 * function crashed on cold entry with a ClassCastException inside [rememberCoroutineScope]
 * (a Boolean came back from the slot where the scope canceller was expected). Two
 * diagnostic builds that only added an annotation or one read at the function head were
 * rejected outright by the ART verifier on this function: the dex showed the live
 * `$default1` mask register being reused as a reference temporary inside the default
 * lambda `remember {}` blocks. The generated code for this signature sits on a register
 * allocation boundary; every probe that changed this function's shape changed the outcome,
 * while probes on the callers or the lazy list did not.
 *
 * Bundling the 6 runtime-model inputs and the 7 lifecycle/diagnostic inputs brings the
 * signature to 25 parameters (1 `$changed` int fewer, `$default1` gone) and changes the
 * register allocation of the whole method. This is a mitigation with two effects at once
 * (a different signature/register shape and holder-based comparison); it does not establish
 * a safe parameter threshold (26–31 were not tested). The holders are unpacked into same-named locals
 * at the top of the function so the body is unchanged. Cost: the holders are plain
 * classes holding Map/List/lambda members, so Compose treats them as unstable, and the
 * caller builds new instances on each recomposition; the tab therefore no longer skips
 * (the same cost as `@NonSkippableComposable`). That is acceptable for one screen of
 * content. Do not add `@Stable` / `@Immutable` to the holders later as an "optimisation":
 * it changes the comparison semantics. Splitting the function into smaller composables is the
 * long-term shape and is tracked separately (task #1130), together with the other
 * >31-parameter composables in this file.
 *
 * Verification is device-only (compiler layer): alpha build 1110013 (this exact code on
 * 8a8ca1b11e) on cloud device vjdp, Members → agent detail, 6 cold starts, 0 crashes,
 * 0 VerifyError, pid unchanged; the unbundled base crashed 6/6 on the same path in earlier
 * rounds (1110001–1110005, 1110007), not all on the same device. The other >31-parameter
 * composables in this file were not exercised by this verification.
 */
internal class MembersAgentRuntimeModelInputs(
    val agentMachineRuntimes: List<String> = emptyList(),
    val agentRuntimeModelsByRuntime: Map<String, SharedCreateAgentRuntimeModelCatalog> = emptyMap(),
    val onAgentRuntimeModelsRequest: (runtime: String, force: Boolean) -> Unit = { _, _ -> }
)

internal class MembersAgentLifecycleInputs(
    val lifecycleInFlight: String? = null,
    val diagnosticActionState: MembersAgentDiagnosticActionState? = null,
    val onRunLifecycleAction: (MembersAgentLifecycleAction) -> Unit = {},
    val onOpenRestartReset: () -> Unit = {},
    val onCopyDiagnostics: () -> Unit = {},
    val onCopyText: (String) -> Unit = {},
    val onReportIssue: () -> Unit = {},
    val onOpenComputer: (() -> Unit)? = null,
    /** Opens an App bot's owning App installed detail (`?installed_app=`, slock #14286); null when unavailable. */
    val onOpenManagedApp: (() -> Unit)? = null,
    /** Stop Current Turn on the "stuck on a tool call" notice; null when the viewer may not. */
    val onStopTurn: (() -> Unit)? = null,
    val stopTurnInProgress: Boolean = false,
    /** Web AgentStatusBadge `showDetail` (canViewPrivateAgentSurfaces): the hero chip shows the activity text. */
    val showActivityDetail: Boolean = false,
    /**
     * Web AgentDetailPanel `openModelSettings` (botiverse/slock #13435, #13460): opens the runtime
     * editor, for whoever may change the agent's model; null otherwise.
     */
    val onOpenModelSettings: (() -> Unit)? = null,
    /** A Change model / View model settings tap waiting for the runtime editor to open it. */
    val runtimeEditorOpenRequested: Boolean = false,
    val onRuntimeEditorOpenRequestHandled: () -> Unit = {},
    /** The viewer's clock, for a paused start's next try. */
    val timeContext: RaftTimeContext? = null,
    /**
     * Web AgentStatusBadge Retry (botiverse/slock #13345): restart now, for whoever may restart
     * the agent from Actions while its Computer is online (web
     * `restartResetOffered(canControlAgentRuntime, computerOffline)`); null otherwise.
     */
    val onRetryRestart: (() -> Unit)? = null,
    val retryRestartInProgress: Boolean = false,
    /**
     * Web AgentDetailPanel `AgentRecentActivity` under the hero, with the status as its first line;
     * null where the profile shows none (the status stays in the hero).
     */
    val recentActivity: MembersAgentRecentActivityInputs? = null,
    /**
     * Web AgentDetailPanel `AgentComputerOfflineCard` (slock #13076): shown directly under the
     * profile hero while the agent's Computer is offline; null otherwise.
     */
    val computerOfflineCard: MembersAgentComputerOfflineCardState? = null,
    /** Optional copy override for [computerOfflineCard]'s restart command; null uses `rememberRaftCopyAction`. */
    val onCopyComputerCommand: ((String) -> Unit)? = null,
    /**
     * Web `AgentComputerRevokedNote` (slock #16468): shown under the agent profile's "Computer
     * access revoked" status when the machine list carries its revocation cause; null otherwise.
     */
    val computerRevokedNote: MembersAgentComputerRevokedNoteState? = null,
)

/**
 * Inputs shared by the editable sections of [MembersAgentProfileTab] (task #1130 part 2).
 * Plain unstable class like the other Members holders; do not add `@Stable`/`@Immutable`.
 */
internal class MembersAgentProfileEditInputs(
    val canEdit: Boolean,
    /** Permission alone (no load-state terms): an open editor closes only when this is lost. */
    val canManage: Boolean = canEdit,
    val agentProfileEditAuthority: MembersAgentProfileEditAuthority?,
    val agentDetailActions: SharedAgentDetailActions?,
    val onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    val onApplyAgentProfileResult: (MemberProfile) -> Unit,
    val onUnavailableAction: (String) -> Unit,
    val errorStrings: ErrorPresentationStrings,
    /** Web AgentProfileInfo `openRuntimeConfigRequested`: open the runtime editor once it can. */
    val runtimeEditorOpenRequested: Boolean = false,
    val onRuntimeEditorOpenRequestHandled: () -> Unit = {},
    /** An error toast with the failed request's trace ID (`bridge.errorToast`); null: a plain toast. */
    val onErrorToast: ((String, ScreenError) -> Unit)? = null
)

/**
 * PROFILE part of the agent detail tab: the hero (its name pencil opens the display-name editor
 * dialog, web AgentHeaderName + AgentProfileEditDialog) and the description, each with its own
 * edit state machine (task #1130 part 2).
 */
/**
 * Keyboard options of the agent "Edit display name" dialog field: a name, so autocorrect is off,
 * like the other name fields (see [RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions], CMP-10674).
 * iOS 1.24.0 crashed on a tap in this field right after the dialog opened; the symbolicated trap is
 * in Skia's caret hit test (`ParagraphImpl::countSurroundingGraphemes`), so turning autocorrect
 * off (no autocorrect / inline-prediction text in the field) is a mitigation, not a proven fix.
 */
internal val MembersAgentDisplayNameKeyboardOptions: KeyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions

@Composable
private fun MembersAgentProfileFieldsSection(
    member: MemberProfile,
    edit: MembersAgentProfileEditInputs,
    sharedAgentDetailStore: SharedAgentDetailStore?,
    storeState: SharedAgentDetailState?,
    showDescriptionWhenNotEditing: Boolean = true,
    /** The profile hero; receives the display-name pencil action (null when not editable). */
    hero: @Composable (onEditDisplayName: (() -> Unit)?) -> Unit
) {
    val canEdit = edit.canEdit
    val agentProfileEditAuthority = edit.agentProfileEditAuthority
    val agentDetailActions = edit.agentDetailActions
    val onUpdateAgentProfile = edit.onUpdateAgentProfile
    val onApplyAgentProfileResult = edit.onApplyAgentProfileResult
    val onUnavailableAction = edit.onUnavailableAction
    val errorStrings = edit.errorStrings
    val scope = rememberCoroutineScope()
    val currentProfileEditAuthority by rememberUpdatedState(agentProfileEditAuthority)
    val profileEditingUnavailable = stringResource(Res.string.member_error_profile_edit_unavailable)
    val displayNameRequired = stringResource(Res.string.member_error_display_name_required)
    val permissionErrorTemplate = membersStringTemplate(Res.string.member_error_permission)
    val saveErrorTemplate = membersStringTemplate(Res.string.member_error_save)
    val descriptionTooLongMessage =
        stringResource(Res.string.agent_create_error_description_length, PROFILE_DESCRIPTION_MAX_LENGTH)

    // Display Name: independent edit state machine, mirrors React `editingDisplayName`
    // (AgentDetailPanel.tsx ~L1073). Pencil icon toggles edit mode; inline Save/Cancel
    // dispatch the update; Enter submits, Escape cancels.
    val profileStore = sharedAgentDetailStore
    val profileState = storeState
    val storeBackedProfileEdit = profileStore != null && profileState != null
    var localEditingDisplayName by remember(member.id.value) { mutableStateOf(false) }
    var localDraftDisplayName by remember(member.id.value, member.displayName) { mutableStateOf(member.displayName) }
    var localSavingDisplayName by remember(member.id.value) { mutableStateOf(false) }
    var localDisplayNameError by remember(member.id.value) { mutableStateOf<String?>(null) }
    var displayNameEditorAuthority by remember(member.id.value) {
        mutableStateOf<MembersAgentProfileEditAuthority?>(null)
    }
    val editingDisplayName =
        if (storeBackedProfileEdit) profileState.displayNameEditing else localEditingDisplayName
    val draftDisplayName =
        if (storeBackedProfileEdit) profileState.displayNameDraft ?: member.displayName else localDraftDisplayName
    val savingDisplayName =
        if (storeBackedProfileEdit) profileState.displayNameSaving else localSavingDisplayName
    val displayNameError =
        if (storeBackedProfileEdit) profileState.displayNameError else localDisplayNameError

    // Description: independent edit state machine, mirrors React `editingRole`
    // (~L1180). Same shape as display name but uses textarea + char counter.
    var localEditingRole by remember(member.id.value) { mutableStateOf(false) }
    var localDraftRole by remember(member.id.value, member.description) { mutableStateOf(member.description.orEmpty()) }
    var localSavingRole by remember(member.id.value) { mutableStateOf(false) }
    var localRoleError by remember(member.id.value) { mutableStateOf<String?>(null) }
    var roleEditorAuthority by remember(member.id.value) {
        mutableStateOf<MembersAgentProfileEditAuthority?>(null)
    }
    val editingRole =
        if (storeBackedProfileEdit) profileState.roleEditing else localEditingRole
    val draftRole =
        if (storeBackedProfileEdit) profileState.roleDraft ?: member.description.orEmpty() else localDraftRole
    val savingRole =
        if (storeBackedProfileEdit) profileState.roleSaving else localSavingRole
    val roleError =
        if (storeBackedProfileEdit) profileState.roleError else localRoleError
    // The failed save's trace id, shown under its error (AGENTS.md "Errors carry the trace ID").
    var roleErrorTraceId by remember(member.id.value) { mutableStateOf<String?>(null) }
    // slock #15236: a saved description's links some of its readers can't open, shown under it until dismissed.
    var descriptionLinkWarnings by remember(member.id.value) {
        mutableStateOf<List<build.raft.app.data.DescriptionLinkWarning>>(emptyList())
    }
    // Web AgentProfileEditDialog (slock #15664): one saved under the older, longer limit stays as it
    // is; once edited, the 300 limit applies. Save waits for a change that fits.
    val trimmedDraftRole = draftRole.trim()
    val descriptionChanged = trimmedDraftRole != member.description.orEmpty()
    val descriptionTooLong = profileDescriptionTooLong(trimmedDraftRole, member.description)

    fun saveDisplayName() {
        val callback = onUpdateAgentProfile
        if (
            !canEdit ||
            displayNameEditorAuthority != agentProfileEditAuthority ||
            (agentDetailActions == null && callback == null) ||
            savingDisplayName
        ) {
            onUnavailableAction(profileEditingUnavailable)
            return
        }
        val next = draftDisplayName.trim()
        if (next.isBlank()) {
            if (storeBackedProfileEdit) {
                profileStore.dispatch(
                    SharedAgentDetailIntent.SetDisplayNameError(displayNameRequired)
                )
            } else {
                localDisplayNameError = displayNameRequired
            }
            return
        }
        scope.launch {
            if (displayNameEditorAuthority != currentProfileEditAuthority) {
                onUnavailableAction(profileEditingUnavailable)
                return@launch
            }
            if (storeBackedProfileEdit) {
                profileStore.dispatch(SharedAgentDetailIntent.StartSaveDisplayName(next))
            } else {
                localSavingDisplayName = true
                localDisplayNameError = null
            }
            val result =
                try {
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = next,
                        description = member.description?.takeIf { it.isNotBlank() }
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_profile_save_failed"))
                }
            if (displayNameEditorAuthority != currentProfileEditAuthority) return@launch
            when (result) {
                is DataResult.Content -> {
                    onApplyAgentProfileResult(result.data)
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveDisplayName)
                    } else {
                        localEditingDisplayName = false
                    }
                }
                is DataResult.Empty -> {
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveDisplayName)
                    } else {
                        localEditingDisplayName = false
                    }
                }
                is DataResult.NoPermission -> {
                    val error =
                        membersFormattedPermissionError(permissionErrorTemplate, result.blocker, errorStrings)
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetDisplayNameError(error))
                    } else {
                        localDisplayNameError = error
                    }
                }
                is DataResult.Error -> {
                    val error = membersFormattedScreenError(saveErrorTemplate, result.error, errorStrings)
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetDisplayNameError(error))
                    } else {
                        localDisplayNameError = error
                    }
                }
            }
            if (!storeBackedProfileEdit) {
                localSavingDisplayName = false
            }
        }
    }

    fun saveRole() {
        val callback = onUpdateAgentProfile
        if (
            !canEdit ||
            roleEditorAuthority != agentProfileEditAuthority ||
            (agentDetailActions == null && callback == null) ||
            savingRole
        ) {
            onUnavailableAction(profileEditingUnavailable)
            return
        }
        val next = draftRole.trim()
        // The field already says so and Save is off; nothing to send.
        if (!descriptionChanged || descriptionTooLong) return
        roleErrorTraceId = null
        descriptionLinkWarnings = emptyList()
        scope.launch {
            if (roleEditorAuthority != currentProfileEditAuthority) {
                onUnavailableAction(profileEditingUnavailable)
                return@launch
            }
            if (storeBackedProfileEdit) {
                profileStore.dispatch(SharedAgentDetailIntent.StartSaveRole(next))
            } else {
                localSavingRole = true
                localRoleError = null
            }
            val result =
                try {
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = member.displayName,
                        description = next.takeIf { it.isNotBlank() }
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_profile_save_failed"))
                }
            if (roleEditorAuthority != currentProfileEditAuthority) return@launch
            when (result) {
                is DataResult.Content -> {
                    descriptionLinkWarnings = result.data.descriptionLinkWarnings
                    onApplyAgentProfileResult(result.data.withoutConfigApply())
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveRole)
                    } else {
                        localEditingRole = false
                    }
                }
                is DataResult.Empty -> {
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveRole)
                    } else {
                        localEditingRole = false
                    }
                }
                is DataResult.NoPermission -> {
                    val error =
                        membersFormattedPermissionError(permissionErrorTemplate, result.blocker, errorStrings)
                    roleErrorTraceId = result.blocker.traceId
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetRoleError(error))
                    } else {
                        localRoleError = error
                    }
                }
                is DataResult.Error -> {
                    // 400 `profile_description_invalid`: the Server's limit (the stored description it
                    // compares with may be newer than this page's); said as the field's own error.
                    val error =
                        if (result.error.serverCode == PROFILE_DESCRIPTION_INVALID_CODE) {
                            descriptionTooLongMessage
                        } else {
                            membersFormattedScreenError(saveErrorTemplate, result.error, errorStrings)
                        }
                    roleErrorTraceId = result.error.traceId
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetRoleError(error))
                    } else {
                        localRoleError = error
                    }
                }
            }
            if (!storeBackedProfileEdit) {
                localSavingRole = false
            }
        }
    }

    fun beginEditDisplayName() {
        displayNameEditorAuthority = agentProfileEditAuthority
        if (storeBackedProfileEdit) {
            profileStore.dispatch(
                SharedAgentDetailIntent.BeginEditDisplayName(member.displayName)
            )
        } else {
            localDraftDisplayName = member.displayName
            localDisplayNameError = null
            localEditingDisplayName = true
        }
    }

    fun cancelEditDisplayName() {
        if (storeBackedProfileEdit) {
            profileStore.dispatch(
                SharedAgentDetailIntent.CancelEditDisplayName
            )
        } else {
            localDraftDisplayName = member.displayName
            localDisplayNameError = null
            localEditingDisplayName = false
        }
        displayNameEditorAuthority = null
    }

    // Web AgentDetailPanel: the hero carries the display name and its pencil (AgentHeaderName);
    // the pencil opens the profile edit dialog on the name field. There is no "Display name"
    // section in the body. The hero is rendered here so it can reach this editor's state.
    hero(
        if (canEdit && !editingDisplayName) {
            { beginEditDisplayName() }
        } else {
            null
        }
    )
    if (editingDisplayName) {
        // Web AgentProfileEditDialog (field = displayName): "Edit display name", one Display name
        // field, Cancel + Save.
        RaftUnifiedDialog(
            title = stringResource(Res.string.member_action_edit_display_name).raftDisplayUppercase(),
            dismissEnabled = !savingDisplayName,
            dismissOnOutsideTap = false,
            onDismiss = { cancelEditDisplayName() }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)
            ) {
                RaftField(
                    label = stringResource(Res.string.member_label_display_name),
                    modifier = Modifier.fillMaxWidth(),
                    error = displayNameError?.takeIf { it.isNotBlank() }
                ) {
                    // The app editor behaviour (IME action, focus dismiss) plus the open-on-edit focus.
                    val displayNameEditor =
                        rememberRaftDesignSystemEditor(
                            keyboardOptions = MembersAgentDisplayNameKeyboardOptions,
                            keyboardActions = KeyboardActions.Default,
                            keyboardHeightChange = {},
                            focusRequester = remember { FocusRequester() },
                            autoFocus = !savingDisplayName,
                            onFocus = {},
                            onBlur = {}
                        )
                    RaftTextField(
                        value = draftDisplayName,
                        placeholder = stringResource(Res.string.member_label_display_name),
                        modifier = Modifier.fillMaxWidth().then(displayNameEditor.modifier),
                        keyboardOptions = displayNameEditor.keyboardOptions,
                        onValueChange = {
                            if (storeBackedProfileEdit) {
                                profileStore.dispatch(
                                    SharedAgentDetailIntent.UpdateDisplayNameDraft(it)
                                )
                            } else {
                                localDraftDisplayName = it
                                if (localDisplayNameError != null) localDisplayNameError = null
                            }
                        },
                        enabled = !savingDisplayName
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm, Alignment.End)
                ) {
                    RaftButton(
                        text = stringResource(Res.string.common_cancel),
                        variant = RaftButtonVariant.Ghost,
                        size = RaftButtonSize.Lg,
                        onClick = { cancelEditDisplayName() },
                        enabled = !savingDisplayName
                    )
                    RaftButton(
                        text = if (savingDisplayName) stringResource(Res.string.member_action_saving) else stringResource(Res.string.common_save),
                        onClick = { saveDisplayName() },
                        enabled = !savingDisplayName,
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Lg
                    )
                }
            }
        }
    }

    // Description — web AgentProfileInfo `px-5 py-3`: the eyebrow row (`mb-1`) over the text.
    // Its `py-3` bottom is 4dp short of the profile column's 16dp section spacing.
    // On a bounded public projection (remote joint-channel agent or `channel_summary` projection),
    // omit the whole section when empty instead of showing "No description".
    if (editingRole || showDescriptionWhenNotEditing) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MembersProfileHeroToDescriptionGap)
                .membersOverlapNextSection(RaftAppSpacing.Md - 12.dp),
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
        ) {
            // Description field (independent edit state machine)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MembersEditableSectionTitleRow(
                    title = stringResource(Res.string.member_section_description),
                    showEdit = canEdit && !editingRole,
                    onEditClick = {
                        roleEditorAuthority = agentProfileEditAuthority
                        if (storeBackedProfileEdit) {
                            profileStore.dispatch(
                                SharedAgentDetailIntent.BeginEditRole(member.description.orEmpty())
                            )
                        } else {
                            localDraftRole = member.description.orEmpty()
                            localRoleError = null
                            localEditingRole = true
                        }
                    }
                )
            }
            if (editingRole) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
                ) {
                    val shownRoleError =
                        roleError?.takeIf { it.isNotBlank() } ?: descriptionTooLongMessage.takeIf { descriptionTooLong }
                    RaftField(
                        label = null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val descriptionFocus = remember { FocusRequester() }
                        LaunchedEffect(Unit) {
                            delay(100)
                            descriptionFocus.requestFocus()
                        }
                        RaftTextarea(
                            value = draftRole,
                            placeholder = stringResource(Res.string.member_placeholder_description),
                            modifier = Modifier.fillMaxWidth(),
                            inputModifier = Modifier.focusRequester(descriptionFocus),
                            invalid = shownRoleError != null,
                            onValueChange = {
                                if (storeBackedProfileEdit) {
                                    profileStore.dispatch(
                                        SharedAgentDetailIntent.UpdateRoleDraft(it)
                                    )
                                } else {
                                    localDraftRole = it
                                    if (localRoleError != null) localRoleError = null
                                }
                            },
                            enabled = !savingRole
                        )
                    }
                    // Web StableField message row: the hint (or the error) and the persistent "n/300"
                    // counter; one saved under the older limit reads e.g. 412/300 before it is edited.
                    RaftProfileDescriptionFoot(
                        count = trimmedDraftRole.length,
                        hint = stringResource(Res.string.agent_detail_description_hint),
                        error = shownRoleError
                    )
                    if (roleError != null) RaftTraceIdNote(roleErrorTraceId)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RaftButton(
                            text = if (savingRole) stringResource(Res.string.member_action_saving) else stringResource(Res.string.common_save),
                            onClick = { saveRole() },
                            enabled = !savingRole && descriptionChanged && !descriptionTooLong,
                            variant = RaftButtonVariant.Primary,
                            size = RaftButtonSize.Lg
                        )
                        RaftButton(
                            text = stringResource(Res.string.common_cancel),
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Lg,
                            onClick = {
                                if (storeBackedProfileEdit) {
                                    profileStore.dispatch(
                                        SharedAgentDetailIntent.CancelEditRole
                                    )
                                } else {
                                    localDraftRole = member.description.orEmpty()
                                    localRoleError = null
                                    localEditingRole = false
                                }
                            },
                            enabled = !savingRole
                        )
                    }
                }
            } else {
                val currentDescription = member.description?.takeIf { it.isNotBlank() }
                if (currentDescription != null) {
                    RaftProfileDescriptionText(
                        text = currentDescription,
                        // Web AgentProfileInfo: text-subhead (15/20 400).
                        style = RaftType.Subhead,
                        color = RaftColor.TextBlack,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        source = "agent_profile",
                        enableHoverCards = true,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.member_state_no_description),
                        // Web AgentProfileInfo: text-subhead (15/20 400); "No description" in hint ink, upright.
                        style = RaftType.Subhead,
                        color = RaftColor.TextMutedGray,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RaftDescriptionLinkWarnings(
                    warnings = descriptionLinkWarnings,
                    subject = build.raft.app.data.DescriptionLinkSubject.Agent,
                    onDismiss = { descriptionLinkWarnings = emptyList() },
                )
            }
        }
    }
}

/**
 * INFO section of the agent detail tab: server role (with its edit state machine), computer
 * status, created date and creator (task #1130 part 2, moved verbatim out of [MembersAgentProfileTab]).
 */
@Composable
private fun MembersAgentInfoSection(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    deleted: Boolean,
    edit: MembersAgentProfileEditInputs,
    machineStatusRow: (machineId: String?) -> MembersMachineStatusRow,
    onOpenComputer: (() -> Unit)?,
    onOpenManagedApp: (() -> Unit)? = null,
    timeContext: RaftTimeContext,
    memberAvatarResolver: RaftMemberAvatarResolver
) {
    val canEdit = edit.canEdit
    val agentProfileEditAuthority = edit.agentProfileEditAuthority
    val agentDetailActions = edit.agentDetailActions
    val onUpdateAgentProfile = edit.onUpdateAgentProfile
    val onApplyAgentProfileResult = edit.onApplyAgentProfileResult
    val onUnavailableAction = edit.onUnavailableAction
    val errorStrings = edit.errorStrings
    val scope = rememberCoroutineScope()
    val currentProfileEditAuthority by rememberUpdatedState(agentProfileEditAuthority)
    val roleEditingUnavailable = stringResource(Res.string.member_error_role_edit_unavailable)
    val permissionErrorTemplate = membersStringTemplate(Res.string.member_error_permission)
    val saveErrorTemplate = membersStringTemplate(Res.string.member_error_save)
    val emptyResponseMessage = stringResource(Res.string.member_error_empty_response)
    val roleUpdateUnconfirmedMessage = stringResource(Res.string.member_error_role_update_unconfirmed)
    val serverRoleDisplay =
        membersAgentDisplayServerRole(member.serverRole, summary?.serverRole, deleted = deleted)
    val currentServerRole = (serverRoleDisplay as? MembersAgentServerRoleDisplay.Known)?.role
    // The editor can only express Admin/Member. Offering it while the server holds a role we
    // do not recognise would let a save silently rewrite that role to one of ours, so the
    // affordance is withdrawn for Unrecognized -- the chip still shows the real name (#261).
    val canEditServerRole = canEdit && serverRoleDisplay !is MembersAgentServerRoleDisplay.Unrecognized
    val serverRoleRememberKey = serverRoleDisplay.rememberKey()
    var editingServerRole by remember(member.id.value, serverRoleRememberKey) { mutableStateOf(false) }
    var draftServerRole by remember(member.id.value, serverRoleRememberKey) {
        mutableStateOf(currentServerRole ?: MembersAgentServerRole.Member)
    }
    var serverRoleMenuExpanded by remember(member.id.value, serverRoleRememberKey) { mutableStateOf(false) }
    var roleHelpOpen by remember(member.id.value) { mutableStateOf(false) }
    var savingServerRole by remember(member.id.value) { mutableStateOf(false) }
    var serverRoleError by remember(member.id.value, serverRoleRememberKey) { mutableStateOf<String?>(null) }
    var serverRoleEditorAuthority by remember(member.id.value) {
        mutableStateOf<MembersAgentProfileEditAuthority?>(null)
    }

    fun saveServerRole() {
        val callback = onUpdateAgentProfile
        if (
            !canEditServerRole ||
            serverRoleEditorAuthority != agentProfileEditAuthority ||
            (agentDetailActions == null && callback == null) ||
            savingServerRole
        ) {
            onUnavailableAction(roleEditingUnavailable)
            return
        }
        val next = draftServerRole
        if (next == currentServerRole) {
            editingServerRole = false
            serverRoleError = null
            return
        }
        val expectedServerId = serverRoleEditorAuthority?.serverId
            ?: run {
                onUnavailableAction(roleEditingUnavailable)
                return
            }
        scope.launch {
            if (serverRoleEditorAuthority != currentProfileEditAuthority) {
                onUnavailableAction(roleEditingUnavailable)
                return@launch
            }
            savingServerRole = true
            serverRoleError = null
            val result =
                try {
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = member.displayName,
                        description = member.description?.takeIf { it.isNotBlank() },
                        serverRole = next.wireValue,
                        includeServerRole = true
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_server_role_save_failed"))
                }
            if (serverRoleEditorAuthority != currentProfileEditAuthority) return@launch
            when (result) {
                is DataResult.Content -> {
                    when (result.membersAgentServerRoleSaveOutcome(member.id, expectedServerId, next)) {
                        MembersAgentServerRoleSaveOutcome.Success -> {
                            onApplyAgentProfileResult(result.data)
                            editingServerRole = false
                            serverRoleMenuExpanded = false
                        }
                        MembersAgentServerRoleSaveOutcome.MismatchedContent ->
                            serverRoleError = roleUpdateUnconfirmedMessage
                        else -> serverRoleError = roleUpdateUnconfirmedMessage
                    }
                }
                is DataResult.Empty -> serverRoleError = emptyResponseMessage
                is DataResult.NoPermission ->
                    serverRoleError =
                        membersFormattedPermissionError(
                            permissionErrorTemplate,
                            result.blocker,
                            errorStrings
                        )
                is DataResult.Error ->
                    serverRoleError = membersFormattedScreenError(saveErrorTemplate, result.error, errorStrings)
            }
            savingServerRole = false
        }
    }

    // INFO section — web AgentProfileInfo: a separator, then `px-5 py-4` with the eyebrow
    // (`mb-3`) over a raft-ui DescriptionList (horizontal): each label beside its value, all labels
    // above their values once one doesn't fit. Order: Role, Computer, Computer status, Computer
    // Version, Created, Creator.
    MembersProfileSection(title = stringResource(Res.string.member_section_info)) {
        val isAppBot = !member.managedByAppInstallId.isNullOrBlank() || !summary?.managedByAppInstallId.isNullOrBlank()
        RaftDescriptionList(Modifier.fillMaxWidth(), RaftDescriptionListDirection.Horizontal) {
            // Role: web AgentProfileInfo hides server-role editing for an App bot (its authority is
            // the App's grant, not a server role).
            if (!isAppBot) {
                // Role: the label carries the "Agent roles" help and, for whoever may change it, the pencil;
                // the value is the plain role word (hidden role = empty value, web parity #261).
                MembersAgentInfoField(
                    label = stringResource(Res.string.member_label_role),
                    labelActions = {
                        MembersProfileIconAction(
                            iconName = RaftLucideIconName.CircleQuestionMark,
                            contentDescription = stringResource(Res.string.member_title_agent_roles),
                            onClick = { roleHelpOpen = true }
                        )
                        if (canEditServerRole && !editingServerRole) {
                            MembersProfilePencilButton(
                                contentDescription = stringResource(Res.string.member_action_edit_role),
                                onClick = {
                                    serverRoleEditorAuthority = agentProfileEditAuthority
                                    draftServerRole = currentServerRole ?: MembersAgentServerRole.Member
                                    serverRoleError = null
                                    serverRoleMenuExpanded = true
                                    editingServerRole = true
                                }
                            )
                        }
                    }
                ) {
                    if (editingServerRole) {
                        val serverRoleLabels = MembersAgentServerRole.entries.associateWith { it.localizedLabel() }
                        MembersServerRoleEditor(
                            displayValue = draftServerRole.localizedLabel(),
                            expanded = serverRoleMenuExpanded,
                            enabled = !savingServerRole,
                            saving = savingServerRole,
                            saveEnabled = draftServerRole != currentServerRole,
                            options = membersAgentServerRoleOptions(draftServerRole) { serverRoleLabels.getValue(it) },
                            selectedId = draftServerRole.wireValue,
                            error = serverRoleError,
                            onToggle = { serverRoleMenuExpanded = !serverRoleMenuExpanded },
                            onSelect = { roleId ->
                                roleId.toMembersAgentServerRoleOrNull()?.let { nextRole ->
                                    draftServerRole = nextRole
                                    serverRoleMenuExpanded = false
                                    serverRoleError = null
                                }
                            },
                            onSave = { saveServerRole() },
                            onCancel = {
                                draftServerRole = currentServerRole ?: MembersAgentServerRole.Member
                                serverRoleError = null
                                serverRoleMenuExpanded = false
                                editingServerRole = false
                            }
                        )
                    } else {
                        MembersAgentServerRoleChip(serverRoleDisplay)
                    }
                }
            }
            MembersAgentComputerInfoRows(
                member = member,
                summary = summary,
                machineStatusRow = machineStatusRow,
                onOpenComputer = onOpenComputer,
                onOpenManagedApp = onOpenManagedApp,
                timeContext = timeContext,
                memberAvatarResolver = memberAvatarResolver
            )
        }
        if (!isAppBot && roleHelpOpen) {
            MembersAgentRoleHelpDialog(onDismiss = { roleHelpOpen = false })
        }
    }
}

/**
 * raft-ui DescriptionDetails value type: `font-sans text-callout` (14/20 400, `foreground`).
 * [mono] is the web's `font-mono` on that span (machine names, versions, dates, model ids):
 * Space Mono at the same callout size.
 */
@Composable
internal fun membersInfoValueStyle(mono: Boolean = false): TextStyle {
    val typography = RaftTheme.typography
    return if (mono) typography.callout.copy(fontFamily = typography.monoCode.fontFamily) else typography.callout
}

/**
 * The "External agent setup" card (install the CLI, sign in, login token) on an agent's profile:
 * only for an external agent the reader may manage, and never for an App Bot (an app install's
 * agent is external on the server, but the app runs it). Web: `isExternalAgent && canManageAgent &&
 * !agent.managedByAppInstallId` (AgentDetailPanel.tsx).
 */
internal fun membersShowsExternalAgentSetup(
    external: Boolean,
    canManage: Boolean,
    managedByAppInstallId: String?,
): Boolean = external && canManage && managedByAppInstallId.isNullOrBlank()

@Composable
internal fun MembersAgentProfileTab(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    runtimeModels: MembersAgentRuntimeModelInputs = MembersAgentRuntimeModelInputs(),
    createAgentFormStore: SharedCreateAgentFormStore? = null,
    agentProfileEditAuthority: MembersAgentProfileEditAuthority? = null,
    canManage: Boolean,
    /** Permission signal for an already open runtime editor; survives a failed/in-flight refresh. */
    runtimeEditorCanManage: Boolean = canManage,
    agentStatusObservedState: RaftAgentStatusIndicatorState?,
    agentStatusIndicatorState: RaftAgentStatusIndicatorState?,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    onChangeAvatar: () -> Unit,
    sharedAgentDetailStore: SharedAgentDetailStore? = null,
    storeState: SharedAgentDetailState? = null,
    agentDetailActions: SharedAgentDetailActions? = null,
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    onApplyAgentProfileResult: (MemberProfile) -> Unit = {},
    onApplyAgentRuntimeConfigProjection:
        suspend (MembersAgentProfileEditAuthority, RuntimeConfig, Boolean?) -> Boolean = { _, _, _ -> false },
    onErrorToast: ((String, ScreenError) -> Unit)? = null,
    agentsApiClient: build.raft.app.data.RaftAgentsApiClient? = null,
    archiveInputs: MembersAgentArchiveInputs = MembersAgentArchiveInputs(),
    externalSetupServerUrl: String? = null,
    onUnavailableAction: (String) -> Unit,
    lifecycle: MembersAgentLifecycleInputs = MembersAgentLifecycleInputs(),
    machineStatusRow: (machineId: String?) -> MembersMachineStatusRow,
    timeContext: RaftTimeContext,
    fixtureStatusFallbackEnabled: Boolean = false,
    accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull,
    modifier: Modifier = Modifier
) {
    // Web AgentDetailPanel `isRemoteJointAgent`: an agent of another server of a joint channel is
    // a bounded public projection; its operational sections belong to its own server.
    val remoteProfile = membersAgentProfileIsRemote(accessMode)
    val onCopyText = lifecycle.onCopyText
    val onOpenComputer = lifecycle.onOpenComputer
    val errorStrings = localizedErrorPresentationStrings()
    val summary = detail?.summary?.let { if (remoteProfile) it.copy(runtime = null) else it }
    val deleted = summary?.deletedAt?.isNotBlank() == true
    // Roster metadata is never edit authority. Only a canonical loaded profile summary may enter
    // an editor or construct an update request.
    val canEdit = canManage && summary != null && agentProfileEditAuthority != null && !deleted
    val edit =
        MembersAgentProfileEditInputs(
            canEdit = canEdit,
            canManage = runtimeEditorCanManage,
            agentProfileEditAuthority = agentProfileEditAuthority,
            agentDetailActions = agentDetailActions,
            onUpdateAgentProfile = onUpdateAgentProfile,
            onApplyAgentProfileResult = onApplyAgentProfileResult,
            onUnavailableAction = onUnavailableAction,
            errorStrings = errorStrings,
            runtimeEditorOpenRequested = lifecycle.runtimeEditorOpenRequested,
            onRuntimeEditorOpenRequestHandled = lifecycle.onRuntimeEditorOpenRequestHandled,
            onErrorToast = onErrorToast
        )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)) {
        if (deleted) {
            // task #517 (web `AgentArchiveSection` parity): the archive block
            // carries the workspace state + delete-later affordance; when the
            // archive record can't be fetched it falls back to the same plain
            // status + timestamp block the pre-archive detail rendered.
            MembersAgentArchiveSection(
                agentId = member.id,
                canManage = canManage,
                fallbackArchivedAt = summary?.deletedAt,
                timeContext = timeContext,
                inputs = archiveInputs
            )
        }

        if (remoteProfile) {
            membersRemoteAgentSourceServerName(member)?.let { serverName ->
                MembersRemoteAgentSourceServerSection(serverName)
            }
        }

        // One plain call, no capturing composable lambda here: this function's generated code is
        // shape-sensitive (see the note above MembersAgentRuntimeModelInputs), so the hero slot
        // lambda lives in [MembersAgentProfileIdentity] instead.
        val showDescriptionWhenNotEditing =
            membersShouldShowAgentDescriptionSection(
                accessMode = accessMode,
                profileProjection = summary?.profileProjection?.takeIf { it.isNotBlank() } ?: member.profileProjection,
                description = member.description
            )
        MembersAgentProfileIdentity(
            member = member,
            summary = summary,
            canEdit = canEdit,
            edit = edit,
            sharedAgentDetailStore = sharedAgentDetailStore,
            storeState = storeState,
            agentStatusIndicatorState = agentStatusIndicatorState,
            agentStatusObservedState = agentStatusObservedState,
            memberAvatarResolver = memberAvatarResolver,
            onChangeAvatar = onChangeAvatar,
            lifecycle = lifecycle,
            showDescriptionWhenNotEditing = showDescriptionWhenNotEditing
        )
        if (!remoteProfile) {
            MembersAgentInfoSection(
                member = member,
                summary = summary,
                deleted = deleted,
                edit = edit,
                machineStatusRow = machineStatusRow,
                onOpenComputer = onOpenComputer,
                onOpenManagedApp = lifecycle.onOpenManagedApp,
                timeContext = timeContext,
                memberAvatarResolver = memberAvatarResolver
            )
            MembersAgentRuntimeConfigSection(
                member = member,
                summary = summary,
                agentStatusIndicatorState = agentStatusIndicatorState,
                edit = edit,
                runtimeModels = runtimeModels,
                createAgentFormStore = createAgentFormStore,
                onApplyAgentRuntimeConfigProjection = onApplyAgentRuntimeConfigProjection
            )

            if (
                membersShowsExternalAgentSetup(
                    external = member.isExternal || summary?.isExternal == true || isExternalAgentRuntime(summary?.runtime),
                    canManage = canManage,
                    managedByAppInstallId = member.managedByAppInstallId,
                )
            ) {
                when (membersExternalAgentSection(summary)) {
                    MembersExternalAgentSection.Hosted ->
                        summary?.hostedRuntime?.let { hostedRuntime ->
                            MembersAgentHostedRuntimeSection(
                                agentId = member.id,
                                initial = hostedRuntime,
                                agentsApiClient = agentsApiClient,
                                paused = !member.assistantPausedAt.isNullOrBlank()
                            )
                        }
                    MembersExternalAgentSection.Setup ->
                        agentsApiClient?.let { client ->
                            MembersExternalAgentConnectionCard(
                                agentId = member.id,
                                name = summary?.displayName?.takeIf { it.isNotBlank() } ?: member.displayName,
                                handle = summary?.name?.takeIf { it.isNotBlank() } ?: member.displayName,
                                initialStatus = detail?.externalStatus,
                                lastSeenAt = summary?.lastSeenAt,
                                serverUrl = externalSetupServerUrl,
                                agentsApiClient = client,
                                timeContext = timeContext,
                            )
                        }
                    MembersExternalAgentSection.NotKnownYet -> Unit
                }
            }

            // Web AgentDetailPanel profile tab: Info and Runtime config are followed by Skills,
            // then Agent migration and Actions (both their own items of the detail list, see
            // MembersDetailContent). It has no "Created agents" section (that list lives on a
            // person's profile, HumanDetailPanel), so the agent profile does not render one either.
            MembersAgentSkillsSection(detail)
        }
    }
}

/**
 * Raft Agent Voice settings & Who asked (web AgentDetailPanel `AgentVoiceSection`), framed as the
 * profile's other sections: a full-bleed separator, then the section's own padding. Nothing when
 * `agent_voice_v1` is off, viewer cannot manage, or `voice-settings` returns 404.
 */
@Composable
internal fun MembersAgentVoiceSectionHost(agentId: MemberId) {
    AgentVoiceSection(agentId = agentId.value) { content ->
        Column(modifier = Modifier.fillMaxWidth()) {
            MembersProfileFullBleedDivider()
            Column(modifier = Modifier.fillMaxWidth().padding(top = MembersProfileSectionPaddingY), content = content)
        }
    }
}

/**
 * The owner's 「Assistant」 section (web AgentDetailPanel `AssistantOwnerSection`), framed as the
 * profile's other sections: a full-bleed separator, then the section's own padding. Nothing for
 * another agent or another viewer.
 */
@Composable
internal fun MembersAgentAssistantOwnerSection(agentId: MemberId) {
    AssistantOwnerSection(agentId = agentId.value) { content ->
        Column(modifier = Modifier.fillMaxWidth()) {
            MembersProfileFullBleedDivider()
            Column(modifier = Modifier.fillMaxWidth().padding(top = MembersProfileSectionPaddingY), content = content)
        }
    }
}

/** The profile hero plus the description, sharing the display-name editor's state. */
@Composable
private fun MembersAgentProfileIdentity(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    canEdit: Boolean,
    edit: MembersAgentProfileEditInputs,
    sharedAgentDetailStore: SharedAgentDetailStore?,
    storeState: SharedAgentDetailState?,
    agentStatusIndicatorState: RaftAgentStatusIndicatorState?,
    agentStatusObservedState: RaftAgentStatusIndicatorState?,
    memberAvatarResolver: RaftMemberAvatarResolver,
    onChangeAvatar: () -> Unit,
    lifecycle: MembersAgentLifecycleInputs,
    showDescriptionWhenNotEditing: Boolean = true
) {
    MembersAgentProfileFieldsSection(
        member = member,
        edit = edit,
        sharedAgentDetailStore = sharedAgentDetailStore,
        storeState = storeState,
        showDescriptionWhenNotEditing = showDescriptionWhenNotEditing,
        hero = { onEditDisplayName ->
            val toolStuck = membersAgentToolStuck(agentStatusIndicatorState, summary?.toolStuck)
            val spawnFailure =
                membersAgentStatusSpawnFailure(
                    indicator = agentStatusIndicatorState,
                    summaryDetailKind = summary?.activityDetailKind,
                    summaryActivityDetail = summary?.activityDetail,
                    summarySpawnFailure = summary?.spawnFailure,
                )
            val recentActivity = lifecycle.recentActivity
            MembersAgentProfileHeader(
                member = member,
                summary = summary,
                canEdit = canEdit,
                agentStatusIndicatorState = agentStatusIndicatorState,
                agentStatusObservedState = agentStatusObservedState,
                memberAvatarResolver = memberAvatarResolver,
                onChangeAvatar = onChangeAvatar,
                toolStuck = toolStuck,
                onStopTurn = lifecycle.onStopTurn,
                stopTurnInProgress = lifecycle.stopTurnInProgress,
                // Web: `canManageAgent && !agent.deletedAt && !agent.managedByAppInstallId`.
                onEditDisplayName = onEditDisplayName?.takeIf { member.managedByAppInstallId.isNullOrBlank() },
                showActivityDetail = lifecycle.showActivityDetail,
                spawnFailure = spawnFailure,
                onChangeModel = lifecycle.onOpenModelSettings,
                timeContext = lifecycle.timeContext,
                onRetry = lifecycle.onRetryRestart,
                retryInProgress = lifecycle.retryRestartInProgress,
                onOpenComputer = lifecycle.onOpenComputer,
                onOpenManagedApp = lifecycle.onOpenManagedApp,
                // With Recent activity the status is the card's first line (web).
                showStatus = recentActivity == null,
                computerRevokedNote = lifecycle.computerRevokedNote,
            )
            lifecycle.computerOfflineCard?.let { offlineCard ->
                // Web AgentDetailPanel: `<div className="px-5 pb-4"><AgentComputerOfflineCard /></div>`,
                // right under the hero and above Recent activity / AgentProfileInfo.
                MembersAgentComputerOfflineCard(
                    state = offlineCard,
                    timeContext = lifecycle.timeContext,
                    onOpenComputer = lifecycle.onOpenComputer,
                    onCopyCommand = lifecycle.onCopyComputerCommand,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (recentActivity != null) {
                // Web `px-5 py-4` then a full-bleed Separator, before AgentProfileInfo: 36dp from
                // the hero (its 8dp, the column's 16dp, 12dp here), 12dp from the separator to
                // the description (the column's 16dp and the description's 8dp, less 12dp).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MembersProfileRecentActivityTopGap)
                        .membersOverlapNextSection(MembersProfileRecentActivityTopGap)
                ) {
                    MembersAgentRecentActivity(
                        agentId = member.id,
                        liveActivity = summary?.activity,
                        inputs = recentActivity,
                        otter = membersAgentRecentActivityOtter(agentStatusObservedState?.status),
                    ) {
                        MembersAgentProfileStatus(
                            member = member,
                            summary = summary,
                            agentStatusObservedState = agentStatusObservedState,
                            agentStatusIndicatorState = agentStatusIndicatorState,
                            toolStuck = toolStuck,
                            onStopTurn = lifecycle.onStopTurn,
                            stopTurnInProgress = lifecycle.stopTurnInProgress,
                            showActivityDetail = lifecycle.showActivityDetail,
                            spawnFailure = spawnFailure,
                            onChangeModel = lifecycle.onOpenModelSettings,
                            timeContext = lifecycle.timeContext,
                            onRetry = lifecycle.onRetryRestart,
                            retryInProgress = lifecycle.retryRestartInProgress,
                            onOpenComputer = lifecycle.onOpenComputer,
                            itemTop = 0.dp,
                            computerRevokedNote = lifecycle.computerRevokedNote,
                        )
                    }
                    Spacer(Modifier.height(MembersProfileSectionPaddingY))
                    MembersProfileFullBleedDivider()
                }
            }
        }
    )
    // Web AgentDetailPanel: under the hero, the owner's paused notice with Start. Here, not in
    // [MembersAgentProfileTab], whose body is shape-sensitive (see MembersAgentRuntimeModelInputs).
    // Only the viewer's own assistant of this server shows it, never a remote agent's profile.
    if (summary?.deletedAt.isNullOrBlank()) {
        AssistantOwnerPausedNotice(agentId = member.id.value, fallbackPausedAt = member.assistantPausedAt)
    }
}

@Composable
internal fun MembersAgentProfileActionsSection(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    resolvedStatus: RaftAgentStatusIndicatorState?,
    canManage: Boolean,
    lifecycleInFlight: String?,
    diagnosticActionState: MembersAgentDiagnosticActionState?,
    onRunLifecycleAction: (MembersAgentLifecycleAction) -> Unit,
    onOpenRestartReset: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onReportIssue: () -> Unit,
    /**
     * Web AgentDetailPanel: Report issue only once the server's client config answered privacy
     * mode "off" (its bundle goes to Raft's trace-upload worker); hidden while unknown or on.
     */
    reportIssueOffered: Boolean,
    /** Web `computerOffline` (the agent's machine is known and offline): Restart / reset is not offered. */
    computerOffline: Boolean = false,
    modifier: Modifier = Modifier
) {
    val summary = detail?.summary
    val deleted = summary?.deletedAt?.isNotBlank() == true
    if (!canManage || deleted) return

    // The action reads the same process presence SSOT as the header/avatar.
    // Detail/profile snapshots never decide a production lifecycle action.
    val primaryAction =
        membersResolvedPrimaryLifecycleAction(
            resolvedStatus = resolvedStatus?.status,
            detailFallbackStatus = null,
            deleted = false,
            hasResolvedPresence = true
        )
    val primaryLabel =
        when (primaryAction) {
            MembersAgentLifecycleAction.Start -> stringResource(Res.string.member_action_start_agent)
            MembersAgentLifecycleAction.Stop -> stringResource(Res.string.member_action_stop_agent)
            null -> stringResource(Res.string.member_action_start_agent)
            else -> primaryAction.localizedLabel()
        }
    val primaryBusy = lifecycleInFlight == primaryAction?.actionKey
    val copyState = diagnosticActionState?.takeIf { it.actionKey == "copy" }
    val reportState = diagnosticActionState?.takeIf { it.actionKey == "report" }
    val diagnosticBusy = diagnosticActionState?.inProgress == true

    // Web AgentActionRows (slock #15515): `<Separator />`, then `px-5 py-4` and a card of action
    // rows with no heading, as at the end of a channel's details. Only Archive agent, which can't
    // be undone, is red; Stop agent and Restart / Reset interrupt but can be undone, so they stay
    // ink and warn in their confirmations.
    Column(modifier = modifier.fillMaxWidth()) {
        MembersProfileFullBleedDivider()
        // The web's names on the card and its rows (data-testid), for the visual-testing component comparison.
        RaftListRowGroup(Modifier.fillMaxWidth().padding(vertical = MembersProfileSectionPaddingY).testTag("agent-actions-group")) {
            if (primaryAction != null) {
                MembersAgentActionRow(
                    testTag = "agent-${primaryAction.actionKey}-action",
                    label = if (primaryBusy) primaryAction.localizedBusyLabel() else primaryLabel,
                    iconName = primaryAction.iconName,
                    enabled = lifecycleInFlight == null,
                    divider = false,
                    risk = membersAgentProfileActionRisk(primaryAction),
                    onClick = { onRunLifecycleAction(primaryAction) }
                )
            }
            // Web `restartResetOffered(canControl, computerOffline)`.
            if (!computerOffline) {
                MembersAgentActionRow(
                    testTag = "agent-restart-reset-action",
                    label = stringResource(Res.string.member_label_restart_reset),
                    iconName = RaftLucideIconName.RotateCcw,
                    enabled = lifecycleInFlight == null,
                    divider = primaryAction != null,
                    risk = membersAgentProfileActionRisk(MembersAgentLifecycleAction.Restart),
                    onClick = onOpenRestartReset
                )
            }
            MembersAgentActionRow(
                testTag = "agent-copy-diagnostic-action",
                label = copyState?.successLabel ?: stringResource(Res.string.member_action_copy_diagnostic_info),
                iconName = if (copyState?.successLabel != null) RaftLucideIconName.Check else RaftLucideIconName.Copy,
                enabled = !diagnosticBusy,
                divider = primaryAction != null || !computerOffline,
                risk = null,
                onClick = onCopyDiagnostics
            )
            if (reportIssueOffered) {
                MembersAgentActionRow(
                    testTag = "agent-report-issue-action",
                    label = if (reportState?.inProgress == true) stringResource(Res.string.member_action_reporting) else stringResource(Res.string.member_action_report_issue),
                    iconName = RaftLucideIconName.Bug,
                    enabled = !diagnosticBusy,
                    divider = true,
                    risk = null,
                    onClick = onReportIssue
                )
            }
            MembersAgentActionRow(
                testTag = "agent-archive-button",
                label = if (lifecycleInFlight == MembersAgentLifecycleAction.Delete.actionKey) stringResource(Res.string.member_action_deleting) else stringResource(Res.string.member_action_delete_agent),
                iconName = RaftLucideIconName.Archive,
                enabled = lifecycleInFlight == null,
                divider = true,
                risk = membersAgentProfileActionRisk(MembersAgentLifecycleAction.Delete),
                onClick = { onRunLifecycleAction(MembersAgentLifecycleAction.Delete) }
            )
        }
    }
}

/**
 * An agent profile action row's risk tone (web AgentActionRows, slock #15515; raft-design ListRow
 * "An action's risk"): red only for what can't be undone (Archive agent). Start, Stop and
 * Restart / Reset are ink: Stop and Restart interrupt but can be undone, and warn in their
 * confirmations' orange buttons instead.
 */
internal fun membersAgentProfileActionRisk(action: MembersAgentLifecycleAction): RaftRiskTone? =
    if (action.destructive) RaftRiskTone.Danger else null

/** One action row (raft-ui ListRow `plain`): its icon and its name, both in the risk's ink. */
@Composable
private fun MembersAgentActionRow(
    testTag: String,
    label: String,
    iconName: RaftLucideIconName,
    enabled: Boolean,
    divider: Boolean,
    risk: RaftRiskTone?,
    onClick: () -> Unit
) {
    RaftListRow(
        modifier = Modifier.testTag(testTag),
        title = label,
        variant = RaftListRowVariant.Plain,
        onClick = onClick,
        risk = risk,
        // Each row is one action: the 18dp icon on all of them, toned or not (raft-ui 0.102.21).
        action = true,
        enabled = enabled,
        divider = divider,
        leading = { RaftComputersIcon(iconName, size = LocalRaftListRowIconSize.current) },
    )
}

@Composable
private fun MemberKindBadge(kind: MemberKind) {
    // raft-ui Badge appearance="outline": the `Agent` / `Human` tag beside the name (web agent page
    // hero, `sidebar.findConversationAgentTag`), in the UI language rather than the enum name.
    val label =
        when (kind) {
            MemberKind.Human -> stringResource(Res.string.composer_mention_actor_human)
            MemberKind.Agent, MemberKind.System, MemberKind.External -> stringResource(Res.string.member_label_agent)
        }
    RaftBadge(text = label, appearance = RaftBadgeAppearance.Outline)
}

@Composable
internal fun MembersProfilePencilButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(18.dp)
            .raftClickable(onClick = onClick, shape = RoundedCornerShape(RaftAppRadius.Chip))
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        RaftLucideIcon(
            RaftLucideIconName.Pencil,
            modifier = Modifier.size(14.dp),
            color = RaftColor.TextMutedGrayArgb
        )
    }
}

@Composable
private fun MembersEditableSectionTitleRow(
    title: String,
    showEdit: Boolean,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
    ) {
        MembersDetailSectionTitle(title)
        if (showEdit) {
            MembersProfilePencilButton(onClick = onEditClick)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Web AgentDetailPanel remote joint agent: `agent.detail.from` eyebrow over the server name. */
@Composable
private fun MembersRemoteAgentSourceServerSection(serverName: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
    ) {
        MembersDetailSectionTitle(stringResource(Res.string.member_section_from))
        RaftText(
            text = serverName,
            style = RaftTheme.typography.subhead.withRaftFontWeight(FontWeight.SemiBold),
            color = RaftTheme.colors.foreground
        )
        MembersDetailSectionDivider(Modifier.padding(top = RaftAppSpacing.Sm))
    }
}

@Composable
internal fun MembersDetailSectionDivider(modifier: Modifier = Modifier) {
    // raft-ui ProfilePanelSection: a `line-hairline` above each section.
    RaftSeparator(modifier)
}

/**
 * Web agent profile `<Separator />`: the hairline spans the whole panel while the section content
 * sits in `px-5`. The profile tab lays its sections out inside [MembersAgentProfileInset], so the
 * line reaches back out over that inset on both sides.
 */
@Composable
internal fun MembersProfileFullBleedDivider(modifier: Modifier = Modifier) {
    RaftSeparator(modifier.membersProfileFullBleed())
}

/** Reports [overlap] less height than drawn, so the next item of a spaced column starts closer. */
private fun Modifier.membersOverlapNextSection(overlap: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height - overlap.roundToPx()).coerceAtLeast(0)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

private fun Modifier.membersProfileFullBleed(inset: Dp = MembersAgentProfileInset): Modifier =
    layout { measurable, constraints ->
        val insetPx = inset.roundToPx()
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val width = constraints.maxWidth + insetPx * 2
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-insetPx, 0) }
    }

@Composable
internal fun MembersDetailSectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    // raft-ui profile section title (SectionEyebrow): footnote bold `foreground-hint`, sentence case.
    RaftSectionEyebrow(text = title, modifier = modifier)
}

/** raft-ui Card (default) frame: container radius, a `line-hairline` edge on `layer-card`. */
@Composable
internal fun Modifier.membersRaftCard(): Modifier {
    val shape = RoundedCornerShape(RaftRadius.Container)
    val colors = RaftTheme.colors
    return clip(shape).background(colors.layerCard, shape).border(RaftStroke.Hairline, colors.lineHairline, shape)
}

@Composable
internal fun MembersDetailSection(
    title: String,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)) {
        MembersDetailSectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
        ) {
            MembersDetailSectionTitle(title, modifier = Modifier.weight(1f))
            trailingContent?.invoke()
        }
        // raft-ui Card (default): container radius, `line-hairline` edge on `layer-card`, p-4.
        val contentModifier =
            if (framed) {
                Modifier
                    .fillMaxWidth()
                    .membersRaftCard()
                    .padding(RaftAppSpacing.Md)
            } else {
                Modifier.fillMaxWidth()
            }
        Column(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
        ) {
            content()
        }
    }
}

@Composable
internal fun MembersDetailField(label: String, value: String) {
    // raft-ui DescriptionItem (vertical): the term over its value; "—" in hint ink when empty.
    Column(modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp)) {
        RaftText(
            text = label,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
            color = RaftTheme.colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        RaftText(
            text = value.ifBlank { "—" },
            style = RaftTheme.typography.callout,
            color = if (value.isBlank()) RaftTheme.colors.foregroundHint else RaftTheme.colors.foreground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}
