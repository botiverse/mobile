package ai.slock.android.core.data.preferences

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLayoutPreferenceRepositoryTest {
    @Test
    fun defaultsToLeftAlignedLayoutWhenNoPreferenceExists() {
        val repository = PersistentChatLayoutPreferenceRepository(InMemoryChatLayoutPreferenceStore())

        val snapshot = runSuspend { repository.getChatLayoutPreference() }

        assertEquals(MyMessageAlignmentPreference.Left, snapshot.myMessageAlignment)
        assertTrue(snapshot.supportedAlignments.contains(MyMessageAlignmentPreference.Left))
        assertTrue(snapshot.supportedAlignments.contains(MyMessageAlignmentPreference.Right))
    }

    @Test
    fun persistsSelectedMyMessageAlignment() {
        val store = InMemoryChatLayoutPreferenceStore()
        val repository = PersistentChatLayoutPreferenceRepository(store)

        val selected = runSuspend {
            repository.selectMyMessageAlignment(MyMessageAlignmentPreference.Left)
        }
        val restored = runSuspend { repository.getChatLayoutPreference() }

        assertEquals(MyMessageAlignmentPreference.Left, selected.myMessageAlignment)
        assertEquals(MyMessageAlignmentPreference.Left, restored.myMessageAlignment)
    }

    private class InMemoryChatLayoutPreferenceStore(
        private var alignment: MyMessageAlignmentPreference = MyMessageAlignmentPreference.Default
    ) : ChatLayoutPreferenceStore {
        override fun readMyMessageAlignmentSync(): MyMessageAlignmentPreference = alignment

        override suspend fun readMyMessageAlignment(): MyMessageAlignmentPreference = alignment

        override suspend fun saveMyMessageAlignment(alignment: MyMessageAlignmentPreference) {
            this.alignment = alignment
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
