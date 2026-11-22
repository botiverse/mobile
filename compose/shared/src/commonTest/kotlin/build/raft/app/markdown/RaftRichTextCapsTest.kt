package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

class RaftRichTextCapsTest {
    @Test
    fun capsLineMatchesLockedV02Contract() {
        val line =
            raftRichTextCapsLine(
                RaftRichTextPlatformCaps(
                    inlineCodeChrome = true,
                    raftRefChrome = false,
                    reactionAtlas = true
                )
            )
        assertEquals(
            "SLOCK_RICHTEXT_CAPS {\"inlineCodeChrome\":true,\"slockRefChrome\":false," +
                "\"reactionAtlas\":true,\"declaredDivergences\":[]}",
            line
        )
    }
}
