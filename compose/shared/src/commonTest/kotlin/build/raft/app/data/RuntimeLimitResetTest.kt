package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.runPortableCoroutineTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * slock #13504 (staging a6885e181): an account's `limitResetsAvailable`, the limit-reset route
 * (packages/server/src/routes/runtimeAuthRoutes.ts, runtimeLimitReset.api.test.ts) and the
 * runtime-auth view its action is gated on.
 */
class RuntimeLimitResetTest {
    @Test
    fun accountCarriesItsLimitResetsOnlyWhenTheCountIsAWholeNumberInRange() {
        fun resets(value: String): Int? =
            assertIs<RuntimeAccountUsageRead.Available>(
                Json.parseToJsonElement(usageBody(value)).toRuntimeAccountUsageRead()
            ).snapshot.accounts.single().limitResetsAvailable

        assertEquals(2, resets("2"))
        assertEquals(0, resets("0"))
        assertEquals(999, resets("999"))
        assertNull(resets("1000"))
        assertNull(resets("-1"))
        assertNull(resets("1.5"))
        assertNull(resets("\"2\""))
        assertNull(resets("null"))
        // Absent: an older Computer, or a runtime that reports none.
        val absent =
            assertIs<RuntimeAccountUsageRead.Available>(Json.parseToJsonElement(FRESH_WITHOUT_RESETS).toRuntimeAccountUsageRead())
        assertNull(absent.snapshot.accounts.single().limitResetsAvailable)
    }

    @Test
    fun resultParsesEveryKindAndRefusesWhatTheStrictSchemaRefuses() {
        fun parse(json: String) = Json.parseToJsonElement(json).toRuntimeLimitResetResult()

        assertEquals(RuntimeLimitResetResult.Reset, parse("""{"kind":"reset"}"""))
        assertEquals(RuntimeLimitResetResult.NothingToReset, parse("""{"kind":"nothing_to_reset"}"""))
        assertEquals(RuntimeLimitResetResult.NoReset, parse("""{"kind":"no_reset"}"""))
        assertEquals(RuntimeLimitResetResult.AlreadyRedeemed, parse("""{"kind":"already_redeemed"}"""))
        assertEquals(RuntimeLimitResetResult.Unknown, parse("""{"kind":"unknown"}"""))
        assertEquals(
            RuntimeLimitResetResult.ReauthRequired("credentials_rejected"),
            parse("""{"kind":"reauth_required","reason":"credentials_rejected"}"""),
        )
        assertEquals(RuntimeLimitResetResult.Unsupported("not_installed"), parse("""{"kind":"unsupported","reason":"not_installed"}"""))
        assertNull(parse("""{"kind":"something_new"}"""))
        assertNull(parse("""{"kind":"reset","extra":true}"""))
        assertNull(parse("""{"kind":"reauth_required"}"""))
        assertNull(parse("""{"kind":"unsupported","reason":"brand_new"}"""))
        assertNull(parse("""{"result":"reset"}"""))
    }

    @Test
    fun onlyUnknownLeavesTheAttemptOpen() {
        assertTrue(RuntimeLimitResetResult.Reset.settlesAttempt)
        assertTrue(RuntimeLimitResetResult.ReauthRequired("scope_missing").settlesAttempt)
        assertTrue(!RuntimeLimitResetResult.Unknown.settlesAttempt)
        assertTrue(!(null as RuntimeLimitResetResult?).settlesAttempt)
    }

    @Test
    fun redeemPostsTheKeyAndReadsTheOutcomeWithTheFreshUsage() = runPortableCoroutineTest<Unit> {
        val api = FakeLimitResetApi(redeem = { ok("""{"result":{"kind":"reset"},"snapshot":${snapshot(1)}}""") })
        val key = assertNotNull(RuntimeLimitResetIdempotencyKey.of(KEY))
        val load = RaftComputersApiClient(api).redeemRuntimeLimitReset(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Codex, key)
        assertEquals(listOf("""POST servers/s1/machines/m1/runtime-account-usage/codex/limit-reset {"idempotencyKey":"$KEY"}"""), api.calls)
        val reply = assertIs<RuntimeLimitResetLoad.Done>(load).reply
        assertEquals(RuntimeLimitResetResult.Reset, reply.result)
        val account = assertNotNull(reply.snapshot).accounts.single()
        assertEquals(1, account.limitResetsAvailable)
        assertEquals("tea****r@example.com", account.identityLabel)
    }

    @Test
    fun aNullOrUnreadableSnapshotStillCarriesTheOutcome() = runPortableCoroutineTest<Unit> {
        val client = RaftComputersApiClient(FakeLimitResetApi(redeem = { ok("""{"result":{"kind":"nothing_to_reset"},"snapshot":null}""") }))
        val reply = assertIs<RuntimeLimitResetLoad.Done>(client.redeemRuntimeLimitReset(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Codex, key())).reply
        assertEquals(RuntimeLimitResetResult.NothingToReset, reply.result)
        assertNull(reply.snapshot)
        val broken = RaftComputersApiClient(FakeLimitResetApi(redeem = { ok("""{"result":{"kind":"unknown"},"snapshot":{"broken":true}}""") }))
        val brokenReply =
            assertIs<RuntimeLimitResetLoad.Done>(broken.redeemRuntimeLimitReset(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Codex, key())).reply
        assertEquals(RuntimeLimitResetResult.Unknown, brokenReply.result)
        assertNull(brokenReply.snapshot)
    }

    @Test
    fun anAnswerWithoutAnOutcomeKeepsTheStatusAndTheRoutesCode() = runPortableCoroutineTest<Unit> {
        suspend fun refusal(response: HomeNetworkResponse): RuntimeLimitResetRefusal =
            assertIs<RuntimeLimitResetLoad.Failed>(
                RaftComputersApiClient(FakeLimitResetApi(redeem = { response }))
                    .redeemRuntimeLimitReset(ServerId("s1"), "m1", RuntimeAccountUsageProvider.Codex, key())
            ).refusal

        assertEquals(
            RuntimeLimitResetRefusal(409, "computer_offline"),
            refusal(HomeNetworkResponse(409, null, errorBodyText = """{"code":"computer_offline","error":"This Computer is offline."}""")),
        )
        assertEquals(
            RuntimeLimitResetRefusal(409, "computer_upgrade_required"),
            refusal(HomeNetworkResponse(409, """{"code":"computer_upgrade_required","error":"Upgrade"}""")),
        )
        assertEquals(RuntimeLimitResetRefusal(403, "forbidden"), refusal(HomeNetworkResponse(403, """{"code":"forbidden","error":"Only"}""")))
        assertEquals(RuntimeLimitResetRefusal(504, "computer_timeout"), refusal(HomeNetworkResponse(504, """{"error":"x","code":"computer_timeout"}""")))
        assertEquals(RuntimeLimitResetRefusal(500, null), refusal(HomeNetworkResponse(500, """{"error":"Failed to use a limit reset"}""")))
        assertEquals(RuntimeLimitResetRefusal(200, "malformed_response"), refusal(ok("""{"result":{"kind":"something_new"}}""")))
    }

    @Test
    fun idempotencyKeyHasTheServersShape() {
        assertNull(RuntimeLimitResetIdempotencyKey.of("short"))
        assertNull(RuntimeLimitResetIdempotencyKey.of("has spaces in it ok"))
        assertNotNull(RuntimeLimitResetIdempotencyKey.of(KEY))
        val minted = RuntimeLimitResetIdempotencyKey.mint()
        assertNotNull(RuntimeLimitResetIdempotencyKey.of(minted.value))
        assertNotEquals(minted, RuntimeLimitResetIdempotencyKey.mint())
    }

    @Test
    fun runtimeAuthViewReadsCanActAvailabilityAndEachRuntimesSignIn() {
        val view =
            assertNotNull(
                Json.parseToJsonElement(
                    """{"status":{"collectedAt":"2026-11-07T10:00:00.000Z","runtimes":{"codex":{"kind":"logged_in","account":{"email":"t***@example.com"}},"claude":{"kind":"logged_out"}}},"availability":"ok","runtimes":["claude","codex"],"canAct":true}"""
                ).toRuntimeAuthView()
            )
        assertEquals(true, view.canAct)
        assertEquals(RuntimeAuthAvailability.Ok, view.availability)
        assertEquals(mapOf("codex" to "logged_in", "claude" to "logged_out"), view.statusKinds)
        val unreported = assertNotNull(Json.parseToJsonElement("""{"status":null,"availability":"computer_offline","runtimes":[],"canAct":false}""").toRuntimeAuthView())
        assertEquals(RuntimeAuthAvailability.ComputerOffline, unreported.availability)
        assertEquals(emptyMap(), unreported.statusKinds)
        assertNull(Json.parseToJsonElement("""{"availability":"ok"}""").toRuntimeAuthView())
        assertNull(Json.parseToJsonElement("""{"availability":"brand_new","canAct":true}""").toRuntimeAuthView())
    }

    @Test
    fun storeWritesTheFreshUsageToTheReadCacheAndKeepsOneKeyPerAttempt() = runPortableCoroutineTest<Unit> {
        val api =
            FakeLimitResetApi(
                usage = { ok(usageBody("2")) },
                redeem = { ok("""{"result":{"kind":"reset"},"snapshot":${snapshot(1)}}""") },
            )
        val store = SharedRuntimeAccountUsageStore(RaftComputersApiClient(api), currentIdentity = { OWNER }, clock = { 0L })
        val usageKey = usageKey()
        store.read(usageKey)
        val attempt = store.limitResetAttemptKey(usageKey)
        assertEquals(attempt, store.limitResetAttemptKey(usageKey), "kept until the attempt settles")
        assertIs<RuntimeLimitResetLoad.Done>(store.redeemLimitReset(usageKey, attempt))
        val cached = assertIs<RuntimeAccountUsageLoad.Loaded>(store.read(usageKey)).read
        assertEquals(1, assertIs<RuntimeAccountUsageRead.Available>(cached).snapshot.accounts.single().limitResetsAvailable)
        assertEquals(1, api.calls.count { it.startsWith("GET") }, "the reset's read is served from the cache")
        store.forgetLimitResetAttempt(usageKey)
        assertNotEquals(attempt, store.limitResetAttemptKey(usageKey))
    }

    @Test
    fun storeNeverRedeemsForASignedOutIdentity() = runPortableCoroutineTest<Unit> {
        val api = FakeLimitResetApi(redeem = { error("no request for another identity") })
        val store = SharedRuntimeAccountUsageStore(RaftComputersApiClient(api), currentIdentity = { null })
        assertEquals(RuntimeLimitResetLoad.Superseded, store.redeemLimitReset(usageKey(), key()))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun storeOffersNoViewToAViewerTheServerRefuses() = runPortableCoroutineTest<Unit> {
        val api = FakeLimitResetApi(auth = { HomeNetworkResponse(403, """{"error":"Only the person who connected this Computer"}""") })
        val store = SharedRuntimeAccountUsageStore(RaftComputersApiClient(api), currentIdentity = { OWNER }, clock = { 0L })
        assertNull(store.runtimeAuthView(usageKey()))
        assertNull(store.runtimeAuthView(usageKey()))
        assertEquals(listOf("GET servers/s1/machines/m1/runtime-auth"), api.calls, "a refusal is remembered for the cache window")
    }

    private fun key() = assertNotNull(RuntimeLimitResetIdempotencyKey.of(KEY))

    private fun usageKey() = RuntimeAccountUsageKey(OWNER, "s1", "m1", RuntimeAccountUsageProvider.Codex)

    internal class FakeLimitResetApi(
        private val usage: () -> HomeNetworkResponse = { error("no usage read expected") },
        private val auth: () -> HomeNetworkResponse = { error("no runtime-auth read expected") },
        private val redeem: () -> HomeNetworkResponse = { error("no redeem expected") },
        private val refresh: () -> HomeNetworkResponse = { error("no refresh expected") },
    ) : RaftComputersApi {
        val calls = mutableListOf<String>()

        override suspend fun refreshRuntimeAccountUsage(
            serverId: ServerId,
            machineId: String,
            provider: String,
            body: String,
            priority: DataRequestPriority
        ): HomeNetworkResponse {
            calls += "POST servers/${serverId.value}/machines/$machineId/runtime-account-usage/$provider/refresh $body"
            return refresh()
        }

        override suspend fun runtimeAccountUsage(serverId: ServerId, machineId: String, provider: String): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines/$machineId/runtime-account-usage/$provider"
            return usage()
        }

        override suspend fun runtimeAuth(serverId: ServerId, machineId: String): HomeNetworkResponse {
            calls += "GET servers/${serverId.value}/machines/$machineId/runtime-auth"
            return auth()
        }

        override suspend fun redeemRuntimeLimitReset(serverId: ServerId, machineId: String, provider: String, body: String): HomeNetworkResponse {
            calls += "POST servers/${serverId.value}/machines/$machineId/runtime-account-usage/$provider/limit-reset $body"
            return redeem()
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

        private fun unexpected(): Nothing = error("unexpected request")
    }

    internal companion object {
        val OWNER = SharedComputersOwner(environmentId = "https://api.example", accountId = "user-1", sessionGeneration = "gen-1")

        const val KEY = "0f8a2b6e-3c1d-4e5f-9a7b-1c2d3e4f5a6b"

        fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)

        /** slock runtimeLimitReset.api.test.ts `snapshot(resets)`. */
        fun snapshot(resets: Int): String =
            """{"protocolVersion":2,"provider":"codex","collectedAt":"2026-11-07T10:00:00.000Z","staleAfter":"2026-11-07T10:30:00.000Z","collectorVersion":"test","accounts":[{"accountKey":"${"a".repeat(64)}","maskedLabel":"tea****r@example.com","health":"ok","limitResetsAvailable":$resets,"windows":[]}]}"""

        fun usageBody(resets: String, health: String = "ok", windowStatus: String = "ok"): String =
            """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-11-07T10:00:00.000Z","staleAfter":"2026-11-07T10:30:00.000Z","accounts":[{"accountKey":"acct-1","maskedLabel":"tea****r@example.com","planLabel":"Pro","health":"$health","limitResetsAvailable":$resets,"windows":[{"id":"five_hour","label":"5-hour limit","status":"$windowStatus","usedRatio":${if (windowStatus == "limit_reached") "1" else "0.4"},"resetsAt":"2026-11-07T12:00:00.000Z"}]}]}}"""

        const val FRESH_WITHOUT_RESETS =
            """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-11-07T10:00:00.000Z","accounts":[{"accountKey":"acct-1","health":"ok","windows":[]}]}}"""
    }
}
