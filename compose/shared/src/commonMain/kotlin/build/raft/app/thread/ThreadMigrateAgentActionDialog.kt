package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.components.RaftProUpgradeDialog
import build.raft.app.components.raftProMigrationGateCopy
import build.raft.app.data.AgentBlockedState
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentMigrationEffect
import build.raft.app.data.AgentMigrationIntent
import build.raft.app.data.AgentMigrationState
import build.raft.app.data.AgentMigrationStore
import build.raft.app.data.AgentMigrationTarget
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.RaftAgentControlsApi
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftAgentsNetworkApi
import build.raft.app.data.RaftProGateDecision
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.agentBlockedState
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.isAgentMigrateAction
import build.raft.app.data.raftProGateDecision
import build.raft.app.data.serverRoleCanMigrateAgents
import build.raft.app.data.toAgentDetailSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.resources.*
import kotlinx.coroutines.flow.flowOf

/** Open state of the native migration dialog launched from an `agent:migrate` action card (#proj-mobile:#356). */
internal data class ThreadMigrateAgentActionDialogState(
    val messageId: String,
    val card: ThreadActionCard,
)

/**
 * Resolved target agent facts for an `agent:migrate` action card (web `actionCardIntentFlows.tsx`).
 * Combines the server member roster (for `blockedReason`, which `GET /agents/:id` does not send)
 * with `GET /agents/:id` (for `executionMode`, `machineId`, `runtime`, `deletedAt`).
 */
internal data class ThreadMigrateAgentActionTarget(
    val agentId: String,
    val agentName: String,
    val runtime: String?,
    val executionMode: String?,
    val sourceMachineId: String?,
    val blockedReason: String?,
) {
    val placesWithoutSource: Boolean
        get() = agentMigrateCardPlacesWithoutSource(executionMode, sourceMachineId, blockedReason)
}

/**
 * Web `actionCardIntentFlows.tsx` `placesWithoutSource` for `agent:migrate`:
 * a BYOC agent on no Computer (`machineId` absent) or on a Computer whose access was revoked
 * (`computer_access_revoked`) is placed directly on the target without a source (not a Pro migration).
 */
internal fun agentMigrateCardPlacesWithoutSource(
    executionMode: String?,
    machineId: String?,
    blockedReason: String?,
): Boolean {
    val isByoc = executionMode.isNullOrBlank() || executionMode.trim().equals("byoc", ignoreCase = true)
    if (!isByoc) return false
    return machineId.isNullOrBlank() || agentBlockedState(blockedReason) == AgentBlockedState.ComputerAccessRevoked
}

/**
 * Resolves [ThreadMigrateAgentActionTarget] from the cached roster profile and `GET /agents/:id`
 * summary. Returns null when the agent is deleted (matching web `!candidate.deletedAt`).
 */
internal fun resolveThreadMigrateAgentActionTarget(
    agentId: String,
    memberProfile: MemberProfile?,
    detail: AgentDetailSummary?,
): ThreadMigrateAgentActionTarget? {
    if (!detail?.deletedAt.isNullOrBlank() || !memberProfile?.agentDeletedAt.isNullOrBlank()) return null
    val executionMode = detail?.executionMode?.takeIf { it.isNotBlank() }
    val sourceMachineId = detail?.machineId?.takeIf { it.isNotBlank() }
    val runtime = detail?.runtime?.takeIf { it.isNotBlank() } ?: memberProfile?.roleDescription?.takeIf { it.isNotBlank() }
    val blockedReason = memberProfile?.agentBlockedReason?.takeIf { it.isNotBlank() }
    val agentName =
        detail?.displayName?.takeIf { it.isNotBlank() }
            ?: detail?.name?.takeIf { it.isNotBlank() }
            ?: memberProfile?.displayName?.takeIf { it.isNotBlank() }
            ?: memberProfile?.handle?.takeIf { it.isNotBlank() }
            ?: ""
    return ThreadMigrateAgentActionTarget(
        agentId = agentId,
        agentName = agentName,
        runtime = runtime,
        executionMode = executionMode,
        sourceMachineId = sourceMachineId,
        blockedReason = blockedReason,
    )
}

/**
 * Web `actionCardCommon.tsx` `canMigrateAgent` for an `agent:migrate` card:
 * - a personal assistant may only be migrated by its `assistantOwnerUserId`;
 * - any other live (non-deleted) agent may be migrated by its human creator (`agentCreatorUserId`)
 *   or a viewer with `migrateAgents` (server `owner` or `admin`).
 * Returns null when the card is not `agent:migrate` or the server roster has not loaded yet.
 */
internal data class AgentMigrateCardAccess(
    val canMigrate: Boolean,
    val agentName: String?,
)

internal fun evaluateAgentMigrateCardAccess(
    card: ThreadActionCard,
    viewerUserId: String?,
    viewerServerRole: String?,
    serverMembers: List<MemberProfile>,
    rosterLoaded: Boolean,
): AgentMigrateCardAccess? {
    if (!card.isAgentMigrateAction()) return null
    val targetAgentId = card.agentId?.trim()?.takeIf { it.isNotEmpty() } ?: return AgentMigrateCardAccess(false, null)
    val agent =
        serverMembers.firstOrNull {
            it.kind == MemberKind.Agent &&
                it.id.value.equals(targetAgentId, ignoreCase = true) &&
                it.agentDeletedAt.isNullOrBlank()
        }
    if (agent == null) {
        if (!rosterLoaded) return null
        return AgentMigrateCardAccess(false, null)
    }
    val viewer = viewerUserId?.trim()?.takeIf { it.isNotEmpty() }
    val assistantOwner = agent.assistantOwnerUserId
    val canMigrate =
        if (!assistantOwner.isNullOrBlank()) {
            viewer != null && assistantOwner.trim().equals(viewer, ignoreCase = true)
        } else {
            serverRoleCanMigrateAgents(viewerServerRole) ||
                (viewer != null && agent.agentCreatorUserId?.trim()?.equals(viewer, ignoreCase = true) == true)
        }
    val agentName = agent.displayName.takeIf { it.isNotBlank() } ?: agent.handle.takeIf { it.isNotBlank() }
    return AgentMigrateCardAccess(canMigrate = canMigrate, agentName = agentName)
}

/**
 * Stable lookup callback for [ThreadActionCardSpecHost.evaluateMigrateAgentAccess].
 * Kept out of `SharedThreadRouteContentContent` to stay under its JVM method-size ceiling.
 */
@Composable
internal fun rememberThreadActionCardMigrateAccess(
    currentSession: StoredAuthSession?,
    currentServerId: String?,
    serverMembersEntry: SharedServerMembersEntry?,
): (ThreadActionCard) -> AgentMigrateCardAccess? {
    val latestSession by rememberUpdatedState(currentSession)
    val latestServerId by rememberUpdatedState(currentServerId)
    val latestMembers by rememberUpdatedState(serverMembersEntry)
    return remember {
        { card ->
            val session = latestSession
            val serverId = latestServerId
            val role = session?.servers?.firstOrNull { it.id == serverId }?.role
            val entry = latestMembers
            evaluateAgentMigrateCardAccess(
                card = card,
                viewerUserId = session?.user?.id,
                viewerServerRole = role,
                serverMembers = entry?.members.orEmpty(),
                rosterLoaded = entry?.status == SharedServerMembersLoadStatus.Loaded,
            )
        }
    }
}

/**
 * Pure presentation host for the `agent:migrate` card's migration flow: checks the Pro gate
 * (unless [target] `placesWithoutSource`), opens [AgentMigrationStartDialog] preselected to
 * `card.suggestedComputer`, surfaces an unverified preflight info toast, and reports
 * [onMigrationStarted] with `(messageId, card, agentId, targetMachineId, placedWithoutSource)`
 * so the caller marks the action card executed (`POST /api/actions/:id/mark-executed`).
 */
@Composable
internal fun ThreadMigrateAgentActionDialogContent(
    dialogState: ThreadMigrateAgentActionDialogState,
    target: ThreadMigrateAgentActionTarget,
    migrationState: AgentMigrationState,
    computers: List<AgentMigrationComputer>,
    viewerUserId: String?,
    onMigrationIntent: (AgentMigrationIntent) -> Unit,
    onOpenUpgrade: () -> Unit,
    onOpenBilling: () -> Unit,
    onCopy: (String) -> Unit,
    onToast: (String) -> Unit,
    onDismiss: () -> Unit,
    onMigrationStarted: (
        messageId: String,
        card: ThreadActionCard,
        agentId: String,
        targetMachineId: String,
        placedWithoutSource: Boolean,
    ) -> Unit,
    nowMillis: () -> Long = ::defaultNowMillis,
    offersUpgrade: Boolean = settingsBillingOffersUpgrade(),
) {
    val proGate = raftProGateDecision(migrationState.billingPlan, nowMillis())
    val requiresUpgrade = !target.placesWithoutSource && proGate == RaftProGateDecision.UpgradeRequired
    var showProUpgradeSheet by remember(dialogState.messageId) { mutableStateOf(false) }
    val preflightUnverifiedTemplate =
        stringResource(
            Res.string.agent_migration_preflight_unverified,
            AgentMigrationPreflightRuntimeToken,
            AgentMigrationPreflightComputerToken
        )

    LaunchedEffect(requiresUpgrade, migrationState.billingChecking, offersUpgrade) {
        if (!migrationState.billingChecking && requiresUpgrade) {
            if (offersUpgrade) {
                onDismiss()
                onOpenUpgrade()
            } else {
                showProUpgradeSheet = true
            }
        }
    }

    LaunchedEffect(migrationState.effect) {
        when (migrationState.effect) {
            AgentMigrationEffect.ProRequired -> {
                onMigrationIntent(AgentMigrationIntent.ConsumeEffect)
                if (offersUpgrade) {
                    onDismiss()
                    onOpenUpgrade()
                } else {
                    showProUpgradeSheet = true
                }
            }
            AgentMigrationEffect.Started,
            AgentMigrationEffect.AgentPlacementChanged -> {
                val placed = migrationState.effect == AgentMigrationEffect.AgentPlacementChanged
                val startResult = migrationState.lastStartResult
                onMigrationIntent(AgentMigrationIntent.ConsumeEffect)
                if (!placed && startResult?.preflightKind == "unverified") {
                    val targetName = computers.firstOrNull { it.id == startResult.targetMachineId }?.name
                    formatAgentMigrationPreflightUnverified(preflightUnverifiedTemplate, target.runtime, targetName)
                        ?.let(onToast)
                }
                onDismiss()
                val targetMachineId = startResult?.targetMachineId
                if (!targetMachineId.isNullOrBlank()) {
                    onMigrationStarted(dialogState.messageId, dialogState.card, target.agentId, targetMachineId, placed)
                }
            }
            null -> return@LaunchedEffect
        }
    }

    if (showProUpgradeSheet) {
        val copy = raftProMigrationGateCopy()
        RaftProUpgradeDialog(
            title = stringResource(copy.title),
            description = stringResource(copy.description),
            onUpgrade = {
                showProUpgradeSheet = false
                onDismiss()
                onOpenBilling()
            },
            onDismiss = {
                showProUpgradeSheet = false
                onDismiss()
            }
        )
        return
    }

    if (migrationState.billingChecking || requiresUpgrade) return

    val inputs =
        AgentMigrationSectionInputs(
            sourceMachineId = target.sourceMachineId,
            computers = computers,
            // Web `actionCardIntentFlows.tsx`: agent:migrate cards move to a Computer (mark-executed requires targetMachineId).
            hostedAvailable = false,
            proGate = proGate,
            proGateChecking = false,
            onIntent = onMigrationIntent,
            onOpenBilling = onOpenBilling,
            onCopy = onCopy,
            formatTimestamp = { it },
            onAgentPlacementChanged = {},
            agentRuntime = target.runtime,
            placesWithoutSource = target.placesWithoutSource,
            agentName = target.agentName,
            viewerUserId = viewerUserId,
            nowMillis = nowMillis,
            onInfoToast = onToast,
        )
    AgentMigrationStartDialog(
        state = migrationState,
        inputs = inputs,
        initialTarget = dialogState.card.suggestedComputer,
        onStart = { selectedTarget, preflightOverride ->
            onMigrationIntent(AgentMigrationIntent.Start(selectedTarget, preflightOverride = preflightOverride))
        },
        onDismiss = { if (!migrationState.starting) onDismiss() }
    )
}

/**
 * Route-level host mounted by [ThreadRouteDialogs] when a user taps "Move agent" on an
 * `agent:migrate` card: loads the agent detail + server computers, owns the scoped
 * [AgentMigrationStore], and delegates rendering to [ThreadMigrateAgentActionDialogContent].
 */
@Composable
internal fun ThreadMigrateAgentActionDialogHost(
    dialogState: ThreadMigrateAgentActionDialogState,
    liveCard: ThreadActionCard?,
    data: SharedThreadRouteContentData,
    serverId: String?,
    serverMembersEntry: SharedServerMembersEntry?,
    onOpenUpgrade: () -> Unit,
    onOpenBilling: () -> Unit,
    onDismiss: () -> Unit,
    onMigrationStarted: (
        messageId: String,
        card: ThreadActionCard,
        agentId: String,
        targetMachineId: String,
        placedWithoutSource: Boolean,
    ) -> Unit,
) {
    val agentId = dialogState.card.agentId?.trim()?.takeIf { it.isNotEmpty() }
    if (agentId == null) {
        LaunchedEffect(dialogState.messageId) { onDismiss() }
        return
    }
    val viewerUserId by
        produceState<String?>(null, data.authStore) {
            value = data.authStore.readSession()?.user?.id
        }
    val scope = rememberCoroutineScope()
    val networkClient =
        remember(data.bridge, data.storeScope) {
            RaftSharedStoreRegistry.get(data.bridge).networkClient(data.storeScope)
        }
    val agentsApiClient =
        remember(networkClient) {
            RaftAgentsApiClient(
                transport = RaftAgentsNetworkApi(networkClient),
                diagnosticsLogger = { message -> data.bridge.log("thread_migrate_agent_card $message") }
            )
        }
    val migrationStore =
        remember(dialogState.messageId, agentId, networkClient, scope) {
            AgentMigrationStore(
                dataSource = RaftAgentControlsApi(networkClient),
                scope = scope,
            )
        }
    DisposableEffect(migrationStore, agentId) {
        migrationStore.dispatch(AgentMigrationIntent.PrepareStart(MemberId(agentId), checkPlan = true))
        onDispose { migrationStore.dispatch(AgentMigrationIntent.StopWatching) }
    }
    val migrationState by migrationStore.state.collectAsState()

    // Dismiss cleanly if the card ended or froze elsewhere while the dialog was open.
    LaunchedEffect(liveCard, migrationState.starting) {
        if (liveCard != null && !migrationState.starting && (liveCard.isExecuted || liveCard.hasEnded || liveCard.isFrozen)) {
            onDismiss()
        }
    }

    val computersStore: SharedComputersStore? = data.computersStore
    LaunchedEffect(computersStore, serverId) {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        computersStore?.dispatch(SharedComputersIntent.EnsureComputerPicker(cleanServerId, DataRequestPriority.Visible))
    }
    val computersFlow =
        remember(computersStore, serverId) {
            computersStore?.migrationComputersFlow(serverId) ?: flowOf(emptyList())
        }
    val computers by computersFlow.collectAsState(emptyList())

    val rosterProfile =
        remember(serverMembersEntry, agentId) {
            serverMembersEntry?.members?.firstOrNull {
                it.kind == MemberKind.Agent && it.id.value.equals(agentId, ignoreCase = true)
            }
        }
    var resolvedTarget by remember(dialogState.messageId, agentId) {
        mutableStateOf<ThreadMigrateAgentActionTarget?>(null)
    }
    var targetLoadFailed by remember(dialogState.messageId, agentId) { mutableStateOf(false) }

    LaunchedEffect(dialogState.messageId, agentId, agentsApiClient, rosterProfile) {
        when (val result = agentsApiClient.agent(MemberId(agentId))) {
            is DataResult.Content -> {
                val target = resolveThreadMigrateAgentActionTarget(agentId, rosterProfile, result.data.toAgentDetailSummary())
                if (target == null) {
                    targetLoadFailed = true
                } else {
                    resolvedTarget = target
                }
            }
            else -> {
                // Fall back to the roster profile if the detail fetch was transient; otherwise dismiss.
                val fallback = rosterProfile?.let { resolveThreadMigrateAgentActionTarget(agentId, it, null) }
                if (fallback != null) {
                    resolvedTarget = fallback
                } else {
                    targetLoadFailed = true
                }
            }
        }
    }
    LaunchedEffect(targetLoadFailed) {
        if (targetLoadFailed) onDismiss()
    }

    val target = resolvedTarget ?: return
    val effectiveState = liveCard?.let { dialogState.copy(card = it) } ?: dialogState
    ThreadMigrateAgentActionDialogContent(
        dialogState = effectiveState,
        target = target,
        migrationState = migrationState,
        computers = computers,
        viewerUserId = viewerUserId,
        onMigrationIntent = migrationStore::dispatch,
        onOpenUpgrade = onOpenUpgrade,
        onOpenBilling = onOpenBilling,
        onCopy = { value -> data.bridge.copyToPasteboard(value) },
        onToast = data.bridge::toast,
        onDismiss = onDismiss,
        onMigrationStarted = onMigrationStarted,
    )
}
