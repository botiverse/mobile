package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadReadAnchorStoreTest {
    @Test
    fun ownerIdentityIsolatedAndFrontierOnlyAdvances() {
        val persistence = InMemoryRaftKeyValueStore()
        val store = KeyValueThreadReadAnchorStore(persistence)
        val owner = owner(accountId = "account-a", conversationId = "channel-a")

        assertNull(store.read(owner))
        assertEquals(
            ThreadReadAnchor(seq = 12L, messageId = "m12"),
            store.advance(owner, ThreadReadAnchor(seq = 12L, messageId = "m12")),
        )
        assertEquals(
            ThreadReadAnchor(seq = 12L, messageId = "m12"),
            store.advance(owner, ThreadReadAnchor(seq = 9L, messageId = "m9")),
        )
        assertNull(store.read(owner(accountId = "account-b", conversationId = "channel-a")))
        assertNull(store.read(owner(accountId = "account-a", conversationId = "channel-b")))
    }

    @Test
    fun malformedAndNonPositiveRecordsFailClosed() {
        val store = KeyValueThreadReadAnchorStore(InMemoryRaftKeyValueStore())
        val owner = owner()

        assertNull(store.advance(owner, ThreadReadAnchor(seq = 0L, messageId = "zero")))
        assertNull(store.advance(owner.copy(serverId = ""), ThreadReadAnchor(seq = 1L, messageId = "m1")))
        assertNull(store.read(owner))
    }

    @Test
    fun unreadProjectionTargetsTheFirstUnreadCanonicalMessage() {
        val messages =
            listOf(
                ThreadUnreadAnchorMessage("pending", null),
                ThreadUnreadAnchorMessage("m10", 10L),
                ThreadUnreadAnchorMessage("m11", 11L),
                ThreadUnreadAnchorMessage("m12", 12L),
            )

        assertEquals(
            ThreadUnreadJumpProjection(unreadCount = 2, targetMessageId = "m11"),
            projectThreadUnreadJump(ThreadReadAnchor(10L, "m10"), messages),
        )
        assertEquals(
            ThreadUnreadJumpProjection(unreadCount = 2, targetMessageId = "m11"),
            projectThreadUnreadJump(ThreadReadAnchor(10L, "deleted"), messages),
        )
        assertEquals(
            ThreadUnreadJumpProjection(unreadCount = 0, targetMessageId = null),
            projectThreadUnreadJump(ThreadReadAnchor(12L, "m12"), messages),
        )
    }

    @Test
    fun visibleFrontierAdvancesMonotonicallyAndUpgradesSameSeqIdentity() {
        val entered = ThreadReadAnchor(10L, null)
        val visible = ThreadReadAnchor(12L, "m12")

        assertEquals(visible, advanceThreadReadAnchorFrontier(entered, visible))
        assertEquals(visible, advanceThreadReadAnchorFrontier(visible, ThreadReadAnchor(11L, "m11")))
        assertEquals(
            ThreadReadAnchor(10L, "m10"),
            advanceThreadReadAnchorFrontier(entered, ThreadReadAnchor(10L, "m10")),
        )
    }

    @Test
    fun visibleWindowConsumptionShrinksUnreadAndVisibleLocalSendClearsIt() {
        val messages =
            listOf(
                ThreadUnreadAnchorMessage("m10", 10L),
                ThreadUnreadAnchorMessage("m11", 11L),
                ThreadUnreadAnchorMessage("m12", 12L),
                ThreadUnreadAnchorMessage("local-visible", 13L),
            )
        val entered = ThreadReadAnchor(10L, "m10")
        val afterVisibleWindow =
            advanceThreadReadAnchorFrontier(entered, ThreadReadAnchor(12L, "m12"))

        assertEquals(
            ThreadUnreadJumpProjection(unreadCount = 1, targetMessageId = "local-visible"),
            projectThreadUnreadJump(afterVisibleWindow, messages),
        )
        assertEquals(
            ThreadUnreadJumpProjection(unreadCount = 0, targetMessageId = null),
            projectThreadUnreadJump(
                advanceThreadReadAnchorFrontier(afterVisibleWindow, ThreadReadAnchor(13L, "local-visible")),
                messages,
            ),
        )
    }

    @Test
    fun gapRefillExclusionShrinksUnreadCountWithoutTouchingFrontier() {
        val messages =
            listOf(
                ThreadUnreadAnchorMessage("m10", 10L),
                ThreadUnreadAnchorMessage("m11", 11L),
                ThreadUnreadAnchorMessage("m12", 12L),
                ThreadUnreadAnchorMessage("m13", 13L),
            )

        // Without exclusion all tail messages count.
        assertEquals(
            ThreadUnreadJumpProjection(unreadCount = 3, targetMessageId = "m11"),
            projectThreadUnreadJump(ThreadReadAnchor(10L, "m10"), messages),
        )
        // Gap-refill arrivals are excluded from the ↓ x new count (task #232 contract).
        assertEquals(
            ThreadUnreadJumpProjection(unreadCount = 1, targetMessageId = "m11"),
            projectThreadUnreadJump(
                ThreadReadAnchor(10L, "m10"),
                messages,
                excludedMessageIds = setOf("m12", "m13"),
            ),
        )
        // Excluding every unread message clears the pill entirely.
        assertEquals(
            ThreadUnreadJumpProjection(unreadCount = 0, targetMessageId = null),
            projectThreadUnreadJump(
                ThreadReadAnchor(10L, "m10"),
                messages,
                excludedMessageIds = setOf("m11", "m12", "m13"),
            ),
        )
        // Excluding unknown/already-read IDs changes nothing.
        assertEquals(
            ThreadUnreadJumpProjection(unreadCount = 3, targetMessageId = "m11"),
            projectThreadUnreadJump(
                ThreadReadAnchor(10L, "m10"),
                messages,
                excludedMessageIds = setOf("m10", "ghost"),
            ),
        )
    }

    private fun owner(
        accountId: String = "account",
        conversationId: String = "channel",
    ) = ThreadReadAnchorOwner(
        environmentId = "https://api.example.test/",
        accountId = accountId,
        serverId = "server",
        conversationId = conversationId,
    )
}
