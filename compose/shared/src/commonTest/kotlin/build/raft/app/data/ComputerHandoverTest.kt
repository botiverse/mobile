package build.raft.app.data

import build.raft.app.RaftComputerHandoverController
import build.raft.app.RaftComputerOwnerCandidate
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.raftComputerHandoverCandidates
import build.raft.app.raftComputerOwnerCandidatesMatching
import build.raft.app.runPortableCoroutineTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * botiverse/slock #16435 / #16445 / #16452:
 * - `COMPUTER_HANDOVER_MIN_COMPUTER_VERSION` floor (`1.25.14`, #16445).
 * - `GET` / `POST` / `DELETE servers/:id/machines/:machineId/handover` contract, refusal codes,
 *   and `RaftComputerHandoverController` reload-on-refusal behavior.
 * - `raftComputerHandoverCandidates` (active human owners/admins only, excluding attacher).
 * - Handover-bound `computer_migrate` grant in Add Computer code entry routes to device login page.
 */
class ComputerHandoverTest {
    @Test
    fun handoverFloorMatchesSlock16445() {
        assertEquals("1.25.14", COMPUTER_HANDOVER_MIN_COMPUTER_VERSION)
    }

    @Test
    fun parseHandoverStateHandlesReadyPendingUpgradeAndInvalidBodies() {
        val ready =
            parseComputerHandoverState(
                """
                {
                  "handover": null,
                  "canDesignate": true,
                  "canCancel": false,
                  "upgradeRequired": false,
                  "minimumComputerVersion": "1.25.14"
                }
                """.trimIndent()
            )
        assertEquals(
            ComputerHandoverState(
                handover = null,
                canDesignate = true,
                canCancel = false,
                upgradeRequired = false,
                minimumComputerVersion = "1.25.14",
            ),
            ready,
        )

        val pending =
            parseComputerHandoverState(
                """
                {
                  "handover": {
                    "id": "h-1",
                    "to": { "id": "user-2", "name": "ada", "displayName": "Ada Admin" },
                    "fromUserId": "user-1",
                    "createdBy": "user-1",
                    "createdAt": "2026-11-23T10:00:00Z",
                    "expiresAt": "2026-11-24T10:00:00Z"
                  },
                  "canDesignate": true,
                  "canCancel": true,
                  "upgradeRequired": false,
                  "minimumComputerVersion": ""
                }
                """.trimIndent()
            )
        assertEquals(
            ComputerHandoverState(
                handover =
                    ComputerHandoverView(
                        id = "h-1",
                        to = ComputerHandoverPerson(id = "user-2", name = "ada", displayName = "Ada Admin"),
                        fromUserId = "user-1",
                        createdBy = "user-1",
                        createdAt = "2026-11-23T10:00:00Z",
                        expiresAt = "2026-11-24T10:00:00Z",
                    ),
                canDesignate = true,
                canCancel = true,
                upgradeRequired = false,
                minimumComputerVersion = COMPUTER_HANDOVER_MIN_COMPUTER_VERSION,
            ),
            pending,
        )
        assertEquals("Ada Admin", pending?.handover?.to?.label)
        assertEquals("ada", ComputerHandoverPerson("user-2", "ada", displayName = "  ").label)

        // Malformed or incomplete payloads return null.
        assertNull(parseComputerHandoverState(null))
        assertNull(parseComputerHandoverState("not json"))
        assertNull(parseComputerHandoverState("{}"))
        assertNull(
            parseComputerHandoverState(
                """{"handover":{"id":"","to":{"id":"u","name":"n"},"fromUserId":"f","createdBy":"c","createdAt":"a","expiresAt":"e"},"canDesignate":true,"canCancel":false,"upgradeRequired":false}"""
            )
        )
    }

    @Test
    fun designateBodyTrimsUserId() {
        assertEquals("""{"toUserId":"user-2"}""", buildComputerHandoverDesignateBody("  user-2  "))
    }

    @Test
    fun networkRepositoryCallsHandoverEndpointsAndPreservesRefusals() = runPortableCoroutineTest {
        val fake =
            FakeHandoverNetwork(
                responses =
                    ArrayDeque(
                        listOf(
                            HomeNetworkResponse(
                                200,
                                """{"handover":null,"canDesignate":true,"canCancel":false,"upgradeRequired":false,"minimumComputerVersion":"1.25.14"}""",
                            ),
                            HomeNetworkResponse(
                                201,
                                """{"handover":{"id":"h-1","to":{"id":"admin-1","name":"ada","displayName":"Ada Admin"},"fromUserId":"attacher-1","createdBy":"attacher-1","createdAt":"2026-11-23T10:00:00Z","expiresAt":"2026-11-24T10:00:00Z"},"canDesignate":true,"canCancel":true,"upgradeRequired":false,"minimumComputerVersion":"1.25.14"}""",
                            ),
                            HomeNetworkResponse(
                                200,
                                """{"handover":null,"canDesignate":true,"canCancel":false,"upgradeRequired":false,"minimumComputerVersion":"1.25.14"}""",
                            ),
                            HomeNetworkResponse(
                                code = 409,
                                bodyText = """{"code":"computer_upgrade_required","error":"Upgrade this Computer first: handing it over needs Raft Computer 1.25.14 or later."}""",
                                responseHeaders = mapOf("X-Trace-Id" to listOf("0123456789abcdef0123456789abcdef")),
                            ),
                        )
                    )
            )
        val repo = NetworkComputerHandoverRepository(fake)

        val got = assertIs<DataResult.Content<ComputerHandoverState>>(repo.getHandover(ServerId("srv-1"), "m-1")).data
        assertTrue(got.canDesignate)
        assertNull(got.handover)

        val designated =
            assertIs<DataResult.Content<ComputerHandoverState>>(
                repo.designateHandover(ServerId("srv-1"), "m-1", "admin-1")
            ).data
        assertEquals("h-1", designated.handover?.id)
        assertEquals("admin-1", designated.handover?.to?.id)

        val canceled = assertIs<DataResult.Content<ComputerHandoverState>>(repo.cancelHandover(ServerId("srv-1"), "m-1")).data
        assertNull(canceled.handover)

        val refused = assertIs<DataResult.Error>(repo.designateHandover(ServerId("srv-1"), "m-1", "admin-1")).error
        assertEquals("computer_upgrade_required", refused.serverCode)
        assertEquals("0123456789abcdef0123456789abcdef", refused.traceId)
        assertFalse(refused.retryable)

        assertEquals(
            listOf(
                Call("GET", "servers/srv-1/machines/m-1/handover", null),
                Call("POST", "servers/srv-1/machines/m-1/handover", """{"toUserId":"admin-1"}"""),
                Call("DELETE", "servers/srv-1/machines/m-1/handover", null),
                Call("POST", "servers/srv-1/machines/m-1/handover", """{"toUserId":"admin-1"}"""),
            ),
            fake.calls,
        )
    }

    @Test
    fun controllerUpdatesOnSuccessAndRefreshesOnRefusal() = runPortableCoroutineTest {
        val ready = ComputerHandoverState(canDesignate = true, canCancel = false, upgradeRequired = false)
        val pending =
            ready.copy(
                handover =
                    ComputerHandoverView(
                        id = "h-1",
                        to = ComputerHandoverPerson("admin-1", "ada", "Ada Admin"),
                        fromUserId = "attacher-1",
                        createdBy = "attacher-1",
                        createdAt = "2026-11-23T10:00:00Z",
                        expiresAt = "2026-11-24T10:00:00Z",
                    ),
                canCancel = true,
            )
        val upgraded = ready.copy(upgradeRequired = true)
        var getCalls = 0
        val repo =
            object : ComputerHandoverRepository {
                override suspend fun getHandover(serverId: ServerId, machineId: String): DataResult<ComputerHandoverState> {
                    getCalls += 1
                    return DataResult.Content(if (getCalls == 1) ready else upgraded)
                }

                override suspend fun designateHandover(
                    serverId: ServerId,
                    machineId: String,
                    toUserId: String,
                ): DataResult<ComputerHandoverState> =
                    if (toUserId == "admin-1") {
                        DataResult.Content(pending)
                    } else {
                        DataResult.Error(ScreenError(code = "computer_upgrade_required", serverCode = "computer_upgrade_required"))
                    }

                override suspend fun cancelHandover(serverId: ServerId, machineId: String): DataResult<ComputerHandoverState> =
                    DataResult.Error(ScreenError(code = "handover_not_found", serverCode = "handover_not_found"))
            }
        val controller = RaftComputerHandoverController(repo)

        // Disabled (raw daemon): skips read and stays null.
        controller.refresh(ServerId("srv-1"), "m-1", enabled = false)
        assertNull(controller.state)
        assertEquals(0, getCalls)

        // Initial load populates state.
        controller.refresh(ServerId("srv-1"), "m-1", enabled = true)
        assertEquals(ready, controller.state)
        assertEquals(1, getCalls)

        // Designate success publishes returned state directly without extra GET.
        assertIs<DataResult.Content<ComputerHandoverState>>(controller.designate(ServerId("srv-1"), "m-1", "admin-1"))
        assertEquals(pending, controller.state)
        assertEquals(1, getCalls)

        // Designate refusal triggers re-read so stale UI catches up.
        assertIs<DataResult.Error>(controller.designate(ServerId("srv-1"), "m-1", "bad-id"))
        assertEquals(upgraded, controller.state)
        assertEquals(2, getCalls)

        // Cancel refusal also triggers re-read.
        assertIs<DataResult.Error>(controller.cancel(ServerId("srv-1"), "m-1"))
        assertEquals(3, getCalls)
    }

    @Test
    fun handoverCandidatesOfferOnlyActiveHumanOwnersAndAdminsExcludingAttacher() {
        val members =
            listOf(
                human("attacher-1", "Attacher", role = "admin"),
                human("owner-1", "Olga Owner", role = "owner"),
                human("admin-1", "Ada Admin", role = "admin"),
                human("member-1", "Pat Member", role = "member"),
                human("guest-1", "Gus Guest", role = "guest"),
                human("removed-1", "Former Admin", role = "admin", status = "removed"),
                human("admin-2", "", handle = "sam", role = "admin"),
                MemberProfile(id = MemberId("agent-1"), displayName = "Bot", handle = "bot", kind = MemberKind.Agent, serverRole = "admin"),
                human("owner-1", "Olga Duplicate", role = "owner"),
            )
        val candidates =
            raftComputerHandoverCandidates(
                members = members,
                attacherUserId = "attacher-1",
                currentUserId = "owner-1",
            )
        assertEquals(
            listOf(
                RaftComputerOwnerCandidate("owner-1", "Olga Owner", isYou = true),
                RaftComputerOwnerCandidate("admin-1", "Ada Admin", isYou = false),
                RaftComputerOwnerCandidate("admin-2", "sam", isYou = false),
            ),
            candidates,
        )
        assertEquals(listOf("admin-1"), raftComputerOwnerCandidatesMatching(candidates, "ada").map { it.id })
    }

    @Test
    fun handoverBoundMigrateGrantRoutesToDeviceLoginPageInAddComputerCode() {
        val request =
            requireNotNull(
                parseSharedDeviceLoginRequest(
                    """
                    {
                      "userCode": "ABCD-EFGH",
                      "clientName": "raft-computer",
                      "requestedAt": "2026-11-23T10:00:00Z",
                      "requester": { "ip": "203.0.113.10", "location": null },
                      "approvalTicket": "ticket-1",
                      "grant": {
                        "kind": "computer_migrate",
                        "machineName": "studio-mac",
                        "hostname": "studio",
                        "os": "macOS",
                        "handover": {
                          "serverId": "srv-1",
                          "serverName": "Home",
                          "computerName": "studio-mac",
                          "registrantMoves": true
                        }
                      }
                    }
                    """.trimIndent()
                )
            )
        assertEquals(DeviceLoginGrantKind.ComputerMigrate, request.grant?.kind)
        // Handover-bound grants carry no servers list: Add Computer offers the device login page instead.
        assertNull(request.addComputerTarget("srv-1"))
    }

    private fun human(id: String, name: String, handle: String = name.lowercase(), role: String, status: String = "active") =
        MemberProfile(
            id = MemberId(id),
            displayName = name,
            handle = handle,
            kind = MemberKind.Human,
            serverRole = role,
            membershipStatus = status,
        )

    private data class Call(val method: String, val path: String, val body: String?)

    private class FakeHandoverNetwork(
        private val responses: ArrayDeque<HomeNetworkResponse>,
    ) : RaftNetworkClient {
        val calls = mutableListOf<Call>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            calls += Call(method, path, bodyText)
            return responses.removeFirst()
        }
    }
}
