package build.raft.app.shared

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
}
