package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.runPortableCoroutineTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeAccountUsageTest {
    @Test
    fun providerMapMirrorsWebUsageProviderForRuntime() {
        assertEquals(RuntimeAccountUsageProvider.Claude, RuntimeAccountUsageProvider.forRuntime("claude"))
        assertEquals(RuntimeAccountUsageProvider.Codex, RuntimeAccountUsageProvider.forRuntime("codex"))
        assertEquals(RuntimeAccountUsageProvider.Grok, RuntimeAccountUsageProvider.forRuntime("grok"))
        assertEquals(RuntimeAccountUsageProvider.Kimi, RuntimeAccountUsageProvider.forRuntime("kimi"))
        assertEquals(RuntimeAccountUsageProvider.Kimi, RuntimeAccountUsageProvider.forRuntime("kimi-sdk"))
        assertNull(RuntimeAccountUsageProvider.forRuntime("builtin"))
        assertNull(RuntimeAccountUsageProvider.forRuntime("gemini"))
        assertNull(RuntimeAccountUsageProvider.forRuntime(null))
    }

    @Test
    fun freshSnapshotParsesAccountsAndWindowsIgnoringUnknownFields() = runPortableCoroutineTest {
        val api = FakeUsageApi { ok(FRESH_BODY) }
        val read = assertIs<DataResult.Content<RuntimeAccountUsageRead>>(
            SlockComputersApiClient(api).runtimeAccountUsage(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Claude)
        ).data
        assertEquals(listOf("GET servers/s1/machines/m1/runtime-account-usage/claude"), api.calls)
        val available = assertIs<RuntimeAccountUsageRead.Available>(read)
        assertEquals(false, available.stale)
        val account = available.snapshot.accounts.single()
        assertEquals("Max", account.planLabel)
        assertEquals("a***@example.com", account.identityLabel)
        assertEquals(RuntimeAccountUsageHealth.Ok, account.health)
        val window = account.windows.single()
        assertEquals("5h", window.label)
        assertEquals(RuntimeAccountUsageWindowStatus.Ok, window.status)
        assertEquals(0.42, window.usedRatio)
        assertEquals("2026-10-09T09:00:00.000Z", window.resetsAt)
    }

    @Test
    fun missingAndStaleStatesAreExplicit() {
        assertSame(
            RuntimeAccountUsageRead.Missing,
            parse("""{"state":"missing","snapshot":null}""")
        )
        val stale = assertIs<RuntimeAccountUsageRead.Available>(parse(FRESH_BODY.replace("\"fresh\"", "\"stale\"")))
        assertEquals(true, stale.stale)
    }

    @Test
    fun unknownEnumsAndInvalidWindowsDegradeToUnavailableNeverZero() {
        val read = assertIs<RuntimeAccountUsageRead.Available>(
            parse(
                """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","accounts":[
                  {"accountKey":"a","health":"brand_new_health","windows":[
                    {"id":"w1","label":"Unknown status","status":"brand_new","usedRatio":0.5},
                    {"id":"w2","label":"No ratio","status":"ok"},
                    {"id":"w3","label":"Too big","status":"ok","usedRatio":1.7},
                    {"id":"w4","label":"Negative","status":"limit_reached","usedRatio":-0.1},
                    {"id":"w5","label":"String ratio","status":"ok","usedRatio":"0.3"},
                    {"id":"w6","label":"Parse unavailable","status":"parse_unavailable","usedRatio":0.9,"resetsAt":"2026-10-09T09:00:00.000Z"},
                    {"id":"w7","label":"Limit","status":"limit_reached","usedRatio":1.0},
                    {"label":"No id","status":"ok","usedRatio":0.1}
                  ]},
                  {"accountKey":"b","health":"unsupported","windows":[{"id":"x","label":"x","status":"ok","usedRatio":0.2}]}
                ]}}"""
            )
        )
        val first = read.snapshot.accounts[0]
        assertEquals(RuntimeAccountUsageHealth.Unknown, first.health)
        val byId = first.windows.associateBy { it.id }
        assertEquals(listOf("w1", "w2", "w3", "w4", "w5", "w6", "w7"), first.windows.map { it.id })
        listOf("w1", "w2", "w3", "w4", "w5", "w6").forEach { id ->
            val window = byId.getValue(id)
            assertEquals(RuntimeAccountUsageWindowStatus.Unavailable, window.status, id)
            assertNull(window.usedRatio, id)
            assertNull(window.resetsAt, id)
        }
        assertEquals(RuntimeAccountUsageWindowStatus.LimitReached, byId.getValue("w7").status)
        assertEquals(1.0, byId.getValue("w7").usedRatio)
        // Unsupported accounts never draw windows.
        assertEquals(RuntimeAccountUsageHealth.Unsupported, read.snapshot.accounts[1].health)
        assertTrue(read.snapshot.accounts[1].windows.isEmpty())
    }

    @Test
    fun unusableEnvelopeIsContractMismatch() = runPortableCoroutineTest {
        listOf(
            """{"state":"brand_new"}""",
            """{"state":"fresh","snapshot":null}""",
            """{"state":"fresh","snapshot":{"provider":"claude","accounts":[]}}""",
            """[]"""
        ).forEach { body ->
            val error = assertIs<DataResult.Error>(
                SlockComputersApiClient(FakeUsageApi { ok(body) })
                    .runtimeAccountUsage(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Claude),
                body
            )
            assertEquals("computer_runtime_account_usage_contract_mismatch", error.error.code)
        }
    }

    @Test
    fun storeMaps403ToForbiddenAndServerErrorsToRetryableFailure() = runPortableCoroutineTest {
        val forbidden = SharedRuntimeAccountUsageStore(SlockComputersApiClient(FakeUsageApi { status(403) }))
        assertSame(RuntimeAccountUsageLoad.Forbidden, forbidden.read(key()))
        val failing = SharedRuntimeAccountUsageStore(SlockComputersApiClient(FakeUsageApi { status(500) }))
        val failed = assertIs<RuntimeAccountUsageLoad.Failed>(failing.read(key()))
        assertEquals("server_unavailable", failed.code)
        assertEquals(true, failed.retryable)
        val unauthenticated = SharedRuntimeAccountUsageStore(SlockComputersApiClient(FakeUsageApi { status(401) }))
        assertEquals("auth_required", assertIs<RuntimeAccountUsageLoad.Failed>(unauthenticated.read(key())).code)
    }

    @Test
    fun storeCachesSixtySecondsPerKeyAndForceReReads() = runPortableCoroutineTest {
        var now = 1_000L
        val api = FakeUsageApi { ok(FRESH_BODY) }
        val store = SharedRuntimeAccountUsageStore(SlockComputersApiClient(api), clock = { now })
        store.read(key())
        store.read(key())
        assertEquals(1, api.calls.size)
        now += RUNTIME_ACCOUNT_USAGE_CACHE_MILLIS - 1
        store.read(key())
        assertEquals(1, api.calls.size)
        now += 1
        store.read(key())
        assertEquals(2, api.calls.size)
        store.read(key(), force = true)
        assertEquals(3, api.calls.size)
        store.clear()
        assertNull(store.peek(key()))
    }

    @Test
    fun cacheIsIsolatedByIdentityServerMachineAndProvider() = runPortableCoroutineTest {
        val api = FakeUsageApi { request ->
            if (request.contains("/m-denied/")) status(403) else ok(FRESH_BODY)
        }
        val store = SharedRuntimeAccountUsageStore(SlockComputersApiClient(api), clock = { 0L })
        val base = key()
        store.read(base)
        val variants =
            listOf(
                base.copy(owner = base.owner.copy(accountId = "user-2")),
                base.copy(owner = base.owner.copy(sessionGeneration = "gen-2")),
                base.copy(owner = base.owner.copy(environmentId = "https://staging.example")),
                base.copy(serverId = "s2"),
                base.copy(machineId = "m2"),
                base.copy(provider = RuntimeAccountUsageProvider.Codex)
            )
        variants.forEach { variant ->
            assertNull(store.peek(variant), variant.toString())
            store.read(variant)
        }
        // Every subject was fetched on its own; none reused the first subject's cache entry.
        assertEquals(1 + variants.size, api.calls.size)
        // A forbidden Computer does not poison (or get served) another Computer's entry.
        assertSame(RuntimeAccountUsageLoad.Forbidden, store.read(base.copy(machineId = "m-denied")))
        assertIs<RuntimeAccountUsageLoad.Loaded>(store.peek(base))
    }

    @Test
    fun concurrentReadsOfOneKeyShareOneRequestAndOtherKeysDoNot() = runPortableCoroutineTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeUsageApi(beforeRespond = { gate.await() }) { ok(FRESH_BODY) }
        val store = SharedRuntimeAccountUsageStore(SlockComputersApiClient(api), clock = { 0L })
        val first = async(start = CoroutineStart.UNDISPATCHED) { store.read(key()) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.read(key()) }
        val other = async(start = CoroutineStart.UNDISPATCHED) { store.read(key().copy(serverId = "s2")) }
        yield()
        gate.complete(Unit)
        assertEquals(first.await(), second.await())
        other.await()
        assertEquals(2, api.calls.size)
    }

    @Test
    fun cancelledReadForAnOldSubjectNeverPublishesAndNextReadRefetches() = runPortableCoroutineTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeUsageApi(beforeRespond = { gate.await() }) { ok(FRESH_BODY) }
        val store = SharedRuntimeAccountUsageStore(SlockComputersApiClient(api), clock = { 0L })
        // The UI cancels the old subject's read when the key changes (LaunchedEffect restart).
        val old = async(start = CoroutineStart.UNDISPATCHED) { store.read(key()) }
        yield()
        old.cancel()
        gate.complete(Unit)
        assertNull(store.peek(key()))
        assertIs<RuntimeAccountUsageLoad.Loaded>(store.read(key()))
        assertEquals(2, api.calls.size)
    }

    private fun parse(body: String): RuntimeAccountUsageRead? =
        computersApiJson.parseToJsonElement(body).toRuntimeAccountUsageRead()

    private fun key(): RuntimeAccountUsageKey =
        RuntimeAccountUsageKey(
            owner = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1"),
            serverId = "s1",
            machineId = "m1",
            provider = RuntimeAccountUsageProvider.Claude
        )

    private class FakeUsageApi(
        private val beforeRespond: suspend () -> Unit = {},
        private val respond: (String) -> HomeNetworkResponse
    ) : SlockComputersApi {
        val calls = mutableListOf<String>()

        override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse {
            val request = "GET servers/${serverId.value}/machines/$machineId/runtime-account-usage/$provider"
            calls += request
            beforeRespond()
            return respond(request)
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = unexpected()
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = unexpected()
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = unexpected()
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = unexpected()
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = unexpected()
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = unexpected()
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = unexpected()
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse =
            unexpected()

        private fun unexpected(): Nothing = error("only the usage read is expected")
    }

    private companion object {
        fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)

        fun status(code: Int) = HomeNetworkResponse(code = code, bodyText = """{"error":"x"}""")

        const val FRESH_BODY =
            """{"state":"fresh","futureField":1,"snapshot":{"protocolVersion":2,"provider":"claude","collectedAt":"2026-10-09T04:00:00.000Z","staleAfter":"2026-10-09T04:10:00.000Z","collectorVersion":"1","accounts":[{"accountKey":"acct-1","maskedLabel":"a***@example.com","planLabel":"Max","health":"ok","extra":true,"windows":[{"id":"five_hour","label":"5h","status":"ok","usedRatio":0.42,"resetsAt":"2026-10-09T09:00:00.000Z","extra":"y"}]}]}}"""
    }
}
