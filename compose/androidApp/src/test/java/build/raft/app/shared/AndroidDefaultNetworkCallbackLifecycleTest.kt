package build.raft.app.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hands a8cd6426 (Samsung, Android 17): a cached Raft was killed for "excessive binder traffic during
 * cached" (its freeze kept failing on pending binder transactions). The default-network callback is
 * the one system callback stream that outlived the UI; it is now registered only while it is needed.
 */
class AndroidDefaultNetworkCallbackLifecycleTest {
    @Test
    fun registeredInFrontOrWithABackgroundConnectionOnly() {
        fun wanted(foreground: Boolean, keepAlive: Boolean, service: Boolean) =
            AndroidDefaultNetworkCallbackPolicy.shouldBeRegistered(foreground, keepAlive, service)

        assertTrue(wanted(foreground = true, keepAlive = false, service = false))
        assertFalse("hidden, no background connection", wanted(foreground = false, keepAlive = false, service = false))
        assertTrue("hidden with keep-alive", wanted(foreground = false, keepAlive = true, service = false))
        assertTrue("hidden while the service runs or starts", wanted(foreground = false, keepAlive = false, service = true))
    }

    private class FakeConnectivity {
        val calls = mutableListOf<String>()
        var failRegister = false
        var failUnregister = false
        fun registration() =
            AndroidDefaultNetworkCallbackRegistration(
                register = {
                    if (failRegister) throw IllegalStateException("too many requests")
                    calls += "register"
                },
                unregister = {
                    if (failUnregister) throw IllegalArgumentException("not registered")
                    calls += "unregister"
                },
            )
    }

    @Test
    fun hiddenUnregistersAndShownRegistersAgainOnce() {
        val connectivity = FakeConnectivity()
        val registration = connectivity.registration()

        assertEquals(AndroidNetworkCallbackTransition.Registered, registration.sync(wanted = true))
        assertEquals(AndroidNetworkCallbackTransition.Unchanged, registration.sync(wanted = true))
        assertEquals(AndroidNetworkCallbackTransition.Unregistered, registration.sync(wanted = false))
        assertEquals(AndroidNetworkCallbackTransition.Unchanged, registration.sync(wanted = false))
        assertEquals(AndroidNetworkCallbackTransition.Registered, registration.sync(wanted = true))

        assertEquals(listOf("register", "unregister", "register"), connectivity.calls)
        assertTrue(registration.registered)
    }

    @Test
    fun aFailedRegisterIsTriedAgainOnTheNextSync() {
        val connectivity = FakeConnectivity()
        val registration = connectivity.registration()
        connectivity.failRegister = true

        assertEquals(AndroidNetworkCallbackTransition.RegisterFailed, registration.sync(wanted = true))
        assertFalse(registration.registered)

        connectivity.failRegister = false
        assertEquals(AndroidNetworkCallbackTransition.Registered, registration.sync(wanted = true))
    }

    @Test
    fun aFailedUnregisterStillCountsAsGoneSoTheNextShowRegisters() {
        val connectivity = FakeConnectivity()
        val registration = connectivity.registration()
        registration.sync(wanted = true)
        connectivity.failUnregister = true

        assertEquals(AndroidNetworkCallbackTransition.UnregisterFailed, registration.sync(wanted = false))
        assertFalse(registration.registered)
        assertEquals(AndroidNetworkCallbackTransition.Registered, registration.sync(wanted = true))
    }

    @Test
    fun hiddenCallbacksAreSummarizedOncePerMinute() {
        val counter = AndroidHiddenNetworkCallbackCounter(windowMillis = 60_000L)

        assertNull("foreground callbacks are not counted", counter.record(AndroidNetworkCallbackKind.Capabilities, hidden = false, nowMillis = 0L))
        assertNull(counter.record(AndroidNetworkCallbackKind.Capabilities, hidden = true, nowMillis = 1_000L))
        assertNull(counter.record(AndroidNetworkCallbackKind.Capabilities, hidden = true, nowMillis = 2_000L))
        assertNull(counter.record(AndroidNetworkCallbackKind.Lost, hidden = true, nowMillis = 30_000L))
        assertNull(counter.record(AndroidNetworkCallbackKind.Available, hidden = true, nowMillis = 60_999L))

        val summary = counter.record(AndroidNetworkCallbackKind.LinkProperties, hidden = true, nowMillis = 61_000L)
        assertEquals(
            "app_scope_network_callback_hidden_summary windowMs=60000 total=4 " +
                "available=1 capabilities=2 link_properties=0 lost=1",
            summary,
        )

        // The callback that closed the window opens the next one.
        assertEquals(
            "app_scope_network_callback_hidden_summary windowMs=4000 total=1 " +
                "available=0 capabilities=0 link_properties=1 lost=0",
            counter.flush(65_000L),
        )
        assertNull("nothing left to report", counter.flush(70_000L))
    }

    @Test
    fun asyncCoordinatorRunsForegroundAndBackgroundOffCallerThreadInOrder() = runTest {
        val ioScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val events = mutableListOf<String>()
            val statuses = mutableListOf<Pair<Boolean, String>>()
            var foreground = false
            val registration =
                AndroidDefaultNetworkCallbackRegistration(
                    register = { events += "register" },
                    unregister = { events += "unregister" },
                )
            val coordinator =
                AndroidAsyncNetworkCallbackCoordinator(
                    ioScope = ioScope,
                    registration = registration,
                    isWanted = { foreground },
                    queryOnline = {
                        events += "query"
                        true
                    },
                    onOnlineStatus = { online, source -> statuses += online to source },
                    onAppBackgrounded = { events += "backgrounded" },
                    onTransition = { transition, source -> events += "transition:${transition.name}:$source" },
                )

            foreground = true
            coordinator.onForeground("app_shown")
            assertTrue("foreground sync must not run synchronously on caller thread", events.isEmpty())

            testScheduler.advanceUntilIdle()
            assertEquals(listOf("register", "transition:Registered:app_shown", "query"), events)
            assertEquals(listOf(true to "app_shown"), statuses)
            assertTrue(registration.registered)

            events.clear()
            foreground = false
            coordinator.onBackground("app_hidden")
            assertTrue("background sync must not run synchronously on caller thread", events.isEmpty())

            testScheduler.advanceUntilIdle()
            assertEquals(listOf("backgrounded", "unregister", "transition:Unregistered:app_hidden"), events)
            assertFalse(registration.registered)
        } finally {
            ioScope.cancel()
        }
    }

    @Test
    fun rapidForegroundThenBackgroundSkipsStaleRegisterAndQueryAndLeaksNoCallback() = runTest {
        val ioScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val events = mutableListOf<String>()
            val statuses = mutableListOf<Pair<Boolean, String>>()
            var foreground = false
            val registration =
                AndroidDefaultNetworkCallbackRegistration(
                    register = { events += "register" },
                    unregister = { events += "unregister" },
                )
            val coordinator =
                AndroidAsyncNetworkCallbackCoordinator(
                    ioScope = ioScope,
                    registration = registration,
                    isWanted = { foreground },
                    queryOnline = {
                        events += "query"
                        true
                    },
                    onOnlineStatus = { online, source -> statuses += online to source },
                    onAppBackgrounded = { events += "backgrounded" },
                    onTransition = { _, _ -> },
                )

            foreground = true
            coordinator.onForeground("app_shown")
            foreground = false
            coordinator.onBackground("app_hidden")

            testScheduler.advanceUntilIdle()
            assertFalse(registration.registered)
            assertEquals(listOf("backgrounded"), events)
            assertTrue(statuses.isEmpty())
        } finally {
            ioScope.cancel()
        }
    }

    @Test
    fun backgroundWhileRegisterInFlightStillUnregistersAndNeverLeaks() = runTest {
        val ioScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val events = mutableListOf<String>()
            val statuses = mutableListOf<Pair<Boolean, String>>()
            var foreground = false
            lateinit var coordinator: AndroidAsyncNetworkCallbackCoordinator
            val registration =
                AndroidDefaultNetworkCallbackRegistration(
                    register = {
                        events += "register"
                        // Simulate Main thread transitioning to background while register is in flight.
                        foreground = false
                        coordinator.onBackground("app_hidden")
                    },
                    unregister = { events += "unregister" },
                )
            coordinator =
                AndroidAsyncNetworkCallbackCoordinator(
                    ioScope = ioScope,
                    registration = registration,
                    isWanted = { foreground },
                    queryOnline = {
                        events += "query"
                        true
                    },
                    onOnlineStatus = { online, source -> statuses += online to source },
                    onAppBackgrounded = { events += "backgrounded" },
                    onTransition = { _, _ -> },
                )

            foreground = true
            coordinator.onForeground("app_shown")
            testScheduler.advanceUntilIdle()

            assertFalse("callback registered before rapid background must be unregistered", registration.registered)
            assertEquals(listOf("register", "backgrounded", "unregister"), events)
            assertTrue("superseded foreground status query must be skipped", statuses.isEmpty())
        } finally {
            ioScope.cancel()
        }
    }

    @Test
    fun rapidBackgroundThenForegroundSkipsStaleBackgroundTransitionAndKeepsCallbackRegistered() = runTest {
        val ioScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val events = mutableListOf<String>()
            var foreground = true
            val registration =
                AndroidDefaultNetworkCallbackRegistration(
                    register = { events += "register" },
                    unregister = { events += "unregister" },
                )
            val coordinator =
                AndroidAsyncNetworkCallbackCoordinator(
                    ioScope = ioScope,
                    registration = registration,
                    isWanted = { foreground },
                    queryOnline = {
                        events += "query"
                        true
                    },
                    onOnlineStatus = { _, _ -> },
                    onAppBackgrounded = { events += "backgrounded" },
                    onTransition = { _, _ -> },
                )

            coordinator.onForeground("app_shown")
            testScheduler.advanceUntilIdle()
            events.clear()

            foreground = false
            coordinator.onBackground("app_hidden")
            foreground = true
            coordinator.onForeground("app_shown")

            testScheduler.advanceUntilIdle()
            assertTrue(registration.registered)
            assertEquals(listOf("query"), events)
        } finally {
            ioScope.cancel()
        }
    }

    @Test
    fun backgroundKeepAliveStartedDuringOnAppBackgroundedKeepsCallbackRegistered() = runTest {
        val ioScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val events = mutableListOf<String>()
            var foreground = true
            var serviceActive = false
            val registration =
                AndroidDefaultNetworkCallbackRegistration(
                    register = { events += "register" },
                    unregister = { events += "unregister" },
                )
            val coordinator =
                AndroidAsyncNetworkCallbackCoordinator(
                    ioScope = ioScope,
                    registration = registration,
                    isWanted = {
                        AndroidDefaultNetworkCallbackPolicy.shouldBeRegistered(
                            foreground = foreground,
                            backgroundKeepAliveEnabled = false,
                            backgroundConnectionServiceActive = serviceActive,
                        )
                    },
                    queryOnline = { true },
                    onOnlineStatus = { _, _ -> },
                    onAppBackgrounded = {
                        events += "backgrounded"
                        serviceActive = true
                    },
                    onTransition = { _, _ -> },
                )

            coordinator.onForeground("app_shown")
            testScheduler.advanceUntilIdle()
            events.clear()

            foreground = false
            coordinator.onBackground("app_hidden")
            testScheduler.advanceUntilIdle()

            assertTrue("keep-alive service started in onAppBackgrounded must keep callback registered", registration.registered)
            assertEquals(listOf("backgrounded"), events)
        } finally {
            ioScope.cancel()
        }
    }
}
