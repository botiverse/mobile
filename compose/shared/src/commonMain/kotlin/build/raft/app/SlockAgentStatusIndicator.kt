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
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull

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

/** Caption for an external agent's detail-page status (React AgentDetailPanel `AgentStatusBadge` parity). */
internal sealed interface ExternalAgentDetailCaption {
    /** `waiting_for_login`: gray dot; copy tells the user to finish setup on the web app. */
    data object SetupRequired : ExternalAgentDetailCaption
    data object Active : ExternalAgentDetailCaption
    /** [iso] is the wire timestamp, kept so the UI can format it with the page's time context. */
    data class LastActivity(val epochMillis: Long, val iso: String) : ExternalAgentDetailCaption
    data object NoRaftActivity : ExternalAgentDetailCaption
    data object NotConfigured : ExternalAgentDetailCaption
    /** Status not provided (non-external, 403, failure): claim only "External". */
    data object External : ExternalAgentDetailCaption
}

internal data class ExternalAgentDetailIndicator(
    val state: SlockAgentStatusIndicatorState,
    val caption: ExternalAgentDetailCaption
)

internal const val ExternalAgentActiveThresholdMillis: Long = 5L * 60L * 1000L
internal const val ExternalAgentRecentThresholdMillis: Long = 30L * 60L * 1000L

/** Server sends `toISOString()` (UTC, `Z`); the repo parser also accepts offsets and epoch-millis strings. */
internal fun parseExternalAgentTimestampOrNull(iso: String?): Long? =
    parseSlockTimestampEpochMillisOrNull(iso, localOffsetMinutes = 0)

/**
 * Detail-page tone for an external agent, mirroring web `AgentStatusBadge`
 * (slock@2676dab22) tier for tier:
 *  - activity within 5 min  → lime, "Active"
 *  - activity within 30 min → cyan, "Last activity …"
 *  - else `waiting_for_login` → gray, "Setup required" (product: not signed in ⇒ gray)
 *  - else                   → cyan
 * [status] == null means *not provided* (non-external, 403, request failed) and
 * yields the plain unconditional-cyan "External" state — never gray, because
 * "we may not read it" is not "it is waiting for login" (task #1119, LiBai).
 */
internal fun externalAgentDetailIndicator(
    status: build.raft.app.data.ExternalAgentStatusSnapshot?,
    nowEpochMillis: Long
): ExternalAgentDetailIndicator {
    // status = null on purpose: external agents have no managed liveness, and a
    // null live status makes membersLiveAgentStatusLabel surface this label
    // verbatim instead of a summary activity string.
    val base = slockAgentStatusIndicatorState(status = null, presence = Presence.Offline, external = true)
    if (status == null) return ExternalAgentDetailIndicator(base, ExternalAgentDetailCaption.External)
    val lastAt = parseExternalAgentTimestampOrNull(status.lastActivityAt)
    val ageMs = lastAt?.let { nowEpochMillis - it }
    val waiting = status.setupState == build.raft.app.data.ExternalAgentSetupState.WaitingForLogin
    val color =
        when {
            ageMs != null && ageMs < ExternalAgentActiveThresholdMillis -> SlockColor.AccentLime
            ageMs != null && ageMs < ExternalAgentRecentThresholdMillis -> SlockColor.AccentCyan
            waiting -> SlockColor.StatusNeutralGray
            else -> SlockColor.AccentCyan
        }
    val caption =
        when {
            waiting -> ExternalAgentDetailCaption.SetupRequired
            lastAt != null && ageMs != null && ageMs < ExternalAgentActiveThresholdMillis -> ExternalAgentDetailCaption.Active
            lastAt != null -> ExternalAgentDetailCaption.LastActivity(lastAt, status.lastActivityAt.orEmpty())
            status.setupState == build.raft.app.data.ExternalAgentSetupState.CredentialMinted ||
                status.setupState == build.raft.app.data.ExternalAgentSetupState.Connected ->
                ExternalAgentDetailCaption.NoRaftActivity
            else -> ExternalAgentDetailCaption.NotConfigured
        }
    return ExternalAgentDetailIndicator(base.copy(color = color), caption)
}
