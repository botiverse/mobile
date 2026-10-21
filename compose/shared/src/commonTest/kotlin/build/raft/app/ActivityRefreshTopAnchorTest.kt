package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Task #47 (Hands e7b1f0ce): cold start paints the cached Activity snapshot, then the fresh
 * first page lands. An untouched list must stay anchored at the top so the newest rows are
 * visible; a reader who already scrolled is never moved (they get the pill instead).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityRefreshTopAnchorTest {
    private val cachedKeys = listOf("c3", "c2", "c1")
    private val freshKeys = listOf("n2", "n1", "c3", "c2", "c1")

    private fun decide(owner: ActivityEntryTopAnchorOwner, scrolling: Boolean, index: Int = 0, offset: Int = 0) =
        activityShouldAnchorTopOnCanonicalSnapshot(
            entryOwnerArmed = owner.armed,
            pullRefreshOwnsTop = false,
            parkedAtTop = activityListParkedAtTop(index, offset),
            scrollInProgress = scrolling,
            genuineTopUpdate = activityHasNewTopRow(cachedKeys, freshKeys),
        )

    @Test
    fun `fresh first page landing on an untouched cold-start list anchors the top`() = runTest {
        val scrollInProgress = MutableStateFlow(false)
        val owner = ActivityEntryTopAnchorOwner()
        owner.arm()
        val fence = launch { owner.fenceUserScroll(scrollInProgress) }
        // Cached snapshot is on screen; the refresh takes a while in virtual time.
        val refresh = launch { delay(1_500) }
        advanceTimeBy(1_501)
        runCurrent()
        assertTrue(refresh.isCompleted)
        assertTrue(owner.armed, "no user scroll happened, the entry session must still own the top")
        assertTrue(decide(owner, scrolling = scrollInProgress.value), "untouched list must anchor at the newest row")
        fence.cancel()
    }

    @Test
    fun `a user scroll before the refresh lands retires the owner and the list is not moved`() = runTest {
        val scrollInProgress = MutableStateFlow(false)
        val owner = ActivityEntryTopAnchorOwner()
        owner.arm()
        val fence = launch { owner.fenceUserScroll(scrollInProgress) }
        runCurrent()
        advanceTimeBy(300)
        scrollInProgress.value = true // drag
        runCurrent()
        advanceTimeBy(200)
        scrollInProgress.value = false // the reader settled on row 4
        runCurrent()
        advanceTimeBy(1_000) // refresh lands
        assertFalse(owner.armed, "the drag must retire entry ownership")
        assertFalse(decide(owner, scrolling = false, index = 4, offset = 12), "a scrolled reader must not be moved")
        // Even if they scrolled back to the exact top, the session is over: pill, no jump.
        assertFalse(decide(owner, scrolling = false))
        fence.cancel()
    }

    @Test
    fun `active scroll at decision time is fenced even while armed`() = runTest {
        val owner = ActivityEntryTopAnchorOwner()
        owner.arm()
        assertFalse(decide(owner, scrolling = true))
    }

    @Test
    fun `re-activation re-arms after a retire`() = runTest {
        val owner = ActivityEntryTopAnchorOwner()
        owner.arm()
        owner.retire()
        assertFalse(decide(owner, scrolling = false))
        owner.arm() // returned from a thread / app foreground
        assertTrue(decide(owner, scrolling = false))
    }

    @Test
    fun `pull to refresh owns the top edge without the entry session`() {
        assertTrue(
            activityShouldAnchorTopOnCanonicalSnapshot(
                entryOwnerArmed = false,
                pullRefreshOwnsTop = true,
                parkedAtTop = true,
                scrollInProgress = false,
                genuineTopUpdate = true,
            )
        )
    }

    @Test
    fun `only a genuine head update counts - never pagination or in-place updates or removing the head`() {
        assertTrue(activityHasNewTopRow(cachedKeys, freshKeys), "new rows above the old head")
        assertTrue(activityHasNewTopRow(cachedKeys, listOf("c1", "c3", "c2")), "row bumped to the top")
        assertTrue(activityHasNewTopRow(cachedKeys, listOf("n1", "c2", "c1")), "head replaced by a new row")
        assertFalse(activityHasNewTopRow(cachedKeys, cachedKeys + listOf("c0", "c-1")), "pagination append")
        assertFalse(activityHasNewTopRow(cachedKeys, cachedKeys), "in-place/read mutation")
        assertFalse(activityHasNewTopRow(cachedKeys, listOf("c2", "c1")), "head marked done")
        assertFalse(activityHasNewTopRow(emptyList(), freshKeys), "first presentation")
        val owner = ActivityEntryTopAnchorOwner().apply { arm() }
        assertFalse(
            activityShouldAnchorTopOnCanonicalSnapshot(
                entryOwnerArmed = owner.armed,
                pullRefreshOwnsTop = false,
                parkedAtTop = true,
                scrollInProgress = false,
                genuineTopUpdate = activityHasNewTopRow(cachedKeys, cachedKeys + "c0"),
            ),
            "pagination never owns a top anchor",
        )
    }

    @Test
    fun `parked at top means the first row fully at the top`() {
        assertTrue(activityListParkedAtTop(0, 0))
        assertFalse(activityListParkedAtTop(0, 40))
        assertFalse(activityListParkedAtTop(3, 0))
    }

    @Test
    fun `a list parked at the top follows new activity after the entry session retired`() {
        // iOS 1.15.0: the reader had scrolled once (session retired) and was back at the top;
        // new activity must keep them on the newest rows, not raise the pill.
        val follow =
            activityShouldAnchorTopOnCanonicalSnapshot(
                entryOwnerArmed = false,
                pullRefreshOwnsTop = false,
                parkedAtTop = activityListParkedAtTop(0, 0),
                scrollInProgress = false,
                genuineTopUpdate = activityHasNewTopRow(cachedKeys, freshKeys),
                followParkedTop = true,
            )
        assertTrue(follow)
        fun followAt(index: Int, offset: Int, scrolling: Boolean) =
            activityShouldAnchorTopOnCanonicalSnapshot(
                entryOwnerArmed = false,
                pullRefreshOwnsTop = false,
                parkedAtTop = activityListParkedAtTop(index, offset),
                scrollInProgress = scrolling,
                genuineTopUpdate = true,
                followParkedTop = true,
            )
        assertFalse(followAt(index = 4, offset = 0, scrolling = false), "a scrolled reader is never moved")
        assertFalse(followAt(index = 0, offset = 30, scrolling = false), "first row partly scrolled away")
        assertFalse(followAt(index = 0, offset = 0, scrolling = true), "a drag in progress is fenced")
    }

    @Test
    fun `new top row keys are the rows that landed above the previous head`() {
        assertEquals(setOf("n2", "n1"), activityNewTopRowKeys(cachedKeys, freshKeys))
        assertEquals(setOf("c1"), activityNewTopRowKeys(cachedKeys, listOf("c1", "c3", "c2")))
        assertEquals(setOf("n1"), activityNewTopRowKeys(cachedKeys, listOf("n1", "c2", "c1")))
        assertEquals(emptySet(), activityNewTopRowKeys(cachedKeys, cachedKeys + "c0"))
        assertEquals(emptySet(), activityNewTopRowKeys(cachedKeys, listOf("c2", "c1")))
        assertEquals(emptySet(), activityNewTopRowKeys(emptyList(), freshKeys))
    }

    @Test
    fun `the new activity pill shows only while a landed row is above the viewport`() {
        // Conversation-list layout: PINNED label, 4 pinned rows, ALL CONVERSATIONS label, rest.
        val lazyKeys =
            listOf(
                "activity-list-section:Pinned", "p1", "p2", "p3", "p4",
                "activity-list-section:AllConversations", "n1", "c3", "c2", "c1",
            )
        val landed = setOf("n1")
        assertFalse(activityNewRowsAboveViewport(landed, lazyKeys, firstVisibleItemIndex = 0), "list at the top")
        assertFalse(activityNewRowsAboveViewport(landed, lazyKeys, firstVisibleItemIndex = 2), "row below, within or under the viewport")
        assertFalse(activityNewRowsAboveViewport(landed, lazyKeys, firstVisibleItemIndex = 6), "row is the first visible item")
        assertTrue(activityNewRowsAboveViewport(landed, lazyKeys, firstVisibleItemIndex = 8), "row scrolled past, above the viewport")
        assertFalse(activityNewRowsAboveViewport(emptySet(), lazyKeys, firstVisibleItemIndex = 8))
        assertTrue(activityNewRowsAboveViewport(landed, lazyKeys, firstVisibleItemIndex = 50), "index past the key list")
    }

    @Test
    fun `the new activity pill counts the unseen landed rows above the viewport`() {
        val lazyKeys =
            listOf(
                "activity-list-section:Pinned", "p1",
                "activity-list-section:AllConversations", "n1", "n2", "n3", "c3", "c2", "c1",
            )
        val landed = setOf("n1", "n2", "n3")
        assertEquals(0, activityNewRowsAboveViewportCount(landed, lazyKeys, firstVisibleItemIndex = 0), "list at the top")
        assertEquals(1, activityNewRowsAboveViewportCount(landed, lazyKeys, firstVisibleItemIndex = 4), "n2, n3 in or below the viewport")
        assertEquals(3, activityNewRowsAboveViewportCount(landed, lazyKeys, firstVisibleItemIndex = 7))
        assertEquals(3, activityNewRowsAboveViewportCount(landed, lazyKeys, firstVisibleItemIndex = 50), "index past the key list")
        // The reader scrolled n2 into view and back down: it was seen, so it no longer counts.
        val unseen = activityUnseenNewRowKeys(landed, visibleItemKeys = setOf("n1", "n2"))
        assertEquals(setOf("n3"), unseen)
        assertEquals(1, activityNewRowsAboveViewportCount(unseen, lazyKeys, firstVisibleItemIndex = 7))
        // Nothing landed is visible: the same set comes back (no state write).
        assertTrue(activityUnseenNewRowKeys(landed, setOf("c1", "c2")) === landed)
    }
}
