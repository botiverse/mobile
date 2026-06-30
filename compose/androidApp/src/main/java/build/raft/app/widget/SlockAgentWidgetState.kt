package build.raft.app.widget

import android.content.Context
import build.raft.app.core.data.auth.AndroidMMKVKeyValueStore
import ai.slock.compose.data.SlockKeyValueStore
import org.json.JSONArray
import org.json.JSONObject

data class SlockAgentWidgetRow(
    val name: String,
    val detail: String,
    val avatarLabel: String,
    val statusTone: SlockAgentWidgetTone,
    val avatarImagePath: String? = null
)

data class SlockAgentWidgetState(
    val title: String = "Live Agents",
    val agentName: String = "No active agent",
    val avatarLabel: String = "A",
    val statusLabel: String = "Offline",
    val statusTone: SlockAgentWidgetTone = SlockAgentWidgetTone.Offline,
    val rows: List<SlockAgentWidgetRow> = emptyList(),
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

    fun read(appWidgetId: Int, refreshing: Boolean = false): SlockAgentWidgetState {
        val encoded = store.getString(stateKey(appWidgetId)) ?: store.getString(KEY_STATE) ?: return SlockAgentWidgetState()
        return runCatching {
            val json = JSONObject(encoded)
            SlockAgentWidgetState(
                title = json.optString("title", "Live Agents"),
                agentName = json.optString("agentName", "No active agent"),
                avatarLabel = json.optString("avatarLabel", "A"),
                statusLabel = json.optString("statusLabel", "Offline"),
                statusTone = runCatching {
                    SlockAgentWidgetTone.valueOf(json.optString("statusTone", SlockAgentWidgetTone.Offline.name))
                }.getOrDefault(SlockAgentWidgetTone.Offline),
                rows = json.optJSONArray("rows").toAgentRows(),
                lastUpdatedAtMillis = json.optLong("lastUpdatedAtMillis", 0L),
                staleReason = json.optString("staleReason").takeIf { it.isNotBlank() },
                refreshing = refreshing
            )
        }.getOrElse { SlockAgentWidgetState(refreshing = refreshing) }
    }

    fun write(appWidgetId: Int, state: SlockAgentWidgetState) {
        val json = JSONObject()
            .put("title", state.title)
            .put("agentName", state.agentName)
            .put("avatarLabel", state.avatarLabel)
            .put("statusLabel", state.statusLabel)
            .put("statusTone", state.statusTone.name)
            .put("rows", state.rows.toJsonArray())
            .put("lastUpdatedAtMillis", state.lastUpdatedAtMillis)
            .put("staleReason", state.staleReason)
        store.putString(stateKey(appWidgetId), json.toString())
    }

    fun clear(appWidgetId: Int) {
        store.putString(stateKey(appWidgetId), null)
    }

    private companion object {
        private const val PREFERENCES_NAME = "slock_agent_status_widget"
        private const val KEY_STATE = "state"

        private fun stateKey(appWidgetId: Int): String = "state:$appWidgetId"

        private fun JSONArray?.toAgentRows(): List<SlockAgentWidgetRow> {
            if (this == null) return emptyList()
            return buildList {
                for (index in 0 until length()) {
                    val item = optJSONObject(index) ?: continue
                    add(
                        SlockAgentWidgetRow(
                            name = item.optString("name"),
                            detail = item.optString("detail"),
                            avatarLabel = item.optString("avatarLabel", "A"),
                            statusTone = runCatching {
                                SlockAgentWidgetTone.valueOf(item.optString("statusTone", SlockAgentWidgetTone.Offline.name))
                            }.getOrDefault(SlockAgentWidgetTone.Offline),
                            avatarImagePath = item.optString("avatarImagePath").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }

        private fun List<SlockAgentWidgetRow>.toJsonArray(): JSONArray =
            JSONArray().also { array ->
                forEach { row ->
                    array.put(
                        JSONObject()
                            .put("name", row.name)
                            .put("detail", row.detail)
                            .put("avatarLabel", row.avatarLabel)
                            .put("statusTone", row.statusTone.name)
                            .put("avatarImagePath", row.avatarImagePath)
                    )
                }
            }
    }
}
