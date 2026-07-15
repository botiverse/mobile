package build.raft.app.core.data.realtime

import build.raft.app.data.StoredAuthServer as AuthServer
import build.raft.app.data.StoredAuthSession as AuthSession
import build.raft.app.data.SlockAuthSessionStore as AuthSessionStore
import build.raft.app.data.StoredAuthUser as AuthUser
import build.raft.app.data.AuthApiResult
import build.raft.app.data.AuthLoginResponse
import build.raft.app.data.AuthMobileOAuthLinkCompleteResponse
import build.raft.app.data.AuthMobileOAuthStartResponse
import build.raft.app.data.AuthRefreshResponse
import build.raft.app.data.AuthSocialCompleteResponse
import build.raft.app.data.ServerSwitcherOrderPreference
import build.raft.app.data.SlockAuthApi
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.DataResult
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SharedAuthTokenProvider
import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.RealtimeConnectionWitnessToken
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.RealtimeSocketTransport
import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageType
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadUpdated
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeRepositoryTest {
    @Test
    fun clientAggregatesSuccessfulEventDiagnosticsInsteadOfLoggingEveryPipelineStage() {
        val transport = FakeRealtimeSocketTransport()
        val logs = mutableListOf<String>()
        val client = SocketIoRealtimeClient(transport = transport, logger = logs::add)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        transport.receive(
            RealtimeSocketEvents.MessageNew,
            mapOf(
                "serverId" to "server-1",
                "id" to "message-1",
                "threadId" to "channel-1",
                "channelId" to "channel-1",
                "seq" to 89,
                "content" to "Realtime body",
                "messageType" to "chat",
                "senderType" to "user",
                "senderId" to "user-1",
                "senderName" to "Artin"
            )
        )

        val summary = logs.single { it.startsWith("event_success_summary ") }
        assertTrue(summary.contains("count=1"))
        assertTrue(summary.contains("types=message_new:1"))
        assertTrue(summary.contains("rawFanOut=1"))
        assertFalse(logs.any { it.startsWith("event_map_parse ") })
        assertFalse(logs.any { it.startsWith("event_received ") })
        assertFalse(logs.any { it.startsWith("event_dispatch ") })
    }

    @Test
    fun clientLogsIgnoredRealtimePayloadWithoutWaitingForSummarySampling() {
        val transport = FakeRealtimeSocketTransport()
        val logs = mutableListOf<String>()
        val rawEvents = mutableListOf<String>()
        val client = SocketIoRealtimeClient(transport = transport, logger = logs::add)
        client.observeRawEvents { _, eventName, _ -> rawEvents += eventName }

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        transport.receive(RealtimeSocketEvents.MessageNew, mapOf("serverId" to "server-1"))

        assertTrue(logs.any { it.startsWith("event_map_ignored eventName=${RealtimeSocketEvents.MessageNew} ") })
        assertEquals(listOf(RealtimeSocketEvents.MessageNew), rawEvents)
    }

    @Test
    fun successDiagnosticsEmitFirstPeriodicAndSlowParseSignals() {
        var now = 0L
        val diagnostics =
            RealtimeSuccessDiagnostics(
                clockMillis = { now },
                summaryEventCount = 3,
                summaryIntervalMillis = 1_000L,
                slowParseMillis = 16L
            )

        fun record(parseDurationMs: Long = 1L): List<String> =
            diagnostics.record(
                eventName = RealtimeSocketEvents.MessageNew,
                mappedType = "message_new",
                parseDurationMs = parseDurationMs,
                appliedSeq = 10L,
                serverMaxSeq = 10L,
                listenerCount = 1,
                rawFanOut = true,
                serverId = "server-1"
            )

        assertTrue(record().single().contains("count=1"))
        assertTrue(record().isEmpty())
        assertTrue(record().isEmpty())
        assertTrue(record().single().contains("count=3"))
        assertTrue(record(parseDurationMs = 20L).single().startsWith("event_parse_slow "))
        now = 1_001L
        val intervalLogs = record()
        assertEquals(1, intervalLogs.count { it.startsWith("event_success_summary ") })
        assertTrue(intervalLogs.single { it.startsWith("event_success_summary ") }.contains("count=2"))
    }

    @Test
    fun reconnectWithTheSameWitnessRejectsThePreviousSocketGeneration() {
        val fence = RealtimeSocketGenerationFence()
        val context =
            RealtimeSocketConnectionContext(
                serverId = ServerId("server-1"),
                witness =
                    RealtimeConnectionWitnessToken(
                        scopeLeaseId = "lease-1",
                        principalId = "user-1",
                        authGeneration = 1,
                        connectionFamilyId = "family-1",
                    ),
            )
        val firstSocketGeneration = fence.begin(context)
        val secondSocketGeneration = fence.begin(context)
        var downstreamDeliveries = 0

        if (fence.accepts(firstSocketGeneration)) downstreamDeliveries += 1
        if (fence.accepts(secondSocketGeneration)) downstreamDeliveries += 1

        assertEquals(1, downstreamDeliveries)
        assertFalse(fence.accepts(firstSocketGeneration))
        assertTrue(fence.accepts(secondSocketGeneration))
    }

    @Test
    fun repositoryInjectsSessionTokenAndSelectedServerIntoRealtimeClient() {
        val transport = FakeRealtimeSocketTransport()
        val repository = SessionRealtimeRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            client = SocketIoRealtimeClient(transport)
        )

        val result = runSuspend { repository.connect(ServerId("server-1"), lastSeq = 42) }

        assertTrue(result is DataResult.Content)
        assertEquals("sample-access", transport.lastAuth["token"])
        assertEquals("server-1", transport.lastAuth["serverId"])

        transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertEquals(RealtimeSocketEvents.SyncResume, transport.emitted.single().first)
        assertEquals(42L, transport.emitted.single().second["lastSeq"])
    }

    @Test
    fun repositoryRejectsMismatchedServerWithoutConnectingTransport() {
        val transport = FakeRealtimeSocketTransport()
        val repository = SessionRealtimeRepository(
            store = InMemoryAuthSessionStore(sampleSession),
            client = SocketIoRealtimeClient(transport)
        )

        val result = runSuspend { repository.connect(ServerId("server-2"), lastSeq = 0) }

        assertTrue(result is DataResult.NoPermission)
        assertTrue(transport.lastAuth.isEmpty())
    }

    @Test
    fun repositoryConsumesCommittedTokenBeforeRealtimeConnectWithoutRefreshing() {
        val transport = FakeRealtimeSocketTransport()
        val store = InMemoryAuthSessionStore(sampleSession.copy(accessToken = "opaque-access"))
        val authApi = FakeAuthApi()
        val repository = SessionRealtimeRepository(
            store = store,
            client = SocketIoRealtimeClient(transport),
            tokenProvider = SharedAuthTokenProvider(
                sessionStore = store,
                authApi = authApi,
                clockMillis = { 0L },
                logger = {},
                warningLogger = {}
            )
        )

        val result = runSuspend { repository.connect(ServerId("server-1"), lastSeq = 0) }

        assertTrue(result is DataResult.Content)
        assertEquals("opaque-access", transport.lastAuth["token"])
        assertEquals("opaque-access", store.session?.accessToken)
        assertEquals(0, authApi.refreshCallCount)
    }

    @Test
    fun repositoryReusesCommittedSocketAuthOnReconnectAttempt() {
        val transport = FakeRealtimeSocketTransport()
        val store = InMemoryAuthSessionStore(sampleSession.copy(accessToken = "opaque-access"))
        val authApi =
            FakeAuthApi(
                responses =
                    listOf(
                        AuthRefreshResponse("socket-first-access", "socket-first-refresh"),
                        AuthRefreshResponse("socket-retry-access", "socket-retry-refresh")
                    )
            )
        val repository = SessionRealtimeRepository(
            store = store,
            client = SocketIoRealtimeClient(transport),
            tokenProvider = SharedAuthTokenProvider(
                sessionStore = store,
                authApi = authApi,
                clockMillis = { 0L },
                logger = {},
                warningLogger = {}
            )
        )

        runSuspend { repository.connect(ServerId("server-1"), lastSeq = 0) }
        assertEquals("opaque-access", transport.lastAuth["token"])

        transport.receive(RealtimeSocketEvents.ReconnectAttempt, 1)

        assertEquals("opaque-access", transport.lastAuth["token"])
        assertEquals("opaque-access", store.session?.accessToken)
        assertEquals(0, authApi.refreshCallCount)
    }

    @Test
    fun repositoryRefreshesSocketAuthAfterExplicitAuthConnectError() {
        val transport = FakeRealtimeSocketTransport()
        val store = InMemoryAuthSessionStore(sampleSession.copy(accessToken = "opaque-access"))
        val authApi =
            FakeAuthApi(
                responses =
                    listOf(
                        AuthRefreshResponse("socket-first-access", "socket-first-refresh"),
                        AuthRefreshResponse("socket-retry-access", "socket-retry-refresh")
                    )
            )
        val repository = SessionRealtimeRepository(
            store = store,
            client = SocketIoRealtimeClient(transport),
            tokenProvider = SharedAuthTokenProvider(
                sessionStore = store,
                authApi = authApi,
                clockMillis = { 0L },
                logger = {},
                warningLogger = {}
            )
        )

        runSuspend { repository.connect(ServerId("server-1"), lastSeq = 0) }
        assertEquals("opaque-access", transport.lastAuth["token"])

        transport.receive(RealtimeSocketEvents.ConnectError, "invalid token")

        assertEquals("socket-first-access", transport.lastAuth["token"])
        assertEquals("socket-first-access", store.session?.accessToken)
        assertEquals(1, authApi.refreshCallCount)
    }

    @Test
    fun pinnedRealtimeSessionKeepsServerDuringProcessSelectionChange() {
        val transport = FakeRealtimeSocketTransport()
        val store =
            InMemoryAuthSessionStore(
                sampleSession.copy(
                    selectedServerId = "server-2",
                    servers = sampleSession.servers + StoredAuthServer("server-2", "Server Two")
                )
            )
        val authApi = FakeAuthApi(responses = listOf(AuthRefreshResponse("rotated-access", "rotated-refresh")))
        val client = SocketIoRealtimeClient(transport)
        SessionRealtimeRepository(
            store = store,
            client = client,
            pinnedServerId = ServerId("server-1"),
            tokenProvider =
                SharedAuthTokenProvider(
                    sessionStore = store,
                    authApi = authApi,
                    clockMillis = { 0L },
                    logger = {},
                    warningLogger = {}
                )
        )
        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth("opaque-access", ServerId("server-1")),
                lastSeq = 0L
            )
        )

        transport.receive(RealtimeSocketEvents.ConnectError, "invalid token")

        assertEquals("rotated-access", transport.lastAuth["token"])
        assertEquals("server-1", transport.lastAuth["serverId"])
        assertEquals("server-2", store.session?.currentServerId)
    }

    @Test
    fun repositoryDoesNotFallbackToStaleTokenWhenRefreshIsCancelled() {
        val transport = FakeRealtimeSocketTransport()
        val store = InMemoryAuthSessionStore(sampleSession.copy(accessToken = "opaque-access"))
        val repository = SessionRealtimeRepository(
            store = store,
            client = SocketIoRealtimeClient(transport),
            tokenProvider = SharedAuthTokenProvider(
                sessionStore = store,
                authApi = FakeAuthApi(refreshFailure = CancellationException("cancelled")),
                clockMillis = { 0L },
                logger = {},
                warningLogger = {}
            )
        )
        var cancelled = false

        runSuspend { repository.connect(ServerId("server-1"), lastSeq = 0) }
        try {
            transport.receive(RealtimeSocketEvents.ConnectError, "invalid token")
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals("opaque-access", transport.lastAuth["token"])
        assertEquals("opaque-access", store.session?.accessToken)
    }

    @Test
    fun repositoryPreservesSessionWhenRealtimeRefreshIsUnauthorized() {
        val transport = FakeRealtimeSocketTransport()
        val store =
            InMemoryAuthSessionStore(
                sampleSession.copy(
                    accessToken = "unauthorized-opaque-access",
                    refreshToken = "unauthorized-refresh"
                )
            )
        val repository = SessionRealtimeRepository(
            store = store,
            client = SocketIoRealtimeClient(transport),
            tokenProvider = SharedAuthTokenProvider(
                sessionStore = store,
                authApi = FakeAuthApi(refreshFailureCode = 401),
                clockMillis = { 0L },
                logger = {},
                warningLogger = {}
            )
        )

        val result = runSuspend { repository.connect(ServerId("server-1"), lastSeq = 0) }
        transport.receive(RealtimeSocketEvents.ConnectError, "invalid token")

        assertTrue(result is DataResult.Content)
        assertEquals("unauthorized-opaque-access", transport.lastAuth["token"])
        assertEquals("unauthorized-opaque-access", store.session?.accessToken)
    }

    @Test
    fun clientMapsHeartbeatResumeMessagesAndJoinChannel() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)
        val events = mutableListOf<RealtimeEvent>()
        client.observeEvents { events += it }

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 12))
        transport.receive(
            RealtimeSocketEvents.MessageNew,
            mapOf(
                "id" to "message-1",
                "threadId" to "thread-1",
                "channelId" to "channel-1",
                "seq" to 13,
                "content" to "Hello",
                "messageType" to "chat",
                "senderType" to "user",
                "senderId" to "user-1",
                "senderName" to "Artin"
            )
        )
        transport.receive(
            RealtimeSocketEvents.SyncResumeResponse,
            mapOf(
                "currentSeq" to 14,
                "hasMore" to false,
                "messages" to listOf(
                    mapOf(
                        "id" to "message-2",
                        "threadId" to "thread-1",
                        "channelId" to "channel-1",
                        "seq" to 14,
                        "content" to "Resumed",
                        "messageType" to "chat",
                        "senderType" to "user",
                        "senderId" to "user-2",
                        "senderName" to "Cindy"
                    )
                )
            )
        )

        assertEquals(RealtimeSocketEvents.JoinChannel, transport.emitted.single().first)
        assertEquals("channel-1", transport.emitted.single().second["channelId"])
        assertTrue(events[0] is Heartbeat)
        assertEquals(12L, (events[0] as Heartbeat).seq)
        assertEquals("Hello", (events[1] as MessageNew).message.body)
        assertEquals(14L, (events[2] as SyncResumeResponse).currentSeq)
        assertEquals("Resumed", (events[2] as SyncResumeResponse).messages.single().body)
    }

    @Test
    fun clientDoesNotCommitResumeCursorAcrossRejectedMessageAndSelectsRecovery() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)
        val events = mutableListOf<RealtimeEvent>()
        client.observeEvents { events += it }

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1")),
                lastSeq = 10,
            )
        )
        val poisonedResume =
            mapOf(
                "currentSeq" to 12,
                "hasMore" to false,
                "messages" to
                    listOf(
                        mapOf(
                            "id" to "message-valid",
                            "seq" to 11,
                            "threadId" to "thread-1",
                            "channelId" to "channel-1",
                            "content" to "Valid",
                            "messageType" to "chat",
                            "senderType" to "user",
                            "senderId" to "user-1",
                            "senderName" to "Artin",
                        ),
                        mapOf(
                            "id" to "message-invalid",
                            "seq" to 12,
                            "threadId" to "thread-1",
                            "channelId" to "channel-1",
                            "content" to "Invalid",
                            "messageType" to "chat",
                            "senderType" to "system",
                            "senderId" to "system",
                            "senderName" to "System",
                        ),
                    ),
            )
        transport.receive(RealtimeSocketEvents.SyncResumeResponse, poisonedResume)

        val recovery = events.single() as Heartbeat
        assertEquals(12L, recovery.seq)
        assertTrue(events.none { it is SyncResumeResponse })

        val retry = transport.emitted.single { it.first == RealtimeSocketEvents.SyncResume }
        assertEquals(10L, retry.second["lastSeq"])

        transport.receive(RealtimeSocketEvents.SyncResumeResponse, poisonedResume)

        assertEquals(2, events.filterIsInstance<Heartbeat>().size)
        assertEquals(1, transport.emitted.count { it.first == RealtimeSocketEvents.SyncResume })
        val escalation = events.filterIsInstance<RealtimeRefreshRequired>().single()
        assertEquals(RealtimeRefreshScope.Home, escalation.scope)
        assertEquals("sync_resume_projection_rejected_repeatedly", escalation.reason)
        assertEquals(ServerId("server-1"), escalation.serverId)
    }

    @Test
    fun clientDoesNotFanOutStableHeartbeatToRawListeners() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)
        val events = mutableListOf<RealtimeEvent>()
        val rawEvents = mutableListOf<String>()
        client.observeEvents { events += it }
        client.observeRawEvents { _, eventName, _ -> rawEvents += eventName }

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1")),
                lastSeq = 42
            )
        )
        transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 42))

        assertTrue(events.single() is Heartbeat)
        assertTrue(rawEvents.isEmpty())
    }

    @Test
    fun clientFansOutHeartbeatOnlyWhenServerIsAhead() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)
        val rawEvents = mutableListOf<String>()
        client.observeRawEvents { _, eventName, _ -> rawEvents += eventName }

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1")),
                lastSeq = 42
            )
        )
        transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 43))
        transport.receive(
            RealtimeSocketEvents.MessageNew,
            mapOf(
                "id" to "message-raw",
                "threadId" to "thread-1",
                "channelId" to "channel-1",
                "seq" to 43,
                "content" to "Caught up",
                "messageType" to "chat",
                "senderType" to "user",
                "senderId" to "user-1",
                "senderName" to "Artin"
            )
        )
        transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 43))

        assertEquals(
            listOf(RealtimeSocketEvents.Heartbeat, RealtimeSocketEvents.MessageNew),
            rawEvents
        )
    }

    @Test
    fun clientReplaysJoinedChannelsAfterConnectButNotRoomsJoinedAck() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        client.joinChannel(ChannelId("channel-2"))
        transport.emitted.clear()

        transport.receive(RealtimeSocketEvents.Connect)

        assertEquals(
            listOf("channel-1", "channel-2"),
            transport.emitted.map { it.second["channelId"] }
        )
        transport.emitted.clear()

        transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertTrue(transport.emitted.isEmpty())
    }

    @Test
    fun clientDoesNotEmitDuplicateJoinForAlreadyJoinedChannel() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        client.joinChannel(ChannelId("channel-1"))
        client.joinChannel(ChannelId("channel-1"))

        assertEquals(
            listOf("channel-1"),
            transport.emitted.map { it.second["channelId"] }
        )
    }

    @Test
    fun clientLeavesJoinedChannelAndDoesNotReplayItAfterReconnect() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        client.joinChannel(ChannelId("channel-2"))
        client.leaveChannel(ChannelId("channel-1"))
        client.leaveChannel(ChannelId("channel-1"))

        assertEquals(
            listOf(
                RealtimeSocketEvents.JoinChannel,
                RealtimeSocketEvents.JoinChannel,
                RealtimeSocketEvents.LeaveChannel
            ),
            transport.emitted.map { it.first }
        )
        assertEquals("channel-1", transport.emitted.last().second["channelId"])

        transport.emitted.clear()
        transport.receive(RealtimeSocketEvents.Reconnect)

        val joinRooms =
            transport.emitted
                .filter { it.first == RealtimeSocketEvents.JoinChannel }
                .map { it.second["channelId"] }
        assertEquals(listOf("channel-2"), joinRooms)
    }

    @Test
    fun clientRoomCommandEmitOwnsJoinedRoomStateForReconnect() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.emitRoomCommand(RealtimeSocketEvents.JoinChannel, "channel-1")
        client.emitRoomCommand(RealtimeSocketEvents.JoinChannel, "channel-1")
        client.emitRoomCommand(RealtimeSocketEvents.JoinChannel, "channel-2")
        client.emitRoomCommand(RealtimeSocketEvents.LeaveChannel, "channel-1")

        assertEquals(
            listOf(
                RealtimeSocketEvents.JoinChannel,
                RealtimeSocketEvents.JoinChannel,
                RealtimeSocketEvents.LeaveChannel
            ),
            transport.emitted.map { it.first }
        )

        transport.emitted.clear()
        transport.receive(RealtimeSocketEvents.Reconnect)

        assertEquals(
            listOf("channel-2"),
            transport.emitted
                .filter { it.first == RealtimeSocketEvents.JoinChannel }
                .map { it.second["channelId"] }
        )
    }

    @Test
    fun clientForcedRoomCommandReemitsJoinedRoom() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.emitRoomCommand(RealtimeSocketEvents.JoinChannel, "channel-1")
        client.emitRoomCommand(RealtimeSocketEvents.JoinChannel, "channel-1", force = true)

        assertEquals(
            listOf("channel-1", "channel-1"),
            transport.emitted
                .filter { it.first == RealtimeSocketEvents.JoinChannel }
                .map { it.second["channelId"] }
        )
    }

    @Test
    fun androidTransportUsesRawChannelIdForRoomCommands() {
        val joinPayload =
            realtimeSocketIoWirePayload(
                RealtimeSocketEvents.JoinChannel,
                mapOf("channelId" to "channel-1")
            )
        val leavePayload =
            realtimeSocketIoWirePayload(
                RealtimeSocketEvents.LeaveChannel,
                mapOf("channelId" to "channel-1")
            )

        assertEquals("channel-1", joinPayload)
        assertEquals("channel-1", leavePayload)
    }

    @Test
    fun androidTransportKeepsObjectPayloadForSyncResume() {
        val payload =
            realtimeSocketIoWirePayload(
                RealtimeSocketEvents.SyncResume,
                mapOf("lastSeq" to 88L)
            ) as JSONObject

        assertEquals(88L, payload.getLong("lastSeq"))
    }

    @Test
    fun clientReplaysRoomsAndResumeAfterReconnect() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)
        val events = mutableListOf<RealtimeEvent>()
        client.observeEvents { events += it }

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        client.joinChannel(ChannelId("thread-1"))
        transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 88))
        transport.emitted.clear()

        transport.receive(RealtimeSocketEvents.Reconnect)

        val joinRooms =
            transport.emitted
                .filter { it.first == RealtimeSocketEvents.JoinChannel }
                .map { it.second["channelId"] }
        assertEquals(listOf("channel-1", "thread-1"), joinRooms)
        assertFalse(transport.emitted.any { it.first == RealtimeSocketEvents.SyncResume })
        assertTrue(events.single() is Heartbeat)
    }

    @Test
    fun clientDoesNotAdvanceResumeCursorFromHeartbeat() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1")),
                lastSeq = 41
            )
        )
        transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 88))

        transport.receive(RealtimeSocketEvents.RoomsJoined)

        val resume = transport.emitted.single { it.first == RealtimeSocketEvents.SyncResume }
        assertEquals(41L, resume.second["lastSeq"])
    }

    @Test
    fun clientRebaselineSkipsResumeCursorAfterReconnect() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        transport.receive(
            RealtimeSocketEvents.MessageNew,
            mapOf(
                "id" to "message-1",
                "threadId" to "channel-1",
                "channelId" to "channel-1",
                "seq" to 89,
                "content" to "Realtime body",
                "messageType" to "chat",
                "senderType" to "user",
                "senderId" to "user-1",
                "senderName" to "Artin"
            )
        )
        transport.emitted.clear()

        transport.receive(RealtimeSocketEvents.Reconnect)

        assertFalse(transport.emitted.any { it.first == RealtimeSocketEvents.SyncResume })
        transport.receive(RealtimeSocketEvents.RoomsJoined)

        assertFalse(transport.emitted.any { it.first == RealtimeSocketEvents.SyncResume })
    }

    @Test
    fun clientForcesReconnectWhenAppHeartbeatAndInboundEventsAreSilent() {
        var now = 1_000L
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport, clockMillis = { now })

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        transport.receive(RealtimeSocketEvents.Connect)
        transport.receive(RealtimeSocketEvents.Heartbeat, mapOf("seq" to 1))
        val firstConnectCount = transport.connectCount

        now += 121_000L
        assertTrue(client.checkHeartbeatSilence(now))

        assertEquals(firstConnectCount + 1, transport.connectCount)
        assertEquals("sample-access", transport.lastAuth["token"])
    }

    @Test
    fun clientClearsJoinedChannelsWhenServerChanges() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        client.joinChannel(ChannelId("channel-1"))
        transport.emitted.clear()

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-2"))
            )
        )
        transport.receive(RealtimeSocketEvents.Connect)

        assertTrue(transport.emitted.isEmpty())
    }

    @Test
    fun clientDropsEventsWhosePayloadServerDoesNotMatchConnectedServer() {
        val transport = FakeRealtimeSocketTransport()
        val client = SocketIoRealtimeClient(transport)
        val events = mutableListOf<RealtimeEvent>()
        client.observeEvents { events += it }

        client.connect(
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "sample-access", serverId = ServerId("server-1"))
            )
        )
        transport.receive(
            RealtimeSocketEvents.MessageNew,
            mapOf(
                "serverId" to "server-2",
                "id" to "message-1",
                "threadId" to "thread-1",
                "channelId" to "channel-1",
                "content" to "Wrong server",
                "messageType" to "chat",
                "senderType" to "user",
                "senderId" to "user-1",
                "senderName" to "Artin"
            )
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun mapperHandlesTaskAndThreadEvents() {
        val mapper = RealtimeEventMapper()

        val thread = mapper.map(
            RealtimeSocketEvents.ThreadUpdated,
            mapOf(
                "parentMessageId" to "message-1",
                "threadChannelId" to "thread-channel-1",
                "replyCount" to 3,
                "participantIds" to listOf("user-1", "agent-1")
            )
        )
        val task = mapper.map(
            RealtimeSocketEvents.TaskUpdated,
            mapOf(
                "task" to mapOf(
                    "id" to "task-1",
                    "title" to "Review Android auth",
                    "status" to "in_review",
                    "taskNumber" to 118
                )
            )
        )

        assertTrue(thread is ThreadUpdated)
        assertEquals("message-1", (thread as ThreadUpdated).parentMessageId.value)
        assertEquals("thread-channel-1", thread.threadChannelId.value)
        assertEquals(3, thread.replyCount)
        assertTrue(task is TaskUpdated)
        assertEquals(118, (task as TaskUpdated).task.taskNumber)
    }

    @Test
    fun mapperHandlesRealServerTaskShapeInRealtimeTaskEvents() {
        val mapper = RealtimeEventMapper()

        val task = mapper.map(
            RealtimeSocketEvents.TaskUpdated,
            mapOf(
                "task" to mapOf(
                    "id" to "message-task-1",
                    "title" to "Open replies from Tasks",
                    "status" to "closed",
                    "channelType" to "channel",
                    "channelId" to "parent-channel-1",
                    "messageId" to "parent-message-1",
                    "createdById" to "user-1",
                    "createdByName" to "Author",
                    "createdByType" to "user",
                    "claimedById" to "agent-1",
                    "claimedByName" to "Android-App-Integrator",
                    "claimedByType" to "agent"
                )
            )
        )

        // Canonical TaskInfo shape (see packages/server/src/services/taskService.ts).
        // Realtime payload carries channel + assignee + creator identity only — no
        // thread linkage; thread navigation goes through /api/tasks/:id.
        assertTrue(task is TaskUpdated)
        val summary = (task as TaskUpdated).task
        assertEquals(TaskStatus.Closed, summary.status)
        assertEquals("parent-channel-1", summary.channelId?.value)
        assertEquals("parent-message-1", summary.messageId?.value)
        assertEquals(MemberKind.Agent, summary.assignee?.kind)
        assertEquals("agent-1", summary.assignee?.id?.value)
        assertEquals(MemberKind.Human, summary.creator?.kind)
        assertEquals("user-1", summary.creator?.id?.value)
    }

    @Test
    fun mapperHandlesMemberPresenceEvents() {
        val mapper = RealtimeEventMapper()

        val agentActivity = mapper.map(
            RealtimeSocketEvents.AgentActivity,
            mapOf(
                "agentId" to "agent-1",
                "activity" to "working",
                "detail" to "Running tests"
            )
        )
        // server:member-* events carry only `{ serverId, userId }` (see
        // packages/server/src/routes/servers.ts). They are membership-change
        // notifications, not presence updates — mapper emits a refresh intent
        // so consumers refetch members instead of fabricating presence state.
        val memberUpdated = mapper.map(
            RealtimeSocketEvents.ServerMemberUpdated,
            mapOf(
                "serverId" to "server-1",
                "userId" to "user-2"
            )
        )

        assertTrue(agentActivity is MemberPresenceUpdated)
        val agent = agentActivity as MemberPresenceUpdated
        assertEquals("agent-1", agent.memberId.value)
        assertEquals(MemberKind.Agent, agent.kind)
        assertEquals(AgentStatus.Working, agent.agentStatus)
        assertEquals(Presence.Active, agent.presence)
        assertEquals("Running tests", agent.statusLabel)
        assertTrue(memberUpdated is RealtimeRefreshRequired)
        val refresh = memberUpdated as RealtimeRefreshRequired
        assertEquals(RealtimeRefreshScope.ServerMembers, refresh.scope)
        assertEquals("server-1", refresh.serverId?.value)
        assertEquals("user-2", refresh.memberId?.value)

        val disconnected = mapper.map(
            RealtimeSocketEvents.AgentActivity,
            mapOf(
                "agentId" to "agent-1",
                "activity" to "offline"
            )
        ) as MemberPresenceUpdated
        assertEquals(AgentStatus.Offline, disconnected.agentStatus)
        assertEquals(Presence.Offline, disconnected.presence)
    }

    @Test
    fun mapperHandlesRefreshAndMembershipRemovalEvents() {
        val mapper = RealtimeEventMapper()

        val channelMembers = mapper.map(
            RealtimeSocketEvents.ChannelMembersUpdated,
            mapOf("serverId" to "server-1", "channelId" to "channel-1")
        )
        val membershipRemoved = mapper.map(
            RealtimeSocketEvents.ServerMembershipRemoved,
            mapOf("serverId" to "server-1")
        )
        val machineStatus = mapper.map(
            RealtimeSocketEvents.MachineStatus,
            mapOf("serverId" to "server-1", "machineId" to "machine-1", "status" to "online")
        )

        assertTrue(channelMembers is RealtimeRefreshRequired)
        assertEquals(RealtimeRefreshScope.ChannelMembers, (channelMembers as RealtimeRefreshRequired).scope)
        assertEquals("channel-1", channelMembers.channelId?.value)
        assertTrue(membershipRemoved is ServerMembershipRemoved)
        assertEquals("server-1", (membershipRemoved as ServerMembershipRemoved).serverId.value)
        assertTrue(machineStatus is RealtimeRefreshRequired)
        assertEquals(RealtimeRefreshScope.Machines, (machineStatus as RealtimeRefreshRequired).scope)
        assertEquals("machine-1", machineStatus.subjectId)
    }

    @Test
    fun mapperHandlesSocketIoJsonObjectPayloads() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            JSONObject(
                """
                {
                  "id": "message-json",
                  "threadId": "thread-json",
                  "channelId": "channel-json",
                  "seq": 77,
                  "content": "Socket payload",
                  "messageType": "chat",
                  "senderType": "user",
                  "senderId": "user-json",
                  "senderName": "Socket User"
                }
                """.trimIndent()
            )
        )

        assertTrue(event is MessageNew)
        val message = (event as MessageNew).message
        assertEquals("message-json", message.id.value)
        assertEquals("channel-json", message.threadId.value)
        assertEquals("channel-json", message.channelId?.value)
        assertEquals(77L, message.seq)
        assertEquals("Socket payload", message.body)
        assertEquals("Socket User", message.author.displayName)
    }

    @Test
    fun mapperRejectsLegacyMessageEnvelopeFallback() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            JSONObject(
                """
                {
                  "message": {
                    "id": "message-envelope",
                    "threadId": "thread-envelope",
                    "channelId": "channel-envelope",
                    "content": "Envelope fallback",
                    "messageType": "chat",
                    "senderType": "user",
                    "senderId": "user-envelope",
                    "senderName": "Envelope User"
                  }
                }
                """.trimIndent()
            )
        )

        assertNull(event)
    }

    @Test
    fun mapperPreservesSocketIoMessageAttachments() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            JSONObject(
                """
                {
                  "id": "message-with-attachments",
                  "threadId": "thread-json",
                  "channelId": "channel-json",
                  "content": "Images from socket",
                  "messageType": "chat",
                  "senderType": "user",
                  "senderId": "user-json",
                  "senderName": "Socket User",
                  "attachments": [
                    {
                      "id": "image-1",
                      "filename": "preview.png",
                      "mimeType": "image/png",
                      "sizeBytes": 43008,
                      "thumbnailUrl": "https://cdn.example/thumbs/image-1.png"
                    },
                    {
                      "id": "file-1",
                      "filename": "report.pdf",
                      "mimeType": "application/pdf",
                      "sizeBytes": 2048
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        assertTrue(event is MessageNew)
        val message = (event as MessageNew).message
        assertEquals(2, message.attachments.size)
        assertEquals("image-1", message.attachments[0].id.value)
        assertEquals("preview.png", message.attachments[0].name)
        assertEquals("image/png", message.attachments[0].mimeType)
        assertEquals(43008L, message.attachments[0].sizeBytes)
        assertEquals("", message.attachments[0].sizeLabel)
        assertEquals("https://cdn.example/thumbs/image-1.png", message.attachments[0].thumbnailUrl)
        assertEquals("file-1", message.attachments[1].id.value)
        assertEquals("report.pdf", message.attachments[1].name)
        assertEquals("application/pdf", message.attachments[1].mimeType)
        assertEquals(2048L, message.attachments[1].sizeBytes)
        assertEquals("", message.attachments[1].sizeLabel)
    }

    @Test
    fun mapperUsesSenderDescriptionFromMessageContract() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            JSONObject(
                """
                {
                  "id": "message-agent-description",
                  "threadId": "thread-agent",
                  "channelId": "channel-agent",
                  "content": "Agent payload",
                  "messageType": "chat",
                  "senderType": "agent",
                  "senderId": "agent-1",
                  "senderName": "API Integration",
                  "senderDescription": "Data contracts",
                  "roleDescription": "agent"
                }
                """.trimIndent()
            )
        )

        assertTrue(event is MessageNew)
        val message = (event as MessageNew).message
        assertEquals("API Integration", message.author.displayName)
        assertEquals("Data contracts", message.author.description)
        assertNull(message.author.roleDescription)
        assertEquals(MemberKind.Agent, message.author.kind)
    }

    @Test
    fun mapperPreservesSocketIoSystemMessageMetadata() {
        val mapper = RealtimeEventMapper()
        val event = mapper.map(
            RealtimeSocketEvents.MessageNew,
            JSONObject(
                """
                {
                  "id": "system-message-json",
                  "threadId": "thread-json",
                  "channelId": "channel-json",
                  "seq": 78,
                  "messageType": "system",
                  "content": "Task moved to Review",
                  "senderType": "user",
                  "senderId": "system",
                  "senderName": "System"
                }
                """.trimIndent()
            )
        )

        // Canonical message row carries `messageType: "system"`. The server
        // does not emit a separate `systemEventType` or `metadata` map on the
        // wire — those are derived later by feature code from the message body
        // / task state, not from the realtime envelope.
        assertTrue(event is MessageNew)
        val message = (event as MessageNew).message
        assertEquals(MessageType.System, message.messageType)
    }

    private class FakeRealtimeSocketTransport : RealtimeSocketTransport {
        val handlers = mutableMapOf<String, MutableList<(Any?) -> Unit>>()
        var lastAuth: Map<String, String> = emptyMap()
        val emitted = mutableListOf<Pair<String, Map<String, Any>>>()
        var connectCount = 0

        override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
            connectCount += 1
            lastAuth = auth
        }

        override fun disconnect() = Unit

        override fun emit(eventName: String, payload: Map<String, Any>) {
            emitted += eventName to payload
        }

        override fun on(eventName: String, listener: (Any?) -> Unit): RealtimeSubscription {
            handlers.getOrPut(eventName) { mutableListOf() } += listener
            return RealtimeSubscription { handlers[eventName]?.remove(listener) }
        }

        fun receive(eventName: String, payload: Any? = null) {
            handlers[eventName].orEmpty().forEach { it(payload) }
        }
    }

    private class InMemoryAuthSessionStore(
        var session: AuthSession?
    ) : AuthSessionStore {
        override suspend fun readSession(): AuthSession? = session

        override suspend fun saveSession(session: AuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession(reason: String, source: String) {
            session = null
        }
    }

    private class FakeAuthApi(
        private val refreshFailure: Throwable? = null,
        private val refreshFailureCode: Int? = null,
        private val responses: List<AuthRefreshResponse> = listOf(AuthRefreshResponse("socket-new-access", "socket-new-refresh"))
    ) : SlockAuthApi {
        private var refreshIndex = 0
        var refreshCallCount: Int = 0
            private set
        override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
            AuthApiResult.Failure(404)

        override suspend fun completeSocialLogin(
            provider: String,
            bodyText: String
        ): AuthApiResult<AuthSocialCompleteResponse> =
            AuthApiResult.Failure(404)

        override suspend fun startMobileOAuth(
            provider: String,
            mode: String,
            platform: String,
            appEnv: String,
            returnUri: String,
            codeChallenge: String,
            authorization: String?
        ): AuthApiResult<AuthMobileOAuthStartResponse> =
            AuthApiResult.Failure(404)

        override suspend fun completeMobileOAuth(
            code: String,
            codeVerifier: String,
            platform: String,
            acceptLegalTerms: Boolean,
            authorization: String?
        ): AuthApiResult<AuthLoginResponse> =
            AuthApiResult.Failure(404)

        override suspend fun startMobileOAuthLink(
            provider: String,
            returnUri: String,
            codeChallenge: String,
            authorization: String
        ): AuthApiResult<AuthMobileOAuthStartResponse> =
            AuthApiResult.Failure(404)

        override suspend fun completeMobileOAuthLink(
            provider: String,
            code: String,
            codeVerifier: String,
            authorization: String
        ): AuthApiResult<AuthMobileOAuthLinkCompleteResponse> =
            AuthApiResult.Failure(404)

        override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
            AuthApiResult.Failure(404)

        override suspend fun forgotPassword(email: String): AuthApiResult<Unit> =
            AuthApiResult.Failure(404)

        override suspend fun resendVerification(authorization: String): AuthApiResult<Unit> =
            AuthApiResult.Failure(404)

        override suspend fun verifyEmail(token: String): AuthApiResult<Unit> =
            AuthApiResult.Failure(404)

        override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
            AuthApiResult.Failure(404)

        override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> {
            refreshCallCount += 1
            refreshFailure?.let { throw it }
            refreshFailureCode?.let { return AuthApiResult.Failure(it) }
            val response = responses.getOrElse(refreshIndex) { responses.last() }
            refreshIndex += 1
            return AuthApiResult.Success(response)
        }

        override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
            AuthApiResult.Success(Unit)

        override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
            AuthApiResult.Failure(404)

        override suspend fun serverOrder(authorization: String): AuthApiResult<ServerSwitcherOrderPreference> =
            AuthApiResult.Failure(404)

        override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
            AuthApiResult.Failure(404)
    }

    companion object {
        private val sampleSession = AuthSession(
            user = AuthUser(
                id = "user-1",
                email = "user@slock.ai",
                name = "User",
                displayName = "Slock User",
                description = null,
                avatarUrl = null,
                emailVerified = true
            ),
            accessToken = "sample-access",
            refreshToken = "sample-refresh",
            selectedServerId = "server-1",
            selectedServerSlug = "slock-android",
            servers = listOf(
                AuthServer(
                    id = "server-1",
                    name = "Slock Android",
                    slug = "slock-android",
                    ownerId = "user-1",
                    onboardingAgentId = "agent-1",
                    plan = "team",
                    planDowngradedAt = null,
                    role = "owner",
                    createdAt = "2026-05-06T00:00:00Z"
                )
            )
        )
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.onSuccess { value = it }
                result.onFailure { failure = it }
            }
        }
    )
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
