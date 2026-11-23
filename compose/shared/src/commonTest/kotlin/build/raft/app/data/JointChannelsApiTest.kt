package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
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
 *  - `listPendingJointChannelInvites` (L1014) for `GET channels/joint-invites`.
 * The accept refusals are `routes/channels.ts` `POST /joint-invites/:inviteId/accept` with
 * `sendJointLimitError` (`refuse()`, `lib/refusal.ts` `refusalBody`) and
 * `services/jointChannelLimitService.ts` `hostPlanLimitError`, botiverse/slock `staging` @
 * 5881c7b53ca7b447e4d4ac391390c68453cba952.
 */
class JointChannelsApiTest {
    /**
     * Roles and the channel's own capability, botiverse/slock `staging` @
     * f3c3905046d16cd4a7225a533a071f667b687647: `JointChannelMetadata.jointRole` and
     * `JointServerMetadata.role` are `"host" | "participant" | null` (pending rows `null`,
     * `attachJointChannelMetadata`); `channelCapabilities` is every
     * `CHANNEL_MANAGEMENT_CAPABILITIES` key (`withHumanChannelAuthorization`).
     */
    @Test
    fun rolesAndChannelFederateCapabilityParseFromTheChannelRow() {
        val host = assertNotNullValue(parseSharedJointChannelConnections(HOST_PROJECTION))
        assertEquals(JointChannelRole.Host, host.jointRole)
        assertEquals(listOf(JointChannelRole.Host, null), host.servers.map { it.role })
        assertEquals(true, host.federateChannels)

        val participant = assertNotNullValue(parseSharedJointChannelConnections(PARTICIPANT_PROJECTION))
        assertEquals(JointChannelRole.Participant, participant.jointRole)
        assertEquals(listOf(JointChannelRole.Host, JointChannelRole.Participant), participant.servers.map { it.role })
        // No channelCapabilities on the payload: unknown, never a server-wide fallback.
        assertNull(participant.federateChannels)

        // A role the client does not know reads as none.
        val unknown =
            assertNotNullValue(
                parseSharedJointChannelConnections(
                    """{"id":"ch-1","name":"room","type":"joint","jointRole":"owner",
                    |"channelCapabilities":{"federateChannels":false},
                    |"jointServers":[{"serverId":"s1","serverName":"S","serverSlug":"s","serverAvatarUrl":"/api/avatars/server-s1/logo.webp","role":"admin","status":"active","plan":"free"}]}""".trimMargin()
                )
            )
        assertNull(unknown.jointRole)
        assertNull(unknown.servers.single().role)
        assertEquals("/api/avatars/server-s1/logo.webp", unknown.servers.single().avatarUrl)
        assertEquals(false, unknown.federateChannels)
    }

    @Test
    fun hostDisconnectsAnotherServerThroughItsOwnRoute() = runTest {
        val client = RecordingJointNetworkClient { _, _ -> HomeNetworkResponse(200, """{"ok":true}""") }

        assertIs<DataResult.Content<Unit>>(RealJointChannelRepository(client).disconnectServer(ChannelId("ch-host"), " srv-target "))

        assertEquals(
            listOf(Triple("POST", "channels/ch-host/joint-servers/srv-target/disconnect", DataRequestPriority.Foreground)),
            client.calls.map { Triple(it.method, it.path, it.priority) },
        )
        assertNull(client.calls.single().serverIdOverride)
    }

    @Test
    fun hostDisconnectRefusalsKeepTheServerCode() = runTest {
        // routes/channels.jointHostDisconnect.api.test.ts (same SHA): the codes the route answers with.
        data class Case(val status: Int, val body: String, val code: String)
        listOf(
            Case(404, """{"error":"That server is not connected to this channel.","code":"server_not_connected"}""", "server_not_connected"),
            Case(409, """{"error":"This channel has guests. Remove them before disconnecting this server.","code":"guests_present"}""", "guests_present"),
            Case(400, """{"error":"The host server can't disconnect from its own channel. Archive the channel instead.","code":"host_cannot_be_disconnected"}""", "host_cannot_be_disconnected"),
            Case(403, """{"error":"Only admins of the host server can disconnect another server.","code":"forbidden"}""", "forbidden"),
            // Flag off for the host: the route answers as an unknown channel.
            Case(404, """{"error":"Channel not found"}""", "joint_http_404"),
        ).forEach { case ->
            val result =
                RealJointChannelRepository(RecordingJointNetworkClient { _, _ -> HomeNetworkResponse(case.status, case.body) })
                    .disconnectServer(ChannelId("ch-host"), "srv-target")
            assertEquals(case.code, result.jointFailureCode(), "status=${case.status}")
        }
    }

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
    }

    @Test
    fun participantProjectionHasNoPendingInvitesOfItsOwn() {
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
    fun disconnectAndAcceptResponsesParse() {
        assertTrue(parseSharedJointDisconnectOk("""{"ok":true}"""))
        val accepted = assertNotNullValue(parseSharedJointAcceptedChannel(PARTICIPANT_PROJECTION))
        assertEquals(ChannelId("ch-target"), accepted.channelId)
        assertEquals("partner-room", accepted.channelName)
    }

    @Test
    fun parsersRejectGuessedWrappersAndAliases() {
        assertNull(parseSharedPendingJointInvites("""{"data":{"invites":[]}}"""))
        assertNull(parseSharedPendingJointInvites("""[{"id":"x"}]"""))
        assertNull(parseSharedJointChannelConnections("""{"channel":{"id":"ch-1","type":"joint"}}"""))
        assertNull(parseSharedJointAcceptedChannel("""{"channelId":"ch-1"}"""))
        assertFalse(parseSharedJointDisconnectOk("""{"success":true}"""))
    }

    @Test
    fun repositoryRoutesUseExactPathsMethodsAndPriorities() = runTest {
        val client =
            RecordingJointNetworkClient { path, _ ->
                when (path) {
                    "channels/ch-host" -> HomeNetworkResponse(200, HOST_PROJECTION)
                    "channels/ch-host/disconnect" -> HomeNetworkResponse(200, """{"ok":true}""")
                    "channels/joint-invites" -> HomeNetworkResponse(200, PENDING_INVITES)
                    else -> HomeNetworkResponse(200, PARTICIPANT_PROJECTION)
                }
            }
        val repository = RealJointChannelRepository(client)
        val channel = ChannelId("ch-host")

        assertIs<DataResult.Content<*>>(repository.loadConnections(channel, DataRequestPriority.Visible))
        assertIs<DataResult.Content<Unit>>(repository.disconnect(channel))
        assertEquals(1, assertIs<DataResult.Content<List<PendingJointInvite>>>(repository.pendingInvites(ServerId("srv-target"), DataRequestPriority.Background)).data.size)
        assertIs<DataResult.Content<JointChannelAccepted>>(repository.acceptInvite(ServerId("srv-target"), "inv-1"))

        assertEquals(
            listOf(
                Triple("GET", "channels/ch-host", DataRequestPriority.Visible),
                Triple("POST", "channels/ch-host/disconnect", DataRequestPriority.Foreground),
                Triple("GET", "channels/joint-invites", DataRequestPriority.Background),
                Triple("POST", "channels/joint-invites/inv-1/accept", DataRequestPriority.Foreground),
            ),
            client.calls.map { Triple(it.method, it.path, it.priority) },
        )
        assertEquals(listOf(null, null, ServerId("srv-target"), ServerId("srv-target")), client.calls.map { it.serverIdOverride })
    }

    @Test
    fun acceptInviteRefusalsPreserveSlock16117CodesAndTraceId() = runTest {
        data class Case(val status: Int, val code: String, val error: String, val expectBlocked: Boolean)
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        listOf(
            Case(404, JOINT_INVITE_NOT_FOUND, "Joint channel invite not found", expectBlocked = false),
            Case(403, JOINT_INVITE_ACCEPT_FORBIDDEN, "Only server admins can accept this invite", expectBlocked = true),
            Case(409, JOINT_INVITE_NOT_ACCEPTABLE, "This invite can no longer be accepted", expectBlocked = false),
            Case(409, JOINT_INVITE_CHANNEL_ARCHIVED, "Channel is archived", expectBlocked = false),
        ).forEach { case ->
            val client =
                RecordingJointNetworkClient { _, _ ->
                    HomeNetworkResponse(
                        code = case.status,
                        bodyText = """{"error":"${case.error}","code":"${case.code}"}""",
                        responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
                    )
                }
            val result = RealJointChannelRepository(client).acceptInvite(ServerId("srv-target"), "inv-1")
            assertEquals(case.code, result.jointFailureCode(), "status=${case.status}")
            if (case.expectBlocked) {
                val blocked = assertIs<DataResult.NoPermission>(result).blocker
                assertEquals(case.error, blocked.serverMessage)
                assertEquals(traceId, blocked.traceId)
            } else {
                val error = assertIs<DataResult.Error>(result).error
                assertEquals(case.error, error.serverMessage)
                assertEquals(traceId, error.traceId)
            }
        }
    }

    @Test
    fun aRateLimitWaitIsWholeMinutesRoundedUpAndAtLeastOne() {
        // The action card's feedback rate limit words its wait with this (ThreadActionCardSpecView).
        assertEquals(
            listOf(1, 1, 1, 2, 59, 60),
            listOf(0L, 1L, 60L, 61L, 3540L, 3600L).map { jointInviteRateLimitMinutes(it) },
        )
        assertNull(jointInviteRateLimitMinutes(null))
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
             "channelRole":null,"channelAdminBasis":"server_role",
             "channelCapabilities":{"editChannelMetadata":true,"archiveChannels":true,"deleteChannels":true,
               "changeChannelVisibility":true,"manageVisitorAccess":true,"federateChannels":true,
               "addChannelMembers":true,"removeChannelMembers":true,"changeChannelMemberRoles":true},
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
        val query: List<Pair<String, String>> = emptyList(),
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
        calls += Call(priority, path, method, serverIdOverride, bodyText, query)
        return respond(path, method)
    }
}
