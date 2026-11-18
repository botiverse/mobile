package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RAFT_CLIENT_CAPABILITIES_HEADER
import build.raft.app.data.RaftComputersNetworkApi
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageCredits
import build.raft.app.data.RuntimeAccountUsageCreditsLine
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowFill
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RuntimeQuotaLineCopy
import build.raft.app.data.RuntimeQuotaLinePart
import build.raft.app.data.creditsLine
import build.raft.app.data.needsUsageAttention
import build.raft.app.data.runtimeQuotaLineParts
import build.raft.app.data.summarizeRuntimeQuota
import build.raft.app.data.toRuntimeAccountUsageRead
import build.raft.app.data.toRuntimeAccountUsageRefresh
import build.raft.app.data.windowFill
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftStatusVariant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * slock #15374: a Codex account's `credits` (OAR's), served only to a request declaring
 * `runtime-usage-credits@1`. A full window the credits carry is "limit reached, using credits",
 * never "used up"; absent credits (an older server or Computer) read as before.
 */
class RuntimeUsageCreditsTest {
    // --- The capability on the wire -------------------------------------------------------------

    @Test
    fun everyRequestAnsweredWithAUsageSnapshotDeclaresCreditsAndWindowScope() = runPortableCoroutineTest<Unit> {
        val client = RecordingClient()
        val api = RaftComputersNetworkApi(client)
        val server = ServerId("s1")
        api.runtimeAccountUsage(server, "m1", "codex")
        api.refreshRuntimeAccountUsage(server, "m1", "codex", """{"reason":"manual"}""", DataRequestPriority.Foreground)
        api.redeemRuntimeLimitReset(server, "m1", "codex", """{"idempotencyKey":"k"}""")
        assertEquals(
            listOf(
                "GET servers/s1/machines/m1/runtime-account-usage/codex",
                "POST servers/s1/machines/m1/runtime-account-usage/codex/refresh",
                "POST servers/s1/machines/m1/runtime-account-usage/codex/limit-reset",
            ),
            client.calls.map { it.first },
        )
        client.calls.forEach { (call, headers) ->
            val raw = headers[RAFT_CLIENT_CAPABILITIES_HEADER]
            // slock `declaresClientCapability`: the header split on "," with each token trimmed.
            val tokens = raw?.split(",")?.map { it.trim() }.orEmpty()
            assertTrue("runtime-usage-credits@1" in tokens, "$call: $raw")
            assertTrue("runtime-usage-window-scope@2" in tokens, "$call keeps the window scope: $raw")
        }
        // A request that is not a usage read does not declare it.
        api.runtimeModels(server, "m1", "codex")
        assertNull(client.calls.last().second[RAFT_CLIENT_CAPABILITIES_HEADER])
    }

    // --- Parsing ---------------------------------------------------------------------------------

    @Test
    fun creditsParseWhenPresentAndStayUnknownWhenAbsent() {
        val present = account("""{"available":true,"unlimited":false,"balance":"30784"}""")
        assertEquals(RuntimeAccountUsageCredits(available = true, unlimited = false, balance = "30784"), present.credits)
        // Absent: unknown, and the account otherwise parses as before.
        val absent = account(null)
        assertNull(absent.credits)
        assertEquals(RuntimeAccountUsageHealth.Ok, absent.health)
        assertEquals(1, absent.windows.size)
    }

    @Test
    fun unlimitedAndAMissingOrOddBalanceAreReadAsTheServerAllows() {
        assertEquals(RuntimeAccountUsageCredits(available = true, unlimited = true, balance = null), account("""{"available":true,"unlimited":true}""").credits)
        // balance null or missing: no balance, never the credits dropped.
        assertEquals(RuntimeAccountUsageCredits(available = true), account("""{"available":true,"balance":null}""").credits)
        assertEquals(RuntimeAccountUsageCredits(available = false, balance = "-3"), account("""{"available":false,"balance":"-3"}""").credits)
        assertEquals(RuntimeAccountUsageCredits(available = true, balance = "1,234.50"), account("""{"available":true,"balance":"1,234.50"}""").credits)
        // A balance that is no number string (or a number, not a string) is dropped on its own.
        assertEquals(RuntimeAccountUsageCredits(available = true), account("""{"available":true,"balance":"30784 USD"}""").credits)
        assertEquals(RuntimeAccountUsageCredits(available = true), account("""{"available":true,"balance":30784}""").credits)
        // No boolean `available`: the credits are unknown.
        assertNull(account("""{"available":"yes","balance":"1"}""").credits)
        assertNull(account("""{}""").credits)
        assertNull(account("null").credits)
    }

    @Test
    fun aRefreshAnswerCarriesCreditsToo() {
        val body =
            """{"accepted":true,"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","accounts":[{"accountKey":"a","health":"ok","credits":{"available":true,"balance":"12"},"windows":[]}]}}"""
        val refresh = Json.parseToJsonElement(body).toRuntimeAccountUsageRefresh()
        assertEquals("12", refresh?.snapshot?.accounts?.single()?.credits?.balance)
    }

    // --- What is shown ---------------------------------------------------------------------------

    @Test
    fun aFullWindowReadsOnCreditsWhileCreditsAreAvailableOrUnlimitedAndUsedUpOtherwise() {
        val full = window(1.0, RuntimeAccountUsageWindowStatus.LimitReached)
        assertEquals(RuntimeAccountUsageWindowFill.OnCredits, codex(full, credits = RuntimeAccountUsageCredits(available = true, balance = "30784")).windowFill(full))
        assertEquals(RuntimeAccountUsageWindowFill.OnCredits, codex(full, credits = RuntimeAccountUsageCredits(available = false, unlimited = true)).windowFill(full))
        // Absent (today's behaviour) or none to spend: used up.
        assertEquals(RuntimeAccountUsageWindowFill.UsedUp, codex(full, credits = null).windowFill(full))
        assertEquals(RuntimeAccountUsageWindowFill.UsedUp, codex(full, credits = RuntimeAccountUsageCredits(available = false, balance = "0")).windowFill(full))
        // A window with room is just used; one that can't be read stays unreadable.
        val part = window(0.4)
        assertEquals(RuntimeAccountUsageWindowFill.Used, codex(part, credits = RuntimeAccountUsageCredits(available = true)).windowFill(part))
        val unreadable = window(null, RuntimeAccountUsageWindowStatus.Unavailable)
        assertNull(codex(unreadable, credits = RuntimeAccountUsageCredits(available = true)).windowFill(unreadable))
    }

    @Test
    fun theCreditsLineShowsTheBalanceWhenKnown() {
        val w = window(1.0, RuntimeAccountUsageWindowStatus.LimitReached)
        assertEquals(RuntimeAccountUsageCreditsLine.Balance("30784"), codex(w, RuntimeAccountUsageCredits(available = true, balance = "30784")).creditsLine)
        assertEquals(RuntimeAccountUsageCreditsLine.Unlimited, codex(w, RuntimeAccountUsageCredits(available = true, unlimited = true, balance = "5")).creditsLine)
        assertEquals(RuntimeAccountUsageCreditsLine.Available, codex(w, RuntimeAccountUsageCredits(available = true)).creditsLine)
        assertEquals(RuntimeAccountUsageCreditsLine.Balance("0"), codex(w, RuntimeAccountUsageCredits(available = false, balance = "0")).creditsLine)
        assertNull(codex(w, RuntimeAccountUsageCredits(available = false)).creditsLine)
        assertNull(codex(w, null).creditsLine)
    }

    @Test
    fun aFullWindowOnCreditsIsNoAttentionOnTheUsageLight() {
        val full = window(1.0, RuntimeAccountUsageWindowStatus.LimitReached)
        val onCredits = loaded(codex(full, RuntimeAccountUsageCredits(available = true, balance = "30784")))
        val unlimited = loaded(codex(full, RuntimeAccountUsageCredits(available = true, unlimited = true)))
        val absent = loaded(codex(full, null))
        assertEquals(RaftStatusVariant.Success, onCredits.computerRuntimeUsageHealth())
        assertEquals(RaftStatusVariant.Success, unlimited.computerRuntimeUsageHealth())
        assertEquals(RaftStatusVariant.Warning, absent.computerRuntimeUsageHealth(), "unknown credits: as before")
        assertEquals(false, onCredits.runtimeAccountUsageNeedsAttention())
        assertEquals(true, absent.runtimeAccountUsageNeedsAttention())
        // The account's own health still counts: rate limited is attention even with credits.
        assertTrue(codex(full, RuntimeAccountUsageCredits(available = true), RuntimeAccountUsageHealth.RateLimited).needsUsageAttention)
    }

    @Test
    fun theRackLineSaysOnCreditsInsteadOfUsedUp() {
        val column = RaftRackUsageColumn(RuntimeAccountUsageProvider.Codex, "codex")
        val full = window(1.0, RuntimeAccountUsageWindowStatus.LimitReached, resetsAt = "2026-11-20T00:00:00.000Z")
        val onCredits = assertIs<RaftRackUsageCell.Value>(raftRackUsageCell(column, loaded(codex(full, RuntimeAccountUsageCredits(available = true, balance = "30784")))))
        assertEquals(100, onCredits.used)
        assertEquals(true, onCredits.usedUp, "the window really is full")
        assertEquals(true, onCredits.onCredits)
        val unlimited = assertIs<RaftRackUsageCell.Value>(raftRackUsageCell(column, loaded(codex(full, RuntimeAccountUsageCredits(available = true, unlimited = true)))))
        assertEquals(true, unlimited.onCredits)
        val absent = assertIs<RaftRackUsageCell.Value>(raftRackUsageCell(column, loaded(codex(full, null))))
        assertEquals(true, absent.usedUp)
        assertEquals(false, absent.onCredits)
        assertEquals("2026-11-20T00:00:00.000Z", absent.resetsAt)
    }

    @Test
    fun thePickerLineSaysLimitReachedUsingCredits() {
        val week = RuntimeAccountUsageWindow(id = "w", label = "Codex · 1 week", status = RuntimeAccountUsageWindowStatus.LimitReached, usedRatio = 1.0, resetsAt = "2026-11-20T00:00:00.000Z")
        fun parts(credits: RuntimeAccountUsageCredits?): List<RuntimeQuotaLinePart> {
            val summary = summarizeRuntimeQuota(RuntimeAccountUsageProvider.Codex, read(codex(week, credits)))!!
            return runtimeQuotaLineParts(summary, zh) { "3 小时后" }
        }
        assertEquals(
            listOf(RuntimeQuotaLinePart("Codex Pro"), RuntimeQuotaLinePart("本周 已达上限 · 正在使用积分")),
            parts(RuntimeAccountUsageCredits(available = true, balance = "30784")),
        )
        assertEquals(
            listOf(RuntimeQuotaLinePart("Codex Pro"), RuntimeQuotaLinePart("本周 已达上限 · 正在使用积分")),
            parts(RuntimeAccountUsageCredits(available = true, unlimited = true)),
        )
        assertEquals(
            listOf(RuntimeQuotaLinePart("Codex Pro"), RuntimeQuotaLinePart("本周 已用完，3 小时后恢复", usedUp = true)),
            parts(null),
        )
    }

    @Test
    fun aResetIsStillOfferedButIsNotTheMainActionWhileCreditsCarryTheAccount() {
        val actor = RuntimeAuthView(canAct = true, availability = RuntimeAuthAvailability.Ok, status = mapOf(RuntimeAuthRuntime.Codex to RuntimeAuthStatus.LoggedIn()))
        val full = window(1.0, RuntimeAccountUsageWindowStatus.LimitReached)
        fun offer(credits: RuntimeAccountUsageCredits?) =
            computerRuntimeLimitResetOffer(loaded(codex(full, credits).copy(limitResetsAvailable = 1)), actor, RuntimeAccountUsageProvider.Codex)
        assertEquals(ComputerRuntimeLimitResetOffer(primary = false), offer(RuntimeAccountUsageCredits(available = true, balance = "30784")))
        assertEquals(ComputerRuntimeLimitResetOffer(primary = false), offer(RuntimeAccountUsageCredits(available = true, unlimited = true)))
        assertEquals(ComputerRuntimeLimitResetOffer(primary = true), offer(null))
        assertEquals(ComputerRuntimeLimitResetOffer(primary = true), offer(RuntimeAccountUsageCredits(available = false)))
    }

    // --- Fixtures --------------------------------------------------------------------------------

    private val zh =
        RuntimeQuotaLineCopy(
            fiveHour = "5 小时",
            week = "本周",
            used = { window, percent -> "$window 已用 $percent" },
            usedUp = { window, reset -> "$window 已用完，${reset}恢复" },
            usedUpNoReset = { window -> "$window 已用完" },
            attention = { plan -> "$plan · 需要处理" },
            onCredits = { window -> "$window 已达上限 · 正在使用积分" },
        )

    /** One Codex account parsed from the wire, `credits` as given (null: no key). */
    private fun account(creditsJson: String?): RuntimeAccountUsageAccount {
        val credits = creditsJson?.let { ""","credits":$it""" }.orEmpty()
        val body =
            """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-11-18T10:00:00.000Z","accounts":[{"accountKey":"${"a".repeat(64)}","planLabel":"Pro","health":"ok"$credits,"windows":[{"id":"w0","label":"Codex · 1 week","status":"limit_reached","usedRatio":1}]}]}}"""
        val read = assertIs<RuntimeAccountUsageRead.Available>(Json.parseToJsonElement(body).toRuntimeAccountUsageRead())
        return read.snapshot.accounts.single()
    }

    private fun window(ratio: Double?, status: RuntimeAccountUsageWindowStatus = RuntimeAccountUsageWindowStatus.Ok, resetsAt: String? = null) =
        RuntimeAccountUsageWindow(id = "w0", label = "Codex · 1 week", status = status, usedRatio = ratio, resetsAt = resetsAt)

    private fun codex(
        window: RuntimeAccountUsageWindow,
        credits: RuntimeAccountUsageCredits?,
        health: RuntimeAccountUsageHealth = RuntimeAccountUsageHealth.Ok,
    ) = RuntimeAccountUsageAccount(
        accountKey = "acct",
        planLabel = "pro",
        identityLabel = null,
        health = health,
        windows = listOf(window),
        credits = credits,
    )

    private fun read(account: RuntimeAccountUsageAccount) =
        RuntimeAccountUsageRead.Available(stale = false, snapshot = RuntimeAccountUsageSnapshot(provider = "codex", collectedAt = null, accounts = listOf(account)))

    private fun loaded(account: RuntimeAccountUsageAccount) = RuntimeAccountUsageLoad.Loaded(read(account))

    private class RecordingClient : RaftNetworkClient {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse = requestWithHeaders(priority, path, emptyMap(), method, query, serverIdOverride, bodyText)

        override suspend fun requestWithHeaders(
            priority: DataRequestPriority,
            path: String,
            headers: Map<String, String>,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            calls += "$method $path" to headers
            return HomeNetworkResponse(200, "{}")
        }
    }
}
