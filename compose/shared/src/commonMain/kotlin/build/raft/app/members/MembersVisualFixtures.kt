package build.raft.app.members

import androidx.compose.runtime.setValue
import build.raft.app.data.AgentMigrationDataSource
import build.raft.app.data.AgentMigrationStatusSnapshot
import build.raft.app.data.AgentMigrationStartResult
import build.raft.app.data.AgentMigrationTarget
import build.raft.app.data.RaftBillingPlanSnapshot
import build.raft.app.data.AgentMigrationStore
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.AgentIssueReportResult
import build.raft.app.data.AgentIssueTranscriptRequestState
import build.raft.app.data.AgentAppLoginSnapshot
import build.raft.app.data.AgentCreatedSummary
import build.raft.app.data.AgentCreatorSummary
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentScopeSet
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentChannelSummary
import build.raft.app.data.AgentDmSummary
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.AgentReminderSummary
import build.raft.app.data.AgentSkillGroup
import build.raft.app.data.AgentSkillPathGroup
import build.raft.app.data.AgentSkillSummary
import build.raft.app.data.AgentSkillsSnapshot
import build.raft.app.data.AgentWorkspaceChildrenSnapshot
import build.raft.app.data.AgentAppEventPage
import build.raft.app.data.AgentWorkspaceTreeSnapshot
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsServerActionResult
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.optString
import build.raft.app.thread.toRaftTimeContext
import build.raft.app.members.toRaftTimeContext
import build.raft.app.model.RaftWorkspaceFileKind
import build.raft.app.model.RaftWorkspaceNode
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.RaftTimeFormat24Hour
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import build.raft.app.MemberDetailAccessMode
import build.raft.app.RAFT_COMPUTER_DEFAULT_SERVER_URL
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.flow.MutableStateFlow
import build.raft.app.data.map

/**
 * The four machines from packages/visual-testing/shared/fixtureData.json, as the computers
 * store would hold them, so the visual routes resolve the status row from a loaded store
 * rather than from an empty one (which would render every fixture agent as "no computer
 * assigned" -- the exact defect #2355 removed from the avatar grid).
 *
 * Why literals and not an injected list: the machine-status capture cases run on the
 * production `compose_member_detail` page (MEMBER_DETAIL_PAGE, this file), whose `visual-`
 * seeds are built here in commonMain. That route has no gated wrapper that could read
 * VisualFixtureData and pass a `List<ComputerSummary>` in. (The gated
 * `compose_members_agent_detail_visual` page *could* inject -- it just doesn't carry these
 * cells.) The drift gate (visual-testing.yml "Check fixture data drift") covers
 * VisualFixtureData.kt, NOT these lines; a separate lint (XiShi, task #259) asserts each row
 * below equals VisualFixtureData.Machines.* field for field, with `computer-missing`
 * listed as the deliberate absent id. Keep the two in lockstep.
 */
private val membersVisualFixtureMachines: List<ComputerSummary> =
    listOf(
        membersVisualFixtureMachine("computer-mbp", "Jiachengs-MacBook-Pro", ComputerStatus.Online, isComputer = true, computerVersion = "0.0.48", daemonVersion = "0.65.0"),
        membersVisualFixtureMachine("computer-studio", "Studio Test Rig", ComputerStatus.Offline, isComputer = true, computerVersion = "0.0.50", daemonVersion = "0.65.0"),
        membersVisualFixtureMachine("computer-long-name", "ci-visual-capture-runner-us-west-2b_node17_macbookpro_m4max_buildhost_2026", ComputerStatus.Online, isComputer = true, computerVersion = "0.0.48", daemonVersion = "0.65.0"),
        membersVisualFixtureMachine("computer-daemon-only", "build-box-bare-daemon", ComputerStatus.Online, isComputer = false, computerVersion = null, daemonVersion = "0.65.0")
    )

private fun membersVisualFixtureMachine(
    id: String,
    name: String,
    status: ComputerStatus,
    isComputer: Boolean,
    computerVersion: String?,
    daemonVersion: String?
): ComputerSummary =
    ComputerSummary(
        id = id,
        name = name,
        status = status,
        statusVersion = 0L,
        apiKeyPrefix = null,
        runtimes = emptyList(),
        daemonVersion = daemonVersion,
        computerVersion = computerVersion,
        isComputer = isComputer,
        hostname = null,
        os = null,
        description = null,
        lastHeartbeat = null,
        createdAt = null
    )

/** Fixture routes: the store is "loaded" with the fixture machines; `computer-missing` resolves to NoComputer by design. */
private val membersVisualFixtureMachineStatusRow: (String?) -> MembersMachineStatusRow = { machineId ->
    membersMachineStatusRow(
        machineId = machineId,
        machines = membersVisualFixtureMachines,
        loadStatus = SharedComputersLoadStatus.Loaded,
        hasSnapshot = true
    )
}

/**
 * Fixture computers-store + server slug (`packages/visual-testing/shared/fixtureData.json`,
 * `server.slug = "visual"`), so `screens.members.agent-detail.profile.computer-offline` (vcase
 * #280) renders [MembersAgentComputerOfflineCard] with the exact same restart command as the web.
 */
private val membersVisualFixtureOfflineComputerInputs =
    MembersAgentOfflineComputerInputs(
        machines = membersVisualFixtureMachines,
        commandContext =
            SettingsComputerCommandContext(
                serverSlug = "visual",
                deploymentEnv = "production",
                serverUrl = RAFT_COMPUTER_DEFAULT_SERVER_URL,
            ),
    )

private val membersAgentDetailVisualMember = MemberProfile(
    id = MemberId("agent-product-ux"),
    displayName = "Product UX Designer",
    handle = "@Product-UX-Designer",
    kind = MemberKind.Agent,
    description = "Turns product feedback, screenshots, and release notes into actionable UX specs.",
    roleDescription = "Product / UX agent",
    avatar = AvatarReference.Pixel("robot"),
    avatarLabel = "P",
    presence = Presence.Active,
    agentStatus = AgentStatus.Working,
    // fixtureData.json agents.productUx.serverRole: the Info Role row reads "Member" on the web.
    serverRole = "member",
    permissionBadges = listOf("computer:MacBook Pro", "runtime:Codex")
)

// fixtureData.json humans.owner: the web captures this profile as its own viewer (the "(you)"
// badge, no role edit), the server's owner, with no agents created.
private val membersHumanDetailVisualMember = MemberProfile(
    id = MemberId("visual-user"),
    displayName = "artin",
    handle = "@artin",
    kind = MemberKind.Human,
    description = "Owner",
    roleDescription = "Owner",
    serverRole = "owner",
    serverId = ServerId("visual-server"),
    email = "artin@slock.ai",
    avatarLabel = "AR",
    presence = Presence.Active,
    joinedAt = "2026-06-18T00:00:00.000Z",
    membershipStatus = "active"
)

private val membersAgentDetailVisualScopeSet = AgentScopeSet(
    agentId = membersAgentDetailVisualMember.id,
    granted = listOf(
        "inbox:receive",
        "server:read",
        "channel:read",
        "message:read",
        "message:send",
        "task:read",
        "task:write",
        "knowledge:read"
    ),
    mode = "custom",
    revision = 7L,
    updatedAt = "2026-06-19T12:28:00.000Z"
)

/**
 * task #535 / #537 — Computer-row oracle variants of the agent-detail visual seed.
 * Same page, same data except the identity and the Computer row inputs, so a
 * capture difference against the default seed is attributable to that row.
 * Values mirror packages/visual-testing/shared/fixtureData.json (agents
 * computerOffline / computerMissing / noComputer / longMachine, machines
 * studio / longName); the slock-side test pins them there.
 */
internal data class MembersAgentDetailVisualVariant(
    val id: String,
    val name: String,
    val displayName: String,
    val avatarLabel: String,
    val description: String,
    val machineId: String?,
    val machineName: String?,
    val daemonVersion: String?,
    /** Server role as the wire sends it. Every fixture agent is a server member;
        one is admin so the role chip cell can fail (task #261). Without this the
        default seed leaves it null and membersAgentDisplayServerRole draws Member.
        Null = no membership row (the wire omits serverRole; role chip hidden). */
    val serverRole: String?
)

private val membersAgentDetailVisualVariants = listOf(
    // store knows the machine and reports it offline: web = gray dot + Offline
    MembersAgentDetailVisualVariant(
        id = "agent-computer-offline",
        name = "Computer-Offline-Probe",
        displayName = "Computer Offline Probe",
        avatarLabel = "C",
        description = "Bound to a computer the store knows and reports offline: web shows a gray dot and Offline.",
        machineId = "computer-studio",
        machineName = "Studio Test Rig",
        daemonVersion = "0.65.0",
        serverRole = "member"
    ),
    // machineId points at a machine the store never loaded: web = "No computer assigned", no dot
    MembersAgentDetailVisualVariant(
        id = "agent-computer-missing",
        name = "Computer-Missing-Probe",
        displayName = "Computer Missing Probe",
        avatarLabel = "C",
        description = "machineId points at a computer the store has never seen: web shows No computer assigned, no dot.",
        machineId = "computer-missing",
        machineName = null,
        daemonVersion = null,
        serverRole = "member"
    ),
    // no machineId at all: web = "No computer assigned", no dot
    MembersAgentDetailVisualVariant(
        id = "agent-no-computer",
        name = "No-Computer-Probe",
        displayName = "No Computer Probe",
        avatarLabel = "N",
        description = "No machineId at all: web shows No computer assigned, no dot.",
        machineId = null,
        machineName = null,
        daemonVersion = null,
        serverRole = "member"
    ),
    // a machine name that cannot fit one 390dp line (44-char run with no break opportunity)
    MembersAgentDetailVisualVariant(
        id = "agent-long-machine",
        name = "Long-Machine-Name-Probe",
        displayName = "Long Machine Name Probe",
        avatarLabel = "L",
        description = "Bound to a computer whose name cannot fit one 390dp line, so wrap vs truncate is observable.",
        machineId = "computer-long-name",
        machineName = "ci-visual-capture-runner-us-west-2b_node17_macbookpro_m4max_buildhost_2026",
        daemonVersion = "0.65.0",
        serverRole = "admin"
    ),
    // a bare daemon (isComputer=false) with no computer version: web = green dot + Online + "daemon v0.65.0"
    MembersAgentDetailVisualVariant(
        id = "agent-daemon-only",
        name = "Daemon-Only-Probe",
        displayName = "Daemon Only Probe",
        avatarLabel = "DO",
        description = "Bound to a bare daemon (not a managed Computer): web shows Connected · daemon vX, never a computer version.",
        machineId = "computer-daemon-only",
        machineName = "build-box-bare-daemon",
        daemonVersion = "0.65.0",
        serverRole = "member"
    )
)

private fun MembersAgentDetailVisualVariant.toMember(): MemberProfile =
    membersAgentDetailVisualMember.copy(
        id = MemberId(id),
        displayName = displayName,
        handle = "@$name",
        description = description,
        avatarLabel = avatarLabel,
        serverRole = serverRole,
        permissionBadges = listOfNotNull(machineName?.let { "computer:$it" }, "runtime:Codex")
    )

private fun MembersAgentDetailVisualVariant.toSeed(): MemberDetailVisualSeed {
    val member = toMember()
    // The default seed always carries a summary; a null here would mean the
    // default case itself lost its Computer row, which is the wrong place to hide.
    val summary = checkNotNull(membersAgentDetailVisualSnapshot.summary).copy(
        id = id,
        name = name,
        displayName = displayName,
        machineId = machineId,
        machineName = machineName,
        daemonVersion = daemonVersion,
        serverRole = serverRole,
        activityDetail = "Holding a fixed Computer-row state for the visual oracle"
    )
    return MemberDetailVisualSeed(
        member = member,
        agentDetailState = MembersAgentDetailLoadState.Content(
            membersAgentDetailVisualSnapshot.copy(agentId = member.id, summary = summary)
        ),
        agentScopeSet = membersAgentDetailVisualScopeSet.copy(agentId = member.id)
    )
}

private val membersAgentDetailVisualSnapshot = AgentDetailSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    summary = AgentDetailSummary(
        id = membersAgentDetailVisualMember.id.value,
        name = "Product-UX-Designer",
        displayName = membersAgentDetailVisualMember.displayName,
        createdAt = "2026-06-18T00:00:00.000Z",
        status = "working",
        runtime = "codex",
        model = "gpt-5-codex",
        reasoning = "medium",
        executionMode = "byoc",
        machineId = "computer-mbp",
        machineName = "Jiachengs-MacBook-Pro",
        daemonVersion = "0.65.0",
        creatorId = "artin",
        creatorType = "user",
        creator = AgentCreatorSummary(
            type = "human",
            id = "artin",
            name = "artin",
            displayName = "artin",
            avatarUrl = "pixel:user"
        ),
        envVarNames = listOf("RAFT_PROFILE", "SLOCK_VISUAL_PROVIDER"),
        // The web fixture's values (VisualTestingCases visualAgent.envVars); the chips mask them,
        // one dot per character up to 8.
        envVars = mapOf("RAFT_PROFILE" to "product-ux", "SLOCK_VISUAL_PROVIDER" to "react"),
        serverRole = "member",
        createdAgents = listOf(
            AgentCreatedSummary(
                id = "agent-visual-qa",
                name = "visual-qa",
                displayName = "Visual QA",
                runtime = "codex",
                status = "idle",
                isExternal = false
            )
        ),
        activity = "working",
        activityDetail = "Capturing deterministic Activity tab state"
    ),
    directMessages = listOf(
        AgentDmSummary(
            id = "dm-agent-product-ux-artin",
            peerId = "artin",
            peerName = "artin",
            lastMessageAt = "2026-06-19T12:24:00Z",
            lastMessagePreview = "201继续推进"
        )
    ),
    channels = listOf(
        AgentChannelSummary(
            id = "channel-product-design",
            name = "product-design",
            description = "Product design reviews and implementation handoffs."
        ),
        AgentChannelSummary(
            id = "channel-visual-testing",
            name = "visual-testing",
            description = "Visual validation results and capture evidence.",
            activityMuted = true,
            muteFromSeq = 1842L
        )
    ),
    reminders = listOf(
        AgentReminderSummary(
            id = "reminder-product-ux-memory",
            title = "Roll 1-6; if 3, tidy Product-UX memory/files",
            fireAt = "2026-06-19T18:30:00Z"
        )
    ),
    activity = listOf(
        AgentActivityEntry(
            id = "activity-agent-detail-working",
            type = "status",
            title = "Working",
            body = "Capturing deterministic Activity tab state",
            timestamp = "2026-06-19T12:28:00Z",
            statusActivity = "working",
            statusDetail = "Capturing deterministic Activity tab state"
        ),
        AgentActivityEntry(
            id = "activity-agent-detail-thinking",
            type = "thinking",
            title = "Thinking",
            body = "Comparing React and Android Members Activity screenshots before publishing.",
            timestamp = "2026-06-19T12:29:00Z"
        ),
        AgentActivityEntry(
            id = "activity-agent-detail-command",
            type = "tool_start",
            title = "Running command",
            body = "pnpm --filter @botiverse/raft-visual-testing exec slock-visual diff --case screens.members.agent-detail.activity",
            timestamp = "2026-06-19T12:30:00Z",
            toolName = "shell",
            toolInput = "pnpm --filter @botiverse/raft-visual-testing exec slock-visual diff --case screens.members.agent-detail.activity"
        ),
        AgentActivityEntry(
            id = "activity-agent-detail-report",
            type = "slock_action",
            title = "Posted status update",
            body = "Reported capture progress in #product:d4870bc3 and kept #225 in review.",
            timestamp = "2026-06-19T12:31:00Z",
            refTextEnabled = true
        )
    ),
    // The web case's `GET /agents/:id/skills` (react-provider.spec.ts): one global and one
    // workspace skill, both from notes/product, neither user-invocable.
    skills = AgentSkillsSnapshot(
        agentId = membersAgentDetailVisualMember.id,
        groups = listOf(
            AgentSkillGroup(
                title = "Global",
                count = 1,
                paths = listOf(
                    AgentSkillPathGroup(
                        path = "notes/product",
                        count = 1,
                        skills = listOf(AgentSkillSummary("visual-testing", "Cross-platform fixture ownership"))
                    )
                )
            ),
            AgentSkillGroup(
                title = "Workspace",
                count = 1,
                paths = listOf(
                    AgentSkillPathGroup(
                        path = "notes/product",
                        count = 1,
                        skills = listOf(AgentSkillSummary("feature-map", "React/KMP parity maps"))
                    )
                )
            )
        )
    ),
    // The web case (react-provider.spec.ts) answers `GET /integrations/agents/:id` with `[]`, so
    // the Apps tab shows its empty state; the fixture agent has no app logins either.
    appLogins = AgentAppLoginSnapshot(
        agentId = membersAgentDetailVisualMember.id,
        items = emptyList()
    )
)

private val membersAgentDetailVisualWorkspaceTreeSnapshot = AgentWorkspaceTreeSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    rootPath = "~/.slock/agents/agent-product-ux",
    root = RaftWorkspaceNode(
        id = "~/.slock/agents/agent-product-ux",
        name = "agent-product-ux",
        path = "~/.slock/agents/agent-product-ux",
        kind = RaftWorkspaceFileKind.Folder,
        children = listOf(
            RaftWorkspaceNode(
                id = "MEMORY.md",
                name = "MEMORY.md",
                path = "MEMORY.md",
                kind = RaftWorkspaceFileKind.Markdown,
                meta = "86 B"
            ),
            RaftWorkspaceNode(
                id = "notes",
                name = "notes",
                path = "notes",
                kind = RaftWorkspaceFileKind.Folder,
                meta = "folder"
            )
        )
    )
)

private val membersAgentDetailVisualWorkspaceNotesChildren = AgentWorkspaceChildrenSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    dirPath = "notes",
    children = listOf(
        RaftWorkspaceNode(
            id = "notes/visual-parity.md",
            name = "visual-parity.md",
            path = "notes/visual-parity.md",
            kind = RaftWorkspaceFileKind.Markdown,
            meta = "1.2 KB"
        )
    )
)

/** Captures show the profile of an ordinary (privacy mode off) server, Report issue included. */
internal val MembersVisualPrivacyModeOff = MutableStateFlow(RaftPrivacyMode.Off)

internal enum class MembersAgentDetailVisualMode {
    Profile,
    LifecycleActions,
    AvatarManagement,
    ReportIssue,
    ReportIssueSuccess
}

private fun String.toMembersAgentDetailVisualMode(): MembersAgentDetailVisualMode =
    when (trim().lowercase()) {
        "lifecycle_actions" -> MembersAgentDetailVisualMode.LifecycleActions
        "avatar_management" -> MembersAgentDetailVisualMode.AvatarManagement
        "report_issue" -> MembersAgentDetailVisualMode.ReportIssue
        "report_issue_success" -> MembersAgentDetailVisualMode.ReportIssueSuccess
        else -> MembersAgentDetailVisualMode.Profile
    }

internal data class MemberDetailVisualSeed(
    val member: MemberProfile,
    val agentDetailState: MembersAgentDetailLoadState,
    val agentScopeSet: AgentScopeSet? = null,
    /** Activity tab Overview fixture (flag on); null keeps the raw log only. */
    val activityOverview: build.raft.app.data.AgentActivityOverviewState? = null,
    /** The signed-in viewer; the human profile is the web fixture's own (`visual-user`). */
    val viewerId: String = "visual-owner"
)

/**
 * `screens.members.agent-detail.activity.overview`: the default agent with the Activity
 * Overview on. Turns mirror slock packages/web/tests/agentActivityOverview.behavior.test.tsx
 * (reply, failing checks, a hidden private-channel trigger with an error ending, a DM with a held
 * send), moved onto the visual fixture day; replies are preloaded so the capture needs no network.
 */
private const val MembersAgentActivityOverviewVisualId = "agent-activity-overview"

private val membersAgentActivityOverviewVisualState: build.raft.app.data.AgentActivityOverviewState =
    build.raft.app.data.AgentActivityOverviewState(
        agentId = MemberId(MembersAgentActivityOverviewVisualId),
        status = build.raft.app.data.AgentActivityOverviewStatus.Loaded,
        next = "1781869209000:00000000-0000-4000-8000-000000000003",
        turns = listOf(
            build.raft.app.data.AgentActivityTurn(
                id = "00000000-0000-4000-8000-000000000001",
                startedAtEpochMillis = 1781870400000L,
                endedAtEpochMillis = 1781870501000L,
                outcome = build.raft.app.data.AgentActivityTurnOutcome.Completed,
                trigger = build.raft.app.data.AgentActivityTurnTrigger.Visible(
                    target = "#proj-runtime:9b526607",
                    channelId = "c1",
                    sender = "Tenny",
                    messageId = "m1"
                ),
                actions = listOf(
                    build.raft.app.data.AgentActivityActionCount(build.raft.app.data.AgentActivityActionKind.Search, 3),
                    build.raft.app.data.AgentActivityActionCount(build.raft.app.data.AgentActivityActionKind.RunChecks, 1)
                ),
                toolCalls = 4,
                problems = listOf(
                    build.raft.app.data.AgentActivityTurnProblem(
                        kind = build.raft.app.data.AgentActivityActionKind.RunChecks,
                        reason = build.raft.app.data.AgentActivityProblemReason.ExitNonzero,
                        exitCode = 1,
                        atEpochMillis = 1781870420000L
                    )
                ),
                problemCount = 1,
                messagesSent = 2,
                lastReplyMessageId = MessageId("reply-1")
            ),
            build.raft.app.data.AgentActivityTurn(
                id = "00000000-0000-4000-8000-000000000002",
                startedAtEpochMillis = 1781869800000L,
                endedAtEpochMillis = 1781869805000L,
                outcome = build.raft.app.data.AgentActivityTurnOutcome.Failed,
                trigger = build.raft.app.data.AgentActivityTurnTrigger.Hidden(
                    build.raft.app.data.AgentActivityTurnTrigger.HiddenPlace.PrivateChannel
                ),
                actions = emptyList(),
                toolCalls = 0,
                problems = listOf(
                    build.raft.app.data.AgentActivityTurnProblem(
                        kind = build.raft.app.data.AgentActivityActionKind.EditFile,
                        reason = build.raft.app.data.AgentActivityProblemReason.CallFailed,
                        exitCode = null,
                        atEpochMillis = 1781869804000L
                    )
                ),
                problemCount = 1,
                messagesSent = 0,
                lastReplyMessageId = null
            ),
            build.raft.app.data.AgentActivityTurn(
                id = "00000000-0000-4000-8000-000000000003",
                startedAtEpochMillis = 1781869200000L,
                endedAtEpochMillis = 1781869209000L,
                outcome = build.raft.app.data.AgentActivityTurnOutcome.Completed,
                trigger = build.raft.app.data.AgentActivityTurnTrigger.Visible(
                    target = "dm:@richard",
                    channelId = "d1",
                    sender = "richard",
                    messageId = null
                ),
                actions = emptyList(),
                toolCalls = 0,
                problems = listOf(
                    build.raft.app.data.AgentActivityTurnProblem(
                        kind = build.raft.app.data.AgentActivityActionKind.SendMessage,
                        reason = build.raft.app.data.AgentActivityProblemReason.SendHeld,
                        exitCode = null,
                        atEpochMillis = 1781869208000L
                    )
                ),
                problemCount = 1,
                messagesSent = 0,
                lastReplyMessageId = null
            )
        ),
        replies = mapOf(
            MessageId("reply-1") to build.raft.app.data.AgentActivityReplyState.Ready(
                build.raft.app.data.AgentActivityTurnReply.Loaded(
                    content = "Fixed it: tests pass now.",
                    createdAtEpochMillis = 1781870490000L
                )
            )
        )
    )

/**
 * The web visual fixture mounts AgentMigrationSection for every managed agent bound to a computer:
 * no migration on record (`GET .../migration` answers none), no hosted target, a plan the gate lets
 * through. Nothing here ever starts, cancels or confirms a move.
 */
private object MembersVisualFixtureMigrationDataSource : AgentMigrationDataSource {
    private val refused = ScreenError(code = "visual_fixture", retryable = false)

    override suspend fun migrationStatus(
        agentId: MemberId,
        priority: DataRequestPriority
    ): DataResult<AgentMigrationStatusSnapshot> = DataResult.Content(AgentMigrationStatusSnapshot(migration = null))

    override suspend fun startMigration(
        agentId: MemberId,
        target: AgentMigrationTarget,
        preflightOverride: Boolean
    ): DataResult<AgentMigrationStartResult> =
        DataResult.Error(refused)

    override suspend fun cancelMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int): DataResult<Unit> =
        DataResult.Error(refused)

    override suspend fun dismissMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int): DataResult<Unit> =
        DataResult.Content(Unit)

    override suspend fun confirmHostedMigration(agentId: MemberId, migrationRef: String, expectedRevision: Int): DataResult<Unit> =
        DataResult.Error(refused)

    override suspend fun billingPlan(): DataResult<RaftBillingPlanSnapshot> =
        DataResult.Content(RaftBillingPlanSnapshot(plan = null))
}

@Composable
private fun rememberMembersVisualFixtureRemoteControls(): MembersAgentRemoteControls {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        MembersAgentRemoteControls(
            serverRole = null,
            turnStopStore = null,
            migration =
                MembersAgentMigrationBinding(
                    store = AgentMigrationStore(MembersVisualFixtureMigrationDataSource, scope),
                    hostedAvailable = false,
                    computers = emptyList(),
                    onOpenBilling = {},
                    onAgentPlacementChanged = {},
                    // The viewer is the server's owner, as on the web.
                    onOpenLog = {}
                )
        )
    }
}

/** Default agent-detail visual seed (fixture agent agent-product-ux). */
private fun membersAgentDetailDefaultVisualSeed(): MemberDetailVisualSeed =
    MemberDetailVisualSeed(
        member = membersAgentDetailVisualMember,
        agentDetailState = MembersAgentDetailLoadState.Content(membersAgentDetailVisualSnapshot),
        agentScopeSet = membersAgentDetailVisualScopeSet
    )

internal fun RaftMemberDetailPayload.toMemberDetailVisualSeedOrNull(): MemberDetailVisualSeed? {
    if (!instanceId.startsWith("visual-")) return null
    return when (identity?.memberId?.value) {
        membersAgentDetailVisualMember.id.value -> membersAgentDetailDefaultVisualSeed()
        MembersAgentActivityOverviewVisualId -> {
            val member = membersAgentDetailVisualMember.copy(id = MemberId(MembersAgentActivityOverviewVisualId))
            MemberDetailVisualSeed(
                member = member,
                agentDetailState = MembersAgentDetailLoadState.Content(
                    membersAgentDetailVisualSnapshot.copy(
                        agentId = member.id,
                        summary = membersAgentDetailVisualSnapshot.summary?.copy(id = member.id.value)
                    )
                ),
                agentScopeSet = membersAgentDetailVisualScopeSet.copy(agentId = member.id),
                activityOverview = membersAgentActivityOverviewVisualState
            )
        }
        membersHumanDetailVisualMember.id.value ->
            MemberDetailVisualSeed(
                member = membersHumanDetailVisualMember,
                agentDetailState = MembersAgentDetailLoadState.Idle,
                viewerId = membersHumanDetailVisualMember.id.value
            )
        else -> membersAgentDetailVisualVariants
            .firstOrNull { it.id == identity?.memberId?.value }
            ?.toSeed()
    }
}

/**
 * Entry for gated visual fixture pages (visualFixtures source set): renders the agent
 * detail for a typed [variant] (null = default agent-product-ux seed) without going
 * through the production `compose_member_detail` pageData parser.
 */
@Composable
internal fun MembersAgentDetailVisualVariantContent(
    payload: RaftMemberDetailPayload,
    variant: MembersAgentDetailVisualVariant?,
    pageVisibleTick: Int,
    machineStatusRow: ((String?) -> MembersMachineStatusRow)? = null,
    offlineComputerInputs: MembersAgentOfflineComputerInputs? = null
) {
    val seed = remember(variant) { variant?.toSeed() ?: membersAgentDetailDefaultVisualSeed() }
    MemberDetailVisualSeedContent(
        payload = payload,
        seed = seed,
        pageVisibleTick = pageVisibleTick,
        machineStatusRow = machineStatusRow ?: membersVisualFixtureMachineStatusRow,
        offlineComputerInputs = offlineComputerInputs ?: membersVisualFixtureOfflineComputerInputs
    )
}

@Composable
internal fun MemberDetailVisualSeedContent(
    payload: RaftMemberDetailPayload,
    seed: MemberDetailVisualSeed,
    pageVisibleTick: Int,
    // Gated visual fixture pages inject other computers-store states (e.g. still loading).
    machineStatusRow: (String?) -> MembersMachineStatusRow = membersVisualFixtureMachineStatusRow,
    offlineComputerInputs: MembersAgentOfflineComputerInputs = membersVisualFixtureOfflineComputerInputs
) {
    val initialAgentTab = remember(payload.initialAgentTab) {
        payload.initialAgentTab.toMembersAgentDetailTabOrNull()
    }
    val visualMode = remember(payload.visualMode) {
        payload.visualMode.orEmpty().toMembersAgentDetailVisualMode()
    }
    MembersDetailContent(
        member = seed.member,
        currentUserId = seed.viewerId,
        currentServerId = "visual-server",
        agentDetailState = seed.agentDetailState,
        activityOverview =
            remember(seed, initialAgentTab) {
                // Only the profile gets the empty overview: it would also open the Activity tab on its Overview.
                val onProfile = initialAgentTab == null || initialAgentTab == MembersAgentDetailTab.Profile
                (seed.activityOverview ?: membersVisualEmptyActivityOverview(seed.member).takeIf { onProfile })?.let {
                    MembersAgentActivityOverviewBinding(state = MutableStateFlow(it), onIntent = {})
                }
            },
        canViewPrivateAgentSurfaces = { member, _ -> member.kind == MemberKind.Agent },
        canManageAgent = { member, _ -> member.kind == MemberKind.Agent },
        // The web fixture's viewer may edit this agent (pencils on the name, description, role
        // and runtime config); edit authority is what turns those on here. Fixture identity only.
        agentProfileEditAuthority =
            MembersAgentProfileEditAuthority(
                memberId = seed.member.id,
                environmentId = "visual-fixture",
                accountId = "visual-fixture",
                serverId = ServerId("visual-fixture"),
                profileGeneration = 0L
            ),
        machineStatusRow = machineStatusRow,
        offlineComputerInputs = offlineComputerInputs,
        actions = MembersDetailActions(
            onBack = {},
            onOpenDirectMessage = {},
            onCopyMemberId = {},
            onOpenUrl = {},
            onUnavailableAction = {}
        ),
        humanRole = MembersHumanRoleInputs(
            humanActorRole = "owner",
            humanOwnerCount = 2,
            onUpdateHumanRole = { _, _ -> DataResult.Content(SettingsServerActionResult(success = true)) }
        ),
        agentCallbacks = MembersAgentDetailCallbacks(
            onUpdateAgentProfile = { DataResult.Content(seed.member) },
            onUploadAgentAvatar = null,
            onAgentLifecycleAction = { agentId, action, _ ->
                DataResult.Content(
                    AgentLifecycleActionResult(
                        agentId = agentId,
                        action = action.actionKey,
                        success = true
                    )
                )
            },
            agentWorkspaceTreeLoader = { DataResult.Content(membersAgentDetailVisualWorkspaceTreeSnapshot) },
            agentWorkspaceChildrenLoader = { _, _ ->
                DataResult.Content(membersAgentDetailVisualWorkspaceNotesChildren)
            },
            // The shared fixture's agent has no app events (web: "No app events yet").
            agentAppEventsLoader = { _, _, _ -> DataResult.Content(AgentAppEventPage(events = emptyList(), nextCursor = null)) },
            onCopyAgentDiagnostics = {},
            onReportAgentIssue = {
                DataResult.Content(
                    AgentIssueReportResult(
                        reportId = "7e91c120-f424-42d7-96e5-21eb816866d0",
                        artifactId = "95d629bd-3ed7-42ff-97eb-3ed35c6dc6ab",
                        serverId = "65598229-cd0a-4e92-96f3-c33e7e2e4cc1",
                        issueDescription = "The agent stopped responding after a long-running command.",
                        transcriptRequested = true,
                        transcriptRequestState = AgentIssueTranscriptRequestState.Requested,
                        ticketId = "3f6c2a10-8d4b-4e2f-9a1c-5b7d8e9f0a1b"
                    )
                )
            }
        ),
        timeContext = payload.toRaftTimeContext(),
        presentation = MembersAgentDetailPresentation(
            pageVisibleTick = pageVisibleTick,
            initialVisualMode = visualMode,
            initialAgentTab = initialAgentTab,
            agentPanelTabOrder = payload.agentPanelTabOrder,
            initialAgentScopeSet = seed.agentScopeSet,
            stableVisualCapture = true,
            privacyMode = MembersVisualPrivacyModeOff,
            visualLiveActivityDetail = (seed.agentDetailState as? MembersAgentDetailLoadState.Content)?.detail?.summary?.activityDetail
        ),
        remoteControls = rememberMembersVisualFixtureRemoteControls()
    )
}

@Composable
internal fun MembersAgentDetailVisualContent(
    // PROTOTYPE (native nav): a pushed page with a working Back.
    protoOnBack: (() -> Unit)? = null,
) {
    val pager = LocalActivity.current.getPager() as Pager
    val visualMode = pager.pageData.params.optString("visualMode").toMembersAgentDetailVisualMode()
    MembersDetailContent(
        member = membersAgentDetailVisualMember,
        agentDetailState = MembersAgentDetailLoadState.Content(membersAgentDetailVisualSnapshot),
        canViewPrivateAgentSurfaces = { _, _ -> true },
        canManageAgent = { _, _ -> true },
        // Without this the avatar editor never opens under capture: MembersAgentProfileTab
        // gates editing on `agentProfileEditAuthority != null` (canEdit), so the
        // avatar-management visual case was photographing the *collapsed* state while web
        // photographed the expanded grid. Its 23.83% mismatch was "expanded vs collapsed",
        // not a spacing difference (XiShi, task #264). The values are fixture identity;
        // nothing here is authoritative for any real server.
        agentProfileEditAuthority =
            MembersAgentProfileEditAuthority(
                memberId = membersAgentDetailVisualMember.id,
                environmentId = "visual-fixture",
                accountId = "visual-fixture",
                serverId = ServerId("visual-fixture"),
                profileGeneration = 0L
            ),
        machineStatusRow = membersVisualFixtureMachineStatusRow,
        offlineComputerInputs = membersVisualFixtureOfflineComputerInputs,
        actions = MembersDetailActions(
            onBack = protoOnBack ?: {},
            onOpenDirectMessage = {},
            onCopyMemberId = {},
            onOpenUrl = {},
            onUnavailableAction = {}
        ),
        agentCallbacks = MembersAgentDetailCallbacks(
            onUpdateAgentProfile = { DataResult.Content(membersAgentDetailVisualMember) },
            onAgentLifecycleAction = { _, action, _ ->
                DataResult.Content(
                    AgentLifecycleActionResult(
                        agentId = membersAgentDetailVisualMember.id,
                        action = action.actionKey,
                        success = true
                    )
                )
            },
            agentWorkspaceTreeLoader = null,
            agentWorkspaceChildrenLoader = null,
            onCopyAgentDiagnostics = {},
            onReportAgentIssue = null,
            onUploadAgentAvatar = null
        ),
        activityOverview =
            remember {
                membersVisualEmptyActivityOverview(membersAgentDetailVisualMember)?.let {
                    MembersAgentActivityOverviewBinding(state = MutableStateFlow(it), onIntent = {})
                }
            },
        timeContext = RaftTimeContext(
            localOffsetMinutes = 480,
            todayEpochDay = 20623,
            nowEpochMillis = 1781872200000,
            timeFormat = RaftTimeFormat24Hour
        ),
        presentation = MembersAgentDetailPresentation(
            initialVisualMode = visualMode,
            stableVisualCapture = true,
            privacyMode = MembersVisualPrivacyModeOff
        ),
        remoteControls = rememberMembersVisualFixtureRemoteControls()
    )
}

/** The web's profile always has its Recent activity card; its fixture has no turns. Null for a person. */
private fun membersVisualEmptyActivityOverview(member: MemberProfile): build.raft.app.data.AgentActivityOverviewState? =
    if (member.kind == MemberKind.Agent) {
        build.raft.app.data.AgentActivityOverviewState(
            agentId = member.id,
            status = build.raft.app.data.AgentActivityOverviewStatus.Loaded,
        )
    } else {
        null
    }

private fun readCurrentServerId(): String? = null

/**
 * Screenshot entry for the profile of an agent from another server of a joint channel
 * (web AgentDetailPanel `isRemoteJointAgent`): the route's remote-profile access mode over the
 * roster seed the joint channel carries. No private surface and no edit authority.
 */
@Composable
internal fun MembersRemoteAgentProfileVisualContent(
    member: MemberProfile,
    detail: AgentDetailSnapshot,
    accessMode: MemberDetailAccessMode = MemberDetailAccessMode.RemoteProfileOnly
) {
    MembersDetailContent(
        member = member,
        currentUserId = "visual-viewer",
        currentServerId = member.serverId?.value,
        agentDetailState = MembersAgentDetailLoadState.Content(detail),
        canViewPrivateAgentSurfaces = { _, _ -> false },
        canManageAgent = { _, _ -> false },
        machineStatusRow = membersVisualFixtureMachineStatusRow,
        offlineComputerInputs = membersVisualFixtureOfflineComputerInputs,
        actions = MembersDetailActions(
            onBack = {},
            onOpenDirectMessage = {},
            onCopyMemberId = {},
            onOpenUrl = {},
            onUnavailableAction = {}
        ),
        agentCallbacks = MembersAgentDetailCallbacks(
            onUpdateAgentProfile = null,
            onAgentLifecycleAction = null,
            agentWorkspaceTreeLoader = null,
            agentWorkspaceChildrenLoader = null,
            onCopyAgentDiagnostics = {},
            onReportAgentIssue = null,
            onUploadAgentAvatar = null
        ),
        timeContext = RaftTimeContext(
            localOffsetMinutes = 480,
            todayEpochDay = 20623,
            nowEpochMillis = 1781872200000,
            timeFormat = RaftTimeFormat24Hour
        ),
        presentation = MembersAgentDetailPresentation(stableVisualCapture = true, privacyMode = MembersVisualPrivacyModeOff),
        accessMode = accessMode
    )
}
