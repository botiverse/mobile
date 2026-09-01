package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.REASONING_EFFORT_RUNTIMES
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.reconcileRuntimeReasoningEffort
import build.raft.app.model.supportsRuntimeReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Locks legacy mobile Kimi reasoning-effort payload compatibility (task #270, from the
 * #proj-runtime #1019 audit, audit-exact main `1113207779711bc4dbe7863ae725036847a99124`).
 *
 * `kimi-sdk` is deliberately NOT in [REASONING_EFFORT_RUNTIMES]: legacy Kimi agents carry no
 * effort and the server treats a missing/null effort as "keep the existing value". Three cells:
 *  1. Kimi create never offers/invents an effort — no `reasoningEffort`, no `formDefinitionRef`
 *     on the wire (the create encoder omits absent optionals; an explicit null ref 409s).
 *  2. Kimi same-model runtime edit keeps the legacy outbound shape: `reasoningEffort: null`,
 *     no ref — asserted on the exact outbound body bytes, not a post-hoc hydrated model.
 *  3. A server 409 on model switch rewrites nothing client-side (Error, no Content) and the
 *     server's actionable reason reaches display (`serverMessage`, server-first priority).
 * Mutation anchor: adding "kimi-sdk" to REASONING_EFFORT_RUNTIMES turns cell 1 RED.
 */
class KimiReasoningEffortCompatTest {

    private val kimiConfig =
        RuntimeConfig(
            runtime = "kimi-sdk",
            provider = RuntimeProviderConfig.Default,
            model = RuntimeModelConfig.Preset("kimi-k2"),
            reasoningEffort = null
        )

    @Test
    fun kimiCreateDoesNotOfferOrInventReasoningEffort() {
        assertFalse(
            supportsRuntimeReasoningEffort("kimi-sdk"),
            "kimi-sdk must stay out of REASONING_EFFORT_RUNTIMES (legacy agents carry no effort)"
        )
        assertNull(
            reconcileRuntimeReasoningEffort(runtime = "kimi-sdk", model = "kimi-k2", current = "high"),
            "kimi-sdk reconciliation must not invent or keep an effort, not even a pre-existing one"
        )
    }

    @Test
    fun kimiCreateWireBodyOmitsReasoningEffortAndFormDefinitionRef() {
        val wire =
            buildCreateAgentBody(
                CreateAgentBody(
                    name = "kimi-agent",
                    runtime = "kimi-sdk",
                    model = "kimi-k2",
                    reasoningEffort = null,
                    formDefinitionRef = null,
                    runtimeConfig = kimiConfig
                )
            )
        val json = membersTestJson.parseToJsonElement(wire).jsonObject
        assertEquals("kimi-sdk", json.getValue("runtime").jsonPrimitive.content)
        assertTrue("reasoningEffort" !in json, "create wire must omit reasoningEffort for kimi, got: $wire")
        assertTrue("formDefinitionRef" !in json, "create wire must omit formDefinitionRef for kimi, got: $wire")
        val nested = json.getValue("runtimeConfig").jsonObject
        assertTrue("reasoningEffort" !in nested, "nested runtimeConfig must omit reasoningEffort for kimi")

        // Positive control: the same encoder keeps an effort when one is genuinely set, so the
        // omissions above are the kimi contract and not an encoder that never writes the field.
        val control =
            buildCreateAgentBody(
                CreateAgentBody(
                    name = "codex-agent",
                    runtime = "codex",
                    model = "gpt-5",
                    reasoningEffort = "high",
                    runtimeConfig =
                        RuntimeConfig(
                            runtime = "codex",
                            provider = RuntimeProviderConfig.Default,
                            model = RuntimeModelConfig.Preset("gpt-5"),
                            reasoningEffort = "high"
                        )
                )
            )
        val controlJson = membersTestJson.parseToJsonElement(control).jsonObject
        assertEquals("high", controlJson.getValue("reasoningEffort").jsonPrimitive.content)
    }

    @Test
    fun kimiSameModelRuntimeEditOutboundKeepsLegacyNullEffortNoRefShape() {
        val wire =
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Kimi Bot",
                    description = null,
                    runtimeConfig = kimiConfig, // same model, untouched effort
                    includeRuntimeConfig = true
                )
            )
        val json = membersTestJson.parseToJsonElement(wire).jsonObject
        assertEquals(
            JsonNull,
            json["reasoningEffort"],
            "same-model kimi edit must send explicit reasoningEffort:null (server keeps existing), got: $wire"
        )
        assertTrue("formDefinitionRef" !in json, "profile-update wire must not carry formDefinitionRef")
        assertEquals("kimi-sdk", json.getValue("runtime").jsonPrimitive.content)
        assertEquals("kimi-k2", json.getValue("model").jsonPrimitive.content)

        // Positive control: a reasoning-capable runtime serializes the chosen effort.
        val controlWire =
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Codex Bot",
                    description = null,
                    runtimeConfig =
                        RuntimeConfig(
                            runtime = "codex",
                            provider = RuntimeProviderConfig.Default,
                            model = RuntimeModelConfig.Preset("gpt-5"),
                            reasoningEffort = "high"
                        ),
                    includeRuntimeConfig = true
                )
            )
        assertEquals(
            "high",
            membersTestJson.parseToJsonElement(controlWire).jsonObject.getValue("reasoningEffort").jsonPrimitive.content
        )
    }

    @Test
    fun modelSwitchConflictRewritesNothingAndSurfacesServerReason() = runBlocking {
        val serverReason = "runtime reasoning effort is not supported for kimi-sdk"
        val network =
            RecordingKimiNetworkClient(
                HomeNetworkResponse(
                    code = 409,
                    bodyText = null,
                    errorBodyText = """{"error":"$serverReason"}"""
                )
            )
        val repository = RealMemberRepository(kimiTestSessionStore(), network)

        val result =
            repository.updateAgentProfile(
                DataRequestPriority.Visible,
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Kimi Bot",
                    description = null,
                    runtimeConfig =
                        kimiConfig.copy(model = RuntimeModelConfig.Preset("kimi-k2-thinking")),
                    includeRuntimeConfig = true
                )
            )

        // Zero client-side rewrite on 409: the result is an Error, never Content, so no caller
        // has data to apply over the legacy state.
        val error = assertIs<DataResult.Error>(result).error
        assertEquals("http_409", error.code)
        assertEquals(serverReason, error.serverMessage)
        // The server's actionable reason wins display priority over localized generic copy.
        val presentation = error.errorPresentation()
        assertEquals(serverReason, presentation.rawServerMessage)
        assertEquals(RawServerMessagePriority.ServerMessageFirst, presentation.rawServerMessagePriority)
        // Exactly one outbound attempt — no silent retry that could double-apply a rewrite.
        assertEquals(1, network.bodies.size)
    }

    private class RecordingKimiNetworkClient(
        private val response: HomeNetworkResponse
    ) : SlockNetworkClient {
        val bodies = mutableListOf<String?>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: build.raft.app.model.ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            bodies += bodyText
            return response
        }
    }

    private companion object {
        val membersTestJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun kimiTestSessionStore(): SlockAuthSessionStore =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai", name = "User"),
                    accessToken = "sample-access",
                    refreshToken = "sample-refresh",
                    selectedServerId = "server-1",
                    selectedServerSlug = "slock",
                    servers =
                        listOf(
                            StoredAuthServer(
                                id = "server-1",
                                name = "Server",
                                slug = "slock",
                                ownerId = "user-1",
                                role = "member",
                                createdAt = "2026-07-01T00:00:00Z"
                            )
                        )
                )
            )
    }
}
