package ai.slock.compose.data.realtime

import ai.slock.compose.data.DataResult
import ai.slock.compose.data.RealtimeSubscription
import ai.slock.compose.data.SlockEnhancedNotifyEvent
import ai.slock.compose.data.SlockEnhancedNotifyEventBus
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ThreadId
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
    fun coordinatorSkipsSameConnectedSessionAndReusesHighestSeqOnForcedReconnect() = runBlocking {
        val connection = FakeAppScopeConnection()
        val coordinator = SlockRealtimeAppScopeCoordinator(connection, FakeAppScopeSink())

        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 7, forceReconnect = false)
        coordinator.onMappedEvent(MessageNew(message(seq = 42)))
        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 10, forceReconnect = false)
        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 10, forceReconnect = true)

        assertEquals(listOf(7L, 42L), connection.connectedLastSeqs)
    }

    @Test
    fun coordinatorAutoJoinsDirectMessagesAndFiltersHeartbeatFanout() = runBlocking {
        val connection = FakeAppScopeConnection()
        val sink = FakeAppScopeSink()
        val coordinator = SlockRealtimeAppScopeCoordinator(connection, sink)

        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 0, forceReconnect = false)
        coordinator.onMappedEvent(DirectMessageCreated(ChannelId("dm-1")))
        coordinator.onRawEvent(RealtimeSocketEvents.Heartbeat, "{}")
        coordinator.onRawEvent(RealtimeSocketEvents.MessageNew, """{"id":"message-1"}""")

        assertEquals(listOf(ChannelId("dm-1")), connection.joinedChannels)
        assertEquals(listOf(RealtimeSocketEvents.MessageNew), sink.fanOutEventNames)
    }

    @Test
    fun coordinatorConsumesRoomCommandsBeforeEmittingJoin() = runBlocking {
        val connection = FakeAppScopeConnection()
        val enhancedNotifyBus = FakeEnhancedNotifyBus()
        val coordinator = SlockRealtimeAppScopeCoordinator(connection, FakeAppScopeSink(), enhancedNotifyBus)

        coordinator.connect(ServerId("server-1"), "token-1", requestedLastSeq = 0, forceReconnect = false)

        enhancedNotifyBus.emitRoomCommand(
            action = SlockRealtimeNotifyContract.ActionJoinChannel,
            channelId = "channel-1"
        )
        enhancedNotifyBus.emitRoomCommand(
            action = SlockRealtimeNotifyContract.ActionJoinChannel,
            channelId = "channel-1"
        )
        enhancedNotifyBus.emitRoomCommand(action = "unsupported", channelId = "channel-2")
        enhancedNotifyBus.emitRoomCommand(
            action = SlockRealtimeNotifyContract.ActionJoinChannel,
            channelId = ""
        )
        enhancedNotifyBus.emit(
            SlockEnhancedNotifyEvent(
                eventName = "unrelated",
                eventData = JSONObject().put(SlockRealtimeNotifyContract.KeyChannelId, "channel-3")
            )
        )

        assertEquals(listOf(ChannelId("channel-1")), connection.joinedChannels)
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
        assertEquals(42L, forced.resumeSeq)
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

        assertTrue(join.shouldJoin)
        assertEquals(ChannelId("channel-1"), join.channelId)
        assertFalse(duplicateJoin.shouldJoin)
        assertFalse(blankJoin.shouldJoin)
        assertFalse(unsupportedJoin.shouldJoin)
        assertFalse(heartbeat.shouldFanOut)
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

        fun emitRoomCommand(action: String, channelId: String) {
            emit(
                SlockEnhancedNotifyEvent(
                    eventName = SlockRealtimeNotifyContract.RoomsEventName,
                    eventData =
                        JSONObject()
                            .put(SlockRealtimeNotifyContract.KeyAction, action)
                            .put(SlockRealtimeNotifyContract.KeyChannelId, channelId),
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
        val joinedChannels = mutableListOf<ChannelId>()

        override suspend fun connect(
            serverId: ServerId,
            accessToken: String,
            lastSeq: Long
        ): DataResult<Unit> {
            connectedLastSeqs += lastSeq
            state = RealtimeConnectionState.Connected
            return DataResult.Content(Unit)
        }

        override fun disconnect() {
            state = RealtimeConnectionState.Disconnected
        }

        override fun emitJoinChannel(channelId: ChannelId) {
            joinedChannels += channelId
        }

        override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription =
            RealtimeSubscription { }

        override fun observeRawEvents(listener: (eventName: String, rawPayload: String) -> Unit): RealtimeSubscription =
            RealtimeSubscription { }
    }

    private class FakeAppScopeSink : SlockRealtimeAppScopeSink {
        val fanOutEventNames = mutableListOf<String>()

        override fun log(message: String) = Unit

        override fun fanOutRawRealtimeEvent(
            serverId: ServerId,
            eventName: String,
            rawPayload: String
        ) {
            fanOutEventNames += eventName
        }

        override suspend fun maybePostBackgroundUnreadNotification(
            serverId: ServerId,
            event: MessageNew,
            isAppInForeground: Boolean
        ) = Unit
    }
}
