package build.raft.app.thread

import build.raft.app.base.BridgeModule
import build.raft.app.data.RaftHostInUse
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.isRaftHostVisible
import build.raft.app.messages
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/** The `thread_render` log and reconcile marker of a conversation page. Runs whenever the page composes. */
@Composable
@NonRestartableComposable
internal fun ThreadRenderReconcileEffect(
    bridge: BridgeModule,
    renderedThread: RaftThreadScreenPayload,
    renderedTimelineSlice: ThreadTimelineSlice,
    performanceGenerationCounter: ThreadPerformanceGenerationCounter,
) {
    val renderSignature = remember(
        renderedThread.instanceId,
        renderedThread.sourceThreadId,
        renderedThread.selectedTab,
        renderedThread.showReplyActions,
        renderedTimelineSlice.status,
        renderedTimelineSlice.messages.size
    ) {
        renderedTimelineSlice.toRenderSignature(
            instanceId = renderedThread.instanceId,
            sourceThreadId = renderedThread.sourceThreadId,
            selectedTab = renderedThread.selectedTab
        )
    }
    val reconcileGeneration = remember(renderSignature) {
        performanceGenerationCounter.nextReconcile()
    }
    LaunchedEffect(renderSignature, reconcileGeneration) {
        bridge.performanceMarker(
            phase = "thread_reconcile_generation",
            routeName = "kuikly_thread",
            threadId = renderSignature.sourceThreadId,
            metadata =
                mapOf(
                    "generation" to reconcileGeneration.toString(),
                    "status" to renderSignature.status,
                    "messages" to renderSignature.messagesSize.toString()
                )
        )
        bridge.log(
            "thread_render instanceId=${renderSignature.instanceId} status=${renderSignature.status} tab=${renderSignature.selectedTab} " +
                "messages=${renderSignature.messagesSize} " +
                "showReplyActions=${renderSignature.showReplyActions} replyRows=${renderSignature.replyRowsSize} " +
                "firstReply=${if (renderSignature.firstReplyId8 == null) "none" else "${renderSignature.firstReplyId8}:${renderSignature.firstReplyCount}/${renderSignature.firstReplyUnreadCount}"}"
        )
    }
}

/** Marks what is on screen as read. Takes the page's states, not their values: the effects read them live. */
@Composable
@NonRestartableComposable
internal fun ThreadVisibleReadEffects(
    bridge: BridgeModule,
    threadViewModel: SharedThreadViewModel,
    storeScope: RaftShellStoreScope,
    active: Boolean,
    renderedThread: RaftThreadScreenPayload,
    renderedTimelineSlice: ThreadTimelineSlice,
    renderSliceState: State<SharedThreadRenderSlice>,
    visibleReadTailIndexState: State<Int?>,
    threadHostForegroundState: State<Boolean>,
) {
    val renderSlice by renderSliceState
    val visibleReadTailIndex by visibleReadTailIndexState
    val threadHostForeground by threadHostForegroundState
    LaunchedEffect(visibleReadTailIndex) {
        bridge.log(
            "thread_read_tail index=${visibleReadTailIndex?.toString() ?: "null"} " +
                "firstMessageListItemIndex=${renderedTimelineSlice.firstMessageListItemIndex()} " +
                "messageCount=${renderedTimelineSlice.messages.size}"
        )
    }
    val visibleReadMarkerKey =
        remember(
            renderedThread.sourceThreadId,
            renderedTimelineSlice.status,
            renderedTimelineSlice.messages.size,
            renderedTimelineSlice.messages.lastOrNull()?.id,
            renderedTimelineSlice.messages.lastOrNull()?.seq,
            visibleReadTailIndex
        ) {
            renderedTimelineSlice.visibleReadMarkerKey(
                sourceThreadId = renderedThread.sourceThreadId,
                visibleTailIndex = visibleReadTailIndex
            )
    }
    // Seen = read only while the window is in use (desktop: focused, input in the last 3 minutes).
    // Keyed on it so coming back reads what is in front of the person then (web seenReadCatchUp).
    val hostInUse by RaftHostInUse.inUse.collectAsState()
    LaunchedEffect(visibleReadMarkerKey, active, threadHostForeground, hostInUse, renderSlice.writeActionsEnabled) {
        if (
            renderSlice.writeActionsEnabled &&
            shouldMarkThreadVisibleRead(
                routeActive = active,
                observedHostForeground = threadHostForeground,
                currentHostVisibility = isRaftHostVisible(storeScope.shellInstanceId),
                markerKey = visibleReadMarkerKey,
                hostInUse = hostInUse
            )
        ) {
            threadViewModel.dispatch(SharedThreadIntent.MarkVisibleRead(visibleReadTailIndex))
        } else if (!active || !threadHostForeground || !hostInUse) {
            // fb-4440a18c: left, covered by another page, or in the background. The store's
            // deferred / queued reads for this page must not go out now.
            threadViewModel.dispatch(SharedThreadIntent.VisibleReadHidden)
        }
    }
    DisposableEffect(threadViewModel) {
        onDispose { threadViewModel.dispatch(SharedThreadIntent.VisibleReadHidden) }
    }
}
