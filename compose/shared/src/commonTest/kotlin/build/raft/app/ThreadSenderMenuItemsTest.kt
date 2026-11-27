package build.raft.app

import build.raft.app.thread.ThreadSenderMenuItem
import build.raft.app.thread.threadSenderMenuItems
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadSenderMenuItemsTest {
    @Test
    fun mentionableSenderGetsNameAndAtHandle() {
        assertEquals(
            listOf(ThreadSenderMenuItem.CopyName("Ada Lovelace"), ThreadSenderMenuItem.CopyHandle("@ada")),
            threadSenderMenuItems(" Ada Lovelace ", "ada"),
        )
        // A handle that already carries its @ is not doubled.
        assertEquals(ThreadSenderMenuItem.CopyHandle("@ada"), threadSenderMenuItems("Ada", "@ada").last())
    }
}
