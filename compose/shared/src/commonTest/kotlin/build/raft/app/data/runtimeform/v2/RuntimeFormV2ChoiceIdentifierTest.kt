package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import build.raft.designsystem.components.RaftComboboxSheetThreshold
import build.raft.app.runtimeChoiceIdentifier
import build.raft.app.runtimeFormV2ChoiceSearchKeywords
import build.raft.app.runtimeFormV2ListedOption
import build.raft.app.runtimeFormV2ShowsChoiceIdentifiers
import build.raft.designsystem.components.raftComboboxFilter
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
    fun internalValuesHaveNoIdLine() {
        // A saved provider connection's value is internal (`connection:<id>`), never shown.
        assertNull(runtimeChoiceIdentifier("connection:conn_8f2a", "DeepSeek (work)"))
        assertNull(runtimeChoiceIdentifier("connection:", "Saved"))
        // The default row stays without one, stored as "" or as a placeholder (web slock #14728).
        assertNull(runtimeChoiceIdentifier("", "Default (DeepSeek Flash)"))
        assertNull(runtimeChoiceIdentifier("__runtime_form_v2_default__", "Default"))
        assertNull(runtimeChoiceIdentifier("__hosted_default__", "Default (DeepSeek Flash)"))
        // A provider id that merely contains the word keeps its line.
        assertEquals("deepseek/connection-test", runtimeChoiceIdentifier("deepseek/connection-test", "Connection Test"))
    }

    @Test
    fun aDescribedChoiceIsStillFoundByItsId() {
        val described = RuntimeFormV2Choice("anthropic/claude-opus-4-1", "Claude Opus", description = "Best for long, careful work")
        val plain = RuntimeFormV2Choice("openai/gpt-5", "GPT-5")
        val options = listOf(described, plain).map { runtimeFormV2ListedOption(it, group = null, showsIdentifiers = true) }
        // The description takes the line, so no identifier; the id stays searchable (web runtimeChoiceMatches).
        assertNull(options[0].identifier)
        assertEquals("openai/gpt-5", options[1].identifier)
        assertEquals(listOf("anthropic/claude-opus-4-1"), raftComboboxFilter(options, "claude-opus-4").map { it.value })
        assertEquals(listOf("openai/gpt-5"), raftComboboxFilter(options, "openai").map { it.value })
        // A plain select (no id line) still finds a choice by its id, as web runtimeChoiceMatches does on every list.
        val plainSelect = listOf(described, plain).map { runtimeFormV2ListedOption(it, group = null, showsIdentifiers = false) }
        assertEquals(listOf("anthropic/claude-opus-4-1"), raftComboboxFilter(plainSelect, "claude-opus-4").map { it.value })
        assertEquals(listOf("openai/gpt-5"), raftComboboxFilter(plainSelect, "OPENAI").map { it.value })
    }

    @Test
    fun aLongProviderListIsSearchedByNameIdGroupAndDescription() {
        // #wg-runtime-form:#28: the Built-in provider list (33 providers) opens as the searchable sheet
        // (more than RaftComboboxSheetThreshold options); search matches like web runtimeChoiceMatches.
        val providers = (1..33).map { i ->
            RuntimeFormV2Choice("provider-$i", "Provider $i", attributes = buildJsonObject { put("group", if (i % 2 == 0) "Gateways" else "Direct") })
        } + RuntimeFormV2Choice("openrouter", "OpenRouter", description = "Many models through one key")
        val options = providers.map { runtimeFormV2ListedOption(it, group = null, showsIdentifiers = false) }
        assertTrue(options.size > RaftComboboxSheetThreshold)
        // Every word must match somewhere, case-insensitive.
        assertEquals(listOf("provider-12"), raftComboboxFilter(options, "PROVIDER-12").map { it.value })
        assertEquals(listOf("openrouter"), raftComboboxFilter(options, "one key").map { it.value })
        assertEquals(listOf("provider-2", "provider-20", "provider-22", "provider-24", "provider-26", "provider-28"), raftComboboxFilter(options, "gateways provider-2").map { it.value })
        // The label is matched by the combobox, so a value that only repeats it is not a keyword.
        assertEquals(listOf("Many models through one key"), options.last().keywords)
    }

    @Test
    fun searchKeywordsLeaveOutBlankAndLabelEcho() {
        val choice = RuntimeFormV2Choice("Default", "default", description = " ")
        assertEquals(emptyList<String>(), runtimeFormV2ChoiceSearchKeywords(choice, description = null))
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
