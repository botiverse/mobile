package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftUiBanner
import build.raft.app.components.RaftUiBannerSize
import build.raft.app.components.RaftUiBannerStatus
import build.raft.app.data.AgentBlockedState
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RuntimeAccountUsageViewer
import build.raft.app.data.selectComputerServerSlice
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.model.isRuntimeRetired
import build.raft.app.model.retiredRuntimeNeedsComputerUpdate
import build.raft.app.resources.*
import build.raft.app.shell.openRaftPage
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.toThreadMemberDetailPageData
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import org.jetbrains.compose.resources.StringResource

internal object AgentRuntimeRetiredTestTags {
    const val SidebarShort = "agent-runtime-retired-sidebar-short"
    const val MemberRowStatus = "agent-runtime-retired-member-row"
    const val DmHeaderStatus = "agent-runtime-retired-dm-header"
    const val Notice = "agent-runtime-retired-notice"
    const val ChangeRuntime = "agent-runtime-retired-change-runtime"
    const val OpenComputer = "agent-runtime-retired-open-computer"
}

/**
 * Web `runtimeRetiredLeadsWithComputerUpdate` (`utils/runtimeRetiredCopy.ts`, slock #16830):
 * only old Kimi CLI has an in-process replacement (Kimi Code) that can require updating the
 * Computer before switching runtimes.
 */
internal fun runtimeRetiredLeadsWithComputerUpdate(
    runtimeId: String?,
    computerUpdateFirst: Boolean,
): Boolean = runtimeId?.trim() == "kimi" && computerUpdateFirst

/**
 * Web `formatRuntimeRetiredText` short form (slock #16830):
 * where there is no room for the runtime name, the status reads the action the person must take —
 * "Computer update required" when updating the Computer comes first, else "Change runtime".
 */
internal fun agentRuntimeRetiredShortLabelRes(
    runtimeId: String?,
    computerUpdateFirst: Boolean,
): StringResource =
    if (runtimeRetiredLeadsWithComputerUpdate(runtimeId, computerUpdateFirst)) {
        Res.string.agent_status_computer_update_required
    } else {
        Res.string.agent_status_runtime_retired_change_runtime
    }

/**
 * Web `formatRuntimeRetiredDetail` (slock #16830): per-runtime explanation of why the agent
 * cannot start, what to switch to, and that its workspace and MEMORY.md are kept.
 */
internal fun agentRuntimeRetiredDetailRes(
    runtimeId: String?,
    computerUpdateFirst: Boolean,
): StringResource =
    when (runtimeId?.trim()) {
        "kimi" ->
            if (computerUpdateFirst) {
                Res.string.agent_status_runtime_retired_detail_kimi_update_computer
            } else {
                Res.string.agent_status_runtime_retired_detail_kimi
            }
        "gemini" -> Res.string.agent_status_runtime_retired_detail_gemini
        else -> Res.string.agent_status_runtime_retired_detail_copilot
    }

/**
 * What the status surfaces (sidebar, member list, DM header, agent profile, DM composer notice)
 * show while the server refuses the agent's starts because its runtime is retired (slock #16830).
 */
internal data class AgentRuntimeRetiredState(
    val runtimeId: String,
    /** Its Computer is too old to offer Kimi Code, so updating the Computer comes first. */
    val computerUpdateFirst: Boolean,
    val machineId: String?,
    val shortText: String,
    val fullText: String,
    val detail: String,
)

/**
 * Web `useAgentRuntimeRetired` (botiverse/slock #16830): resolves the retired-runtime status for
 * one agent. Returns null for any non-retired or external agent. Collects `computersStateFlow`
 * only when this agent is actually blocked on a retired runtime so Computer heartbeats never
 * recompose healthy rows.
 */
@Composable
internal fun rememberAgentRuntimeRetiredState(
    state: RaftAgentStatusIndicatorState?,
    blockedOverride: AgentBlockedState? = null,
    fallbackRuntime: String? = null,
    fallbackMachineId: String? = null,
    fallbackMachineRuntimes: List<String>? = null,
): AgentRuntimeRetiredState? {
    val effectiveBlocked = blockedOverride ?: state?.blocked
    if (effectiveBlocked != AgentBlockedState.RuntimeRetired || state?.external == true) return null
    val runtimeId =
        (state?.runtime?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackRuntime?.trim()?.takeIf { it.isNotEmpty() })
            ?.takeIf(::isRuntimeRetired)
            ?: return null
    val machineId =
        state?.machineId?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackMachineId?.trim()?.takeIf { it.isNotEmpty() }
    val access = LocalMembersRuntimeAccountUsageAccess.current
    val serverId = access?.serverId?.trim()?.takeIf { it.isNotEmpty() }
    val machineRuntimes =
        if (fallbackMachineRuntimes != null) {
            fallbackMachineRuntimes
        } else if (serverId != null && machineId != null && access?.computersStateFlow != null) {
            val computersState = access.computersStateFlow.collectAsState().value
            computersState
                .selectComputerServerSlice(serverId)
                .machines
                .firstOrNull { it.id == machineId }
                ?.runtimes
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    val computerUpdateFirst = retiredRuntimeNeedsComputerUpdate(runtimeId, machineRuntimes)
    val runtimeName = observeAgentRuntimeDisplayName(runtimeId)
    return AgentRuntimeRetiredState(
        runtimeId = runtimeId,
        computerUpdateFirst = computerUpdateFirst,
        machineId = machineId,
        shortText = stringResource(agentRuntimeRetiredShortLabelRes(runtimeId, computerUpdateFirst)),
        fullText = stringResource(Res.string.agent_status_runtime_retired_full, runtimeName),
        detail = stringResource(agentRuntimeRetiredDetailRes(runtimeId, computerUpdateFirst)),
    )
}

/**
 * Web `AgentRuntimeRetiredNotice` (botiverse/slock #16830): warning banner explaining why the
 * agent cannot start, with "Change runtime" (or "Open computer" when its Computer must update
 * first) only when [canChangeRuntime] is true.
 */
@Composable
internal fun AgentRuntimeRetiredNotice(
    retired: AgentRuntimeRetiredState,
    canChangeRuntime: Boolean,
    onChangeRuntime: (() -> Unit)?,
    onOpenComputer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val updateComputer = if (retired.computerUpdateFirst) onOpenComputer else null
    val actionSlot: (@Composable () -> Unit)? =
        when {
            !canChangeRuntime -> null
            updateComputer != null -> ({
                RaftButton(
                    text = stringResource(Res.string.agent_status_open_computer),
                    onClick = updateComputer,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                    modifier = Modifier.testTag(AgentRuntimeRetiredTestTags.OpenComputer),
                )
            })
            onChangeRuntime != null -> ({
                RaftButton(
                    text = stringResource(Res.string.agent_status_runtime_retired_change_runtime),
                    onClick = onChangeRuntime,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                    modifier = Modifier.testTag(AgentRuntimeRetiredTestTags.ChangeRuntime),
                )
            })
            else -> null
        }
    RaftDesignSystemTheme {
        RaftUiBanner(
            status = RaftUiBannerStatus.Warning,
            description = retired.detail,
            size = RaftUiBannerSize.Small,
            modifier = modifier.testTag(AgentRuntimeRetiredTestTags.Notice),
            action = actionSlot,
        )
    }
}

/**
 * Web `useCanChangeAgentRuntime` (slock #16830): owner/admin (`editAgents` / `canEditMachines`)
 * or the agent's human creator may change its runtime.
 */
internal fun canChangeRetiredAgentRuntime(
    viewer: RuntimeAccountUsageViewer?,
    creatorUserId: String?,
): Boolean {
    if (viewer == null) return false
    if (viewer.canEditMachines) return true
    val currentUserId = viewer.currentUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val creator = creatorUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return currentUserId == creator
}

/**
 * Web `AgentDmComposerNotices` -> `AgentRuntimeRetiredNotice` above a DM's composer (slock #16830):
 * warns that messages sent here wait unread until the agent's runtime is switched, offering
 * "Change runtime" (opening the agent's Profile tab with the runtime editor open) or "Open computer"
 * (when its Computer must update first) for viewers who may edit the agent.
 */
@Composable
internal fun ThreadDmAgentRuntimeRetiredNotice(
    dmAgentPeerId: String?,
    renderedPayload: RaftThreadScreenPayload,
    agentStatusResolver: RaftAgentStatusResolver,
    storeScope: RaftShellStoreScope,
    modifier: Modifier = Modifier,
) {
    val peerId = dmAgentPeerId?.trim()?.takeIf { it.isNotEmpty() } ?: return
    if (renderedPayload.surfaceKind != RaftThreadSurfaceKind.DirectMessage) return
    val status = rememberRaftAgentStatusIndicatorState(agentStatusResolver, peerId)
    val retired = rememberAgentRuntimeRetiredState(status) ?: return
    val access = LocalMembersRuntimeAccountUsageAccess.current
    val canChange = canChangeRetiredAgentRuntime(access?.viewer, status?.creatorUserId)
    val pager = LocalActivity.current.getPager() as Pager
    val serverId =
        access?.serverId?.trim()?.takeIf { it.isNotEmpty() }
            ?: storeScope.initialServerId?.trim()?.takeIf { it.isNotEmpty() }
    val onChangeRuntime: () -> Unit = {
        val pageData =
            renderedPayload.toThreadMemberDetailPageData(
                MemberDetailIdentity(
                    memberId = MemberId(peerId),
                    kind = MemberKind.Agent,
                    sourceServerId = serverId?.let(::ServerId),
                )
            )
        if (pageData != null) {
            pageData.put("initialAgentTab", MembersAgentDetailTab.Profile.serverOrderKey)
            pageData.put(MemberDetailOpenRuntimeEditorKey, true)
            pager.openRaftPage(
                pageName = MEMBER_DETAIL_PAGE,
                pageData = pageData,
                storeScopeOverride = storeScope,
            )
        }
    }
    val onOpenComputer: (() -> Unit)? =
        settingsComputerRouteIdentity(serverId, retired.machineId)?.let { target ->
            { pager.openSettingsComputerPage(storeScope, target) }
        }
    AgentRuntimeRetiredNotice(
        retired = retired,
        canChangeRuntime = canChange,
        onChangeRuntime = onChangeRuntime,
        onOpenComputer = onOpenComputer,
        modifier = modifier,
    )
}

