package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** The jumped-to message row lays raft-ui's located `info-soft` (0.22.1 #719) as its fill. */
class ThreadMessageFocusHighlightTest {
    @Test
    fun locatedMessageFillIsInfoSoftAtItsStrength() {
        val infoSoft = RaftColor.InfoSoft
        assertEquals(infoSoft, threadMessageLocatedFill(1f))
        assertEquals(infoSoft.copy(alpha = infoSoft.alpha * 0.5f), threadMessageLocatedFill(0.5f))
        assertEquals(RaftColor.Canvas, threadMessageLocatedFill(0f))
    }

    @Test
    fun locatedMessageFillClampsTheStrength() {
        assertEquals(RaftColor.InfoSoft, threadMessageLocatedFill(2f))
        assertEquals(RaftColor.Canvas, threadMessageLocatedFill(-1f))
    }
}
