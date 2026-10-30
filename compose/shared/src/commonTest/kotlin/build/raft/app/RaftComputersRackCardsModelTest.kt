package build.raft.app

import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerBroadcastPolicySnapshot
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerVersionGateSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
    fun currentComputerShowsNoNoticeAndItsPresenceLight() {
        val row = computer("m-1", platform = "darwin arm64", computerVersion = "1.0.53", latest = "1.0.53").toRaftComputerRow()
        val facts = assertIs<RaftComputerCardFacts.Online>(row.cardFacts())
        assertEquals("macOS", facts.osName)
        assertNull(facts.notice)
        assertEquals(RaftComputerDot.Online, row.dot)
    }

    @Test
    fun osNameIsTheReleaseElseMacOsOrWindowsElseNothing() {
        assertEquals("Debian GNU/Linux 12", raftComputerOsName(" Debian GNU/Linux 12 ", "linux arm64"))
        assertEquals("macOS", raftComputerOsName(null, "darwin arm64"))
        assertEquals("Windows", raftComputerOsName("", "win32 x64"))
        assertNull(raftComputerOsName(null, "linux x64"))
        assertNull(raftComputerOsName(null, null))
    }

    @Test
    fun belowTheVersionFloorSaysNeedsUpgradeOrReinstallBeforeTheArrow() {
        val gate = SettingsComputerVersionGateSnapshot(reason = "computer_version_unsupported", minVersion = "1.0.50")
        val upgrade = computer("m-1", computerVersion = "1.0.46", latest = "1.0.53", versionGate = gate).toRaftComputerRow()
        assertEquals(RaftComputerVersionNotice.BelowFloor(reinstall = false), upgrade.versionNotice)
        assertEquals(RaftComputerDot.Upgrade, upgrade.dot)
        // Older than web upgrade can reach: a reinstall.
        val reinstall = computer("m-2", computerVersion = "1.0.40", latest = "1.0.53", versionGate = gate).toRaftComputerRow()
        assertEquals(RaftComputerVersionNotice.BelowFloor(reinstall = true), reinstall.versionNotice)
    }

    @Test
    fun unreportedVersionPointsAtTheEligiblePolicyTarget() {
        val policy = SettingsComputerBroadcastPolicySnapshot(eligibility = "eligible", targetVersion = "1.0.53")
        val snapshot = computer("m-1", computerVersion = null, latest = "1.0.53").copy(upgradeAvailable = true, broadcastPolicy = policy)
        assertEquals("1.0.53", raftComputerAvailableVersion(snapshot))
        assertNull(raftComputerAvailableVersion(snapshot.copy(upgradeAvailable = false)))
        // A daemon never points at a version.
        assertNull(raftComputerAvailableVersion(snapshot.copy(isComputer = false)))
    }

    @Test
    fun offlineFactsAreOfflineAndLastSeen() {
        val row = computer("m-1", online = false, computerVersion = "1.0.49", latest = "1.0.53", lastHeartbeat = "2026-10-20T12:00:00Z").toRaftComputerRow()
        assertEquals(RaftComputerCardFacts.Offline("2026-10-20T12:00:00Z"), row.cardFacts())
        // An outdated offline Computer keeps the pink light, as on web.
        assertEquals(RaftComputerDot.Upgrade, row.dot)
        val current = computer("m-2", online = false, computerVersion = "1.0.53", latest = "1.0.53").toRaftComputerRow()
        assertEquals(RaftComputerDot.Offline, current.dot)
        assertEquals(RaftComputerCardFacts.Offline(null), current.cardFacts())
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
    fun agentStripKeepsOneLineAndEndsWithTheOverflowCount() {
        assertEquals(RaftComputerAgentStrip(visible = 0, overflow = 0), raftComputerAgentStrip(total = 0, capacity = 9))
        assertEquals(RaftComputerAgentStrip(visible = 9, overflow = 0), raftComputerAgentStrip(total = 9, capacity = 9))
        // One too many: the last slot becomes "+2" (the 9th and 10th agent).
        assertEquals(RaftComputerAgentStrip(visible = 8, overflow = 2), raftComputerAgentStrip(total = 10, capacity = 9))
        assertEquals(RaftComputerAgentStrip(visible = 8, overflow = 6), raftComputerAgentStrip(total = 14, capacity = 9))
        // No room at all still shows the count.
        assertEquals(RaftComputerAgentStrip(visible = 0, overflow = 3), raftComputerAgentStrip(total = 3, capacity = 0))
    }

    @Test
    fun slotCapacityCountsWholeSlotsWithTheirGaps() {
        // 27dp avatars 4dp apart: n slots take 31n - 4.
        assertEquals(10, raftComputerAgentSlotCapacity(width = 306f, slot = 27f, gap = 4f))
        assertEquals(9, raftComputerAgentSlotCapacity(width = 305f, slot = 27f, gap = 4f))
        assertEquals(1, raftComputerAgentSlotCapacity(width = 27f, slot = 27f, gap = 4f))
        assertEquals(0, raftComputerAgentSlotCapacity(width = 0f, slot = 27f, gap = 4f))
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
