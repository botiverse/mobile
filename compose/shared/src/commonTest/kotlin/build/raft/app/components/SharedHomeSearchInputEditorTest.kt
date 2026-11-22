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
    fun historySelectionReplacesTextAtTheEndAndProjectsOnce() {
        val owner = SharedHomeSearchEditorOwner("")
        val projected = mutableListOf<String>()

        owner.replaceText("i18n search") { projected += it }

        assertEquals("i18n search", owner.value.text)
        assertEquals(TextRange("i18n search".length), owner.value.selection)
        assertEquals(listOf("i18n search"), projected)
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
}
