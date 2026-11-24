package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.Res
import build.raft.app.resources.StringDesc
import build.raft.app.resources.desc
import build.raft.app.resources.thread_state_loading_older_replies
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI behavior for inline timeline gap rows (#proj-mobile:#358):
 * - Stand-in initial refresh (`durableLastKnown && !mainLoadFailed`) stays collapsed at 0 px for
 *   [ThreadStandInInlineGapRevealDelayMs] so routine slow opens and brief reconnects never insert
 *   blank whitespace or flash a mid-list spinner between two messages, then reveals dedicated
 *   missing-message / missing-reply copy (never "Loading older…").
 * - Failing to offline immediately switches to the tappable offline missing-messages row, and
 *   tapping it reveals the loading row immediately.
 * - Online auto-fill preserves its [ThreadStandInLoadingRevealDelayMs] collapsed delay across
 *   `idle -> loading`, while explicit Retry clicks reveal the loading row immediately.
 * - Top-of-list [ThreadHistoryStatusRow] keeps its default `reserveSpaceBeforeReveal = true`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadTimelineGapRowUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var originalLocaleType = StringDesc.localeType

    @BeforeTest
    fun setUp() {
        originalLocaleType = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun tearDown() {
        StringDesc.localeType = originalLocaleType
    }

    private fun ComposeUiTest.advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    @Test
    fun standInGapCollapsesDuringGraceWindowThenRevealsDedicatedMissingReplyCopy() = runRobolectricComposeUiTest {
        mainClock.autoAdvance = false
        var measuredHeightPx = -1
        var gap by mutableStateOf(ThreadTimelineGap(10L, 30L, status = "idle"))
        var durableLastKnown by mutableStateOf(true)
        var mainLoadFailed by mutableStateOf(false)
        val fillRequests = mutableListOf<ThreadTimelineGap>()

        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { measuredHeightPx = it.size.height }
                ) {
                    ThreadTimelineGapRow(
                        gap = gap,
                        durableLastKnown = durableLastKnown,
                        mainLoadFailed = mainLoadFailed,
                        onFillGap = { fillRequests += it },
                        replyThread = true,
                    )
                }
            }
        }

        advance(50)
        assertEquals(0, measuredHeightPx, "inline stand-in gap must reserve 0 px before reveal delay")
        assertTrue(fillRequests.isEmpty(), "stand-in initial refresh must not trigger separate gap fill")
        onNodeWithText("Loading missing replies…").assertDoesNotExist()
        onNodeWithText("Loading older replies…").assertDoesNotExist()

        // Still collapsed well past the old 600 ms top-of-list delay.
        advance(1_500)
        assertEquals(0, measuredHeightPx, "inline stand-in gap stays collapsed within 2s grace window")
        onNodeWithText("Loading missing replies…").assertDoesNotExist()

        // Once the grace window elapses on a stalled refresh, dedicated missing-reply copy appears.
        advance(500)
        assertTrue(measuredHeightPx > 0, "inline stand-in gap reveals after grace window")
        onNodeWithText("Loading missing replies…").assertExists()
        onNodeWithText("Loading older replies…").assertDoesNotExist()

        // Monotonic reveal latch: changing revealDelayMs (2000 ms -> 600 ms via durableLastKnown flip)
        // must never collapse an already-visible spinner back to 0 px.
        durableLastKnown = false
        advance(50)
        assertTrue(measuredHeightPx > 0, "revealed loading row must stay revealed when delay changes")
        onNodeWithText("Loading missing replies…").assertExists()
        durableLastKnown = true
        advance(16)

        // Main load failure switches immediately to tappable offline missing-reply copy.
        mainLoadFailed = true
        advance(50)
        onNodeWithText("Missing replies are not available offline").assertExists()
        onNodeWithText("Older messages are not available offline").assertDoesNotExist()

        // Tapping the offline row requests a fill and shows the loading spinner immediately.
        val requestsBeforeOfflineTap = fillRequests.size
        onNodeWithText("Missing replies are not available offline").performClick()
        advance(16)
        assertEquals(requestsBeforeOfflineTap + 1, fillRequests.size)
        assertEquals(ThreadTimelineGap(10L, 30L, status = "idle"), fillRequests.last())
        gap = gap.copy(status = "loading")
        advance(16)
        onNodeWithText("Loading missing replies…").assertExists()
    }

    @Test
    fun onlineAutoFillKeepsCollapsedDelayAcrossIdleToLoadingAndAsyncManualRetryRevealsImmediately() = runRobolectricComposeUiTest {
        mainClock.autoAdvance = false
        var measuredHeightPx = -1
        var gap by mutableStateOf(ThreadTimelineGap(10L, 30L, status = "idle"))
        val fillRequests = mutableListOf<ThreadTimelineGap>()

        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { measuredHeightPx = it.size.height }
                ) {
                    ThreadTimelineGapRow(
                        gap = gap,
                        durableLastKnown = false,
                        mainLoadFailed = false,
                        onFillGap = { target -> fillRequests += target },
                        replyThread = false,
                    )
                }
            }
        }

        // Auto-fill runs on mount; store transitions idle -> loading asynchronously, yet row stays collapsed until 600 ms.
        advance(50)
        assertEquals(1, fillRequests.size)
        gap = gap.copy(status = "loading")
        advance(50)
        assertEquals(0, measuredHeightPx, "fast online auto-fill stays collapsed before 600 ms")
        onNodeWithText("Loading missing messages…").assertDoesNotExist()

        advance(400)
        assertEquals(0, measuredHeightPx, "delay did not restart or bypass before 600 ms")

        advance(200)
        assertTrue(measuredHeightPx > 0)
        onNodeWithText("Loading missing messages…").assertExists()
        onNodeWithText("Loading older messages…").assertDoesNotExist()

        // Failure shows dedicated Retry button (never "Retry older messages").
        gap = gap.copy(status = "error")
        advance(50)
        onNodeWithText("Retry missing messages").assertExists()
        onNodeWithText("Retry older messages").assertDoesNotExist()

        // Tapping Retry dispatches async; even after an intermediate frame still in status="error",
        // flipping status="loading" reveals loading copy immediately (0 ms delay).
        onNodeWithText("Retry missing messages").performClick()
        advance(32)
        assertEquals(2, fillRequests.size)
        gap = gap.copy(status = "loading")
        advance(16)
        assertTrue(measuredHeightPx > 0)
        onNodeWithText("Loading missing messages…").assertExists()

        // If the fill is later cancelled back to idle (revealDelayMs 0 ms -> 600 ms), the
        // monotonic reveal latch keeps the already-visible loading row from collapsing and popping.
        gap = gap.copy(status = "idle")
        advance(50)
        assertTrue(measuredHeightPx > 0, "revealed loading row must stay visible on 0 ms -> 600 ms delay change")
        onNodeWithText("Loading missing messages…").assertExists()
    }

    @Test
    fun topOfListHistoryStatusRowStillReservesSpaceBeforeRevealByDefault() = runRobolectricComposeUiTest {
        mainClock.autoAdvance = false
        var measuredHeightPx = -1

        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { measuredHeightPx = it.size.height }
                ) {
                    ThreadHistoryStatusRow(
                        spec =
                            ThreadHistoryStatusSpec(
                                label = Res.string.thread_state_loading_older_replies.desc(),
                                loading = true,
                            ),
                    )
                }
            }
        }

        advance(50)
        assertTrue(
            measuredHeightPx > 0,
            "top-of-list history header must keep reserving height during its alpha-0 reveal delay",
        )
    }
}
