package build.raft.app

import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.components.SlockRaftText
import build.raft.app.components.SlockRaftValueBlock
import build.raft.app.components.slockRaftCardSurface
import build.raft.app.components.slockRaftControlSurface
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
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.compose.ui.unit.sp

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
                style = SlockType.Caption.copy(color = SlockColor.DangerStrong),
                color = SlockColor.DangerStrong
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
            Text(it, style = SlockType.Caption.copy(color = SlockColor.DangerStrong), color = SlockColor.DangerStrong)
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
                // raft CommandRow step label: text-footnote semibold ink (source case).
                style = SlockType.Body.copy(
                    fontSize = SlockRaftText.Footnote,
                    lineHeight = SlockRaftText.FootnoteLine,
                    letterSpacing = 0.sp,
                    color = SlockColor.TextBlack
                ).withSlockFontWeight(FontWeight.SemiBold),
                color = SlockColor.TextBlack
            )
            AddComputerCommandRow(
                command = computerCommand,
                copyTarget = AddComputerCommandTarget.Computer,
                copied = state.copiedCommand == AddComputerCommandTarget.Computer,
                onIntent = onIntent
            )
        }
        // raft-ui Banner intent="info": soft info wash, hairline card, status lead.
        Row(
            modifier = Modifier.fillMaxWidth()
                .slockRaftCardSurface(fill = SlockColor.InfoSoft, radius = SlockRadius.Control)
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
                style = SlockType.Caption.copy(fontSize = SlockRaftText.Caption, color = SlockColor.TextMuted),
                color = SlockColor.TextMuted
            )
        }
        userFacingError?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = SlockType.Caption.copy(color = SlockColor.DangerStrong), color = SlockColor.DangerStrong)
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
        // raft-ui Banner intent="success": soft success wash, hairline card.
        Row(
            modifier = Modifier.fillMaxWidth()
                .slockRaftCardSurface(fill = SlockColor.SuccessSoft, radius = SlockRadius.Control)
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
                            color = SlockColor.TextMuted
                        ),
                        color = SlockColor.TextMuted,
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
    // Web AddMachineDialog type card: rounded-control, 1.5dp line-strong edge,
    // info-soft when selected; the unavailable card is fill-muted with a hairline.
    val active = enabled || selected
    Row(
        modifier = modifier
            .let {
                if (active) {
                    it.slockRaftControlSurface(
                        fill = if (selected) SlockColor.InfoSoft else SlockColor.Card,
                        border = SlockColor.BorderBlack
                    )
                } else {
                    it.slockRaftCardSurface(fill = SlockColor.FillMuted, radius = SlockRadius.Control)
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(
            iconName,
            modifier = Modifier.size(20.dp),
            color = slockArgbHex(if (active) SlockColor.TextBlack else SlockColor.ForegroundHint)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                // Web AddMachineDialog type cards: text-callout semibold title, text-footnote detail.
                style = SlockType.Body.copy(
                    fontSize = SlockRaftText.Callout,
                    lineHeight = SlockRaftText.CalloutLine,
                    letterSpacing = 0.sp,
                    color = if (active) SlockColor.TextBlack else SlockColor.ForegroundHint
                ).withSlockFontWeight(FontWeight.SemiBold),
                color = if (active) SlockColor.TextBlack else SlockColor.ForegroundHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = SlockType.Caption.copy(
                    fontSize = SlockRaftText.Footnote,
                    lineHeight = SlockRaftText.FootnoteLine,
                    color = if (active) SlockColor.TextMuted else SlockColor.ForegroundHint
                ),
                color = if (active) SlockColor.TextMuted else SlockColor.ForegroundHint,
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
        // raft-ui CopyableCode: code layer, hairline, mono 13/20.
        SlockRaftValueBlock(
            value = command,
            modifier = Modifier.weight(1f),
            maxLines = 4
        )
        SlockBrutalIconButton(
            iconName = if (copied) SlockLucideIconName.Check else SlockLucideIconName.Copy,
            onClick = { onIntent(SharedSettingsIntent.CopyAddComputerCommand(copyTarget)) },
            size = 30.dp,
            backgroundColor = SlockColor.SurfaceWhite
        )
    }
}

@Composable
private fun SettingsComputerCommandFallback(state: AddComputerDialogState) {
    val pending = stringResource(MR.strings.settings_computers_api_key_pending)
    // raft muted inset note: fill-muted, control radius, hairline.
    Box(
        modifier = Modifier.fillMaxWidth()
            .slockRaftCardSurface(fill = SlockColor.FillMuted, radius = SlockRadius.Control)
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
