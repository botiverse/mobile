package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.runPortableCoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Computer owner v1 and the upgrade drain, on the wire as botiverse/slock staging a6885e181 sends
 * them: `ownerUserId` / `owner` on a `GET /servers/:id/machines` row (#13344),
 * `PUT /servers/:id/machines/:machineId/owner` (computerOwnerRoutes.ts), and the upgrade request's
 * `deadlineAt` / `phase` / `drainingAgentIds` (#13506, computerUpgradeRequestService.ts).
 */
class ComputerOwnerApiTest {
    @Test
    fun aRowCarriesItsOwnerAndTheUpgradeDrain() = runPortableCoroutineTest {
        val client = RaftComputersApiClient(OwnerComputersApi())
        val list = assertIs<DataResult.Content<ComputerListSummary>>(client.machines(ServerId("server-1"))).data

        val owned = list.machines.first { it.id == "machine-1" }
        assertEquals("user-2", owned.ownerUserId)
        assertEquals(SettingsComputerCreatorSnapshot(id = "user-2", name = "bea", displayName = "Bea"), owned.owner)
        val request = requireNotNull(owned.upgradeRequest)
        assertEquals("2026-11-08T00:40:00.000Z", request.deadlineAt)
        assertEquals("draining", request.phase)
        assertEquals(listOf("agent-a", "agent-b"), request.drainingAgentIds)
        assertEquals("2026-11-08T00:10:00.000Z", request.drainDeadlineAt)

        val snapshot = owned.toSettingsComputerSnapshot()
        assertEquals("user-2", snapshot.ownerUserId)
        assertEquals(owned.owner, snapshot.owner)

        // No owner (and an older server without the fields): Public, no drain.
        val public = list.machines.first { it.id == "machine-2" }
        assertNull(public.ownerUserId)
        assertNull(public.owner)
        assertNull(public.upgradeRequest?.phase)
        assertEquals(emptyList(), public.upgradeRequest?.drainingAgentIds)
    }

    @Test
    fun setOwnerPutsTheIdOrNullAndReadsTheMachineBack() = runPortableCoroutineTest {
        val api = OwnerComputersApi()
        val client = RaftComputersApiClient(api)

        val changed = assertIs<DataResult.Content<ComputerOwnerChange>>(client.setMachineOwner(ServerId("server-1"), "machine-2", "user-1")).data
        assertEquals("user-1", changed.machine?.ownerUserId)
        assertEquals(OwnerCall("server-1", "machine-2", """{"ownerUserId":"user-1"}"""), api.calls.last())

        assertIs<DataResult.Content<ComputerOwnerChange>>(client.setMachineOwner(ServerId("server-1"), "machine-1", null))
        assertEquals("""{"ownerUserId":null}""", api.calls.last().body)
    }

    @Test
    fun aRefusedChangeIsAnError() = runPortableCoroutineTest {
        val api =
            OwnerComputersApi(
                ownerResponse =
                    HomeNetworkResponse(
                        403,
                        """{"code":"computer_owner_forbidden","error":"Only the Computer's owner or a server owner or admin can change its owner."}"""
                    )
            )
        assertIs<DataResult.NoPermission>(RaftComputersApiClient(api).setMachineOwner(ServerId("server-1"), "machine-1", "user-3"))
        Unit
    }

    @Test
    fun transferIsTheOwnerOrAnAdminsAndMakeItMineThePublicComputersConnectors() {
        val owned = SettingsComputerSnapshot(id = "m", name = "m", daemonVersion = "", isComputer = true, online = true, ownerUserId = "user-2")
        val public = owned.copy(ownerUserId = null)
        fun permissions(role: String?, user: String?, computer: SettingsComputerSnapshot) =
            settingsComputerOwnerPermissions(serverRole = role, currentUserId = user, computer = computer)

        assertEquals(SettingsComputerOwnerPermissions(canTransfer = true, canClaim = false), permissions("owner", "user-9", owned))
        assertEquals(SettingsComputerOwnerPermissions(canTransfer = true, canClaim = false), permissions("admin", "user-9", owned))
        assertEquals(SettingsComputerOwnerPermissions(canTransfer = true, canClaim = false), permissions("member", "user-2", owned))
        assertEquals(SettingsComputerOwnerPermissions(canTransfer = false, canClaim = false), permissions("member", "user-9", owned))
        // A Public Computer: only whoever connected it may claim it (the attacher; a daemon's registrant).
        assertEquals(SettingsComputerOwnerPermissions(canTransfer = false, canClaim = false), permissions("member", "user-9", public))
        assertEquals(
            SettingsComputerOwnerPermissions(canTransfer = false, canClaim = true),
            permissions("member", "user-9", public.copy(attachedByCurrentUser = true))
        )
        val daemon = public.copy(isComputer = false, creatorUserId = "user-9")
        assertEquals(SettingsComputerOwnerPermissions(canTransfer = false, canClaim = true), permissions("member", "user-9", daemon))
        // A connector of a Computer that already has an owner cannot take it.
        assertEquals(
            SettingsComputerOwnerPermissions(canTransfer = false, canClaim = false),
            permissions("member", "user-9", owned.copy(attachedByCurrentUser = true))
        )
        assertEquals(SettingsComputerOwnerPermissions(canTransfer = true, canClaim = true), permissions("admin", "user-9", daemon))
    }

    private data class OwnerCall(val serverId: String, val machineId: String, val body: String)

    private class OwnerComputersApi(private val ownerResponse: HomeNetworkResponse? = null) : RaftComputersApi {
        val calls = mutableListOf<OwnerCall>()

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = HomeNetworkResponse(200, MACHINES)

        override suspend fun setMachineOwner(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse {
            calls += OwnerCall(serverId.value, machineId, body)
            ownerResponse?.let { return it }
            val owner = if (body.contains("user-1")) """"ownerUserId":"user-1","owner":{"type":"human","id":"user-1","name":"ann","displayName":null,"avatarUrl":null}""" else """"ownerUserId":null,"owner":null"""
            return HomeNetworkResponse(200, """{"machine":{"id":"$machineId","name":"Box","status":"online","statusVersion":1,"runtimes":[],$owner}}""")
        }

        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = unused()
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = unused()
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = unused()
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = unused()
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = unused()
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = unused()
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
            unused()

        private fun unused(): HomeNetworkResponse = error("not used by this test")
    }

    private companion object {
        val MACHINES =
            """
            {"machines":[
              {"id":"machine-1","name":"Studio","status":"online","statusVersion":3,"runtimes":["claude"],"computerVersion":"1.0.46",
               "isComputer":true,"ownerUserId":"user-2",
               "owner":{"type":"human","id":"user-2","name":"bea","displayName":"Bea","avatarUrl":null,"gravatarHash":null},
               "upgradeRequest":{"id":"req-1","targetVersion":"1.0.50","requestedAt":"2026-11-08T00:00:00.000Z","state":"pending",
                 "observedVersion":null,"reason":null,"resolvedAt":null,"failureHint":null,"deadlineAt":"2026-11-08T00:40:00.000Z",
                 "phase":"draining","drainingAgentIds":["agent-a","agent-b"],"drainDeadlineAt":"2026-11-08T00:10:00.000Z"}},
              {"id":"machine-2","name":"Box","status":"online","statusVersion":1,"runtimes":[],"computerVersion":"1.0.46","isComputer":true,
               "upgradeRequest":{"id":"req-2","targetVersion":"1.0.50","requestedAt":"2026-11-08T00:00:00.000Z","state":"pending"}}
            ],"latestComputerVersion":"1.0.50"}
            """.trimIndent()
    }
}
