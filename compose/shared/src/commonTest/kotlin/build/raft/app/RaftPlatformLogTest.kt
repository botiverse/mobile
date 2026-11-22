package build.raft.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
