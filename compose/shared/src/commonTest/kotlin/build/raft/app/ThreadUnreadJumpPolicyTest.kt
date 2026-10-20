package build.raft.app

import build.raft.app.data.ThreadReadAnchor
import build.raft.app.data.ThreadUnreadJumpProjection
import build.raft.app.data.projectThreadUnreadJump
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadUnreadJumpPolicyTest {
    @Test
    fun visibleTailIgnoresBottomPaddingAndKeepsLastMessage() {
        // Messages occupy list indexes 1..3; index 4 is the bottom scroll padding.
        assertEquals(
            2,
            threadVisibleReadTailMessageIndex(
                visibleListItemIndexes = listOf(1, 2, 3, 4),
                firstMessageListItemIndex = 1,
                messageCount = 3,
            ),
        )
    }

    @Test
    fun unreadJumpHidesAndClearsOnceTheViewportIsAtTheCanonicalBottom() {
        val atBottom =
            threadUnreadJumpAtCanonicalBottom(
                canLoadMoreAfter = false,
                canScrollTowardBottom = false,
                latestItemOffBottom = false,
            )
        assertTrue(atBottom)
        assertFalse(
            shouldShowThreadUnreadJumpButton(
                preferenceEnabled = true,
                routeActive = true,
                hostForeground = true,
                unreadCount = 3,
                targetMessageId = "m11",
                inlineSearchVisible = false,
                atCanonicalBottom = atBottom,
            ),
        )

        val session = ThreadReadAnchor(seq = 10L, messageId = "m10")
        val latest = ThreadReadAnchor(seq = 13L, messageId = "m13")
        val consumed = consumeUnreadJumpFrontierAtCanonicalBottom(session, latest, atBottom)
        assertEquals(latest, consumed)
        assertEquals(
            ThreadUnreadJumpProjection(unreadCount = 0, targetMessageId = null),
            projectThreadUnreadJump(
                consumed,
                listOf(
                    build.raft.app.data.ThreadUnreadAnchorMessage("m10", 10L),
                    build.raft.app.data.ThreadUnreadAnchorMessage("m11", 11L),
                    build.raft.app.data.ThreadUnreadAnchorMessage("m12", 12L),
                    build.raft.app.data.ThreadUnreadAnchorMessage("m13", 13L),
                ),
            ),
        )
    }

    @Test
    fun unreadJumpStaysWhenNewerMessagesAreStillOffBottom() {
        assertFalse(
            threadUnreadJumpAtCanonicalBottom(
                canLoadMoreAfter = false,
                canScrollTowardBottom = true,
                latestItemOffBottom = true,
            )
        )
        assertTrue(
            shouldShowThreadUnreadJumpButton(
                preferenceEnabled = true,
                routeActive = true,
                hostForeground = true,
                unreadCount = 3,
                targetMessageId = "m11",
                inlineSearchVisible = false,
                atCanonicalBottom = false,
            )
        )
        assertEquals(
            ThreadReadAnchor(10L, "m10"),
            consumeUnreadJumpFrontierAtCanonicalBottom(
                sessionAnchor = ThreadReadAnchor(10L, "m10"),
                latestCanonicalMessage = ThreadReadAnchor(13L, "m13"),
                atCanonicalBottom = false,
            ),
        )
    }

    @Test
    fun floatingThreadPillsUseCompactHeight() {
        assertEquals(28.dp, ThreadFloatingPillButtonMinHeight)
    }
}
