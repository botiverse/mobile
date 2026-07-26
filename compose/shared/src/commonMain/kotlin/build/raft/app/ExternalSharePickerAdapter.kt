package build.raft.app

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareState
import build.raft.app.data.externalShareDirectorySessionId
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
        },
        topContent = {
            ExternalShareServerSelector(
                state = state,
                onServerSelected = { serverId -> onIntent(ExternalShareIntent.SelectServer(serverId)) }
            )
        }
    )
}

@Composable
private fun ExternalShareServerSelector(
    state: ExternalShareState.SelectingTargets,
    onServerSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Server",
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextMutedGray
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.serverOptions.size, key = { index -> state.serverOptions[index].id }) { index ->
                val server = state.serverOptions[index]
                SlockModalActionButton(
                    label = server.title,
                    onClick = { onServerSelected(server.id) },
                    color = if (server.id == state.serverId) SlockColor.AccentPink else SlockColor.SurfaceWhite,
                    enabled = !state.loading && server.id != state.serverId
                )
            }
        }
    }
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
