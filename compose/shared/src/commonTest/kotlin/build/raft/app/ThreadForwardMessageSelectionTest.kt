package build.raft.app

import build.raft.app.model.ForwardMessageBundle
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadForwardMessageSelectionTest {
    @Test
    fun originalMessagesAreAllForwardable() {
        val selection =
            listOf(
                message("message-1"),
                message("message-2")
            ).threadForwardMessageSelection()

        assertEquals(listOf("message-1", "message-2"), selection.forwardableMessages.map { it.id })
        assertEquals(0, selection.skippedForwardedBundleCount)
    }

    @Test
    fun forwardedBundlesAreNotForwardable() {
        val selection =
            listOf(
                message("forwarded-1", forwardedBundle = true),
                message("forwarded-2", forwardedBundle = true)
            ).threadForwardMessageSelection()

        assertEquals(emptyList(), selection.forwardableMessages)
        assertEquals(2, selection.skippedForwardedBundleCount)
    }

    @Test
    fun mixedSelectionKeepsOnlyOriginalMessagesInTimelineOrder() {
        val selection =
            listOf(
                message("forwarded-1", forwardedBundle = true),
                message("message-1"),
                message("forwarded-2", forwardedBundle = true),
                message("message-2")
            ).threadForwardMessageSelection()

        assertEquals(listOf("message-1", "message-2"), selection.forwardableMessages.map { it.id })
        assertEquals(2, selection.skippedForwardedBundleCount)
    }

    @Test
    fun unsupportedServerCodesUseNestedForwardGuidance() {
        assertEquals(ThreadNestedForwardUnsupportedMessage, "unsupport".threadForwardErrorMessage())
        assertEquals(ThreadNestedForwardUnsupportedMessage, " Unsupported ".threadForwardErrorMessage())
        assertEquals("server_unavailable", "server_unavailable".threadForwardErrorMessage())
    }

    private fun message(
        id: String,
        forwardedBundle: Boolean = false
    ): KuiklyThreadMessageItem =
        KuiklyThreadMessageItem(
            id = id,
            authorId = "member-1",
            authorName = "Member",
            forwardBundle = ForwardMessageBundle(id = "bundle-$id").takeIf { forwardedBundle }
        )
}
