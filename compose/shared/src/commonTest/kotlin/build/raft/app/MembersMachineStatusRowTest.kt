package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.members.MembersAgentComputerKnownState
import build.raft.app.members.MembersMachineRunLabel
import build.raft.app.members.MembersMachineStatusRow
import build.raft.app.members.formatArg
import build.raft.app.members.membersAgentComputerKnownState
import build.raft.app.members.membersMachineRunLabel
import build.raft.app.members.membersMachineStatusRow
import build.raft.app.members.resource
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun onlineBareDaemonWithoutVersionIsOnlineNotOffline() {
        assertEquals(
            MembersMachineRunLabel.DaemonOnline,
            membersMachineRunLabel(machine(isComputer = false, computerVersion = null, daemonVersion = null))
        )
    }

    // --- row resolution: cells 4-6 of the table ---------------------------------------------

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

    // LiBai (review of c1fadb13f): localized() is @Composable, so the six label -> resource pairs had
    // no unit coverage; a swapped resource would compile and pass every test above. The pairing is
    // now a pure function. Each branch is asserted by identity against its own MR key, and the
    // argument-carrying branches are asserted separately from the argument-free ones.

    @Test
    fun computerVersionPairsWithItsOwnResourceAndCarriesTheVersion() {
        val label = MembersMachineRunLabel.ComputerVersion("0.0.48")
        assertSame(Res.string.member_machine_run_computer_version, label.resource())
        assertEquals("0.0.48", label.formatArg())
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
}