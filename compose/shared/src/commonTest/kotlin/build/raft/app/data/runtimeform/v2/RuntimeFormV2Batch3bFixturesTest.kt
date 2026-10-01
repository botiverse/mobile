package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Server batch 3b (slock #8637): Claude, Cursor and Copilot render on mobile from the server's exact bodies. */
class RuntimeFormV2Batch3bFixturesTest {
    private fun form(json: String) = assertNotNull(parseRuntimeFormV2(json), "parses without falling back to v1")
    private fun source(json: String) = assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(json)))

    private val claude = form(CLAUDE_V2_FORM_JSON)
    private val claudeSources = mapOf(
        "provider" to source(CLAUDE_V2_PROVIDER_SOURCE_JSON),
        "model" to source(CLAUDE_V2_FALLBACK_SOURCE_JSON)
    )

    @Test
    fun everyBatch3bFormParsesWithNothingBlocking() {
        for ((name, json) in listOf(
            "claude.form" to CLAUDE_V2_FORM_JSON,
            "claude.edit" to CLAUDE_V2_EDIT_JSON,
            "cursor.form" to CURSOR_V2_FORM_JSON,
            "cursor.edit" to CURSOR_V2_EDIT_JSON,
            "copilot.form" to COPILOT_V2_FORM_JSON,
        )) {
            val parsed = form(json)
            assertTrue(parsed.blockingFieldKeys.isEmpty(), "$name: no unsupported required field")
            assertTrue(parsed.fields.none { it.kind == RuntimeFormV2FieldKind.Unsupported }, "$name: every field kind is known")
        }
    }

    @Test
    fun createFormsAreSubmittableFromDefaults() {
        for ((name, json, sources) in listOf(
            Triple("claude", CLAUDE_V2_FORM_JSON, claudeSources),
            Triple("cursor", CURSOR_V2_FORM_JSON, mapOf("model" to source(CURSOR_V2_FALLBACK_SOURCE_JSON))),
            Triple("copilot", COPILOT_V2_FORM_JSON, mapOf("model" to source(COPILOT_V2_LIVE_SOURCE_JSON))),
        )) {
            val parsed = form(json)
            assertEquals(emptyMap(), validateRuntimeFormV2(parsed, sources, initialRuntimeFormV2Values(parsed, sources)), name)
        }
    }

    @Test
    fun claudeApiUrlAndKeyShowOnlyForTheCustomProvider() {
        val values = initialRuntimeFormV2Values(claude, claudeSources)
        val apiUrl = claude.fields.single { it.key == "apiUrl" }
        val apiKey = claude.fields.single { it.key == "apiKey" }
        assertEquals(RuntimeFormV2FieldKind.Secret, apiKey.kind)
        assertNull(runtimeFormV2SourceStatus(claude.fields.single { it.key == "provider" }, claudeSources), "a static source has no status")
        assertFalse(isRuntimeFormV2FieldVisible(apiUrl, values, claudeSources))
        val custom = applyRuntimeFormV2Change(claude, claudeSources, values, "provider", JsonPrimitive("custom"))
        assertTrue(isRuntimeFormV2FieldVisible(apiUrl, custom, claudeSources))
        assertTrue(isRuntimeFormV2FieldVisible(apiKey, custom, claudeSources))
    }

    @Test
    fun claudeEditNeverPrefillsTheKeyAndSavesUnchangedWithItBlank() {
        val edit = form(CLAUDE_V2_EDIT_JSON)
        val values = initialRuntimeFormV2Values(edit, claudeSources)
        assertEquals("custom", values.text("provider"))
        assertEquals("", values.text("apiKey"), "the key is write-only: the edit values never carry it")
        assertEquals(emptyMap(), validateRuntimeFormV2(edit, claudeSources, values, editing = true))
        val submitted = runtimeFormV2Submission(edit, values, claudeSources)
        assertEquals(JsonPrimitive("https://gateway.example.test"), submitted["apiUrl"], "same URL + blank key keeps the stored key")
        assertEquals(JsonPrimitive(""), submitted["apiKey"], "a blank key is sent as \"\", which the server reads as keep")
    }

    @Test
    fun cursorEditPrefillsTheStoredModelAndCanBeSavedUnchanged() {
        val edit = form(CURSOR_V2_EDIT_JSON)
        val sources = mapOf("model" to source(CURSOR_V2_FALLBACK_SOURCE_JSON))
        assertEquals(emptyMap(), validateRuntimeFormV2(edit, sources, initialRuntimeFormV2Values(edit, sources), editing = true))
    }
}
