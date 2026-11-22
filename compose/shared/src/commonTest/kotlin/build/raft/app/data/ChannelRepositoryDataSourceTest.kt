package build.raft.app.data

import build.raft.app.homeDirectMessageOpenFailureMessage
import build.raft.app.englishErrorPresentationStringsForTest
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChannelRepositoryDataSourceTest {
    @Test
    fun openUserDirectMessageAllowsCurrentUserAsTheTarget() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody =
                    """{"id":"dm-self","name":"Self DM","type":"dm","peerType":"user","peerId":"user-1","peerName":"Artin"}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result = repository.openUserDirectMessage(MemberId("user-1"))

        val opened = assertIs<DataResult.Content<OpenDirectMessageResult>>(result).data
        assertEquals("dm-self", opened.conversation.channelId?.value)
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "POST", "channels/dm")),
            network.requests
        )
        assertEquals("user-1", network.bodies.single().asJsonObject()["userId"]?.jsonPrimitive?.content)
    }

    @Test
    fun openUserDirectMessageRejectsForeignPeerResponse() = runBlocking {
        val warnings = mutableListOf<String>()
        val network =
            FakeChannelNetworkClient(
                responseBody =
                    """{"id":"dm-foreign","name":"Wrong DM","type":"dm","peerType":"user","peerId":"user-2","peerName":"Other"}"""
            )
        val repository = RealChannelRepository(sampleStore(), network, warnings::add)

        val result = repository.openUserDirectMessage(MemberId("user-1"))

        val error = assertIs<DataResult.Error>(result)
        assertEquals("network_parse_failed", error.error.code)
        assertTrue(warnings.any { it.contains("dm_open_identity_mismatch") && it.contains("requestedPeerId=user-1") })
    }

    @Test
    fun directMessageStructuredServerErrorReachesHomeVisibleCopy() = runBlocking {
        val repository =
            RealChannelRepository(
                sampleStore(),
                FakeChannelNetworkClient(
                    responseCode = 400,
                    responseBody = null,
                    responseErrorBody = """{"error":"User is not a member of this server"}"""
                )
            )

        val error = assertIs<DataResult.Error>(repository.openUserDirectMessage(MemberId("user-2"))).error

        assertEquals(
            "Could not open direct message: User is not a member of this server",
            homeDirectMessageOpenFailureMessage(
                "Could not open direct message",
                error,
                englishErrorPresentationStringsForTest()
            )
        )
    }

    @Test
    fun createChannelSendsInitialMembersInCreateBodyWithoutPostCreateMemberCalls() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """{"id":"channel-1","name":"review","type":"private","joined":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result =
            repository.createChannel(
                CreateChannelRequest(
                    name = " review ",
                    description = "  Design review  ",
                    visibility = " private ",
                    agentMemberIds = listOf("agent-1", "agent-1", "", "agent-2"),
                    userMemberIds = listOf("user-1", "user-1", " ")
                )
            )

        val created = assertIs<DataResult.Content<CreateChannelResult>>(result).data
        assertEquals("channel-1", created.channel.channelId?.value)
        assertEquals(listOf("agent-1", "agent-2"), created.addedAgentMemberIds)
        assertEquals(listOf("user-1"), created.addedUserMemberIds)
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "POST", "channels")),
            network.requests
        )

        val body = network.bodies.single().asJsonObject()
        assertEquals("review", body["name"]?.jsonPrimitive?.content)
        assertEquals("Design review", body["description"]?.jsonPrimitive?.content)
        assertEquals("private", body["visibility"]?.jsonPrimitive?.content)
        assertEquals(listOf("agent-1", "agent-2"), body.stringList("agentIds"))
        assertEquals(listOf("user-1"), body.stringList("userIds"))
    }

    @Test
    fun createChannelPreservesServerConflictCode() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseCode = 409,
                responseBody = """{"code":"archived_name_collision","error":"Channel name is held by an archived channel"}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result = repository.createChannel(CreateChannelRequest(name = "review"))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("archived_name_collision", error.code)
        assertEquals(false, error.retryable)
        // No archived channel named: no Open Channel offer, the plain error line stays.
        assertEquals(null, error.archivedNameCollisionOrNull())
    }

    @Test
    fun createChannelCarriesTheArchivedChannelHoldingTheName() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseCode = 409,
                responseBody =
                    """{"code":"archived_name_collision","error":"Channel name \"review\" is held by an archived channel",""" +
                        """"archivedChannelId":"ch-old","archivedChannelName":"review","archivedChannelType":"private",""" +
                        """"canUnarchiveArchivedChannel":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val error = assertIs<DataResult.Error>(repository.createChannel(CreateChannelRequest(name = "review"))).error

        assertEquals(
            ArchivedNameCollision(ChannelId("ch-old"), "review", "private", canUnarchive = true),
            error.archivedNameCollisionOrNull()
        )
    }

    @Test
    fun archivedNameCollisionIsOnlyAnArchivedChannelCreateChannelCanOffer() {
        val fields =
            mapOf(
                ARCHIVED_CHANNEL_ID_FIELD to "ch-old",
                ARCHIVED_CHANNEL_NAME_FIELD to "review",
                ARCHIVED_CHANNEL_TYPE_FIELD to "channel",
            )
        val collision = ScreenError(code = ARCHIVED_NAME_COLLISION, serverFields = fields)
        // Without the permission flag (or with it false) Unarchive is not offered.
        assertEquals(
            ArchivedNameCollision(ChannelId("ch-old"), "review", "channel", canUnarchive = false),
            collision.archivedNameCollisionOrNull()
        )
        // Web parseArchivedNameCollision(err, ["channel", "private"]): never an archived joint channel.
        assertEquals(
            null,
            collision.copy(serverFields = fields + (ARCHIVED_CHANNEL_TYPE_FIELD to "joint")).archivedNameCollisionOrNull()
        )
        assertEquals(null, collision.copy(serverFields = fields - ARCHIVED_CHANNEL_ID_FIELD).archivedNameCollisionOrNull())
        assertEquals(null, collision.copy(serverFields = fields - ARCHIVED_CHANNEL_NAME_FIELD).archivedNameCollisionOrNull())
        // The plain "already taken" refusal (someone who cannot see the archived channel).
        assertEquals(null, ScreenError(code = "http_409", serverFields = fields).archivedNameCollisionOrNull())
    }

    @Test
    fun createChannelRejectsOverlengthNameBeforeNetwork() = runBlocking {
        val network = FakeChannelNetworkClient()
        val repository = RealChannelRepository(sampleStore(), network)

        val result = repository.createChannel(CreateChannelRequest(name = "a".repeat(ChannelNameMaxLength + 1)))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("channel_name_invalid", error.code)
        assertEquals(false, error.retryable)
        assertEquals(emptyList(), network.requests)
    }

    @Test
    fun updateChannelProfileSendsCanonicalPatchAndReturnsCanonicalChannel() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody =
                    """{"id":"channel-1","name":"renamed","description":"Canonical description","type":"channel","joined":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result =
            repository.updateChannelProfile(
                ChannelProfileUpdateRequest(
                    channelId = ChannelId("channel-1"),
                    name = " renamed ",
                    description = " Canonical description "
                )
            )

        val updated = assertIs<DataResult.Content<ChannelProfileUpdateResult>>(result).data
        assertEquals(ChannelId("channel-1"), updated.channelId)
        assertEquals("renamed", updated.name)
        assertEquals("Canonical description", updated.description)
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "PATCH", "channels/channel-1")),
            network.requests
        )
        val body = network.bodies.single().asJsonObject()
        assertEquals(setOf("name", "description"), body.keys)
        assertEquals("renamed", body["name"]?.jsonPrimitive?.content)
        assertEquals("Canonical description", body["description"]?.jsonPrimitive?.content)
    }

    /** The `channels` row the routes answer with (`res.json(updated)`; packages/server/src/db/schema.ts `channels`). */
    private val canonicalChannelRow =
        """{"id":"channel-1","serverId":"server-1","name":"triage","description":"Intake","type":"channel",""" +
            """"guestVisible":true,"guestJoinable":true,"parentMessageId":null,"parentChannelId":null,""" +
            """"createdAt":"2026-10-01T08:00:00.000Z","archivedAt":null,"archivedByUserId":null,""" +
            """"archivedByAgentId":null,"deletedAt":null}"""

    @Test
    fun unarchivePostsAndReadsTheRestoredRow() = runBlocking {
        val network = FakeChannelNetworkClient(responseBody = canonicalChannelRow)
        val repository = RealChannelRepository(sampleStore(), network)

        val row = assertIs<DataResult.Content<HomeChannelRow>>(repository.unarchiveChannel(ChannelId("channel-1"))).data

        assertEquals(ChannelId("channel-1"), row.channelId)
        assertEquals(false, row.archived)
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "POST", "channels/channel-1/unarchive")),
            network.requests,
        )
    }

    @Test
    fun unarchiveRejectsAnotherChannelsRow() = runBlocking {
        val network = FakeChannelNetworkClient(responseBody = canonicalChannelRow.replace("channel-1", "channel-9"))
        val repository = RealChannelRepository(sampleStore(), network)

        assertIs<DataResult.Error>(repository.unarchiveChannel(ChannelId("channel-1")))
        Unit
    }

    @Test
    fun updateChannelProfileSendsEmptyDescriptionToClearIt() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """{"id":"channel-1","name":"general","description":"","type":"channel","joined":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        repository.updateChannelProfile(
            ChannelProfileUpdateRequest(ChannelId("channel-1"), "general", "   ")
        )

        assertEquals("", network.bodies.single().asJsonObject()["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun updateChannelProfileRejectsMismatchedCanonicalChannelIdentity() = runBlocking {
        val logs = mutableListOf<String>()
        val network =
            FakeChannelNetworkClient(
                responseBody =
                    """{"id":"channel-2","name":"wrong-channel","description":"Wrong","type":"channel","joined":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network, logs::add)

        val result =
            repository.updateChannelProfile(
                ChannelProfileUpdateRequest(ChannelId("channel-1"), "renamed", "Description")
            )

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("channel_update_identity_mismatch", error.code)
        assertEquals(false, error.retryable)
        assertEquals(
            listOf("channel_update_profile_identity_mismatch requested=channel-1 response=channel-2"),
            logs
        )
    }

    @Test
    fun channelCacheAllowsJoinedStateToBecomeFalse() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """[{"id":"channel-1","name":"general","type":"channel","joined":true}]"""
            )
        val repository = RealChannelRepository(sampleStore(), network)
        val serverId = ServerId("server-1")

        val joined = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(serverId)).data.channels.single()
        assertEquals(true, joined.joined)
        assertEquals(true, joined.permissions.canSend)

        network.responseBody = """[{"id":"channel-1","name":"general","type":"channel","joined":false}]"""
        val notJoined = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(serverId)).data.channels.single()

        assertEquals(false, notJoined.joined)
        assertEquals(false, notJoined.permissions.canSend)
    }

    @Test
    fun channelRepositoryDoesNotReusePreviousChannelDisplayFields() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """[{"id":"channel-1","name":"General Team","type":"channel","description":"Team room","joined":true}]"""
            )
        val repository = RealChannelRepository(sampleStore(), network)
        val serverId = ServerId("server-1")

        val rich = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(serverId)).data.channels.single()
        assertEquals("General Team", rich.name)
        assertEquals("Team room", rich.subtitle)

        network.responseBody = """[{"id":"channel-1","name":"channel-1","type":"channel","joined":true}]"""
        val generic = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(serverId)).data.channels.single()

        assertEquals("channel-1", generic.name)
        assertEquals(null, generic.subtitle)
    }

    @Test
    fun channelRepositoryParsesActivityMuteFieldsForChannelsAndDirectMessages() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                pathResponses =
                    mapOf(
                        "channels" to """[{"id":"channel-1","name":"general","type":"channel","joined":true,"activityMuted":true,"muteFromSeq":42,"activityMuteSupported":true}]""",
                        "channels/dm" to """[{"id":"dm-1","name":"dm-1","type":"dm","peerType":"user","peerId":"user-2","peerName":"Cindy","activityMuted":false,"muteFromSeq":null,"activityMuteSupported":true}]"""
                    )
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val channel = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(ServerId("server-1"))).data.channels.single()
        assertEquals(true, channel.activityMuted)
        assertEquals(42, channel.muteFromSeq)
        assertEquals(true, channel.activityMuteSupported)

        val dm = assertIs<DataResult.Content<DirectMessageSnapshot>>(repository.getDirectMessages(ServerId("server-1"))).data.directMessages.single()
        assertEquals(false, dm.activityMuted)
        assertEquals(null, dm.muteFromSeq)
        assertEquals(true, dm.activityMuteSupported)
    }

    @Test
    fun setChannelActivityMutedUsesHumanNotificationSettingsEndpoint() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """{"activityMuted":true,"muteFromSeq":99,"activityMuteSupported":true,"prefsVersion":12}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result = repository.setChannelActivityMuted(ChannelId("channel-1"), true)

        val settings = assertIs<DataResult.Content<ChannelActivityMuteSettings>>(result).data
        assertEquals(ChannelId("channel-1"), settings.channelId)
        assertEquals(true, settings.activityMuted)
        assertEquals(99, settings.muteFromSeq)
        assertEquals(true, settings.activityMuteSupported)
        assertEquals(12, settings.prefsVersion)
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "PATCH", "channels/channel-1/notification-settings")),
            network.requests
        )
        val body = network.bodies.single().asJsonObject()
        assertEquals("true", body["activityMuted"]?.jsonPrimitive?.content)
    }

    @Test
    fun getHomePreservesServerUnreadSummary() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                pathResponses =
                    mapOf(
                        "channels" to """[{"id":"channel-1","name":"general","type":"channel","joined":true}]""",
                        "channels/dm" to """[]""",
                        "channels/unread" to channelUnreadSummaryBody(mapOf("channel-1" to 4)),
                        "servers/unread-summary" to """[{"serverId":"server-1","unreadCount":12}]"""
                    )
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val home = assertIs<DataResult.Content<HomeSnapshot>>(repository.getHome(ServerId("server-1"))).data

        assertEquals(AppBadgeSource.SERVER_UNREAD_SUMMARY, home.unreadSource)
        assertEquals(4, home.channels.single().unreadCount)
        assertEquals(12, home.unreadTotal)
        assertEquals(12, home.server.unreadCount)
        assertEquals(12, home.serverUnreadCounts["server-1"])
    }

    @Test
    fun onlyTheComposerChannelListAsksForArchivedChannels() = runBlocking {
        // Home keeps the server's default (archived left out): global search finds archived
        // channels through core's search (`GET /api/search?app=channels`), not the Home list.
        val channels =
            """[{"id":"channel-1","name":"general","type":"channel","joined":true},""" +
                """{"id":"channel-old","name":"old","type":"channel","joined":true,"archivedAt":"2026-11-01T00:00:00.000Z"}]"""
        val network =
            FakeChannelNetworkClient(
                pathResponses =
                    mapOf(
                        "channels" to channels,
                        "channels/dm" to """[]""",
                        "channels/unread" to channelUnreadSummaryBody(emptyMap()),
                        "servers/unread-summary" to """[]"""
                    )
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val home = assertIs<DataResult.Content<HomeSnapshot>>(repository.getHome(ServerId("server-1"))).data
        assertEquals(emptyList(), network.queriesByPath["channels"])
        assertEquals(listOf("channel-1"), home.channels.map { it.channelId.value })

        // Other channel lists keep the server's default (archived left out)...
        network.queriesByPath.clear()
        val plain = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannels(ServerId("server-1"))).data
        assertEquals(emptyList(), network.queriesByPath["channels"])
        assertEquals(listOf("channel-1"), plain.channels.map { it.channelId.value })

        // ...except the composer's `#` list, which offers archived channels like the web.
        val withArchived = assertIs<DataResult.Content<ChannelSnapshot>>(repository.getChannelsIncludingArchived(ServerId("server-1"))).data
        assertEquals(listOf("archived" to "include"), network.queriesByPath["channels"])
        assertEquals(listOf(false, true), withArchived.channels.map { it.archived })
    }

    @Test
    fun setChannelVisitorVisiblePatchesVisitorVisibleAndPreservesRefusalCodes() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody =
                    """{"id":"channel-1","name":"lobby","type":"channel","visitorVisible":true,"guestVisible":true,"joined":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val updated =
            assertIs<DataResult.Content<HomeChannelRow>>(
                repository.setChannelVisitorVisible(ChannelId("channel-1"), visitorVisible = true)
            ).data

        assertTrue(updated.guestVisible)
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "PATCH", "channels/channel-1")),
            network.requests,
        )
        assertEquals("true", network.bodies.single().asJsonObject()["visitorVisible"]?.jsonPrimitive?.content)

        val forbiddenRepo =
            RealChannelRepository(
                sampleStore(),
                FakeChannelNetworkClient(
                    responseCode = 403,
                    responseBody = null,
                    responseErrorBody = """{"code":"visitor_visibility_forbidden","error":"Forbidden"}""",
                ),
            )
        assertEquals(
            "visitor_visibility_forbidden",
            assertIs<DataResult.NoPermission>(
                forbiddenRepo.setChannelVisitorVisible(ChannelId("channel-1"), visitorVisible = true)
            ).blocker.code,
        )

        val requiresPublicRepo =
            RealChannelRepository(
                sampleStore(),
                FakeChannelNetworkClient(
                    responseCode = 400,
                    responseBody = null,
                    responseErrorBody = """{"code":"visitor_visibility_requires_public_server","error":"Server is not public"}""",
                ),
            )
        assertEquals(
            "visitor_visibility_requires_public_server",
            assertIs<DataResult.Error>(
                requiresPublicRepo.setChannelVisitorVisible(ChannelId("channel-1"), visitorVisible = true)
            ).error.code,
        )
    }

    private fun String.asJsonObject() =
        Json.parseToJsonElement(this).jsonObject

    private fun kotlinx.serialization.json.JsonObject.stringList(key: String): List<String> =
        get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun sampleStore(): RaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-1",
                        email = "artin@slock.ai",
                        displayName = "Artin"
                    ),
                accessToken = "token-a",
                refreshToken = "token-r",
                selectedServerId = "server-1",
                servers =
                    listOf(
                        StoredAuthServer(
                            id = "server-1",
                            name = "Slock Android",
                            slug = "android"
                        ),
                        StoredAuthServer(
                            id = "server-2",
                            name = "Work",
                            slug = "work"
                        )
                    )
            )
        )

    private class FakeChannelNetworkClient(
        private val responseCode: Int = 200,
        var responseBody: String? = """{"id":"channel-1","name":"general","type":"channel","joined":true}""",
        private val pathResponses: Map<String, String> = emptyMap(),
        private val responseErrorBody: String? = null
    ) : RaftNetworkClient {
        val requests = mutableListOf<RecordedChannelRequest>()
        val bodies = mutableListOf<String>()
        val queriesByPath = mutableMapOf<String, List<Pair<String, String>>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            val normalizedPath = path.trim('/')
            requests += RecordedChannelRequest(priority, method, normalizedPath)
            queriesByPath[normalizedPath] = query
            bodyText?.let { bodies += it }
            return HomeNetworkResponse(
                code = responseCode,
                bodyText = pathResponses[normalizedPath] ?: responseBody,
                errorBodyText = responseErrorBody
            )
        }
    }

    private data class RecordedChannelRequest(
        val priority: DataRequestPriority,
        val method: String,
        val path: String
    )
}
