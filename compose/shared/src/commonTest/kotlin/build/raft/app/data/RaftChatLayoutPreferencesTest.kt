package build.raft.app.data

import build.raft.app.buildThreadLaunchConversationIdentity
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RaftChatLayoutPreferencesTest {
    @Test
    fun partialSnapshotEventFailsClosedInsteadOfResettingUnspecifiedPreferences() {
        val partial =
            buildJsonObject {
                put("myMessageAlignment", "right")
                put("conversationForwardGestureEnabled", true)
            }

        assertNull(partial.toChatLayoutPreferenceSnapshotOrNull())
    }

    @Test
    fun everyPersistentWritePublishesTheFullSnapshotToEveryLiveShell() = runBlocking {
        val events = RaftChatLayoutPreferenceEvents(RaftEventBuses.inProcess())
        val firstSnapshots = mutableListOf<ChatLayoutPreferenceSnapshot>()
        val secondSnapshots = mutableListOf<ChatLayoutPreferenceSnapshot>()
        val firstSubscription = events.addPreferenceListener(firstSnapshots::add)
        val secondSubscription = events.addPreferenceListener(secondSnapshots::add)
        val repository =
            raftChatLayoutPreferenceRepository(
                PersistentChatLayoutPreferenceRepository(
                    KeyValueChatLayoutPreferenceStore(InMemoryRaftKeyValueStore())
                ),
                events,
            )

        try {
            repository.selectMyMessageAlignment(MyMessageAlignmentPreference.Right)
            repository.setDesktopModeOnLargeScreen(false)
            repository.setCompactConsecutiveMessages(false)
            repository.setLongMessageCollapseEnabled(true)
            repository.setNewMessageMenuEnabled(true)
            repository.setConversationForwardGestureEnabled(true)
            repository.setForegroundMessageBannersEnabled(true)
            repository.setActivityTaskStatusBadgesEnabled(true)
            repository.setActivitySenderAvatarsEnabled(true)
            repository.setComposerSendKeyEnabled(true)
            repository.setMultiShellHostEnabled(true)
            repository.setServerDrawerEnabled(true)
            repository.setNewDesignSettingsEnabled(true)
            repository.setHomeServerAvatarEnabled(true)
            repository.setTranslationMode(TranslationModePreference.Automatic)
            repository.setTranslationTargetLanguage("ja")

            assertEquals(16, firstSnapshots.size)
            assertEquals(firstSnapshots, secondSnapshots)
            firstSnapshots.last().let { snapshot ->
                assertEquals(MyMessageAlignmentPreference.Right, snapshot.myMessageAlignment)
                assertFalse(snapshot.desktopModeOnLargeScreen)
                assertFalse(snapshot.compactConsecutiveMessages)
                assertTrue(snapshot.longMessageCollapseEnabled)
                assertTrue(snapshot.newMessageMenuEnabled)
                assertTrue(snapshot.conversationForwardGestureEnabled)
                assertTrue(snapshot.foregroundMessageBannersEnabled)
                assertTrue(snapshot.activityTaskStatusBadgesEnabled)
                assertTrue(snapshot.activitySenderAvatarsEnabled)
                assertTrue(snapshot.composerSendKeyEnabled)
                assertTrue(snapshot.multiShellHostEnabled)
                assertTrue(snapshot.serverDrawerEnabled)
                assertTrue(snapshot.newDesignSettingsEnabled)
                assertTrue(snapshot.homeServerAvatarEnabled)
                assertEquals(TranslationModePreference.Automatic, snapshot.translationMode)
                assertEquals("ja", snapshot.translationTargetLanguage)
            }
        } finally {
            firstSubscription.close()
            secondSubscription.close()
        }
    }

    @Test
    fun fullSnapshotProcessEventRemainsAuthoritativeWhenRepositorySeedCompletesLate() {
        val applied = mutableListOf<ChatLayoutPreferenceSnapshot>()
        val authority = ChatLayoutPreferenceSnapshotRuntimeAuthority(applied::add)
        val processSnapshot =
            ChatLayoutPreferenceSnapshot(
                myMessageAlignment = MyMessageAlignmentPreference.Right,
                compactConsecutiveMessages = false,
                foregroundMessageBannersEnabled = true,
            )
        val staleSeed =
            ChatLayoutPreferenceSnapshot(
                myMessageAlignment = MyMessageAlignmentPreference.Left,
                compactConsecutiveMessages = true,
                foregroundMessageBannersEnabled = false,
            )

        authority.applyProcessPreferenceChange(processSnapshot)
        authority.seedFromRepository(staleSeed)

        assertEquals(listOf(processSnapshot), applied)
    }

    @Test
    fun processOffRemainsAuthoritativeWhenInitialRepositorySeedCompletesLate() {
        val applied = mutableListOf<Boolean>()
        val authority =
            ConversationForwardGestureRuntimeAuthority(
                supported = true,
                onEffectivePreferenceChanged = applied::add
            )

        // Another shell durably writes OFF while this shell's initial repository read is still
        // suspended.
        authority.applyProcessPreferenceChange(false)

        // The older initial read completes after OFF. Its stale true value must not re-enable the
        // shell runtime.
        authority.seedFromRepository(true)

        assertEquals(listOf(false), applied)
    }

    @Test
    fun unsupportedHostFailsClosedForRepositorySeedAndProcessEvents() {
        val applied = mutableListOf<Boolean>()
        val authority =
            ConversationForwardGestureRuntimeAuthority(
                supported = false,
                onEffectivePreferenceChanged = applied::add
            )

        authority.seedFromRepository(true)
        authority.applyProcessPreferenceChange(true)

        assertEquals(listOf(false, false), applied)
    }

    @Test
    fun appScopedTransitionsReconcileEveryLiveShellAndNeverResurrectHistory() = runBlocking {
        val events = RaftChatLayoutPreferenceEvents(RaftEventBuses.inProcess())
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
                KeyValueChatLayoutPreferenceStore(InMemoryRaftKeyValueStore())
            )
        val repository = raftChatLayoutPreferenceRepository(persistentRepository, events)

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
