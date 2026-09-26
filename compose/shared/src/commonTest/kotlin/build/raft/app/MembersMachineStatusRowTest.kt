package build.raft.app

import build.raft.app.data.ComputerStatus
import build.raft.app.resources.MR
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SharedComputersLoadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Task #259. One test per cell of the acceptance table on the card. Every input here is
 * constructed in memory, so the three cells that still lack a visual fixture
 * (daemonOffline, computerOnline, daemonOnline) are covered at the logic layer -- which is
 * NOT the same as their visual cells being covered; those stay NOT RUN until Batch 3 lands.
 */
class MembersMachineStatusRowTest {
    private fun machine(
        id: String = "m",
        status: ComputerStatus = ComputerStatus.Online,
        isComputer: Boolean = true,
        computerVersion: String? = "0.0.48",
        daemonVersion: String? = "0.65.0"
    ) = ComputerSummary(
        id = id,
        name = "machine-$id",
        status = status,
        statusVersion = 0L,
        apiKeyPrefix = null,
        runtimes = emptyList(),
        daemonVersion = daemonVersion,
        computerVersion = computerVersion,
        isComputer = isComputer,
        hostname = null,
        os = null,
        description = null,
        lastHeartbeat = null,
        createdAt = null
    )

    // --- run label: the SIX-way choice from web machineRunLabel.ts ---------------------------

    @Test
    fun onlineComputerWithVersionShowsComputerVersion() {
        assertEquals(
            MembersMachineRunLabel.ComputerVersion("0.0.48"),
            membersMachineRunLabel(machine())
        )
    }

    @Test
    fun onlineBareDaemonWithVersionShowsDaemonVersion() {
        // Same shape as fixture Machines.DaemonOnly (#2346).
        assertEquals(
            MembersMachineRunLabel.DaemonVersion("0.65.0"),
            membersMachineRunLabel(machine(isComputer = false, computerVersion = null))
        )
    }

    @Test
    fun offlineComputerShowsComputerOfflineWithoutVersion() {
        // artin: 乙 -- keep the "computer offline" segment, drop the version number.
        assertEquals(
            MembersMachineRunLabel.ComputerOffline,
            membersMachineRunLabel(machine(status = ComputerStatus.Offline))
        )
    }

    @Test
    fun offlineBareDaemonShowsDaemonOffline() {
        // No fixture input yet (Batch 3). Logic-layer coverage only.
        assertEquals(
            MembersMachineRunLabel.DaemonOffline,
            membersMachineRunLabel(machine(status = ComputerStatus.Offline, isComputer = false, computerVersion = null))
        )
    }

    @Test
    fun onlineComputerWithoutVersionIsOnlineNotOffline() {
        // The incident cell (#wg-raft-computer:de94165c): a non-owner REST read returns
        // status=online with version=null. Routing this into the offline label showed a live
        // Computer as offline. It must never reach ComputerOffline.
        assertEquals(
            MembersMachineRunLabel.ComputerOnline,
            membersMachineRunLabel(machine(computerVersion = null))
        )
    }

    @Test
    fun onlineBareDaemonWithoutVersionIsOnlineNotOffline() {
        assertEquals(
            MembersMachineRunLabel.DaemonOnline,
            membersMachineRunLabel(machine(isComputer = false, computerVersion = null, daemonVersion = null))
        )
    }

    @Test
    fun blankVersionCountsAsMissing() {
        assertEquals(
            MembersMachineRunLabel.ComputerOnline,
            membersMachineRunLabel(machine(computerVersion = "  "))
        )
    }

    @Test
    fun unknownStatusIsTreatedAsNotOnline() {
        // ComputerStatus.Unknown is unreachable under the current server contract (task #262
        // monitors it). If it ever arrives, "not online" is the conservative reading; this test
        // pins that it does not accidentally claim the machine is up.
        assertEquals(
            MembersMachineRunLabel.ComputerOffline,
            membersMachineRunLabel(machine(status = ComputerStatus.Unknown))
        )
    }

    // --- row resolution: cells 4-6 of the table ---------------------------------------------

    @Test
    fun noMachineIdIsNoComputerRegardlessOfStore() {
        assertEquals(
            MembersMachineStatusRow.NoComputer,
            membersMachineStatusRow(null, listOf(machine()), SharedComputersLoadStatus.Loaded, hasSnapshot = true)
        )
        assertEquals(
            MembersMachineStatusRow.NoComputer,
            membersMachineStatusRow("  ", emptyList(), SharedComputersLoadStatus.Idle, hasSnapshot = false)
        )
    }

    @Test
    fun machineIdAbsentFromLoadedStoreIsNoComputer() {
        // Cell 4: web renders "no computer assigned" here; we do too. No dot.
        assertEquals(
            MembersMachineStatusRow.NoComputer,
            membersMachineStatusRow("missing", listOf(machine(id = "other")), SharedComputersLoadStatus.Loaded, hasSnapshot = true)
        )
    }

    @Test
    fun machineIdAbsentFromUnloadedStoreIsLoadingNotNoComputer() {
        // Cell 6, the one web gets wrong: before machines arrive, the id is "absent" for the
        // same reason as cell 4 but for the opposite cause. artin chose 甲 -- render nothing
        // for the row -- so this must resolve to Loading, never to NoComputer.
        for (status in listOf(SharedComputersLoadStatus.Idle, SharedComputersLoadStatus.InFlight, SharedComputersLoadStatus.Error)) {
            assertEquals(
                MembersMachineStatusRow.Loading,
                membersMachineStatusRow("m", emptyList(), status, hasSnapshot = false),
                "status=$status"
            )
        }
    }

    @Test
    fun aRetainedSnapshotIsUsedEvenWhileRefreshing() {
        // hasSnapshot=true with a non-Loaded status is a refresh over known data: answer from
        // the snapshot rather than blanking the row on every refresh.
        assertEquals(
            MembersMachineStatusRow.Machine(online = true, runLabel = MembersMachineRunLabel.ComputerVersion("0.0.48")),
            membersMachineStatusRow("m", listOf(machine()), SharedComputersLoadStatus.InFlight, hasSnapshot = true)
        )
    }

    @Test
    fun statusWordAndRunLabelAreIndependentFacts() {
        // LiBai: two spans from one record. An offline machine's row carries online=false AND
        // an offline run label; asserting only the concatenated string would let one hide the other.
        val row = membersMachineStatusRow(
            "m", listOf(machine(id = "m", status = ComputerStatus.Offline)), SharedComputersLoadStatus.Loaded, hasSnapshot = true
        ) as MembersMachineStatusRow.Machine
        assertEquals(false, row.online)
        assertEquals(MembersMachineRunLabel.ComputerOffline, row.runLabel)
    }

    // LiBai (review of c1fadb13f): localized() is @Composable, so the six label -> resource pairs had
    // no unit coverage; a swapped resource would compile and pass every test above. The pairing is
    // now a pure function. Each branch is asserted by identity against its own MR key, and the
    // argument-carrying branches are asserted separately from the argument-free ones.

    @Test
    fun computerVersionPairsWithItsOwnResourceAndCarriesTheVersion() {
        val label = MembersMachineRunLabel.ComputerVersion("0.0.48")
        assertSame(MR.strings.member_machine_run_computer_version, label.resource())
        assertEquals("0.0.48", label.formatArg())
    }

    @Test
    fun daemonVersionPairsWithItsOwnResourceAndCarriesTheVersion() {
        val label = MembersMachineRunLabel.DaemonVersion("0.65.0")
        assertSame(MR.strings.member_machine_run_daemon_version, label.resource())
        assertEquals("0.65.0", label.formatArg())
    }

    @Test
    fun computerOnlinePairsWithItsOwnResourceAndTakesNoArgument() {
        assertSame(MR.strings.member_machine_run_computer_online, MembersMachineRunLabel.ComputerOnline.resource())
        assertNull(MembersMachineRunLabel.ComputerOnline.formatArg())
    }

    @Test
    fun daemonOnlinePairsWithItsOwnResourceAndTakesNoArgument() {
        assertSame(MR.strings.member_machine_run_daemon_online, MembersMachineRunLabel.DaemonOnline.resource())
        assertNull(MembersMachineRunLabel.DaemonOnline.formatArg())
    }

    @Test
    fun computerOfflinePairsWithItsOwnResourceAndTakesNoArgument() {
        assertSame(MR.strings.member_machine_run_computer_offline, MembersMachineRunLabel.ComputerOffline.resource())
        assertNull(MembersMachineRunLabel.ComputerOffline.formatArg())
    }

    @Test
    fun daemonOfflinePairsWithItsOwnResourceAndTakesNoArgument() {
        assertSame(MR.strings.member_machine_run_daemon_offline, MembersMachineRunLabel.DaemonOffline.resource())
        assertNull(MembersMachineRunLabel.DaemonOffline.formatArg())
    }

    @Test
    fun theSixLabelsMapToSixDistinctResources() {
        // A copy-paste that pointed two branches at one key would pass each single-branch test
        // above only if the wrong key were also the asserted one; this catches the pair colliding.
        val resources = listOf(
            MembersMachineRunLabel.ComputerVersion("x"),
            MembersMachineRunLabel.DaemonVersion("x"),
            MembersMachineRunLabel.ComputerOnline,
            MembersMachineRunLabel.DaemonOnline,
            MembersMachineRunLabel.ComputerOffline,
            MembersMachineRunLabel.DaemonOffline
        ).map { it.resource() }
        assertEquals(6, resources.toSet().size)
    }

    // --- task #336: machineId-vs-server-list gate for the agent-detail Computer link ---------

    @Test
    fun knownStateIsAbsentWhenMachineIdIsBlank() {
        assertEquals(
            MembersAgentComputerKnownState.Absent,
            membersAgentComputerKnownState(
                machineId = " ",
                machines = listOf(machine("m-1")),
                loadStatus = SharedComputersLoadStatus.Loaded,
                hasSnapshot = true
            )
        )
    }

    @Test
    fun knownStateIsUnknownWhileStoreHasNotAnswered() {
        // machineId present but store still loading: the link stays (three-state, same
        // Loading/answered split as membersMachineStatusRow).
        assertEquals(
            MembersAgentComputerKnownState.Unknown,
            membersAgentComputerKnownState(
                machineId = "m-1",
                machines = emptyList(),
                loadStatus = SharedComputersLoadStatus.Idle,
                hasSnapshot = false
            )
        )
    }

    @Test
    fun knownStateIsAbsentOnceSnapshotProvesMachineNotOnThisServer() {
        // #336 root case: agent detail carries a machineId the current server does not list
        // -> link must be withdrawn instead of opening a guaranteed 404.
        assertEquals(
            MembersAgentComputerKnownState.Absent,
            membersAgentComputerKnownState(
                machineId = "m-gone",
                machines = listOf(machine("m-1"), machine("m-2")),
                loadStatus = SharedComputersLoadStatus.Loaded,
                hasSnapshot = true
            )
        )
    }

    @Test
    fun knownStateIsPresentWhenLoadedSliceContainsTheMachine() {
        assertEquals(
            MembersAgentComputerKnownState.Present,
            membersAgentComputerKnownState(
                machineId = "m-2",
                machines = listOf(machine("m-1"), machine("m-2")),
                loadStatus = SharedComputersLoadStatus.Loaded,
                hasSnapshot = true
            )
        )
    }
}