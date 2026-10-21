package build.raft.app

import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftRelativeTimeUnit
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.resolveRaftRelativeTimeLabel
import build.raft.app.resources.MR
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS 1.15.0: the newest Activity row read "in 1 minute" because the device clock ran behind
 * the server. Activity rows clamp any future delta to "now" (the same resource in every locale).
 */
class RaftRelativeTimeFutureClampTest {
    private val now = 1_760_000_000_000L // 2025-10-09T08:53:20Z
    private val context = RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = now)

    private fun clampedSelection(timestamp: Long) =
        (resolveRaftRelativeTimeLabel(timestamp.toString(), context).clampedFutureToNow() as RaftRelativeTimeLabel.Relative)
            .selectLocalizedStringResource()

    @Test
    fun futureTimestampsOfPastEventsReadAsNow() {
        // Unclamped, a 50 s skew is "in 1 minute": the bug.
        assertEquals(
            RaftRelativeTimeLabel.Relative(1, RaftRelativeTimeUnit.Minute),
            resolveRaftRelativeTimeLabel((now + 50_000).toString(), context),
        )
        for (skew in listOf(31_000L, 50_000L, 5 * 60_000L, 3 * 3_600_000L, 3 * 86_400_000L)) {
            assertEquals(
                RaftRelativeTimeStringSelection(MR.strings.thread_timestamp_now),
                clampedSelection(now + skew),
                "skew ${skew}ms",
            )
        }
    }

    @Test
    fun pastAndNonRelativeLabelsAreUntouched() {
        assertEquals(
            RaftRelativeTimeStringSelection(MR.strings.relative_time_minute_past_many, 5),
            clampedSelection(now - 5 * 60_000L),
        )
        assertEquals(
            RaftRelativeTimeLabel.Relative(-2, RaftRelativeTimeUnit.Hour),
            RaftRelativeTimeLabel.Relative(-2, RaftRelativeTimeUnit.Hour).clampedFutureToNow(),
        )
        for (label in listOf(RaftRelativeTimeLabel.Empty, RaftRelativeTimeLabel.Unknown, RaftRelativeTimeLabel.Literal("09:30"))) {
            assertEquals(label, label.clampedFutureToNow())
        }
    }
}
