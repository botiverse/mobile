package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.runPortableCoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * slock #15002 (botiverse/slock 03db25a85, pinned staging 8600b62c6): GET /servers/:id/machines
 * (routes/servers.ts buildMachineList) sends `controlledByCurrentUser` per machine, whether the viewer
 * is the controller the routes decide by (computerControllerUserId). Web `isMachineController` /
 * `canManageMachine` gate rename, description, rescan and removal on it; it outlives a key revoke.
 */
class ComputerControllerPermissionsTest {
    private val adopter = "user-adopter"
    private val previousOwner = "user-previous"

    // The server test's adopted Computer after its key is revoked: the row still names the previous
    // owner (`userId`), nobody attaches it now and `creator` is gone, but the adopter controls it.
    private val body =
        """
        {"machines":[
          {"id":"m-adopted","name":"Adopted","status":"offline","isComputer":false,
           "userId":"$previousOwner","computerAttachedByCurrentUser":false,"creator":null,
           "controlledByCurrentUser":true},
          {"id":"m-older","name":"Older server","status":"offline","isComputer":false,
           "userId":"$previousOwner","computerAttachedByCurrentUser":false,"creator":null}
        ],"latestDaemonVersion":null,"latestComputerVersion":null}
        """.trimIndent()

    private suspend fun snapshots(): Map<String, SettingsComputerSnapshot> =
        assertIs<DataResult.Content<ComputerListSummary>>(
            RaftComputersApiClient(ComputersApiTest.RecordingComputersApi(machinesBody = body)).machines(ServerId("server-1"), DataRequestPriority.Visible)
        ).data.machines.map { it.toSettingsComputerSnapshot() }.associateBy { it.id }

    @Test
    fun theMachineListCarriesWhetherTheViewerControlsEachMachine() = runPortableCoroutineTest {
        val snapshots = snapshots()
        assertEquals(true, snapshots.getValue("m-adopted").controlledByCurrentUser)
        assertNull(snapshots.getValue("m-older").controlledByCurrentUser)
    }

    @Test
    fun theControllerKeepsEditAndRemoveAfterTheKeyIsRevoked() = runPortableCoroutineTest {
        val adopted = snapshots().getValue("m-adopted")
        assertEquals(
            SettingsComputerPermissions(canEdit = true, canRemove = true),
            settingsComputerPermissions(serverRole = "member", currentUserId = adopter, computer = adopted)
        )
        // The machine row's previous owner is not the controller, whatever machines.userId says.
        assertEquals(
            SettingsComputerPermissions(canEdit = false, canRemove = false),
            settingsComputerPermissions(
                serverRole = "member",
                currentUserId = previousOwner,
                computer = adopted.copy(controlledByCurrentUser = false)
            )
        )
        assertEquals(
            SettingsComputerPermissions(canEdit = true, canRemove = true),
            settingsComputerPermissions(serverRole = "admin", currentUserId = previousOwner, computer = adopted.copy(controlledByCurrentUser = false))
        )
    }

    @Test
    fun anOlderServerWithoutTheFlagKeepsTheCreatorAndAttacherRule() = runPortableCoroutineTest {
        val older = snapshots().getValue("m-older")
        assertEquals(
            SettingsComputerPermissions(canEdit = true, canRemove = true),
            settingsComputerPermissions(serverRole = "member", currentUserId = previousOwner, computer = older)
        )
        assertEquals(
            SettingsComputerPermissions(canEdit = false, canRemove = false),
            settingsComputerPermissions(serverRole = "member", currentUserId = adopter, computer = older)
        )
        assertEquals(
            SettingsComputerPermissions(canEdit = true, canRemove = true),
            settingsComputerPermissions(serverRole = "member", currentUserId = adopter, computer = older.copy(attachedByCurrentUser = true))
        )
    }
}
