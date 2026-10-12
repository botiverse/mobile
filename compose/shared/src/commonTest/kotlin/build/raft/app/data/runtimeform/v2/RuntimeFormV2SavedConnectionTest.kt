package build.raft.app.data.runtimeform.v2

import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.SlockComputersApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capability `provider.saved_connection` (slock #8818 / #9506): saved provider connections inside a v2
 * form. Port of the "saved connection" cases in slock `packages/runtime-form/src/v2State.test.ts`, plus
 * the mobile loader's `?formRevision=` echo and its whole-form refetch on 409 `form_revision_stale`.
 */
class RuntimeFormV2SavedConnectionTest {
    /** The builtin fixture as a server with provider connections sends it: capability, revision, owned fields. */
    private fun connectionFormJson(revision: String = "rev-1"): String {
        val raw = Json.parseToJsonElement(BUILTIN_V2_FORM_JSON).jsonObject
        val dataSchema = raw.getValue("dataSchema").jsonObject
        val properties = dataSchema.getValue("properties").jsonObject.mapValues { (key, schema) ->
            if (key in setOf("apiKey", "baseUrl", "supportsImageInput")) {
                JsonObject(schema.jsonObject + ("x-connectionOwned" to JsonPrimitive(true)))
            } else {
                schema
            }
        }
        return JsonObject(
            raw + mapOf(
                "requiredClientCapabilities" to JsonArray(listOf(JsonPrimitive("provider.saved_connection"))),
                "revision" to JsonPrimitive(revision),
                "dataSchema" to JsonObject(dataSchema + ("properties" to JsonObject(properties)))
            )
        ).toString()
    }

    private val conn = assertNotNull(parseRuntimeFormV2(connectionFormJson()))
    private val pi = assertNotNull(parseRuntimeFormV2(BUILTIN_V2_FORM_JSON))
    private fun field(key: String) = conn.fields.single { it.key == key }

    private fun option(value: String, label: String, vararg attributes: Pair<String, JsonElement>) =
        RuntimeFormV2Choice(
            value,
            label,
            JsonObject(mapOf("value" to JsonPrimitive(value), "label" to JsonPrimitive(label)) + attributes)
        )

    private fun saved(id: String, label: String, provider: String, kind: String, vararg extra: Pair<String, JsonElement>) =
        option(
            "connection:$id",
            label,
            "group" to JsonPrimitive("saved"),
            "connectionId" to JsonPrimitive(id),
            "underlyingProviderId" to JsonPrimitive(provider),
            "providerKind" to JsonPrimitive(kind),
            *extra
        )

    private fun connSources(vararg leading: RuntimeFormV2Choice): RuntimeFormV2Sources = mapOf(
        "provider" to RuntimeFormV2Source(
            sourceId = "provider",
            kind = RuntimeFormV2SourceKind.Select,
            pointer = "/providerId",
            options = leading.toList() + listOf(
                saved("conn-1", "DeepSeek · Team", "deepseek", "preset"),
                saved(
                    "conn-2",
                    "DeepSeek · Old",
                    "deepseek",
                    "preset",
                    "unavailable" to JsonPrimitive(true),
                    "unavailableReason" to JsonPrimitive("disabled")
                ),
                option("deepseek", "DeepSeek", "group" to JsonPrimitive("direct"), "providerKind" to JsonPrimitive("preset")),
                option("openai-compatible", "OpenAI compatible", "group" to JsonPrimitive("direct"), "providerKind" to JsonPrimitive("gateway"))
            ),
            defaultValue = "deepseek"
        ),
        "model" to RuntimeFormV2Source(
            sourceId = "model",
            kind = RuntimeFormV2SourceKind.DependentSelect,
            pointer = "/model",
            dependsOn = "/providerId",
            optionsByValue = mapOf(
                "connection:conn-1" to listOf(option("deepseek/v4", "V4")),
                "connection:conn-2" to listOf(option("deepseek/v4", "V4")),
                "deepseek" to listOf(option("deepseek/v4", "V4")),
                "openai-compatible" to emptyList()
            ),
            defaultValueByValue = mapOf("connection:conn-1" to "deepseek/v4", "deepseek" to "deepseek/v4"),
            customValueAllowedByValue = mapOf(
                "connection:conn-1" to false,
                "connection:conn-2" to false,
                "deepseek" to false,
                "openai-compatible" to true
            )
        )
    )

    private fun RuntimeFormV2Values.with(key: String, value: String) = this + (key to JsonPrimitive(value))

    @Test
    fun parserKeepsRevisionAndConnectionOwned() {
        assertTrue(RUNTIME_FORM_V2_CAPABILITY_SAVED_CONNECTION in RUNTIME_FORM_V2_CLIENT_CAPABILITIES)
        assertEquals("rev-1", conn.revision)
        assertTrue(field("apiKey").connectionOwned)
        assertTrue(field("baseUrl").connectionOwned)
        assertTrue(field("supportsImageInput").connectionOwned)
        assertFalse(field("model").connectionOwned)
        assertNull(pi.revision, "a form without the capability carries no revision")
    }

    @Test
    fun selectingAConnectionClearsOwnedFieldsLiftsTheirRequiredRuleAndSubmitsWithoutThem() {
        val sources = connSources()
        var values = initialRuntimeFormV2Values(conn, sources).with("apiKey", "sk-direct")
        values = applyRuntimeFormV2Change(conn, sources, values, "providerId", JsonPrimitive("connection:conn-1"))
        assertEquals("", values.text("apiKey"), "switching to a connection clears the field it owns")
        assertEquals("deepseek/v4", values.text("model"), "the model follows the connection's own list")
        assertEquals(emptyMap(), validateRuntimeFormV2(conn, sources, values), "connection-owned fields are not the user's to fill")
        val submitted = runtimeFormV2Submission(conn, values, sources)
        assertFalse("apiKey" in submitted, "owned fields never enter formValues, not even as empty strings")
        assertFalse("baseUrl" in submitted)
        assertFalse("supportsImageInput" in submitted)
        assertEquals("connection:conn-1", submitted["providerId"].stringOrNull())
        assertEquals("deepseek/v4", submitted["model"].stringOrNull())
    }

    @Test
    fun anUnavailableBindingStaysVisibleAndBlocksTheSave() {
        val sources = connSources()
        val values = initialRuntimeFormV2Values(conn, sources)
            .with("providerId", "connection:conn-2")
            .with("model", "deepseek/v4")
        assertEquals(
            mapOf("providerId" to RuntimeFormV2ErrorCode.ConnectionUnavailable),
            validateRuntimeFormV2(conn, sources, values, editing = true)
        )
    }

    @Test
    fun switchingBackToDirectRestoresTheSchemasOwnRules() {
        val sources = connSources()
        var values = applyRuntimeFormV2Change(
            conn,
            sources,
            initialRuntimeFormV2Values(conn, sources),
            "providerId",
            JsonPrimitive("connection:conn-1")
        )
        assertEquals(emptyMap(), validateRuntimeFormV2(conn, sources, values))
        values = applyRuntimeFormV2Change(conn, sources, values, "providerId", JsonPrimitive("openai-compatible"))
        assertEquals(
            mapOf("apiKey" to RuntimeFormV2ErrorCode.Required, "model" to RuntimeFormV2ErrorCode.Required),
            validateRuntimeFormV2(conn, sources, values),
            "the direct schema's own required rules are back once no connection is selected"
        )
        val submitted = runtimeFormV2Submission(conn, values.with("apiKey", "sk-direct").with("model", "any/model"), sources)
        assertEquals("sk-direct", submitted["apiKey"].stringOrNull(), "direct mode submits its own secret as before")
    }

    @Test
    fun aSavedGatewayKeepsItsEndpointAndImageFieldsVisible() {
        val sources = connSources(saved("gw", "Gateway", "openai-compatible", "gateway"))
        val values = initialRuntimeFormV2Values(conn, sources)
            .with("providerId", "connection:gw")
            .with("model", "any/model")
        for (key in listOf("baseUrl", "supportsImageInput")) {
            assertTrue(isRuntimeFormV2FieldVisible(field(key), values.with("providerId", "openai-compatible"), sources))
            assertTrue(isRuntimeFormV2FieldVisible(field(key), values, sources), "$key must stay visible as a disabled managed field")
        }
    }

    @Test
    fun choicesCarryTheConnectionMetadata() {
        val choices = assertIs<RuntimeFormV2Choices.Select>(
            runtimeFormV2Choices(field("providerId"), connSources(), emptyMap())
        ).options
        assertEquals(listOf("saved", "saved", "direct", "direct"), choices.map { it.group })
        assertEquals("conn-1", choices.first().connectionId)
        assertEquals("deepseek", choices.first().underlyingProviderId)
        assertEquals(listOf(false, true, false, false), choices.map { it.unavailable })
        assertNull(choices[2].connectionId)
    }

    // --- loader: formRevision echo and 409 form_revision_stale ---

    private class RevisionApi(
        private val forms: MutableList<String>,
        private val respond: (sourceId: String, formRevision: String?, refresh: Boolean) -> HomeNetworkResponse
    ) : SlockComputersApi {
        val formRequests = mutableListOf<String>()
        val sourceRequests = mutableListOf<String>()

        override suspend fun runtimeFormV2(serverId: ServerId, machineId: String, runtimeId: String): HomeNetworkResponse {
            formRequests += runtimeId
            return HomeNetworkResponse(code = 200, bodyText = if (forms.size > 1) forms.removeAt(0) else forms.single())
        }

        override suspend fun runtimeFormV2OptionSource(
            serverId: ServerId,
            machineId: String,
            runtimeId: String,
            sourceId: String,
            refresh: Boolean,
            formRevision: String?
        ): HomeNetworkResponse {
            sourceRequests += "$sourceId rev=$formRevision refresh=$refresh"
            return respond(sourceId, formRevision, refresh)
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
            error("unused")
    }

    private val stale =
        HomeNetworkResponse(
            code = 409,
            bodyText = null,
            errorBodyText = """{"error":"Form is stale","code":"form_revision_stale","recovery":"refetch_form"}"""
        )

    private fun liveSource(sourceId: String): HomeNetworkResponse =
        HomeNetworkResponse(
            code = 200,
            bodyText =
                if (sourceId == "provider") {
                    """{"sourceId":"provider","kind":"select","pointer":"/providerId",""" +
                        """"options":[{"value":"deepseek","label":"DeepSeek"}],"defaultValue":"deepseek"}"""
                } else {
                    """{"sourceId":"model","kind":"dependent_select","pointer":"/model","dependsOn":"/providerId",""" +
                        """"optionsByValue":{"deepseek":[{"value":"deepseek/v4","label":"V4"}]}}"""
                }
        )

    @Test
    fun everySourceRequestEchoesTheFormRevision() = runBlocking {
        val api = RevisionApi(mutableListOf(connectionFormJson("rev-1"))) { sourceId, _, _ -> liveSource(sourceId) }
        val loaded = assertIs<DataResult.Content<RuntimeFormV2Loaded>>(
            RuntimeFormV2Loader(api).loadCreate(ServerId("s1"), "m1", "builtin")
        ).data
        assertEquals("rev-1", loaded.form.revision)
        assertEquals(listOf("provider rev=rev-1 refresh=false", "model rev=rev-1 refresh=false"), api.sourceRequests)

        val unrevisioned = RevisionApi(mutableListOf(BUILTIN_V2_FORM_JSON)) { sourceId, _, _ -> liveSource(sourceId) }
        RuntimeFormV2Loader(unrevisioned).loadCreate(ServerId("s1"), "m1", "builtin")
        assertTrue(unrevisioned.sourceRequests.all { "rev=null" in it }, "a form without a revision keeps the plain protocol")
    }

    @Test
    fun aStaleRevisionRefetchesTheWholeFormOnce() = runBlocking {
        val api = RevisionApi(mutableListOf(connectionFormJson("rev-old"), connectionFormJson("rev-new"))) { sourceId, revision, _ ->
            if (revision == "rev-old") stale else liveSource(sourceId)
        }
        val loaded = assertIs<DataResult.Content<RuntimeFormV2Loaded>>(
            RuntimeFormV2Loader(api).loadCreate(ServerId("s1"), "m1", "builtin")
        ).data
        assertEquals("rev-new", loaded.form.revision, "options are never merged into the stale form")
        assertEquals(2, api.formRequests.size)
        assertNull(runtimeFormV2SourceStatus(loaded.form.fields.single { it.key == "providerId" }, loaded.sources))
    }

    @Test
    fun aRevisionThatStaysStaleFailsTheFormInsteadOfDegradingASource() = runBlocking {
        val api = RevisionApi(mutableListOf(connectionFormJson("rev-old"))) { _, _, _ -> stale }
        val result = RuntimeFormV2Loader(api).loadCreate(ServerId("s1"), "m1", "builtin")
        assertTrue(result.isFormRevisionStale(), "a stale form is never shown with a source marked unavailable")
        assertEquals(2, api.formRequests.size, "the whole-form refetch is bounded to one")
    }

    @Test
    fun aStaleReprobeAsksTheCallerToReloadTheForm() = runBlocking {
        val api = RevisionApi(mutableListOf(connectionFormJson())) { _, _, _ -> stale }
        val result = RuntimeFormV2Loader(api).reloadSource(ServerId("s1"), "m1", "builtin", "model", formRevision = "rev-1")
        assertTrue(result.isFormRevisionStale())
        assertEquals(listOf("model rev=rev-1 refresh=true"), api.sourceRequests)
    }

    @Test
    fun aPlain409IsStillOneUnavailableSource() = runBlocking {
        val offline = HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = """{"error":"Computer is offline"}""")
        val api = RevisionApi(mutableListOf(connectionFormJson())) { sourceId, _, _ -> if (sourceId == "model") offline else liveSource(sourceId) }
        val loaded = assertIs<DataResult.Content<RuntimeFormV2Loaded>>(
            RuntimeFormV2Loader(api).loadCreate(ServerId("s1"), "m1", "builtin")
        ).data
        assertEquals("unavailable", loaded.sources.getValue("model").status)
        assertEquals(1, api.formRequests.size)
    }
}
