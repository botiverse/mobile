package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus

/**
 * The runtime editor shows the model-limits warning (web AgentProfileInfo, botiverse/slock #13492)
 * only while its draft is still the saved Built-in target: the server says the session reported a
 * Raft default ([defaultsReported]), the Runtime select is on `builtin`, and nothing was changed.
 */
internal fun membersAgentModelLimitsWarningInEditor(
    defaultsReported: Boolean,
    selectedRuntimeId: String,
    draftChanged: Boolean
): Boolean = defaultsReported && selectedRuntimeId.trim() == "builtin" && !draftChanged

/**
 * Web `AgentModelLimitsWarning`: a small warning banner with its icon, a title and one paragraph.
 * Only a warning: no limit values and no per-field source, and nothing at all unless the server
 * reported Raft defaults (no report never reads as "confirmed").
 */
@Composable
internal fun MembersAgentModelLimitsWarning(modifier: Modifier = Modifier) {
    RaftBanner(
        description = stringResource(Res.string.agent_model_limits_defaults_warning_body),
        status = RaftBannerStatus.Warning,
        modifier = modifier,
        title = stringResource(Res.string.agent_model_limits_defaults_warning_title),
        size = RaftBannerSize.Sm
    )
}
