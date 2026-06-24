package ai.slock.compose

import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalCard
import ai.slock.compose.components.SlockBrutalIconButton
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.data.AddComputerDialogState
import ai.slock.compose.data.AddComputerStep
import ai.slock.compose.data.AddComputerType
import ai.slock.compose.data.SettingsComputerRegisterRequest
import ai.slock.compose.data.SharedSettingsIntent
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

@Composable
internal fun AddComputerDialog(
    state: AddComputerDialogState,
    onIntent: (SharedSettingsIntent) -> Unit
) {
    SlockBrutalCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val title = when (state.step) {
                    AddComputerStep.Type -> "Add computer"
                    AddComputerStep.Waiting -> "Set up your computer"
                    AddComputerStep.Connected -> "Connected"
                }
                Text(title, style = SlockType.PageTitle.copy(fontSize = 18.sp, color = SlockColor.TextBlack), color = SlockColor.TextBlack)
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.X,
                    onClick = { onIntent(SharedSettingsIntent.DismissAddComputerDialog) },
                    size = 28.dp,
                    shadowOffset = 2.dp
                )
            }
            when (state.step) {
                AddComputerStep.Type -> AddComputerTypeStep(state, onIntent)
                AddComputerStep.Waiting -> AddComputerWaitingStep(state, onIntent)
                AddComputerStep.Connected -> AddComputerConnectedStep(state, onIntent)
            }
        }
    }
}

@Composable
private fun AddComputerTypeStep(state: AddComputerDialogState, onIntent: (SharedSettingsIntent) -> Unit) {
    if (state.atLimit) {
        Text(
            "Computer limit reached for your plan. Upgrade to add more.",
            style = SlockType.Caption.copy(color = SlockColor.AccentRed),
            color = SlockColor.AccentRed
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Choose how to connect:",
            style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlockBrutalActionButton(
                label = "Local",
                onClick = { onIntent(SharedSettingsIntent.SetAddComputerType(AddComputerType.Local)) },
                enabled = true,
                leadingIcon = SlockLucideIconName.Monitor,
                backgroundColor = if (state.machineType == AddComputerType.Local) SlockColor.AccentPink else SlockColor.SurfaceWhite
            )
            SlockBrutalActionButton(
                label = "Cloud",
                onClick = { onIntent(SharedSettingsIntent.SetAddComputerType(AddComputerType.Cloud)) },
                enabled = true,
                leadingIcon = SlockLucideIconName.Server,
                backgroundColor = if (state.machineType == AddComputerType.Cloud) SlockColor.AccentPink else SlockColor.SurfaceWhite
            )
        }
        Text(
            if (state.machineType == AddComputerType.Local)
                "Install the daemon on your machine and connect it to this workspace."
            else
                "Spin up a managed cloud computer that runs agents for you.",
            style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Spacer(modifier = Modifier.height(8.dp))
        SlockBrutalTextField(
            label = "Computer name",
            value = state.name,
            placeholder = "my-macbook",
            onValueChange = { onIntent(SharedSettingsIntent.SetAddComputerName(it)) },
            required = true,
            enabled = !state.registering
        )
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = SlockType.Caption.copy(color = SlockColor.AccentRed), color = SlockColor.AccentRed)
        }
        SlockBrutalActionButton(
            label = if (state.registering) "Creating..." else "Create computer",
            onClick = {
                if (state.name.isNotBlank()) {
                    onIntent(SharedSettingsIntent.RegisterComputer(SettingsComputerRegisterRequest(name = state.name)))
                }
            },
            enabled = state.name.isNotBlank() && !state.registering,
            leadingIcon = if (state.registering) SlockLucideIconName.Loader else SlockLucideIconName.Plus,
            backgroundColor = SlockColor.AccentPink
        )
    }
}

@Composable
private fun AddComputerWaitingStep(state: AddComputerDialogState, onIntent: (SharedSettingsIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Run this command on your computer:",
            style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(SlockColor.SurfaceCream)
                .padding(12.dp)
        ) {
            Text(
                "raft-computer setup /${state.name.ifBlank { "workspace" }}",
                style = SlockType.Input.copy(fontSize = 13.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack
            )
        }
        Text(
            "Waiting for your computer to come online...",
            style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        if (state.apiKeyPrefix != null) {
            Text(
                "API key prefix: ${state.apiKeyPrefix}...",
                style = SlockType.Caption.copy(fontSize = 12.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray
            )
        }
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = SlockType.Caption.copy(color = SlockColor.AccentRed), color = SlockColor.AccentRed)
        }
        SlockBrutalActionButton(
            label = "Cancel",
            onClick = { onIntent(SharedSettingsIntent.DismissAddComputerDialog) },
            backgroundColor = SlockColor.SurfaceWhite
        )
    }
}

@Composable
private fun AddComputerConnectedStep(state: AddComputerDialogState, onIntent: (SharedSettingsIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Connected! Your computer is now part of this workspace.",
            style = SlockType.Body.copy(fontSize = 14.sp, color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
        SlockBrutalTextField(
            label = "Computer name",
            value = state.name,
            placeholder = "my-macbook",
            onValueChange = { onIntent(SharedSettingsIntent.SetAddComputerName(it)) },
            required = true,
            enabled = true
        )
        SlockBrutalActionButton(
            label = "Done",
            onClick = { onIntent(SharedSettingsIntent.DismissAddComputerDialog) },
            backgroundColor = SlockColor.AccentPink
        )
    }
}