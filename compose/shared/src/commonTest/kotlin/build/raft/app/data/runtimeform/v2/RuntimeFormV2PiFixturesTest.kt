package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Server batch 4 (slock #8647): Pi. "configured" uses the Computer's own models (typed allowed);
 * DeepSeek picks from its own list with an API key. Two model fields, one visible per provider.
 */
class RuntimeFormV2PiFixturesTest {
    private fun form(json: String) = assertNotNull(parseRuntimeFormV2(json), "parses without falling back to v1")
    private fun source(json: String) = assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(json)))

    private val pi = form(PI_V2_FORM_JSON)
    private val sources = mapOf(
        "provider" to source(PI_V2_PROVIDER_SOURCE_JSON),
        "model" to source(PI_V2_FALLBACK_SOURCE_JSON),
        "providerModel" to source(PI_V2_PROVIDER_MODEL_SOURCE_JSON)
    )
    private fun field(key: String) = pi.fields.single { it.key == key }

    @Test
    fun everyPiFormParsesWithNothingBlocking() {
        for ((name, json) in listOf("pi.form" to PI_V2_FORM_JSON, "pi.edit" to PI_V2_EDIT_JSON, "pi.edit.configured" to PI_V2_EDIT_CONFIGURED_JSON)) {
            val parsed = form(json)
            assertTrue(parsed.blockingFieldKeys.isEmpty(), "$name: no unsupported required field")
            assertTrue(parsed.fields.none { it.kind == RuntimeFormV2FieldKind.Unsupported }, "$name: every field kind is known")
        }
    }

    @Test
    fun configuredCreateIsSubmittableOnTheFallbackListAndSaysSo() {
        val values = initialRuntimeFormV2Values(pi, sources)
        assertEquals("configured", values.text("provider"))
        assertTrue(isRuntimeFormV2FieldVisible(field("model"), values, sources))
        assertFalse(isRuntimeFormV2FieldVisible(field("providerModel"), values, sources), "the other provider's required model is not checked")
        assertFalse(runtimeFormV2SourceStatus(field("model"), sources)!!.unavailable, "offline: a usable fallback, not a blocked field")
        assertEquals(emptyMap(), validateRuntimeFormV2(pi, sources, values))
    }

    @Test
    fun deepSeekShowsItsKeyAndListAndDerivesTheEffortFromThatList() {
        val values = applyRuntimeFormV2Change(pi, sources, initialRuntimeFormV2Values(pi, sources), "provider", JsonPrimitive("deepseek"))
        assertTrue(isRuntimeFormV2FieldVisible(field("apiKey"), values, sources))
        assertFalse(isRuntimeFormV2FieldVisible(field("model"), values, sources))
        assertEquals("deepseek/deepseek-v4-pro", values.text("providerModel"))
        val model = runtimeFormV2Choices(field("providerModel"), sources, values) as RuntimeFormV2Choices.Select
        assertFalse(model.allowCustom, "DeepSeek models only come from its list")
        val effort = runtimeFormV2Choices(field("providerReasoningEffort"), sources, values) as RuntimeFormV2Choices.Select
        assertEquals(listOf("low", "medium", "high", "xhigh"), effort.options.map { it.value })
        assertEquals(RuntimeFormV2ErrorCode.NotAChoice, validateRuntimeFormV2(pi, sources, values + ("providerModel" to JsonPrimitive("typed")))["providerModel"])
    }

    @Test
    fun editsPrefillAndCanBeSavedUnchanged() {
        for ((name, json) in listOf("deepseek" to PI_V2_EDIT_JSON, "configured" to PI_V2_EDIT_CONFIGURED_JSON)) {
            val edit = form(json)
            val values = initialRuntimeFormV2Values(edit, sources)
            assertEquals("", values.text("apiKey"), "$name: the key is write-only")
            assertEquals(emptyMap(), validateRuntimeFormV2(edit, sources, values, editing = true), name)
        }
        val configured = initialRuntimeFormV2Values(form(PI_V2_EDIT_CONFIGURED_JSON), sources)
        assertEquals("my-org/pi-custom", configured.text("model"), "a typed model stays typed")
    }
}
