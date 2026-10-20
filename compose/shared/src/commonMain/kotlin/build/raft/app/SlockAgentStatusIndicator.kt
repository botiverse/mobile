package build.raft.app

import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull

internal data class SlockAgentStatusIndicatorState(
    val status: AgentStatus?,
    val color: Color,
    val pulsing: Boolean,
    val label: String,
    /** External agents only: `lastSeenAt` carried through for presence resolution (task #410). */
    val lastSeenAt: String? = null,
    /** True when the dot renders the external-agent token rather than managed liveness. */
    val external: Boolean
)

/** React parity: external agents caption as "External" (agentStore.ts resolveAgentDisplayState). */
internal const val ExternalAgentStatusLabel = "External"

/**
 * task #410: web `EXTERNAL_AGENT_ONLINE_WINDOW_MS` parity — an external agent
 * is "online" while `now - lastSeenAt < 120s`, otherwise "last active". Server
 * emits `lastSeenAt` on `GET /agents*` and pushes `agent:seen`; this seam is
 * the single place that rule is evaluated so list rows and the detail badge
 * can never disagree.
 */
internal const val ExternalAgentOnlineWindowMillis: Long = 120_000L

internal fun isExternalAgentSeenOnline(
    lastSeenAt: String?,
    nowEpochMillis: Long?
): Boolean {
    val seen = parseExternalAgentTimestampOrNull(lastSeenAt) ?: return false
    val now = nowEpochMillis ?: return false
    return now - seen < ExternalAgentOnlineWindowMillis
}

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
    // task #410: the external presence tier depends on `now` as well as the
    // fact. Re-resolve it whenever the live time context ticks (minute
    // boundary) or the underlying state changes, so "online" does not outlive
    // the 120s window without a new agent:seen.
    val liveNow = LocalSlockTimeContextProvider.current?.current?.nowEpochMillis
    val base = status
    return if (base?.external == true && base.lastSeenAt != null) {
        val now = liveNow ?: defaultNowMillis()
        base.copy(color = if (isExternalAgentSeenOnline(base.lastSeenAt, now)) SlockColor.AccentLime else SlockColor.AccentCyan)
    } else {
        base
    }
}

internal fun SharedServerMemberStatus.toSlockAgentStatusIndicatorState(): SlockAgentStatusIndicatorState =
    slockAgentStatusIndicatorState(
        status = status,
        presence = presence,
        statusLabel = statusLabel,
        external = external,
        lastSeenAt = lastSeenAt
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
    external: Boolean,
    /**
     * External agents only (task #410): `lastSeenAt` — credential last-use
     * (ISO-8601) from `GET /agents*` / realtime `agent:seen`. The online tier
     * resolves at read time from [nowEpochMillis]; a stale time does not
     * repaint the dot, it merely lags the window boundary.
     */
    lastSeenAt: String? = null,
    nowEpochMillis: Long? = null
): SlockAgentStatusIndicatorState {
    if (external) {
        // React StatusDot parity: `external` overrides activity unconditionally
        // ("no managed liveness green/red"). The tier is no longer always
        // cyan: `lastSeenAt` inside the online window (web
        // `EXTERNAL_AGENT_ONLINE_WINDOW_MS` = 120s) renders lime. Color,
        // pulse and label move together so the dot can never disagree with
        // the text beside it.
        val online = isExternalAgentSeenOnline(lastSeenAt, nowEpochMillis)
        return SlockAgentStatusIndicatorState(
            status = status,
            color = if (online) SlockColor.AccentLime else SlockColor.AccentCyan,
            pulsing = false,
            label = ExternalAgentStatusLabel,
            external = true,
            lastSeenAt = lastSeenAt
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
    /** `lastSeenAt` within the online window (task #410): lime dot, "Online". */
    data object Online : ExternalAgentDetailCaption
    data object Active : ExternalAgentDetailCaption
    /** [iso] is the wire timestamp, kept so the UI can format it with the page's time context. */
    data class LastActivity(val epochMillis: Long, val iso: String) : ExternalAgentDetailCaption
    /** `lastSeenAt` older than the online window: cyan dot, "Last active {time}" (web parity). */
    data class LastSeen(val epochMillis: Long, val iso: String) : ExternalAgentDetailCaption
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

/** Server sends `toISOString()` (UTC, `Z`); the repo parser also accepts offsets and epoch-millis strings. */
internal fun parseExternalAgentTimestampOrNull(iso: String?): Long? =
    parseSlockTimestampEpochMillisOrNull(iso, localOffsetMinutes = 0)

/**
 * Detail-page tone for an external agent, mirroring web `AgentStatusBadge`
 * (slock@2676dab22) with one deliberate difference:
 *  - `waiting_for_login`      → gray, "Setup required" — evaluated FIRST. Web
 *    checks activity tiers before the setup state, which can pair a lime dot
 *    with a "setup required" caption (an agent that ran and then lost its
 *    credential). Product rule (@artin): not signed in ⇒ gray, and this file's
 *    invariant is that the dot never disagrees with the text beside it.
 *  - activity within 5 min    → lime, "Active"
 *  - any older activity       → cyan, "Last activity …"
 *  - no activity              → cyan, "No Raft activity" / "Not configured"
 *
 * Note there are only TWO colour tiers here, deliberately. Web has a third line
 * at 30 minutes, and it is meaningful there *because* web is activity-first: an
 * unlogged-in agent whose last activity is 5-30 minutes old gets cyan on web and
 * gray here. The two ends actually disagree across the whole under-30-minute span
 * (below 5 minutes web shows lime, also against gray here); 30 minutes is where
 * web falls through to its own setup check and both go gray. So the divergence is
 * bounded — it is not "anything older than 5 minutes" — and the tier that would
 * have been ported is only the cyan half of it. Since this file evaluates
 * `waiting` first, that same input never
 * reaches an age comparison, so a 30-minute constant would be unreachable — it
 * existed and was removed. This is an intentional divergence, not missing parity;
 * re-adding the tier here would reintroduce dead code, and making the two ends
 * agree is a product decision about ordering, not a cleanup.
 *
 * [status] == null means *not provided* (non-external, 403, request failed) and
 * yields the plain unconditional-cyan "External" state — never gray, because
 * "we may not read it" is not "it is waiting for login" (task #1119, LiBai).
 */
internal fun externalAgentDetailIndicator(
    status: build.raft.app.data.ExternalAgentStatusSnapshot?,
    nowEpochMillis: Long,
    /**
     * `lastSeenAt` from the agent summary (`GET /agents*` + `agent:seen`).
     * The online tier (120s) is the presence signal; the `lastActivityAt`
     * tiers below are the setup/activity detail. Web `AgentStatusBadge`
     * parity: Online wins over a stale relative time; `waiting_for_login`
     * stays gray-first per the existing KDoc divergence.
     */
    lastSeenAt: String? = null
): ExternalAgentDetailIndicator {
    // status = null on purpose: external agents have no managed liveness, and a
    // null live status makes membersLiveAgentStatusLabel surface this label
    // verbatim instead of a summary activity string.
    val base = slockAgentStatusIndicatorState(status = null, presence = Presence.Offline, external = true)
    val seenAt = parseExternalAgentTimestampOrNull(lastSeenAt)
    val seenOnline = isExternalAgentSeenOnline(lastSeenAt, nowEpochMillis)
    if (status == null) {
        // task #410: even without the permission-gated externalStatus the
        // summary's lastSeenAt still carries the presence tiers.
        return when {
            seenOnline -> ExternalAgentDetailIndicator(
                base.copy(color = SlockColor.AccentLime),
                ExternalAgentDetailCaption.Online
            )
            seenAt != null -> ExternalAgentDetailIndicator(
                base,
                ExternalAgentDetailCaption.LastSeen(seenAt, lastSeenAt.orEmpty())
            )
            else -> ExternalAgentDetailIndicator(base, ExternalAgentDetailCaption.External)
        }
    }
    val lastAt = parseExternalAgentTimestampOrNull(status.lastActivityAt)
    val ageMs = lastAt?.let { nowEpochMillis - it }
    val waiting = status.setupState == build.raft.app.data.ExternalAgentSetupState.WaitingForLogin
    // waiting-first: see the KDoc on the two-tier divergence from web before adding a tier.
    val color =
        when {
            waiting -> SlockColor.StatusNeutralGray
            seenOnline -> SlockColor.AccentLime
            ageMs != null && ageMs < ExternalAgentActiveThresholdMillis -> SlockColor.AccentLime
            else -> SlockColor.AccentCyan
        }
    val caption =
        when {
            waiting -> ExternalAgentDetailCaption.SetupRequired
            seenOnline -> ExternalAgentDetailCaption.Online
            lastAt != null && ageMs != null && ageMs < ExternalAgentActiveThresholdMillis -> ExternalAgentDetailCaption.Active
            seenAt != null -> ExternalAgentDetailCaption.LastSeen(seenAt, lastSeenAt.orEmpty())
            lastAt != null -> ExternalAgentDetailCaption.LastActivity(lastAt, status.lastActivityAt.orEmpty())
            status.setupState == build.raft.app.data.ExternalAgentSetupState.CredentialMinted ||
                status.setupState == build.raft.app.data.ExternalAgentSetupState.Connected ->
                ExternalAgentDetailCaption.NoRaftActivity
            else -> ExternalAgentDetailCaption.NotConfigured
        }
    return ExternalAgentDetailIndicator(base.copy(color = color), caption)
}

/** Translate transport labels, retaining activity details supplied by the store. */
@Composable
internal fun SlockAgentStatusIndicatorState.localizedMemberStatusLabel(): String =
    when (label.trim().lowercase()) {
        "online", "active" -> stringResource(MR.strings.member_row_status_online)
        "offline" -> stringResource(MR.strings.member_row_status_offline)
        "idle", "away" -> stringResource(MR.strings.member_row_status_idle)
        "disconnected" -> stringResource(MR.strings.member_row_status_disconnected)
        "error" -> stringResource(MR.strings.member_row_status_error)
        "external" -> stringResource(MR.strings.member_row_status_external)
        "working" -> stringResource(MR.strings.member_row_status_working)
        "thinking" -> stringResource(MR.strings.member_row_status_thinking)
        else -> label
    }
