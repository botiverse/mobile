package build.raft.designsystem.text

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftCjkLineHeightTest {
    @Test
    fun cjkScriptsAreDetected() {
        listOf("排版", "かな", "カナ", "한글", "，", "Ａ", "𠀋").forEach { assertTrue(it.hasCjk(), it) }
    }

    @Test
    fun latinAndEmojiAreNot() {
        listOf("", "Aa 0123", "café — “quotes”", "😀").forEach { assertFalse(it.hasCjk(), it) }
    }
}
