package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Capability `choice.labels` against the server's own fixture (slock #8625 `choices.*`). */
class RuntimeFormV2ChoiceLabelsTest {
    private val form = assertNotNull(parseRuntimeFormV2(CHOICES_V2_FORM_JSON), "a form declaring choice.labels renders in v2")
    private val sources = mapOf(
        "tier" to assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(CHOICES_V2_SOURCE_JSON)))
    )
    private fun field(key: String) = form.fields.single { it.key == key }
    private fun options(key: String, values: RuntimeFormV2Values = initialRuntimeFormV2Values(form, sources)) =
        assertNotNull(runtimeFormV2Choices(field(key), sources, values) as? RuntimeFormV2Choices.Select).options

    @Test
    fun choiceLabelsIsAcceptedAndStillOnlyItsOwnCapability() {
        assertTrue(RUNTIME_FORM_V2_CAPABILITY_CHOICE_LABELS in RUNTIME_FORM_V2_CLIENT_CAPABILITIES)
        // The other two stay gated until implemented: a form needing them still falls back to v1.
        assertNull(parseRuntimeFormV2(CODEX_LIKE_REQUIRES_CUSTOM_VALUE))
    }

    @Test
    fun labelPriorityIsChoicesThenOptionLabelThenRawValue() {
        val tier = options("tier")
        assertEquals("Fast tier", tier.single { it.value == "fast" }.label, "choices[value].label wins over the option's label")
        assertEquals(
            "Steady (no choices key: the option's own label)",
            tier.single { it.value == "steady" }.label,
            "no choices key: the option's own label"
        )
        assertEquals("Lower latency", tier.single { it.value == "fast" }.description)
        assertNull(tier.single { it.value == "steady" }.description, "descriptions never come from the option itself")
        assertTrue(tier.none { it.value == "retired" }, "a choices key matching no option adds nothing")
    }

    @Test
    fun derivedSelectChoicesAreLabelledToo() {
        val effort = options("effort")
        assertEquals(listOf("low", "high", "max"), effort.map { it.value })
        assertEquals("Low", effort[0].label)
        assertEquals("Fast responses with lighter reasoning", effort[0].description)
        assertEquals("High", effort[1].label)
        assertEquals("max", effort[2].label, "no copy and no option label: the raw value")
    }

    @Test
    fun malformedChoiceCopyIsIgnoredEntryByEntry() {
        val tweaked = formJson(CHOICES_V2_FORM_JSON).set(
            listOf("uiSchema", "localization", "tier", "choices"),
            Json.parseToJsonElement(
                """{"fast":{"label":7},"steady":{"label":"Steady","description":3,"extra":true},"x":"nope"}"""
            )
        )
        val parsed = assertNotNull(parseRuntimeFormV2(tweaked))
        val tier = assertNotNull(
            runtimeFormV2Choices(parsed.fields.single { it.key == "tier" }, sources, initialRuntimeFormV2Values(parsed, sources))
                as? RuntimeFormV2Choices.Select
        ).options
        assertEquals("fast (option label, overridden by choices)", tier.single { it.value == "fast" }.label, "non-string label: entry ignored")
        assertEquals("Steady", tier.single { it.value == "steady" }.label)
        assertNull(tier.single { it.value == "steady" }.description, "non-string description is dropped")
    }

    private val CODEX_LIKE_REQUIRES_CUSTOM_VALUE: JsonObject =
        formJson(CHOICES_V2_FORM_JSON).set(
            listOf("requiredClientCapabilities"),
            buildJsonArray { add(JsonPrimitive("choice.labels")); add(JsonPrimitive("select.custom_value")) }
        )

    private fun formJson(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun JsonObject.set(path: List<String>, value: JsonElement): JsonObject {
        val head = path.first()
        return if (path.size == 1) {
            JsonObject(this + (head to value))
        } else {
            JsonObject(this + (head to (this[head] as? JsonObject ?: JsonObject(emptyMap())).set(path.drop(1), value)))
        }
    }
}
