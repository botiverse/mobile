package build.raft.app.desktop

import build.raft.app.raftDesktopBrowseUri
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What desktop's "Open in browser" opens in place of a video or web view it can't show inline. */
class RaftDesktopOpenInBrowserTest {
    @Test
    fun webAndFileUrlsOpenAsTheyAre() {
        assertEquals("https://app.raft.build/a/b", raftDesktopBrowseUri(" https://app.raft.build/a/b ").toString())
        assertEquals("http://localhost:3000/x", raftDesktopBrowseUri("http://localhost:3000/x").toString())
        assertEquals("file:/tmp/clip.mp4", raftDesktopBrowseUri("file:/tmp/clip.mp4").toString())
    }

    @Test
    fun aPlainPathOpensAsAFile() {
        val path = File("clip.m4a").absolutePath
        assertEquals(File(path).toURI(), raftDesktopBrowseUri(path))
    }

    @Test
    fun nothingToOpenHidesTheAction() {
        assertNull(raftDesktopBrowseUri(null))
        assertNull(raftDesktopBrowseUri("  "))
        assertNull(raftDesktopBrowseUri("raft-blob://abc"))
    }
}
