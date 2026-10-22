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
    fun openDirectMessageRejectsWrongPeerKindEvenWhenIdMatches() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody =
                    """{"id":"dm-wrong-kind","name":"Wrong kind","type":"dm","peerType":"agent","peerId":"user-1","peerName":"Artin"}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result = repository.openUserDirectMessage(MemberId("user-1"))

        val error = assertIs<DataResult.Error>(result)
        assertEquals("network_parse_failed", error.error.code)
    }

    @Test
    fun directMessageErrorsPreserveOnlyStructuredServerErrorText() = runBlocking {
        val fixtures =
            listOf(
                "structured" to ("""{"error":"User is not a member of this server"}""" to "User is not a member of this server"),
                "wrong-alias" to ("""{"message":"User is not a member of this server"}""" to null),
                "malformed" to ("""{"error":"unterminated""" to null),
                "plain-text" to ("User is not a member of this server" to null),
                "empty" to ("" to null)
            )

        fixtures.forEach { (name, fixture) ->
            val (body, expectedMessage) = fixture
            val repository =
                RealChannelRepository(
                    sampleStore(),
                    FakeChannelNetworkClient(
                        responseCode = 400,
                        responseBody = null,
                        responseErrorBody = body
                    )
                )

            val error =
                assertIs<DataResult.Error>(repository.openUserDirectMessage(MemberId("user-2")), name)
                    .error

            assertEquals("http_400", error.code, name)
            assertEquals(expectedMessage, error.serverMessage, name)
        }
    }

    @Test
    fun directMessagePermissionErrorsRetainExactErrorFieldOn401And403() = runBlocking {
        listOf(401, 403).forEach { status ->
            val exactReason = "  channel-$status 理由  "
            val repository =
                RealChannelRepository(
                    sampleStore(),
                    FakeChannelNetworkClient(
                        responseCode = status,
                        responseBody = null,
                        responseErrorBody = """{"error":"$exactReason"}"""
                    )
                )

            val blocker =
                assertIs<DataResult.NoPermission>(
                    repository.openUserDirectMessage(MemberId("user-2"))
                ).blocker

            assertEquals(exactReason, blocker.serverMessage)
        }
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
    fun warmSuccessfulOpenDoesNotFallbackWhenNextResponseTargetsAnotherPeer() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody =
                    """{"id":"dm-self","name":"Self DM","type":"dm","peerType":"user","peerId":"user-1","peerName":"Artin"}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        assertIs<DataResult.Content<OpenDirectMessageResult>>(
            repository.openUserDirectMessage(MemberId("user-1"))
        )
        network.responseBody =
            """{"id":"dm-other","name":"Other DM","type":"dm","peerType":"user","peerId":"user-2","peerName":"Other"}"""

        val second = repository.openUserDirectMessage(MemberId("user-1"))

        assertIs<DataResult.Error>(second)
        assertEquals(2, network.requests.size)
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
    fun createJointChannelSendsServerInviteContractFields() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """{"id":"joint-1","name":"partner","type":"joint","joined":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        repository.createChannel(
            CreateChannelRequest(
                name = "partner",
                visibility = "joint",
                agentMemberIds = listOf("agent-1"),
                userMemberIds = listOf("user-1"),
                targetServerSlug = " partner-server ",
                invitedPeople = listOf("owner@example.com", "owner@example.com", " @admin ")
            )
        )

        val body = network.bodies.single().asJsonObject()
        assertEquals("joint", body["visibility"]?.jsonPrimitive?.content)
        assertEquals(listOf("agent-1"), body.stringList("agentIds"))
        assertEquals(listOf("user-1"), body.stringList("userIds"))
        assertEquals("partner-server", body["targetServerSlug"]?.jsonPrimitive?.content)
        assertEquals(listOf("owner@example.com", "@admin"), body.stringList("invitedPeople"))
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "POST", "channels")),
            network.requests
        )
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
    }

    @Test
    fun createChannelPreservesUnicodeName() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """{"id":"channel-1","name":"markdown专修","type":"channel","joined":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result = repository.createChannel(CreateChannelRequest(name = " markdown专修 "))

        val created = assertIs<DataResult.Content<CreateChannelResult>>(result).data
        assertEquals("markdown专修", created.channel.name)
        assertEquals("markdown专修", network.bodies.single().asJsonObject()["name"]?.jsonPrimitive?.content)
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
    fun createChannelRejectsBlankNameBeforeNetwork() = runBlocking {
        val network = FakeChannelNetworkClient()
        val repository = RealChannelRepository(sampleStore(), network)

        val result = repository.createChannel(CreateChannelRequest(name = "   "))

        assertIs<DataResult.Empty>(result)
        assertEquals(emptyList(), network.requests)
    }

    @Test
    fun createChannelMapsServerNameValidationMessageToTypedCode() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseCode = 400,
                responseBody =
                    """{"error":"Channel name must start with a letter and can only contain letters, numbers, hyphens, and underscores"}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result = repository.createChannel(CreateChannelRequest(name = "123-start"))

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("channel_name_invalid", error.code)
        assertEquals(false, error.retryable)
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
    fun guestPolicyPatchSendsOnlyTheChangedFlagsAndReadsTheRow() = runBlocking {
        val network = FakeChannelNetworkClient(responseBody = canonicalChannelRow)
        val repository = RealChannelRepository(sampleStore(), network)

        val result =
            repository.updateChannelGuestPolicy(
                ChannelId("channel-1"),
                ChannelGuestPolicyUpdate(guestVisible = true, guestJoinable = true),
            )

        val row = assertIs<DataResult.Content<HomeChannelRow>>(result).data
        assertTrue(row.guestVisible)
        assertTrue(row.guestJoinable)
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "PATCH", "channels/channel-1")),
            network.requests,
        )
        val body = network.bodies.single().asJsonObject()
        assertEquals(setOf("guestVisible", "guestJoinable"), body.keys)

        repository.updateChannelGuestPolicy(ChannelId("channel-1"), ChannelGuestPolicyUpdate(guestJoinable = false))
        assertEquals(setOf("guestJoinable"), network.bodies.last().asJsonObject().keys)
        assertEquals("false", network.bodies.last().asJsonObject()["guestJoinable"]?.jsonPrimitive?.content)
    }

    @Test
    fun guestPolicyLimitConflictKeepsTheServerCode() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseCode = 409,
                responseBody = null,
                responseErrorBody =
                    """{"error":"Guest-joinable channel limit reached","code":"guest_joinable_channel_limit_reached","limit":3}""",
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result =
            repository.updateChannelGuestPolicy(ChannelId("channel-1"), ChannelGuestPolicyUpdate(guestVisible = true, guestJoinable = true))

        assertEquals("guest_joinable_channel_limit_reached", assertIs<DataResult.Error>(result).error.code)
    }

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
    fun updateChannelProfilePreservesUnicodeName() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody =
                    """{"id":"channel-1","name":"markdown专修","description":"中文描述","type":"channel","joined":true}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result =
            repository.updateChannelProfile(
                ChannelProfileUpdateRequest(ChannelId("channel-1"), " markdown专修 ", " 中文描述 ")
            )

        val updated = assertIs<DataResult.Content<ChannelProfileUpdateResult>>(result).data
        assertEquals("markdown专修", updated.name)
        assertEquals("markdown专修", network.bodies.single().asJsonObject()["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun updateChannelProfileRejectsOverlengthNameBeforeNetwork() = runBlocking {
        val network = FakeChannelNetworkClient()
        val repository = RealChannelRepository(sampleStore(), network)

        val result =
            repository.updateChannelProfile(
                ChannelProfileUpdateRequest(
                    ChannelId("channel-1"),
                    "a".repeat(ChannelNameMaxLength + 1),
                    "description"
                )
            )

        assertEquals("channel_name_invalid", assertIs<DataResult.Error>(result).error.code)
        assertEquals(emptyList(), network.requests)
    }

    @Test
    fun updateChannelProfileRejectsBlankNameBeforeNetwork() = runBlocking {
        val network = FakeChannelNetworkClient()
        val repository = RealChannelRepository(sampleStore(), network)

        val result =
            repository.updateChannelProfile(
                ChannelProfileUpdateRequest(ChannelId("channel-1"), "   ", "description")
            )

        assertEquals("channel_name_invalid", assertIs<DataResult.Error>(result).error.code)
        assertEquals(emptyList(), network.requests)
    }

    @Test
    fun updateChannelProfileMapsServerNameValidationMessageToTypedCode() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseCode = 400,
                responseBody =
                    """{"error":"Channel name must start with a letter and can only contain letters, numbers, hyphens, and underscores"}"""
            )
        val repository = RealChannelRepository(sampleStore(), network)

        val result =
            repository.updateChannelProfile(
                ChannelProfileUpdateRequest(ChannelId("channel-1"), "bad.name", "description")
            )

        assertEquals("channel_name_invalid", assertIs<DataResult.Error>(result).error.code)
        assertEquals(
            listOf(RecordedChannelRequest(DataRequestPriority.Foreground, "PATCH", "channels/channel-1")),
            network.requests
        )
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
    fun channelRepositoryDoesNotReusePreviousDirectMessageDisplayFields() = runBlocking {
        val network =
            FakeChannelNetworkClient(
                responseBody = """[{"id":"dm-1","name":"dm-1","type":"dm","peerType":"agent","peerId":"agent-1","peerName":"Product UX","peerDescription":"Design system"}]"""
            )
        val repository = RealChannelRepository(sampleStore(), network)
        val serverId = ServerId("server-1")

        val rich = assertIs<DataResult.Content<DirectMessageSnapshot>>(repository.getDirectMessages(serverId)).data.directMessages.single()
        assertEquals("Product UX", rich.name)
        assertEquals("Design system", rich.subtitle)
        assertEquals("agent", rich.directMessagePeerType)

        network.responseBody = """[{"id":"dm-1","name":"dm-1","type":"dm","peerType":"user","peerId":"user-1","peerName":"dm-1"}]"""
        val generic = assertIs<DataResult.Content<DirectMessageSnapshot>>(repository.getDirectMessages(serverId)).data.directMessages.single()

        assertEquals("dm-1", generic.name)
        assertEquals(null, generic.subtitle)
        assertEquals("user", generic.directMessagePeerType)
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
