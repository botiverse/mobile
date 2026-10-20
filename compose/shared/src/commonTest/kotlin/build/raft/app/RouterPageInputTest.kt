package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task #130 Phase D: the developer-route input is a caller-local
 * [androidx.compose.ui.text.input.TextFieldValue]. Programmatic
 * replacement (suggestion pick / route row tap) must leave the caret at the end
 * of the injected text so the next keystroke appends instead of landing at a
 * stale offset.
 */
class RouterPageInputTest {

    @Test
    fun replacedInputPutsCaretAtEnd() {
        val replaced = developerRouteInputReplaced("SharedTasksPage")
        assertEquals("SharedTasksPage", replaced.text)
        assertEquals(replaced.text.length, replaced.selection.start)
        assertEquals(replaced.text.length, replaced.selection.end)
    }

    @Test
    fun replacedInputCollapsesSelection() {
        val replaced = developerRouteInputReplaced("pageName=RouterPage&debug=1")
        assertEquals(replaced.selection.start, replaced.selection.end)
    }

    @Test
    fun replacedEmptyInputKeepsCaretAtZero() {
        val replaced = developerRouteInputReplaced("")
        assertEquals("", replaced.text)
        assertEquals(0, replaced.selection.start)
        assertEquals(0, replaced.selection.end)
    }

    @Test
    fun replacedInputPreservesCjkTextAndCaret() {
        val replaced = developerRouteInputReplaced("页面=任务列表")
        assertEquals("页面=任务列表", replaced.text)
        assertEquals(replaced.text.length, replaced.selection.start)
    }
}
