package ai.slock.compose

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.Presence
import com.tencent.kuikly.compose.ui.graphics.Color

internal data class SlockAgentStatusIndicatorState(
    val status: AgentStatus?,
    val color: Color,
    val pulsing: Boolean
)

internal fun slockAgentStatusIndicatorState(
    status: AgentStatus?,
    presence: Presence
): SlockAgentStatusIndicatorState =
    SlockAgentStatusIndicatorState(
        status = status,
        color =
            when (status) {
                AgentStatus.Online -> SlockColor.AccentLime
                AgentStatus.Working,
                AgentStatus.Thinking -> SlockColor.BrandYellow
                AgentStatus.Idle -> SlockColor.AccentLime
                AgentStatus.Disconnected,
                AgentStatus.Offline -> SlockColor.EmptyStateGray
                AgentStatus.Error -> SlockColor.AccentOrange
                null ->
                    when (presence) {
                        Presence.Active -> SlockColor.AccentLime
                        Presence.Away -> SlockColor.BrandYellow
                        Presence.Offline -> SlockColor.EmptyStateGray
                    }
            },
        pulsing = status == AgentStatus.Working || status == AgentStatus.Thinking
    )

internal fun slockAgentStatusIndicatorState(
    statusValue: String?,
    fallbackPresence: Presence
): SlockAgentStatusIndicatorState =
    slockAgentStatusIndicatorState(
        status = AgentStatus.fromWireValue(statusValue),
        presence = fallbackPresence
    )
