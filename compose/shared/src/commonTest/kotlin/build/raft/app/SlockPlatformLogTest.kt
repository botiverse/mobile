package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockPlatformLogTest {
    @Test
    fun sharedLogSinkReceivesSanitizedMessage() {
        val entries = mutableListOf<Triple<String, String, String>>()
        installSlockPlatformLogSink { level, tag, message ->
            entries += Triple(level, tag, message)
        }
        try {
            slockLogInfo(
                tag = "SlockTest",
                message = "token sk_agent_secret123 and Bearer abc.def access_token=abc client_key=qk_secret"
            )
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
    fun sharedLoggerAddsPrefixWithoutBridge() {
        val entries = mutableListOf<Triple<String, String, String>>()
        installSlockPlatformLogSink { level, tag, message ->
            entries += Triple(level, tag, message)
        }
        try {
            slockLogger("SlockStore", "shared_home", SlockLogLevel.Warn)("loaded")
        } finally {
            installSlockPlatformLogSink(null)
        }

        assertEquals(Triple("warn", "SlockStore", "shared_home loaded"), entries.single())
    }
}
