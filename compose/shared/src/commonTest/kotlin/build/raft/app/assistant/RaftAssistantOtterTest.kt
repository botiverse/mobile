package build.raft.app.assistant

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The otter's pure rules against web assistant/AssistantOtter.tsx and assistantOtterState.ts. */
class RaftAssistantOtterTest {
    @Test
    fun moodPicksTheWebPose() {
        // Nothing new: it sleeps (Artea, #proj-assistant:#24).
        assertEquals("sleepy", raftOtterPose(RaftOtterMood.Idle).name)
        assertEquals("thinking", raftOtterPose(RaftOtterMood.Working).name)
        assertEquals("wave", raftOtterPose(RaftOtterMood.New).name)
        assertEquals("confused", raftOtterPose(RaftOtterMood.Confirm).name)
        assertEquals("sleepy", raftOtterPose(RaftOtterMood.Away).name)
        RaftOtterMood.entries.forEach { assertEquals("running", raftOtterPose(it, entering = true).name) }
    }

    @Test
    fun posesKeepTheWebSizesAndHeadX() {
        fun check(mood: RaftOtterMood, w: Int, h: Int, cx: Int) {
            val pose = raftOtterPose(mood)
            assertEquals(w.dp, pose.width, "$mood width")
            assertEquals(h.dp, pose.height, "$mood height")
            assertEquals(cx.dp, pose.headX, "$mood head x")
        }
        check(RaftOtterMood.Idle, 135, 71, 65)
        check(RaftOtterMood.New, 96, 52, 47)
        check(RaftOtterMood.Working, 87, 62, 44)
        check(RaftOtterMood.Confirm, 93, 66, 50)
        check(RaftOtterMood.Away, 135, 71, 65)
        val running = raftOtterPose(RaftOtterMood.Idle, entering = true)
        assertEquals(109.dp, running.width)
        assertEquals(60.dp, running.height)
        assertEquals(63.dp, running.headX)
    }

    @Test
    fun pawsFollowTheMood() {
        val both = listOf((-37).dp, 15.dp)
        assertEquals(emptyList(), raftOtterPaws(RaftOtterMood.Idle))
        assertEquals(listOf(15.dp), raftOtterPaws(RaftOtterMood.Working))
        assertEquals(both, raftOtterPaws(RaftOtterMood.Confirm))
        assertEquals(listOf(15.dp), raftOtterPaws(RaftOtterMood.New))
        assertEquals(emptyList(), raftOtterPaws(RaftOtterMood.Away))
        RaftOtterMood.entries.forEach { assertEquals(both, raftOtterPaws(it, entering = true)) }
    }

    @Test
    fun bubbleOnlyForNewAndConfirmOnceSettled() {
        assertTrue(raftOtterShowsBubble(RaftOtterMood.New))
        assertTrue(raftOtterShowsBubble(RaftOtterMood.Confirm))
        assertFalse(raftOtterShowsBubble(RaftOtterMood.Idle))
        assertFalse(raftOtterShowsBubble(RaftOtterMood.Working))
        assertFalse(raftOtterShowsBubble(RaftOtterMood.Away))
        assertFalse(raftOtterShowsBubble(RaftOtterMood.New, entering = true))
    }

    @Test
    fun edgeMetricsForHosts() {
        assertEquals(9.dp * 0.8f, RaftAssistantOtterDefaults.belowEdge())
        assertEquals(69.dp, RaftAssistantOtterDefaults.aboveEdge(RaftOtterMood.Idle, scale = 1f))
    }

    @Test
    fun moodPrecedenceMatchesTheWeb() {
        fun mood(paused: Boolean = false, confirm: Boolean = false, unread: Boolean = false, working: Boolean = false, offline: Boolean = false) =
            raftOtterMood(paused = paused, confirm = confirm, unread = unread, working = working, offline = offline)
        assertEquals(RaftOtterMood.Idle, mood())
        assertEquals(RaftOtterMood.Away, mood(paused = true, confirm = true, unread = true, working = true))
        assertEquals(RaftOtterMood.Confirm, mood(confirm = true, unread = true, working = true, offline = true))
        assertEquals(RaftOtterMood.New, mood(unread = true, working = true, offline = true))
        assertEquals(RaftOtterMood.Working, mood(working = true))
        assertEquals(RaftOtterMood.Away, mood(offline = true))
    }

    @Test
    fun itSleepsWhenNothingIsNewAndWakesForSomething() {
        assertTrue(raftOtterAsleep(RaftOtterMood.Idle))
        assertTrue(raftOtterAsleep(RaftOtterMood.Away))
        assertFalse(raftOtterAsleep(RaftOtterMood.Working))
        assertFalse(raftOtterAsleep(RaftOtterMood.New))
        assertFalse(raftOtterAsleep(RaftOtterMood.Idle, entering = true))
        assertTrue(raftOtterAwake(RaftOtterMood.New))
        assertTrue(raftOtterAwake(RaftOtterMood.Confirm))
        // Busy keeps its own face: neither asleep nor waking.
        assertFalse(raftOtterAwake(RaftOtterMood.Working))
        assertFalse(raftOtterAwake(RaftOtterMood.Idle))
        assertFalse(raftOtterAwake(RaftOtterMood.New, entering = true))
    }

    /** Web index.css `assistant-otter-breathe`: 3.4s, at rest at both ends, `scale(1.015, 1.045)` halfway. */
    @Test
    fun aBreathFollowsTheWebKeyframes() {
        val rest = raftOtterBreath(0)
        assertEquals(1f, rest.scaleX, 1e-5f)
        assertEquals(1f, rest.scaleY, 1e-5f)
        val full = raftOtterBreath(RaftOtterBreatheMillis / 2L)
        assertEquals(1.015f, full.scaleX, 1e-4f)
        assertEquals(1.045f, full.scaleY, 1e-4f)
        // A loop: one period on, the same breath.
        assertEquals(raftOtterBreath(700).scaleY, raftOtterBreath(700L + RaftOtterBreatheMillis).scaleY, 1e-5f)
    }

    /** Web `assistant-otter-zzz`: 2.7s each, the second and third 0.9s / 1.8s later; up 24 and right 12, fading. */
    @Test
    fun theZsRiseOneAfterAnother() {
        val first = raftOtterZ(0, 0)
        assertEquals(0f, first.alpha, 1e-5f)
        assertEquals(0.7f, first.scale, 1e-5f)
        assertEquals(0.9f, raftOtterZ(0, (RaftOtterZzzMillis * 0.2f).toLong()).alpha, 1e-3f)
        val top = raftOtterZ(0, RaftOtterZzzMillis - 1L)
        assertEquals(12f, top.dx, 0.05f)
        assertEquals(-24f, top.dy, 0.1f)
        assertEquals(1.15f, top.scale, 0.01f)
        assertTrue(top.alpha < 0.01f)
        // Not there before its delay.
        assertEquals(0f, raftOtterZ(1, RaftOtterZzzStaggerMillis - 1L).alpha)
        assertEquals(0f, raftOtterZ(2, 2L * RaftOtterZzzStaggerMillis - 1L).alpha)
        assertTrue(raftOtterZ(2, 2L * RaftOtterZzzStaggerMillis + 300).alpha > 0f)
        // Reduced motion: one z, still, at 70%.
        assertEquals(0.7f, RaftOtterStillZ.alpha)
        assertEquals(1f, RaftOtterStillZ.scale)
    }

    /** Web `assistant-otter-wake`: from behind the edge, out past its rest, then two bobs up; only up and down. */
    @Test
    fun wakingPopsOutThenBobsTwice() {
        assertEquals(16f, raftOtterWake(0f).dy, 1e-4f)
        assertEquals(0.9f, raftOtterWake(0f).scale, 1e-5f)
        assertEquals(-6f, raftOtterWake(RaftOtterWakeMillis * 0.35f).dy, 1e-3f)
        assertEquals(1.06f, raftOtterWake(RaftOtterWakeMillis * 0.35f).scale, 1e-3f)
        assertEquals(0f, raftOtterWake(RaftOtterWakeMillis * 0.5f).dy, 1e-3f)
        assertEquals(-5f, raftOtterWake(RaftOtterWakeMillis * 0.62f).dy, 1e-3f)
        assertEquals(0f, raftOtterWake(RaftOtterWakeMillis * 0.75f).dy, 1e-3f)
        assertEquals(-5f, raftOtterWake(RaftOtterWakeMillis * 0.87f).dy, 1e-3f)
        val rest = raftOtterWake(RaftOtterWakeMillis.toFloat())
        assertEquals(0f, rest.dy, 1e-5f)
        assertEquals(1f, rest.scale, 1e-5f)
        // Past its end it stays at rest.
        assertEquals(0f, raftOtterWake(RaftOtterWakeMillis * 2f).dy, 1e-5f)
    }

    @Test
    fun itsSleepLoopStopsWhileThePanelHidesIt() {
        fun runs(
            asleep: Boolean = true,
            foreground: Boolean = true,
            reducedMotion: Boolean = false,
            heldStill: Boolean = false,
            tuck: RaftOtterTuck = RaftOtterTuck.None,
        ) = raftOtterLoopRuns(asleep, foreground, reducedMotion, heldStill, tuck)
        assertTrue(runs())
        assertTrue(runs(tuck = RaftOtterTuck.Composing), "tucked for composing, its head still shows")
        assertFalse(runs(tuck = RaftOtterTuck.PanelOpen), "hidden behind the open panel: no frame clock")
        assertFalse(runs(asleep = false))
        assertFalse(runs(foreground = false))
        assertFalse(runs(reducedMotion = true))
        assertFalse(runs(heldStill = true))
    }

    @Test
    fun tuckSharesMatchTheWeb() {
        assertEquals(0f, RaftOtterTuck.None.fraction)
        assertEquals(0.58f, RaftOtterTuck.Composing.fraction)
        assertEquals(1.05f, RaftOtterTuck.PanelOpen.fraction)
    }
}
