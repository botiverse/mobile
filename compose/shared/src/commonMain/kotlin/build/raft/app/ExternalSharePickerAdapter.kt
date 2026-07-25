package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareState
import build.raft.app.data.externalShareSelectionKey

internal data class ExternalSharePickerSurface(
    val picker: SlockForwardTargetPickerUiState<build.raft.app.data.ForwardMessageTarget>,
    val selectionErrorCode: String? = null
)

@Composable
internal fun ExternalShareTargetPickerSurface(
    state: ExternalShareState.SelectingTargets,
    onIntent: (ExternalShareIntent) -> Unit
) {
    val surface = state.toExternalSharePickerSurface()
    val contentCount = state.envelope.items.size + if (state.envelope.text.isNotBlank() || state.envelope.subject.isNotBlank()) 1 else 0
    SlockForwardTargetPicker(
        messageCount = contentCount,
        skippedCount = 0,
        nestedForwardCount = 0,
        taskLinkedCount = 0,
        state = surface.picker,
        onIntent = { pickerIntent ->
            state.toExternalShareIntent(pickerIntent)?.let(onIntent)
        }
    )
}

internal fun ExternalShareState.SelectingTargets.toExternalSharePickerSurface(): ExternalSharePickerSurface {
    val items = targets.map { it.toSlockForwardTargetPickerItem() }
    val selected = selectedTargets.map { it.toSlockForwardTargetPickerItem() }
    return ExternalSharePickerSurface(
        picker =
            SlockForwardTargetPickerUiState(
                sessionKey =
                    SlockForwardPickerSessionKey(
                        sourceContext = "external-share:${envelope.token}",
                        serverId = serverId,
                        openGeneration = 1
                    ),
                query = query,
                items = items,
                selectedItems = selected,
                loadState =
                    when {
                        loading -> SlockForwardPickerLoadState.Loading
                        errorCode != null -> SlockForwardPickerLoadState.Error
                        else -> SlockForwardPickerLoadState.Loaded
                    },
                errorCode = errorCode,
                canLoadMore = nextCursor != null,
                appending = appending,
                interactive = !loading && errorCode == null,
                dismissEnabled = true,
                requestGeneration = requestGeneration
            ),
        selectionErrorCode = selectionErrorCode
    )
}

/**
 * Converts the reusable picker contract back into store intents while rechecking the live render
 * generation and target identity. A queued callback from an older query therefore fails closed.
 */
internal fun ExternalShareState.SelectingTargets.toExternalShareIntent(
    intent: SlockForwardTargetPickerIntent<build.raft.app.data.ForwardMessageTarget>
): ExternalShareIntent? =
    when (intent) {
        is SlockForwardTargetPickerIntent.QueryChanged -> ExternalShareIntent.Search(intent.query)
        SlockForwardTargetPickerIntent.Retry -> ExternalShareIntent.Search(query)
        SlockForwardTargetPickerIntent.LoadMore -> ExternalShareIntent.LoadMore
        SlockForwardTargetPickerIntent.Dismiss -> ExternalShareIntent.Cancel
        SlockForwardTargetPickerIntent.Continue ->
            ExternalShareIntent.Continue.takeIf { selectedTargets.isNotEmpty() && !loading && errorCode == null }
        is SlockForwardTargetPickerIntent.RemoveSelection ->
            selectedTargets
                .firstOrNull { it.externalShareSelectionKey() == intent.stableKey }
                ?.let(ExternalShareIntent::ToggleTarget)
        is SlockForwardTargetPickerIntent.TargetSelected -> {
            if (loading || errorCode != null || intent.renderGeneration != requestGeneration) {
                null
            } else {
                val live = targets.firstOrNull { it.externalShareSelectionKey() == intent.item.stableKey }
                if (live == intent.item.payload) ExternalShareIntent.ToggleTarget(live) else null
            }
        }
    }

internal data class ExternalShareConfirmationUiState(
    val selectedTargets: List<SlockForwardTargetPickerItem<build.raft.app.data.ForwardMessageTarget>>,
    val contentLines: List<String>,
    val comment: String,
    val canSend: Boolean
)

internal fun ExternalShareState.Confirming.toExternalShareConfirmationUiState(): ExternalShareConfirmationUiState =
    ExternalShareConfirmationUiState(
        selectedTargets = selectedTargets.map { it.toSlockForwardTargetPickerItem() },
        contentLines =
            buildList {
                envelope.subject.trim().takeIf(String::isNotBlank)?.let(::add)
                envelope.text.trim().takeIf(String::isNotBlank)?.let(::add)
                envelope.items.forEach { item -> add(item.displayName) }
            },
        comment = comment,
        canSend = selectedTargets.isNotEmpty() && envelope.hasContent()
    )
