package build.raft.app

import kotlin.test.Test
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
}
