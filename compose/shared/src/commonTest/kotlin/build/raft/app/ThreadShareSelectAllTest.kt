package build.raft.app

import build.raft.app.model.ForwardMessageBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The thread multi-select's select-all checkbox (web SelectModeToolbar, botiverse/slock #12681):
 * what it selects, its three looks, what a tap does and when the count says "All".
 */
class ThreadShareSelectAllTest {
    private fun message(
        id: String,
        messageType: String = "Chat",
        forwardedBundle: Boolean = false,
        actionCard: Boolean = false,
        retracted: Boolean = false,
    ) = RaftThreadMessageItem(
        id = id,
        authorId = "member-1",
        authorName = "Member",
        messageType = messageType,
        metadata = if (actionCard) mapOf("kind" to "action-card") else emptyMap(),
        forwardBundle = ForwardMessageBundle(id = "bundle-$id").takeIf { forwardedBundle },
        deletedAt = "2026-11-03T10:00:00.000Z".takeIf { retracted },
    )

    @Test
    fun selectsTheParentAndEveryForwardableLoadedReply() {
        val slice =
            RaftThreadScreenPayload(
                originMessage = message("parent"),
                messages =
                    listOf(
                        message("reply-1"),
                        message("system-1", messageType = "System"),
                        message("bundle-1", forwardedBundle = true),
                        message("card-1", actionCard = true),
                        message("retracted-1", retracted = true),
                        message("reply-2"),
                    ),
            ).toThreadTimelineSlice()

        assertEquals(setOf("parent", "reply-1", "reply-2"), slice.threadSelectableShareMessageIds())
    }

    @Test
    fun checkIsCheckedOnlyForExactlyTheSelectableSet() {
        val selectable = setOf("parent", "reply-1", "reply-2")

        assertEquals(ThreadShareSelectAllCheck.Unchecked, threadShareSelectAllCheck(emptySet(), selectable))
        assertEquals(ThreadShareSelectAllCheck.Indeterminate, threadShareSelectAllCheck(setOf("reply-1"), selectable))
        assertEquals(ThreadShareSelectAllCheck.Checked, threadShareSelectAllCheck(selectable, selectable))
        // A message more than select-all takes (a hand-picked row it leaves out) is not "all".
        assertEquals(
            ThreadShareSelectAllCheck.Indeterminate,
            threadShareSelectAllCheck(selectable + "system-1", selectable),
        )
        // Nothing selectable and nothing selected stays empty.
        assertEquals(ThreadShareSelectAllCheck.Unchecked, threadShareSelectAllCheck(emptySet(), emptySet()))
    }

    @Test
    fun aTapSelectsAllFromNothingAndClearsAnythingElse() {
        val selectable = setOf("parent", "reply-1", "reply-2")

        assertEquals(selectable, threadShareSelectAllNext(emptySet(), selectable))
        assertEquals(emptySet(), threadShareSelectAllNext(setOf("reply-1"), selectable))
        assertEquals(emptySet(), threadShareSelectAllNext(selectable, selectable))
    }

    @Test
    fun theCountSaysAllOnlyWhenExactlySelectedAndTheWholeThreadIsLoaded() {
        assertTrue(threadShareSelectionSaysAll(ThreadShareSelectAllCheck.Checked, complete = true))
        // Older or newer replies not loaded yet: the plain "N selected".
        assertFalse(threadShareSelectionSaysAll(ThreadShareSelectAllCheck.Checked, complete = false))
        assertFalse(threadShareSelectionSaysAll(ThreadShareSelectAllCheck.Indeterminate, complete = true))
        assertFalse(threadShareSelectionSaysAll(ThreadShareSelectAllCheck.Unchecked, complete = true))
    }
}
