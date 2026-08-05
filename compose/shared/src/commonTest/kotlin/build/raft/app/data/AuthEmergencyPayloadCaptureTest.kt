package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class AuthEmergencyPayloadCaptureTest {
    @Test
    fun captureRequiresExplicitEnableAndClearErasesTheSensitiveBody() = runBlocking {
        val store = AuthEmergencyPayloadCaptureStore(CoroutineScope(coroutineContext), retentionMillis = 100L)

        store.capture("https://api.raft.build/api/auth/login", "{\"password\":\"secret\"}")
        assertNull(store.state.value.snapshot)

        store.setEnabled(true)
        store.capture("https://api.raft.build/api/auth/login", "{\"password\":\"secret\"}")

        assertFalse(store.state.value.enabled)
        assertEquals("https://api.raft.build/api/auth/login", store.state.value.snapshot?.endpoint)
        assertEquals("{\"password\":\"secret\"}", store.state.value.snapshot?.bodyText)

        store.clear()
        assertFalse(store.state.value.enabled)
        assertNull(store.state.value.snapshot)
    }

    @Test
    fun capturedPayloadAutoExpiresAndRemainsDisarmed() = runBlocking {
        val store = AuthEmergencyPayloadCaptureStore(CoroutineScope(coroutineContext), retentionMillis = 80L)
        store.setEnabled(true)
        store.capture("first", "first-body")
        delay(100L)

        assertFalse(store.state.value.enabled)
        assertNull(store.state.value.snapshot)
    }
}
