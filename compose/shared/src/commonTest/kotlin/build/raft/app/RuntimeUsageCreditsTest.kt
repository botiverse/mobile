package build.raft.app

import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageCredits
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowFill
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.toRuntimeAccountUsageRead
import build.raft.app.data.windowFill
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * slock #15374: a Codex account's `credits` (OAR's), served only to a request declaring
 * `runtime-usage-credits@1`. A full window the credits carry is "limit reached, using credits",
 * never "used up"; absent credits (an older server or Computer) read as before.
 */
class RuntimeUsageCreditsTest {
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

    // --- Fixtures --------------------------------------------------------------------------------

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
}
