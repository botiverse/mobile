package ai.slock.compose

import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal data class SharedCreateAgentDialogSubmit(
    val name: String,
    val description: String?,
    val machineId: String?
)

@Composable
internal fun SharedCreateAgentDialog(
    submitting: Boolean,
    errorMessage: String?,
    initialName: String = "",
    initialDescription: String = "",
    draftHint: String? = null,
    suggestedComputer: String? = null,
    requiredComputer: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (SharedCreateAgentDialogSubmit) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    val canSubmit = name.trim().isNotBlank() && !submitting
    val computerId =
        requiredComputer?.takeIf { it.isNotBlank() }
            ?: suggestedComputer?.takeIf { it.isNotBlank() }

    SlockUnifiedDialog(
        title = "CREATE AGENT",
        dismissEnabled = !submitting,
        onDismiss = onDismiss
    ) {
        draftHint?.takeIf { it.isNotBlank() }?.let { hint ->
            Text(
                text = hint,
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.2f))
                        .padding(SlockSpacing.Xs)
            )
        }
        if (!computerId.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (!requiredComputer.isNullOrBlank()) "Required computer" else "Suggested computer",
                    style =
                        SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            color = SlockColor.TextBlack
                        ),
                    color = SlockColor.TextBlack
                )
                Text(
                    text = computerId,
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        SlockBrutalTextField(
            label = "Name",
            required = true,
            value = name,
            onValueChange = { name = it },
            placeholder = "e.g. Noel",
            enabled = !submitting,
            autoFocus = true
        )
        SlockBrutalTextField(
            label = "Description",
            optional = true,
            value = description,
            onValueChange = { description = it },
            placeholder = "Leave blank for a general-purpose agent, or describe a role...",
            enabled = !submitting,
            maxLines = 3,
            fieldMinHeight = 82.dp
        )
        errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = error,
                style =
                    SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = SlockColor.AccentRed
                    ),
                color = SlockColor.AccentRed
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            SlockBrutalActionButton(
                label = "Cancel",
                enabled = !submitting,
                backgroundColor = SlockColor.SurfaceWhite,
                onClick = onDismiss
            )
            SlockBrutalActionButton(
                label = if (submitting) "Creating..." else "Create Agent",
                enabled = canSubmit,
                minWidth = 126.dp,
                backgroundColor = SlockColor.AccentPink,
                onClick = {
                    onSubmit(
                        SharedCreateAgentDialogSubmit(
                            name = name,
                            description = description.trim().takeIf { it.isNotBlank() },
                            machineId = computerId
                        )
                    )
                }
            )
        }
    }
}
