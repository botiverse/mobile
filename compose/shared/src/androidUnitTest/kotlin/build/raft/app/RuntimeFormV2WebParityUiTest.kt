package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.runtimeform.v2.BUILTIN_V3_FORM_JSON
import build.raft.app.data.runtimeform.v2.RuntimeFormV2ErrorCode
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.applyRuntimeFormV2Change
import build.raft.app.data.runtimeform.v2.initialRuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2Source
import build.raft.app.data.runtimeform.v2.validateRuntimeFormV2
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The runtime form renderer on the shapes the web visual cases draw (slock
 * packages/runtime-form/fixtures/visual-cases.v3.json), checked against the web renderer
 * (RuntimeConfigFields / RuntimeFormV2FieldControl / RuntimeChoiceCombobox):
 * - `components.runtime-form.create.builtin`: Built-in Pi on an OpenAI-compatible gateway with an
 *   empty model, whose model source lists nothing for that provider but takes a typed id (status
 *   fallback, machine offline). The model is a plain text input; the form composes and lays out.
 * - a required field this build cannot render says "Update Raft to edit this setting." once, with no
 *   error under it (slock #15397); the form still cannot be saved.
 * - validation copy is the web's; a selected choice's description is not repeated under the field; a
 *   saved value that is an unavailable entry says it is not in the current list; a stale list says the
 *   Computer did not answer in time.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeFormV2WebParityUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun builtinGatewayWithAnEmptyModelComposesWithAModelIdInput() = runRobolectricComposeUiTest {
        val loaded =
            RuntimeFormV2Loaded(
                form = checkNotNull(parseRuntimeFormV2(BUILTIN_V3_FORM_JSON)),
                sources = sources(BUILTIN_PROVIDER_SOURCE, BUILTIN_MODEL_SOURCE, BUILTIN_REASONING_SOURCE)
            )
        // As the visual case sets them: over the initial values, not through a change.
        val values =
            initialRuntimeFormV2Values(loaded.form, loaded.sources) +
                mapOf("providerId" to JsonPrimitive("openai-compatible"), "model" to JsonPrimitive(""))
        setContent { Form(loaded, values, showAllErrors = false) }
        waitForIdle()
        // The model field takes a typed id: its placeholder, no picker.
        assertEquals(1, onAllNodes(textContains("Model ID"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodes(textContains("Base URL"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodes(textContains("OpenAI Compatible"), useUnmergedTree = true).fetchSemanticsNodes().size)
        // Nothing is shown as an error before a submit attempt.
        assertEquals(0, onAllNodes(textContains("This field is required."), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun webValidationCopyAndHintsWithoutRepeats() = runRobolectricComposeUiTest {
        val loaded = RuntimeFormV2Loaded(checkNotNull(parseRuntimeFormV2(PARITY_FORM)), sources(SELECT_REQUIRED_SOURCE, SELECT_RETIRED_SOURCE, SELECT_STALE_SOURCE))
        val values =
            initialRuntimeFormV2Values(loaded.form, loaded.sources) +
                mapOf(
                    // As the kitchen-sink state: a required select left empty, a retired saved value, a bad URL.
                    "selectRequired" to JsonPrimitive(""),
                    "selectRetired" to JsonPrimitive("retired"),
                    "url" to JsonPrimitive("gateway.example.test")
                )
        setContent { Form(loaded, values, showAllErrors = true) }
        waitForIdle()
        fun count(text: String) = onAllNodes(textContains(text), useUnmergedTree = true).fetchSemanticsNodes().size
        // Unsupported required: the field says it once; no error repeats it, and the form cannot be saved.
        assertEquals(1, count("Update Raft to edit this setting."))
        assertEquals(RuntimeFormV2ErrorCode.UnsupportedRequired, validateRuntimeFormV2(loaded.form, loaded.sources, values)["numberRequired"])
        // The web's words.
        assertEquals(1, count("This field is required."))
        assertEquals(1, count("Base URL must start with http:// or https://."))
        assertEquals(1, count("Choose one of the available options."))
        // A saved value that is now an unavailable entry: the web's "not in the current list" line.
        assertEquals(1, count("The saved value retired is not in the current list."))
        // A stale list says so instead of its timeout reason.
        assertEquals(1, count("did not answer in time, so these options may be out of date."))
        assertEquals(0, count("took too long to list the options"))
        // The selected choice's description belongs to its list row, not under the closed field.
        assertEquals(0, count("The first choice"))
    }

    @Composable
    private fun Form(loaded: RuntimeFormV2Loaded, initial: RuntimeFormV2Values, showAllErrors: Boolean) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
        var values: RuntimeFormV2Values by remember { mutableStateOf(initial) }
        var advancedOpen by remember { mutableStateOf(false) }
        val errors = validateRuntimeFormV2(loaded.form, loaded.sources, values)
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftTheme(appearance = RaftAppearance.Light) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RuntimeFormV2Fields(
                        loaded = loaded,
                        values = values,
                        onChange = { key, value -> values = applyRuntimeFormV2Change(loaded.form, loaded.sources, values, key, value) },
                        errors = if (showAllErrors) errors else runtimeFormV2ShownErrors(errors, emptySet()),
                        serverErrorKeys = emptySet(),
                        serverMessage = null,
                        editing = false,
                        advancedOpen = advancedOpen,
                        onAdvancedOpenChange = { advancedOpen = it },
                        enabled = true,
                        sourceRefresh = RuntimeFormV2SourceRefresh(refreshing = emptySet(), failed = emptySet(), onRefresh = {})
                    )
                }
            }
        }
    }

    private fun textContains(text: String) =
        SemanticsMatcher("text contains \"$text\"") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains(text) } ||
                node.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(text) == true
        }

    private fun sources(vararg json: String) =
        json.map { checkNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(it))) }.associateBy { it.sourceId }

    private companion object {
        // slock fixtures builtin.option-source.*.v3.json, cut to a few providers; the OpenAI-compatible
        // gateway lists no model and takes a typed one.
        const val BUILTIN_PROVIDER_SOURCE =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v3","sourceId":"provider","kind":"select","pointer":"/providerId","options":[{"value":"deepseek","label":"DeepSeek","providerKind":"preset"},{"value":"openai-compatible","label":"OpenAI Compatible","providerKind":"gateway"},{"value":"anthropic-compatible","label":"Anthropic Compatible","providerKind":"gateway"}],"defaultValue":"deepseek"}"""
        const val BUILTIN_MODEL_SOURCE =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v3","sourceId":"model","kind":"dependent_select","pointer":"/model","dependsOn":"/providerId","optionsByValue":{"deepseek":[{"value":"deepseek/deepseek-v4-pro","label":"DeepSeek V4 Pro"}],"openai-compatible":[],"anthropic-compatible":[]},"defaultValueByValue":{"deepseek":"deepseek/deepseek-v4-pro"},"customValueAllowedByValue":{"deepseek":true,"openai-compatible":true,"anthropic-compatible":true},"status":"fallback","reason":"machine_offline","retryable":true}"""
        const val BUILTIN_REASONING_SOURCE =
            """{"protocolVersion":1,"runtimeId":"builtin","schemaVersion":"builtin-pi.create.v3","kind":"select","sourceId":"reasoningEffort","pointer":"/reasoningEffort","options":[{"value":"","label":"Default"},{"value":"low","label":"Low"},{"value":"high","label":"High"}],"defaultValue":""}"""

        // A slice of slock's kitchen-sink v3 forms (kitchen-sink-inputs / kitchen-sink-choices).
        const val PARITY_FORM =
            """{"protocolVersion":3,"runtimeId":"parity","schemaVersion":"parity.create.v1","dataSchema":{"type":"object","additionalProperties":false,"required":["numberRequired","selectRequired","selectRetired"],"properties":{"numberRequired":{"type":"number","title":"Number, required"},"url":{"type":"string","format":"uri","title":"URL"},"selectRequired":{"type":"string","minLength":1,"title":"Select, required","x-optionSource":"selectRequired"},"selectRetired":{"type":"string","minLength":1,"title":"Select, retired","x-optionSource":"selectRetired"},"selectStale":{"type":"string","title":"Select, stale","x-optionSource":"selectStale"}}},"uiSchema":{"order":["numberRequired","url","selectRequired","selectRetired","selectStale"],"layout":{"advanced":[]},"localization":{"numberRequired":{"label":"Number, required"},"url":{"label":"URL"},"selectRequired":{"label":"Select, required"},"selectRetired":{"label":"Select, retired"},"selectStale":{"label":"Select, stale","choices":{"alpha":{"label":"Alpha","description":"The first choice"}}}},"rules":[]},"optionSources":{"selectRequired":{"protocolVersion":1,"runtimeId":"parity","schemaVersion":"parity.create.v1","sourceId":"selectRequired","kind":"select","pointer":"/selectRequired"},"selectRetired":{"protocolVersion":1,"runtimeId":"parity","schemaVersion":"parity.create.v1","sourceId":"selectRetired","kind":"select","pointer":"/selectRetired"},"selectStale":{"protocolVersion":1,"runtimeId":"parity","schemaVersion":"parity.create.v1","sourceId":"selectStale","kind":"select","pointer":"/selectStale"}},"revision":"parity"}"""
        const val SELECT_REQUIRED_SOURCE =
            """{"protocolVersion":1,"runtimeId":"parity","schemaVersion":"parity.create.v1","sourceId":"selectRequired","kind":"select","pointer":"/selectRequired","options":[{"value":"alpha","label":"Alpha"},{"value":"beta","label":"Beta"}],"status":"live"}"""
        const val SELECT_RETIRED_SOURCE =
            """{"protocolVersion":1,"runtimeId":"parity","schemaVersion":"parity.create.v1","sourceId":"selectRetired","kind":"select","pointer":"/selectRetired","options":[{"value":"alpha","label":"Alpha"},{"value":"beta","label":"Beta"}],"status":"live","unavailableOptions":[{"value":"retired","label":"Retired","reason":"Disabled by the runtime"}]}"""
        const val SELECT_STALE_SOURCE =
            """{"protocolVersion":1,"runtimeId":"parity","schemaVersion":"parity.create.v1","sourceId":"selectStale","kind":"select","pointer":"/selectStale","options":[{"value":"alpha","label":"Alpha"},{"value":"beta","label":"Beta"}],"defaultValue":"alpha","status":"fallback","reason":"probe_timeout","retryable":true,"state":"stale","fetchedAt":"2026-11-18T08:00:00.000Z"}"""
    }
}
