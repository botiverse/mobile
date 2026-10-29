package build.raft.app.data.realtime

import build.raft.app.data.DataResult
import build.raft.app.data.PUSH_DELIVERY_STATUS_MAX_AGE_MILLIS
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftRealtimeEvents
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.RaftEnhancedNotifyEvent
import build.raft.app.data.RaftEnhancedNotifyEventBus
import build.raft.app.data.RaftPushDeliveryStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RaftRealtimeAppScopePolicyTest {
    @Test
    fun connectPlanResumesSameSessionFromHighestKnownSeq() {
        val state = RaftRealtimeAppScopeState()

        val first = state.prepareConnect(ServerId("server-1"), "token-1", requestedLastSeq = 10)
        state.updateFromEvent(MessageNew(message(seq = 42)))
        val second = state.prepareConnect(ServerId("server-1"), "token-1", requestedLastSeq = 20)

        assertFalse(first.sessionChanged)
        assertEquals(10L, first.resumeSeq)
        assertFalse(second.sessionChanged)
        assertEquals(42L, second.resumeSeq)
    }

    @Test
    fun connectPlanResetsSeqAndJoinedChannelsWhenSessionChanges() {
        val state = RaftRealtimeAppScopeState()

        state.prepareConnect(ServerId("server-1"), "token-1", requestedLastSeq = 10)
        state.updateFromEvent(MessageNew(message(seq = 42)))
        assertTrue(state.markChannelJoined(ChannelId("channel-1")))

        val changed = state.prepareConnect(ServerId("server-2"), "token-2", requestedLastSeq = 99)

        assertTrue(changed.sessionChanged)
        assertEquals(0L, changed.resumeSeq)
        assertEquals(0L, state.lastSeq)
        assertEquals(0, state.joinedChannelCount)
    }

    @Test
    fun joinedChannelStateDedupesRooms() {
        val state = RaftRealtimeAppScopeState()

        assertTrue(state.markChannelJoined(ChannelId("channel-1")))
        assertFalse(state.markChannelJoined(ChannelId("channel-1")))
        assertFalse(state.markChannelJoined(ChannelId("")))
        assertEquals(1, state.joinedChannelCount)
    }

    @Test
    fun sessionEndReasonsAreExplicit() {
        assertTrue(RaftRealtimeAppScopePolicy.isSessionEndDisconnectReason("logout"))
        assertTrue(RaftRealtimeAppScopePolicy.isSessionEndDisconnectReason("api_environment_changed"))
        assertTrue(RaftRealtimeAppScopePolicy.isSessionEndDisconnectReason("server_switch"))
        assertFalse(RaftRealtimeAppScopePolicy.isSessionEndDisconnectReason("temporary_background"))
    }

    @Test
    fun backgroundUnreadNotificationPolicyIsShared() {
        assertTrue(
            RaftRealtimeAppScopePolicy.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = true,
                isAppInForeground = false
            )
        )
        assertFalse(
            RaftRealtimeAppScopePolicy.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = true,
                preferenceEnabled = true,
                isAppInForeground = false
            )
        )
    }

    @Test
    fun backgroundUnreadNotificationSkipsWhenRemotePushIsActive() {
        assertFalse(
            RaftRealtimeAppScopePolicy.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = true,
                isAppInForeground = false,
                remotePushActive = true
            )
        )
        assertEquals(
            "remote_push_active",
            RaftRealtimeAppScopePolicy.backgroundUnreadNotificationSkipReason(
                hasAttachedConsumers = false,
                preferenceEnabled = true,
                isAppInForeground = false,
                remotePushActive = true
            )
        )
        // An earlier reason still wins, so existing skip logs keep their meaning.
        assertEquals(
            "preference_disabled",
            RaftRealtimeAppScopePolicy.backgroundUnreadNotificationSkipReason(
                hasAttachedConsumers = false,
                preferenceEnabled = false,
                isAppInForeground = false,
                remotePushActive = true
            )
        )
    }

    @Test
    fun remotePushIsActiveForTheEventServerWhenTheMatchingAnswerSaysFcmActive() {
        assertTrue(remotePushActive())
        // The answer covers every server of the user; the event's server decides, not the selected one.
        assertTrue(remotePushActive(serverId = ServerId("server-2"), status = deliveryStatus(server2 = true)))
        // A trailing slash on the environment is the same environment.
        assertTrue(remotePushActive(environmentId = "https://api.example/"))
    }

    @Test
    fun remotePushIsInactiveOnceTheAnswerIsOlderThanThirtyMinutes() {
        val fetchedAt = 1_000_000L
        val maxAge = PUSH_DELIVERY_STATUS_MAX_AGE_MILLIS
        assertTrue(remotePushActive(nowMillis = fetchedAt + maxAge))
        assertFalse(remotePushActive(nowMillis = fetchedAt + maxAge + 1), "expired: post locally")
        assertFalse(remotePushActive(nowMillis = fetchedAt - 1), "clock moved backwards: unknown")
    }

    @Test
    fun remotePushIsInactiveWithoutAStoredAnswer() {
        // Never fetched, every fetch failed, a 404 from an older server, or cleared on logout.
        assertFalse(remotePushActive(status = null))
    }

    @Test
    fun remotePushIsInactiveForAServerMissingFromTheAnswerOrNotFcmActive() {
        assertFalse(remotePushActive(serverId = ServerId("server-3")), "server missing from the answer")
        assertFalse(remotePushActive(serverId = ServerId("server-2")), "server-2 is fcmActive=false")
        assertFalse(remotePushActive(serverId = ServerId(" ")), "blank server")
    }

    @Test
    fun remotePushIsInactiveForAnAnswerOfAnotherUserInstallationOrEnvironment() {
        assertFalse(remotePushActive(currentUserId = "user-b"), "account switched")
        assertFalse(remotePushActive(currentUserId = null), "signed out")
        assertFalse(remotePushActive(installationId = "installation-2"), "another installation")
        assertFalse(remotePushActive(installationId = null), "no installation")
        assertFalse(remotePushActive(environmentId = "https://staging.example"), "another environment")
        assertFalse(remotePushActive(environmentId = null), "unknown environment")
    }

    private fun remotePushActive(
        serverId: ServerId = ServerId("server-1"),
        environmentId: String? = "https://api.example",
        currentUserId: String? = "user-a",
        installationId: String? = "installation-1",
        status: RaftPushDeliveryStatus? = deliveryStatus(),
        nowMillis: Long = 1_000_000L + 60_000L
    ): Boolean =
        RaftRealtimeAppScopePolicy.isRemotePushActive(
            serverId = serverId,
            environmentId = environmentId,
            currentUserId = currentUserId,
            installationId = installationId,
            deliveryStatus = status,
            nowMillis = nowMillis
        )

    private fun deliveryStatus(server2: Boolean = false): RaftPushDeliveryStatus =
        RaftPushDeliveryStatus(
            environmentId = "https://api.example",
            userId = "user-a",
            installationId = "installation-1",
            fcmActiveByServer = mapOf("server-1" to true, "server-2" to server2),
            fetchedAtMillis = 1_000_000L
        )

    @Test
    fun backgroundUnreadNotificationKindTreatsThreadMessagesAsThread() {
        assertEquals(
            ConversationKind.Thread,
            RaftRealtimeAppScopePolicy.backgroundUnreadNotificationConversationKind(
                message(
                    channelId = "thread-channel",
                    threadChannelId = "thread-channel",
                    parentMessageId = "parent-message"
                )
            )
        )
    }

    @Test
    fun coordinatorSkipsSameConnectedSessionAndResumesForcedReconnect() = runBlocking {
        val connection = FakeAppScopeConnection()
        val coordinator = RaftRealtimeAppScopeCoordinator(connection, FakeAppScopeSink())

        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 7, forceReconnect = false, witness = witness())
        coordinator.onMappedEvent(MessageNew(message(seq = 42)))
        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 10, forceReconnect = false, witness = witness())
        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 10, forceReconnect = true, witness = witness())

        assertEquals(listOf(7L, 42L), connection.connectedLastSeqs)
    }

    @Test
    fun coordinatorAutoJoinsDirectMessagesAndFansOutHeartbeatForVisibleBackfill() = runBlocking {
        val connection = FakeAppScopeConnection()
        val sink = FakeAppScopeSink()
        val coordinator = RaftRealtimeAppScopeCoordinator(connection, sink)

        val context = RealtimeSocketConnectionContext(ServerId("server-1"), witness())
        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 0, forceReconnect = false, witness = context.witness)
        coordinator.onMappedEvent(DirectMessageCreated(ChannelId("dm-1")))
        coordinator.onRawEvent(context, RealtimeSocketEvents.Heartbeat, """{"seq":43}""")
        coordinator.onRawEvent(context, RealtimeSocketEvents.MessageNew, """{"id":"message-1"}""")

        assertEquals(listOf(RealtimeSocketEvents.JoinChannel to "dm-1"), connection.roomCommands)
        assertEquals(listOf(RealtimeSocketEvents.Heartbeat, RealtimeSocketEvents.MessageNew), sink.fanOutEventNames)
    }

    @Test
    fun coordinatorFansOutTheEpochBoundWhenTheSocketReceivedTheFrame() = runBlocking {
        // Task #168: an old socket's frame keeps the epoch it was received under, even when it is fanned out
        // after a reconnect, so the durable canary can refuse to append it to the newer window.
        val connection = FakeAppScopeConnection()
        val sink = FakeAppScopeSink()
        val coordinator = RaftRealtimeAppScopeCoordinator(connection, sink)
        val context = RealtimeSocketConnectionContext(ServerId("server-1"), witness())
        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 0, forceReconnect = false, witness = context.witness)

        coordinator.onRawEvent(context, RealtimeSocketEvents.MessageNew, """{"id":"m110"}""", transportEpoch = 1L)
        coordinator.onRawEvent(context, RealtimeSocketEvents.MessageNew, """{"id":"m111"}""", transportEpoch = 2L)
        coordinator.onRawEvent(context, RealtimeSocketEvents.MessageNew, """{"id":"m112"}""")

        assertEquals(listOf<Long?>(1L, 2L, null), sink.fanOutTransportEpochs)
    }

    @Test
    fun coordinatorGatesAgainstCapturedConnectionServerBeforeFanout() = runBlocking {
        val connection = FakeAppScopeConnection()
        val sink = FakeAppScopeSink()
        val coordinator = RaftRealtimeAppScopeCoordinator(connection, sink)

        coordinator.connect(ServerId("server-a"), "token-1", 0, false, witness())
        coordinator.onRawEvent(
            RealtimeSocketConnectionContext(ServerId("server-b"), witness()),
            RealtimeSocketEvents.MessageNew,
            """{"serverId":"server-a"}""",
        )

        assertEquals(emptyList(), sink.fanOutEventNames)
    }

    @Test
    fun coordinatorEmitsSharedAgentActivitySubscriptionsOnItsOwnServerOnly() = runBlocking {
        val connection = FakeAppScopeConnection()
        val enhancedNotifyBus = FakeEnhancedNotifyBus()
        RaftRealtimeAppScopeCoordinator(connection, FakeAppScopeSink(), enhancedNotifyBus)
            .connect(ServerId("server-1"), "token-1", requestedLastSeq = 0, forceReconnect = false, witness = witness())
        // What shared AgentActivitySubscriptions posts, through the real rooms-topic writer.
        val events =
            RaftRealtimeEvents(
                object : RaftEventBus {
                    override fun observe(eventName: String, crossProcess: Boolean, listener: (JsonObject?) -> Unit) =
                        RealtimeSubscription { }

                    override fun post(eventName: String, eventData: JsonObject, crossProcess: Boolean, source: String) =
                        enhancedNotifyBus.emit(RaftEnhancedNotifyEvent(eventName, eventData, crossProcess, source))
                }
            )

        events.postAgentActivitySubscription("server-1", subscribe = true, agentIds = listOf("a1", "a2"))
        events.postAgentActivitySubscription("server-2", subscribe = true, agentIds = listOf("b1"))
        events.postAgentActivitySubscription("server-1", subscribe = false, agentIds = listOf("a2"))
        events.postAgentActivitySubscription("server-1", subscribe = true, agentIds = emptyList())

        assertEquals(
            listOf(
                RealtimeSocketEvents.AgentActivitySubscribe to listOf("a1", "a2"),
                RealtimeSocketEvents.AgentActivityUnsubscribe to listOf("a2"),
            ),
            connection.agentActivitySubscriptions
        )
        assertTrue(connection.roomCommands.isEmpty())
    }

    @Test
    fun coordinatorConsumesRoomCommandsBeforeEmittingJoin() = runBlocking {
        val connection = FakeAppScopeConnection()
        val enhancedNotifyBus = FakeEnhancedNotifyBus()
        val coordinator = RaftRealtimeAppScopeCoordinator(connection, FakeAppScopeSink(), enhancedNotifyBus)

        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 0, forceReconnect = false, witness = witness())

        enhancedNotifyBus.emitRoomCommand(
            action = RaftRealtimeNotifyContract.ActionJoinChannel,
            channelId = "channel-1",
            serverId = "server-1"
        )
        enhancedNotifyBus.emitRoomCommand(
            action = RaftRealtimeNotifyContract.ActionJoinChannel,
            channelId = "channel-1",
            serverId = "server-1"
        )
        enhancedNotifyBus.emitRoomCommand(
            action = RaftRealtimeNotifyContract.ActionJoinChannel,
            channelId = "channel-b",
            serverId = "server-2"
        )
        enhancedNotifyBus.emitRoomCommand(action = "unsupported", channelId = "channel-2")
        enhancedNotifyBus.emitRoomCommand(
            action = RaftRealtimeNotifyContract.ActionJoinChannel,
            channelId = ""
        )
        enhancedNotifyBus.emitRoomCommand(
            action = RaftRealtimeNotifyContract.ActionLeaveChannel,
            channelId = "channel-1",
            serverId = "server-1"
        )
        enhancedNotifyBus.emitRoomCommand(
            action = RaftRealtimeNotifyContract.ActionLeaveChannel,
            channelId = "channel-1",
            serverId = "server-1"
        )
        enhancedNotifyBus.emit(
            RaftEnhancedNotifyEvent(
                eventName = "unrelated",
                eventData = buildJsonObject { put(RaftRealtimeNotifyContract.KeyChannelId, "channel-3") }
            )
        )

        assertEquals(
            listOf(
                RealtimeSocketEvents.JoinChannel to "channel-1",
                RealtimeSocketEvents.LeaveChannel to "channel-1"
            ),
            connection.roomCommands
        )
    }

    @Test
    fun hostEngineConnectDecisionMatchesAppScopeCoordinatorRules() {
        val engine = RaftRealtimeAppScopeEngine()

        val first =
            engine.connectDecisionForHost(
                serverIdValue = "server-1",
                accessToken = "token-1",
                requestedLastSeq = 7,
                forceReconnect = false,
                connectionStateName = RaftRealtimeAppScopeEngine.StateDisconnected
            )
        engine.onMappedEvent(MessageNew(message(seq = 42)))
        val sameConnected =
            engine.connectDecisionForHost(
                serverIdValue = "server-1",
                accessToken = "token-1",
                requestedLastSeq = 10,
                forceReconnect = false,
                connectionStateName = RaftRealtimeAppScopeEngine.StateConnected
            )
        val forced =
            engine.connectDecisionForHost(
                serverIdValue = "server-1",
                accessToken = "token-1",
                requestedLastSeq = 10,
                forceReconnect = true,
                connectionStateName = RaftRealtimeAppScopeEngine.StateConnected
            )

        assertTrue(first.shouldConnect)
        assertEquals(7L, first.resumeSeq)
        assertFalse(sameConnected.shouldConnect)
        assertEquals(42L, sameConnected.resumeSeq)
        assertTrue(forced.shouldConnect)
        assertEquals(42L, forced.resumeSeq)
    }

    @Test
    fun perServerHostCursorsAdvanceMonotonicallyResumeIndependentlyAndResetOnIdentityReplacement() {
        val serverA = RaftRealtimeAppScopeEngine()
        val serverB = RaftRealtimeAppScopeEngine()
        serverA.connectDecisionForHost(
            serverIdValue = "server-a",
            accessToken = "token-1",
            requestedLastSeq = 0,
            forceReconnect = false,
            connectionStateName = RaftRealtimeAppScopeEngine.StateDisconnected,
        )
        serverB.connectDecisionForHost(
            serverIdValue = "server-b",
            accessToken = "token-1",
            requestedLastSeq = 0,
            forceReconnect = false,
            connectionStateName = RaftRealtimeAppScopeEngine.StateDisconnected,
        )

        assertEquals(11L, serverA.advanceAppliedSeqForHost(11L))
        assertEquals(11L, serverA.advanceAppliedSeqForHost(7L))
        assertEquals(29L, serverB.advanceAppliedSeqForHost(29L))

        val reconnectA =
            serverA.connectDecisionForHost(
                serverIdValue = "server-a",
                accessToken = "token-1",
                requestedLastSeq = 0,
                forceReconnect = true,
                connectionStateName = RaftRealtimeAppScopeEngine.StateDisconnected,
            )
        val reconnectB =
            serverB.connectDecisionForHost(
                serverIdValue = "server-b",
                accessToken = "token-1",
                requestedLastSeq = 0,
                forceReconnect = true,
                connectionStateName = RaftRealtimeAppScopeEngine.StateDisconnected,
            )
        val replacedA =
            serverA.connectDecisionForHost(
                serverIdValue = "server-a",
                accessToken = "token-2",
                requestedLastSeq = 99,
                forceReconnect = false,
                connectionStateName = RaftRealtimeAppScopeEngine.StateDisconnected,
            )

        assertEquals(11L, reconnectA.resumeSeq)
        assertEquals(29L, reconnectB.resumeSeq)
        assertEquals(0L, replacedA.resumeSeq)
        assertEquals(0L, serverA.lastSeq)
        assertEquals(29L, serverB.lastSeq)
    }

    @Test
    fun onlyAppliedDomainEventsProduceNativeResumeCursorAdvances() {
        assertEquals(42L, RealtimeCursorPolicy.appliedSeqForEvent(MessageNew(message(seq = 42L))))
        assertEquals(0L, RealtimeCursorPolicy.appliedSeqForEvent(Heartbeat(seq = 99L)))
        assertEquals(0L, RealtimeCursorPolicy.appliedSeqForEvent(DirectMessageCreated(ChannelId("channel-1"))))
    }

    @Test
    fun hostEngineOwnsJoinAndRawFanoutDecisions() {
        val engine = RaftRealtimeAppScopeEngine()

        assertFalse(engine.onRawEvent(RealtimeSocketEvents.MessageNew, "{}").shouldFanOut)
        engine.connectDecisionForHost(
            serverIdValue = "server-1",
            accessToken = "token-1",
            requestedLastSeq = 0,
            forceReconnect = false,
            connectionStateName = RaftRealtimeAppScopeEngine.StateDisconnected
        )
        val join = engine.joinChannelDecisionForHost("channel-1", "notify")
        val duplicateJoin = engine.joinChannelDecisionForHost("channel-1", "notify")
        val leave =
            engine.roomCommandDecisionForHost(
                RaftRealtimeNotifyContract.ActionLeaveChannel,
                "channel-1",
                "notify"
            )
        val duplicateLeave =
            engine.roomCommandDecisionForHost(
                RaftRealtimeNotifyContract.ActionLeaveChannel,
                "channel-1",
                "notify"
            )
        val blankJoin =
            engine.roomCommandDecisionForHost(
                RaftRealtimeNotifyContract.ActionJoinChannel,
                "",
                "notify"
            )
        val unsupportedJoin =
            engine.roomCommandDecisionForHost("unsupported", "channel-2", "notify")
        val heartbeat = engine.onRawEvent(RealtimeSocketEvents.Heartbeat, "{}")
        val message = engine.onRawEvent(RealtimeSocketEvents.MessageNew, """{"id":"message-1"}""")

        assertTrue(join.shouldEmit)
        assertEquals(RealtimeSocketEvents.JoinChannel, join.eventName)
        assertEquals("channel-1", join.payload)
        assertEquals(RaftRealtimeRoomJoinedChannelAction.Join, join.joinedChannelAction)
        assertEquals(ChannelId("channel-1"), join.channelId)
        assertFalse(duplicateJoin.shouldEmit)
        assertTrue(leave.shouldEmit)
        assertEquals(RealtimeSocketEvents.LeaveChannel, leave.eventName)
        assertEquals("channel-1", leave.payload)
        assertEquals(RaftRealtimeRoomJoinedChannelAction.Leave, leave.joinedChannelAction)
        assertEquals(ChannelId("channel-1"), leave.channelId)
        assertFalse(duplicateLeave.shouldEmit)
        assertEquals(RaftRealtimeRoomJoinedChannelAction.None, duplicateLeave.joinedChannelAction)
        assertEquals("", heartbeat.logMessage)
        assertEquals("", message.logMessage)
        assertFalse(blankJoin.shouldEmit)
        assertFalse(unsupportedJoin.shouldEmit)
        assertTrue(heartbeat.shouldFanOut)
        assertEquals(ServerId("server-1"), heartbeat.serverId)
        assertTrue(message.shouldFanOut)
        assertEquals(ServerId("server-1"), message.serverId)
    }

    @Test
    fun rawIngressDropsOnlyPresentMismatchedTopLevelServerId() {
        val matched =
            realtimePayloadServerGate(
                connectionServerId = "server-a",
                rawPayload = """{"serverId":"server-a","nested":{"serverId":"server-b"}}""",
            )
        val absent =
            realtimePayloadServerGate(
                connectionServerId = "server-a",
                rawPayload = """{"nested":{"serverId":"server-b"}}""",
            )
        val explicitNull =
            realtimePayloadServerGate(
                connectionServerId = "server-a",
                rawPayload = """{"serverId":null}""",
            )
        val mismatch =
            realtimePayloadServerGate(
                connectionServerId = "server-a",
                rawPayload = """{"serverId":"server-b"}""",
            )

        assertTrue(matched.shouldAccept)
        assertEquals("matched", matched.reason)
        assertTrue(absent.shouldAccept)
        assertEquals("absent", absent.reason)
        assertTrue(explicitNull.shouldAccept)
        assertEquals("absent", explicitNull.reason)
        assertFalse(mismatch.shouldAccept)
        assertEquals("mismatch", mismatch.reason)
    }

    @Test
    fun rawIngressKeepsUserScopedEventsAboutAnotherServer() {
        val engine = RaftRealtimeAppScopeEngine()
        val otherServer = """{"serverId":"server-b","theme":"space","themeDecor":true}"""

        val appearance = engine.onRawEventForConnection("server-a", RealtimeSocketEvents.AppearanceUpdated, otherServer)
        val planUpdate = engine.onRawEventForConnection("server-a", RealtimeSocketEvents.ServerPlanUpdated, otherServer)
        val invalid =
            engine.onRawEventForConnection("server-a", RealtimeSocketEvents.AppearanceUpdated, """{"serverId":42}""")

        assertTrue(appearance.shouldFanOut)
        assertEquals(ServerId("server-a"), appearance.serverId)
        assertFalse(planUpdate.shouldFanOut)
        assertFalse(invalid.shouldFanOut)
    }

    @Test
    fun rawIngressFailsClosedForBlankConnectionOrInvalidPresentServerId() {
        assertFalse(
            realtimePayloadServerGate(
                connectionServerId = "",
                rawPayload = """{"serverId":"server-a"}""",
            ).shouldAccept
        )
        assertFalse(
            realtimePayloadServerGate(
                connectionServerId = "server-a",
                rawPayload = """{"serverId":42}""",
            ).shouldAccept
        )
        assertFalse(
            realtimePayloadServerGate(
                connectionServerId = "server-a",
                rawPayload = """{"serverId":""}""",
            ).shouldAccept
        )
    }

    private fun message(
        seq: Long = 1L,
        channelId: String? = "channel-1",
        threadChannelId: String? = null,
        parentMessageId: String? = null
    ): Message =
        Message(
            id = MessageId("message-$seq"),
            threadId = ThreadId(threadChannelId ?: channelId ?: "thread-1"),
            channelId = channelId?.let(::ChannelId),
            threadChannelId = threadChannelId?.let(::ChannelId),
            seq = seq,
            author =
                MemberProfile(
                    id = MemberId("member-1"),
                    displayName = "Member One",
                    handle = "member-one",
                    kind = MemberKind.Human
                ),
            timestampLabel = "12:00",
            body = "Message $seq",
            parentMessageId = parentMessageId?.let(::MessageId)
        )

    private class FakeEnhancedNotifyBus : RaftEnhancedNotifyEventBus {
        private val listeners = mutableListOf<(RaftEnhancedNotifyEvent) -> Unit>()

        override fun observeNotifyEvents(
            listener: (RaftEnhancedNotifyEvent) -> Unit
        ): RealtimeSubscription {
            listeners += listener
            return RealtimeSubscription {
                listeners -= listener
            }
        }

        fun emitRoomCommand(action: String, channelId: String, serverId: String? = null) {
            emit(
                RaftEnhancedNotifyEvent(
                    eventName = RaftRealtimeNotifyContract.RoomsEventName,
                    eventData =
                        buildJsonObject {
                            put(RaftRealtimeNotifyContract.KeyAction, action)
                            put(RaftRealtimeNotifyContract.KeyChannelId, channelId)
                            serverId?.let { put(RaftRealtimeNotifyContract.KeyServerId, it) }
                        },
                    source = "notify"
                )
            )
        }

        fun emit(event: RaftEnhancedNotifyEvent) {
            listeners.toList().forEach { listener ->
                listener(event)
            }
        }
    }

    private class FakeAppScopeConnection : RaftRealtimeAppScopeConnection {
        override var state: RealtimeConnectionState = RealtimeConnectionState.Disconnected
        val connectedLastSeqs = mutableListOf<Long>()
        val roomCommands = mutableListOf<Pair<String, String>>()

        override suspend fun connect(
            serverId: ServerId,
            accessToken: String,
            lastSeq: Long,
            witness: RealtimeConnectionWitnessToken,
        ): DataResult<Unit> {
            connectedLastSeqs += lastSeq
            state = RealtimeConnectionState.Connected
            return DataResult.Content(Unit)
        }

        override fun disconnect() {
            state = RealtimeConnectionState.Disconnected
        }

        override fun emitRoomCommand(eventName: String, payload: String, force: Boolean) {
            roomCommands += eventName to payload
        }

        val agentActivitySubscriptions = mutableListOf<Pair<String, List<String>>>()

        override fun emitAgentActivitySubscription(eventName: String, agentIds: List<String>) {
            agentActivitySubscriptions += eventName to agentIds
        }

        override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription =
            RealtimeSubscription { }

        override fun observeRawEvents(
            listener: (RealtimeSocketConnectionContext, String, String, Long?) -> Unit
        ): RealtimeSubscription =
            RealtimeSubscription { }
    }

    private class FakeAppScopeSink : RaftRealtimeAppScopeSink {
        val fanOutEventNames = mutableListOf<String>()
        val fanOutTransportEpochs = mutableListOf<Long?>()

        override fun log(message: String) = Unit

        override fun fanOutRawRealtimeEvent(
            serverId: ServerId,
            witness: RealtimeConnectionWitnessToken,
            eventName: String,
            rawPayload: String,
            transportEpoch: Long?,
        ) {
            fanOutEventNames += eventName
            fanOutTransportEpochs += transportEpoch
        }

        override suspend fun maybePostBackgroundUnreadNotification(
            serverId: ServerId,
            event: NotificationPushReceived,
            isAppInForeground: Boolean
        ) = Unit
    }

    private fun witness(): RealtimeConnectionWitnessToken =
        RealtimeConnectionWitnessToken(
            scopeLeaseId = "lease-1",
            principalId = "member-1",
            authGeneration = 1L,
            connectionFamilyId = "family-1",
        )
    @Test
    fun rejoinChannelForcesEmitWhileKeepingJoinedSetConsistent() {
        val engine = RaftRealtimeAppScopeEngine()
        engine.connectDecisionForHost(
            serverIdValue = "server-1",
            accessToken = "token-1",
            requestedLastSeq = 0,
            forceReconnect = false,
            connectionStateName = RaftRealtimeAppScopeEngine.StateDisconnected
        )

        val join = engine.joinChannelDecisionForHost("channel-1", "notify")
        val rejoin =
            engine.roomCommandDecisionForHost(
                RaftRealtimeNotifyContract.ActionRejoinChannel,
                "channel-1",
                "foreground_reconcile"
            )
        val duplicatePlainJoin = engine.joinChannelDecisionForHost("channel-1", "notify")
        val rejoinWhenNeverJoined =
            engine.roomCommandDecisionForHost(
                RaftRealtimeNotifyContract.ActionRejoinChannel,
                "channel-2",
                "foreground_reconcile"
            )
        val leaveAfterRejoin =
            engine.roomCommandDecisionForHost(
                RaftRealtimeNotifyContract.ActionLeaveChannel,
                "channel-1",
                "notify"
            )

        assertTrue(join.shouldEmit)
        // Force path re-emits join:channel even though the local set says joined.
        assertTrue(rejoin.shouldEmit)
        assertEquals(RealtimeSocketEvents.JoinChannel, rejoin.eventName)
        assertEquals("channel-1", rejoin.payload)
        assertEquals(RaftRealtimeRoomJoinedChannelAction.Rejoin, rejoin.joinedChannelAction)
        // Joined-set stays idempotent: a plain join after force rejoin still dedupes.
        assertFalse(duplicatePlainJoin.shouldEmit)
        // Rejoin also covers rooms the local set never saw (fresh join).
        assertTrue(rejoinWhenNeverJoined.shouldEmit)
        // And leave semantics are unaffected by a prior force rejoin.
        assertTrue(leaveAfterRejoin.shouldEmit)
    }

    @Test
    fun resetRoomStateForHostClearsJoinedSetForNextSession() {
        val engine = RaftRealtimeAppScopeEngine()

        val firstJoin = engine.joinChannelDecisionForHost("channel-1", "notify")
        engine.resetRoomStateForHost()
        val joinAfterReset = engine.joinChannelDecisionForHost("channel-1", "notify")
        val duplicateAfterReset = engine.joinChannelDecisionForHost("channel-1", "notify")

        assertTrue(firstJoin.shouldEmit)
        // Without the reset this would be skipped as already_joined.
        assertTrue(joinAfterReset.shouldEmit)
        assertFalse(duplicateAfterReset.shouldEmit)
    }

}
