package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class RaftComposerImageEditorTimingTrackerTest {
    @Test
    fun recordsDecodeCanvasReadyAndFirstGestureOnceWithExactMonotonicDeltas() {
        val clock = TestTimeSource()
        val tracker = RaftComposerImageEditorTimingTracker(timeSource = clock)

        // Canvas ready before decode (or with zero size) is ignored until bitmap lands.
        assertNull(tracker.onCanvasReady(1080, 1440))
        assertNull(tracker.onImageDecoded(0, 100))

        clock += 120.milliseconds
        assertEquals(
            "composer_image_editor_image_decoded elapsedMs=120 imageWidth=1200 imageHeight=1600",
            tracker.onImageDecoded(1200, 1600),
        )
        assertNull(tracker.onImageDecoded(1200, 1600), "decode milestone logs once")

        // Zero-sized layout pass does not satisfy canvas-ready.
        assertNull(tracker.onCanvasReady(0, 1440))

        clock += 35.milliseconds
        assertEquals(
            "composer_image_editor_canvas_ready elapsedMs=155 decodeMs=120 layoutAfterDecodeMs=35 canvasWidthPx=1080 canvasHeightPx=1440",
            tracker.onCanvasReady(1080, 1440),
        )
        assertNull(tracker.onCanvasReady(1080, 1440), "canvas-ready milestone logs once")

        clock += 45.milliseconds
        assertEquals(
            "composer_image_editor_first_gesture elapsedMs=200 sinceCanvasReadyMs=45 tool=pencil gesture=drag",
            tracker.onFirstGesture(RaftComposerImageEditorTool.Pencil, "drag"),
        )
        assertNull(
            tracker.onFirstGesture(RaftComposerImageEditorTool.Pencil, "tap"),
            "first-gesture milestone logs once",
        )
    }

    @Test
    fun recordsFirstGestureBeforeCanvasReadyWithSentinelWait() {
        val clock = TestTimeSource()
        val tracker = RaftComposerImageEditorTimingTracker(timeSource = clock)
        clock += 50.milliseconds
        assertEquals(
            "composer_image_editor_first_gesture elapsedMs=50 sinceCanvasReadyMs=-1 tool=crop gesture=drag",
            tracker.onFirstGesture(RaftComposerImageEditorTool.Crop, "drag"),
        )
    }

    @Test
    fun previewDecodeTrackerLogsOnceWithSanitizedSource() {
        val clock = TestTimeSource()
        val tracker = RaftFilePreviewImageDecodeTracker(timeSource = clock)
        assertNull(tracker.onImageDecoded("att-123456789", ""))

        clock += 88.milliseconds
        val line = tracker.onImageDecoded("att-123456789", "file:///tmp/secret-photo.jpg")
        assertTrue(line != null)
        assertTrue("file_preview_image_decoded attachment=att-1234 elapsedMs=88 " in line, line)
        assertTrue("scheme=file" in line, line)
        assertTrue("secret-photo" !in line, "raw file path must never leak into logs: $line")
        assertNull(tracker.onImageDecoded("att-123456789", "file:///tmp/secret-photo.jpg"))

        val blankAttachmentTracker = RaftFilePreviewImageDecodeTracker(timeSource = clock)
        val blankLine = blankAttachmentTracker.onImageDecoded("", "file:///tmp/composer-photo.jpg")
        assertTrue(blankLine != null && "file_preview_image_decoded attachment=none elapsedMs=0 " in blankLine, blankLine)
    }
}
