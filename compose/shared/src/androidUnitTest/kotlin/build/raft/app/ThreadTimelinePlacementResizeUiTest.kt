package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.LocalRaftHostImeInsets
import build.raft.app.compat.ui.RaftHostImeInsets
import build.raft.app.nav.LocalRaftStackPageLeaving
import build.raft.app.thread.LazyViewportAnchorFallback
import build.raft.app.thread.LazyViewportAnchorPlacement
import build.raft.app.thread.LazyViewportAnchorTarget
import build.raft.app.thread.LazyViewportCommandId
import build.raft.app.thread.LazyViewportCommandTarget
import build.raft.app.thread.LazyViewportControllerState
import build.raft.app.thread.LazyViewportEvent
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadImeProgrammaticViewportPolicy
import build.raft.app.thread.ThreadListViewport
import build.raft.app.thread.ThreadTimelinePlacementResizeEffect
import build.raft.app.thread.ThreadTimelinePlacementTracker
import build.raft.app.thread.ThreadTimelineSlice
import build.raft.app.thread.ThreadUserScrollGeneration
import build.raft.app.thread.ThreadViewportOwnerTag
import build.raft.app.thread.reduceLazyViewport
import build.raft.app.thread.scrollThreadItemToFocusedViewport
import build.raft.app.thread.threadFocusedItemScrollOffset
import build.raft.app.thread.threadMessageListItemIndex
import build.raft.app.thread.usableViewportHeightPx
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robolectric Compose UI tests for `#wg-mobile-sync:#350` (`botiverse/slock` `#17076`):
 * composes [ThreadTimelinePlacementResizeEffect] over a real [LazyColumn] + [ThreadListViewport]
 * and verifies:
 * 1. Reversed list: viewport shrink (-150dp) and grow (+100dp) keep a focused mid-history row
 *    pinned at `forwardTop == 0` under [ThreadViewportOwnerTag.FocusedResize].
 * 2. Forward list: bottom-clamped last reply stays wholly in view on -69dp shrink (exact web
 *    `rowLiveResize.test.ts` parity).
 * 3. Negative fences: user drag (`swipeDown`), live host IME open (and subsequent close), active
 *    pagination, and `LocalRaftStackPageLeaving == true` all suppress or retire resize re-placement.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadTimelinePlacementResizeUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun testMessages(count: Int): List<RaftThreadMessageItem> =
        List(count) { i ->
            RaftThreadMessageItem(
                id = "m$i",
                authorId = "user-alice",
                authorName = "Alice",
                authorKind = "human",
                timestampLabel = "12:00",
                body = "Message $i",
            )
        }

    private fun testSlice(messages: List<RaftThreadMessageItem>, focusedId: String): ThreadTimelineSlice =
        ThreadTimelineSlice(
            status = "ready",
            originStatus = "none",
            messages = messages,
            originMessage = null,
            focusedMessageId = focusedId,
            focusedMessageRequestNonce = 1,
            canLoadMoreBefore = false,
            canLoadMoreAfter = false,
            olderMessagesStatus = "idle",
            newerMessagesStatus = "idle",
            emptyTitle = "",
            emptyDetail = null,
            sourceThreadId = null,
            alignOwnMessagesRight = false,
            compactConsecutiveMessages = false,
            surfaceKind = "channel",
            showMessageSaveAction = false,
            showReactionActions = false,
            showTaskOpenActions = false,
            showTaskStatusActions = false,
            canConvertMessagesToTasks = false,
            beginningReplyCountLabel = null,
        )

    private class Harness(
        initialHeightDp: Int,
        val reverseLayout: Boolean,
        val focusedMessageId: String,
        val rowCount: Int,
        val rowHeightDp: Int,
        val imePolicy: ThreadImeProgrammaticViewportPolicy = ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen,
    ) {
        var viewportHeightDp by mutableStateOf(initialHeightDp)
        var imeInsetDp by mutableStateOf(0f)
        val hostImeInsets = RaftHostImeInsets()
        var olderPageCycleActive by mutableStateOf(false)
        var pageLeaving by mutableStateOf(false)
        val tracker = ThreadTimelinePlacementTracker()
        val userScrollGeneration = ThreadUserScrollGeneration()
        var viewport: ThreadListViewport? = null
        var dispatchEvent: ((LazyViewportEvent) -> Unit)? = null
        val logs = mutableListOf<String>()
        val dispatchedOwners = mutableListOf<String>()
    }

    @Composable
    private fun ResizeHost(harness: Harness) {
        val slice = remember(harness.rowCount, harness.focusedMessageId) {
            testSlice(testMessages(harness.rowCount), harness.focusedMessageId)
        }
        val raw = rememberLazyListState()
        val viewport = remember(raw, harness.reverseLayout) { ThreadListViewport(raw, reverse = harness.reverseLayout) }
        harness.viewport = viewport
        val controllerState = remember { mutableStateOf(LazyViewportControllerState()) }
        val layoutGenState = remember { mutableStateOf(0L) }
        val payloadState = rememberUpdatedState(slice)
        val imeState = rememberUpdatedState(harness.imeInsetDp)
        val olderState = rememberUpdatedState(harness.olderPageCycleActive)
        val falseState = rememberUpdatedState(false)

        val dispatchViewportEvent: (LazyViewportEvent) -> Unit = { event ->
            controllerState.value = reduceLazyViewport(controllerState.value, event)
        }
        harness.dispatchEvent = dispatchViewportEvent
        suspend fun awaitSettlement(commandId: LazyViewportCommandId) {
            snapshotFlow { controllerState.value.epoch to controllerState.value.pendingCommand?.id }
                .first { (epoch, pendingId) -> epoch != commandId.epoch || pendingId == null }
        }

        // Execute Anchor commands just like ThreadMessagesListViewportCommandEffects.
        val pendingCommand = controllerState.value.pendingCommand
        LaunchedEffect(pendingCommand?.id) {
            val cmd = pendingCommand ?: return@LaunchedEffect
            when (val target = cmd.target) {
                is LazyViewportCommandTarget.Anchor -> {
                    val msgId = (target.target as? LazyViewportAnchorTarget.Message)?.messageId
                    val targetIdx = msgId?.let { slice.threadMessageListItemIndex(it) } ?: -1
                    if (targetIdx >= 0 && viewport.canScrollToThreadItem(targetIdx)) {
                        viewport.noteCommandOwner(ThreadViewportOwnerTag.FocusedReanchor)
                        harness.dispatchedOwners += viewport.activeScrollOwnerTag
                        viewport.scrollThreadItemToFocusedViewport(
                            itemIndex = targetIdx,
                            focusedScrollOffset = { itemSize, info ->
                                threadFocusedItemScrollOffset(
                                    itemSize = itemSize,
                                    viewportHeight = info.viewportEndOffset - info.viewportStartOffset,
                                )
                            },
                            commandStillCurrent = { controllerState.value.pendingCommand?.id == cmd.id },
                        )
                    }
                }
                else -> Unit
            }
            dispatchViewportEvent(LazyViewportEvent.CommandConsumed(cmd.id))
        }

        CompositionLocalProvider(
            LocalRaftStackPageLeaving provides harness.pageLeaving,
            LocalRaftHostImeInsets provides harness.hostImeInsets,
        ) {
            Box(Modifier.fillMaxWidth().height(harness.viewportHeightDp.dp)) {
                LazyColumn(
                    state = raw,
                    reverseLayout = harness.reverseLayout,
                    modifier = Modifier.fillMaxSize().testTag("timeline-list"),
                ) {
                    val count = harness.rowCount
                    items(count, key = { idx ->
                        val forwardIdx = if (harness.reverseLayout) count - 1 - idx else idx
                        "m$forwardIdx"
                    }) {
                        Box(Modifier.fillMaxWidth().height(harness.rowHeightDp.dp))
                    }
                }
            }
            ThreadTimelinePlacementResizeEffect(
                threadInstanceKey = "t-1",
                tracker = harness.tracker,
                listState = viewport,
                userScrollGeneration = harness.userScrollGeneration,
                imeProgrammaticViewportPolicy = harness.imePolicy,
                currentPayloadState = payloadState,
                viewportControllerMutableState = controllerState,
                viewportLayoutGenerationState = layoutGenState,
                liveComposerKeyboardInsetDpState = imeState,
                olderPageCycleActiveState = olderState,
                newerPageCycleActiveState = falseState,
                shareSelectionCaptureActiveState = falseState,
                adaptiveAnchorRestoreInFlightState = falseState,
                dispatchViewportEvent = dispatchViewportEvent,
                awaitViewportCommandSettlement = { id -> awaitSettlement(id) },
                diagnosticsLogger = { line -> harness.logs += line },
            )
        }
    }

    private fun ThreadListViewport.visibleItemOffset(forwardIndex: Int): Int? =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == forwardIndex }?.offset

    private fun ThreadListViewport.visibleItemBottom(forwardIndex: Int): Int? =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == forwardIndex }?.let { it.offset + it.size }

    private fun androidx.compose.ui.test.ComposeUiTest.placeFocusedInitially(harness: Harness) {
        val vp = requireNotNull(harness.viewport)
        runOnIdle {
            harness.dispatchEvent?.invoke(
                LazyViewportEvent.Anchor(
                    target = LazyViewportAnchorTarget.Message(harness.focusedMessageId),
                    offset = 0,
                    layoutGeneration = 0L,
                    placement = LazyViewportAnchorPlacement.Focused,
                    fallback = LazyViewportAnchorFallback.Free,
                )
            )
        }
        waitForIdle()
        runOnIdle {
            val rawInfo = vp.raw.layoutInfo
            harness.tracker.place(
                messageId = harness.focusedMessageId,
                placement = LazyViewportAnchorPlacement.Focused,
                userScrollGeneration = harness.userScrollGeneration.value,
                viewportHeightPx = rawInfo.usableViewportHeightPx(),
                viewportWidthPx = rawInfo.viewportSize.width,
            )
        }
        waitForIdle()
        harness.dispatchedOwners.clear()
    }

    @Test
    fun reversedListShrinkAndGrowKeepFocusedMidHistoryRowPinnedAtTop() = runRobolectricComposeUiTest {
        val harness = Harness(initialHeightDp = 600, reverseLayout = true, focusedMessageId = "m6", rowCount = 20, rowHeightDp = 80)
        setContent { ResizeHost(harness) }
        waitForIdle()
        val vp = requireNotNull(harness.viewport)

        placeFocusedInitially(harness)
        val initialOffset = vp.visibleItemOffset(6)
        assertTrue(initialOffset != null && abs(initialOffset) <= 2, "expected m6 initially at top, got $initialOffset")

        // Shrink viewport by 150dp (would clip/push m6 above top without re-placement on reverseLayout=true).
        harness.viewportHeightDp = 450
        waitForIdle()

        val offsetAfterShrink = vp.visibleItemOffset(6)
        assertTrue(offsetAfterShrink != null && abs(offsetAfterShrink) <= 2, "expected m6 at top after shrink, got $offsetAfterShrink")
        assertTrue(ThreadViewportOwnerTag.FocusedResize in harness.dispatchedOwners)
        assertTrue(harness.tracker.rePlacementSettledGeneration >= 1)

        // Grow viewport back by +100dp -> m6 stays pinned at top == 0.
        harness.viewportHeightDp = 550
        waitForIdle()
        val offsetAfterGrow = vp.visibleItemOffset(6)
        assertTrue(offsetAfterGrow != null && abs(offsetAfterGrow) <= 2, "expected m6 at top after grow, got $offsetAfterGrow")
    }

    @Test
    fun forwardListBottomClampedReplyStaysWhollyInViewOnShrink() = runRobolectricComposeUiTest {
        val harness = Harness(initialHeightDp = 600, reverseLayout = false, focusedMessageId = "m11", rowCount = 12, rowHeightDp = 72)
        setContent { ResizeHost(harness) }
        waitForIdle()
        val vp = requireNotNull(harness.viewport)

        placeFocusedInitially(harness)
        val initialBottom = vp.visibleItemBottom(11)
        assertTrue(initialBottom != null && initialBottom <= vp.layoutInfo.viewportEndOffset + 2, "expected m11 initially in view")

        // Shrink by 69dp (exact slock #17076 rowLiveResize.test.ts scenario).
        harness.viewportHeightDp = 531
        waitForIdle()

        val bottom = vp.visibleItemBottom(11)
        val viewportEnd = vp.layoutInfo.viewportEndOffset
        assertTrue(bottom != null && bottom <= viewportEnd + 2, "expected m11 wholly in view (bottom=$bottom <= end=$viewportEnd)")
        assertTrue(ThreadViewportOwnerTag.FocusedResize in harness.dispatchedOwners)
    }

    @Test
    fun userDragRetiresPlacementSoSubsequentResizeDoesNotScroll() = runRobolectricComposeUiTest {
        val harness = Harness(initialHeightDp = 600, reverseLayout = true, focusedMessageId = "m6", rowCount = 20, rowHeightDp = 80)
        setContent { ResizeHost(harness) }
        waitForIdle()

        placeFocusedInitially(harness)

        // User drags the list -> ThreadUserScrollEffect clears placement.
        onNodeWithTag("timeline-list").performTouchInput { swipeDown() }
        waitForIdle()
        assertNull(harness.tracker.current)

        val ownerCountBefore = harness.dispatchedOwners.size
        harness.viewportHeightDp = 450
        waitForIdle()
        assertEquals(ownerCountBefore, harness.dispatchedOwners.size, "resize after user drag must not scroll")
    }

    @Test
    fun liveHostImeOpenRetiresPlacementEvenUnderAllowWhileImeOpenAndCloseDoesNotYankBack() = runRobolectricComposeUiTest {
        val harness =
            Harness(
                initialHeightDp = 600,
                reverseLayout = true,
                focusedMessageId = "m6",
                rowCount = 20,
                rowHeightDp = 80,
                imePolicy = ThreadImeProgrammaticViewportPolicy.AllowWhileImeOpen,
            )
        setContent { ResizeHost(harness) }
        waitForIdle()

        placeFocusedInitially(harness)

        // Frame 0 of keyboard rise: live RaftHostImeInsets.imeBottomPx rises before delayed imeInsetDp updates.
        harness.hostImeInsets.imeBottomPx = 300
        harness.viewportHeightDp = 300
        waitForIdle()
        assertNull(harness.tracker.current)
        assertEquals(0, harness.dispatchedOwners.size)

        // IME closes + viewport grows back -> still zero FocusedResize scrolls.
        harness.hostImeInsets.imeBottomPx = 0
        harness.viewportHeightDp = 600
        waitForIdle()
        assertEquals(0, harness.dispatchedOwners.size)
    }

    @Test
    fun paginationAndPageLeavingSuppressResizeReplacement() = runRobolectricComposeUiTest {
        val harness = Harness(initialHeightDp = 600, reverseLayout = true, focusedMessageId = "m6", rowCount = 20, rowHeightDp = 80)
        setContent { ResizeHost(harness) }
        waitForIdle()

        placeFocusedInitially(harness)

        // Active pagination defers re-placement.
        harness.olderPageCycleActive = true
        harness.viewportHeightDp = 500
        waitForIdle()
        assertEquals(0, harness.dispatchedOwners.size)

        // Page leaving cancels ThreadListScrollOwnerEffect cleanly.
        harness.olderPageCycleActive = false
        harness.pageLeaving = true
        harness.viewportHeightDp = 420
        waitForIdle()
        assertEquals(0, harness.dispatchedOwners.size)
    }
}
