package build.raft.app.data

import build.raft.app.buildThreadLaunchConversationIdentity
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KuiklyChatLayoutPreferencesTest {
    @Test
    fun appScopedTransitionsReconcileEveryLiveShellAndNeverResurrectHistory() = runBlocking {
        val events = KuiklyChatLayoutPreferenceEvents(SlockEventBuses.inProcess())
        val firstHistory = SharedConversationForwardHistory()
        val secondHistory = SharedConversationForwardHistory()
        var firstNativeDisableCount = 0
        var secondNativeDisableCount = 0
        val firstSubscription =
            events.addConversationForwardGestureListener { enabled ->
                reconcileConversationForwardGesturePreference(
                    enabled = enabled,
                    history = firstHistory,
                    disableNativeGesture = { firstNativeDisableCount += 1 }
                )
            }
        val secondSubscription =
            events.addConversationForwardGestureListener { enabled ->
                reconcileConversationForwardGesturePreference(
                    enabled = enabled,
                    history = secondHistory,
                    disableNativeGesture = { secondNativeDisableCount += 1 }
                )
            }
        val persistentRepository =
            PersistentChatLayoutPreferenceRepository(
                KeyValueChatLayoutPreferenceStore(InMemorySlockKeyValueStore())
            )
        val repository = kuiklyChatLayoutPreferenceRepository(persistentRepository, events)

        try {
            repository.setConversationForwardGestureEnabled(true)

            assertTrue(firstHistory.enabledForTest())
            assertTrue(secondHistory.enabledForTest())
            assertFalse(firstHistory.canForward)
            assertFalse(secondHistory.canForward)

            firstHistory.recordNewOpen(entry("server-a", "channel-a"))
            secondHistory.recordNewOpen(entry("server-a", "channel-b"))
            assertTrue(firstHistory.recordHomeVisible())
            assertTrue(secondHistory.recordHomeVisible())
            assertTrue(firstHistory.canForward)
            assertTrue(secondHistory.canForward)

            repository.setConversationForwardGestureEnabled(false)

            assertFalse(firstHistory.enabledForTest())
            assertFalse(secondHistory.enabledForTest())
            assertFalse(firstHistory.canForward)
            assertFalse(secondHistory.canForward)
            assertNull(firstHistory.forwardEntryForTest())
            assertNull(secondHistory.forwardEntryForTest())
            assertEquals(1, firstNativeDisableCount)
            assertEquals(1, secondNativeDisableCount)

            repository.setConversationForwardGestureEnabled(true)

            assertTrue(firstHistory.enabledForTest())
            assertTrue(secondHistory.enabledForTest())
            assertFalse(firstHistory.canForward)
            assertFalse(secondHistory.canForward)
            assertNull(firstHistory.forwardEntryForTest())
            assertNull(secondHistory.forwardEntryForTest())
            assertEquals(1, firstNativeDisableCount)
            assertEquals(1, secondNativeDisableCount)
        } finally {
            firstSubscription.close()
            secondSubscription.close()
        }
    }

    private fun entry(
        serverId: String,
        channelId: String
    ): ConversationForwardEntry =
        buildThreadLaunchConversationIdentity(
            kind = ConversationKind.Channel,
            channelId = ChannelId(channelId)
        ).toConversationForwardEntry(serverId)
}
