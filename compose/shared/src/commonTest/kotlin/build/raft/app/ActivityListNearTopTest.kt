package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Activity list's "at the top" (web slock ThreadsInbox `isNearTopRef = scrollTop < 50`) and the
 * head the top edge rule compares (the laid-out head: day heading and first row).
 */
class ActivityListNearTopTest {
    @Test
    fun `the threshold is the web's 50`() {
        assertEquals(50.dp, ActivityNearTopThreshold)
    }

    @Test
    fun `distance from the top adds the laid-out items before the first visible one`() {
        assertEquals(0, activityScrolledFromTopPx(0, 0) { null })
        assertEquals(30, activityScrolledFromTopPx(0, 30) { null })
        // A stuck day heading (index 0, 26px) over the first row scrolled 10px.
        assertEquals(36, activityScrolledFromTopPx(1, 10) { index -> if (index == 0) 26 else null })
        // An item before the first visible one is not laid out: further than we can tell.
        assertNull(activityScrolledFromTopPx(3, 0) { index -> if (index == 0) 26 else null })
    }

    @Test
    fun `near the top is less than the threshold from it`() {
        assertTrue(activityListNearTop(0, thresholdPx = 50))
        assertTrue(activityListNearTop(49, thresholdPx = 50))
        assertFalse(activityListNearTop(50, thresholdPx = 50))
        assertFalse(activityListNearTop(null, thresholdPx = 50))
        assertTrue(activityListNearTop(0, thresholdPx = 0), "the very top is always the top")
        assertFalse(activityListNearTop(1, thresholdPx = 0))
    }

    private val timeContext =
        RaftTimeContext(localOffsetMinutes = 0, timeFormat = "24h")
            .refreshedAt(parseRaftTimestampEpochMillisOrNull("2026-11-05T12:00:00Z")!!)

    private fun row(key: String, at: String?) =
        InboxItem(key = key, kind = InboxItemKind.Channel, channelId = ChannelId(key), channelName = key, lastMessageAt = at)

    @Test
    fun `the head signature changes when a new day heading leads even when the head row is the same`() {
        val yesterday = listOf(row("a", "2026-11-04T20:00:00Z"), row("b", "2026-11-04T19:00:00Z"))
        val aMovedToToday = listOf(row("a", "2026-11-05T11:00:00Z"), row("b", "2026-11-04T19:00:00Z"))
        assertNotEquals(activityListHeadSignature(yesterday, timeContext), activityListHeadSignature(aMovedToToday, timeContext))
    }

    @Test
    fun `the head signature changes with the head row and not with rows below it`() {
        val list = listOf(row("a", "2026-11-05T11:00:00Z"), row("b", "2026-11-05T10:00:00Z"))
        val newHead = listOf(row("n", "2026-11-05T11:30:00Z")) + list
        val belowChanged = listOf(list[0], row("c", "2026-11-05T09:00:00Z"))
        assertNotEquals(activityListHeadSignature(list, timeContext), activityListHeadSignature(newHead, timeContext))
        assertEquals(activityListHeadSignature(list, timeContext), activityListHeadSignature(belowChanged, timeContext))
    }

    @Test
    fun `without day headings the signature is the head row's, and an empty list has none`() {
        val list = listOf(row("a", null), row("b", null))
        assertEquals(activityListHeadSignature(list, null), activityListHeadSignature(list.take(1), null))
        assertNotEquals(activityListHeadSignature(list, null), activityListHeadSignature(list.reversed(), null))
        assertNull(activityListHeadSignature(emptyList(), timeContext))
    }
}
