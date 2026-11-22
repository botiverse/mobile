package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentMigrationLogTest {
    private fun move(
        ref: String,
        agent: String = "a1",
        from: String = "m1",
        to: String? = "m2",
        outcome: AgentMigrationLogOutcome = AgentMigrationLogOutcome.Completed,
        startedAt: String = "2026-11-20T10:00:00.000Z",
        endedAt: String? = "2026-11-20T10:03:01.000Z",
    ) = AgentMigrationLogEntry(
        migrationRef = ref,
        agentId = agent,
        sourceMachineId = from,
        sourceMachineName = "$from-then",
        toCloud = to == null,
        targetMachineId = to,
        targetMachineName = to?.let { "$it-then" },
        initiatedBy = null,
        outcome = outcome,
        startedAt = startedAt,
        endedAt = endedAt,
        failureKind = null,
    )

    @Test
    fun readsTheServersBodyAndDropsOnlyARowItCannotRead() {
        val log =
            parseAgentMigrationLog(
                """{"migrations":[
                {"migrationRef":"r1","agentId":"a1","sourceMachineId":"m1","sourceMachineName":"hx","targetKind":"computer",
                 "targetMachineId":"m2","targetMachineName":null,"initiatedBy":{"id":"u1","name":"artin","displayName":null},
                 "outcome":"failed","startedAt":"2026-11-20T10:00:00.000Z","endedAt":"2026-11-20T10:00:09.600Z",
                 "bytes":12,"files":3,"failureKind":"brand_new","failureCode":"X"},
                {"migrationRef":"r2","agentId":"a1","sourceMachineId":"m1","sourceMachineName":null,"targetKind":"hosted",
                 "targetMachineId":null,"targetMachineName":null,"initiatedBy":null,"outcome":"in_progress",
                 "startedAt":"2026-11-20T11:00:00.000Z","endedAt":null,"bytes":null,"files":null,"failureKind":null,"failureCode":null},
                {"migrationRef":"r3","outcome":"completed"}
                ],"truncated":true}""",
            )
        assertEquals(listOf("r1", "r2"), log?.entries?.map { it.migrationRef })
        assertEquals(true, log?.truncated)
        val (failed, running) = log!!.entries
        assertEquals(AgentMigrationLogFailureKind.Other, failed.failureKind)
        assertEquals(10L, failed.durationSeconds)
        assertEquals("cloud", running.targetNode)
        assertNull(running.durationSeconds)
        assertNull(parseAgentMigrationLog("<html>"))
    }

    @Test
    fun codesReadLikeAirportCodesAndNeverRepeat() {
        val codes =
            agentMigrationComputerCodes(
                listOf(
                    "1" to "hetzner-ex63-3",
                    "2" to "armbian-1",
                    "3" to "Alices-MacBook-Air.local",
                    "4" to "oak linux",
                    "5" to "pi",
                    "6" to "hetzner-ex64-3",
                    "7" to "开发机",
                ),
            )
        assertEquals("HE3", codes["1"])
        assertEquals("AR1", codes["2"])
        assertEquals("AMA", codes["3"])
        assertEquals("OLI", codes["4"])
        assertEquals("PIX", codes["5"])
        // The same letters as hetzner-ex63-3: the last one walks on.
        assertEquals("HEH", codes["6"])
        assertEquals("开发机", codes["7"])
        assertEquals(codes.size, codes.values.toSet().size)
    }

    @Test
    fun placesAreCurrentComputersThenTheCloudThenRemovedOnes() {
        val nodes =
            agentMigrationLogNodes(
                listOf(move("r1", from = "gone", to = "m1"), move("r2", from = "m1", to = null)),
                listOf(
                    AgentMigrationLogMachine("m1", "Studio"),
                    AgentMigrationLogMachine("m2", "alpha", hostProvider = "Hetzner"),
                    AgentMigrationLogMachine("m3", "beta", hostProvider = "hetzner"),
                    AgentMigrationLogMachine("m4", "mini", deviceClass = "laptop"),
                ),
            )
        // Groups by size, then "other", the cloud, the removed ones.
        assertEquals(listOf("alpha", "beta", "mini", "Studio", "", "gone-then"), nodes.map { it.name })
        assertEquals(listOf(true, true, true, true, false, false), nodes.map { it.known })
        assertEquals("CLD", nodes[4].code)
    }

    @Test
    fun filtersCombine() {
        val now = 1_795_000_000_000L
        val entries =
            listOf(
                move("r1", agent = "a1", from = "m1", to = "m2", startedAt = "2020-01-01T00:00:00.000Z"),
                move("r2", agent = "a2", from = "m2", to = "m1", outcome = AgentMigrationLogOutcome.Failed),
                move("r3", agent = "a1", from = "m3", to = null, outcome = AgentMigrationLogOutcome.InProgress),
            )

        fun refs(filters: AgentMigrationLogFilters) = filters.apply(entries, now).map { it.migrationRef }
        assertEquals(listOf("r1", "r3"), refs(AgentMigrationLogFilters(agents = listOf("a1"))))
        assertEquals(listOf("r1", "r2"), refs(AgentMigrationLogFilters(computers = listOf("m2"))))
        assertEquals(listOf("r2"), refs(AgentMigrationLogFilters(from = listOf("m2"))))
        assertEquals(listOf("r3"), refs(AgentMigrationLogFilters(to = listOf("cloud"))))
        assertEquals(listOf("r2"), refs(AgentMigrationLogFilters(outcomes = listOf(AgentMigrationLogOutcome.Failed, AgentMigrationLogOutcome.Canceled))))
        assertEquals(listOf("r2", "r3"), refs(AgentMigrationLogFilters(days = 30)))
        assertEquals(emptyList(), refs(AgentMigrationLogFilters(agents = listOf("a2"), to = listOf("m2"))))
        assertEquals(false, AgentMigrationLogFilters().isFiltered)
        assertEquals(true, AgentMigrationLogFilters(days = 7).isFiltered)
    }

    @Test
    fun theSuccessRateLeavesOutCanceledAndRunningMoves() {
        val stats =
            agentMigrationLogStats(
                listOf(
                    move("r1"),
                    move("r2", agent = "a2", from = "m2", to = null, outcome = AgentMigrationLogOutcome.Failed),
                    move("r3", outcome = AgentMigrationLogOutcome.Canceled),
                    move("r4", outcome = AgentMigrationLogOutcome.InProgress),
                    move("r5"),
                ),
            )
        assertEquals(AgentMigrationLogStats(total = 5, successRate = 67, agents = 2, computers = 3), stats)
        assertNull(agentMigrationLogStats(emptyList()).successRate)
    }

    @Test
    fun searchLooksInEveryTextOfARow() {
        val entries = listOf(move("r1", from = "m1"), move("r2", from = "m9"))
        val found = searchAgentMigrationLog(entries, "  M9-THEN ") { listOf(it.sourceMachineName, null) }
        assertEquals(listOf("r2"), found.map { it.migrationRef })
        assertEquals(entries, searchAgentMigrationLog(entries, " ") { emptyList() })
    }

    @Test
    fun aRouteCountsItsFinishedMovesOnly() {
        val cells =
            agentMigrationRouteCells(
                listOf(
                    move("r1"),
                    move("r2", outcome = AgentMigrationLogOutcome.Failed),
                    move("r3", outcome = AgentMigrationLogOutcome.Canceled),
                    move("r4", from = "m2", to = "m1", outcome = AgentMigrationLogOutcome.InProgress),
                    move("r5", from = "m2", to = null),
                ),
            )
        assertEquals(listOf(AgentMigrationRouteCell("m1", "m2", 1, 1), AgentMigrationRouteCell("m2", "cloud", 1, 0)), cells)
    }

    @Test
    fun aJourneyKeepsTheTriesBeforeEachArrival() {
        val journey =
            agentMigrationJourney(
                listOf(
                    move("r1", outcome = AgentMigrationLogOutcome.Failed),
                    move("other", agent = "a2"),
                    move("r2"),
                    move("r3", from = "m2", to = "m3", outcome = AgentMigrationLogOutcome.Canceled),
                    move("r4", from = "m2", to = "m3", outcome = AgentMigrationLogOutcome.InProgress),
                ),
                "a1",
            )
        assertEquals("m1", journey.start)
        assertEquals(listOf("r2" to listOf("r1")), journey.legs.map { leg -> leg.entry.migrationRef to leg.tries.map { it.migrationRef } })
        assertEquals(listOf("r3"), journey.pending.map { it.migrationRef })
        assertNull(agentMigrationJourney(emptyList(), "a1").start)
    }

    @Test
    fun theBusiestAgentComesFirst() {
        assertEquals(listOf("a2" to 2, "a1" to 1), agentMigrationLogAgentMoves(listOf(move("r1"), move("r2", agent = "a2"), move("r3", agent = "a2"))))
    }
}
