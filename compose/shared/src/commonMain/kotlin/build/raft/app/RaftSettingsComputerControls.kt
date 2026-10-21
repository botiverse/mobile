package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsComputerReleaseNotesSnapshot
import build.raft.app.data.SettingsComputerRestartProgress
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.COMPUTER_RESTART_TIMED_OUT
import build.raft.app.markdown.RaftMarkdownBody
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/*
 * The Computer controls of the computer detail page, after web MachineDetailPanel.tsx (botiverse/slock
 * staging 6b367a0cd40adf0532ae0fe226091a8dbd395140, lines ~1380-2390): the version notice, the
 * offline "bring this computer back online" card, the legacy daemon's migrate block, the Computer
 * card (restart, upgrade, terminal verification) and the recovery guide. The decisions live in
 * RaftComputerUpgradeModel.kt; the commands in RaftComputerCommands.kt.
 */

/** Everything the controls need besides the computer row, resolved once by the detail body. */
internal class RaftComputerControlsContext(
    val computer: SettingsComputerSnapshot,
    val upgrade: RaftComputerUpgradeModel,
    val restart: SettingsComputerRestartProgress?,
    val canManage: Boolean,
    val commandContext: SettingsComputerCommandContext?,
    val onRestart: () -> Unit,
    val onUpgrade: (String) -> Unit,
    val onCopy: (String) -> Unit,
    val onOpenInstallGuide: () -> Unit,
) {
    val platform: RaftComputerCommandPlatform = raftComputerCommandPlatform(computer.platform)

    /**
     * The machine's own commands (web `machineComputerCommands`, which equal its
     * `computerFreshInstallCommands`: no version pin, the installer resolves the latest release).
     * Legacy daemon rows adopt themselves with `--machine <id>`.
     */
    val commands: RaftComputerCommandSet? =
        raftComputerCommands(commandContext, platform, machineId = computer.id.takeUnless { computer.isComputer })
}

// region Shared parts

/** Explanatory copy above a control or a command (web `DETAIL_HELP_CLASS_NAME`). */
@Composable
private fun RaftComputerHelp(text: String, modifier: Modifier = Modifier) {
    RaftText(text, modifier = modifier, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
}

/** Something that went wrong but can be fixed (web `DETAIL_PROBLEM_CLASS_NAME`). */
@Composable
private fun RaftComputerProblem(text: String) {
    RaftText(text, style = RaftTheme.typography.footnote, color = RaftTheme.colors.warningStrong)
}

/** The label of one step (web `DETAIL_STEP_LABEL_CLASS_NAME`). */
@Composable
private fun RaftComputerStepLabel(text: String) {
    RaftText(text, style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold), color = RaftTheme.colors.foreground)
}

/** A heading inside a card or a section: a 16dp icon and the eyebrow (web `SectionEyebrow`). */
@Composable
private fun RaftComputerEyebrow(text: String, icon: RaftLucideIconName? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) RaftComputersIcon(icon)
        RaftText(text, style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold), color = RaftTheme.colors.foregroundHint)
    }
}

/** One command line with its copy button (web `CommandCopyRoot` + `CopyableCode`). */
@Composable
internal fun RaftComputerCommandLine(
    command: String,
    onCopy: (String) -> Unit,
    label: String? = null,
    copyLabel: String = stringResource(MR.strings.settings_computers_copy_command),
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) RaftComputerStepLabel(label)
        RaftCodeBlock(
            text = command,
            action = {
                RaftIconButton(
                    onClick = { onCopy(command) },
                    tooltip = copyLabel,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Xs,
                ) {
                    RaftComputersIcon(RaftLucideIconName.Copy)
                }
            },
        )
    }
}

/**
 * Web `ComputerManualInstall`: the Add Computer command(s) for this machine's platform (one line on
 * mac/Linux production, the two steps otherwise), then the install guide.
 */
@Composable
private fun RaftComputerManualInstall(context: RaftComputerControlsContext) {
    val commands = context.commands
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (commands != null) {
            val oneLine = commands.installAndSetup.takeIf { context.platform == RaftComputerCommandPlatform.MacLinux }
            if (oneLine != null) {
                RaftComputerCommandLine(oneLine, context.onCopy)
            } else {
                RaftComputerCommandLine(commands.install, context.onCopy, label = stringResource(MR.strings.settings_computers_install_step))
                RaftComputerCommandLine(commands.setup, context.onCopy, label = stringResource(MR.strings.settings_computers_setup_step))
            }
        }
        RaftTextLink(
            text = stringResource(MR.strings.settings_computers_install_guide),
            onClick = context.onOpenInstallGuide,
            variant = RaftTextLinkVariant.Strong,
            style = RaftTheme.typography.footnote,
        )
    }
}

/** Web `ComputerReleaseNotesAffordance`: a "What's new" link opening the notes in the UI language. */
@Composable
internal fun RaftComputerReleaseNotesLink(notes: SettingsComputerReleaseNotesSnapshot) {
    val languageTag = remember { build.raft.app.data.runtimeFormRequestLanguageTag() }
    val text = raftComputerReleaseNotesText(notes, languageTag) ?: return
    var open by remember(notes.version) { mutableStateOf(false) }
    RaftTextLink(
        text = stringResource(MR.strings.settings_computers_release_notes_open),
        onClick = { open = true },
        variant = RaftTextLinkVariant.Strong,
        style = RaftTheme.typography.footnote,
    )
    if (open) {
        RaftDialog(
            onDismissRequest = { open = false },
            title = stringResource(MR.strings.settings_computers_release_notes_title, notes.version),
            closeLabel = stringResource(MR.strings.common_close),
        ) {
            RaftMarkdownBody(body = text, modifier = Modifier.fillMaxWidth())
        }
    }
}

// endregion

// region Version notice

/**
 * Web `ComputerVersionNotice`: the always-on warning when the Computer is below the version floor,
 * with the card's one-click upgrade (only when the card offers it) or the manual install.
 */
@Composable
internal fun RaftComputerVersionNotice(context: RaftComputerControlsContext) {
    val tier = context.upgrade.tier as? RaftComputerVersionTier.Notice ?: return
    val computerVersion = context.computer.computerVersion?.trim()?.takeIf { it.isNotBlank() }
    val date = tier.enforcedAt?.let { raftRelativeTimestampLabel(it) }?.takeIf { it.isNotBlank() }
    val unknown = tier.reason == "computer_version_unknown"
    val title =
        when {
            tier.enforced && unknown -> stringResource(MR.strings.settings_computers_version_notice_enforced_title_unknown)
            tier.enforced -> stringResource(MR.strings.settings_computers_version_notice_enforced_title)
            !tier.reinstall && date != null -> stringResource(MR.strings.settings_computers_version_notice_upgrade_title_scheduled, date)
            !tier.reinstall -> stringResource(MR.strings.settings_computers_version_notice_upgrade_title)
            date != null -> stringResource(MR.strings.settings_computers_version_notice_reinstall_title_scheduled, date)
            unknown -> stringResource(MR.strings.settings_computers_version_notice_unknown_title)
            else -> stringResource(MR.strings.settings_computers_version_notice_reinstall_title)
        }
    val body =
        when {
            !tier.reinstall ->
                stringResource(MR.strings.settings_computers_version_notice_upgrade_body, computerVersion.orEmpty(), tier.minVersion)
            unknown || computerVersion == null -> stringResource(MR.strings.settings_computers_version_notice_unknown_body)
            else -> stringResource(MR.strings.settings_computers_version_notice_reinstall_body, computerVersion)
        }
    val askAdmin =
        if (context.canManage) {
            null
        } else if (tier.reinstall) {
            stringResource(MR.strings.settings_computers_version_notice_ask_admin_reinstall)
        } else {
            stringResource(MR.strings.settings_computers_version_notice_ask_admin_upgrade)
        }
    val oneClick = (context.upgrade.card as? RaftComputerCardState.OneClick)?.version?.takeIf { !tier.reinstall && context.canManage }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftBanner(
            description = listOfNotNull(body, askAdmin).joinToString("\n"),
            status = RaftBannerStatus.Warning,
            title = title,
            action =
                oneClick?.let { version ->
                    {
                        RaftButton(
                            text = stringResource(MR.strings.settings_computers_upgrade_to_version, version),
                            onClick = { context.onUpgrade(version) },
                        )
                    }
                },
        )
        if (context.canManage && (tier.reinstall || context.upgrade.card is RaftComputerCardState.ManualUpgrade)) {
            RaftComputerManualInstall(context)
        }
    }
}

// endregion

// region Connection (offline / legacy daemon)

/**
 * The connection blocks between Info and Agents (web: the legacy daemon's migrate block, its
 * admin-only note, the upgrade's reconnect banner and the offline recovery card). Nothing when the
 * Computer is online and up to date.
 */
@Composable
internal fun RaftComputerConnectionSection(context: RaftComputerControlsContext) {
    val computer = context.computer
    val reconnect = context.upgrade.reconnect
    val migrate = !computer.isComputer && context.canManage && context.commands != null
    val daemonAdminOnly = !computer.isComputer && !context.canManage && !computer.online
    val bringOnline = computer.isComputer && !computer.online && !reconnect.reconnecting
    if (!migrate && !daemonAdminOnly && !reconnect.reconnecting && !bringOnline) return
    Column(Modifier.fillMaxWidth()) {
        RaftSeparator()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (migrate) RaftComputerMigrateBlock(context)
            if (daemonAdminOnly) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftComputerEyebrow(stringResource(MR.strings.settings_computers_connection))
                    RaftComputerHelp(stringResource(MR.strings.settings_computers_connect_commands_admin_only))
                }
            }
            if (reconnect.reconnecting) {
                val version = reconnect.inFlight?.targetVersion.orEmpty()
                val status = context.commands?.status ?: RAFT_COMPUTER_STATUS_COMMAND
                val title = stringResource(MR.strings.settings_computers_upgrade_status_reconnecting, version)
                // Admins get the command to check with; everyone else the status sentence alone.
                RaftBanner(
                    description =
                        if (context.canManage) {
                            stringResource(MR.strings.settings_computers_upgrade_status_reconnecting_hint, status)
                        } else {
                            title
                        },
                    status = RaftBannerStatus.Info,
                    title = title.takeIf { context.canManage },
                )
            }
            if (bringOnline) RaftComputerBringOnlineBlock(context)
        }
    }
}

@Composable
private fun RaftComputerMigrateBlock(context: RaftComputerControlsContext) {
    val commands = context.commands ?: return
    val windows = context.platform == RaftComputerCommandPlatform.Windows
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftComputerEyebrow(
            stringResource(
                MR.strings.settings_computers_migrate_to_computer,
                if (windows) stringResource(MR.strings.settings_computers_windows_x64_suffix) else "",
            )
        )
        RaftComputerHelp(
            stringResource(
                MR.strings.settings_computers_migrate_detail,
                if (windows) {
                    stringResource(MR.strings.settings_computers_windows_powershell)
                } else {
                    stringResource(MR.strings.settings_computers_mac_linux)
                },
            )
        )
        RaftComputerCommandLine(commands.install, context.onCopy, label = stringResource(MR.strings.settings_computers_install_step))
        RaftComputerCommandLine(commands.setup, context.onCopy, label = stringResource(MR.strings.settings_computers_setup_step))
    }
}

/** Web `computer-recovery-card`: an offline managed Computer's way back, admin-only. */
@Composable
private fun RaftComputerBringOnlineBlock(context: RaftComputerControlsContext) {
    val computer = context.computer
    val upgrade = context.upgrade
    val lastSeen = computer.lastHeartbeat?.let { raftRelativeTimestampLabel(it) }?.takeIf { it.isNotBlank() }
    var showSetup by remember(computer.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RaftComputerEyebrow(stringResource(MR.strings.settings_computers_bring_online), RaftLucideIconName.Terminal)
        if (upgrade.reconnect.overdue) {
            RaftComputerProblem(
                stringResource(MR.strings.settings_computers_upgrade_status_reconnect_overdue, upgrade.reconnect.inFlight?.targetVersion.orEmpty())
            )
        }
        if (!context.canManage) {
            val parenthetical = lastSeen?.let { stringResource(MR.strings.settings_computers_last_seen_parenthetical, it).trim() }.orEmpty()
            RaftComputerHelp(stringResource(MR.strings.settings_computers_offline_admin_only, parenthetical))
            return@Column
        }
        val commands = context.commands
        val status = commands?.status ?: RAFT_COMPUTER_STATUS_COMMAND
        upgrade.requestOutcome?.let { outcome ->
            RaftComputerProblem(
                if (outcome.state == "failed") {
                    stringResource(MR.strings.settings_computers_upgrade_status_failed, computer.computerVersion.orEmpty(), status)
                } else {
                    stringResource(MR.strings.settings_computers_upgrade_status_no_response, status)
                }
            )
        }
        if (upgrade.requestOutcome != null || upgrade.reconnect.overdue) {
            Column {
                RaftComputerHelp(stringResource(MR.strings.settings_computers_upgrade_status_manual_fallback))
                RaftComputerManualInstall(context)
            }
        }
        if (commands != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftComputerHelp(
                    stringResource(MR.strings.settings_computers_bring_online_restart_detail, context.commandContext?.serverSlug?.trim()?.trimStart('/').orEmpty())
                )
                RaftComputerCommandLine(commands.restart, context.onCopy)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftComputerHelp(stringResource(MR.strings.settings_computers_offline_diagnostics))
                RaftComputerCommandLine(commands.status, context.onCopy)
                RaftComputerCommandLine(commands.doctor, context.onCopy)
            }
        }
        if (lastSeen != null) RaftComputerHelp(raftComputerLastSeenSentence(lastSeen))
        if (commands != null) {
            RaftSeparator()
            RaftTextLink(
                text =
                    if (showSetup) {
                        stringResource(MR.strings.settings_computers_hide_install_setup)
                    } else {
                        stringResource(MR.strings.settings_computers_command_not_found_setup)
                    },
                onClick = { showSetup = !showSetup },
                style = RaftTheme.typography.footnote,
            )
            if (showSetup) {
                val windows = context.platform == RaftComputerCommandPlatform.Windows
                RaftComputerHelp(
                    stringResource(MR.strings.settings_computers_install_setup_detail) +
                        if (windows) " " + stringResource(MR.strings.settings_computers_windows_installer_x64_only) else ""
                )
                RaftComputerCommandLine(commands.install, context.onCopy, label = stringResource(MR.strings.settings_computers_install_step))
                RaftComputerCommandLine(commands.setup, context.onCopy, label = stringResource(MR.strings.settings_computers_setup_step))
            }
        }
    }
}

@Composable
private fun raftComputerLastSeenSentence(lastSeen: String): String = stringResource(MR.strings.settings_computers_last_seen, lastSeen)

// endregion

// region Actions: Computer card and recovery guide

/**
 * Web `computer-service-actions`: a managed, online Computer's card. One status sentence and exactly
 * one next step (Restart, Upgrade, or the commands to run), then "verify from this computer's terminal".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RaftComputerServiceCard(context: RaftComputerControlsContext) {
    val computer = context.computer
    val upgrade = context.upgrade
    val card = upgrade.card
    val commands = context.commands
    val status = commands?.status ?: RAFT_COMPUTER_STATUS_COMMAND
    RaftCard(title = stringResource(MR.strings.settings_computers_detail_computer)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val restart = context.restart?.takeIf { it.machineId == computer.id }
            if (restart != null) {
                RaftComputerRestartProgressLine(restart)
            } else {
                RaftComputerHelp(raftComputerCardSentence(card, upgrade.tier))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (card !is RaftComputerCardState.Upgrading) {
                        RaftButton(
                            text = stringResource(MR.strings.settings_computers_restart),
                            onClick = context.onRestart,
                            leadingIcon = { RaftComputersIcon(RaftLucideIconName.RotateCcw) },
                        )
                    }
                    if (card is RaftComputerCardState.UpToDate && upgrade.webUpgradeOn) {
                        RaftButton(
                            text = stringResource(MR.strings.settings_computers_up_to_date),
                            onClick = {},
                            enabled = false,
                            leadingIcon = { RaftComputersIcon(RaftLucideIconName.CircleCheck) },
                        )
                    }
                    if (card is RaftComputerCardState.OneClick) {
                        // The one pink action on this page: the upgrade the computer is waiting for.
                        RaftButton(
                            text = stringResource(MR.strings.settings_computers_upgrade_to_version, card.version),
                            onClick = { context.onUpgrade(card.version) },
                            variant = RaftButtonVariant.Primary,
                            leadingIcon = { RaftComputersIcon(RaftLucideIconName.Play) },
                        )
                    }
                    if (card is RaftComputerCardState.Upgrading) {
                        RaftButton(
                            text = stringResource(MR.strings.settings_computers_upgrading),
                            onClick = {},
                            loading = true,
                            loadingLabel = stringResource(MR.strings.settings_computers_upgrading),
                        )
                    }
                }
                upgrade.releaseNotes?.let { RaftComputerReleaseNotesLink(it) }
                val outcome =
                    when {
                        card is RaftComputerCardState.OneClick && upgrade.refusal == RaftComputerUpgradeRefusal.Unknown ->
                            stringResource(MR.strings.settings_computers_upgrade_status_refused_unknown)
                        card !is RaftComputerCardState.Upgrading && card !is RaftComputerCardState.UpToDate && upgrade.requestOutcome != null ->
                            if (upgrade.requestOutcome?.state == "failed") {
                                stringResource(MR.strings.settings_computers_upgrade_status_failed, computer.computerVersion.orEmpty(), status)
                            } else {
                                stringResource(MR.strings.settings_computers_upgrade_status_no_response, status)
                            }
                        else -> null
                    }
                if (outcome != null) RaftComputerProblem(outcome)
                if (outcome != null && card is RaftComputerCardState.OneClick) {
                    Column {
                        RaftComputerHelp(stringResource(MR.strings.settings_computers_upgrade_status_manual_fallback))
                        RaftComputerManualInstall(context)
                    }
                }
                if (card is RaftComputerCardState.ManualUpgrade && upgrade.tier is RaftComputerVersionTier.None) {
                    RaftComputerManualInstall(context)
                }
                val fresh = context.commands
                if (card is RaftComputerCardState.Commands && fresh != null) {
                    RaftComputerCommandLine(fresh.install, context.onCopy, label = stringResource(MR.strings.settings_computers_upgrade_install_step))
                    RaftComputerCommandLine(
                        fresh.restartService,
                        context.onCopy,
                        label = stringResource(MR.strings.settings_computers_upgrade_restart_step),
                    )
                }
            }
            if (commands != null) {
                RaftSeparator()
                RaftComputerEyebrow(stringResource(MR.strings.settings_computers_verify_title), RaftLucideIconName.Terminal)
                RaftComputerHelp(stringResource(MR.strings.settings_computers_verify_detail))
                RaftComputerCommandLine(commands.status, context.onCopy)
                RaftComputerCommandLine(commands.doctor, context.onCopy)
                RaftComputerHelp(stringResource(MR.strings.settings_computers_web_buttons_not_responding))
                RaftComputerCommandLine(commands.restart, context.onCopy)
            }
        }
    }
}

/** The card's status sentence for [card] (web `statusSentence`). */
@Composable
private fun raftComputerCardSentence(card: RaftComputerCardState, tier: RaftComputerVersionTier): String =
    when (card) {
        RaftComputerCardState.Reinstall -> stringResource(MR.strings.settings_computers_upgrade_status_reinstall)
        RaftComputerCardState.Reading -> stringResource(MR.strings.settings_computers_upgrade_status_reading)
        RaftComputerCardState.CannotCheck -> stringResource(MR.strings.settings_computers_upgrade_status_cannot_check)
        RaftComputerCardState.UpToDate -> stringResource(MR.strings.settings_computers_upgrade_status_up_to_date)
        is RaftComputerCardState.Upgrading -> stringResource(MR.strings.settings_computers_upgrade_status_in_progress, card.version)
        is RaftComputerCardState.OneClick -> stringResource(MR.strings.settings_computers_upgrade_status_available, card.version)
        is RaftComputerCardState.ManualUpgrade ->
            if (tier is RaftComputerVersionTier.None) {
                stringResource(MR.strings.settings_computers_upgrade_status_manual, card.version)
            } else {
                // With the version notice up, its copy of the command is the one to use.
                stringResource(MR.strings.settings_computers_upgrade_status_manual_above, card.version)
            }
        is RaftComputerCardState.Commands ->
            if (card.refusedNotAllowed) {
                stringResource(MR.strings.settings_computers_upgrade_status_refused_not_allowed)
            } else {
                stringResource(MR.strings.settings_computers_upgrade_status_run_commands, card.version)
            }
    }

/** A restart in flight (spinner) or just finished (restarted / why it failed). */
@Composable
private fun RaftComputerRestartProgressLine(restart: SettingsComputerRestartProgress) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!restart.done) {
            RaftSpinner(size = RaftSpinnerSize.Sm)
            RaftText(
                stringResource(MR.strings.settings_computers_restarting),
                style = RaftTheme.typography.monoMeta,
                color = RaftTheme.colors.foregroundMuted,
            )
        } else {
            val error = restart.errorCode
            RaftComputersIcon(if (error != null) RaftLucideIconName.TriangleAlert else RaftLucideIconName.CircleCheck, size = 14.dp)
            RaftText(
                if (error == null) stringResource(MR.strings.settings_computers_restarted) else raftComputerRestartErrorText(restart),
                style = RaftTheme.typography.monoMeta,
                color = if (error != null) RaftTheme.colors.warningStrong else RaftTheme.colors.foregroundMuted,
            )
        }
    }
}

/**
 * Web `handleComputerRestart` / `completeComputerRestart` copy: the route's refusal (no request id
 * yet) reads as "couldn't request", the Computer's own `restart_failed` as "Restart failed", any
 * other Computer error as it reported it.
 */
@Composable
private fun raftComputerRestartErrorText(restart: SettingsComputerRestartProgress): String =
    when (val code = restart.errorCode) {
        COMPUTER_RESTART_TIMED_OUT -> stringResource(MR.strings.settings_computers_restart_timed_out)
        "computer_offline" -> stringResource(MR.strings.settings_computers_restart_computer_offline)
        "restart_failed", null -> stringResource(MR.strings.settings_computers_restart_failed)
        else ->
            if (restart.requestId == null) stringResource(MR.strings.settings_computers_restart_request_failed) else code.orEmpty()
    }

/**
 * Web `computer-recovery-guide`: restart, fresh install, restart after install. Collapsed while the
 * Computer is online, open while it is offline, until the user toggles it (for this machine and state).
 */
@Composable
internal fun RaftComputerRecoveryGuideCard(context: RaftComputerControlsContext) {
    val commands = context.commands ?: return
    val fresh = context.commands ?: return
    val computer = context.computer
    var disclosure by remember(computer.id, computer.online) { mutableStateOf<Boolean?>(null) }
    val open = disclosure ?: !computer.online
    val windows = context.platform == RaftComputerCommandPlatform.Windows
    RaftCard(
        title = stringResource(MR.strings.settings_computers_recovery_guide),
        leading = { RaftComputersIcon(RaftLucideIconName.Terminal) },
        trailing = {
            RaftIconButton(
                onClick = { disclosure = !open },
                tooltip =
                    if (open) {
                        stringResource(MR.strings.settings_computers_recovery_guide_hide)
                    } else {
                        stringResource(MR.strings.settings_computers_recovery_guide_show)
                    },
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Xs,
            ) {
                RaftComputersIcon(if (open) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight)
            }
        },
        content =
            if (open) {
                {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RaftComputerHelp(stringResource(MR.strings.settings_computers_recovery_guide_detail))
                        RaftComputerRecoveryStep(
                            label = stringResource(MR.strings.settings_computers_recovery_restart_step),
                            detail = stringResource(MR.strings.settings_computers_recovery_restart_step_detail),
                            command = commands.restart,
                            onCopy = context.onCopy,
                        )
                        RaftComputerRecoveryStep(
                            label =
                                stringResource(
                                    MR.strings.settings_computers_recovery_fresh_install_step,
                                    if (windows) stringResource(MR.strings.settings_computers_windows_x64_suffix) else "",
                                ),
                            detail = stringResource(MR.strings.settings_computers_recovery_fresh_install_detail),
                            command = fresh.install,
                            onCopy = context.onCopy,
                        )
                        RaftComputerRecoveryStep(
                            label = stringResource(MR.strings.settings_computers_recovery_restart_after_install_step),
                            detail = stringResource(MR.strings.settings_computers_recovery_restart_after_install_detail),
                            command = fresh.restartService,
                            onCopy = context.onCopy,
                        )
                    }
                }
            } else {
                null
            },
    )
}

@Composable
private fun RaftComputerRecoveryStep(label: String, detail: String, command: String, onCopy: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            RaftComputerStepLabel(label)
            RaftComputerHelp(detail)
        }
        RaftComputerCommandLine(command, onCopy)
    }
}

// endregion
