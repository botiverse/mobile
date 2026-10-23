package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.runPortableCoroutineTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
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
    fun freshSnapshotParsesAccountsAndWindowsIgnoringUnknownFields() = runPortableCoroutineTest<Unit> {
        val api = FakeUsageApi { ok(FRESH_BODY) }
        val read = assertIs<DataResult.Content<RuntimeAccountUsageRead>>(
            RaftComputersApiClient(api).runtimeAccountUsage(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Claude)
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
    fun unusableEnvelopeIsContractMismatch() = runPortableCoroutineTest<Unit> {
        listOf(
            """{"state":"brand_new"}""",
            """{"state":"fresh","snapshot":null}""",
            """{"state":"fresh","snapshot":{"provider":"claude","accounts":[]}}""",
            """[]"""
        ).forEach { body ->
            val error = assertIs<DataResult.Error>(
                RaftComputersApiClient(FakeUsageApi { ok(body) })
                    .runtimeAccountUsage(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Claude),
                body
            )
            assertEquals("computer_runtime_account_usage_contract_mismatch", error.error.code)
        }
    }

    @Test
    fun storeMaps403ToForbiddenAndServerErrorsToRetryableFailure() = runPortableCoroutineTest<Unit> {
        val forbidden = store(FakeUsageApi { status(403) })
        assertSame(RuntimeAccountUsageLoad.Forbidden, forbidden.read(key()))
        val failing = store(FakeUsageApi { status(500) })
        val failed = assertIs<RuntimeAccountUsageLoad.Failed>(failing.read(key()))
        assertEquals("server_unavailable", failed.code)
        assertEquals(true, failed.retryable)
        val unauthenticated = store(FakeUsageApi { status(401) })
        assertEquals("auth_required", assertIs<RuntimeAccountUsageLoad.Failed>(unauthenticated.read(key())).code)
    }

    @Test
    fun storeCachesSixtySecondsPerKeyAndForceReReads() = runPortableCoroutineTest<Unit> {
        var now = 1_000L
        val api = FakeUsageApi { ok(FRESH_BODY) }
        val store = store(api, clock = { now })
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
    fun cacheIsIsolatedByIdentityServerMachineAndProvider() = runPortableCoroutineTest<Unit> {
        val api = FakeUsageApi { request ->
            if (request.contains("/m-denied/")) status(403) else ok(FRESH_BODY)
        }
        val store = store(api)
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
            signedIn = variant.owner
            store.read(variant)
        }
        signedIn = base.owner
        // Every subject was fetched on its own; none reused the first subject's cache entry.
        assertEquals(1 + variants.size, api.calls.size)
        // A forbidden Computer does not poison (or get served) another Computer's entry.
        assertSame(RuntimeAccountUsageLoad.Forbidden, store.read(base.copy(machineId = "m-denied")))
        assertIs<RuntimeAccountUsageLoad.Loaded>(store.peek(base))
    }

    @Test
    fun concurrentReadsOfOneKeyShareOneRequestAndOtherKeysDoNot() = runPortableCoroutineTest<Unit> {
        val gate = CompletableDeferred<Unit>()
        val api = FakeUsageApi(beforeRespond = { gate.await() }) { ok(FRESH_BODY) }
        val store = store(api)
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
    fun cancelledReadForAnOldSubjectNeverPublishesAndNextReadRefetches() = runPortableCoroutineTest<Unit> {
        val gate = CompletableDeferred<Unit>()
        val api = FakeUsageApi(beforeRespond = { gate.await() }) { ok(FRESH_BODY) }
        val store = store(api)
        // The UI cancels the old subject's read when the key changes (LaunchedEffect restart).
        val old = async(start = CoroutineStart.UNDISPATCHED) { store.read(key()) }
        yield()
        old.cancel()
        gate.complete(Unit)
        assertNull(store.peek(key()))
        assertIs<RuntimeAccountUsageLoad.Loaded>(store.read(key()))
        assertEquals(2, api.calls.size)
    }

    @Test
    fun accountSwitchWhileReadingDropsTheResponseAndNeverCachesIt() = runPortableCoroutineTest<Unit> {
        // The page stays open; the GET is sent as user-1 but the session becomes user-2 before
        // the response arrives, so the response may be user-2's and must not be published.
        val api = FakeUsageApi(beforeRespond = { signedIn = key().owner.copy(accountId = "user-2", sessionGeneration = "gen-9") }) {
            ok(FRESH_BODY)
        }
        val store = store(api)
        assertSame(RuntimeAccountUsageLoad.Superseded, store.read(key()))
        assertEquals(1, api.calls.size)
        assertNull(store.peek(key()))
        // The old owner is refused up front from now on, without a request.
        assertSame(RuntimeAccountUsageLoad.Superseded, store.read(key(), force = true))
        assertEquals(1, api.calls.size)
        // The re-resolved owner reads for itself.
        val switched = key().copy(owner = signedIn!!)
        assertIs<RuntimeAccountUsageLoad.Loaded>(store.read(switched))
        assertEquals(2, api.calls.size)
    }

    @Test
    fun cachedUsageIsNotServedToAnOwnerThatIsNoLongerSignedIn() = runPortableCoroutineTest<Unit> {
        val api = FakeUsageApi { ok(FRESH_BODY) }
        val store = store(api)
        assertIs<RuntimeAccountUsageLoad.Loaded>(store.read(key()))
        signedIn = key().owner.copy(accountId = "user-2")
        assertSame(RuntimeAccountUsageLoad.Superseded, store.read(key()))
        signedIn = null
        assertSame(RuntimeAccountUsageLoad.Superseded, store.read(key()))
        assertEquals(1, api.calls.size)
    }

    @Test
    fun tokenRotationForTheSameAccountIsNotAnIdentityChange() = runPortableCoroutineTest<Unit> {
        val api = FakeUsageApi(beforeRespond = { signedIn = key().owner.copy(sessionGeneration = "gen-2") }) { ok(FRESH_BODY) }
        assertIs<RuntimeAccountUsageLoad.Loaded>(store(api).read(key()))
    }

    /** The identity the fake session currently authenticates as. */
    // Shapes of slock packages/server/src/routes/servers.ts POST …/runtime-account-usage/:provider/refresh
    // (staging 2026-10-23): 202 {accepted:false,state:"cooldown"}, 202 {accepted,state:"requested"|"computer_offline"},
    // 200 {accepted:true,state:"fresh",snapshot}, 200 {accepted:true,state:"timeout"}.
    @Test
    fun refreshEnvelopesParseAndUnknownStatesAreRejected() {
        val fresh = parseRefresh(FRESH_REFRESH_BODY)
        assertEquals(RuntimeAccountUsageRefreshState.Fresh, fresh?.state)
        assertEquals(true, fresh?.accepted)
        assertEquals("acct-1", fresh?.snapshot?.accounts?.single()?.accountKey)
        assertEquals(RuntimeAccountUsageRefreshState.Cooldown, parseRefresh("""{"accepted":false,"state":"cooldown"}""")?.state)
        assertEquals(RuntimeAccountUsageRefreshState.Requested, parseRefresh("""{"accepted":true,"state":"requested"}""")?.state)
        assertEquals(false, parseRefresh("""{"accepted":false,"state":"computer_offline"}""")?.accepted)
        val timeout = parseRefresh("""{"accepted":true,"state":"timeout"}""")
        assertEquals(RuntimeAccountUsageRefreshState.Timeout, timeout?.state)
        assertNull(timeout?.snapshot)
        assertNull(parseRefresh("""{"accepted":true,"state":"queued"}"""))
        assertNull(parseRefresh("""{"accepted":"yes","state":"requested"}"""))
        assertNull(parseRefresh("""{"data":{"accepted":true,"state":"requested"}}"""))
    }

    @Test
    fun manualRefreshPostsTheReasonCachesAFreshSnapshotAndOpensTheWindow() = runPortableCoroutineTest<Unit> {
        var now = 5_000L
        val api = FakeUsageApi(refreshRespond = { ok(FRESH_REFRESH_BODY) }) { ok(FRESH_BODY) }
        val store = store(api, clock = { now })
        val done = assertIs<RuntimeAccountUsageRefreshLoad.Done>(store.refresh(key(), RuntimeAccountUsageRefreshReason.Manual))
        assertEquals(RuntimeAccountUsageRefreshState.Fresh, done.refresh.state)
        assertEquals(
            listOf("POST servers/s1/machines/m1/runtime-account-usage/claude/refresh {\"reason\":\"manual\"} Foreground"),
            api.calls
        )
        val cached = assertIs<RuntimeAccountUsageLoad.Loaded>(store.peek(key()))
        assertIs<RuntimeAccountUsageRead.Available>(cached.read)
        assertEquals(RUNTIME_ACCOUNT_USAGE_REFRESH_COOLDOWN_MILLIS, store.refreshCooldownRemainingMillis(key()))
        // Inside the window: cooldown without asking the server.
        now += RUNTIME_ACCOUNT_USAGE_REFRESH_COOLDOWN_MILLIS - 1
        val cooled = assertIs<RuntimeAccountUsageRefreshLoad.Done>(store.refresh(key(), RuntimeAccountUsageRefreshReason.Manual))
        assertEquals(RuntimeAccountUsageRefreshState.Cooldown, cooled.refresh.state)
        assertEquals(1, api.calls.size)
        now += 1
        assertEquals(0L, store.refreshCooldownRemainingMillis(key()))
        store.refresh(key(), RuntimeAccountUsageRefreshReason.StaleOrMissing)
        assertEquals(
            "POST servers/s1/machines/m1/runtime-account-usage/claude/refresh {\"reason\":\"stale_or_missing\"} Background",
            api.calls.last()
        )
    }

    @Test
    fun refreshMaps403ToForbidden() = runPortableCoroutineTest<Unit> {
        val store = store(FakeUsageApi(refreshRespond = { status(403) }) { ok(FRESH_BODY) })
        assertSame(RuntimeAccountUsageRefreshLoad.Forbidden, store.refresh(key(), RuntimeAccountUsageRefreshReason.Manual))
    }

    private fun parseRefresh(body: String): RuntimeAccountUsageRefresh? =
        Json.parseToJsonElement(body).toRuntimeAccountUsageRefresh()

    private var signedIn: SharedComputersOwner? = key().owner

    private fun store(api: FakeUsageApi, clock: RaftEpochMillisSource = RaftEpochMillisSource { 0L }) =
        SharedRuntimeAccountUsageStore(
            client = RaftComputersApiClient(api),
            currentIdentity = { signedIn },
            clock = clock
        )

    private fun parse(body: String): RuntimeAccountUsageRead? =
        Json.parseToJsonElement(body).toRuntimeAccountUsageRead()

    private fun key(): RuntimeAccountUsageKey =
        RuntimeAccountUsageKey(
            owner = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1"),
            serverId = "s1",
            machineId = "m1",
            provider = RuntimeAccountUsageProvider.Claude
        )

    private class FakeUsageApi(
        private val beforeRespond: suspend () -> Unit = {},
        private val refreshRespond: (String) -> HomeNetworkResponse = { error("no refresh expected") },
        private val respond: (String) -> HomeNetworkResponse
    ) : RaftComputersApi {
        val calls = mutableListOf<String>()

        override suspend fun refreshRuntimeAccountUsage(
            serverId: ServerId,
            machineId: String,
            provider: String,
            body: String,
            priority: DataRequestPriority
        ): HomeNetworkResponse {
            val request = "POST servers/${serverId.value}/machines/$machineId/runtime-account-usage/$provider/refresh $body $priority"
            calls += request
            return refreshRespond(request)
        }

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

        const val FRESH_REFRESH_BODY =
            """{"accepted":true,"state":"fresh","snapshot":{"protocolVersion":2,"provider":"claude","collectedAt":"2026-10-09T04:00:00.000Z","accounts":[{"accountKey":"acct-1","planLabel":"Max","health":"ok","windows":[{"id":"five_hour","label":"5h","status":"ok","usedRatio":0.5,"resetsAt":"2026-10-09T09:00:00.000Z"}]}]}}"""

        const val FRESH_BODY =
            """{"state":"fresh","futureField":1,"snapshot":{"protocolVersion":2,"provider":"claude","collectedAt":"2026-10-09T04:00:00.000Z","staleAfter":"2026-10-09T04:10:00.000Z","collectorVersion":"1","accounts":[{"accountKey":"acct-1","maskedLabel":"a***@example.com","planLabel":"Max","health":"ok","extra":true,"windows":[{"id":"five_hour","label":"5h","status":"ok","usedRatio":0.42,"resetsAt":"2026-10-09T09:00:00.000Z","extra":"y"}]}]}}"""
    }
}
