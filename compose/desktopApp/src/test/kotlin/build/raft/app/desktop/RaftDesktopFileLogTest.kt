package build.raft.app.desktop

import build.raft.app.RaftDesktopFileLog
import build.raft.app.RaftRollingLogWriter
import build.raft.app.raftDesktopThrowableLines
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The desktop log file (~/Library/Logs/Raft): size cap, rotation, redaction, crash formatting. */
class RaftDesktopFileLogTest {
    private fun tempDir(): File = Files.createTempDirectory("raft-log").toFile()

    @Test fun `rotation keeps at most maxFiles files of about maxFileBytes each`() {
        val dir = tempDir()
        try {
            val writer = RaftRollingLogWriter(dir, maxFileBytes = 1_000, maxFiles = 3)
            repeat(200) { index -> writer.append("line %04d ".format(index) + "x".repeat(40)) }
            writer.close()

            val files = dir.listFiles().orEmpty().map { it.name }.sorted()
            assertEquals(listOf("raft.1.log", "raft.2.log", "raft.log"), files)
            dir.listFiles().orEmpty().forEach { assertTrue(it.length() <= 1_000, "${it.name} is ${it.length()} bytes") }
            // Newest lines in raft.log, older ones in raft.1 / raft.2; the oldest are gone.
            assertTrue(File(dir, "raft.log").readText().contains("line 0199"))
            assertFalse(dir.listFiles().orEmpty().any { it.readText().contains("line 0000 ") })
            assertEquals(listOf("raft.log", "raft.1.log", "raft.2.log"), writer.files().map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `appending continues an existing file and counts its size`() {
        val dir = tempDir()
        try {
            File(dir, "raft.log").writeText("x".repeat(900) + "\n")
            val writer = RaftRollingLogWriter(dir, maxFileBytes = 1_000, maxFiles = 2)
            writer.append("y".repeat(200))
            writer.close()
            // 901 + 201 bytes would pass the cap: the old content rotated to raft.1.log first.
            assertEquals("y".repeat(200) + "\n", File(dir, "raft.log").readText())
            assertTrue(File(dir, "raft.1.log").readText().startsWith("xxx"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `lines are redacted, single-line and bounded`() {
        val line =
            RaftDesktopFileLog.formatLine(
                "warn",
                "SlockAuth",
                "refresh Authorization: Bearer abc.def-123 key=sk_agent_SECRET123 url=/x?access_token=tok123&y=1\r",
            )
        assertTrue(line.contains(" W ["), line)
        assertTrue(line.contains("SlockAuth: refresh"), line)
        assertFalse(line.contains("abc.def-123"), line)
        assertFalse(line.contains("SECRET123"), line)
        assertFalse(line.contains("tok123"), line)
        assertFalse(line.contains("\r"))
        val long = RaftDesktopFileLog.formatLine("info", "T", "z".repeat(10_000))
        assertTrue(long.length < 4_300, "line not cut: ${long.length}")
    }

    @Test fun `a throwable becomes its type, a bounded message, frames and causes`() {
        val cause = IllegalStateException("inner")
        val error = RuntimeException("m".repeat(1_000), cause)
        val lines = raftDesktopThrowableLines(error, maxFrames = 3, maxMessageChars = 50)
        assertTrue(lines.first().startsWith("java.lang.RuntimeException: " + "m".repeat(50)))
        assertTrue(lines.first().length < 100)
        assertEquals(3, lines.drop(1).takeWhile { it.startsWith("\tat ") }.size)
        assertTrue(lines.any { it == "Caused by: java.lang.IllegalStateException: inner" }, lines.joinToString("\n"))
    }

    @Test fun `diagnostics entries parse log lines newest first`() {
        val payload =
            RaftDesktopSettingsCapability.diagnosticsPayload(
                listOf(
                    "2026-11-03T12:00:00.123+08:00 I [main] RaftDesktopCrash: startup version=1",
                    "2026-11-03T12:00:01.456+08:00 F [AWT-EventQueue-0] RaftDesktopCrash: crash source=uncaught",
                ),
            )
        assertEquals("ok", payload["status"].toString().trim('"'))
        val first = payload["entries"].toString()
        assertTrue(first.indexOf("crash source=uncaught") < first.indexOf("startup version=1"), first)
        assertTrue(first.contains("\"timestampLabel\":\"12:00:01.456\""), first)
        assertTrue(first.contains("\"tag\":\"F RaftDesktopCrash\""), first)
    }
}
