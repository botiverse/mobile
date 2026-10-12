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

/** Capability `option_source.status` against the server's own Codex/Grok fixtures (slock #8625). */
class RuntimeFormV2SourceStatusTest {
    private val codex = assertNotNull(parseRuntimeFormV2(CODEX_V2_FORM_JSON), "Codex (a+b+c) renders in v2")
    private val codexEdit = assertNotNull(parseRuntimeFormV2(CODEX_V2_EDIT_JSON))
    private fun sources(json: String) = mapOf("model" to assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(json))))
    private val live = sources(CODEX_V2_LIVE_SOURCE_JSON)
    private val fallback = sources(CODEX_V2_FALLBACK_SOURCE_JSON)
    private val unavailable = sources(CODEX_V2_UNAVAILABLE_SOURCE_JSON)
    private val model = codex.fields.single { it.key == "model" }

    @Test
    fun codexAndGrokNowRenderInV2() {
        assertTrue(RUNTIME_FORM_V2_CAPABILITY_SOURCE_STATUS in RUNTIME_FORM_V2_CLIENT_CAPABILITIES)
        assertNotNull(parseRuntimeFormV2(GROK_V2_FORM_JSON), "Grok (b+c) renders in v2")
        assertTrue(codex.blockingFieldKeys.isEmpty())
    }

    @Test
    fun statusIsReadAsSentAndOnlyANonLiveSourceReportsOne() {
        assertNull(runtimeFormV2SourceStatus(model, live))
        assertEquals(RuntimeFormV2SourceStatus(unavailable = false, reason = "missing_config", retryable = false), runtimeFormV2SourceStatus(model, fallback))
        assertEquals(RuntimeFormV2SourceStatus(unavailable = true, reason = "machine_offline", retryable = true), runtimeFormV2SourceStatus(model, unavailable))
        assertNull(runtimeFormV2SourceStatus(codex.fields.single { it.key == "reasoningEffort" }, unavailable), "a derived field follows its parent")
    }

    @Test
    fun anUnknownReasonIsKeptAndAnUnknownStatusIsIgnored() {
        val oddReason = sourceWith("reason" to JsonPrimitive("gpu_on_fire"))
        assertEquals("gpu_on_fire", runtimeFormV2SourceStatus(model, oddReason)?.reason, "clients must accept an unknown reason")
        val oddStatus = sourceWith("status" to JsonPrimitive("degraded"))
        assertNull(runtimeFormV2SourceStatus(model, oddStatus), "an unknown status is treated like a source without one")
        val stringly = sourceWith("retryable" to JsonPrimitive("true"))
        assertEquals(false, runtimeFormV2SourceStatus(model, stringly)?.retryable, "a string is not a boolean")
    }

    @Test
    fun aFallbackListIsUsableAndSubmittable() {
        val values = initialRuntimeFormV2Values(codex, fallback)
        assertEquals("gpt-6.1-sol", values.text("model"))
        assertEquals(emptyMap(), validateRuntimeFormV2(codex, fallback, values))
    }

    @Test
    fun anUnavailableRequiredFieldStaysVisibleAndBlocksSubmitEvenWithATypedValue() {
        assertTrue(isRuntimeFormV2FieldVisible(model, emptyMap(), unavailable))
        val typed = applyRuntimeFormV2Change(codex, unavailable, initialRuntimeFormV2Values(codex, unavailable), "model", JsonPrimitive("my-model"))
        assertEquals(RuntimeFormV2ErrorCode.SourceUnavailable, validateRuntimeFormV2(codex, unavailable, typed)["model"])
        val edit = initialRuntimeFormV2Values(codexEdit, unavailable)
        assertEquals(RuntimeFormV2ErrorCode.SourceUnavailable, validateRuntimeFormV2(codexEdit, unavailable, edit, editing = true)["model"])
    }

    @Test
    fun anUnavailableOptionalFieldIsHiddenAndItsStoredValueSurvivesTheSave() {
        val optionalEdit = assertNotNull(parseRuntimeFormV2(formJson(CODEX_V2_EDIT_JSON).set(listOf("dataSchema", "required"), JsonArray(emptyList()))))
        val optionalModel = optionalEdit.fields.single { it.key == "model" }
        val values = initialRuntimeFormV2Values(optionalEdit, unavailable)
        assertFalse(isRuntimeFormV2FieldVisible(optionalModel, values, unavailable))
        assertTrue(isRuntimeFormV2FieldVisible(optionalModel, values), "without sources only the visibility rules apply")
        assertNull(validateRuntimeFormV2(optionalEdit, unavailable, values, editing = true)["model"])
        val submitted = runtimeFormV2Submission(optionalEdit, values - "model", unavailable)
        assertEquals(JsonPrimitive("my-org/codex-custom"), submitted["model"], "the hidden field keeps the stored value")

        val optionalCreate = assertNotNull(parseRuntimeFormV2(formJson(CODEX_V2_FORM_JSON).set(listOf("dataSchema", "required"), JsonArray(emptyList()))))
        val created = runtimeFormV2Submission(optionalCreate, initialRuntimeFormV2Values(optionalCreate, unavailable), unavailable)
        assertFalse("model" in created, "on create a hidden field has no stored value and is omitted")
    }

    private fun sourceWith(vararg entries: Pair<String, JsonElement>): RuntimeFormV2Sources {
        val raw = JsonObject(formJson(CODEX_V2_UNAVAILABLE_SOURCE_JSON) + entries)
        return mapOf("model" to assertNotNull(parseRuntimeFormV2Source(raw)))
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
