package build.raft.app.data.realtime

import build.raft.app.data.DataResult
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.SlockEnhancedNotifyEvent
import build.raft.app.data.SlockEnhancedNotifyEventBus
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.DirectMessageCreated
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
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockRealtimeAppScopePolicyTest {
    @Test
    fun connectPlanResumesSameSessionFromHighestKnownSeq() {
        val state = SlockRealtimeAppScopeState()

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
        val state = SlockRealtimeAppScopeState()

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
        val state = SlockRealtimeAppScopeState()

        assertTrue(state.markChannelJoined(ChannelId("channel-1")))
        assertFalse(state.markChannelJoined(ChannelId("channel-1")))
        assertFalse(state.markChannelJoined(ChannelId("")))
        assertEquals(1, state.joinedChannelCount)
    }

    @Test
    fun sessionEndReasonsAreExplicit() {
        assertTrue(SlockRealtimeAppScopePolicy.isSessionEndDisconnectReason("logout"))
        assertTrue(SlockRealtimeAppScopePolicy.isSessionEndDisconnectReason("api_environment_changed"))
        assertTrue(SlockRealtimeAppScopePolicy.isSessionEndDisconnectReason("server_switch"))
        assertFalse(SlockRealtimeAppScopePolicy.isSessionEndDisconnectReason("temporary_background"))
    }

    @Test
    fun backgroundUnreadNotificationPolicyIsShared() {
        assertTrue(
            SlockRealtimeAppScopePolicy.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = true,
                isAppInForeground = false
            )
        )
        assertFalse(
            SlockRealtimeAppScopePolicy.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = true,
                preferenceEnabled = true,
                isAppInForeground = false
            )
        )
    }

    @Test
    fun backgroundUnreadNotificationKindTreatsThreadMessagesAsThread() {
        assertEquals(
            ConversationKind.Thread,
            SlockRealtimeAppScopePolicy.backgroundUnreadNotificationConversationKind(
                message(
                    channelId = "thread-channel",
                    threadChannelId = "thread-channel",
                    parentMessageId = "parent-message"
                )
            )
        )
    }

    @Test
    fun coordinatorSkipsSameConnectedSessionAndRebaselinesForcedReconnect() = runBlocking {
        val connection = FakeAppScopeConnection()
        val coordinator = SlockRealtimeAppScopeCoordinator(connection, FakeAppScopeSink())

        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 7, forceReconnect = false, witness = witness())
        coordinator.onMappedEvent(MessageNew(message(seq = 42)))
        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 10, forceReconnect = false, witness = witness())
        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 10, forceReconnect = true, witness = witness())

        assertEquals(listOf(7L, 0L), connection.connectedLastSeqs)
    }

    @Test
    fun coordinatorAutoJoinsDirectMessagesAndFansOutHeartbeatForVisibleBackfill() = runBlocking {
        val connection = FakeAppScopeConnection()
        val sink = FakeAppScopeSink()
        val coordinator = SlockRealtimeAppScopeCoordinator(connection, sink)

        val context = RealtimeSocketConnectionContext(ServerId("server-1"), witness())
        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 0, forceReconnect = false, witness = context.witness)
        coordinator.onMappedEvent(DirectMessageCreated(ChannelId("dm-1")))
        coordinator.onRawEvent(context, RealtimeSocketEvents.Heartbeat, """{"seq":43}""")
        coordinator.onRawEvent(context, RealtimeSocketEvents.MessageNew, """{"id":"message-1"}""")

        assertEquals(listOf(RealtimeSocketEvents.JoinChannel to "dm-1"), connection.roomCommands)
        assertEquals(listOf(RealtimeSocketEvents.Heartbeat, RealtimeSocketEvents.MessageNew), sink.fanOutEventNames)
    }

    @Test
    fun coordinatorConsumesRoomCommandsBeforeEmittingJoin() = runBlocking {
        val connection = FakeAppScopeConnection()
        val enhancedNotifyBus = FakeEnhancedNotifyBus()
        val coordinator = SlockRealtimeAppScopeCoordinator(connection, FakeAppScopeSink(), enhancedNotifyBus)

        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 0, forceReconnect = false, witness = witness())

        enhancedNotifyBus.emitRoomCommand(
            action = SlockRealtimeNotifyContract.ActionJoinChannel,
            channelId = "channel-1",
            serverId = "server-1"
        )
        enhancedNotifyBus.emitRoomCommand(
            action = SlockRealtimeNotifyContract.ActionJoinChannel,
            channelId = "channel-1",
            serverId = "server-1"
        )
        enhancedNotifyBus.emitRoomCommand(
            action = SlockRealtimeNotifyContract.ActionJoinChannel,
            channelId = "channel-b",
            serverId = "server-2"
        )
        enhancedNotifyBus.emitRoomCommand(action = "unsupported", channelId = "channel-2")
        enhancedNotifyBus.emitRoomCommand(
            action = SlockRealtimeNotifyContract.ActionJoinChannel,
            channelId = ""
        )
        enhancedNotifyBus.emitRoomCommand(
            action = SlockRealtimeNotifyContract.ActionLeaveChannel,
            channelId = "channel-1"
        )
        enhancedNotifyBus.emitRoomCommand(
            action = SlockRealtimeNotifyContract.ActionLeaveChannel,
            channelId = "channel-1"
        )
        enhancedNotifyBus.emit(
            SlockEnhancedNotifyEvent(
                eventName = "unrelated",
                eventData = JSONObject().put(SlockRealtimeNotifyContract.KeyChannelId, "channel-3")
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
        val engine = SlockRealtimeAppScopeEngine()

        val first =
            engine.connectDecisionForHost(
                serverIdValue = "server-1",
                accessToken = "token-1",
                requestedLastSeq = 7,
                forceReconnect = false,
                connectionStateName = SlockRealtimeAppScopeEngine.StateDisconnected
            )
        engine.onMappedEvent(MessageNew(message(seq = 42)))
        val sameConnected =
            engine.connectDecisionForHost(
                serverIdValue = "server-1",
                accessToken = "token-1",
                requestedLastSeq = 10,
                forceReconnect = false,
                connectionStateName = SlockRealtimeAppScopeEngine.StateConnected
            )
        val forced =
            engine.connectDecisionForHost(
                serverIdValue = "server-1",
                accessToken = "token-1",
                requestedLastSeq = 10,
                forceReconnect = true,
                connectionStateName = SlockRealtimeAppScopeEngine.StateConnected
            )

        assertTrue(first.shouldConnect)
        assertEquals(7L, first.resumeSeq)
        assertFalse(sameConnected.shouldConnect)
        assertEquals(42L, sameConnected.resumeSeq)
        assertTrue(forced.shouldConnect)
        assertEquals(0L, forced.resumeSeq)
    }

    @Test
    fun hostEngineOwnsJoinAndRawFanoutDecisions() {
        val engine = SlockRealtimeAppScopeEngine()

        assertFalse(engine.onRawEvent(RealtimeSocketEvents.MessageNew, "{}").shouldFanOut)
        engine.connectDecisionForHost(
            serverIdValue = "server-1",
            accessToken = "token-1",
            requestedLastSeq = 0,
            forceReconnect = false,
            connectionStateName = SlockRealtimeAppScopeEngine.StateDisconnected
        )
        val join = engine.joinChannelDecisionForHost("channel-1", "notify")
        val duplicateJoin = engine.joinChannelDecisionForHost("channel-1", "notify")
        val leave =
            engine.roomCommandDecisionForHost(
                SlockRealtimeNotifyContract.ActionLeaveChannel,
                "channel-1",
                "notify"
            )
        val duplicateLeave =
            engine.roomCommandDecisionForHost(
                SlockRealtimeNotifyContract.ActionLeaveChannel,
                "channel-1",
                "notify"
            )
        val blankJoin =
            engine.roomCommandDecisionForHost(
                SlockRealtimeNotifyContract.ActionJoinChannel,
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
        assertEquals(SlockRealtimeRoomJoinedChannelAction.Join, join.joinedChannelAction)
        assertEquals(ChannelId("channel-1"), join.channelId)
        assertFalse(duplicateJoin.shouldEmit)
        assertTrue(leave.shouldEmit)
        assertEquals(RealtimeSocketEvents.LeaveChannel, leave.eventName)
        assertEquals("channel-1", leave.payload)
        assertEquals(SlockRealtimeRoomJoinedChannelAction.Leave, leave.joinedChannelAction)
        assertEquals(ChannelId("channel-1"), leave.channelId)
        assertFalse(duplicateLeave.shouldEmit)
        assertEquals(SlockRealtimeRoomJoinedChannelAction.None, duplicateLeave.joinedChannelAction)
        assertEquals("", heartbeat.logMessage)
        assertEquals("", message.logMessage)
        assertFalse(blankJoin.shouldEmit)
        assertFalse(unsupportedJoin.shouldEmit)
        assertTrue(heartbeat.shouldFanOut)
        assertEquals(ServerId("server-1"), heartbeat.serverId)
        assertTrue(message.shouldFanOut)
        assertEquals(ServerId("server-1"), message.serverId)
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

    private class FakeEnhancedNotifyBus : SlockEnhancedNotifyEventBus {
        private val listeners = mutableListOf<(SlockEnhancedNotifyEvent) -> Unit>()

        override fun observeNotifyEvents(
            listener: (SlockEnhancedNotifyEvent) -> Unit
        ): RealtimeSubscription {
            listeners += listener
            return RealtimeSubscription {
                listeners -= listener
            }
        }

        fun emitRoomCommand(action: String, channelId: String, serverId: String? = null) {
            emit(
                SlockEnhancedNotifyEvent(
                    eventName = SlockRealtimeNotifyContract.RoomsEventName,
                    eventData =
                        JSONObject()
                            .put(SlockRealtimeNotifyContract.KeyAction, action)
                            .put(SlockRealtimeNotifyContract.KeyChannelId, channelId)
                            .also { payload ->
                                serverId?.let { payload.put(SlockRealtimeNotifyContract.KeyServerId, it) }
                            },
                    source = "notify"
                )
            )
        }

        fun emit(event: SlockEnhancedNotifyEvent) {
            listeners.toList().forEach { listener ->
                listener(event)
            }
        }
    }

    private class FakeAppScopeConnection : SlockRealtimeAppScopeConnection {
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

        override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription =
            RealtimeSubscription { }

        override fun observeRawEvents(
            listener: (RealtimeSocketConnectionContext, String, String) -> Unit
        ): RealtimeSubscription =
            RealtimeSubscription { }
    }

    private class FakeAppScopeSink : SlockRealtimeAppScopeSink {
        val fanOutEventNames = mutableListOf<String>()

        override fun log(message: String) = Unit

        override fun fanOutRawRealtimeEvent(
            serverId: ServerId,
            witness: RealtimeConnectionWitnessToken,
            eventName: String,
            rawPayload: String
        ) {
            fanOutEventNames += eventName
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
        val engine = SlockRealtimeAppScopeEngine()
        engine.connectDecisionForHost(
            serverIdValue = "server-1",
            accessToken = "token-1",
            requestedLastSeq = 0,
            forceReconnect = false,
            connectionStateName = SlockRealtimeAppScopeEngine.StateDisconnected
        )

        val join = engine.joinChannelDecisionForHost("channel-1", "notify")
        val rejoin =
            engine.roomCommandDecisionForHost(
                SlockRealtimeNotifyContract.ActionRejoinChannel,
                "channel-1",
                "foreground_reconcile"
            )
        val duplicatePlainJoin = engine.joinChannelDecisionForHost("channel-1", "notify")
        val rejoinWhenNeverJoined =
            engine.roomCommandDecisionForHost(
                SlockRealtimeNotifyContract.ActionRejoinChannel,
                "channel-2",
                "foreground_reconcile"
            )
        val leaveAfterRejoin =
            engine.roomCommandDecisionForHost(
                SlockRealtimeNotifyContract.ActionLeaveChannel,
                "channel-1",
                "notify"
            )

        assertTrue(join.shouldEmit)
        // Force path re-emits join:channel even though the local set says joined.
        assertTrue(rejoin.shouldEmit)
        assertEquals(RealtimeSocketEvents.JoinChannel, rejoin.eventName)
        assertEquals("channel-1", rejoin.payload)
        assertEquals(SlockRealtimeRoomJoinedChannelAction.Rejoin, rejoin.joinedChannelAction)
        // Joined-set stays idempotent: a plain join after force rejoin still dedupes.
        assertFalse(duplicatePlainJoin.shouldEmit)
        // Rejoin also covers rooms the local set never saw (fresh join).
        assertTrue(rejoinWhenNeverJoined.shouldEmit)
        // And leave semantics are unaffected by a prior force rejoin.
        assertTrue(leaveAfterRejoin.shouldEmit)
    }

    @Test
    fun resetRoomStateForHostClearsJoinedSetForNextSession() {
        val engine = SlockRealtimeAppScopeEngine()

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
