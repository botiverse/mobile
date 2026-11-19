package build.raft.app.compat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftHostImeInsetsTest {
    private data class Case(
        val name: String,
        val composeImePx: Int,
        val hostImePx: Int?,
        val hostAnimating: Boolean,
        val expectedPx: Int,
    )

    @Test
    fun keyboardInsetPrefersTheHostReportUnlessAnAnimationDrivesCompose() {
        listOf(
            // iOS / tests: no host report, WindowInsets.ime alone.
            Case("no host report", composeImePx = 640, hostImePx = null, hostAnimating = false, expectedPx = 640),
            // Hands 1af517cc: the keyboard opened without an animation; Compose still reads 0.
            Case("stale compose on open", composeImePx = 0, hostImePx = 1006, hostAnimating = false, expectedPx = 1006),
            // ...and a keyboard that closed the same way must not leave the composer floating.
            Case("stale compose on close", composeImePx = 1006, hostImePx = 0, hostAnimating = false, expectedPx = 0),
            // A platform animation: follow Compose's frame-by-frame value, not the end state.
            Case("animating open", composeImePx = 310, hostImePx = 1006, hostAnimating = true, expectedPx = 310),
            Case("animating close", composeImePx = 420, hostImePx = 0, hostAnimating = true, expectedPx = 420),
            Case("settled", composeImePx = 1006, hostImePx = 1006, hostAnimating = false, expectedPx = 1006),
        ).forEach { case ->
            assertEquals(
                case.expectedPx,
                resolveRaftImeBottomPx(case.composeImePx, case.hostImePx, case.hostAnimating),
                case.name,
            )
        }
    }

    @Test
    fun keyboardTargetIsWhereTheKeyboardEndsNotWhereItsSlideIs() {
        // iOS: the slide is a third of the way up (310), the target is set as it starts (1006).
        assertEquals(1006, resolveRaftImeTargetBottomPx({ 310 }, { 310 }, hostImeTargetBottomPx = 1006))
        // iOS hiding: the target is 0 from the first frame of the slide down.
        assertEquals(0, resolveRaftImeTargetBottomPx({ 900 }, { 900 }, hostImeTargetBottomPx = 0))
        // Android: no separate target; each dispatch is already the end state.
        assertEquals(1006, resolveRaftImeTargetBottomPx({ 310 }, { 1006 }, hostImeTargetBottomPx = null))
        // No host (tests): WindowInsets.ime.
        assertEquals(640, resolveRaftImeTargetBottomPx({ 640 }, { null }, hostImeTargetBottomPx = null))
        assertEquals(0, resolveRaftImeTargetBottomPx({ -3 }, { null }, hostImeTargetBottomPx = null))
    }

    @Test
    fun aHostTargetDoesNotReadTheSlide() {
        // The values that change every frame of the slide are not read once the host gives a target.
        val target = resolveRaftImeTargetBottomPx({ error("compose ime read") }, { error("host slide read") }, hostImeTargetBottomPx = 812)
        assertEquals(812, target)
    }
}
