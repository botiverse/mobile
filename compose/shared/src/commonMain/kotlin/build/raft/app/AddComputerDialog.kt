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
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
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
        AddComputerStep.Type -> "Add computer"
        AddComputerStep.Waiting -> "Connect computer"
        AddComputerStep.Connected -> "Computer connected"
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
        dismissInputFocusOnBackgroundDown = true,
        maxWidth = 520.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22,
        contentSpacing = 12.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.step) {
                AddComputerStep.Type -> AddComputerTypeStep(state, onIntent)
                AddComputerStep.Waiting -> AddComputerWaitingStep(state, onIntent)
                AddComputerStep.Connected -> AddComputerConnectedStep(state, onIntent)
            }
        }
    }
}

@Composable
private fun AddComputerTypeStep(
    state: AddComputerDialogState,
    onIntent: (SharedSettingsIntent) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.canManage || state.atLimit) {
            Text(
                state.errorMessage ?: "You do not have permission to add computers.",
                style = SlockType.Caption.copy(color = SlockColor.AccentRed),
                color = SlockColor.AccentRed
            )
        }
        Text(
            "Choose how to connect:",
            style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddComputerTypeCard(
                title = "Your Computer",
                detail = "Run agents on your own computer",
                iconName = SlockLucideIconName.Monitor,
                selected = state.machineType == AddComputerType.Local,
                enabled = state.canManage && !state.atLimit && !state.registering,
                onClick = { onIntent(SharedSettingsIntent.SetAddComputerType(AddComputerType.Local)) },
                modifier = Modifier.weight(1f)
            )
            AddComputerTypeCard(
                title = "Cloud Computer",
                detail = "Coming soon",
                iconName = SlockLucideIconName.Server,
                selected = state.machineType == AddComputerType.Cloud,
                enabled = false,
                onClick = { onIntent(SharedSettingsIntent.SetAddComputerType(AddComputerType.Cloud)) },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            "Install the daemon on your machine and connect it to this workspace.",
            style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        state.errorMessage?.takeIf { it.isNotBlank() && state.canManage && !state.atLimit }?.let {
            Text(it, style = SlockType.Caption.copy(color = SlockColor.AccentRed), color = SlockColor.AccentRed)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                onClick = { onIntent(SharedSettingsIntent.DismissAddComputerDialog) },
                enabled = !state.registering,
                backgroundColor = SlockColor.SurfaceWhite
            )
            SlockBrutalActionButton(
                label = if (state.registering) "Setting up..." else "Next",
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
                "Run this command on your computer to connect:",
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
                "Computer",
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
                "Waiting for computer to connect...",
                style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack
            )
        }
        state.apiKeyPrefix?.takeIf { it.isNotBlank() }?.let {
            Text(
                "API key prefix: $it...",
                style = SlockType.Caption.copy(fontSize = 12.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = SlockType.Caption.copy(color = SlockColor.AccentRed), color = SlockColor.AccentRed)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
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
                    "Computer connected successfully!",
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
            label = "Computer name",
            value = state.name,
            placeholder = state.connectedDetail?.substringBefore(" - ") ?: "My Mac",
            onValueChange = { onIntent(SharedSettingsIntent.SetAddComputerName(it)) },
            required = true,
            enabled = !state.saving
        )
        SlockBrutalActionButton(
            label = if (state.saving) "Saving..." else "Done",
            onClick = { onIntent(SharedSettingsIntent.CompleteAddComputerDialog) },
            enabled = !state.saving,
            backgroundColor = SlockColor.AccentLime
        )
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
                title.uppercase(),
                style = SlockType.Button.copy(
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    color = if (enabled || selected) SlockColor.TextBlack else SlockColor.TextMutedGray
                ),
                color = if (enabled || selected) SlockColor.TextBlack else SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                style = SlockType.Caption.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
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
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(SlockColor.SurfaceCream)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(12.dp)
    ) {
        Text(
            "API key prefix: ${state.apiKeyPrefix ?: "pending"}...",
            style = SlockType.Input.copy(
                fontSize = 13.sp,
                color = SlockColor.TextBlack,
                fontStyle = FontStyle.Italic
            ),
            color = SlockColor.TextBlack
        )
    }
}
