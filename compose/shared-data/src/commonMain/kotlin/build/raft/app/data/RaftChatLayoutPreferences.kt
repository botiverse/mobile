package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.has
import build.raft.app.optBoolean
import build.raft.app.optString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


private const val CHAT_LAYOUT_PREFERENCE_SNAPSHOT_EVENT = "slock.chatLayoutPreferenceSnapshotChanged"
private const val KEY_MY_MESSAGE_ALIGNMENT = "myMessageAlignment"
private const val KEY_DESKTOP_MODE_ON_LARGE_SCREEN = "desktopModeOnLargeScreen"
private const val KEY_COMPACT_CONSECUTIVE_MESSAGES = "compactConsecutiveMessages"
private const val KEY_LONG_MESSAGE_COLLAPSE_ENABLED = "longMessageCollapseEnabled"
private const val KEY_NEW_MESSAGE_MENU_ENABLED = "newMessageMenuEnabled"
private const val KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED = "foregroundMessageBannersEnabled"
private const val KEY_COMPOSER_SEND_KEY_ENABLED = "composerSendKeyEnabled"
private const val KEY_MULTI_SHELL_HOST_ENABLED = "multiShellHostEnabled"
private const val KEY_SERVER_DRAWER_ENABLED = "serverDrawerEnabled"
private const val KEY_TRANSLATION_MODE = "translationMode"
private const val KEY_TRANSLATION_TARGET_LANGUAGE = "translationTargetLanguage"
private val CHAT_LAYOUT_PREFERENCE_SNAPSHOT_KEYS =
    setOf(
        KEY_MY_MESSAGE_ALIGNMENT,
        KEY_COMPACT_CONSECUTIVE_MESSAGES,
        KEY_LONG_MESSAGE_COLLAPSE_ENABLED,
        KEY_NEW_MESSAGE_MENU_ENABLED,
        KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED,
        KEY_COMPOSER_SEND_KEY_ENABLED,
        KEY_MULTI_SHELL_HOST_ENABLED,
        KEY_SERVER_DRAWER_ENABLED,
        KEY_TRANSLATION_MODE,
        KEY_TRANSLATION_TARGET_LANGUAGE,
    )

/**
 * App-process propagation for chat-layout preferences whose runtime state is retained per shell.
 * Durable truth remains in [ChatLayoutPreferenceRepository].
 */
class RaftChatLayoutPreferenceEvents(
    private val eventBus: RaftEventBus,
    private val source: String = "shared"
) {
    fun addPreferenceListener(
        onChanged: (ChatLayoutPreferenceSnapshot) -> Unit
    ): RealtimeSubscription =
        eventBus.observe(CHAT_LAYOUT_PREFERENCE_SNAPSHOT_EVENT) { data ->
            data?.toChatLayoutPreferenceSnapshotOrNull()?.let(onChanged)
        }

    fun postPreferenceChanged(snapshot: ChatLayoutPreferenceSnapshot) {
        eventBus.post(
            CHAT_LAYOUT_PREFERENCE_SNAPSHOT_EVENT,
            snapshot.toPreferenceEventData(),
            source = source
        )
    }
}

fun raftChatLayoutPreferenceRepository(
    delegate: ChatLayoutPreferenceRepository,
    events: RaftChatLayoutPreferenceEvents
): ChatLayoutPreferenceRepository =
    object : ChatLayoutPreferenceRepository by delegate {
        private suspend fun persistAndPublish(
            operation: suspend () -> ChatLayoutPreferenceSnapshot
        ): ChatLayoutPreferenceSnapshot =
            operation().also(events::postPreferenceChanged)

        override suspend fun selectMyMessageAlignment(
            alignment: MyMessageAlignmentPreference
        ): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.selectMyMessageAlignment(alignment) }

        override suspend fun setDesktopModeOnLargeScreen(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setDesktopModeOnLargeScreen(enabled) }

        override suspend fun setCompactConsecutiveMessages(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setCompactConsecutiveMessages(enabled) }

        override suspend fun setLongMessageCollapseEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setLongMessageCollapseEnabled(enabled) }

        override suspend fun setNewMessageMenuEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setNewMessageMenuEnabled(enabled) }

        override suspend fun setForegroundMessageBannersEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setForegroundMessageBannersEnabled(enabled) }

        override suspend fun setComposerSendKeyEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setComposerSendKeyEnabled(enabled) }

        override suspend fun setMultiShellHostEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setMultiShellHostEnabled(enabled) }

        override suspend fun setServerDrawerEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setServerDrawerEnabled(enabled) }

        override suspend fun setTranslationMode(mode: TranslationModePreference): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setTranslationMode(mode) }

        override suspend fun setTranslationTargetLanguage(language: String): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setTranslationTargetLanguage(language) }

    }

private fun ChatLayoutPreferenceSnapshot.toPreferenceEventData(): JsonObject =
    buildJsonObject {
        put(KEY_MY_MESSAGE_ALIGNMENT, myMessageAlignment.storageKey)
        put(KEY_DESKTOP_MODE_ON_LARGE_SCREEN, desktopModeOnLargeScreen)
        put(KEY_COMPACT_CONSECUTIVE_MESSAGES, compactConsecutiveMessages)
        put(KEY_LONG_MESSAGE_COLLAPSE_ENABLED, longMessageCollapseEnabled)
        put(KEY_NEW_MESSAGE_MENU_ENABLED, newMessageMenuEnabled)
        put(KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED, foregroundMessageBannersEnabled)
        put(KEY_COMPOSER_SEND_KEY_ENABLED, composerSendKeyEnabled)
        put(KEY_MULTI_SHELL_HOST_ENABLED, multiShellHostEnabled)
        put(KEY_SERVER_DRAWER_ENABLED, serverDrawerEnabled)
        put(KEY_TRANSLATION_MODE, translationMode.storageKey)
        put(KEY_TRANSLATION_TARGET_LANGUAGE, translationTargetLanguage)
    }

@RaftCoreInternalApi fun JsonObject.toChatLayoutPreferenceSnapshotOrNull(): ChatLayoutPreferenceSnapshot? {
    if (CHAT_LAYOUT_PREFERENCE_SNAPSHOT_KEYS.any { !has(it) }) return null
    return ChatLayoutPreferenceSnapshot(
        myMessageAlignment = MyMessageAlignmentPreference.fromStorageKey(optString(KEY_MY_MESSAGE_ALIGNMENT)),
        desktopModeOnLargeScreen = optBoolean(KEY_DESKTOP_MODE_ON_LARGE_SCREEN, DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN),
        compactConsecutiveMessages = optBoolean(KEY_COMPACT_CONSECUTIVE_MESSAGES, true),
        longMessageCollapseEnabled = optBoolean(KEY_LONG_MESSAGE_COLLAPSE_ENABLED, RAFT_LONG_MESSAGE_COLLAPSE_DEFAULT_ENABLED),
        newMessageMenuEnabled = optBoolean(KEY_NEW_MESSAGE_MENU_ENABLED, false),
        foregroundMessageBannersEnabled = optBoolean(KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED, false),
        composerSendKeyEnabled = optBoolean(KEY_COMPOSER_SEND_KEY_ENABLED, false),
        multiShellHostEnabled = optBoolean(KEY_MULTI_SHELL_HOST_ENABLED, false),
        serverDrawerEnabled = optBoolean(KEY_SERVER_DRAWER_ENABLED, false),
        translationMode = TranslationModePreference.fromStorageKey(optString(KEY_TRANSLATION_MODE)),
        translationTargetLanguage =
            optString(KEY_TRANSLATION_TARGET_LANGUAGE).ifBlank { TranslationTargetLanguages.Browser },
    )
}

/** Prevents an in-flight repository seed from overwriting a newer process event. */
@RaftCoreInternalApi class ChatLayoutPreferenceSnapshotRuntimeAuthority(
    private val onEffectivePreferenceChanged: (ChatLayoutPreferenceSnapshot) -> Unit
) {
    private var observedProcessChange: Boolean = false

    fun seedFromRepository(snapshot: ChatLayoutPreferenceSnapshot) {
        if (observedProcessChange) return
        onEffectivePreferenceChanged(snapshot)
    }

    fun applyProcessPreferenceChange(snapshot: ChatLayoutPreferenceSnapshot) {
        observedProcessChange = true
        onEffectivePreferenceChanged(snapshot)
    }
}
