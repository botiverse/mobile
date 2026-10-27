package build.raft.app.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedHomeSearchInputEditorTest {
    @Test
    fun rapidNativeEditsAdvanceSynchronouslyWithoutAStoreValueRoundTrip() {
        val owner = SharedHomeSearchEditorOwner("")
        val projections = mutableListOf<String>()

        owner.acceptEditorValue(TextFieldValue("n", selection = TextRange(1))) {
            assertEquals("n", owner.value.text)
            projections += it
        }
        val latest = TextFieldValue("ni", selection = TextRange(2))
        owner.acceptEditorValue(latest) {
            assertSame(latest, owner.value)
            projections += it
        }

        assertEquals(listOf("n", "ni"), projections)
        assertSame(latest, owner.value)
        assertEquals("ni", owner.value.text)
        assertEquals(TextRange(2), owner.value.selection)
    }

    @Test
    fun selectionAndCompositionOnlyEditsKeepTheExactCallbackValue() {
        val owner = SharedHomeSearchEditorOwner("ni")
        var textProjectionCalls = 0
        val composing =
            TextFieldValue(
                text = "ni",
                selection = TextRange(1),
                composition = TextRange(0, 2)
            )

        owner.acceptEditorValue(composing) { textProjectionCalls += 1 }

        assertSame(composing, owner.value)
        assertEquals(TextRange(1), owner.value.selection)
        assertEquals(TextRange(0, 2), owner.value.composition)
        assertEquals(0, textProjectionCalls)
    }

    @Test
    fun structurallyEqualNativeCallbackStillReplacesTheEditorObject() {
        val owner = SharedHomeSearchEditorOwner("ni")
        val exactNativeCallback = TextFieldValue("ni", selection = TextRange(2))

        owner.acceptEditorValue(exactNativeCallback) {}

        assertSame(exactNativeCallback, owner.value)
    }

    @Test
    fun lateSearchResultsCannotReplaceTheCurrentEditorValue() {
        val owner = SharedHomeSearchEditorOwner("")
        val latest =
            TextFieldValue(
                text = "ni",
                selection = TextRange(2),
                composition = TextRange(0, 2)
            )
        var visibleResultQuery = ""

        owner.acceptEditorValue(TextFieldValue("n", selection = TextRange(1))) {}
        owner.acceptEditorValue(latest) {}
        visibleResultQuery = "n"

        assertEquals("n", visibleResultQuery)
        assertSame(latest, owner.value)
        assertEquals("ni", owner.value.text)
        assertEquals(TextRange(0, 2), owner.value.composition)
    }

    @Test
    fun explicitClearIsSynchronousAndIdempotent() {
        val owner = SharedHomeSearchEditorOwner("query")
        var clearCalls = 0

        assertTrue(owner.clear {
            assertEquals(TextFieldValue(""), owner.value)
            clearCalls += 1
        })
        assertEquals(TextFieldValue(""), owner.value)
        assertFalse(owner.clear { clearCalls += 1 })
        assertEquals(TextFieldValue(""), owner.value)
        assertEquals(1, clearCalls)
    }

    @Test
    fun historySelectionReplacesTextAtTheEndAndProjectsOnce() {
        val owner = SharedHomeSearchEditorOwner("")
        val projected = mutableListOf<String>()

        owner.replaceText("i18n search") { projected += it }

        assertEquals("i18n search", owner.value.text)
        assertEquals(TextRange("i18n search".length), owner.value.selection)
        assertEquals(listOf("i18n search"), projected)
    }

    @Test
    fun selectAllSelectsTheWholeQueryWithoutAStoreProjection() {
        val owner = SharedHomeSearchEditorOwner("")
        val projections = mutableListOf<String>()
        owner.acceptEditorValue(
            TextFieldValue("hello", selection = TextRange(2), composition = TextRange(0, 5))
        ) { projections += it }

        owner.selectAll()

        assertEquals("hello", owner.value.text)
        assertEquals(TextRange(0, 5), owner.value.selection)
        assertEquals(null, owner.value.composition)
        assertEquals(listOf("hello"), projections)

        // Typing over the selection replaces the old query.
        owner.acceptEditorValue(TextFieldValue("w", selection = TextRange(1))) { projections += it }
        assertEquals(listOf("hello", "w"), projections)
    }

    @Test
    fun selectAllLeavesAnEmptyFieldUntouched() {
        val owner = SharedHomeSearchEditorOwner("")
        val before = owner.value

        owner.selectAll()

        assertSame(before, owner.value)
        assertEquals(TextRange(0), owner.value.selection)
    }

    @Test
    fun storeResetOnServerSwitchEmptiesTheFieldWithoutAnEcho() {
        val owner = SharedHomeSearchEditorOwner("")
        val projections = mutableListOf<String>()
        owner.acceptEditorValue(TextFieldValue("deploy", selection = TextRange(6))) { projections += it }

        assertTrue(owner.adoptStoreReset(""))

        assertEquals(TextFieldValue(""), owner.value)
        assertEquals(listOf("deploy"), projections)
        assertFalse(owner.adoptStoreReset(""))
    }

    @Test
    fun storeTextThatMayLagTypingNeverOverridesTheField() {
        val owner = SharedHomeSearchEditorOwner("")
        val latest = TextFieldValue("deploy", selection = TextRange(6))
        owner.acceptEditorValue(latest) {}

        assertFalse(owner.adoptStoreReset("depl"))

        assertSame(latest, owner.value)
    }
}
