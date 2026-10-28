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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.unit.dp
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * artin 2026-10-28 ("activity 页面顶部明明就两个未读，但是如果第一条是未读，往下拉的时候，已读也会被计算上") on a
 * real LazyColumn: the "↑ N new updates" pill counts only rows that ARRIVED above the viewport
 * while the reader was away from the top, never rows the reader scrolled past. The harness wires
 * the pill exactly as SharedInboxPanel does: settleTopEdge's [keepTopIfParked] +
 * [activityPillKeysAfterChange] in the same snapshot as the list change, the count from
 * [activityNewRowsAboveViewportCount], seen rows pruned by [activityUnseenNewRowKeys], cleared at the top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class ActivityNewActivityPillLayoutTest {
    @get:Rule val activities = RobolectricComposeActivityRule()

    private fun row(key: String, lastMessage: String = "$key-m1", unread: Int = 0) =
        InboxItem(
            key = key,
            kind = InboxItemKind.Channel,
            channelId = ChannelId(key),
            channelName = key,
            unreadCount = unread,
            lastMessageId = MessageId(lastMessage),
        )

    /** The reported list: an unread head over read rows. */
    private val unreadHead = row("proj-seo", lastMessage = "seo-system", unread = 2)
    private val rows = listOf(unreadHead) + (1 until 60).map { row("row$it") }

    private fun frames(count: Int = 60) = repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private class Harness(
        val items: MutableState<List<InboxItem>>,
        val pillKeys: MutableState<Set<String>>,
        val listState: LazyListState,
    ) {
        val count: Int
            get() =
                activityNewRowsAboveViewportCount(
                    newRowKeys = pillKeys.value,
                    lazyItemKeys = items.value.map(InboxItem::key),
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                )
    }

    private fun mount(): Harness {
        val harness = Harness(mutableStateOf(rows), mutableStateOf(emptySet()), LazyListState())
        activities.launch().setContent {
            val list by harness.items
            LaunchedEffect(Unit) {
                snapshotFlow { harness.listState.layoutInfo.visibleItemsInfo.mapNotNullTo(mutableSetOf()) { it.key as? String } }
                    .distinctUntilChanged()
                    .collect { visible ->
                        val unseen = activityUnseenNewRowKeys(harness.pillKeys.value, visible)
                        if (unseen != harness.pillKeys.value) harness.pillKeys.value = unseen
                    }
            }
            LaunchedEffect(Unit) {
                snapshotFlow {
                    activityListParkedAtTop(harness.listState.firstVisibleItemIndex, harness.listState.firstVisibleItemScrollOffset)
                }.collect { atTop -> if (atTop && harness.pillKeys.value.isNotEmpty()) harness.pillKeys.value = emptySet() }
            }
            LazyColumn(state = harness.listState, modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.key }) { BasicText(it.key, Modifier.fillMaxWidth().height(60.dp)) }
            }
        }
        frames(10)
        return harness
    }

    /** The list change and settleTopEdge, in one snapshot, before the next measure. */
    private fun change(harness: Harness, next: List<InboxItem>) {
        val previous = harness.items.value
        harness.items.value = next
        val keptTop = harness.listState.keepTopIfParked(previous.firstOrNull()?.key, next.firstOrNull()?.key)
        harness.pillKeys.value = activityPillKeysAfterChange(harness.pillKeys.value, previous, next, listAtTop = keptTop)
        frames()
    }

    /** A finger drag down the list by [rowCount] rows. */
    private fun scrollRows(harness: Harness, rowCount: Int) {
        val rowPx = harness.listState.layoutInfo.visibleItemsInfo.first().size
        CoroutineScope(AndroidUiDispatcher.Main).launch {
            harness.listState.scroll(MutatePriority.UserInput) { scrollBy(rowPx * rowCount.toFloat()) }
        }
        frames()
    }

    @Test
    fun `scrolling down from an unread head counts nothing when nothing arrived`() {
        val harness = mount()
        scrollRows(harness, 5)
        assertEquals(5, harness.listState.firstVisibleItemIndex)
        assertEquals(0, harness.count)
        // The reorder the reported list goes through while the reader scrolls: the unread head drops
        // back below rows already passed (realtime vs served order), then returns. No new activity.
        val served = rows.drop(1).take(8) + unreadHead + rows.drop(9)
        val positional = activityNewTopRowKeys(rows.map(InboxItem::key), served.map(InboxItem::key))
        assertEquals(8, positional.size, "the positional set the pill used to take: every passed row")
        change(harness, served)
        assertEquals(0, harness.count)
        change(harness, rows)
        assertEquals(0, harness.count)
        scrollRows(harness, 5)
        assertTrue(harness.listState.firstVisibleItemIndex >= 10)
        assertEquals(emptySet(), harness.pillKeys.value)
        assertEquals(0, harness.count, "no pill: rows scrolled past, read or unread, never count")
    }

    @Test
    fun `two rows arriving above a scrolled reader count two, and scrolling further keeps two`() {
        val harness = mount()
        scrollRows(harness, 5)
        val readerKey = harness.listState.layoutInfo.visibleItemsInfo.first().key
        change(harness, listOf(row("new1"), row("new2")) + rows)
        assertEquals(readerKey, harness.listState.layoutInfo.visibleItemsInfo.first().key, "the reader keeps their row")
        assertEquals(2, harness.count)
        scrollRows(harness, 5)
        assertEquals(2, harness.count)
    }

    @Test
    fun `tapping the pill goes back to the top and clears it`() {
        val harness = mount()
        scrollRows(harness, 5)
        change(harness, listOf(row("new1"), row("new2")) + rows)
        assertEquals(2, harness.count)
        // The pill's onClick: clear the keys, then the expressive scroll to the top (revealRequest).
        harness.pillKeys.value = emptySet()
        CoroutineScope(AndroidUiDispatcher.Main).launch { harness.listState.animateScrollToTopExpressive() }
        frames(120)
        assertEquals(0, harness.listState.firstVisibleItemIndex)
        assertEquals(0, harness.listState.firstVisibleItemScrollOffset)
        assertEquals("new1", harness.listState.layoutInfo.visibleItemsInfo.first().key)
        assertEquals(0, harness.count)
        scrollRows(harness, 5)
        assertEquals(0, harness.count, "the arrived rows were seen; scrolling past them again counts nothing")
    }
}
