package build.raft.app.widget

import android.content.Context
import build.raft.app.core.data.auth.AndroidMMKVKeyValueStore
import build.raft.app.data.RaftKeyValueStore
import org.json.JSONArray
import org.json.JSONObject

data class RaftAgentWidgetRow(
    val name: String,
    val detail: String,
    val avatarLabel: String,
    val statusTone: RaftAgentWidgetTone,
    val avatarImagePath: String? = null
)

data class RaftAgentWidgetState(
    val title: String = "Live Agents",
    val agentName: String = "No active agent",
    val avatarLabel: String = "A",
    val statusLabel: String = "Offline",
    val statusTone: RaftAgentWidgetTone = RaftAgentWidgetTone.Offline,
    val rows: List<RaftAgentWidgetRow> = emptyList(),
    val lastUpdatedAtMillis: Long = 0L,
    val staleReason: String? = null,
    val refreshing: Boolean = false
) {
    val updatedDetail: String
        get() = staleReason?.let { "Last refresh failed: $it" } ?: statusLabel
}

enum class RaftAgentWidgetTone {
    Active,
    Idle,
    Error,
    Offline
}

fun RaftAgentWidgetState.asStale(reason: String): RaftAgentWidgetState =
    copy(staleReason = reason, refreshing = false)

class RaftAgentWidgetCache(context: Context) {
    private val store: RaftKeyValueStore =
        AndroidMMKVKeyValueStore(context.applicationContext, PREFERENCES_NAME)

    fun read(appWidgetId: Int, refreshing: Boolean = false): RaftAgentWidgetState {
        val encoded = store.getString(stateKey(appWidgetId)) ?: store.getString(KEY_STATE) ?: return RaftAgentWidgetState()
        return runCatching {
            val json = JSONObject(encoded)
            RaftAgentWidgetState(
                title = json.optString("title", "Live Agents"),
                agentName = json.optString("agentName", "No active agent"),
                avatarLabel = json.optString("avatarLabel", "A"),
                statusLabel = json.optString("statusLabel", "Offline"),
                statusTone = runCatching {
                    RaftAgentWidgetTone.valueOf(json.optString("statusTone", RaftAgentWidgetTone.Offline.name))
                }.getOrDefault(RaftAgentWidgetTone.Offline),
                rows = json.optJSONArray("rows").toAgentRows(),
                lastUpdatedAtMillis = json.optLong("lastUpdatedAtMillis", 0L),
                staleReason = json.optString("staleReason").takeIf { it.isNotBlank() },
                refreshing = refreshing
            )
        }.getOrElse { RaftAgentWidgetState(refreshing = refreshing) }
    }

    fun write(appWidgetId: Int, state: RaftAgentWidgetState) {
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

        private fun JSONArray?.toAgentRows(): List<RaftAgentWidgetRow> {
            if (this == null) return emptyList()
            return buildList {
                for (index in 0 until length()) {
                    val item = optJSONObject(index) ?: continue
                    add(
                        RaftAgentWidgetRow(
                            name = item.optString("name"),
                            detail = item.optString("detail"),
                            avatarLabel = item.optString("avatarLabel", "A"),
                            statusTone = runCatching {
                                RaftAgentWidgetTone.valueOf(item.optString("statusTone", RaftAgentWidgetTone.Offline.name))
                            }.getOrDefault(RaftAgentWidgetTone.Offline),
                            avatarImagePath = item.optString("avatarImagePath").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }

        private fun List<RaftAgentWidgetRow>.toJsonArray(): JSONArray =
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
