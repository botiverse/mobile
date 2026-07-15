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
        assertEquals(
            listOf(AppLocale.System, AppLocale.English, AppLocale.SimplifiedChinese),
            snapshot.supported
        )
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
    fun localePersistsExplicitSystemSelection() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        val repository = PersistentLocaleRepository(KeyValueLocalePreferenceStore(store))

        repository.selectLocale(AppLocale.System)

        assertEquals(AppLocale.System, repository.getLocale().current)
        assertEquals("system", store.getString("locale:app_locale"))
    }

    @Test
    fun localeRestoresExplicitChoicesAndFallsBackUnknownValuesToEnglish() {
        assertEquals(AppLocale.English, AppLocale.fromStorageKey(null))
        assertEquals(AppLocale.English, AppLocale.fromStorageKey(""))
        assertEquals(AppLocale.English, AppLocale.fromStorageKey("en"))
        assertEquals(AppLocale.SimplifiedChinese, AppLocale.fromStorageKey("zh-Hans"))
        assertEquals(AppLocale.SimplifiedChinese, AppLocale.fromStorageKey("zh-CN"))
        assertEquals(AppLocale.SimplifiedChinese, AppLocale.fromStorageKey("zh-rCN"))
        assertEquals(AppLocale.System, AppLocale.fromStorageKey("auto"))
        assertEquals(AppLocale.System, AppLocale.fromStorageKey("default"))
        assertEquals(AppLocale.System, AppLocale.fromStorageKey("system"))
        assertEquals(AppLocale.English, AppLocale.fromStorageKey("unknown"))
    }

    @Test
    fun chatLayoutDefaultsToSharedValuesWhenNoPreferenceExists() = runBlocking {
        val repository = PersistentChatLayoutPreferenceRepository(
            KeyValueChatLayoutPreferenceStore(InMemorySlockKeyValueStore())
        )

        val snapshot = repository.getChatLayoutPreference()

        assertEquals(MyMessageAlignmentPreference.Left, snapshot.myMessageAlignment)
        assertEquals(LargeScreenNavigationPreference.LeftRail, snapshot.largeScreenNavigation)
        assertEquals(true, snapshot.compactConsecutiveMessages)
        assertEquals(false, snapshot.longMessageCollapseEnabled)
        assertEquals(true, snapshot.imageAnnotationEnabled)
        assertEquals(false, snapshot.foregroundMessageBannersEnabled)
        assertEquals(false, snapshot.activityTaskStatusBadgesEnabled)
        assertEquals(true, snapshot.composerSendKeyEnabled)
        assertEquals(TranslationModePreference.Manual, snapshot.translationMode)
        assertEquals(setOf(ThreadRowAffordancePreference.Reactions), snapshot.threadRowAffordances)
        assertTrue(snapshot.supportedAlignments.contains(MyMessageAlignmentPreference.Right))
        assertTrue(
            snapshot.supportedLargeScreenNavigation.contains(
                LargeScreenNavigationPreference.FloatingDock
            )
        )
        assertEquals(
            listOf(ThreadRowAffordancePreference.Reactions),
            snapshot.supportedThreadRowAffordances
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
        val longMessageCollapse = repository.setLongMessageCollapseEnabled(true)
        val imageAnnotation = repository.setImageAnnotationEnabled(true)
        val foregroundMessageBanners = repository.setForegroundMessageBannersEnabled(true)
        val activityTaskStatusBadges = repository.setActivityTaskStatusBadgesEnabled(true)
        val composerSendKey = repository.setComposerSendKeyEnabled(false)
        val translationMode = repository.setTranslationMode(TranslationModePreference.Manual)
        val savedAction = repository.setThreadRowAffordance(ThreadRowAffordancePreference.Saved, true)
        val reactionAction = repository.setThreadRowAffordance(ThreadRowAffordancePreference.Reactions, false)
        val restored = repository.getChatLayoutPreference()
        val disabledImageAnnotation = repository.setImageAnnotationEnabled(false)
        val disabledActivityTaskStatusBadges = repository.setActivityTaskStatusBadgesEnabled(false)
        val disabledCompact = repository.setCompactConsecutiveMessages(false)
        val enabledComposerSendKey = repository.setComposerSendKeyEnabled(true)
        val restoredDisabledCompact = repository.getChatLayoutPreference()

        assertEquals(MyMessageAlignmentPreference.Right, alignment.myMessageAlignment)
        assertEquals(LargeScreenNavigationPreference.FloatingDock, navigation.largeScreenNavigation)
        assertEquals(true, compact.compactConsecutiveMessages)
        assertEquals(true, longMessageCollapse.longMessageCollapseEnabled)
        assertEquals(true, imageAnnotation.imageAnnotationEnabled)
        assertEquals(true, foregroundMessageBanners.foregroundMessageBannersEnabled)
        assertEquals(true, activityTaskStatusBadges.activityTaskStatusBadgesEnabled)
        assertEquals(false, composerSendKey.composerSendKeyEnabled)
        assertEquals(TranslationModePreference.Manual, translationMode.translationMode)
        assertEquals(MyMessageAlignmentPreference.Right, restored.myMessageAlignment)
        assertEquals(LargeScreenNavigationPreference.FloatingDock, restored.largeScreenNavigation)
        assertEquals(true, restored.compactConsecutiveMessages)
        assertEquals(true, restored.longMessageCollapseEnabled)
        assertEquals(true, restored.imageAnnotationEnabled)
        assertEquals(true, restored.foregroundMessageBannersEnabled)
        assertEquals(true, restored.activityTaskStatusBadgesEnabled)
        assertEquals(false, restored.composerSendKeyEnabled)
        assertEquals(TranslationModePreference.Manual, restored.translationMode)
        assertEquals(setOf(ThreadRowAffordancePreference.Reactions), savedAction.threadRowAffordances)
        assertEquals(emptySet(), reactionAction.threadRowAffordances)
        assertEquals(emptySet(), restored.threadRowAffordances)
        assertEquals(false, disabledImageAnnotation.imageAnnotationEnabled)
        assertEquals(false, repository.setForegroundMessageBannersEnabled(false).foregroundMessageBannersEnabled)
        assertEquals(false, disabledActivityTaskStatusBadges.activityTaskStatusBadgesEnabled)
        assertEquals(false, disabledCompact.compactConsecutiveMessages)
        assertEquals(true, enabledComposerSendKey.composerSendKeyEnabled)
        assertEquals(false, restoredDisabledCompact.imageAnnotationEnabled)
        assertEquals(false, restoredDisabledCompact.compactConsecutiveMessages)
        assertEquals(true, restoredDisabledCompact.composerSendKeyEnabled)
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
