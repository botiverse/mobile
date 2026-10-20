package build.raft.app

import build.raft.app.components.raftOptionSurface
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.components.RaftBrutalTextField
import build.raft.app.data.AddComputerCommandTarget
import build.raft.app.data.AddComputerDialogState
import build.raft.app.data.AddComputerStep
import build.raft.app.data.AddComputerType
import build.raft.app.data.SettingsComputerRegisterRequest
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.ui.unit.sp

@Composable
internal fun AddComputerDialog(
    state: AddComputerDialogState,
    onIntent: (SharedSettingsIntent) -> Unit
) {
    LaunchedEffect(state.copiedCommand) {
        if (state.copiedCommand != null) {
            kotlinx.coroutines.delay(2_000)
            onIntent(SharedSettingsIntent.ClearAddComputerCopiedCommand)
        }
    }

    val title = when (state.step) {
        AddComputerStep.Type -> stringResource(MR.strings.settings_computers_add_computer)
        AddComputerStep.Waiting -> stringResource(MR.strings.settings_computers_dialog_title_connect)
        AddComputerStep.Connected -> stringResource(MR.strings.settings_computers_dialog_title_connected)
    }
    val dismissIntent =
        if (state.step == AddComputerStep.Connected) {
            SharedSettingsIntent.CompleteAddComputerDialog
        } else {
            SharedSettingsIntent.DismissAddComputerDialog
        }
    RaftUnifiedDialog(
        title = title,
        onDismiss = { onIntent(dismissIntent) },
        dismissEnabled = !state.registering && !state.saving,
        maxWidth = 520.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22,
        contentSpacing = 12.dp,
        keyboardAvoidanceEnabled = true
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                when (state.step) {
                    AddComputerStep.Type -> AddComputerTypeStep(state, onIntent)
                    AddComputerStep.Waiting -> AddComputerWaitingStep(state, onIntent)
                    AddComputerStep.Connected -> AddComputerConnectedStep(state, onIntent)
                }
            }
        }
    }
}

@Composable
private fun AddComputerTypeStep(
    state: AddComputerDialogState,
    onIntent: (SharedSettingsIntent) -> Unit
) {
    val userFacingError = state.userFacingErrorMessage(localizedErrorPresentationStrings())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.canManage || state.atLimit) {
            Text(
                userFacingError ?: stringResource(MR.strings.settings_computers_error_add_permission),
                style = RaftType.Caption.copy(color = RaftColor.AccentRed),
                color = RaftColor.AccentRed
            )
        }
        Text(
            stringResource(MR.strings.settings_computers_choose_connection),
            style = RaftType.Body.copy(fontSize = 14.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddComputerTypeCard(
                title = stringResource(MR.strings.settings_computers_your_computer),
                detail = stringResource(MR.strings.settings_computers_your_computer_hint),
                iconName = RaftLucideIconName.Monitor,
                selected = state.machineType == AddComputerType.Local,
                enabled = state.canManage && !state.atLimit && !state.registering,
                onClick = { onIntent(SharedSettingsIntent.SetAddComputerType(AddComputerType.Local)) },
                modifier = Modifier.weight(1f)
            )
            AddComputerTypeCard(
                title = stringResource(MR.strings.settings_computers_cloud_computer),
                detail = stringResource(MR.strings.settings_computers_coming_soon),
                iconName = RaftLucideIconName.Server,
                selected = state.machineType == AddComputerType.Cloud,
                enabled = false,
                onClick = { onIntent(SharedSettingsIntent.SetAddComputerType(AddComputerType.Cloud)) },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            stringResource(MR.strings.settings_computers_install_daemon_hint),
            style = RaftType.Body.copy(fontSize = 14.sp, color = RaftColor.TextBlack),
            color = RaftColor.TextBlack
        )
        userFacingError?.takeIf { it.isNotBlank() && state.canManage && !state.atLimit }?.let {
            Text(it, style = RaftType.Caption.copy(color = RaftColor.AccentRed), color = RaftColor.AccentRed)
        }
        RaftDialogFooter {
            RaftBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                onClick = { onIntent(SharedSettingsIntent.DismissAddComputerDialog) },
                enabled = !state.registering,
                backgroundColor = RaftColor.SurfaceWhite
            )
            RaftBrutalActionButton(
                label = if (state.registering) {
                    stringResource(MR.strings.settings_computers_setting_up)
                } else {
                    stringResource(MR.strings.settings_computers_next)
                },
                onClick = {
                    onIntent(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest(name = "my-computer")))
                },
                enabled = state.canManage && !state.atLimit && !state.registering,
                leadingIcon = if (state.registering) RaftLucideIconName.LoaderCircle else RaftLucideIconName.Plus,
                leadingIconSpinning = state.registering,
                backgroundColor = RaftColor.AccentPink
            )
        }
    }
}

@Composable
private fun AddComputerWaitingStep(
    state: AddComputerDialogState,
    onIntent: (SharedSettingsIntent) -> Unit
) {
    val userFacingError = state.userFacingErrorMessage(localizedErrorPresentationStrings())
    LaunchedEffect(state.step, state.machineId) {
        val targetId = state.machineId ?: return@LaunchedEffect
        while (state.step == AddComputerStep.Waiting) {
            kotlinx.coroutines.delay(3_000)
            onIntent(SharedSettingsIntent.PollAddComputerMachineStatus(targetId))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RaftLucideIcon(RaftLucideIconName.Terminal, modifier = Modifier.size(16.dp))
            Text(
                stringResource(MR.strings.settings_computers_run_command_hint),
                style = RaftType.Body.copy(fontSize = 14.sp, color = RaftColor.TextBlack)
                    .withRaftFontWeight(FontWeight.Bold),
                color = RaftColor.TextBlack
            )
        }
        val daemonCommand = state.daemonRunCommand
        if (!daemonCommand.isNullOrBlank()) {
            AddComputerCommandRow(
                command = daemonCommand,
                copyTarget = AddComputerCommandTarget.Daemon,
                copied = state.copiedCommand == AddComputerCommandTarget.Daemon,
                onIntent = onIntent
            )
        } else {
            SettingsComputerCommandFallback(state)
        }
        val computerCommand = state.computerSetupCommand
        if (!computerCommand.isNullOrBlank()) {
            Text(
                stringResource(MR.strings.settings_computers_detail_computer),
                style = RaftType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 14.sp, color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray
            )
            AddComputerCommandRow(
                command = computerCommand,
                copyTarget = AddComputerCommandTarget.Computer,
                copied = state.copiedCommand == AddComputerCommandTarget.Computer,
                onIntent = onIntent
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(RaftColor.SurfaceCream)
                .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RaftStatusIndicator(color = RaftColor.BrandYellow)
            Text(
                stringResource(MR.strings.settings_computers_waiting_for_connection),
                style = RaftType.Body.copy(fontSize = 14.sp, color = RaftColor.TextBlack)
                    .withRaftFontWeight(FontWeight.Bold),
                color = RaftColor.TextBlack
            )
        }
        state.apiKeyPrefix?.takeIf { it.isNotBlank() }?.let {
            Text(
                stringResource(MR.strings.settings_computers_api_key_prefix, it),
                style = RaftType.Caption.copy(fontSize = 12.sp, color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray
            )
        }
        userFacingError?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = RaftType.Caption.copy(color = RaftColor.AccentRed), color = RaftColor.AccentRed)
        }
        RaftDialogFooter {
            RaftBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                onClick = { onIntent(SharedSettingsIntent.DismissAddComputerDialog) },
                backgroundColor = RaftColor.SurfaceWhite
            )
            RaftBrutalActionButton(
                label = stringResource(MR.strings.common_done),
                onClick = {},
                enabled = false,
                backgroundColor = RaftColor.AccentLime
            )
        }
    }
}

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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(RaftColor.AccentLime)
                .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RaftLucideIcon(RaftLucideIconName.CircleCheckBig, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(MR.strings.settings_computers_connected_success),
                    style = RaftType.Body.copy(fontSize = 14.sp, color = RaftColor.TextBlack)
                        .withRaftFontWeight(FontWeight.Bold),
                    color = RaftColor.TextBlack
                )
                state.connectedDetail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = RaftType.Input.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontFamily = RaftMonoFamily,
                            color = RaftColor.TextMutedGray
                        ),
                        color = RaftColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        RaftBrutalTextField(
            label = stringResource(MR.strings.settings_computers_label_name),
            value = nameValue,
            placeholder = state.connectedDetail?.substringBefore(" - ")
                ?: stringResource(MR.strings.settings_computers_placeholder_name),
            onValueChange = {
                nameValue = it
                onIntent(SharedSettingsIntent.SetAddComputerName(it.text))
            },
            required = true,
            enabled = !state.saving
        )
        RaftDialogFooter {
            RaftBrutalActionButton(
                label = if (state.saving) {
                    stringResource(MR.strings.settings_computers_action_saving)
                } else {
                    stringResource(MR.strings.common_done)
                },
                onClick = { onIntent(SharedSettingsIntent.CompleteAddComputerDialog) },
                enabled = !state.saving,
                backgroundColor = RaftColor.AccentLime
            )
        }
    }
}

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
    Row(
        modifier = modifier
            .raftOptionSurface(selected = selected, enabled = enabled || selected)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(
            iconName,
            modifier = Modifier.size(18.dp),
            color = raftArgbHex(if (enabled || selected) RaftColor.TextBlack else RaftColor.ForegroundDisabled)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                // Web AddMachineDialog type cards: text-sm title, text-xs detail.
                style = RaftType.Button.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    color = if (enabled || selected) RaftColor.TextBlack else RaftColor.TextMutedGray
                ),
                color = if (enabled || selected) RaftColor.TextBlack else RaftColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = RaftType.Caption.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (enabled || selected) RaftColor.TextMutedGray else RaftColor.EmptyStateGray
                ),
                color = if (enabled || selected) RaftColor.TextMutedGray else RaftColor.EmptyStateGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddComputerCommandRow(
    command: String,
    copyTarget: AddComputerCommandTarget,
    copied: Boolean,
    onIntent: (SharedSettingsIntent) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f)
                .background(RaftColor.TextBlack)
                .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                .padding(10.dp)
        ) {
            Text(
                command,
                style = RaftType.Input.copy(
                    fontFamily = RaftMonoFamily,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = RaftColor.AccentLime
                ),
                color = RaftColor.AccentLime,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        RaftBrutalIconButton(
            iconName = if (copied) RaftLucideIconName.Check else RaftLucideIconName.Copy,
            onClick = { onIntent(SharedSettingsIntent.CopyAddComputerCommand(copyTarget)) },
            size = 30.dp,
            shadowOffset = 2.dp,
            backgroundColor = RaftColor.SurfaceWhite
        )
    }
}

@Composable
private fun SettingsComputerCommandFallback(state: AddComputerDialogState) {
    val pending = stringResource(MR.strings.settings_computers_api_key_pending)
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(RaftColor.SurfaceCream)
            .border(RaftAppStroke.Default, RaftColor.BorderBlack)
            .padding(12.dp)
    ) {
        Text(
            stringResource(MR.strings.settings_computers_api_key_prefix, state.apiKeyPrefix ?: pending),
            style = RaftType.Input.copy(
                fontSize = 13.sp,
                color = RaftColor.TextBlack,
                fontStyle = FontStyle.Italic
            ),
            color = RaftColor.TextBlack
        )
    }
}
