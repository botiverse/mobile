package build.raft.app

import build.raft.app.resources.*
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftRelativeTimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Consumer-integration teeth for the relative-time label (task #103, #proj-i18n:6b21213b).
 *
 * Drives the production selection seam [selectLocalizedStringResource] that
 * `RaftRelativeTimeLabel.Relative.localized()` delegates to, locking the
 * singular/many and past/future wiring per unit. Deleting a key or mis-wiring a
 * case (e.g. routing past to the future resource, or one to many) turns RED.
 *
 * The locale-resolved text itself is covered by RelativeTimeSpacingResourceTest
 * (zh catalog values + placeholder parity + de-spacing mutation).
 */
class RaftRelativeTimeResourceSelectionTest {
    private fun relative(value: Int, unit: RaftRelativeTimeUnit) =
        RaftRelativeTimeLabel.Relative(value = value, unit = unit)

    @Test
    fun minuteSelectionWiresNowSingularManyPastAndFuture() {
        val minute = RaftRelativeTimeUnit.Minute

        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.thread_timestamp_now),
            relative(0, minute).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_minute_past_one),
            relative(-1, minute).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_minute_past_many, 5),
            relative(-5, minute).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_minute_future_one),
            relative(1, minute).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_minute_future_many, 5),
            relative(5, minute).selectLocalizedStringResource()
        )
    }

    @Test
    fun hourSelectionWiresNowSingularManyPastAndFuture() {
        val hour = RaftRelativeTimeUnit.Hour

        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_hour_now),
            relative(0, hour).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_hour_past_one),
            relative(-1, hour).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_hour_past_many, 3),
            relative(-3, hour).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_hour_future_one),
            relative(1, hour).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_hour_future_many, 3),
            relative(3, hour).selectLocalizedStringResource()
        )
    }

    @Test
    fun daySelectionWiresNamedDaysAndManyPastFuture() {
        val day = RaftRelativeTimeUnit.Day

        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_day_before_yesterday),
            relative(-2, day).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_day_yesterday),
            relative(-1, day).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_day_today),
            relative(0, day).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_day_tomorrow),
            relative(1, day).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_day_after_tomorrow),
            relative(2, day).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_day_past_many, 4),
            relative(-4, day).selectLocalizedStringResource()
        )
        assertEquals(
            RaftRelativeTimeStringSelection(Res.string.relative_time_day_future_many, 4),
            relative(4, day).selectLocalizedStringResource()
        )
    }
}
