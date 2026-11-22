package build.raft.app

import build.raft.app.resources.*
import build.raft.app.model.AgentStatus
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.runtimeform.v2.CLAUDE_V3_EDIT_JSON
import build.raft.app.data.runtimeform.v2.CODEX_V2_FALLBACK_SOURCE_JSON
import build.raft.app.data.runtimeform.v2.CODEX_V3_EDIT_JSON
import build.raft.app.data.runtimeform.v2.CODEX_V3_SPEED_SOURCE_JSON
import build.raft.app.data.runtimeform.v2.ParsedRuntimeFormV2
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Sources
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.applyRuntimeFormV2Change
import build.raft.app.data.runtimeform.v2.booleanValue
import build.raft.app.data.runtimeform.v2.initialRuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.isRuntimeFormV2FieldVisible
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2Source
import build.raft.app.data.runtimeform.v2.runtimeFormV2Submission
import build.raft.app.data.runtimeform.v2.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MembersRuntimeConfigTest {
    @Test
    fun runtimeResolutionKeepsNonSecretBuiltInProviderIdentityForEditHydration() {
        val provider = RuntimeProviderConfig.Gateway(
            "openai-compatible",
            "https://gateway.example.test/v1",
            "",
            supportsImageInput = true
        )
        val resolution =
            AgentDetailSummary(
                id = "agent-1",
                name = "Agent One",
                runtime = "builtin",
                model = "vendor/model",
                runtimeModelKind = "custom",
                runtimeProvider = provider
            ).toMembersRuntimeConfigResolution()

        val config = (resolution as MembersAgentRuntimeConfigResolution.Content).config
        assertEquals(provider, config.provider)
        assertEquals(RuntimeModelConfig.Custom("vendor/model"), config.model)
    }

    @Test
    fun runtimeModelKindPreservesPresetVsCustomIdentity() {
        assertEquals(
            RuntimeModelConfig.Preset("minimax/MiniMax-M2.7"),
            membersRuntimeModelConfig("builtin", "minimax/MiniMax-M2.7", "preset")
        )
        assertEquals(
            RuntimeModelConfig.Custom("vendor/private-model"),
            membersRuntimeModelConfig("claude", "vendor/private-model", "custom")
        )
        assertEquals(
            RuntimeModelConfig.Preset("gpt-5.6-sol"),
            membersRuntimeModelConfig("codex", "gpt-5.6-sol", null)
        )
        assertEquals(
            RuntimeModelConfig.Custom("legacy/private-model"),
            membersRuntimeModelConfig("codex", "legacy/private-model", null)
        )
    }

    @Test
    fun unknownRuntimeStatusBlocksMutationInsteadOfActingInactive() {
        assertFalse(membersAgentRuntimeStatusResolved(null))
        assertTrue(membersAgentRuntimeStatusResolved(AgentStatus.Offline))
        assertTrue(membersAgentRuntimeStatusResolved(AgentStatus.Working))

        assertFalse(membersAgentRuntimeActive(null))
        assertFalse(membersAgentRuntimeActive(AgentStatus.Offline))
        assertTrue(membersAgentRuntimeActive(AgentStatus.Working))
    }

    /**
     * 1.28 report: the profile said Mode "Default" while the editor opened with Fast mode checked, because
     * the profile read `executionMode` (where the agent runs). Both now start from the saved
     * `runtimeConfig.mode`: the profile through the agent read ([AgentDetailSummary.runtimeMode], web
     * `runtimeConfigFastMode`), the editor through the server's edit values for the same config (server
     * `valuesFromRuntimeConfig`: `fastMode`, and on Codex `speed`, Standard being ""). Each row is one
     * saved config; the edit body is the server's v3 fixture carrying that config's values. In the codex
     * fixture GPT-6.2 Sol has one fast tier (the Fast mode box) and GPT-6.2 Astra several (the Speed menu).
     */
    @Test
    fun profileModeAndTheRuntimeEditorStartFromTheSameSavedMode() {
        val cases =
            listOf(
                SavedModeCase("none saved", null, "codex", "gpt-6.2-sol", codexEditValues("gpt-6.2-sol", fastMode = false, speed = ""), fast = false),
                SavedModeCase("default", "default", "codex", "gpt-6.2-sol", codexEditValues("gpt-6.2-sol", fastMode = false, speed = ""), fast = false),
                SavedModeCase("fast", "fast", "codex", "gpt-6.2-sol", codexEditValues("gpt-6.2-sol", fastMode = true, speed = "priority"), fast = true),
                SavedModeCase("default, Speed menu", "default", "codex", "gpt-6.2-astra", codexEditValues("gpt-6.2-astra", fastMode = false, speed = ""), fast = false),
                SavedModeCase("ultrafast, Speed menu", "fast", "codex", "gpt-6.2-astra", codexEditValues("gpt-6.2-astra", fastMode = true, speed = "ultrafast"), fast = true),
                SavedModeCase("fast", "fast", "claude", "sonnet", claudeEditValues("sonnet", fastMode = true), fast = true),
                // Shared `runtimeModelSupportsFastMode`: saved fast, launched (and edited) at the standard speed.
                SavedModeCase("fast on a model that no longer runs fast", "fast", "claude", "claude-opus-4-8", claudeEditValues("claude-opus-4-8", fastMode = false), fast = false),
            )
        for (case in cases) {
            val label = "${case.runtime} ${case.name}"
            val config =
                assertIs<MembersAgentRuntimeConfigResolution.Content>(
                    AgentDetailSummary(
                        id = "agent-1",
                        name = "agent",
                        runtime = case.runtime,
                        model = case.model,
                        runtimeModelKind = "preset",
                        runtimeMode = case.runtimeMode,
                        executionMode = "byoc"
                    ).toMembersRuntimeConfigResolution(),
                    label
                ).config
            assertEquals(case.fast, membersRuntimeConfigFastMode(config), "$label: the profile's Mode row")

            val codex = case.runtime == "codex"
            val sources: RuntimeFormV2Sources = if (codex) codexEditSources else emptyMap()
            val form = editForm(if (codex) CODEX_V3_EDIT_JSON else CLAUDE_V3_EDIT_JSON, case.editValues)
            val initial = initialRuntimeFormV2Values(form, sources)
            assertEquals(case.fast, editorShowsFastMode(form, sources, initial), "$label: the editor opens on the profile's mode")

            // Saved after changing something else, the mode goes back as saved; a field its rule hides
            // (Fast mode under the Speed menu, or the other way round) is not sent, as before.
            val edited = applyRuntimeFormV2Change(form, sources, initial, "envVars", JsonObject(mapOf("EXTRA_FLAG" to JsonPrimitive("1"))))
            val submitted = runtimeFormV2Submission(form, edited, sources)
            for (key in listOf("fastMode", "speed")) {
                val field = form.fields.firstOrNull { it.key == key } ?: continue
                if (isRuntimeFormV2FieldVisible(field, edited, sources)) {
                    assertEquals(case.editValues[key], submitted[key], "$label: $key is sent as saved")
                } else {
                    assertFalse(key in submitted, "$label: the hidden $key is not sent")
                }
            }
        }
    }

    private data class SavedModeCase(
        val name: String,
        /** The agent read's `runtimeConfig.mode.kind`; null when it carries none. */
        val runtimeMode: String?,
        val runtime: String,
        val model: String,
        /** The server's edit values for the same saved config. */
        val editValues: Map<String, JsonElement>,
        val fast: Boolean
    )

    private fun codexEditValues(model: String, fastMode: Boolean, speed: String): Map<String, JsonElement> =
        mapOf("model" to JsonPrimitive(model), "fastMode" to JsonPrimitive(fastMode), "speed" to JsonPrimitive(speed))

    private fun claudeEditValues(model: String, fastMode: Boolean): Map<String, JsonElement> =
        mapOf("model" to JsonPrimitive(model), "fastMode" to JsonPrimitive(fastMode))

    /** The model list that names GPT-6.2 Sol and Astra, and the speeds Astra offers. */
    private val codexEditSources: RuntimeFormV2Sources =
        mapOf(
            "model" to assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(CODEX_V2_FALLBACK_SOURCE_JSON))),
            "speed" to assertNotNull(parseRuntimeFormV2Source(Json.parseToJsonElement(CODEX_V3_SPEED_SOURCE_JSON)))
        )

    /** The server's edit body [fixture] with [values] over its stored ones. */
    private fun editForm(fixture: String, values: Map<String, JsonElement>): ParsedRuntimeFormV2 {
        val body = Json.parseToJsonElement(fixture).jsonObject
        val stored = body.getValue("values").jsonObject
        return assertNotNull(parseRuntimeFormV2(JsonObject(body + ("values" to JsonObject(stored + values)))))
    }

    /** The mode the editor shows: the Speed menu where it is shown (Standard is ""), else the Fast mode box. */
    private fun editorShowsFastMode(form: ParsedRuntimeFormV2, sources: RuntimeFormV2Sources, values: RuntimeFormV2Values): Boolean {
        val speed = form.fields.firstOrNull { it.key == "speed" }
        if (speed != null && isRuntimeFormV2FieldVisible(speed, values, sources)) return values.text("speed").isNotEmpty()
        val fastMode = form.fields.single { it.key == "fastMode" }
        return isRuntimeFormV2FieldVisible(fastMode, values, sources) && values["fastMode"].booleanValue()
    }
}
