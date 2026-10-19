package build.raft.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun slockForwardPickerActionBottomPadding(bottomSafeArea: Dp): Dp =
    12.dp + if (bottomSafeArea > 0.dp) bottomSafeArea else 0.dp

/** Stable for one picker open. Query, retry, paging, and note steps must not replace it. */
data class SlockForwardPickerSessionKey(
    val sourceContext: String,
    val serverId: String,
    val openGeneration: Int
) {
    fun directorySessionId(): String = "$sourceContext:$serverId:$openGeneration"
}

enum class SlockForwardTargetVisualKind {
    DirectMessage,
    PublicChannel,
    PrivateChannel,
    JointChannel
}

data class SlockForwardTargetPickerItem<T>(
    val stableKey: String,
    val title: String,
    val subtitle: String,
    val visualKind: SlockForwardTargetVisualKind,
    val avatarWireValue: String? = null,
    val avatarLabel: String = title.take(1).uppercase(),
    val payload: T,
    val cacheMemberKind: build.raft.app.model.MemberKind? = null,
    val cacheMemberId: String? = null,
    val cacheServerId: String? = null
) {
    val rowKey: String
        get() = "${visualKind.name}:$stableKey"
}

enum class SlockForwardPickerLoadState {
    Idle,
    Loading,
    Loaded,
    Error
}

enum class SlockForwardPickerStep {
    Closed,
    Targets,
    Note
}

data class SlockForwardPickerFlowState(
    val sessionKey: SlockForwardPickerSessionKey? = null,
    val step: SlockForwardPickerStep = SlockForwardPickerStep.Closed,
    val query: String = "",
    val selectedKey: String? = null,
    val selectedKeys: Set<String> = emptySet(),
    val loadState: SlockForwardPickerLoadState = SlockForwardPickerLoadState.Idle,
    val errorCode: String? = null,
    val requestGeneration: Int = 0,
    val openGeneration: Int = 0,
    val appending: Boolean = false
) {
    val rowsInteractive: Boolean
        get() = step == SlockForwardPickerStep.Targets && loadState == SlockForwardPickerLoadState.Loaded

    val dismissEnabled: Boolean
        get() = step == SlockForwardPickerStep.Targets
}

sealed interface SlockForwardPickerFlowEvent {
    data class Open(val sourceContext: String, val serverId: String) : SlockForwardPickerFlowEvent
    data class QueryChanged(val query: String) : SlockForwardPickerFlowEvent
    data class Retry(val appending: Boolean) : SlockForwardPickerFlowEvent
    data object LoadMore : SlockForwardPickerFlowEvent
    data class LoadSucceeded(val generation: Int) : SlockForwardPickerFlowEvent
    data class LoadFailed(val generation: Int, val errorCode: String) : SlockForwardPickerFlowEvent
    data class TargetSelected(
        val stableKey: String,
        val renderGeneration: Int
    ) : SlockForwardPickerFlowEvent
    data object Continue : SlockForwardPickerFlowEvent
    data class ClearSelected(val stableKeys: Set<String>) : SlockForwardPickerFlowEvent
    data object NoteBack : SlockForwardPickerFlowEvent
    data object Dismiss : SlockForwardPickerFlowEvent
}

internal fun SlockForwardPickerFlowState.reduce(event: SlockForwardPickerFlowEvent): SlockForwardPickerFlowState =
    when (event) {
        is SlockForwardPickerFlowEvent.Open -> {
            val nextOpenGeneration = openGeneration + 1
            copy(
                sessionKey =
                    SlockForwardPickerSessionKey(
                        sourceContext = event.sourceContext,
                        serverId = event.serverId,
                        openGeneration = nextOpenGeneration
                    ),
                step = SlockForwardPickerStep.Targets,
                query = "",
                selectedKey = null,
                selectedKeys = emptySet(),
                loadState = SlockForwardPickerLoadState.Loading,
                errorCode = null,
                requestGeneration = requestGeneration + 1,
                openGeneration = nextOpenGeneration,
                appending = false
            )
        }
        is SlockForwardPickerFlowEvent.QueryChanged ->
            copy(
                query = event.query,
                loadState = SlockForwardPickerLoadState.Loading,
                errorCode = null,
                requestGeneration = requestGeneration + 1,
                appending = false
            )
        is SlockForwardPickerFlowEvent.Retry ->
            copy(
                loadState = SlockForwardPickerLoadState.Loading,
                errorCode = null,
                requestGeneration = requestGeneration + 1,
                appending = event.appending
            )
        SlockForwardPickerFlowEvent.LoadMore ->
            copy(
                loadState = SlockForwardPickerLoadState.Loading,
                errorCode = null,
                requestGeneration = requestGeneration + 1,
                appending = true
            )
        is SlockForwardPickerFlowEvent.LoadSucceeded ->
            if (event.generation == requestGeneration) {
                copy(loadState = SlockForwardPickerLoadState.Loaded, errorCode = null, appending = false)
            } else {
                this
            }
        is SlockForwardPickerFlowEvent.LoadFailed ->
            if (event.generation == requestGeneration) {
                copy(loadState = SlockForwardPickerLoadState.Error, errorCode = event.errorCode, appending = false)
            } else {
                this
            }
        is SlockForwardPickerFlowEvent.TargetSelected ->
            if (
                step == SlockForwardPickerStep.Targets &&
                loadState == SlockForwardPickerLoadState.Loaded &&
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
        SlockForwardPickerFlowEvent.Continue ->
            if (step == SlockForwardPickerStep.Targets && selectedKeys.isNotEmpty()) {
                copy(step = SlockForwardPickerStep.Note)
            } else {
                this
            }
        is SlockForwardPickerFlowEvent.ClearSelected ->
            copy(
                selectedKeys = selectedKeys - event.stableKeys,
                selectedKey = selectedKeys.firstOrNull { it !in event.stableKeys }
            )
        SlockForwardPickerFlowEvent.NoteBack -> copy(step = SlockForwardPickerStep.Targets)
        SlockForwardPickerFlowEvent.Dismiss ->
            copy(
                sessionKey = null,
                step = SlockForwardPickerStep.Closed,
                selectedKey = null,
                selectedKeys = emptySet(),
                loadState = SlockForwardPickerLoadState.Idle,
                errorCode = null,
                requestGeneration = requestGeneration + 1,
                appending = false
            )
    }

data class SlockForwardTargetPickerUiState<T>(
    val sessionKey: SlockForwardPickerSessionKey,
    val query: String = "",
    val items: List<SlockForwardTargetPickerItem<T>> = emptyList(),
    val selectedKey: String? = null,
    val selectedItems: List<SlockForwardTargetPickerItem<T>> = emptyList(),
    val loadState: SlockForwardPickerLoadState = SlockForwardPickerLoadState.Idle,
    val errorCode: String? = null,
    val canLoadMore: Boolean = false,
    val appending: Boolean = false,
    val interactive: Boolean = true,
    val dismissEnabled: Boolean = true,
    val requestGeneration: Int = 0
) {
    val selectedKeys: Set<String>
        get() = selectedItems.mapTo(linkedSetOf()) { it.stableKey } + listOfNotNull(selectedKey)
}

sealed interface SlockForwardTargetPickerIntent<out T> {
    data class QueryChanged(val query: String) : SlockForwardTargetPickerIntent<Nothing>
    data object Retry : SlockForwardTargetPickerIntent<Nothing>
    data object LoadMore : SlockForwardTargetPickerIntent<Nothing>
    data object Dismiss : SlockForwardTargetPickerIntent<Nothing>
    data object Continue : SlockForwardTargetPickerIntent<Nothing>
    data class RemoveSelection(val stableKey: String) : SlockForwardTargetPickerIntent<Nothing>
    data class TargetSelected<T>(
        val item: SlockForwardTargetPickerItem<T>,
        val renderGeneration: Int
    ) : SlockForwardTargetPickerIntent<T>
}

data class SlockForwardPickerRequestIdentity(
    val serverId: String,
    val sessionKey: SlockForwardPickerSessionKey,
    val query: String,
    val generation: Int
)

internal fun SlockForwardPickerRequestIdentity.accepts(
    serverId: String,
    sessionKey: SlockForwardPickerSessionKey,
    query: String,
    generation: Int
): Boolean =
    this.serverId == serverId &&
        this.sessionKey == sessionKey &&
        this.query == query &&
        this.generation == generation

internal fun SlockForwardTargetVisualKind.iconName(): SlockLucideIconName? =
    when (this) {
        SlockForwardTargetVisualKind.DirectMessage -> null
        SlockForwardTargetVisualKind.PublicChannel -> SlockLucideIconName.Hash
        SlockForwardTargetVisualKind.PrivateChannel -> SlockLucideIconName.Lock
        SlockForwardTargetVisualKind.JointChannel -> SlockLucideIconName.GitBranch
    }
