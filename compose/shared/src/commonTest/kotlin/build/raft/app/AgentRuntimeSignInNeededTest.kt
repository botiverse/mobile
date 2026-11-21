package build.raft.app

import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthUnavailableReason
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.data.parseSharedAgentProfile
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Web `runtimeSignInNeededOf`, `signInRuntimeFor`, and `runtimeSignInOffered` (slock #15282). */
class AgentRuntimeSignInNeededTest {
    private fun needed(
        status: AgentStatus?,
        live: String? = null,
        kept: String? = null,
        blocked: Boolean = false,
        likelyOnline: Boolean = false,
        external: Boolean = false,
        paused: Boolean = false,
    ) = agentRuntimeSignInNeeded(status, live, kept, blocked, likelyOnline, external, paused)

    @Test
    fun aFailedStartOrTheKeptRecordSaysSoWhileOfflineOrInError() {
        assertEquals(true, needed(AgentStatus.Offline, live = "runtime_login_required"))
        assertEquals(true, needed(AgentStatus.Error, kept = "runtime_login_required"))
        assertEquals(false, needed(AgentStatus.Offline, live = "runtime_not_found"))
        assertEquals(false, needed(AgentStatus.Offline))
        // Running again: no longer.
        assertEquals(false, needed(AgentStatus.Online, kept = "runtime_login_required"))
        assertEquals(false, needed(null, kept = "runtime_login_required"))
    }

    @Test
    fun neverForAHeldPausedExternalOrGuessedOnlineAgent() {
        assertEquals(false, needed(AgentStatus.Offline, live = "runtime_login_required", blocked = true))
        assertEquals(false, needed(AgentStatus.Offline, live = "runtime_login_required", paused = true))
        assertEquals(false, needed(AgentStatus.Offline, live = "runtime_login_required", external = true))
        assertEquals(false, needed(AgentStatus.Offline, live = "runtime_login_required", likelyOnline = true))
    }

    @Test
    fun theProfileReadsTheLoadedStatusAndKeptReasonWithoutALiveIndicator() {
        val summary = AgentDetailSummary(id = "a1", name = "cat", activity = "offline", lastRuntimeErrorSpawnFailureReason = "runtime_login_required")
        assertEquals(true, membersAgentRuntimeSignInNeeded(indicator = null, summary = summary, external = false, blocked = null, liveSpawnFailureReason = null))
        assertEquals(false, membersAgentRuntimeSignInNeeded(indicator = null, summary = null, external = false, blocked = null, liveSpawnFailureReason = null))
    }

    @Test
    fun signInRuntimeForMatchesWebSingleSignInRule() {
        assertEquals(RuntimeAuthRuntime.Claude, signInRuntimeFor("claude"))
        assertEquals(RuntimeAuthRuntime.Codex, signInRuntimeFor("codex"))
        assertEquals(RuntimeAuthRuntime.Kimi, signInRuntimeFor("kimi-sdk"))
        assertEquals(RuntimeAuthRuntime.Qoder, signInRuntimeFor("qoder"))
        assertEquals(RuntimeAuthRuntime.Antigravity, signInRuntimeFor("antigravity"))
        assertEquals(RuntimeAuthRuntime.Grok, signInRuntimeFor("grok"))
        // Per-provider and no-login runtimes have no single sign-in to offer.
        assertNull(signInRuntimeFor("pi"))
        assertNull(signInRuntimeFor("opencode"))
        assertNull(signInRuntimeFor("qwen"))
        assertNull(signInRuntimeFor("dsh"))
        assertNull(signInRuntimeFor("builtin"))
        assertNull(signInRuntimeFor("kimi"))
        assertNull(signInRuntimeFor(null))
        assertNull(signInRuntimeFor(""))
    }

    @Test
    fun runtimeSignInOfferedRequiresCanActOkAvailabilityAndLoggedOut() {
        fun view(
            status: RuntimeAuthStatus?,
            canAct: Boolean = true,
            availability: RuntimeAuthAvailability = RuntimeAuthAvailability.Ok,
        ) = RuntimeAuthView(
            canAct = canAct,
            availability = availability,
            status = if (status != null) mapOf(RuntimeAuthRuntime.Kimi to status) else emptyMap(),
            runtimes = listOf(RuntimeAuthRuntime.Kimi),
            logoutRuntimes = listOf(RuntimeAuthRuntime.Kimi),
        )

        assertTrue(runtimeSignInOffered(view(RuntimeAuthStatus.LoggedOut()), RuntimeAuthRuntime.Kimi))
        assertFalse(runtimeSignInOffered(view(RuntimeAuthStatus.LoggedOut(), canAct = false), RuntimeAuthRuntime.Kimi))
        assertFalse(runtimeSignInOffered(view(RuntimeAuthStatus.LoggedOut(), availability = RuntimeAuthAvailability.ComputerOffline), RuntimeAuthRuntime.Kimi))
        assertFalse(runtimeSignInOffered(view(RuntimeAuthStatus.LoggedOut(), availability = RuntimeAuthAvailability.ComputerUpgradeRequired), RuntimeAuthRuntime.Kimi))
        assertFalse(runtimeSignInOffered(view(RuntimeAuthStatus.LoggedOut(), availability = RuntimeAuthAvailability.ComputerNotAttached), RuntimeAuthRuntime.Kimi))
        assertFalse(runtimeSignInOffered(view(RuntimeAuthStatus.LoggedIn()), RuntimeAuthRuntime.Kimi))
        assertFalse(runtimeSignInOffered(view(RuntimeAuthStatus.Unknown), RuntimeAuthRuntime.Kimi))
        assertFalse(runtimeSignInOffered(view(RuntimeAuthStatus.Unavailable(RuntimeAuthUnavailableReason.NotInstalled)), RuntimeAuthRuntime.Kimi))
        assertFalse(runtimeSignInOffered(view(null), RuntimeAuthRuntime.Kimi))
        assertFalse(runtimeSignInOffered(null, RuntimeAuthRuntime.Kimi))
    }

    @Test
    fun agentProfileParserCarriesLastRuntimeErrorSpawnFailureReason() {
        val withReason =
            parseSharedAgentProfile(
                """
                {
                  "id": "agent-1",
                  "name": "milo",
                  "displayName": "Milo",
                  "runtime": "kimi-sdk",
                  "status": "offline",
                  "lastRuntimeError": {
                    "spawnFailureReason": "runtime_login_required"
                  }
                }
                """.trimIndent()
            )
        assertNotNull(withReason)
        assertEquals("runtime_login_required", withReason.lastRuntimeErrorSpawnFailureReason)

        val withoutReason =
            parseSharedAgentProfile(
                """
                {
                  "id": "agent-2",
                  "name": "nova",
                  "runtime": "codex",
                  "status": "offline"
                }
                """.trimIndent()
            )
        assertNotNull(withoutReason)
        assertNull(withoutReason.lastRuntimeErrorSpawnFailureReason)
    }

    @Test
    fun indicatorStatePreservesSignInFieldsAcrossWithoutActivityDetail() {
        val indicator =
            SharedServerMemberStatus(
                status = AgentStatus.Offline,
                presence = Presence.Offline,
                runtime = "kimi-sdk",
                external = false,
                spawnFailureReason = "runtime_login_required",
                lastRuntimeErrorSpawnFailureReason = "runtime_login_required",
                paused = false,
                machineId = "m-1",
                machineName = "Studio Mac",
            ).toRaftAgentStatusIndicatorState()

        val stripped = indicator.withoutActivityDetail()
        assertEquals("runtime_login_required", stripped.spawnFailureReason)
        assertEquals("runtime_login_required", stripped.lastRuntimeErrorSpawnFailureReason)
        assertEquals("m-1", stripped.machineId)
        assertEquals("Studio Mac", stripped.machineName)
        assertFalse(stripped.paused)
    }
}
