package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
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
    fun theImageRectIsTheFittedImageUnderTheZoomLayer() {
        val layout = Size(1000f, 500f)
        val content = Size(400f, 400f)
        // Fitted: 500x500 centred.
        assertEquals(Rect(250f, 0f, 750f, 500f), raftImageLightboxImageRect(layout, content, scale = 1f, offset = Offset.Zero))
        // 2x about the centre, then panned 100px right.
        assertEquals(Rect(100f, -250f, 1100f, 750f), raftImageLightboxImageRect(layout, content, scale = 2f, offset = Offset(100f, 0f)))
        // Unknown size: the whole page.
        assertEquals(Rect(0f, 0f, 1000f, 500f), raftImageLightboxImageRect(layout, Size.Zero, scale = 1f, offset = Offset.Zero))
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
