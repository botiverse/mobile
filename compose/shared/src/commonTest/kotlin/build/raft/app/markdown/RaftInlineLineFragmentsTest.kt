package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun withoutTheLabelFilterEveryLineIsAFragment() {
        // Underlines keep the plain split.
        val fragments = raftInlineLineFragments(lines, boxStart, boxEnd)
        assertEquals(
            listOf(
                RaftInlineLineFragment(line = 0, start = 3, end = 4, isFirst = true, isLast = false),
                RaftInlineLineFragment(line = 1, start = 4, end = boxEnd, isFirst = false, isLast = true),
            ),
            fragments
        )
    }

    @Test
    fun aTrailOnlyFragmentOnTheNextLineIsDroppedAndTheLabelKeepsItsEnd() {
        val wrapped = "x \u2060#7\u2060 y"
        val fragments = raftInlineLineFragments(listOf(0 until 5, 5 until wrapped.length), 2, 6, labelOnly = wrapped)
        assertEquals(listOf(RaftInlineLineFragment(line = 0, start = 2, end = 5, isFirst = true, isLast = true)), fragments)
    }

    @Test
    fun aDrawnPlaceholderKeepsItsBoxedFragment() {
        // A busy spinner (or a chip icon) that wrapped alone is drawn, so its fragment stays boxed.
        val busy = "#wg\u2060 \uFFFC"
        val fragments = raftInlineLineFragments(listOf(0 until 5, 5 until busy.length), 0, busy.length, labelOnly = busy)
        assertEquals(listOf(0, 1), fragments.map { it.line })
    }

    @Test
    fun aLabelSplitOverTwoLinesKeepsBothFragments() {
        val label = "see `long code span`"
        val fragments = raftInlineLineFragments(listOf(0 until 10, 10 until label.length), 4, label.length, labelOnly = label)
        assertEquals(listOf(true to false, false to true), fragments.map { it.isFirst to it.isLast })
        assertEquals(listOf(0, 1), fragments.map { it.line })
    }

    @Test
    fun drawnCharactersExcludeJoinersAndSpaces() {
        assertFalse(raftInlineHasLabelChar("\u2060\u200B \u2060", 0, 4))
        assertTrue(raftInlineHasLabelChar("\u2060#\u2060", 0, 3))
        assertTrue(raftInlineHasLabelChar("\u2060\uFFFC", 0, 2))
        assertFalse(raftInlineHasLabelChar("ab", 1, 1))
    }

    @Test
    fun emptyRangesAndLinesOutsideTheRangeYieldNothing() {
        assertTrue(raftInlineLineFragments(lines, 5, 5, labelOnly = text).isEmpty())
        assertTrue(raftInlineLineFragments(listOf(IntRange.EMPTY, 4 until 6), 0, 3, labelOnly = text).isEmpty())
    }
}
