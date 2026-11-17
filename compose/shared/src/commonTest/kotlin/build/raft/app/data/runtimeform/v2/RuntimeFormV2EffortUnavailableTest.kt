package build.raft.app.data.runtimeform.v2

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reasoning effort (derived_select over the selected model's `supportedReasoningEfforts`) whose model offers
 * no level stays visible when the Computer says why (slock #15274, triage #492): `reasoningEffortNone` (the
 * model takes none) or `reasoningEffortUnavailableReason` (its levels could not be read). An empty list with
 * neither is "not reported" (an older Computer): hidden, as before. Port of slock v2State
 * `runtimeFormV2EffortUnavailable` and the web runtimeFormV2EffortStates behaviour test.
 */
class RuntimeFormV2EffortUnavailableTest {
    private val codex = assertNotNull(parseRuntimeFormV2(CODEX_V2_FORM_JSON))
    private val effort = codex.fields.single { it.key == "reasoningEffort" }

    /** The model options exactly as slock's server test pins them (runtimeFormV2Batch3a.test.ts, slock 68f16efa). */
    private val sources = mapOf(
        "model" to assertNotNull(
            parseRuntimeFormV2Source(
                Json.parseToJsonElement(
                    """
                    {"sourceId":"model","kind":"select","pointer":"/model","options":[
                      {"value":"agy-levels","label":"Levels","supportedReasoningEfforts":["low","high"],"defaultReasoningEffort":"high"},
                      {"value":"agy-none","label":"None","supportedReasoningEfforts":[],"reasoningEffortNone":true},
                      {"value":"agy-unread","label":"Unread","supportedReasoningEfforts":[],"reasoningEffortUnavailableReason":"Native catalog did not expose this model's <b>effort</b> menu."},
                      {"value":"agy-old","label":"Old","supportedReasoningEfforts":[]},
                      {"value":"agy-blank","label":"Blank","supportedReasoningEfforts":[],"reasoningEffortUnavailableReason":"  ","reasoningEffortNone":true}
                    ]}
                    """.trimIndent()
                )
            )
        )
    )

    private fun values(model: String): RuntimeFormV2Values = mapOf("model" to JsonPrimitive(model), "reasoningEffort" to JsonPrimitive(""))

    @Test
    fun theGeneratedContractDecodesBothFieldsAndTheirAbsence() {
        val json = Json { ignoreUnknownKeys = true }
        val unread = json.decodeFromString<RuntimeFormV2Option>(
            """{"value":"agy-unread","label":"Unread","supportedReasoningEfforts":[],"reasoningEffortUnavailableReason":"Native catalog did not expose this model's effort menu."}"""
        )
        assertEquals("Native catalog did not expose this model's effort menu.", unread.reasoningEffortUnavailableReason)
        assertNull(unread.reasoningEffortNone)
        val none = json.decodeFromString<RuntimeFormV2Option>("""{"value":"agy-none","label":"None","supportedReasoningEfforts":[],"reasoningEffortNone":true}""")
        assertEquals(true, none.reasoningEffortNone)
        assertNull(none.reasoningEffortUnavailableReason)
        val old = json.decodeFromString<RuntimeFormV2Option>("""{"value":"agy-old","label":"Old","supportedReasoningEfforts":[]}""")
        assertNull(old.reasoningEffortNone)
        assertNull(old.reasoningEffortUnavailableReason)
    }

    @Test
    fun theOptionSourceKeepsBothFieldsOnTheChoice() {
        val options = assertNotNull(sources.getValue("model").options).associateBy { it.value }
        assertTrue(options.getValue("agy-none").reasoningEffortNone)
        assertNull(options.getValue("agy-none").reasoningEffortUnavailableReason)
        assertEquals("Native catalog did not expose this model's <b>effort</b> menu.", options.getValue("agy-unread").reasoningEffortUnavailableReason)
        assertEquals(false, options.getValue("agy-old").reasoningEffortNone)
        assertNull(options.getValue("agy-old").reasoningEffortUnavailableReason)
    }

    @Test
    fun aModelWithLevelsOffersThePickerAndNoReason() {
        assertNull(runtimeFormV2EffortUnavailable(effort, sources, values("agy-levels")))
        val choices = runtimeFormV2Choices(effort, sources, values("agy-levels")) as RuntimeFormV2Choices.Select
        assertEquals(listOf("low", "high"), choices.options.map { it.value })
    }

    @Test
    fun aModelThatTakesNoEffortIsShownNotAdjustable() {
        assertEquals(RuntimeFormV2EffortUnavailable.None, runtimeFormV2EffortUnavailable(effort, sources, values("agy-none")))
        assertTrue(isRuntimeFormV2FieldVisible(effort, values("agy-none"), sources))
    }

    @Test
    fun unreadableLevelsCarryTheRuntimesReasonAsIs() {
        assertEquals(
            RuntimeFormV2EffortUnavailable.Unreadable("Native catalog did not expose this model's <b>effort</b> menu."),
            runtimeFormV2EffortUnavailable(effort, sources, values("agy-unread"))
        )
    }

    @Test
    fun aBlankReasonFallsBackToNoneLikeTheWeb() {
        assertEquals(RuntimeFormV2EffortUnavailable.None, runtimeFormV2EffortUnavailable(effort, sources, values("agy-blank")))
    }

    @Test
    fun anUnexplainedEmptyListStaysHidden() {
        assertNull(runtimeFormV2EffortUnavailable(effort, sources, values("agy-old")), "an older Computer: hidden, as before")
        assertNull(runtimeFormV2EffortUnavailable(effort, sources, values("typed-custom-model")), "no option describes the model")
        assertNull(runtimeFormV2EffortUnavailable(effort, emptyMap(), values("agy-none")), "no source loaded")
    }

    @Test
    fun onlyAnEffortDerivedSelectAsks() {
        val model = codex.fields.single { it.key == "model" }
        assertNull(runtimeFormV2EffortUnavailable(model, sources, values("agy-none")))
        val other = effort.copy(derivedFrom = effort.derivedFrom?.copy(attribute = "somethingElse"))
        assertNull(runtimeFormV2EffortUnavailable(other, sources, values("agy-none")))
    }

    @Test
    fun aStoredEffortIsKeptAndDoesNotBlockTheSave() {
        val stored = values("agy-none") + ("reasoningEffort" to JsonPrimitive("high"))
        assertEquals(emptyMap(), validateRuntimeFormV2(codex, sources, stored).filterKeys { it == "reasoningEffort" })
    }
}
