package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [withRaftWebTextBreaks] and [withRaftInlineChipGlue]: where the marks go, and that copy drops them. */
class RaftWebLineBreaksTest {
    private val wj = "\u2060"

    @Test
    fun emojiAndSurrogatePairsAreNeverSplitInText() {
        val text = "👩\u200D💻-dev ❤\uFE0F/ok 𠀀-a"
        val marked = text.withRaftWebTextBreaks()
        assertEquals(text, marked.raftCopyText())
        assertTrue(marked.contains("❤\uFE0F/${wj}ok"))
        assertTrue(marked.contains("👩\u200D💻-dev"), "no letter before the hyphen: no mark")
    }

    @Test
    fun copyDropsEveryMark() {
        val text = "read-state a/b traversal/layout"
        assertEquals(text, text.withRaftWebTextBreaks().raftCopyText())
    }

    @Test
    fun aCjkChipLabelIsGluedBetweenItsCharacters() {
        assertEquals(
            "#${wj}对${wj}话${wj}流${wj}专${wj}修${wj}:${wj}661a12bf",
            "#对话流专修:661a12bf".withRaftInlineChipGlue()
        )
        assertEquals("#对话流专修:661a12bf", "#对话流专修:661a12bf".withRaftInlineChipGlue().raftCopyText())
        // A CJK character outside the BMP is one code point: no joiner inside its surrogate pair.
        val glued = "#𠀀𠀁".withRaftInlineChipGlue()
        assertEquals("#${wj}𠀀${wj}𠀁", glued)
    }

    @Test
    fun qualifiedTaskAndThreadChipLabelsAreGluedAfterHyphensAndColons() {
        assertEquals(
            "#wg-${wj}mobile-${wj}sync:${wj}#276",
            "#wg-mobile-sync:#276".withRaftInlineChipGlue()
        )
        assertEquals(
            "dm:${wj}@artin:${wj}#170",
            "dm:@artin:#170".withRaftInlineChipGlue()
        )
        assertEquals(
            "lounge:${wj}@agent:${wj}#7",
            "lounge:@agent:#7".withRaftInlineChipGlue()
        )
        // Bare task numbers have no break points and stay untouched.
        assertEquals("#92", "#92".withRaftInlineChipGlue())
        listOf("#wg-mobile-sync:#276", "dm:@artin:#170", "lounge:@agent:#7").forEach { label ->
            assertEquals(label, label.withRaftInlineChipGlue().raftCopyText())
        }
    }
}
