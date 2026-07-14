package build.raft.app.core.data.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadScrollInvalidationSourceContractTest {
    @Test
    fun messageRowsDoNotSubscribeTheWholeCardToLazyListScrollState() {
        val source =
            File(
                findRepoRoot(),
                "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt"
            ).readText()

        assertFalse(
            "ThreadMessagesList must not read scroll state into a row-wide Boolean; that invalidates every visible message card.",
            source.contains("isMessageListScrolling = listState.isScrollInProgress")
        )
        assertFalse(
            "ThreadMessagesList must not rebuild every row just to toggle long-press anchor capture during scrolling.",
            source.contains("val captureMessageAnchors = !listState.isScrollInProgress")
        )
        assertTrue(
            "Scroll state should be deferred to the avatar leaf that owns status pulsing.",
            source.contains("isMessageListScrolling = { listState.isScrollInProgress }") &&
                source.contains("isMessageListScrolling: () -> Boolean")
        )
        assertTrue(
            "Long-press eligibility should be read inside the gesture callback instead of during row composition.",
            source.contains("captureLongPressAnchor = { !listState.isScrollInProgress }") &&
                source.contains("val currentCaptureLongPressAnchor by rememberUpdatedState(captureLongPressAnchor)") &&
                source.contains("if (currentCaptureLongPressAnchor())")
        )
    }

    private fun findRepoRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        var current = File(userDir).absoluteFile
        while (current.parentFile != null) {
            if (File(current, "AGENTS.md").isFile && File(current, "compose/settings.gradle.kts").isFile) {
                return current
            }
            current = requireNotNull(current.parentFile)
        }
        error("Could not find repo root from $userDir")
    }
}
