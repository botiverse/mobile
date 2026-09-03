package build.raft.app

import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal data class SlockAgentStatusIndicatorState(
    val status: AgentStatus?,
    val color: Color,
    val pulsing: Boolean,
    val label: String,
    /** True when the dot renders the external-agent token rather than managed liveness. */
    val external: Boolean
)

/** React parity: external agents caption as "External" (agentStore.ts resolveAgentDisplayState). */
internal const val ExternalAgentStatusLabel = "External"

internal typealias SlockAgentStatusResolver = (String?) -> Flow<SlockAgentStatusIndicatorState?>

internal val EmptySlockAgentStatusResolver: SlockAgentStatusResolver = { flowOf(null) }

internal fun slockScopedAgentStatusResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?
): SlockAgentStatusResolver {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
        ?: return EmptySlockAgentStatusResolver
    return slockAgentStatusResolver(serverMembersStore, cleanServerId)
}

internal fun slockAgentStatusResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?
): SlockAgentStatusResolver {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
        ?: return EmptySlockAgentStatusResolver
    return { memberId ->
        if (serverMembersStore == null) {
            flowOf(null)
        } else {
            serverMembersStore
                .memberStatusFlow(cleanServerId, memberId)
                .map { status -> status?.toSlockAgentStatusIndicatorState() }
        }
    }
}

@Composable
internal fun rememberSlockAgentStatusIndicatorState(
    agentStatusResolver: SlockAgentStatusResolver,
    memberId: String?
): SlockAgentStatusIndicatorState? {
    val statusFlow = remember(agentStatusResolver, memberId) { agentStatusResolver(memberId) }
    val status by statusFlow.collectAsState(null)
    return status
}

internal fun SharedServerMemberStatus.toSlockAgentStatusIndicatorState(): SlockAgentStatusIndicatorState =
    slockAgentStatusIndicatorState(
        status = status,
        presence = presence,
        statusLabel = statusLabel,
        external = external
    )

/**
 * [external] is required (no default): every producer must state it, so a
 * forgotten flag is a compile error rather than an external agent silently
 * rendering managed green (task #1119).
 */
internal fun slockAgentStatusIndicatorState(
    status: AgentStatus?,
    presence: Presence,
    statusLabel: String? = null,
    external: Boolean
): SlockAgentStatusIndicatorState {
    if (external) {
        // React StatusDot parity: `external` overrides activity unconditionally
        // ("no managed liveness green/red"); color, pulse and label move together
        // so the dot can never disagree with the text beside it.
        return SlockAgentStatusIndicatorState(
            status = status,
            color = SlockColor.AccentCyan,
            pulsing = false,
            label = ExternalAgentStatusLabel,
            external = true
        )
    }
    return SlockAgentStatusIndicatorState(
        status = status,
        color =
            when (status) {
                AgentStatus.Online -> SlockColor.AccentLime
                AgentStatus.Working,
                AgentStatus.Thinking -> SlockColor.BrandYellow
                AgentStatus.Idle -> SlockColor.AccentLime
                AgentStatus.Disconnected,
                AgentStatus.Offline -> SlockColor.StatusNeutralGray
                AgentStatus.Error -> SlockColor.AccentOrange
                null ->
                    when (presence) {
                        Presence.Active -> SlockColor.AccentLime
                        Presence.Away -> SlockColor.BrandYellow
                        Presence.Offline -> SlockColor.StatusNeutralGray
                    }
            },
        pulsing = status == AgentStatus.Working || status == AgentStatus.Thinking,
        // Label derives from the same resolved status as color/pulse so a dot
        // can never disagree with the text beside it (React
        // resolveAgentDisplayState parity, #198/#73).
        label = statusLabel?.takeIf { it.isNotBlank() } ?: status?.label ?: presence.name,
        external = false
    )
}

/**
 * Home consumers must render an indicator only from the authoritative resolver.
 * A null value is Unknown/not-ready; manufacturing Offline here would show a
 * misleading gray dot while the member projection is loading or recovering.
 */
internal fun resolveHomeAgentStatusIndicatorState(
    canonicalState: SlockAgentStatusIndicatorState?
): SlockAgentStatusIndicatorState? = canonicalState

/**
 * Shared projection used by Home DM and Settings search consumers.  A missing
 * member id, disabled presence, or unresolved resolver value must stay absent;
 * consumers must never manufacture an Offline indicator while loading.
 */
internal fun projectHomeAgentStatusIndicatorState(
    showPresence: Boolean,
    memberId: String?,
    canonicalState: SlockAgentStatusIndicatorState?
): SlockAgentStatusIndicatorState? =
    if (showPresence && !memberId.isNullOrBlank()) {
        resolveHomeAgentStatusIndicatorState(canonicalState)
    } else {
        null
    }
