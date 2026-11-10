package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.*
import build.raft.app.data.*
import build.raft.app.model.MemberKind
import kotlinx.coroutines.launch

/**
 * A Computer card's ⋯ → Create agent (web `RackRowMenu`, the Computer page's `machine.detail.create`):
 * the canonical create form with [machineId] preselected (the card offers it only while the computer
 * is online; should it drop offline meanwhile, the form picks as usual). The user may change the
 * computer. Built like
 * [ChannelMembersCreateAgentDialog] without the channel join: [onCreated] gets the new agent's id
 * after the create succeeded (the caller closes the dialog and refreshes).
 */
@Composable
internal fun RaftComputerCreateAgentDialog(
    owner: AuthenticatedRouteOwner,
    machineId: String,
    authority: AuthenticatedRouteAuthorityGateway,
    computersStore: SharedComputersStore,
    formStore: SharedCreateAgentFormStore,
    membersStore: SharedServerMembersStore,
    onDismiss: () -> Unit,
    onCreated: (agentId: String) -> Unit,
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
    // A suggested name avoids the @names already used on this server.
    val serverMembers by remember(membersStore, owner) {
        membersStore.serverMembersEntryFlow(owner)
    }.collectAsState(remember(membersStore, owner) { membersStore.currentServerMembersEntry(owner) })
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
            serverAgentCount = serverMembers.members.count { it.kind == MemberKind.Agent },
        )
    var submitting by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    val failedLabel = stringResource(Res.string.common_error)
    LaunchedEffect(owner) {
        computersStore.dispatch(SharedComputersIntent.EnsureComputerPicker(owner.serverId, DataRequestPriority.Visible))
    }
    fun submit(request: SharedCreateAgentDialogSubmit) {
        if (submitting) return
        submitting = true
        error = null
        scope.launch {
            try {
                val receipt = authority.capture(owner)
                if (receipt == null) {
                    error = failedLabel
                    return@launch
                }
                val client = authority.networkClient(receipt)
                val coordinator =
                    SharedCreateAgentCoordinator(
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
                // Last: the caller's dismissal cancels this scope.
                onCreated(result.data.id)
            } finally {
                submitting = false
            }
        }
    }
    SharedCreateAgentDialog(
        offersHosting = true,
        submitting = submitting,
        errorMessage = error,
        initialComputer = machineId,
        machines = picker.machines,
        machinesLoading = picker.initialLoadInFlight,
        machinesErrorMessage = if (picker.initialLoadError != null) failedLabel else null,
        onMachinesRetry = { computersStore.dispatch(SharedComputersIntent.RefreshComputerPicker(owner.serverId, DataRequestPriority.Visible)) },
        createAgentFormStore = formStore,
        createAgentServerId = owner.serverId,
        takenAgentNames = serverMembers.members.takenAgentNames(),
        currentAgentCount = agentLimit.usage,
        maxAgents = agentLimit.limit,
        limitPlanLabel = agentLimit.planLabel,
        onUpgrade = onUpgrade,
        onAddComputer = onAddComputer,
        onDismiss = { if (!submitting) onDismiss() },
        onSubmit = { submit(it) },
    )
}
