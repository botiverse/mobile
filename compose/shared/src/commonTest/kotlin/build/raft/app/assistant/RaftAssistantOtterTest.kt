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
        assertEquals("curious", raftOtterPose(RaftOtterMood.Idle).name)
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
        check(RaftOtterMood.Idle, 89, 60, 47)
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
        assertEquals(both, raftOtterPaws(RaftOtterMood.Idle))
        assertEquals(both, raftOtterPaws(RaftOtterMood.Working))
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
    fun bandIsThePoseHeightAtScaleRoundedUp() {
        // Web: Math.ceil(pose.h * scale).
        assertEquals(60.dp, raftAssistantOtterBandHeight(RaftOtterMood.Idle, scale = 1f))
        assertEquals(71.dp, raftAssistantOtterBandHeight(RaftOtterMood.Away, scale = 1f))
        assertEquals(48.dp, raftAssistantOtterBandHeight(RaftOtterMood.Idle, scale = 0.8f)) // 48.0
        assertEquals(50.dp, raftAssistantOtterBandHeight(RaftOtterMood.Working, scale = 0.8f)) // 49.6
        assertEquals(42.dp, raftAssistantOtterBandHeight(RaftOtterMood.New, scale = 0.8f)) // 41.6
        assertEquals(53.dp, raftAssistantOtterBandHeight(RaftOtterMood.Confirm, scale = 0.8f)) // 52.8
        assertEquals(57.dp, raftAssistantOtterBandHeight(RaftOtterMood.Away, scale = 0.8f)) // 56.8
        assertEquals(raftAssistantOtterBandHeight(RaftOtterMood.Idle), 48.dp, "phone scale by default")
    }

    @Test
    fun edgeMetricsForHosts() {
        assertEquals(9.dp * 0.8f, RaftAssistantOtterDefaults.belowEdge())
        assertEquals(58.dp, RaftAssistantOtterDefaults.aboveEdge(RaftOtterMood.Idle, scale = 1f))
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
    fun tuckSharesMatchTheWeb() {
        assertEquals(0f, RaftOtterTuck.None.fraction)
        assertEquals(0.58f, RaftOtterTuck.Composing.fraction)
        assertEquals(1.05f, RaftOtterTuck.PanelOpen.fraction)
    }
}
