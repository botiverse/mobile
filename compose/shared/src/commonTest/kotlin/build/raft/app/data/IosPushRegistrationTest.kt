package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class IosPushRegistrationTest {
    @AfterTest
    fun tearDown() {
        SlockPushRegistrationLifecycle.clearForTests()
    }

    @Test
    fun installationIdentityIsStableForTheInstalledAppScope() {
        val store = InMemorySlockKeyValueStore()
        val coordinator = coordinator(store = store)

        val first = coordinator.installationId("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
        val second = coordinator.installationId("11111111-2222-3333-4444-555555555555")

        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", first)
        assertEquals(first, second)
        assertNotEquals("11111111-2222-3333-4444-555555555555", second)
    }

    @Test
    fun atomicRegistrationWaitsForAuthThenDedupesAndRebindsOnAccountSwitch() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        var session: StoredAuthSession? = null
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                store = store,
                currentSession = { session },
                api = api
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertFalse(
            coordinator.updateRegistration(
                deviceToken = "A0B1C2D3",
                topic = "build.raft.app",
                env = "production",
                appVersion = "1.3.0"
            )
        )
        assertTrue(api.calls.isEmpty())

        session = storedSession(userId = "user-1")
        assertTrue(coordinator.retryPendingRegistration(reason = "login"))
        assertEquals(1, api.calls.size)
        assertEquals(
            """POST {"installationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","provider":"apns","platform":"ios","deviceToken":"a0b1c2d3","topic":"build.raft.app","env":"production","appVersion":"1.3.0"}""",
            api.calls.single()
        )

        assertTrue(coordinator.retryPendingRegistration(reason = "foreground"))
        assertEquals(1, api.calls.size)

        session = storedSession(userId = "user-2")
        assertTrue(coordinator.retryPendingRegistration(reason = "account_switch"))
        assertEquals(2, api.calls.size)

        session = storedSession(userId = "user-2", serverId = "server-2")
        assertTrue(coordinator.retryPendingRegistration(reason = "server_switch"))
        assertEquals(3, api.calls.size)
    }

    @Test
    fun registrationRejectsAnyPartialOrUnsignedEnvironmentIdentity() = runBlocking {
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                currentSession = { storedSession("user-1") },
                api = api
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertFalse(coordinator.updateRegistration("", "build.raft.app", "production", "1.3.0"))
        assertFalse(coordinator.updateRegistration("a0b1", "", "production", "1.3.0"))
        assertFalse(coordinator.updateRegistration("a0b1", "build.raft.app", "release", "1.3.0"))
        assertFalse(coordinator.updateRegistration("a0b1", "build.raft.app", "sandbox", ""))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun logoutUnbindPreservesInstallationAndAllowsNextLoginToRebind() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        var session: StoredAuthSession? = storedSession("user-1")
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                store = store,
                currentSession = { session },
                api = api
            )
        val installationId = coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.updateRegistration("a0b1c2d3", "build.raft.app", "sandbox", "1.3.0"))

        coordinator.unbindBeforeLogout()
        assertEquals("DELETE $installationId", api.calls.last())
        assertEquals(installationId, coordinator.installationId("replacement-must-not-win"))

        assertFalse(coordinator.retryPendingRegistration(reason = "foreground_while_logged_out"))

        session = storedSession("user-2")
        assertTrue(coordinator.handleSessionSaved())
        assertTrue(api.calls.last().startsWith("POST "))
    }

    @Test
    fun tokenRefreshDuringLogoutCannotRaceARegistrationBackAfterUnbind() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        val calls = mutableListOf<String>()
        lateinit var coordinator: SlockIosPushRegistrationCoordinator
        val api =
            object : SlockPushRegistrationApi {
                override suspend fun upsert(payloadJson: String): HomeNetworkResponse {
                    calls += "POST"
                    return HomeNetworkResponse(code = 204, bodyText = null)
                }

                override suspend fun unbind(installationId: String): HomeNetworkResponse {
                    calls += "DELETE"
                    // A 401 retry may rotate auth tokens while DELETE is in flight.
                    // That save callback must not queue a POST after unbind succeeds.
                    coordinator.onSessionSaved()
                    return HomeNetworkResponse(code = 204, bodyText = null)
                }
            }
        coordinator =
            coordinator(
                store = store,
                currentSession = { storedSession("user-1") },
                api = api
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.updateRegistration("a0b1c2d3", "build.raft.app", "production", "1.3.0"))

        coordinator.unbindBeforeLogout()

        assertEquals(listOf("POST", "DELETE"), calls)
        assertFalse(coordinator.retryPendingRegistration(reason = "post_logout_foreground"))
        assertEquals(listOf("POST", "DELETE"), calls)
    }

    @Test
    fun networkApiUsesTheRfc045RegistrationRoutes() = runBlocking {
        val network = RecordingNetworkClient()
        val api = SlockPushRegistrationNetworkApi(network)

        api.upsert("""{"installationId":"installation-1"}""")
        api.unbind("installation-1")

        assertEquals(
            listOf(
                "POST push/registrations body={\"installationId\":\"installation-1\"}",
                "DELETE push/registrations/installation-1 body=null"
            ),
            network.calls
        )
    }

    private fun coordinator(
        store: SlockKeyValueStore = InMemorySlockKeyValueStore(),
        currentSession: suspend () -> StoredAuthSession? = { null },
        api: SlockPushRegistrationApi = RecordingPushRegistrationApi()
    ): SlockIosPushRegistrationCoordinator =
        SlockIosPushRegistrationCoordinator(
            store = store,
            currentSession = currentSession,
            apiProvider = { api },
            scope = CoroutineScope(SupervisorJob())
        )

    private fun storedSession(userId: String, serverId: String = "server-1"): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@example.com"),
            accessToken = "access-$userId",
            refreshToken = "refresh-$userId",
            selectedServerId = serverId
        )

    private class RecordingPushRegistrationApi : SlockPushRegistrationApi {
        val calls = mutableListOf<String>()

        override suspend fun upsert(payloadJson: String): HomeNetworkResponse {
            calls += "POST $payloadJson"
            return HomeNetworkResponse(code = 204, bodyText = null)
        }

        override suspend fun unbind(installationId: String): HomeNetworkResponse {
            calls += "DELETE $installationId"
            return HomeNetworkResponse(code = 204, bodyText = null)
        }
    }

    private class RecordingNetworkClient : SlockNetworkClient {
        val calls = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            calls += "$method $path body=$bodyText"
            return HomeNetworkResponse(code = 204, bodyText = null)
        }
    }
}
