package build.raft.app.desktop

import build.raft.app.raftDesktopIsMac
import build.raft.app.raftDesktopOpenFileCommand
import build.raft.app.raftDesktopRevealFileCommand
import build.raft.app.raftDesktopSavedFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A saved attachment's Show in Finder / Open fallbacks, when `java.awt.Desktop` can't. */
class RaftDesktopSavedFilesTest {
    @Test
    fun revealSelectsTheFileInTheFileManager() {
        assertEquals(listOf("open", "-R", "/Users/a/Downloads/r.pdf"), raftDesktopRevealFileCommand("Mac OS X", "/Users/a/Downloads/r.pdf"))
        assertEquals(listOf("explorer.exe", "/select,C:\\Users\\a\\r.pdf"), raftDesktopRevealFileCommand("Windows 11", "C:\\Users\\a\\r.pdf"))
        // Linux has no common "select this file" command: the host opens the folder instead.
        assertNull(raftDesktopRevealFileCommand("Linux", "/home/a/r.pdf"))
    }

    @Test
    fun openUsesTheDefaultApp() {
        assertEquals(listOf("open", "/a/r.pdf"), raftDesktopOpenFileCommand("Mac OS X", "/a/r.pdf"))
        assertEquals(listOf("explorer.exe", "C:\\r.pdf"), raftDesktopOpenFileCommand("Windows 10", "C:\\r.pdf"))
        assertEquals(listOf("xdg-open", "/a/r.pdf"), raftDesktopOpenFileCommand("Linux", "/a/r.pdf"))
        assertTrue(raftDesktopIsMac("Mac OS X"))
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
