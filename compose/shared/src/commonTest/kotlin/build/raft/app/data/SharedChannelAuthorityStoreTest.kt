package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ChannelAuthorityUpdated
import build.raft.app.model.ChannelCapabilities
import build.raft.app.model.ChannelId
import build.raft.app.model.ChannelViewerAuthority
import build.raft.app.model.ChannelViewerAuthorityView
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.ServerId
import build.raft.app.model.ServerPermissions
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedChannelAuthorityStoreTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun readChannelAuthoritySkipsBareBroadcastPatchAndDmRowsAndParsesAllFourFields() {
        val broadcastRow =
            json.parseToJsonElement(
                """{"id":"ch-1","name":"design","type":"joint","description":"updated"}"""
            ).jsonObject
        val dmRow =
            json.parseToJsonElement(
                """{"id":"dm-1","name":"alice","type":"dm"}"""
            ).jsonObject
        val patchRow =
            json.parseToJsonElement(
                """{"id":"ch-1","name":"design","guestVisible":true,"channelRole":"admin","channelAuthorityRevision":2}"""
            ).jsonObject
        val perViewerRow =
            json.parseToJsonElement(
                """
                {
                  "id": "ch-joint",
                  "name": "partners",
                  "type": "joint",
                  "channelRole": "admin",
                  "channelAdminBasis": "server_owner",
                  "channelAuthorityRevision": 7,
                  "channelCapabilities": {
                    "addChannelMembers": true,
                    "removeChannelMembers": true,
                    "changeChannelMemberRoles": false,
                    "editChannelMetadata": false,
                    "changeChannelVisibility": false,
                    "archiveChannels": false,
                    "deleteChannels": true,
                    "federateChannels": false,
                    "manageVisitorAccess": false
                  }
                }
                """.trimIndent()
            ).jsonObject

        assertNull(readChannelAuthority(broadcastRow))
        assertNull(readChannelAuthority(dmRow))
        assertNull(readChannelAuthority(patchRow))

        val parsed = assertNotNull(readChannelAuthority(perViewerRow))
        assertEquals("admin", parsed.role)
        assertEquals("server_owner", parsed.adminBasis)
        assertEquals(7L, parsed.revision)
        assertEquals(true, parsed.capabilities.addChannelMembers)
        assertEquals(true, parsed.capabilities.removeChannelMembers)
        assertEquals(false, parsed.capabilities.changeChannelMemberRoles)
        assertEquals(true, parsed.capabilities.deleteChannels)
        assertEquals(false, parsed.capabilities.editChannelMetadata)
    }

    @Test
    fun writeAnswersRejectsStaleInFlightAnswersByObservedSeqAndPreservesInstanceOnEqualData() {
        val store = SharedChannelAuthorityStore()
        val olderSeq = store.nextObservedSeq()
        val newerSeq = store.nextObservedSeq()

        val promoted =
            ChannelViewerAuthority(
                role = "admin",
                adminBasis = "channel_admin",
                capabilities = ChannelCapabilities(addChannelMembers = true, archiveChannels = true),
                revision = 2L,
            )
        val staleMember =
            ChannelViewerAuthority(
                role = "member",
                adminBasis = null,
                capabilities = ChannelCapabilities(addChannelMembers = false, archiveChannels = false),
                revision = 1L,
            )

        assertTrue(
            store.writeAnswers(
                serverId = "server-1",
                rows = listOf(ChannelAuthorityAnswerRow("ch-1", promoted)),
                observedSeq = newerSeq,
            )
        )
        val storedInstance = assertNotNull(store.authorityOf("server-1", "ch-1"))
        val stateAfterNewer = store.state.value

        // Older request settling later must never overwrite newer observedSeq.
        assertFalse(
            store.writeAnswers(
                serverId = "server-1",
                rows = listOf(ChannelAuthorityAnswerRow("ch-1", staleMember)),
                observedSeq = olderSeq,
            )
        )
        assertSame(storedInstance, store.authorityOf("server-1", "ch-1"))
        assertSame(stateAfterNewer, store.state.value)

        // Newer answer with identical data preserves the stored authority instance.
        val evenNewerSeq = store.nextObservedSeq()
        val equalCopy = promoted.copy(capabilities = promoted.capabilities.copy())
        assertTrue(
            store.writeAnswers(
                serverId = "server-1",
                rows = listOf(ChannelAuthorityAnswerRow("ch-1", equalCopy)),
                observedSeq = evenNewerSeq,
            )
        )
        assertSame(storedInstance, store.authorityOf("server-1", "ch-1"))
        assertSame(stateAfterNewer, store.state.value)
    }

    @Test
    fun authorityRevisionIsNewerHandlesMissingNullLowerEqualAndHigher() {
        val store = SharedChannelAuthorityStore()
        assertTrue(store.authorityRevisionIsNewer("server-1", "ch-1", 1L))

        val seq1 = store.nextObservedSeq()
        store.writeAnswers(
            serverId = "server-1",
            rows =
                listOf(
                    ChannelAuthorityAnswerRow(
                        channelId = "ch-null-rev",
                        authority = ChannelViewerAuthority(capabilities = ChannelCapabilities(), revision = null),
                    ),
                    ChannelAuthorityAnswerRow(
                        channelId = "ch-rev-3",
                        authority = ChannelViewerAuthority(capabilities = ChannelCapabilities(), revision = 3L),
                    ),
                ),
            observedSeq = seq1,
        )

        // Null stored revision (no membership row yet) is strictly below every numeric revision.
        assertTrue(store.authorityRevisionIsNewer("server-1", "ch-null-rev", 1L))
        assertFalse(store.authorityRevisionIsNewer("server-1", "ch-rev-3", 2L))
        assertFalse(store.authorityRevisionIsNewer("server-1", "ch-rev-3", 3L))
        assertTrue(store.authorityRevisionIsNewer("server-1", "ch-rev-3", 4L))
    }

    @Test
    fun channelUpdatedBroadcastNeverErasesStoredAuthorityOrDirectoryCapabilitiesForConnectedServerOwner() {
        val store = SharedChannelAuthorityStore()
        val connectedOwnerCaps =
            ChannelCapabilities(
                addChannelMembers = true,
                removeChannelMembers = true,
                deleteChannels = true,
                editChannelMetadata = false,
            )
        val connectedOwnerAuthority =
            ChannelViewerAuthority(
                role = "admin",
                adminBasis = "connected_server_owner",
                capabilities = connectedOwnerCaps,
                revision = 5L,
            )
        val token = store.captureRequestToken("server-connected")
        store.writeSingleAnswer(token, ChannelId("ch-joint"), connectedOwnerAuthority)

        // Broadcast `channel:updated` carries no channelCapabilities; attempting to parse/write it is a no-op.
        val broadcastJson =
            """{"id":"ch-joint","name":"partners-renamed","type":"joint","description":"updated topic"}"""
        assertNull(parseChannelViewerAuthority(broadcastJson))
        assertFalse(
            store.writeAnswers(
                token = store.captureRequestToken("server-connected"),
                rows = listOf(ChannelAuthorityAnswerRow("ch-joint", readChannelAuthority(json.parseToJsonElement(broadcastJson).jsonObject))),
            )
        )
        assertEquals(connectedOwnerAuthority, store.authorityOf("server-connected", "ch-joint"))

        // RealtimeEventMapper maps `channel:updated` to a bare refresh event without wiping authority.
        val mapped =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.ChannelUpdated,
                broadcastJson,
            )
        assertIs<RealtimeRefreshRequired>(mapped)

        // Directory projection merge preserves existing channelCapabilities when an update row has null.
        val initialProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-connected",
                canonicalChannelId = "ch-joint",
                aliases = setOf("ch-joint"),
                conversationKind = ConversationKind.Joint,
                surfaceKind = "channel",
                channelKind = "joint",
                title = "partners",
                channelCapabilities = connectedOwnerCaps,
            )
        val broadcastProjection =
            initialProjection.copy(
                title = "partners-renamed",
                channelCapabilities = null,
            )
        val merged =
            listOf(
                SharedConversationDirectoryState(projectionsByCanonicalId = mapOf("ch-joint" to initialProjection)),
                SharedConversationDirectoryState(projectionsByCanonicalId = mapOf("ch-joint" to broadcastProjection)),
            ).mergedSharedConversationDirectoryState()
        assertEquals(connectedOwnerCaps, merged.resolve("ch-joint")?.channelCapabilities)
        assertTrue(authorityAllows(store.viewOf("server-connected", "ch-joint")) { it.addChannelMembers })
    }

    @Test
    fun requestChannelAuthorityDeduplicatesInFlightAndSkipsAlreadyKnown() = runTest {
        val gate = CompletableDeferred<ChannelViewerAuthority?>()
        var fetchCalls = 0
        val store =
            SharedChannelAuthorityStore(
                scope = backgroundScope,
                fetchChannelAuthority = { _, _ ->
                    fetchCalls += 1
                    gate.await()
                },
            )

        assertIs<ChannelViewerAuthorityView.Loading>(store.viewOf("server-1", "ch-1"))
        assertTrue(store.requestChannelAuthority("server-1", "ch-1"))
        assertFalse(store.requestChannelAuthority("server-1", "ch-1"))
        runCurrent()
        assertEquals(1, fetchCalls)

        val resolved =
            ChannelViewerAuthority(
                role = "admin",
                adminBasis = "server_owner",
                capabilities = ChannelCapabilities(addChannelMembers = true),
                revision = 1L,
            )
        gate.complete(resolved)
        runCurrent()

        val view = assertIs<ChannelViewerAuthorityView.Known>(store.viewOf("server-1", "ch-1"))
        assertEquals(resolved, view.authority)
        // Once known, requestChannelAuthority skips issuing another first-read.
        assertFalse(store.requestChannelAuthority("server-1", "ch-1"))
        assertEquals(1, fetchCalls)
    }

    @Test
    fun serverResetClearsAuthorityAndInFlightSlotsWithoutLettingStaleReadFreeNewSlot() = runTest {
        val firstReadGate = CompletableDeferred<ChannelViewerAuthority?>()
        val secondReadGate = CompletableDeferred<ChannelViewerAuthority?>()
        var callCount = 0
        val store =
            SharedChannelAuthorityStore(
                scope = backgroundScope,
                fetchChannelAuthority = { _, _ ->
                    callCount += 1
                    if (callCount == 1) firstReadGate.await() else secondReadGate.await()
                },
            )

        // Start read #1 on server-1.
        assertTrue(store.requestChannelAuthority("server-1", "ch-1"))
        runCurrent()
        assertEquals(1, callCount)

        // Switch away from server-1: clears stored authority and in-flight slot.
        store.resetServer("server-1")

        // Remount back on server-1 while read #1 is still in flight: read #2 starts cleanly.
        assertTrue(store.requestChannelAuthority("server-1", "ch-1"))
        runCurrent()
        assertEquals(2, callCount)

        // Read #1 settles with stale authority: must be dropped by epoch AND must NOT free read #2's slot.
        firstReadGate.complete(
            ChannelViewerAuthority(
                role = "member",
                capabilities = ChannelCapabilities(addChannelMembers = false),
                revision = 1L,
            )
        )
        runCurrent()
        assertIs<ChannelViewerAuthorityView.Loading>(store.viewOf("server-1", "ch-1"))
        // Read #2's slot is still held, so a duplicate request is deduplicated.
        assertFalse(store.requestChannelAuthority("server-1", "ch-1"))
        assertEquals(2, callCount)

        // Read #2 settles with fresh authority and populates the store.
        val fresh =
            ChannelViewerAuthority(
                role = "admin",
                adminBasis = "connected_server_owner",
                capabilities = ChannelCapabilities(addChannelMembers = true),
                revision = 2L,
            )
        secondReadGate.complete(fresh)
        runCurrent()
        assertEquals(fresh, store.authorityOf("server-1", "ch-1"))
    }

    @Test
    fun channelAuthorityUpdatedRefreshesHeldChannelOncePerNewerRevisionAndIgnoresRedeliveriesAndUnheldChannels() = runTest {
        val heldChannels = mutableSetOf("server-1" to "ch-held")
        val fetchGates = mutableListOf<CompletableDeferred<ChannelViewerAuthority?>>()
        val refreshedEvents = mutableListOf<Pair<String, ChannelViewerAuthority>>()
        val store =
            SharedChannelAuthorityStore(
                scope = backgroundScope,
                fetchChannelAuthority = { _, _ ->
                    CompletableDeferred<ChannelViewerAuthority?>().also { fetchGates += it }.await()
                },
                isChannelHeld = { serverId, channelId -> (serverId to channelId) in heldChannels },
                onAuthorityRefreshed = { _, channelId, authority ->
                    refreshedEvents += channelId to authority
                },
            )

        // Seed revision 2 for ch-held.
        val initial =
            ChannelViewerAuthority(
                role = "member",
                capabilities = ChannelCapabilities(addChannelMembers = false),
                revision = 2L,
            )
        store.writeSingleAnswer(store.captureRequestToken("server-1"), ChannelId("ch-held"), initial)

        // Unheld channel (e.g. another server from user room broadcast) is ignored.
        assertFalse(
            store.onChannelAuthorityUpdated(
                "server-1",
                ChannelAuthorityUpdated(ChannelId("ch-unheld"), channelRole = "admin", authorityRevision = 5L),
            )
        )
        assertEquals(0, fetchGates.size)

        // Equal or older revision is ignored.
        assertFalse(
            store.onChannelAuthorityUpdated(
                "server-1",
                ChannelAuthorityUpdated(ChannelId("ch-held"), channelRole = "member", authorityRevision = 2L),
            )
        )
        assertFalse(
            store.onChannelAuthorityUpdated(
                "server-1",
                ChannelAuthorityUpdated(ChannelId("ch-held"), channelRole = "member", authorityRevision = 1L),
            )
        )
        assertEquals(0, fetchGates.size)

        // Newer revision 3 triggers one single-channel read; redelivered revision 3 deduplicates.
        assertTrue(
            store.onChannelAuthorityUpdated(
                "server-1",
                ChannelAuthorityUpdated(ChannelId("ch-held"), channelRole = "admin", authorityRevision = 3L),
            )
        )
        assertFalse(
            store.onChannelAuthorityUpdated(
                "server-1",
                ChannelAuthorityUpdated(ChannelId("ch-held"), channelRole = "admin", authorityRevision = 3L),
            )
        )
        runCurrent()
        assertEquals(1, fetchGates.size)

        // Even newer revision 4 while rev 3 is still in flight triggers a second read.
        assertTrue(
            store.onChannelAuthorityUpdated(
                "server-1",
                ChannelAuthorityUpdated(ChannelId("ch-held"), channelRole = "member", authorityRevision = 4L),
            )
        )
        runCurrent()
        assertEquals(2, fetchGates.size)

        // Rev 4 settles first with higher observedSeq; rev 3 settling later cannot roll it back.
        val rev4Authority =
            ChannelViewerAuthority(
                role = "member",
                capabilities = ChannelCapabilities(addChannelMembers = false),
                revision = 4L,
            )
        val rev3Authority =
            ChannelViewerAuthority(
                role = "admin",
                capabilities = ChannelCapabilities(addChannelMembers = true),
                revision = 3L,
            )
        fetchGates[1].complete(rev4Authority)
        runCurrent()
        assertEquals(rev4Authority, store.authorityOf("server-1", "ch-held"))

        fetchGates[0].complete(rev3Authority)
        runCurrent()
        assertEquals(rev4Authority, store.authorityOf("server-1", "ch-held"))
        assertEquals(listOf("ch-held" to rev4Authority), refreshedEvents)
    }

    @Test
    fun channelAuthorityUpdatedThroughRealtimeOwnerUpdatesAuthorityStoreAndHomeSnapshot() = runTest {
        val promotedCaps = ChannelCapabilities(addChannelMembers = true, archiveChannels = true)
        val promotedAuthority =
            ChannelViewerAuthority(
                role = "admin",
                adminBasis = "channel_admin",
                capabilities = promotedCaps,
                revision = 2L,
            )
        var homeSnapshot =
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Raft", permissions = ServerPermissions()),
                currentMember = MemberProfile(id = MemberId("user-1"), displayName = "Ada", handle = "ada", kind = MemberKind.Human),
                channels =
                    listOf(
                        HomeChannelRow(
                            threadId = ThreadId("ch-1"),
                            channelId = ChannelId("ch-1"),
                            name = "ops",
                            channelCapabilities = ChannelCapabilities(addChannelMembers = false, archiveChannels = false),
                        )
                    ),
                directMessages = emptyList(),
                unreadTotal = 0,
            )
        val authorityStore =
            SharedChannelAuthorityStore(
                scope = backgroundScope,
                fetchChannelAuthority = { _, _ -> promotedAuthority },
                isChannelHeld = { serverId, channelId ->
                    homeSnapshot.server.id.value == serverId &&
                        homeSnapshot.channels.any { it.channelId.value == channelId }
                },
                onAuthorityRefreshed = { _, channelId, authority ->
                    homeSnapshot = homeSnapshot.withChannelCapabilities(ChannelId(channelId), authority.capabilities)
                },
            )
        val owner = SharedRealtimeDomainEventOwner(runIfCurrentGrant = { _, block -> block(); true })
        owner.installChannelAuthorityUpdatedSink { serverId, event ->
            authorityStore.onChannelAuthorityUpdated(serverId, event)
        }
        val grant =
            RealtimeGrantRecord(
                slotKey = RealtimeSlotKey("process-realtime-host", "server-1"),
                principalId = "user-1",
                environmentId = "env-test",
                authGeneration = 1L,
                connectionFamilyId = "family-1",
                connectionEpochId = "epoch-1",
            )

        owner.acceptProcessMutation(
            grant,
            ChannelAuthorityUpdated(ChannelId("ch-1"), channelRole = "admin", authorityRevision = 2L),
        )
        runCurrent()

        assertEquals(promotedAuthority, authorityStore.authorityOf("server-1", "ch-1"))
        assertEquals(promotedCaps, homeSnapshot.channels.single().channelCapabilities)
    }

    @Test
    fun perViewerChannelRepositoriesWriteAuthorityWithRequestStartObservedSeq() = runTest {
        val store = SharedChannelAuthorityStore()
        val sessionStore =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "ada@raft.build"),
                    accessToken = "access",
                    refreshToken = "refresh",
                    selectedServerId = "server-1",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Raft", role = "owner")),
                )
            )
        val listGate = CompletableDeferred<HomeNetworkResponse>()
        val client =
            object : RaftNetworkClient {
                override suspend fun request(
                    priority: DataRequestPriority,
                    path: String,
                    method: String,
                    query: List<Pair<String, String>>,
                    serverIdOverride: ServerId?,
                    bodyText: String?,
                ): HomeNetworkResponse =
                    when {
                        path == "channels" && method == "GET" -> listGate.await()
                        path == "channels" && method == "POST" ->
                            HomeNetworkResponse(
                                code = 201,
                                bodyText =
                                    """
                                    {
                                      "id": "ch-created",
                                      "serverId": "server-1",
                                      "name": "ops",
                                      "type": "channel",
                                      "channelRole": "admin",
                                      "channelAdminBasis": "server_owner",
                                      "channelAuthorityRevision": 1,
                                      "channelCapabilities": {
                                        "addChannelMembers": true,
                                        "editChannelMetadata": true
                                      }
                                    }
                                    """.trimIndent(),
                            )
                        path == "channels/ch-joint" && method == "GET" ->
                            HomeNetworkResponse(
                                code = 200,
                                bodyText =
                                    """
                                    {
                                      "id": "ch-joint",
                                      "serverId": "server-1",
                                      "name": "partners",
                                      "type": "joint",
                                      "jointRole": "participant",
                                      "channelRole": "admin",
                                      "channelAdminBasis": "connected_server_owner",
                                      "channelAuthorityRevision": 2,
                                      "channelCapabilities": {
                                        "addChannelMembers": true,
                                        "removeChannelMembers": true,
                                        "deleteChannels": true
                                      }
                                    }
                                    """.trimIndent(),
                            )
                        path == "channels/ch-joint/members" && method == "GET" ->
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"humans":[{"id":"user-1","name":"Ada"}],"agents":[]}""",
                            )
                        else -> HomeNetworkResponse(code = 404, bodyText = null)
                    }
            }

        val channelRepo = RealChannelRepository(sessionStore, client, channelAuthorityStore = store)
        val conversationRepo = RealConversationRepository(sessionStore, client, channelAuthorityStore = store)
        val memberRepo = RealMemberRepository(sessionStore, client, channelAuthorityStore = store)
        val jointRepo =
            RealJointChannelRepository(
                networkClient = client,
                channelAuthorityStore = store,
                serverIdProvider = { "server-1" },
            )

        // Start GET /channels first (captures lower observedSeq), then complete GET /channels/:id (higher observedSeq).
        val listJob = launch { channelRepo.getChannels(ServerId("server-1")) }
        runCurrent()

        val detailResult = conversationRepo.getConversationDetail(ChannelId("ch-joint"), DataRequestPriority.Visible)
        assertIs<DataResult.Content<*>>(detailResult)
        assertTrue(authorityAllows(store.viewOf("server-1", "ch-joint")) { it.addChannelMembers })
        assertEquals(2L, store.authorityOf("server-1", "ch-joint")?.revision)

        // Now settle the older GET /channels with stale rev 1 (addChannelMembers = false): must NOT overwrite detail.
        listGate.complete(
            HomeNetworkResponse(
                code = 200,
                bodyText =
                    """
                    [
                      {
                        "id": "ch-joint",
                        "serverId": "server-1",
                        "name": "partners",
                        "type": "joint",
                        "channelRole": "member",
                        "channelAuthorityRevision": 1,
                        "channelCapabilities": { "addChannelMembers": false }
                      },
                      {
                        "id": "ch-other",
                        "serverId": "server-1",
                        "name": "general",
                        "type": "channel",
                        "channelRole": "member",
                        "channelAuthorityRevision": 1,
                        "channelCapabilities": { "archiveChannels": true }
                      }
                    ]
                    """.trimIndent(),
            )
        )
        listJob.join()
        assertTrue(authorityAllows(store.viewOf("server-1", "ch-joint")) { it.addChannelMembers })
        assertEquals(2L, store.authorityOf("server-1", "ch-joint")?.revision)
        assertTrue(authorityAllows(store.viewOf("server-1", "ch-other")) { it.archiveChannels })

        // POST /channels writes created channel authority.
        val created = channelRepo.createChannel(CreateChannelRequest(name = "ops"))
        assertIs<DataResult.Content<*>>(created)
        assertTrue(authorityAllows(store.viewOf("server-1", "ch-created")) { it.editChannelMetadata })

        // RealMemberRepository.getChannelMembers and RealJointChannelRepository.loadConnections also write authority.
        store.resetServer("server-1")
        assertIs<DataResult.Content<*>>(memberRepo.getChannelMembers(DataRequestPriority.Visible, ChannelId("ch-joint")))
        assertTrue(authorityAllows(store.viewOf("server-1", "ch-joint")) { it.removeChannelMembers })

        store.resetServer("server-1")
        assertIs<DataResult.Content<*>>(jointRepo.loadConnections(ChannelId("ch-joint"), DataRequestPriority.Visible))
        assertTrue(authorityAllows(store.viewOf("server-1", "ch-joint")) { it.deleteChannels })
    }

    @Test
    fun globalClearFencesStaleInFlightRequestsAcrossAllServers() {
        val store = SharedChannelAuthorityStore()
        val tokenA = store.captureRequestToken("server-a")
        val tokenB = store.captureRequestToken("server-b")
        store.writeSingleAnswer(
            tokenA,
            ChannelId("ch-1"),
            ChannelViewerAuthority(capabilities = ChannelCapabilities(archiveChannels = true), revision = 1L),
        )
        assertNotNull(store.authorityOf("server-a", "ch-1"))

        store.clear()
        assertNull(store.authorityOf("server-a", "ch-1"))
        assertIs<ChannelViewerAuthorityView.Loading>(store.viewOf("server-a", "ch-1"))

        // Token captured before clear is rejected.
        assertFalse(
            store.writeSingleAnswer(
                tokenB,
                ChannelId("ch-2"),
                ChannelViewerAuthority(capabilities = ChannelCapabilities(archiveChannels = true), revision = 1L),
            )
        )
        assertNull(store.authorityOf("server-b", "ch-2"))
    }

    @Test
    fun authorityHelpersFailClosedWhileLoadingAndHonourKnownCapabilities() {
        val loading: ChannelViewerAuthorityView = ChannelViewerAuthorityView.Loading
        assertFalse(authorityAllows(loading) { it.addChannelMembers })
        assertFalse(authorityAllows(loading) { it.archiveChannels })
        assertFalse(authorityCanManageChannel(loading))
        assertEquals(ChannelCapabilities(), knownCapabilities(loading))

        val visitorOnly =
            ChannelViewerAuthorityView.Known(
                ChannelViewerAuthority(capabilities = ChannelCapabilities(manageVisitorAccess = true))
            )
        assertTrue(authorityAllows(visitorOnly) { it.manageVisitorAccess })
        assertFalse(authorityAllows(visitorOnly) { it.archiveChannels })
        assertFalse(authorityCanManageChannel(visitorOnly))

        val memberAdder =
            ChannelViewerAuthorityView.Known(
                ChannelViewerAuthority(capabilities = ChannelCapabilities(addChannelMembers = true))
            )
        assertTrue(authorityAllows(memberAdder) { it.addChannelMembers })
        assertTrue(authorityCanManageChannel(memberAdder))

        val deleteOnly =
            ChannelViewerAuthorityView.Known(
                ChannelViewerAuthority(capabilities = ChannelCapabilities(deleteChannels = true))
            )
        assertTrue(authorityCanManageChannel(deleteOnly))
    }
}
