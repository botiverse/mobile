package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import build.raft.app.data.RuntimeAuthAction
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthView
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.theme.RaftTheme

internal object AgentRuntimeSignInTestTags {
    const val SidebarShort = "sidebar_agent_sign_in_needed"
    const val MemberRowStatus = "member_row_sign_in_needed"
    const val MemberRowDetail = "member_row_sign_in_detail"
    const val MemberRowAction = "member_row_sign_in"
    const val DmHeaderStatus = "agent_dm_status_sign_in_needed"
    const val DmHeaderAction = "agent_dm_status_sign_in"
    const val ProfileAction = "agent_profile_sign_in"
}

/**
 * Web `signInRuntimeFor` (`packages/web/src/utils/runtimeSignInState.ts`, slock #15282):
 * the runtime whose single sign-in a Raft runtime uses, or null when Raft has none to offer
 * (no login action, or a per-provider runtime like Pi / OpenCode whose "signed out" may still
 * run on a key in the Computer's environment).
 */
internal fun signInRuntimeFor(raftRuntimeId: String?): RuntimeAuthRuntime? {
    val runtime = RuntimeAuthRuntime.forRaftRuntime(raftRuntimeId) ?: return null
    return runtime.takeIf { it.supports(RuntimeAuthAction.Login) && !it.perProvider }
}

/**
 * Web `runtimeSignInOffered` (`packages/web/src/components/machine/runtimeSignInGate.ts`,
 * slock #15282): whether to offer Sign in for [runtime] on this Computer. The Computer's live
 * sign-in state says `logged_out` (not `logged_in`, `unknown`, or `unavailable`), the server lets
 * this viewer act (`canAct`), and the Computer is reachable and ready (`availability == Ok`).
 */
internal fun runtimeSignInOffered(view: RuntimeAuthView?, runtime: RuntimeAuthRuntime): Boolean =
    view?.canAct == true &&
        view.availability == RuntimeAuthAvailability.Ok &&
        view.statusOf(runtime) == RuntimeAuthStatus.LoggedOut

/**
 * What the agent status surfaces (sidebar, member list, DM header, profile badge) show while an
 * agent's start is waiting for its runtime's sign-in on its Computer (web `AgentRuntimeSignInNeeded`,
 * botiverse/slock #15282).
 */
internal data class AgentRuntimeSignInState(
    val runtimeId: String,
    val machineId: String?,
    val fullText: String,
    val shortText: String,
    val detail: String,
    val offered: Boolean,
    val controller: ComputerRuntimeAuthController?,
    val runtimeDisplayName: String,
    val computerDisplayName: String?,
    val openUrl: (String) -> Unit,
)

/**
 * Web `useAgentRuntimeSignInNeeded` + `useAgentRuntimeSignInView` (botiverse/slock #15282):
 * resolves the sign-in-needed status and optional live Computer auth read for one agent.
 *
 * - Returns `null` for every other state, for an agent without a known runtime, and once the
 *   viewer's live read of the Computer reports `logged_in`.
 * - Never reads `GET …/runtime-auth` unless sign-in is needed, the runtime has a single sign-in,
 *   the viewer may see the Computer's sign-in state (`canViewMachine`), and the shell provided
 *   [LocalMembersRuntimeAccountUsageAccess].
 * - `offered` is true only when [runtimeSignInOffered] holds and an interactive auth host is bound.
 */
@Composable
internal fun rememberAgentRuntimeSignInState(
    state: RaftAgentStatusIndicatorState?,
    neededOverride: Boolean? = null,
    fallbackRuntime: String? = null,
    fallbackName: String? = null,
    fallbackMachineId: String? = null,
    fallbackMachineName: String? = null,
): AgentRuntimeSignInState? {
    val runtimeId = (state?.runtime ?: fallbackRuntime)?.trim()?.takeIf { it.isNotEmpty() }
    val needed =
        runtimeId != null &&
            (
                neededOverride
                    ?: (
                        state != null &&
                            agentRuntimeSignInNeeded(
                                status = state.status,
                                spawnFailureReason = state.spawnFailureReason,
                                lastRuntimeErrorSpawnFailureReason = state.lastRuntimeErrorSpawnFailureReason,
                                blocked = state.blocked != null,
                                likelyOnline = state.likelyOnline,
                                external = state.external,
                                paused = state.paused,
                            )
                        )
                )
    val machineId = (state?.machineId ?: fallbackMachineId)?.trim()?.takeIf { it.isNotEmpty() }
    val access = LocalMembersRuntimeAccountUsageAccess.current
    val authRuntime = if (needed) signInRuntimeFor(runtimeId) else null
    val owner = access?.owner
    val serverId = access?.serverId?.trim()?.takeIf { it.isNotEmpty() }
    val canView =
        if (access == null || serverId == null || machineId == null) {
            false
        } else {
            access.canViewMachine?.invoke(serverId, machineId)
                ?: (access.viewer == null || access.viewer.canEditMachines)
        }
    val active = needed && authRuntime != null && owner != null && serverId != null && machineId != null && canView
    val scope = rememberCoroutineScope()
    val controller =
        remember(active, access?.store, owner, serverId, machineId, runtimeId, authRuntime, access?.onIdentityChanged, access?.log) {
            if (!active || access == null || owner == null || serverId == null || machineId == null || runtimeId == null || authRuntime == null) {
                null
            } else {
                ComputerRuntimeAuthController(
                    serverId = serverId,
                    machineId = machineId,
                    raftRuntimeId = runtimeId,
                    runtime = authRuntime,
                    ops = StoreComputerRuntimeAuthOps(access.store, owner, serverId, machineId),
                    scope = scope,
                    log = { message -> access.log("agent_status_$message") },
                    onIdentityChanged = access.onIdentityChanged,
                )
            }
        }
    DisposableEffect(controller) { onDispose { controller?.dispose() } }
    LaunchedEffect(controller) { controller?.read() }
    val realtime = access?.auth?.realtime
    LaunchedEffect(controller, realtime) {
        if (controller != null && realtime != null) {
            realtime.events.collect(controller::onRealtime)
        }
    }
    if (!needed || runtimeId == null) return null
    // The failed start (above all a stored one, kept until the next good start) can outlive the
    // sign-in it waited for: once the viewer's live read says the runtime is signed in, drop the status.
    if (controller?.status is RuntimeAuthStatus.LoggedIn) return null

    val runtimeName = raftAgentRuntimeDisplayName(runtimeId)
    val agentName =
        (state?.agentDisplayName ?: fallbackName)?.trim()?.takeIf { it.isNotEmpty() }
            ?: stringResource(Res.string.thread_member_role_agent)
    val rawComputerName =
        (state?.machineName ?: fallbackMachineName)?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (serverId != null && machineId != null) {
                access?.machineName?.invoke(serverId, machineId)?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
    val computerName = rawComputerName ?: stringResource(Res.string.settings_computers_runtime_auth_this_computer)
    val fullText = stringResource(Res.string.agent_status_runtime_sign_in_full, runtimeName)
    val shortText = stringResource(Res.string.agent_status_runtime_sign_in_short)
    val detail = stringResource(Res.string.agent_status_runtime_sign_in_detail, agentName, runtimeName, computerName)
    val offered =
        controller != null &&
            authRuntime != null &&
            access?.auth != null &&
            runtimeSignInOffered(controller.view, authRuntime)
    return AgentRuntimeSignInState(
        runtimeId = runtimeId,
        machineId = machineId,
        fullText = fullText,
        shortText = shortText,
        detail = detail,
        offered = offered,
        controller = controller,
        runtimeDisplayName = runtimeName,
        computerDisplayName = rawComputerName,
        openUrl = access?.auth?.openUrl ?: {},
    )
}

/**
 * Web `RuntimeSignInAction` (`packages/web/src/components/machine/RuntimeSignInAction.tsx`,
 * slock #15282): quiet `Sign in` text link beside an agent's "Sign-in needed" status in the
 * member list and DM header, opening the Computer's `ComputerRuntimeSignInDialog` in place.
 * Accessible name names the runtime (`"Sign in to Kimi Code"`).
 */
@Composable
internal fun AgentRuntimeSignInAction(
    signIn: AgentRuntimeSignInState,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val controller = signIn.controller
    if (!signIn.offered || controller == null) return
    val signInTitle = stringResource(Res.string.settings_computers_runtime_auth_sign_in_title, signIn.runtimeDisplayName)
    RaftTextLink(
        text = stringResource(Res.string.settings_computers_runtime_auth_sign_in_action),
        onClick = controller::openSignIn,
        variant = RaftTextLinkVariant.Muted,
        style = RaftTheme.typography.footnote,
        maxLines = 1,
        modifier = modifier.testTag(testTag).semantics { contentDescription = signInTitle },
    )
    ComputerRuntimeAuthDialogHost(
        controller = controller,
        runtimeName = signIn.runtimeDisplayName,
        computerName = signIn.computerDisplayName,
        agents = null,
        openUrl = signIn.openUrl,
    )
}
