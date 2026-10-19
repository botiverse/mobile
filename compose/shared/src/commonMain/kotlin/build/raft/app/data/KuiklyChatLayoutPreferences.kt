package build.raft.app.data

import build.raft.app.has
import build.raft.app.optBoolean
import build.raft.app.optString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


private const val CHAT_LAYOUT_PREFERENCE_EVENT = "slock.chatLayoutPreferenceChanged"
private const val CHAT_LAYOUT_PREFERENCE_SNAPSHOT_EVENT = "slock.chatLayoutPreferenceSnapshotChanged"
private const val KEY_CONVERSATION_FORWARD_GESTURE_ENABLED =
    "conversationForwardGestureEnabled"
private const val KEY_MY_MESSAGE_ALIGNMENT = "myMessageAlignment"
private const val KEY_DESKTOP_MODE_ON_LARGE_SCREEN = "desktopModeOnLargeScreen"
private const val KEY_COMPACT_CONSECUTIVE_MESSAGES = "compactConsecutiveMessages"
private const val KEY_LONG_MESSAGE_COLLAPSE_ENABLED = "longMessageCollapseEnabled"
private const val KEY_SINGLE_TAP_OPENS_THREAD_ENABLED = "singleTapOpensThreadEnabled"
private const val KEY_THREAD_UNREAD_JUMP_ENABLED = "threadUnreadJumpEnabled"
private const val KEY_NEW_MESSAGE_MENU_ENABLED = "newMessageMenuEnabled"
private const val KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED = "foregroundMessageBannersEnabled"
private const val KEY_ACTIVITY_TASK_STATUS_BADGES_ENABLED = "activityTaskStatusBadgesEnabled"
private const val KEY_ACTIVITY_SENDER_AVATARS_ENABLED = "activitySenderAvatarsEnabled"
private const val KEY_COMPOSER_SEND_KEY_ENABLED = "composerSendKeyEnabled"
private const val KEY_MULTI_SHELL_HOST_ENABLED = "multiShellHostEnabled"
private const val KEY_SERVER_DRAWER_ENABLED = "serverDrawerEnabled"
private const val KEY_NEW_DESIGN_SETTINGS_ENABLED = "newDesignSettingsEnabled"
private const val KEY_HOME_SERVER_AVATAR_ENABLED = "homeServerAvatarEnabled"
private const val KEY_TRANSLATION_MODE = "translationMode"
private const val KEY_TRANSLATION_TARGET_LANGUAGE = "translationTargetLanguage"
private val CHAT_LAYOUT_PREFERENCE_SNAPSHOT_KEYS =
    setOf(
        KEY_MY_MESSAGE_ALIGNMENT,
        KEY_COMPACT_CONSECUTIVE_MESSAGES,
        KEY_LONG_MESSAGE_COLLAPSE_ENABLED,
        KEY_SINGLE_TAP_OPENS_THREAD_ENABLED,
        KEY_THREAD_UNREAD_JUMP_ENABLED,
        KEY_NEW_MESSAGE_MENU_ENABLED,
        KEY_CONVERSATION_FORWARD_GESTURE_ENABLED,
        KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED,
        KEY_ACTIVITY_TASK_STATUS_BADGES_ENABLED,
        KEY_ACTIVITY_SENDER_AVATARS_ENABLED,
        KEY_COMPOSER_SEND_KEY_ENABLED,
        KEY_MULTI_SHELL_HOST_ENABLED,
        KEY_SERVER_DRAWER_ENABLED,
        KEY_NEW_DESIGN_SETTINGS_ENABLED,
        KEY_HOME_SERVER_AVATAR_ENABLED,
        KEY_TRANSLATION_MODE,
        KEY_TRANSLATION_TARGET_LANGUAGE,
    )

/**
 * App-process propagation for chat-layout preferences whose runtime state is retained per shell.
 * Durable truth remains in [ChatLayoutPreferenceRepository]; this event only reconciles already
 * mounted AppShells so an app-scoped OFF cannot leave another window's native gate alive.
 */
class KuiklyChatLayoutPreferenceEvents(
    private val eventBus: SlockEventBus,
    private val source: String = "shared"
) {
    fun addConversationForwardGestureListener(
        onChanged: (Boolean) -> Unit
    ): RealtimeSubscription =
        eventBus.observe(CHAT_LAYOUT_PREFERENCE_EVENT) { data ->
            if (data == null || !data.has(KEY_CONVERSATION_FORWARD_GESTURE_ENABLED)) {
                return@observe
            }
            onChanged(data.optBoolean(KEY_CONVERSATION_FORWARD_GESTURE_ENABLED, false))
        }

    fun addPreferenceListener(
        onChanged: (ChatLayoutPreferenceSnapshot) -> Unit
    ): RealtimeSubscription =
        eventBus.observe(CHAT_LAYOUT_PREFERENCE_SNAPSHOT_EVENT) { data ->
            data?.toChatLayoutPreferenceSnapshotOrNull()?.let(onChanged)
        }

    fun postConversationForwardGestureChanged(enabled: Boolean) {
        eventBus.post(
            CHAT_LAYOUT_PREFERENCE_EVENT,
            buildJsonObject { put(KEY_CONVERSATION_FORWARD_GESTURE_ENABLED, enabled) },
            source = source
        )
    }

    fun postPreferenceChanged(snapshot: ChatLayoutPreferenceSnapshot) {
        eventBus.post(
            CHAT_LAYOUT_PREFERENCE_SNAPSHOT_EVENT,
            snapshot.toPreferenceEventData(),
            source = source
        )
    }
}

fun kuiklyChatLayoutPreferenceRepository(
    delegate: ChatLayoutPreferenceRepository,
    events: KuiklyChatLayoutPreferenceEvents
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

        override suspend fun setSingleTapOpensThreadEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setSingleTapOpensThreadEnabled(enabled) }

        override suspend fun setThreadUnreadJumpEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setThreadUnreadJumpEnabled(enabled) }

        override suspend fun setNewMessageMenuEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setNewMessageMenuEnabled(enabled) }

        override suspend fun setConversationForwardGestureEnabled(
            enabled: Boolean
        ): ChatLayoutPreferenceSnapshot {
            val snapshot = persistAndPublish { delegate.setConversationForwardGestureEnabled(enabled) }
            events.postConversationForwardGestureChanged(snapshot.conversationForwardGestureEnabled)
            return snapshot
        }

        override suspend fun setForegroundMessageBannersEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setForegroundMessageBannersEnabled(enabled) }

        override suspend fun setActivityTaskStatusBadgesEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setActivityTaskStatusBadgesEnabled(enabled) }

        override suspend fun setActivitySenderAvatarsEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setActivitySenderAvatarsEnabled(enabled) }

        override suspend fun setComposerSendKeyEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setComposerSendKeyEnabled(enabled) }

        override suspend fun setMultiShellHostEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setMultiShellHostEnabled(enabled) }

        override suspend fun setServerDrawerEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setServerDrawerEnabled(enabled) }

        override suspend fun setNewDesignSettingsEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setNewDesignSettingsEnabled(enabled) }

        override suspend fun setHomeServerAvatarEnabled(enabled: Boolean): ChatLayoutPreferenceSnapshot =
            persistAndPublish { delegate.setHomeServerAvatarEnabled(enabled) }

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
        put(KEY_SINGLE_TAP_OPENS_THREAD_ENABLED, singleTapOpensThreadEnabled)
        put(KEY_THREAD_UNREAD_JUMP_ENABLED, threadUnreadJumpEnabled)
        put(KEY_NEW_MESSAGE_MENU_ENABLED, newMessageMenuEnabled)
        put(KEY_CONVERSATION_FORWARD_GESTURE_ENABLED, conversationForwardGestureEnabled)
        put(KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED, foregroundMessageBannersEnabled)
        put(KEY_ACTIVITY_TASK_STATUS_BADGES_ENABLED, activityTaskStatusBadgesEnabled)
        put(KEY_ACTIVITY_SENDER_AVATARS_ENABLED, activitySenderAvatarsEnabled)
        put(KEY_COMPOSER_SEND_KEY_ENABLED, composerSendKeyEnabled)
        put(KEY_MULTI_SHELL_HOST_ENABLED, multiShellHostEnabled)
        put(KEY_SERVER_DRAWER_ENABLED, serverDrawerEnabled)
        put(KEY_NEW_DESIGN_SETTINGS_ENABLED, newDesignSettingsEnabled)
        put(KEY_HOME_SERVER_AVATAR_ENABLED, homeServerAvatarEnabled)
        put(KEY_TRANSLATION_MODE, translationMode.storageKey)
        put(KEY_TRANSLATION_TARGET_LANGUAGE, translationTargetLanguage)
    }

internal fun JsonObject.toChatLayoutPreferenceSnapshotOrNull(): ChatLayoutPreferenceSnapshot? {
    if (CHAT_LAYOUT_PREFERENCE_SNAPSHOT_KEYS.any { !has(it) }) return null
    return ChatLayoutPreferenceSnapshot(
        myMessageAlignment = MyMessageAlignmentPreference.fromStorageKey(optString(KEY_MY_MESSAGE_ALIGNMENT)),
        desktopModeOnLargeScreen = optBoolean(KEY_DESKTOP_MODE_ON_LARGE_SCREEN, DEFAULT_DESKTOP_MODE_ON_LARGE_SCREEN),
        compactConsecutiveMessages = optBoolean(KEY_COMPACT_CONSECUTIVE_MESSAGES, true),
        longMessageCollapseEnabled = optBoolean(KEY_LONG_MESSAGE_COLLAPSE_ENABLED, false),
        singleTapOpensThreadEnabled = optBoolean(KEY_SINGLE_TAP_OPENS_THREAD_ENABLED, true),
        threadUnreadJumpEnabled = optBoolean(KEY_THREAD_UNREAD_JUMP_ENABLED, false),
        newMessageMenuEnabled = optBoolean(KEY_NEW_MESSAGE_MENU_ENABLED, false),
        conversationForwardGestureEnabled = optBoolean(KEY_CONVERSATION_FORWARD_GESTURE_ENABLED, false),
        foregroundMessageBannersEnabled = optBoolean(KEY_FOREGROUND_MESSAGE_BANNERS_ENABLED, false),
        activityTaskStatusBadgesEnabled = optBoolean(KEY_ACTIVITY_TASK_STATUS_BADGES_ENABLED, DEFAULT_ACTIVITY_TASK_STATUS_BADGES_ENABLED),
        activitySenderAvatarsEnabled = optBoolean(KEY_ACTIVITY_SENDER_AVATARS_ENABLED, false),
        composerSendKeyEnabled = optBoolean(KEY_COMPOSER_SEND_KEY_ENABLED, false),
        multiShellHostEnabled = optBoolean(KEY_MULTI_SHELL_HOST_ENABLED, false),
        serverDrawerEnabled = optBoolean(KEY_SERVER_DRAWER_ENABLED, false),
        newDesignSettingsEnabled = true,
        homeServerAvatarEnabled = optBoolean(KEY_HOME_SERVER_AVATAR_ENABLED, DEFAULT_HOME_SERVER_AVATAR_ENABLED),
        translationMode = TranslationModePreference.fromStorageKey(optString(KEY_TRANSLATION_MODE)),
        translationTargetLanguage =
            optString(KEY_TRANSLATION_TARGET_LANGUAGE).ifBlank { TranslationTargetLanguages.Browser },
    )
}

internal fun reconcileConversationForwardGesturePreference(
    enabled: Boolean,
    history: SharedConversationForwardHistory,
    disableNativeGesture: () -> Unit
) {
    history.setEnabled(enabled)
    if (!enabled) disableNativeGesture()
}

/** Prevents an in-flight repository seed from overwriting a newer process event. */
internal class ChatLayoutPreferenceSnapshotRuntimeAuthority(
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

/**
 * Runtime authority for an AppShell's conversation Forward preference.
 *
 * A repository read seeds a newly mounted shell. Once this process has observed a successful
 * preference write, that process event is newer than any in-flight Settings snapshot or seed
 * that started before the write, so a late seed must not overwrite it.
 */
internal class ConversationForwardGestureRuntimeAuthority(
    private val supported: Boolean,
    private val onEffectivePreferenceChanged: (Boolean) -> Unit
) {
    private var observedProcessChange: Boolean = false

    fun seedFromRepository(enabled: Boolean) {
        if (observedProcessChange) return
        onEffectivePreferenceChanged(supported && enabled)
    }

    fun applyProcessPreferenceChange(enabled: Boolean) {
        observedProcessChange = true
        onEffectivePreferenceChanged(supported && enabled)
    }
}
