package build.raft.app.desktop

import build.raft.app.data.RaftDesktopRotatingLogFile
import build.raft.app.data.raftDesktopLogLine
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftDesktopLogFileTest {
    private val root = createTempDirectory("raft-log-test").toFile()
    private val dir = File(root, "logs")

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun names() = dir.list().orEmpty().sorted()

    @Test fun `lines append to one file under the cap`() {
        val log = RaftDesktopRotatingLogFile(dir, "raft-desktop.log", maxBytes = 100, maxFiles = 3)
        log.append("one")
        log.append("two")
        assertEquals(listOf("raft-desktop.log"), names())
        assertEquals("one\ntwo\n", log.current.readText())
    }

    @Test fun `a line past the cap rotates and only maxFiles files are kept`() {
        val log = RaftDesktopRotatingLogFile(dir, "raft-desktop.log", maxBytes = 10, maxFiles = 3)
        log.append("aaaaaaaa") // 9 bytes
        log.append("bbbbbbbb") // would be 18: rotate
        assertEquals(listOf("raft-desktop.log", "raft-desktop.log.1"), names())
        assertEquals("bbbbbbbb\n", log.current.readText())
        assertEquals("aaaaaaaa\n", File(dir, "raft-desktop.log.1").readText())

        log.append("cccccccc")
        log.append("dddddddd")
        assertEquals(listOf("raft-desktop.log", "raft-desktop.log.1", "raft-desktop.log.2"), names())
        assertEquals("dddddddd\n", log.current.readText())
        assertEquals("cccccccc\n", File(dir, "raft-desktop.log.1").readText())
        assertEquals("bbbbbbbb\n", File(dir, "raft-desktop.log.2").readText())
    }

    @Test fun `a single line longer than the cap is still written`() {
        val log = RaftDesktopRotatingLogFile(dir, "raft-desktop.log", maxBytes = 4, maxFiles = 2)
        log.append("longer than four")
        assertEquals("longer than four\n", log.current.readText())
        log.append("x")
        assertEquals("x\n", log.current.readText())
        assertEquals("longer than four\n", File(dir, "raft-desktop.log.1").readText())
    }

    @Test fun `the folder and file are owner only`() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        val log = RaftDesktopRotatingLogFile(dir, "raft-desktop.log", maxBytes = 100, maxFiles = 3)
        log.append("one")
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(log.current.toPath())))
    }

    @Test fun `a line is timestamped single-line and redacted`() {
        val line = raftDesktopLogLine(0L, "I", "Tag", "auth Bearer abc.def x?access_token=secret&y=1\nnext", ZoneOffset.UTC)
        assertEquals("1970-01-01 00:00:00.000 I Tag: auth Bearer <redacted> x?access_token=<redacted>&y=1 next", line)
        assertFalse("secret" in line)
        assertTrue(raftDesktopLogLine(0L, "W", "T", "key sk_agent_123abc", ZoneOffset.UTC).endsWith("key sk_agent_<redacted>"))
    }
}
