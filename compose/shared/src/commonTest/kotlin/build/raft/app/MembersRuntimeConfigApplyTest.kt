package build.raft.app

import build.raft.app.data.AgentConfigApply
import build.raft.app.data.AgentResetMode
import build.raft.app.data.parseSharedAgentConfigApply
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * botiverse/slock #13372 / #13373 (reconciler step 11c): a running agent's runtime config change
 * saves without a restart and applies when the agent is next idle. The PATCH answer's
 * `configApply` decides the saved notice (`announceRuntimeConfigApply`), Restart now restarts
 * (or resets the session of) the agent, and the Model row says what still runs until then.
 */
class MembersRuntimeConfigApplyTest {
    @Test
    fun configApplyIsReadFromThePatchAnswer() {
        // slock packages/server/src/routes/agents.ts `res.json({ ...agent, configApply })`.
        assertEquals(AgentConfigApply.WhenIdle, parseSharedAgentConfigApply("""{"id":"a1","name":"witty","configApply":"when_idle"}"""))
        assertEquals(AgentConfigApply.Restarted, parseSharedAgentConfigApply("""{"id":"a1","configApply":"restarted"}"""))
        assertEquals(AgentConfigApply.Saved, parseSharedAgentConfigApply("""{"id":"a1","configApply":"saved"}"""))
        // An older server sends none; an unknown value is not guessed.
        assertNull(parseSharedAgentConfigApply("""{"id":"a1"}"""))
        assertNull(parseSharedAgentConfigApply("""{"id":"a1","configApply":"later"}"""))
        assertNull(parseSharedAgentConfigApply("""{"id":"a1","config_apply":"when_idle"}"""))
    }

    @Test
    fun theProfileAsStoredHasNoConfigApply() {
        val saved = MemberProfile(MemberId("a1"), "witty", "witty", MemberKind.Agent, configApply = AgentConfigApply.WhenIdle)
        assertNull(saved.withoutConfigApply().configApply)
        assertEquals(saved.copy(configApply = null), saved.withoutConfigApply())
    }

    @Test
    fun whenIdleSaysItAppliesAtTheNextIdlePointWithRestartNow() {
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.WhenIdle, AgentResetMode.Restart),
            notice(AgentConfigApply.WhenIdle, agentActive = true, newSession = false)
        )
        // A new-session change: its own words, and Restart now resets the session.
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.NewSessionWhenIdle, AgentResetMode.Session),
            notice(AgentConfigApply.WhenIdle, agentActive = true, newSession = true)
        )
    }

    @Test
    fun anOlderDaemonsSavedOnARunningAgentAppliesAtTheNextStart() {
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.NextStart, AgentResetMode.Restart),
            notice(AgentConfigApply.Saved, agentActive = true, newSession = false)
        )
        assertEquals(AgentResetMode.Session, notice(AgentConfigApply.Saved, agentActive = true, newSession = true)?.restartNow)
    }

    @Test
    fun aStoppedAgentARestartAndAnOldServerShowNoNotice() {
        assertNull(notice(AgentConfigApply.Saved, agentActive = false, newSession = false))
        assertNull(notice(AgentConfigApply.Restarted, agentActive = true, newSession = false))
        assertNull(notice(null, agentActive = true, newSession = false))
    }

    @Test
    fun restartNowIsOfferedOnlyToWhoMayRestartTheAgent() {
        assertEquals(
            MembersRuntimeConfigApplyNotice(MembersRuntimeConfigApplyKind.WhenIdle, restartNow = null),
            notice(AgentConfigApply.WhenIdle, agentActive = true, newSession = false, canRestart = false)
        )
    }

    @Test
    fun theModelRowSaysWhatARunningAgentStillRuns() {
        assertEquals(
            MembersRuntimeRunningHint.Model(running = "label:grok-3", saved = "label:grok-4"),
            hint(runningRuntime = "grok", runningModel = "grok-3")
        )
        assertEquals(
            MembersRuntimeRunningHint.Runtime(running = "name:claude", saved = "name:grok"),
            hint(runningRuntime = "claude", runningModel = "sonnet")
        )
        // The running runtime unknown: the model still decides, as on the web.
        assertEquals(MembersRuntimeRunningHint.Model("label:grok-3", "label:grok-4"), hint(runningRuntime = null, runningModel = "grok-3"))
    }

    @Test
    fun theModelRowSaysNothingWhenEqualUnknownOrStopped() {
        assertNull(hint(runningRuntime = "grok", runningModel = "grok-4"))
        assertNull(hint(runningRuntime = null, runningModel = null))
        assertNull(hint(runningRuntime = "grok", runningModel = null))
        assertNull(hint(runningRuntime = "grok", runningModel = "grok-3", agentActive = false))
        assertNull(hint(runningRuntime = "claude", runningModel = "sonnet", agentActive = false))
    }

    private fun notice(configApply: AgentConfigApply?, agentActive: Boolean, newSession: Boolean, canRestart: Boolean = true) =
        membersRuntimeConfigApplyNotice(configApply, agentActive, newSession, canRestart)

    private fun hint(runningRuntime: String?, runningModel: String?, agentActive: Boolean = true) =
        membersRuntimeRunningHint(
            agentActive = agentActive,
            savedRuntime = "grok",
            savedModel = "grok-4",
            runningRuntime = runningRuntime,
            runningModel = runningModel,
            runtimeName = { "name:$it" },
            modelLabel = { "label:$it" }
        )
}
