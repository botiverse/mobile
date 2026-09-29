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

/** Capability `select.custom_value`: a select that also takes a typed value (e.g. a Codex model id). */
class RuntimeFormV2CustomValueTest {
    private val form = assertNotNull(
        parseRuntimeFormV2(
            formJson(CHOICES_V2_FORM_JSON).set(
                listOf("requiredClientCapabilities"),
                buildJsonArray { add(JsonPrimitive("choice.labels")); add(JsonPrimitive("select.custom_value")) }
            )
        ),
        "a form declaring choice.labels + select.custom_value renders in v2"
    )
    private val listedOnly = source(customValueAllowed = null)
    private val customAllowed = source(customValueAllowed = true)

    private fun source(customValueAllowed: Boolean?): RuntimeFormV2Sources {
        val raw = formJson(CHOICES_V2_SOURCE_JSON).let {
            if (customValueAllowed == null) it else it.set(listOf("customValueAllowed"), JsonPrimitive(customValueAllowed))
        }
        return mapOf("tier" to assertNotNull(parseRuntimeFormV2Source(raw)))
    }

    private fun typed(sources: RuntimeFormV2Sources, tier: String): RuntimeFormV2Values =
        applyRuntimeFormV2Change(form, sources, initialRuntimeFormV2Values(form, sources), "tier", JsonPrimitive(tier))

    @Test
    fun customValueIsAcceptedButAnUnknownCapabilityIsStillGated() {
        assertTrue(RUNTIME_FORM_V2_CAPABILITY_CUSTOM_VALUE in RUNTIME_FORM_V2_CLIENT_CAPABILITIES)
        val needsUnknown = formJson(CHOICES_V2_FORM_JSON).set(
            listOf("requiredClientCapabilities"),
            buildJsonArray { add(JsonPrimitive("select.custom_value")); add(JsonPrimitive("x.not_a_real_capability")) }
        )
        assertNull(parseRuntimeFormV2(needsUnknown), "a capability this client lacks: fall back to v1")
    }

    @Test
    fun customValueAllowedIsReadOnlyFromABooleanAndOnlyWidensPlainSelects() {
        assertEquals(true, customAllowed.getValue("tier").customValueAllowed)
        assertNull(listedOnly.getValue("tier").customValueAllowed)
        val stringly = formJson(CHOICES_V2_SOURCE_JSON).set(listOf("customValueAllowed"), JsonPrimitive("true"))
        assertNull(parseRuntimeFormV2Source(stringly)?.customValueAllowed, "a string is not a boolean")
        val tier = form.fields.single { it.key == "tier" }
        assertEquals(true, (runtimeFormV2Choices(tier, customAllowed, emptyMap()) as RuntimeFormV2Choices.Select).allowCustom)
        val effort = form.fields.single { it.key == "effort" }
        assertEquals(false, (runtimeFormV2Choices(effort, customAllowed, typed(customAllowed, "fast")) as RuntimeFormV2Choices.Select).allowCustom)
    }

    @Test
    fun aTypedValueIsValidOnlyWhenTheSourceAllowsIt() {
        assertEquals(emptyMap(), validateRuntimeFormV2(form, customAllowed, typed(customAllowed, "my-own-tier")))
        assertEquals(
            RuntimeFormV2ErrorCode.NotAChoice,
            validateRuntimeFormV2(form, listedOnly, typed(listedOnly, "my-own-tier"))["tier"]
        )
    }

    @Test
    fun aTypedModelHasNoEffortMenuAndItsEffortIsLeftToTheServer() {
        // artin 09-29: a typed custom model shows no reasoning effort (aligned with web).
        val values = typed(customAllowed, "my-own-tier")
        val effort = form.fields.single { it.key == "effort" }
        assertTrue((runtimeFormV2Choices(effort, customAllowed, values) as RuntimeFormV2Choices.Select).options.isEmpty())
        assertEquals("", values.text("effort"), "switching to a typed model resets the derived effort")
        val staleEffort = values + ("effort" to JsonPrimitive("low"))
        assertNull(validateRuntimeFormV2(form, customAllowed, staleEffort)["effort"], "an unconstrained derived value is not flagged")
    }

    @Test
    fun anEditPrefillOfATypedValueIsShownAsTypedNotAsNotAChoice() {
        val edit = form.copy(values = JsonObject(mapOf("tier" to JsonPrimitive("typed-earlier"))))
        val values = initialRuntimeFormV2Values(edit, customAllowed)
        assertEquals("typed-earlier", values.text("tier"))
        assertNull(validateRuntimeFormV2(edit, customAllowed, values, editing = true)["tier"])
    }

    @Test
    fun aReprobeNeverReplacesATypedValue() {
        val values = typed(customAllowed, "my-own-tier")
        assertEquals(values, runtimeFormV2ValuesAfterSourceReload(form, customAllowed, values, "tier"))
    }

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
