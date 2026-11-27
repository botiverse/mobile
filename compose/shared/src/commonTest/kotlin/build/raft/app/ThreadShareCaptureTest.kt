package build.raft.app

import build.raft.app.thread.LazyViewportAnchorTarget
import build.raft.app.thread.ThreadShareCaptureSlice
import build.raft.app.thread.threadShareCaptureAnchorTarget
import build.raft.app.thread.threadShareCaptureSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadShareCaptureTest {
    @Test
    fun missingShareTargetFailsClosedBeforeIndexNormalizationWithAndWithoutOrigin() {
        assertNull(
            threadShareCaptureAnchorTarget(
                targetIndex = -1,
                anchorBeforeTarget = true,
                originVisible = true,
                historyBoundaryVisible = true,
                messageIds = listOf("first-reply")
            )
        )
        assertNull(
            threadShareCaptureAnchorTarget(
                targetIndex = -1,
                anchorBeforeTarget = true,
                originVisible = false,
                historyBoundaryVisible = true,
                messageIds = listOf("first-reply")
            )
        )
    }

    @Test
    fun laterReplyCaptureTargetsTheNormalPreviousMessageWithAndWithoutOrigin() {
        val messageIds = listOf("first-reply", "second-reply")

        assertEquals(
            LazyViewportAnchorTarget.Message("first-reply"),
            threadShareCaptureAnchorTarget(
                targetIndex = 3,
                anchorBeforeTarget = true,
                originVisible = true,
                historyBoundaryVisible = true,
                messageIds = messageIds
            )
        )
        assertEquals(
            LazyViewportAnchorTarget.Message("first-reply"),
            threadShareCaptureAnchorTarget(
                targetIndex = 2,
                anchorBeforeTarget = true,
                originVisible = false,
                historyBoundaryVisible = true,
                messageIds = messageIds
            )
        )
    }

    @Test
    fun tallMessageCaptureAdvancesThroughOrderedViewportSlicesWithoutCroppingTheTop() {
        val first =
            threadShareCaptureSlice(
                messageHeight = 1_180f,
                currentMessageTop = 24f,
                captureViewportTop = 24f,
                captureViewportBottom = 1_204f,
                capturedHeight = 0,
                segmentHeight = 0
            )
        assertEquals(ThreadShareCaptureSlice(y = 24f, height = 1_180f), first)

        val second =
            threadShareCaptureSlice(
                messageHeight = 1_180f,
                currentMessageTop = -476f,
                captureViewportTop = 24f,
                captureViewportBottom = 524f,
                capturedHeight = 500,
                segmentHeight = 500
            )
        assertEquals(ThreadShareCaptureSlice(y = 24f, height = 500f), second)

        val final =
            threadShareCaptureSlice(
                messageHeight = 1_180f,
                currentMessageTop = -976f,
                captureViewportTop = 24f,
                captureViewportBottom = 524f,
                capturedHeight = 1_000,
                segmentHeight = 500
            )
        assertEquals(ThreadShareCaptureSlice(y = 24f, height = 180f), final)
    }

    @Test
    fun tallMessageCaptureExcludesTheTransparentSelectionToolbarFromEverySlice() {
        val first =
            threadShareCaptureSlice(
                messageHeight = 1_725f,
                currentMessageTop = 189f,
                captureViewportTop = 189f,
                captureViewportBottom = 1_138f,
                capturedHeight = 0,
                segmentHeight = 0
            )
        assertEquals(ThreadShareCaptureSlice(y = 189f, height = 949f), first)

        val second =
            threadShareCaptureSlice(
                messageHeight = 1_725f,
                currentMessageTop = -760f,
                captureViewportTop = 189f,
                captureViewportBottom = 1_138f,
                capturedHeight = 949,
                segmentHeight = 949
            )
        assertEquals(ThreadShareCaptureSlice(y = 189f, height = 776f), second)
    }
}
