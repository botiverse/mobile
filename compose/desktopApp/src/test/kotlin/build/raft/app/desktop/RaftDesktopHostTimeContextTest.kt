package build.raft.app.desktop

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.resolveRaftRelativeTimeLabel
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * artin's Activity list read "unknown time" on every row: the desktop host never put the local
 * time facts into page data (Android / iOS hosts do), so the shared pages had no time context.
 */
class RaftDesktopHostTimeContextTest {
    private val now = 1_793_000_000_000L // 2026-10-26T07:33:20Z
    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun pageDataGetsTheHostTimeFacts() {
        val data = JsonObject(emptyMap()).withRaftDesktopHostTimeContext(now, shanghai)
        assertEquals(480, data.getValue("localOffsetMinutes").jsonPrimitive.int)
        assertEquals(now, data.getValue("nowEpochMillis").jsonPrimitive.long)
        assertEquals(Math.floorDiv(now + 480 * 60_000L, 86_400_000L), data.getValue("todayEpochDay").jsonPrimitive.long)
    }

    @Test
    fun openerValuesWinAndOtherKeysSurvive() {
        val opened = JsonObject(mapOf("localOffsetMinutes" to JsonPrimitive(-300), "payload" to JsonPrimitive("x")))
        val data = opened.withRaftDesktopHostTimeContext(now, shanghai)
        assertEquals(-300, data.getValue("localOffsetMinutes").jsonPrimitive.int)
        assertEquals("x", data.getValue("payload").jsonPrimitive.content)
        assertSame(data, data.withRaftDesktopHostTimeContext(now + 1, shanghai))
    }

    @OptIn(RaftCoreInternalApi::class)
    @Test
    fun hostOffsetGivesRelativeLabelsInsteadOfUnknown() {
        val offset = JsonObject(emptyMap()).withRaftDesktopHostTimeContext(now, shanghai)
            .getValue("localOffsetMinutes").jsonPrimitive.int
        // What the shared live time context builds from the page's offset (now refreshed live).
        val context = RaftTimeContext(localOffsetMinutes = offset, timeFormat = "24h").refreshedAt(now)
        val label = resolveRaftRelativeTimeLabel("2026-10-26T00:00:00Z", context)
        assertIs<RaftRelativeTimeLabel.Relative>(label)
        // Without the host facts there was no context at all: every row read "unknown time".
        assertEquals(RaftRelativeTimeLabel.Unknown, resolveRaftRelativeTimeLabel("2026-10-26T00:00:00Z", null))
    }
}
