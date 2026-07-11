package build.raft.app.shared

import build.raft.app.data.DataResult
import build.raft.app.data.RealtimeSubscription
import build.raft.app.data.realtime.RealtimeConnectionState
import build.raft.app.data.realtime.SlockRealtimeAppScopeConnection
import build.raft.app.data.realtime.SlockRealtimeAppScopeRuntime
import build.raft.app.data.realtime.SlockRealtimeAppScopeSink
import build.raft.app.data.realtime.bootstrapRealtime
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppScopeRealtimeOwnerTest {
    @Test
    fun hiddenVisibilityClosesReceiptGateBeforeRuntime() {
        val calls = mutableListOf<String>()

        applyAndroidHostVisibilityTransition(
            visibility = AndroidHostVisibility.Hidden,
            publishVisibility = { calls += "gate_hidden" },
            applyRuntimeVisibility = { calls += "runtime_hidden" }
        )

        assertEquals(listOf("gate_hidden", "runtime_hidden"), calls)
    }

    @Test
    fun visibleVisibilityRestoresRuntimeBeforeOpeningReceiptGate() {
        val calls = mutableListOf<String>()

        applyAndroidHostVisibilityTransition(
            visibility = AndroidHostVisibility.Visible,
            publishVisibility = { calls += "gate_visible" },
            applyRuntimeVisibility = { calls += "runtime_visible" }
        )

        assertEquals(listOf("runtime_visible", "gate_visible"), calls)
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

    private fun realtimeRuntime(): SlockRealtimeAppScopeRuntime =
        bootstrapRealtime(
            connection = FakeAppScopeConnection(),
            sink = FakeAppScopeSink(),
            enhancedNotifyBus = null,
            dispatch = { block -> kotlinx.coroutines.runBlocking { block() } }
        )

    private class FakeAppScopeConnection : SlockRealtimeAppScopeConnection {
        override val state: RealtimeConnectionState = RealtimeConnectionState.Disconnected

        override suspend fun connect(serverId: ServerId, accessToken: String, lastSeq: Long): DataResult<Unit> =
            DataResult.Content(Unit)

        override fun disconnect() = Unit

        override fun emitRoomCommand(eventName: String, payload: String, force: Boolean) = Unit

        override fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription =
            RealtimeSubscription {}

        override fun observeRawEvents(listener: (eventName: String, rawPayload: String) -> Unit): RealtimeSubscription =
            RealtimeSubscription {}
    }

    private class FakeAppScopeSink : SlockRealtimeAppScopeSink {
        override fun log(message: String) = Unit

        override fun fanOutRawRealtimeEvent(serverId: ServerId, eventName: String, rawPayload: String) = Unit

        override suspend fun maybePostBackgroundUnreadNotification(
            serverId: ServerId,
            event: NotificationPushReceived,
            isAppInForeground: Boolean
        ) = Unit
    }
}
