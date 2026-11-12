package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.RuntimeAuthAccountLabel
import build.raft.app.model.RuntimeAuthLoginEventPushed
import build.raft.app.model.RuntimeAuthLoginOutcome
import build.raft.app.model.RuntimeAuthLoginResultPushed
import build.raft.app.model.RuntimeAuthLoginStep
import build.raft.app.model.RuntimeAuthUpdated
import build.raft.app.model.ServerId
import build.raft.app.runPortableCoroutineTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime sign-in from the app, against the wire contract at botiverse/slock staging
 * 853c6998bcef45df5c25e63b1035e2388608721d: packages/shared/src/runtimeAuth.ts (schemas; vectors from
 * runtimeAuth.test.ts), packages/server/src/routes/runtimeAuthRoutes.ts (routes and bodies; the read
 * from runtimeAuth.api.test.ts) and services/agentOrchestrator.ts `relayRuntimeAuthLogin` (pushes).
 */
class RuntimeAuthContractTest {
    @Test
    fun readCarriesEachRuntimesStatusTheComputersRuntimesAndWhoMayAct() {
        // runtimeAuth.api.test.ts stored report + runtimeAuth.test.ts Pi and Grok status vectors.
        val view =
            Json.parseToJsonElement(
                """
                {"status":{"collectedAt":"2026-11-11T10:00:00.000Z","runtimes":{
                  "claude":{"kind":"logged_in","account":{"maskedLabel":"a***@example.com","planLabel":"Max"}},
                  "codex":{"kind":"unavailable","reason":"not_installed"},
                  "grok":{"kind":"logged_in","account":{"methodLabel":"grok.com"}},
                  "antigravity":{"kind":"logged_out"},
                  "pi":{"kind":"logged_in","providers":[
                    {"id":"anthropic","label":"Anthropic","method":"oauth","subscription":true},
                    {"id":"deepseek","label":"DeepSeek","method":"api_key"}]},
                  "brand-new":{"kind":"logged_in"},
                  "qoder":{"kind":"something_new"}}},
                 "availability":"ok","runtimes":["claude","codex","grok","pi","future"],"logoutRuntimes":["claude","codex","grok","pi"],"canAct":true}
                """.trimIndent()
            ).toRuntimeAuthView()!!
        assertTrue(view.canAct)
        assertEquals(RuntimeAuthAvailability.Ok, view.availability)
        assertEquals(
            RuntimeAuthStatus.LoggedIn(RuntimeAuthAccountLabel(maskedLabel = "a***@example.com", planLabel = "Max")),
            view.statusOf(RuntimeAuthRuntime.Claude),
        )
        assertEquals("a***@example.com · Max", (view.statusOf(RuntimeAuthRuntime.Claude) as RuntimeAuthStatus.LoggedIn).account?.text)
        assertEquals(RuntimeAuthStatus.Unavailable(RuntimeAuthUnavailableReason.NotInstalled), view.statusOf(RuntimeAuthRuntime.Codex))
        assertEquals(RuntimeAuthStatus.LoggedOut, view.statusOf(RuntimeAuthRuntime.Antigravity))
        assertEquals(
            listOf(
                RuntimeAuthSignedInProvider("anthropic", "Anthropic", apiKey = false, subscription = true),
                RuntimeAuthSignedInProvider("deepseek", "DeepSeek", apiKey = true),
            ),
            (view.statusOf(RuntimeAuthRuntime.Pi) as RuntimeAuthStatus.LoggedIn).providers,
        )
        assertNull(view.statusOf(RuntimeAuthRuntime.Qoder), "an unknown kind is no status")
        assertEquals(listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex, RuntimeAuthRuntime.Grok, RuntimeAuthRuntime.Pi), view.runtimes)
        // Antigravity is not in `runtimes`: no card (server-gated), and no sign-out without `logoutRuntimes`.
        assertFalse(view.computerSupports(RuntimeAuthRuntime.Antigravity))
        assertTrue(view.computerSignsOut(RuntimeAuthRuntime.Grok))
        assertFalse(view.computerSignsOut(RuntimeAuthRuntime.Qoder), "Qoder has no logout")
    }

    @Test
    fun aServerThatOmitsTheRuntimeListsSignsInClaudeAndCodexOnly() {
        val view = Json.parseToJsonElement("""{"status":null,"availability":"ok","canAct":true}""").toRuntimeAuthView()!!
        assertTrue(view.computerSupports(RuntimeAuthRuntime.Codex))
        assertFalse(view.computerSupports(RuntimeAuthRuntime.Kimi))
        // Web `runtimeAuthComputerSignsOut` without `logoutRuntimes`: Antigravity's logout needs a newer daemon.
        val older = view.copy(runtimes = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Antigravity))
        assertTrue(older.computerSignsOut(RuntimeAuthRuntime.Claude))
        assertFalse(older.computerSignsOut(RuntimeAuthRuntime.Antigravity))
    }

    @Test
    fun raftRuntimesMapToTheirSignIn() {
        assertEquals(RuntimeAuthRuntime.Kimi, RuntimeAuthRuntime.forRaftRuntime("kimi-sdk"))
        assertNull(RuntimeAuthRuntime.forRaftRuntime("kimi"), "the deprecated Kimi CLI has none")
        assertNull(RuntimeAuthRuntime.forRaftRuntime("builtin"), "nor Built-in Pi")
        assertEquals(RuntimeAuthRuntime.Grok, RuntimeAuthRuntime.forRaftRuntime("grok"))
        assertEquals(RuntimeAuthLoginRegion.entries, RuntimeAuthRuntime.Kimi.regionChoices)
        assertTrue(RuntimeAuthRuntime.Qwen.hasProviderKey)
        assertFalse(RuntimeAuthRuntime.Pi.hasProviderKey, "Pi's keys are per-provider sign-ins")
        assertEquals("npm install -g @openai/codex", RUNTIME_INSTALL_COMMANDS["codex"])
    }

    @Test
    fun loginStepsParseAsTheStrictSchemaReadsThem() {
        fun step(json: String) = Json.parseToJsonElement(json).toRuntimeAuthLoginStep()
        assertEquals(
            RuntimeAuthLoginStep.DeviceCode("ABCD-1234", "https://accounts.x.ai/oauth2/device?user_code=ABCD-1234", 900),
            step("""{"kind":"device_code","userCode":"ABCD-1234","verificationUri":"https://accounts.x.ai/oauth2/device?user_code=ABCD-1234","expiresInSeconds":900}"""),
        )
        assertEquals(
            RuntimeAuthLoginStep.AuthUrl("https://claude.ai/oauth/authorize?code=true"),
            step("""{"kind":"auth_url","url":"https://claude.ai/oauth/authorize?code=true","instructions":"Open this URL to sign in."}"""),
        )
        assertEquals(RuntimeAuthLoginStep.CodePrompt(retry = true), step("""{"kind":"code_prompt","retry":true}"""))
        assertEquals(RuntimeAuthLoginStep.Info("Waiting"), step("""{"kind":"info","message":"Waiting"}"""))
        assertNull(step("""{"kind":"auth_url","url":"http://claude.ai/oauth"}"""), "https only")
        assertNull(step("""{"kind":"device_code","userCode":"A","verificationUri":"https://x.ai","expiresInSeconds":0}"""))
        assertNull(step("""{"kind":"info","message":"x","token":"secret"}"""), "an extra field")
        assertNull(step("""{"kind":"browser_opened"}"""))
    }

    @Test
    fun loginAndLogoutResultsParseEveryKind() {
        fun outcome(json: String) = Json.parseToJsonElement(json).toRuntimeAuthLoginOutcome()
        assertEquals(RuntimeAuthLoginOutcome.LoggedIn(RuntimeAuthAccountLabel(methodLabel = "grok.com")), outcome("""{"kind":"logged_in","account":{"methodLabel":"grok.com"}}"""))
        assertEquals(RuntimeAuthLoginOutcome.Failed("interaction_failed"), outcome("""{"kind":"failed","reason":"interaction_failed"}"""))
        assertEquals(RuntimeAuthLoginOutcome.Unsupported("version_unsupported"), outcome("""{"kind":"unsupported","reason":"version_unsupported"}"""))
        assertEquals(RuntimeAuthLoginOutcome.Busy, outcome("""{"kind":"busy"}"""))
        assertNull(outcome("""{"kind":"failed","reason":"brand_new"}"""))

        fun logout(json: String) = Json.parseToJsonElement(json).toRuntimeAuthLogoutResult()
        assertEquals(RuntimeAuthLogoutResult.LoggedOut, logout("""{"kind":"logged_out"}"""))
        assertEquals(RuntimeAuthLogoutResult.Failed("still_logged_in"), logout("""{"kind":"failed","reason":"still_logged_in"}"""))
        assertNull(logout("""{"kind":"failed","reason":"not_logged_in"}"""), "a login reason is no logout reason")
    }

    @Test
    fun piProvidersCarryTheirSignInWays() {
        // runtimeAuth.test.ts "lists a Pi provider with its sign-in ways".
        val result =
            Json.parseToJsonElement(
                """
                {"kind":"listed","providers":[
                  {"id":"anthropic","label":"Anthropic","configured":true,"endpoint":{"kind":"fixed"},"needsModelIds":false,
                   "signIn":{"oauth":{"label":"Anthropic (Claude Pro/Max)","subscription":true,"flow":"paste_code"},
                             "apiKey":{"label":"Anthropic API key","entry":"key"},"stored":"oauth"}},
                  {"id":"amazon-bedrock","label":"Amazon Bedrock","configured":false,"endpoint":{"kind":"fixed"},"needsModelIds":false,
                   "signIn":{"apiKey":{"label":"Amazon Bedrock","entry":"more_fields"}}}]}
                """.trimIndent()
            ).toRuntimeAuthProvidersResult()
        val listed = assertIs<RuntimeAuthProvidersResult.Listed>(result)
        assertEquals(
            RuntimeAuthProvider(
                id = "anthropic",
                label = "Anthropic",
                oauthLabel = "Anthropic (Claude Pro/Max)",
                oauthFlow = RuntimeAuthProviderOAuthFlow.PasteCode,
                apiKeyEntry = RuntimeAuthProviderKeyEntry.Key,
                stored = "oauth",
            ),
            listed.providers.first(),
        )
        assertEquals(RuntimeAuthProviderKeyEntry.MoreFields, listed.providers[1].apiKeyEntry)
        assertNull(
            Json.parseToJsonElement(
                """{"kind":"listed","providers":[{"id":"x","label":"X","configured":false,"endpoint":{"kind":"fixed"},"needsModelIds":false,"signIn":{"oauth":{"label":"X","subscription":false,"flow":"browser"}}}]}"""
            ).toRuntimeAuthProvidersResult(),
            "a flow it does not know",
        )
        // slock #14378 (staging fa3b81be1): OpenCode lists every OAuth sign-in of a provider, and
        // an odd method label drops that method only; its stored accounts claim no subscription.
        val opencode =
            assertIs<RuntimeAuthProvidersResult.Listed>(
                Json.parseToJsonElement(
                    """
                    {"kind":"listed","providers":[{"id":"github-copilot","label":"GitHub Copilot","configured":false,"endpoint":{"kind":"fixed"},"needsModelIds":false,
                     "signIn":{"oauthMethods":[{"label":"Login with GitHub (device)","flow":"device_code"},{"label":"Login with GitHub","flow":"link"},{"label":" padded","flow":"link"}]}}]}
                    """.trimIndent()
                ).toRuntimeAuthProvidersResult()
            ).providers.single()
        assertEquals(
            listOf(
                RuntimeAuthOAuthMethod("Login with GitHub (device)", RuntimeAuthProviderOAuthFlow.DeviceCode),
                RuntimeAuthOAuthMethod("Login with GitHub", RuntimeAuthProviderOAuthFlow.Link),
            ),
            opencode.oauthMethods,
        )
        assertNull(
            (Json.parseToJsonElement("""{"kind":"logged_in","providers":[{"id":"openai","label":"OpenAI","method":"oauth"}]}""").toRuntimeAuthStatus() as RuntimeAuthStatus.LoggedIn)
                .providers.single().subscription,
        )
        assertEquals(RuntimeAuthRuntime.Opencode, RuntimeAuthRuntime.forRaftRuntime("opencode"))
        assertFalse(RuntimeAuthRuntime.Opencode.literalApiKeyFits("{env:OPENAI_API_KEY}"))
        assertTrue(RuntimeAuthRuntime.Opencode.literalApiKeyFits("!sk-1"))
        assertEquals(RuntimeAuthLogoutResult.Failed("busy"), Json.parseToJsonElement("""{"kind":"failed","reason":"busy"}""").toRuntimeAuthLogoutResult())
        assertEquals(RuntimeAuthProviderConnectResult.Busy, Json.parseToJsonElement("""{"kind":"busy"}""").toRuntimeAuthProviderConnectResult())
        assertFalse(isPiLiteralApiKey("!op read x"))
        assertFalse(isPiLiteralApiKey("\$KEY"))
        assertTrue(isPiLiteralApiKey("sk-1234"))
    }

    @Test
    fun requestsGoToTheRoutesWithOnlyTheFieldsTheRuntimeTakes() = runPortableCoroutineTest<Unit> {
        val api = RecordingRuntimeAuthApi()
        val client = RaftComputersApiClient(api)
        val started = client.startRuntimeLogin(ServerId("s1"), "m1", RuntimeAuthRuntime.Codex)
        assertEquals(RuntimeAuthCall.Done("rtl_abcdefghijklmnop12"), started)
        client.startRuntimeLogin(ServerId("s1"), "m1", RuntimeAuthRuntime.Kimi, region = RuntimeAuthLoginRegion.Global)
        client.startRuntimeLogin(ServerId("s1"), "m1", RuntimeAuthRuntime.Pi, providerId = "openai-codex")
        client.logoutRuntime(ServerId("s1"), "m1", RuntimeAuthRuntime.Grok)
        client.startRuntimeLogin(ServerId("s1"), "m1", RuntimeAuthRuntime.Opencode, providerId = "github-copilot", oauthMethod = "Login with GitHub (device)")
        // The server refuses a `region` or `providerId` that is present, even null, where the runtime takes none.
        assertEquals(
            listOf(
                Triple("login", "codex", emptyMap()),
                Triple("login", "kimi", mapOf("region" to "global")),
                Triple("login", "pi", mapOf("providerId" to "openai-codex")),
                Triple("logout", "grok", emptyMap<String, String>()),
                Triple("login", "opencode", mapOf("providerId" to "github-copilot", "oauthMethod" to "Login with GitHub (device)")),
            ),
            api.calls.map { (route, runtime, body) -> Triple(route, runtime, body.mapValues { it.value.jsonPrimitive.content }) },
        )
    }

    @Test
    fun aRefusalKeepsItsStatusCodeAndTraceId() = runPortableCoroutineTest<Unit> {
        val api =
            RecordingRuntimeAuthApi(
                answer =
                    HomeNetworkResponse(
                        409,
                        null,
                        errorBodyText = """{"error":"A sign-in for this runtime is already running on this Computer.","code":"login_in_progress"}""",
                        responseHeaders = mapOf("X-Trace-Id" to listOf("0123456789abcdef0123456789abcdef")),
                    ),
            )
        val refused = assertIs<RuntimeAuthCall.Refused>(RaftComputersApiClient(api).startRuntimeLogin(ServerId("s1"), "m1", RuntimeAuthRuntime.Claude))
        assertEquals(RuntimeAuthRequestError(409, "login_in_progress", "0123456789abcdef0123456789abcdef"), refused.error)
        // A 200 without a login id that checks out is no login.
        val malformed = RaftComputersApiClient(RecordingRuntimeAuthApi(answer = HomeNetworkResponse(200, """{"loginId":"nope"}""")))
        assertEquals("malformed_response", assertIs<RuntimeAuthCall.Refused>(malformed.startRuntimeLogin(ServerId("s1"), "m1", RuntimeAuthRuntime.Claude)).error.code)
    }

    /** The store sends a sign-in's cancel from its own scope, so a caller that is going away can ask for it. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun aDetachedCancelIsSentFromTheStoresScope() = kotlinx.coroutines.test.runTest {
        val api = RecordingRuntimeAuthApi()
        val owner = SharedComputersOwner(environmentId = "https://api.example", accountId = "u1", sessionGeneration = "g1")
        val store = SharedRuntimeAccountUsageStore(RaftComputersApiClient(api), currentIdentity = { owner }, detachedScope = backgroundScope)
        store.cancelRuntimeLoginDetached(owner, "s1", "m1", RuntimeAuthRuntime.Claude, "rtl_abcdefghijklmnop12")
        testScheduler.runCurrent()
        assertEquals(listOf("cancel" to "claude"), api.calls.map { (route, runtime, _) -> route to runtime })
    }

    @Test
    fun realtimePushesMapToTheirEvents() {
        assertTrue(RealtimeSocketEvents.RuntimeAuthLoginEvent in RealtimeSocketEvents.ServerWireEvents)
        assertTrue(RealtimeSocketEvents.RuntimeAuthLoginResult in RealtimeSocketEvents.ServerWireEvents)
        assertTrue(RealtimeSocketEvents.MachineRuntimeAuthUpdated in RealtimeSocketEvents.ServerWireEvents)
        val mapper = RealtimeEventMapper()
        assertEquals(RuntimeAuthUpdated("m1"), mapper.map("machine:runtime_auth:updated", """{"machineId":"m1"}"""))
        assertEquals(
            RuntimeAuthLoginEventPushed("s1", "m1", "codex", LOGIN_ID, RuntimeAuthLoginStep.DeviceCode("ABCD-1234", "https://auth.openai.com/codex/device")),
            mapper.map(
                "runtime_auth:login:event",
                """{"serverId":"s1","machineId":"m1","runtime":"codex","loginId":"$LOGIN_ID","event":{"kind":"device_code","userCode":"ABCD-1234","verificationUri":"https://auth.openai.com/codex/device"}}""",
            ),
        )
        assertEquals(
            RuntimeAuthLoginResultPushed("s1", "m1", "claude", LOGIN_ID, RuntimeAuthLoginOutcome.Cancelled),
            mapper.map("runtime_auth:login:result", """{"serverId":"s1","machineId":"m1","runtime":"claude","loginId":"$LOGIN_ID","result":{"kind":"cancelled"}}"""),
        )
        assertNull(
            mapper.map("runtime_auth:login:result", """{"serverId":"s1","machineId":"m1","runtime":"claude","loginId":"not-a-login","result":{"kind":"cancelled"}}"""),
            "a login id that does not check out",
        )
        assertNull(mapper.map("runtime_auth:login:event", """{"serverId":"s1","machineId":"m1","runtime":"cursor","loginId":"$LOGIN_ID","event":{"kind":"info","message":"x"}}"""))
    }

    /** Records each runtime-auth request's route, runtime and parsed body. */
    private class RecordingRuntimeAuthApi(
        private val answer: HomeNetworkResponse = HomeNetworkResponse(200, """{"loginId":"rtl_abcdefghijklmnop12","result":{"kind":"logged_out"}}"""),
    ) : RaftComputersApi {
        val calls = mutableListOf<Triple<String, String, JsonObject>>()

        override suspend fun startRuntimeLogin(serverId: ServerId, machineId: String, runtime: String, body: String): HomeNetworkResponse {
            calls += Triple("login", runtime, Json.parseToJsonElement(body).jsonObject)
            return answer
        }

        override suspend fun cancelRuntimeLogin(serverId: ServerId, machineId: String, runtime: String, loginId: String): HomeNetworkResponse {
            calls += Triple("cancel", runtime, JsonObject(emptyMap()))
            return HomeNetworkResponse(202, """{"accepted":true}""")
        }

        override suspend fun logoutRuntime(serverId: ServerId, machineId: String, runtime: String, body: String): HomeNetworkResponse {
            calls += Triple("logout", runtime, Json.parseToJsonElement(body).jsonObject)
            return answer
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
    }

    private companion object {
        const val LOGIN_ID = "rtl_abcdefghijklmnop12"
    }
}
