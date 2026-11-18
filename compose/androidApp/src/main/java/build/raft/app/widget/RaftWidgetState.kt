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

/**
 * The activity widget's state. It holds no copy: [titleText] / [detailText] say which line to
 * show, and the provider draws it from the app's Android strings.
 */
sealed class RaftWidgetState(
    open val lastUpdatedAtMillis: Long,
    open val refreshing: Boolean
) {
    open val unreadCount: Int = 0
    open val activityUnreadCount: Int = 0
    open val savedCount: Int = 0

    data object SignedOut : RaftWidgetState(lastUpdatedAtMillis = 0L, refreshing = false)

    data class Error(
        val kind: RaftWidgetErrorKind = RaftWidgetErrorKind.OpenRaft,
        /** Shown instead of the kind's own line when a refresh failed. */
        val staleReason: RaftWidgetStaleReason? = null,
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
        val staleReason: RaftWidgetStaleReason? = null,
        override val refreshing: Boolean = false
    ) : RaftWidgetState(lastUpdatedAtMillis, refreshing)
}

enum class RaftWidgetErrorKind(val wire: String) {
    /** No server for this widget: "Choose a server", tap and hold to edit. */
    ChooseServer("choose_server"),

    /** Anything else: "Raft", open Raft to continue. */
    OpenRaft("open_raft");

    companion object {
        fun fromWire(value: String?): RaftWidgetErrorKind? = entries.firstOrNull { it.wire == value }
    }
}

fun RaftWidgetState.asStale(reason: RaftWidgetStaleReason): RaftWidgetState =
    when (this) {
        is RaftWidgetState.Content -> copy(staleReason = reason, refreshing = false)
        is RaftWidgetState.Error -> copy(staleReason = reason, refreshing = false)
        RaftWidgetState.SignedOut -> RaftWidgetState.Error(
            kind = RaftWidgetErrorKind.OpenRaft,
            staleReason = reason,
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
                        staleReason = RaftWidgetStaleReason.fromWire(json.optNullableString("staleReason")),
                        refreshing = refreshing
                    )
                "error" -> json.toRaftWidgetErrorState(refreshing)
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
                json.put("staleReason", state.staleReason?.wire)
            }
            is RaftWidgetState.Error -> {
                json.put("type", "error")
                json.put("kind", state.kind.wire)
                json.put("staleReason", state.staleReason?.wire)
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

/** A string field, or null when it is missing or JSON null (`optString` reads a null as "null"). */
internal fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

/**
 * An error state; a cache written before 1.27 holds its English title and line instead of
 * `kind` / `staleReason`: "Choose a server" was the server choice, any other line than the
 * kind's own was a failed refresh's reason.
 */
internal fun JSONObject.toRaftWidgetErrorState(refreshing: Boolean): RaftWidgetState.Error {
    val lastUpdatedAtMillis = optLong("lastUpdatedAtMillis", 0L)
    val kind = RaftWidgetErrorKind.fromWire(optNullableString("kind"))
    if (kind != null) {
        return RaftWidgetState.Error(
            kind = kind,
            staleReason = RaftWidgetStaleReason.fromWire(optNullableString("staleReason")),
            lastUpdatedAtMillis = lastUpdatedAtMillis,
            refreshing = refreshing
        )
    }
    val legacyKind =
        if (optNullableString("title") == LEGACY_CHOOSE_SERVER_TITLE) RaftWidgetErrorKind.ChooseServer else RaftWidgetErrorKind.OpenRaft
    val legacyDetail = optNullableString("detail")?.takeIf { it !in LEGACY_OWN_DETAILS }
    return RaftWidgetState.Error(
        kind = legacyKind,
        staleReason = RaftWidgetStaleReason.fromWire(legacyDetail),
        lastUpdatedAtMillis = lastUpdatedAtMillis,
        refreshing = refreshing
    )
}

private const val LEGACY_CHOOSE_SERVER_TITLE = "Choose a server"
private val LEGACY_OWN_DETAILS =
    setOf("Tap and hold the widget, then edit it to select a server.", "Open Raft to continue.")
