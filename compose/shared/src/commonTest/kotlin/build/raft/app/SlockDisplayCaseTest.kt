package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockDisplayCaseTest {
    @Test
    fun latinTextIsUppercased() {
        assertEquals("RESTART AGENT", "Restart Agent".slockDisplayUppercase())
        assertEquals("RESET SESSION & RESTART", "Reset Session & Restart".slockDisplayUppercase())
    }

    @Test
    fun cjkTextKeepsEmbeddedLatinCase() {
        assertEquals("重启 Agent", "重启 Agent".slockDisplayUppercase())
        assertEquals("重启 CC-Cata", "重启 CC-Cata".slockDisplayUppercase())
        assertEquals("已创建的 Agent", "已创建的 Agent".slockDisplayUppercase())
    }
}
