package ai.slock.compose

import ai.slock.compose.data.InboxItem
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.SlockTimeContext
import ai.slock.compose.model.slockEpochDayForDate
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedInboxPanelTest {
    @Test
    fun activityTimeLabelFormatsLatestActivityBeforeRendering() {
        val item =
            activityItem(
                latestActivityAt = "2026-06-18T09:12:00Z",
                lastMessageAt = "2026-06-18T08:05:00Z"
            )
        val context =
            SlockTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 6, 18)
            )

        assertEquals("17:12", item.activityTimeLabel(context))
    }

    @Test
    fun activityTimeLabelFormatsLastMessageFallbackBeforeRendering() {
        val item =
            activityItem(
                latestActivityAt = null,
                lastMessageAt = "2026-06-17T23:05:00Z"
            )
        val context =
            SlockTimeContext(
                localOffsetMinutes = 480,
                todayEpochDay = slockEpochDayForDate(2026, 6, 18)
            )

        assertEquals("07:05", item.activityTimeLabel(context))
    }

    @Test
    fun activityTimeLabelKeepsRawValueWhenContextIsMissing() {
        val raw = "2026-06-18T09:12:00Z"

        assertEquals(raw, activityItem(latestActivityAt = raw).activityTimeLabel(null))
    }

    private fun activityItem(
        latestActivityAt: String? = null,
        lastMessageAt: String? = null
    ): InboxItem =
        InboxItem(
            key = "activity-1",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("channel-1"),
            channelName = "general",
            lastMessageAt = lastMessageAt,
            latestActivityAt = latestActivityAt
        )
}
