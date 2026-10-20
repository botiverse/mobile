package build.raft.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftPlatformLogTest {
    @Test
    fun sharedLogSinkReceivesSanitizedMessage() = runBlocking {
        val entries = mutableListOf<Triple<String, String, String>>()
        installRaftPlatformLogSink { level, tag, message ->
            entries += Triple(level, tag, message)
        }
        try {
            raftLogInfo(
                tag = "SlockTest",
                message = "token sk_agent_secret123 and Bearer abc.def access_token=abc client_key=qk_secret"
            )
            flushRaftPlatformLogsForTest()
        } finally {
            installRaftPlatformLogSink(null)
        }

        assertEquals(1, entries.size)
        assertEquals("info", entries.single().first)
        assertEquals("SlockTest", entries.single().second)
        assertEquals(
            "token sk_agent_<redacted> and Bearer <redacted> access_token=<redacted> client_key=<redacted>",
            entries.single().third
        )
    }

    @Test
    fun sharedLoggerAddsPrefixWithoutBridge() = runBlocking {
        val entries = mutableListOf<Triple<String, String, String>>()
        installRaftPlatformLogSink { level, tag, message ->
            entries += Triple(level, tag, message)
        }
        try {
            raftLogger("SlockStore", "shared_home", RaftLogLevel.Warn)("loaded")
            flushRaftPlatformLogsForTest()
        } finally {
            installRaftPlatformLogSink(null)
        }

        assertEquals(Triple("warn", "SlockStore", "shared_home loaded"), entries.single())
    }

    @Test
    fun typedRecordSinkReceivesTimestampAndHint() = runBlocking {
        val entries = mutableListOf<RaftPlatformLogRecord>()
        val backgroundStarted = CompletableDeferred<Unit>()
        val releaseBackground = CompletableDeferred<Unit>()
        val markerTag = "RaftPlatformLogTest.typedRecord"
        val markerMessage = "typed-record-marker"
        installRaftPlatformLogRecordSink(entries::add)
        try {
            val background = launch(Dispatchers.Default) {
                raftLogInfo("BackgroundLog", "before typed record")
                backgroundStarted.complete(Unit)
                releaseBackground.await()
                raftLogInfo("BackgroundLog", "after typed record")
            }
            withTimeout(1_000) { backgroundStarted.await() }
            raftLogWarn(markerTag, markerMessage, hint = RaftPlatformLogHint.AuthCritical)
            releaseBackground.complete(Unit)
            withTimeout(1_000) { background.join() }
            flushRaftPlatformLogsForTest()
        } finally {
            releaseBackground.complete(Unit)
            installRaftPlatformLogRecordSink(null)
        }

        val entry =
            entries.single { record ->
                record.tag == markerTag && record.message == markerMessage
            }
        assertEquals("warn", entry.level)
        assertEquals(markerTag, entry.tag)
        assertEquals(markerMessage, entry.message)
        assertEquals(RaftPlatformLogHint.AuthCritical, entry.hint)
        assertTrue(entry.timestampMillis > 0L)
        assertTrue(entries.any { it.tag == "BackgroundLog" })
    }

    @Test
    fun sharedLoggerDoesNotBlockProducerOnSlowSink() = runBlocking {
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        installRaftPlatformLogSink { _, _, _ ->
            sinkEntered.complete(Unit)
            runBlocking { releaseSink.await() }
        }
        try {
            val producer = launch(Dispatchers.Default) {
                raftLogInfo("SlockTest", "slow sink")
            }
            withTimeout(1_000) { producer.join() }
            withTimeout(1_000) { sinkEntered.await() }
            assertFalse(releaseSink.isCompleted)
            releaseSink.complete(Unit)
            flushRaftPlatformLogsForTest()
        } finally {
            releaseSink.complete(Unit)
            installRaftPlatformLogSink(null)
        }
    }

    @Test
    fun acceptedEntriesKeepGlobalOrderAcrossNormalAndCriticalLanes() = runBlocking {
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        val messages = mutableListOf<String>()
        installRaftPlatformLogRecordSink { record ->
            messages += record.message
            if (record.message == "held") {
                sinkEntered.complete(Unit)
                runBlocking { releaseSink.await() }
            }
        }
        try {
            raftLogInfo("SlockTest", "held")
            withTimeout(1_000) { sinkEntered.await() }
            raftLogInfo("SlockTest", "normal-1")
            raftLogWarn("SlockTest", "critical-2", hint = RaftPlatformLogHint.AuthCritical)
            raftLogInfo("SlockTest", "normal-3")
            releaseSink.complete(Unit)
            flushRaftPlatformLogsForTest()
        } finally {
            releaseSink.complete(Unit)
            flushRaftPlatformLogsForTest()
            installRaftPlatformLogRecordSink(null)
        }

        assertEquals(listOf("held", "normal-1", "critical-2", "normal-3"), messages)
    }

    @Test
    fun normalSaturationDoesNotConsumeAuthCriticalReserve() = runBlocking {
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        val captured = mutableListOf<Pair<String, RaftPlatformLogHint>>()
        installRaftPlatformLogRecordSink { record ->
            captured += record.message to record.hint
            if (record.message == "held") {
                sinkEntered.complete(Unit)
                runBlocking { releaseSink.await() }
            }
        }
        try {
            raftLogInfo("SlockTest", "held")
            withTimeout(1_000) { sinkEntered.await() }
            repeat(600) { index -> raftLogInfo("SlockTest", "normal-$index") }
            raftLogWarn("SlockTest", "auth-critical", hint = RaftPlatformLogHint.AuthCritical)
            releaseSink.complete(Unit)
            flushRaftPlatformLogsForTest()
        } finally {
            releaseSink.complete(Unit)
            flushRaftPlatformLogsForTest()
            installRaftPlatformLogRecordSink(null)
        }

        assertTrue(captured.contains("auth-critical" to RaftPlatformLogHint.AuthCritical))
        assertTrue(
            captured.any { (message, _) ->
                message.startsWith("slock_platform_log_queue_dropped lane=normal ") &&
                    "overflow=104" in message
            }
        )
    }

    @Test
    fun authCriticalOverflowIsDropNewAndObservable() = runBlocking {
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        val captured = mutableListOf<Triple<String, String, RaftPlatformLogHint>>()
        installRaftPlatformLogRecordSink { record ->
            captured += Triple(record.tag, record.message, record.hint)
            if (record.message == "held") {
                sinkEntered.complete(Unit)
                runBlocking { releaseSink.await() }
            }
        }
        try {
            raftLogInfo("SlockTest", "held")
            withTimeout(1_000) { sinkEntered.await() }
            repeat(17) { index ->
                raftLogWarn(
                    "SlockTest",
                    "critical-$index",
                    hint = RaftPlatformLogHint.AuthCritical
                )
            }
            releaseSink.complete(Unit)
            flushRaftPlatformLogsForTest()
        } finally {
            releaseSink.complete(Unit)
            flushRaftPlatformLogsForTest()
            installRaftPlatformLogRecordSink(null)
        }

        assertEquals(
            (0 until 16).map { "critical-$it" },
            captured.map { it.second }.filter { it.startsWith("critical-") }
        )
        assertTrue(
            captured.any { (tag, message, hint) ->
                tag == "SlockPlatformLog" &&
                    hint == RaftPlatformLogHint.AuthCritical &&
                    message ==
                    "slock_platform_log_queue_dropped lane=auth_critical count=1 " +
                    "overflow=1 admissionBusy=0 capacity=16"
            }
        )
    }

    @Test
    fun sharedActivityDiagnosticsLoggerStampsOneMonotonicSequenceAcrossInstances() = runBlocking {
        val entries = mutableListOf<RaftPlatformLogRecord>()
        installRaftPlatformLogRecordSink(entries::add)
        try {
            // Two instances on purpose: both call sites build their own logger, and
            // independent counters would make the runs uncomparable — which is what
            // makes a contiguous run usable as proof that nothing was dropped.
            val first = sharedActivityStoreDiagnosticsLogger()
            val second = sharedActivityStoreDiagnosticsLogger()
            first("store_load domain=activity phase=probe channel=live")
            second("store_load domain=activity phase=start")
            first("store_load domain=activity result=ok")
            flushRaftPlatformLogsForTest()
        } finally {
            installRaftPlatformLogRecordSink(null)
        }

        val sequences =
            entries
                .filter { it.message.contains("domain=activity") }
                .mapNotNull { record -> Regex("""seq=(\d+)""").find(record.message)?.groupValues?.get(1)?.toLong() }
        assertEquals(3, sequences.size, "every activity diagnostic line must carry a sequence; actual=$entries")
        assertEquals(
            sequences.sorted(),
            sequences,
            "sequence must be monotonic across both logger instances; actual=$sequences"
        )
        assertEquals(
            (sequences.first()..sequences.last()).toList(),
            sequences,
            "a run with no drops must be contiguous, otherwise absence carries no information; actual=$sequences"
        )
    }

    @Test
    fun sharedActivityDiagnosticsSequenceNeverRepeatsAValueUnderConcurrency() = runBlocking {
        // Tested on the stamping function directly, NOT through the logger. Delivery
        // drops under admission contention, so a duplicated number could be hidden by
        // one of the pair being dropped — the assertion would then pass on a broken
        // counter. Off the lossy path every allocation is observable, so this locks
        // the contract deterministically.
        //
        // Uniqueness is the property the whole protocol rests on: if one value were
        // handed to two lines, a dropped value could be masked by a surviving one and
        // a gap-free set would stop proving that nothing was lost.
        val emitters = 64
        val perEmitter = 16
        val stamped =
            (1..emitters)
                .map { async(Dispatchers.Default) { List(perEmitter) { stampSharedActivityDiagnosticsSequence("line") } } }
                .flatMap { it.await() }

        val values =
            stamped.mapNotNull { line -> Regex("""^seq=(\d+) """).find(line)?.groupValues?.get(1)?.toLong() }
        assertEquals(emitters * perEmitter, values.size, "every stamp must carry a parseable sequence")
        assertEquals(
            values.size,
            values.distinct().size,
            "a sequence value must never be handed out twice under concurrency"
        )
    }
}
