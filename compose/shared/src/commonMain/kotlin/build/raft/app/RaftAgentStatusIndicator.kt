package build.raft.app

import build.raft.app.data.NeutralAgentStatusLabel
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.truncateAgentToolLabel
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
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull

internal data class RaftAgentStatusIndicatorState(
    val status: AgentStatus?,
    val color: Color,
    val pulsing: Boolean,
    val label: String,
    /** External agents only: `lastSeenAt` carried through for presence resolution (task #410). */
    val lastSeenAt: String? = null,
    /** True when the dot renders the external-agent token rather than managed liveness. */
    val external: Boolean,
    /** agent:activity `detailKind` behind [label] ("starting", "compacting_context", ...). */
    val detailKind: String? = null,
    /** Normalized tool name when [label] is a client-built tool label. */
    val activityToolName: String? = null,
    /** The agent's runtime id (web `agent.runtime`); null when unknown. */
    val runtime: String? = null
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

internal typealias RaftAgentStatusResolver = (String?) -> Flow<RaftAgentStatusIndicatorState?>

internal val EmptyRaftAgentStatusResolver: RaftAgentStatusResolver = { flowOf(null) }

internal fun raftScopedAgentStatusResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?
): RaftAgentStatusResolver {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
        ?: return EmptyRaftAgentStatusResolver
    return raftAgentStatusResolver(serverMembersStore, cleanServerId)
}

internal fun raftAgentStatusResolver(
    serverMembersStore: SharedServerMembersStore?,
    serverId: String?
): RaftAgentStatusResolver {
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
        ?: return EmptyRaftAgentStatusResolver
    return { memberId ->
        if (serverMembersStore == null) {
            flowOf(null)
        } else {
            serverMembersStore
                .memberStatusFlow(cleanServerId, memberId)
                .map { status -> status?.toRaftAgentStatusIndicatorState() }
        }
    }
}

@Composable
internal fun rememberRaftAgentStatusIndicatorState(
    agentStatusResolver: RaftAgentStatusResolver,
    memberId: String?
): RaftAgentStatusIndicatorState? {
    val statusFlow = remember(agentStatusResolver, memberId) { agentStatusResolver(memberId) }
    val status by statusFlow.collectAsState(null)
    // task #410: the external presence tier depends on `now` as well as the
    // fact. Re-resolve it whenever the live time context ticks (minute
    // boundary) or the underlying state changes, so "online" does not outlive
    // the 120s window without a new agent:seen.
    val liveNow = LocalRaftTimeContextProvider.current?.current?.nowEpochMillis
    val base = status
    return if (base?.external == true && base.lastSeenAt != null) {
        val now = liveNow ?: defaultNowMillis()
        base.copy(color = if (isExternalAgentSeenOnline(base.lastSeenAt, now)) RaftColor.AccentLime else RaftColor.AccentCyan)
    } else {
        base
    }
}

internal fun SharedServerMemberStatus.toRaftAgentStatusIndicatorState(): RaftAgentStatusIndicatorState =
    raftAgentStatusIndicatorState(
        status = status,
        presence = presence,
        statusLabel = statusLabel,
        external = external,
        lastSeenAt = lastSeenAt
    ).copy(
        detailKind = detailKind,
        activityToolName = activityToolName,
        runtime = runtime
    )

/**
 * [external] is required (no default): every producer must state it, so a
 * forgotten flag is a compile error rather than an external agent silently
 * rendering managed green (task #1119).
 */
internal fun raftAgentStatusIndicatorState(
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
): RaftAgentStatusIndicatorState {
    if (external) {
        // React StatusDot parity: `external` overrides activity unconditionally
        // ("no managed liveness green/red"). The tier is no longer always
        // cyan: `lastSeenAt` inside the online window (web
        // `EXTERNAL_AGENT_ONLINE_WINDOW_MS` = 120s) renders lime. Color,
        // pulse and label move together so the dot can never disagree with
        // the text beside it.
        val online = isExternalAgentSeenOnline(lastSeenAt, nowEpochMillis)
        return RaftAgentStatusIndicatorState(
            status = status,
            color = if (online) RaftColor.AccentLime else RaftColor.AccentCyan,
            pulsing = false,
            label = ExternalAgentStatusLabel,
            external = true,
            lastSeenAt = lastSeenAt
        )
    }
    return RaftAgentStatusIndicatorState(
        status = status,
        color =
            when (status) {
                AgentStatus.Online -> RaftColor.AccentLime
                AgentStatus.Working,
                AgentStatus.Thinking -> RaftColor.BrandYellow
                AgentStatus.Idle -> RaftColor.AccentLime
                AgentStatus.Disconnected,
                AgentStatus.Offline -> RaftColor.StatusNeutralGray
                AgentStatus.Error -> RaftColor.AccentOrange
                null ->
                    when (presence) {
                        Presence.Active -> RaftColor.AccentLime
                        Presence.Away -> RaftColor.BrandYellow
                        Presence.Offline -> RaftColor.StatusNeutralGray
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
    canonicalState: RaftAgentStatusIndicatorState?
): RaftAgentStatusIndicatorState? = canonicalState

/**
 * Shared projection used by Home DM and Settings search consumers.  A missing
 * member id, disabled presence, or unresolved resolver value must stay absent;
 * consumers must never manufacture an Offline indicator while loading.
 */
internal fun projectHomeAgentStatusIndicatorState(
    showPresence: Boolean,
    memberId: String?,
    canonicalState: RaftAgentStatusIndicatorState?
): RaftAgentStatusIndicatorState? =
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
    val state: RaftAgentStatusIndicatorState,
    val caption: ExternalAgentDetailCaption
)

internal const val ExternalAgentActiveThresholdMillis: Long = 5L * 60L * 1000L

/** Server sends `toISOString()` (UTC, `Z`); the repo parser also accepts offsets and epoch-millis strings. */
internal fun parseExternalAgentTimestampOrNull(iso: String?): Long? =
    parseRaftTimestampEpochMillisOrNull(iso, localOffsetMinutes = 0)

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
    val base = raftAgentStatusIndicatorState(status = null, presence = Presence.Offline, external = true)
    val seenAt = parseExternalAgentTimestampOrNull(lastSeenAt)
    val seenOnline = isExternalAgentSeenOnline(lastSeenAt, nowEpochMillis)
    if (status == null) {
        // task #410: even without the permission-gated externalStatus the
        // summary's lastSeenAt still carries the presence tiers.
        return when {
            seenOnline -> ExternalAgentDetailIndicator(
                base.copy(color = RaftColor.AccentLime),
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
            waiting -> RaftColor.StatusNeutralGray
            seenOnline -> RaftColor.AccentLime
            ageMs != null && ageMs < ExternalAgentActiveThresholdMillis -> RaftColor.AccentLime
            else -> RaftColor.AccentCyan
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

/**
 * What an agent's activity detail says, typed so the UI can localize the parts
 * the client or the server's `detailKind` define (web `getActivityTextDescriptor`):
 * `starting`/`runtime_starting` -> "Starting…", `compacting_context` ->
 * "Compacting context…", client-built tool labels -> the localized tool label.
 * Anything else is the server's own detail, shown verbatim.
 */
internal sealed interface RaftAgentActivityDetailText {
    data object Starting : RaftAgentActivityDetailText

    data object CompactingContext : RaftAgentActivityDetailText

    data class Tool(val toolName: String) : RaftAgentActivityDetailText

    data class Raw(val text: String) : RaftAgentActivityDetailText
}

/** Null for non-working agents and for a working agent with no detail. */
internal fun RaftAgentStatusIndicatorState.workingActivityDetailText(): RaftAgentActivityDetailText? {
    if (external || status != AgentStatus.Working) return null
    when (detailKind?.trim()) {
        "starting", "runtime_starting" -> return RaftAgentActivityDetailText.Starting
        "compacting_context" -> return RaftAgentActivityDetailText.CompactingContext
    }
    activityToolName?.trim()?.takeIf { it.isNotBlank() }?.let { return RaftAgentActivityDetailText.Tool(it) }
    val detail = label.trim()
    if (detail.isGenericWorkingLabel()) return null
    return RaftAgentActivityDetailText.Raw(detail)
}

/**
 * The indicator falls back to the bare status name ("Working") when no detail
 * arrived; that is web's "no detail" case, not a detail to print verbatim.
 */
private fun String.isGenericWorkingLabel(): Boolean {
    if (isBlank() || this == NeutralAgentStatusLabel) return true
    val bare = trimEnd('.', '…').trim()
    return bare.equals(AgentStatus.Working.label, ignoreCase = true)
}

@Composable
internal fun RaftAgentActivityDetailText.localizedText(): String =
    when (this) {
        RaftAgentActivityDetailText.Starting -> stringResource(MR.strings.agent_activity_starting)
        RaftAgentActivityDetailText.CompactingContext -> stringResource(MR.strings.agent_activity_compacting_context)
        is RaftAgentActivityDetailText.Tool ->
            when (toolName) {
                "read_file" -> stringResource(MR.strings.agent_activity_tool_read_file)
                "write_file" -> stringResource(MR.strings.agent_activity_tool_write_file)
                "upload_file" -> stringResource(MR.strings.agent_activity_tool_upload_file)
                "view_file" -> stringResource(MR.strings.agent_activity_tool_view_file)
                "schedule_reminder" -> stringResource(MR.strings.agent_activity_tool_schedule_reminder)
                "list_reminders" -> stringResource(MR.strings.agent_activity_tool_list_reminders)
                "cancel_reminder" -> stringResource(MR.strings.agent_activity_tool_cancel_reminder)
                "run_command" -> stringResource(MR.strings.agent_activity_tool_run_command)
                else -> stringResource(MR.strings.agent_activity_tool_using, toolName.truncateAgentToolLabel())
            }
        is RaftAgentActivityDetailText.Raw -> text
    }

/** Translate transport labels, retaining activity details supplied by the store. */
@Composable
internal fun RaftAgentStatusIndicatorState.localizedMemberStatusLabel(): String {
    // Server-kind and client-built details localize the same way the Home DM row does.
    workingActivityDetailText()
        ?.takeIf { it !is RaftAgentActivityDetailText.Raw }
        ?.let { return it.localizedText() }
    return when (label.trim().lowercase()) {
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
}
