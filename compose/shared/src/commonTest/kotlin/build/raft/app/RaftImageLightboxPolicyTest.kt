package build.raft.app

import androidx.compose.ui.input.key.Key
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.RaftThreadMessageItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop / wide image lightbox's pure rules (RaftImageLightbox.kt). */
class RaftImageLightboxPolicyTest {
    @Test
    fun desktopAlwaysAndOtherWindowsFromTheDualPaneWidth() {
        assertTrue(raftImageLightboxEnabled(finePointer = true, windowWidthDp = 600f))
        assertTrue(raftImageLightboxEnabled(finePointer = false, windowWidthDp = DualPaneMinimumWidthDp))
        assertFalse(raftImageLightboxEnabled(finePointer = false, windowWidthDp = 411f), "phones keep the full-page viewer")
    }

    @Test
    fun keysAndStepsStopAtTheEnds() {
        assertEquals(RaftImageLightboxKeyAction.Previous, raftImageLightboxKeyAction(Key.DirectionLeft))
        assertEquals(RaftImageLightboxKeyAction.Next, raftImageLightboxKeyAction(Key.DirectionRight))
        assertEquals(RaftImageLightboxKeyAction.Close, raftImageLightboxKeyAction(Key.Escape))
        assertNull(raftImageLightboxKeyAction(Key.Spacebar))
        assertEquals(0, raftImageLightboxStep(index = 0, count = 3, delta = -1))
        assertEquals(1, raftImageLightboxStep(index = 0, count = 3, delta = 1))
        assertEquals(2, raftImageLightboxStep(index = 2, count = 3, delta = 1))
    }

    @Test
    fun theRequestSpansTheConversationOldestFirstAndSkipsRetractedMessages() {
        fun image(id: String) = RaftThreadAttachmentItem(id = id, name = "$id.png", mimeType = "image/png")
        val messages =
            listOf(
                RaftThreadMessageItem(id = "m1", authorId = "a", authorName = "Artin", timestampLabel = "14:00", attachments = listOf(image("i1"), RaftThreadAttachmentItem(id = "d1", name = "notes.pdf", mimeType = "application/pdf"))),
                RaftThreadMessageItem(id = "m2", authorId = "b", authorName = "Ghost", deletedAt = "2026-10-29T00:00:00Z", attachments = listOf(image("gone"))),
                RaftThreadMessageItem(id = "m3", authorId = "c", authorName = "Lagoon", timestampLabel = "14:05", attachments = listOf(image("i2"), image("i3")))
            )
        val request = raftImageLightboxRequestFor(messages, "i2") { it.mimeType.startsWith("image/") }
        requireNotNull(request)
        assertEquals(listOf("i1", "i2", "i3"), request.items.map { it.attachment.id })
        assertEquals(1, request.index)
        assertEquals("Lagoon", request.items[1].senderName)
        assertEquals("14:05", request.items[1].timeLabel)
        assertNull(raftImageLightboxRequestFor(messages, "gone") { true })
    }
}
