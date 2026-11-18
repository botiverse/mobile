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

/**
 * The Live Agents widget's state. Its rows carry the agents' names and their status words
 * (resolved when the refresh loaded them); without rows, [placeholder] says which line to show,
 * drawn from the app's Android strings by the provider ([placeholderName] / [placeholderDetail]).
 */
data class RaftAgentWidgetState(
    val placeholder: RaftAgentWidgetPlaceholder = RaftAgentWidgetPlaceholder.NoActiveAgent,
    val rows: List<RaftAgentWidgetRow> = emptyList(),
    val lastUpdatedAtMillis: Long = 0L,
    val staleReason: RaftWidgetStaleReason? = null,
    val refreshing: Boolean = false
)

/** What the widget says when it has no agent row to show. */
enum class RaftAgentWidgetPlaceholder(val wire: String) {
    /** Before the first refresh: "No active agent" · "Offline". */
    NoActiveAgent("no_active_agent"),

    /** Signed out: "No active agent" · "Sign in to see agent activity". */
    SignedOut("signed_out"),

    /** No agent has a status or anything else to show: "No agents yet" · "No live agent activity". */
    NoAgents("no_agents");

    companion object {
        fun fromWire(value: String?): RaftAgentWidgetPlaceholder? = entries.firstOrNull { it.wire == value }
    }
}

/** The row's status dot, one per colour of the app's agent status dot (RaftAgentStatusDotKind). */
enum class RaftAgentWidgetTone {
    /** Online / idle: lime. */
    Active,
    /** Kept so a widget cache written before 1.15.2 still reads; drawn like [Active]. */
    Idle,
    /** Working / thinking: yellow. */
    Working,
    /** External agent outside its online window: cyan. */
    External,
    Error,
    /** Offline, disconnected or unknown: gray. */
    Offline
}

fun RaftAgentWidgetState.asStale(reason: RaftWidgetStaleReason): RaftAgentWidgetState =
    copy(staleReason = reason, refreshing = false)

class RaftAgentWidgetCache(context: Context) {
    private val store: RaftKeyValueStore =
        AndroidMMKVKeyValueStore(context.applicationContext, PREFERENCES_NAME)

    fun read(appWidgetId: Int, refreshing: Boolean = false): RaftAgentWidgetState {
        val encoded = store.getString(stateKey(appWidgetId)) ?: store.getString(KEY_STATE) ?: return RaftAgentWidgetState()
        return runCatching { JSONObject(encoded).toRaftAgentWidgetState(refreshing) }
            .getOrElse { RaftAgentWidgetState(refreshing = refreshing) }
    }

    fun write(appWidgetId: Int, state: RaftAgentWidgetState) {
        val json = JSONObject()
            .put("placeholder", state.placeholder.wire)
            .put("rows", state.rows.toJsonArray())
            .put("lastUpdatedAtMillis", state.lastUpdatedAtMillis)
            .put("staleReason", state.staleReason?.wire)
        store.putString(stateKey(appWidgetId), json.toString())
    }

    fun clear(appWidgetId: Int) {
        store.putString(stateKey(appWidgetId), null)
    }

    private companion object {
        private const val PREFERENCES_NAME = "slock_agent_status_widget"
        private const val KEY_STATE = "state"

        private fun stateKey(appWidgetId: Int): String = "state:$appWidgetId"

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

/**
 * The cached state; a cache written before 1.27 holds the English placeholder lines instead of
 * `placeholder` ("Sign in to see agent activity" signed out, "No agents yet" none to show).
 */
internal fun JSONObject.toRaftAgentWidgetState(refreshing: Boolean): RaftAgentWidgetState =
    RaftAgentWidgetState(
        placeholder =
            RaftAgentWidgetPlaceholder.fromWire(optNullableString("placeholder"))
                ?: when {
                    optNullableString("statusLabel") == "Sign in to see agent activity" -> RaftAgentWidgetPlaceholder.SignedOut
                    optNullableString("agentName") == "No agents yet" -> RaftAgentWidgetPlaceholder.NoAgents
                    else -> RaftAgentWidgetPlaceholder.NoActiveAgent
                },
        rows = optJSONArray("rows").toAgentRows(),
        lastUpdatedAtMillis = optLong("lastUpdatedAtMillis", 0L),
        staleReason = RaftWidgetStaleReason.fromWire(optNullableString("staleReason")),
        refreshing = refreshing
    )

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
