package ai.slock.android.core.data.preferences

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPreferenceRepositoryTest {
    @Test
    fun defaultsToDisabledWhenNoPreferenceExists() {
        val repository = PersistentNotificationPreferenceRepository(
            InMemoryNotificationPreferenceStore()
        )

        val snapshot = runSuspend { repository.getNotificationPreference() }

        assertEquals(false, snapshot.enabled)
    }

    @Test
    fun persistsSelectedNotificationState() {
        val store = InMemoryNotificationPreferenceStore()
        val repository = PersistentNotificationPreferenceRepository(store)

        val enabled = runSuspend { repository.setNotificationEnabled(true) }
        val restoredEnabled = runSuspend { repository.getNotificationPreference() }
        val disabled = runSuspend { repository.setNotificationEnabled(false) }
        val restoredDisabled = runSuspend { repository.getNotificationPreference() }

        assertEquals(true, enabled.enabled)
        assertEquals(true, restoredEnabled.enabled)
        assertEquals(false, disabled.enabled)
        assertEquals(false, restoredDisabled.enabled)
    }

    private class InMemoryNotificationPreferenceStore(
        private var enabled: Boolean = false
    ) : NotificationPreferenceStore {
        override fun readNotificationEnabledSync(): Boolean = enabled

        override suspend fun readNotificationEnabled(): Boolean = enabled

        override suspend fun saveNotificationEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.onSuccess { value = it }
                result.onFailure { failure = it }
            }
        }
    )
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
