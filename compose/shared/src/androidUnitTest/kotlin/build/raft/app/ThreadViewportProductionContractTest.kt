package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadViewportProductionContractTest {
    @Test
    fun threadHasExactlyOneViewportWriteBoundary() {
        val source = threadPageSource()
        val executor = source.substringBetween(
            "// THREAD_VIEWPORT_EXECUTOR_START",
            "// THREAD_VIEWPORT_EXECUTOR_END"
        )
        val outsideExecutor = source.replace(executor, "")

        assertTrue(executor.contains("listState.animateScrollToItem(0)"))
        assertTrue(executor.contains("listState.scrollLatestItemToRenderedBottom(targetIndex)"))
        assertTrue(executor.contains("listState.scrollToItem(targetIndex, -target.offset)"))
        assertTrue(executor.contains("listState.scrollThreadItemToFocusedViewport(targetIndex)"))
        assertFalse(outsideExecutor.contains("listState.scrollToItem("))
        assertFalse(outsideExecutor.contains("listState.animateScrollToItem("))
        assertFalse(outsideExecutor.contains("listState.scrollBy("))
        assertFalse(outsideExecutor.contains("listState.animateScrollBy("))
        assertFalse(outsideExecutor.contains("listState.animateScrollToTopExpressive("))
        assertFalse(outsideExecutor.contains("listState.scrollLatestItemToRenderedBottom("))
        assertFalse(outsideExecutor.contains("listState.scrollThreadItemToFocusedViewport("))
        assertFalse(source.contains("anchorInitialThreadBottomToLatest("))
    }

    @Test
    fun allThreadViewportIntentFamiliesSubmitToTheController() {
        val source = threadPageSource()
        val localSendHandoff =
            source.substringBetween(
                "// THREAD_LOCAL_SEND_VIEWPORT_HANDOFF_START",
                "// THREAD_LOCAL_SEND_VIEWPORT_HANDOFF_END"
            )
        val newerPageSettlement =
            source.substringBetween(
                "// THREAD_NEWER_PAGE_VIEWPORT_SETTLEMENT_START",
                "// THREAD_NEWER_PAGE_VIEWPORT_SETTLEMENT_END"
            )

        assertEquals(1, source.countOccurrences("LazyViewportEvent.PinTop"))
        assertTrue(
            localSendHandoff.contains(
                "LazyViewportEvent.FollowLatest(canOverrideUserDragging = false)"
            ),
            "local-send follow requests must preserve an active direct drag"
        )
        assertFalse(localSendHandoff.contains("canOverrideUserDragging = true"))
        assertTrue(
            source.contains(
                "LazyViewportEvent.FollowLatest(canOverrideUserDragging = true)"
            ),
            "explicit back-to-bottom must be able to replace the current viewport owner"
        )
        assertTrue(source.countOccurrences("LazyViewportEvent.Anchor(") >= 4)
        assertTrue(source.contains("LazyViewportEvent.PointerDown"))
        assertTrue(source.contains("LazyViewportEvent.PointerMove"))
        assertTrue(source.contains("LazyViewportEvent.PointerSettled("))
        assertTrue(source.contains("LazyViewportMutation.Prepend"))
        assertTrue(source.contains("LazyViewportMutation.Append"))
        assertTrue(source.contains("shouldRestoreThreadNewerViewportAfterSettlement("))
        assertTrue(source.contains("outcome = outcome"))
        assertTrue(
            newerPageSettlement.contains(
                "LazyViewportEvent.NewerPageSettled("
            ),
            "newer-page geometry must restore the current owner through the viewport controller"
        )
        assertTrue(newerPageSettlement.contains("expectedViewportEpoch = restoreTicket.expectedViewportEpoch"))
        assertTrue(newerPageSettlement.contains("expectedViewportIntent = restoreTicket.expectedViewportIntent"))
        assertTrue(newerPageSettlement.contains("requestAnchor ="))
        assertTrue(source.contains("val activeCycle = newerPageCycle"))
        assertTrue(source.contains("newerPageCycle = cycle.copy(anchor = anchor)"))
        assertFalse(newerPageSettlement.contains("LazyViewportEvent.Anchor("))
        assertTrue(source.contains("LazyViewportMutation.HeightChange"))
        assertTrue(source.contains("LazyViewportMutation.InsetChange"))
    }

    private fun threadPageSource(): String =
        listOf(
            File("src/commonMain/kotlin/build/raft/app/ThreadPage.kt"),
            File("shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt")
        ).firstOrNull(File::isFile)?.readText()?.replace("\r\n", "\n")
            ?: error("ThreadPage.kt source not found")

    private fun String.substringBetween(start: String, end: String): String {
        val startIndex = indexOf(start)
        val endIndex = indexOf(end, startIndex + start.length)
        require(startIndex >= 0 && endIndex > startIndex) { "viewport executor markers missing" }
        return substring(startIndex, endIndex + end.length)
    }

    private fun String.countOccurrences(needle: String): Int =
        windowed(needle.length, step = 1).count { it == needle }
}
