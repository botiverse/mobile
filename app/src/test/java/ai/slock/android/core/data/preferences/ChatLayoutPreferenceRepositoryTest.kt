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
        assertEquals(LargeScreenNavigationPreference.LeftRail, snapshot.largeScreenNavigation)
        assertTrue(snapshot.supportedAlignments.contains(MyMessageAlignmentPreference.Left))
        assertTrue(snapshot.supportedAlignments.contains(MyMessageAlignmentPreference.Right))
        assertTrue(
            snapshot.supportedLargeScreenNavigation.contains(
                LargeScreenNavigationPreference.LeftRail
            )
        )
        assertTrue(
            snapshot.supportedLargeScreenNavigation.contains(
                LargeScreenNavigationPreference.FloatingDock
            )
        )
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

    @Test
    fun persistsSelectedLargeScreenNavigation() {
        val store = InMemoryChatLayoutPreferenceStore()
        val repository = PersistentChatLayoutPreferenceRepository(store)

        val selected = runSuspend {
            repository.selectLargeScreenNavigation(LargeScreenNavigationPreference.FloatingDock)
        }
        val restored = runSuspend { repository.getChatLayoutPreference() }

        assertEquals(LargeScreenNavigationPreference.FloatingDock, selected.largeScreenNavigation)
        assertEquals(LargeScreenNavigationPreference.FloatingDock, restored.largeScreenNavigation)
    }

    private class InMemoryChatLayoutPreferenceStore(
        private var alignment: MyMessageAlignmentPreference = MyMessageAlignmentPreference.Default,
        private var largeScreenNavigation: LargeScreenNavigationPreference =
            LargeScreenNavigationPreference.Default
    ) : ChatLayoutPreferenceStore {
        override fun readMyMessageAlignmentSync(): MyMessageAlignmentPreference = alignment

        override suspend fun readMyMessageAlignment(): MyMessageAlignmentPreference = alignment

        override suspend fun saveMyMessageAlignment(alignment: MyMessageAlignmentPreference) {
            this.alignment = alignment
        }

        override fun readLargeScreenNavigationSync(): LargeScreenNavigationPreference =
            largeScreenNavigation

        override suspend fun readLargeScreenNavigation(): LargeScreenNavigationPreference =
            largeScreenNavigation

        override suspend fun saveLargeScreenNavigation(
            navigation: LargeScreenNavigationPreference
        ) {
            this.largeScreenNavigation = navigation
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
