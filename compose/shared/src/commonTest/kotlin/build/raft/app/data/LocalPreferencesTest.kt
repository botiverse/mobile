package build.raft.app.data

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
        assertEquals(false, snapshot.imageAnnotationEnabled)
        assertEquals(setOf(ThreadRowAffordancePreference.Reactions), snapshot.threadRowAffordances)
        assertTrue(snapshot.supportedAlignments.contains(MyMessageAlignmentPreference.Right))
        assertTrue(
            snapshot.supportedLargeScreenNavigation.contains(
                LargeScreenNavigationPreference.FloatingDock
            )
        )
        assertTrue(snapshot.supportedThreadRowAffordances.contains(ThreadRowAffordancePreference.Saved))
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
        val imageAnnotation = repository.setImageAnnotationEnabled(true)
        val savedAction = repository.setThreadRowAffordance(ThreadRowAffordancePreference.Saved, true)
        val reactionAction = repository.setThreadRowAffordance(ThreadRowAffordancePreference.Reactions, false)
        val restored = repository.getChatLayoutPreference()
        val disabledImageAnnotation = repository.setImageAnnotationEnabled(false)

        assertEquals(MyMessageAlignmentPreference.Right, alignment.myMessageAlignment)
        assertEquals(LargeScreenNavigationPreference.FloatingDock, navigation.largeScreenNavigation)
        assertEquals(true, compact.compactConsecutiveMessages)
        assertEquals(true, imageAnnotation.imageAnnotationEnabled)
        assertEquals(MyMessageAlignmentPreference.Right, restored.myMessageAlignment)
        assertEquals(LargeScreenNavigationPreference.FloatingDock, restored.largeScreenNavigation)
        assertEquals(true, restored.compactConsecutiveMessages)
        assertEquals(true, restored.imageAnnotationEnabled)
        assertEquals(
            setOf(ThreadRowAffordancePreference.Reactions, ThreadRowAffordancePreference.Saved),
            savedAction.threadRowAffordances
        )
        assertEquals(setOf(ThreadRowAffordancePreference.Saved), reactionAction.threadRowAffordances)
        assertEquals(setOf(ThreadRowAffordancePreference.Saved), restored.threadRowAffordances)
        assertEquals(false, disabledImageAnnotation.imageAnnotationEnabled)
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
