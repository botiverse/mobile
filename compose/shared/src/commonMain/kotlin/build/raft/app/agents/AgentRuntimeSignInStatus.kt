package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AgentActivityDetailKindRuntimeUnavailable
import build.raft.app.data.RealtimeGuardedActiveThreadSink
import build.raft.app.data.RuntimeAuthAction
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.canViewMachineRuntimeAccountUsage
import build.raft.app.data.selectComputerServerSlice
import build.raft.app.model.AgentAttention
import build.raft.app.model.AgentStatus
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
        view.statusOf(runtime) is RuntimeAuthStatus.LoggedOut

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

internal data class AgentRuntimeSignInResolution(
    val state: AgentRuntimeSignInState?,
    /** True when the Computer's live auth read confirmed the runtime is already signed in. */
    val clearedByLiveLogin: Boolean,
)

/**
 * Strips a synthesized `runtime_login_required` start-failure attention once the viewer's live
 * Computer auth read confirms the runtime is signed in (prevents a stale stored sign-in error
 * from repainting the error light / "Sign-in needed" after sign-in completes).
 */
internal fun RaftAgentStatusIndicatorState.withoutClearedLoginRequiredAttention(
    clearedByLiveLogin: Boolean,
): RaftAgentStatusIndicatorState {
    if (!clearedByLiveLogin) return this
    val startFailure = attention as? AgentAttention.StartFailure ?: return this
    if (startFailure.spawnFailure.reason != RuntimeLoginRequiredSpawnFailure) return this
    return copy(
        status = AgentStatus.Offline,
        color = RaftColor.PresenceOffline,
        label = AgentStatus.Offline.label,
        detailKind = detailKind?.takeUnless { it == AgentActivityDetailKindRuntimeUnavailable },
        spawnFailureReason = null,
        spawnFailure = null,
        attention = null,
    )
}

/**
 * Web `useAgentRuntimeSignInNeeded` + `useAgentRuntimeSignInView` (botiverse/slock #15282):
 * resolves the sign-in-needed status and optional live Computer auth read for one agent.
 *
 * - Returns `null` state for every other state, for an agent without a known runtime, and once the
 *   viewer's live read of the Computer reports `logged_in` (with `clearedByLiveLogin = true`).
 * - Never reads `GET …/runtime-auth` unless sign-in is needed, the runtime has a single sign-in,
 *   the viewer may see the Computer's sign-in state (`canViewMachine` or fail-closed `viewer` +
 *   `computersStateFlow`), and the shell provided [LocalMembersRuntimeAccountUsageAccess].
 * - Hosts [ComputerRuntimeAuthDialogHost] whenever an interactive sign-in dialog is open, even
 *   after `logged_in` arrives or `offered` flips transiently, so the user sees the completion
 *   confirmation and can close the session cleanly.
 */
@Composable
internal fun rememberAgentRuntimeSignInResolution(
    state: RaftAgentStatusIndicatorState?,
    neededOverride: Boolean? = null,
    fallbackRuntime: String? = null,
    fallbackName: String? = null,
    fallbackMachineId: String? = null,
    fallbackMachineName: String? = null,
): AgentRuntimeSignInResolution {
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
    // Collect computers state only for rows that actually need sign-in so Computer heartbeats
    // never recompose the shell or healthy rows.
    val computersStateFlow = if (needed) access?.computersStateFlow else null
    val computersState = if (computersStateFlow != null) computersStateFlow.collectAsState().value else null
    val matchedMachine =
        if (serverId != null && machineId != null && computersState != null) {
            computersState.selectComputerServerSlice(serverId).machines.firstOrNull { it.id == machineId }
        } else {
            null
        }
    // Fail-closed: never issue GET .../runtime-auth unless canViewMachine or viewer + machine proves access.
    val canView =
        if (access == null || serverId == null || machineId == null) {
            false
        } else if (access.canViewMachine != null) {
            access.canViewMachine.invoke(serverId, machineId)
        } else {
            val currentViewer = access.viewer
            when {
                currentViewer == null -> false
                currentViewer.canEditMachines -> true
                matchedMachine != null ->
                    canViewMachineRuntimeAccountUsage(
                        attachedByCurrentUser = matchedMachine.attachedByCurrentUser,
                        creatorId = matchedMachine.creatorUserId ?: matchedMachine.creator?.id,
                        currentUserId = currentViewer.currentUserId,
                        canEditMachines = currentViewer.canEditMachines,
                    )
                else -> false
            }
        }
    val active = needed && authRuntime != null && owner != null && serverId != null && machineId != null && canView
    val scope = rememberCoroutineScope()
    val currentLog by rememberUpdatedState(access?.log)
    val currentOnIdentityChanged by rememberUpdatedState(access?.onIdentityChanged)
    val controller =
        remember(active, access?.store, owner, serverId, machineId, runtimeId, authRuntime) {
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
                    log = { message -> currentLog?.invoke("agent_status_$message") },
                    onIdentityChanged = { currentOnIdentityChanged?.invoke() },
                )
            }
        }
    val realtime = access?.auth?.realtime
    DisposableEffect(controller, realtime) {
        if (controller == null) return@DisposableEffect onDispose {}
        val sink = RealtimeGuardedActiveThreadSink { _, event -> controller.onRealtime(event) }
        // On main: the controller and its sign-in session write Compose state and plain fields that
        // the dialog also writes.
        realtime?.registerGuardedActiveThreadSink(sink, onMain = true)
        onDispose {
            realtime?.unregisterGuardedActiveThreadSink(sink)
            controller.dispose()
        }
    }
    LaunchedEffect(controller) { controller?.read() }
    if (!needed || runtimeId == null) return AgentRuntimeSignInResolution(state = null, clearedByLiveLogin = false)

    val runtimeName = observeAgentRuntimeDisplayName(runtimeId)
    val rawComputerName =
        (state?.machineName ?: fallbackMachineName)?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (serverId != null && machineId != null) {
                access?.machineName?.invoke(serverId, machineId)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: matchedMachine?.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: matchedMachine?.hostname?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
    val openUrl = access?.auth?.openUrl ?: {}
    // Keep any open sign-in dialog hosted until the user dismisses it, even after the live read
    // flips to LoggedIn or offered flaps transiently (never strand an open Computer login session).
    if (controller != null && controller.dialog != null) {
        ComputerRuntimeAuthDialogHost(
            controller = controller,
            runtimeName = runtimeName,
            computerName = rawComputerName,
            agents = null,
            openUrl = openUrl,
        )
    }
    // The failed start (above all a stored one, kept until the next good start) can outlive the
    // sign-in it waited for: once the viewer's live read says the runtime is signed in, drop the status.
    if (controller?.status is RuntimeAuthStatus.LoggedIn) {
        return AgentRuntimeSignInResolution(state = null, clearedByLiveLogin = true)
    }

    val agentName =
        (state?.agentDisplayName ?: fallbackName)?.trim()?.takeIf { it.isNotEmpty() }
            ?: stringResource(Res.string.thread_member_role_agent)
    val computerName = rawComputerName ?: stringResource(Res.string.settings_computers_runtime_auth_this_computer)
    val fullText = stringResource(Res.string.agent_status_runtime_sign_in_full, runtimeName)
    val shortText = stringResource(Res.string.agent_status_runtime_sign_in_short)
    val detail = stringResource(Res.string.agent_status_runtime_sign_in_detail, agentName, runtimeName, computerName)
    val offered =
        controller != null &&
            authRuntime != null &&
            access?.auth != null &&
            runtimeSignInOffered(controller.view, authRuntime)
    return AgentRuntimeSignInResolution(
        state =
            AgentRuntimeSignInState(
                runtimeId = runtimeId,
                machineId = machineId,
                fullText = fullText,
                shortText = shortText,
                detail = detail,
                offered = offered,
                controller = controller,
                runtimeDisplayName = runtimeName,
                computerDisplayName = rawComputerName,
                openUrl = openUrl,
            ),
        clearedByLiveLogin = false,
    )
}

@Composable
internal fun rememberAgentRuntimeSignInState(
    state: RaftAgentStatusIndicatorState?,
    neededOverride: Boolean? = null,
    fallbackRuntime: String? = null,
    fallbackName: String? = null,
    fallbackMachineId: String? = null,
    fallbackMachineName: String? = null,
): AgentRuntimeSignInState? =
    rememberAgentRuntimeSignInResolution(
        state = state,
        neededOverride = neededOverride,
        fallbackRuntime = fallbackRuntime,
        fallbackName = fallbackName,
        fallbackMachineId = fallbackMachineId,
        fallbackMachineName = fallbackMachineName,
    ).state

/**
 * Web `RuntimeSignInAction` (`packages/web/src/components/machine/RuntimeSignInAction.tsx`,
 * slock #15282): quiet `Sign in` text link beside an agent's "Sign-in needed" status in the
 * member list, DM header, and agent profile, opening the Computer's `ComputerRuntimeSignInDialog`
 * in place (hosted by [rememberAgentRuntimeSignInState]).
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
    // DM header and other legacy shells render outside RaftDesignSystemTheme; wrap the design
    // system text link here so RaftTheme.typography / RaftTheme.colors are always provided.
    RaftDesignSystemTheme {
        RaftTextLink(
            text = stringResource(Res.string.settings_computers_runtime_auth_sign_in_action),
            onClick = controller::openSignIn,
            variant = RaftTextLinkVariant.Muted,
            style = RaftTheme.typography.footnote,
            maxLines = 1,
            modifier = modifier.testTag(testTag).semantics { contentDescription = signInTitle },
        )
    }
}
