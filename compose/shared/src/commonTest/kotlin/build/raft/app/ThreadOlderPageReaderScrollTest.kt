package build.raft.app

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.pointer.PointerEventType
import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Task #253 (artin, the Mac app): a long thread scrolled up to its "Loading older messages…" row never
 * loaded the older replies. The older page waits for the reader's own scroll (task #1303), and only a
 * finger's drag was ever counted as one.
 */
class ThreadOlderPageReaderScrollTest {
    @Test
    fun aWheelOrTrackpadScrollUpToTheHistoryRowAsksForTheOlderPage() = runTest {
        val reader = ThreadUserScrollGeneration()
        // The list's older-page check observes the count: at the top of the list a scroll moves no row and
        // changes no layout, so the count itself has to wake the check.
        val checked = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            snapshotFlow { reader.observed }.collect { checked += it }
        }

        // A pointer moving over the list (desktop hover) and a sideways swipe move no row: not a scroll.
        reader.onListPointerEvent(PointerEventType.Move, scrollDeltaY = 0f)
        reader.onListPointerEvent(PointerEventType.Scroll, scrollDeltaY = 0f)
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals(listOf(0), checked, "no scroll yet")

        // What the desktop app delivers for a trackpad or mouse wheel scroll up (an iPad or an Android
        // device with one too): a Scroll event with no button down, which the touch listener never sees.
        reader.onListPointerEvent(PointerEventType.Scroll, scrollDeltaY = -1f)
        Snapshot.sendApplyNotifications()
        runCurrent()

        assertEquals(listOf(0, 1), checked, "the older-page check re-ran on the scroll")
        assertTrue(
            shouldThreadAutoLoadOlderMessages(
                boundaryIndex = 1,
                firstVisibleIndex = 0,
                userScrollGeneration = reader.observed,
                lastSettledUserScrollGeneration = 0,
            ),
            "the history row is in view after the reader's scroll: the older page is asked for",
        )
    }

    @Test
    fun aListTooShortToScrollAsksForTheOlderPageWithoutAScroll() {
        // Every row fits: the history row shows from the start, and no scroll can bring it any closer, so
        // waiting for one would leave "Loading older messages…" up for good (the web loads then too).
        assertTrue(
            shouldThreadAutoLoadOlderMessages(
                boundaryIndex = 1,
                firstVisibleIndex = 0,
                userScrollGeneration = 0,
                lastSettledUserScrollGeneration = 0,
                listScrolls = false,
            )
        )
    }

    @Test
    fun loadingOlderStatusShowsOnlyWhileLoadingAndUsesRepliesCopyInThreads() {
        val channelIdle =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                canLoadMoreBefore = true,
                olderMessagesStatus = "idle",
            )
        val threadIdle =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                canLoadMoreBefore = true,
                olderMessagesStatus = "idle",
            )
        assertNull(threadOlderMessagesStatusSpec(channelIdle), "idle channel with more older history shows no loading row")
        assertNull(threadOlderMessagesStatusSpec(threadIdle), "idle reply thread with more older history shows no loading row")

        val channelLoading = assertNotNull(threadOlderMessagesStatusSpec(channelIdle.copy(olderMessagesStatus = "loading")))
        assertEquals(Res.string.thread_state_loading_older.desc(), channelLoading.label)
        assertTrue(channelLoading.loading)

        val threadLoading = assertNotNull(threadOlderMessagesStatusSpec(threadIdle.copy(olderMessagesStatus = "loading")))
        assertEquals(Res.string.thread_state_loading_older_replies.desc(), threadLoading.label)
        assertTrue(threadLoading.loading)

        val threadStandInLoading =
            assertNotNull(
                threadOlderMessagesStatusSpec(
                    RaftThreadScreenPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        canLoadMoreBefore = false,
                        durableLastKnown = true,
                        durableReachedOldest = false,
                        mainLoadFailed = false,
                    )
                )
            )
        assertEquals(Res.string.thread_state_loading_older_replies.desc(), threadStandInLoading.label)
        assertTrue(threadStandInLoading.loading)

        val channelBeginning =
            assertNotNull(
                threadOlderMessagesStatusSpec(
                    RaftThreadScreenPayload(
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        canLoadMoreBefore = false,
                    )
                )
            )
        assertEquals(Res.string.thread_state_beginning_conversation.desc(), channelBeginning.label)

        val replyThreadBeginning =
            assertNotNull(
                threadOlderMessagesStatusSpec(
                    RaftThreadScreenPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        canLoadMoreBefore = false,
                    )
                )
            )
        assertEquals(Res.string.thread_state_beginning_replies.desc(), replyThreadBeginning.label)

        val taskBeginning =
            assertNotNull(
                threadOlderMessagesStatusSpec(
                    RaftThreadScreenPayload(
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = RaftTaskConversationChannelKind,
                        canLoadMoreBefore = false,
                    )
                )
            )
        assertEquals(Res.string.thread_state_beginning_replies.desc(), taskBeginning.label)

        val taskLoading =
            assertNotNull(
                threadOlderMessagesStatusSpec(
                    RaftThreadScreenPayload(
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = RaftTaskConversationChannelKind,
                        canLoadMoreBefore = true,
                        olderMessagesStatus = "loading",
                    )
                )
            )
        assertEquals(Res.string.thread_state_loading_older_replies.desc(), taskLoading.label)
        assertTrue(taskLoading.loading)
    }
}
