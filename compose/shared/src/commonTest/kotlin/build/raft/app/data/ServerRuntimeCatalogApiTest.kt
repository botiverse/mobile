package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.RuntimeModelInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/servers/:id/runtimes` (botiverse/slock `docs/contracts/server-runtime-catalog.md`
 * at f5fe2ca864, PR #12065). [ContractExample] is the contract's response example verbatim;
 * [ServerProjection] follows `serverRuntimeCatalogService.test.ts` at the same SHA.
 */
class ServerRuntimeCatalogApiTest {
    @Test
    fun decodesTheContractExample() {
        val catalog = assertNotNull(decodeServerRuntimeCatalog(ContractExample, RuntimeCatalogSource.Server))
        assertEquals(1, catalog.catalogVersion)
        assertEquals(RuntimeCatalogSource.Server, catalog.source)
        val kimi = catalog.runtimes.single()
        assertEquals("kimi-sdk", kimi.id)
        assertEquals("Kimi Code", kimi.displayName)
        assertEquals("KC", kimi.abbreviation)
        assertEquals("", kimi.binary)
        assertTrue(kimi.supported)
        assertFalse(kimi.experimental)
        assertFalse(kimi.deprecated)
        assertFalse(kimi.oarOnly)
        assertEquals(listOf(RuntimeModelInfo("...", "...")), kimi.models)
        assertEquals(RuntimeCatalogModelsSource.Fallback, kimi.modelsSource)
        assertEquals(listOf(RuntimeCatalogForm.V1("kimi-sdk.create.v1"), RuntimeCatalogForm.V2), kimi.forms)
    }

    @Test
    fun decodesTheServerProjectionFlags() {
        val catalog = assertNotNull(decodeServerRuntimeCatalog(ServerProjection, RuntimeCatalogSource.Server))
        val byId = catalog.runtimes.associateBy { it.id }
        assertEquals(listOf("claude", "copilot", "qwen", "qoder", "codex"), catalog.runtimes.map { it.id })
        assertEquals(RuntimeCatalogModelsSource.Static, byId.getValue("claude").modelsSource)
        assertEquals(listOf(RuntimeCatalogForm.Generic, RuntimeCatalogForm.V2), byId.getValue("claude").forms)
        assertTrue(byId.getValue("copilot").deprecated)
        assertTrue(byId.getValue("qwen").experimental)
        assertTrue(byId.getValue("qwen").oarOnly)
        assertEquals(emptyList(), byId.getValue("qwen").models)
        assertEquals(RuntimeCatalogModelsSource.Fallback, byId.getValue("codex").modelsSource)
    }

    @Test
    fun unknownFieldsKindsAndValuesNeverFailTheResponse() {
        val body =
            """
            {"catalogVersion":7,"futureTopLevel":{"x":1},"runtimes":[
              {"id":"claude","displayName":"Claude Code","abbreviation":"CC","binary":"claude","supported":true,
               "experimental":false,"deprecated":false,"oarOnly":false,"newFlag":true,"signIn":{"kind":"oauth"},
               "models":[{"id":"opus","label":"Claude Opus","tier":"max"}],"modelsSource":"live-only",
               "forms":[{"kind":"v3","schemaVersion":"x"},{"kind":"v2","extra":1},{"kind":"generic"}]},
              {"id":"future","displayName":"Future Runtime","supported":true,"forms":[{"kind":"v9"}]},
              {"id":"v1only","displayName":"V1 Only","supported":true,"forms":[{"kind":"v1","schemaVersion":"v1only.create.v1"},{"kind":"v1"}]}
            ]}
            """.trimIndent()
        val catalog = assertNotNull(decodeServerRuntimeCatalog(body, RuntimeCatalogSource.Server))
        assertEquals(7, catalog.catalogVersion)
        val claude = catalog.entry("claude")!!
        assertEquals(RuntimeCatalogModelsSource.Unknown, claude.modelsSource)
        assertEquals(listOf(RuntimeModelInfo("opus", "Claude Opus")), claude.models)
        assertEquals(listOf(RuntimeCatalogForm.Unknown("v3"), RuntimeCatalogForm.V2, RuntimeCatalogForm.Generic), claude.forms)
        assertEquals(listOf(RuntimeCatalogForm.Unknown("v9")), catalog.entry("future")!!.forms)
        // A v1 entry without schemaVersion names no loadable form.
        assertEquals(
            listOf(RuntimeCatalogForm.V1("v1only.create.v1"), RuntimeCatalogForm.Unknown("v1")),
            catalog.entry("v1only")!!.forms
        )
    }

    @Test
    fun aMalformedRowIsDroppedAndTheRestStillRead() {
        val body =
            """
            {"catalogVersion":1,"runtimes":[
              {"displayName":"No id"},
              {"id":"claude","displayName":"Claude Code","supported":"yes"},
              "not-an-object",
              {"id":"codex","displayName":"Codex CLI","supported":true,"forms":[{"kind":"v2"}],"models":[{"label":"no id"},{"id":"gpt-5"}]},
              {"id":"codex","displayName":"Duplicate","supported":true}
            ]}
            """.trimIndent()
        val catalog = assertNotNull(decodeServerRuntimeCatalog(body, RuntimeCatalogSource.Server))
        assertEquals(listOf("codex"), catalog.runtimes.map { it.id })
        assertEquals("Codex CLI", catalog.runtimes.single().displayName)
        assertEquals(listOf(RuntimeModelInfo("gpt-5", "gpt-5")), catalog.runtimes.single().models)
    }

    @Test
    fun aBodyThatIsNotACatalogDecodesToNull() {
        assertNull(decodeServerRuntimeCatalog("[]", RuntimeCatalogSource.Server))
        assertNull(decodeServerRuntimeCatalog("<html>", RuntimeCatalogSource.Server))
        // Any other JSON object a 200 can carry is not a catalog (Otter, #4001): never "0 runtimes".
        assertNull(decodeServerRuntimeCatalog("{}", RuntimeCatalogSource.Server))
        assertNull(decodeServerRuntimeCatalog("""{"error":"x"}""", RuntimeCatalogSource.Server))
        assertNull(decodeServerRuntimeCatalog("""{"catalogVersion":0,"runtimes":[]}""", RuntimeCatalogSource.Server))
        assertNull(decodeServerRuntimeCatalog("""{"runtimes":[]}""", RuntimeCatalogSource.Server))
        // A real catalog with no runtimes (every switch off) still reads.
        assertEquals(0, decodeServerRuntimeCatalog("""{"catalogVersion":1,"runtimes":[]}""", RuntimeCatalogSource.Server)!!.runtimes.size)
    }

    @Test
    fun aRuntimeIsSelectableOnlyWithAFormThisAppKnows() {
        val v2 = entry("a", forms = listOf(RuntimeCatalogForm.V2))
        val genericOnly = entry("b", forms = listOf(RuntimeCatalogForm.Generic))
        val unknownV1 = entry("c", forms = listOf(RuntimeCatalogForm.V1("c.create.v1")))
        val futureOnly = entry("d", forms = listOf(RuntimeCatalogForm.Unknown("v3")))
        val noForms = entry("e", forms = emptyList())
        assertTrue(v2.hasMobileForm())
        // This app retired v1 and the generic payload (task #8): only v2 configures a runtime.
        assertFalse(genericOnly.hasMobileForm())
        assertFalse(unknownV1.hasMobileForm())
        assertFalse(futureOnly.hasMobileForm())
        // Never inferred from absence.
        assertFalse(noForms.hasMobileForm())
        // A build that knows a v1 schema would accept it.
        assertTrue(unknownV1.hasMobileForm { it == RuntimeCatalogForm.V1("c.create.v1") })
    }

    @Test
    fun deprecatedRuntimesAreHiddenForNewAgentsButStillNamed() {
        val catalog = decodeServerRuntimeCatalog(ServerProjection, RuntimeCatalogSource.Server)!!
        val creatable = catalog.runtimes.filter { it.creatableHere() }.map { it.id }
        assertEquals(listOf("claude", "qwen", "qoder", "codex"), creatable)
        val names = RaftRuntimeCatalogNameRegistry()
        names.publish("s1", catalog)
        assertEquals("Copilot CLI", names.entry("copilot", "s1")?.displayName)
        assertTrue(names.entry("copilot", "s1")!!.deprecated)
    }

    @Test
    fun nameLookupFallsBackFromCatalogToBundledToRawId() {
        val names = RaftRuntimeCatalogNameRegistry()
        val catalog =
            ServerRuntimeCatalog(
                catalogVersion = 1,
                runtimes = listOf(entry("claude", displayName = "Claude Code (server)"), entry("newcomer", displayName = "Newcomer")),
                source = RuntimeCatalogSource.Server
            )
        names.publish("s1", catalog)
        assertEquals("Claude Code (server)", names.entry("claude", "s1")?.displayName)
        assertEquals("Newcomer", names.entry("newcomer")?.displayName)
        // Turned off on the server (absent): the bundled table still names it.
        assertEquals("Codex CLI", names.entry("codex", "s1")?.displayName)
        // Known nowhere: no entry, the caller shows the id.
        assertNull(names.entry("mystery", "s1"))
        // Another server without a catalog read yet: bundled.
        assertEquals("Claude Code", names.entry("claude", "s2")?.displayName)
    }

    @Test
    fun staticModelsAreTheWholeListAndFallbackLetsLiveDetectionWin() {
        val live = listOf(RuntimeModelInfo("live", "Live"))
        val static = entry("claude", models = listOf(RuntimeModelInfo("opus", "Opus")), modelsSource = RuntimeCatalogModelsSource.Static)
        val fallback = entry("codex", models = listOf(RuntimeModelInfo("gpt-5", "GPT-5")), modelsSource = RuntimeCatalogModelsSource.Fallback)
        val emptyFallback = entry("qwen", models = emptyList(), modelsSource = RuntimeCatalogModelsSource.Fallback)
        assertEquals(static.models, static.modelsFor(live))
        assertEquals(live, fallback.modelsFor(live))
        assertEquals(fallback.models, fallback.modelsFor(null))
        assertEquals(fallback.models, fallback.modelsFor(emptyList()))
        assertEquals(emptyList(), emptyFallback.modelsFor(null))
    }

    @Test
    fun theRepositoryPrefersTheServerThenTheCacheThenTheBundledTable() =
        runTest {
            val prefs = MapPreferences()
            val api = FakeApi()
            val names = RaftRuntimeCatalogNameRegistry()
            val repository =
                SharedServerRuntimeCatalogRepository(api, backgroundScope, ServerRuntimeCatalogCache(prefs), scopeProvider = { "env|acct" }, names = names)

            // Nothing cached and the server fails: the bundled table.
            api.next = HomeNetworkResponse(code = 503, bodyText = null)
            val bundled = repository.load("s1", DataRequestPriority.Visible)
            assertEquals(RuntimeCatalogSource.Bundled, bundled.source)
            assertEquals(DataRequestPriority.Visible, api.priorities.last())
            assertEquals("servers/s1/runtimes", api.paths.last())

            // The server answers: used and cached.
            api.next = HomeNetworkResponse(code = 200, bodyText = ServerProjection)
            val fresh = repository.load("s1", DataRequestPriority.Background)
            assertEquals(RuntimeCatalogSource.Server, fresh.source)
            assertEquals(DataRequestPriority.Background, api.priorities.last())
            assertNotNull(prefs.values["${SERVER_RUNTIME_CATALOG_CACHE_KEY_PREFIX}env|acct|s1"])
            assertEquals("Qwen Code", names.entry("qwen", "s1")?.displayName)

            // The server fails again (or sends garbage): the cached answer.
            api.next = HomeNetworkResponse(code = 200, bodyText = "<html>")
            val cached = repository.load("s1", DataRequestPriority.Visible)
            assertEquals(RuntimeCatalogSource.Cache, cached.source)
            assertEquals(fresh.runtimes, cached.runtimes)
            // A 200 with JSON that is not a catalog neither overwrites the cache nor empties the names.
            for (body in listOf("{}", """{"error":"x"}""", """{"catalogVersion":0,"runtimes":[]}""")) {
                api.next = HomeNetworkResponse(code = 200, bodyText = body)
                val answer = repository.load("s1", DataRequestPriority.Visible)
                assertEquals(RuntimeCatalogSource.Cache, answer.source, body)
                assertEquals(fresh.runtimes, answer.runtimes, body)
                assertEquals("Qwen Code", names.entry("qwen", "s1")?.displayName, body)
            }
            val requestsBefore = api.paths.size
            api.next = HomeNetworkResponse(code = 403, bodyText = """{"error":"Forbidden"}""")
            assertEquals(RuntimeCatalogSource.Cache, repository.load("s1", DataRequestPriority.Visible).source)
            api.failWith = IllegalStateException("offline")
            assertEquals(RuntimeCatalogSource.Cache, repository.load("s1", DataRequestPriority.Visible).source)
            assertEquals(RuntimeCatalogSource.Cache, repository.cached("s1").source)
            // 403/404 are answered once each; the repository never retries on its own.
            assertEquals(requestsBefore + 2, api.paths.size)

            // The cache is per server (and per account): another server falls back to bundled.
            assertEquals(RuntimeCatalogSource.Bundled, repository.load("s2", DataRequestPriority.Visible).source)
            val otherAccount =
                SharedServerRuntimeCatalogRepository(api, backgroundScope, ServerRuntimeCatalogCache(prefs), scopeProvider = { "env|other" }, names = names)
            assertEquals(RuntimeCatalogSource.Bundled, otherAccount.cached("s1").source)
        }

    @Test
    fun oneRequestPerServerInFlight() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val api = FakeApi(gate = gate)
            api.next = HomeNetworkResponse(code = 200, bodyText = ContractExample)
            val repository =
                SharedServerRuntimeCatalogRepository(api, backgroundScope, cache = null, scopeProvider = { null }, names = RaftRuntimeCatalogNameRegistry())
            val first = async { repository.load("s1", DataRequestPriority.Visible) }
            val second = async { repository.load("s1", DataRequestPriority.Visible) }
            testScheduler.runCurrent()
            gate.complete(Unit)
            assertEquals(first.await(), second.await())
            assertEquals(1, api.paths.size)
        }

    @Test
    fun aCancelledInitiatorDoesNotCancelAnotherWaiter() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val api = FakeApi(gate = gate)
            api.next = HomeNetworkResponse(code = 200, bodyText = ContractExample)
            val repository =
                SharedServerRuntimeCatalogRepository(api, backgroundScope, cache = null, scopeProvider = { null }, names = RaftRuntimeCatalogNameRegistry())
            val initiator = async { repository.load("s1", DataRequestPriority.Visible) }
            testScheduler.runCurrent()
            val waiter = async { repository.load("s1", DataRequestPriority.Visible) }
            testScheduler.runCurrent()
            // The page that started the request closes.
            initiator.cancel()
            testScheduler.runCurrent()
            gate.complete(Unit)
            val catalog = waiter.await()
            assertEquals(RuntimeCatalogSource.Server, catalog.source)
            assertEquals(listOf("kimi-sdk"), catalog.runtimes.map { it.id })
            assertTrue(initiator.isCancelled)
            assertEquals(1, api.paths.size)
        }

    private class MapPreferences : SharedPreferenceStore {
        val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private class FakeApi(private val gate: CompletableDeferred<Unit>? = null) : RaftServerRuntimeCatalogApi {
        var next: HomeNetworkResponse = HomeNetworkResponse(code = 500, bodyText = null)
        var failWith: Exception? = null
        val paths = mutableListOf<String>()
        val priorities = mutableListOf<DataRequestPriority>()

        override suspend fun runtimes(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
            paths += "servers/${serverId.value}/runtimes"
            priorities += priority
            gate?.await()
            failWith?.let { throw it }
            return next
        }
    }

    private fun entry(
        id: String,
        displayName: String = id,
        forms: List<RuntimeCatalogForm> = listOf(RuntimeCatalogForm.V2),
        models: List<RuntimeModelInfo> = emptyList(),
        modelsSource: RuntimeCatalogModelsSource = RuntimeCatalogModelsSource.Fallback
    ) = RuntimeCatalogEntry(id = id, displayName = displayName, forms = forms, models = models, modelsSource = modelsSource)

    companion object {
        /** `docs/contracts/server-runtime-catalog.md` "Response", verbatim (f5fe2ca864). */
        val ContractExample =
            """
            {
              "catalogVersion": 1,
              "runtimes": [{
                "id": "kimi-sdk", "displayName": "Kimi Code", "abbreviation": "KC",
                "binary": "", "supported": true, "experimental": false, "deprecated": false,
                "oarOnly": false,
                "models": [{ "id": "...", "label": "..." }],
                "modelsSource": "fallback",
                "forms": [{ "kind": "v1", "schemaVersion": "kimi-sdk.create.v1" }, { "kind": "v2" }]
              }]
            }
            """.trimIndent()

        /** Rows as `projectServerRuntimeCatalog` builds them (serverRuntimeCatalogService.test.ts, f5fe2ca864). */
        val ServerProjection =
            """
            {"catalogVersion":1,"runtimes":[
              {"id":"claude","displayName":"Claude Code","abbreviation":"CC","binary":"claude","supported":true,"experimental":false,"deprecated":false,"oarOnly":false,"models":[{"id":"opus","label":"Claude Opus"}],"modelsSource":"static","forms":[{"kind":"generic"},{"kind":"v2"}]},
              {"id":"copilot","displayName":"Copilot CLI","abbreviation":"CP","binary":"copilot","supported":true,"experimental":false,"deprecated":true,"oarOnly":false,"models":[],"modelsSource":"static","forms":[{"kind":"generic"},{"kind":"v2"}]},
              {"id":"qwen","displayName":"Qwen Code","abbreviation":"QW","binary":"qwen","supported":true,"experimental":true,"deprecated":false,"oarOnly":true,"models":[],"modelsSource":"fallback","forms":[{"kind":"generic"},{"kind":"v2"}]},
              {"id":"qoder","displayName":"Qoder","abbreviation":"QD","binary":"qodercli","supported":true,"experimental":true,"deprecated":false,"oarOnly":true,"models":[],"modelsSource":"fallback","forms":[{"kind":"generic"},{"kind":"v2"}]},
              {"id":"codex","displayName":"Codex CLI","abbreviation":"CX","binary":"codex","supported":true,"experimental":false,"deprecated":false,"oarOnly":false,"models":[],"modelsSource":"fallback","forms":[{"kind":"generic"},{"kind":"v2"}]}
            ]}
            """.trimIndent()
    }
}
