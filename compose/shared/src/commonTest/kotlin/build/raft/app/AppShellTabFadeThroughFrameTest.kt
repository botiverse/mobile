package build.raft.app

import build.raft.designsystem.motion.RaftMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The frames of the bottom-tab fade through follow [RaftMotion.fadeThrough]'s timing. */
class AppShellTabFadeThroughFrameTest {
    @Test
    fun outgoingFadesOutFirstThenIncomingFadesInAndGrows() {
        val start = appShellTabFadeThroughFrame(0f)
        assertEquals(AppShellTabFadeThroughFrame(1f, 0f, RaftMotion.FadeThroughStartScale), start)

        val mid = appShellTabFadeThroughFrame(RaftMotion.FadeThroughOutgoingMillis / 2f)
        assertTrue(mid.outgoingAlpha in 0.01f..0.99f && mid.incomingAlpha == 0f, "outgoing part: $mid")

        val handover = appShellTabFadeThroughFrame(RaftMotion.FadeThroughOutgoingMillis.toFloat())
        assertEquals(0f, handover.outgoingAlpha)
        assertEquals(false, handover.outgoingShown)
        assertEquals(0f, handover.incomingAlpha)

        val incoming = appShellTabFadeThroughFrame(RaftMotion.FadeThroughOutgoingMillis + RaftMotion.FadeThroughIncomingMillis / 2f)
        assertTrue(incoming.incomingAlpha in 0.01f..0.99f, "incoming part: $incoming")
        assertTrue(incoming.incomingScale in RaftMotion.FadeThroughStartScale..1f, "incoming part: $incoming")

        assertEquals(AppShellTabFadeThroughFrame.Rest, appShellTabFadeThroughFrame(RaftMotion.FadeThroughMillis.toFloat()))
    }
}
