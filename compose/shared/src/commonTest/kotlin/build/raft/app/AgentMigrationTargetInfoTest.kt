package build.raft.app

import build.raft.app.data.ComputerSizeStatus
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeQuotaLineCopy
import build.raft.app.data.RuntimeQuotaSegment
import build.raft.app.data.RuntimeQuotaSummary
import build.raft.app.data.RuntimeQuotaWindowKind
import build.raft.app.data.runtimeQuotaLineParts
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Move dialog target list's computer line and details (web targetComputerInfo.ts,
 * botiverse/slock 424c5203 #11038, row order from #11086 716c3711, "used" from #11855 96cc6349; strings from
 * packages/web/src/i18n/messages/en.ts).
 */
class AgentMigrationTargetInfoTest {
    private val gb = 1024.0 * 1024.0 * 1024.0

    private val en =
        AgentMigrationTargetInfoCopy(
            offline = "Offline",
            memoryUsed = { "RAM $it used" },
            diskUsed = { "Disk $it used" },
            agents = { count -> if (count == 0) "No agents" else if (count == 1) "1 agent" else "$count agents" }
        )

    private fun computer(
        online: Boolean = true,
        memory: ComputerSizeStatus? = null,
        disk: ComputerSizeStatus? = null,
        agents: Int? = null
    ) = AgentMigrationComputer("m", "Studio Mac", isComputer = true, online = online, offline = !online, agentCount = agents, diskStatus = disk, memoryStatus = memory)

    @Test
    fun theLineSaysAgentsThenMemoryThenDiskLeavingOutWhatIsUnknown() {
        val memory = ComputerSizeStatus(12 * gb, 32 * gb)
        val disk = ComputerSizeStatus(120 * gb, 500 * gb)
        val cases =
            listOf(
                computer(memory = memory, disk = disk, agents = 3) to "3 agents · RAM 63% used · Disk 76% used",
                // An older Computer reports no memory: the part is left out.
                computer(disk = disk, agents = 1) to "1 agent · Disk 76% used",
                computer(agents = 0) to "No agents",
                // Offline: said first; a lingering report is ignored.
                computer(online = false, memory = memory, disk = disk, agents = 3) to "Offline · 3 agents",
                computer() to null
            )
        cases.forEach { (machine, expected) -> assertEquals(expected, agentMigrationTargetInfoLine(machine, en)) }
    }

    @Test
    fun offlineDurationIsCoarseFromTheLastHeartbeat() {
        val now = requireNotNull(parseRaftTimestampEpochMillisOrNull("2026-10-23T12:00:00.000Z"))
        assertEquals(AgentMigrationDurationUnit.Minutes to 1, agentMigrationOfflineDuration("2026-10-23T12:00:00.000Z", now))
        assertEquals(AgentMigrationDurationUnit.Minutes to 25, agentMigrationOfflineDuration("2026-10-23T11:35:00.000Z", now))
        assertEquals(AgentMigrationDurationUnit.Hours to 3, agentMigrationOfflineDuration("2026-10-23T09:00:00.000Z", now))
        assertEquals(AgentMigrationDurationUnit.Days to 2, agentMigrationOfflineDuration("2026-10-21T12:00:00.000Z", now))
        assertEquals(null, agentMigrationOfflineDuration(null, now))
    }

    @Test
    fun aUsedUpWindowIsMarkedForTheWarningColour() {
        val copy =
            RuntimeQuotaLineCopy(
                fiveHour = "5h",
                week = "Week",
                used = { window, percent -> "$window $percent used" },
                usedUp = { window, reset -> "$window used up, resets $reset" },
                usedUpNoReset = { window -> "$window used up" },
                attention = { plan -> "$plan · needs attention" }
            )
        val summary =
            RuntimeQuotaSummary(
                provider = RuntimeAccountUsageProvider.Claude,
                planLabel = "Claude Max",
                segments =
                    listOf(
                        RuntimeQuotaSegment(RuntimeQuotaWindowKind.FiveHour, "Current session", 100, usedUp = true, resetsAt = "2026-10-23T15:00:00.000Z"),
                        RuntimeQuotaSegment(RuntimeQuotaWindowKind.Week, "Current week (all models)", 45, usedUp = false, resetsAt = null)
                    ),
                attention = false
            )
        val parts = runtimeQuotaLineParts(summary, copy) { "in 3 hours" }
        assertEquals(listOf("Claude Max", "5h used up, resets in 3 hours", "Week 45% used"), parts.map { it.text })
        assertEquals(listOf(false, true, false), parts.map { it.usedUp })
    }
}
