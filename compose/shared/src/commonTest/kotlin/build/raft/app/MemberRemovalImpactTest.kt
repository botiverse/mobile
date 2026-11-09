package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.MemberRemovalImpact
import build.raft.app.data.MemberRemovalImpactState
import build.raft.app.data.NetworkMemberRemovalImpactRepository
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.holdsConfirm
import build.raft.app.data.parseMemberRemovalImpact
import build.raft.app.data.toMemberRemovalImpactState
import build.raft.app.model.ServerId
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * botiverse/slock #13547: `GET /servers/:id/members/:memberId/removal-impact` read by the Remove
 * member and Leave server confirmations, and how its names read (web MemberRemovalImpactNote).
 */
class MemberRemovalImpactTest {
    @Test
    fun theRoutesAnswerDecodes() {
        assertEquals(
            MemberRemovalImpact(computers = 4, agents = 6, othersAgents = 2, computerNames = listOf("Alpha", "Beta", "Gamma")),
            parseMemberRemovalImpact("""{"computers":4,"agents":6,"othersAgents":2,"computerNames":["Alpha","Beta","Gamma"," "],"extra":1}""")
        )
        assertNull(parseMemberRemovalImpact("""{"agents":1}"""))
        assertNull(parseMemberRemovalImpact("not json"))
        assertNull(parseMemberRemovalImpact(null))
    }

    @Test
    fun theReadAsksTheRouteAndAFailedOrHungReadIsFailed() = runPortableCoroutineTest {
        val network = FakeImpactNetwork(HomeNetworkResponse(200, """{"computers":1,"agents":2,"othersAgents":0,"computerNames":["Studio"]}"""))
        val repository = NetworkMemberRemovalImpactRepository(network)
        val ready = repository.removalImpact(ServerId("server-1"), "user-2")
        assertEquals(listOf("servers/server-1/members/user-2/removal-impact"), network.paths)
        assertEquals(
            MemberRemovalImpactState.Ready(MemberRemovalImpact(1, 2, 0, listOf("Studio"))),
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
    fun onlyAReadInFlightHoldsTheConfirm() {
        assertTrue(MemberRemovalImpactState.Loading.holdsConfirm)
        assertFalse(MemberRemovalImpactState.Failed.holdsConfirm)
        assertFalse(MemberRemovalImpactState.None.holdsConfirm)
        assertFalse(MemberRemovalImpactState.Ready(MemberRemovalImpact(0, 0, 0, emptyList())).holdsConfirm)
    }

    @Test
    fun namesBeyondTheThreeTheRouteNamesReadAsOthers() {
        assertEquals(
            listOf("Alpha", "Beta", "Gamma", "others"),
            memberRemovalImpactNameItems(MemberRemovalImpact(5, 0, 0, listOf("Alpha", "Beta", "Gamma")), "others")
        )
        assertEquals(listOf("Alpha", "Beta"), memberRemovalImpactNameItems(MemberRemovalImpact(2, 0, 0, listOf("Alpha", "Beta")), "others"))
    }

    @Test
    fun namesJoinAsAConjunctionList() {
        fun join(items: List<String>) =
            memberRemovalImpactJoin(
                items,
                two = { a, b -> "$a and $b" },
                more = { a, b -> "$a, $b" },
                end = { a, b -> "$a, and $b" },
            )
        assertEquals("", join(emptyList()))
        assertEquals("Alpha", join(listOf("Alpha")))
        assertEquals("Alpha and Beta", join(listOf("Alpha", "Beta")))
        assertEquals("Alpha, Beta, Gamma, and others", join(listOf("Alpha", "Beta", "Gamma", "others")))
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
