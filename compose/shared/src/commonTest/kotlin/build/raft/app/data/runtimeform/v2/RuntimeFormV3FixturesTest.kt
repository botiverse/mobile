package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * Protocol v3 (slock #14471, README "Protocol v3"): every server-produced `*.v3.json` parses through the
 * one v2/v3 parser, and a rule this client cannot evaluate refuses the whole form (fail closed). Port of
 * slock `contract.test.ts` "v3 fixtures conform … nothing unsupported".
 */
class RuntimeFormV3FixturesTest {
    private val forms = mapOf(
        "builtin.form.v3.json" to BUILTIN_V3_FORM_JSON,
        "builtin.form.saved-connections.v3.json" to BUILTIN_V3_FORM_SAVED_CONNECTIONS_JSON,
        "builtin.edit.connection-unavailable.v3.json" to BUILTIN_V3_EDIT_CONNECTION_UNAVAILABLE_JSON,
        "codex.form.v3.json" to CODEX_V3_FORM_JSON,
        "codex.edit.v3.json" to CODEX_V3_EDIT_JSON,
        "grok.form.v3.json" to GROK_V3_FORM_JSON,
        "claude.form.v3.json" to CLAUDE_V3_FORM_JSON,
        "claude.edit.v3.json" to CLAUDE_V3_EDIT_JSON,
        "copilot.edit.v3.json" to COPILOT_V3_EDIT_JSON,
        "kimi-sdk.form.v3.json" to KIMI_SDK_V3_FORM_JSON,
        "kimi-sdk.edit.v3.json" to KIMI_SDK_V3_EDIT_JSON,
        "pi.form.v3.json" to PI_V3_FORM_JSON,
        "pi.edit.v3.json" to PI_V3_EDIT_JSON
    )
    private val sources = mapOf(
        "builtin.option-source.provider.saved-connections.v3.json" to BUILTIN_V3_PROVIDER_SAVED_CONNECTIONS_SOURCE_JSON,
        "codex.option-source.reasoning-effort.v3.json" to CODEX_V3_REASONING_EFFORT_SOURCE_JSON,
        "claude.option-source.reasoning-effort.v3.json" to CLAUDE_V3_REASONING_EFFORT_SOURCE_JSON,
        "kimi-sdk.option-source.reasoning-effort.v3.json" to KIMI_SDK_V3_REASONING_EFFORT_SOURCE_JSON,
        "pi.option-source.reasoning-effort.v3.json" to PI_V3_REASONING_EFFORT_SOURCE_JSON,
        "pi.option-source.provider-reasoning-effort.v3.json" to PI_V3_PROVIDER_REASONING_EFFORT_SOURCE_JSON
    )

    @Test
    fun everyV3FormParsesAsProtocol3WithNothingUnsupported() {
        for ((name, json) in forms) {
            val form = assertNotNull(parseRuntimeFormV2(json), name)
            assertEquals(RUNTIME_FORM_V3_PROTOCOL_VERSION, form.protocolVersion, name)
            assertTrue(!form.revision.isNullOrEmpty(), "$name: every v3 form carries a revision")
            assertEquals(emptyList(), form.blockingFieldKeys, name)
            assertTrue(form.fields.none { it.kind == RuntimeFormV2FieldKind.DerivedSelect }, "$name: no x-optionsFrom on v3")
            assertTrue(form.fields.none { it.connectionOwned }, "$name: no x-connectionOwned on v3")
            assertTrue(form.fields.all { it.visibleWhen.isEmpty() }, "$name: rules, never visibility")
        }
    }

    @Test
    fun everyReasoningEffortIsADependentSelectOnItsModel() {
        val efforts = mapOf(
            CODEX_V3_FORM_JSON to listOf("reasoningEffort" to "model"),
            GROK_V3_FORM_JSON to listOf("reasoningEffort" to "model"),
            CLAUDE_V3_FORM_JSON to listOf("reasoningEffort" to "model"),
            COPILOT_V3_EDIT_JSON to listOf("reasoningEffort" to "model"),
            KIMI_SDK_V3_FORM_JSON to listOf("reasoningEffort" to "model"),
            PI_V3_FORM_JSON to listOf("reasoningEffort" to "model", "providerReasoningEffort" to "providerModel")
        )
        for ((json, pairs) in efforts) {
            val form = assertNotNull(parseRuntimeFormV2(json))
            for ((key, parent) in pairs) {
                val field = form.fields.single { it.key == key }
                assertEquals(RuntimeFormV2FieldKind.DependentSelect, field.kind, "${form.runtimeId}/$key")
                assertEquals(parent, field.dependsOn, "${form.runtimeId}/$key")
                assertFalse(field.required, "${form.runtimeId}/$key is optional")
            }
        }
    }

    @Test
    fun rulesAndConditionalRequiredAreReadFromTheSavedConnectionsForm() {
        val form = assertNotNull(parseRuntimeFormV2(BUILTIN_V3_FORM_SAVED_CONNECTIONS_JSON))
        val apiKey = form.fields.single { it.key == "apiKey" }
        assertEquals(RuntimeFormV3Effect.Hide, apiKey.rule?.effect)
        assertEquals(RuntimeFormV3Scope("providerId"), apiKey.rule?.condition?.scope)
        assertFalse(apiKey.required, "required only through dataSchema.allOf")
        assertEquals(listOf(false), apiKey.requiredWhen.map { it.whenResult }, "the `else` branch of `if provider is a connection`")
        assertEquals(RuntimeFormV3Effect.Show, form.fields.single { it.key == "baseUrl" }.rule?.effect)
        assertNull(form.fields.single { it.key == "model" }.rule)
    }

    @Test
    fun everyV3SourceParsesWithTheServersDefaultFirstAndNoUnavailableOption() {
        for ((name, json) in sources) {
            val source = assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(json)), name)
            assertTrue(source.options.orEmpty().none { it.unavailable }, "$name: unavailable entries only in unavailableOptions")
            for ((parent, options) in source.optionsByValue.orEmpty()) {
                assertEquals("" to "Default", options.first().let { it.value to it.label }, "$name/$parent: the server-sent Default comes first")
            }
        }
        val provider = assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(BUILTIN_V3_PROVIDER_SAVED_CONNECTIONS_SOURCE_JSON)))
        val off = provider.unavailableOptions.single()
        assertEquals("connection:conn-off", off.value)
        assertEquals("conn-off", off.connectionId)
        assertEquals("saved", off.group)
        assertEquals("This saved provider is disabled.", off.reason)
        val claude = assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(CLAUDE_MODEL_LIVE_SOURCE_JSON)))
        assertEquals(
            listOf(RuntimeFormV2UnavailableChoice("claude-opus-9", "Claude Opus 9", reason = "Update Claude Code to use this model")),
            claude.unavailableOptions
        )
    }

    @Test
    fun aRuleOrConditionalRequiredThisClientCannotEvaluateRefusesTheWholeForm() {
        val base = Json.parseToJsonElement(BUILTIN_V3_FORM_SAVED_CONNECTIONS_JSON).jsonObject
        val rules = base.at("uiSchema", "rules") as JsonArray
        val firstRule = rules.first().jsonObject
        fun withRules(vararg extra: JsonElement) = base.replacedAt(listOf("uiSchema", "rules"), JsonArray(rules + extra))
        fun rule(scope: String = "#/properties/model", effect: String = "SHOW", conditionScope: String = "#/properties/providerId", schema: JsonElement) =
            JsonObject(
                mapOf(
                    "scope" to JsonPrimitive(scope),
                    "effect" to JsonPrimitive(effect),
                    "condition" to JsonObject(mapOf("scope" to JsonPrimitive(conditionScope), "schema" to schema))
                )
            )
        val known = JsonObject(mapOf("const" to JsonPrimitive("deepseek")))
        val refused = mapOf(
            "unknown keyword" to withRules(rule(schema = JsonObject(mapOf("minLength" to JsonPrimitive(1))))),
            "unknown effect" to withRules(rule(effect = "DISABLE", schema = known)),
            "unknown field scope" to withRules(rule(scope = "#/properties/a/b", schema = known)),
            "unknown condition scope" to withRules(rule(conditionScope = "/providerId", schema = known)),
            "a second rule for one field" to withRules(firstRule),
            "allOf with an unknown keyword" to base.replacedAt(
                listOf("dataSchema", "allOf"),
                JsonArray(listOf(JsonObject(mapOf("if" to JsonObject(mapOf("type" to JsonPrimitive("string"))), "then" to JsonObject(mapOf("required" to JsonArray(emptyList())))))))
            ),
            "allOf clause without required" to base.replacedAt(
                listOf("dataSchema", "allOf"),
                JsonArray(listOf(JsonObject(mapOf("if" to known, "else" to JsonObject(emptyMap())))))
            ),
            "protocol 4" to base.replacedAt(listOf("protocolVersion"), JsonPrimitive(4))
        )
        for ((name, body) in refused) assertNull(parseRuntimeFormV2(body), name)
        assertNotNull(parseRuntimeFormV2(withRules(rule(schema = known))), "a well-formed extra rule is fine")
    }

    @Test
    fun aV2FormNeverEvaluatesRules() {
        // `rules` are a v3 shape: the same body as protocol 2 is read without them (v2 keeps `visibility`).
        val v2 = Json.parseToJsonElement(BUILTIN_V3_FORM_SAVED_CONNECTIONS_JSON).jsonObject.replacedAt(listOf("protocolVersion"), JsonPrimitive(2))
        val form = assertNotNull(parseRuntimeFormV2(v2))
        assertEquals(RUNTIME_FORM_V2_PROTOCOL_VERSION, form.protocolVersion)
        assertTrue(form.fields.all { it.rule == null && it.requiredWhen.isEmpty() })
    }
}

private fun JsonObject.at(vararg path: String): JsonElement? =
    path.fold(this as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

private fun JsonObject.replacedAt(path: List<String>, value: JsonElement): JsonObject {
    val key = path.first()
    return if (path.size == 1) {
        JsonObject(this + (key to value))
    } else {
        JsonObject(this + (key to (this[key] as? JsonObject ?: JsonObject(emptyMap())).replacedAt(path.drop(1), value)))
    }
}
