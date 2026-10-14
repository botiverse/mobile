package build.raft.app.data.runtimeform.v2

import build.raft.app.runtimeFormV2ShownErrors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `writeOnlyKeepRequiresUnchanged` (slock #9217): on edit a blank secret keeps the stored value only
 * while every visible field it is bound to still equals its stored value. Mirrors slock
 * packages/runtime-form/src/v2State.ts `keepBindingChanged` / `secret_reentry_required`.
 */
class RuntimeFormV2WriteOnlyKeepTest {
    private fun form(json: String) = assertNotNull(parseRuntimeFormV2(json), "parses without falling back to v1")
    private fun source(json: String) = assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(json)))

    private val claudeEdit = form(CLAUDE_V2_EDIT_JSON)
    private val claudeSources = mapOf(
        "provider" to source(CLAUDE_V2_PROVIDER_SOURCE_JSON),
        "model" to source(CLAUDE_V2_FALLBACK_SOURCE_JSON)
    )
    private val builtinEdit = form(BUILTIN_V2_EDIT_JSON)

    private fun RuntimeFormV2Values.with(key: String, value: String) = this + (key to JsonPrimitive(value))

    private fun claudeErrors(values: RuntimeFormV2Values) =
        validateRuntimeFormV2(claudeEdit, claudeSources, values, editing = true)

    /** Builtin errors for the secret only: the test sources do not list every provider/model. */
    private fun builtinKeyError(form: ParsedRuntimeFormV2, values: RuntimeFormV2Values) =
        validateRuntimeFormV2(form, emptyMap(), values, editing = true)["apiKey"]

    @Test
    fun theEditFixturesCarryTheBindingByFieldKey() {
        assertEquals(mapOf("apiKey" to listOf("provider", "apiUrl")), claudeEdit.writeOnlyKeepRequiresUnchanged)
        assertEquals(mapOf("apiKey" to listOf("providerId", "baseUrl")), builtinEdit.writeOnlyKeepRequiresUnchanged)
        assertEquals(mapOf("apiKey" to listOf("provider")), form(PI_V2_EDIT_JSON).writeOnlyKeepRequiresUnchanged)
        assertEquals(mapOf("apiKey" to listOf("provider")), form(PI_V2_EDIT_CONFIGURED_JSON).writeOnlyKeepRequiresUnchanged)
        assertEquals(emptyMap(), form(CLAUDE_V2_FORM_JSON).writeOnlyKeepRequiresUnchanged, "create forms never carry it")
    }

    @Test
    fun changedUrlWithABlankKeyBlocksTheSaveOnTheKey() {
        val values = initialRuntimeFormV2Values(claudeEdit, claudeSources).with("apiUrl", "https://other.example.test")
        assertEquals(mapOf("apiKey" to RuntimeFormV2ErrorCode.SecretReentryRequired), claudeErrors(values))
    }

    @Test
    fun theReentryErrorShowsBeforeTheKeyIsTouched() {
        val values = initialRuntimeFormV2Values(claudeEdit, claudeSources).with("apiUrl", "https://other.example.test")
        assertEquals(
            mapOf("apiKey" to RuntimeFormV2ErrorCode.SecretReentryRequired),
            runtimeFormV2ShownErrors(claudeErrors(values), shownKeys = setOf("apiUrl"))
        )
    }

    @Test
    fun changedUrlWithANewKeyIsAllowed() {
        val values = initialRuntimeFormV2Values(claudeEdit, claudeSources)
            .with("apiUrl", "https://other.example.test")
            .with("apiKey", "sk-new")
        assertEquals(emptyMap(), claudeErrors(values))
    }

    @Test
    fun nothingChangedKeepsTheBlankKey() {
        assertEquals(emptyMap(), claudeErrors(initialRuntimeFormV2Values(claudeEdit, claudeSources)))
        // A whitespace-only key is still blank: keep, not a new key.
        val spaces = initialRuntimeFormV2Values(claudeEdit, claudeSources).with("apiKey", "   ")
        assertEquals(emptyMap(), claudeErrors(spaces))
    }

    @Test
    fun aUrlChangedOnlyByTrailingSlashOrWhitespaceStillKeeps() {
        val initial = initialRuntimeFormV2Values(claudeEdit, claudeSources)
        for (url in listOf("https://gateway.example.test/", "  https://gateway.example.test  ", " https://gateway.example.test// ")) {
            assertEquals(emptyMap(), claudeErrors(initial.with("apiUrl", url)), "'$url' is the stored URL")
        }
    }

    @Test
    fun aChangedProviderBlocksTheBlankKey() {
        val initial = initialRuntimeFormV2Values(builtinEdit, emptyMap())
        assertEquals(null, builtinKeyError(builtinEdit, initial))
        // anthropic-compatible keeps both apiKey and baseUrl visible; only the provider changed.
        val changed = initial.with("providerId", "anthropic-compatible")
        assertEquals(RuntimeFormV2ErrorCode.SecretReentryRequired, builtinKeyError(builtinEdit, changed))
        assertEquals(null, builtinKeyError(builtinEdit, changed.with("apiKey", "sk-new")))
    }

    @Test
    fun aHiddenBoundFieldIsSkipped() {
        // The real builtin edit body, stored on a preset provider: baseUrl is stored but hidden.
        val preset = form(BUILTIN_V2_EDIT_JSON.replace("\"providerId\": \"openai-compatible\"", "\"providerId\": \"deepseek\""))
        val baseUrl = preset.fields.single { it.key == "baseUrl" }
        val initial = initialRuntimeFormV2Values(preset, emptyMap())
        val values = initial.with("baseUrl", "https://elsewhere.example.test")
        assertTrue(!isRuntimeFormV2FieldVisible(baseUrl, values, emptyMap()), "precondition: baseUrl hidden")
        assertEquals(null, builtinKeyError(preset, values))
    }

    @Test
    fun aStoredNullAndAnEmptyValueAreTheSame() {
        val noUrl = form(BUILTIN_V2_EDIT_JSON.replace("\"baseUrl\": \"https://gateway.example.test/v1\"", "\"baseUrl\": null"))
        val initial = initialRuntimeFormV2Values(noUrl, emptyMap())
        assertEquals("", initial.text("baseUrl"))
        assertEquals(null, builtinKeyError(noUrl, initial.with("baseUrl", "  ")))
        assertEquals(RuntimeFormV2ErrorCode.SecretReentryRequired, builtinKeyError(noUrl, initial.with("baseUrl", "https://x.example.test")))
    }

    @Test
    fun aFormWithoutTheBindingKeepsTheOldBehaviour() {
        val unbound = form(CLAUDE_V2_EDIT_JSON.replace("\"writeOnlyKeepRequiresUnchanged\"", "\"futureUnknownKey\""))
        assertEquals(emptyMap(), unbound.writeOnlyKeepRequiresUnchanged)
        val values = initialRuntimeFormV2Values(unbound, claudeSources).with("apiUrl", "https://other.example.test")
        assertEquals(emptyMap(), validateRuntimeFormV2(unbound, claudeSources, values, editing = true))
    }

    @Test
    fun createNeverAppliesTheBinding() {
        val values = initialRuntimeFormV2Values(claudeEdit, claudeSources)
            .with("apiUrl", "https://other.example.test")
        assertEquals(null, validateRuntimeFormV2(claudeEdit, claudeSources, values, editing = false)["apiKey"])
        // Builtin's key is required: on create a blank one is plain Required, never a re-entry.
        val builtin = initialRuntimeFormV2Values(builtinEdit, emptyMap()).with("providerId", "anthropic-compatible")
        assertEquals(RuntimeFormV2ErrorCode.Required, validateRuntimeFormV2(builtinEdit, emptyMap(), builtin, editing = false)["apiKey"])
    }

    @Test
    fun malformedEntriesAndUnknownBoundFieldsAreIgnoredAndEachSecretIsBoundOnItsOwn() {
        val json = """{
          "protocolVersion": 2, "runtimeId": "x", "schemaVersion": "x.v1",
          "dataSchema": {"type": "object", "properties": {
            "url": {"type": "string", "format": "uri"},
            "region": {"type": "string"},
            "apiKey": {"type": "string", "writeOnly": true},
            "token": {"type": "string", "writeOnly": true},
            "plain": {"type": "string"}
          }},
          "writeOnlyKeepRequiresUnchanged": {
            "/apiKey": ["/url", "/missing"],
            "/token": ["/region", 7, "/token"],
            "/plain": ["/url"],
            "/missingSecret": ["/url"],
            "/bad": "not-an-array"
          },
          "values": {"url": "https://a.example.test", "region": "eu"}
        }"""
        val parsed = form(json)
        assertEquals(mapOf("apiKey" to listOf("url"), "token" to listOf("region")), parsed.writeOnlyKeepRequiresUnchanged)
        val initial = initialRuntimeFormV2Values(parsed, emptyMap())
        assertEquals(emptyMap(), validateRuntimeFormV2(parsed, emptyMap(), initial, editing = true))
        assertEquals(
            mapOf("apiKey" to RuntimeFormV2ErrorCode.SecretReentryRequired),
            validateRuntimeFormV2(parsed, emptyMap(), initial.with("url", "https://b.example.test"), editing = true)
        )
        assertEquals(
            mapOf("token" to RuntimeFormV2ErrorCode.SecretReentryRequired),
            validateRuntimeFormV2(parsed, emptyMap(), initial.with("region", "us"), editing = true)
        )
        assertEquals(
            mapOf("apiKey" to RuntimeFormV2ErrorCode.SecretReentryRequired, "token" to RuntimeFormV2ErrorCode.SecretReentryRequired),
            validateRuntimeFormV2(parsed, emptyMap(), initial.with("url", "https://b.example.test").with("region", "us"), editing = true)
        )
    }
}
