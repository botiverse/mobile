package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `POST channels` with `visibility: "joint"`, pinned to botiverse/slock `staging` @
 * 7d349b509d6511eb922f7870099017fdda0d3f61 (`packages/server/src/routes/channels.ts`
 * `channelRouter.post("/")` + `parseJointInviteRequests`; web `CreateJointChannelDialog.tsx`).
 */
class CreateJointChannelApiTest {
    @Test
    fun createBodyUsesTheJointInvitesArrayShape() {
        val body =
            Json.parseToJsonElement(
                buildSharedCreateJointChannelBody(
                    name = "launch",
                    description = null,
                    agentIds = listOf("agent-1"),
                    userIds = listOf("user-2"),
                    invites = listOf(JointInviteTarget("partner-co", listOf("@ada")), JointInviteTarget("third-co", listOf("@cy", "dee@example.com"))),
                )
            ).jsonObject

        assertEquals(setOf("name", "description", "visibility", "agentIds", "userIds", "jointInvites"), body.keys)
        assertEquals("joint", body.getValue("visibility").jsonPrimitive.content)
        assertEquals(JsonNull, body.getValue("description"))
        val invites = body.getValue("jointInvites").jsonArray.map { it.jsonObject }
        assertEquals(listOf("partner-co", "third-co"), invites.map { it.getValue("targetServerSlug").jsonPrimitive.content })
        assertEquals(listOf("@cy", "dee@example.com"), invites[1].getValue("invitedPeople").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun createOutcomesFollowTheRouteResponses() = runTest {
        fun repo(status: Int, body: String) = RealJointChannelRepository(RecordingJointNetworkClient { _, _ -> HomeNetworkResponse(status, body) })
        suspend fun create(status: Int, body: String) =
            repo(status, body).createJointChannel("launch", null, emptyList(), emptyList(), listOf(JointInviteTarget("partner-co", listOf("@ada"))))

        val created = assertIs<CreateJointChannelOutcome.Created>(
            create(200, """{"id":"ch-new","serverId":"srv-host","name":"launch","type":"joint","jointServers":[],"jointPendingInvites":[],"jointInvites":[],"jointInvite":null}""")
        )
        assertEquals(ChannelId("ch-new"), created.channel.channelId)

        // ArchivedNameCollisionError branch.
        val collision = assertIs<CreateJointChannelOutcome.ArchivedNameCollision>(
            create(
                409,
                """{"error":"Channel name \"launch\" is held by an archived channel","code":"archived_name_collision","archivedChannelId":"ch-old","archivedChannelName":"launch","archivedChannelType":"joint","canUnarchiveArchivedChannel":true}""",
            )
        )
        assertEquals(ChannelId("ch-old"), collision.archivedChannelId)
        assertEquals(true, collision.canUnarchive)
        // A regular channel holding the name is not offered as a joint unarchive.
        val regularHolder = assertIs<CreateJointChannelOutcome.Failed>(
            create(409, """{"error":"Channel name \"launch\" is held by an archived channel","code":"archived_name_collision","archivedChannelId":"ch-old","archivedChannelName":"launch","archivedChannelType":"channel","canUnarchiveArchivedChannel":true}""")
        )
        assertEquals(ARCHIVED_NAME_COLLISION, regularHolder.details?.code)

        // sendJointLimitError with blockingChannel.
        val hostLimit = assertIs<CreateJointChannelOutcome.Failed>(
            create(403, """{"error":"limit","code":"joint_channel_free_limit_reached","blockingChannel":{"name":"old-room"}}""")
        )
        assertEquals(JOINT_CHANNEL_FREE_LIMIT_REACHED, hostLimit.result.jointFailureCode())
        assertEquals("old-room", hostLimit.details?.blockingChannelName)

        // sendJointInviteValidationError carries the target slug and invitee.
        val invitee = assertIs<CreateJointChannelOutcome.Failed>(
            create(400, """{"error":"Invited person not found in target server: @ghost","code":"joint_invitee_not_found","targetServerSlug":"partner-co","invitee":"@ghost","inviteeIndex":0}""")
        )
        assertEquals("partner-co", invitee.details?.targetServerSlug)
        assertEquals("@ghost", invitee.details?.invitee)

        val federate = assertIs<CreateJointChannelOutcome.Failed>(create(403, """{"error":"You do not have permission to federate channels"}"""))
        assertEquals("joint_forbidden", federate.result.jointFailureCode())
    }

    @Test
    fun createAndUnarchiveUseExactRoutes() = runTest {
        val client = RecordingJointNetworkClient { _, _ -> HomeNetworkResponse(200, """{"id":"ch-old","name":"launch","type":"joint"}""") }
        val repository = RealJointChannelRepository(client)

        repository.createJointChannel("launch", "why", emptyList(), emptyList(), listOf(JointInviteTarget("partner-co", listOf("@ada"))))
        assertIs<DataResult.Content<JointChannelAccepted>>(repository.unarchiveChannel(ChannelId("ch-old")))

        assertEquals(
            listOf(
                Triple("POST", "channels", DataRequestPriority.Foreground),
                Triple("POST", "channels/ch-old/unarchive", DataRequestPriority.Foreground),
            ),
            client.calls.map { Triple(it.method, it.path, it.priority) },
        )
    }
}
