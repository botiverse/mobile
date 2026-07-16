package build.raft.app.shared

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadShareChromeTest {
    @Test
    fun framedImageAddsOnlyTheHeaderAndKeepsAllCapturedContentPixels() {
        assertEquals(96, threadShareChromeHeaderHeight(width = 1_080))
        assertEquals(2_496, threadShareChromeOutputHeight(width = 1_080, contentHeight = 2_400))

        assertEquals(72, threadShareChromeHeaderHeight(width = 320))
        assertEquals(673, threadShareChromeOutputHeight(width = 320, contentHeight = 601))
        assertEquals(86, threadShareChromeHeaderHeight(width = 720))
        assertEquals(18f, threadShareChromeCaptionTextSize(width = 320), 0.01f)
        assertEquals(24.48f, threadShareChromeCaptionTextSize(width = 720), 0.01f)
        assertEquals(26f, threadShareChromeCaptionTextSize(width = 1_080), 0.01f)
    }

    @Test
    fun productionCaptureCopiesWindowPixelsInsteadOfDrawingTheSurfaceContainerBlank() {
        val source =
            sourceFile("androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt")
                .readText()
        val captureFunction =
            source.substringAfter("private fun captureThreadShareImage(")
                .substringBefore("private fun stitchThreadShareFragments")

        assertTrue(captureFunction.contains("container.getLocationInWindow(locationInWindow)"))
        assertEquals(
            2,
            captureFunction
                .windowed("container.postOnAnimation".length)
                .count { it == "container.postOnAnimation" }
        )
        assertTrue(captureFunction.contains("PixelCopy.request("))
        assertTrue(captureFunction.contains("hostActivity.window"))
        assertTrue(captureFunction.contains("copyResult == PixelCopy.SUCCESS"))
        assertTrue(
            captureFunction.indexOf("container.postOnAnimation") <
                captureFunction.indexOf("PixelCopy.request(")
        )
    }

    @Test
    fun productionRealignmentEnablesTailSpaceBeforeScrollingTheLastMessageToTheTop() {
        val source =
            sourceFile("shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt")
                .readText()
        val initialAnchor =
            source.substringAfter("shareCaptureTargetMessageId = message.id")
                .substringBefore("var attempts = 0")
        val targetStartup =
            source.substringAfter("selectedShareMessages.forEach { message ->")
                .substringBefore("shareCaptureTargetMessageId = message.id")
        val exportStartup =
            source.substringAfter("thread_share_export_click")
                .substringAfter("sharePreviewImage = null")
                .substringBefore("shareCaptureMode = true")

        assertTrue(
            initialAnchor.indexOf("shareCaptureTailPaddingActive = true") in
                0 until initialAnchor.indexOf("shareCaptureAnchorBeforeTarget = false")
        )
        assertTrue(
            initialAnchor.substringAfter("delay(ThreadShareCaptureScrollSettleMs + 64L)")
                .substringBefore("shareCaptureAnchorBeforeTarget = false")
                .contains("selectedShareBounds.clear()")
                .not()
        )
        assertTrue(
            targetStartup
                .contains("selectedShareBounds.clear()")
                .not()
        )
        assertTrue(exportStartup.contains("selectedShareBounds.clear()").not())
        assertTrue(
            source.contains(
                "(shareSelectionActive || shareSelectionCaptureActive) &&\n" +
                    "                        message.id in selectedShareMessageIds"
            )
        )
        assertTrue(
            source.contains(
                "val activeListBeyondBoundsItems =\n" +
                    "        if (shareSelectionCaptureActive) {\n" +
                    "            0"
            )
        )
        assertTrue(
            source.contains(
                "LaunchedEffect(threadInstanceKey, latestItemIndex, shareSelectionCaptureActive) {\n" +
                    "        if (shareSelectionCaptureActive) {\n" +
                    "            allowBottomAutoAnchor = false"
            )
        )
        assertTrue(
            source.contains(
                "if (shareSelectionCaptureActive || latestItemIndex < 0 || !initialBottomAnchorReady)"
            )
        )
        assertTrue(
            source.contains(
                ".onGloballyPositioned { coordinates ->\n" +
                    "                                shareCaptureViewportBottomPx = coordinates.positionInRoot().y"
            )
        )
        assertTrue(source.contains(".alpha(if (shareCaptureMode) 0f else 1f)"))
    }

    private fun sourceFile(relativePath: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        repeat(8) {
            File(current, relativePath).takeIf(File::isFile)?.let { return it }
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath from $userDir")
    }
}
