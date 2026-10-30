package build.raft.app.data

import build.raft.app.data.realtime.RaftRealtimeAppScopePolicy
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
        RaftPushRegistrationLifecycle.clearForTests()
    }

    @Test
    fun installationIdentityIsStableForTheInstalledAppScope() {
        val store = InMemoryRaftKeyValueStore()
        val coordinator = coordinator(store = store)

        val first = coordinator.installationId("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
        val second = coordinator.installationId("11111111-2222-3333-4444-555555555555")

        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", first)
        assertEquals(first, second)
        assertNotEquals("11111111-2222-3333-4444-555555555555", second)
    }

    @Test
    fun atomicRegistrationWaitsForAuthThenDedupesAndRebindsOnAccountSwitch() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
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
            """POST {"installationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","provider":"apns","platform":"ios","deviceToken":"a0b1c2d3","topic":"build.raft.app","env":"production","appVersion":"1.3.0","previewKeySet":2}""",
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
        val store = InMemoryRaftKeyValueStore()
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
        val store = InMemoryRaftKeyValueStore()
        val calls = mutableListOf<String>()
        lateinit var coordinator: RaftPushRegistrationCoordinator
        val api =
            object : RaftPushRegistrationApi {
                override suspend fun upsert(payloadJson: String): HomeNetworkResponse {
                    calls += "POST"
                    return confirmedUpsert(payloadJson)
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
        RaftPushRegistrationLifecycle.install(
            object : RaftPushRegistrationLifecycleSink {
                override fun onSessionSaved() {
                    savedEvents += 1
                    if (savedEvents == 2) observed.complete(Unit)
                }

                override fun onSessionInvalidated(reason: AuthSessionClearReason) = Unit

                override suspend fun unbindBeforeLogout() = Unit
            }
        )
        RaftPushRegistrationLifecycle.observeSessionEvents(
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
        RaftPushRegistrationLifecycle.install(
            object : RaftPushRegistrationLifecycleSink {
                override fun onSessionSaved() {
                    savedEvents += 1
                }

                override fun onSessionInvalidated(reason: AuthSessionClearReason) {
                    invalidated.complete(reason)
                }

                override suspend fun unbindBeforeLogout() = Unit
            }
        )
        RaftPushRegistrationLifecycle.observeSessionEvents(
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
        val authKv = InMemoryRaftKeyValueStore()
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
        RaftPushRegistrationLifecycle.install(coordinator)
        RaftPushRegistrationLifecycle.observeSessionEvents(
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
                spec = RaftPushProviderSpec.Fcm,
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
            """POST {"installationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","provider":"fcm","platform":"android","deviceToken":"cX9-AbC_dEfGhIjKlMnOpQ","topic":"build.raft.app","env":"production","appVersion":"1.3.0","previewKeySet":2}""",
            api.calls.single(),
            "an FID is case-sensitive: it is trimmed but never lowercased"
        )
    }

    @Test
    fun deliveryStatusFetchedAfterUpsertMakesTheEventServerRemotePushActive() = runBlocking {
        val clock = TestClock()
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true, "server-2" to false)
        // The writer sees the base URL with a trailing slash; the background reader passes it raw too.
        val coordinator =
            deliveryStatusCoordinator(currentSession = { storedSession("user-1") }, api = api, clock = clock)
        val installationId = coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertTrue(coordinator.registerFcm())

        assertTrue(coordinator.isRemotePushActiveFor("server-1", environmentId = " https://api.example/ ", now = clock.now))
        assertTrue(coordinator.isRemotePushActiveFor("server-1", environmentId = "https://api.example", now = clock.now))
        assertFalse(coordinator.isRemotePushActiveFor("server-2", now = clock.now), "fcmActive=false")
        assertFalse(coordinator.isRemotePushActiveFor("server-3", now = clock.now), "not in the answer")
        assertFalse(coordinator.isRemotePushActiveFor("server-1", userId = "user-2", now = clock.now))
        assertEquals(installationId, coordinator.currentInstallationId())
    }

    @Test
    fun deliveryStatusOlderThanThirtyMinutesIsNotActive() = runBlocking {
        val clock = TestClock()
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        val coordinator =
            deliveryStatusCoordinator(currentSession = { storedSession("user-1") }, api = api, clock = clock)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        val fetchedAt = clock.now

        assertTrue(coordinator.isRemotePushActiveFor("server-1", now = fetchedAt + PUSH_DELIVERY_STATUS_MAX_AGE_MILLIS))
        assertFalse(
            coordinator.isRemotePushActiveFor("server-1", now = fetchedAt + PUSH_DELIVERY_STATUS_MAX_AGE_MILLIS + 1)
        )

        // A refresh renews it.
        clock.now = fetchedAt + PUSH_DELIVERY_STATUS_MAX_AGE_MILLIS + 1
        assertTrue(coordinator.refreshDeliveryStatus(reason = "realtime_periodic"))
        assertTrue(coordinator.isRemotePushActiveFor("server-1", now = clock.now))
    }

    @Test
    fun failedOrMissingDeliveryStatusRouteKeepsThePreviousAnswer() = runBlocking {
        val clock = TestClock()
        val api = RecordingPushRegistrationApi()
        val coordinator =
            deliveryStatusCoordinator(currentSession = { storedSession("user-1") }, api = api, clock = clock)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        // An older server without the route: nothing stored, so the background path posts locally.
        assertTrue(coordinator.registerFcm())
        assertEquals(null, coordinator.storedDeliveryStatus())

        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        clock.advanceBeyondRequestSpacing()
        assertTrue(coordinator.refreshDeliveryStatus(reason = "app_foreground"))
        val answer = coordinator.storedDeliveryStatus()

        for (failure in listOf(
            HomeNetworkResponse(code = 404, bodyText = null),
            HomeNetworkResponse(code = 503, bodyText = null),
            HomeNetworkResponse(code = 200, bodyText = """{"unexpected":true}""")
        )) {
            api.deliveryStatusResponse = failure
            clock.advanceBeyondRequestSpacing()
            assertFalse(coordinator.refreshDeliveryStatus(reason = "app_foreground"))
            assertEquals(answer, coordinator.storedDeliveryStatus())
        }
    }

    @Test
    fun registrationChangeClearsTheAnswerUntilTheNewRegistrationIsAnswered() = runBlocking {
        val clock = TestClock()
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        val coordinator =
            deliveryStatusCoordinator(currentSession = { storedSession("user-1") }, api = api, clock = clock)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm(token = "fid-one"))
        assertTrue(coordinator.isRemotePushActiveFor("server-1", now = clock.now))

        // New FID whose upsert fails: the old answer no longer applies, and none is fetched meanwhile.
        api.upsertResponse = HomeNetworkResponse(code = 503, bodyText = null)
        assertFalse(coordinator.registerFcm(token = "fid-two"))
        assertEquals(null, coordinator.storedDeliveryStatus())
        clock.advanceBeyondRequestSpacing()
        assertFalse(coordinator.refreshDeliveryStatus(reason = "app_foreground"))
        assertFalse(coordinator.isRemotePushActiveFor("server-1", now = clock.now))

        // Once the new registration is upserted, a fresh answer applies again.
        api.upsertResponse = null
        assertTrue(coordinator.retryPendingRegistration(reason = "host_retry"))
        assertTrue(coordinator.isRemotePushActiveFor("server-1", now = clock.now))
    }

    @Test
    fun answerRequestedBeforeANewRegistrationSettledIsDropped() = runBlocking {
        val clock = TestClock()
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        val coordinator =
            deliveryStatusCoordinator(currentSession = { storedSession("user-1") }, api = api, clock = clock)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm(token = "fid-one"))
        clock.advanceBeyondRequestSpacing()

        // A periodic request is in flight when a new FID registers and settles.
        api.onDeliveryStatus = {
            api.onDeliveryStatus = null
            runBlocking { assertTrue(coordinator.registerFcm(token = "fid-two")) }
        }
        assertFalse(coordinator.refreshDeliveryStatus(reason = "periodic"))
        assertFalse(coordinator.isRemotePushActiveFor("server-1", now = clock.now))
    }

    @Test
    fun deliveryStatusRequestsAreSpacedUnlessNoAnswerExists() = runBlocking {
        val clock = TestClock()
        val store = InMemoryRaftKeyValueStore()
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        val coordinator =
            deliveryStatusCoordinator(store = store, currentSession = { storedSession("user-1") }, api = api, clock = clock)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        assertEquals(1, api.deliveryStatusRequests)

        assertFalse(coordinator.refreshDeliveryStatus(reason = "app_foreground"))
        assertFalse(coordinator.refreshDeliveryStatus(reason = "session_saved", force = true), "an answer exists")
        assertEquals(1, api.deliveryStatusRequests)
        clock.advanceBeyondRequestSpacing()
        assertTrue(coordinator.refreshDeliveryStatus(reason = "app_foreground"))
        assertEquals(2, api.deliveryStatusRequests)

        // Cold start of a new process over the same storage: startup reconcile, foreground and the realtime
        // connect fire together and cost one request.
        val restarted =
            deliveryStatusCoordinator(store = store, currentSession = { storedSession("user-1") }, api = api, clock = clock)
        restarted.handleSessionSaved()
        assertFalse(restarted.refreshDeliveryStatus(reason = "app_foreground"))
        assertFalse(restarted.refreshDeliveryStatus(reason = "realtime_connected"))
        assertEquals(3, api.deliveryStatusRequests)
    }

    @Test
    fun logoutClearsTheDeliveryStatusAndTheNextAccountGetsItsOwnAnswer() = runBlocking {
        val clock = TestClock()
        var session: StoredAuthSession? = storedSession("user-1")
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        val coordinator = deliveryStatusCoordinator(currentSession = { session }, api = api, clock = clock)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        assertTrue(coordinator.isRemotePushActiveFor("server-1", now = clock.now))

        coordinator.unbindBeforeLogout()
        assertEquals(null, coordinator.storedDeliveryStatus(), "logout clears")

        // The next login is not held back by the spacing: there is no answer for this user.
        session = storedSession("user-2")
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to false)
        assertTrue(coordinator.handleSessionSaved())
        assertFalse(coordinator.isRemotePushActiveFor("server-1", userId = "user-1", now = clock.now))
        assertFalse(coordinator.isRemotePushActiveFor("server-1", userId = "user-2", now = clock.now))
        assertEquals("user-2", coordinator.storedDeliveryStatus()?.userId)
    }

    @Test
    fun deliveryStatusLandingAfterAnAccountSwitchIsNotStored() = runBlocking {
        val clock = TestClock()
        var session: StoredAuthSession? = storedSession("user-1")
        val api = RecordingPushRegistrationApi()
        val coordinator = deliveryStatusCoordinator(currentSession = { session }, api = api, clock = clock)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())

        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        api.onDeliveryStatus = { session = storedSession("user-2") }
        clock.advanceBeyondRequestSpacing()
        assertFalse(coordinator.refreshDeliveryStatus(reason = "app_foreground"))
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
        assertEquals(0, api.deliveryStatusRequests, "iOS (APNs) behaviour is unchanged")
        assertEquals(null, coordinator.storedDeliveryStatus())
    }

    @Test
    fun fcmRegistrationRejectsSandboxAndMalformedIds() = runBlocking {
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                spec = RaftPushProviderSpec.Fcm,
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
        val api = RaftPushRegistrationNetworkApi(network)

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

    @Test
    fun upsertWithoutTheServersRegistrationEchoIsNotARegistration() = runBlocking {
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(spec = RaftPushProviderSpec.Fcm, currentSession = { storedSession("user-1") }, api = api)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        for (unconfirmed in listOf(
            HomeNetworkResponse(code = 204, bodyText = null),
            HomeNetworkResponse(code = 200, bodyText = "<html>portal</html>"),
            HomeNetworkResponse(code = 200, bodyText = """{"ok":true}"""),
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"ok":true,"registration":{"installationId":"someone-else","provider":"fcm"}}"""
            ),
            HomeNetworkResponse(
                code = 200,
                bodyText = """{"ok":true,"registration":{"installationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","provider":"apns"}}"""
            )
        )) {
            api.upsertResponse = unconfirmed
            assertFalse(coordinator.registerFcm(), "unconfirmed: ${unconfirmed.bodyText}")
        }
        assertEquals(5, api.calls.size, "each unconfirmed upsert stays pending and is tried again")

        api.upsertResponse = null
        assertTrue(coordinator.retryPendingRegistration(reason = "foreground"))
        assertEquals(6, api.calls.size)
        assertTrue(coordinator.retryPendingRegistration(reason = "foreground"))
        assertEquals(6, api.calls.size, "a confirmed registration dedupes")
    }

    @Test
    fun aConfirmationOlderThanTheRefreshIntervalIsUpsertedAgain() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val clock = TestClock()
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                store = store,
                spec = RaftPushProviderSpec.Fcm,
                currentSession = { storedSession("user-1") },
                api = api,
                clockMillis = { clock.now }
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(1, api.calls.size)

        clock.now += PUSH_REGISTRATION_REFRESH_INTERVAL_MILLIS
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(2, api.calls.size, "an idempotent re-upsert heals a registration the server dropped")
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(2, api.calls.size)

        // An install upgraded from a build that never stored the confirmation time re-confirms once.
        store.putString("lastRegisteredAtMillis", null)
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(3, api.calls.size)
    }

    @Test
    fun aNewSessionFamilyRebindsTheSameUserAndToken() = runBlocking {
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(spec = RaftPushProviderSpec.Fcm, currentSession = { storedSession("user-1") }, api = api)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        assertEquals(1, api.calls.size)

        // Token rotation and selection changes keep the binding.
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(1, api.calls.size)

        // A login (Authenticated) may have started a new session family on the server.
        assertTrue(coordinator.handleSessionSaved(forceUpsert = true))
        assertEquals(2, api.calls.size)
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(2, api.calls.size, "the forced upsert happens once")
    }

    @Test
    fun aLoginBeforeTheAuthRuntimeIsReadyStillForcesTheNextUpsert() = runBlocking {
        var authReady = true
        val api = RecordingPushRegistrationApi()
        val coordinator =
            RaftPushRegistrationCoordinator(
                store = InMemoryRaftKeyValueStore(),
                spec = RaftPushProviderSpec.Fcm,
                currentSession = { storedSession("user-1") },
                authRuntimeReady = { authReady },
                apiProvider = { api },
                scope = CoroutineScope(SupervisorJob()),
                remoteNotificationsController = RecordingRemoteNotificationsController(),
                clockMillis = { 0L }
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        assertEquals(1, api.calls.size)

        // The Authenticated event arrives while the auth runtime is not ready: nothing flushes yet...
        authReady = false
        assertFalse(coordinator.handleSessionSaved(forceUpsert = true))
        assertEquals(1, api.calls.size)

        // ...but the force survives the early return and the next plain trigger re-upserts once.
        authReady = true
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(2, api.calls.size, "the forced upsert is not lost until the 24h expiry")
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(2, api.calls.size)
    }

    @Test
    fun aSameBindingRefreshKeepsTheStoredDeliveryStatus() = runBlocking {
        val clock = TestClock()
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        val coordinator =
            deliveryStatusCoordinator(currentSession = { storedSession("user-1") }, api = api, clock = clock)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        assertTrue(coordinator.isRemotePushActiveFor("server-1", now = clock.now))
        val stored = coordinator.storedDeliveryStatus()
        val posts = { api.calls.count { it.startsWith("POST ") } }
        assertEquals(1, posts())
        // Later status requests fail, so only the coordinator itself could drop the stored answer.
        api.deliveryStatusResponse = HomeNetworkResponse(code = 503, bodyText = null)

        // New session family: same token, user and environment, re-upserted.
        assertTrue(coordinator.handleSessionSaved(forceUpsert = true))
        assertEquals(2, posts())
        assertEquals(stored, coordinator.storedDeliveryStatus(), "FCM still delivers; the answer stays")
        assertTrue(coordinator.isRemotePushActiveFor("server-1", now = clock.now), "no local double alert")

        // Confirmation expiry: same binding, re-upserted after 24h.
        clock.now += PUSH_REGISTRATION_REFRESH_INTERVAL_MILLIS
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(3, posts())
        assertEquals(stored, coordinator.storedDeliveryStatus())

        // A new token is a different registration: its answer is cleared.
        assertTrue(coordinator.registerFcm(token = "dY0-BcD_eFgHiJkLmNoPqR"))
        assertEquals(4, posts())
        assertEquals(null, coordinator.storedDeliveryStatus())
    }

    @Test
    fun anEnvironmentChangeRebindsTheRegistration() = runBlocking {
        val clock = TestClock()
        var environment = "https://api.example/"
        val api = RecordingPushRegistrationApi()
        val coordinator =
            deliveryStatusCoordinator(
                currentSession = { storedSession("user-1") },
                api = api,
                clock = clock,
                environmentId = { environment }
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        val posts = { api.calls.count { it.startsWith("POST ") } }
        assertEquals(1, posts())

        environment = "https://api.other.example"
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(2, posts())
    }

    @Test
    fun theServerReportingNoRegistrationReupsertsOncePerProcess() = runBlocking {
        val clock = TestClock()
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody(fcmRegistered = false, "server-1" to false)
        val coordinator =
            deliveryStatusCoordinator(currentSession = { storedSession("user-1") }, api = api, clock = clock)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val posts = { api.calls.count { it.startsWith("POST ") } }

        // Upsert -> status says not registered -> one repair upsert -> status still says not registered.
        assertTrue(coordinator.registerFcm())
        assertEquals(2, posts())
        assertEquals(null, coordinator.storedDeliveryStatus())
        assertFalse(coordinator.isRemotePushActiveFor("server-1", now = clock.now))

        // No loop: later answers only log.
        clock.advanceBeyondRequestSpacing()
        assertFalse(coordinator.refreshDeliveryStatus(reason = "app_foreground"))
        assertTrue(coordinator.handleSessionSaved())
        assertEquals(2, posts())

        // Once the server has the registration again, its answer is stored as before.
        api.deliveryStatusResponse = deliveryStatusBody(fcmRegistered = true, "server-1" to true)
        clock.advanceBeyondRequestSpacing()
        assertTrue(coordinator.refreshDeliveryStatus(reason = "app_foreground"))
        assertTrue(coordinator.isRemotePushActiveFor("server-1", now = clock.now))
    }

    @Test
    fun aServerWithoutTheRegisteredFieldKeepsTheCurrentBehaviour() = runBlocking {
        val clock = TestClock()
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to false)
        val coordinator =
            deliveryStatusCoordinator(currentSession = { storedSession("user-1") }, api = api, clock = clock)
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertTrue(coordinator.registerFcm())
        assertEquals(1, api.calls.count { it.startsWith("POST ") }, "fcmActive=false alone may be a switched-off rollout")
        assertEquals("server-1", coordinator.storedDeliveryStatus()?.fcmActiveByServer?.keys?.single())
    }

    @Test
    fun aDeviceWithoutFcmNeverRegistersAndKeepsLocalNotifications() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val clock = TestClock()
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        // An earlier process registered the Firebase Installation ID and stored a delivery answer.
        val earlier =
            deliveryStatusCoordinator(store = store, currentSession = { storedSession("user-1") }, api = api, clock = clock)
        earlier.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(earlier.registerFcm())
        assertTrue(earlier.isRemotePushActiveFor("server-1", now = clock.now))
        api.calls.clear()
        api.deliveryStatusRequests = 0

        // This process finds no Google Play services before its startup reconcile.
        clock.now += PUSH_REGISTRATION_REFRESH_INTERVAL_MILLIS
        val current =
            deliveryStatusCoordinator(store = store, currentSession = { storedSession("user-1") }, api = api, clock = clock)
        current.reportProviderUnavailable("no_play_services")
        assertFalse(current.handleSessionSaved(forceUpsert = true))
        current.markProviderUnavailable("no_play_services")

        assertFalse(current.registerFcm())
        assertFalse(current.retryPendingRegistration(reason = "host_retry"))
        assertFalse(current.refreshDeliveryStatus(reason = "app_foreground", force = true))
        assertTrue(api.calls.isEmpty(), "no upsert of an undeliverable token, no delivery-status request")
        assertEquals(null, current.storedDeliveryStatus())
        assertFalse(current.isRemotePushActiveFor("server-1", now = clock.now), "the realtime path posts locally")
    }

    @Test
    fun authenticatedEventsReachTheSinkAsAuthenticated() = runBlocking {
        val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 4)
        val received = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        RaftPushRegistrationLifecycle.install(
            object : RaftPushRegistrationLifecycleSink {
                override fun onSessionSaved() {
                    received += "saved"
                }

                override fun onSessionAuthenticated() {
                    received += "authenticated"
                    done.complete(Unit)
                }

                override fun onSessionInvalidated(reason: AuthSessionClearReason) = Unit

                override suspend fun unbindBeforeLogout() = Unit
            }
        )
        RaftPushRegistrationLifecycle.observeSessionEvents(
            source = events,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        events.emit(AuthSessionEvent.TokensRotated(generation = 1))
        events.emit(AuthSessionEvent.Authenticated(generation = 2))
        withTimeout(1_000) { done.await() }

        assertEquals(listOf("saved", "saved", "authenticated"), received)
    }

    @Test
    fun registrationBodyCarriesTheDeviceStateAndNoPreviewMode() = runBlocking {
        // slock docs/contracts/notifications.md §3.1: alertsEnabled + osPermission; previewMode is
        // account-wide now (me/notification-settings) and new clients omit it.
        var state = RaftPushDeviceState(alertsEnabled = false, osPermission = "denied")
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                spec = RaftPushProviderSpec.Fcm,
                currentSession = { storedSession(userId = "user-1") },
                api = api,
                deviceStateProvider = { state }
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertTrue(coordinator.registerFcm())

        assertEquals(
            """POST {"installationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","provider":"fcm","platform":"android","deviceToken":"cX9-AbC_dEfGhIjKlMnOpQ","topic":"build.raft.app","env":"production","appVersion":"1.3.0","alertsEnabled":false,"osPermission":"denied","previewKeySet":2}""",
            api.calls.single()
        )
        state = RaftPushDeviceState(alertsEnabled = true, osPermission = "granted")
        assertTrue(coordinator.handleDeviceStateChanged())
        assertTrue(api.calls.last().endsWith(""","alertsEnabled":true,"osPermission":"granted","previewKeySet":2}"""), api.calls.last())
    }

    @Test
    fun anUnknownPermissionReadsUnknownAndAFailedReadOmitsTheDeviceState() = runBlocking {
        val cases =
            listOf<Pair<() -> RaftPushDeviceState?, String>>(
                { RaftPushDeviceState(alertsEnabled = true, osPermission = "maybe") } to ""","alertsEnabled":true,"osPermission":"unknown","previewKeySet":2}""",
                { null } to ""","appVersion":"1.3.0","previewKeySet":2}""",
                { error("store unavailable") } to ""","appVersion":"1.3.0","previewKeySet":2}"""
            )
        for ((provider, expectedEnd) in cases) {
            val api = RecordingPushRegistrationApi()
            val coordinator =
                coordinator(
                    spec = RaftPushProviderSpec.Fcm,
                    currentSession = { storedSession(userId = "user-1") },
                    api = api,
                    deviceStateProvider = provider
                )
            coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
            assertTrue(coordinator.registerFcm())
            assertTrue(api.calls.single().endsWith(expectedEnd), api.calls.single())
            assertFalse(api.calls.single().contains("previewMode"), api.calls.single())
        }
    }

    @Test
    fun turningAlertsOffReRegistersAndKeepsTheDeliveryStatus() = runBlocking {
        val clock = TestClock()
        var alertsEnabled = true
        val api = RecordingPushRegistrationApi()
        api.deliveryStatusResponse = deliveryStatusBody("server-1" to true)
        val coordinator =
            deliveryStatusCoordinator(
                currentSession = { storedSession("user-1") },
                api = api,
                clock = clock,
                deviceStateProvider = { RaftPushDeviceState(alertsEnabled = alertsEnabled, osPermission = "granted") }
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        val posts = { api.calls.filter { it.startsWith("POST ") } }
        assertEquals(1, posts().size)
        assertTrue(posts().last().contains(""""alertsEnabled":true"""))
        val stored = coordinator.storedDeliveryStatus()
        api.deliveryStatusResponse = HomeNetworkResponse(code = 503, bodyText = null)

        alertsEnabled = false
        assertTrue(coordinator.handleDeviceStateChanged())

        assertEquals(2, posts().size, "the change is sent now, not at the next 24h refresh")
        assertTrue(posts().last().contains(""""alertsEnabled":false"""), posts().last())
        assertEquals(stored, coordinator.storedDeliveryStatus(), "a device-state-only change keeps the token binding")

        // Nothing changed since: the dedupe holds.
        assertTrue(coordinator.handleDeviceStateChanged())
        assertEquals(2, posts().size)
    }

    @Test
    fun aFailedReUpsertAfterADeviceStateChangeIsRetriedWithBackoff() = runBlocking {
        var alertsEnabled = true
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                spec = RaftPushProviderSpec.Fcm,
                currentSession = { storedSession(userId = "user-1") },
                api = api,
                deviceStateProvider = { RaftPushDeviceState(alertsEnabled = alertsEnabled, osPermission = "granted") }
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        alertsEnabled = false
        var failuresLeft = 2
        api.upsertResponseFor = { payload ->
            if (failuresLeft-- > 0) HomeNetworkResponse(code = 503, bodyText = null) else confirmedUpsert(payload)
        }

        assertTrue(coordinator.syncDeviceStateWithRetry())

        val posts = api.calls.filter { it.startsWith("POST ") }
        assertEquals(4, posts.size, "initial + two failed + the successful retry")
        assertTrue(posts.drop(1).all { it.contains(""""alertsEnabled":false""") })
    }

    @Test
    fun retriesStopWhenTheServerKeepsFailing() = runBlocking {
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                spec = RaftPushProviderSpec.Fcm,
                currentSession = { storedSession(userId = "user-1") },
                api = api,
                deviceStateProvider = { RaftPushDeviceState(alertsEnabled = false, osPermission = "granted") }
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        api.upsertResponse = HomeNetworkResponse(code = 503, bodyText = null)
        assertFalse(coordinator.registerFcm())

        assertFalse(coordinator.syncDeviceStateWithRetry())

        assertEquals(5, api.calls.count { it.startsWith("POST ") }, "1 + first try + 3 bounded retries")
    }

    @Test
    fun aDeviceStateChangeWithNothingToSendDoesNotRetry() = runBlocking {
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                spec = RaftPushProviderSpec.Fcm,
                currentSession = { storedSession(userId = "user-1") },
                api = api
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

        assertFalse(coordinator.syncDeviceStateWithRetry())

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun aPendingPayloadFromAnOlderBuildDropsItsPreviewModeOnTheNextFlush() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        store.putString(
            "pendingRegistration",
            """{"installationId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","provider":"fcm","platform":"android","deviceToken":"cX9-AbC_dEfGhIjKlMnOpQ","topic":"build.raft.app","env":"production","appVersion":"1.2.0","previewMode":"hidden"}"""
        )
        store.putString("installationId", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                store = store,
                spec = RaftPushProviderSpec.Fcm,
                currentSession = { storedSession(userId = "user-1") },
                api = api,
                deviceStateProvider = { RaftPushDeviceState(alertsEnabled = true, osPermission = "granted") }
            )

        assertTrue(coordinator.retryPendingRegistration(reason = "startup"))

        assertTrue(
            api.calls.single().endsWith(""","appVersion":"1.2.0","alertsEnabled":true,"osPermission":"granted","previewKeySet":2}"""),
            api.calls.single()
        )
    }

    @Test
    fun aGrantedPermissionReSendsTheRegistrationWithGranted() = runBlocking {
        // Review B1: the permission answer must reach the server at once, not at the next start.
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                spec = RaftPushProviderSpec.Fcm,
                currentSession = { storedSession(userId = "user-1") },
                api = api,
                deviceStateProvider = { RaftPushDeviceState(alertsEnabled = true, osPermission = RaftPushDeviceStateHost.osPermission) }
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        var changes = 0
        RaftPushRegistrationLifecycle.install(
            object : RaftPushRegistrationLifecycleSink {
                override fun onSessionSaved() = Unit

                override fun onSessionInvalidated(reason: AuthSessionClearReason) = Unit

                override suspend fun unbindBeforeLogout() = Unit

                override fun onDeviceStateChanged() {
                    changes += 1
                }
            }
        )
        RaftPushDeviceStateHost.updateOsPermission("unknown")
        assertTrue(coordinator.registerFcm())
        assertTrue(api.calls.last().contains(""""osPermission":"unknown""""), api.calls.last())
        changes = 0

        RaftPushDeviceStateHost.updateOsPermission("granted")
        RaftPushDeviceStateHost.updateOsPermission("granted")
        assertEquals(1, changes, "one re-registration per change")
        assertTrue(coordinator.handleDeviceStateChanged())
        assertTrue(api.calls.last().contains(""""osPermission":"granted""""), api.calls.last())
        RaftPushRegistrationLifecycle.clearForTests()
    }

    @Test
    fun anOlderServerGetsThePreviewChoiceInTheRegistration() = runBlocking {
        // Review S1: before the unified API is known, the registration carries previewMode as before.
        val api = RecordingPushRegistrationApi()
        val coordinator =
            coordinator(
                spec = RaftPushProviderSpec.Fcm,
                currentSession = { storedSession(userId = "user-1") },
                api = api,
                deviceStateProvider = {
                    RaftPushDeviceState(alertsEnabled = true, osPermission = "granted", legacyPreviewMode = "private")
                }
            )
        coordinator.installationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(coordinator.registerFcm())
        // An unreadable choice fails closed, as the device store does.
        assertTrue(
            api.calls.single().endsWith(""","previewMode":"hidden","alertsEnabled":true,"osPermission":"granted","previewKeySet":2}"""),
            api.calls.single()
        )
    }

    @Test
    fun deviceStateChangesReachTheInstalledSink() {
        var changes = 0
        RaftPushRegistrationLifecycle.install(
            object : RaftPushRegistrationLifecycleSink {
                override fun onSessionSaved() = Unit

                override fun onSessionInvalidated(reason: AuthSessionClearReason) = Unit

                override suspend fun unbindBeforeLogout() = Unit

                override fun onDeviceStateChanged() {
                    changes += 1
                }
            }
        )

        RaftPushRegistrationLifecycle.onDeviceStateChanged()

        assertEquals(1, changes)
    }

    private fun coordinator(
        store: RaftKeyValueStore = InMemoryRaftKeyValueStore(),
        spec: RaftPushProviderSpec = RaftPushProviderSpec.Apns,
        currentSession: suspend () -> StoredAuthSession? = { null },
        api: RaftPushRegistrationApi = RecordingPushRegistrationApi(),
        remoteNotificationsController: RaftIosRemoteNotificationsController =
            RecordingRemoteNotificationsController(),
        currentEnvironmentId: (() -> String?)? = null,
        clockMillis: () -> Long = { 0L },
        scope: CoroutineScope = CoroutineScope(SupervisorJob()),
        deviceStateProvider: () -> RaftPushDeviceState? = { null },
        deviceStateRetryDelaysMillis: List<Long> = listOf(0L, 0L, 0L)
    ): RaftPushRegistrationCoordinator =
        RaftPushRegistrationCoordinator(
            store = store,
            spec = spec,
            currentSession = currentSession,
            apiProvider = { api },
            scope = scope,
            remoteNotificationsController = remoteNotificationsController,
            currentEnvironmentId = currentEnvironmentId,
            clockMillis = clockMillis,
            deviceStateProvider = deviceStateProvider,
            deviceStateRetryDelaysMillis = deviceStateRetryDelaysMillis
        )

    private class TestClock(var now: Long = 1_700_000_000_000L) {
        fun advanceBeyondRequestSpacing() {
            now += PUSH_DELIVERY_STATUS_MIN_REQUEST_INTERVAL_MILLIS
        }
    }

    /** Unconfined: a refresh launched by the coordinator has finished when the triggering call returns. */
    private fun deliveryStatusCoordinator(
        store: RaftKeyValueStore = InMemoryRaftKeyValueStore(),
        currentSession: suspend () -> StoredAuthSession?,
        api: RaftPushRegistrationApi,
        clock: TestClock,
        environmentId: () -> String? = { "https://api.example/" },
        deviceStateProvider: () -> RaftPushDeviceState? = { null }
    ): RaftPushRegistrationCoordinator =
        coordinator(
            store = store,
            spec = RaftPushProviderSpec.Fcm,
            currentSession = currentSession,
            api = api,
            currentEnvironmentId = environmentId,
            clockMillis = { clock.now },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            deviceStateProvider = deviceStateProvider
        )

    private suspend fun RaftPushRegistrationCoordinator.registerFcm(token: String = "cX9-AbC_dEfGhIjKlMnOpQ"): Boolean =
        updateRegistration(token, "build.raft.app", "production", "1.3.0")

    /** What the Android background path decides for a realtime event of [serverId]. */
    private fun RaftPushRegistrationCoordinator.isRemotePushActiveFor(
        serverId: String,
        userId: String = "user-1",
        environmentId: String = "https://api.example/",
        now: Long
    ): Boolean =
        RaftRealtimeAppScopePolicy.isRemotePushActive(
            serverId = ServerId(serverId),
            environmentId = environmentId,
            currentUserId = userId,
            installationId = currentInstallationId(),
            deliveryStatus = storedDeliveryStatus(),
            nowMillis = now
        )

    private fun deliveryStatusBody(vararg servers: Pair<String, Boolean>): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                servers.joinToString(prefix = """{"servers":{""", postfix = "}}") { (id, active) ->
                    """"$id":{"fcmActive":$active}"""
                }
        )

    private fun deliveryStatusBody(fcmRegistered: Boolean, vararg servers: Pair<String, Boolean>): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                servers.joinToString(
                    prefix = """{"fcmRegistered":$fcmRegistered,"servers":{""",
                    postfix = "}}"
                ) { (id, active) -> """"$id":{"fcmActive":$active}""" }
        )

    private fun storedSession(userId: String, serverId: String = "server-1"): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@example.com"),
            accessToken = "access-$userId",
            refreshToken = "refresh-$userId",
            selectedServerId = serverId
        )

    private class RecordingPushRegistrationApi : RaftPushRegistrationApi {
        val calls = mutableListOf<String>()

        /** Null answers like the server: 200 echoing the stored registration. */
        var upsertResponse: HomeNetworkResponse? = null
        var upsertResponseFor: ((String) -> HomeNetworkResponse)? = null

        override suspend fun upsert(payloadJson: String): HomeNetworkResponse {
            calls += "POST $payloadJson"
            upsertResponseFor?.let { return it(payloadJson) }
            return upsertResponse ?: confirmedUpsert(payloadJson)
        }

        override suspend fun unbind(installationId: String): HomeNetworkResponse {
            calls += "DELETE $installationId"
            return HomeNetworkResponse(code = 204, bodyText = null)
        }

        var deliveryStatusResponse: HomeNetworkResponse = HomeNetworkResponse(code = 404, bodyText = null)
        var onDeliveryStatus: (() -> Unit)? = null
        var deliveryStatusRequests = 0

        override suspend fun deliveryStatus(installationId: String): HomeNetworkResponse {
            calls += "GET delivery-status $installationId"
            deliveryStatusRequests += 1
            onDeliveryStatus?.invoke()
            return deliveryStatusResponse
        }
    }

    private class RecordingRemoteNotificationsController : RaftIosRemoteNotificationsController {
        val states = mutableListOf<Boolean>()
        var onDisabled: (() -> Unit)? = null

        override suspend fun setEnabled(enabled: Boolean) {
            states += enabled
            if (!enabled) onDisabled?.invoke()
        }
    }

    private class RecordingNetworkClient : RaftNetworkClient {
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

/** The server's 200 for an upsert: the registration it stored, echoed. */
private fun confirmedUpsert(payloadJson: String): HomeNetworkResponse {
    val installationId = Regex("\"installationId\":\"([^\"]*)\"").find(payloadJson)?.groupValues?.get(1)
    val provider = Regex("\"provider\":\"([^\"]*)\"").find(payloadJson)?.groupValues?.get(1)
    return HomeNetworkResponse(
        code = 200,
        bodyText = """{"ok":true,"registration":{"installationId":"$installationId","provider":"$provider"}}"""
    )
}
