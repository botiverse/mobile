package build.raft.app.widget

import android.content.Context
import build.raft.app.core.data.auth.AndroidMMKVKeyValueStore
import build.raft.app.data.RaftKeyValueStore
import org.json.JSONArray
import org.json.JSONObject

data class RaftWidgetHomeRow(
    val title: String,
    val detail: String,
    val avatarLabel: String,
    val unreadCount: Int,
    val avatarImagePath: String? = null
)

sealed class RaftWidgetState(
    open val lastUpdatedAtMillis: Long,
    open val refreshing: Boolean
) {
    abstract val title: String
    abstract val detail: String
    open val unreadCount: Int = 0
    open val activityUnreadCount: Int = 0
    open val savedCount: Int = 0

    data object SignedOut : RaftWidgetState(lastUpdatedAtMillis = 0L, refreshing = false) {
        override val title: String = "Raft"
        override val detail: String = "Sign in to see workspace updates."
    }

    data class Error(
        override val title: String,
        override val detail: String,
        override val lastUpdatedAtMillis: Long = 0L,
        override val refreshing: Boolean = false
    ) : RaftWidgetState(lastUpdatedAtMillis, refreshing)

    data class Content(
        val serverName: String,
        override val unreadCount: Int,
        override val activityUnreadCount: Int,
        override val savedCount: Int,
        val rows: List<RaftWidgetHomeRow> = emptyList(),
        override val lastUpdatedAtMillis: Long,
        val staleReason: String? = null,
        override val refreshing: Boolean = false
    ) : RaftWidgetState(lastUpdatedAtMillis, refreshing) {
        override val title: String = serverName.ifBlank { "Raft" }
        override val detail: String =
            if (staleReason == null) {
                "Workspace activity"
            } else {
                "Last refresh failed: $staleReason"
            }
    }
}
fun RaftWidgetState.asStale(reason: String): RaftWidgetState =
    when (this) {
        is RaftWidgetState.Content -> copy(staleReason = reason, refreshing = false)
        is RaftWidgetState.Error -> copy(detail = reason, refreshing = false)
        RaftWidgetState.SignedOut -> RaftWidgetState.Error(
            title = "Raft",
            detail = reason,
            lastUpdatedAtMillis = 0L
        )
    }

class RaftWidgetCache(context: Context) {
    private val store: RaftKeyValueStore =
        AndroidMMKVKeyValueStore(context.applicationContext, PREFERENCES_NAME)

    fun read(appWidgetId: Int, refreshing: Boolean = false): RaftWidgetState {
        val encoded = store.getString(stateKey(appWidgetId)) ?: store.getString(KEY_STATE) ?: return RaftWidgetState.SignedOut
        return runCatching {
            val json = JSONObject(encoded)
            when (json.optString("type")) {
                "content" ->
                    RaftWidgetState.Content(
                        serverName = json.optString("serverName", "Raft"),
                        unreadCount = json.optInt("unreadCount", 0),
                        activityUnreadCount = json.optInt("activityUnreadCount", 0),
                        savedCount = json.optInt("savedCount", 0),
                        rows = json.optJSONArray("rows").toHomeRows(),
                        lastUpdatedAtMillis = json.optLong("lastUpdatedAtMillis", 0L),
                        staleReason = json.optString("staleReason").takeIf { it.isNotBlank() },
                        refreshing = refreshing
                    )
                "error" ->
                    RaftWidgetState.Error(
                        title = json.optString("title", "Raft"),
                        detail = json.optString("detail", "Open Raft to continue."),
                        lastUpdatedAtMillis = json.optLong("lastUpdatedAtMillis", 0L),
                        refreshing = refreshing
                    )
                else -> RaftWidgetState.SignedOut
            }
        }.getOrElse { RaftWidgetState.SignedOut }
    }

    fun write(appWidgetId: Int, state: RaftWidgetState) {
        val json = JSONObject()
        when (state) {
            is RaftWidgetState.Content -> {
                json.put("type", "content")
                json.put("serverName", state.serverName)
                json.put("unreadCount", state.unreadCount)
                json.put("activityUnreadCount", state.activityUnreadCount)
                json.put("savedCount", state.savedCount)
                json.put("rows", state.rows.toJsonArray())
                json.put("lastUpdatedAtMillis", state.lastUpdatedAtMillis)
                json.put("staleReason", state.staleReason)
            }
            is RaftWidgetState.Error -> {
                json.put("type", "error")
                json.put("title", state.title)
                json.put("detail", state.detail)
                json.put("lastUpdatedAtMillis", state.lastUpdatedAtMillis)
            }
            RaftWidgetState.SignedOut -> {
                store.putString(stateKey(appWidgetId), null)
                return
            }
        }
        store.putString(stateKey(appWidgetId), json.toString())
    }

    fun clear(appWidgetId: Int) {
        store.putString(stateKey(appWidgetId), null)
    }

    private companion object {
        private const val PREFERENCES_NAME = "slock_status_widget"
        private const val KEY_STATE = "state"

        private fun stateKey(appWidgetId: Int): String = "state:$appWidgetId"

        private fun JSONArray?.toHomeRows(): List<RaftWidgetHomeRow> {
            if (this == null) return emptyList()
            return buildList {
                for (index in 0 until length()) {
                    val item = optJSONObject(index) ?: continue
                    add(
                        RaftWidgetHomeRow(
                            title = item.optString("title"),
                            detail = item.optString("detail"),
                            avatarLabel = item.optString("avatarLabel", "#"),
                            unreadCount = item.optInt("unreadCount", 0),
                            avatarImagePath = item.optString("avatarImagePath").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }

        private fun List<RaftWidgetHomeRow>.toJsonArray(): JSONArray =
            JSONArray().also { array ->
                forEach { row ->
                    array.put(
                        JSONObject()
                            .put("title", row.title)
                            .put("detail", row.detail)
                            .put("avatarLabel", row.avatarLabel)
                            .put("unreadCount", row.unreadCount)
                            .put("avatarImagePath", row.avatarImagePath)
                    )
                }
            }
    }
}
