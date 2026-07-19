package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ThreadBackToBottomPointerBoundaryTest {
    @Test
    fun kuiklyLatchesNativeCaptureFromTheActionDownHitPath() {
        val hitPathTracker =
            repoFile(
                "third_party/kuikly-ui/compose/src/commonMain/kotlin/" +
                    "com/tencent/kuikly/compose/ui/input/pointer/HitPathTracker.kt"
            ).readText()
        val superTouchManager =
            repoFile(
                "third_party/kuikly-ui/compose/src/commonMain/kotlin/" +
                    "com/tencent/kuikly/compose/container/SuperTouchManager.kt"
            ).readText()
        val androidRoot =
            repoFile(
                "third_party/kuikly-ui/core-render-android/src/main/java/" +
                    "com/tencent/kuikly/core/render/android/expand/component/KRView.kt"
            ).readText()

        assertTrue(
            "val nativeDispatchCaptured = root.capturesNativeDispatch()" in hitPathTracker,
            "Kuikly must derive native capture from the Compose pointer hit path.",
        )
        assertTrue(
            "if (result.nativeDispatchCaptured)" in superTouchManager &&
                "getView()?.getViewAttr()?.nativeDispatchCapture(true)" in superTouchManager,
            "The Compose hit-path result must request capture on the native root.",
        )
        assertTrue(
            "if (event.actionMasked == MotionEvent.ACTION_DOWN)" in androidRoot &&
                "nativeDispatchCapturedGesture = nativeDispatchCaptureRequested" in androidRoot &&
                "if (nativeDispatchCapturedGesture)" in androidRoot,
            "Android must latch that request at ACTION_DOWN for the complete native gesture.",
        )
    }

    @Test
    fun floatingButtonCapturesNativeDispatchOnItsInteractiveHitPath() {
        val source = threadPageSource()
        val button =
            source.functionWindow(
                "private fun ThreadBackToBottomButton(",
                "private const val ThreadBackToBottomReleaseGuardMillis",
            )

        val actionIndex = button.indexOf("SlockBrutalActionButton(")
        val captureIndex = button.indexOf("modifier = modifier.slockNativeDispatchCapture()", actionIndex)

        assertTrue(
            actionIndex >= 0 && captureIndex > actionIndex,
            "Native dispatch capture must be on the interactive button's own ancestor hit path.",
        )
        assertTrue(
            ".matchParentSize()" !in button,
            "A behind-button sibling is not part of the ACTION_DOWN hit path and must not be used as the capture oracle.",
        )
    }

    @Test
    fun floatingButtonKeepsTheCaptureMountedUntilAfterScrollAndRelease() {
        val source = threadPageSource()
        val rendering =
            source.functionWindow(
                "val shouldShowBackToBottomButton =",
                "reactionDetails?.let { details ->",
            )

        val guardStart = rendering.indexOf("backToBottomInteractionGuardActive = true")
        val scrollStart = rendering.indexOf("coroutineScope.launch", guardStart)
        val nonCancellableRelease = rendering.indexOf("withContext(NonCancellable)", scrollStart)
        val guardEnd = rendering.indexOf("backToBottomInteractionGuardActive = false", nonCancellableRelease)

        assertTrue(
            "shouldKeepThreadBackToBottomOverlayMounted(" in rendering,
            "Scroll-derived visibility must be ORed with the in-flight interaction guard.",
        )
        assertTrue(
            guardStart >= 0 && scrollStart > guardStart,
            "The overlay guard must be active before the scroll can hide the button.",
        )
        assertTrue(
            nonCancellableRelease > scrollStart && guardEnd > nonCancellableRelease,
            "The guard must survive scroll cancellation and release only after the native release grace.",
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

    private fun repoFile(relativePath: String): File {
        val start = File(System.getProperty("user.dir")).canonicalFile
        return requireNotNull(
            generateSequence(start) { it.parentFile }
                .map { root -> File(root, relativePath) }
                .firstOrNull(File::isFile)
        ) { "Could not locate repository file: $relativePath from $start" }
    }

    private fun String.functionWindow(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) { "Missing production function window: $startMarker" }
        return substring(start, end)
    }
}
