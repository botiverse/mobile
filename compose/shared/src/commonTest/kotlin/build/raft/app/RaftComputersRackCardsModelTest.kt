package build.raft.app

import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerUpgradeRequestSnapshot
import build.raft.app.data.SettingsComputerVersionGateSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Computers page's rack cards (web ComputersRack `view === "cards"`): facts, agents, counts. */
class RaftComputersRackCardsModelTest {
    @Test
    fun factsLineIsOsVersionAndTheNewerVersion() {
        val row = computer("m-1", osRelease = "Ubuntu 24.04", platform = "linux x64", computerVersion = "1.0.49", latest = "1.0.53").toRaftComputerRow()
        assertEquals(
            RaftComputerCardFacts.Online(
                osName = "Ubuntu 24.04",
                kind = RaftComputerKind.Computer,
                version = "1.0.49",
                notice = RaftComputerVersionNotice.Available("1.0.53"),
            ),
            row.cardFacts(),
        )
        assertEquals(RaftComputerDot.Upgrade, row.dot)
    }

    @Test
    fun belowTheVersionFloorSaysUpdateRequiredWhetherAnUpgradeOrAReinstall() {
        val gate = SettingsComputerVersionGateSnapshot(reason = "computer_version_unsupported", minVersion = "1.0.60")
        val upgrade = computer("m-1", computerVersion = "1.0.53", latest = "1.0.60", versionGate = gate).toRaftComputerRow()
        assertEquals(RaftComputerVersionNotice.BelowFloor(reinstall = false), upgrade.versionNotice)
        assertEquals(RaftComputerDot.Upgrade, upgrade.dot)
        // Below the built-in 1.0.53 floor: a reinstall.
        val reinstall = computer("m-2", computerVersion = "1.0.52", latest = "1.0.60", versionGate = gate).toRaftComputerRow()
        assertEquals(RaftComputerVersionNotice.BelowFloor(reinstall = true), reinstall.versionNotice)
    }

    @Test
    fun offlineFactsAreOfflineAndLastSeen() {
        val row = computer("m-1", online = false, computerVersion = "1.0.49", latest = "1.0.53", lastHeartbeat = "2026-10-20T12:00:00Z").toRaftComputerRow()
        assertEquals(RaftComputerCardFacts.Offline("2026-10-20T12:00:00Z"), row.cardFacts())
        // An outdated offline Computer keeps the orange needs-attention light.
        assertEquals(RaftComputerDot.Upgrade, row.dot)
        val current = computer("m-2", online = false, computerVersion = "1.0.53", latest = "1.0.53").toRaftComputerRow()
        assertEquals(RaftComputerDot.Offline, current.dot)
        assertEquals(RaftComputerCardFacts.Offline(null), current.cardFacts())
    }

    @Test
    fun anUpdateInFlightIsUpdatingInPlaceNeverOffline() {
        // raft-design patterns/status.md: the machines row's pending `upgradeRequest` (every viewer
        // reads it) is in progress: the yellow light, "Updating to v…", and the computer stays in
        // place while it restarts offline.
        val pending = SettingsComputerUpgradeRequestSnapshot(id = "u1", targetVersion = "1.0.53", requestedAt = "2026-10-22T09:00:00Z", state = "pending")
        val restarting =
            computer("m-1", online = false, computerVersion = "1.0.49", latest = "1.0.53")
                .copy(upgradeRequest = pending)
        val row = restarting.toRaftComputerRow()
        assertTrue(row.updating)
        assertTrue(row.present)
        assertEquals(RaftComputerDot.Updating, row.dot)
        assertEquals(RaftComputerVersionNotice.Updating("1.0.53"), row.versionNotice)
        assertEquals(RaftComputerVersionNotice.Updating("1.0.53"), assertIs<RaftComputerCardFacts.Online>(row.cardFacts()).notice)
        assertFalse(raftRackNeedsAttention(restarting.copy(upgradeAvailable = false)), "a restart for an update is not an outage")
        // Settled requests are not in flight; a daemon never updates from here.
        assertFalse(restarting.copy(upgradeRequest = pending.copy(state = "done")).toRaftComputerRow().updating)
        assertFalse(restarting.copy(upgradeRequest = pending.copy(state = "no_response")).toRaftComputerRow().updating)
        assertFalse(restarting.copy(isComputer = false).toRaftComputerRow().updating)
    }

    @Test
    fun lowDiskNeedsUnderTenPercentAndUnderTwentyGibWhileOnline() {
        val gib = 1024.0 * 1024.0 * 1024.0
        fun low(available: Double, total: Double, online: Boolean = true) =
            raftComputerDiskLow(computer("m", online = online).copy(diskStatus = SettingsComputerSizeStatusSnapshot(available * gib, total * gib)))
        assertTrue(low(9.0, 100.0))
        assertFalse(low(10.0, 100.0))
        assertFalse(low(100.0, 2048.0), "5% of 2 TiB is still 100 GiB")
        assertTrue(low(19.0, 2048.0))
        assertFalse(low(15.0, 100.0), "15% is not low even under 20 GiB")
        assertFalse(low(1.0, 100.0, online = false))
        assertFalse(raftComputerDiskLow(computer("m")))
        assertTrue(computer("m").copy(diskStatus = SettingsComputerSizeStatusSnapshot(1 * gib, 100 * gib)).toRaftComputerRow().diskLow)
    }

    @Test
    fun agentsGroupByTheirComputerAndSkipDeletedHumansAndUnassigned() {
        val members =
            listOf(
                agent("a-1", "Wug", "m-1"),
                agent("a-2", "Jony", "m-2"),
                agent("a-3", "Gone", "m-1").copy(agentDeletedAt = "2026-10-01T00:00:00Z"),
                agent("a-4", "Loose", machineId = null),
                MemberProfile(id = MemberId("u-1"), displayName = "Artin", handle = "artin", kind = MemberKind.Human, permissionBadges = listOf("computer-id:m-1")),
                agent("a-5", "Lagoon", "m-1"),
                agent("a-1", "Wug", "m-1"),
            )
        val byMachine = raftComputerAgentsByMachine(members)
        assertEquals(listOf("a-1", "a-5"), byMachine["m-1"]?.map { it.id })
        assertEquals(listOf("Jony"), byMachine["m-2"]?.map { it.name })
        assertEquals(setOf("m-1", "m-2"), byMachine.keys)
    }

    @Test
    fun headerCountsComputersOnlineAndAgentsOnListedComputers() {
        val model =
            assertIs<RaftComputersListModel.Content>(
                raftComputersListModel(
                    ScreenLoadState.Content(
                        SettingsComputersSnapshot(
                            computers = listOf(computer("m-1"), computer("m-2", online = false), computer("m-3")),
                        )
                    )
                )
            )
        val agents =
            raftComputerAgentsByMachine(
                listOf(agent("a-1", "Wug", "m-1"), agent("a-2", "Jony", "m-2"), agent("a-3", "Kirby", "m-2"), agent("a-4", "Elsewhere", "m-9"))
            )
        assertEquals(RaftComputersCounts(computers = 3, online = 2, agents = 3), raftComputersCounts(model.rows, agents))
        assertEquals(RaftComputersCounts(computers = 3, online = 2, agents = 0), raftComputersCounts(model.rows, emptyMap()))
    }

    // The facts line's layout (web ComputerRackRow `onlineFacts`: one line, the OS gives way first).
    // Widths in a mono-meta character (7px here): "Ubuntu 26.04" 12, " · v1.25.9" 10,
    // " · Update available" 19; a card at the grid's 250dp minimum has about 190px for the line.
    private fun ch(n: Int) = n * 7

    @Test
    fun factsLineKeepsTheStepBesideTheVersionWhileTheOsCanGiveWay() {
        // Everything fits: nothing gives way.
        assertEquals(RaftComputerFactsLinePlan(osWidth = ch(12), stepOnLine = true), raftComputerFactsLinePlan(ch(45), ch(12), 24, ch(10), ch(19)))
        // The OS gives way first; the version and the step stay whole on the line.
        assertEquals(RaftComputerFactsLinePlan(osWidth = ch(37 - 29), stepOnLine = true), raftComputerFactsLinePlan(ch(37), ch(12), 24, ch(10), ch(19)))
    }

    @Test
    fun aStepThatCannotSitBesideTheVersionGoesWholeOntoTheNextLine() {
        // Not even the OS cut to its floor leaves room: the step takes the next line, and the OS keeps
        // its whole name beside the version instead of two letters for nothing (#wg-mobile-sync:#255).
        assertEquals(RaftComputerFactsLinePlan(osWidth = ch(12), stepOnLine = false), raftComputerFactsLinePlan(190, ch(12), 24, ch(10), ch(19)))
        // Narrower still, the OS gives way only as far as the version needs.
        assertEquals(RaftComputerFactsLinePlan(osWidth = 100 - ch(10), stepOnLine = false), raftComputerFactsLinePlan(100, ch(12), 24, ch(10), ch(19)))
    }

    @Test
    fun factsLineDropsStepToNextLineBeforeSqueezingReadableOsNameOnMediumCards() {
        // Portrait tablet 800dp 2-column card (~270px text width): keeping " · Update available" on
        // line 1 would crush "Ubuntu 24.04" (84px) into "Ubuntu 2..." (~67px). With the readable
        // inline step floor (88px), the step wraps onto line 2 so the full OS name stays intact.
        val inlineFloor = RaftComputerFactsOsInlineStepFloor.value.toInt()
        assertEquals(
            RaftComputerFactsLinePlan(osWidth = ch(12), stepOnLine = false),
            raftComputerFactsLinePlan(
                width = 270,
                os = ch(12),
                osFloor = 24,
                version = ch(10),
                step = ch(19),
                osInlineStepFloor = inlineFloor,
            ),
        )
        // A very long distro name still shares line 1 with the step when it keeps at least the
        // readable inline floor (88px), and very narrow widths still protect the version down to 24px.
        assertEquals(
            RaftComputerFactsLinePlan(osWidth = 310 - ch(10) - ch(19), stepOnLine = true),
            raftComputerFactsLinePlan(
                width = 310,
                os = ch(28),
                osFloor = 24,
                version = ch(10),
                step = ch(19),
                osInlineStepFloor = inlineFloor,
            ),
        )
        assertEquals(
            RaftComputerFactsLinePlan(osWidth = 100 - ch(10), stepOnLine = false),
            raftComputerFactsLinePlan(
                width = 100,
                os = ch(12),
                osFloor = 24,
                version = ch(10),
                step = ch(19),
                osInlineStepFloor = inlineFloor,
            ),
        )
    }

    private fun agent(id: String, name: String, machineId: String?) =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = name.lowercase(),
            kind = MemberKind.Agent,
            permissionBadges = listOfNotNull("claude", machineId?.let { "computer:$it-name" }, machineId?.let { "computer-id:$it" }),
        )

    private fun computer(
        id: String,
        online: Boolean = true,
        computerVersion: String? = "1.0.53",
        latest: String? = "1.0.53",
        platform: String? = null,
        osRelease: String? = null,
        lastHeartbeat: String? = null,
        versionGate: SettingsComputerVersionGateSnapshot? = null,
    ) = SettingsComputerSnapshot(
        id = id,
        name = "Computer $id",
        daemonVersion = "",
        computerVersion = computerVersion,
        isComputer = true,
        online = online,
        platform = platform,
        osRelease = osRelease,
        lastHeartbeat = lastHeartbeat,
        versionGate = versionGate,
        latestComputerVersion = latest,
    )
}
