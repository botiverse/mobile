package build.raft.app.data

import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `inbox_row_position_v1`, the muted rule as RisingWave serves it (Tenny, #triage:#244; slock #15111, the
 * verdict's `moves`): no message past a channel/DM's Activity mute moves a row that has a pre-mute
 * message. A muted mention only lights the @ (no move, no preview change); a plain muted message does
 * nothing. A mention-only row still moves to the mention. The contract cases
 * (`muted_message_leaves_row`, `muted_mention_lights_without_moving`) pin the rule; these pin the row the
 * store shows (preview, no re-read).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedActivityStoreMutedRowTest {
    private val row =
        activityItem("channel-1").copy(latestActivitySeq = "10", readStateLatestActivitySeq = "10", lastMessagePreview = "before the mute")

    private class Fixture(val store: SharedActivityStore, val inbox: FakeInboxRepository) {
        val shown: InboxItem get() = store.state.value.items.single()
    }

    private fun TestScope.loaded(rowPosition: Boolean = true, served: InboxItem = row): Fixture {
        val inbox = FakeInboxRepository(listOf(snapshot(listOf(served))))
        val store =
            activityStore(
                scope = backgroundScope,
                inboxRepository = inbox,
                realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
                nowMillis = { testScheduler.currentTime },
                inboxInPlaceEnabled = { true },
                inboxRowPositionEnabled = { rowPosition }
            )
        store.dispatch(SharedActivityIntent.LoadInitial)
        runCurrent()
        return Fixture(store, inbox)
    }

    /** A message past the mute, as the shell delivers it. */
    private fun muted(id: String, seq: Long, mentionsMe: Boolean) =
        SharedActivityIntent.ApplyRealtimeEvent(
            MessageNew(
                message(id, channelId = "channel-1", seq = seq).copy(
                    mentions = if (mentionsMe) listOf(MessageMention(type = "user", id = "user-1")) else emptyList()
                )
            ),
            allowLocalPatch = false,
            activityMuted = true
        )

    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(ActivityRowRereadDelayMillis + ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
        runCurrent()
    }

    @Test
    fun aPlainMutedMessageDoesNothing() = runTest {
        val f = loaded()
        f.store.dispatch(muted("m-11", seq = 11L, mentionsMe = false))
        settle()

        assertEquals(row, f.shown, "no position move, no preview change, no unread")
        assertEquals(1, f.inbox.initialLoadCount, "and nothing is re-read")
    }

    @Test
    fun aMutedMentionOnlyLightsTheAt() = runTest {
        val f = loaded()
        f.store.dispatch(muted("m-11", seq = 11L, mentionsMe = true))
        settle()

        assertEquals(
            listOf("10", "before the mute", "0", "true"),
            f.shown.let { listOf(it.latestActivitySeq, it.lastMessagePreview, it.unreadCount.toString(), it.hasMention.toString()) }
        )
        assertEquals(1, f.inbox.initialLoadCount)
    }

    @Test
    fun aMentionOnlyRowStillMovesToTheMention() = runTest {
        // Nothing unread, only the @: its position is its highest mention.
        val mentionOnly = row.copy(hasMention = true, lastMessagePreview = "an earlier mention")
        val f = loaded(served = mentionOnly)
        f.store.dispatch(muted("m-11", seq = 11L, mentionsMe = true))
        settle()

        assertEquals(listOf("11", "message m-11", "0"), f.shown.let { listOf(it.latestActivitySeq, it.lastMessagePreview, it.unreadCount.toString()) })
        // A plain muted message does not move it either.
        f.store.dispatch(muted("m-12", seq = 12L, mentionsMe = false))
        settle()
        assertEquals("11", f.shown.latestActivitySeq)
    }

    @Test
    fun theFlagOffRereadsTheListAsBefore() = runTest {
        val f = loaded(rowPosition = false)
        f.store.dispatch(muted("m-11", seq = 11L, mentionsMe = true))
        settle()
        assertEquals(2, f.inbox.initialLoadCount, "past the mute: no local patch, the list is re-read")
    }

    @Test
    fun aMutedMentionIsAppliedWithoutBecomingTheShownPosition() = runTest {
        // An answer behind it keeps the @ (the mention stays applied above the answer's position).
        val f = loaded()
        f.store.dispatch(muted("m-11", seq = 11L, mentionsMe = true))
        runCurrent()
        assertEquals(
            listOf(ActivityRowAppliedSeq("11", counts = false, mention = true, moves = false)),
            f.store.rowPositionStateForTest("channel-1")?.applied
        )
    }
}
