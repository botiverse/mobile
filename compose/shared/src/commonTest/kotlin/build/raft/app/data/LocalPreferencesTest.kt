package build.raft.app.data

import build.raft.app.model.TaskStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalPreferencesTest {
    @Test
    fun localeDefaultsToSystemWhenNoPreferenceExists() = runBlocking {
        val repository = PersistentLocaleRepository(
            KeyValueLocalePreferenceStore(InMemoryRaftKeyValueStore())
        )

        val snapshot = repository.getLocale()

        assertEquals(AppLocale.System, snapshot.current)
        assertEquals(
            listOf(
                AppLocale.System,
                AppLocale.English,
                AppLocale.Spanish,
                AppLocale.BrazilianPortuguese,
                AppLocale.Russian,
                AppLocale.Korean,
                AppLocale.Japanese,
                AppLocale.SimplifiedChinese,
                AppLocale.TraditionalChinese
            ),
            snapshot.supported
        )
    }

    @Test
    fun localePersistsSelectedValue() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val repository = PersistentLocaleRepository(KeyValueLocalePreferenceStore(store))

        val selected = repository.selectLocale(AppLocale.Japanese)
        val restored = repository.getLocale()

        assertEquals(AppLocale.Japanese, selected.current)
        assertEquals(AppLocale.Japanese, restored.current)
    }

    @Test
    fun localePersistsExplicitSystemSelection() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val repository = PersistentLocaleRepository(KeyValueLocalePreferenceStore(store))

        repository.selectLocale(AppLocale.System)

        assertEquals(AppLocale.System, repository.getLocale().current)
        assertEquals("system", store.getString("locale:app_locale"))
    }

    @Test
    fun localeRestoresEveryExplicitSelectionWithoutTreatingEnglishAsMissing() = runBlocking {
        val expectedLocales =
            listOf(
                AppLocale.System,
                AppLocale.English,
                AppLocale.Spanish,
                AppLocale.BrazilianPortuguese,
                AppLocale.Russian,
                AppLocale.Korean,
                AppLocale.Japanese,
                AppLocale.SimplifiedChinese,
                AppLocale.TraditionalChinese
            )

        expectedLocales.forEach { expected ->
            val store = InMemoryRaftKeyValueStore()
            val preferences = KeyValueLocalePreferenceStore(store)
            preferences.saveLocale(expected)

            assertEquals(expected, preferences.readLocaleSync())
        }
    }

    @Test
    fun localeRestoresExplicitChoicesAndFallsBackUnknownValuesToEnglish() {
        assertEquals(AppLocale.English, AppLocale.fromStorageKey(null))
        assertEquals(AppLocale.English, AppLocale.fromStorageKey(""))
        assertEquals(AppLocale.English, AppLocale.fromStorageKey("en"))
        assertEquals(AppLocale.SimplifiedChinese, AppLocale.fromStorageKey("zh-Hans"))
        assertEquals(AppLocale.SimplifiedChinese, AppLocale.fromStorageKey("zh-CN"))
        assertEquals(AppLocale.SimplifiedChinese, AppLocale.fromStorageKey("zh-rCN"))
        assertEquals(AppLocale.Japanese, AppLocale.fromStorageKey("ja"))
        assertEquals(AppLocale.Japanese, AppLocale.fromStorageKey("ja-JP"))
        assertEquals(AppLocale.TraditionalChinese, AppLocale.fromStorageKey("zh-Hant"))
        assertEquals(AppLocale.TraditionalChinese, AppLocale.fromStorageKey("zh-TW"))
        assertEquals(AppLocale.BrazilianPortuguese, AppLocale.fromStorageKey("pt-BR"))
        assertEquals(AppLocale.Spanish, AppLocale.fromStorageKey("es"))
        assertEquals(AppLocale.Korean, AppLocale.fromStorageKey("ko"))
        assertEquals(AppLocale.Russian, AppLocale.fromStorageKey("ru"))
        assertEquals(AppLocale.Russian, AppLocale.fromStorageKey("ru-RU"))
        assertEquals(AppLocale.Russian, AppLocale.fromStorageKey("ru_RU"))
        assertEquals(AppLocale.System, AppLocale.fromStorageKey("auto"))
        assertEquals(AppLocale.System, AppLocale.fromStorageKey("default"))
        assertEquals(AppLocale.System, AppLocale.fromStorageKey("system"))
        assertEquals(AppLocale.English, AppLocale.fromStorageKey("unknown"))
    }

    @Test
    fun linkOpenModeDefaultsToSystemBrowserAndFallsBackUnknownValues() = runBlocking {
        val emptyRepository =
            PersistentLinkOpenPreferenceRepository(
                KeyValueLinkOpenPreferenceStore(InMemoryRaftKeyValueStore())
            )
        val unknownRepository =
            PersistentLinkOpenPreferenceRepository(
                KeyValueLinkOpenPreferenceStore(
                    InMemoryRaftKeyValueStore(
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
        val store = InMemoryRaftKeyValueStore()
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
            KeyValueChatLayoutPreferenceStore(InMemoryRaftKeyValueStore())
        )

        val snapshot = repository.getChatLayoutPreference()

        assertEquals(MyMessageAlignmentPreference.Left, snapshot.myMessageAlignment)
        assertEquals(true, snapshot.desktopModeOnLargeScreen)
        assertEquals(true, snapshot.compactConsecutiveMessages)
        assertEquals(false, snapshot.longMessageCollapseEnabled, "long messages show in full by default (web #10275)")
        assertEquals(false, snapshot.newMessageMenuEnabled)
        assertEquals(false, snapshot.conversationForwardGestureEnabled)
        assertEquals(false, snapshot.foregroundMessageBannersEnabled)
        assertEquals(false, snapshot.composerSendKeyEnabled)
        assertEquals(false, snapshot.multiShellHostEnabled)
        assertEquals(false, snapshot.serverDrawerEnabled)
        assertEquals(TranslationModePreference.Manual, snapshot.translationMode)
        assertEquals(TranslationTargetLanguages.Browser, snapshot.translationTargetLanguage)
        assertTrue(snapshot.supportedAlignments.contains(MyMessageAlignmentPreference.Right))
    }

    @Test
    fun multiServerRealtimeDefaultsOffAndPersistsExplicitOn() {
        val store = InMemoryRaftKeyValueStore()

        assertFalse(KeyValueChatLayoutPreferenceStore(store).readMultiServerRealtimeEnabledSync())

        KeyValueChatLayoutPreferenceStore(store).saveMultiServerRealtimeEnabledSync(true)
        assertTrue(KeyValueChatLayoutPreferenceStore(store).readMultiServerRealtimeEnabledSync())
        assertFalse(
            KeyValueChatLayoutPreferenceStore(store).readMultiShellHostEnabledSync(),
            "the multi-session switch does not turn on separate windows",
        )

        KeyValueChatLayoutPreferenceStore(store).saveMultiServerRealtimeEnabledSync(false)
        assertFalse(KeyValueChatLayoutPreferenceStore(store).readMultiServerRealtimeEnabledSync())
    }

    @Test
    fun desktopModeDefaultsOnAndPreservesExplicitOff() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val repository = PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(store))

        assertEquals(true, repository.getChatLayoutPreference().desktopModeOnLargeScreen)

        repository.setDesktopModeOnLargeScreen(false)

        assertEquals(false, repository.getChatLayoutPreference().desktopModeOnLargeScreen)
        assertEquals(false, KeyValueChatLayoutPreferenceStore(store).readDesktopModeOnLargeScreenSync())

        repository.setDesktopModeOnLargeScreen(true)

        assertEquals(true, repository.getChatLayoutPreference().desktopModeOnLargeScreen)
    }

    @Test
    fun desktopModeLegacyInstallsWithoutValueStillDefaultsOn() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        // Simulate a pre-#45 install: other layout prefs exist but the new key is absent.
        KeyValueChatLayoutPreferenceStore(store).saveCompactConsecutiveMessages(false)

        val repository = PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(store))

        assertEquals(true, repository.getChatLayoutPreference().desktopModeOnLargeScreen)
    }

    @Test
    fun serverDrawerDefaultsOffButPreservesExplicitOn() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val repository = PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(store))

        assertEquals(false, repository.getChatLayoutPreference().serverDrawerEnabled)

        repository.setServerDrawerEnabled(true)

        assertEquals(true, repository.getChatLayoutPreference().serverDrawerEnabled)
    }

    @Test
    fun legacyBrowserTranslationTargetRemainsNoPreferenceAfterUpgrade() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        store.putString("settings.translationTargetLanguage", "browser")
        val preferenceStore = KeyValueChatLayoutPreferenceStore(store)

        val restored = preferenceStore.readTranslationTargetLanguage()

        assertEquals(TranslationTargetLanguages.System, restored)
        assertEquals("browser", store.getString("settings.translationTargetLanguage"))
        assertEquals("ja", TranslationTargetLanguages.resolve(restored, AppLocale.Japanese.displayLanguageTag("en-US")))
    }

    @Test
    fun displayLanguageTagIsTheLanguageTheUiIsShownIn() {
        // (app locale, device language) -> no-preference translation target.
        val cases =
            listOf(
                Triple(AppLocale.English, "ja-JP", "en"),
                Triple(AppLocale.SimplifiedChinese, "en-US", "zh-cn"),
                Triple(AppLocale.Japanese, "zh-Hans-CN", "ja"),
                Triple(AppLocale.System, "ja-JP", "ja"),
                Triple(AppLocale.System, "zh_TW", "zh-tw"),
                Triple(AppLocale.System, "ko-KR", "ko"),
                Triple(AppLocale.System, "es-MX", "es"),
                Triple(AppLocale.System, "ru-RU", "ru"),
                Triple(AppLocale.Russian, "en-US", "ru"),
                Triple(AppLocale.System, "pt-PT", "pt-br"),
                Triple(AppLocale.TraditionalChinese, "en-US", "zh-tw"),
                Triple(AppLocale.BrazilianPortuguese, "en-US", "pt-br"),
                // No French UI catalog: the UI falls back to English.
                Triple(AppLocale.System, "fr-FR", "en"),
                Triple(AppLocale.System, null, "en"),
            )
        cases.forEach { (locale, device, expected) ->
            assertEquals(
                expected,
                TranslationTargetLanguages.resolve(TranslationTargetLanguages.System, locale.displayLanguageTag(device)),
                "$locale on $device"
            )
        }
        // The whole preferred-language list, like the string lookup: [fr, ja] shows a Japanese UI.
        assertEquals("ja", AppLocale.System.displayLanguageTag(listOf("fr-FR", "ja-JP")))
        assertEquals("zh-Hant", AppLocale.System.displayLanguageTag(listOf("zh-Hant-TW", "fr-FR")))
        assertEquals("en", AppLocale.System.displayLanguageTag(listOf("fr-FR", "de-DE")))
    }

    /** The web stores the account's display language as these codes (botiverse/slock DISPLAY_LOCALES). */
    @Test
    fun everyExplicitLocaleHasTheWebDisplayLocaleCodeInTheWebPickerOrder() {
        assertEquals(
            listOf("en", "es", "pt-br", "ru", "ko", "ja", "zh-cn", "zh-tw"),
            AppLocale.entries.mapNotNull { it.webDisplayLocale }
        )
        assertEquals(
            listOf("English", "Español", "Português (Brasil)", "Русский", "한국어", "日本語", "简体中文", "繁體中文"),
            AppLocale.entries.filter { it != AppLocale.System }.map { it.nativeLabel }
        )
        AppLocale.entries.filter { it != AppLocale.System }.forEach { locale ->
            assertEquals(locale, AppLocale.fromWebDisplayLocale(locale.webDisplayLocale), "$locale")
            assertEquals(locale, AppLocale.fromStorageKey(locale.webDisplayLocale), "$locale")
            assertEquals(locale, AppLocale.fromStorageKey(locale.storageKey), "$locale")
        }
    }

    @Test
    fun chatLayoutRestoresExplicitSendKeyChoiceAfterDefaultChangesToNewLine() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        store.putBoolean("chatLayout:composer_send_key_enabled", true)
        val repository =
            PersistentChatLayoutPreferenceRepository(
                KeyValueChatLayoutPreferenceStore(store)
            )

        val snapshot = repository.getChatLayoutPreference()

        assertEquals(true, snapshot.composerSendKeyEnabled)
    }

    @Test
    fun conversationForwardGestureDefaultsOffFailsClosedAndPersistsIndependently() = runBlocking {
        val key = "mobile:conversation_forward_gesture_enabled"
        val store = InMemoryRaftKeyValueStore()
        val preferenceStore = KeyValueChatLayoutPreferenceStore(store)
        val repository = PersistentChatLayoutPreferenceRepository(preferenceStore)

        assertEquals(false, preferenceStore.readConversationForwardGestureEnabledSync())
        assertEquals(false, repository.getChatLayoutPreference().conversationForwardGestureEnabled)

        store.putString(key, "unsupported")
        assertEquals(false, preferenceStore.readConversationForwardGestureEnabled())

        val enabled = repository.setConversationForwardGestureEnabled(true)
        assertEquals(true, enabled.conversationForwardGestureEnabled)
        assertEquals(true, preferenceStore.readConversationForwardGestureEnabledSync())

        repository.setNewMessageMenuEnabled(true)
        val restored =
            PersistentChatLayoutPreferenceRepository(
                KeyValueChatLayoutPreferenceStore(store)
            ).getChatLayoutPreference()
        assertEquals(true, restored.conversationForwardGestureEnabled)
        assertEquals(true, restored.newMessageMenuEnabled)
    }

    @Test
    fun chatLayoutPersistsSelectedValues() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val repository = PersistentChatLayoutPreferenceRepository(
            KeyValueChatLayoutPreferenceStore(store)
        )

        val alignment = repository.selectMyMessageAlignment(MyMessageAlignmentPreference.Right)
        val navigation = repository.setDesktopModeOnLargeScreen(false)
        val compact = repository.setCompactConsecutiveMessages(true)
        val longMessageCollapse = repository.setLongMessageCollapseEnabled(true)
        val newMessageMenu = repository.setNewMessageMenuEnabled(true)
        val conversationForwardGesture = repository.setConversationForwardGestureEnabled(true)
        val foregroundMessageBanners = repository.setForegroundMessageBannersEnabled(true)
        val composerSendKey = repository.setComposerSendKeyEnabled(false)
        val multiShellHost = repository.setMultiShellHostEnabled(true)
        val serverDrawer = repository.setServerDrawerEnabled(true)
        val translationMode = repository.setTranslationMode(TranslationModePreference.Manual)
        val translationTarget = repository.setTranslationTargetLanguage("zh-Hans")
        val restored = repository.getChatLayoutPreference()
        val disabledCompact = repository.setCompactConsecutiveMessages(false)
        val enabledComposerSendKey = repository.setComposerSendKeyEnabled(true)
        val restoredDisabledCompact = repository.getChatLayoutPreference()

        assertEquals(MyMessageAlignmentPreference.Right, alignment.myMessageAlignment)
        assertEquals(false, navigation.desktopModeOnLargeScreen)
        assertEquals(true, compact.compactConsecutiveMessages)
        assertEquals(true, longMessageCollapse.longMessageCollapseEnabled)
        assertEquals(true, newMessageMenu.newMessageMenuEnabled)
        assertEquals(true, conversationForwardGesture.conversationForwardGestureEnabled)
        assertEquals(true, foregroundMessageBanners.foregroundMessageBannersEnabled)
        assertEquals(false, composerSendKey.composerSendKeyEnabled)
        assertEquals(true, multiShellHost.multiShellHostEnabled)
        assertEquals(true, serverDrawer.serverDrawerEnabled)
        assertEquals(TranslationModePreference.Manual, translationMode.translationMode)
        assertEquals("zh-cn", translationTarget.translationTargetLanguage)
        assertEquals(MyMessageAlignmentPreference.Right, restored.myMessageAlignment)
        assertEquals(false, restored.desktopModeOnLargeScreen)
        assertEquals(true, restored.compactConsecutiveMessages)
        assertEquals(true, restored.longMessageCollapseEnabled)
        assertEquals(true, restored.newMessageMenuEnabled)
        assertEquals(true, restored.conversationForwardGestureEnabled)
        assertEquals(true, restored.foregroundMessageBannersEnabled)
        assertEquals(false, restored.composerSendKeyEnabled)
        assertEquals(true, restored.multiShellHostEnabled)
        assertEquals(true, restored.serverDrawerEnabled)
        assertEquals(TranslationModePreference.Manual, restored.translationMode)
        assertEquals("zh-cn", restored.translationTargetLanguage)
        assertEquals(false, repository.setForegroundMessageBannersEnabled(false).foregroundMessageBannersEnabled)
        assertEquals(false, disabledCompact.compactConsecutiveMessages)
        assertEquals(true, enabledComposerSendKey.composerSendKeyEnabled)
        assertEquals(false, restoredDisabledCompact.compactConsecutiveMessages)
        assertEquals(true, restoredDisabledCompact.composerSendKeyEnabled)
        assertEquals(true, restoredDisabledCompact.multiShellHostEnabled)
        assertEquals(true, restoredDisabledCompact.serverDrawerEnabled)
        assertEquals(true, restoredDisabledCompact.newMessageMenuEnabled)
    }

    @Test
    fun tasksViewModeDefaultsAndPersistsSelectedMode() = runBlocking {
        val repository = KeyValueTasksViewPreferenceRepository(InMemoryRaftKeyValueStore())

        assertEquals(TasksViewMode.List, repository.getTasksViewModeSync(TasksViewMode.List))

        val selected = repository.selectTasksViewMode(TasksViewMode.Board)
        val restored = repository.getTasksViewMode(TasksViewMode.List)

        assertEquals(TasksViewMode.Board, selected)
        assertEquals(TasksViewMode.Board, restored)
    }

    @Test
    fun tasksPageFoldsAreKeptPerServerAndUnreadableOnesReadAsNone() {
        val backing = InMemoryRaftKeyValueStore()
        val repository = KeyValueTasksPageFoldPreferenceRepository(backing)
        assertEquals(emptyMap(), repository.folds("server-a"))

        repository.saveFolds("server-a", mapOf(TaskStatus.Done to false, TaskStatus.Todo to true))
        // A new repository over the same device store (the app opened again) reads them; another server has none.
        assertEquals(mapOf(TaskStatus.Done to false, TaskStatus.Todo to true), KeyValueTasksPageFoldPreferenceRepository(backing).folds("server-a"))
        assertEquals(emptyMap(), repository.folds("server-b"))

        // Web readStoredFolds (botiverse/slock eb63aec86e): unreadable folds read as none, and an entry that is
        // not a known status set to true or false is skipped.
        fun stored(raw: String) =
            KeyValueTasksPageFoldPreferenceRepository(InMemoryRaftKeyValueStore(mapOf("tasks:pageFolds:server-a" to raw))).folds("server-a")
        assertEquals(emptyMap(), stored("not json"))
        assertEquals(emptyMap(), stored("""["done"]"""))
        assertEquals(mapOf(TaskStatus.Done to true), stored("""{"done":true,"bogus":true,"closed":"yes","todo":"true","backlog":null}"""))
    }

    @Test
    fun computersRackViewDefaultsToCardsAndKeepsTheChoice() {
        val backing = InMemoryRaftKeyValueStore()
        val repository = KeyValueComputersRackViewPreferenceRepository(backing)

        assertEquals(ComputersRackView.Cards, repository.getComputersRackViewSync())

        assertEquals(ComputersRackView.List, repository.selectComputersRackViewSync(ComputersRackView.List))
        // A new repository over the same device store (the page opened again) reads the choice.
        assertEquals(ComputersRackView.List, KeyValueComputersRackViewPreferenceRepository(backing).getComputersRackViewSync())

        repository.selectComputersRackViewSync(ComputersRackView.Cards)
        assertEquals(ComputersRackView.Cards, KeyValueComputersRackViewPreferenceRepository(backing).getComputersRackViewSync())
    }

    @Test
    fun computersRackViewReadsAnUnknownValueAsCards() {
        val repository = KeyValueComputersRackViewPreferenceRepository(InMemoryRaftKeyValueStore(mapOf("computers:rackView" to "grid")))

        assertEquals(ComputersRackView.Cards, repository.getComputersRackViewSync())
    }

    @Test
    fun pushPreviewModeDefaultsToFullWhenNeverStored() = runBlocking {
        val store = KeyValuePushPreviewModePreferenceStore(InMemoryRaftKeyValueStore())

        assertEquals(PUSH_PREVIEW_MODE_DEFAULT, store.readPushPreviewModeSync())
        assertEquals("full", store.readPushPreviewModeSync())
    }

    @Test
    fun pushPreviewModePersistsOnlyCanonicalWireValues() = runBlocking {
        val backing = InMemoryRaftKeyValueStore()
        val store = KeyValuePushPreviewModePreferenceStore(backing)

        store.savePushPreviewMode("sender_only")
        assertEquals("sender_only", store.readPushPreviewModeSync())

        // Anything the enum does not know is ignored rather than written.
        store.savePushPreviewMode("nonsense")
        store.savePushPreviewMode("")
        assertEquals("sender_only", store.readPushPreviewModeSync())
        assertEquals("sender_only", backing.getString(PUSH_PREVIEW_MODE_PREFERENCE_KEY))
    }

    @Test
    fun unreadableStoredPushPreviewModeFailsClosed() = runBlocking {
        // This setting exists to keep message text out of notifications. A corrupt value must not
        // silently start showing bodies again, so it resolves to hidden rather than full.
        val backing = InMemoryRaftKeyValueStore(mapOf(PUSH_PREVIEW_MODE_PREFERENCE_KEY to "corrupt"))

        val store = KeyValuePushPreviewModePreferenceStore(backing)

        assertEquals(PUSH_PREVIEW_MODE_UNREADABLE, store.readPushPreviewModeSync())
        assertEquals("hidden", store.readPushPreviewModeSync())
    }

    @Test
    fun missingAndUnreadablePushPreviewModeResolveDifferently() {
        val empty = KeyValuePushPreviewModePreferenceStore(InMemoryRaftKeyValueStore())
        val corruptBacking = InMemoryRaftKeyValueStore(mapOf(PUSH_PREVIEW_MODE_PREFERENCE_KEY to "corrupt"))
        val corrupt = KeyValuePushPreviewModePreferenceStore(corruptBacking)

        assertEquals("full", empty.readPushPreviewModeSync())
        assertEquals("hidden", corrupt.readPushPreviewModeSync())
    }
}
