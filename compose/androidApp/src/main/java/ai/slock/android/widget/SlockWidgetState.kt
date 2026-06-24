package ai.slock.android.widget

import android.content.Context
import ai.slock.android.core.data.auth.AndroidMMKVKeyValueStore
import ai.slock.compose.data.SlockKeyValueStore
import org.json.JSONObject

sealed class SlockWidgetState(
    open val lastUpdatedAtMillis: Long,
    open val refreshing: Boolean
) {
    abstract val title: String
    abstract val detail: String
    open val unreadCount: Int = 0
    open val activityUnreadCount: Int = 0
    open val savedCount: Int = 0

    data object SignedOut : SlockWidgetState(lastUpdatedAtMillis = 0L, refreshing = false) {
        override val title: String = "Raft"
        override val detail: String = "Sign in to see workspace updates."
    }

    data class Error(
        override val title: String,
        override val detail: String,
        override val lastUpdatedAtMillis: Long = 0L,
        override val refreshing: Boolean = false
    ) : SlockWidgetState(lastUpdatedAtMillis, refreshing)

    data class Content(
        val serverName: String,
        override val unreadCount: Int,
        override val activityUnreadCount: Int,
        override val savedCount: Int,
        override val lastUpdatedAtMillis: Long,
        val staleReason: String? = null,
        override val refreshing: Boolean = false
    ) : SlockWidgetState(lastUpdatedAtMillis, refreshing) {
        override val title: String = serverName.ifBlank { "Raft" }
        override val detail: String =
            if (staleReason == null) {
                "Workspace activity"
            } else {
                "Last refresh failed: $staleReason"
            }
    }
}
fun SlockWidgetState.asStale(reason: String): SlockWidgetState =
    when (this) {
        is SlockWidgetState.Content -> copy(staleReason = reason, refreshing = false)
        is SlockWidgetState.Error -> copy(detail = reason, refreshing = false)
        SlockWidgetState.SignedOut -> SlockWidgetState.Error(
            title = "Raft",
            detail = reason,
            lastUpdatedAtMillis = 0L
        )
    }

class SlockWidgetCache(context: Context) {
    private val store: SlockKeyValueStore =
        AndroidMMKVKeyValueStore(context.applicationContext, PREFERENCES_NAME)

    fun read(appWidgetId: Int, refreshing: Boolean = false): SlockWidgetState {
        val encoded = store.getString(stateKey(appWidgetId)) ?: store.getString(KEY_STATE) ?: return SlockWidgetState.SignedOut
        return runCatching {
            val json = JSONObject(encoded)
            when (json.optString("type")) {
                "content" ->
                    SlockWidgetState.Content(
                        serverName = json.optString("serverName", "Raft"),
                        unreadCount = json.optInt("unreadCount", 0),
                        activityUnreadCount = json.optInt("activityUnreadCount", 0),
                        savedCount = json.optInt("savedCount", 0),
                        lastUpdatedAtMillis = json.optLong("lastUpdatedAtMillis", 0L),
                        staleReason = json.optString("staleReason").takeIf { it.isNotBlank() },
                        refreshing = refreshing
                    )
                "error" ->
                    SlockWidgetState.Error(
                        title = json.optString("title", "Raft"),
                        detail = json.optString("detail", "Open Raft to continue."),
                        lastUpdatedAtMillis = json.optLong("lastUpdatedAtMillis", 0L),
                        refreshing = refreshing
                    )
                else -> SlockWidgetState.SignedOut
            }
        }.getOrElse { SlockWidgetState.SignedOut }
    }

    fun write(appWidgetId: Int, state: SlockWidgetState) {
        val json = JSONObject()
        when (state) {
            is SlockWidgetState.Content -> {
                json.put("type", "content")
                json.put("serverName", state.serverName)
                json.put("unreadCount", state.unreadCount)
                json.put("activityUnreadCount", state.activityUnreadCount)
                json.put("savedCount", state.savedCount)
                json.put("lastUpdatedAtMillis", state.lastUpdatedAtMillis)
                json.put("staleReason", state.staleReason)
            }
            is SlockWidgetState.Error -> {
                json.put("type", "error")
                json.put("title", state.title)
                json.put("detail", state.detail)
                json.put("lastUpdatedAtMillis", state.lastUpdatedAtMillis)
            }
            SlockWidgetState.SignedOut -> {
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
    }
}
