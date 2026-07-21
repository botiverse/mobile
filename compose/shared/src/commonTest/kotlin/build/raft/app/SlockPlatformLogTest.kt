package build.raft.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockPlatformLogTest {
    @Test
    fun sharedLogSinkReceivesSanitizedMessage() = runBlocking {
        val entries = mutableListOf<Triple<String, String, String>>()
        installSlockPlatformLogSink { level, tag, message ->
            entries += Triple(level, tag, message)
        }
        try {
            slockLogInfo(
                tag = "SlockTest",
                message = "token sk_agent_secret123 and Bearer abc.def access_token=abc client_key=qk_secret"
            )
            flushSlockPlatformLogsForTest()
        } finally {
            installSlockPlatformLogSink(null)
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
        installSlockPlatformLogSink { level, tag, message ->
            entries += Triple(level, tag, message)
        }
        try {
            slockLogger("SlockStore", "shared_home", SlockLogLevel.Warn)("loaded")
            flushSlockPlatformLogsForTest()
        } finally {
            installSlockPlatformLogSink(null)
        }

        assertEquals(Triple("warn", "SlockStore", "shared_home loaded"), entries.single())
    }

    @Test
    fun typedRecordSinkReceivesTimestampAndHint() = runBlocking {
        val entries = mutableListOf<SlockPlatformLogRecord>()
        val backgroundStarted = CompletableDeferred<Unit>()
        val releaseBackground = CompletableDeferred<Unit>()
        val markerTag = "SlockPlatformLogTest.typedRecord"
        val markerMessage = "typed-record-marker"
        installSlockPlatformLogRecordSink(entries::add)
        try {
            val background = launch(Dispatchers.Default) {
                slockLogInfo("BackgroundLog", "before typed record")
                backgroundStarted.complete(Unit)
                releaseBackground.await()
                slockLogInfo("BackgroundLog", "after typed record")
            }
            withTimeout(1_000) { backgroundStarted.await() }
            slockLogWarn(markerTag, markerMessage, hint = SlockPlatformLogHint.AuthCritical)
            releaseBackground.complete(Unit)
            withTimeout(1_000) { background.join() }
            flushSlockPlatformLogsForTest()
        } finally {
            releaseBackground.complete(Unit)
            installSlockPlatformLogRecordSink(null)
        }

        val entry =
            entries.single { record ->
                record.tag == markerTag && record.message == markerMessage
            }
        assertEquals("warn", entry.level)
        assertEquals(markerTag, entry.tag)
        assertEquals(markerMessage, entry.message)
        assertEquals(SlockPlatformLogHint.AuthCritical, entry.hint)
        assertTrue(entry.timestampMillis > 0L)
        assertTrue(entries.any { it.tag == "BackgroundLog" })
    }

    @Test
    fun sharedLoggerDoesNotBlockProducerOnSlowSink() = runBlocking {
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        installSlockPlatformLogSink { _, _, _ ->
            sinkEntered.complete(Unit)
            runBlocking { releaseSink.await() }
        }
        try {
            val producer = launch(Dispatchers.Default) {
                slockLogInfo("SlockTest", "slow sink")
            }
            withTimeout(1_000) { producer.join() }
            withTimeout(1_000) { sinkEntered.await() }
            assertFalse(releaseSink.isCompleted)
            releaseSink.complete(Unit)
            flushSlockPlatformLogsForTest()
        } finally {
            releaseSink.complete(Unit)
            installSlockPlatformLogSink(null)
        }
    }

    @Test
    fun acceptedEntriesKeepGlobalOrderAcrossNormalAndCriticalLanes() = runBlocking {
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        val messages = mutableListOf<String>()
        installSlockPlatformLogRecordSink { record ->
            messages += record.message
            if (record.message == "held") {
                sinkEntered.complete(Unit)
                runBlocking { releaseSink.await() }
            }
        }
        try {
            slockLogInfo("SlockTest", "held")
            withTimeout(1_000) { sinkEntered.await() }
            slockLogInfo("SlockTest", "normal-1")
            slockLogWarn("SlockTest", "critical-2", hint = SlockPlatformLogHint.AuthCritical)
            slockLogInfo("SlockTest", "normal-3")
            releaseSink.complete(Unit)
            flushSlockPlatformLogsForTest()
        } finally {
            releaseSink.complete(Unit)
            flushSlockPlatformLogsForTest()
            installSlockPlatformLogRecordSink(null)
        }

        assertEquals(listOf("held", "normal-1", "critical-2", "normal-3"), messages)
    }

    @Test
    fun normalSaturationDoesNotConsumeAuthCriticalReserve() = runBlocking {
        val sinkEntered = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        val captured = mutableListOf<Pair<String, SlockPlatformLogHint>>()
        installSlockPlatformLogRecordSink { record ->
            captured += record.message to record.hint
            if (record.message == "held") {
                sinkEntered.complete(Unit)
                runBlocking { releaseSink.await() }
            }
        }
        try {
            slockLogInfo("SlockTest", "held")
            withTimeout(1_000) { sinkEntered.await() }
            repeat(600) { index -> slockLogInfo("SlockTest", "normal-$index") }
            slockLogWarn("SlockTest", "auth-critical", hint = SlockPlatformLogHint.AuthCritical)
            releaseSink.complete(Unit)
            flushSlockPlatformLogsForTest()
        } finally {
            releaseSink.complete(Unit)
            flushSlockPlatformLogsForTest()
            installSlockPlatformLogRecordSink(null)
        }

        assertTrue(captured.contains("auth-critical" to SlockPlatformLogHint.AuthCritical))
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
        val captured = mutableListOf<Triple<String, String, SlockPlatformLogHint>>()
        installSlockPlatformLogRecordSink { record ->
            captured += Triple(record.tag, record.message, record.hint)
            if (record.message == "held") {
                sinkEntered.complete(Unit)
                runBlocking { releaseSink.await() }
            }
        }
        try {
            slockLogInfo("SlockTest", "held")
            withTimeout(1_000) { sinkEntered.await() }
            repeat(17) { index ->
                slockLogWarn(
                    "SlockTest",
                    "critical-$index",
                    hint = SlockPlatformLogHint.AuthCritical
                )
            }
            releaseSink.complete(Unit)
            flushSlockPlatformLogsForTest()
        } finally {
            releaseSink.complete(Unit)
            flushSlockPlatformLogsForTest()
            installSlockPlatformLogRecordSink(null)
        }

        assertEquals(
            (0 until 16).map { "critical-$it" },
            captured.map { it.second }.filter { it.startsWith("critical-") }
        )
        assertTrue(
            captured.any { (tag, message, hint) ->
                tag == "SlockPlatformLog" &&
                    hint == SlockPlatformLogHint.AuthCritical &&
                    message ==
                    "slock_platform_log_queue_dropped lane=auth_critical count=1 " +
                    "overflow=1 admissionBusy=0 capacity=16"
            }
        )
    }
}
