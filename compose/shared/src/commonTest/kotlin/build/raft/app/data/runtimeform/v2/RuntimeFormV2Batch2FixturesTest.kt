package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Server batch 2 (slock #8612) runtimes render on mobile from the server's exact bodies:
 * OpenCode create/edit, retired Kimi CLI / Gemini / Antigravity edit, and the built-in
 * option list the server returns when probing did not succeed.
 */
class RuntimeFormV2Batch2FixturesTest {
    private fun form(json: String) = assertNotNull(parseRuntimeFormV2(json), "parses without falling back to v1")
    private fun source(json: String) = assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(json)))

    @Test
    fun everyBatch2FormParsesWithNothingBlockingAndNoCapabilityGate() {
        for ((name, json) in listOf(
            "opencode.form" to OPENCODE_V2_FORM_JSON,
            "opencode.edit" to OPENCODE_V2_EDIT_JSON,
            "kimi.edit" to KIMI_CLI_V2_EDIT_JSON,
            "gemini.edit" to GEMINI_V2_EDIT_JSON,
            "antigravity.edit" to ANTIGRAVITY_V2_EDIT_JSON,
        )) {
            val parsed = form(json)
            assertTrue(parsed.blockingFieldKeys.isEmpty(), "$name: no unsupported required field")
            assertTrue(parsed.fields.none { it.kind == RuntimeFormV2FieldKind.Unsupported }, "$name: every field kind is known")
        }
    }

    @Test
    fun fallbackOptionSourcesAreOrdinarySelectsWithABuiltInList() {
        for ((name, json) in listOf(
            "opencode fallback" to OPENCODE_V2_FALLBACK_SOURCE_JSON,
            "kimi fallback" to KIMI_CLI_V2_FALLBACK_SOURCE_JSON,
            "gemini" to GEMINI_V2_SOURCE_JSON,
        )) {
            val parsed = source(json)
            assertEquals(RuntimeFormV2SourceKind.Select, parsed.kind, name)
            assertTrue(!parsed.options.isNullOrEmpty(), "$name: a usable list, not an error")
            assertTrue(parsed.options!!.any { it.value == parsed.defaultValue }, "$name: the default is one of the options")
        }
    }

    @Test
    fun openCodeCreateIsSubmittableFromDefaultsOnTheFallbackList() {
        val opencode = form(OPENCODE_V2_FORM_JSON)
        val sources = mapOf("model" to source(OPENCODE_V2_FALLBACK_SOURCE_JSON))
        val values = initialRuntimeFormV2Values(opencode, sources)
        assertEquals(emptyMap(), validateRuntimeFormV2(opencode, sources, values))
    }

    @Test
    fun editFormsPrefillTheStoredValuesAndCanBeSavedUnchanged() {
        for ((name, json, sourceJson) in listOf(
            Triple("opencode.edit", OPENCODE_V2_EDIT_JSON, OPENCODE_V2_FALLBACK_SOURCE_JSON),
            Triple("kimi.edit", KIMI_CLI_V2_EDIT_JSON, KIMI_CLI_V2_FALLBACK_SOURCE_JSON),
            Triple("gemini.edit", GEMINI_V2_EDIT_JSON, GEMINI_V2_SOURCE_JSON),
        )) {
            val edit = form(json)
            val sources = mapOf("model" to source(sourceJson))
            val values = initialRuntimeFormV2Values(edit, sources)
            assertEquals(
                emptyMap(),
                validateRuntimeFormV2(edit, sources, values, editing = true),
                "$name: the stored model must be offered, or the user cannot save without re-picking"
            )
        }
    }

}
