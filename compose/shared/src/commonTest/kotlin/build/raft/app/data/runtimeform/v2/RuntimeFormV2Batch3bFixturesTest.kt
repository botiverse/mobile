package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Server batch 3b (slock #8637): Claude, Cursor and Copilot render on mobile from the server's exact bodies. */
class RuntimeFormV2Batch3bFixturesTest {
    private fun form(json: String) = assertNotNull(parseRuntimeFormV2(json), "parses without falling back to v1")
    private fun source(json: String) = assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(json)))

    private val claude = form(CLAUDE_V2_FORM_JSON)
    private val claudeSources = mapOf(
        "provider" to source(CLAUDE_V2_PROVIDER_SOURCE_JSON),
        "model" to source(CLAUDE_V2_FALLBACK_SOURCE_JSON),
        "authType" to source(CLAUDE_AUTH_TYPE_SOURCE_JSON)
    )

    @Test
    fun everyBatch3bFormParsesWithNothingBlocking() {
        for ((name, json) in listOf(
            "claude.form" to CLAUDE_V2_FORM_JSON,
            "claude.edit" to CLAUDE_V2_EDIT_JSON,
            "cursor.form" to CURSOR_V2_FORM_JSON,
            "cursor.edit" to CURSOR_V2_EDIT_JSON,
            "copilot.edit" to COPILOT_V2_EDIT_JSON,
        )) {
            val parsed = form(json)
            assertTrue(parsed.blockingFieldKeys.isEmpty(), "$name: no unsupported required field")
            assertTrue(parsed.fields.none { it.kind == RuntimeFormV2FieldKind.Unsupported }, "$name: every field kind is known")
        }
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
}
