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
import kotlin.test.assertTrue

/**
 * Hands b205bd89 on a real LazyColumn: after the Activity scroll-to-top lands, a change that moves
 * the head row down (and does not pass through the canonical anchor) must not carry the list off
 * the top; once the reader scrolls, the list is theirs again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class ActivityScrollToTopOwnerLayoutTest {
    @get:Rule val activities = RobolectricComposeActivityRule()

    private val rows = (0 until 80).map { "row$it" }

    /** The head row moved below 20 others: a refresh/bump that leaves it on the second screen. */
    private fun List<String>.headMovedDown(by: Int = 20): List<String> = drop(1).take(by) + first() + drop(by + 1)

    private fun frames(count: Int = 60) = repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private class Harness(val items: androidx.compose.runtime.MutableState<List<String>>, val listState: LazyListState)

    private fun mount(owner: ActivityScrollToTopOwner?): Harness {
        val harness = Harness(mutableStateOf(rows), LazyListState(firstVisibleItemIndex = 40))
        activities.launch().setContent {
            val list by harness.items
            if (owner != null) ActivityScrollToTopOwnerEffect(harness.listState, owner)
            LazyColumn(state = harness.listState, modifier = Modifier.fillMaxSize()) {
                items(list, key = { it }) { BasicText(it, Modifier.fillMaxWidth().height(60.dp)) }
            }
        }
        frames(10)
        return harness
    }

    private fun scrollToTop(harness: Harness, owner: ActivityScrollToTopOwner?) {
        CoroutineScope(AndroidUiDispatcher.Main).launch {
            if (owner != null) {
                harness.listState.animateActivityScrollToTop(owner)
            } else {
                harness.listState.animateScrollToTopExpressive()
            }
        }
        frames()
        assertEquals(0, harness.listState.firstVisibleItemIndex, "the scroll-to-top lands at the top")
    }

    @Test
    fun `without an owner the parked list follows the head row down (the reported jump)`() {
        val harness = mount(owner = null)
        scrollToTop(harness, owner = null)
        harness.items.value = rows.headMovedDown()
        frames()
        assertEquals(20, harness.listState.firstVisibleItemIndex)
    }

    @Test
    fun `a landed scroll-to-top keeps the list at the top when the head row moves down`() {
        val owner = ActivityScrollToTopOwner()
        val harness = mount(owner)
        scrollToTop(harness, owner)
        harness.items.value = rows.headMovedDown()
        frames()
        assertEquals(0, harness.listState.firstVisibleItemIndex)
        assertEquals(0, harness.listState.firstVisibleItemScrollOffset)
        assertEquals("row1", harness.listState.layoutInfo.visibleItemsInfo.first().key)
    }

    @Test
    fun `after the reader scrolls, a head move is not pinned back to the top`() {
        val owner = ActivityScrollToTopOwner()
        val harness = mount(owner)
        scrollToTop(harness, owner)
        CoroutineScope(AndroidUiDispatcher.Main).launch {
            harness.listState.scroll(MutatePriority.UserInput) { scrollBy(400f) }
        }
        frames()
        val readerKey = harness.listState.layoutInfo.visibleItemsInfo.first().key
        assertTrue(harness.listState.firstVisibleItemIndex > 0)
        harness.items.value = harness.items.value.headMovedDown()
        frames()
        assertEquals(readerKey, harness.listState.layoutInfo.visibleItemsInfo.first().key, "the reader's row stays put")
        assertTrue(harness.listState.firstVisibleItemIndex > 0, "the reader is not pulled back to the top")
    }
}
