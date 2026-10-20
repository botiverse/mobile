package build.raft.app

import build.raft.app.data.NeutralAgentStatusLabel
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftHomeDmAgentLiveActivityTest {
    private fun state(
        status: AgentStatus?,
        statusLabel: String? = null,
        external: Boolean = false,
        presence: Presence = status?.presence ?: Presence.Offline,
        detailKind: String? = null,
        toolName: String? = null,
        runtime: String? = null
    ): RaftAgentStatusIndicatorState =
        SharedServerMemberStatus(
            status = status,
            presence = presence,
            statusLabel = statusLabel,
            detailKind = detailKind,
            activityToolName = toolName,
            runtime = runtime,
            external = external
        ).toRaftAgentStatusIndicatorState()

    private fun raw(text: String) =
        RaftHomeDmAgentLiveActivity.Detail(RaftAgentActivityDetailText.Raw(text))

    @Test
    fun unresolvedStatusShowsNothing() {
        assertNull(raftHomeDmAgentLiveActivity(null))
        assertNull(raftHomeDmAgentMeta(null))
    }

    @Test
    fun workingWithDetailShowsTheDetailVerbatim() {
        assertEquals(raw("Tool finished"), raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Tool finished")))
        assertEquals(raw("Running tests"), raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "  Running tests  ")))
    }

    @Test
    fun workingWithoutDetailShowsGenericWorking() {
        listOf(null, "", "   ", "Working", "working", "Working...", "Working…", NeutralAgentStatusLabel).forEach { label ->
            assertEquals(
                RaftHomeDmAgentLiveActivity.Working,
                raftHomeDmAgentLiveActivity(state(AgentStatus.Working, label)),
                "label=$label"
            )
        }
    }

    @Test
    fun detailKindStartingAndCompactingAreLocalizedKinds() {
        listOf("starting", "runtime_starting").forEach { kind ->
            assertEquals(
                RaftHomeDmAgentLiveActivity.Detail(RaftAgentActivityDetailText.Starting),
                raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Starting…", detailKind = kind)),
                "kind=$kind"
            )
        }
        assertEquals(
            RaftHomeDmAgentLiveActivity.Detail(RaftAgentActivityDetailText.CompactingContext),
            raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Compacting context...", detailKind = "compacting_context"))
        )
        // An unknown kind keeps the server's detail.
        assertEquals(
            raw("Tool finished"),
            raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Tool finished", detailKind = "tool_end"))
        )
    }

    @Test
    fun clientBuiltToolLabelIsLocalizedByToolName() {
        assertEquals(
            RaftHomeDmAgentLiveActivity.Detail(RaftAgentActivityDetailText.Tool("run_command")),
            raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Running command...", toolName = "run_command"))
        )
    }

    @Test
    fun thinkingAlwaysShowsThinkingAndNeverTheThoughtText() {
        assertEquals(
            RaftHomeDmAgentLiveActivity.Thinking,
            raftHomeDmAgentLiveActivity(state(AgentStatus.Thinking, "Let me check the failing test first"))
        )
        assertEquals(RaftHomeDmAgentLiveActivity.Thinking, raftHomeDmAgentLiveActivity(state(AgentStatus.Thinking)))
    }

    @Test
    fun idleOnlineOfflineAndErrorShowNoLiveActivity() {
        listOf(
            AgentStatus.Online,
            AgentStatus.Idle,
            AgentStatus.Offline,
            AgentStatus.Disconnected,
            AgentStatus.Error
        ).forEach { status ->
            assertNull(raftHomeDmAgentLiveActivity(state(status, "Tool finished")), "status=$status")
        }
        assertNull(raftHomeDmAgentLiveActivity(state(status = null, presence = Presence.Active)))
    }

    @Test
    fun externalAgentsNeverShowLiveActivity() {
        assertNull(raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Tool finished", external = true)))
        assertNull(raftHomeDmAgentLiveActivity(state(AgentStatus.Thinking, external = true)))
        assertNull(state(AgentStatus.Working, "Starting…", external = true, detailKind = "starting").workingActivityDetailText())
    }

    @Test
    fun metaIsLiveActivityWhileWorkingElseRuntimeName() {
        assertEquals(
            RaftHomeDmAgentMeta.Live(raw("Tool finished")),
            raftHomeDmAgentMeta(state(AgentStatus.Working, "Tool finished", runtime = "claude"))
        )
        assertEquals(
            RaftHomeDmAgentMeta.Runtime("Claude Code"),
            raftHomeDmAgentMeta(state(AgentStatus.Online, runtime = "claude"))
        )
        // External agents keep their runtime label (web SidebarAgentMeta).
        assertEquals(
            RaftHomeDmAgentMeta.Runtime("External agent"),
            raftHomeDmAgentMeta(state(AgentStatus.Working, "Tool finished", external = true, runtime = "external"))
        )
        assertNull(raftHomeDmAgentMeta(state(AgentStatus.Online, runtime = "  ")))
        assertNull(raftHomeDmAgentMeta(state(AgentStatus.Online)))
    }

    @Test
    fun unreadBadgeIsShownAloneHidingDraftAndAgentMeta() {
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.UnreadBadge),
            raftHomeDmTrailingSlots(badge = "3", hasDraft = true, isAgent = true)
        )
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.UnreadBadge),
            raftHomeDmTrailingSlots(badge = "99+", hasDraft = false, isAgent = false)
        )
    }

    @Test
    fun withoutUnreadTheDraftMarkComesBeforeTheAgentMeta() {
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.Draft, RaftHomeDmTrailingSlot.AgentMeta),
            raftHomeDmTrailingSlots(badge = null, hasDraft = true, isAgent = true)
        )
        assertEquals(listOf(RaftHomeDmTrailingSlot.AgentMeta), raftHomeDmTrailingSlots(badge = null, hasDraft = false, isAgent = true))
        assertEquals(listOf(RaftHomeDmTrailingSlot.Draft), raftHomeDmTrailingSlots(badge = null, hasDraft = true, isAgent = false))
        assertEquals(emptyList(), raftHomeDmTrailingSlots(badge = null, hasDraft = false, isAgent = false))
    }
}
