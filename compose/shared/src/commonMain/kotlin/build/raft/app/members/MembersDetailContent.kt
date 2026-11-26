package build.raft.app

import build.raft.app.data.agentOffersLounge
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.data.agentBlockedState
import build.raft.app.resources.*
import androidx.compose.runtime.setValue
import build.raft.app.components.RaftUiControlledMenu
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.components.TabPager
import build.raft.app.components.TabPagerGesturePolicy
import build.raft.app.data.AccountDescriptionUpdateResult
import build.raft.app.data.AgentMigrationIntent
import build.raft.app.data.AgentMigrationState
import build.raft.app.data.AgentMigrationStore
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.raftProGateDecision
import build.raft.app.data.AgentTurnStopFeedback
import build.raft.app.data.AgentSubagentStopFeedback
import build.raft.app.data.AgentSubagentStopIntent
import build.raft.app.data.AgentSubagentStopState
import build.raft.app.data.AgentAppGrantsRevokeStore
import build.raft.app.data.AgentSubagentStopStore
import build.raft.app.data.AgentWorkerTimelineStore
import build.raft.app.data.AgentWorkersIntent
import build.raft.app.data.AgentWorkersState
import build.raft.app.data.AgentWorkersStore
import build.raft.app.data.canStopAgentSubagents
import build.raft.app.data.canStopAgentWorkers
import build.raft.app.data.AgentTurnStopIntent
import build.raft.app.data.AgentTurnStopState
import build.raft.app.data.AgentTurnStopStore
import build.raft.app.data.canStopAgentTurn
import build.raft.app.data.PlatformFile
import build.raft.app.data.AgentArchiveWorkspaceChoice
import build.raft.app.data.AgentIssueReportResult
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.RaftTelemetryPrivacy
import build.raft.app.data.agentIssueReportAllowed
import build.raft.app.data.AgentIssueTranscriptRequestState
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentScopeSet
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.AgentWorkspaceChildrenSnapshot
import build.raft.app.data.AgentWorkspaceTreeSnapshot
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.SharedAgentDetailActions
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.SharedAgentIssueReportRequest
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsServerActionResult
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.SharedServerMembersState
import build.raft.app.model.AgentProfile
import build.raft.app.model.AgentStatus
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.RaftWorkspaceNode
import build.raft.app.model.RaftTimeContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import build.raft.app.data.map
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.selected

private data class MembersAgentActionState(
    val action: MembersAgentLifecycleAction,
    val inProgress: Boolean,
    val error: ScreenError? = null,
    val successLabel: String? = null
)

/**
 * Detail-page dot + caption for an external agent. Tiers mirror web
 * `AgentStatusBadge`; the caption is localized here because the pure mapping
 * ([externalAgentDetailIndicator]) must stay free of string resources.
 * A missing status (non-external, 403, failure) renders plain cyan "External".
 */
@Composable
private fun rememberExternalAgentDetailIndicatorState(
    detail: AgentDetailSnapshot?,
    timeContext: RaftTimeContext
): RaftAgentStatusIndicatorState {
    val externalStatus = detail?.externalStatus
    // task #410: lastSeenAt comes from the detail summary (GET /agents/:id
    // carries it for external agents) and realtime agent:seen updates it in
    // SharedAgentDetailStore — no extra request.
    val lastSeenAt = detail?.summary?.lastSeenAt
    val now = timeContext.nowEpochMillis ?: remember(externalStatus) { defaultNowMillis() }
    val indicator = remember(externalStatus, lastSeenAt, now) {
        externalAgentDetailIndicator(externalStatus, now, lastSeenAt)
    }
    val hostedLabel = detail?.summary?.hostedRuntime?.let { hostedRuntimeStateLabel(it) }
    // A cloud agent has no setup for a person to finish: its provisioning state instead.
    val label =
        hostedLabel?.takeIf {
            indicator.caption == ExternalAgentDetailCaption.SetupRequired ||
                indicator.caption == ExternalAgentDetailCaption.NotConfigured
        } ?: when (val caption = indicator.caption) {
            ExternalAgentDetailCaption.External -> stringResource(Res.string.member_external_status)
            ExternalAgentDetailCaption.Online -> stringResource(Res.string.member_external_online)
            ExternalAgentDetailCaption.SetupRequired -> stringResource(Res.string.member_external_setup_required)
            ExternalAgentDetailCaption.Active -> stringResource(Res.string.member_external_active)
            is ExternalAgentDetailCaption.LastActivity ->
                membersStringTemplate(Res.string.member_external_last_activity)
                    .membersFormat(timeContext.formatTimestampLabel(caption.iso).ifBlank { caption.iso })
            is ExternalAgentDetailCaption.LastSeen ->
                membersStringTemplate(Res.string.member_external_last_seen)
                    .membersFormat(timeContext.formatTimestampLabel(caption.iso).ifBlank { caption.iso })
            ExternalAgentDetailCaption.NoRaftActivity -> stringResource(Res.string.member_external_no_raft_activity)
            ExternalAgentDetailCaption.NotConfigured -> stringResource(Res.string.member_external_not_configured)
        }
    return indicator.state.copy(label = label)
}

/**
 * The live `agent:activity` detail the web visual fixture seeds for every agent-detail case
 * (VisualTestingCases primeAgentDetailStores `agentActivities`): the profile hero's status chip
 * shows it to a viewer who may see private surfaces.
 */
private const val MembersAgentDetailVisualLiveActivityDetail = "Capturing deterministic profile state"

private fun RaftAgentStatusIndicatorState.withMembersAgentDetailVisualLiveActivity(
    detail: String? = MembersAgentDetailVisualLiveActivityDetail
): RaftAgentStatusIndicatorState =
    if (status == AgentStatus.Working && !external && !detail.isNullOrBlank()) copy(label = detail) else this

/**
 * Parameter holders for [MembersDetailContent] (task #1130): the composable took 53 parameters,
 * past the same 31-per-int `$changed` / `$default` mask boundary as [MembersAgentProfileTab].
 * Same shape and same caveats as [MembersAgentRuntimeModelInputs]: plain unstable classes,
 * behaviour unchanged, no `@Stable` / `@Immutable` later.
 */
internal class MembersHumanRoleInputs(
    val humanActorRole: String? = null,
    val humanOwnerCount: Int? = null,
    val humanAccessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull,
    val onUpdateHumanRole: (suspend (String, MembersHumanServerRole) -> DataResult<SettingsServerActionResult>)? = null,
    val onUpdateHumanDescription: (suspend (String?) -> DataResult<AccountDescriptionUpdateResult>)? = null,
    val onApplyHumanDescription: ((String?) -> Unit)? = null
)

internal class MembersAgentDetailCallbacks(
    val onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    /** `bridge.errorToast`: an error toast with the failed request's trace ID; null: a plain toast. */
    val onErrorToast: ((String, ScreenError) -> Unit)? = null,
    val onApplyAgentProfileResult: (MemberProfile) -> Unit = {},
    val onApplyAgentRuntimeConfigProjection:
        suspend (MembersAgentProfileEditAuthority, RuntimeConfig, Boolean?) -> Boolean = { _, _, _ -> false },
    val onUploadAgentAvatar: (suspend (MemberId, PlatformFile) -> DataResult<MemberProfile>)?,
    val onPickAgentAvatarFile: suspend () -> DataResult<PlatformFile> = { DataResult.Empty(EmptyReason.NoMembers) },
    val onAgentLifecycleAction: (suspend (MemberId, MembersAgentLifecycleAction, AgentArchiveWorkspaceChoice) -> DataResult<AgentLifecycleActionResult>)?,
    val agentWorkspaceTreeLoader: (suspend (MemberId) -> DataResult<AgentWorkspaceTreeSnapshot>)?,
    val agentWorkspaceChildrenLoader: (suspend (MemberId, String) -> DataResult<AgentWorkspaceChildrenSnapshot>)?,
    /** The Apps tab's App events (web AgentAppEventsSection); null hides nothing, it just loads none. */
    val agentAppEventsLoader: MembersAgentAppEventsLoader? = null,
    val agentAppEventLoader: MembersAgentAppEventLoader? = null,
    val onCopyAgentDiagnostics: (String) -> Unit,
    val onCopyAgentIssueReference: (String) -> Unit = onCopyAgentDiagnostics,
    val onReportAgentIssue: (suspend (SharedAgentIssueReportRequest) -> DataResult<AgentIssueReportResult>)?,
    /** Opens a report's feedback ticket (Settings › Feedback on that ticket); null hides View Ticket. */
    val onOpenFeedbackTicket: ((String) -> Unit)? = null
)

/** Web `stopSubagentWithFeedback`'s toasts, one per outcome. */
internal class MembersAgentSubagentStopMessages(
    private val interrupted: String,
    private val notRunning: String,
    private val unsupported: String,
    private val upgradeRequired: String,
    private val computerOffline: String,
    private val failed: String
) {
    fun messageFor(feedback: AgentSubagentStopFeedback): String =
        when (feedback) {
            AgentSubagentStopFeedback.Interrupted -> interrupted
            AgentSubagentStopFeedback.NotRunning -> notRunning
            AgentSubagentStopFeedback.Unsupported -> unsupported
            is AgentSubagentStopFeedback.UpgradeRequired -> upgradeRequired
            is AgentSubagentStopFeedback.ComputerOffline -> computerOffline
            is AgentSubagentStopFeedback.Failed -> failed
        }
}

/** The failed request behind [this] feedback, for its trace ID; null for an answer. */
internal fun AgentSubagentStopFeedback.serverError(): ScreenError? =
    when (this) {
        is AgentSubagentStopFeedback.UpgradeRequired -> error
        is AgentSubagentStopFeedback.ComputerOffline -> error
        is AgentSubagentStopFeedback.Failed -> error
        AgentSubagentStopFeedback.Interrupted,
        AgentSubagentStopFeedback.NotRunning,
        AgentSubagentStopFeedback.Unsupported -> null
    }

@Composable
private fun membersAgentSubagentStopMessages(): MembersAgentSubagentStopMessages =
    MembersAgentSubagentStopMessages(
        interrupted = stringResource(Res.string.member_activity_subagents_stop_interrupted),
        notRunning = stringResource(Res.string.member_activity_subagents_stop_not_running),
        unsupported = stringResource(Res.string.member_activity_subagents_stop_unsupported),
        upgradeRequired = stringResource(Res.string.member_activity_subagents_stop_upgrade_required),
        // Web: the stop-turn refusal's own words (`agent.detail.stopTurn.computerOffline`).
        computerOffline = stringResource(Res.string.agent_stop_turn_computer_offline),
        failed = stringResource(Res.string.member_activity_subagents_stop_failed)
    )

/** Agent remote controls the detail page offers beyond the lifecycle actions (web parity). */
internal class MembersAgentRemoteControls(
    /** The viewer's role on the agent's server; mirrors the server capability matrix. */
    val serverRole: String?,
    val turnStopStore: AgentTurnStopStore?,
    val migration: MembersAgentMigrationBinding? = null,
    /** Stop one running sub-agent from the Activity tab's Sub-agents view (web stopSubagent). */
    val subagentStopStore: AgentSubagentStopStore? = null,
    /** Worker v1 (`worker_v1` on for a Computer agent): Activity › Workers panel store; null when flag off or external. */
    val workersStore: AgentWorkersStore? = null,
    /** Worker v1 observability: one launch's timeline pushed inside the Workers panel; null when flag off or external. */
    val workerTimelineStore: AgentWorkerTimelineStore? = null,
    /** The Apps tab's Revoke access and per-grant Revoke (web AgentAppAccessTab); null offers neither. */
    val appAccess: MembersAgentAppAccessBinding? = null
)

/** The Apps tab's revoke store and who may use it (web AgentDetailPanel `canManageAgentAccess`). */
internal class MembersAgentAppAccessBinding(
    val store: AgentAppGrantsRevokeStore,
    val canManage: (AgentDetailSnapshot?) -> Boolean
)

/** The migration section's store and page-level inputs (web AgentMigrationSection). */
internal class MembersAgentMigrationBinding(
    val store: AgentMigrationStore,
    val hostedAvailable: Boolean,
    val computers: List<AgentMigrationComputer>,
    val onOpenBilling: () -> Unit,
    val onAgentPlacementChanged: () -> Unit,
    /** The agents on each computer, for a target's details (its avatars open their profiles). */
    val computerAgents: AgentMigrationComputerAgents? = null,
    /** The server's migration log, opened on this agent; null for anyone but owners and admins. */
    val onOpenLog: (() -> Unit)? = null,
    /** Info toast for non-blocking migration notices such as an unverified preflight. */
    val onInfoToast: ((String) -> Unit)? = null
)

@Composable
private fun membersAgentTurnStopMessages(): Map<AgentTurnStopFeedback, String> =
    mapOf(
        AgentTurnStopFeedback.Interrupted to stringResource(Res.string.agent_stop_turn_interrupted),
        AgentTurnStopFeedback.Restarted to stringResource(Res.string.agent_stop_turn_restarted),
        AgentTurnStopFeedback.NotRunning to stringResource(Res.string.agent_stop_turn_not_running),
        AgentTurnStopFeedback.UpgradeRequired to stringResource(Res.string.agent_stop_turn_upgrade_required),
        AgentTurnStopFeedback.ComputerOffline to stringResource(Res.string.agent_stop_turn_computer_offline),
        AgentTurnStopFeedback.Failed to stringResource(Res.string.agent_stop_turn_failed)
    )

internal class MembersDetailActions(
    val onBack: () -> Unit,
    val onOpenDirectMessage: () -> Unit,
    /** A Message tap waits on the server: the button shows its loading state. */
    val directMessageOpening: Boolean = false,
    val onOpenComputer: (() -> Unit)? = null,
    /** Opens an App bot's owning App installed detail (`?installed_app=`, slock #14286); null when unavailable. */
    val onOpenManagedApp: (() -> Unit)? = null,
    val onCopyMemberId: () -> Unit,
    val onRemoveMember: (() -> Unit)? = null,
    /** Promote to server member, for a Guest an owner/admin may make a member (web HumanProfileActions). */
    val onPromoteGuest: (() -> Unit)? = null,
    /** The promotion is in flight: the button is disabled. */
    val promotingGuest: Boolean = false,
    val onOpenUrl: (String) -> Unit,
    val onOpenReminderMessage: (String) -> Unit = {},
    /** Opens a message the agent Activity overview names, by channel and message id. */
    val onOpenActivityMessage: (channelId: String, messageId: String) -> Unit = { _, _ -> },
    /** Resolves and opens a message named by a Worker timeline `"View message"` link. */
    val onOpenWorkerTimelineMessage: ((messageId: String) -> Unit)? = null,
    val onUnavailableAction: (String) -> Unit,
    /** Opens the profile of an agent listed under a human's Created Agents. */
    val onOpenCreatedAgent: ((AgentProfile) -> Unit)? = null,
    /**
     * Raft Agent Voice: starts a voice call to this agent. Null when the flag is off, the viewer
     * is a Guest, the native engine is unsupported, or the agent is not callable.
     */
    val onStartCall: (() -> Unit)? = null,
    /**
     * Agent Lounge v0: opens this agent's Lounge. Null when Lounges are off here (flag, Guest); the
     * profile also hides it for a deleted agent, a personal assistant and a remote profile.
     */
    val onOpenLounge: (() -> Unit)? = null,
    val loungeOpening: Boolean = false
)

internal class MembersAgentDetailPresentation(
    val pageVisibleTick: Int = 0,
    val initialVisualMode: MembersAgentDetailVisualMode = MembersAgentDetailVisualMode.Profile,
    val initialAgentTab: MembersAgentDetailTab? = null,
    /** A Members row's Edit agent: open the runtime editor once it can (as Change model does). */
    val openRuntimeEditor: Boolean = false,
    val agentPanelTabOrder: List<String> = emptyList(),
    /** False while a cold-start open still waits for the user's tab order (task #22). */
    val agentPanelTabOrderReady: Boolean = true,
    val onAgentPanelTabOrderChange: ((List<MembersAgentDetailTab>) -> Unit)? = null,
    val initialAgentScopeSet: AgentScopeSet? = null,
    val stableVisualCapture: Boolean = false,
    /**
     * The live `agent:activity` detail a visual capture's hero chip shows for a working agent. The
     * component pages (VisualTestingCases) seed [MembersAgentDetailVisualLiveActivityDetail]; the
     * real-route captures (react-provider.spec) show the agent's own `activityDetail`.
     */
    val visualLiveActivityDetail: String? = MembersAgentDetailVisualLiveActivityDetail,
    /**
     * `RAFT_PRIVACY_MODE` for the current environment (process-wide [RaftTelemetryPrivacy]): Report
     * issue is offered, and its bundle sent, only while it is [RaftPrivacyMode.Off]. Visual
     * fixtures pin it off.
     */
    val privacyMode: StateFlow<RaftPrivacyMode> = RaftTelemetryPrivacy.state
)

@Composable
internal fun MembersDetailContent(
    member: MemberProfile,
    currentUserId: String? = null,
    currentServerId: String? = null,
    serverMembersState: SharedServerMembersState = SharedServerMembersState(),
    agentDetailState: MembersAgentDetailLoadState,
    agentActivityLoadState: MembersAgentActivityLoadState = MembersAgentActivityLoadState.Idle,
    /** Activity tab per-turn Overview; null (flag off or no access) keeps the raw log only. */
    activityOverview: MembersAgentActivityOverviewBinding? = null,
    runtimeModels: MembersAgentRuntimeModelInputs = MembersAgentRuntimeModelInputs(),
    createAgentFormStore: SharedCreateAgentFormStore? = null,
    agentProfileEditAuthority: MembersAgentProfileEditAuthority? = null,
    canViewPrivateAgentSurfaces: (MemberProfile, AgentDetailSnapshot?) -> Boolean,
    canManageAgent: (MemberProfile, AgentDetailSnapshot?) -> Boolean,
    /** Task #259: resolves the machine status row from the computers store. No default on purpose -- a silent fallback here is the #253 shape. */
    machineStatusRow: (machineId: String?) -> MembersMachineStatusRow,
    /** Computers-store snapshot and server command context for [MembersAgentComputerOfflineCard]. */
    offlineComputerInputs: MembersAgentOfflineComputerInputs = MembersAgentOfflineComputerInputs(),
    actions: MembersDetailActions,
    humanRole: MembersHumanRoleInputs = MembersHumanRoleInputs(),
    agentDetailActions: SharedAgentDetailActions? = null,
    agentCallbacks: MembersAgentDetailCallbacks,
    timeContext: RaftTimeContext,
    sharedAgentDetailStore: SharedAgentDetailStore? = null,
    presentation: MembersAgentDetailPresentation = MembersAgentDetailPresentation(),
    accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
    memberAvatarResolver: RaftMemberAvatarResolver = EmptyRaftMemberAvatarResolver,
    // #89: forwarded to the Permissions tab so it can call
    // `agentScopes` / `updateAgentScopes` / `resetAgentScopes`. When null
    // the Permissions tab renders the "Failed to load agent permissions" error banner.
    agentsApiClient: build.raft.app.data.RaftAgentsApiClient? = null,
    externalSetupServerUrl: String? = null,
    remoteControls: MembersAgentRemoteControls? = null
) {
    val agentMachineRuntimes = runtimeModels.agentMachineRuntimes
    val agentRuntimeModelsByRuntime = runtimeModels.agentRuntimeModelsByRuntime
    val onAgentRuntimeModelsRequest = runtimeModels.onAgentRuntimeModelsRequest
    val humanActorRole = humanRole.humanActorRole
    val humanOwnerCount = humanRole.humanOwnerCount
    val humanAccessMode = humanRole.humanAccessMode
    val onUpdateHumanRole = humanRole.onUpdateHumanRole
    val onUpdateAgentProfile = agentCallbacks.onUpdateAgentProfile
    val onApplyAgentProfileResult = agentCallbacks.onApplyAgentProfileResult
    val onApplyAgentRuntimeConfigProjection = agentCallbacks.onApplyAgentRuntimeConfigProjection
    val onAgentErrorToast = agentCallbacks.onErrorToast
    val onUploadAgentAvatar = agentCallbacks.onUploadAgentAvatar
    val onPickAgentAvatarFile = agentCallbacks.onPickAgentAvatarFile
    val onAgentLifecycleAction = agentCallbacks.onAgentLifecycleAction
    val agentWorkspaceTreeLoader = agentCallbacks.agentWorkspaceTreeLoader
    val agentAppEventsLoader = agentCallbacks.agentAppEventsLoader
    val agentAppEventLoader = agentCallbacks.agentAppEventLoader
    val agentWorkspaceChildrenLoader = agentCallbacks.agentWorkspaceChildrenLoader
    val onCopyAgentDiagnostics = agentCallbacks.onCopyAgentDiagnostics
    val onCopyAgentIssueReference = agentCallbacks.onCopyAgentIssueReference
    val onOpenFeedbackTicket = agentCallbacks.onOpenFeedbackTicket
    val onReportAgentIssue = agentCallbacks.onReportAgentIssue
    val onBack = actions.onBack
    val onOpenDirectMessage = actions.onOpenDirectMessage
    val onOpenComputer = actions.onOpenComputer
    val onCopyMemberId = actions.onCopyMemberId
    val onRemoveMember = actions.onRemoveMember
    val onOpenUrl = actions.onOpenUrl
    val onOpenReminderMessage = actions.onOpenReminderMessage
    val onOpenActivityMessage = actions.onOpenActivityMessage
    val onUnavailableAction = actions.onUnavailableAction
    val onOpenCreatedAgent = actions.onOpenCreatedAgent
    val pageVisibleTick = presentation.pageVisibleTick
    val initialVisualMode = presentation.initialVisualMode
    val initialAgentTab = presentation.initialAgentTab
    val agentPanelTabOrder = presentation.agentPanelTabOrder
    val agentPanelTabOrderReady = presentation.agentPanelTabOrderReady
    val onAgentPanelTabOrderChange = presentation.onAgentPanelTabOrderChange
    val initialAgentScopeSet = presentation.initialAgentScopeSet
    val stableVisualCapture = presentation.stableVisualCapture
    val errorStrings = localizedErrorPresentationStrings()
    val detail = (agentDetailState as? MembersAgentDetailLoadState.Content)?.detail
    val managementUnavailable = stringResource(Res.string.member_error_action_unavailable)
    val issueReportingUnavailable = stringResource(Res.string.member_error_issue_unavailable)
    val avatarEditingUnavailable = stringResource(Res.string.member_error_avatar_edit_unavailable)
    val permissionErrorTemplate = membersStringTemplate(Res.string.member_error_permission)
    val diagnosticsCopied = stringResource(Res.string.member_action_diagnostics_copied)
    val issueReported = stringResource(Res.string.member_action_issue_reported)
    val issueReportTitle = stringResource(Res.string.member_report_issue_title_for_agent, member.displayName)
    val issueTicketRetryFailed = stringResource(Res.string.member_report_issue_retry_ticket_failed)
    val deleted = detail?.summary?.deletedAt?.isNotBlank() == true
    val remoteProfile = membersAgentProfileIsRemote(accessMode)
    // task #256: observed-or-null drives the dot. The label-facing resolution below may
    // substitute the unknown placeholder, which is grey and would impersonate Offline.
    // task #258: cross-server joint-channel agents (RemoteProfileOnly) have no live status
    // resolver bound, so fall back to the status/activity returned by GET /api/agents/:id when
    // no live canonical state exists; live status still wins if bound, and absent status stays
    // null so no chip is rendered.
    val managedAgentStatusObservedState =
        if (member.kind == MemberKind.Agent) {
            val rawCanonicalState = rememberRaftAgentStatusIndicatorState(agentStatusResolver, member.id.value)
            val canonicalState =
                if (remoteProfile && rawCanonicalState?.runningWorkers != null && !rawCanonicalState.runningWorkersPresenceOnly) {
                    rawCanonicalState.copy(runningWorkers = 1, runningWorkersPresenceOnly = true)
                } else {
                    rawCanonicalState
                }
            val remoteProfileFallback =
                if (remoteProfile) {
                    membersRemoteProfileAgentStatusIndicatorStateOrNull(
                        summaryStatus = detail?.summary?.status,
                        summaryActivity = detail?.summary?.activity,
                        external = false,
                        runtime = detail?.summary?.runtime,
                        model = detail?.summary?.model,
                        deliveryHold = detail?.summary?.deliveryHold,
                        runningWorkers = detail?.summary?.runningWorkers,
                        runningWorkersPresenceOnly = remoteProfile,
                        serviceTier = detail?.summary?.serviceTier,
                    )
                } else {
                    null
                }
            resolveMembersAgentStatusIndicatorStateOrNull(
                canonicalState = canonicalState,
                fixtureFallback =
                    if (stableVisualCapture) {
                        member.toFixtureRaftAgentStatusIndicatorState(
                            summaryStatus = detail?.summary?.status,
                            summaryActivity = detail?.summary?.activity
                        ).withMembersAgentDetailVisualLiveActivity(presentation.visualLiveActivityDetail)
                    } else {
                        null
                    },
                fixtureStatusFallbackEnabled = stableVisualCapture,
                remoteProfileFallback = remoteProfileFallback
            )
        } else {
            null
        }
    val managedAgentStatusIndicatorState =
        if (member.kind == MemberKind.Agent) {
            managedAgentStatusObservedState ?: unknownMembersAgentStatusIndicatorState()
        } else {
            null
        }
    val isExternalAgent =
        member.kind == MemberKind.Agent && (member.isExternal || detail?.summary?.isExternal == true)
    // task #1119 PR 2: the detail page (and only the detail page) reads the
    // external agent's setup/activity status — React AgentDetailPanel parity.
    val agentStatusIndicatorState =
        if (isExternalAgent) {
            rememberExternalAgentDetailIndicatorState(detail = detail, timeContext = timeContext)
        } else {
            managedAgentStatusIndicatorState
        }
    // task #256 x #1119: the no-manufactured-dot rule covers the MANAGED branch only. An
    // external agent's tiers are cyan/green — never the offline stone — so that state is an
    // observation in its own right and must keep painting its dot. Suppressing it here would
    // silently undo #1119's setup/activity tiers, which is exactly what resolving this
    // conflict in favour of either side alone would have done.
    val agentStatusObservedState =
        if (isExternalAgent) agentStatusIndicatorState else managedAgentStatusObservedState
    val canViewPrivate = member.kind == MemberKind.Agent && canViewPrivateAgentSurfaces(member, detail)
    val canManage = member.kind == MemberKind.Agent && !deleted && canManageAgent(member, detail)
    // An open runtime editor reads permission from the last loaded detail while a refresh is in
    // flight or failed: `detail` is null then, which would read a creator's right as revoked.
    val lastContentDetailHolder = remember(member.id.value) { arrayOfNulls<AgentDetailSnapshot>(1) }
    if (detail != null) lastContentDetailHolder[0] = detail
    val runtimeEditorCanManage =
        member.kind == MemberKind.Agent &&
            membersRuntimeEditorCanManage(
                currentDetail = detail,
                lastContentDetail = lastContentDetailHolder[0],
                refreshTransient =
                    agentDetailState is MembersAgentDetailLoadState.Loading ||
                        agentDetailState is MembersAgentDetailLoadState.Error,
                canManageAgent = { snapshot -> canManageAgent(member, snapshot) }
            )
    val turnStopStore = remoteControls?.turnStopStore
    val fallbackTurnStopState = remember { MutableStateFlow(AgentTurnStopState()) }
    val turnStopState by (turnStopStore?.state ?: fallbackTurnStopState).collectAsState()
    val turnStopMessages = membersAgentTurnStopMessages()
    LaunchedEffect(turnStopState.feedback, turnStopState.feedbackAgentId) {
        val feedback = turnStopState.feedback ?: return@LaunchedEffect
        if (turnStopState.feedbackAgentId == member.id) {
            val message = turnStopMessages.getValue(feedback)
            if (feedback.isError && onAgentErrorToast != null) {
                onAgentErrorToast(
                    message,
                    build.raft.app.data.ScreenError(
                        code = "agent_turn_stop_failed",
                        traceId = turnStopState.feedbackTraceId,
                    ),
                )
            } else {
                onUnavailableAction(message)
            }
        }
        turnStopStore?.dispatch(AgentTurnStopIntent.ConsumeFeedback)
    }
    // Web AgentDetailPanel (#10446): whoever may stop this agent's turn gets Stop Current Turn on
    // the "stuck on a tool call" notice too, by the same rule as the management menu.
    val profileStopTurn: (() -> Unit)? =
        if (
            member.kind == MemberKind.Agent &&
            turnStopStore != null &&
            canStopAgentTurn(
                serverRole = remoteControls?.serverRole,
                isCreator = currentUserId != null && detail?.summary?.creatorId == currentUserId,
                isExternalAgent = isExternalAgent,
                deleted = deleted,
                online =
                    membersResolvedPrimaryLifecycleAction(
                        resolvedStatus = agentStatusIndicatorState?.status,
                        detailFallbackStatus = null,
                        deleted = deleted,
                        hasResolvedPresence = true
                    ) == MembersAgentLifecycleAction.Stop
            )
        ) {
            { turnStopStore.dispatch(AgentTurnStopIntent.Stop(member.id)) }
        } else {
            null
        }
    // Web AgentActivityLog (#proj-runtime:#41): Stop on a running sub-agent's page, for whoever
    // has Stop turn's authority on a runtime that can stop one; one short toast says how it went.
    val subagentStopStore = remoteControls?.subagentStopStore
    val fallbackSubagentStopState = remember { MutableStateFlow(AgentSubagentStopState()) }
    val subagentStopState by (subagentStopStore?.state ?: fallbackSubagentStopState).collectAsState()
    val subagentStopMessages = membersAgentSubagentStopMessages()
    LaunchedEffect(subagentStopState.feedback, subagentStopState.feedbackAgentId) {
        val feedback = subagentStopState.feedback ?: return@LaunchedEffect
        if (subagentStopState.feedbackAgentId == member.id) {
            val message = subagentStopMessages.messageFor(feedback)
            val error = feedback.serverError()
            if (error != null && onAgentErrorToast != null) onAgentErrorToast(message, error) else onUnavailableAction(message)
        }
        subagentStopStore?.dispatch(AgentSubagentStopIntent.ConsumeFeedback)
    }
    val activitySubagentStop: MembersAgentSubagentStop? =
        if (
            member.kind == MemberKind.Agent &&
            subagentStopStore != null &&
            canStopAgentSubagents(
                serverRole = remoteControls?.serverRole,
                isCreator = currentUserId != null && detail?.summary?.creatorId == currentUserId,
                isExternalAgent = isExternalAgent,
                deleted = deleted,
                runtime = detail?.summary?.runtime
            )
        ) {
            MembersAgentSubagentStop(subagentStopState.inFlightTaskId) { taskId ->
                subagentStopStore.dispatch(AgentSubagentStopIntent.Stop(member.id, taskId))
            }
        } else {
            null
        }
    // Worker v1 (docs/contracts/worker-snapshot.md): Activity › Workers panel + Stop feedback toasts.
    val workersStore = remoteControls?.workersStore
    val fallbackWorkersState = remember { MutableStateFlow(AgentWorkersState()) }
    val workersState by (workersStore?.state ?: fallbackWorkersState).collectAsState()
    val workerParentHandle =
        detail?.summary?.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: member.handle.trim().removePrefix("@").ifBlank { member.displayName }
    val pendingWorkerStopFeedback =
        workersState.feedback?.takeIf { workersState.feedbackAgentId == member.id }
    val pendingWorkerStopMessage =
        if (pendingWorkerStopFeedback != null) {
            membersAgentWorkerStopFeedbackMessage(pendingWorkerStopFeedback, workerParentHandle)
        } else {
            null
        }
    LaunchedEffect(workersState.feedback, workersState.feedbackAgentId) {
        val feedback = workersState.feedback ?: return@LaunchedEffect
        if (workersState.feedbackAgentId == member.id && pendingWorkerStopMessage != null) {
            val error = feedback.serverError()
            if (error != null && onAgentErrorToast != null) {
                onAgentErrorToast(pendingWorkerStopMessage, error)
            } else {
                onUnavailableAction(pendingWorkerStopMessage)
            }
        }
        workersStore?.dispatch(AgentWorkersIntent.ConsumeFeedback)
    }
    val activityWorkersComputerOffline =
        (machineStatusRow(detail?.summary?.machineId) as? MembersMachineStatusRow.Machine)?.online == false
    val workerTimelineStore = remoteControls?.workerTimelineStore
    val activityWorkers: MembersAgentWorkersBinding? =
        if (member.kind == MemberKind.Agent && workersStore != null && !isExternalAgent) {
            MembersAgentWorkersBinding(
                state = workersStore.state,
                parentHandle = workerParentHandle,
                canStop =
                    canStopAgentWorkers(
                        serverRole = remoteControls?.serverRole,
                        isCreator = currentUserId != null && detail?.summary?.creatorId == currentUserId,
                        isExternalAgent = isExternalAgent,
                        deleted = deleted,
                        assistantOwnerUserId = detail?.summary?.assistantOwnerUserId,
                        currentUserId = currentUserId,
                    ),
                computerOffline = activityWorkersComputerOffline,
                onIntent = workersStore::dispatch,
                timelineState = workerTimelineStore?.state,
                onTimelineIntent = workerTimelineStore?.let { it::dispatch },
                onOpenTimelineMessage = actions.onOpenWorkerTimelineMessage,
            )
        } else {
            null
        }
    // Web AgentDetailPanel mounts AgentMigrationSection for people who can manage a
    // non-external agent that has a computer (cloud agents keep it only for their arrival,
    // which mobile does not show).
    val migrationBinding = remoteControls?.migration
    val migrationVisible =
        migrationBinding != null && canManage && !isExternalAgent &&
            !detail?.summary?.machineId.isNullOrBlank()
    val fallbackMigrationState = remember { MutableStateFlow(AgentMigrationState()) }
    val migrationState by (migrationBinding?.store?.state ?: fallbackMigrationState).collectAsState()
    DisposableEffect(member.id.value, migrationVisible, migrationBinding?.store) {
        if (migrationVisible) migrationBinding?.store?.dispatch(AgentMigrationIntent.Watch(member.id))
        onDispose { migrationBinding?.store?.dispatch(AgentMigrationIntent.StopWatching) }
    }
    val scope = rememberCoroutineScope()
    val pager = LocalActivity.current.getPager() as Pager
    val fallbackAgentDetailStore = remember { SharedAgentDetailStore() }
    val storeState by (sharedAgentDetailStore ?: fallbackAgentDetailStore).state.collectAsState()
    var actionState by remember(member.id.value, initialVisualMode) {
        mutableStateOf<MembersAgentActionState?>(null)
    }
    var diagnosticActionState by remember(member.id.value) { mutableStateOf<MembersAgentDiagnosticActionState?>(null) }
    var pendingConfirmation by remember(member.id.value) {
        mutableStateOf<MembersAgentLifecycleAction?>(null)
    }
    var restartResetDialogOpen by remember(member.id.value, initialVisualMode) {
        mutableStateOf(false)
    }
    var reportIssueDialogOpen by remember(member.id.value) { mutableStateOf(false) }
    // Web AgentDetailPanel / ReportIssueDialog (`isPrivacyModeOn()`): no entry and no upload until
    // this environment's client config has answered "off".
    val privacyMode by presentation.privacyMode.collectAsState()
    val reportIssueOffered = agentIssueReportAllowed(privacyMode)
    var issueReportReceipt by remember(member.id.value) { mutableStateOf<AgentIssueReportResult?>(null) }
    var issueReportReferenceCopied by remember(member.id.value) { mutableStateOf(false) }
    var issueTicketRetrying by remember(member.id.value) { mutableStateOf(false) }
    // Set before the launch: a second tap in the same frame must not file a second ticket (every
    // submit gets a new submission id, so the server's dedup can't catch it).
    var issueReportSubmitting by remember(member.id.value) { mutableStateOf(false) }
    var restartResetSelection by remember(member.id.value) {
        mutableStateOf(MembersAgentLifecycleAction.Restart)
    }
    // Mirrors React AgentDetailPanel: the active tab is derived — an explicit
    // selection (deep link or user tap) wins only while visible; otherwise the
    // first server-ordered tab is the default.
    var selectedTabOverride by remember(member.id.value, initialAgentTab) {
        mutableStateOf(initialAgentTab)
    }
    // Web AgentDetailPanel `openModelSettings` (botiverse/slock #13435, #13460): Change model on the
    // status and View model settings on the billing banner open the Profile tab's runtime editor,
    // for whoever may configure the agent (`canManageAgent && !deletedAt && !isExternalAgent`).
    var runtimeEditorOpenRequested by remember(member.id.value, presentation.openRuntimeEditor) {
        mutableStateOf(presentation.openRuntimeEditor)
    }
    val openModelSettings: (() -> Unit)? =
        if (membersAgentCanOpenModelSettings(canManage = canManage, isExternalAgent = isExternalAgent)) {
            {
                selectedTabOverride = MembersAgentDetailTab.Profile
                runtimeEditorOpenRequested = true
            }
        } else {
            null
        }
    // task #232 PR2 (@LiBai review): each tab's list scroll position lives here,
    // keyed by TAB rather than by pager index so it survives both a swipe away
    // and a tab reorder. Inside the page the LazyColumn is discarded whenever
    // the page is not the selected one, so a state remembered there would
    // reset to the top on every return — the Activity caller hoists its
    // per-page states for the same reason (SharedInboxPanel pageListStates).
    // rememberLazyListState (saveable, like Activity's) rather than a bare
    // LazyListState so rotation keeps the position too; key() re-creates the
    // set when the detail switches member, so one member's scroll offsets
    // never show up on another (same pattern as ThreadPage's listState). The
    // map covers the FULL enum, so getValue below is total whatever the
    // visible-tab set is.
    val agentTabListStates = key(member.id.value) {
        MembersAgentDetailTab.entries.associateWith { rememberLazyListState() }
    }
    var managementMenuOpen by remember(member.id.value, initialVisualMode) { mutableStateOf(false) }
    val storeLifecycleInFlight: String? = storeState.lifecycleActionInFlight
    val storeDiagnosticInFlight: String? = storeState.diagnosticActionInFlight
    val lifecycleInFlight: String? = if (sharedAgentDetailStore != null) {
        storeLifecycleInFlight
    } else {
        actionState?.takeIf { it.inProgress }?.action?.actionKey
    }
    val diagnosticInFlight: String? = if (sharedAgentDetailStore != null) {
        storeDiagnosticInFlight
    } else {
        diagnosticActionState?.takeIf { it.inProgress }?.actionKey
    }
    val storePendingConfirmation: MembersAgentLifecycleAction? =
        storeState.pendingConfirmation.takeIf { sharedAgentDetailStore != null }
    val effectiveDiagnosticActionState =
        if (sharedAgentDetailStore != null) {
            storeState.diagnosticActionInFlight?.let {
                MembersAgentDiagnosticActionState(actionKey = it, inProgress = true)
            } ?: storeState.lastActionResult
                ?.takeIf { it.actionKey == "copy" || it.actionKey == "report" }
                ?.let {
                    MembersAgentDiagnosticActionState(
                        actionKey = it.actionKey,
                        inProgress = false,
                        error = it.error,
                        successLabel = it.successLabel
                    )
                }
        } else {
            diagnosticActionState
        }
    var managementMenuAnchor by remember(member.id.value) { mutableStateOf<RaftPopoverAnchor?>(null) }
    var managementMenuAnchorResolver by remember(member.id.value) {
        mutableStateOf<RaftPopoverAnchorResolver?>(null)
    }
    var showAvatarPicker by remember(member.id.value, initialVisualMode) { mutableStateOf(false) }
    var workspaceTreeState by remember(member.id.value) {
        mutableStateOf<MembersAgentWorkspaceTreeState>(MembersAgentWorkspaceTreeState.Idle)
    }
    var expandedWorkspaceDirs by remember(member.id.value) { mutableStateOf<Set<String>>(emptySet()) }
    var loadedWorkspaceDirs by remember(member.id.value) { mutableStateOf<Map<String, List<RaftWorkspaceNode>>>(emptyMap()) }
    var loadingWorkspaceDirs by remember(member.id.value) { mutableStateOf<Set<String>>(emptySet()) }
    var workspaceDirErrors by remember(member.id.value) { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Hoisted above TabPager so Usage and App Events survive horizontal tab disposal (#proj-mobile:#386).
    val usageTabState = remember(member.id.value, currentServerId) { MembersAgentUsageTabState() }
    val appEventsState = remember(member.id.value, currentServerId) { MembersAgentAppEventsState() }
    LaunchedEffect(member.id.value, initialVisualMode) {
        when (initialVisualMode) {
            MembersAgentDetailVisualMode.LifecycleActions -> {
                Unit
            }
            MembersAgentDetailVisualMode.AvatarManagement -> {
                showAvatarPicker = true
            }
            MembersAgentDetailVisualMode.ReportIssue -> {
                reportIssueDialogOpen = true
            }
            MembersAgentDetailVisualMode.ReportIssueSuccess -> {
                issueReportReceipt =
                    AgentIssueReportResult(
                        reportId = "7e91c120-f424-42d7-96e5-21eb816866d0",
                        artifactId = "95d629bd-3ed7-42ff-97eb-3ed35c6dc6ab",
                        serverId = "65598229-cd0a-4e92-96f3-c33e7e2e4cc1",
                        issueDescription = "The agent stopped responding after a long-running command.",
                        transcriptRequested = true,
                        transcriptRequestState = AgentIssueTranscriptRequestState.Requested,
                        ticketId = "3f6c2a10-8d4b-4e2f-9a1c-5b7d8e9f0a1b"
                    )
                issueReportReferenceCopied = false
            }
            MembersAgentDetailVisualMode.Profile -> Unit
        }
    }
    LaunchedEffect(member.id.value, initialVisualMode, managementMenuAnchorResolver) {
        if (initialVisualMode == MembersAgentDetailVisualMode.LifecycleActions) {
            managementMenuAnchorResolver?.resolve()?.let { anchor ->
                managementMenuAnchor = anchor
                managementMenuOpen = true
            }
        }
    }
    val managedByApp =
        !detail?.summary?.managedByAppInstallId.isNullOrBlank() ||
            !member.managedByAppInstallId.isNullOrBlank()
    val visibleTabs = remember(accessMode, canViewPrivate, deleted, managedByApp, member.kind, agentPanelTabOrder) {
        if (member.kind == MemberKind.Agent) {
            membersOrderedAgentTabs(
                visibleTabs =
                    membersAgentDetailTabsForAccess(
                        accessMode = accessMode,
                        canViewPrivateSurfaces = canViewPrivate,
                        deleted = deleted,
                        managedByApp = managedByApp,
                    ),
                storedOrder = agentPanelTabOrder
            )
        } else {
            listOf(MembersAgentDetailTab.Profile)
        }
    }
    val selectedTab = membersAgentDetailSelectedTab(selectedTabOverride, visibleTabs)
    // Task #22: once the tab strip is on screen the tab it shows is the selection, so a later
    // order change (web sync, a reorder) moves the strip around it instead of selecting whatever
    // tab became first.
    val pinnedSelectedTab =
        membersAgentDetailPinnedSelection(
            override = selectedTabOverride,
            shownTab = selectedTab,
            stripShown = member.kind == MemberKind.Agent && agentPanelTabOrderReady
        )
    if (pinnedSelectedTab != selectedTabOverride) selectedTabOverride = pinnedSelectedTab
    // In the profile column beside a conversation, the tab shown is remembered with the profile (beside memory).
    val reportAgentTab = LocalRaftProfileAgentTabReport.current
    if (member.kind == MemberKind.Agent && reportAgentTab != null) {
        LaunchedEffect(selectedTab, reportAgentTab) { reportAgentTab(selectedTab.serverOrderKey) }
    }
    var visitedAgentTabs by remember(member.id.value) {
        mutableStateOf(setOf(selectedTab))
    }
    if (selectedTab !in visitedAgentTabs) {
        visitedAgentTabs = visitedAgentTabs + selectedTab
    }
    fun loadWorkspaceRoot() {
        val callback = agentWorkspaceTreeLoader
        if (callback == null) {
            workspaceTreeState = MembersAgentWorkspaceTreeState.Error(ScreenError("workspace_loader_unavailable"))
            return
        }
        scope.launch {
            workspaceTreeState = MembersAgentWorkspaceTreeState.Loading
            expandedWorkspaceDirs = emptySet()
            loadedWorkspaceDirs = emptyMap()
            loadingWorkspaceDirs = emptySet()
            workspaceDirErrors = emptyMap()
            workspaceTreeState =
                try {
                    callback(member.id).toMembersWorkspaceTreeState()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    MembersAgentWorkspaceTreeState.Error(ScreenError("workspace_root_failed"))
                }
        }
    }
    fun toggleWorkspaceDirectory(path: String) {
        val expanded = path in expandedWorkspaceDirs
        expandedWorkspaceDirs =
            if (expanded) {
                expandedWorkspaceDirs - path
            } else {
                expandedWorkspaceDirs + path
            }
        if (!expanded && path !in loadedWorkspaceDirs && path !in loadingWorkspaceDirs) {
            val callback = agentWorkspaceChildrenLoader
            if (callback == null) {
                workspaceDirErrors = workspaceDirErrors +
                    (path to "workspace_children_unavailable".userFacingErrorCodeMessage(errorStrings))
                return
            }
            scope.launch {
                loadingWorkspaceDirs = loadingWorkspaceDirs + path
                workspaceDirErrors = workspaceDirErrors - path
                when (
                    val result =
                        try {
                            callback(member.id, path)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            DataResult.Error(ScreenError("workspace_children_failed"))
                        }
                ) {
                    is DataResult.Content ->
                        loadedWorkspaceDirs = loadedWorkspaceDirs + (path to result.data.children)
                    is DataResult.Empty ->
                        loadedWorkspaceDirs = loadedWorkspaceDirs + (path to emptyList())
                    is DataResult.NoPermission ->
                        workspaceDirErrors = workspaceDirErrors +
                            (path to result.blocker.userFacingMessage(errorStrings))
                    is DataResult.Error ->
                        workspaceDirErrors = workspaceDirErrors +
                            (path to result.error.userFacingMessage(errorStrings))
                }
                loadingWorkspaceDirs = loadingWorkspaceDirs - path
            }
        }
    }
    fun selectWorkspaceFile(path: String) {
        pager.openRaftFilePreviewPage(agentWorkspaceLazyPreviewPayload(agentId = member.id.value, path = path))
    }
    LaunchedEffect(member.id.value, selectedTab, canViewPrivate, agentWorkspaceTreeLoader) {
        if (
            selectedTab == MembersAgentDetailTab.Workspace &&
            canViewPrivate &&
            workspaceTreeState is MembersAgentWorkspaceTreeState.Idle
        ) {
            loadWorkspaceRoot()
        }
    }
    fun runLifecycleAction(
        action: MembersAgentLifecycleAction,
        confirmed: Boolean = false,
        workspaceChoice: AgentArchiveWorkspaceChoice = AgentArchiveWorkspaceChoice.Keep
    ) {
        if (lifecycleInFlight != null) return
        val callback = onAgentLifecycleAction
        if (!canManage || (agentDetailActions == null && callback == null)) {
            onUnavailableAction(managementUnavailable)
            return
        }
        if (action.requiresConfirmation && !confirmed) {
            actionState = null
            if (sharedAgentDetailStore != null) {
                sharedAgentDetailStore.dispatch(SharedAgentDetailIntent.ShowConfirmation(action))
            } else {
                pendingConfirmation = action
            }
            return
        }
        scope.launch {
            pendingConfirmation = null
            actionState = MembersAgentActionState(action, inProgress = true)
            val result =
                if (sharedAgentDetailStore != null && agentDetailActions != null) {
                    sharedAgentDetailStore.runLifecycleAction(member.id, action, agentDetailActions, workspaceChoice)
                } else {
                    try {
                        requireNotNull(callback).invoke(member.id, action, workspaceChoice)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError("agent_${action.actionKey}_failed"))
                    }
                }
            val error = result.membersLifecycleError(action)
            if (error == null && action.isRestartResetDialogAction()) {
                restartResetDialogOpen = false
                restartResetSelection = MembersAgentLifecycleAction.Restart
            }
            actionState =
                MembersAgentActionState(
                    action = action,
                    inProgress = false,
                    error = error,
                    successLabel = action.successLabel.takeIf { error == null }
                )
        }
    }
    // Web AgentStatusBadge Retry: the menu's Restart (`POST /agents/:id/reset {mode:"restart"}`),
    // offered where Restart is (Actions: runtime control, not archived, the Computer online), with
    // no confirmation; "Failed to reset agent" when it does not go through.
    val retryRestartComputerOffline =
        (machineStatusRow(detail?.summary?.machineId) as? MembersMachineStatusRow.Machine)?.online == false
    val computerOfflineCard =
        if (member.kind == MemberKind.Agent) {
            val serverMembers =
                currentServerId?.takeIf { it.isNotBlank() }?.let { serverId ->
                    serverMembersState.entryFor(ServerId(serverId))?.members
                }.orEmpty()
            resolveMembersAgentComputerOfflineCardState(
                machineId = detail?.summary?.machineId ?: member.membersComputerId,
                machines = offlineComputerInputs.machines,
                offlineObservedAtMs = offlineComputerInputs.offlineObservedAtMs,
                serverMembers = serverMembers,
                commandContext = offlineComputerInputs.commandContext,
                deleted = deleted || member.agentDeletedAt?.isNotBlank() == true,
                isExternalAgent =
                    isExternalAgent || build.raft.app.model.isExternalAgentRuntime(detail?.summary?.runtime),
                isBoundedPublicProjection =
                    membersAgentIsBoundedPublicProjection(
                        accessMode = accessMode,
                        profileProjection =
                            detail?.summary?.profileProjection?.takeIf { it.isNotBlank() }
                                ?: member.profileProjection,
                    ),
                offlineFromRefusalIds = offlineComputerInputs.offlineFromRefusalIds,
            )
        } else {
            null
        }
    val computerRevokedNote =
        if (member.kind == MemberKind.Agent) {
            resolveMembersAgentComputerRevokedNoteState(
                machineId = detail?.summary?.machineId ?: member.membersComputerId,
                machines = offlineComputerInputs.machines,
                commandContext = offlineComputerInputs.commandContext,
                deleted = deleted || member.agentDeletedAt?.isNotBlank() == true,
                isExternalAgent =
                    isExternalAgent || build.raft.app.model.isExternalAgentRuntime(detail?.summary?.runtime),
                isBoundedPublicProjection =
                    membersAgentIsBoundedPublicProjection(
                        accessMode = accessMode,
                        profileProjection =
                            detail?.summary?.profileProjection?.takeIf { it.isNotBlank() }
                                ?: member.profileProjection,
                    ),
            )
        } else {
            null
        }
    val canRetryRestart =
        canManage && agentDetailActions != null && !membersAgentProfileIsRemote(accessMode) && !retryRestartComputerOffline
    var retryRestartInFlight by remember(member.id.value) { mutableStateOf(false) }
    val retryRestartFailed = stringResource(Res.string.agent_status_retry_failed)
    fun retryRestart() {
        val actions = agentDetailActions ?: return
        if (retryRestartInFlight || lifecycleInFlight != null) return
        retryRestartInFlight = true
        scope.launch {
            val action = MembersAgentLifecycleAction.Restart
            val result =
                try {
                    if (sharedAgentDetailStore != null) {
                        sharedAgentDetailStore.runLifecycleAction(member.id, action, actions)
                    } else {
                        actions.runLifecycleAction(member.id, action)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_${action.actionKey}_failed"))
                } finally {
                    retryRestartInFlight = false
                }
            result.membersLifecycleError(action)?.let { error ->
                if (onAgentErrorToast != null) onAgentErrorToast(retryRestartFailed, error) else onUnavailableAction(retryRestartFailed)
            }
        }
    }
    fun copyDiagnostics() {
        val diagnostics = buildMembersAgentDiagnostics(member, detail, agentStatusIndicatorState)
        if (sharedAgentDetailStore != null && agentDetailActions != null) {
            sharedAgentDetailStore.copyDiagnostics(diagnostics, agentDetailActions, diagnosticsCopied)
        } else {
            onCopyAgentDiagnostics(diagnostics)
            diagnosticActionState =
                MembersAgentDiagnosticActionState(
                    actionKey = "copy",
                    inProgress = false,
                    successLabel = diagnosticsCopied
                )
        }
    }
    fun openReportIssue() {
        if (!agentIssueReportAllowed(presentation.privacyMode.value)) return
        issueReportReceipt = null
        issueReportReferenceCopied = false
        reportIssueDialogOpen = true
    }
    fun submitIssueReport(
        selection: MembersAgentIssueReportSelection,
        canIncludeMachineLog: Boolean,
        canIncludeDiagnosticsBundle: Boolean,
    ) {
        val callback = onReportAgentIssue
        if (diagnosticInFlight != null || issueReportSubmitting) return
        // The dialog was open when the answer turned "on" (or the environment changed): never send.
        if (!agentIssueReportAllowed(presentation.privacyMode.value)) return
        if (agentDetailActions == null && callback == null) {
            onUnavailableAction(issueReportingUnavailable)
            return
        }
        issueReportSubmitting = true
        scope.launch {
            try {
                diagnosticActionState =
                    MembersAgentDiagnosticActionState(actionKey = "report", inProgress = true)
                val request =
                    SharedAgentIssueReportRequest(
                        description = selection.description.trim(),
                        diagnostics = buildMembersAgentDiagnostics(member, detail, agentStatusIndicatorState),
                        includeRecentMessages = selection.includeRecentMessages,
                        includeLiveActivity = selection.includeLiveActivity,
                        includeActivityHistory = selection.includeActivityHistory,
                        includeRuntimeTranscript = selection.includeRuntimeTranscript,
                        liveActivity = storeState.agentRealtimeActivityLogs[member.id].orEmpty(),
                        title = issueReportTitle,
                        locale = raftCurrentUiLocaleTag(),
                        includeMachineLogTail = selection.includeMachineLogTail,
                        canIncludeMachineLog = canIncludeMachineLog,
                        includeDiagnosticsBundle = selection.includeDiagnosticsBundle,
                        canIncludeDiagnosticsBundle = canIncludeDiagnosticsBundle,
                        diagnosticsBundleRange = selection.diagnosticsBundleRange
                    )
                val result =
                    if (sharedAgentDetailStore != null && agentDetailActions != null) {
                        sharedAgentDetailStore.reportIssue(request, agentDetailActions, issueReported)
                    } else {
                        try {
                            requireNotNull(callback).invoke(request)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            DataResult.Error(ScreenError("agent_issue_report_failed"))
                        }
                    }
                val error =
                    when (result) {
                        is DataResult.Content -> null
                        is DataResult.Empty -> ScreenError(result.reason.name, retryable = false)
                        is DataResult.Error -> result.error
                        is DataResult.NoPermission -> ScreenError(result.blocker.code, retryable = false)
                    }
                diagnosticActionState =
                    MembersAgentDiagnosticActionState(
                        actionKey = "report",
                        inProgress = false,
                        error = error,
                        successLabel = issueReported.takeIf { error == null }
                    )
                if (result is DataResult.Content) {
                    reportIssueDialogOpen = false
                    issueReportReceipt = result.data
                    issueReportReferenceCopied = false
                }
            } finally {
                issueReportSubmitting = false
            }
        }
    }
    fun openRestartResetDialog() {
        restartResetSelection = MembersAgentLifecycleAction.Restart
        actionState = null
        restartResetDialogOpen = true
    }
    // The change-avatar dialog's Save (web AgentProfileEditDialog): a picked image goes through
    // the avatar upload, a pixel avatar or the default through the profile update.
    suspend fun saveAvatar(draft: MembersAgentAvatarDraft): DataResult<*> {
        val callback = onUpdateAgentProfile
        val upload = onUploadAgentAvatar
        if (!canManage || deleted || (draft is MembersAgentAvatarDraft.Upload && upload == null) || callback == null) {
            onUnavailableAction(avatarEditingUnavailable)
            return DataResult.Error(ScreenError("agent_avatar_edit_unavailable", retryable = false))
        }
        if (draft is MembersAgentAvatarDraft.Upload) {
            return checkNotNull(upload)(member.id, draft.file)
        }
        return callback(
            AgentProfileUpdateRequest(
                agentId = member.id,
                displayName = member.displayName,
                description = member.description?.takeIf { it.isNotBlank() },
                avatarUrl = draft.profileAvatarUrl(),
                includeAvatarUrl = true
            )
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RaftColor.SurfaceWhite)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Web AgentDetailPanel `activityErrorText`: known runtime errors in the app's words (a
            // "not installed" says how the lookup ended and names the runtime, botiverse/slock
            // #12729), anything else as the server put it.
            if (member.kind == MemberKind.Agent) build.raft.app.data.RaftRuntimeCatalogNames.observeCatalogs()
            val runtimeErrorDisplay = member.membersAgentRuntimeErrorDisplay(detail, agentStatusIndicatorState, canViewPrivate)
            val runtimeErrorText = runtimeErrorDisplay?.let { agentRuntimeErrorDisplayText(it) }
            if (member.kind == MemberKind.Human) {
                MembersHumanDetailHeader(
                    member = member,
                    currentUserId = currentUserId,
                    currentServerId = currentServerId,
                    memberAvatarResolver = memberAvatarResolver,
                    viewerRole = humanActorRole,
                    onBack = onBack,
                    onOpenDirectMessage = onOpenDirectMessage,
                    directMessageOpening = actions.directMessageOpening
                )
            } else {
                MembersDetailHeader(
                    member = member,
                    currentUserId = currentUserId,
                    currentServerId = currentServerId,
                    detail = detail,
                    deleted = deleted,
                    canManage = canManage,
                    canMessage = !membersAgentProfileIsRemote(accessMode),
                    onBack = onBack,
                    onOpenDirectMessage = onOpenDirectMessage,
                    directMessageOpening = actions.directMessageOpening,
                    onStartCall = actions.onStartCall?.takeIf { !membersAgentProfileIsRemote(accessMode) },
                    onOpenLounge =
                        actions.onOpenLounge?.takeIf {
                            agentOffersLounge(
                                deleted = deleted || member.agentDeletedAt?.isNotBlank() == true,
                                personalAssistant =
                                    detail?.summary?.assistantOwnerUserId != null || member.assistantPausedAt != null,
                                remoteProfile = membersAgentProfileIsRemote(accessMode)
                            )
                        },
                    loungeOpening = actions.loungeOpening,
                    onCopyMemberId = onCopyMemberId,
                    agentStatusIndicatorState = agentStatusIndicatorState,
                    memberAvatarResolver = memberAvatarResolver,
                    onManagementMenuAnchorResolverReady = { managementMenuAnchorResolver = it },
                    onRequestManagementMenu = { anchor ->
                        if (anchor != null) {
                            managementMenuAnchor = anchor
                            managementMenuOpen = true
                        }
                    }
                )
            }
            val runtimeErrorBanner =
                membersAgentRuntimeErrorBannerState(
                    memberKind = member.kind,
                    selectedTab = selectedTab,
                    runtimeErrorText = runtimeErrorText,
                    billingExhausted = runtimeErrorDisplay?.isBillingExhausted == true
                )
            if (runtimeErrorBanner != null) {
                MembersAgentRuntimeErrorBanner(
                    errorText = runtimeErrorBanner.errorText,
                    canViewPrivate = canViewPrivate,
                    showViewLogs = runtimeErrorBanner.showViewLogs,
                    onViewLogs = { selectedTabOverride = MembersAgentDetailTab.Activity },
                    onCopyInfo = { copyDiagnostics() },
                    billingAgentName = member.displayName.takeIf { runtimeErrorBanner.billingExhausted },
                    onOpenModelSettings = openModelSettings,
                    coded = runtimeErrorDisplay as? AgentRuntimeErrorDisplay.Coded
                )
            }
            if (member.kind == MemberKind.Agent && !agentPanelTabOrderReady) {
                // Task #22: cold start without a known tab order. Draw no strip rather than the
                // default order that would reorder a moment later.
                RaftCenteredLoading(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else if (member.kind == MemberKind.Agent) {
                // task #232 PR2: the six agent tabs share TabPager with Activity
                // (PR1, #2224). The tab bar stays the tap surface and selection
                // owner; a horizontal swipe on the body settles on a neighbour
                // tab. Gating matches the Activity caller exactly: a page
                // composes real content only while it is the pager's current
                // page AND the selection has settled on it (`active` is the
                // pager's own current-page flag, and onSelectedIndexChange
                // fires once per settle), so an incoming page shows the
                // loading body for the whole drag, then fills — and a
                // keepItemAlive-hidden page stops composing its tab content
                // instead of keeping its effects alive. Per-tab cost therefore equals the
                // previous single `when (selectedTab)`. State that must survive
                // a swipe is hoisted above: workspace tree, chat tick, profile
                // editing, and (agentTabListStates) each tab's scroll position.
                // Keyed by the order: a reorder rebuilds the pager on the selected tab's new index
                // instead of animating across pages that now hold other tabs.
                key(visibleTabs) {
                TabPager(
                    selectedIndex = membersAgentDetailPagerIndex(visibleTabs, selectedTab),
                    onSelectedIndexChange = { index ->
                        membersAgentDetailPageTab(visibleTabs, index)?.let { selectedTabOverride = it }
                    },
                    pageCount = visibleTabs.size.coerceAtLeast(1),
                    gesturePolicy = TabPagerGesturePolicy(
                        startEdgeInset = raftPlatformTabPagerStartEdgeInsetDp().dp,
                        swipeEnabled = raftPlatformTabPagerSwipeEnabled(),
                    ),
                    keepItemAlive = true,
                    tabBar = {
                        MembersAgentTabBar(
                            tabs = visibleTabs,
                            selectedTab = selectedTab,
                            onSelectedTabChange = { selectedTabOverride = it },
                            onReorderTabs = onAgentPanelTabOrderChange
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page, active ->
                    val pageTab = membersAgentDetailPageTab(visibleTabs, page)
                    if (pageTab == null || !active || !membersAgentDetailShouldComposeTab(pageTab, selectedTab, visitedAgentTabs)) {
                        // RaftCenteredLoading renders common_loading when message
                        // is null — only shown for tabs that have not been visited yet.
                        RaftCenteredLoading(modifier = Modifier.fillMaxSize())
                    } else if (pageTab == MembersAgentDetailTab.Activity) {
                        MembersAgentActivityTab(
                            detail = detail,
                            serverId = currentServerId,
                            agentStatusIndicatorState = agentStatusObservedState,
                            heldByOfflineComputer =
                                membersAgentHeldByOfflineComputer(
                                    computerOffline =
                                        (machineStatusRow(detail?.summary?.machineId) as? MembersMachineStatusRow.Machine)
                                            ?.online == false,
                                    indicator = agentStatusObservedState,
                                    lifecycleStatus = detail?.summary?.status ?: member.agentLifecycleStatus,
                                    external = member.isExternal || agentStatusObservedState?.external == true,
                                ),
                            loadState = agentActivityLoadState,
                            overview = activityOverview,
                            onOpenComputer = onOpenComputer,
                            onOpenMessage = onOpenActivityMessage,
                            diagnosticActionState = effectiveDiagnosticActionState,
                            onCopyDiagnostics = { copyDiagnostics() },
                            timeContext = timeContext,
                            onOpenUrl = onOpenUrl,
                            autoScrollToLatest = !stableVisualCapture,
                            modifier = Modifier.fillMaxSize(),
                            subagentStop = activitySubagentStop,
                            workers = activityWorkers,
                        )
                    } else if (pageTab == MembersAgentDetailTab.Usage) {
                        MembersAgentUsageTab(
                            agentId = member.id,
                            serverId = currentServerId?.trim()?.takeIf { it.isNotEmpty() }?.let(::ServerId),
                            agentsApiClient = agentsApiClient,
                            timeContext = timeContext,
                            onOpenComputer = onOpenComputer,
                            state = usageTabState,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            state = agentTabListStates.getValue(pageTab),
                            modifier = Modifier.fillMaxSize().raftSafeAreaHorizontalPadding(),
                            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
                        ) {
                            // Web AgentWorkspace's path bar sits right under the tabs: no spacer.
                            if (
                                initialVisualMode != MembersAgentDetailVisualMode.AvatarManagement &&
                                pageTab != MembersAgentDetailTab.Workspace
                            ) {
                                item(key = "member-detail-${member.id.value}-top-spacer") {
                                    // Web AgentDetailPanel's hero sits 20dp under the tabs
                                    // (`py-5`): this slot's 16dp gap plus the hero's 4dp. Web
                                    // AgentAppAccessTab's `py-4` is the slot's 16dp gap alone.
                                    Spacer(
                                        Modifier.height(
                                            when (pageTab) {
                                                MembersAgentDetailTab.Profile,
                                                MembersAgentDetailTab.Apps -> 0.dp
                                                else -> RaftAppSpacing.Sm
                                            }
                                        )
                                    )
                                }
                            }
                            if (
                                membersAgentDetailShowsProfileStateNotice(
                                    selectedTab = pageTab,
                                    avatarManagementMode = initialVisualMode == MembersAgentDetailVisualMode.AvatarManagement,
                                    state = agentDetailState,
                                )
                            ) {
                                item(key = "member-detail-${member.id.value}-state-notice") {
                                    MembersAgentDetailStateNotice(
                                        state = agentDetailState,
                                        modifier = Modifier.padding(horizontal = MembersPageInset)
                                    )
                                }
                            }
                            // This item switches between composables with different remember slots
                            // as the selected tab/member changes.  An implicit index key lets
                            // Kuikly's lazy provider reuse the previous tab's slot table; on
                            // Android that can make rememberCoroutineScope read a Boolean (or
                            // another tab state) as CompositionScopedCoroutineScopeCanceller and
                            // crash while entering the profile.  Keep each member/tab surface a
                            // distinct lazy item so state is disposed/recreated at the boundary.
                            item(key = membersAgentDetailBodyItemKey(member.id.value, pageTab)) {
                                // Kuikly's lazy item key is not sufficient to fence the nested
                                // composition on every renderer.  Give each tab surface its own
                                // Compose group as well, so remember slots from Profile/Chat/etc.
                                // can never be reused across a tab switch.
                                key(pageTab) {
                                    when (pageTab) {
                                    MembersAgentDetailTab.Profile ->
                                        MembersAgentProfileTab(
                                            member = member,
                                            detail = detail,
                                            runtimeModels = MembersAgentRuntimeModelInputs(
                                                agentMachineRuntimes = agentMachineRuntimes,
                                                agentRuntimeModelsByRuntime = agentRuntimeModelsByRuntime,
                                                onAgentRuntimeModelsRequest = onAgentRuntimeModelsRequest
                                            ),
                                            createAgentFormStore = createAgentFormStore,
                                            agentProfileEditAuthority = agentProfileEditAuthority,
                                            canManage = canManage,
                                            runtimeEditorCanManage = runtimeEditorCanManage,
                                            agentStatusIndicatorState = agentStatusIndicatorState,
                                            agentStatusObservedState = agentStatusObservedState,
                                            agentStatusResolver = agentStatusResolver,
                                            memberAvatarResolver = memberAvatarResolver,
                                            onChangeAvatar = { showAvatarPicker = true },
                                            sharedAgentDetailStore = sharedAgentDetailStore,
                                            storeState = storeState.takeIf { sharedAgentDetailStore != null },
                                            agentDetailActions = agentDetailActions,
                                            onUpdateAgentProfile = onUpdateAgentProfile,
                                            onApplyAgentProfileResult = onApplyAgentProfileResult,
                                            onApplyAgentRuntimeConfigProjection = onApplyAgentRuntimeConfigProjection,
                                            onErrorToast = onAgentErrorToast,
                                            agentsApiClient = agentsApiClient,
                                            archiveInputs = MembersAgentArchiveInputs(
                                                loadArchive = { id ->
                                                    agentsApiClient?.agentArchive(id)
                                                        ?: DataResult.Error(
                                                            ScreenError(code = "archive_unavailable", retryable = false)
                                                        )
                                                },
                                                deleteWorkspace = { id ->
                                                    agentsApiClient?.deleteAgentArchiveWorkspace(id)
                                                        ?: DataResult.Error(
                                                            ScreenError(code = "archive_unavailable", retryable = false)
                                                        )
                                                },
                                                memberNameByUserId = { userId ->
                                                    val serverId = currentServerId
                                                    if (serverId.isNullOrBlank()) {
                                                        null
                                                    } else {
                                                        serverMembersState.entryFor(ServerId(serverId))
                                                            ?.members
                                                            ?.firstOrNull { it.id.value == userId }
                                                            ?.displayName
                                                    }
                                                }
                                            ),
                                            externalSetupServerUrl = externalSetupServerUrl,
                                            onUnavailableAction = onUnavailableAction,
                                            lifecycle = MembersAgentLifecycleInputs(
                                                lifecycleInFlight = lifecycleInFlight,
                                                diagnosticActionState = effectiveDiagnosticActionState,
                                                onRunLifecycleAction = { runLifecycleAction(it) },
                                                onOpenRestartReset = { openRestartResetDialog() },
                                                onCopyDiagnostics = { copyDiagnostics() },
                                                onCopyText = { text -> agentDetailActions?.copyDiagnostics(text) ?: Unit },
                                                onReportIssue = { openReportIssue() },
                                                onOpenComputer = onOpenComputer,
                                                onOpenManagedApp = actions.onOpenManagedApp,
                                                onStopTurn = profileStopTurn,
                                                stopTurnInProgress = turnStopState.inFlightAgentId != null,
                                                showActivityDetail = canViewPrivate,
                                                onOpenModelSettings = openModelSettings,
                                                runtimeEditorOpenRequested = runtimeEditorOpenRequested,
                                                onRuntimeEditorOpenRequestHandled = { runtimeEditorOpenRequested = false },
                                                timeContext = timeContext,
                                                onRetryRestart = if (canRetryRestart) ({ retryRestart() }) else null,
                                                retryRestartInProgress =
                                                    retryRestartInFlight ||
                                                        lifecycleInFlight == MembersAgentLifecycleAction.Restart.actionKey,
                                                recentActivity =
                                                    // Web `showRecentActivity`; its turns are the overview's.
                                                    activityOverview?.takeIf {
                                                        membersAgentShowsRecentActivity(visibleTabs = visibleTabs, deleted = deleted)
                                                    }?.let { overview ->
                                                        MembersAgentRecentActivityInputs(
                                                            overview = overview,
                                                            serverId = currentServerId,
                                                            timeContext = timeContext,
                                                            onOpenActivity = {
                                                                selectedTabOverride = MembersAgentDetailTab.Activity
                                                            }
                                                        )
                                                    },
                                                computerOfflineCard = computerOfflineCard,
                                                computerRevokedNote = computerRevokedNote
                                            ),
                                            machineStatusRow = machineStatusRow,
                                            timeContext = timeContext,
                                            fixtureStatusFallbackEnabled = stableVisualCapture,
                                            accessMode = accessMode,
                                            // Web profile tab sections are `px-5`.
                                            modifier = Modifier.padding(horizontal = MembersAgentProfileInset)
                                        )
                                    MembersAgentDetailTab.Chat ->
                                        MembersAgentChatTab(
                                            detail = detail,
                                            currentServerId = currentServerId,
                                            serverMembersState = serverMembersState,
                                            timeContext = timeContext,
                                            pageVisibleTick = pageVisibleTick,
                                            modifier = Modifier.padding(horizontal = MembersPageInset)
                                        )
                                    MembersAgentDetailTab.Reminders ->
                                        MembersAgentRemindersTab(
                                            detail = detail,
                                            timeContext = timeContext,
                                            onOpenMessageRef = onOpenReminderMessage,
                                            modifier = Modifier.padding(horizontal = MembersPageInset)
                                        )
                                    MembersAgentDetailTab.Workspace ->
                                        MembersAgentWorkspaceTab(
                                            treeState = workspaceTreeState,
                                            expandedDirs = expandedWorkspaceDirs,
                                            loadedDirs = loadedWorkspaceDirs,
                                            loadingDirs = loadingWorkspaceDirs,
                                            dirErrors = workspaceDirErrors,
                                            workspacePath = membersAgentWorkspacePath(member.id.value, detail?.summary),
                                            onCopyPath = onCopyAgentIssueReference,
                                            onRefresh = { loadWorkspaceRoot() },
                                            onToggleDirectory = { toggleWorkspaceDirectory(it) },
                                            onSelectFile = { selectWorkspaceFile(it) }
                                        )
                                    MembersAgentDetailTab.Apps ->
                                        MembersAgentAppsTab(
                                            agentId = member.id,
                                            detail = detail,
                                            timeContext = timeContext,
                                            onOpenUrl = onOpenUrl,
                                            appEventsLoader = agentAppEventsLoader,
                                            appEventLoader = agentAppEventLoader,
                                            appAccess = remoteControls?.appAccess,
                                            appEventsState = appEventsState,
                                            // Web AgentAppAccessTab: `px-5`.
                                            modifier = Modifier.padding(horizontal = MembersAgentProfileInset)
                                        )
                                        MembersAgentDetailTab.Activity,
                                        MembersAgentDetailTab.Usage -> Unit
                                    }
                                }
                            }
                            if (
                                pageTab == MembersAgentDetailTab.Profile &&
                                migrationVisible &&
                                migrationBinding != null &&
                                migrationState.agentId == member.id
                            ) {
                                item(key = "member-detail-${member.id.value}-migration") {
                                    val liveTime = currentRaftTimeContextProvider
                                    AgentMigrationSection(
                                        state = migrationState,
                                        inputs =
                                            AgentMigrationSectionInputs(
                                                sourceMachineId = detail?.summary?.machineId,
                                                computers = migrationBinding.computers,
                                                hostedAvailable = migrationBinding.hostedAvailable,
                                                proGate = raftProGateDecision(migrationState.billingPlan, defaultNowMillis()),
                                                proGateChecking = migrationState.billingChecking,
                                                onIntent = migrationBinding.store::dispatch,
                                                onOpenBilling = migrationBinding.onOpenBilling,
                                                onCopy = onCopyAgentIssueReference,
                                                formatTimestamp = { value ->
                                                    build.raft.app.model.formatRaftTimestampLabel(value, timeContext).takeIf { it.isNotBlank() }
                                                },
                                                onAgentPlacementChanged = migrationBinding.onAgentPlacementChanged,
                                                agentRuntime = detail?.summary?.runtime,
                                                placesWithoutSource =
                                                    membersMigrationPlacesWithoutSource(
                                                        executionMode = detail?.summary?.executionMode,
                                                        blocked = agentStatusIndicatorState?.blocked
                                                            ?: agentBlockedState(member.agentBlockedReason)
                                                    ),
                                                agentName = member.displayName.ifBlank { member.handle },
                                                viewerUserId = currentUserId,
                                                formatClock = { value -> formatThreadTimeRevealLabel(value, timeContext).takeIf { it.isNotBlank() } },
                                                // Reading the live time context re-reads the lines that count minutes
                                                // ("at most N more minutes", "N minutes so far") as it ticks, once a
                                                // minute, between status reads that change nothing; the minutes come
                                                // from the wall clock, as the web's `currentTimeMs()`.
                                                nowMillis = {
                                                    liveTime?.current
                                                    defaultNowMillis()
                                                },
                                                computerAgents = migrationBinding.computerAgents,
                                                onOpenMigrationLog = migrationBinding.onOpenLog,
                                                onInfoToast = migrationBinding.onInfoToast
                                            ),
                                        // The list's own item spacing is the web section's `py-4` bottom.
                                        bottomPadding = 0.dp
                                    )
                                }
                            }
                            // Web: Actions close the profile, after Agent migration.
                            if (pageTab == MembersAgentDetailTab.Profile && !membersAgentProfileIsRemote(accessMode)) {
                                item(key = "member-detail-${member.id.value}-actions") {
                                    MembersAgentProfileActionsSection(
                                        member = member,
                                        detail = detail,
                                        resolvedStatus = agentStatusIndicatorState,
                                        canManage = canManage,
                                        computerOffline =
                                            (machineStatusRow(detail?.summary?.machineId) as? MembersMachineStatusRow.Machine)
                                                ?.online == false,
                                        lifecycleInFlight = lifecycleInFlight,
                                        diagnosticActionState = effectiveDiagnosticActionState,
                                        onRunLifecycleAction = { runLifecycleAction(it) },
                                        onOpenRestartReset = { openRestartResetDialog() },
                                        onCopyDiagnostics = { copyDiagnostics() },
                                        onReportIssue = { openReportIssue() },
                                        reportIssueOffered = reportIssueOffered,
                                        modifier = Modifier.padding(horizontal = MembersAgentProfileInset)
                                    )
                                }
                            }
                            item(key = "member-detail-${member.id.value}-bottom-spacer") {
                                Spacer(Modifier.height(180.dp))
                            }
                        }
                    }
                }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().raftSafeAreaHorizontalPadding(),
                ) {
                    item(key = "member-detail-${member.id.value}-human-content") {
                        MembersHumanDetailSections(
                            member = member,
                            timeContext = timeContext,
                            memberAvatarResolver = memberAvatarResolver,
                            agentStatusResolver = agentStatusResolver,
                            fixtureStatusFallbackEnabled = stableVisualCapture,
                            actorRole = humanActorRole,
                            accessMode = humanAccessMode,
                            currentUserId = currentUserId,
                            currentServerId = currentServerId,
                            ownerCount = humanOwnerCount,
                            onUpdateRole = onUpdateHumanRole,
                            onUpdateDescription = humanRole.onUpdateHumanDescription,
                            onApplyDescription = humanRole.onApplyHumanDescription,
                            onRemoveMember = onRemoveMember?.takeIf {
                                member.membershipStatus != "removed"
                            },
                            onPromoteGuest = actions.onPromoteGuest,
                            promotingGuest = actions.promotingGuest,
                            onOpenCreatedAgent = onOpenCreatedAgent
                        )
                    }
                    item(key = "member-detail-${member.id.value}-bottom-spacer") {
                        Spacer(Modifier.height(180.dp))
                    }
                }
            }
        }
        val dropdownAnchor = managementMenuAnchor
        if (managementMenuOpen && member.kind == MemberKind.Agent && dropdownAnchor != null) {
            val menuPrimary =
                membersResolvedPrimaryLifecycleAction(
                    resolvedStatus = agentStatusIndicatorState?.status,
                    detailFallbackStatus = null,
                    deleted = deleted,
                    hasResolvedPresence = true
                )
            val menuItems =
                buildMembersManagementMenuItems(
                    primaryAction = menuPrimary,
                    canManage = canManage,
                    deleted = deleted,
                    inProgress = lifecycleInFlight != null
                ).map { item ->
                    RaftUiMenuItem(
                        id = item.id,
                        label = item.label.localized(),
                        icon = item.icon,
                        enabled = item.enabled
                    )
                }
            RaftUiControlledMenu(
                expanded = managementMenuOpen,
                anchor = dropdownAnchor,
                items = menuItems,
                onSelect = { id ->
                    managementMenuOpen = false
                    handleMembersManagementMenuSelection(
                        id = id,
                        primaryAction = menuPrimary,
                        openRestartResetDialog = { openRestartResetDialog() },
                        runLifecycleAction = { runLifecycleAction(it) }
                    )
                },
                onDismiss = {
                    managementMenuOpen = false
                }
            )
        }
        if (restartResetDialogOpen && member.kind == MemberKind.Agent) {
            val selectedActionResult =
                if (sharedAgentDetailStore != null) {
                    storeState.lastActionResult
                        ?.takeIf { it.actionKey == restartResetSelection.actionKey }
                        ?.let {
                            MembersAgentActionState(
                                action = restartResetSelection,
                                inProgress = false,
                                error = it.error,
                                successLabel = it.successLabel
                            )
                        }
                } else {
                    actionState?.takeIf { it.action == restartResetSelection }
                }
            MembersRestartResetDialog(
                agentName = member.displayName,
                selectedAction = restartResetSelection,
                busy = lifecycleInFlight != null,
                errorMessage = selectedActionResult?.error?.userFacingMessage(errorStrings),
                onSelectedActionChange = { restartResetSelection = it },
                onConfirm = { runLifecycleAction(restartResetSelection, confirmed = true) },
                onDismiss = {
                    if (lifecycleInFlight == null) {
                        restartResetDialogOpen = false
                        restartResetSelection = MembersAgentLifecycleAction.Restart
                        actionState = null
                    }
                }
            )
        }
        if (showAvatarPicker && member.kind == MemberKind.Agent && canManage && !deleted) {
            MembersAgentAvatarDialog(
                member = member,
                currentAvatarWireValue = rememberRaftMemberAvatarWireValue(memberAvatarResolver, member.id.value),
                onPickFile = onPickAgentAvatarFile,
                onSave = { draft -> saveAvatar(draft) },
                onDismiss = { showAvatarPicker = false }
            )
        }
        if (reportIssueDialogOpen && member.kind == MemberKind.Agent) {
            val reportState = effectiveDiagnosticActionState?.takeIf { it.actionKey == "report" }
            // Web `canIncludeMachineLog` (slock #15306): the reporter attached the agent's
            // machine, or is a server owner/admin; ticked by default only on their own.
            val reportMachine = machineStatusRow(detail?.summary?.machineId) as? MembersMachineStatusRow.Machine
            val ownAttachedMachine = reportMachine?.attachedByCurrentUser == true
            val canIncludeMachineLog =
                membersAgentIssueCanIncludeMachineLog(
                    hasMachine = reportMachine != null,
                    attachedByCurrentUser = ownAttachedMachine,
                    serverRole = remoteControls?.serverRole,
                )
            MembersAgentIssueReportDialog(
                agentName = member.displayName,
                runtimeTranscriptAvailable = detail?.summary?.machineId?.isNotBlank() == true,
                canIncludeMachineLog = canIncludeMachineLog,
                ownAttachedMachine = ownAttachedMachine,
                busy = reportState?.inProgress == true,
                errorMessage =
                    reportState?.error?.let { error ->
                        membersAgentIssueReportErrorMessage(error, error.userFacingMessage(errorStrings))
                    },
                errorTraceId = reportState?.error?.traceId,
                privacyMode = privacyMode,
                onSubmit = { selection -> submitIssueReport(selection, canIncludeMachineLog, ownAttachedMachine) },
                onDismiss = {
                    if (reportState?.inProgress != true) {
                        reportIssueDialogOpen = false
                    }
                }
            )
        }
        issueReportReceipt?.let { receipt ->
            val retryActions = agentDetailActions
            MembersAgentIssueReportReceiptDialog(
                receipt = receipt,
                referenceCopied = issueReportReferenceCopied,
                ticketRetrying = issueTicketRetrying,
                onCopyReference = {
                    onCopyAgentIssueReference(buildMembersAgentIssueReportReference(receipt))
                    issueReportReferenceCopied = true
                },
                onRetryTicket =
                    receipt.ticketRequest?.takeIf { retryActions != null }?.let { ticketRequest ->
                        {
                            if (!issueTicketRetrying) {
                                issueTicketRetrying = true
                                scope.launch {
                                    try {
                                        val retried = requireNotNull(retryActions).retryIssueTicket(ticketRequest)
                                        if (retried !is DataResult.Content) {
                                            onUnavailableAction(issueTicketRetryFailed)
                                        } else if (issueReportReceipt?.reportId == receipt.reportId) {
                                            issueReportReceipt = receipt.copy(ticketId = retried.data)
                                        }
                                    } finally {
                                        issueTicketRetrying = false
                                    }
                                }
                            }
                        }
                    },
                onViewTicket =
                    onOpenFeedbackTicket?.let { open ->
                        { ticketId: String ->
                            issueReportReceipt = null
                            issueReportReferenceCopied = false
                            open(ticketId)
                        }
                    },
                onDismiss = {
                    issueReportReceipt = null
                    issueReportReferenceCopied = false
                }
            )
        }
        val activePendingConfirmation =
            if (sharedAgentDetailStore != null) storePendingConfirmation else pendingConfirmation
        activePendingConfirmation?.let { action ->
            // task #515: archive (the former delete) gains the web
            // `AgentArchiveDialog` contract — "keep on the Computer" vs "delete
            // the workspace" — only when this agent actually has a workspace on
            // a Computer, matching the web gate `hasComputerWorkspace`
            // (`machineId` set, not an external runtime).
            val archiveHasComputerWorkspace =
                action == MembersAgentLifecycleAction.Delete &&
                    membersAgentArchiveHasComputerWorkspace(
                        machineId = detail?.summary?.machineId,
                        isExternalAgent = isExternalAgent
                    )
            var archiveWorkspaceChoice by remember(member.id.value, detail?.summary?.machineId) {
                mutableStateOf(AgentArchiveWorkspaceChoice.Keep)
            }
            // Reuse the shared unified dialog (React ConfirmDialog is a plain
            // card: uppercase 18px title + X, no header divider, max-w-sm).
            RaftUnifiedDialog(
                title = membersLifecycleConfirmTitle(action, member.displayName).raftDisplayUppercase(),
                dismissEnabled = lifecycleInFlight != action.actionKey,
                dismissOnOutsideTap = false,
                maxWidth = 384.dp,
                contentPadding = 24.dp,
                onDismiss = {
                    if (sharedAgentDetailStore != null) {
                        sharedAgentDetailStore.dispatch(SharedAgentDetailIntent.DismissConfirmation)
                    } else {
                        pendingConfirmation = null
                    }
                }
            ) {
                MembersLifecycleConfirmation(
                    action = action,
                    agentName = member.displayName,
                    busy = lifecycleInFlight == action.actionKey,
                    archiveHasComputerWorkspace = archiveHasComputerWorkspace,
                    archiveComputerName = detail?.summary?.machineName?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.member_archive_unknown_computer),
                    archiveWorkspaceChoice = archiveWorkspaceChoice,
                    onArchiveWorkspaceChoiceChange = { archiveWorkspaceChoice = it },
                    onConfirm = { runLifecycleAction(action, confirmed = true, workspaceChoice = archiveWorkspaceChoice) },
                    onCancel = {
                        if (sharedAgentDetailStore != null) {
                            sharedAgentDetailStore.dispatch(SharedAgentDetailIntent.DismissConfirmation)
                        } else {
                            pendingConfirmation = null
                            actionState = null
                        }
                    }
                )
            }
        }
    }
}

internal fun DataResult<AgentLifecycleActionResult>.membersLifecycleError(action: MembersAgentLifecycleAction): ScreenError? =
    when (this) {
        is DataResult.Content ->
            if (data.success) null else ScreenError("agent_${action.actionKey}_failed")
        is DataResult.Empty -> ScreenError(reason.name, retryable = false)
        is DataResult.Error -> error
        is DataResult.NoPermission -> ScreenError(blocker.code, retryable = false)
    }

internal data class MembersManagementMenuItemSpec(
    val id: String,
    val label: StringDesc,
    val icon: RaftLucideIconName,
    val enabled: Boolean
)

internal fun buildMembersManagementMenuItems(
    primaryAction: MembersAgentLifecycleAction?,
    canManage: Boolean,
    deleted: Boolean,
    inProgress: Boolean
): List<MembersManagementMenuItemSpec> {
    // The agent More menu holds the runtime controls only (artin): Direct Message is the head's
    // own button and there is no Stop Current Turn item.
    if (deleted || !canManage) return emptyList()
    val items = mutableListOf<MembersManagementMenuItemSpec>()
    if (primaryAction != null) {
        items += MembersManagementMenuItemSpec(
            id = primaryAction.actionKey,
            label = when (primaryAction) {
                MembersAgentLifecycleAction.Start -> Res.string.member_action_start_agent.desc()
                MembersAgentLifecycleAction.Stop -> Res.string.member_action_stop_agent.desc()
                else -> primaryAction.label.desc()
            },
            icon = primaryAction.iconName,
            enabled = !inProgress
        )
    }
    items += MembersManagementMenuItemSpec(
        id = MembersAgentLifecycleAction.Restart.actionKey,
        label = Res.string.member_label_restart_reset.desc(),
        icon = RaftLucideIconName.RotateCcw,
        enabled = !inProgress
    )
    return items
}

private fun handleMembersManagementMenuSelection(
    id: String,
    primaryAction: MembersAgentLifecycleAction?,
    openRestartResetDialog: () -> Unit,
    runLifecycleAction: (MembersAgentLifecycleAction) -> Unit
) {
    if (id == MembersAgentLifecycleAction.Restart.actionKey) {
        openRestartResetDialog()
        return
    }
    val action = MembersAgentLifecycleAction.values().firstOrNull { it.actionKey == id }
        ?: primaryAction?.takeIf { it.actionKey == id }
    if (action != null) {
        runLifecycleAction(action)
    }
}

private fun DataResult<AgentWorkspaceTreeSnapshot>.toMembersWorkspaceTreeState(): MembersAgentWorkspaceTreeState =
    when (this) {
        is DataResult.Content -> MembersAgentWorkspaceTreeState.Content(data)
        is DataResult.Empty -> MembersAgentWorkspaceTreeState.Empty
        is DataResult.Error -> MembersAgentWorkspaceTreeState.Error(error)
        is DataResult.NoPermission -> MembersAgentWorkspaceTreeState.NoPermission(blocker)
    }
