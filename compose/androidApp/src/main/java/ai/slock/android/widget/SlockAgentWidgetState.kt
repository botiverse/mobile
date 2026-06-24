package ai.slock.android.widget

import android.content.Context
import ai.slock.android.core.data.auth.AndroidMMKVKeyValueStore
import ai.slock.compose.data.SlockKeyValueStore
import org.json.JSONObject

data class SlockAgentWidgetState(
    val title: String = "Agent Status",
    val agentName: String = "No active agent",
    val avatarLabel: String = "A",
    val statusLabel: String = "Offline",
    val statusTone: SlockAgentWidgetTone = SlockAgentWidgetTone.Offline,
    val lastUpdatedAtMillis: Long = 0L,
    val staleReason: String? = null,
    val refreshing: Boolean = false
) {
    val updatedDetail: String
        get() = staleReason?.let { "Last refresh failed: $it" } ?: statusLabel
}

enum class SlockAgentWidgetTone {
    Active,
    Idle,
    Error,
    Offline
}

fun SlockAgentWidgetState.asStale(reason: String): SlockAgentWidgetState =
    copy(staleReason = reason, refreshing = false)

class SlockAgentWidgetCache(context: Context) {
    private val store: SlockKeyValueStore =
        AndroidMMKVKeyValueStore(context.applicationContext, PREFERENCES_NAME)

    fun read(refreshing: Boolean = false): SlockAgentWidgetState {
        val encoded = store.getString(KEY_STATE) ?: return SlockAgentWidgetState()
        return runCatching {
            val json = JSONObject(encoded)
            SlockAgentWidgetState(
                title = json.optString("title", "Agent Status"),
                agentName = json.optString("agentName", "No active agent"),
                avatarLabel = json.optString("avatarLabel", "A"),
                statusLabel = json.optString("statusLabel", "Offline"),
                statusTone = runCatching {
                    SlockAgentWidgetTone.valueOf(json.optString("statusTone", SlockAgentWidgetTone.Offline.name))
                }.getOrDefault(SlockAgentWidgetTone.Offline),
                lastUpdatedAtMillis = json.optLong("lastUpdatedAtMillis", 0L),
                staleReason = json.optString("staleReason").takeIf { it.isNotBlank() },
                refreshing = refreshing
            )
        }.getOrElse { SlockAgentWidgetState(refreshing = refreshing) }
    }

    fun write(state: SlockAgentWidgetState) {
        val json = JSONObject()
            .put("title", state.title)
            .put("agentName", state.agentName)
            .put("avatarLabel", state.avatarLabel)
            .put("statusLabel", state.statusLabel)
            .put("statusTone", state.statusTone.name)
            .put("lastUpdatedAtMillis", state.lastUpdatedAtMillis)
            .put("staleReason", state.staleReason)
        store.putString(KEY_STATE, json.toString())
    }

    private companion object {
        private const val PREFERENCES_NAME = "slock_agent_status_widget"
        private const val KEY_STATE = "state"
    }
}
