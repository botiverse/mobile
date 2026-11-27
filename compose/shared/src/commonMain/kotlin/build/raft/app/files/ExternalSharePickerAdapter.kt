package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.resources.*
import build.raft.app.thread.RaftForwardPickerLoadState
import build.raft.app.thread.RaftForwardPickerSessionKey
import build.raft.app.thread.RaftForwardTargetPicker
import build.raft.app.thread.RaftForwardTargetPickerIntent
import build.raft.app.thread.RaftForwardTargetPickerUiState
import build.raft.app.thread.toRaftForwardTargetPickerItem
import build.raft.app.data.ExternalShareIntent
import build.raft.app.data.ExternalShareServerOption
import build.raft.app.data.ExternalShareState
import build.raft.app.data.externalShareDirectorySessionId
import build.raft.app.data.externalShareSelectionKey
import kotlinx.coroutines.delay

internal const val ExternalShareSearchDebounceMillis = 300L

internal data class ExternalSharePickerSurface(
    val picker: RaftForwardTargetPickerUiState<build.raft.app.data.ForwardMessageTarget>,
    val selectionErrorCode: String? = null
)

@Composable
internal fun ExternalShareTargetPickerSurface(
    state: ExternalShareState.SelectingTargets,
    onIntent: (ExternalShareIntent) -> Unit
) {
    var inputQuery by remember(state.envelope.token, state.serverId) { mutableStateOf(state.query) }
    val bottomSafeArea = raftBottomSafeAreaPadding()
    LaunchedEffect(state.envelope.token, state.serverId, inputQuery) {
        if (inputQuery != state.query) {
            delay(ExternalShareSearchDebounceMillis)
            onIntent(ExternalShareIntent.Search(inputQuery))
        }
    }
    val surface = state.toExternalSharePickerSurface(queryOverride = inputQuery)
    val contentCount = state.envelope.items.size + if (state.envelope.text.isNotBlank() || state.envelope.subject.isNotBlank()) 1 else 0
    val subtitle =
        if (contentCount == 1) {
            stringResource(Res.string.external_share_items_selected_one)
        } else {
            stringResource(Res.string.external_share_items_selected, contentCount)
        }
    RaftForwardTargetPicker(
        messageCount = contentCount,
        skippedCount = 0,
        nestedForwardCount = 0,
        state = surface.picker,
        onIntent = { pickerIntent ->
            if (pickerIntent is RaftForwardTargetPickerIntent.QueryChanged) {
                inputQuery = pickerIntent.query
            } else {
                state.toExternalShareIntent(pickerIntent)?.let(onIntent)
            }
        },
        // Keep the native input mounted while directory rows reload. Replacing it with the
        // non-interactive placeholder on every debounced request drops Android IME focus.
        searchInputInteractive = true,
        bottomSafeArea = bottomSafeArea,
        subtitleOverride = subtitle,
        topContent = {
            RaftServerSwitcherControl(
                servers = state.toExternalShareServerSwitcherItems(),
                selectedServerId = state.serverId,
                accessibilityLabel = stringResource(Res.string.settings_server_title),
                enabled = !state.loading && !state.handoffInProgress,
                onSelect = { serverId -> onIntent(ExternalShareIntent.SelectServer(serverId)) }
            )
        }
    )
}

internal fun ExternalShareServerOption.toRaftServerSwitcherItem(): RaftServerSwitcherItem =
    RaftServerSwitcherItem(
        id = id,
        name = title,
        slug = slug.orEmpty(),
        avatarUrl = avatarUrl,
        publicPageLive = publicPageLive
    )

internal fun ExternalShareState.SelectingTargets.toExternalShareServerSwitcherItems(): List<RaftServerSwitcherItem> =
    serverOptions.map { it.toRaftServerSwitcherItem() }

internal fun ExternalShareState.SelectingTargets.toExternalSharePickerSurface(
    queryOverride: String = query
): ExternalSharePickerSurface {
    val items = targets.map { it.toRaftForwardTargetPickerItem() }
    val conversationItems = conversationTargets.map { it.toRaftForwardTargetPickerItem() }
    val selected = selectedTargets.map { it.toRaftForwardTargetPickerItem() }
    val queryPending = queryOverride != query
    return ExternalSharePickerSurface(
        picker =
            RaftForwardTargetPickerUiState(
                sessionKey =
                    RaftForwardPickerSessionKey(
                        sourceContext = "external-share:${envelope.token}",
                        serverId = serverId,
                        openGeneration = 1
                    ),
                query = queryOverride,
                items = items,
                selectedItems = selected,
                loadState =
                    when {
                        queryPending || loading -> RaftForwardPickerLoadState.Loading
                        errorCode != null -> RaftForwardPickerLoadState.Error
                        else -> RaftForwardPickerLoadState.Loaded
                    },
                errorCode = errorCode,
                canLoadMore = nextCursor != null,
                appending = appending,
                interactive = !queryPending && !loading && !handoffInProgress && errorCode == null,
                dismissEnabled = true,
                requestGeneration = requestGeneration,
                conversationItems = conversationItems
            ),
        selectionErrorCode = selectionErrorCode
    )
}

/**
 * Converts the reusable picker contract back into store intents while rechecking the live render
 * generation and target identity. A queued callback from an older query therefore fails closed.
 */
internal fun ExternalShareState.SelectingTargets.toExternalShareIntent(
    intent: RaftForwardTargetPickerIntent<build.raft.app.data.ForwardMessageTarget>
): ExternalShareIntent? =
    when (intent) {
        is RaftForwardTargetPickerIntent.QueryChanged -> ExternalShareIntent.Search(intent.query)
        RaftForwardTargetPickerIntent.Retry -> ExternalShareIntent.Search(query)
        RaftForwardTargetPickerIntent.LoadMore -> ExternalShareIntent.LoadMore
        RaftForwardTargetPickerIntent.Dismiss -> ExternalShareIntent.Cancel
        RaftForwardTargetPickerIntent.Continue ->
            ExternalShareIntent.Continue.takeIf {
                selectedTargets.size == 1 && !loading && !handoffInProgress && errorCode == null
            }
        is RaftForwardTargetPickerIntent.RemoveSelection ->
            selectedTargets
                .firstOrNull { it.externalShareSelectionKey() == intent.stableKey }
                ?.let { target ->
                    ExternalShareIntent.ToggleTarget(
                        target = target,
                        sessionId = externalShareDirectorySessionId(),
                        renderGeneration = requestGeneration
                    )
                }
        is RaftForwardTargetPickerIntent.TargetSelected -> {
            if (loading || handoffInProgress || errorCode != null || intent.renderGeneration != requestGeneration) {
                null
            } else {
                val live =
                    targets.firstOrNull { it.externalShareSelectionKey() == intent.item.stableKey }
                        ?: conversationTargets.takeIf { query.isBlank() }
                            ?.firstOrNull { it.externalShareSelectionKey() == intent.item.stableKey }
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
