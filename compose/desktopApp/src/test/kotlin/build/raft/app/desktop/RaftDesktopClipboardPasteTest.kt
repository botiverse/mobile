package build.raft.app.desktop

import build.raft.app.data.RaftDesktopClipboardPasteKind
import build.raft.app.data.RaftDesktopComposerTemporaryFiles
import build.raft.app.data.raftDesktopClipboardPasteKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftDesktopClipboardPasteTest {
    private val root = Files.createTempDirectory("raft-clipboard-test").toFile()
    private val dir = File(root, "clipboard").apply { mkdirs() }

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    @Test fun `a Finder file list attaches files even with text and an icon image`() {
        assertEquals(RaftDesktopClipboardPasteKind.Files, raftDesktopClipboardPasteKind(hasFileList = true, hasImage = true, hasText = true))
    }

    @Test fun `text with an image as from Excel or Word pastes as text`() {
        assertEquals(RaftDesktopClipboardPasteKind.Text, raftDesktopClipboardPasteKind(hasFileList = false, hasImage = true, hasText = true))
    }

    @Test fun `retiring moves pasted images out and rejects files outside the clipboard cache`() {
        val pasted = File(dir, "pasted-a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val userFile = File(root, "report.pdf").apply { writeBytes(byteArrayOf(4)) }
        var tombstones = emptyList<File>()
        val result =
            RaftDesktopComposerTemporaryFiles.retire(dir, "s", setOf(pasted.absolutePath, userFile.absolutePath)) {
                tombstones = it
            }
        assertFalse(pasted.exists())
        assertTrue(userFile.exists())
        assertEquals(setOf(pasted.absolutePath), result.quarantinedFileRefs)
        assertEquals(setOf(userFile.absolutePath), result.rejectedFileRefs)
        assertEquals(1, tombstones.size)
        assertTrue(tombstones.single().exists())
        assertEquals(dir.canonicalFile, tombstones.single().parentFile.canonicalFile)
    }
}
