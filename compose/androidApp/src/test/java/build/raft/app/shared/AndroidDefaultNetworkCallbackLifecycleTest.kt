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
}
