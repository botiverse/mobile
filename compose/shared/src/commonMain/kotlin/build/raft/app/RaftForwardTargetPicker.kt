package build.raft.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.DataResult
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.data.ForwardTargetSearchUpdate
import build.raft.app.data.ForwardTargetServerSearchDebounceMillis
import build.raft.app.model.ServerId
import kotlinx.coroutines.delay

internal fun raftForwardPickerActionBottomPadding(bottomSafeArea: Dp): Dp =
    12.dp + if (bottomSafeArea > 0.dp) bottomSafeArea else 0.dp

/** Stable for one picker open. Query, retry, paging, and note steps must not replace it. */
data class RaftForwardPickerSessionKey(
    val sourceContext: String,
    val serverId: String,
    val openGeneration: Int
) {
    fun directorySessionId(): String = "$sourceContext:$serverId:$openGeneration"
}

enum class RaftForwardTargetVisualKind {
    DirectMessage,
    PublicChannel,
    PrivateChannel,
    JointChannel,
    /** slock #12621: a thread destination (raft-ui `ThreadIcon` in a small IconTile). */
    Thread,
    /** slock #12621: a task discussion destination (lucide `list-todo`). */
    Task
}

data class RaftForwardTargetPickerItem<T>(
    val stableKey: String,
    val title: String,
    val subtitle: String,
    val visualKind: RaftForwardTargetVisualKind,
    val avatarWireValue: String? = null,
    val avatarLabel: String = title.toRaftAvatarLabel(),
    val payload: T,
    val cacheMemberKind: build.raft.app.model.MemberKind? = null,
    val cacheMemberId: String? = null,
    val cacheServerId: String? = null,
    /** The thread / task the picker was opened from: a trailing soft muted "Current" badge. */
    val current: Boolean = false,
    /** A channel row's channel id: its glyph is the Home row's ([RaftChannelKinds], web `ChannelIcon`). */
    val channelId: String? = null,
) {
    val rowKey: String
        get() = "${visualKind.name}:$stableKey"
}

enum class RaftForwardPickerLoadState {
    Idle,
    Loading,
    Loaded,
    Error
}

enum class RaftForwardPickerStep {
    Closed,
    Targets,
    Note
}

data class RaftForwardPickerFlowState(
    val sessionKey: RaftForwardPickerSessionKey? = null,
    val step: RaftForwardPickerStep = RaftForwardPickerStep.Closed,
    val query: String = "",
    val selectedKey: String? = null,
    val selectedKeys: Set<String> = emptySet(),
    val loadState: RaftForwardPickerLoadState = RaftForwardPickerLoadState.Idle,
    val errorCode: String? = null,
    val requestGeneration: Int = 0,
    val openGeneration: Int = 0,
    val appending: Boolean = false
) {
    val rowsInteractive: Boolean
        get() = step == RaftForwardPickerStep.Targets && loadState == RaftForwardPickerLoadState.Loaded

    val dismissEnabled: Boolean
        get() = step == RaftForwardPickerStep.Targets
}

sealed interface RaftForwardPickerFlowEvent {
    data class Open(val sourceContext: String, val serverId: String) : RaftForwardPickerFlowEvent
    data class QueryChanged(val query: String) : RaftForwardPickerFlowEvent
    data class Retry(val appending: Boolean) : RaftForwardPickerFlowEvent
    data object LoadMore : RaftForwardPickerFlowEvent
    data class LoadSucceeded(val generation: Int) : RaftForwardPickerFlowEvent
    data class LoadFailed(val generation: Int, val errorCode: String) : RaftForwardPickerFlowEvent
    data class TargetSelected(
        val stableKey: String,
        val renderGeneration: Int
    ) : RaftForwardPickerFlowEvent
    data object Continue : RaftForwardPickerFlowEvent
    data class ClearSelected(val stableKeys: Set<String>) : RaftForwardPickerFlowEvent
    data object NoteBack : RaftForwardPickerFlowEvent
    data object Dismiss : RaftForwardPickerFlowEvent
}

internal fun RaftForwardPickerFlowState.reduce(event: RaftForwardPickerFlowEvent): RaftForwardPickerFlowState =
    when (event) {
        is RaftForwardPickerFlowEvent.Open -> {
            val nextOpenGeneration = openGeneration + 1
            copy(
                sessionKey =
                    RaftForwardPickerSessionKey(
                        sourceContext = event.sourceContext,
                        serverId = event.serverId,
                        openGeneration = nextOpenGeneration
                    ),
                step = RaftForwardPickerStep.Targets,
                query = "",
                selectedKey = null,
                selectedKeys = emptySet(),
                loadState = RaftForwardPickerLoadState.Loading,
                errorCode = null,
                requestGeneration = requestGeneration + 1,
                openGeneration = nextOpenGeneration,
                appending = false
            )
        }
        is RaftForwardPickerFlowEvent.QueryChanged ->
            copy(
                query = event.query,
                loadState = RaftForwardPickerLoadState.Loading,
                errorCode = null,
                requestGeneration = requestGeneration + 1,
                appending = false
            )
        is RaftForwardPickerFlowEvent.Retry ->
            copy(
                loadState = RaftForwardPickerLoadState.Loading,
                errorCode = null,
                requestGeneration = requestGeneration + 1,
                appending = event.appending
            )
        RaftForwardPickerFlowEvent.LoadMore ->
            copy(
                loadState = RaftForwardPickerLoadState.Loading,
                errorCode = null,
                requestGeneration = requestGeneration + 1,
                appending = true
            )
        is RaftForwardPickerFlowEvent.LoadSucceeded ->
            if (event.generation == requestGeneration) {
                copy(loadState = RaftForwardPickerLoadState.Loaded, errorCode = null, appending = false)
            } else {
                this
            }
        is RaftForwardPickerFlowEvent.LoadFailed ->
            if (event.generation == requestGeneration) {
                copy(loadState = RaftForwardPickerLoadState.Error, errorCode = event.errorCode, appending = false)
            } else {
                this
            }
        is RaftForwardPickerFlowEvent.TargetSelected ->
            if (
                step == RaftForwardPickerStep.Targets &&
                loadState == RaftForwardPickerLoadState.Loaded &&
                event.renderGeneration == requestGeneration
            ) {
                val nextSelectedKeys = selectedKeys.toMutableSet().apply {
                    if (!add(event.stableKey)) remove(event.stableKey)
                }
                copy(
                    selectedKey = nextSelectedKeys.firstOrNull(),
                    selectedKeys = nextSelectedKeys
                )
            } else {
                this
            }
        RaftForwardPickerFlowEvent.Continue ->
            if (step == RaftForwardPickerStep.Targets && selectedKeys.isNotEmpty()) {
                copy(step = RaftForwardPickerStep.Note)
            } else {
                this
            }
        is RaftForwardPickerFlowEvent.ClearSelected ->
            copy(
                selectedKeys = selectedKeys - event.stableKeys,
                selectedKey = selectedKeys.firstOrNull { it !in event.stableKeys }
            )
        RaftForwardPickerFlowEvent.NoteBack -> copy(step = RaftForwardPickerStep.Targets)
        RaftForwardPickerFlowEvent.Dismiss ->
            copy(
                sessionKey = null,
                step = RaftForwardPickerStep.Closed,
                selectedKey = null,
                selectedKeys = emptySet(),
                loadState = RaftForwardPickerLoadState.Idle,
                errorCode = null,
                requestGeneration = requestGeneration + 1,
                appending = false
            )
    }

data class RaftForwardTargetPickerUiState<T>(
    val sessionKey: RaftForwardPickerSessionKey,
    val query: String = "",
    val items: List<RaftForwardTargetPickerItem<T>> = emptyList(),
    val selectedKey: String? = null,
    val selectedItems: List<RaftForwardTargetPickerItem<T>> = emptyList(),
    val loadState: RaftForwardPickerLoadState = RaftForwardPickerLoadState.Idle,
    val errorCode: String? = null,
    val canLoadMore: Boolean = false,
    val appending: Boolean = false,
    val interactive: Boolean = true,
    val dismissEnabled: Boolean = true,
    val requestGeneration: Int = 0,
    /**
     * slock #12621 "Threads & tasks": up to 4 rows shown above Recent while the query is empty.
     * Empty (none, or the request failed) = no group, the picker as before.
     */
    val conversationItems: List<RaftForwardTargetPickerItem<T>> = emptyList()
) {
    /** The group shows only with an empty query (web `ForwardComposerTargetList`, not search mode). */
    val visibleConversationItems: List<RaftForwardTargetPickerItem<T>>
        get() = if (query.isBlank()) conversationItems else emptyList()

    val selectedKeys: Set<String>
        get() = selectedItems.mapTo(linkedSetOf()) { it.stableKey } + listOfNotNull(selectedKey)
}

sealed interface RaftForwardTargetPickerIntent<out T> {
    data class QueryChanged(val query: String) : RaftForwardTargetPickerIntent<Nothing>
    data object Retry : RaftForwardTargetPickerIntent<Nothing>
    data object LoadMore : RaftForwardTargetPickerIntent<Nothing>
    data object Dismiss : RaftForwardTargetPickerIntent<Nothing>
    data object Continue : RaftForwardTargetPickerIntent<Nothing>
    data class RemoveSelection(val stableKey: String) : RaftForwardTargetPickerIntent<Nothing>
    data class TargetSelected<T>(
        val item: RaftForwardTargetPickerItem<T>,
        val renderGeneration: Int
    ) : RaftForwardTargetPickerIntent<T>
}

data class RaftForwardPickerRequestIdentity(
    val serverId: String,
    val sessionKey: RaftForwardPickerSessionKey,
    val query: String,
    val generation: Int
)

internal fun RaftForwardPickerRequestIdentity.accepts(
    serverId: String,
    sessionKey: RaftForwardPickerSessionKey,
    query: String,
    generation: Int
): Boolean =
    this.serverId == serverId &&
        this.sessionKey == sessionKey &&
        this.query == query &&
        this.generation == generation

/** Generic picker-load debounce. Target search debounces its server request itself ([raftForwardPickerProgressiveSearch]). */
internal const val RaftForwardPickerSearchDebounceMillis = 120L

/**
 * One picker load behind the debounce and stale-token gates. The caller's coroutine is
 * cancelled when a newer query/generation restarts it (a keystroke during the debounce
 * never reaches [load]); [isCurrent] drops a result that a newer request superseded.
 * Returns null when this request is stale.
 */
internal suspend fun <R : Any> raftForwardPickerGatedLoad(
    debounce: Boolean,
    isCurrent: () -> Boolean,
    debounceMillis: Long = RaftForwardPickerSearchDebounceMillis,
    load: suspend () -> R
): R? {
    if (debounce) delay(debounceMillis)
    if (!isCurrent()) return null
    val result = load()
    return result.takeIf { isCurrent() }
}

/**
 * One picker search keystroke: local interim matches go to [onInterim] immediately, the server's
 * answer (after [serverDebounceMillis]) is returned. The caller restarts this on every keystroke,
 * which cancels the previous in-flight server request; [isCurrent] additionally drops any update
 * a newer query superseded, so an older query's results are never shown. Returns null when stale.
 */
internal suspend fun raftForwardPickerProgressiveSearch(
    repository: ForwardMessageTargetRepository,
    serverId: ServerId,
    query: String,
    limit: Int,
    isCurrent: () -> Boolean,
    onInterim: (List<ForwardMessageTarget>) -> Unit,
    serverDebounceMillis: Long = ForwardTargetServerSearchDebounceMillis
): DataResult<List<ForwardMessageTarget>>? {
    var final: DataResult<List<ForwardMessageTarget>>? = null
    repository.searchTargetsProgressively(serverId, query, limit, serverDebounceMillis).collect { update ->
        if (!isCurrent()) return@collect
        when (update) {
            is ForwardTargetSearchUpdate.Interim -> onInterim(update.targets)
            is ForwardTargetSearchUpdate.Final -> final = update.result
        }
    }
    return final?.takeIf { isCurrent() }
}

/** A channel row's wire type, for the one glyph chooser ([raftChannelKindGlyph]); null for other rows. */
internal fun RaftForwardTargetVisualKind.channelType(): String? =
    when (this) {
        RaftForwardTargetVisualKind.PublicChannel -> "channel"
        RaftForwardTargetVisualKind.PrivateChannel -> "private"
        RaftForwardTargetVisualKind.JointChannel -> JointChannelKindWireValue
        else -> null
    }

internal fun RaftForwardTargetVisualKind.iconName(): RaftLucideIconName? =
    when (this) {
        RaftForwardTargetVisualKind.DirectMessage -> null
        RaftForwardTargetVisualKind.PublicChannel,
        RaftForwardTargetVisualKind.PrivateChannel,
        RaftForwardTargetVisualKind.JointChannel -> raftChannelKindGlyph(channelType()).iconName
        RaftForwardTargetVisualKind.Thread -> null
        RaftForwardTargetVisualKind.Task -> RaftLucideIconName.ListTodo
    }

/**
 * web ForwardComposerMobile: the bar's section eyebrow ("Recent") is hidden while the list leads
 * with its own "Threads & tasks" and "Recent" titles (empty query). It stays when it names the
 * multi-select count instead.
 */
internal fun <T> RaftForwardTargetPickerUiState<T>.forwardPickerTopEyebrowVisible(multiSelect: Boolean): Boolean =
    visibleConversationItems.isEmpty() || (multiSelect && selectedKeys.isNotEmpty())
