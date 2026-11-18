package build.raft.app.widget

import android.content.Context
import androidx.annotation.StringRes
import build.raft.app.R
import build.raft.app.model.ConversationKind

/**
 * A widget line before it is drawn: a string resource with its arguments (the app's Android
 * strings, resolved by the provider, which has a Context), or text shown as is (a server or agent
 * name, an error code). The widget states hold no copy of their own; they say which line to show.
 */
sealed interface RaftWidgetText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : RaftWidgetText

    data class Plain(val text: String) : RaftWidgetText
}

fun RaftWidgetText.resolve(context: Context): String =
    when (this) {
        is RaftWidgetText.Plain -> text
        is RaftWidgetText.Res ->
            if (args.isEmpty()) {
                context.getString(id)
            } else {
                context.getString(id, *args.map { (it as? RaftWidgetText)?.resolve(context) ?: it }.toTypedArray())
            }
    }

/** Why the last refresh did not land: kept with the state, put in words when drawn. */
sealed interface RaftWidgetStaleReason {
    /** The request threw (no connection, timeout). */
    data object CouldNotRefresh : RaftWidgetStaleReason

    /** The widget has no server (or the chosen one is gone). */
    data object ChooseServer : RaftWidgetStaleReason

    /** The home snapshot came back empty. */
    data object NoConversations : RaftWidgetStaleReason

    /** A server or contract error, shown by its code as before (e.g. `home_http_500`). */
    data class Code(val code: String) : RaftWidgetStaleReason

    val wire: String
        get() =
            when (this) {
                CouldNotRefresh -> WIRE_COULD_NOT_REFRESH
                ChooseServer -> WIRE_CHOOSE_SERVER
                NoConversations -> WIRE_NO_CONVERSATIONS
                is Code -> code
            }

    companion object {
        /** Reads [wire], and the English a widget cache written before 1.27 holds. */
        fun fromWire(value: String?): RaftWidgetStaleReason? =
            when (val text = value?.takeIf { it.isNotBlank() }) {
                null -> null
                WIRE_COULD_NOT_REFRESH, "Could not refresh" -> CouldNotRefresh
                WIRE_CHOOSE_SERVER, "Edit widget to choose a server" -> ChooseServer
                WIRE_NO_CONVERSATIONS, "No conversations yet" -> NoConversations
                else -> Code(text)
            }
    }
}

fun RaftWidgetStaleReason.text(): RaftWidgetText =
    when (this) {
        RaftWidgetStaleReason.CouldNotRefresh -> RaftWidgetText.Res(R.string.raft_widget_could_not_refresh)
        RaftWidgetStaleReason.ChooseServer -> RaftWidgetText.Res(R.string.raft_widget_edit_to_choose_server)
        RaftWidgetStaleReason.NoConversations -> RaftWidgetText.Res(R.string.raft_widget_no_conversations)
        is RaftWidgetStaleReason.Code -> RaftWidgetText.Plain(code)
    }

/** The refresh button: "Refreshing…" while a refresh runs. */
fun raftWidgetRefreshText(refreshing: Boolean): RaftWidgetText =
    RaftWidgetText.Res(if (refreshing) R.string.raft_widget_refreshing else R.string.raft_widget_refresh)

/**
 * The footer: "Updating…" while a refresh runs, "Open Raft to start" before the first one, else
 * when it last landed ([timeLabel]): "Stale at" when the last refresh failed.
 */
fun raftWidgetUpdatedText(
    refreshing: Boolean,
    lastUpdatedAtMillis: Long,
    stale: Boolean,
    timeLabel: () -> String,
): RaftWidgetText =
    when {
        refreshing -> RaftWidgetText.Res(R.string.raft_widget_updating)
        lastUpdatedAtMillis <= 0L -> RaftWidgetText.Res(R.string.raft_widget_open_raft_to_start)
        stale -> RaftWidgetText.Res(R.string.raft_widget_stale_at, listOf(timeLabel()))
        else -> RaftWidgetText.Res(R.string.slock_status_widget_updated, listOf(timeLabel()))
    }

// Activity widget.

fun RaftWidgetState.titleText(): RaftWidgetText =
    when (this) {
        RaftWidgetState.SignedOut -> RaftWidgetText.Plain(RAFT_WIDGET_BRAND)
        is RaftWidgetState.Error ->
            when (kind) {
                RaftWidgetErrorKind.ChooseServer -> RaftWidgetText.Res(R.string.raft_widget_choose_server_title)
                RaftWidgetErrorKind.OpenRaft -> RaftWidgetText.Plain(RAFT_WIDGET_BRAND)
            }
        is RaftWidgetState.Content -> RaftWidgetText.Plain(serverName.ifBlank { RAFT_WIDGET_BRAND })
    }

/** The line under the title: the error's reason, or whether any conversation has unread messages. */
fun RaftWidgetState.detailText(): RaftWidgetText =
    when (this) {
        RaftWidgetState.SignedOut -> RaftWidgetText.Res(R.string.raft_widget_signed_out)
        is RaftWidgetState.Error ->
            staleReason?.text()
                ?: when (kind) {
                    RaftWidgetErrorKind.ChooseServer -> RaftWidgetText.Res(R.string.raft_widget_choose_server_detail)
                    RaftWidgetErrorKind.OpenRaft -> RaftWidgetText.Res(R.string.raft_widget_open_raft_to_continue)
                }
        is RaftWidgetState.Content ->
            RaftWidgetText.Res(if (rows.isEmpty()) R.string.raft_widget_no_unread_rows else R.string.raft_widget_rows_with_messages)
    }

fun RaftWidgetState.updatedText(timeLabel: () -> String): RaftWidgetText =
    raftWidgetUpdatedText(
        refreshing = refreshing,
        lastUpdatedAtMillis = lastUpdatedAtMillis,
        stale = this is RaftWidgetState.Content && staleReason != null,
        timeLabel = timeLabel,
    )

/** A conversation row's title: its name, or "Conversation". */
fun raftWidgetRowTitle(name: String): RaftWidgetText =
    name.takeIf { it.isNotBlank() }?.let(RaftWidgetText::Plain) ?: RaftWidgetText.Res(R.string.raft_widget_conversation)

/**
 * A conversation row's second line: the last message's preview, "Latest from" its sender, the
 * conversation's subtitle, else what kind of conversation it is. Arguments are already plain text.
 */
fun raftWidgetRowDetail(
    preview: String?,
    senderName: String?,
    subtitle: String?,
    kind: ConversationKind,
): RaftWidgetText =
    preview?.takeIf { it.isNotBlank() }?.let(RaftWidgetText::Plain)
        ?: senderName?.takeIf { it.isNotBlank() }?.let { RaftWidgetText.Res(R.string.raft_widget_latest_from, listOf(it)) }
        ?: subtitle?.takeIf { it.isNotBlank() }?.let(RaftWidgetText::Plain)
        ?: RaftWidgetText.Res(
            when (kind) {
                ConversationKind.DirectMessage -> R.string.raft_widget_direct_message
                ConversationKind.Thread -> R.string.raft_widget_thread
                else -> R.string.raft_widget_channel
            }
        )

// Live Agents widget.

/** The agent row shown when no agent has anything to show: who (first) and what (second). */
fun RaftAgentWidgetState.placeholderName(): RaftWidgetText =
    RaftWidgetText.Res(
        when (placeholder) {
            RaftAgentWidgetPlaceholder.NoAgents -> R.string.raft_agent_widget_no_agents
            RaftAgentWidgetPlaceholder.NoActiveAgent,
            RaftAgentWidgetPlaceholder.SignedOut -> R.string.raft_agent_widget_no_active_agent
        }
    )

fun RaftAgentWidgetState.placeholderDetail(): RaftWidgetText =
    staleReason?.let { RaftWidgetText.Res(R.string.raft_widget_last_refresh_failed, listOf(it.text())) }
        ?: RaftWidgetText.Res(
            when (placeholder) {
                RaftAgentWidgetPlaceholder.NoActiveAgent -> R.string.raft_agent_widget_offline
                RaftAgentWidgetPlaceholder.SignedOut -> R.string.raft_agent_widget_signed_out
                RaftAgentWidgetPlaceholder.NoAgents -> R.string.raft_agent_widget_no_live_activity
            }
        )

fun RaftAgentWidgetState.updatedText(timeLabel: () -> String): RaftWidgetText =
    raftWidgetUpdatedText(
        refreshing = refreshing,
        lastUpdatedAtMillis = lastUpdatedAtMillis,
        stale = staleReason != null,
        timeLabel = timeLabel,
    )

// The cache's words for the stale reasons with a line of their own (not an error code).
private const val WIRE_COULD_NOT_REFRESH = "widget:could_not_refresh"
private const val WIRE_CHOOSE_SERVER = "widget:choose_server"
private const val WIRE_NO_CONVERSATIONS = "widget:no_conversations"

/** The product name, not translated. */
private const val RAFT_WIDGET_BRAND = "Raft"
