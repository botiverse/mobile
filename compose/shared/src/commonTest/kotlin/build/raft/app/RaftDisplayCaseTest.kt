package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftDisplayCaseTest {
    @Test
    fun latinTextIsUppercased() {
        assertEquals("RESTART AGENT", "Restart Agent".raftDisplayUppercase())
        assertEquals("RESET SESSION & RESTART", "Reset Session & Restart".raftDisplayUppercase())
    }

    @Test
    fun cjkTextKeepsEmbeddedLatinCase() {
        assertEquals("重启 Agent", "重启 Agent".raftDisplayUppercase())
        assertEquals("重启 CC-Cata", "重启 CC-Cata".raftDisplayUppercase())
        assertEquals("已创建的 Agent", "已创建的 Agent".raftDisplayUppercase())
    }
}
