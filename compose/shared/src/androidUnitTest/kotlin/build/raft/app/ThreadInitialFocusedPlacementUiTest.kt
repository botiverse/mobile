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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun cachedFocusedOpenNearTailClampsCleanlyWithoutRedundantScrolls() =
        runRobolectricComposeUiTest {
            val totalMessages = 30
            val messages = (0 until totalMessages).map { "m$it" }
            val focusedIndex = 28
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
                                    retiredByHost = { false },
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
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertTrue(owner.applied)
            assertNotNull(viewport.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focusedIndex })
            assertTrue(
                viewport.isLatestItemAtRenderedBottom(totalMessages - 1),
                "near-tail focus must clamp to the rendered bottom without leaving blank space"
            )
            val scrollsBeforeConfirm = viewport.issuedScrolls
            runOnUiThread {
                scope.launch {
                    viewport.scrollThreadItemToFocusedViewport(focusedIndex) { true }
                }
                Snapshot.sendApplyNotifications()
            }
            repeat(8) { mainClock.advanceTimeByFrame() }
            assertEquals(
                scrollsBeforeConfirm,
                viewport.issuedScrolls,
                "clamped tail row must not emit redundant zero-delta scrollBy calls"
            )
            mainClock.autoAdvance = true
            waitForIdle()
        }

    @Test
    fun nonDragUserNavigationEndsOpenTraceAsUserTookOverWithoutFollowLatestShift() =
        runRobolectricComposeUiTest {
            val totalMessages = 30
            val messages = (0 until totalMessages).map { "m$it" }
            val focusedIndex = 5
            val positioned = mutableStateOf(false)
            val session =
                ThreadOpenTraceSession(
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    openKind = "focused",
                    sampleRate = 1.0,
                    recordCondition = "trace_sample",
                    viewKey = "focused-back-to-bottom",
                    nowMs = { mainClock.currentTime },
                    nowEpochMs = { 0L },
                )
            lateinit var viewport: ThreadListViewport
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
                LazyColumn(
                    state = raw,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(messages.asReversed(), key = { it }) {
                        Box(Modifier.fillMaxWidth().height(120.dp))
                    }
                }
                ThreadOpenShiftRecorderEffect(session = session, listState = viewport, typeScaleKey = 0) { inputs ->
                    inputs.status = "content"
                    inputs.messageRows = totalMessages
                    inputs.backgroundRefreshing = true
                    inputs.positioned = positioned.value
                }
            }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertEquals(0, session.shiftCount)

            // Simulate tapping "Back to bottom": record user navigation before retiring focus and scrolling to latest.
            runOnUiThread {
                viewport.noteUserNavigation()
                positioned.value = true
                scope.launch {
                    viewport.noteViewportOwner(ThreadViewportOwnerTag.FollowLatest)
                    viewport.scrollLatestItemToRenderedBottom(totalMessages - 1) { true }
                }
                Snapshot.sendApplyNotifications()
            }
            repeat(3) { mainClock.advanceTimeByFrame() }
            assertEquals(ThreadOpenEnd.UserTookOver, session.end)
            assertEquals(0, session.shiftCount, "explicit Back to bottom tap must not log a follow_latest open shift")
            mainClock.autoAdvance = true
            waitForIdle()
        }
}
