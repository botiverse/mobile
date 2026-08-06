package build.raft.app

import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Task #130 Phase D: each create-tasks title row owns an independent
 * [TextFieldValue] session. Editing one row — or appending a new one — must not
 * disturb another row's selection or in-flight composition.
 */
class TasksCreateTitleRowsTest {

    @Test
    fun editingOneRowReusesTheOtherRowInstances() {
        val untouched = TextFieldValue("first", selection = TextRange(2))
        val titles = listOf(untouched, TextFieldValue("second"))

        val updated = tasksCreateTitlesUpdated(titles, 1, TextFieldValue("second row"))

        // Same instance, so selection and any live composition survive untouched.
        assertSame(untouched, updated[0])
        assertEquals("second row", updated[1].text)
    }

    @Test
    fun editingARowPreservesSiblingSelection() {
        val titles = listOf(TextFieldValue("alpha", selection = TextRange(1, 3)), TextFieldValue(""))

        val updated = tasksCreateTitlesUpdated(titles, 1, TextFieldValue("b"))

        assertEquals(1, updated[0].selection.start)
        assertEquals(3, updated[0].selection.end)
    }

    @Test
    fun appendingARowLeavesExistingRowsIdentical() {
        val first = TextFieldValue("keep", selection = TextRange(4))
        val titles = listOf(first)

        val appended = tasksCreateTitlesAppended(titles)

        assertSame(first, appended[0])
        assertEquals("", appended[1].text)
    }

    @Test
    fun outOfRangeIndexIsIgnored() {
        val titles = listOf(TextFieldValue("only"))
        assertEquals(titles, tasksCreateTitlesUpdated(titles, 5, TextFieldValue("x")))
        assertEquals(titles, tasksCreateTitlesUpdated(titles, -1, TextFieldValue("x")))
    }

    @Test
    fun submitProjectionKeepsRowOrder() {
        val titles = listOf(TextFieldValue("a"), TextFieldValue("b"), TextFieldValue("c"))
        assertEquals(listOf("a", "b", "c"), tasksCreateTitlesSubmission(titles))
    }

    @Test
    fun submitProjectionDropsSelectionAndComposition() {
        val titles =
            listOf(
                TextFieldValue("draft", selection = TextRange(1, 4), composition = TextRange(0, 5))
            )
        assertEquals(listOf("draft"), tasksCreateTitlesSubmission(titles))
    }

    @Test
    fun appendedRowStartsEmptyWithCollapsedCaret() {
        val appended = tasksCreateTitlesAppended(listOf(TextFieldValue("a")))
        assertEquals(2, appended.size)
        assertEquals("", appended[1].text)
        assertEquals(0, appended[1].selection.start)
        assertEquals(0, appended[1].selection.end)
    }
}
