package build.raft.app.data

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
                put("newMessageMenuEnabled", true)
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
            repository.setForegroundMessageBannersEnabled(true)
            repository.setComposerSendKeyEnabled(true)
            repository.setMultiShellHostEnabled(true)
            repository.setServerDrawerEnabled(true)
            repository.setTranslationMode(TranslationModePreference.Automatic)
            repository.setTranslationTargetLanguage("ja")

            assertEquals(11, firstSnapshots.size)
            assertEquals(firstSnapshots, secondSnapshots)
            firstSnapshots.last().let { snapshot ->
                assertEquals(MyMessageAlignmentPreference.Right, snapshot.myMessageAlignment)
                assertFalse(snapshot.desktopModeOnLargeScreen)
                assertFalse(snapshot.compactConsecutiveMessages)
                assertTrue(snapshot.longMessageCollapseEnabled)
                assertTrue(snapshot.newMessageMenuEnabled)
                assertTrue(snapshot.foregroundMessageBannersEnabled)
                assertTrue(snapshot.composerSendKeyEnabled)
                assertTrue(snapshot.multiShellHostEnabled)
                assertTrue(snapshot.serverDrawerEnabled)
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
}
