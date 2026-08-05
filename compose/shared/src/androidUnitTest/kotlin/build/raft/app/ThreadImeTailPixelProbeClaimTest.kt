package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * task #990: the pixel probe claim gate decides whether a phase samples at all.
 *
 * The previous gate admitted one claim per generation, so a multi-phase timeline
 * was silently impossible — the first phase claimed and every later phase
 * returned null without sampling or reporting. That defect was invisible in the
 * log (a missing phase looks the same as a phase that found nothing), so it is
 * pinned here rather than left to review.
 */
class ThreadImeTailPixelProbeClaimTest {

    @Test
    fun eachPhaseOfOneCycleClaimsExactlyOnce() {
        val probe = ThreadImeTailDrawProbe()
        val generation = probe.beginOrContinue("message-a")

        val onset = probe.claimPixelProbe("onset")
        val frame1 = probe.claimPixelProbe("shrink_frame1")
        val settled = probe.claimPixelProbe("settled")

        assertEquals(generation, onset, "onset must claim the active generation")
        assertEquals(generation, frame1, "frame1 must claim despite onset having claimed")
        assertEquals(generation, settled, "settled must claim despite earlier phases")
    }

    @Test
    fun repeatingOnePhaseIsRefused() {
        val probe = ThreadImeTailDrawProbe()
        probe.beginOrContinue("message-a")

        assertEquals(probe.claimPixelProbe("onset") != null, true)
        assertNull(probe.claimPixelProbe("onset"), "the same phase must not sample twice in one cycle")
    }

    @Test
    fun aNewCycleReopensEveryPhase() {
        val probe = ThreadImeTailDrawProbe()
        probe.beginOrContinue("message-a")
        probe.claimPixelProbe("onset")
        probe.claimPixelProbe("settled")

        // A different target starts a new generation; every phase must be
        // claimable again, otherwise the second IME cycle records nothing.
        val next = probe.beginOrContinue("message-b")
        assertEquals(next, probe.claimPixelProbe("onset"))
        assertEquals(next, probe.claimPixelProbe("settled"))
    }

    @Test
    fun noGenerationMeansNoClaim() {
        val probe = ThreadImeTailDrawProbe()
        assertNull(probe.claimPixelProbe("onset"), "a probe with no active generation must not claim")
    }

    @Test
    fun defaultPhaseKeepsOnePerGeneration() {
        val probe = ThreadImeTailDrawProbe()
        probe.beginOrContinue("message-a")

        assertEquals(probe.claimPixelProbe() != null, true)
        assertNull(probe.claimPixelProbe(), "callers passing no phase keep one claim per generation")
    }
}
