package build.raft.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftDiagnosticsVolumePolicyTest {
    private val threadA = "b7610ab9-61de-4158-a56b-2173bc9eaef1"
    private val threadB = "87b96b4f-0aea-406d-b29b-6725d100dab4"

    @Test
    fun manyRoutineEventsInAMinuteBecomeOneSummaryLineWithCountAndSampleIds() {
        val policy = RaftDiagnosticsVolumePolicy()
        val persisted =
            (0 until 40).count { i ->
                val thread = if (i % 2 == 0) threadA else threadB
                policy.admit(
                    "info",
                    "SlockKuikly",
                    "thread_realtime_fold threadId=$thread messagesBefore=30 messagesAfter=30 samePayload=true",
                    nowMillis = i * 1_000L,
                    verbose = false,
                ).persist
            }
        assertEquals(1, persisted)

        // The next line after the minute closes the window: one summary for the 39 held back.
        val next =
            policy.admit("info", "SlockKuikly", "thread_realtime_fold threadId=$threadA", 61_000L, verbose = false)
        assertTrue(next.persist)
        val summary = next.summaries.single()
        assertEquals(
            "diagnostics_summary windowMs=61000 keys=1 suppressed=39 thread_realtime_fold=39/40[87b96b4f,b7610ab9;2]",
            summary
        )
    }

    @Test
    fun drainWritesTheCountsHeldSoFar() {
        val policy = RaftDiagnosticsVolumePolicy()
        repeat(5) { i ->
            policy.admit("info", "SlockSharedHome", "shared_home home_parse_start source=home responseBytes=$i", i.toLong(), false)
        }
        val summaries = policy.drain(10L)
        assertEquals(1, summaries.size)
        assertTrue(summaries.single().endsWith(" shared_home:home_parse_start=4/5"), summaries.single())
        assertTrue(policy.drain(20L).isEmpty())
    }

    @Test
    fun failuresWarningsAndKeepWhenMarkersAreAlwaysWritten() {
        val policy = RaftDiagnosticsVolumePolicy()
        repeat(3) { i ->
            assertTrue(policy.admit("warn", "SlockRealtimeDomain", "realtime_domain_accept_skip reason=duplicate n=$i", i.toLong(), false).persist)
            assertTrue(policy.admit("info", "SlockKuikly", "thread_realtime_fold threadId=$threadA result=error n=$i", i.toLong(), false).persist)
            assertTrue(
                policy.admit(
                    "info",
                    "SlockActivityUnread",
                    "process_activity_unread process_activity_unread_request result=start source=invalidate:realtime n=$i",
                    i.toLong(),
                    false,
                ).persist
            )
        }
    }

    @Test
    fun verboseCategoriesAreNotWrittenWhileOffAndWrittenWhileOn() {
        val off = RaftDiagnosticsVolumePolicy()
        val lines =
            listOf(
                "SlockRealtimeDomain" to "realtime_domain_accept_skip source=app_shell reason=duplicate key=dm:new:x",
                "SlockKuikly" to "thread_avatar_member result=profile serverHash=0e84 memberHash=99a0",
                "SlockKuikly" to "thread_markdown_preparse scope=visible_overscan parsed=7",
                "SlockKuikly" to "thread_markdown_body_recompose key=message:9:e534f082 n=1",
                "SlockKuikly" to "thread_viewport_intent key=reply-stream:$threadA event=pn5 from=following to=user_dragging",
                "SlockSharedNetwork" to "shared_network slock_http_execute_finish path=https://api.raft.build/api/channels code=200 durationMs=80",
            )
        lines.forEach { (tag, message) -> assertFalse(off.admit("info", tag, message, 0L, verbose = false).persist, message) }
        val counts = off.drain(5_000L).single()
        assertTrue(counts.startsWith("diagnostics_verbose_off windowMs=5000 "), counts)
        assertTrue(counts.contains("duplicate=1 avatar=1 markdown=2 viewport=1 network=1"), counts)

        val on = RaftDiagnosticsVolumePolicy()
        lines.forEach { (tag, message) -> assertTrue(on.admit("info", tag, message, 0L, verbose = true).persist, message) }
        assertTrue(on.drain(5_000L).isEmpty())
    }

    @Test
    fun authNetworkLinesAndKeptEventsAreNotGatedOrBudgeted() {
        val policy = RaftDiagnosticsVolumePolicy()
        repeat(30) { i ->
            assertTrue(
                policy.admit("info", "SlockSharedNetwork", "shared_network slock_http_execute_start path=https://api.raft.build/api/auth/refresh n=$i", i.toLong(), false).persist
            )
            assertTrue(policy.admit("info", "SlockKuikly", "thread_read_skip reason=left channelId=$threadA n=$i", i.toLong(), false).persist)
            assertTrue(policy.admit("info", "SlockWebView", "page_finished url=https://x.raft.build/a n=$i", i.toLong(), false).persist)
            assertTrue(policy.admit("info", "SlockArtifactViewer", "artifact_viewer_open id=$i", i.toLong(), false).persist)
            assertTrue(policy.admit("info", "SlockNavTransitionFrames", "nav_transition_frames kind=push n=$i", i.toLong(), false).persist)
        }
        assertTrue(policy.drain(100L).isEmpty())
    }

    @Test
    fun unlistedChattyEventsGetTheRoutineBudgetPerMinute() {
        val policy = RaftDiagnosticsVolumePolicy(routineBudget = 4)
        val kept = (0 until 20).count { i -> policy.admit("info", "SlockKuikly", "thread_new_probe n=$i", i.toLong(), false).persist }
        assertEquals(4, kept)
        assertTrue(policy.drain(30L).single().endsWith(" thread_new_probe=16/20"))
    }

    @Test
    fun oneSummaryLineCarriesEveryKeyMostSuppressedFirstAndSplitsWhenLong() {
        val policy = RaftDiagnosticsVolumePolicy()
        repeat(3) { i -> policy.admit("info", "SlockKuikly", "thread_lazy_window first=$i", i.toLong(), false) }
        repeat(6) { i -> policy.admit("info", "SlockKuikly", "thread_back_to_bottom_pill visible=$i", i.toLong(), false) }
        assertEquals(
            listOf("diagnostics_summary windowMs=100 keys=2 suppressed=7 thread_back_to_bottom_pill=5/6 thread_lazy_window=2/3"),
            policy.drain(100L)
        )

        repeat(200) { key -> repeat(12) { policy.admit("info", "SlockKuikly", "probe_number_$key n=$it", 0L, false) } }
        val parts = policy.drain(1L)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length < 2_048 && it.contains(" part=") }, parts.first())
    }

    @Test
    fun sweepClosesExpiredWindowsWhileTheStreamIsQuiet() {
        val policy = RaftDiagnosticsVolumePolicy()
        repeat(3) { i -> policy.admit("info", "SlockKuikly", "thread_lazy_window first=$i", i.toLong(), false) }
        assertTrue(policy.sweep(30_000L).isEmpty())
        assertTrue(policy.sweep(60_500L).single().endsWith(" thread_lazy_window=2/3"))
    }

    @Test
    fun queueSendsGatedLinesToTheSinkAsNotPersistedAndFlushWritesTheSummary() = runBlocking {
        val records = mutableListOf<RaftPlatformLogRecord>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var verbose = false
        try {
            val queue =
                RaftPlatformLogQueue(scope, recordSink = { records += it }, verboseDiagnostics = { verbose })
            queue.enqueue(RaftLogLevel.Info, "SlockKuikly", "thread_avatar_row source=none", RaftPlatformLogHint.None)
            queue.enqueue(RaftLogLevel.Warn, "SlockKuikly", "thread_avatar_row source=error", RaftPlatformLogHint.None)
            queue.flush()
            verbose = true
            queue.enqueue(RaftLogLevel.Info, "SlockKuikly", "thread_avatar_row source=member", RaftPlatformLogHint.None)
            queue.flush()
        } finally {
            scope.cancel()
        }

        assertEquals(
            listOf(
                "thread_avatar_row source=none" to false,
                "thread_avatar_row source=error" to true,
                "diagnostics_verbose_off" to true,
                "thread_avatar_row source=member" to true,
            ),
            records.map { record -> record.message.substringBefore(" windowMs=") to record.persist }
        )
        assertEquals(RaftDiagnosticsVolumePolicy.SummaryTag, records[2].tag)
    }
}
