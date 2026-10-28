package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import build.raft.app.model.AgentStatus
import build.raft.app.model.getRuntimeDisplayName
import build.raft.app.model.isExternalAgentRuntime

/**
 * What a Home DM row with an agent peer says it is doing right now, on the
 * right of the row (web `SidebarAgentMeta` live branch, `rf-srow__meta--live`,
 * slock staging `packages/web/src/components/layout/Sidebar.tsx`).
 *
 * Web shows it only while the agent is `working` or `thinking`, never for an
 * external agent (its presence is time-derived and the raw activity can
 * outlive it), and formats it with `formatActivityText`:
 *  - thinking -> "Thinking…" (the detail is not shown);
 *  - working  -> "Starting…" / "Compacting context…" by `detailKind`, a
 *    localized client-built tool label, else the activity detail verbatim
 *    ("Tool finished"), or "Working…" when there is none.
 */
internal sealed interface RaftHomeDmAgentLiveActivity {
    data object Thinking : RaftHomeDmAgentLiveActivity

    data object Working : RaftHomeDmAgentLiveActivity

    data class Detail(val detail: RaftAgentActivityDetailText) : RaftHomeDmAgentLiveActivity
}

/**
 * Projects the same resolved agent status the thread header and member rows
 * read ([RaftAgentStatusResolver] -> `SharedServerMembersStore.memberStatusFlow`,
 * fed by realtime `agent:activity`) into the row's live activity, or null when
 * the row shows no live activity.
 */
internal fun raftHomeDmAgentLiveActivity(
    state: RaftAgentStatusIndicatorState?
): RaftHomeDmAgentLiveActivity? {
    if (state == null || state.external) return null
    return when (state.status) {
        AgentStatus.Thinking -> RaftHomeDmAgentLiveActivity.Thinking
        AgentStatus.Working ->
            state.workingActivityDetailText()?.let(RaftHomeDmAgentLiveActivity::Detail)
                ?: RaftHomeDmAgentLiveActivity.Working
        else -> null
    }
}

/** The right side of an agent DM row: the live activity, else the runtime name (web `SidebarAgentMeta`). */
internal sealed interface RaftHomeDmAgentMeta {
    data class Live(val activity: RaftHomeDmAgentLiveActivity) : RaftHomeDmAgentMeta

    /** Web `getRuntimeDisplayName(agent.runtime)` in Space Mono (`SidebarItemMeta`). */
    data class Runtime(val label: String) : RaftHomeDmAgentMeta
}

internal fun raftHomeDmAgentMeta(state: RaftAgentStatusIndicatorState?): RaftHomeDmAgentMeta? {
    raftHomeDmAgentLiveActivity(state)?.let { return RaftHomeDmAgentMeta.Live(it) }
    val runtime = state?.runtime?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return RaftHomeDmAgentMeta.Runtime(raftAgentRuntimeDisplayName(runtime))
}

/** Web shared `EXTERNAL_AGENT_RUNTIME_DISPLAY_NAME` (not localized on web either). */
internal const val ExternalAgentRuntimeDisplayName = "External agent"

/** Web shared `getRuntimeDisplayName`: the external sentinel reads "External agent". */
internal fun raftAgentRuntimeDisplayName(runtime: String): String =
    if (isExternalAgentRuntime(runtime)) ExternalAgentRuntimeDisplayName else getRuntimeDisplayName(runtime)

/** One element of a DM row's trailing column, in render order. */
internal enum class RaftHomeDmTrailingSlot {
    UnreadBadge,
    /** Marked unread with no count (botiverse/slock #11795): web `SidebarPeerRowBody`'s quiet dot. */
    MarkedUnreadDot,
    Draft,
    AgentMeta
}

/**
 * Web `SidebarPeerRowBody`: an unread count is shown alone; then, marked unread
 * with no count ([markedUnread]), the quiet dot alone; otherwise the
 * draft mark, then the agent's meta (live activity or runtime). An App Bot
 * ([isAppBot]) runs on no runtime and has no activity: no meta (web `DmRow`
 * `agentMeta={... && !appBot ...}`), so a long name keeps its room.
 */
internal fun raftHomeDmTrailingSlots(
    badge: String?,
    hasDraft: Boolean,
    isAgent: Boolean,
    isAppBot: Boolean = false,
    markedUnread: Boolean = false
): List<RaftHomeDmTrailingSlot> =
    if (badge != null) {
        listOf(RaftHomeDmTrailingSlot.UnreadBadge)
    } else if (markedUnread) {
        listOf(RaftHomeDmTrailingSlot.MarkedUnreadDot)
    } else {
        buildList {
            if (hasDraft) add(RaftHomeDmTrailingSlot.Draft)
            if (isAgent && !isAppBot) add(RaftHomeDmTrailingSlot.AgentMeta)
        }
    }

/**
 * The agent whose presence a Home DM row shows (the status pixel on the avatar and the
 * trailing meta): none for a human, and none for an App Bot, which has no presence (web
 * `DmRow` / `AgentDmRow`: no `SidebarAgentActivityBadge` when `appBot`).
 */
internal fun raftHomeDmPresenceAgentId(memberId: String?, isAgent: Boolean, isAppBot: Boolean): String? =
    memberId?.takeIf { isAgent && !isAppBot && it.isNotBlank() }

@Composable
internal fun RaftHomeDmAgentLiveActivity.localizedText(): String =
    when (this) {
        RaftHomeDmAgentLiveActivity.Thinking -> stringResource(Res.string.home_dm_agent_activity_thinking)
        RaftHomeDmAgentLiveActivity.Working -> stringResource(Res.string.home_dm_agent_activity_working)
        is RaftHomeDmAgentLiveActivity.Detail -> detail.localizedText()
    }
