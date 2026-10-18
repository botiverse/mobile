package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class PushRegistrationTest {
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
        assertEquals(2, api.calls.size, "pure selected-server changes do not rebind user-scoped push")
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
        val remoteNotifications = RecordingRemoteNotificationsController()
        val coordinator =
            coordinator(
                store = store,
                currentSession = { session },
                api = api,
                remoteNotificationsController = remoteNotifications
            )
        val installationId = coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.updateRegistration("a0b1c2d3", "build.raft.app", "sandbox", "1.3.0"))

        coordinator.unbindBeforeLogout()
        assertEquals("DELETE $installationId", api.calls.last())
        assertEquals(listOf(true, false), remoteNotifications.states)
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
        lateinit var coordinator: SlockPushRegistrationCoordinator
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

                override suspend fun deliveryStatus(installationId: String): HomeNetworkResponse =
                    HomeNetworkResponse(code = 404, bodyText = null)
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
    fun typedSessionEventsTriggerStartupReconcileAndRegistrationWake() = runBlocking {
        val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 4)
        val observed = CompletableDeferred<Unit>()
        var savedEvents = 0
        SlockPushRegistrationLifecycle.install(
            object : SlockPushRegistrationLifecycleSink {
                override fun onSessionSaved() {
                    savedEvents += 1
                    if (savedEvents == 2) observed.complete(Unit)
                }

                override fun onSessionInvalidated(reason: AuthSessionClearReason) = Unit

                override suspend fun unbindBeforeLogout() = Unit
            }
        )
        SlockPushRegistrationLifecycle.observeSessionEvents(
            source = events,
            scope = CoroutineScope(SupervisorJob())
        )

        events.emit(AuthSessionEvent.Authenticated(generation = 1))

        withTimeout(1_000) { observed.await() }
    }

    @Test
    fun typedAuthLossRoutesToInvalidationAndRejectsLateOlderEvents() = runBlocking {
        val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 4)
        val invalidated = CompletableDeferred<AuthSessionClearReason>()
        var savedEvents = 0
        SlockPushRegistrationLifecycle.install(
            object : SlockPushRegistrationLifecycleSink {
                override fun onSessionSaved() {
                    savedEvents += 1
                }

                override fun onSessionInvalidated(reason: AuthSessionClearReason) {
                    invalidated.complete(reason)
                }

                override suspend fun unbindBeforeLogout() = Unit
            }
        )
        SlockPushRegistrationLifecycle.observeSessionEvents(
            source = events,
            scope = CoroutineScope(SupervisorJob())
        )

        events.emit(AuthSessionEvent.TokensRotated(generation = 2))
        events.emit(AuthSessionEvent.Cleared(generation = 1, reason = AuthSessionClearReason.Logout))
        events.emit(AuthSessionEvent.Cleared(generation = 3, reason = AuthSessionClearReason.AuthLoss))

        assertEquals(AuthSessionClearReason.AuthLoss, withTimeout(1_000) { invalidated.await() })
        assertEquals(2, savedEvents, "startup reconcile plus generation-2 wake; stale generation-1 is ignored")
    }

    @Test
    fun crossInstanceMatchedAuthLossClearUnregistersWithoutAuthenticatedDelete() = runBlocking {
        val authKv = InMemorySlockKeyValueStore()
        val observedStore = KeyValueAuthSessionStore(authKv)
        val clearingStore = KeyValueAuthSessionStore(authKv)
        val session = storedSession("user-1")
        observedStore.replaceAuthenticatedSession(session)
        val remoteNotifications = RecordingRemoteNotificationsController()
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                currentSession = { observedStore.readSession() },
                api = api,
                remoteNotificationsController = remoteNotifications
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.updateRegistration("a0b1c2d3", "build.raft.app", "production", "1.3.0"))

        val unregistered = CompletableDeferred<Unit>()
        remoteNotifications.onDisabled = { unregistered.complete(Unit) }
        SlockPushRegistrationLifecycle.install(coordinator)
        SlockPushRegistrationLifecycle.observeSessionEvents(
            source = observedStore.authSessionEvents,
            scope = CoroutineScope(SupervisorJob())
        )

        assertTrue(
            clearingStore.clearSessionFromSessionClearedNotify(
                reason = "remote_session_cleared",
                previousSessionFingerprint = session.authSessionClearFingerprint()
            )
        )
        withTimeout(1_000) { unregistered.await() }

        assertEquals(listOf(true, false), remoteNotifications.states)
        assertEquals(1, api.calls.count { it.startsWith("POST ") })
        assertEquals(0, api.calls.count { it.startsWith("DELETE ") }, "auth loss has no valid token for DELETE")
    }

    @Test
    fun authenticatedStartupRegistersOnceAndAuthLossUnregistersBeforeNextLogin() = runBlocking {
        var session: StoredAuthSession? = storedSession("user-1")
        val remoteNotifications = RecordingRemoteNotificationsController()
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                currentSession = { session },
                api = api,
                remoteNotificationsController = remoteNotifications
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertFalse(coordinator.handleSessionSaved(), "registration waits for the APNs token")
        assertEquals(listOf(true), remoteNotifications.states)

        assertFalse(coordinator.handleSessionSaved(), "token rotation does not repeat APNs registration")
        assertEquals(listOf(true), remoteNotifications.states)

        assertTrue(coordinator.updateRegistration("a0b1c2d3", "build.raft.app", "production", "1.3.0"))
        coordinator.handleSessionInvalidated(reason = "auth_loss")
        assertEquals(listOf(true, false), remoteNotifications.states)
        assertEquals(1, api.calls.count { it.startsWith("POST ") })

        session = storedSession("user-1")
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(listOf(true, false, true), remoteNotifications.states)
        assertEquals(2, api.calls.count { it.startsWith("POST ") }, "new auth family must rebind")
    }

    @Test
    fun loggedOutStartupUnregistersWithoutCallingTheRegistrationApi() = runBlocking {
        val remoteNotifications = RecordingRemoteNotificationsController()
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                currentSession = { null },
                api = api,
                remoteNotificationsController = remoteNotifications
            )

        assertFalse(coordinator.handleSessionSaved())

        assertEquals(listOf(false), remoteNotifications.states)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun fcmRegistrationSendsTheFirebaseInstallationIdUnchanged() = runBlocking {
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                spec = SlockPushProviderSpec.Fcm,
                currentSession = { storedSession(userId = "user-1") },
                api = api
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertTrue(
            coordinator.updateRegistration(
                deviceToken = " cX9-AbC_dEfGhIjKlMnOpQ ",
                topic = "build.raft.app",
                env = "production",
                appVersion = "1.3.0"
            )
        )
        assertEquals(
            """POST {"installationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","provider":"fcm","platform":"android","deviceToken":"cX9-AbC_dEfGhIjKlMnOpQ","topic":"build.raft.app","env":"production","appVersion":"1.3.0"}""",
            api.calls.single(),
            "an FID is case-sensitive: it is trimmed but never lowercased"
        )
    }

    @Test
    fun deliveryStatusIsFetchedAfterUpsertAndStoredForEnvironmentUserAndInstallation() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true, "server-2" to false)
        val coordinator = deliveryStatusCoordinator(store, currentSession = { storedSession("user-1") }, api = api)
        val installationId = coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertTrue(coordinator.updateRegistration("cX9-AbC_dEfGhIjKlMnOpQ", "build.raft.app", "production", "1.3.0"))

        assertEquals("GET delivery-status $installationId", api.calls.last())
        assertEquals(
            SlockPushDeliveryStatus(
                environmentId = "https://api.example",
                userId = "user-1",
                installationId = installationId,
                fcmActiveByServer = mapOf("server-1" to true, "server-2" to false)
            ),
            coordinator.storedDeliveryStatus()
        )
    }

    @Test
    fun failedOrMissingDeliveryStatusRouteKeepsThePreviousAnswer() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        val api = RecordingPushRegistrationApi()
        val coordinator = deliveryStatusCoordinator(store, currentSession = { storedSession("user-1") }, api = api)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        // An older server without the route: nothing stored, so the background path posts locally.
        assertFalse(coordinator.refreshDeliveryStatus(reason = "test"))
        assertEquals(null, coordinator.storedDeliveryStatus())

        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        assertTrue(coordinator.refreshDeliveryStatus(reason = "test"))
        val answer = coordinator.storedDeliveryStatus()

        api.deliveryStatusResponse = HomeNetworkResponse(code = 404, bodyText = null)
        assertFalse(coordinator.refreshDeliveryStatus(reason = "test"))
        api.deliveryStatusResponse = HomeNetworkResponse(code = 503, bodyText = null)
        assertFalse(coordinator.refreshDeliveryStatus(reason = "test"))
        api.deliveryStatusResponse = HomeNetworkResponse(code = 200, bodyText = """{"unexpected":true}""")
        assertFalse(coordinator.refreshDeliveryStatus(reason = "test"))
        assertEquals(answer, coordinator.storedDeliveryStatus())
    }

    @Test
    fun logoutClearsTheDeliveryStatusAndAccountSwitchRefetches() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        var session: StoredAuthSession? = storedSession("user-1")
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        val coordinator = deliveryStatusCoordinator(store, currentSession = { session }, api = api)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        coordinator.handleSessionSaved()
        assertEquals("user-1", coordinator.storedDeliveryStatus()?.userId, "login fetches")

        // Same user again (token rotation, selection change): the stored answer still matches, no fetch.
        val fetches = api.calls.count { it.startsWith("GET") }
        coordinator.handleSessionSaved()
        assertEquals(fetches, api.calls.count { it.startsWith("GET") })

        coordinator.unbindBeforeLogout()
        assertEquals(null, coordinator.storedDeliveryStatus(), "logout clears")

        session = storedSession("user-2")
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to false)
        coordinator.handleSessionSaved()
        assertEquals("user-2", coordinator.storedDeliveryStatus()?.userId)
        assertEquals(mapOf("server-1" to false), coordinator.storedDeliveryStatus()?.fcmActiveByServer)
    }

    @Test
    fun deliveryStatusLandingAfterAnAccountSwitchIsNotStored() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        var session: StoredAuthSession? = storedSession("user-1")
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        api.onDeliveryStatus = { session = storedSession("user-2") }
        val coordinator = deliveryStatusCoordinator(store, currentSession = { session }, api = api)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertFalse(coordinator.refreshDeliveryStatus(reason = "test"))
        assertEquals(null, coordinator.storedDeliveryStatus())
    }

    @Test
    fun withoutAnEnvironmentProviderTheCoordinatorNeverAsksForDeliveryStatus() = runBlocking {
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        val coordinator = coordinator(currentSession = { storedSession("user-1") }, api = api)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertTrue(coordinator.updateRegistration("a0b1c2d3", "build.raft.app", "production", "1.3.0"))
        assertFalse(coordinator.refreshDeliveryStatus(reason = "test"))
        assertTrue(api.calls.none { it.startsWith("GET") }, "iOS (APNs) behaviour is unchanged")
        assertEquals(null, coordinator.storedDeliveryStatus())
    }

    @Test
    fun fcmRegistrationRejectsSandboxAndMalformedIds() = runBlocking {
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                spec = SlockPushProviderSpec.Fcm,
                currentSession = { storedSession(userId = "user-1") },
                api = api
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertFalse(
            coordinator.updateRegistration("cX9AbCdEfGhIjKlMnOpQrS", "build.raft.app", "sandbox", "1.3.0"),
            "FCM has no sandbox"
        )
        assertFalse(coordinator.updateRegistration("has space", "build.raft.app", "production", "1.3.0"))
        assertFalse(coordinator.updateRegistration("", "build.raft.app", "production", "1.3.0"))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun apnsSpecStillRejectsAnFcmShapedToken() = runBlocking {
        val api = RecordingPushRegistrationApi()
        val coordinator = coordinator(currentSession = { storedSession(userId = "user-1") }, api = api)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertFalse(coordinator.updateRegistration("cX9-AbC:dEf", "build.raft.app", "production", "1.3.0"))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun networkApiUsesTheRfc045RegistrationRoutes() = runBlocking {
        val network = RecordingNetworkClient()
        val api = SlockPushRegistrationNetworkApi(network)

        api.upsert("""{"installationId":"installation-1"}""")
        api.unbind("installation-1")
        api.deliveryStatus("installation-1")

        assertEquals(
            listOf(
                "POST push/registrations body={\"installationId\":\"installation-1\"}",
                "DELETE push/registrations/installation-1 body=null",
                "GET push/delivery-status?installationId=installation-1 body=null"
            ),
            network.calls
        )
    }

    private fun coordinator(
        store: SlockKeyValueStore = InMemorySlockKeyValueStore(),
        spec: SlockPushProviderSpec = SlockPushProviderSpec.Apns,
        currentSession: suspend () -> StoredAuthSession? = { null },
        api: SlockPushRegistrationApi = RecordingPushRegistrationApi(),
        remoteNotificationsController: SlockIosRemoteNotificationsController =
            RecordingRemoteNotificationsController(),
        currentEnvironmentId: (() -> String?)? = null,
        scope: CoroutineScope = CoroutineScope(SupervisorJob())
    ): SlockPushRegistrationCoordinator =
        SlockPushRegistrationCoordinator(
            store = store,
            spec = spec,
            currentSession = currentSession,
            apiProvider = { api },
            scope = scope,
            remoteNotificationsController = remoteNotificationsController,
            currentEnvironmentId = currentEnvironmentId
        )

    /** Unconfined: a refresh launched by the coordinator has finished when the triggering call returns. */
    private fun deliveryStatusCoordinator(
        store: SlockKeyValueStore,
        currentSession: suspend () -> StoredAuthSession?,
        api: SlockPushRegistrationApi,
        environmentId: () -> String? = { "https://api.example/" }
    ): SlockPushRegistrationCoordinator =
        coordinator(
            store = store,
            spec = SlockPushProviderSpec.Fcm,
            currentSession = currentSession,
            api = api,
            currentEnvironmentId = environmentId,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )

    private fun deliveryStatusBody(vararg servers: Pair<String, Boolean>): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                servers.joinToString(prefix = """{"servers":{""", postfix = "}}") { (id, active) ->
                    """"$id":{"fcmActive":$active}"""
                }
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

        var deliveryStatusResponse: HomeNetworkResponse = HomeNetworkResponse(code = 404, bodyText = null)
        var onDeliveryStatus: (() -> Unit)? = null

        override suspend fun deliveryStatus(installationId: String): HomeNetworkResponse {
            calls += "GET delivery-status $installationId"
            onDeliveryStatus?.invoke()
            return deliveryStatusResponse
        }
    }

    private class RecordingRemoteNotificationsController : SlockIosRemoteNotificationsController {
        val states = mutableListOf<Boolean>()
        var onDisabled: (() -> Unit)? = null

        override suspend fun setEnabled(enabled: Boolean) {
            states += enabled
            if (!enabled) onDisabled?.invoke()
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
            val queryText = if (query.isEmpty()) "" else "?" + query.joinToString("&") { (k, v) -> "$k=$v" }
            calls += "$method $path$queryText body=$bodyText"
            return HomeNetworkResponse(code = 204, bodyText = null)
        }
    }
}
