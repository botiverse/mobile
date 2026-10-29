package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `GET /channels/threads/followed` rows carry `parentInHistory` / `repliesInHistory` (botiverse/slock
 * 9747a5aec, #11517, packages/server/src/routes/channels.ts: computed with the paywall's own history
 * window). The field names and boolean shape are copied from that route; older servers omit both.
 */
class FollowedThreadHistoryWindowParserTest {
    private fun row(extra: String) =
        """{"threadChannelId":"t-1","parentChannelId":"c-1","parentMessageId":"p-1","unreadCount":2,""" +
            """"parentMessageCreatedAt":"2026-01-02T03:04:05.000Z"$extra}"""

    private fun parse(vararg rows: String) =
        assertNotNull(parseFollowedThreadListResponse("""{"threads":[${rows.joinToString(",")}],"totalCount":${rows.size},"hasMore":false}"""))
            .items
            .associateBy { it.threadChannelId?.value }

    @Test
    fun theHistoryFlagsReachTheRowAndAreNullFromAnOlderServer() {
        val parsed =
            parse(
                row(""","parentInHistory":false,"repliesInHistory":true""").replace("t-1", "t-open"),
                row(""","parentInHistory":false,"repliesInHistory":false""").replace("t-1", "t-gone"),
                row("").replace("t-1", "t-old-server"),
            )

        assertEquals(false to true, parsed.getValue("t-open").let { it.parentInHistory to it.repliesInHistory })
        assertEquals(false to false, parsed.getValue("t-gone").let { it.parentInHistory to it.repliesInHistory })
        val old = parsed.getValue("t-old-server")
        assertNull(old.parentInHistory)
        assertNull(old.repliesInHistory)
    }
}
