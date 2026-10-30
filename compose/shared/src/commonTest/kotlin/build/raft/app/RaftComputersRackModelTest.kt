package build.raft.app

import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Web rack card meters and usage lines (botiverse/slock rack/rackRuntimeColumns.ts, ComputersRack.tsx). */
class RaftComputersRackModelTest {
    private val gib = 1024.0 * 1024.0 * 1024.0
    private val claude = RaftRackUsageColumn(RuntimeAccountUsageProvider.Claude, "claude")

    @Test
    fun diskWarnsByTheLowDiskRuleAndMemoryNever() {
        val row =
            computer(
                disk = SettingsComputerSizeStatusSnapshot(availableBytes = 6 * gib, totalBytes = 500 * gib),
                memory = SettingsComputerSizeStatusSnapshot(availableBytes = 0.5 * gib, totalBytes = 64 * gib),
            ).toRaftComputerRow()
        assertEquals(RaftComputerResourceMeter(percentUsed = 99, warn = true), row.disk)
        assertEquals(RaftComputerResourceMeter(percentUsed = 99, warn = false), row.memory)

        // 85% used but 150 GiB free: not low, no warning (not Meter's 80%).
        val roomy = computer(disk = SettingsComputerSizeStatusSnapshot(availableBytes = 150 * gib, totalBytes = 1000 * gib)).toRaftComputerRow()
        assertEquals(RaftComputerResourceMeter(percentUsed = 85, warn = false), roomy.disk)
        assertNull(roomy.memory)

        // Offline: no meters at all.
        val offline =
            computer(online = false, disk = SettingsComputerSizeStatusSnapshot(availableBytes = 6 * gib, totalBytes = 500 * gib)).toRaftComputerRow()
        assertNull(offline.disk)
    }

    @Test
    fun columnsOneLinePerProviderInCatalogOrderAndTheRestInOther() {
        val columns =
            raftRackRuntimeColumns(
                runtimes = listOf("gemini", "kimi", "codex", "kimi-sdk", "claude", "zeta"),
                catalogOrder = listOf("claude", "codex", "kimi-sdk", "kimi", "gemini"),
            )
        assertEquals(
            listOf(
                RaftRackUsageColumn(RuntimeAccountUsageProvider.Claude, "claude"),
                RaftRackUsageColumn(RuntimeAccountUsageProvider.Codex, "codex"),
                RaftRackUsageColumn(RuntimeAccountUsageProvider.Kimi, "kimi-sdk"),
            ),
            columns.usage,
        )
        assertEquals(listOf("gemini", "zeta"), columns.other)
    }

    @Test
    fun cellWarnsFromEightyPercentAndSaysUsedUpAtTheLimit() {
        assertEquals(
            RaftRackUsageCell.Value(claude, used = 62, warn = false, usedUp = false, stale = false, attention = false),
            raftRackUsageCell(claude, loaded(account(window(0.62)))),
        )
        assertEquals(
            RaftRackUsageCell.Value(claude, used = 80, warn = true, usedUp = false, stale = false, attention = false),
            raftRackUsageCell(claude, loaded(account(window(0.8)))),
        )
        assertEquals(
            RaftRackUsageCell.Value(claude, used = 100, warn = true, usedUp = true, stale = false, attention = false),
            raftRackUsageCell(claude, loaded(account(window(0.3), window(null, RuntimeAccountUsageWindowStatus.LimitReached)))),
        )
    }

    @Test
    fun cellTakesTheTightestAccountKeepsAttentionAndDimsStale() {
        val cell =
            raftRackUsageCell(
                claude,
                loaded(
                    account(window(0.4)),
                    account(window(0.7)),
                    account(health = RuntimeAccountUsageHealth.ReauthRequired),
                    stale = true,
                ),
            )
        assertEquals(RaftRackUsageCell.Value(claude, used = 70, warn = false, usedUp = false, stale = true, attention = true), cell)
    }

    @Test
    fun cellIsADashWithNothingToRead() {
        assertEquals(RaftRackUsageCell.Unavailable(claude, attention = false), raftRackUsageCell(claude, null))
        assertEquals(RaftRackUsageCell.Unavailable(claude, attention = false), raftRackUsageCell(claude, RuntimeAccountUsageLoad.Forbidden))
        assertEquals(
            RaftRackUsageCell.Unavailable(claude, attention = false),
            raftRackUsageCell(claude, RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Missing)),
        )
        val attention = raftRackUsageCell(claude, loaded(account(health = RuntimeAccountUsageHealth.ReauthRequired)))
        assertIs<RaftRackUsageCell.Unavailable>(attention)
        assertEquals(true, attention.attention)
    }

    private fun computer(
        online: Boolean = true,
        disk: SettingsComputerSizeStatusSnapshot? = null,
        memory: SettingsComputerSizeStatusSnapshot? = null,
    ) = SettingsComputerSnapshot(id = "m1", name = "m1", daemonVersion = "", online = online, diskStatus = disk, memoryStatus = memory)

    private fun window(ratio: Double?, status: RuntimeAccountUsageWindowStatus = RuntimeAccountUsageWindowStatus.Ok) =
        RuntimeAccountUsageWindow(id = "w", label = "5h", status = status, usedRatio = ratio, resetsAt = null)

    private fun account(vararg windows: RuntimeAccountUsageWindow, health: RuntimeAccountUsageHealth = RuntimeAccountUsageHealth.Ok) =
        RuntimeAccountUsageAccount(accountKey = "a", planLabel = null, identityLabel = null, health = health, windows = windows.toList())

    private fun loaded(vararg accounts: RuntimeAccountUsageAccount, stale: Boolean = false) =
        RuntimeAccountUsageLoad.Loaded(
            RuntimeAccountUsageRead.Available(stale = stale, snapshot = RuntimeAccountUsageSnapshot("claude", null, accounts.toList())),
        )
}
