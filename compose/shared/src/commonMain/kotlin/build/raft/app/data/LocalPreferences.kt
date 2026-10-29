package build.raft.app.data

import build.raft.app.raftPlatformComposerEnterSendsByDefault
import build.raft.app.resources.raftSystemResourceLocale
import build.raft.app.resources.StringDesc
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal const val DEFAULT_SERVER_DRAWER_ENABLED = false
/**
 * Task #470 (artin 2026-10-11): the workspace avatar in the Home header is on by default. Only a user
 * who has no stored choice gets it; an explicit "off" is kept.
 */
internal const val DEFAULT_HOME_SERVER_AVATAR_ENABLED = true

enum class AppLocale(
    val storageKey: String,
    val languageTag: String,
    val englishLabel: String,
    val nativeLabel: String
) {
    System(
        storageKey = "system",
        languageTag = "system",
        englishLabel = "System default",
        nativeLabel = "System default"
    ),
    English(
        storageKey = "en",
        languageTag = "en",
        englishLabel = "English",
        nativeLabel = "English"
    ),
    SimplifiedChinese(
        storageKey = "zh-Hans",
        languageTag = "zh-Hans",
        englishLabel = "Simplified Chinese",
        nativeLabel = "简体中文"
    ),
    Japanese(
        storageKey = "ja",
        languageTag = "ja",
        englishLabel = "Japanese",
        nativeLabel = "日本語"
    );

    companion object {
        val Default = English

        fun fromStorageKey(value: String?): AppLocale {
            val normalized = value?.trim()?.lowercase()
            return when (normalized) {
                null, "" -> Default
                "auto", "default", System.storageKey -> System
                "zh-cn", "zh-rcn", "zh_rcn" -> SimplifiedChinese
                "ja-jp", "ja_jp" -> Japanese
                else ->
                    entries.firstOrNull {
                        it.storageKey.lowercase() == normalized || it.languageTag.lowercase() == normalized
                    } ?: Default
            }
        }
    }
}

/**
 * #85 follow-up: BCP-47 tag used by KuiklyBase KmmResource's
 * `StringDesc.LocaleType.Custom(tag)` to pick a resource folder at
 * runtime. The generated resource folder parser accepts
 * BCP-47 with `r` separator, e.g. `zh-rCN`. We use the same tags
 * as the resource folders so the runtime lookup lands on the
 * actual generated folders.
 *
 * English falls through to the `base/` folder. Simplified Chinese maps
 * to the `zh-rCN/` folder (region-based; the BCP-47 matching falls back
 * from `zh-Hans` script-only to `zh-rCN` region-only by language match),
 * while Japanese maps directly to the `ja/` folder.
 */
val AppLocale.kmmResourceLocaleTag: String?
    get() =
        when (this) {
            AppLocale.System -> null
            AppLocale.English -> "en"
            AppLocale.SimplifiedChinese -> "zh-CN"
            AppLocale.Japanese -> "ja"
        }

val AppLocale.stringDescLocaleType: StringDesc.LocaleType
    get() =
        when (this) {
            AppLocale.System -> StringDesc.LocaleType.System
            AppLocale.English,
            AppLocale.SimplifiedChinese,
            AppLocale.Japanese -> StringDesc.LocaleType.Custom(requireNotNull(kmmResourceLocaleTag))
        }

/**
 * The BCP 47 tag of the language the app UI is actually shown in. An explicit
 * choice is its own tag; [AppLocale.System] takes the catalog the string lookup
 * selects from the device's preferred languages ([raftSystemResourceLocale]), so
 * the tag always names the language the strings are shown in.
 */
fun AppLocale.displayLanguageTag(systemLanguages: List<String>): String =
    when (this) {
        AppLocale.English,
        AppLocale.SimplifiedChinese,
        AppLocale.Japanese -> languageTag
        AppLocale.System ->
            when (raftSystemResourceLocale(systemLanguages).language) {
                "zh" -> AppLocale.SimplifiedChinese.languageTag
                "ja" -> AppLocale.Japanese.languageTag
                else -> AppLocale.English.languageTag
            }
    }

/** [displayLanguageTag] for a single device language (null: unknown). */
fun AppLocale.displayLanguageTag(systemLanguage: String?): String = displayLanguageTag(listOfNotNull(systemLanguage))

data class LocaleSnapshot(
    val current: AppLocale,
    val supported: List<AppLocale> = AppLocale.entries
)

interface LocalePreferenceStore {
    fun readLocaleSync(): AppLocale
    suspend fun readLocale(): AppLocale
    suspend fun saveLocale(locale: AppLocale)
}

class KeyValueLocalePreferenceStore(
    private val store: SharedPreferenceStore
) : LocalePreferenceStore {
    override fun readLocaleSync(): AppLocale {
        val storedLocale = store.getString(KEY_LOCALE)
        return if (storedLocale.isNullOrBlank()) {
            AppLocale.System
        } else {
            AppLocale.fromStorageKey(storedLocale)
        }
    }

    override suspend fun readLocale(): AppLocale = readLocaleSync()

    override suspend fun saveLocale(locale: AppLocale) {
        store.putString(KEY_LOCALE, locale.storageKey)
    }

    private companion object {
        const val KEY_LOCALE = "locale:app_locale"
    }
}

interface LocaleRepository {
    suspend fun getLocale(): LocaleSnapshot
    suspend fun selectLocale(locale: AppLocale): LocaleSnapshot
}

class PersistentLocaleRepository(
    private val store: LocalePreferenceStore
) : LocaleRepository {
    override suspend fun getLocale(): LocaleSnapshot =
        LocaleSnapshot(current = store.readLocale())

    override suspend fun selectLocale(locale: AppLocale): LocaleSnapshot {
        store.saveLocale(locale)
        return LocaleSnapshot(current = locale)
    }
}

enum class MyMessageAlignmentPreference(val storageKey: String) {
    Left("left"),
    Right("right");

    companion object {
        val Default = Left

        fun fromStorageKey(value: String?): MyMessageAlignmentPreference =
            entries.firstOrNull { it.storageKey == value } ?: Default
    }
}

const val DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN = true

/** Task status badges on Activity rows (Settings > Mobile); on unless the user turned it off (1.13.0, artin). */
const val DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED = true

enum class TranslationModePreference(val storageKey: String) {
    Automatic("automatic"),
    Manual("manual"),
    Original("original");

    companion object {
        val Default: TranslationModePreference = Manual

        fun fromStorageKey(value: String?): TranslationModePreference =
            entries.firstOrNull { it.storageKey == value } ?: Default
    }
}

enum class LinkOpenMode(val storageKey: String) {
    SystemBrowser("system"),
    InAppBrowser("in_app");

    companion object {
        val Default: LinkOpenMode = SystemBrowser

        fun fromStorageKey(value: String?): LinkOpenMode =
            entries.firstOrNull { it.storageKey == value } ?: Default
    }
}

interface LinkOpenPreferenceStore {
    fun readLinkOpenModeSync(): LinkOpenMode
    suspend fun readLinkOpenMode(): LinkOpenMode
    suspend fun saveLinkOpenMode(mode: LinkOpenMode)
}

class KeyValueLinkOpenPreferenceStore(
    private val store: SharedPreferenceStore
) : LinkOpenPreferenceStore {
    override fun readLinkOpenModeSync(): LinkOpenMode =
        LinkOpenMode.fromStorageKey(store.getString(KEY_LINK_OPEN_MODE))

    override suspend fun readLinkOpenMode(): LinkOpenMode =
        readLinkOpenModeSync()

    override suspend fun saveLinkOpenMode(mode: LinkOpenMode) {
        store.putString(KEY_LINK_OPEN_MODE, mode.storageKey)
    }

    private companion object {
        const val KEY_LINK_OPEN_MODE = "navigation:link_open_mode"
    }
}

interface LinkOpenPreferenceRepository {
    fun getLinkOpenModeSync(): LinkOpenMode
    suspend fun getLinkOpenMode(): LinkOpenMode
    suspend fun selectLinkOpenMode(mode: LinkOpenMode): LinkOpenMode
}

class PersistentLinkOpenPreferenceRepository(
    private val store: LinkOpenPreferenceStore
) : LinkOpenPreferenceRepository {
    override fun getLinkOpenModeSync(): LinkOpenMode =
        store.readLinkOpenModeSync()

    override suspend fun getLinkOpenMode(): LinkOpenMode =
        store.readLinkOpenMode()

    override suspend fun selectLinkOpenMode(mode: LinkOpenMode): LinkOpenMode {
        store.saveLinkOpenMode(mode)
        return mode
    }
}

data class ChatLayoutPreferenceSnapshot(
    val myMessageAlignment: MyMessageAlignmentPreference,
    val supportedAlignments: List<MyMessageAlignmentPreference> =
        MyMessageAlignmentPreference.entries,
    val desktopModeOnLargeScreen: Boolean = DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN,
    val compactConsecutiveMessages: Boolean = true,
    val longMessageCollapseEnabled: Boolean = RAFT_LONG_MESSAGE_COLLAPSE_DEFAULT_ENABLED,
    val newMessageMenuEnabled: Boolean = false,
    val conversationForwardGestureEnabled: Boolean = false,
    val foregroundMessageBannersEnabled: Boolean = false,
    val activityTaskStatusBadgesEnabled: Boolean = DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED,
    val activitySenderAvatarsEnabled: Boolean = false,
    val composerSendKeyEnabled: Boolean = false,
    val multiShellHostEnabled: Boolean = false,
    val serverDrawerEnabled: Boolean = DEFAULT_SERVER_DRAWER_ENABLED,
    val newDesignSettingsEnabled: Boolean = false,
    val homeServerAvatarEnabled: Boolean = DEFAULT_HOME_SERVER_AVATAR_ENABLED,
    val translationMode: TranslationModePreference = TranslationModePreference.Default,
    val translationTargetLanguage: String = TranslationTargetLanguages.Browser,
    val supportedTranslationModes: List<TranslationModePreference> =
        TranslationModePreference.entries,
)

interface ChatLayoutPreferenceStore {
    fun readMyMessageAlignmentSync(): MyMessageAlignmentPreference
    suspend fun readMyMessageAlignment(): MyMessageAlignmentPreference
    suspend fun saveMyMessageAlignment(alignment: MyMessageAlignmentPreference)
    fun readDesktopModeOnLargeScreenSync(): Boolean
    suspend fun readDesktopModeOnLargeScreen(): Boolean
    suspend fun saveDesktopModeOnLargeScreen(enabled: Boolean)
    fun readCompactConsecutiveMessagesSync(): Boolean
    suspend fun readCompactConsecutiveMessages(): Boolean
    suspend fun saveCompactConsecutiveMessages(enabled: Boolean)
    fun readLongMessageCollapseEnabledSync(): Boolean
    suspend fun readLongMessageCollapseEnabled(): Boolean
    suspend fun saveLongMessageCollapseEnabled(enabled: Boolean)
    fun readNewMessageMenuEnabledSync(): Boolean
    suspend fun readNewMessageMenuEnabled(): Boolean
    suspend fun saveNewMessageMenuEnabled(enabled: Boolean)
    fun readConversationForwardGestureEnabledSync(): Boolean
    suspend fun readConversationForwardGestureEnabled(): Boolean
    suspend fun saveConversationForwardGestureEnabled(enabled: Boolean)
    fun readForegroundMessageBannersEnabledSync(): Boolean
    suspend fun readForegroundMessageBannersEnabled(): Boolean
    suspend fun saveForegroundMessageBannersEnabled(enabled: Boolean)
    fun readActivityTaskStatusBadgesEnabledSync(): Boolean
    suspend fun readActivityTaskStatusBadgesEnabled(): Boolean
    suspend fun saveActivityTaskStatusBadgesEnabled(enabled: Boolean)
    fun readActivitySenderAvatarsEnabledSync(): Boolean
    suspend fun readActivitySenderAvatarsEnabled(): Boolean
    suspend fun saveActivitySenderAvatarsEnabled(enabled: Boolean)
    fun readComposerSendKeyEnabledSync(): Boolean
    suspend fun readComposerSendKeyEnabled(): Boolean
    suspend fun saveComposerSendKeyEnabled(enabled: Boolean)
    fun readMultiShellHostEnabledSync(): Boolean
    suspend fun readMultiShellHostEnabled(): Boolean
    suspend fun saveMultiShellHostEnabled(enabled: Boolean)
    fun readServerDrawerEnabledSync(): Boolean
    suspend fun readServerDrawerEnabled(): Boolean
    suspend fun saveServerDrawerEnabled(enabled: Boolean)
    fun readNewDesignSettingsEnabledSync(): Boolean
    suspend fun readNewDesignSettingsEnabled(): Boolean
    suspend fun saveNewDesignSettingsEnabled(enabled: Boolean)
    fun readHomeServerAvatarEnabledSync(): Boolean
    suspend fun readHomeServerAvatarEnabled(): Boolean
    suspend fun saveHomeServerAvatarEnabled(enabled: Boolean)
    fun readTranslationModeSync(): TranslationModePreference
    suspend fun readTranslationMode(): TranslationModePreference
    suspend fun saveTranslationMode(mode: TranslationModePreference)
    fun readTranslationTargetLanguageSync(): String
    suspend fun readTranslationTargetLanguage(): String
    suspend fun saveTranslationTargetLanguage(language: String)
}

class KeyValueChatLayoutPreferenceStore(
    private val store: SharedPreferenceStore
) : ChatLayoutPreferenceStore {
    override fun readMyMessageAlignmentSync(): MyMessageAlignmentPreference =
        MyMessageAlignmentPreference.fromStorageKey(
            store.getString(KEY_MY_MESSAGE_ALIGNMENT)
        )

    override suspend fun readMyMessageAlignment(): MyMessageAlignmentPreference =
        readMyMessageAlignmentSync()

    override suspend fun saveMyMessageAlignment(alignment: MyMessageAlignmentPreference) {
        store.putString(KEY_MY_MESSAGE_ALIGNMENT, alignment.storageKey)
    }

    override fun readDesktopModeOnLargeScreenSync(): Boolean =
        store.getBoolean(KEY_DESKTOP_MODE_ON_LARGE_SCREEN, DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN)

    override suspend fun readDesktopModeOnLargeScreen(): Boolean =
        readDesktopModeOnLargeScreenSync()

    override suspend fun saveDesktopModeOnLargeScreen(enabled: Boolean) {
        store.putBoolean(KEY_DESKTOP_MODE_ON_LARGE_SCREEN, enabled)
    }

    /**
     * task #37 / artin task #20: the saved width (dp) of one list column kind, read through
     * [raftListPanePreferredWidthDp]. The first read moves the pre-#20 single width into every
     * kind's own key (clamped to that kind's range) and then drops the old key, so a width someone
     * already dragged carries over.
     */
    fun readAdaptiveListPaneWidthSync(kind: RaftListPaneKind): String? {
        migrateAdaptiveListPaneWidths()
        return store.getString(kind.storageKey)
    }

    fun saveAdaptiveListPaneWidth(kind: RaftListPaneKind, widthDp: String) {
        store.putString(kind.storageKey, widthDp)
    }

    private fun migrateAdaptiveListPaneWidths() {
        val legacy = store.getString(KEY_ADAPTIVE_LIST_PANE_WIDTH_DP) ?: return
        RaftListPaneKind.entries.forEach { kind ->
            raftListPaneMigratedWidth(kind, store.getString(kind.storageKey), legacy)
                ?.let { store.putString(kind.storageKey, it) }
        }
        store.putString(KEY_ADAPTIVE_LIST_PANE_WIDTH_DP, null)
    }

    override fun readCompactConsecutiveMessagesSync(): Boolean =
        store.getBoolean(KEY_COMPACT_CONSECUTIVE_MESSAGES, true)

    override suspend fun readCompactConsecutiveMessages(): Boolean =
        readCompactConsecutiveMessagesSync()

    override suspend fun saveCompactConsecutiveMessages(enabled: Boolean) {
        store.putBoolean(KEY_COMPACT_CONSECUTIVE_MESSAGES, enabled)
    }

    override fun readLongMessageCollapseEnabledSync(): Boolean =
        store.getBoolean(KEY_LONG_MESSAGE_COLLAPSE_ENABLED, RAFT_LONG_MESSAGE_COLLAPSE_DEFAULT_ENABLED)

    override suspend fun readLongMessageCollapseEnabled(): Boolean =
        readLongMessageCollapseEnabledSync()

    override suspend fun saveLongMessageCollapseEnabled(enabled: Boolean) {
        if (readLongMessageCollapseEnabledSync() != enabled) store.advanceRaftLongMessageCollapseGlobalRevision()
        store.putBoolean(KEY_LONG_MESSAGE_COLLAPSE_ENABLED, enabled)
        RaftLongMessageCollapsePreferenceRevision.bump()
    }

    override fun readNewMessageMenuEnabledSync(): Boolean =
        if (store.getString(KEY_NEW_MESSAGE_MENU_ENABLED) != null) {
            store.getBoolean(KEY_NEW_MESSAGE_MENU_ENABLED, false)
        } else {
            // Preserve the existing Beta opt-in while separating it from the
            // removed inline-selection behavior. New writes use only the new
            // menu key, so a deliberate off choice wins over this fallback.
            store.getBoolean(KEY_LEGACY_INLINE_SELECTION_TOOLBAR_ENABLED, false)
        }

    override suspend fun readNewMessageMenuEnabled(): Boolean =
        readNewMessageMenuEnabledSync()

    override suspend fun saveNewMessageMenuEnabled(enabled: Boolean) {
        store.putBoolean(KEY_NEW_MESSAGE_MENU_ENABLED, enabled)
    }

    override fun readConversationForwardGestureEnabledSync(): Boolean =
        store.getBoolean(KEY_CONVERSATION_FORWARD_GESTURE_ENABLED, false)

    override suspend fun readConversationForwardGestureEnabled(): Boolean =
        readConversationForwardGestureEnabledSync()

    override suspend fun saveConversationForwardGestureEnabled(enabled: Boolean) {
        store.putBoolean(KEY_CONVERSATION_FORWARD_GESTURE_ENABLED, enabled)
    }

    override fun readForegroundMessageBannersEnabledSync(): Boolean =
        store.getBoolean(KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED, false)

    override suspend fun readForegroundMessageBannersEnabled(): Boolean =
        readForegroundMessageBannersEnabledSync()

    override suspend fun saveForegroundMessageBannersEnabled(enabled: Boolean) {
        store.putBoolean(KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED, enabled)
    }

    override fun readActivityTaskStatusBadgesEnabledSync(): Boolean =
        store.getBoolean(KEY_ACTIVITY_TASK_STATUS_BADGES_ENABLED, DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED)

    override suspend fun readActivityTaskStatusBadgesEnabled(): Boolean =
        readActivityTaskStatusBadgesEnabledSync()

    override suspend fun saveActivityTaskStatusBadgesEnabled(enabled: Boolean) {
        store.putBoolean(KEY_ACTIVITY_TASK_STATUS_BADGES_ENABLED, enabled)
    }

    override fun readActivitySenderAvatarsEnabledSync(): Boolean =
        store.getBoolean(KEY_ACTIVITY_SENDER_AVATARS_ENABLED, false)

    override suspend fun readActivitySenderAvatarsEnabled(): Boolean =
        readActivitySenderAvatarsEnabledSync()

    override suspend fun saveActivitySenderAvatarsEnabled(enabled: Boolean) {
        store.putBoolean(KEY_ACTIVITY_SENDER_AVATARS_ENABLED, enabled)
    }

    override fun readComposerSendKeyEnabledSync(): Boolean =
        store.getBoolean(KEY_COMPOSER_SEND_KEY_ENABLED, raftPlatformComposerEnterSendsByDefault())

    override suspend fun readComposerSendKeyEnabled(): Boolean =
        readComposerSendKeyEnabledSync()

    override suspend fun saveComposerSendKeyEnabled(enabled: Boolean) {
        store.putBoolean(KEY_COMPOSER_SEND_KEY_ENABLED, enabled)
    }

    override fun readMultiShellHostEnabledSync(): Boolean =
        store.getBoolean(KEY_MULTI_SHELL_HOST_ENABLED, false)

    override suspend fun readMultiShellHostEnabled(): Boolean =
        readMultiShellHostEnabledSync()

    override suspend fun saveMultiShellHostEnabled(enabled: Boolean) {
        store.putBoolean(KEY_MULTI_SHELL_HOST_ENABLED, enabled)
    }

    override fun readServerDrawerEnabledSync(): Boolean =
        store.getBoolean(KEY_SERVER_DRAWER_ENABLED, DEFAULT_SERVER_DRAWER_ENABLED)

    override suspend fun readServerDrawerEnabled(): Boolean =
        readServerDrawerEnabledSync()

    override suspend fun saveServerDrawerEnabled(enabled: Boolean) {
        store.putBoolean(KEY_SERVER_DRAWER_ENABLED, enabled)
    }

    override fun readNewDesignSettingsEnabledSync(): Boolean = true

    override suspend fun readNewDesignSettingsEnabled(): Boolean =
        readNewDesignSettingsEnabledSync()

    override suspend fun saveNewDesignSettingsEnabled(enabled: Boolean) {
        store.putBoolean(KEY_NEW_DESIGN_SETTINGS_ENABLED, enabled)
    }

    override fun readHomeServerAvatarEnabledSync(): Boolean =
        store.getBoolean(KEY_HOME_SERVER_AVATAR_ENABLED, DEFAULT_HOME_SERVER_AVATAR_ENABLED)

    override suspend fun readHomeServerAvatarEnabled(): Boolean =
        readHomeServerAvatarEnabledSync()

    override suspend fun saveHomeServerAvatarEnabled(enabled: Boolean) {
        store.putBoolean(KEY_HOME_SERVER_AVATAR_ENABLED, enabled)
    }

    override fun readTranslationModeSync(): TranslationModePreference =
        TranslationModePreference.fromStorageKey(store.getString(KEY_TRANSLATION_MODE))

    override suspend fun readTranslationMode(): TranslationModePreference =
        readTranslationModeSync()

    override suspend fun saveTranslationMode(mode: TranslationModePreference) {
        store.putString(KEY_TRANSLATION_MODE, mode.storageKey)
    }

    override fun readTranslationTargetLanguageSync(): String {
        val stored = store.getString(KEY_TRANSLATION_TARGET_LANGUAGE)
        return TranslationTargetLanguages.normalize(stored) ?: TranslationTargetLanguages.Browser
    }

    override suspend fun readTranslationTargetLanguage(): String =
        readTranslationTargetLanguageSync()

    override suspend fun saveTranslationTargetLanguage(language: String) {
        val normalized = TranslationTargetLanguages.normalize(language)
        store.putString(KEY_TRANSLATION_TARGET_LANGUAGE, normalized ?: TranslationTargetLanguages.Browser)
    }

    private companion object {
        const val KEY_MY_MESSAGE_ALIGNMENT = "chatLayout:my_message_alignment"
        const val KEY_DESKTOP_MODE_ON_LARGE_SCREEN = "mobile:desktop_mode_on_large_screen"
        const val KEY_ADAPTIVE_LIST_PANE_WIDTH_DP = RAFT_LIST_PANE_LEGACY_STORAGE_KEY
        const val KEY_COMPACT_CONSECUTIVE_MESSAGES = "chatLayout:compact_consecutive_messages"
        const val KEY_LONG_MESSAGE_COLLAPSE_ENABLED = "chatLayout:long_message_collapse_enabled"
        const val KEY_NEW_MESSAGE_MENU_ENABLED = "mobile:new_message_menu_enabled"
        const val KEY_LEGACY_INLINE_SELECTION_TOOLBAR_ENABLED =
            "mobile:inline_selection_toolbar_enabled"
        const val KEY_CONVERSATION_FORWARD_GESTURE_ENABLED = "mobile:conversation_forward_gesture_enabled"
        const val KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED = "chatLayout:foreground_message_banners_enabled"
        const val KEY_ACTIVITY_TASK_STATUS_BADGES_ENABLED = "chatLayout:activity_task_status_badges_enabled"
        const val KEY_ACTIVITY_SENDER_AVATARS_ENABLED = "chatLayout:activity_sender_avatars_enabled"
        const val KEY_COMPOSER_SEND_KEY_ENABLED = "chatLayout:composer_send_key_enabled"
        const val KEY_MULTI_SHELL_HOST_ENABLED = "mobile:multi_shell_host_enabled"
        const val KEY_SERVER_DRAWER_ENABLED = "mobile:server_drawer_enabled"
        const val KEY_NEW_DESIGN_SETTINGS_ENABLED = "mobile:new_design_settings_enabled"
        const val KEY_HOME_SERVER_AVATAR_ENABLED = "mobile:home_server_avatar_enabled"
        const val KEY_TRANSLATION_MODE = "settings.translationMode"
        const val KEY_TRANSLATION_TARGET_LANGUAGE = "settings.translationTargetLanguage"
    }
}

interface ChatLayoutPreferenceRepository {
    suspend fun getChatLayoutPreference(): ChatLayoutPreferenceSnapshot
    suspend fun selectMyMessageAlignment(
        alignment: MyMessageAlignmentPreference
    ): ChatLayoutPreferenceSnapshot
    suspend fun setDesktopModeOnLargeScreen(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setCompactConsecutiveMessages(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setLongMessageCollapseEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setNewMessageMenuEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setConversationForwardGestureEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setForegroundMessageBannersEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setActivityTaskStatusBadgesEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setActivitySenderAvatarsEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setComposerSendKeyEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setMultiShellHostEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setServerDrawerEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setNewDesignSettingsEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setHomeServerAvatarEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setTranslationMode(mode: TranslationModePreference): ChatLayoutPreferenceSnapshot
    suspend fun setTranslationTargetLanguage(language: String): ChatLayoutPreferenceSnapshot
}

class PersistentChatLayoutPreferenceRepository(
    private val store: ChatLayoutPreferenceStore
) : ChatLayoutPreferenceRepository {
    override suspend fun getChatLayoutPreference(): ChatLayoutPreferenceSnapshot =
        ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            desktopModeOnLargeScreen = store.readDesktopModeOnLargeScreen(),
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            longMessageCollapseEnabled = store.readLongMessageCollapseEnabled(),
            newMessageMenuEnabled = store.readNewMessageMenuEnabled(),
            conversationForwardGestureEnabled = store.readConversationForwardGestureEnabled(),
            foregroundMessageBannersEnabled = store.readForegroundMessageBannersEnabled(),
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            activitySenderAvatarsEnabled = store.readActivitySenderAvatarsEnabled(),
            composerSendKeyEnabled = store.readComposerSendKeyEnabled(),
            multiShellHostEnabled = store.readMultiShellHostEnabled(),
            serverDrawerEnabled = store.readServerDrawerEnabled(),
            newDesignSettingsEnabled = store.readNewDesignSettingsEnabled(),
            homeServerAvatarEnabled = store.readHomeServerAvatarEnabled(),
            translationMode = store.readTranslationMode(),
            translationTargetLanguage = store.readTranslationTargetLanguage(),
        )

    override suspend fun selectMyMessageAlignment(
        alignment: MyMessageAlignmentPreference
    ): ChatLayoutPreferenceSnapshot {
        store.saveMyMessageAlignment(alignment)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = alignment,
            desktopModeOnLargeScreen = store.readDesktopModeOnLargeScreen(),
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            longMessageCollapseEnabled = store.readLongMessageCollapseEnabled(),
            newMessageMenuEnabled = store.readNewMessageMenuEnabled(),
            conversationForwardGestureEnabled = store.readConversationForwardGestureEnabled(),
            foregroundMessageBannersEnabled = store.readForegroundMessageBannersEnabled(),
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            activitySenderAvatarsEnabled = store.readActivitySenderAvatarsEnabled(),
            composerSendKeyEnabled = store.readComposerSendKeyEnabled(),
            multiShellHostEnabled = store.readMultiShellHostEnabled(),
            serverDrawerEnabled = store.readServerDrawerEnabled(),
            newDesignSettingsEnabled = store.readNewDesignSettingsEnabled(),
            homeServerAvatarEnabled = store.readHomeServerAvatarEnabled(),
            translationMode = store.readTranslationMode(),
            translationTargetLanguage = store.readTranslationTargetLanguage(),
        )
    }

    override suspend fun setDesktopModeOnLargeScreen(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveDesktopModeOnLargeScreen(enabled)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            desktopModeOnLargeScreen = enabled,
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            longMessageCollapseEnabled = store.readLongMessageCollapseEnabled(),
            newMessageMenuEnabled = store.readNewMessageMenuEnabled(),
            conversationForwardGestureEnabled = store.readConversationForwardGestureEnabled(),
            foregroundMessageBannersEnabled = store.readForegroundMessageBannersEnabled(),
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            activitySenderAvatarsEnabled = store.readActivitySenderAvatarsEnabled(),
            composerSendKeyEnabled = store.readComposerSendKeyEnabled(),
            multiShellHostEnabled = store.readMultiShellHostEnabled(),
            serverDrawerEnabled = store.readServerDrawerEnabled(),
            newDesignSettingsEnabled = store.readNewDesignSettingsEnabled(),
            homeServerAvatarEnabled = store.readHomeServerAvatarEnabled(),
            translationMode = store.readTranslationMode(),
            translationTargetLanguage = store.readTranslationTargetLanguage(),
        )
    }

    override suspend fun setCompactConsecutiveMessages(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveCompactConsecutiveMessages(enabled)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            desktopModeOnLargeScreen = store.readDesktopModeOnLargeScreen(),
            compactConsecutiveMessages = enabled,
            longMessageCollapseEnabled = store.readLongMessageCollapseEnabled(),
            newMessageMenuEnabled = store.readNewMessageMenuEnabled(),
            conversationForwardGestureEnabled = store.readConversationForwardGestureEnabled(),
            foregroundMessageBannersEnabled = store.readForegroundMessageBannersEnabled(),
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            activitySenderAvatarsEnabled = store.readActivitySenderAvatarsEnabled(),
            composerSendKeyEnabled = store.readComposerSendKeyEnabled(),
            multiShellHostEnabled = store.readMultiShellHostEnabled(),
            serverDrawerEnabled = store.readServerDrawerEnabled(),
            newDesignSettingsEnabled = store.readNewDesignSettingsEnabled(),
            homeServerAvatarEnabled = store.readHomeServerAvatarEnabled(),
            translationMode = store.readTranslationMode(),
            translationTargetLanguage = store.readTranslationTargetLanguage(),
        )
    }

    override suspend fun setLongMessageCollapseEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveLongMessageCollapseEnabled(enabled)
        return getChatLayoutPreference()
    }

    override suspend fun setNewMessageMenuEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveNewMessageMenuEnabled(enabled)
        return getChatLayoutPreference()
    }

    override suspend fun setConversationForwardGestureEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveConversationForwardGestureEnabled(enabled)
        return getChatLayoutPreference()
    }

    override suspend fun setForegroundMessageBannersEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveForegroundMessageBannersEnabled(enabled)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            desktopModeOnLargeScreen = store.readDesktopModeOnLargeScreen(),
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            longMessageCollapseEnabled = store.readLongMessageCollapseEnabled(),
            newMessageMenuEnabled = store.readNewMessageMenuEnabled(),
            conversationForwardGestureEnabled = store.readConversationForwardGestureEnabled(),
            foregroundMessageBannersEnabled = enabled,
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            activitySenderAvatarsEnabled = store.readActivitySenderAvatarsEnabled(),
            composerSendKeyEnabled = store.readComposerSendKeyEnabled(),
            multiShellHostEnabled = store.readMultiShellHostEnabled(),
            serverDrawerEnabled = store.readServerDrawerEnabled(),
            newDesignSettingsEnabled = store.readNewDesignSettingsEnabled(),
            homeServerAvatarEnabled = store.readHomeServerAvatarEnabled(),
            translationMode = store.readTranslationMode(),
            translationTargetLanguage = store.readTranslationTargetLanguage(),
        )
    }

    override suspend fun setActivityTaskStatusBadgesEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveActivityTaskStatusBadgesEnabled(enabled)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            desktopModeOnLargeScreen = store.readDesktopModeOnLargeScreen(),
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            longMessageCollapseEnabled = store.readLongMessageCollapseEnabled(),
            newMessageMenuEnabled = store.readNewMessageMenuEnabled(),
            conversationForwardGestureEnabled = store.readConversationForwardGestureEnabled(),
            foregroundMessageBannersEnabled = store.readForegroundMessageBannersEnabled(),
            activityTaskStatusBadgesEnabled = enabled,
            activitySenderAvatarsEnabled = store.readActivitySenderAvatarsEnabled(),
            composerSendKeyEnabled = store.readComposerSendKeyEnabled(),
            multiShellHostEnabled = store.readMultiShellHostEnabled(),
            serverDrawerEnabled = store.readServerDrawerEnabled(),
            newDesignSettingsEnabled = store.readNewDesignSettingsEnabled(),
            homeServerAvatarEnabled = store.readHomeServerAvatarEnabled(),
            translationMode = store.readTranslationMode(),
            translationTargetLanguage = store.readTranslationTargetLanguage(),
        )
    }

    override suspend fun setActivitySenderAvatarsEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveActivitySenderAvatarsEnabled(enabled)
        return getChatLayoutPreference()
    }

    override suspend fun setComposerSendKeyEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveComposerSendKeyEnabled(enabled)
        return getChatLayoutPreference()
    }

    override suspend fun setMultiShellHostEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveMultiShellHostEnabled(enabled)
        return getChatLayoutPreference()
    }

    override suspend fun setServerDrawerEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveServerDrawerEnabled(enabled)
        return getChatLayoutPreference()
    }

    override suspend fun setNewDesignSettingsEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveNewDesignSettingsEnabled(enabled)
        return getChatLayoutPreference()
    }

    override suspend fun setHomeServerAvatarEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveHomeServerAvatarEnabled(enabled)
        return getChatLayoutPreference()
    }

    override suspend fun setTranslationMode(mode: TranslationModePreference): ChatLayoutPreferenceSnapshot {
        store.saveTranslationMode(mode)
        return ChatLayoutPreferenceSnapshot(
            myMessageAlignment = store.readMyMessageAlignment(),
            desktopModeOnLargeScreen = store.readDesktopModeOnLargeScreen(),
            compactConsecutiveMessages = store.readCompactConsecutiveMessages(),
            longMessageCollapseEnabled = store.readLongMessageCollapseEnabled(),
            newMessageMenuEnabled = store.readNewMessageMenuEnabled(),
            conversationForwardGestureEnabled = store.readConversationForwardGestureEnabled(),
            foregroundMessageBannersEnabled = store.readForegroundMessageBannersEnabled(),
            activityTaskStatusBadgesEnabled = store.readActivityTaskStatusBadgesEnabled(),
            activitySenderAvatarsEnabled = store.readActivitySenderAvatarsEnabled(),
            composerSendKeyEnabled = store.readComposerSendKeyEnabled(),
            multiShellHostEnabled = store.readMultiShellHostEnabled(),
            serverDrawerEnabled = store.readServerDrawerEnabled(),
            newDesignSettingsEnabled = store.readNewDesignSettingsEnabled(),
            homeServerAvatarEnabled = store.readHomeServerAvatarEnabled(),
            translationMode = mode,
            translationTargetLanguage = store.readTranslationTargetLanguage(),
        )
    }

    override suspend fun setTranslationTargetLanguage(language: String): ChatLayoutPreferenceSnapshot {
        store.saveTranslationTargetLanguage(language)
        return getChatLayoutPreference()
    }

}

interface TasksViewPreferenceRepository {
    fun getTasksViewModeSync(defaultMode: TasksViewMode): TasksViewMode
    suspend fun getTasksViewMode(defaultMode: TasksViewMode): TasksViewMode
    fun selectTasksViewModeSync(mode: TasksViewMode): TasksViewMode
    suspend fun selectTasksViewMode(mode: TasksViewMode): TasksViewMode
}

class KeyValueTasksViewPreferenceRepository(
    private val store: SharedPreferenceStore
) : TasksViewPreferenceRepository {
    override fun getTasksViewModeSync(defaultMode: TasksViewMode): TasksViewMode =
        when (store.getString(KEY_TASKS_VIEW_MODE)) {
            TASKS_VIEW_MODE_BOARD -> TasksViewMode.Board
            TASKS_VIEW_MODE_LIST -> TasksViewMode.List
            else -> defaultMode
        }

    override suspend fun getTasksViewMode(defaultMode: TasksViewMode): TasksViewMode =
        getTasksViewModeSync(defaultMode)

    override fun selectTasksViewModeSync(mode: TasksViewMode): TasksViewMode {
        store.putString(
            KEY_TASKS_VIEW_MODE,
            when (mode) {
                TasksViewMode.Board -> TASKS_VIEW_MODE_BOARD
                TasksViewMode.List -> TASKS_VIEW_MODE_LIST
            }
        )
        return mode
    }

    override suspend fun selectTasksViewMode(mode: TasksViewMode): TasksViewMode =
        selectTasksViewModeSync(mode)

    private companion object {
        const val KEY_TASKS_VIEW_MODE = "tasks:viewMode"
        const val TASKS_VIEW_MODE_BOARD = "board"
        const val TASKS_VIEW_MODE_LIST = "list"
    }
}

class KeyValueRouterPreferenceRepository(
    private val store: SharedPreferenceStore
) {
    fun saveLastRouteInput(input: String) {
        store.putString(KEY_LAST_ROUTE_INPUT, input)
    }

    fun readLastRouteInput(): String? =
        store.getString(KEY_LAST_ROUTE_INPUT)

    private companion object {
        const val KEY_LAST_ROUTE_INPUT = "router:last_input"
    }
}

/**
 * Settings switch "Bottom-up message list" (artin 2026-10-11: the switch lives in Settings, the
 * `mobile_thread_reverse_layout_v0` flag only sets its default). Three states under
 * [THREAD_REVERSE_LAYOUT_PREFERENCE_KEY]: absent = follow the default, "true" / "false" = the user's choice.
 */
interface ThreadReverseLayoutPreferenceStore {
    /** The user's explicit choice, or null while the switch follows the default. */
    fun readThreadReverseLayoutUserChoiceSync(): Boolean?
    suspend fun saveThreadReverseLayoutUserChoice(enabled: Boolean)
}

class KeyValueThreadReverseLayoutPreferenceStore(
    private val store: SharedPreferenceStore
) : ThreadReverseLayoutPreferenceStore {
    override fun readThreadReverseLayoutUserChoiceSync(): Boolean? =
        when (store.getString(THREAD_REVERSE_LAYOUT_PREFERENCE_KEY)?.trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    override suspend fun saveThreadReverseLayoutUserChoice(enabled: Boolean) {
        store.putBoolean(THREAD_REVERSE_LAYOUT_PREFERENCE_KEY, enabled)
    }
}

const val THREAD_REVERSE_LAYOUT_PREFERENCE_KEY = "chatLayout:thread_reverse_layout_enabled"

/**
 * Per-device choice of how much message content a push notification may show.
 *
 * This is a local preference, not a server setting: the push registration and the notification
 * poster read it directly, so it never passes through the settings repository.
 * A missing value means [PUSH_PREVIEW_MODE_DEFAULT] (the pre-existing behaviour). A stored value
 * that cannot be read means [PUSH_PREVIEW_MODE_UNREADABLE]: this setting exists to keep message
 * text out of notifications, so a corrupt preference must fail closed rather than silently start
 * showing bodies again. The server applies the same split for the same reason.
 */
interface PushPreviewModePreferenceStore {
    fun readPushPreviewModeSync(): String
    suspend fun savePushPreviewMode(wireName: String)
}

class KeyValuePushPreviewModePreferenceStore(
    private val store: SharedPreferenceStore
) : PushPreviewModePreferenceStore {
    override fun readPushPreviewModeSync(): String {
        val stored = store.getString(PUSH_PREVIEW_MODE_PREFERENCE_KEY)
        if (stored == null) return PUSH_PREVIEW_MODE_DEFAULT
        // Stored but unrecognizable: fail closed, so corruption cannot turn previews back on.
        return PushPreviewMode.fromWire(stored)?.wireName ?: PUSH_PREVIEW_MODE_UNREADABLE
    }

    override suspend fun savePushPreviewMode(wireName: String) {
        // Only canonical wire values are ever persisted.
        val canonical = PushPreviewMode.fromWire(wireName)?.wireName ?: return
        store.putString(PUSH_PREVIEW_MODE_PREFERENCE_KEY, canonical)
    }
}

const val PUSH_PREVIEW_MODE_PREFERENCE_KEY = "notifications:push_preview_mode"
const val PUSH_PREVIEW_MODE_DEFAULT = "full"

/** What a stored-but-unreadable preference resolves to: hide the body rather than show it. */
const val PUSH_PREVIEW_MODE_UNREADABLE = "hidden"

/**
 * Whether the app already asked for the system notification permission on its own (once, after
 * sign-in; artin 10-27). Kept across sign-outs: the automatic prompt never repeats, Settings ›
 * Notifications stays the way to ask again.
 *
 * Install state, not a UI preference: it lives in [RAFT_DEVICE_STATE_MMKV_NAMESPACE], which
 * backup and device transfer never copy (a restored "asked" without the permission, which is not
 * restored, would never ask again; sindresorhus #3820).
 */
interface NotificationPermissionPromptPreferenceStore {
    fun readAutoPromptAskedSync(): Boolean
    fun markAutoPromptAsked()

    /** The request never reached the system (no activity to show it on): the next launch may ask. */
    fun clearAutoPromptAsked()
}

class KeyValueNotificationPermissionPromptPreferenceStore(
    private val store: SharedPreferenceStore
) : NotificationPermissionPromptPreferenceStore {
    override fun readAutoPromptAskedSync(): Boolean =
        store.getBoolean(NOTIFICATION_PERMISSION_AUTO_PROMPT_ASKED_KEY, defaultValue = false)

    override fun markAutoPromptAsked() {
        store.putBoolean(NOTIFICATION_PERMISSION_AUTO_PROMPT_ASKED_KEY, true)
    }

    override fun clearAutoPromptAsked() {
        store.putString(NOTIFICATION_PERMISSION_AUTO_PROMPT_ASKED_KEY, null)
    }
}

/**
 * App-scope namespace for state that belongs to this install of the app on this device and must
 * not come back from a backup or a device transfer (Android excludes it in both backup rule
 * files). Not for UI preferences, which stay in the backed-up [RaftLocalPreferenceStore] namespace.
 */
const val RAFT_DEVICE_STATE_MMKV_NAMESPACE = "slockDeviceState"

/** The [RAFT_DEVICE_STATE_MMKV_NAMESPACE] store. Only hosts that install it may call this (Android, desktop). */
fun raftDeviceStateKeyValueStore(): SharedPreferenceStore =
    RaftSharedKeyValueStoreProvider.create(
        namespace = RAFT_DEVICE_STATE_MMKV_NAMESPACE,
        storageName = "device state"
    ).store

const val NOTIFICATION_PERMISSION_AUTO_PROMPT_ASKED_KEY = "notifications:permission_auto_prompt_asked"

/**
 * Task #22: the agent-profile tab order the user set (on web or here) for each server, kept so the
 * profile's first frame already shows that order. The server copy stays canonical; this is only the
 * last known value, refreshed on every sidebar-order load or save.
 */
class KeyValueAgentPanelTabOrderPreferenceRepository(
    private val store: SharedPreferenceStore
) : SharedAgentPanelTabOrderCache {
    override fun readOrder(serverId: String): List<String>? {
        val raw = store.getString(key(serverId)) ?: return null
        return runCatching { agentPanelTabOrderJson.decodeFromString(agentPanelTabOrderSerializer, raw) }.getOrNull()
    }

    override fun writeOrder(serverId: String, order: List<String>) {
        store.putString(key(serverId), agentPanelTabOrderJson.encodeToString(agentPanelTabOrderSerializer, order))
    }

    private fun key(serverId: String): String = "$KEY_PREFIX${serverId.trim()}"

    private companion object {
        const val KEY_PREFIX = "members:agentPanelTabOrder:"
        val agentPanelTabOrderJson = Json { ignoreUnknownKeys = true }
        val agentPanelTabOrderSerializer =
            ListSerializer(String.serializer())
    }
}

fun raftSharedPreferenceStore(): SharedPreferenceStore =
    RaftSharedKeyValueStoreProvider.create(
        namespace = RaftLocalPreferenceStore.KuiklyNamespace,
        storageName = "shared preference"
    ).store
