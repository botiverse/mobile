package build.raft.app.desktop

import build.raft.app.raftDesktopBrowseUri
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What desktop's "Open in browser" opens in place of a video or web view it can't show inline:
 * only web links (http / https), as the desktop openUrl policy allows; nothing else reaches
 * Desktop.browse.
 */
class RaftDesktopOpenInBrowserTest {
    @Test
    fun webUrlsOpen() {
        assertEquals("https://app.raft.build/a/b", raftDesktopBrowseUri(" https://app.raft.build/a/b ").toString())
        assertEquals("http://localhost:3000/x", raftDesktopBrowseUri("http://localhost:3000/x").toString())
    }

    @Test
    fun filesPathsAndOtherSchemesGetNoButton() {
        assertNull(raftDesktopBrowseUri("file:/tmp/clip.mp4"))
        assertNull(raftDesktopBrowseUri(File("clip.m4a").absolutePath))
        assertNull(raftDesktopBrowseUri("raft-blob://abc"))
        assertNull(raftDesktopBrowseUri("javascript:alert(1)"))
        assertNull(raftDesktopBrowseUri("https:///no-host"))
    }

    @Test
    fun nothingToOpenHidesTheAction() {
        assertNull(raftDesktopBrowseUri(null))
        assertNull(raftDesktopBrowseUri("  "))
    }
}
