package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ThreadBackToBottomPointerBoundaryTest {
    @Test
    fun androidCaptureModifierDelegatesToKuiklyNativeDispatch() {
        val source = androidMainSource("build/raft/app/SlockNativeDispatchCapture.android.kt")

        assertTrue(
            "internal actual fun Modifier.slockNativeDispatchCapture(): Modifier" in source &&
                "nativeDispatchCapture()" in source,
            "The shared marker must remain wired to Kuikly's Android native-dispatch capture modifier.",
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

    private fun androidMainSource(relativePath: String): String {
        val androidMain =
            requireNotNull(
                listOf(File("src/androidMain/kotlin"), File("shared/src/androidMain/kotlin"))
                    .firstOrNull(File::isDirectory)
            ) { "Could not locate shared androidMain Kotlin sources" }
        return File(androidMain, relativePath).readText().replace("\r\n", "\n")
    }

    private fun String.functionWindow(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        val end = indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) { "Missing production function window: $startMarker" }
        return substring(start, end)
    }
}
