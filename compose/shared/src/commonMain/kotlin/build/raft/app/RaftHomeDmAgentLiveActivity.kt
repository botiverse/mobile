package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.NeutralAgentStatusLabel
import build.raft.app.model.AgentStatus
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * What a Home DM row with an agent peer says it is doing right now, on the
 * right of the row (web `SidebarAgentMeta` live branch, `rf-srow__meta--live`,
 * slock staging `packages/web/src/components/layout/Sidebar.tsx`).
 *
 * Web shows it only while the agent is `working` or `thinking`, never for an
 * external agent (its presence is time-derived and the raw activity can
 * outlive it), and formats it with `formatActivityText`:
 *  - thinking -> "Thinking…" (the detail is not shown);
 *  - working  -> the activity detail verbatim ("Tool finished", "Running
 *    command…"), or "Working…" when there is none.
 */
internal sealed interface RaftHomeDmAgentLiveActivity {
    data object Thinking : RaftHomeDmAgentLiveActivity

    data object Working : RaftHomeDmAgentLiveActivity

    data class Detail(val text: String) : RaftHomeDmAgentLiveActivity
}

/**
 * Projects the same resolved agent status the thread header and member rows
 * read ([RaftAgentStatusResolver] -> `SharedServerMembersStore.memberStatusFlow`,
 * fed by realtime `agent:activity`) into the row's live activity, or null when
 * the row shows nothing.
 */
internal fun raftHomeDmAgentLiveActivity(
    state: RaftAgentStatusIndicatorState?
): RaftHomeDmAgentLiveActivity? {
    if (state == null || state.external) return null
    return when (state.status) {
        AgentStatus.Thinking -> RaftHomeDmAgentLiveActivity.Thinking
        AgentStatus.Working -> {
            val detail = state.label.trim()
            if (detail.isGenericWorkingLabel()) {
                RaftHomeDmAgentLiveActivity.Working
            } else {
                RaftHomeDmAgentLiveActivity.Detail(detail)
            }
        }
        else -> null
    }
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
internal fun RaftHomeDmAgentLiveActivity.localizedText(): String =
    when (this) {
        RaftHomeDmAgentLiveActivity.Thinking -> stringResource(MR.strings.home_dm_agent_activity_thinking)
        RaftHomeDmAgentLiveActivity.Working -> stringResource(MR.strings.home_dm_agent_activity_working)
        is RaftHomeDmAgentLiveActivity.Detail -> text
    }
