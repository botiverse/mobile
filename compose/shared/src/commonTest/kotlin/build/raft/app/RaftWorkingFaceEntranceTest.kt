package build.raft.app

import build.raft.designsystem.motion.RaftMotion
import kotlin.test.Test
import kotlin.test.assertEquals

/** Web v1.21.49 `WorkingFaces`: `duration-200 ease-slide starting:-translate-x-1 starting:opacity-0`. */
class RaftWorkingFaceEntranceTest {
    @Test
    fun aJoiningFaceSlidesInFromFourDpLeftAndFadesInOver200msOnTheSlideCurve() {
        assertEquals(200, RaftWorkingFaceEntrance.Spec.durationMillis)
        assertEquals(0, RaftWorkingFaceEntrance.Spec.delay)
        assertEquals(RaftMotion.EaseSlide, RaftWorkingFaceEntrance.Spec.easing)
        assertEquals(0f to -4f, RaftWorkingFaceEntrance.frame(0f), "starts transparent, 4dp to the left")
        assertEquals(0.5f to -2f, RaftWorkingFaceEntrance.frame(0.5f))
        assertEquals(1f to 0f, RaftWorkingFaceEntrance.frame(1f), "ends opaque, in place")
    }
}
