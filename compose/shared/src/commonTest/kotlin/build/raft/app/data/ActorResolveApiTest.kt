package build.raft.app.data

import build.raft.app.model.MemberKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /api/actors/resolve` wire vectors. Response shape from botiverse/slock 200dbbf09
 * (#11193) `packages/server/src/routes/actors.api.test.ts` ("resolves same-server humans and
 * agents ..." and "resolves joint-channel peers ...") and `packages/shared/src/actorResolveContract.ts`.
 */
class ActorResolveApiTest {
    private val server = "6f1c2a0e-1111-4c4c-8a8a-000000000001"
    private val peerServer = "6f1c2a0e-2222-4c4c-8a8a-000000000002"
    private val colleague = "0b6f7d1e-3333-4e4e-9b9b-000000000003"
    private val localAgent = "0b6f7d1e-4444-4e4e-9b9b-000000000004"
    private val peerHuman = "0b6f7d1e-5555-4e4e-9b9b-000000000005"
    private val peerAgent = "0b6f7d1e-6666-4e4e-9b9b-000000000006"
    private val hash = "a".repeat(64)

    private val serverResponse = """
        {"actors":[
          {"kind":"human","id":"$colleague","homeServerId":"$server","name":"colleague","displayName":"Colleague","avatarUrl":null,"gravatarHash":"$hash"},
          {"kind":"agent","id":"$localAgent","homeServerId":"$server","name":"local-1a2b3c4d","displayName":"Local Agent","avatarUrl":null,"gravatarHash":null},
          {"kind":"human","id":"$peerHuman","homeServerId":"$peerServer","name":"peer","displayName":"Peer","avatarUrl":"https://example.test/peer.png","gravatarHash":null},
          {"kind":"agent","id":"$peerAgent","homeServerId":"$peerServer","name":"peer-9f8e7d6c","displayName":null,"avatarUrl":null,"gravatarHash":null}
        ]}
    """.trimIndent()

    @Test
    fun parsesTheServerAnswerForSameServerAndJointPeers() {
        val actors = checkNotNull(parseSharedActorResolveResponse(serverResponse, server))

        assertEquals(listOf(colleague, localAgent, peerHuman, peerAgent), actors.map { it.id })
        val colleagueRow = actors[0]
        assertEquals(MemberKind.Human, colleagueRow.kind)
        assertEquals(server, colleagueRow.homeServerId)
        assertEquals("Colleague", colleagueRow.displayName)
        assertEquals(hash, colleagueRow.gravatarHash)
        val peer = actors[2]
        assertEquals(peerServer, peer.homeServerId)
        assertEquals("https://example.test/peer.png", peer.avatarUrl)
        assertNull(peer.gravatarHash)
        val agent = actors[3]
        assertEquals(MemberKind.Agent, agent.kind)
        assertEquals("peer-9f8e7d6c", agent.name)
        assertNull(agent.displayName)
        assertNull(agent.avatarUrl)
    }

    @Test
    fun keepsAGravatarHashOnlyForAPersonOfTheAskedServer() {
        // Asked through the peer server: the colleague is another organization's person there.
        val actors = checkNotNull(parseSharedActorResolveResponse(serverResponse, peerServer))
        assertNull(actors.first { it.id == colleague }.gravatarHash)
        // An agent never keeps one, even if a server sent it.
        val agentWithHash =
            """{"actors":[{"kind":"agent","id":"$localAgent","homeServerId":"$server","name":"a","displayName":null,"avatarUrl":null,"gravatarHash":"$hash"}]}"""
        assertNull(checkNotNull(parseSharedActorResolveResponse(agentWithHash, server)).single().gravatarHash)
    }

    @Test
    fun dropsMalformedEntriesAndRejectsAGuessedEnvelope() {
        val body = """
            {"actors":[
              {"kind":"system","id":"$colleague","homeServerId":"$server","name":"x","displayName":null,"avatarUrl":null,"gravatarHash":null},
              {"kind":"human","id":"$colleague","name":"no home","displayName":null,"avatarUrl":null,"gravatarHash":null},
              {"kind":"human","id":"$colleague","homeServerId":"$server","name":7,"displayName":null,"avatarUrl":null,"gravatarHash":null},
              {"kind":"agent","id":"$peerAgent","homeServerId":"$peerServer","name":"kept","displayName":null,"avatarUrl":null,"gravatarHash":null,"extra":true}
            ]}
        """.trimIndent()
        assertEquals(listOf(peerAgent), checkNotNull(parseSharedActorResolveResponse(body, server)).map { it.id })
        assertNull(parseSharedActorResolveResponse("""{"items":[]}""", server))
        assertNull(parseSharedActorResolveResponse("""{"data":{"actors":[]}}""", server))
        assertNull(parseSharedActorResolveResponse("not json", server))
        assertEquals(emptyList(), parseSharedActorResolveResponse("""{"actors":[]}""", server))
    }

    @Test
    fun requestBodyCarriesKindAndIdPerRef() {
        val body =
            buildSharedActorResolveBody(
                listOf(ActorResolveRef(MemberKind.Human, peerHuman), ActorResolveRef(MemberKind.Agent, peerAgent))
            )
        val actors = Json.parseToJsonElement(body).jsonObject.getValue("actors").jsonArray
        assertEquals(listOf("human", "agent"), actors.map { it.jsonObject.getValue("kind").jsonPrimitive.content })
        assertEquals(listOf(peerHuman, peerAgent), actors.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(setOf("kind", "id"), actors.first().jsonObject.keys)
    }

    @Test
    fun statusesMapToOutcomes() {
        val ok = HomeNetworkResponse(code = 200, bodyText = serverResponse).toActorResolveOutcome(server)
        assertEquals(4, (ok as ActorResolveOutcome.Resolved).actors.size)
        // An older server without the route, or a guest: off for the session.
        listOf(403, 404, 405, 501).forEach { code ->
            assertEquals(ActorResolveOutcome.Unavailable, HomeNetworkResponse(code, bodyText = null).toActorResolveOutcome(server), "code=$code")
        }
        assertEquals(ActorResolveOutcome.Failed(500), HomeNetworkResponse(500, bodyText = null).toActorResolveOutcome(server))
        assertEquals(ActorResolveOutcome.Failed(429), HomeNetworkResponse(429, bodyText = null).toActorResolveOutcome(server))
        assertEquals(ActorResolveOutcome.Failed(200), HomeNetworkResponse(200, bodyText = "{}").toActorResolveOutcome(server))
    }

    @Test
    fun onlyUuidIdsAreSendable() {
        assertTrue(isActorResolveId(peerHuman))
        assertTrue(isActorResolveId(peerHuman.uppercase()))
        assertFalse(isActorResolveId("system"))
        assertFalse(isActorResolveId("agent-1"))
        assertFalse(isActorResolveId(""))
    }
}
