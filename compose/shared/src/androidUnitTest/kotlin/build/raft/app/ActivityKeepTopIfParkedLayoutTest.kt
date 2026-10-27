package build.raft.app

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hands b205bd89 (artin: "tap back to the top, then it jumps to the second screen") on a real
 * LazyColumn. LazyList keeps the first visible row by key, so a change that moves the head row
 * down carries a list parked at the top with it. The root rule [keepTopIfParked], applied in the
 * same snapshot as the change (as SharedInboxPanel's settleTopEdge does), keeps it at the top;
 * a reader who scrolled away keeps their row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class ActivityKeepTopIfParkedLayoutTest {
    @get:Rule val activities = RobolectricComposeActivityRule()

    private val rows = (0 until 80).map { "row$it" }

    /** The head row moved below 20 others: a refresh/bump that leaves it on the second screen. */
    private fun List<String>.headMovedDown(by: Int = 20): List<String> = drop(1).take(by) + first() + drop(by + 1)

    private fun frames(count: Int = 60) = repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private class Harness(val items: androidx.compose.runtime.MutableState<List<String>>, val listState: LazyListState)

    private fun mount(firstVisibleItemIndex: Int = 0): Harness {
        val harness = Harness(mutableStateOf(rows), LazyListState(firstVisibleItemIndex = firstVisibleItemIndex))
        activities.launch().setContent {
            val list by harness.items
            LazyColumn(state = harness.listState, modifier = Modifier.fillMaxSize()) {
                items(list, key = { it }) { BasicText(it, Modifier.fillMaxWidth().height(60.dp)) }
            }
        }
        frames(10)
        return harness
    }

    /** The change, and (with [keepTop]) the rule in the same snapshot, before the next measure. */
    private fun change(harness: Harness, next: List<String>, keepTop: Boolean): Boolean {
        val previousHead = harness.items.value.first()
        harness.items.value = next
        val pinned = if (keepTop) harness.listState.keepTopIfParked(previousHead, next.first()) else false
        frames()
        return pinned
    }

    @Test
    fun `without the rule a list parked at the top follows the head row down (the reported jump)`() {
        val harness = mount()
        change(harness, rows.headMovedDown(), keepTop = false)
        assertEquals(20, harness.listState.firstVisibleItemIndex)
    }

    @Test
    fun `a list parked at the top stays at the top when the head row moves down`() {
        val harness = mount()
        assertTrue(change(harness, rows.headMovedDown(), keepTop = true))
        assertEquals(0, harness.listState.firstVisibleItemIndex)
        assertEquals(0, harness.listState.firstVisibleItemScrollOffset)
        assertEquals("row1", harness.listState.layoutInfo.visibleItemsInfo.first().key)
    }

    @Test
    fun `after a scroll to the top lands, the next head move keeps the top too`() {
        val harness = mount(firstVisibleItemIndex = 40)
        CoroutineScope(AndroidUiDispatcher.Main).launch { harness.listState.animateScrollToTopExpressive() }
        frames()
        assertEquals(0, harness.listState.firstVisibleItemIndex, "the scroll-to-top lands at the top")
        assertTrue(change(harness, rows.headMovedDown(), keepTop = true))
        assertEquals(0, harness.listState.firstVisibleItemIndex)
    }

    @Test
    fun `a reader scrolled away keeps their row`() {
        val harness = mount()
        CoroutineScope(AndroidUiDispatcher.Main).launch {
            harness.listState.scroll(MutatePriority.UserInput) { scrollBy(400f) }
        }
        frames()
        val readerKey = harness.listState.layoutInfo.visibleItemsInfo.first().key
        assertTrue(harness.listState.firstVisibleItemIndex > 0)
        assertFalse(change(harness, harness.items.value.headMovedDown(), keepTop = true))
        assertEquals(readerKey, harness.listState.layoutInfo.visibleItemsInfo.first().key, "the reader's row stays put")
    }

    @Test
    fun `an unchanged head is not touched`() {
        val harness = mount()
        assertFalse(change(harness, rows.take(1) + rows.drop(2), keepTop = true))
        assertEquals(0, harness.listState.firstVisibleItemIndex)
    }
}
