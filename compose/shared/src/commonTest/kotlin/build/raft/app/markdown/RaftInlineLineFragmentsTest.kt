package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The box painter's per-line split (artin 10-29, iOS): a line fragment of a chip box that holds
 * nothing drawn (only its lead padding spacer) is not painted, so a chip that wrapped never leaves
 * a sliver of its box at the end of the previous line.
 */
class RaftInlineLineFragmentsTest {
    // "今天 " + [lead spacer] + "#15244" + [trail spacer], the lead left on line 0 (as the old
    // placeholder lead was on iOS, or after a forced break): line 0 = "今天 " + lead, line 1 = "#15244" + trail.
    private val text = "今天 \u2060#15244\u2060"
    private val lines = listOf(0 until 4, 4 until text.length)
    private val boxStart = 3
    private val boxEnd = text.length

    @Test
    fun aLeadOnlyFragmentIsDroppedAndTheLabelFragmentBecomesFirst() {
        val fragments = raftInlineLineFragments(lines, boxStart, boxEnd, labelOnly = text)
        assertEquals(listOf(RaftInlineLineFragment(line = 1, start = 4, end = boxEnd, isFirst = true, isLast = true)), fragments)
    }
}
