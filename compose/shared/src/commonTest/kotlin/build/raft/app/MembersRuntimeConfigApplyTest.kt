package build.raft.app

import build.raft.app.data.AgentConfigApply
import build.raft.app.data.RaftBulkModelTarget
import build.raft.app.data.RaftBulkPlanAgent
import build.raft.app.data.RaftBulkPlanRow
import build.raft.app.data.SettingsComputerAgentSnapshot
import build.raft.app.data.parseSharedAgentConfigApply
import build.raft.app.data.parseSharedAgentConfigApplyNewSession
import build.raft.app.data.planRaftBulkModelChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * botiverse/slock #15669 / #15714 (reconciler step 11c + `#proj-runtime:#80`): a running agent's
 * saved runtime config applies automatically with a plain confirmation toast (no manual Restart
 * now / Switch now prompt), and bulk Change model defaults to applying immediately without a
 * restart checkbox.
 */
class MembersRuntimeConfigApplyTest {
    @Test
    fun configApplyAndNewSessionAreReadFromThePatchAnswer() {
        // slock packages/server/src/routes/agents.ts `res.json({ ...agent, configApply, configApplyNewSession })`.
        assertEquals(AgentConfigApply.Applied, parseSharedAgentConfigApply("""{"id":"a1","configApply":"applied"}"""))
        assertEquals(AgentConfigApply.WhenIdle, parseSharedAgentConfigApply("""{"id":"a1","name":"witty","configApply":"when_idle"}"""))
        assertEquals(AgentConfigApply.Restarted, parseSharedAgentConfigApply("""{"id":"a1","configApply":"restarted"}"""))
        assertEquals(AgentConfigApply.Saved, parseSharedAgentConfigApply("""{"id":"a1","configApply":"saved"}"""))
        // An older server sends none; an unknown value is not guessed.
        assertNull(parseSharedAgentConfigApply("""{"id":"a1"}"""))
        assertNull(parseSharedAgentConfigApply("""{"id":"a1","configApply":"later"}"""))
        assertNull(parseSharedAgentConfigApply("""{"id":"a1","config_apply":"when_idle"}"""))
        // Server's configApplyNewSession flag.
        assertEquals(true, parseSharedAgentConfigApplyNewSession("""{"id":"a1","configApplyNewSession":true}"""))
        assertEquals(false, parseSharedAgentConfigApplyNewSession("""{"id":"a1","configApplyNewSession":false}"""))
        assertNull(parseSharedAgentConfigApplyNewSession("""{"id":"a1"}"""))
    }

    @Test
    fun appliedAndRestartedOnAnActiveAgentShowSingleLineAppliedConfirmation() {
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.Applied),
            notice(AgentConfigApply.Applied, agentActive = true, newSession = false)
        )
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.Applied),
            notice(AgentConfigApply.Restarted, agentActive = true, newSession = false)
        )
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.Applied),
            notice(AgentConfigApply.Applied, agentActive = true, newSession = true)
        )
        // Inactive agent or explicit saved: single-line saved confirmation (#16556).
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.Saved),
            notice(AgentConfigApply.Applied, agentActive = false, newSession = false)
        )
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.Saved),
            notice(AgentConfigApply.Restarted, agentActive = false, newSession = false)
        )
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.Saved),
            notice(AgentConfigApply.Saved, agentActive = true, newSession = false)
        )
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.Saved),
            notice(AgentConfigApply.Saved, agentActive = false, newSession = false)
        )
        // Older server without configApply: no toast.
        assertNull(notice(null, agentActive = true, newSession = false))
    }

    @Test
    fun whenIdleOnOlderDaemonShowsAutomaticCurrentMessageEndNotice() {
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.WhenIdle),
            notice(AgentConfigApply.WhenIdle, agentActive = true, newSession = false)
        )
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.NewSessionWhenIdle),
            notice(AgentConfigApply.WhenIdle, agentActive = true, newSession = true)
        )
    }

    @Test
    fun bulkModelChangeDefaultsToImmediateApplyWithoutExplicitRestart() {
        val runningAgent =
            SettingsComputerAgentSnapshot(
                id = "a1",
                name = "Otter",
                runtime = "claude",
                model = "claude-sonnet-4-5"
            )
        val plan =
            planRaftBulkModelChange(
                agents = listOf(RaftBulkPlanAgent(runningAgent, running = true)),
                target = RaftBulkModelTarget(runtime = "claude", model = "claude-opus-4-6", reasoningEffort = null)
            )
        assertEquals(1, plan.applyCount)
        assertEquals(1, plan.runningChangeCount)
        assertEquals(0, plan.restartCount)
        val row = plan.rows.single() as RaftBulkPlanRow.Change
        assertFalse(row.restarts)
        assertTrue(row.agentId == "a1")
    }

    private fun notice(configApply: AgentConfigApply?, agentActive: Boolean, newSession: Boolean) =
        membersRuntimeConfigApplyNotice(configApply, agentActive, newSession)
}
