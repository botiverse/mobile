package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MembersAgentComputerNavigationTest {
    @Test
    fun canonicalSummaryMachineIdWinsOverMemberBadgeFallback() {
        assertEquals(
            SettingsComputerRouteIdentity(
                serverId = "server-1",
                machineId = "machine-from-summary",
            ),
            membersAgentComputerNavigationTarget(
                serverId = " server-1 ",
                summaryMachineId = " machine-from-summary ",
                memberMachineId = "machine-from-badge",
                accessMode = MemberDetailAccessMode.LocalFull,
            ),
        )
    }

    @Test
    fun memberBadgeMachineIdIsUsedWhenSummaryIdentityIsMissing() {
        assertEquals(
            SettingsComputerRouteIdentity(
                serverId = "server-1",
                machineId = "machine-from-badge",
            ),
            membersAgentComputerNavigationTarget(
                serverId = "server-1",
                summaryMachineId = " ",
                memberMachineId = " machine-from-badge ",
                accessMode = MemberDetailAccessMode.LocalFull,
            ),
        )
    }

    @Test
    fun blankServerOrMachineIdentityCannotCreateAComputerTarget() {
        assertNull(
            membersAgentComputerNavigationTarget(
                serverId = " ",
                summaryMachineId = "machine-1",
                memberMachineId = null,
                accessMode = MemberDetailAccessMode.LocalFull,
            ),
        )
        assertNull(
            membersAgentComputerNavigationTarget(
                serverId = "server-1",
                summaryMachineId = null,
                memberMachineId = " ",
                accessMode = MemberDetailAccessMode.LocalFull,
            ),
        )
    }

    @Test
    fun remoteProfileCannotNavigateIntoTheCurrentServersComputerSurface() {
        assertNull(
            membersAgentComputerNavigationTarget(
                serverId = "server-1",
                summaryMachineId = "machine-1",
                memberMachineId = "machine-2",
                accessMode = MemberDetailAccessMode.RemoteProfileOnly,
            ),
        )
    }


    @Test
    fun machineAbsentFromCurrentServerComputersCannotNavigate() {
        assertNull(
            membersAgentComputerNavigationTarget(
                serverId = "server-1",
                summaryMachineId = "machine-1",
                memberMachineId = null,
                accessMode = MemberDetailAccessMode.LocalFull,
                machineKnown = MembersAgentComputerKnownState.Absent,
            ),
        )
    }

    @Test
    fun machinePresentOrStoreStillLoadingKeepsTheTarget() {
        val expected = SettingsComputerRouteIdentity(serverId = "server-1", machineId = "machine-1")
        listOf(
            MembersAgentComputerKnownState.Unknown,
            MembersAgentComputerKnownState.Present,
        ).forEach { state ->
            assertEquals(
                expected,
                membersAgentComputerNavigationTarget(
                    serverId = "server-1",
                    summaryMachineId = "machine-1",
                    memberMachineId = null,
                    accessMode = MemberDetailAccessMode.LocalFull,
                    machineKnown = state,
                ),
            )
        }
    }
}