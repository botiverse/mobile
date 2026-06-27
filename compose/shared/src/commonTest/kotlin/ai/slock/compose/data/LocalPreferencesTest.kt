package ai.slock.compose.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalPreferencesTest {
    @Test
    fun localeDefaultsToEnglishWhenNoPreferenceExists() = runBlocking {
        val repository = PersistentLocaleRepository(
            KeyValueLocalePreferenceStore(InMemorySlockKeyValueStore())
        )

        val snapshot = repository.getLocale()

        assertEquals(AppLocale.English, snapshot.current)
        assertTrue(snapshot.supported.contains(AppLocale.SimplifiedChinese))
    }

    @Test
    fun localePersistsSelectedValue() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        val repository = PersistentLocaleRepository(KeyValueLocalePreferenceStore(store))

        val selected = repository.selectLocale(AppLocale.SimplifiedChinese)
        val restored = repository.getLocale()

        assertEquals(AppLocale.SimplifiedChinese, selected.current)
        assertEquals(AppLocale.SimplifiedChinese, restored.current)
    }

    @Test
    fun chatLayoutDefaultsToSharedValuesWhenNoPreferenceExists() = runBlocking {
        val repository = PersistentChatLayoutPreferenceRepository(
            KeyValueChatLayoutPreferenceStore(InMemorySlockKeyValueStore())
        )

        val snapshot = repository.getChatLayoutPreference()

        assertEquals(MyMessageAlignmentPreference.Left, snapshot.myMessageAlignment)
        assertEquals(LargeScreenNavigationPreference.LeftRail, snapshot.largeScreenNavigation)
        assertEquals(false, snapshot.compactConsecutiveMessages)
        assertTrue(snapshot.supportedAlignments.contains(MyMessageAlignmentPreference.Right))
        assertTrue(
            snapshot.supportedLargeScreenNavigation.contains(
                LargeScreenNavigationPreference.FloatingDock
            )
        )
    }

    @Test
    fun chatLayoutPersistsSelectedValues() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        val repository = PersistentChatLayoutPreferenceRepository(
            KeyValueChatLayoutPreferenceStore(store)
        )

        val alignment = repository.selectMyMessageAlignment(MyMessageAlignmentPreference.Right)
        val navigation =
            repository.selectLargeScreenNavigation(LargeScreenNavigationPreference.FloatingDock)
        val compact = repository.setCompactConsecutiveMessages(true)
        val restored = repository.getChatLayoutPreference()

        assertEquals(MyMessageAlignmentPreference.Right, alignment.myMessageAlignment)
        assertEquals(LargeScreenNavigationPreference.FloatingDock, navigation.largeScreenNavigation)
        assertEquals(true, compact.compactConsecutiveMessages)
        assertEquals(MyMessageAlignmentPreference.Right, restored.myMessageAlignment)
        assertEquals(LargeScreenNavigationPreference.FloatingDock, restored.largeScreenNavigation)
        assertEquals(true, restored.compactConsecutiveMessages)
    }

    @Test
    fun tasksViewModeDefaultsAndPersistsSelectedMode() = runBlocking {
        val repository = KeyValueTasksViewPreferenceRepository(InMemorySlockKeyValueStore())

        assertEquals(TasksViewMode.List, repository.getTasksViewModeSync(TasksViewMode.List))

        val selected = repository.selectTasksViewMode(TasksViewMode.Board)
        val restored = repository.getTasksViewMode(TasksViewMode.List)

        assertEquals(TasksViewMode.Board, selected)
        assertEquals(TasksViewMode.Board, restored)
    }
}
