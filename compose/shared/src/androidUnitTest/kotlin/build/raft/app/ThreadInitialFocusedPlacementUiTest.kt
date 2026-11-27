package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import build.raft.app.thread.LazyViewportAnchorPlacement
import build.raft.app.thread.ThreadInitialFocusedPlacementOwner
import build.raft.app.thread.ThreadListViewport
import build.raft.app.thread.ThreadOpenEnd
import build.raft.app.thread.ThreadOpenShiftRecorderEffect
import build.raft.app.thread.ThreadOpenStableQuietMs
import build.raft.app.thread.ThreadOpenTraceSession
import build.raft.app.thread.ThreadViewportOwnerTag
import build.raft.app.thread.key
import build.raft.app.thread.scrollThreadItemToFocusedViewport
import build.raft.app.thread.shouldArmThreadInitialFocusedPlacement
import build.raft.app.thread.threadInitialFirstVisibleItemIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UI tests for #proj-mobile:#258 on a real `LazyColumn(reverseLayout = true)`:
 * 1. Cached focused open places the focused message at forward top 0 before Frame 1 draws, with
 *    zero `focused_reanchor` shifts in [ThreadOpenShiftRecorderEffect].
 * 2. Cached focused open near the tail clamps cleanly to the rendered bottom and subsequent
 *    [scrollThreadItemToFocusedViewport] issues zero extra scrolls into the clamped edge.
 * 3. Non-drag user navigation ([ThreadListViewport.noteUserNavigation], e.g. Back to bottom tap)
 *    ends [ThreadOpenShiftRecorderEffect] as [ThreadOpenEnd.UserTookOver] without recording a
 *    false `follow_latest` open shift.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ThreadInitialFocusedPlacementUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun cachedFocusedOpenPlacesTargetOnFirstFrameWithoutOpenTraceShift() =
        runRobolectricComposeUiTest {
            val totalMessages = 30
            val messages = (0 until totalMessages).map { "m$it" }
            val focusedIndex = 10
            val positioned = mutableStateOf(false)
            val refreshing = mutableStateOf(true)
            val session =
                ThreadOpenTraceSession(
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    openKind = "focused",
                    sampleRate = 1.0,
                    recordCondition = "trace_sample",
                    viewKey = "focused-open-mid",
                    nowMs = { mainClock.currentTime },
                    nowEpochMs = { 0L },
                )
            lateinit var viewport: ThreadListViewport
            lateinit var owner: ThreadInitialFocusedPlacementOwner
            lateinit var scope: CoroutineScope
            mainClock.autoAdvance = false
            setContent {
                val raw =
                    rememberLazyListState(
                        initialFirstVisibleItemIndex =
                            threadInitialFirstVisibleItemIndex(
                                reverseLayout = true,
                                latestItemIndex = totalMessages - 1,
                                lazyItemCount = totalMessages,
                                focusedItemIndex = focusedIndex,
                            )
                    )
                viewport = remember(raw) { ThreadListViewport(raw, reverse = true) }
                scope = rememberCoroutineScope()
                owner =
                    remember(viewport) {
                        ThreadInitialFocusedPlacementOwner(
                            listState = viewport,
                            scope = scope,
                            armed = true,
                        )
                    }
                LazyColumn(
                    state = raw,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.Bottom,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                owner.afterLayout(
                                    owns = { !viewport.isScrollInProgress && !viewport.isRetired },
                                    retiredByHost = { positioned.value },
                                    focusedForwardIndex = { focusedIndex },
                                    placement = { LazyViewportAnchorPlacement.Focused },
                                    minBottomReservePx = 100,
                                )
                            },
                ) {
                    items(messages.asReversed(), key = { it }) {
                        Box(Modifier.fillMaxWidth().height(120.dp))
                    }
                }
                ThreadOpenShiftRecorderEffect(session = session, listState = viewport, typeScaleKey = 0) { inputs ->
                    inputs.status = "content"
                    inputs.messageRows = totalMessages
                    inputs.backgroundRefreshing = refreshing.value
                    inputs.positioned = positioned.value
                }
            }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertTrue(owner.applied, "initial focused placement should apply before Frame 1 draws")
            val focusedItem =
                viewport.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
            assertNotNull(focusedItem, "focused item m$focusedIndex must be visible on Frame 1")
            assertEquals(0, focusedItem.offset, "focused item must land at forward top 0 on Frame 1")
            assertEquals(0, session.shiftCount, "no jump shift should be recorded on Frame 1")

            // Running the controller's focused anchor confirmation afterward is a zero-scroll no-op.
            val scrollsBeforeConfirm = viewport.issuedScrolls
            runOnUiThread {
                scope.launch {
                    viewport.scrollThreadItemToFocusedViewport(focusedIndex) { true }
                }
                positioned.value = true
                refreshing.value = false
                Snapshot.sendApplyNotifications()
            }
            repeat(8) { mainClock.advanceTimeByFrame() }
            assertEquals(scrollsBeforeConfirm, viewport.issuedScrolls, "already aligned: no redundant scroll")
            assertEquals(0, session.shiftCount, "no shift recorded after focused confirmation")

            mainClock.advanceTimeBy(ThreadOpenStableQuietMs + 100L)
            repeat(2) { mainClock.advanceTimeByFrame() }
            assertEquals(ThreadOpenEnd.Stable, session.end)
            mainClock.autoAdvance = true
            waitForIdle()
        }

    @Test
    fun retiredByHostPreventsPlacementAfterUserTakeover() =
        runRobolectricComposeUiTest {
            val totalMessages = 30
            val messages = (0 until totalMessages).map { "m$it" }
            val focusedIndex = 10
            val ownsPlacement = mutableStateOf(false)
            val hostRetired = mutableStateOf(false)
            val triggerRelayout = mutableStateOf(0)
            lateinit var viewport: ThreadListViewport
            lateinit var owner: ThreadInitialFocusedPlacementOwner
            mainClock.autoAdvance = false
            setContent {
                val raw =
                    rememberLazyListState(
                        initialFirstVisibleItemIndex =
                            threadInitialFirstVisibleItemIndex(
                                reverseLayout = true,
                                latestItemIndex = totalMessages - 1,
                                lazyItemCount = totalMessages,
                                focusedItemIndex = focusedIndex,
                            )
                    )
                viewport = remember(raw) { ThreadListViewport(raw, reverse = true) }
                val scope = rememberCoroutineScope()
                owner =
                    remember(viewport) {
                        ThreadInitialFocusedPlacementOwner(
                            listState = viewport,
                            scope = scope,
                            armed = true,
                        )
                    }
                val relayoutKey = triggerRelayout.value
                LazyColumn(
                    state = raw,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.Bottom,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                owner.afterLayout(
                                    owns = { ownsPlacement.value && relayoutKey >= 0 },
                                    retiredByHost = { hostRetired.value },
                                    focusedForwardIndex = { focusedIndex },
                                    placement = { LazyViewportAnchorPlacement.Focused },
                                    minBottomReservePx = 100,
                                )
                            },
                ) {
                    items(messages.asReversed(), key = { it }) {
                        Box(Modifier.fillMaxWidth().height(120.dp))
                    }
                }
            }
            repeat(2) { mainClock.advanceTimeByFrame() }
            assertFalse(owner.applied)
            assertFalse(owner.retired)

            // User drag / navigation retires the owner; subsequent layout with owns=true must not jump.
            runOnUiThread {
                hostRetired.value = true
                ownsPlacement.value = true
                triggerRelayout.value = 1
                Snapshot.sendApplyNotifications()
            }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertTrue(owner.retired)
            assertFalse(owner.applied)
            assertEquals(0, viewport.issuedScrolls)
            mainClock.autoAdvance = true
            waitForIdle()
        }

    @Test
    fun replyThreadOriginOnlyFrameStaysArmedUntilCachedRepliesArrive() =
        runRobolectricComposeUiTest {
            // Frame 1: reply thread paints only origin + bottom padding (messages empty) while focused on r9.
            val rows = mutableStateOf(listOf("origin", "bottom-padding"))
            val focusedIndex = mutableStateOf(-1)
            lateinit var viewport: ThreadListViewport
            lateinit var owner: ThreadInitialFocusedPlacementOwner
            mainClock.autoAdvance = false
            setContent {
                val raw = rememberLazyListState(initialFirstVisibleItemIndex = 0)
                viewport = remember(raw) { ThreadListViewport(raw, reverse = true) }
                val scope = rememberCoroutineScope()
                owner =
                    remember(viewport) {
                        ThreadInitialFocusedPlacementOwner(
                            listState = viewport,
                            scope = scope,
                            armed =
                                shouldArmThreadInitialFocusedPlacement(
                                    reverseLayout = true,
                                    adaptiveAnchorRestoreInFlight = false,
                                    initialFocusedItemIndex = -1,
                                    messagesEmpty = true,
                                    focusedMessageId = "r9",
                                ),
                        )
                    }
                val afterLayout: () -> Unit = {
                    owner.afterLayout(
                        owns = { !viewport.isScrollInProgress && !viewport.isRetired },
                        retiredByHost = { false },
                        focusedForwardIndex = { focusedIndex.value },
                        placement = { LazyViewportAnchorPlacement.Focused },
                        minBottomReservePx = 100,
                    )
                }
                val currentRows = rows.value
                LazyColumn(
                    state = raw,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.Bottom,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { afterLayout() },
                ) {
                    items(currentRows.asReversed(), key = { it }) { id ->
                        val height = if (id == "bottom-padding") 16.dp else 120.dp
                        Box(Modifier.fillMaxWidth().height(height).onGloballyPositioned { afterLayout() })
                    }
                }
            }
            repeat(2) { mainClock.advanceTimeByFrame() }
            assertFalse(owner.applied, "origin-only frame has no target row yet")
            assertFalse(owner.retired, "owner must stay armed while waiting for cached replies")

            // Frame 2: cached replies arrive (origin + r0..r15 + bottom-padding); r9 (forward index 10)
            // has 6 replies + bottom-padding below it (> 800dp viewport) and sits inside the initial
            // reversed bottom window, so the still-armed owner aligns it to forward top 0 before draw.
            runOnUiThread {
                rows.value = listOf("origin") + (0 until 16).map { "r$it" } + "bottom-padding"
                focusedIndex.value = 10
                Snapshot.sendApplyNotifications()
            }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertTrue(owner.applied, "owner should align focused reply once replies lay out")
            assertTrue(owner.retired, "owner retires after single-shot placement")
            val placed = viewport.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 10 }
            assertNotNull(placed, "focused reply r9 must be visible")
            assertEquals(0, placed.offset, "focused reply must land at forward top 0")
            assertEquals(ThreadViewportOwnerTag.FocusedReanchor, viewport.lastScrollOwnerTag)
            assertEquals(1, viewport.lastScrollOwnerAttempt)
            mainClock.autoAdvance = true
            waitForIdle()
        }
}
