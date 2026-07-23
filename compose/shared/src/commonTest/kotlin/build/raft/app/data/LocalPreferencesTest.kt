package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalPreferencesTest {
    @Test
    fun networkTransportPreferenceUsesPlatformDefaultUntilExplicitlyPersisted() {
        val values = InMemorySlockKeyValueStore()
        val store = KeyValueNetworkTransportPreferenceStore(values)

        assertEquals(false, store.readHttp3EnabledSync(defaultValue = false))
        assertEquals(true, store.readHttp3EnabledSync(defaultValue = true))

        store.writeHttp3EnabledSync(false)
        assertEquals(false, store.readHttp3EnabledSync(defaultValue = true))

        store.writeHttp3EnabledSync(true)
        assertEquals(true, store.readHttp3EnabledSync(defaultValue = false))
        assertEquals(
            "true",
            values.getString(KeyValueNetworkTransportPreferenceStore.KEY_HTTP3_ENABLED)
        )
    }

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
    fun linkOpenModeDefaultsToSystemBrowserAndFallsBackUnknownValues() = runBlocking {
        val emptyRepository =
            PersistentLinkOpenPreferenceRepository(
                KeyValueLinkOpenPreferenceStore(InMemorySlockKeyValueStore())
            )
        val unknownRepository =
            PersistentLinkOpenPreferenceRepository(
                KeyValueLinkOpenPreferenceStore(
                    InMemorySlockKeyValueStore(
                        mapOf("navigation:link_open_mode" to "unsupported")
                    )
                )
            )

        assertEquals(LinkOpenMode.SystemBrowser, emptyRepository.getLinkOpenMode())
        assertEquals(LinkOpenMode.SystemBrowser, unknownRepository.getLinkOpenMode())
        assertEquals(LinkOpenMode.SystemBrowser, LinkOpenMode.fromStorageKey(null))
        assertEquals(LinkOpenMode.SystemBrowser, LinkOpenMode.fromStorageKey("unknown"))
    }

    @Test
    fun linkOpenModePersistsInAppBrowserUsingNavigationKey() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        val repository =
            PersistentLinkOpenPreferenceRepository(KeyValueLinkOpenPreferenceStore(store))

        val selected = repository.selectLinkOpenMode(LinkOpenMode.InAppBrowser)

        assertEquals(LinkOpenMode.InAppBrowser, selected)
        assertEquals(LinkOpenMode.InAppBrowser, repository.getLinkOpenModeSync())
        assertEquals("in_app", store.getString("navigation:link_open_mode"))
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
        assertEquals(false, snapshot.singleTapOpensThreadEnabled)
        assertEquals(true, snapshot.imageAnnotationEnabled)
        assertEquals(false, snapshot.foregroundMessageBannersEnabled)
        assertEquals(false, snapshot.activityTaskStatusBadgesEnabled)
        assertEquals(false, snapshot.activitySenderAvatarsEnabled)
        assertEquals(false, snapshot.composerSendKeyEnabled)
        assertEquals(false, snapshot.multiShellHostEnabled)
        assertEquals(false, snapshot.serverDrawerEnabled)
        assertEquals(TranslationModePreference.Manual, snapshot.translationMode)
        assertEquals(TranslationTargetLanguages.Browser, snapshot.translationTargetLanguage)
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
    fun chatLayoutRestoresExplicitSendKeyChoiceAfterDefaultChangesToNewLine() = runBlocking {
        val store = InMemorySlockKeyValueStore()
        store.putBoolean("chatLayout:composer_send_key_enabled", true)
        val repository =
            PersistentChatLayoutPreferenceRepository(
                KeyValueChatLayoutPreferenceStore(store)
            )

        val snapshot = repository.getChatLayoutPreference()

        assertEquals(true, snapshot.composerSendKeyEnabled)
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
        val singleTapOpensThread = repository.setSingleTapOpensThreadEnabled(true)
        val imageAnnotation = repository.setImageAnnotationEnabled(true)
        val foregroundMessageBanners = repository.setForegroundMessageBannersEnabled(true)
        val activityTaskStatusBadges = repository.setActivityTaskStatusBadgesEnabled(true)
        val activitySenderAvatars = repository.setActivitySenderAvatarsEnabled(true)
        val composerSendKey = repository.setComposerSendKeyEnabled(false)
        val multiShellHost = repository.setMultiShellHostEnabled(true)
        val serverDrawer = repository.setServerDrawerEnabled(true)
        val translationMode = repository.setTranslationMode(TranslationModePreference.Manual)
        val translationTarget = repository.setTranslationTargetLanguage("zh-Hans")
        val savedAction = repository.setThreadRowAffordance(ThreadRowAffordancePreference.Saved, true)
        val reactionAction = repository.setThreadRowAffordance(ThreadRowAffordancePreference.Reactions, false)
        val restored = repository.getChatLayoutPreference()
        val disabledImageAnnotation = repository.setImageAnnotationEnabled(false)
        val disabledActivityTaskStatusBadges = repository.setActivityTaskStatusBadgesEnabled(false)
        val disabledActivitySenderAvatars = repository.setActivitySenderAvatarsEnabled(false)
        val disabledCompact = repository.setCompactConsecutiveMessages(false)
        val enabledComposerSendKey = repository.setComposerSendKeyEnabled(true)
        val restoredDisabledCompact = repository.getChatLayoutPreference()

        assertEquals(MyMessageAlignmentPreference.Right, alignment.myMessageAlignment)
        assertEquals(LargeScreenNavigationPreference.FloatingDock, navigation.largeScreenNavigation)
        assertEquals(true, compact.compactConsecutiveMessages)
        assertEquals(true, longMessageCollapse.longMessageCollapseEnabled)
        assertEquals(true, singleTapOpensThread.singleTapOpensThreadEnabled)
        assertEquals(true, imageAnnotation.imageAnnotationEnabled)
        assertEquals(true, foregroundMessageBanners.foregroundMessageBannersEnabled)
        assertEquals(true, activityTaskStatusBadges.activityTaskStatusBadgesEnabled)
        assertEquals(true, activitySenderAvatars.activitySenderAvatarsEnabled)
        assertEquals(false, composerSendKey.composerSendKeyEnabled)
        assertEquals(true, multiShellHost.multiShellHostEnabled)
        assertEquals(true, serverDrawer.serverDrawerEnabled)
        assertEquals(TranslationModePreference.Manual, translationMode.translationMode)
        assertEquals("zh-cn", translationTarget.translationTargetLanguage)
        assertEquals(MyMessageAlignmentPreference.Right, restored.myMessageAlignment)
        assertEquals(LargeScreenNavigationPreference.FloatingDock, restored.largeScreenNavigation)
        assertEquals(true, restored.compactConsecutiveMessages)
        assertEquals(true, restored.longMessageCollapseEnabled)
        assertEquals(true, restored.singleTapOpensThreadEnabled)
        assertEquals(true, restored.imageAnnotationEnabled)
        assertEquals(true, restored.foregroundMessageBannersEnabled)
        assertEquals(true, restored.activityTaskStatusBadgesEnabled)
        assertEquals(true, restored.activitySenderAvatarsEnabled)
        assertEquals(false, restored.composerSendKeyEnabled)
        assertEquals(true, restored.multiShellHostEnabled)
        assertEquals(true, restored.serverDrawerEnabled)
        assertEquals(TranslationModePreference.Manual, restored.translationMode)
        assertEquals("zh-cn", restored.translationTargetLanguage)
        assertEquals(setOf(ThreadRowAffordancePreference.Reactions), savedAction.threadRowAffordances)
        assertEquals(emptySet(), reactionAction.threadRowAffordances)
        assertEquals(emptySet(), restored.threadRowAffordances)
        assertEquals(false, disabledImageAnnotation.imageAnnotationEnabled)
        assertEquals(false, repository.setForegroundMessageBannersEnabled(false).foregroundMessageBannersEnabled)
        assertEquals(false, disabledActivityTaskStatusBadges.activityTaskStatusBadgesEnabled)
        assertEquals(false, disabledActivitySenderAvatars.activitySenderAvatarsEnabled)
        assertEquals(false, disabledCompact.compactConsecutiveMessages)
        assertEquals(true, enabledComposerSendKey.composerSendKeyEnabled)
        assertEquals(false, restoredDisabledCompact.imageAnnotationEnabled)
        assertEquals(false, restoredDisabledCompact.compactConsecutiveMessages)
        assertEquals(true, restoredDisabledCompact.composerSendKeyEnabled)
        assertEquals(true, restoredDisabledCompact.multiShellHostEnabled)
        assertEquals(true, restoredDisabledCompact.serverDrawerEnabled)
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
