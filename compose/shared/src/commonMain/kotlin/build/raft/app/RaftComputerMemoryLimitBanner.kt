package build.raft.app

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.data.SettingsComputerAgentScopesSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.StringResource

/**
 * Web `MachineDetailPanel` `agentMemoryLimitNotApplied` (botiverse/slock #10860): a per-agent memory
 * limit is configured on this online machine but cannot be applied, so its agents run without it.
 * Null when the warning is not shown.
 */
internal fun raftComputerMemoryLimitNotApplied(computer: SettingsComputerSnapshot): SettingsComputerAgentScopesSnapshot? =
    computer.agentScopes?.takeIf { computer.online && it.limitsNotApplied }

/** Web `machine.agentMemoryLimit.reason`: the daemon's closed reason enum as a short phrase; anything else is "unknown reason". */
internal fun raftComputerMemoryLimitReasonText(reason: String): StringResource =
    when (reason) {
        "cgroup_v2_unavailable" -> MR.strings.settings_computers_memory_limit_reason_cgroup_v2_unavailable
        "systemd_run_missing" -> MR.strings.settings_computers_memory_limit_reason_systemd_run_missing
        "systemctl_missing" -> MR.strings.settings_computers_memory_limit_reason_systemctl_missing
        "systemd_version_unknown" -> MR.strings.settings_computers_memory_limit_reason_systemd_version_unknown
        "user_bus_unreachable" -> MR.strings.settings_computers_memory_limit_reason_user_bus_unreachable
        "user_manager_cgroup_not_found" -> MR.strings.settings_computers_memory_limit_reason_user_manager_cgroup_not_found
        "memory_controller_not_delegated" -> MR.strings.settings_computers_memory_limit_reason_memory_controller_not_delegated
        "scope_trial_failed" -> MR.strings.settings_computers_memory_limit_reason_scope_trial_failed
        "bus_unreachable" -> MR.strings.settings_computers_memory_limit_reason_bus_unreachable
        "scope_start_failed" -> MR.strings.settings_computers_memory_limit_reason_scope_start_failed
        "bus_socket_missing" -> MR.strings.settings_computers_memory_limit_reason_bus_socket_missing
        "slice_property_failed" -> MR.strings.settings_computers_memory_limit_reason_slice_property_failed
        else -> MR.strings.settings_computers_memory_limit_reason_unknown
    }

/** The "configured but not applied" warning on a computer's detail page, under the version notice (web order). */
@Composable
internal fun RaftComputerMemoryLimitNotAppliedBanner(scopes: SettingsComputerAgentScopesSnapshot) {
    val title =
        scopes.reason?.let { reason ->
            stringResource(MR.strings.settings_computers_memory_limit_not_applied_title, stringResource(raftComputerMemoryLimitReasonText(reason)))
        } ?: stringResource(MR.strings.settings_computers_memory_limit_not_applied_title_no_reason)
    RaftBanner(
        description = stringResource(MR.strings.settings_computers_memory_limit_not_applied_body),
        status = RaftBannerStatus.Warning,
        title = title,
        size = RaftBannerSize.Sm,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
