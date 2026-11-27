package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.MEMBER_HAS_COMPUTER_REFUSAL_CODE
import build.raft.app.data.MemberAttachedComputer
import build.raft.app.data.MemberHandedOverComputer
import build.raft.app.data.MemberRemovalImpact
import build.raft.app.data.MemberRemovalImpactState
import build.raft.app.data.NetworkMemberRemovalImpactRepository
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.ScreenError
import build.raft.app.data.blockingComputers
import build.raft.app.data.buildSettingsRemoveServerMemberBody
import build.raft.app.data.handedOverComputers
import build.raft.app.data.holdsConfirm
import build.raft.app.data.holdsLeaveConfirm
import build.raft.app.data.isMemberHasComputerRefusal
import build.raft.app.data.isMemberHasComputerRefusalCode
import build.raft.app.data.parseMemberRemovalImpact
import build.raft.app.data.toMemberRemovalImpactState
import build.raft.app.members.memberRemovalImpactNameItems
import build.raft.app.model.ServerId
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * botiverse/slock #13547, #16386, #16435: `GET /servers/:id/members/:memberId/removal-impact`,
 * blocking Computers, handed-over Computers, `DELETE` body with `{ revokeComputers, computerIds }`,
 * and `409 member_has_computer` refusal handling.
 */
class MemberRemovalImpactTest {
    @Test
    fun theRoutesAnswerDecodes() {
        assertEquals(
            MemberRemovalImpact(
                computers = 4,
                agents = 6,
                othersAgents = 2,
                computerNames = listOf("Alpha", "Beta", "Gamma"),
                blocking = false,
                attachedComputers = emptyList(),
                handedOverComputers = emptyList(),
            ),
            parseMemberRemovalImpact("""{"computers":4,"agents":6,"othersAgents":2,"computerNames":["Alpha","Beta","Gamma"," "],"extra":1}""")
        )
        assertNull(parseMemberRemovalImpact("""{"agents":1}"""))
        assertNull(parseMemberRemovalImpact("not json"))
        assertNull(parseMemberRemovalImpact(null))
    }

    @Test
    fun blockingAttachedAndHandedOverComputersDecode() {
        val parsed =
            parseMemberRemovalImpact(
                """
                {
                  "computers": 2,
                  "agents": 4,
                  "othersAgents": 2,
                  "computerNames": ["studio-mac", "build-box"],
                  "blocking": true,
                  "attachedComputers": [
                    {"id": "c-1", "machineId": "m-1", "name": "studio-mac", "agentCount": 3, "othersAgentCount": 2},
                    {"id": "c-2", "machineId": null, "name": "build-box", "agentCount": 1, "othersAgentCount": 0},
                    {"id": " ", "machineId": "m-bad", "name": "ignored"}
                  ],
                  "handedOverComputers": [
                    {"id": "c-9", "machineId": "m-9", "name": "old-laptop"},
                    {"id": "c-bad", "machineId": "m-x", "name": " "}
                  ]
                }
                """.trimIndent()
            )
        assertEquals(
            MemberRemovalImpact(
                computers = 2,
                agents = 4,
                othersAgents = 2,
                computerNames = listOf("studio-mac", "build-box"),
                blocking = true,
                attachedComputers =
                    listOf(
                        MemberAttachedComputer(id = "c-1", machineId = "m-1", name = "studio-mac", agentCount = 3, othersAgentCount = 2),
                        MemberAttachedComputer(id = "c-2", machineId = null, name = "build-box", agentCount = 1, othersAgentCount = 0),
                    ),
                handedOverComputers =
                    listOf(
                        MemberHandedOverComputer(id = "c-9", machineId = "m-9", name = "old-laptop"),
                    ),
            ),
            parsed,
        )
    }

    @Test
    fun theReadAsksTheRouteAndAFailedOrHungReadIsFailed() = runPortableCoroutineTest {
        val network =
            FakeImpactNetwork(
                HomeNetworkResponse(
                    200,
                    """{"computers":1,"agents":2,"othersAgents":0,"computerNames":["Studio"],"blocking":true,"attachedComputers":[{"id":"c-1","machineId":"m-1","name":"Studio","agentCount":2,"othersAgentCount":0}]}""",
                )
            )
        val repository = NetworkMemberRemovalImpactRepository(network)
        val ready = repository.removalImpact(ServerId("server-1"), "user-2")
        assertEquals(listOf("servers/server-1/members/user-2/removal-impact"), network.paths)
        assertEquals(
            MemberRemovalImpactState.Ready(
                MemberRemovalImpact(
                    computers = 1,
                    agents = 2,
                    othersAgents = 0,
                    computerNames = listOf("Studio"),
                    blocking = true,
                    attachedComputers = listOf(MemberAttachedComputer("c-1", "m-1", "Studio", 2, 0)),
                )
            ),
            ready.toMemberRemovalImpactState()
        )

        val refused = NetworkMemberRemovalImpactRepository(FakeImpactNetwork(HomeNetworkResponse(403, """{"error":"no"}""")))
        assertEquals(MemberRemovalImpactState.Failed, refused.removalImpact(ServerId("server-1"), "user-2").toMemberRemovalImpactState())

        // A read that never answers gives up (web: 10 s) and leaves the confirm available.
        val hung = NetworkMemberRemovalImpactRepository(FakeImpactNetwork(hang = true), timeoutMillis = 50)
        assertIs<DataResult.Error>(hung.removalImpact(ServerId("server-1"), "user-2"))
        Unit
    }

    @Test
    fun confirmHoldRulesMatchWebForRemoveAndLeave() {
        val none = MemberRemovalImpactState.Ready(MemberRemovalImpact(0, 0, 0, emptyList()))
        val legacyOffline = MemberRemovalImpactState.Ready(MemberRemovalImpact(1, 2, 1, listOf("Legacy"), blocking = false))
        val handedOverOnly =
            MemberRemovalImpactState.Ready(
                MemberRemovalImpact(
                    computers = 0,
                    agents = 0,
                    othersAgents = 0,
                    computerNames = emptyList(),
                    blocking = false,
                    handedOverComputers = listOf(MemberHandedOverComputer("c-9", "m-9", "old-mac")),
                )
            )
        val blocking =
            MemberRemovalImpactState.Ready(
                MemberRemovalImpact(
                    computers = 1,
                    agents = 3,
                    othersAgents = 2,
                    computerNames = listOf("studio-mac"),
                    blocking = true,
                    attachedComputers = listOf(MemberAttachedComputer("c-1", "m-1", "studio-mac", 3, 2)),
                )
            )

        // Remove confirm waits only while loading (blocking offers one-step disconnect + remove).
        assertTrue(MemberRemovalImpactState.Loading.holdsConfirm)
        assertFalse(MemberRemovalImpactState.Failed.holdsConfirm)
        assertFalse(MemberRemovalImpactState.None.holdsConfirm)
        assertFalse(none.holdsConfirm)
        assertFalse(legacyOffline.holdsConfirm)
        assertFalse(handedOverOnly.holdsConfirm)
        assertFalse(blocking.holdsConfirm)

        // Leave confirm waits while loading AND stays disabled while blocking Computers exist.
        assertTrue(MemberRemovalImpactState.Loading.holdsLeaveConfirm)
        assertTrue(blocking.holdsLeaveConfirm)
        assertFalse(MemberRemovalImpactState.Failed.holdsLeaveConfirm)
        assertFalse(MemberRemovalImpactState.None.holdsLeaveConfirm)
        assertFalse(none.holdsLeaveConfirm)
        assertFalse(legacyOffline.holdsLeaveConfirm)
        assertFalse(handedOverOnly.holdsLeaveConfirm)

        assertEquals(listOf("c-1"), blocking.blockingComputers().map { it.id })
        assertEquals(emptyList(), legacyOffline.blockingComputers())
        assertEquals(listOf("old-mac"), handedOverOnly.handedOverComputers().map { it.name })
    }

    @Test
    fun removeMemberRevokeBodyMatchesServerContract() {
        assertNull(buildSettingsRemoveServerMemberBody(emptyList()))
        assertNull(buildSettingsRemoveServerMemberBody(listOf("", "   ")))
        assertEquals(
            """{"revokeComputers":true,"computerIds":["c-1","c-2"]}""",
            buildSettingsRemoveServerMemberBody(listOf(" c-1 ", "", "c-2")),
        )
    }

    @Test
    fun memberHasComputerRefusalDetectionMatchesCode() {
        assertTrue(MEMBER_HAS_COMPUTER_REFUSAL_CODE.isMemberHasComputerRefusalCode())
        assertTrue(" member_has_computer ".isMemberHasComputerRefusalCode())
        assertFalse("insufficient_role".isMemberHasComputerRefusalCode())
        assertFalse((null as String?).isMemberHasComputerRefusalCode())

        val conflict =
            DataResult.Error(
                ScreenError(
                    code = "http_409",
                    retryable = true,
                    serverMessage = "You attached 1 Computer on this server.",
                    serverCode = MEMBER_HAS_COMPUTER_REFUSAL_CODE,
                )
            )
        val otherError = DataResult.Error(ScreenError(code = "http_500", retryable = true, serverCode = "internal_error"))
        assertTrue(conflict.isMemberHasComputerRefusal())
        assertFalse(otherError.isMemberHasComputerRefusal())
        assertFalse(DataResult.Content(Unit).isMemberHasComputerRefusal())
    }

    @Test
    fun namesBeyondTheThreeTheRouteNamesReadAsOthers() {
        assertEquals(
            listOf("Alpha", "Beta", "Gamma", "others"),
            memberRemovalImpactNameItems(MemberRemovalImpact(5, 0, 0, listOf("Alpha", "Beta", "Gamma")), "others")
        )
        assertEquals(listOf("Alpha", "Beta"), memberRemovalImpactNameItems(MemberRemovalImpact(2, 0, 0, listOf("Alpha", "Beta")), "others"))
        assertEquals(
            listOf("A", "B", "C", "others"),
            memberRemovalImpactNameItems(listOf("A", "B", "C", "D"), 4, "others"),
        )
    }

    private class FakeImpactNetwork(
        private val response: HomeNetworkResponse = HomeNetworkResponse(200, "{}"),
        private val hang: Boolean = false,
    ) : RaftNetworkClient {
        val paths = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            paths += path
            if (hang) awaitCancellation()
            return response
        }
    }
}
