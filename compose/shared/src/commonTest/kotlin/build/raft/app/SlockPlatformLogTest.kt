package build.raft.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
