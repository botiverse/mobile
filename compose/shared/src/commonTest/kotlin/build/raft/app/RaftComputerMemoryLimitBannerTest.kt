package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.SettingsComputerAgentScopesSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Web MachineDetailPanel `agentMemoryLimitNotApplied` (botiverse/slock #10860), see RaftComputerMemoryLimitBanner.kt. */
class RaftComputerMemoryLimitBannerTest {
    private val notApplied =
        SettingsComputerAgentScopesSnapshot(
            state = "unavailable",
            limitsConfigured = true,
            limitsNotApplied = true,
            reason = "memory_controller_not_delegated",
            memoryMax = "6G",
        )

    private fun computer(online: Boolean, agentScopes: SettingsComputerAgentScopesSnapshot?) =
        SettingsComputerSnapshot(id = "m-1", name = "box", daemonVersion = "", online = online, agentScopes = agentScopes)

    @Test
    fun showsOnlyForAnOnlineMachineWhoseLimitIsNotApplied() {
        assertEquals(notApplied, raftComputerMemoryLimitNotApplied(computer(online = true, agentScopes = notApplied)))
        // Offline: the server drops agentScopes anyway, but a stale row that went offline must not warn.
        assertNull(raftComputerMemoryLimitNotApplied(computer(online = false, agentScopes = notApplied)))
        // Configured and applied.
        assertNull(
            raftComputerMemoryLimitNotApplied(
                computer(online = true, agentScopes = notApplied.copy(state = "available", limitsNotApplied = false, reason = null)),
            ),
        )
        // Scopes off, an older daemon or an older server.
        assertNull(raftComputerMemoryLimitNotApplied(computer(online = true, agentScopes = null)))
    }

    @Test
    fun reasonsReadAsAShortPhraseWithAnUnknownFallback() {
        assertEquals(
            Res.string.settings_computers_memory_limit_reason_memory_controller_not_delegated,
            raftComputerMemoryLimitReasonText("memory_controller_not_delegated"),
        )
        assertEquals(
            Res.string.settings_computers_memory_limit_reason_slice_property_failed,
            raftComputerMemoryLimitReasonText("slice_property_failed"),
        )
        assertEquals(Res.string.settings_computers_memory_limit_reason_unknown, raftComputerMemoryLimitReasonText("some_future_reason"))
    }
}
