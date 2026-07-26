package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareState
import build.raft.app.data.externalShareDirectorySessionId
import build.raft.app.data.externalShareSelectionKey
import kotlinx.coroutines.delay

internal const val ExternalShareSearchDebounceMillis = 300L

internal data class ExternalSharePickerSurface(
    val picker: SlockForwardTargetPickerUiState<build.raft.app.data.ForwardMessageTarget>,
    val selectionErrorCode: String? = null
)

@Composable
internal fun ExternalShareTargetPickerSurface(
    state: ExternalShareState.SelectingTargets,
    onIntent: (ExternalShareIntent) -> Unit
) {
    var inputQuery by remember(state.envelope.token, state.serverId) { mutableStateOf(state.query) }
    LaunchedEffect(state.envelope.token, state.serverId, inputQuery) {
        if (inputQuery != state.query) {
            delay(ExternalShareSearchDebounceMillis)
            onIntent(ExternalShareIntent.Search(inputQuery))
        }
    }
    val surface = state.toExternalSharePickerSurface(queryOverride = inputQuery)
    val contentCount = state.envelope.items.size + if (state.envelope.text.isNotBlank() || state.envelope.subject.isNotBlank()) 1 else 0
    SlockForwardTargetPicker(
        messageCount = contentCount,
        skippedCount = 0,
        nestedForwardCount = 0,
        taskLinkedCount = 0,
        state = surface.picker,
        onIntent = { pickerIntent ->
            if (pickerIntent is SlockForwardTargetPickerIntent.QueryChanged) {
                inputQuery = pickerIntent.query
            } else {
                state.toExternalShareIntent(pickerIntent)?.let(onIntent)
            }
        },
        // Keep the native input mounted while directory rows reload. Replacing it with the
        // non-interactive placeholder on every debounced request drops Android IME focus.
        searchInputInteractive = true,
        topContent = {
            SlockServerChoice(
                title = "Server",
                options = state.serverOptions,
                selectedId = state.serverId,
                optionId = { server -> server.id },
                optionLabel = { server -> server.title },
                enabled = !state.loading,
                onSelect = { server -> onIntent(ExternalShareIntent.SelectServer(server.id)) }
            )
        }
    )
}

internal fun ExternalShareState.SelectingTargets.toExternalSharePickerSurface(
    queryOverride: String = query
): ExternalSharePickerSurface {
    val items = targets.map { it.toSlockForwardTargetPickerItem() }
    val selected = selectedTargets.map { it.toSlockForwardTargetPickerItem() }
    val queryPending = queryOverride != query
    return ExternalSharePickerSurface(
        picker =
            SlockForwardTargetPickerUiState(
                sessionKey =
                    SlockForwardPickerSessionKey(
                        sourceContext = "external-share:${envelope.token}",
                        serverId = serverId,
                        openGeneration = 1
                    ),
                query = queryOverride,
                items = items,
                selectedItems = selected,
                loadState =
                    when {
                        queryPending || loading -> SlockForwardPickerLoadState.Loading
                        errorCode != null -> SlockForwardPickerLoadState.Error
                        else -> SlockForwardPickerLoadState.Loaded
                    },
                errorCode = errorCode,
                canLoadMore = nextCursor != null,
                appending = appending,
                interactive = !queryPending && !loading && errorCode == null,
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
                ?.let { target ->
                    ExternalShareIntent.ToggleTarget(
                        target = target,
                        sessionId = externalShareDirectorySessionId(),
                        renderGeneration = requestGeneration
                    )
                }
        is SlockForwardTargetPickerIntent.TargetSelected -> {
            if (loading || errorCode != null || intent.renderGeneration != requestGeneration) {
                null
            } else {
                val live = targets.firstOrNull { it.externalShareSelectionKey() == intent.item.stableKey }
                if (live == intent.item.payload) {
                    ExternalShareIntent.ToggleTarget(
                        target = live,
                        sessionId = externalShareDirectorySessionId(),
                        renderGeneration = requestGeneration
                    )
                } else {
                    null
                }
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
