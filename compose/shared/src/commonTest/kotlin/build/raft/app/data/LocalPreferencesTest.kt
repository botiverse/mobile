package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
                AppLocale.SimplifiedChinese,
                AppLocale.Japanese
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
                AppLocale.SimplifiedChinese,
                AppLocale.Japanese
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
        assertEquals(true, snapshot.longMessageCollapseEnabled)
        assertEquals(false, snapshot.singleTapOpensThreadEnabled)
        assertEquals(false, snapshot.threadUnreadJumpEnabled)
        assertEquals(false, snapshot.newMessageMenuEnabled)
        assertEquals(false, snapshot.conversationForwardGestureEnabled)
        assertEquals(false, snapshot.foregroundMessageBannersEnabled)
        assertEquals(true, snapshot.activityTaskStatusBadgesEnabled)
        assertEquals(false, snapshot.activitySenderAvatarsEnabled)
        assertEquals(false, snapshot.composerSendKeyEnabled)
        assertEquals(false, snapshot.multiShellHostEnabled)
        assertEquals(false, snapshot.serverDrawerEnabled)
        assertEquals(true, snapshot.newDesignSettingsEnabled)
        assertEquals(true, snapshot.homeServerAvatarEnabled)
        assertEquals(TranslationModePreference.Manual, snapshot.translationMode)
        assertEquals(TranslationTargetLanguages.Browser, snapshot.translationTargetLanguage)
        assertTrue(snapshot.supportedAlignments.contains(MyMessageAlignmentPreference.Right))
    }

    @Test
    fun threadUnreadJumpDefaultsOffButPreservesExplicitOn() = runBlocking {
        val store = InMemoryRaftKeyValueStore()
        val repository = PersistentChatLayoutPreferenceRepository(KeyValueChatLayoutPreferenceStore(store))

        assertEquals(false, repository.getChatLayoutPreference().threadUnreadJumpEnabled)

        repository.setThreadUnreadJumpEnabled(true)

        assertEquals(true, repository.getChatLayoutPreference().threadUnreadJumpEnabled)
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
    fun chatLayoutSnapshotModelDefaultsSingleTapThreadOpeningOff() {
        assertEquals(
            false,
            ChatLayoutPreferenceSnapshot(
                myMessageAlignment = MyMessageAlignmentPreference.Left
            ).singleTapOpensThreadEnabled
        )
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
                Triple(AppLocale.System, "zh_TW", "zh-cn"),
                // No Korean / French UI catalog: the UI falls back to English.
                Triple(AppLocale.System, "ko-KR", "en"),
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
    fun chatLayoutRestoresExplicitSingleTapThreadChoiceAcrossDefaultChanges() = runBlocking {
        val disabledStore = InMemoryRaftKeyValueStore()
        val disabledPreferenceStore = KeyValueChatLayoutPreferenceStore(disabledStore)
        disabledPreferenceStore.saveSingleTapOpensThreadEnabled(false)

        val disabledRestored =
            PersistentChatLayoutPreferenceRepository(
                KeyValueChatLayoutPreferenceStore(disabledStore)
            ).getChatLayoutPreference()

        val enabledStore = InMemoryRaftKeyValueStore()
        val enabledPreferenceStore = KeyValueChatLayoutPreferenceStore(enabledStore)
        enabledPreferenceStore.saveSingleTapOpensThreadEnabled(true)

        val enabledRestored =
            PersistentChatLayoutPreferenceRepository(
                KeyValueChatLayoutPreferenceStore(enabledStore)
            ).getChatLayoutPreference()

        assertEquals(false, disabledRestored.singleTapOpensThreadEnabled)
        assertEquals(true, enabledRestored.singleTapOpensThreadEnabled)
    }

    @Test
    fun homeServerAvatarPreferenceDefaultsOnAndKeepsAnExplicitOff() = runBlocking {
        // Task #470: on for anyone without a stored choice; a user who turned it off stays off.
        val key = "mobile:home_server_avatar_enabled"
        val store = InMemoryRaftKeyValueStore()
        val preferenceStore = KeyValueChatLayoutPreferenceStore(store)

        assertEquals(true, preferenceStore.readHomeServerAvatarEnabled())

        preferenceStore.saveHomeServerAvatarEnabled(false)
        assertEquals(false, preferenceStore.readHomeServerAvatarEnabled())

        preferenceStore.saveHomeServerAvatarEnabled(true)
        assertEquals(true, preferenceStore.readHomeServerAvatarEnabled())

        store.putString(key, null)
        assertEquals(true, preferenceStore.readHomeServerAvatarEnabled())
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
        val singleTapOpensThread = repository.setSingleTapOpensThreadEnabled(true)
        val threadUnreadJump = repository.setThreadUnreadJumpEnabled(true)
        val newMessageMenu = repository.setNewMessageMenuEnabled(true)
        val conversationForwardGesture = repository.setConversationForwardGestureEnabled(true)
        val foregroundMessageBanners = repository.setForegroundMessageBannersEnabled(true)
        val activityTaskStatusBadges = repository.setActivityTaskStatusBadgesEnabled(true)
        val activitySenderAvatars = repository.setActivitySenderAvatarsEnabled(true)
        val composerSendKey = repository.setComposerSendKeyEnabled(false)
        val multiShellHost = repository.setMultiShellHostEnabled(true)
        val serverDrawer = repository.setServerDrawerEnabled(true)
        val newDesignSettings = repository.setNewDesignSettingsEnabled(true)
        val homeServerAvatar = repository.setHomeServerAvatarEnabled(true)
        val translationMode = repository.setTranslationMode(TranslationModePreference.Manual)
        val translationTarget = repository.setTranslationTargetLanguage("zh-Hans")
        val restored = repository.getChatLayoutPreference()
        val disabledActivityTaskStatusBadges = repository.setActivityTaskStatusBadgesEnabled(false)
        val disabledActivitySenderAvatars = repository.setActivitySenderAvatarsEnabled(false)
        val disabledCompact = repository.setCompactConsecutiveMessages(false)
        val enabledComposerSendKey = repository.setComposerSendKeyEnabled(true)
        val restoredDisabledCompact = repository.getChatLayoutPreference()

        assertEquals(MyMessageAlignmentPreference.Right, alignment.myMessageAlignment)
        assertEquals(false, navigation.desktopModeOnLargeScreen)
        assertEquals(true, compact.compactConsecutiveMessages)
        assertEquals(true, longMessageCollapse.longMessageCollapseEnabled)
        assertEquals(true, singleTapOpensThread.singleTapOpensThreadEnabled)
        assertEquals(true, threadUnreadJump.threadUnreadJumpEnabled)
        assertEquals(true, newMessageMenu.newMessageMenuEnabled)
        assertEquals(true, conversationForwardGesture.conversationForwardGestureEnabled)
        assertEquals(true, foregroundMessageBanners.foregroundMessageBannersEnabled)
        assertEquals(true, activityTaskStatusBadges.activityTaskStatusBadgesEnabled)
        assertEquals(true, activitySenderAvatars.activitySenderAvatarsEnabled)
        assertEquals(false, composerSendKey.composerSendKeyEnabled)
        assertEquals(true, multiShellHost.multiShellHostEnabled)
        assertEquals(true, serverDrawer.serverDrawerEnabled)
        assertEquals(true, newDesignSettings.newDesignSettingsEnabled)
        assertEquals(true, homeServerAvatar.homeServerAvatarEnabled)
        assertEquals(TranslationModePreference.Manual, translationMode.translationMode)
        assertEquals("zh-cn", translationTarget.translationTargetLanguage)
        assertEquals(MyMessageAlignmentPreference.Right, restored.myMessageAlignment)
        assertEquals(false, restored.desktopModeOnLargeScreen)
        assertEquals(true, restored.compactConsecutiveMessages)
        assertEquals(true, restored.longMessageCollapseEnabled)
        assertEquals(true, restored.singleTapOpensThreadEnabled)
        assertEquals(true, restored.threadUnreadJumpEnabled)
        assertEquals(true, restored.newMessageMenuEnabled)
        assertEquals(true, restored.conversationForwardGestureEnabled)
        assertEquals(true, restored.foregroundMessageBannersEnabled)
        assertEquals(true, restored.activityTaskStatusBadgesEnabled)
        assertEquals(true, restored.activitySenderAvatarsEnabled)
        assertEquals(false, restored.composerSendKeyEnabled)
        assertEquals(true, restored.multiShellHostEnabled)
        assertEquals(true, restored.serverDrawerEnabled)
        assertEquals(true, restored.newDesignSettingsEnabled)
        assertEquals(true, restored.homeServerAvatarEnabled)
        assertEquals(TranslationModePreference.Manual, restored.translationMode)
        assertEquals("zh-cn", restored.translationTargetLanguage)
        assertEquals(false, repository.setForegroundMessageBannersEnabled(false).foregroundMessageBannersEnabled)
        assertEquals(false, disabledActivityTaskStatusBadges.activityTaskStatusBadgesEnabled)
        assertEquals(false, disabledActivitySenderAvatars.activitySenderAvatarsEnabled)
        assertEquals(false, disabledCompact.compactConsecutiveMessages)
        assertEquals(true, enabledComposerSendKey.composerSendKeyEnabled)
        assertEquals(false, restoredDisabledCompact.compactConsecutiveMessages)
        assertEquals(true, restoredDisabledCompact.composerSendKeyEnabled)
        assertEquals(true, restoredDisabledCompact.multiShellHostEnabled)
        assertEquals(true, restoredDisabledCompact.serverDrawerEnabled)
        assertEquals(true, restoredDisabledCompact.newDesignSettingsEnabled)
        assertEquals(true, restoredDisabledCompact.homeServerAvatarEnabled)
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
