package ai.slock.android.core.data.realtime

import ai.slock.android.core.data.auth.AuthRepository
import ai.slock.android.core.data.auth.AuthResult
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.KtorAuthRepository
import ai.slock.android.core.data.network.RealChannelRepository
import ai.slock.android.core.data.network.RealConversationRepository
import ai.slock.android.core.data.network.RealSlockApiFactory
import ai.slock.android.core.data.repository.CreateChannelRequest
import ai.slock.compose.data.DataResult
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.ServerId
import ai.slock.compose.network.SlockRequestPriority
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveRealtimeServerScopeTest {
    @Test
    fun liveCaptureInboxAndRealtimePayloadShape() = runBlocking {
        val fixture = LiveFixture.login()
        val api = RealSlockApiFactory.create(fixture.authStore, baseUrl = fixture.baseUrl)
        val channelRepository = RealChannelRepository(fixture.authStore, api, warningLogger = {})
        val conversationRepository = RealConversationRepository(fixture.authStore, api, warningLogger = {})
        val artifact = LiveArtifact("live-realtime-payload-shape")
        val serverId = requireNotNull(fixture.session.currentServerId)

        val inboxResponse = api.inbox(SlockRequestPriority.Visible, filter = "all", limit = 30, offset = 0)
        val inboxBody = inboxResponse.body()?.string().orEmpty()
        artifact.record(
            "rest_inbox",
            mapOf(
                "code" to inboxResponse.code(),
                "selectedServerId" to serverId,
                "containsServerScopeKey" to inboxBody.containsAnyServerScopeKey(),
                "topLevelKeys" to inboxBody.topLevelKeys()
            )
        )

        val suffix = System.currentTimeMillis().toString(36)
        val channelId = channelRepository.createChannel(
            CreateChannelRequest(
                name = "android-live-realtime-$suffix",
                description = "Temporary channel created by LiveRealtimeServerScopeTest"
            )
        ).contentOrThrow("create channel").channel.channelId
            ?: error("Create channel response did not include channelId.")

        val transport = SocketIoSocketTransport()
        val connected = CountDownLatch(1)
        val realtimePayload = CountDownLatch(1)
        SocketIoRealtimeClient.RealtimeWireEventNames.forEach { eventName ->
            transport.on(eventName) { payload ->
                artifact.record(
                    "socket_event",
                    mapOf(
                        "eventName" to eventName,
                        "payloadType" to payload?.javaClass?.simpleName.orEmpty(),
                        "containsServerScopeKey" to payload.toString().containsAnyServerScopeKey(),
                        "payload" to payload.toString().take(MAX_PAYLOAD_CHARS)
                    )
                )
                realtimePayload.countDown()
            }
        }
        transport.on(RealtimeSocketEvents.Connect) { connected.countDown() }
        transport.on(RealtimeSocketEvents.RoomsJoined) { payload ->
            artifact.record(
                "socket_rooms_joined",
                mapOf(
                    "payloadType" to payload?.javaClass?.simpleName.orEmpty(),
                    "payload" to payload.toString().take(MAX_PAYLOAD_CHARS)
                )
            )
        }

        try {
            transport.connect(mapOf("token" to fixture.session.accessToken, "serverId" to serverId))
            assertTrue("Socket did not connect for live payload capture.", connected.await(15, TimeUnit.SECONDS))
            transport.emit(RealtimeSocketEvents.JoinChannel, mapOf("channelId" to channelId.value))
            conversationRepository.sendMessage(
                channelId = channelId,
                body = "live realtime payload capture $suffix"
            ).contentOrThrow("send message")
            assertTrue("Socket did not receive a realtime payload to inspect.", realtimePayload.await(20, TimeUnit.SECONDS))
        } finally {
            transport.disconnect()
            api.archiveChannel(SlockRequestPriority.Visible, channelId.value)
        }
    }

    @Test
    fun liveSocketSwitchDoesNotDeliverPreviousServerRoomMessages() = runBlocking {
        val fixture = LiveFixture.login()
        val firstServer = requireNotNull(fixture.session.currentServerId)
        val secondServer = fixture.session.servers.firstOrNull { it.id != firstServer }
        assumeTrue("Live account needs at least two servers to verify cross-server socket delivery.", secondServer != null)

        val api = RealSlockApiFactory.create(fixture.authStore, baseUrl = fixture.baseUrl)
        val channelRepository = RealChannelRepository(fixture.authStore, api, warningLogger = {})
        val conversationRepository = RealConversationRepository(fixture.authStore, api, warningLogger = {})
        val artifact = LiveArtifact("live-realtime-server-switch")
        val suffix = System.currentTimeMillis().toString(36)
        val channelId = channelRepository.createChannel(
            CreateChannelRequest(
                name = "android-live-switch-$suffix",
                description = "Temporary channel created by LiveRealtimeServerScopeTest"
            )
        ).contentOrThrow("create channel").channel.channelId
            ?: error("Create channel response did not include channelId.")

        val transport = SocketIoSocketTransport()
        val client = SocketIoRealtimeClient(
            transport = transport,
            logger = { artifact.record("client_log", mapOf("message" to it)) }
        )
        val firstConnected = CountDownLatch(1)
        val secondConnected = CountDownLatch(1)
        val connectCount = AtomicInteger(0)
        val receivedPreviousServerMessage = CountDownLatch(1)
        val targetBody = "live previous server message $suffix"
        transport.on(RealtimeSocketEvents.Connect) {
            if (connectCount.incrementAndGet() == 1) {
                firstConnected.countDown()
            } else {
                secondConnected.countDown()
            }
        }
        client.observeEvents { event ->
            artifact.record(
                "mapped_event",
                mapOf("eventType" to event::class.simpleName.orEmpty(), "event" to event.toString().take(MAX_PAYLOAD_CHARS))
            )
            if (event is MessageNew && event.message.body == targetBody) {
                receivedPreviousServerMessage.countDown()
            }
        }

        try {
            client.connect(
                RealtimeConnectRequest(
                    auth = RealtimeAuth(fixture.session.accessToken, ServerId(firstServer))
                )
            )
            assertTrue("First socket did not connect.", firstConnected.await(15, TimeUnit.SECONDS))
            client.joinChannel(channelId)

            client.connect(
                RealtimeConnectRequest(
                    auth = RealtimeAuth(fixture.session.accessToken, ServerId(requireNotNull(secondServer).id))
                )
            )
            assertTrue("Second socket did not connect.", secondConnected.await(15, TimeUnit.SECONDS))

            fixture.authStore.updateSelectedServer(firstServer, fixture.session.servers.first { it.id == firstServer }.slug)
            conversationRepository.sendMessage(channelId = channelId, body = targetBody)
                .contentOrThrow("send previous server message")

            assertFalse(
                "Socket connected to ${secondServer.id} received a message from previous server $firstServer.",
                receivedPreviousServerMessage.await(8, TimeUnit.SECONDS)
            )
        } finally {
            transport.disconnect()
            fixture.authStore.updateSelectedServer(firstServer, fixture.session.servers.first { it.id == firstServer }.slug)
            api.archiveChannel(SlockRequestPriority.Visible, channelId.value)
        }
    }

    private class LiveArtifact(name: String) {
        private val file = File("artifacts/realtime/$name-${System.currentTimeMillis()}.jsonl")

        init {
            file.parentFile?.mkdirs()
            record("artifact", mapOf("path" to file.path))
        }

        fun record(type: String, fields: Map<String, Any?>) {
            val json = JSONObject()
            json.put("type", type)
            fields.forEach { (key, value) -> json.put(key, value) }
            file.appendText(json.toString() + "\n")
        }
    }

    private object LiveFixture {
        fun login(): Session {
            assumeTrue("Set SLOCK_LIVE_TESTS=true to run live Slock API tests.", env("SLOCK_LIVE_TESTS") == "true")
            val email = requireEnv("SLOCK_TEST_EMAIL")
            val password = requireEnv("SLOCK_TEST_PASSWORD")
            val baseUrl = env("SLOCK_BASE_URL") ?: KtorAuthRepository.BASE_URL
            val authStore = InMemoryAuthSessionStore()
            val authRepository: AuthRepository = KtorAuthRepository(
                store = authStore,
                api = KtorAuthRepository.createAuthApi(baseUrl)
            )

            val login = runBlocking { authRepository.login(email, password) }
            assertTrue(login is AuthResult.Success)
            return Session(
                authStore = authStore,
                session = requireNotNull(authStore.session),
                baseUrl = baseUrl
            )
        }

        private fun env(name: String): String? =
            System.getenv(name)?.takeIf { it.isNotBlank() }

        private fun requireEnv(name: String): String =
            requireNotNull(env(name)) { "Missing required environment variable $name" }
    }

    private data class Session(
        val authStore: InMemoryAuthSessionStore,
        val session: AuthSession,
        val baseUrl: String
    )

    private class InMemoryAuthSessionStore(
        var session: AuthSession? = null
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

        override suspend fun clearSession() {
            session = null
        }
    }

    private fun <T> DataResult<T>.contentOrThrow(label: String): T =
        when (this) {
            is DataResult.Content -> data
            is DataResult.Empty -> error("$label returned empty: ${reason.name}")
            is DataResult.Error -> error("$label failed: ${error.code}")
            is DataResult.NoPermission -> error("$label denied: ${blocker.code}")
        }

    private fun String.containsAnyServerScopeKey(): Boolean =
        SERVER_SCOPE_KEYS.any { contains(it, ignoreCase = true) }

    private fun String.topLevelKeys(): String =
        runCatching {
            JSONObject(this).keys().asSequence().joinToString("|")
        }.getOrDefault("")

    private companion object {
        const val MAX_PAYLOAD_CHARS = 4_000
        val SERVER_SCOPE_KEYS = listOf("serverId", "server_id", "workspaceId", "workspace_id")
    }
}
