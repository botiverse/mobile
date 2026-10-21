package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-contract vectors for the joint-channel routes, pinned to botiverse/slock `staging` @
 * 7d349b509d6511eb922f7870099017fdda0d3f61. Payload shapes follow:
 *  - `packages/server/src/services/channelService.ts` `JointChannelMetadata`, `JointServerMetadata`,
 *    `JointPendingInviteMetadata` and `attachJointChannelMetadata` (L1666-1845);
 *  - `listPendingJointChannelInvites` (L1014) for `GET channels/joint-invites`;
 *  - `packages/server/src/routes/channels.joint.api.test.ts` L986 (`{ ok: true, resentCount: 2 }`),
 *    L3330/L3455/L3614 (invite `code`s) and `routes/channels.ts` `sendJointLimitError` /
 *    `sendJointInviteValidationError` for the error bodies.
 */
class JointChannelsApiTest {
    @Test
    fun hostProjectionParsesConnectedAndPendingServersWithPlans() {
        val connections = assertNotNullValue(parseSharedJointChannelConnections(HOST_PROJECTION))

        assertEquals(ChannelId("ch-host"), connections.channelId)
        assertEquals("partner-room", connections.channelName)
        assertTrue(connections.isJoint)
        assertFalse(connections.archived)
        assertEquals(listOf("srv-host", "srv-target"), connections.servers.map { it.serverId })
        val host = connections.servers[0]
        assertTrue(host.active)
        assertTrue(host.isCurrentServer)
        assertEquals(JointChannelServerPlan.Paid, host.plan)
        assertEquals("host-co", host.slug)
        val target = connections.servers[1]
        assertFalse(target.active)
        assertFalse(target.isCurrentServer)
        assertEquals(JointChannelServerPlan.Free, target.plan)
        assertEquals("Target Co", target.name)
        assertTrue(connections.hasCurrentServerPendingInvites)
        assertFalse(connections.serverLimitReached)
    }

    @Test
    fun participantProjectionWithoutOwnPendingInvitesCannotResend() {
        val connections = assertNotNullValue(parseSharedJointChannelConnections(PARTICIPANT_PROJECTION))

        assertEquals(2, connections.servers.size)
        assertTrue(connections.servers.all { it.active })
        assertFalse(connections.hasCurrentServerPendingInvites)
        assertTrue(connections.archived)
    }

    @Test
    fun legacyPeerFieldsStandInForMissingJointServers() {
        // Web EditChannelDialog falls back to jointPeer* when jointServers is absent (older servers).
        val connections =
            assertNotNullValue(
                parseSharedJointChannelConnections(
                    """{"id":"ch-1","serverId":"srv-host","name":"room","type":"joint",
                    |"jointPeerServerId":"srv-peer","jointPeerServerName":"Peer","jointPeerServerSlug":"peer",
                    |"jointPeerStatus":"pending"}""".trimMargin()
                )
            )

        val peer = connections.servers.single()
        assertEquals("srv-peer", peer.serverId)
        assertEquals("Peer", peer.name)
        assertFalse(peer.active)
        assertNull(peer.plan)
        assertTrue(connections.hasCurrentServerPendingInvites)
    }

    @Test
    fun pendingInvitesListParsesServiceRows() {
        val invites = assertNotNullValue(parseSharedPendingJointInvites(PENDING_INVITES))

        val invite = invites.single()
        assertEquals("0b9c7f8e-1d2a-4c3b-9e8f-7a6b5c4d3e2f", invite.id)
        assertEquals("Host Co", invite.fromServerName)
        assertEquals("host-co", invite.fromServerSlug)
        assertEquals("partner-room", invite.channelName)
        assertEquals("2026-10-28T08:00:00.000Z", invite.expiresAt)
        assertEquals(emptyList(), parseSharedPendingJointInvites("""{"invites":[]}"""))
    }

    @Test
    fun resendDisconnectAndAcceptResponsesParse() {
        assertEquals(2, parseSharedJointResendCount("""{"ok":true,"resentCount":2}"""))
        assertTrue(parseSharedJointDisconnectOk("""{"ok":true}"""))
        val accepted = assertNotNullValue(parseSharedJointAcceptedChannel(PARTICIPANT_PROJECTION))
        assertEquals(ChannelId("ch-target"), accepted.channelId)
        assertEquals("partner-room", accepted.channelName)
    }

    @Test
    fun parsersRejectGuessedWrappersAndAliases() {
        assertNull(parseSharedPendingJointInvites("""{"data":{"invites":[]}}"""))
        assertNull(parseSharedPendingJointInvites("""[{"id":"x"}]"""))
        assertNull(parseSharedJointResendCount("""{"ok":true,"resent_count":2}"""))
        assertNull(parseSharedJointChannelConnections("""{"channel":{"id":"ch-1","type":"joint"}}"""))
        assertNull(parseSharedJointAcceptedChannel("""{"channelId":"ch-1"}"""))
        assertFalse(parseSharedJointDisconnectOk("""{"success":true}"""))
    }

    @Test
    fun inviteBodyCarriesExactServerFields() {
        val body = Json.parseToJsonElement(buildSharedJointChannelInviteBody("partner-co", listOf("@ada", "bo@example.com"))).jsonObject

        assertEquals(setOf("targetServerSlug", "invitedPeople"), body.keys)
        assertEquals("partner-co", body.getValue("targetServerSlug").jsonPrimitive.content)
        assertEquals(listOf("@ada", "bo@example.com"), body.getValue("invitedPeople").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun invitedPeopleAndSlugFollowWebRules() {
        assertEquals(listOf("@ada", "bo@example.com", "cy"), parseJointInvitedPeopleText(" @ada ,bo@example.com\n\n cy ,"))
        assertEquals(JointServerSlugIssue.Required, jointServerSlugIssue(""))
        assertEquals(JointServerSlugIssue.Pattern, jointServerSlugIssue("Partner"))
        assertEquals(JointServerSlugIssue.Pattern, jointServerSlugIssue("1partner"))
        // Reference slugs have no minimum length (shared validateServerSlugReferenceReason).
        assertNull(jointServerSlugIssue("ab"))
        assertNull(jointServerSlugIssue("partner-co-2"))
    }

    @Test
    fun repositoryRoutesUseExactPathsMethodsAndPriorities() = runTest {
        val client =
            RecordingJointNetworkClient { path, _ ->
                when (path) {
                    "channels/ch-host" -> HomeNetworkResponse(200, HOST_PROJECTION)
                    "channels/ch-host/joint-invites" -> HomeNetworkResponse(200, HOST_PROJECTION)
                    "channels/ch-host/joint-invite/resend" -> HomeNetworkResponse(200, """{"ok":true,"resentCount":2}""")
                    "channels/ch-host/disconnect" -> HomeNetworkResponse(200, """{"ok":true}""")
                    "channels/joint-invites" -> HomeNetworkResponse(200, PENDING_INVITES)
                    else -> HomeNetworkResponse(200, PARTICIPANT_PROJECTION)
                }
            }
        val repository = RealJointChannelRepository(client)
        val channel = ChannelId("ch-host")

        assertIs<DataResult.Content<*>>(repository.loadConnections(channel, DataRequestPriority.Visible))
        assertIs<DataResult.Content<*>>(repository.inviteServer(channel, "target-co", listOf("@ada")))
        assertEquals(2, assertIs<DataResult.Content<Int>>(repository.resendInvites(channel)).data)
        assertIs<DataResult.Content<Unit>>(repository.disconnect(channel))
        assertEquals(1, assertIs<DataResult.Content<List<PendingJointInvite>>>(repository.pendingInvites(ServerId("srv-target"), DataRequestPriority.Background)).data.size)
        assertIs<DataResult.Content<JointChannelAccepted>>(repository.acceptInvite(ServerId("srv-target"), "inv-1"))

        assertEquals(
            listOf(
                Triple("GET", "channels/ch-host", DataRequestPriority.Visible),
                Triple("POST", "channels/ch-host/joint-invites", DataRequestPriority.Foreground),
                Triple("POST", "channels/ch-host/joint-invite/resend", DataRequestPriority.Foreground),
                Triple("POST", "channels/ch-host/disconnect", DataRequestPriority.Foreground),
                Triple("GET", "channels/joint-invites", DataRequestPriority.Background),
                Triple("POST", "channels/joint-invites/inv-1/accept", DataRequestPriority.Foreground),
            ),
            client.calls.map { Triple(it.method, it.path, it.priority) },
        )
        assertEquals(listOf(null, null, null, null, ServerId("srv-target"), ServerId("srv-target")), client.calls.map { it.serverIdOverride })
        val inviteBody = Json.parseToJsonElement(assertNotNullValue(client.calls[1].body)).jsonObject
        assertEquals("target-co", inviteBody.getValue("targetServerSlug").jsonPrimitive.content)
    }

    @Test
    fun failuresKeepServerCodeAndReason() = runTest {
        data class Case(val status: Int, val body: String, val code: String, val blocked: Boolean, val message: String?)
        listOf(
            // sendJointLimitError: 403 { error, code }.
            Case(403, """{"error":"This joint channel already has 2 free servers; one side needs to upgrade.","code":"joint_free_server_limit"}""", JOINT_FREE_SERVER_LIMIT, true, "This joint channel already has 2 free servers; one side needs to upgrade."),
            Case(403, """{"error":"Joint channels are limited to 30 servers.","code":"joint_server_limit"}""", JOINT_SERVER_LIMIT, true, "Joint channels are limited to 30 servers."),
            // federateChannels gate: 403 { error } only.
            Case(403, """{"error":"Only admins can invite servers to joint channels"}""", "joint_forbidden", true, "Only admins can invite servers to joint channels"),
            // sendJointInviteValidationError: 400 { error, code, targetServerSlug?, invitee? }.
            Case(400, """{"error":"Invited person not found in target server: @ghost","code":"joint_invitee_not_found","targetServerSlug":"target-co","invitee":"@ghost"}""", JOINT_INVITEE_NOT_FOUND, false, "Invited person not found in target server: @ghost"),
            Case(400, """{"error":"Target server not found","code":"joint_target_server_invalid","targetServerSlug":"nope"}""", JOINT_TARGET_SERVER_INVALID, false, "Target server not found"),
            // Resend with nothing pending: 409 { error }.
            Case(409, """{"error":"No pending joint channel invite found"}""", "joint_http_409", false, "No pending joint channel invite found"),
            Case(500, """{"error":"Failed to disconnect joint channel"}""", "server_unavailable", false, "Failed to disconnect joint channel"),
        ).forEach { case ->
            val result = RealJointChannelRepository(RecordingJointNetworkClient { _, _ -> HomeNetworkResponse(case.status, case.body) })
                .inviteServer(ChannelId("ch-host"), "target-co", listOf("@ghost"))
            assertEquals(case.code, result.jointFailureCode(), "status=${case.status}")
            if (case.blocked) {
                assertEquals(case.message, assertIs<DataResult.NoPermission>(result).blocker.serverMessage)
            } else {
                assertEquals(case.message, assertIs<DataResult.Error>(result).error.serverMessage)
            }
        }
        val unauthenticated =
            RealJointChannelRepository(RecordingJointNetworkClient { _, _ -> HomeNetworkResponse(401, """{"error":"Unauthorized"}""") })
                .resendInvites(ChannelId("ch-host"))
        assertEquals("auth_required", unauthenticated.jointFailureCode())
    }

    private fun <T> assertNotNullValue(value: T?): T {
        kotlin.test.assertNotNull(value)
        return value
    }

    private companion object {
        /** `GET channels/:id` for the host projection after inviting one target (attachJointChannelMetadata). */
        val HOST_PROJECTION =
            """
            {"id":"ch-host","serverId":"srv-host","name":"partner-room","description":null,"type":"joint",
             "createdAt":"2026-10-21T08:00:00.000Z","archivedAt":null,"joined":true,
             "jointChannelId":"jc-1","jointRole":"host",
             "jointPeerServerId":"srv-target","jointPeerServerName":"Target Co","jointPeerServerSlug":"target-co",
             "jointPeerStatus":"pending",
             "jointServers":[
               {"serverId":"srv-host","serverName":"Host Co","serverSlug":"host-co","role":"host","status":"active","isCurrentServer":true,"plan":"paid"},
               {"serverId":"srv-target","serverName":"Target Co","serverSlug":"target-co","role":null,"status":"pending","plan":"free"}
             ],
             "jointPendingInvites":[
               {"id":"inv-1","fromServerId":"srv-host","toServerId":"srv-target","serverName":"Target Co","serverSlug":"target-co","invitedUserId":"user-t","status":"pending"}
             ],
             "jointBillingLocked":false,"jointOverLimitGraceEndsAt":null,
             "jointInvites":[{"id":"inv-1"}],"jointInvite":{"id":"inv-1"}}
            """.trimIndent()

        /** The accepted participant projection (`POST channels/joint-invites/:id/accept`), later archived. */
        val PARTICIPANT_PROJECTION =
            """
            {"id":"ch-target","serverId":"srv-target","name":"partner-room","description":null,"type":"joint",
             "archivedAt":"2026-10-22T08:00:00.000Z","joined":true,"jointChannelId":"jc-1","jointRole":"participant",
             "jointPeerServerId":"srv-host","jointPeerServerName":"Host Co","jointPeerServerSlug":"host-co","jointPeerStatus":"active",
             "jointServers":[
               {"serverId":"srv-host","serverName":"Host Co","serverSlug":"host-co","role":"host","status":"active","isCurrentServer":false,"plan":"paid"},
               {"serverId":"srv-target","serverName":"Target Co","serverSlug":"target-co","role":"participant","status":"active","isCurrentServer":true,"plan":"free"}
             ],
             "jointPendingInvites":[],"jointBillingLocked":false,"jointOverLimitGraceEndsAt":null}
            """.trimIndent()

        /** `GET channels/joint-invites` row (listPendingJointChannelInvites select list). */
        val PENDING_INVITES =
            """
            {"invites":[{"id":"0b9c7f8e-1d2a-4c3b-9e8f-7a6b5c4d3e2f","jointChannelId":"jc-1","fromServerId":"srv-host",
              "fromServerName":"Host Co","fromServerSlug":"host-co","channelName":"partner-room","channelDescription":null,
              "invitedByUserId":"user-h","invitedUserId":"user-t","expiresAt":"2026-10-28T08:00:00.000Z",
              "createdAt":"2026-10-21T08:00:00.000Z"}]}
            """.trimIndent()
    }
}

internal class RecordingJointNetworkClient(
    private val respond: (path: String, method: String) -> HomeNetworkResponse,
) : RaftNetworkClient {
    data class Call(
        val priority: DataRequestPriority,
        val path: String,
        val method: String,
        val serverIdOverride: ServerId?,
        val body: String?,
    )

    val calls = mutableListOf<Call>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
    ): HomeNetworkResponse {
        calls += Call(priority, path, method, serverIdOverride, bodyText)
        return respond(path, method)
    }
}
