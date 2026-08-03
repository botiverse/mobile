package build.raft.app.data

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

private const val CHAT_LAYOUT_PREFERENCE_EVENT = "slock.chatLayoutPreferenceChanged"
private const val KEY_CONVERSATION_FORWARD_GESTURE_ENABLED =
    "conversationForwardGestureEnabled"

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

    fun postConversationForwardGestureChanged(enabled: Boolean) {
        eventBus.post(
            CHAT_LAYOUT_PREFERENCE_EVENT,
            JSONObject().put(KEY_CONVERSATION_FORWARD_GESTURE_ENABLED, enabled),
            source = source
        )
    }
}

fun kuiklyChatLayoutPreferenceRepository(
    delegate: ChatLayoutPreferenceRepository,
    events: KuiklyChatLayoutPreferenceEvents
): ChatLayoutPreferenceRepository =
    object : ChatLayoutPreferenceRepository by delegate {
        override suspend fun setConversationForwardGestureEnabled(
            enabled: Boolean
        ): ChatLayoutPreferenceSnapshot {
            val snapshot = delegate.setConversationForwardGestureEnabled(enabled)
            events.postConversationForwardGestureChanged(
                snapshot.conversationForwardGestureEnabled
            )
            return snapshot
        }
    }

internal fun reconcileConversationForwardGesturePreference(
    enabled: Boolean,
    history: SharedConversationForwardHistory,
    disableNativeGesture: () -> Unit
) {
    history.setEnabled(enabled)
    if (!enabled) disableNativeGesture()
}
