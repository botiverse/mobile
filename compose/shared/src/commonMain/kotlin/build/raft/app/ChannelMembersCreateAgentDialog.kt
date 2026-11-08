package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import androidx.compose.runtime.*
import build.raft.app.data.*
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import androidx.compose.material3.Text
import kotlinx.coroutines.launch

/** Reuses the canonical create form and retains a created agent when joining fails. */
@Composable
internal fun ChannelMembersCreateAgentDialog(
    owner: AuthenticatedRouteOwner,
    channelId: ChannelId,
    initialName: String,
    mode: MembersCreateAgentMode = MembersCreateAgentMode.Managed,
    /** The channel the new agent joins, said in the dialog (web `autoJoinChannelName`). */
    channelName: String? = null,
    authority: AuthenticatedRouteAuthorityGateway,
    computersStore: SharedComputersStore,
    formStore: SharedCreateAgentFormStore,
    membersStore: SharedServerMembersStore,
    onDismiss: () -> Unit,
    onJoined: () -> Unit,
    /**
     * Reports the created agent so the caller can open its profile. External agents are
     * created to be configured straight away (task #4), so the caller needs the id; managed
     * agents keep the previous behaviour of just closing and refreshing.
     */
    onCreated: (MemberId) -> Unit = {},
    /** The no-computer dialog's "Add Computer"; null hides it. */
    onAddComputer: (() -> Unit)? = null,
    /** The server's plan from the session, for the agent limit when billing can't be read. */
    serverPlan: String? = null,
    /** "Upgrade for more" in the agent-limit banner; null leaves it as text. */
    onUpgrade: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val picker by remember(computersStore, owner) {
        computersStore.computerPickerSliceFlow(owner.serverId)
    }.collectAsState(SharedComputersPickerSlice())
    // Owner-scoped server directory, so a suggested name (blank query) avoids agent
    // @names already used on this server; the server's uniqueness check still decides.
    val serverMembers by remember(membersStore, owner) {
        membersStore.serverMembersEntryFlow(owner)
    }.collectAsState(remember(membersStore, owner) { membersStore.currentServerMembersEntry(owner) })
    // The plan's agent limit (web CreateAgentDialog `loadBilling`): at the limit the dialog says so
    // and Create stays disabled. Unread billing (a 403, offline) falls back to the plan's limits.
    var billing by remember(owner) { mutableStateOf<CreateAgentBilling?>(null) }
    LaunchedEffect(owner) {
        val receipt = authority.capture(owner) ?: return@LaunchedEffect
        val loaded = loadCreateAgentBilling(authority.networkClient(receipt))
        if (authority.isCurrent(receipt)) billing = loaded
    }
    val agentLimit =
        createAgentLimit(
            billing = billing,
            serverPlan = serverPlan,
            serverAgentCount = serverMembers.members.count { it.kind == build.raft.app.model.MemberKind.Agent },
        )
    var submitting by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var createdId by remember(owner) { mutableStateOf<String?>(null) }
    val failedLabel = stringResource(Res.string.common_error)
    val joinFailedLabel = stringResource(Res.string.thread_members_created_join_failed)
    LaunchedEffect(owner, mode) {
        if (mode.requiresComputerSelection()) {
            computersStore.dispatch(SharedComputersIntent.EnsureComputerPicker(owner.serverId, DataRequestPriority.Visible))
        }
    }
    fun submit(request: SharedCreateAgentDialogSubmit?) {
        if (submitting) return
        submitting = true
        error = null
        scope.launch {
            try {
                val receipt = authority.capture(owner)
                if (receipt == null) { error = failedLabel; return@launch }
                var agentId = createdId
                if (agentId == null) {
                    if (request == null) { error = failedLabel; return@launch }
                    val client = authority.networkClient(receipt)
                    val coordinator = SharedCreateAgentCoordinator(
                        RaftAgentsApiClient(RaftAgentsNetworkApi(client)),
                        RaftComputersApiClient(RaftComputersNetworkApi(client)),
                    )
                    val result = coordinator.createAgent(request.toCreateAgentRequest())
                    if (!authority.isCurrent(receipt)) return@launch
                    if (result !is DataResult.Content) {
                        // The server's words when it gave a reason (a model no longer offered, one set by an admin).
                        error = (result as? DataResult.Error)?.error?.let { createAgentFallbackErrorText(it, failedLabel) } ?: failedLabel
                        return@launch
                    }
                    formStore.rememberCreatedAgent(owner.serverId, request)
                    agentId = result.data.id
                    createdId = agentId
                }
                val joined = membersStore.addChannelMembersBatch(owner, createdAgentJoinRequest(channelId, agentId))
                if (!authority.isCurrent(receipt)) return@launch
                if (joined is DataResult.Content) {
                    // task #5 (Hands 906abab9): ordering is the contract — see
                    // createdAgentAfterJoinSteps. Opening the profile after the dismissal never
                    // ran because the dismissal cancels this composable's scope.
                    createdAgentAfterJoinSteps(mode, isExternalAgent = true).forEach { step ->
                        when (step) {
                            CreatedAgentAfterJoinStep.OpenProfile -> onCreated(MemberId(agentId))
                            CreatedAgentAfterJoinStep.DismissDialog -> onJoined()
                        }
                    }
                } else {
                    error = joinFailedLabel
                }
            } finally {
                submitting = false
            }
        }
    }
    if (createdId != null) {
        RaftUnifiedDialog(title = stringResource(Res.string.thread_action_add_member),
            onDismiss = onDismiss, dismissEnabled = !submitting) {
            Text(error ?: joinFailedLabel, style = RaftType.Body)
            RaftDialogFooter {
                RaftButton(
                    text = stringResource(Res.string.common_retry),
                    enabled = !submitting,
                    onClick = { submit(null) },
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg
                )
            }
        }
    } else {
        SharedCreateAgentDialog(
            external = mode == MembersCreateAgentMode.External,
            offersHosting = true,
            autoJoinChannelName = channelName,
            submitting = submitting, errorMessage = error, initialName = initialName,
            machines = if (mode.requiresComputerSelection()) picker.machines else emptyList(),
            machinesLoading = mode.requiresComputerSelection() && picker.initialLoadInFlight,
            machinesErrorMessage = if (mode.requiresComputerSelection() && picker.initialLoadError != null) failedLabel else null,
            onMachinesRetry = { computersStore.dispatch(SharedComputersIntent.RefreshComputerPicker(owner.serverId, DataRequestPriority.Visible)) },
            createAgentFormStore = formStore, createAgentServerId = owner.serverId,
            takenAgentNames = serverMembers.members.takenAgentNames(),
            currentAgentCount = agentLimit.usage,
            maxAgents = agentLimit.limit,
            limitPlanLabel = agentLimit.planLabel,
            onUpgrade = onUpgrade,
            onAddComputer = onAddComputer,
            onDismiss = { if (!submitting) onDismiss() }, onSubmit = { submit(it) },
        )
    }
}

/**
 * The created agent's join (web `joinCreatedAgent`): the same one-call batch as "Add Selected",
 * `{ userIds: [], agentIds: [id] }`, so a failed join is retried the same way.
 */
internal fun createdAgentJoinRequest(channelId: ChannelId, agentId: String): AddChannelMembersBatchRequest =
    AddChannelMembersBatchRequest(channelId, emptyList(), listOf(MemberId(agentId)))

/**
 * Ordered steps to perform once the created agent has been joined into the channel.
 *
 * task #5 (Hands 906abab9): #3132 called `onJoined` before `onCreated`. `onJoined` clears
 * `createAgentName`, which removes the dialog from composition and cancels the dialog's own
 * coroutine scope, so the follow-on navigation never ran. On device change #3132 looked like a
 * no-op while its unit tests stayed green, because nothing pinned this ordering.
 *
 * The sequence is a contract, so it is modelled here where a test can assert it rather than
 * being implied by statement order inside a composable body.
 */
internal enum class CreatedAgentAfterJoinStep { OpenProfile, DismissDialog }

internal fun createdAgentAfterJoinSteps(
    mode: MembersCreateAgentMode,
    isExternalAgent: Boolean
): List<CreatedAgentAfterJoinStep> {
    val openProfile =
        mode == MembersCreateAgentMode.External && isExternalAgent
    // Navigation must happen while the dialog's coroutine scope is still alive, i.e. before the
    // dismissal that tears the dialog down.
    return if (openProfile) {
        listOf(CreatedAgentAfterJoinStep.OpenProfile, CreatedAgentAfterJoinStep.DismissDialog)
    } else {
        listOf(CreatedAgentAfterJoinStep.DismissDialog)
    }
}
