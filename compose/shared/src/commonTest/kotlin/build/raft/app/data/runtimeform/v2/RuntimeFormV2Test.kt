package build.raft.app.data.runtimeform.v2

import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.CreateAgentBody
import build.raft.app.data.buildCreateAgentV2Body
import build.raft.app.data.buildSharedAgentProfileUpdateBody
import build.raft.app.data.AgentResetMode
import build.raft.app.model.MemberId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of slock `packages/runtime-form/src/v2.test.ts` and `v2State.test.ts`: the
 * mobile parser and state rules must agree with the reference client case by case.
 */
class RuntimeFormV2Test {
    private fun formJson(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject
    private val pi = parseRuntimeFormV2(BUILTIN_PI_V2_FORM_JSON)!!
    private val kimi = parseRuntimeFormV2(KIMI_SDK_V2_FORM_JSON)!!
    private fun ParsedRuntimeFormV2.field(key: String) = fields.first { it.key == key }

    private fun choice(value: String, label: String, vararg attributes: Pair<String, JsonElement>) =
        RuntimeFormV2Choice(
            value,
            label,
            JsonObject(mapOf("value" to JsonPrimitive(value), "label" to JsonPrimitive(label)) + attributes)
        )

    private val piSources: RuntimeFormV2Sources = mapOf(
        "provider" to RuntimeFormV2Source(
            sourceId = "provider",
            kind = RuntimeFormV2SourceKind.Select,
            pointer = "/providerId",
            options = listOf(choice("deepseek", "DeepSeek"), choice("openai-compatible", "OpenAI compatible")),
            defaultValue = "deepseek"
        ),
        "model" to RuntimeFormV2Source(
            sourceId = "model",
            kind = RuntimeFormV2SourceKind.DependentSelect,
            pointer = "/model",
            dependsOn = "/providerId",
            optionsByValue = mapOf(
                "deepseek" to listOf(choice("deepseek/v4", "V4"), choice("deepseek/flash", "Flash")),
                "openai-compatible" to emptyList()
            ),
            defaultValueByValue = mapOf("deepseek" to "deepseek/v4"),
            customValueAllowedByValue = mapOf("deepseek" to false, "openai-compatible" to true)
        )
    )
    private val kimiSources: RuntimeFormV2Sources = mapOf(
        "model" to RuntimeFormV2Source(
            sourceId = "model",
            kind = RuntimeFormV2SourceKind.Select,
            pointer = "/model",
            options = listOf(
                choice(
                    "k2", "K2",
                    "supportedReasoningEfforts" to buildJsonArray { add(JsonPrimitive("low")); add(JsonPrimitive("high")) },
                    "defaultReasoningEffort" to JsonPrimitive("high")
                ),
                choice("k1", "K1")
            ),
            defaultValue = "k2"
        )
    )

    // ── v2.test.ts ──

    @Test
    fun builtInPiRendersByFieldKindInOrderWithAdvancedAndVisibility() {
        assertEquals(
            listOf(
                "providerId" to RuntimeFormV2FieldKind.Select,
                "apiKey" to RuntimeFormV2FieldKind.Secret,
                "baseUrl" to RuntimeFormV2FieldKind.Url,
                "supportsImageInput" to RuntimeFormV2FieldKind.Boolean,
                "model" to RuntimeFormV2FieldKind.DependentSelect,
                "loadLocalPlugins" to RuntimeFormV2FieldKind.Boolean,
                "envVars" to RuntimeFormV2FieldKind.StringMap
            ),
            pi.fields.map { it.key to it.kind }
        )
        assertEquals("providerId", pi.field("model").dependsOn)
        assertEquals("Load local Pi extensions", pi.field("loadLocalPlugins").label)
        assertEquals(listOf("loadLocalPlugins", "envVars"), pi.fields.filter { it.advanced }.map { it.key })
        assertEquals(listOf("providerId", "apiKey", "model"), pi.fields.filter { it.required }.map { it.key })
        assertEquals("providerId", pi.field("baseUrl").visibleWhen.first().key)
        assertEquals(emptyList(), pi.blockingFieldKeys)
    }

    @Test
    fun kimiRendersModelAsSelectAndEffortAsDerivedChoices() {
        assertEquals(
            listOf(
                "model" to RuntimeFormV2FieldKind.Select,
                "reasoningEffort" to RuntimeFormV2FieldKind.DerivedSelect,
                "envVars" to RuntimeFormV2FieldKind.StringMap
            ),
            kimi.fields.map { it.key to it.kind }
        )
        assertEquals(
            RuntimeFormV2DerivedFrom("model", "supportedReasoningEfforts", "defaultReasoningEffort"),
            kimi.field("reasoningEffort").derivedFrom
        )
    }

    @Test
    fun aDerivedFieldNamingAMissingFieldFallsBackToText() {
        val form = formJson(KIMI_SDK_V2_FORM_JSON).set(
            listOf("dataSchema", "properties", "reasoningEffort", "x-optionsFrom"),
            buildJsonObject { put("field", "gone"); put("attribute", "x") }
        )
        assertEquals(RuntimeFormV2FieldKind.Text, parseRuntimeFormV2(form)!!.field("reasoningEffort").kind)
    }

    @Test
    fun toleratesEverythingAServerIsAllowedToAdd() {
        val form = formJson(BUILTIN_PI_V2_FORM_JSON)
            .set(listOf("somethingNew"), buildJsonObject { put("nested", true) })
            .set(listOf("uiSchema", "somethingNew"), buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) })
            .set(listOf("dataSchema", "properties", "apiKey", "somethingNew"), JsonPrimitive("x"))
            .set(
                listOf("dataSchema", "properties", "newOptionalFlag"),
                buildJsonObject { put("type", "boolean"); put("title", "New flag") }
            )
            .set(
                listOf("dataSchema", "properties", "newWidget"),
                buildJsonObject { put("type", "color"); put("title", "Colour") }
            )
            // A JSON Schema shape the generated Kotlin classes cannot decode must not drop the form.
            .set(
                listOf("dataSchema", "properties", "strictMap"),
                buildJsonObject { put("type", "object"); put("additionalProperties", false) }
            )
            .set(listOf("uiSchema", "localization", "providerId", "label"), JsonPrimitive("Model provider"))
            .set(listOf("uiSchema", "order"), buildJsonArray { add(JsonPrimitive("model")); add(JsonPrimitive("providerId")) })

        val parsed = assertNotNull(parseRuntimeFormV2(form), "unknown additions must not reject the form")
        val keys = parsed.fields.map { it.key }
        assertEquals(listOf("model", "providerId"), keys.take(2), "declared order first")
        assertTrue("newOptionalFlag" in keys && "apiKey" in keys, "undeclared fields are appended, not dropped")
        assertEquals(RuntimeFormV2FieldKind.Unsupported, parsed.field("newWidget").kind)
        assertEquals(RuntimeFormV2FieldKind.Unsupported, parsed.field("strictMap").kind)
        assertEquals(emptyList(), parsed.blockingFieldKeys, "an optional unknown kind does not block")
        assertEquals("Model provider", parsed.field("providerId").label)
    }

    @Test
    fun aRequiredFieldOfAnUnknownKindBlocksSavingButStillYieldsTheForm() {
        val base = formJson(KIMI_SDK_V2_FORM_JSON)
        val required = (base["dataSchema"]!!.jsonObject["required"] as JsonArray) + JsonPrimitive("region")
        val form = base
            .set(listOf("dataSchema", "properties", "region"), buildJsonObject { put("type", "geo"); put("title", "Region") })
            .set(listOf("dataSchema", "required"), JsonArray(required))
        val parsed = assertNotNull(parseRuntimeFormV2(form))
        assertEquals(listOf("region"), parsed.blockingFieldKeys)
        assertEquals(4, parsed.fields.size)
        val values = initialRuntimeFormV2Values(parsed, kimiSources)
        assertEquals(RuntimeFormV2ErrorCode.UnsupportedRequired, validateRuntimeFormV2(parsed, kimiSources, values)["region"])
    }

    @Test
    fun labelsFallBackToTheTitleThenTheKey() {
        val form = formJson(KIMI_SDK_V2_FORM_JSON)
            .remove(listOf("uiSchema", "localization", "envVars"))
            .set(listOf("dataSchema", "properties", "bare"), buildJsonObject { put("type", "string") })
        val parsed = parseRuntimeFormV2(form)!!
        assertEquals("Environment Variables", parsed.field("envVars").label)
        assertEquals("bare", parsed.field("bare").label)
    }

    @Test
    fun onlyABodyThatIsNotAV2FormIsRejected() {
        val v1 = formJson(BUILTIN_PI_V2_FORM_JSON).set(listOf("protocolVersion"), JsonPrimitive(1))
        assertNull(parseRuntimeFormV2(v1), "v1 body")
        assertNull(parseRuntimeFormV2(buildJsonObject { put("protocolVersion", 2) }), "no dataSchema")
        assertNull(parseRuntimeFormV2("\"nope\""))
        assertNull(parseRuntimeFormV2("not json"))
    }

    @Test
    fun theGeneratedContractClassesDecodeTheReleasedForms() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(RuntimeFormV2Definition.serializer(), BUILTIN_PI_V2_FORM_JSON)
        assertEquals(RUNTIME_FORM_V2_PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals("provider", decoded.dataSchema.properties["providerId"]?.optionSource)
        assertNotNull(json.decodeFromString(RuntimeFormV2Definition.serializer(), KIMI_SDK_V2_FORM_JSON).dataSchema)
    }

    // ── v2State.test.ts ──

    @Test
    fun initialValuesFollowSourcesParentsFirstThenDependentsAndDerivedChoices() {
        val values = initialRuntimeFormV2Values(pi, piSources)
        assertEquals("deepseek", values.text("providerId"))
        assertEquals("deepseek/v4", values.text("model"))
        assertEquals(JsonPrimitive(false), values["loadLocalPlugins"])
        assertEquals(JsonObject(emptyMap()), values["envVars"])
        assertEquals("high", initialRuntimeFormV2Values(kimi, kimiSources).text("reasoningEffort"))
    }

    @Test
    fun changingAParentResetsWhatDependsOnItAndVisibilityFollows() {
        var values = initialRuntimeFormV2Values(pi, piSources)
        assertFalse(isRuntimeFormV2FieldVisible(pi.field("baseUrl"), values))
        values = applyRuntimeFormV2Change(pi, piSources, values, "providerId", JsonPrimitive("openai-compatible"))
        assertEquals("", values.text("model"), "a gateway starts with an empty custom model")
        assertTrue(isRuntimeFormV2FieldVisible(pi.field("baseUrl"), values))
        assertEquals(RuntimeFormV2Choices.FreeText, runtimeFormV2Choices(pi.field("model"), piSources, values))

        var kimiValues = initialRuntimeFormV2Values(kimi, kimiSources)
        kimiValues = applyRuntimeFormV2Change(kimi, kimiSources, kimiValues, "model", JsonPrimitive("k1"))
        assertEquals("", kimiValues.text("reasoningEffort"), "a model without an effort menu falls back to the default")
        assertEquals(
            RuntimeFormV2Choices.Select(emptyList(), allowEmpty = true),
            runtimeFormV2Choices(kimi.field("reasoningEffort"), kimiSources, kimiValues)
        )
    }

    @Test
    fun validationCoversRequiredUrlAndChoiceMembershipOnVisibleFieldsOnly() {
        var values = initialRuntimeFormV2Values(pi, piSources)
        assertEquals(mapOf("apiKey" to RuntimeFormV2ErrorCode.Required), validateRuntimeFormV2(pi, piSources, values))
        values = values + mapOf("apiKey" to JsonPrimitive("k"), "model" to JsonPrimitive("not-offered"))
        assertEquals(mapOf("model" to RuntimeFormV2ErrorCode.NotAChoice), validateRuntimeFormV2(pi, piSources, values))
        values = applyRuntimeFormV2Change(
            pi, piSources, values + ("model" to JsonPrimitive("deepseek/v4")), "providerId", JsonPrimitive("openai-compatible")
        )
        values = values + mapOf("model" to JsonPrimitive("any/model"), "baseUrl" to JsonPrimitive("ftp://x"))
        assertEquals(mapOf("baseUrl" to RuntimeFormV2ErrorCode.InvalidUrl), validateRuntimeFormV2(pi, piSources, values))
    }

    @Test
    fun theSubmissionCarriesVisibleFieldsOnlyTrimmedAndAnEmptyDerivedChoiceAsNull() {
        val values = initialRuntimeFormV2Values(pi, piSources) +
            mapOf("apiKey" to JsonPrimitive(" k "), "baseUrl" to JsonPrimitive("https://hidden.example"))
        val submitted = runtimeFormV2Submission(pi, values)
        assertEquals(JsonPrimitive("k"), submitted["apiKey"])
        assertFalse("baseUrl" in submitted, "a hidden field is not submitted")
        val kimiSubmitted = runtimeFormV2Submission(
            kimi,
            initialRuntimeFormV2Values(kimi, kimiSources) + ("reasoningEffort" to JsonPrimitive(""))
        )
        assertEquals(JsonNull, kimiSubmitted["reasoningEffort"])
    }

    @Test
    fun editStartsFromStoredValuesAndDependentsFollowTheStoredParent() {
        val editForm = pi.copy(
            values = buildJsonObject {
                put("providerId", "deepseek")
                put("model", "deepseek/flash")
                put("loadLocalPlugins", true)
                put("envVars", buildJsonObject { put("A", "1") })
                put("supportsImageInput", "not-a-boolean")
            }
        )
        val values = initialRuntimeFormV2Values(editForm, piSources)
        assertEquals("deepseek/flash", values.text("model"), "stored choice kept, not reset to the default")
        assertEquals(JsonPrimitive(true), values["loadLocalPlugins"])
        assertEquals(buildJsonObject { put("A", "1") }, values["envVars"])
        assertEquals(JsonPrimitive(false), values["supportsImageInput"], "a stored value of the wrong kind falls back")
        assertEquals("", values.text("apiKey"), "writeOnly fields start blank (blank on edit keeps the stored secret)")
    }

    @Test
    fun onEditABlankSecretIsValidOnCreateItIsRequired() {
        val values = initialRuntimeFormV2Values(pi, piSources)
        assertEquals(mapOf("apiKey" to RuntimeFormV2ErrorCode.Required), validateRuntimeFormV2(pi, piSources, values))
        assertEquals(emptyMap(), validateRuntimeFormV2(pi, piSources, values, editing = true))
    }

    // ── mobile wire ──

    @Test
    fun optionSourcesParseTolerantlyAndKeepEveryOptionAttribute() {
        val source = assertNotNull(
            parseRuntimeFormV2Source(
                Json.parseToJsonElement(
                    """{"protocolVersion":1,"sourceId":"model","kind":"select","pointer":"/model","future":[1],""" +
                        """"options":[{"value":"k2","label":"K2","supportedReasoningEfforts":["low"],"newAttr":{"x":1}},{"label":"no value"}],""" +
                        """"defaultValue":"k2"}"""
                )
            )
        )
        assertEquals(listOf("k2"), source.options?.map { it.value }, "an option without a value is skipped")
        assertEquals(JsonArray(listOf(JsonPrimitive("low"))), source.options?.first()?.attributes?.get("supportedReasoningEfforts"))
        assertNull(parseRuntimeFormV2Source(buildJsonObject { put("sourceId", "x"); put("kind", "radio"); put("pointer", "/x") }))
    }

    @Test
    fun createBodyCarriesTheV2RefAndFieldValuesInsteadOfARuntimeConfig() {
        val submit = RuntimeFormV2SubmitBody("builtin", buildJsonObject { put("providerId", "deepseek"); put("apiKey", "k") })
        val body = Json.parseToJsonElement(
            buildCreateAgentV2Body(
                CreateAgentBody(name = "pi", runtime = "builtin", machineId = "m1", avatarUrl = "pixel:mug"),
                submit
            )
        ).jsonObject
        assertEquals(buildJsonObject { put("protocolVersion", 2); put("runtimeId", "builtin") }, body["formDefinitionRef"])
        assertEquals(submit.formValues, body["formValues"])
        assertEquals(JsonPrimitive("m1"), body["machineId"])
        assertFalse("runtimeConfig" in body)
        assertFalse(body.values.any { it == JsonNull }, "absent optional fields are omitted, never null")
    }

    @Test
    fun editBodySendsOnlyTheV2RefFieldValuesAndRestartMode() {
        val submit = RuntimeFormV2SubmitBody("builtin", buildJsonObject { put("apiKey", "") })
        val body = Json.parseToJsonElement(
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("a1"),
                    displayName = "pi",
                    description = null,
                    runtimeResetMode = AgentResetMode.Restart,
                    runtimeFormV2 = submit
                )
            )
        ).jsonObject
        assertEquals(buildJsonObject { put("protocolVersion", 2); put("runtimeId", "builtin") }, body["formDefinitionRef"])
        assertEquals(submit.formValues, body["formValues"], "a blank secret travels as \"\" and means keep")
        assertEquals(JsonPrimitive("restart"), body["restartMode"])
        assertFalse("runtimeConfig" in body)
        assertFalse("model" in body)
    }

    @Test
    fun serverIssuesPointingAtFormValuesMapToFieldKeys() {
        val error = runtimeFormV2ServerError(
            """{"error":"Model is not available","issues":[{"code":"x","pointer":"/formValues/model"},""" +
                """{"code":"y","pointer":"/formValues/provider/apiKey"},{"code":"z","pointer":"/runtimeConfig/model"}]}"""
        )
        assertEquals("Model is not available", error?.message)
        assertEquals(listOf("model", "provider"), error?.fieldKeys)
        assertNull(runtimeFormV2FieldForPointer("/formValues"))
    }
}

private fun JsonObject.set(path: List<String>, value: JsonElement): JsonObject {
    val head = path.first()
    return if (path.size == 1) {
        JsonObject(this + (head to value))
    } else {
        JsonObject(this + (head to (this[head] as? JsonObject ?: JsonObject(emptyMap())).set(path.drop(1), value)))
    }
}

private fun JsonObject.remove(path: List<String>): JsonObject {
    val head = path.first()
    return if (path.size == 1) {
        JsonObject(this - head)
    } else {
        JsonObject(this + (head to (this[head] as JsonObject).remove(path.drop(1))))
    }
}
