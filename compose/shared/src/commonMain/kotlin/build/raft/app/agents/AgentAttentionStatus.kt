package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import build.raft.app.data.NeutralAgentStatusLabel
import build.raft.app.data.defaultNowMillis
import build.raft.app.model.AgentAttention
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import org.jetbrains.compose.resources.StringResource

internal object AgentAttentionTestTags {
    const val SidebarShort = "sidebar_agent_attention"
    const val MemberRowStatus = "member_row_attention"
    const val DmHeaderStatus = "agent_dm_status_attention"
    const val ProfileStartFailureDetail = "agent-start-failure-detail"
}

/**
 * Web `START_FAILURE_SHORT_IDS` (`packages/web/src/utils/activity.ts`, botiverse/slock #17124):
 * the short status word for a stored failed start only a person gets out of.
 */
internal fun agentStartFailureAttentionShortRes(reason: String?): StringResource =
    when (reason?.trim()) {
        "model_unsupported" -> Res.string.agent_status_attention_model_unavailable
        "runtime_login_required" -> Res.string.agent_status_runtime_sign_in_short
        "session_needs_reset" -> Res.string.agent_status_attention_needs_restart
        else -> Res.string.agent_readiness_generic_title
    }

/**
 * Web `formatAgentAttentionParts` + `formatAgentAttentionText` (botiverse/slock #17124):
 * [short] for compact columns / chips / sidebar, [detail] for profile lines and hover tooltips,
 * and [fullText] (`"{title}: {detail}"`, or [short] when [detail] is blank) for accessible names
 * and Computer agent rows.
 */
internal data class AgentAttentionParts(
    val short: String,
    val detail: String,
    val fullText: String,
)

@Composable
internal fun rememberAgentAttentionParts(
    attention: AgentAttention?,
    activityDetail: String? = null,
    timeContext: RaftTimeContext? = null,
): AgentAttentionParts? {
    if (attention == null) return null
    return when (attention) {
        is AgentAttention.Readiness -> {
            val copy =
                formatAgentReadinessDisplayCopy(
                    readiness = attention.readiness,
                    fallbackRuntimeId = attention.runtimeId,
                    timeContext = timeContext,
                )
            val short = copy.title
            val detail = copy.body
            val fullText =
                if (detail.isNotBlank()) {
                    stringResource(Res.string.agent_status_attention_with_detail, short, detail)
                } else {
                    short
                }
            AgentAttentionParts(short = short, detail = detail, fullText = fullText)
        }
        is AgentAttention.StartFailure -> {
            val live = currentRaftTimeContextProvider?.current
            val nowMs = timeContext?.nowEpochMillis ?: live?.nowEpochMillis ?: defaultNowMillis()
            val clock = agentSpawnFailureTimeContext(timeContext, live, nowMs)
            val cleanDetail =
                activityDetail?.trim().orEmpty().takeUnless { raw ->
                    raw.isEmpty() ||
                        raw.equals("Error", ignoreCase = true) ||
                        raw.equals("Offline", ignoreCase = true) ||
                        raw == NeutralAgentStatusLabel
                }.orEmpty()
            val short = stringResource(agentStartFailureAttentionShortRes(attention.spawnFailure.reason))
            val detail =
                agentSpawnFailureCopy(attention.spawnFailure, cleanDetail, nowMs)
                    ?.let { agentSpawnFailureCopyText(it, clock) }
                    ?: cleanDetail
            val fullText =
                if (detail.isNotBlank()) {
                    stringResource(Res.string.agent_status_attention_with_detail, short, detail)
                } else {
                    short
                }
            AgentAttentionParts(short = short, detail = detail, fullText = fullText)
        }
    }
}

@Composable
internal fun rememberAgentAttentionParts(
    state: RaftAgentStatusIndicatorState?,
    appBot: Boolean = false,
    timeContext: RaftTimeContext? = null,
): AgentAttentionParts? {
    if (state == null || appBot || state.external || state.paused || state.blocked != null) return null
    val attention = state.attention ?: return null
    val detail =
        state.lastRuntimeErrorMessage?.trim()?.takeIf { it.isNotEmpty() }
            ?: state.label.trim().takeUnless { raw ->
                raw.isEmpty() ||
                    raw.equals("Error", ignoreCase = true) ||
                    raw.equals("Offline", ignoreCase = true) ||
                    raw == NeutralAgentStatusLabel
            }
    return rememberAgentAttentionParts(
        attention = attention,
        activityDetail = detail,
        timeContext = timeContext,
    )
}

/**
 * Web `AgentStatusBadge` `agent-start-failure-detail` (botiverse/slock #17133):
 * the full start-failure sentence rendered below the status chip when [showActivityDetail] is true.
 */
@Composable
internal fun AgentStartFailureDetailNotice(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RaftText(
            text = text,
            style = RaftTheme.typography.footnote,
            color = RaftTheme.colors.foregroundMuted,
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .testTag(AgentAttentionTestTags.ProfileStartFailureDetail)
                    .semantics { contentDescription = text },
        )
    }
}
