package build.raft.app.data.runtimeform.v2

import build.raft.app.runtimeFormV2ShownErrors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Protocol v3 client state against the server's v3 fixtures (slock #14471): rules show/hide (hidden =
 * not validated, not required, not submitted), conditional required, the reasoning effort as a
 * model-keyed dependent_select with the server's Default, unavailable options, and the submit's
 * protocolVersion. Port of the v3 cases in slock `v3State.test.ts` and the web behaviour tests.
 */
class RuntimeFormV3StateTest {
    private fun form(json: String) = assertNotNull(parseRuntimeFormV2(json), "a v3 form this client renders")
    private fun source(json: String) = assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(json)))
    private fun ParsedRuntimeFormV2.field(key: String) = fields.single { it.key == key }
    private fun RuntimeFormV2Values.with(key: String, value: String) = this + (key to JsonPrimitive(value))

    private fun model(key: String) = RuntimeFormV2Choice(key, key)

    /** Built-in's provider source as the server sends it with saved connections, and a model list per provider. */
    private val builtinSources: RuntimeFormV2Sources = mapOf(
        "provider" to source(BUILTIN_V3_PROVIDER_SAVED_CONNECTIONS_SOURCE_JSON),
        "model" to RuntimeFormV2Source(
            sourceId = "model",
            kind = RuntimeFormV2SourceKind.DependentSelect,
            pointer = "/model",
            dependsOn = "/providerId",
            optionsByValue = mapOf(
                "deepseek" to listOf(model("deepseek/deepseek-v4-pro")),
                "connection:conn-deepseek" to listOf(model("deepseek/deepseek-v4-pro")),
                "connection:conn-gateway" to emptyList(),
                "openai-compatible" to emptyList()
            ),
            defaultValueByValue = mapOf("deepseek" to "deepseek/deepseek-v4-pro", "connection:conn-deepseek" to "deepseek/deepseek-v4-pro"),
            customValueAllowedByValue = mapOf("connection:conn-gateway" to true, "openai-compatible" to true)
        )
    )

    private val codex = form(CODEX_V3_FORM_JSON)
    private val codexSources: RuntimeFormV2Sources = mapOf(
        "model" to source(CODEX_MODEL_LIVE_SOURCE_JSON),
        "reasoningEffort" to source(CODEX_V3_REASONING_EFFORT_SOURCE_JSON)
    )

    @Test
    fun aSavedConnectionHidesTheFieldsItOwnsAndTheyAreNeitherRequiredNorSubmitted() {
        val builtin = form(BUILTIN_V3_FORM_SAVED_CONNECTIONS_JSON)
        val direct = initialRuntimeFormV2Values(builtin, builtinSources)
        assertEquals("deepseek", direct.text("providerId"), "the source's default")
        assertTrue(isRuntimeFormV2FieldVisible(builtin.field("apiKey"), direct, builtinSources))
        assertTrue(isRuntimeFormV2FieldRequired(builtin.field("apiKey"), direct), "required by allOf's else branch")
        assertEquals(mapOf("apiKey" to RuntimeFormV2ErrorCode.Required), validateRuntimeFormV2(builtin, builtinSources, direct))

        val connection = applyRuntimeFormV2Change(builtin, builtinSources, direct.with("apiKey", "sk-typed"), "providerId", JsonPrimitive("connection:conn-deepseek"))
        for (key in listOf("apiKey", "baseUrl", "supportsImageInput", "contextWindow", "maxOutputTokens")) {
            assertFalse(isRuntimeFormV2FieldVisible(builtin.field(key), connection, builtinSources), "$key hidden while a connection is selected")
        }
        assertFalse(isRuntimeFormV2FieldRequired(builtin.field("apiKey"), connection))
        assertEquals(emptyMap(), validateRuntimeFormV2(builtin, builtinSources, connection))
        val submitted = runtimeFormV2Submission(builtin, connection, builtinSources)
        assertEquals(JsonPrimitive("connection:conn-deepseek"), submitted["providerId"])
        assertEquals(JsonPrimitive("deepseek/deepseek-v4-pro"), submitted["model"])
        for (key in listOf("apiKey", "baseUrl", "supportsImageInput")) assertFalse(key in submitted, "$key is never submitted for a connection")

        val gateway = applyRuntimeFormV2Change(builtin, builtinSources, connection, "providerId", JsonPrimitive("connection:conn-gateway"))
        assertTrue(isRuntimeFormV2FieldVisible(builtin.field("contextWindow"), gateway, builtinSources), "the gateway connection keeps its limit fields")
        assertFalse(isRuntimeFormV2FieldVisible(builtin.field("baseUrl"), gateway, builtinSources), "but its URL is the connection's")

        val compatible = applyRuntimeFormV2Change(builtin, builtinSources, connection, "providerId", JsonPrimitive("openai-compatible"))
        assertTrue(isRuntimeFormV2FieldVisible(builtin.field("baseUrl"), compatible, builtinSources))
        assertTrue(isRuntimeFormV2FieldRequired(builtin.field("apiKey"), compatible), "off the connection the key is required again")
    }

    @Test
    fun aBoundConnectionInUnavailableOptionsStaysSelectedBlocksTheSaveAndIsNeverRewritten() {
        val edit = form(BUILTIN_V3_EDIT_CONNECTION_UNAVAILABLE_JSON)
        val stored = initialRuntimeFormV2Values(edit, builtinSources)
        assertEquals("connection:conn-off", stored.text("providerId"), "the stored binding, not the source default")
        val provider = edit.field("providerId")
        assertEquals("conn-off", runtimeFormV2UnavailableSelection(provider, builtinSources, stored)?.connectionId)
        assertFalse(isRuntimeFormV2FieldVisible(edit.field("apiKey"), stored, builtinSources), "still a connection: the key stays hidden")
        val errors = validateRuntimeFormV2(edit, builtinSources, stored, editing = true)
        assertEquals(RuntimeFormV2ErrorCode.OptionUnavailable, errors["providerId"])
        assertEquals(RuntimeFormV2ErrorCode.OptionUnavailable, runtimeFormV2ShownErrors(errors, emptySet())["providerId"], "shown before the field is touched")
        assertEquals(
            stored,
            runtimeFormV2ValuesAfterSourceReload(edit, builtinSources, stored, "provider"),
            "a re-probe never resets a value the source lists as unavailable"
        )

        val repicked = applyRuntimeFormV2Change(edit, builtinSources, stored, "providerId", JsonPrimitive("deepseek"))
        val repickedErrors = validateRuntimeFormV2(edit, builtinSources, repicked, editing = true)
        assertNull(repickedErrors["providerId"])
        assertEquals(
            RuntimeFormV2ErrorCode.SecretReentryRequired,
            repickedErrors["apiKey"],
            "the stored secret was bound to the old provider (writeOnlyKeepRequiresUnchanged)"
        )
    }

    @Test
    fun aDisabledModelBlocksOnAListOnlySourceAndIsTakenAsTypedWhereTypingIsAllowed() {
        val claude = form(CLAUDE_V3_FORM_JSON)
        val typedAllowed = source(CLAUDE_MODEL_LIVE_SOURCE_JSON)
        val listOnly = typedAllowed.copy(customValueAllowed = false)
        val values = mapOf("provider" to JsonPrimitive("anthropic"), "model" to JsonPrimitive("claude-opus-9"))
        assertEquals("Claude Opus 9", runtimeFormV2UnavailableSelection(claude.field("model"), mapOf("model" to typedAllowed), values)?.label)
        assertNull(validateRuntimeFormV2(claude, mapOf("model" to typedAllowed), values)["model"])
        assertEquals(RuntimeFormV2ErrorCode.OptionUnavailable, validateRuntimeFormV2(claude, mapOf("model" to listOnly), values)["model"])
        assertEquals(listOf("claude-opus-9"), runtimeFormV2UnavailableChoices(claude.field("model"), mapOf("model" to typedAllowed)).map { it.value })
    }

    @Test
    fun theReasoningEffortFollowsTheModelWithTheServersDefaultAndTheSubmitSaysProtocol3() {
        val values = initialRuntimeFormV2Values(codex, codexSources)
        assertEquals("gpt-5.6-sol", values.text("model"))
        assertEquals("medium", values.text("reasoningEffort"), "the model's default effort")
        val effort = codex.field("reasoningEffort")
        val choices = runtimeFormV2Choices(effort, codexSources, values) as RuntimeFormV2Choices.Select
        assertEquals("" to "Default", choices.options.first().let { it.value to it.label }, "Default is a server option")
        assertEquals("Medium", choices.options.single { it.value == "medium" }.label, "labelled from FieldCopy.choices")

        val default = values.with("reasoningEffort", "")
        assertEquals(emptyMap(), validateRuntimeFormV2(codex, codexSources, default))
        val submitted = runtimeFormV2Submission(codex, default, codexSources)
        assertEquals(JsonPrimitive(""), submitted["reasoningEffort"], "Default is sent as \"\" (stored as null)")
        val body = JsonObject(emptyMap()).withRuntimeFormV2(RuntimeFormV2SubmitBody(codex.runtimeId, submitted, codex.protocolVersion))
        assertEquals(JsonPrimitive(3), body.getValue("formDefinitionRef").jsonObject["protocolVersion"])

        val preview = applyRuntimeFormV2Change(codex, codexSources, values, "model", JsonPrimitive("gpt-7-preview"))
        assertEquals("high", preview.text("reasoningEffort"), "a new model resets the effort to its own default")
    }

    @Test
    fun aModelWithoutEffortsHidesTheOptionalEffortWhichIsThenLeftOutOrKeptAsStored() {
        val values = initialRuntimeFormV2Values(codex, codexSources)
        for (model in listOf("gpt-5.5", "my-org/typed-model")) {
            val next = applyRuntimeFormV2Change(codex, codexSources, values, "model", JsonPrimitive(model))
            assertFalse(isRuntimeFormV2FieldVisible(codex.field("reasoningEffort"), next, codexSources), "$model has no effort key")
            assertEquals(emptyMap(), validateRuntimeFormV2(codex, codexSources, next), model)
            assertFalse("reasoningEffort" in runtimeFormV2Submission(codex, next, codexSources), "$model: nothing stored, nothing sent")
        }

        val edit = form(CODEX_V3_EDIT_JSON)
        val stored = initialRuntimeFormV2Values(edit, codexSources)
        assertEquals("my-org/codex-custom", stored.text("model"))
        assertFalse(isRuntimeFormV2FieldVisible(edit.field("reasoningEffort"), stored, codexSources))
        assertEquals(emptyMap(), validateRuntimeFormV2(edit, codexSources, stored, editing = true))
        assertEquals(JsonPrimitive("high"), runtimeFormV2Submission(edit, stored, codexSources)["reasoningEffort"], "an edit submits the stored effort unchanged")
    }

    @Test
    fun refreshingASourceNamesTheSourcesKeyedByItsField() {
        assertEquals(listOf("reasoningEffort"), runtimeFormV2DependentSourceIds(codex, "model"))
        assertEquals(emptyList(), runtimeFormV2DependentSourceIds(codex, "reasoningEffort"))
        val pi = form(PI_V3_FORM_JSON)
        assertEquals(listOf("providerModel"), runtimeFormV2DependentSourceIds(pi, "provider"))
        assertEquals(listOf("providerReasoningEffort"), runtimeFormV2DependentSourceIds(pi, "providerModel"))
        assertEquals(listOf("reasoningEffort"), runtimeFormV2DependentSourceIds(pi, "model"))
    }

    @Test
    fun theV2SubmitStaysProtocol2() {
        val v2 = assertNotNull(parseRuntimeFormV2(CODEX_V2_FORM_JSON))
        assertEquals(RUNTIME_FORM_V2_PROTOCOL_VERSION, v2.protocolVersion)
        val body = JsonObject(emptyMap()).withRuntimeFormV2(RuntimeFormV2SubmitBody(v2.runtimeId, JsonObject(emptyMap()), v2.protocolVersion))
        assertEquals(JsonPrimitive(2), body.getValue("formDefinitionRef").jsonObject["protocolVersion"])
    }
}
