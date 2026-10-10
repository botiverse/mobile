package build.raft.app.data

import build.raft.app.network.REALTIME_CURL_SOCKETIO_PREFERENCE_KEY
import build.raft.app.network.SlockCurlSocketIoPolicy
import com.tencent.tmm.kmmresource.resource.desc.StringDesc

internal const val DEFAULT_SERVER_DRAWER_ENABLED = false

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
    val longMessageCollapseEnabled: Boolean = false,
    val singleTapOpensThreadEnabled: Boolean = false,
    val threadUnreadJumpEnabled: Boolean = false,
    val newMessageMenuEnabled: Boolean = false,
    val conversationForwardGestureEnabled: Boolean = false,
    val foregroundMessageBannersEnabled: Boolean = false,
    val activityTaskStatusBadgesEnabled: Boolean = DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED,
    val activitySenderAvatarsEnabled: Boolean = false,
    val composerSendKeyEnabled: Boolean = false,
    val multiShellHostEnabled: Boolean = false,
    val serverDrawerEnabled: Boolean = DEFAULT_SERVER_DRAWER_ENABLED,
    val newDesignSettingsEnabled: Boolean = false,
    val homeServerAvatarEnabled: Boolean = false,
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
    fun readSingleTapOpensThreadEnabledSync(): Boolean
    suspend fun readSingleTapOpensThreadEnabled(): Boolean
    suspend fun saveSingleTapOpensThreadEnabled(enabled: Boolean)
    fun readThreadUnreadJumpEnabledSync(): Boolean
    suspend fun readThreadUnreadJumpEnabled(): Boolean
    suspend fun saveThreadUnreadJumpEnabled(enabled: Boolean)
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
    /** task #94: persisted Slack-aligned bottom-tab order (raw string; absent = untouched). */
    fun readSlackNavTabOrderSync(): String?
    suspend fun readSlackNavTabOrder(): String?
    suspend fun saveSlackNavTabOrder(rawOrder: String?)
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

    /** task #37: the saved list column width (dp), read through [slockListPanePreferredWidthDp]. */
    fun readAdaptiveListPaneWidthSync(): String? = store.getString(KEY_ADAPTIVE_LIST_PANE_WIDTH_DP)

    fun saveAdaptiveListPaneWidth(widthDp: String) {
        store.putString(KEY_ADAPTIVE_LIST_PANE_WIDTH_DP, widthDp)
    }

    override fun readCompactConsecutiveMessagesSync(): Boolean =
        store.getBoolean(KEY_COMPACT_CONSECUTIVE_MESSAGES, true)

    override suspend fun readCompactConsecutiveMessages(): Boolean =
        readCompactConsecutiveMessagesSync()

    override suspend fun saveCompactConsecutiveMessages(enabled: Boolean) {
        store.putBoolean(KEY_COMPACT_CONSECUTIVE_MESSAGES, enabled)
    }

    override fun readLongMessageCollapseEnabledSync(): Boolean =
        store.getBoolean(KEY_LONG_MESSAGE_COLLAPSE_ENABLED, false)

    override suspend fun readLongMessageCollapseEnabled(): Boolean =
        readLongMessageCollapseEnabledSync()

    override suspend fun saveLongMessageCollapseEnabled(enabled: Boolean) {
        store.putBoolean(KEY_LONG_MESSAGE_COLLAPSE_ENABLED, enabled)
    }

    override fun readSingleTapOpensThreadEnabledSync(): Boolean =
        store.getBoolean(KEY_SINGLE_TAP_OPENS_THREAD_ENABLED, false)

    override suspend fun readSingleTapOpensThreadEnabled(): Boolean =
        readSingleTapOpensThreadEnabledSync()

    override suspend fun saveSingleTapOpensThreadEnabled(enabled: Boolean) {
        store.putBoolean(KEY_SINGLE_TAP_OPENS_THREAD_ENABLED, enabled)
    }

    override fun readThreadUnreadJumpEnabledSync(): Boolean =
        store.getBoolean(KEY_THREAD_UNREAD_JUMP_ENABLED, false)

    override suspend fun readThreadUnreadJumpEnabled(): Boolean =
        readThreadUnreadJumpEnabledSync()

    override suspend fun saveThreadUnreadJumpEnabled(enabled: Boolean) {
        store.putBoolean(KEY_THREAD_UNREAD_JUMP_ENABLED, enabled)
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
        store.getBoolean(KEY_COMPOSER_SEND_KEY_ENABLED, false)

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
        store.getBoolean(KEY_HOME_SERVER_AVATAR_ENABLED, false)

    override suspend fun readHomeServerAvatarEnabled(): Boolean =
        readHomeServerAvatarEnabledSync()

    override suspend fun saveHomeServerAvatarEnabled(enabled: Boolean) {
        store.putBoolean(KEY_HOME_SERVER_AVATAR_ENABLED, enabled)
    }

    override fun readSlackNavTabOrderSync(): String? =
        store.getString(KEY_SLACK_NAV_TAB_ORDER)?.takeIf { it.isNotBlank() }

    override suspend fun readSlackNavTabOrder(): String? =
        readSlackNavTabOrderSync()

    override suspend fun saveSlackNavTabOrder(rawOrder: String?) {
        store.putString(KEY_SLACK_NAV_TAB_ORDER, rawOrder?.takeIf { it.isNotBlank() })
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
        const val KEY_ADAPTIVE_LIST_PANE_WIDTH_DP = "mobile:adaptive_list_pane_width_dp"
        const val KEY_COMPACT_CONSECUTIVE_MESSAGES = "chatLayout:compact_consecutive_messages"
        const val KEY_LONG_MESSAGE_COLLAPSE_ENABLED = "chatLayout:long_message_collapse_enabled"
        const val KEY_SINGLE_TAP_OPENS_THREAD_ENABLED = "chatLayout:single_tap_opens_thread_enabled"
        const val KEY_THREAD_UNREAD_JUMP_ENABLED = "chatLayout:thread_unread_jump_enabled"
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
        const val KEY_SLACK_NAV_TAB_ORDER = "mobile:slack_nav_tab_order"
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
    suspend fun setSingleTapOpensThreadEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
    suspend fun setThreadUnreadJumpEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot
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
            singleTapOpensThreadEnabled = store.readSingleTapOpensThreadEnabled(),
            threadUnreadJumpEnabled = store.readThreadUnreadJumpEnabled(),
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
            singleTapOpensThreadEnabled = store.readSingleTapOpensThreadEnabled(),
            threadUnreadJumpEnabled = store.readThreadUnreadJumpEnabled(),
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
            singleTapOpensThreadEnabled = store.readSingleTapOpensThreadEnabled(),
            threadUnreadJumpEnabled = store.readThreadUnreadJumpEnabled(),
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
            singleTapOpensThreadEnabled = store.readSingleTapOpensThreadEnabled(),
            threadUnreadJumpEnabled = store.readThreadUnreadJumpEnabled(),
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

    override suspend fun setSingleTapOpensThreadEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveSingleTapOpensThreadEnabled(enabled)
        return getChatLayoutPreference()
    }

    override suspend fun setThreadUnreadJumpEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot {
        store.saveThreadUnreadJumpEnabled(enabled)
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
            singleTapOpensThreadEnabled = store.readSingleTapOpensThreadEnabled(),
            threadUnreadJumpEnabled = store.readThreadUnreadJumpEnabled(),
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
            singleTapOpensThreadEnabled = store.readSingleTapOpensThreadEnabled(),
            threadUnreadJumpEnabled = store.readThreadUnreadJumpEnabled(),
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
            singleTapOpensThreadEnabled = store.readSingleTapOpensThreadEnabled(),
            threadUnreadJumpEnabled = store.readThreadUnreadJumpEnabled(),
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
 * Task #154: the Settings switch "realtime over the new network stack (curl)". Three states under
 * [REALTIME_CURL_SOCKETIO_PREFERENCE_KEY]: absent = follow the server flag, "true" / "false" = the
 * user's explicit choice (written with [putBoolean], so Android can read the raw MMKV string).
 */
interface RealtimeTransportPreferenceStore {
    /** The user's explicit choice, or null while the switch follows the server flag. */
    fun readCurlSocketIoUserChoiceSync(): Boolean?
    suspend fun saveCurlSocketIoUserChoice(enabled: Boolean)
}

class KeyValueRealtimeTransportPreferenceStore(
    private val store: SharedPreferenceStore
) : RealtimeTransportPreferenceStore {
    override fun readCurlSocketIoUserChoiceSync(): Boolean? =
        SlockCurlSocketIoPolicy.parseUserChoice(store.getString(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY))

    override suspend fun saveCurlSocketIoUserChoice(enabled: Boolean) {
        store.putBoolean(REALTIME_CURL_SOCKETIO_PREFERENCE_KEY, enabled)
    }
}

fun slockSharedPreferenceStore(): SharedPreferenceStore =
    SlockSharedKeyValueStoreProvider.create(
        namespace = SlockLocalPreferenceStore.KuiklyNamespace,
        storageName = "shared preference"
    ).store
