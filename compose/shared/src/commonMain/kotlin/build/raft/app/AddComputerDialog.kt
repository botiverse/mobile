package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.data.AddComputerDialogState
import build.raft.app.data.AddComputerStep
import build.raft.app.data.AddComputerType
import build.raft.app.data.SettingsComputerRegisterRequest
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCheckboxRow
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import com.tencent.tmm.kmmresource.compose.stringResource

/*
 * Add Computer, after web AddMachineDialog.tsx and ComputerCommandGuide.tsx (botiverse/slock
 * staging): on a phone the raft-ui Dialog "On a phone" sheet (`DialogCard narrow="sheet"`), × at
 * the start of its head and the step's one commit button at the end; on a tablet the centred
 * dialog. Three steps: the type, the `raft-computer` connect commands while waiting, and naming
 * the computer that connected.
 */

/** Web `WAITING_HINT_DELAY_MS`: after this long, point the user at the terminal. */
private const val AddComputerWaitingHintDelayMs = 45_000L

/** The sheet's head for the current step: its title and its one commit button. */
private class AddComputerSheetHead(
    val title: String,
    val commitLabel: String,
    val commitEnabled: Boolean,
    /** Connected (and confirmed when asked): × finishes instead of cancelling, as on the web. */
    val canFinish: Boolean,
)

@Composable
private fun addComputerSheetHead(state: AddComputerDialogState): AddComputerSheetHead {
    val canFinish = state.step == AddComputerStep.Connected && (!state.requiresConfirmation || state.confirmedMatch)
    return AddComputerSheetHead(
        title =
            when (state.step) {
                AddComputerStep.Type -> stringResource(MR.strings.settings_computers_add_computer)
                AddComputerStep.Waiting -> stringResource(MR.strings.settings_computers_dialog_title_connect)
                AddComputerStep.Connected -> stringResource(MR.strings.settings_computers_dialog_title_connected)
            },
        commitLabel =
            when {
                state.step == AddComputerStep.Type && state.registering -> stringResource(MR.strings.settings_computers_setting_up)
                state.step == AddComputerStep.Type -> stringResource(MR.strings.settings_computers_next)
                state.step == AddComputerStep.Connected && state.saving -> stringResource(MR.strings.settings_computers_action_saving)
                else -> stringResource(MR.strings.common_done)
            },
        commitEnabled =
            when (state.step) {
                AddComputerStep.Type -> state.canManage && !state.atLimit && !state.registering
                // Web: Done waits, disabled, until the computer connects.
                AddComputerStep.Waiting -> false
                AddComputerStep.Connected -> canFinish && !state.saving
            },
        canFinish = canFinish,
    )
}

private fun addComputerCommit(state: AddComputerDialogState, onIntent: (SharedSettingsIntent) -> Unit) {
    when (state.step) {
        AddComputerStep.Type -> onIntent(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest(name = "my-computer")))
        AddComputerStep.Waiting -> Unit
        AddComputerStep.Connected -> onIntent(SharedSettingsIntent.CompleteAddComputerDialog)
    }
}

@Composable
internal fun AddComputerDialog(
    state: AddComputerDialogState,
    onIntent: (SharedSettingsIntent) -> Unit
) {
    // The dialog sits outside the page's design-system theme.
    RaftDesignSystemTheme {
        val head = addComputerSheetHead(state)
        RaftDialogSheet(
            onDismissRequest = {
                if (state.registering || state.saving) return@RaftDialogSheet
                onIntent(if (head.canFinish) SharedSettingsIntent.CompleteAddComputerDialog else SharedSettingsIntent.DismissAddComputerDialog)
            },
            title = head.title,
            commitLabel = head.commitLabel,
            commitEnabled = head.commitEnabled,
            onCommit = { addComputerCommit(state, onIntent) },
            closeLabel = stringResource(MR.strings.common_close),
            cancelLabel = stringResource(MR.strings.common_cancel),
        ) {
            AddComputerSheetBody(state, onIntent)
        }
    }
}

/** The phone sheet's surface without its window, for screenshot fixtures. */
@Composable
internal fun AddComputerSheetSurfaceForFixture(state: AddComputerDialogState, modifier: Modifier = Modifier) {
    val head = addComputerSheetHead(state)
    RaftDialogSheetSurface(
        title = head.title,
        commitLabel = head.commitLabel,
        commitEnabled = head.commitEnabled,
        onCommit = {},
        onClose = {},
        modifier = modifier,
        closeLabel = stringResource(MR.strings.common_close),
    ) {
        AddComputerSheetBody(state, onIntent = {})
    }
}

@Composable
private fun ColumnScope.AddComputerSheetBody(state: AddComputerDialogState, onIntent: (SharedSettingsIntent) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (state.step) {
            AddComputerStep.Type -> AddComputerTypeStep(state, onIntent)
            AddComputerStep.Waiting -> AddComputerWaitingStep(state, onIntent)
            AddComputerStep.Connected -> AddComputerConnectedStep(state, onIntent)
        }
    }
}

@Composable
private fun AddComputerTypeStep(
    state: AddComputerDialogState,
    onIntent: (SharedSettingsIntent) -> Unit
) {
    val userFacingError = state.userFacingErrorMessage(localizedErrorPresentationStrings())
    // Web: one warning Banner for the limit or a failed registration.
    val warning =
        if (!state.canManage || state.atLimit) {
            userFacingError ?: stringResource(MR.strings.settings_computers_error_add_permission)
        } else {
            userFacingError?.takeIf { it.isNotBlank() }
        }
    if (warning != null) RaftBanner(description = warning, status = RaftBannerStatus.Warning)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AddComputerTypeCard(
            title = stringResource(MR.strings.settings_computers_your_computer),
            detail = stringResource(MR.strings.settings_computers_your_computer_hint),
            iconName = RaftLucideIconName.Monitor,
            selected = state.machineType == AddComputerType.Local,
            enabled = true,
            onClick = { onIntent(SharedSettingsIntent.SetAddComputerType(AddComputerType.Local)) },
            modifier = Modifier.weight(1f)
        )
        AddComputerTypeCard(
            title = stringResource(MR.strings.settings_computers_cloud_computer),
            detail = stringResource(MR.strings.settings_computers_coming_soon),
            iconName = RaftLucideIconName.Server,
            selected = false,
            enabled = false,
            onClick = {},
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AddComputerWaitingStep(
    state: AddComputerDialogState,
    onIntent: (SharedSettingsIntent) -> Unit
) {
    val userFacingError = state.userFacingErrorMessage(localizedErrorPresentationStrings())
    LaunchedEffect(state.step, state.registeredMachineId) {
        val targetId = state.registeredMachineId ?: state.machineId ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(3_000)
            onIntent(SharedSettingsIntent.PollAddComputerMachineStatus(targetId))
        }
    }
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(AddComputerWaitingHintDelayMs)
        stalled = true
    }
    AddComputerCommandGuide(state, onCopy = { onIntent(SharedSettingsIntent.CopyComputerCommand(it)) })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Web: an info Banner whose spinner takes the icon's place while the computer connects.
        RaftBanner(
            description = addComputerBold(stringResource(MR.strings.settings_computers_waiting_for_connection)),
            status = RaftBannerStatus.Info,
            pending = true,
        )
        if (stalled) {
            RaftText(
                stringResource(MR.strings.settings_computers_still_waiting_hint),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundMuted,
            )
        }
    }
    userFacingError?.takeIf { it.isNotBlank() }?.let { RaftBanner(description = it, status = RaftBannerStatus.Warning) }
}

/** Web `ComputerCommandGuide`: the connect command for macOS / Linux or Windows x64. */
@Composable
private fun AddComputerCommandGuide(state: AddComputerDialogState, onCopy: (String) -> Unit) {
    var platform by remember { mutableStateOf(RaftComputerCommandPlatform.MacLinux) }
    var showConnectOnly by remember { mutableStateOf(false) }
    val macLinux = raftComputerCommands(state.commandContext, RaftComputerCommandPlatform.MacLinux)
    val windows = raftComputerCommands(state.commandContext, RaftComputerCommandPlatform.Windows)
    val commands = if (platform == RaftComputerCommandPlatform.Windows) windows else macLinux
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // On a phone the eyebrow and the platform switch stack (web `flex-col sm:flex-row`).
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AddComputerIcon(RaftLucideIconName.Terminal, muted = true)
                RaftText(
                    stringResource(MR.strings.settings_computers_connect_command),
                    style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
                    color = RaftTheme.colors.foregroundHint,
                )
            }
            RaftSegmentedControl {
                RaftSegment(
                    text = stringResource(MR.strings.settings_computers_mac_linux),
                    selected = platform == RaftComputerCommandPlatform.MacLinux,
                    onClick = { platform = RaftComputerCommandPlatform.MacLinux },
                )
                RaftSegment(
                    text = stringResource(MR.strings.settings_computers_windows_x64),
                    selected = platform == RaftComputerCommandPlatform.Windows,
                    onClick = { platform = RaftComputerCommandPlatform.Windows },
                )
            }
        }
        RaftText(
            when {
                commands == null -> stringResource(MR.strings.settings_computers_generate_fresh_connect)
                platform == RaftComputerCommandPlatform.Windows -> stringResource(MR.strings.settings_computers_windows_computer_description)
                else -> stringResource(MR.strings.settings_computers_mac_linux_computer_description)
            },
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundMuted,
        )
        if (commands == null) {
            AddComputerCommandPlaceholder(stringResource(MR.strings.settings_computers_generate_fresh_connect))
            return@Column
        }
        if (platform == RaftComputerCommandPlatform.Windows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftText(
                    stringResource(MR.strings.settings_computers_raft_computer_windows_x64),
                    style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
                    color = RaftTheme.colors.foreground,
                )
                RaftBadge(text = stringResource(MR.strings.settings_computers_badge_experimental), variant = RaftBadgeVariant.Success)
            }
        }
        // mac/Linux production: one install-then-setup line; otherwise the two steps.
        val oneLine = commands.installAndSetup.takeIf { platform == RaftComputerCommandPlatform.MacLinux }
        if (oneLine != null) {
            AddComputerCommandRow(oneLine, onCopy)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftTextLink(
                    text = stringResource(MR.strings.settings_computers_already_installed),
                    onClick = { showConnectOnly = !showConnectOnly },
                    style = RaftTheme.typography.footnote,
                )
                if (showConnectOnly) {
                    RaftText(
                        stringResource(MR.strings.settings_computers_connect_only_description),
                        style = RaftTheme.typography.footnote,
                        color = RaftTheme.colors.foregroundMuted,
                    )
                    AddComputerCommandRow(commands.setup, onCopy)
                }
            }
        } else {
            AddComputerCommandRow(commands.install, onCopy, label = stringResource(MR.strings.settings_computers_install_step))
            AddComputerCommandRow(commands.setup, onCopy, label = stringResource(MR.strings.settings_computers_setup_step))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddComputerConnectedStep(
    state: AddComputerDialogState,
    onIntent: (SharedSettingsIntent) -> Unit
) {
    // Caller-local TextFieldValue is the sole editor owner; the durable store
    // keeps a String projection (task #130 Phase D). Seeded once per mount —
    // this step is only composed for AddComputerStep.Connected, so the mount
    // itself is the session boundary and no external event re-seeds it (a seed
    // keyed on the live store String would reset the caret on every keystroke).
    var nameValue by remember {
        mutableStateOf(TextFieldValue(state.name, selection = TextRange(state.name.length)))
    }
    val detail = state.connectedDetail?.takeIf { it.isNotBlank() }
    val monoMeta = RaftTheme.typography.monoMeta
    val connectedTitle = stringResource(MR.strings.settings_computers_connected_success)
    // Web: the success Banner's title, and "hostname — os" in mono under it when reported.
    if (detail == null) {
        RaftBanner(description = addComputerBold(connectedTitle), status = RaftBannerStatus.Success)
    } else {
        RaftBanner(
            description = buildAnnotatedString { withStyle(monoMeta.toSpanStyle()) { append(detail) } },
            title = connectedTitle,
            status = RaftBannerStatus.Success,
        )
    }
    RaftField(
        label = stringResource(MR.strings.settings_computers_label_name),
        description = stringResource(MR.strings.settings_computers_name_hint),
        enabled = !state.saving,
    ) {
        RaftTextField(
            value = nameValue,
            keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
            onValueChange = {
                nameValue = it
                onIntent(SharedSettingsIntent.SetAddComputerName(it.text))
            },
            placeholder = state.connectedHostname ?: stringResource(MR.strings.settings_computers_placeholder_name),
            enabled = !state.saving,
        )
    }
    if (state.connectedRuntimes.isNotEmpty()) {
        // Web KeyValueRow "Detected Runtimes": every runtime, cyan-soft when detected.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RaftText(
                stringResource(MR.strings.settings_computers_label_runtimes),
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                color = RaftTheme.colors.foregroundMuted,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                raftComputerRuntimeChips(state.connectedRuntimes).forEach { chip -> AddComputerRuntimeBadge(chip) }
            }
        }
    }
    if (state.requiresConfirmation) {
        val hostname = state.connectedHostname?.let { " ($it)" }.orEmpty()
        RaftCheckboxRow(
            label = stringResource(MR.strings.settings_computers_confirm_match, hostname),
            checked = state.confirmedMatch,
            onCheckedChange = { onIntent(SharedSettingsIntent.SetAddComputerConfirmedMatch(it)) },
            enabled = !state.saving,
        )
    }
}

@Composable
private fun AddComputerRuntimeBadge(chip: RaftComputerRuntimeChip) {
    val suffix =
        when (chip.availability) {
            RaftComputerRuntimeAvailability.Detected -> ""
            RaftComputerRuntimeAvailability.NotInstalled -> stringResource(MR.strings.agent_create_suffix_not_installed)
            RaftComputerRuntimeAvailability.UpdateComputer -> stringResource(MR.strings.settings_computers_runtime_suffix_update_computer)
        }
    RaftBadge(
        text = chip.name + suffix,
        appearance = RaftBadgeAppearance.Soft,
        variant =
            if (chip.availability == RaftComputerRuntimeAvailability.Detected) RaftBadgeVariant.Information else RaftBadgeVariant.Muted,
    )
}

/**
 * Web Add Machine option card: `rounded-control`, a 1.5dp `line-strong` edge, `info-soft` when
 * picked; the unavailable option is the disabled surface (`fill-muted`, hairline, disabled ink).
 */
@Composable
private fun AddComputerTypeCard(
    title: String,
    detail: String,
    iconName: RaftLucideIconName,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    Row(
        modifier = modifier
            .background(
                when {
                    !enabled -> colors.fillMuted
                    selected -> colors.infoSoft
                    else -> colors.layerCard
                },
                shape,
            )
            .border(
                if (enabled) RaftStroke.Control else RaftStroke.Hairline,
                if (enabled) colors.lineStrong else colors.lineHairline,
                shape,
            )
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AddComputerIcon(iconName, size = 20, muted = enabled, disabled = !enabled)
        Column(modifier = Modifier.weight(1f)) {
            RaftText(
                title,
                style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) colors.foreground else colors.foregroundDisabled,
            )
            RaftText(
                detail,
                style = RaftTheme.typography.footnote,
                color = if (enabled) colors.foregroundMuted else colors.foregroundDisabled,
            )
        }
    }
}

/** Web `CommandRow`: an optional step label over the command and its copy button. */
@Composable
private fun AddComputerCommandRow(command: String, onCopy: (String) -> Unit, label: String? = null) {
    var copied by remember(command) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2_000)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) {
            RaftText(
                label,
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = RaftTheme.colors.foreground,
            )
        }
        RaftCodeBlock(
            text = command,
            action = {
                RaftIconButton(
                    onClick = {
                        onCopy(command)
                        copied = true
                    },
                    tooltip = stringResource(if (copied) MR.strings.common_copied else MR.strings.settings_computers_copy_command),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Md,
                ) {
                    AddComputerIcon(if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy)
                }
            },
        )
    }
}

/** Web `COMMAND_PLACEHOLDER_CLASS_NAME`: where the command would be when there is none yet. */
@Composable
private fun AddComputerCommandPlaceholder(text: String) {
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        Modifier
            .fillMaxWidth()
            .border(RaftStroke.Hairline, RaftTheme.colors.lineSeam, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        RaftText(text, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundHint)
    }
}

private fun addComputerBold(text: String): AnnotatedString =
    buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) } }

@Composable
private fun AddComputerIcon(name: RaftLucideIconName, size: Int = 16, muted: Boolean = false, disabled: Boolean = false) {
    val colors = RaftTheme.colors
    val ink =
        when {
            disabled -> colors.foregroundDisabled
            muted -> colors.foregroundMuted
            else -> colors.foreground
        }
    RaftLucideIcon(name, modifier = Modifier.size(size.dp), color = raftArgbHex(ink))
}
