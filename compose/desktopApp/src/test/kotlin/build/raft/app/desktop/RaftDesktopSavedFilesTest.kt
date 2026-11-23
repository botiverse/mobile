package build.raft.app.desktop

import build.raft.app.raftDesktopRevealFileCommand
import build.raft.app.raftDesktopSavedFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A saved attachment's Show in Finder / Open: the system's own commands (RaftDesktopExternalOpen). */
class RaftDesktopSavedFilesTest {
    @Test
    fun revealSelectsTheFileInTheFileManager() {
        assertEquals(listOf("open", "-R", "/Users/a/Downloads/r.pdf"), raftDesktopRevealFileCommand("Mac OS X", "/Users/a/Downloads/r.pdf"))
        assertEquals(listOf("explorer.exe", "/select,C:\\Users\\a\\r.pdf"), raftDesktopRevealFileCommand("Windows 11", "C:\\Users\\a\\r.pdf"))
        // Linux has no common "select this file" command: the host opens the folder instead.
        assertNull(raftDesktopRevealFileCommand("Linux", "/home/a/r.pdf"))
    }

    @Test
    fun savedUriIsAFileUriOrAPath() {
        val file = File("/tmp/raft saved/r.pdf")
        assertEquals(file.absoluteFile, raftDesktopSavedFile(file.toURI().toString())?.absoluteFile)
        assertEquals(File("/a/r.pdf"), raftDesktopSavedFile("/a/r.pdf"))
        // Not a local file (an Android content URI, a web link) or nothing at all.
        assertNull(raftDesktopSavedFile("content://downloads/1"))
        assertNull(raftDesktopSavedFile("https://example.com/r.pdf"))
        assertNull(raftDesktopSavedFile(" "))
    }
}
