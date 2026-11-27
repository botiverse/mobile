package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The Activity list's "at the top" (web slock ThreadsInbox `isNearTopRef = scrollTop < 50`) and the
 * head the top edge rule compares (the laid-out head: day heading and first row).
 */
class ActivityListNearTopTest {
    @Test
    fun `distance from the top adds the laid-out items before the first visible one`() {
        assertEquals(0, activityScrolledFromTopPx(0, 0) { null })
        assertEquals(30, activityScrolledFromTopPx(0, 30) { null })
        // A stuck day heading (index 0, 26px) over the first row scrolled 10px.
        assertEquals(36, activityScrolledFromTopPx(1, 10) { index -> if (index == 0) 26 else null })
        // An item before the first visible one is not laid out: further than we can tell.
        assertNull(activityScrolledFromTopPx(3, 0) { index -> if (index == 0) 26 else null })
    }

    private val timeContext =
        RaftTimeContext(localOffsetMinutes = 0, timeFormat = "24h")
            .refreshedAt(parseRaftTimestampEpochMillisOrNull("2026-11-05T12:00:00Z")!!)

    private fun row(key: String, at: String?) =
        InboxItem(key = key, kind = InboxItemKind.Channel, channelId = ChannelId(key), channelName = key, lastMessageAt = at)

    @Test
    fun `the head signature changes with the head row and not with rows below it`() {
        val list = listOf(row("a", "2026-11-05T11:00:00Z"), row("b", "2026-11-05T10:00:00Z"))
        val newHead = listOf(row("n", "2026-11-05T11:30:00Z")) + list
        val belowChanged = listOf(list[0], row("c", "2026-11-05T09:00:00Z"))
        assertNotEquals(activityListHeadSignature(list, timeContext), activityListHeadSignature(newHead, timeContext))
        assertEquals(activityListHeadSignature(list, timeContext), activityListHeadSignature(belowChanged, timeContext))
    }
}
