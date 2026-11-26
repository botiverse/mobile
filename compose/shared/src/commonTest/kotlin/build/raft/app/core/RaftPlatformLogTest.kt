package build.raft.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
    fun aLineLoggedWhileAnotherLineIsBeingAdmittedIsKept() = runTest {
        val messages = mutableListOf<String>()
        lateinit var queue: RaftPlatformLogQueue
        var secondLogged = false
        queue =
            RaftPlatformLogQueue(
                scope = backgroundScope,
                recordSink = { record -> messages += record.message },
                // Mid-admission of "first", where another thread's line used to find admission busy
                // and be dropped (Hands cf580bb9: about 2 lines a minute, request finish lines among
                // them). A line logged here stands for any thread's line logged at that moment.
                duringAdmission = {
                    if (!secondLogged) {
                        secondLogged = true
                        assertTrue(queue.enqueue(RaftLogLevel.Info, "RaftTest", "second", RaftPlatformLogHint.None))
                    }
                },
                volumePolicy = null,
            )

        assertTrue(queue.enqueue(RaftLogLevel.Info, "RaftTest", "first", RaftPlatformLogHint.None))
        queue.flush()

        // Both lines, and no slock_platform_log_queue_dropped line.
        assertEquals(listOf("first", "second"), messages.sorted())
    }

    @Test
    fun aFullLaneDropsOnlyTheLinesThatDoNotFitSaysSoAndTakesLinesAgainOnceDrained() = runTest {
        val messages = mutableListOf<String>()
        val queue =
            RaftPlatformLogQueue(backgroundScope, recordSink = { record -> messages += record.message }, volumePolicy = null)

        // The consumer has not run yet, so nothing leaves the lane while it fills up.
        var accepted = 0
        while (accepted < 10_000 && queue.enqueue(RaftLogLevel.Info, "RaftTest", "line-$accepted", RaftPlatformLogHint.None)) {
            accepted += 1
        }
        assertTrue(accepted in 1 until 10_000, "the lane is bounded: accepted=$accepted")
        assertFalse(queue.enqueue(RaftLogLevel.Info, "RaftTest", "refused", RaftPlatformLogHint.None))
        queue.flush()

        assertEquals(accepted, messages.count { it.startsWith("line-") })
        val dropped = messages.filter { it.startsWith("slock_platform_log_queue_dropped ") }
        assertEquals(1, dropped.size, "one report for the lines that did not fit: $dropped")
        // The line that stopped the loop and the one refused after it.
        assertTrue(dropped.single().startsWith("slock_platform_log_queue_dropped lane=normal count=2 "), dropped.single())

        // Drained: the lane takes lines again.
        assertTrue(queue.enqueue(RaftLogLevel.Info, "RaftTest", "after", RaftPlatformLogHint.None))
        queue.flush()
        assertEquals("after", messages.last())
    }

    @Test
    fun redactsBareThreeSegmentJwtsWhileKeepingNonJwtBase64Tokens() = runTest {
        val sampleJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyLTEifQ.sigPayload1234-"
        val redacted =
            raftRedactLogMessage(
                "bare=$sampleJwt quoted=\"$sampleJwt\" cursor=eyJ1IjoxfQ handle=v1.kid.eyJraW5kIjoiaGFuZGxlIn0.mac12345"
            )
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), redacted)
        assertFalse(redacted.contains("eyJzdWIiOiJ1c2VyLTEifQ"), redacted)
        assertFalse(redacted.contains("sigPayload1234"), redacted)
        assertEquals(
            "bare=<jwt-redacted> quoted=\"<jwt-redacted>\" cursor=eyJ1IjoxfQ handle=v1.kid.eyJraW5kIjoiaGFuZGxlIn0.mac12345",
            redacted,
        )

        // Bearer and access_token forms still use their dedicated prefixes without leaking JWT parts.
        assertEquals(
            "Bearer <redacted> access_token=<redacted>",
            raftRedactLogMessage("Bearer $sampleJwt access_token=$sampleJwt"),
        )

        // Queued log lines pass through the same sanitizer.
        val messages = mutableListOf<String>()
        val queue =
            RaftPlatformLogQueue(
                scope = backgroundScope,
                recordSink = { record -> messages += record.message },
                volumePolicy = null,
            )
        assertTrue(queue.enqueue(RaftLogLevel.Warn, "SlockAuth", "rejected token $sampleJwt.", RaftPlatformLogHint.None))
        queue.flush()
        assertEquals(listOf("rejected token <jwt-redacted>."), messages)
    }
}
