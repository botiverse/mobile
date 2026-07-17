package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ThreadBackToBottomPointerBoundaryTest {
    @Test
    fun floatingButtonCapturesNativeDispatchBehindItsInteractiveContent() {
        val source = threadPageSource()
        val button =
            source.functionWindow(
                "private fun ThreadBackToBottomButton(",
                "private const val ThreadBackToBottomTouchGuardAlpha",
            )

        val captureIndex = button.indexOf(".slockNativeDispatchCapture()")
        val actionIndex = button.indexOf("SlockBrutalActionButton(")

        assertTrue(
            "Box(modifier = modifier, propagateMinConstraints = true)" in button,
            "The externally positioned button must own a same-size pointer boundary.",
        )
        assertTrue(
            ".matchParentSize()" in button &&
                ".background(SlockColor.SurfaceWhite.copy(alpha = ThreadBackToBottomTouchGuardAlpha))" in button,
            "The native capture sibling must cover the complete floating button bounds and create a host hit-test node.",
        )
        assertTrue(
            captureIndex >= 0 && actionIndex > captureIndex,
            "Native dispatch capture must be a sibling behind the interactive button, not a parent that blocks it.",
        )
    }

    private fun threadPageSource(): String {
        val commonMain =
            requireNotNull(
                listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                    .firstOrNull(File::isDirectory)
            ) { "Could not locate shared commonMain Kotlin sources" }
        return File(commonMain, "build/raft/app/ThreadPage.kt").readText().replace("\r\n", "\n")
    }

    private fun String.functionWindow(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) { "Missing production function window: $startMarker" }
        return substring(start, end)
    }
}
