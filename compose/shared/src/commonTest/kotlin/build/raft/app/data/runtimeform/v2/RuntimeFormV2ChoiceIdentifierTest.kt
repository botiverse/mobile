package build.raft.app.data.runtimeform.v2

import build.raft.app.runtimeChoiceIdentifier
import build.raft.app.runtimeFormV2ShowsChoiceIdentifiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Web RuntimeChoiceCombobox `renderOption`: a model row shows its stored id (Built-in Pi:
 * `provider/modelId`) under its name, unless the id is the name or there is no id; only the
 * searchable lists (custom value, or a source-backed select that is not derived) draw it.
 */
class RuntimeFormV2ChoiceIdentifierTest {
    @Test
    fun theIdLineIsTheStoredValueWhenItDiffersFromTheName() {
        assertEquals("anthropic/claude-sonnet-4-5", runtimeChoiceIdentifier("anthropic/claude-sonnet-4-5", "Claude Sonnet 4.5"))
        assertEquals("gpt-5.6-luna", runtimeChoiceIdentifier("gpt-5.6-luna", "GPT-5.6 Luna"))
    }

    @Test
    fun noIdLineWhenItWouldRepeatTheNameOrForTheDefault() {
        // A value the list does not have (or a custom one) is shown as itself: label == value.
        assertNull(runtimeChoiceIdentifier("my-finetune", "my-finetune"))
        // The runtime default ("") has no id.
        assertNull(runtimeChoiceIdentifier("", "Default"))
    }

    @Test
    fun onlySearchableListsDrawTheIdLine() {
        val model = RuntimeFormV2Field("model", RuntimeFormV2FieldKind.DependentSelect, required = true, advanced = false, label = "Model", optionSourceId = "pi-models")
        val effort = RuntimeFormV2Field("effort", RuntimeFormV2FieldKind.DerivedSelect, required = false, advanced = false, label = "Effort", optionSourceId = "pi-models")
        val fixed = RuntimeFormV2Field("mode", RuntimeFormV2FieldKind.Select, required = false, advanced = false, label = "Mode")
        val select = RuntimeFormV2Choices.Select(options = emptyList(), allowEmpty = false)
        assertTrue(runtimeFormV2ShowsChoiceIdentifiers(model, select))
        assertFalse(runtimeFormV2ShowsChoiceIdentifiers(effort, select))
        assertFalse(runtimeFormV2ShowsChoiceIdentifiers(fixed, select))
        // A custom value field is the searchable list too, with or without a source.
        assertTrue(runtimeFormV2ShowsChoiceIdentifiers(fixed, select.copy(allowCustom = true)))
    }
}
