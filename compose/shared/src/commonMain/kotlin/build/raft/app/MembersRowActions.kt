package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.pager.Pager
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.AgentResetMode
import build.raft.app.data.AgentResetRequest
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.serverRoleCanControlAgentRuntime
import build.raft.app.data.serverRoleCanStopAgents
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What a Members row can do beyond opening the profile (web `MemberActions`,
 * botiverse/slock `features/members/memberActionsContext.ts`): its long-press menu (Message; for an
 * agent Edit agent, Start or Stop, Restart / reset) and an editable agent's pencil. The host builds
 * it ([rememberMembersRowActions]); without it a row only opens the profile.
 */
internal class MembersRowActions(
    /** The reader's role on this server (`owner` / `admin` / `member` / `guest`); null while unknown. */
    val viewerRole: String?,
    val currentUserId: String?,
    val currentServerId: String?,
    /** Opens the DM with this member (an existing one first), as the profile's Message does. */
    val openDirectMessage: (MemberProfile) -> Unit,
    /** Start, Stop, Restart or a reset: the profile's own calls (`POST /agents/:id/start|stop|reset`). */
    val runLifecycleAction: suspend (MemberProfile, MembersAgentLifecycleAction) -> DataResult<AgentLifecycleActionResult>,
    /** Edit agent: the agent's profile with its Edit runtime config dialog open. */
    val editAgent: (MemberProfile) -> Unit,
    /** A failed Start or Stop: an error toast with the request's trace id. */
    val onError: (message: String, error: ScreenError) -> Unit,
)

/** Web `agentRuntimeControls`: which of Start, Stop and Restart / reset the menu offers now. */
internal data class MembersRowAgentControls(val start: Boolean, val stop: Boolean, val restartReset: Boolean) {
    val any: Boolean get() = start || stop || restartReset
}

private fun MemberProfile.membersRowCreatedBy(userId: String?): Boolean =
    !userId.isNullOrBlank() && agentCreatorUserId == userId.trim()

/** Web `canViewAgentPrivateSurfaces` behind a bounded projection: another server's agent gets nothing. */
private fun MemberProfile.membersRowIsOtherServers(currentServerId: String?): Boolean {
    val own = currentServerId?.trim().orEmpty()
    val its = serverId?.value?.trim().orEmpty()
    return own.isNotEmpty() && its.isNotEmpty() && own != its
}

/**
 * Web `canMessageEntry`: an agent while it is not archived; a person by the profile's rule (a Guest
 * has no DMs, either side; no one who has left). Nothing to offer means no menu at all.
 */
internal fun membersRowCanMessage(member: MemberProfile, actions: MembersRowActions): Boolean =
    member.kind != MemberKind.System &&
        membersDirectMessageEligibility(
            member = member,
            currentUserId = actions.currentUserId,
            currentServerId = actions.currentServerId,
            deleted = member.kind == MemberKind.Agent && !member.agentDeletedAt.isNullOrBlank(),
            viewerRole = actions.viewerRole,
        ) != MembersDirectMessageEligibility.Unavailable

/**
 * Web `canEditAgentRuntimeConfig`: `editAgents` (owner, admin) or the agent's human creator, on a
 * live agent of this server that is neither external nor an App's.
 */
internal fun membersRowCanEditAgent(member: MemberProfile, actions: MembersRowActions): Boolean {
    if (member.kind != MemberKind.Agent) return false
    if (!member.agentDeletedAt.isNullOrBlank() || !member.managedByAppInstallId.isNullOrBlank()) return false
    if (member.isExternal || member.membersRowIsOtherServers(actions.currentServerId)) return false
    val role = actions.viewerRole?.trim()?.lowercase()
    return role == "owner" || role == "admin" || member.membersRowCreatedBy(actions.currentUserId)
}

/**
 * Web `agentRuntimePermissions` + `agentRuntimeControls`: runtime control is `controlAgentRuntime`
 * (owner, admin, member) or the creator's, never an external agent's; Stop also needs `stopAgents`
 * (owner, admin) or the creator. Start while it is not running and its computer is reachable; Stop
 * while it runs (or waits on an offline computer it was not stopped on); Restart / reset whenever
 * its computer is reachable. [online] null (presence not known yet) offers neither Start nor Stop,
 * as the profile's head does.
 */
internal fun membersRowAgentControls(
    member: MemberProfile,
    viewerRole: String?,
    currentUserId: String?,
    currentServerId: String?,
    online: Boolean?,
    computerOffline: Boolean,
): MembersRowAgentControls {
    val creator = member.membersRowCreatedBy(currentUserId)
    val canControl =
        member.kind == MemberKind.Agent &&
            !member.isExternal &&
            !member.membersRowIsOtherServers(currentServerId) &&
            (serverRoleCanControlAgentRuntime(viewerRole?.trim()?.lowercase()) || creator)
    val canStop = canControl && (serverRoleCanStopAgents(viewerRole?.trim()?.lowercase()) || creator)
    val stoppedByPerson = member.agentLifecycleStatus == "stopped"
    return MembersRowAgentControls(
        start = canControl && online == false && !computerOffline,
        stop = canControl && canStop && (online == true || (computerOffline && !stoppedByPerson)),
        restartReset = canControl && !computerOffline,
    )
}

/** Whether the agent runs now, from its live status; null while unknown. */
internal fun membersRowAgentOnline(status: AgentStatus?): Boolean? =
    when (status) {
        null -> null
        AgentStatus.Online, AgentStatus.Working, AgentStatus.Thinking, AgentStatus.Idle -> true
        AgentStatus.Offline, AgentStatus.Disconnected, AgentStatus.Error -> false
    }

/** Web `isMachineOffline`: the agent's computer is known and not online. */
internal fun membersRowComputerOffline(member: MemberProfile, machines: List<ComputerSummary>): Boolean {
    val id =
        member.permissionBadges.firstNotNullOfOrNull { badge ->
            badge.removePrefix("computer-id:").takeIf { badge.startsWith("computer-id:") && it.isNotBlank() }
        } ?: return false
    val machine = machines.firstOrNull { it.id == id } ?: return false
    return machine.status != ComputerStatus.Online
}

/** The Stop confirmation and the Restart / reset chooser a row's menu opens (one at a time, page-wide). */
internal class MembersRowDialogState {
    var stopTarget by mutableStateOf<MemberProfile?>(null)
    var resetTarget by mutableStateOf<MemberProfile?>(null)
    var resetSelection by mutableStateOf(MembersAgentLifecycleAction.Restart)
    var busy by mutableStateOf(false)
    var resetError by mutableStateOf<String?>(null)
}

private fun DataResult<AgentLifecycleActionResult>.membersRowError(action: MembersAgentLifecycleAction): ScreenError? =
    when (this) {
        is DataResult.Content -> if (data.success) null else ScreenError("agent_${action.actionKey}_failed")
        is DataResult.Empty -> ScreenError(reason.name, retryable = false)
        is DataResult.Error -> error
        is DataResult.NoPermission -> ScreenError(blocker.code, retryable = false)
    }

private suspend fun MembersRowActions.runSafely(
    member: MemberProfile,
    action: MembersAgentLifecycleAction,
): DataResult<AgentLifecycleActionResult> =
    try {
        runLifecycleAction(member, action)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        DataResult.Error(ScreenError("agent_${action.actionKey}_failed"))
    }

/**
 * The dialogs the rows' menus open, the profile's own: Stop's confirmation
 * ([MembersLifecycleConfirmation]) and the Restart / reset chooser ([MembersRestartResetDialog]).
 */
@Composable
internal fun MembersRowActionDialogs(state: MembersRowDialogState, actions: MembersRowActions) {
    val scope = rememberCoroutineScope()
    val errorStrings = localizedErrorPresentationStrings()
    val stopTarget = state.stopTarget
    if (stopTarget != null) {
        val close: () -> Unit = { if (!state.busy) state.stopTarget = null }
        RaftUnifiedDialog(
            title = stringResource(Res.string.member_action_stop_agent).raftDisplayUppercase(),
            dismissEnabled = !state.busy,
            dismissOnOutsideTap = false,
            maxWidth = 384.dp,
            contentPadding = 24.dp,
            onDismiss = close,
        ) {
            MembersLifecycleConfirmation(
                action = MembersAgentLifecycleAction.Stop,
                agentName = stopTarget.displayName,
                busy = state.busy,
                onConfirm = {
                    if (!state.busy) {
                        state.busy = true
                        scope.launch {
                            val action = MembersAgentLifecycleAction.Stop
                            val error = actions.runSafely(stopTarget, action).membersRowError(action)
                            state.busy = false
                            state.stopTarget = null
                            if (error != null) actions.onError(error.userFacingMessage(errorStrings), error)
                        }
                    }
                },
                onCancel = close,
            )
        }
    }
    val resetTarget = state.resetTarget
    if (resetTarget != null) {
        MembersRestartResetDialog(
            agentName = resetTarget.displayName,
            selectedAction = state.resetSelection,
            busy = state.busy,
            errorMessage = state.resetError,
            onSelectedActionChange = {
                state.resetSelection = it
                state.resetError = null
            },
            onConfirm = {
                if (!state.busy) {
                    state.busy = true
                    state.resetError = null
                    scope.launch {
                        val action = state.resetSelection
                        val error = actions.runSafely(resetTarget, action).membersRowError(action)
                        state.busy = false
                        if (error == null) {
                            state.resetTarget = null
                            state.resetSelection = MembersAgentLifecycleAction.Restart
                        } else {
                            state.resetError = error.userFacingMessage(errorStrings)
                        }
                    }
                }
            },
            onDismiss = {
                if (!state.busy) {
                    state.resetTarget = null
                    state.resetSelection = MembersAgentLifecycleAction.Restart
                    state.resetError = null
                }
            },
        )
    }
}

/**
 * A row's menu (web `MemberMenuTrigger`, a long press on touch): Message for a person; for an
 * agent Direct message, Edit agent, then Start or Stop and Restart / reset, by the profile's rules.
 * The agent's live state is read only while the menu is open.
 */
@Composable
internal fun MembersRowMenu(
    member: MemberProfile,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: MembersRowActions,
    dialogs: MembersRowDialogState,
    machines: List<ComputerSummary>,
    agentStatusResolver: RaftAgentStatusResolver,
) {
    RaftDropdownMenu(expanded = expanded, onDismissRequest = onDismiss, minWidth = 192.dp) {
        val isAgent = member.kind == MemberKind.Agent
        RaftMenuItem(
            text =
                if (isAgent) {
                    stringResource(Res.string.members_menu_direct_message)
                } else {
                    stringResource(Res.string.member_label_message)
                },
            leadingIcon = { MembersMenuIcon(RaftLucideIconName.DirectMessage) },
            onClick = {
                onDismiss()
                actions.openDirectMessage(member)
            },
        )
        if (isAgent) {
            if (membersRowCanEditAgent(member, actions)) {
                RaftMenuItem(
                    text = stringResource(Res.string.members_menu_edit_agent),
                    leadingIcon = { MembersMenuIcon(RaftLucideIconName.Pencil) },
                    onClick = {
                        onDismiss()
                        actions.editAgent(member)
                    },
                )
            }
            val status = rememberRaftAgentStatusIndicatorState(agentStatusResolver, member.id.value)?.status
            val controls =
                membersRowAgentControls(
                    member = member,
                    viewerRole = actions.viewerRole,
                    currentUserId = actions.currentUserId,
                    currentServerId = actions.currentServerId,
                    online = membersRowAgentOnline(status ?: member.agentStatus),
                    computerOffline = membersRowComputerOffline(member, machines),
                )
            if (controls.any) RaftMenuSeparator()
            if (controls.start || controls.stop) {
                val scope = rememberCoroutineScope()
                val errorStrings = localizedErrorPresentationStrings()
                val latestActions by rememberUpdatedState(actions)
                RaftMenuItem(
                    text =
                        if (controls.stop) {
                            stringResource(Res.string.member_action_stop_agent)
                        } else {
                            stringResource(Res.string.member_action_start_agent)
                        },
                    leadingIcon = {
                        MembersMenuIcon(if (controls.stop) RaftLucideIconName.Square else RaftLucideIconName.Play)
                    },
                    onClick = {
                        onDismiss()
                        if (controls.stop) {
                            dialogs.stopTarget = member
                        } else {
                            // Start asks nothing (web `startAgent`); a refusal says why.
                            val run = latestActions
                            scope.launch {
                                val action = MembersAgentLifecycleAction.Start
                                run.runSafely(member, action).membersRowError(action)?.let { error ->
                                    run.onError(error.userFacingMessage(errorStrings), error)
                                }
                            }
                        }
                    },
                )
            }
            if (controls.restartReset) {
                RaftMenuItem(
                    text = stringResource(Res.string.member_label_restart_reset),
                    leadingIcon = { MembersMenuIcon(RaftLucideIconName.RotateCcw) },
                    onClick = {
                        onDismiss()
                        dialogs.resetSelection = MembersAgentLifecycleAction.Restart
                        dialogs.resetError = null
                        dialogs.resetTarget = member
                    },
                )
            }
        }
    }
}

/**
 * Edit agent at the end of an editable agent's row (web `EditAgentButton`): a ghost icon button
 * with the Pencil, always shown by touch, that opens the agent's Edit runtime config.
 */
@Composable
internal fun MembersRowEditAgentButton(member: MemberProfile, onEdit: (MemberProfile) -> Unit) {
    RaftIconButton(
        onClick = { onEdit(member) },
        tooltip = stringResource(Res.string.members_menu_edit_agent),
        contentDescription = stringResource(Res.string.members_edit_agent_named, member.displayName),
        variant = RaftButtonVariant.Ghost,
        size = RaftIconButtonSize.Sm,
    ) {
        RaftContentLucideIcon(RaftLucideIconName.Pencil, size = 16.dp)
    }
}

/**
 * [MembersRowActions] for a Members page host: the DM opened as the profile's Message opens it
 * ([openMemberDirectMessage], in the app shell's Chat when [openInShell] takes it), and the
 * profile's lifecycle calls on [RealMemberRepository].
 */
@Composable
internal fun rememberMembersRowActions(
    pager: Pager,
    bridge: BridgeModule,
    storeScope: RaftShellStoreScope,
    viewerRole: String?,
    currentUserId: String?,
    currentServerId: String?,
    onEditAgent: (MemberProfile) -> Unit,
    openInShell: ((ThreadLaunchConversationIdentity) -> Boolean)? = null,
    timeContext: RaftTimeContext? = null,
): MembersRowActions {
    val scope = rememberCoroutineScope()
    val errorStrings = localizedErrorPresentationStrings()
    val unavailableMessage = stringResource(Res.string.member_error_open_direct_message)
    val errorTemplate =
        stringResource(Res.string.member_error_open_direct_message_detail, MembersRowSentinel)
            .replace(MembersRowSentinel, "%1\$s")
    val permissionMessage = stringResource(Res.string.member_error_open_direct_message_permission)
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val sharedHomeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val channelRepository =
        remember(networkClient, authStore) {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { message -> bridge.log("members_row_channel_repository $message") }
            )
        }
    val memberRepository =
        remember(networkClient, authStore) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("members_row_member_repository $message") }
            )
        }
    val latestEdit by rememberUpdatedState(onEditAgent)
    val latestOpenInShell by rememberUpdatedState(openInShell)
    val latestErrorStrings by rememberUpdatedState(errorStrings)
    val latestUnavailable by rememberUpdatedState(unavailableMessage)
    val latestErrorTemplate by rememberUpdatedState(errorTemplate)
    val latestPermission by rememberUpdatedState(permissionMessage)
    val latestTimeContext by rememberUpdatedState(timeContext)
    return remember(viewerRole, currentUserId, currentServerId, pager, storeScope, bridge) {
        MembersRowActions(
            viewerRole = viewerRole,
            currentUserId = currentUserId,
            currentServerId = currentServerId,
            openDirectMessage = { member ->
                bridge.log("members_row_message memberId=${member.id.value}")
                scope.launch {
                    openMemberDirectMessage(member, authStore, channelRepository, sharedHomeStore)
                        ?.openMembersDirectMessageResult(
                            pager = pager,
                            storeScope = storeScope,
                            openInShell = latestOpenInShell,
                            member = member,
                            timeContext = latestTimeContext,
                            unavailableMessage = latestUnavailable,
                            errorTemplate = latestErrorTemplate,
                            permissionMessage = latestPermission,
                            errorStrings = latestErrorStrings,
                            onUnavailableAction = { bridge.toast(it) },
                            onErrorAction = { message, traceId -> bridge.errorToast(message, traceId) },
                        )
                }
            },
            runLifecycleAction = { member, action ->
                bridge.log("members_row_lifecycle action=${action.actionKey} memberId=${member.id.value}")
                when (action) {
                    MembersAgentLifecycleAction.Start ->
                        memberRepository.startAgent(DataRequestPriority.Foreground, member.id)
                    MembersAgentLifecycleAction.Stop ->
                        memberRepository.stopAgent(DataRequestPriority.Foreground, member.id)
                    MembersAgentLifecycleAction.Restart,
                    MembersAgentLifecycleAction.ResetSession,
                    MembersAgentLifecycleAction.ResetFull ->
                        memberRepository.resetAgent(
                            DataRequestPriority.Foreground,
                            AgentResetRequest(member.id, action.resetMode ?: AgentResetMode.Restart)
                        )
                    MembersAgentLifecycleAction.Delete,
                    MembersAgentLifecycleAction.UnassignMachine ->
                        DataResult.Error(ScreenError("agent_${action.actionKey}_unsupported", retryable = false))
                }
            },
            editAgent = { member -> latestEdit(member) },
            onError = { message, error -> bridge.errorToast(message, error) },
        )
    }
}

private const val MembersRowSentinel = "__RAFT_MEMBERS_ROW_NAME__"
