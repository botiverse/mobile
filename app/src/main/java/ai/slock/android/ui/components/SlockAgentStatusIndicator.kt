package ai.slock.android.ui.components

import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.Presence
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SlockAgentStatusIndicator(
    status: AgentStatus?,
    presence: Presence,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    contentDescriptionPrefix: String = "Agent status"
) {
    Box(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = "$contentDescriptionPrefix: ${status.agentStatusLabel(presence)}"
            }
            .background(status.agentStatusColor(presence), CircleShape)
            .border(SlockStroke.Thin, SlockColor.BorderBlack, CircleShape)
    )
}

fun AgentStatus?.agentStatusColor(presence: Presence): Color =
    when (this) {
        AgentStatus.Online -> SlockColor.AccentLime
        AgentStatus.Working -> SlockColor.AccentCyan
        AgentStatus.Thinking -> SlockColor.BrandYellow
        AgentStatus.Idle -> SlockColor.AccentLavender
        AgentStatus.Disconnected,
        AgentStatus.Offline -> SlockColor.EmptyStateGray
        AgentStatus.Error -> SlockColor.AccentRed
        null -> presence.presenceColor
    }

fun AgentStatus?.agentStatusLabel(presence: Presence): String =
    this?.label ?: presence.readableLabel

val Presence.readableLabel: String
    get() = when (this) {
        Presence.Active -> "Active"
        Presence.Away -> "Away"
        Presence.Offline -> "Offline"
    }

private val Presence.presenceColor: Color
    get() = when (this) {
        Presence.Active -> SlockColor.AccentLime
        Presence.Away -> SlockColor.BrandYellow
        Presence.Offline -> SlockColor.EmptyStateGray
    }
