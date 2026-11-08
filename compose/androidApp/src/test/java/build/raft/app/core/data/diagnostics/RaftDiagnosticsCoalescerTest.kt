package build.raft.app.core.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftDiagnosticsCoalescerTest {
    private val skip =
        "realtime_domain_accept_skip source=app_shell reason=duplicate " +
            "key=member:presence:bccd5969-5c94-498c-9221-625f87d5ca12:3bc83ca0-d0b3-45c4-a623-e2f6a397d062:"

    private fun coalescer() = RaftDiagnosticsCoalescer(windowMillis = 60_000L, budget = 3, maxShapes = 8)

    @Test
    fun keepsTheBudgetThenCountsRepeatsAndSummarizesWhenTheWindowCloses() {
        val coalescer = coalescer()
        val kept = (0 until 100).count { i ->
            coalescer.admit("info", "SlockRealtimeDomain", "$skip${1_000 + i}", nowMillis = i * 100L).persist
        }
        // Same shape (only the timestamp differs): the budget of 3 and nothing else.
        assertEquals(3, kept)

        val next = coalescer.admit("info", "SlockRealtimeDomain", "${skip}9", nowMillis = 60_000L)

        assertTrue(next.persist)
        assertEquals(
            listOf(
                "diagnostics_coalesced key=SlockRealtimeDomain:realtime_domain_accept_skip " +
                    "suppressed=97 kept=3 windowMs=60000"
            ),
            next.summaries
        )
    }

    @Test
    fun aNewShapeInTheWindowIsStillWrittenSoStateChangesSurvive() {
        val coalescer = coalescer()
        repeat(10) { i ->
            coalescer.admit("info", "SlockKuikly", "thread_back_to_bottom_pill visible=false first=$i:0", i.toLong())
        }

        val change = coalescer.admit("info", "SlockKuikly", "thread_back_to_bottom_pill visible=true first=11:0", 20L)
        val repeat = coalescer.admit("info", "SlockKuikly", "thread_back_to_bottom_pill visible=true first=12:0", 21L)

        assertTrue(change.persist)
        assertFalse(repeat.persist)
    }

    @Test
    fun distinctShapesAreCappedPerWindow() {
        val coalescer = RaftDiagnosticsCoalescer(windowMillis = 60_000L, budget = 1, maxShapes = 3)
        val kept = listOf("a", "b", "c", "d", "e").count { state ->
            coalescer.admit("info", "SlockKuikly", "thread_back_to_bottom_pill state=$state", 0L).persist
        }
        assertEquals(3, kept)
    }

    @Test
    fun warnErrorFailuresAndUnlistedEventsAreNeverCoalesced() {
        val coalescer = RaftDiagnosticsCoalescer(windowMillis = 60_000L, budget = 0, maxShapes = 0)
        repeat(5) { i ->
            assertTrue(coalescer.admit("warn", "SlockRealtimeDomain", "$skip$i", i.toLong()).persist)
            assertTrue(coalescer.admit("error", "SlockRealtimeDomain", "$skip$i", i.toLong()).persist)
            assertTrue(
                coalescer.admit(
                    "info",
                    "SlockSharedHome",
                    "shared_home home_parse_result source=home result=error durationMs=$i",
                    i.toLong()
                ).persist
            )
            assertTrue(
                coalescer.admit("info", "SlockKuikly", "thread_avatar_member result=profile n=$i", i.toLong()).persist
            )
        }
        assertTrue(coalescer.drain(10L).isEmpty())
    }

    @Test
    fun subEventsOfAPrefixFamilyHaveTheirOwnBudget() {
        assertEquals(
            "SlockSharedHome shared_home home_parse_start",
            RaftDiagnosticsCoalescer.coalescerKey("SlockSharedHome", "shared_home home_parse_start source=x")
        )
        assertEquals(
            "SlockRealtimeDomain realtime_domain_accept",
            RaftDiagnosticsCoalescer.coalescerKey("SlockRealtimeDomain", "realtime_domain_accept source=app_shell")
        )
        val coalescer = RaftDiagnosticsCoalescer(windowMillis = 60_000L, budget = 1, maxShapes = 1)
        assertTrue(coalescer.admit("info", "T", "shared_home home_parse_start bytes=1", 0L).persist)
        assertFalse(coalescer.admit("info", "T", "shared_home home_parse_start bytes=2", 1L).persist)
        assertTrue(coalescer.admit("info", "T", "shared_home home_parse_result bytes=2", 2L).persist)
    }

    @Test
    fun pureNoiseProbeKeepsOnlyItsCount() {
        val coalescer = coalescer()
        val stale = "thread_ime_tail_pixel_probe generation=1 status=stale_before_claim threadInstanceCurrent=false"
        repeat(4) { i -> assertFalse(coalescer.admit("info", "SlockKuikly", stale, i.toLong()).persist) }
        assertTrue(
            coalescer.admit("info", "SlockKuikly", "thread_ime_tail_pixel_probe generation=2 status=ok", 5L).persist
        )

        assertEquals(
            listOf(
                "diagnostics_coalesced key=SlockKuikly:thread_ime_tail_pixel_probe suppressed=4 kept=1 windowMs=7"
            ),
            coalescer.drain(7L)
        )
        assertTrue(coalescer.drain(8L).isEmpty())
    }

    @Test
    fun aQuietEventsCountIsFlushedByTheSweepOnAnyLaterLine() {
        val coalescer = coalescer()
        repeat(5) { i -> coalescer.admit("info", "SlockKuikly", "thread_realtime_apply_result emitted=false", i.toLong()) }

        val later = coalescer.admit("info", "SlockKuikly", "thread_avatar_row source=none", 61_000L)

        assertTrue(later.persist)
        assertEquals(1, later.summaries.size)
        assertTrue(later.summaries.single(), later.summaries.single().contains("suppressed=2 kept=3"))
    }

    @Test
    fun keyCapLetsNewKeysThroughUncounted() {
        val coalescer =
            RaftDiagnosticsCoalescer(windowMillis = 60_000L, budget = 0, maxShapes = 0, maxKeys = 1)
        assertFalse(coalescer.admit("info", "A", "thread_back_to_bottom_pill x=1", 0L).persist)
        assertTrue(coalescer.admit("info", "B", "thread_back_to_bottom_pill x=1", 0L).persist)
    }

    @Test
    fun shapeIgnoresIdsAndNumbers() {
        assertEquals(
            RaftDiagnosticsCoalescer.diagnosticsMessageShape("$skip 1794132645901 first=27:-738+1046"),
            RaftDiagnosticsCoalescer.diagnosticsMessageShape(
                skip.replace("bccd5969", "0123abcd") + " 1794132645999 first=31:-12+54"
            )
        )
    }

    @Test
    fun checkpointPersistsCountsSoFarWithoutResettingTheBudgetAndNeverCountsTwice() {
        val coalescer = coalescer()
        repeat(10) { i -> coalescer.admit("info", "SlockRealtimeDomain", "$skip$i", i.toLong()) }

        assertEquals(
            listOf(
                "diagnostics_coalesced key=SlockRealtimeDomain:realtime_domain_accept_skip " +
                    "suppressed=7 kept=3 windowMs=30000 partial=true"
            ),
            coalescer.checkpoint(30_000L)
        )
        // The budget is still spent: further repeats in the window stay suppressed.
        assertFalse(coalescer.admit("info", "SlockRealtimeDomain", "${skip}11", 30_001L).persist)
        assertTrue(coalescer.checkpoint(30_002L).single().contains("suppressed=1 kept=3"))
        assertTrue(coalescer.checkpoint(30_003L).isEmpty())
        assertTrue(coalescer.drain(30_004L).isEmpty())
    }
}
