package build.raft.app

import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalTextField
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
    SlockUnifiedDialog(
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
                style = SlockType.Caption.copy(color = SlockColor.AccentRed),
                color = SlockColor.AccentRed
            )
        }
        Text(
            stringResource(MR.strings.settings_computers_choose_connection),
            style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddComputerTypeCard(
                title = stringResource(MR.strings.settings_computers_your_computer),
                detail = stringResource(MR.strings.settings_computers_your_computer_hint),
                iconName = SlockLucideIconName.Monitor,
                selected = state.machineType == AddComputerType.Local,
                enabled = state.canManage && !state.atLimit && !state.registering,
                onClick = { onIntent(SharedSettingsIntent.SetAddComputerType(AddComputerType.Local)) },
                modifier = Modifier.weight(1f)
            )
            AddComputerTypeCard(
                title = stringResource(MR.strings.settings_computers_cloud_computer),
                detail = stringResource(MR.strings.settings_computers_coming_soon),
                iconName = SlockLucideIconName.Server,
                selected = state.machineType == AddComputerType.Cloud,
                enabled = false,
                onClick = { onIntent(SharedSettingsIntent.SetAddComputerType(AddComputerType.Cloud)) },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            stringResource(MR.strings.settings_computers_install_daemon_hint),
            style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        userFacingError?.takeIf { it.isNotBlank() && state.canManage && !state.atLimit }?.let {
            Text(it, style = SlockType.Caption.copy(color = SlockColor.AccentRed), color = SlockColor.AccentRed)
        }
        SlockDialogFooter {
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                onClick = { onIntent(SharedSettingsIntent.DismissAddComputerDialog) },
                enabled = !state.registering,
                backgroundColor = SlockColor.SurfaceWhite
            )
            SlockBrutalActionButton(
                label = if (state.registering) {
                    stringResource(MR.strings.settings_computers_setting_up)
                } else {
                    stringResource(MR.strings.settings_computers_next)
                },
                onClick = {
                    onIntent(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest(name = "my-computer")))
                },
                enabled = state.canManage && !state.atLimit && !state.registering,
                leadingIcon = if (state.registering) SlockLucideIconName.LoaderCircle else SlockLucideIconName.Plus,
                leadingIconSpinning = state.registering,
                backgroundColor = SlockColor.AccentPink
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
            SlockLucideIcon(SlockLucideIconName.Terminal, modifier = Modifier.size(16.dp))
            Text(
                stringResource(MR.strings.settings_computers_run_command_hint),
                style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack
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
                style = SlockType.SectionLabel.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
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
                .background(SlockColor.SurfaceCream)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockStatusIndicator(color = SlockColor.BrandYellow)
            Text(
                stringResource(MR.strings.settings_computers_waiting_for_connection),
                style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack
            )
        }
        state.apiKeyPrefix?.takeIf { it.isNotBlank() }?.let {
            Text(
                stringResource(MR.strings.settings_computers_api_key_prefix, it),
                style = SlockType.Caption.copy(fontSize = 12.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        userFacingError?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = SlockType.Caption.copy(color = SlockColor.AccentRed), color = SlockColor.AccentRed)
        }
        SlockDialogFooter {
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                onClick = { onIntent(SharedSettingsIntent.DismissAddComputerDialog) },
                backgroundColor = SlockColor.SurfaceWhite
            )
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_done),
                onClick = {},
                enabled = false,
                backgroundColor = SlockColor.AccentLime
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
                .background(SlockColor.AccentLime)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SlockLucideIcon(SlockLucideIconName.CircleCheckBig, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(MR.strings.settings_computers_connected_success),
                    style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack
                )
                state.connectedDetail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = SlockType.Input.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontFamily = SlockMonoFamily,
                            color = SlockColor.TextMutedGray
                        ),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        SlockBrutalTextField(
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
        SlockDialogFooter {
            SlockBrutalActionButton(
                label = if (state.saving) {
                    stringResource(MR.strings.settings_computers_action_saving)
                } else {
                    stringResource(MR.strings.common_done)
                },
                onClick = { onIntent(SharedSettingsIntent.CompleteAddComputerDialog) },
                enabled = !state.saving,
                backgroundColor = SlockColor.AccentLime
            )
        }
    }
}

@Composable
private fun AddComputerTypeCard(
    title: String,
    detail: String,
    iconName: SlockLucideIconName,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, if (enabled || selected) SlockColor.BorderBlack else SlockColor.EmptyStateGray)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(
            iconName,
            modifier = Modifier.size(18.dp),
            color = if (enabled || selected) "#FF000000" else "#66000000"
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title.slockDisplayUppercase(),
                // Web AddMachineDialog type cards: text-sm title, text-xs detail.
                style = SlockType.Button.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    color = if (enabled || selected) SlockColor.TextBlack else SlockColor.TextMutedGray
                ),
                color = if (enabled || selected) SlockColor.TextBlack else SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = SlockType.Caption.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (enabled || selected) SlockColor.TextMutedGray else SlockColor.EmptyStateGray
                ),
                color = if (enabled || selected) SlockColor.TextMutedGray else SlockColor.EmptyStateGray,
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
                .background(SlockColor.TextBlack)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(10.dp)
        ) {
            Text(
                command,
                style = SlockType.Input.copy(
                    fontFamily = SlockMonoFamily,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = SlockColor.AccentLime
                ),
                color = SlockColor.AccentLime,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        SlockBrutalIconButton(
            iconName = if (copied) SlockLucideIconName.Check else SlockLucideIconName.Copy,
            onClick = { onIntent(SharedSettingsIntent.CopyAddComputerCommand(copyTarget)) },
            size = 30.dp,
            shadowOffset = 2.dp,
            backgroundColor = SlockColor.SurfaceWhite
        )
    }
}

@Composable
private fun SettingsComputerCommandFallback(state: AddComputerDialogState) {
    val pending = stringResource(MR.strings.settings_computers_api_key_pending)
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceCream)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(12.dp)
    ) {
        Text(
            stringResource(MR.strings.settings_computers_api_key_prefix, state.apiKeyPrefix ?: pending),
            style = SlockType.Input.copy(
                fontSize = 13.sp,
                color = SlockColor.TextBlack,
                fontStyle = FontStyle.Italic
            ),
            color = SlockColor.TextBlack
        )
    }
}
