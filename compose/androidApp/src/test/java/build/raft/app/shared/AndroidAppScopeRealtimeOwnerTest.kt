package build.raft.app.shared

import build.raft.app.data.DataResult
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.realtime.RealtimeConnectionState
import build.raft.app.data.realtime.RealtimeAuth
import build.raft.app.data.realtime.RealtimeConnectRequest
import build.raft.app.data.realtime.RealtimeConnectionWitnessToken
import build.raft.app.data.realtime.RealtimeSocketConnectionContext
import build.raft.app.data.realtime.RealtimeSocketTransport
import build.raft.app.data.realtime.RaftRealtimeAppScopeConnection
import build.raft.app.data.realtime.RaftRealtimeAppScopeRuntime
import build.raft.app.data.realtime.RaftRealtimeAppScopeSink
import build.raft.app.data.realtime.bootstrapRealtime
import build.raft.app.core.data.realtime.SocketIoRealtimeClient
import build.raft.app.model.ChannelId
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
import build.raft.app.model.ConversationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAppScopeRealtimeOwnerTest {
    // AndroidAppScopeRealtimeOwner's scope runs on Dispatchers.Main.immediate; since the CMP switch
    // (kotlinx-coroutines 1.10) a JVM unit test has no platform Main dispatcher to fall back to.
    @Before
    fun installTestMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetTestMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun processVisibilityTracksStartedActivitiesAndIgnoresPageDuplicates() {
        val transitions = mutableListOf<Pair<Boolean, Long>>()
        val tracker = AndroidProcessVisibilityTracker { foreground, epoch ->
            transitions += foreground to epoch
        }

        tracker.onActivityStarted()
        tracker.onActivityStarted()
        tracker.onActivityStopped(changingConfigurations = false)
        tracker.onActivityStopped(changingConfigurations = false)

        assertEquals(listOf(true to 1L, false to 1L), transitions)
    }

    @Test
    fun configurationReplacementDoesNotCreateFalseProcessLifecycleEdges() {
        val transitions = mutableListOf<Pair<Boolean, Long>>()
        val tracker = AndroidProcessVisibilityTracker { foreground, epoch ->
            transitions += foreground to epoch
        }

        tracker.onActivityStarted()
        tracker.onActivityStopped(changingConfigurations = true)
        tracker.onActivityStarted()
        tracker.onActivityStopped(changingConfigurations = false)

        assertEquals(listOf(true to 1L, false to 1L), transitions)
    }

    @Test
    fun foregroundEpochAdvancesOnlyAfterTheWholeProcessWasBackgrounded() {
        val transitions = mutableListOf<Pair<Boolean, Long>>()
        val tracker = AndroidProcessVisibilityTracker { foreground, epoch ->
            transitions += foreground to epoch
        }

        tracker.onActivityStarted() // AppShell starts.
        tracker.onActivityStarted() // Thread starts before AppShell stops.
        tracker.onActivityStopped(changingConfigurations = false) // AppShell stops.

        assertEquals(1L, tracker.currentForegroundEpoch())
        assertEquals(listOf(true to 1L), transitions)

        tracker.onActivityStopped(changingConfigurations = false) // Thread backgrounds.
        tracker.onActivityStarted() // A real process foreground follows.

        assertEquals(2L, tracker.currentForegroundEpoch())
        assertEquals(
            listOf(true to 1L, false to 1L, true to 2L),
            transitions
        )
    }

    @Test
    fun lifecycleEventsKeepTheActualActivityPageIdentity() {
        assertEquals(
            RaftCmpActivity.PAGE_THREAD,
            normalizedAndroidLifecyclePageName(RaftCmpActivity.PAGE_THREAD)
        )
        assertEquals(
            RaftCmpActivity.PAGE_APP_SHELL,
            normalizedAndroidLifecyclePageName("  ")
        )
    }

    @Test
    fun backgroundUnreadNotificationPolicyRequiresBackgroundEnabledPreferenceAndNoConsumers() {
        assertTrue(
            AndroidAppScopeRealtimeOwner.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = true,
                isAppInForeground = false
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = true,
                preferenceEnabled = true,
                isAppInForeground = false
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = false,
                isAppInForeground = false
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = true,
                isAppInForeground = true
            )
        )
        // FCM covers this server: the background socket must not post a second copy.
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldPostBackgroundUnreadNotification(
                hasAttachedConsumers = false,
                preferenceEnabled = true,
                isAppInForeground = false,
                remotePushActive = true
            )
        )
    }

    @Test
    fun backgroundUnreadNotificationKindTreatsThreadMessagesAsThread() {
        val message =
            message(
                channelId = "thread-channel",
                threadChannelId = "thread-channel",
                parentMessageId = "parent-message"
            )

        assertEquals(
            ConversationKind.Thread,
            AndroidAppScopeRealtimeOwner.backgroundUnreadNotificationConversationKind(message)
        )
    }

    @Test
    fun backgroundUnreadNotificationKindTreatsChannelMessagesAsChannel() {
        val message = message(channelId = "channel-1")

        assertEquals(
            ConversationKind.Channel,
            AndroidAppScopeRealtimeOwner.backgroundUnreadNotificationConversationKind(message)
        )
    }

    @Test
    fun foregroundReconnectAfterLongBackgroundUsesFiveMinuteThreshold() {
        val hiddenAt = 1_000L

        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldForceReconnectAfterLongBackground(
                hiddenAtMillis = null,
                shownAtMillis = hiddenAt + 10_000L
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldForceReconnectAfterLongBackground(
                hiddenAtMillis = hiddenAt,
                shownAtMillis = hiddenAt
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.shouldForceReconnectAfterLongBackground(
                hiddenAtMillis = hiddenAt,
                shownAtMillis = hiddenAt + 299_999L
            )
        )
        assertTrue(
            AndroidAppScopeRealtimeOwner.shouldForceReconnectAfterLongBackground(
                hiddenAtMillis = hiddenAt,
                shownAtMillis = hiddenAt + 300_000L
            )
        )
    }

    @Test
    fun bootstrapRuntimeVisibilityFollowsCurrentAndroidOwnerVisibility() {
        val foregroundRuntime = realtimeRuntime()
        val backgroundRuntime = realtimeRuntime()

        AndroidAppScopeRealtimeOwner.syncRuntimeVisibility(
            foregroundRuntime,
            AndroidAppScopeRealtimeOwner.AppVisibility.Foreground
        )
        AndroidAppScopeRealtimeOwner.syncRuntimeVisibility(
            backgroundRuntime,
            AndroidAppScopeRealtimeOwner.AppVisibility.Background
        )

        assertTrue(foregroundRuntime.isAppInForeground)
        assertFalse(backgroundRuntime.isAppInForeground)
    }

    @Test
    fun realtimeReconnectRequiresForegroundOrBackgroundKeepAlive() {
        assertTrue(
            AndroidAppScopeRealtimeOwner.realtimeReconnectAllowed(
                visibility = AndroidAppScopeRealtimeOwner.AppVisibility.Foreground,
                backgroundKeepAliveEnabled = false,
            )
        )
        assertTrue(
            AndroidAppScopeRealtimeOwner.realtimeReconnectAllowed(
                visibility = AndroidAppScopeRealtimeOwner.AppVisibility.Background,
                backgroundKeepAliveEnabled = true,
            )
        )
        assertFalse(
            AndroidAppScopeRealtimeOwner.realtimeReconnectAllowed(
                visibility = AndroidAppScopeRealtimeOwner.AppVisibility.Background,
                backgroundKeepAliveEnabled = false,
            )
        )
    }

    @Test
    fun endingKeepAliveSuspendsEveryRemainingBackgroundClient() {
        val removedTransport = OwnerPolicyFakeTransport()
        val remainingTransport = OwnerPolicyFakeTransport()
        val removedClient = SocketIoRealtimeClient(removedTransport)
        val remainingClient = SocketIoRealtimeClient(remainingTransport)
        val request =
            RealtimeConnectRequest(
                auth = RealtimeAuth(token = "access", serverId = ServerId("server-1"))
            )
        removedClient.connect(request)
        remainingClient.connect(request.copy(auth = request.auth.copy(serverId = ServerId("server-2"))))

        AndroidAppScopeRealtimeOwner.applyRealtimeReconnectPolicy(
            clients = listOf(removedClient, remainingClient),
            visibility = AndroidAppScopeRealtimeOwner.AppVisibility.Background,
            backgroundKeepAliveEnabled = true,
        )
        removedClient.disconnect()
        AndroidAppScopeRealtimeOwner.applyRealtimeReconnectPolicy(
            clients = listOf(remainingClient),
            visibility = AndroidAppScopeRealtimeOwner.AppVisibility.Background,
            backgroundKeepAliveEnabled = false,
        )

        assertEquals(1, remainingTransport.disconnectCount)
        assertEquals(RealtimeConnectionState.Disconnected, remainingClient.state)
        assertEquals(1, remainingTransport.connectCount)
    }

    @Test
    fun apiEnvironmentChangeDisposesAppScopeRealtimeRuntime() {
        assertTrue(AndroidAppScopeRealtimeOwner.shouldDisposeRuntimeForSessionEnd("api_environment_changed"))
        assertFalse(AndroidAppScopeRealtimeOwner.shouldDisposeRuntimeForSessionEnd("logout"))
        assertFalse(AndroidAppScopeRealtimeOwner.shouldDisposeRuntimeForSessionEnd("server_switch"))
    }

    private fun message(
        channelId: String? = null,
        threadChannelId: String? = null,
        parentMessageId: String? = null
    ): Message =
        Message(
            id = MessageId("message-1"),
            threadId = ThreadId(threadChannelId ?: channelId ?: "thread-1"),
            channelId = channelId?.let(::ChannelId),
            threadChannelId = threadChannelId?.let(::ChannelId),
            author =
                MemberProfile(
                    id = MemberId("member-2"),
                    displayName = "Ada",
                    handle = "ada",
                    kind = MemberKind.Human
                ),
            timestampLabel = "now",
            body = "Hello",
            parentMessageId = parentMessageId?.let(::MessageId)
        )

    private fun realtimeRuntime(): RaftRealtimeAppScopeRuntime =
        bootstrapRealtime(
            connection = FakeAppScopeConnection(),
            sink = FakeAppScopeSink(),
            enhancedNotifyBus = null,
            dispatch = { block -> kotlinx.coroutines.runBlocking { block() } }
        )

    private class FakeAppScopeConnection : RaftRealtimeAppScopeConnection {
        override val state: RealtimeConnectionState = RealtimeConnectionState.Disconnected

        override suspend fun connect(
            serverId: ServerId,
            accessToken: String,
            lastSeq: Long,
            witness: RealtimeConnectionWitnessToken,
        ): DataResult<Unit> =
            DataResult.Content(Unit)

        override fun disconnect() = Unit

        override fun emitRoomCommand(eventName: String, payload: String, force: Boolean) = Unit

        override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription =
            RealtimeSubscription {}

        override fun observeRawEvents(
            listener: (RealtimeSocketConnectionContext, String, String, Long?) -> Unit
        ): RealtimeSubscription =
            RealtimeSubscription {}
    }

    private class FakeAppScopeSink : RaftRealtimeAppScopeSink {
        override fun log(message: String) = Unit

        override fun fanOutRawRealtimeEvent(
            serverId: ServerId,
            witness: RealtimeConnectionWitnessToken,
            eventName: String,
            rawPayload: String,
            transportEpoch: Long?,
        ) = Unit

        override suspend fun maybePostBackgroundUnreadNotification(
            serverId: ServerId,
            event: NotificationPushReceived,
            isAppInForeground: Boolean
        ) = Unit
    }

    private class OwnerPolicyFakeTransport : RealtimeSocketTransport {
        var connectCount = 0
            private set
        var disconnectCount = 0
            private set

        override fun <T> linearize(block: () -> T): T = block()

        override fun connect(auth: Map<String, String>, context: RealtimeSocketConnectionContext) {
            connectCount += 1
        }

        override fun disconnect() {
            disconnectCount += 1
        }

        override fun emit(eventName: String, payload: Map<String, Any>) = Unit

        override fun on(
            eventName: String,
            listener: (RealtimeSocketConnectionContext, Any?) -> Unit,
        ): RealtimeSubscription = RealtimeSubscription {}
    }
}
