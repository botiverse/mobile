package build.raft.app

import build.raft.app.data.AgentBlockedState
import build.raft.app.data.RuntimeAccountUsageViewer
import build.raft.app.data.agentBlockedState
import build.raft.app.data.englishStatusLabel
import build.raft.app.data.statusLabelRes
import build.raft.app.model.RETIRED_RUNTIME_IDS
import build.raft.app.model.RUNTIMES
import build.raft.app.model.isRuntimeRetired
import build.raft.app.model.retiredRuntimeNeedsComputerUpdate
import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retired runtimes parity (botiverse/slock #16830): old Kimi CLI (`kimi`), Gemini CLI (`gemini`),
 * and Copilot CLI (`copilot`) are retired, blocked with `runtime_retired`, hidden from creation
 * pickers, and lead with Computer update only when an older Computer cannot offer Kimi Code.
 */
class AgentRuntimeRetiredTest {
    @Test
    fun retiredRuntimeCatalogEntriesAreAllDeprecatedAndExcludedFromCreatableRuntimes() {
        assertEquals(listOf("kimi", "gemini", "copilot"), RETIRED_RUNTIME_IDS)
        RETIRED_RUNTIME_IDS.forEach { retiredId ->
            assertTrue(isRuntimeRetired(retiredId), "expected retired: $retiredId")
            val info = RUNTIMES.firstOrNull { it.id == retiredId }
            assertTrue(info != null && info.deprecated, "expected deprecated catalog row for $retiredId")
        }
        assertFalse(isRuntimeRetired("kimi-sdk"))
        assertFalse(isRuntimeRetired("claude"))
        assertFalse(isRuntimeRetired("antigravity"))
        assertFalse(isRuntimeRetired(null))
        assertFalse(isRuntimeRetired("   "))
        assertTrue(isRuntimeRetired(" kimi "))

        val creatableIds = RUNTIMES.filterNot { it.deprecated }.map { it.id }
        RETIRED_RUNTIME_IDS.forEach { retiredId ->
            assertFalse(retiredId in creatableIds, "creatable runtimes must hide $retiredId")
        }
        assertTrue("kimi-sdk" in creatableIds)
        assertTrue("antigravity" in creatableIds)
    }

    @Test
    fun retiredRuntimeNeedsComputerUpdateOnlyForKimiOnComputerWithoutKimiCode() {
        assertTrue(retiredRuntimeNeedsComputerUpdate("kimi", listOf("kimi", "claude")))
        assertFalse(retiredRuntimeNeedsComputerUpdate("kimi", listOf("kimi", "kimi-sdk", "claude")))
        assertFalse(retiredRuntimeNeedsComputerUpdate("kimi", null))
        assertFalse(retiredRuntimeNeedsComputerUpdate("kimi", emptyList()))
        // CLI replacements (Antigravity) may just not be installed, never "update computer first".
        assertFalse(retiredRuntimeNeedsComputerUpdate("gemini", listOf("gemini", "claude")))
        assertFalse(retiredRuntimeNeedsComputerUpdate("copilot", listOf("copilot", "claude")))
        assertFalse(retiredRuntimeNeedsComputerUpdate("claude", listOf("kimi")))
    }

    @Test
    fun retiredRuntimeCopySelectsActionShortLabelAndPerRuntimeDetail() {
        assertTrue(runtimeRetiredLeadsWithComputerUpdate("kimi", computerUpdateFirst = true))
        assertFalse(runtimeRetiredLeadsWithComputerUpdate("kimi", computerUpdateFirst = false))
        assertFalse(runtimeRetiredLeadsWithComputerUpdate("gemini", computerUpdateFirst = true))
        assertFalse(runtimeRetiredLeadsWithComputerUpdate("copilot", computerUpdateFirst = true))

        assertEquals(
            Res.string.agent_status_computer_update_required,
            agentRuntimeRetiredShortLabelRes("kimi", computerUpdateFirst = true),
        )
        assertEquals(
            Res.string.agent_status_runtime_retired_change_runtime,
            agentRuntimeRetiredShortLabelRes("kimi", computerUpdateFirst = false),
        )
        assertEquals(
            Res.string.agent_status_runtime_retired_change_runtime,
            agentRuntimeRetiredShortLabelRes("gemini", computerUpdateFirst = true),
        )
        assertEquals(
            Res.string.agent_status_runtime_retired_change_runtime,
            agentRuntimeRetiredShortLabelRes("copilot", computerUpdateFirst = false),
        )

        assertEquals(
            Res.string.agent_status_runtime_retired_detail_kimi,
            agentRuntimeRetiredDetailRes("kimi", computerUpdateFirst = false),
        )
        assertEquals(
            Res.string.agent_status_runtime_retired_detail_kimi_update_computer,
            agentRuntimeRetiredDetailRes("kimi", computerUpdateFirst = true),
        )
        assertEquals(
            Res.string.agent_status_runtime_retired_detail_gemini,
            agentRuntimeRetiredDetailRes("gemini", computerUpdateFirst = false),
        )
        assertEquals(
            Res.string.agent_status_runtime_retired_detail_copilot,
            agentRuntimeRetiredDetailRes("copilot", computerUpdateFirst = false),
        )
    }

    @Test
    fun blockedStateRuntimeRetiredClearsImmediatelyWhenRuntimeSwitches() {
        assertEquals(AgentBlockedState.RuntimeRetired, agentBlockedState("runtime_retired"))
        assertEquals(AgentBlockedState.RuntimeRetired, agentBlockedState("runtime_retired", "kimi"))
        assertEquals(AgentBlockedState.RuntimeRetired, agentBlockedState("runtime_retired", "gemini"))
        assertEquals(AgentBlockedState.RuntimeRetired, agentBlockedState("runtime_retired", "copilot"))
        assertNull(agentBlockedState("runtime_retired", "kimi-sdk"))
        assertNull(agentBlockedState("runtime_retired", "claude"))
        assertEquals("Change runtime", AgentBlockedState.RuntimeRetired.englishStatusLabel)
        assertEquals(Res.string.agent_status_runtime_retired_change_runtime, AgentBlockedState.RuntimeRetired.statusLabelRes)
    }

    @Test
    fun canChangeRetiredAgentRuntimeRequiresOwnerAdminOrHumanCreator() {
        assertFalse(canChangeRetiredAgentRuntime(viewer = null, creatorUserId = "user-1"))
        assertTrue(
            canChangeRetiredAgentRuntime(
                viewer = RuntimeAccountUsageViewer(currentUserId = "admin-1", canEditMachines = true),
                creatorUserId = "other-user",
            )
        )
        assertTrue(
            canChangeRetiredAgentRuntime(
                viewer = RuntimeAccountUsageViewer(currentUserId = "user-1", canEditMachines = false),
                creatorUserId = "user-1",
            )
        )
        assertFalse(
            canChangeRetiredAgentRuntime(
                viewer = RuntimeAccountUsageViewer(currentUserId = "user-2", canEditMachines = false),
                creatorUserId = "user-1",
            )
        )
        assertFalse(
            canChangeRetiredAgentRuntime(
                viewer = RuntimeAccountUsageViewer(currentUserId = "user-2", canEditMachines = false),
                creatorUserId = null,
            )
        )
    }
}
