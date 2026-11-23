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
import build.raft.app.data.realtime.SocketIoRealtimeClient
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
    fun backgroundKeepAliveLetsOnlyThePrimarySessionReconnect() {
        val sessions = connectedMultiSessionClients("server-a", "server-b", "server-c")
        val primary = ServerId("server-a")

        AndroidAppScopeRealtimeOwner.applyMultiSessionReconnectPolicy(
            clients = sessions.map { it.serverId to it.client },
            primaryServerId = primary,
            visibility = AndroidAppScopeRealtimeOwner.AppVisibility.Background,
            backgroundKeepAliveEnabled = true,
        )

        assertEquals(
            "only the primary session stays up; the others are suspended",
            mapOf("server-a" to 0, "server-b" to 1, "server-c" to 1),
            sessions.associate { it.serverId.value to it.transport.disconnectCount }
        )
        // A suspended session does not connect again on its own; the primary one does.
        sessions.forEach { it.client.connect(connectRequest(it.serverId)) }
        assertEquals(
            mapOf("server-a" to 2, "server-b" to 1, "server-c" to 1),
            sessions.associate { it.serverId.value to it.transport.connectCount }
        )
        assertTrue(
            listOf("server-b", "server-c").all { id -> sessions.single { it.serverId.value == id }.client.state == RealtimeConnectionState.Disconnected }
        )

        // Keep-alive ends in the background: the primary one is suspended too (today's rule without keep-alive).
        AndroidAppScopeRealtimeOwner.applyMultiSessionReconnectPolicy(
            clients = sessions.map { it.serverId to it.client },
            primaryServerId = primary,
            visibility = AndroidAppScopeRealtimeOwner.AppVisibility.Background,
            backgroundKeepAliveEnabled = false,
        )
        assertEquals(1, sessions.first().transport.disconnectCount)
    }

    @Test
    fun foregroundAfterFiveMinutesReconnectsEveryDesiredSession() {
        val desired = listOf("server-a", "server-b", "server-c").map(::ServerId)
        val hiddenAt = 10_000L

        assertEquals(
            "every desired session, not only the primary one",
            desired,
            AndroidAppScopeRealtimeOwner.longBackgroundReconnectServers(desired, hiddenAt, hiddenAt + 300_000L)
        )
        assertTrue(AndroidAppScopeRealtimeOwner.longBackgroundReconnectServers(desired, hiddenAt, hiddenAt + 299_999L).isEmpty())
        assertTrue(AndroidAppScopeRealtimeOwner.longBackgroundReconnectServers(desired, null, hiddenAt).isEmpty())

        // Back in the foreground the suspended sessions may reconnect again.
        val sessions = connectedMultiSessionClients("server-a", "server-b")
        AndroidAppScopeRealtimeOwner.applyMultiSessionReconnectPolicy(
            clients = sessions.map { it.serverId to it.client },
            primaryServerId = ServerId("server-a"),
            visibility = AndroidAppScopeRealtimeOwner.AppVisibility.Background,
            backgroundKeepAliveEnabled = true,
        )
        AndroidAppScopeRealtimeOwner.applyMultiSessionReconnectPolicy(
            clients = sessions.map { it.serverId to it.client },
            primaryServerId = ServerId("server-a"),
            visibility = AndroidAppScopeRealtimeOwner.AppVisibility.Foreground,
            backgroundKeepAliveEnabled = true,
        )
        sessions.forEach { it.client.connect(connectRequest(it.serverId)) }
        assertEquals(listOf(2, 2), sessions.map { it.transport.connectCount })
    }

    private class MultiSessionClient(
        val serverId: ServerId,
        val transport: OwnerPolicyFakeTransport,
        val client: SocketIoRealtimeClient
    )

    private fun connectRequest(serverId: ServerId): RealtimeConnectRequest =
        RealtimeConnectRequest(auth = RealtimeAuth(token = "access", serverId = serverId))

    private fun connectedMultiSessionClients(vararg serverIds: String): List<MultiSessionClient> =
        serverIds.map { id ->
            val transport = OwnerPolicyFakeTransport()
            val client = SocketIoRealtimeClient(transport)
            client.connect(connectRequest(ServerId(id)))
            MultiSessionClient(ServerId(id), transport, client)
        }

    @Test
    fun apiEnvironmentChangeDisposesAppScopeRealtimeRuntime() {
        assertTrue(AndroidAppScopeRealtimeOwner.shouldDisposeRuntimeForSessionEnd("api_environment_changed"))
        assertFalse(AndroidAppScopeRealtimeOwner.shouldDisposeRuntimeForSessionEnd("logout"))
        assertFalse(AndroidAppScopeRealtimeOwner.shouldDisposeRuntimeForSessionEnd("server_switch"))
    }

    @Test
    fun silentTokenAndConnectionFamilyRotationKeepsSameProcessBinding() {
        val before =
            AndroidAppScopeRealtimeOwner.MultiSessionIdentity(
                environmentId = "https://api.raft.build/api",
                socketUrl = "https://api.raft.build",
                principalId = "user-1",
                authGeneration = 3L,
                connectionFamilyId = "proc:family:1",
                accessToken = "token-old",
            )
        val afterSilentRefresh =
            before.copy(
                authGeneration = 4L,
                connectionFamilyId = "proc:family:2",
                accessToken = "token-new",
            )

        assertTrue(
            "silent token refresh rotates authGeneration, connectionFamilyId, and accessToken on the same binding",
            before.sameBindingAs(afterSilentRefresh),
        )
        assertFalse(before.sameBindingAs(before.copy(principalId = "user-2")))
        assertFalse(before.sameBindingAs(before.copy(environmentId = "https://staging.raft.build/api")))
        assertFalse(before.sameBindingAs(before.copy(socketUrl = "https://staging.raft.build")))
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
