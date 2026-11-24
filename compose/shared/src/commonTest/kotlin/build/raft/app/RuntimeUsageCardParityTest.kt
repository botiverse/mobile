package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageBadge
import build.raft.app.data.RuntimeAccountUsageCredits
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageModelLimit
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageUnsupportedReason
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowScope
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.RuntimeLimitResetTest
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.modelLimitChip
import build.raft.app.data.toRuntimeAccountUsageRead
import build.raft.app.data.toRuntimeAuthStatus
import build.raft.app.data.unsupportedReasonLine
import build.raft.app.data.usageBadge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Web parity for the runtime usage card (botiverse/slock #15482, #15483, #15557, #15395, #15786,
 * #15787, #15931): what the card reads from the wire and decides before drawing it.
 */
class RuntimeUsageCardParityTest {
    // --- #15395: why an account is unsupported -------------------------------------------------

    @Test
    fun anUnsupportedReasonIsReadOnlyWithUnsupportedHealthAndAnUnknownOneReadsAsPlainUnsupported() {
        val quota = account("""{"accountKey":"a","health":"unsupported","unsupportedReason":"quota_unavailable","windows":[]}""")
        assertEquals(RuntimeAccountUsageUnsupportedReason.QuotaUnavailable, quota.unsupportedReason)
        assertEquals(RuntimeAccountUsageBadge.NoUsageData, quota.usageBadge())
        assertNull(quota.unsupportedReasonLine, "the chip says it")
        val old = account("""{"accountKey":"a","health":"unsupported","unsupportedReason":"version_unsupported","windows":[]}""")
        assertEquals(RuntimeAccountUsageBadge.UpdateRequired, old.usageBadge())
        assertNull(old.unsupportedReasonLine)
        val mode = account("""{"accountKey":"a","health":"unsupported","unsupportedReason":"unsupported_auth_mode","windows":[]}""")
        assertEquals(RuntimeAccountUsageBadge.Unsupported, mode.usageBadge())
        assertEquals(RuntimeAccountUsageUnsupportedReason.UnsupportedAuthMode, mode.unsupportedReasonLine)
        assertEquals(RuntimeAccountUsageUnsupportedReason.UnsupportedInstallation, account("""{"accountKey":"a","health":"unsupported","unsupportedReason":"unsupported_installation","windows":[]}""").unsupportedReasonLine)
        assertNull(account("""{"accountKey":"a","health":"unsupported","unsupportedReason":"capability_unavailable","windows":[]}""").unsupportedReasonLine)
        // A reason the app does not know, or one on an account that is not unsupported, is no reason.
        val unknown = account("""{"accountKey":"a","health":"unsupported","unsupportedReason":"brand_new","windows":[]}""")
        assertNull(unknown.unsupportedReason)
        assertEquals(RuntimeAccountUsageBadge.Unsupported, unknown.usageBadge())
        assertNull(account("""{"accountKey":"a","health":"ok","unsupportedReason":"quota_unavailable","windows":[]}""").unsupportedReason)
        assertNull(account("""{"accountKey":"a","health":"unsupported","unsupportedReason":7,"windows":[]}""").unsupportedReason)
    }

    // --- #15482 / #15483: the account's chip -----------------------------------------------------

    @Test
    fun aHealthyAccountHasNoChipAndAModelLimitSaysSo() {
        assertNull(usage(window("Current session", 0.4)).usageBadge(), "no \"ok\" chip")
        assertEquals(RuntimeAccountUsageBadge.RateLimited, usage(window("Current session", 1.0, RuntimeAccountUsageWindowStatus.LimitReached), health = RuntimeAccountUsageHealth.RateLimited).usageBadge())
        assertEquals(RuntimeAccountUsageBadge.ReauthRequired, usage(health = RuntimeAccountUsageHealth.ReauthRequired).usageBadge())
        // One model's window used up while the account runs others.
        val opus = window("Current week (Opus)", 1.0, RuntimeAccountUsageWindowStatus.LimitReached, RuntimeAccountUsageWindowScope.Model("Opus"))
        val some = usage(window("Current week (all models)", 0.3), opus)
        assertEquals(RuntimeAccountUsageModelLimit.SomeModelsUsedUp, some.modelLimitChip())
        assertEquals(RuntimeAccountUsageBadge.SomeModelsUsedUp, some.usageBadge())
        // On credits, its agents keep working: no chip.
        assertNull(some.copy(credits = RuntimeAccountUsageCredits(available = true)).usageBadge())
    }

    // --- #15482: the limit reset only from 80% used ----------------------------------------------

    @Test
    fun aResetIsOfferedFromEightyPercentUsedAndLeadsOnceTheAccountIsLimited() {
        assertNull(usage(window("Current session", 0.79)).limitResetPrimary(), "56% with days to go: no button")
        assertEquals(false, usage(window("Current session", 0.8)).limitResetPrimary())
        assertEquals(true, usage(window("Current session", 1.0, RuntimeAccountUsageWindowStatus.LimitReached)).limitResetPrimary())
        assertEquals(true, usage(window("Current session", 0.5), health = RuntimeAccountUsageHealth.RateLimited).limitResetPrimary())
        // A model's own window used up alone is a quiet button.
        val spark = window("GPT-5.3-Codex-Spark", 1.0, RuntimeAccountUsageWindowStatus.LimitReached, RuntimeAccountUsageWindowScope.Model("Spark"))
        assertEquals(false, usage(window("Codex · 5 hours", 0.1), spark).limitResetPrimary())
    }

    // --- #15786: usage read again after a sign-in ----------------------------------------------

    @Test
    fun aSignInReadsTheUsageAgainUntilANewerSnapshotLands() = runTest {
        val bodies = mutableListOf(usageAt("10:00", "reauth_required"), usageAt("10:00", "reauth_required"), usageAt("10:00", "reauth_required"), usageAt("10:02", "ok"))
        val api = RuntimeLimitResetTest.FakeLimitResetApi(usage = { ok(if (bodies.size > 1) bodies.removeAt(0) else bodies.first()) }, auth = { ok(AUTH_VIEW) })
        withController(api) { controller ->
            controller.onOpen()
            advanceUntilIdle()
            assertEquals("2026-11-07T10:00:00.000Z", shown(controller).snapshot.collectedAt)
            controller.readAfterSignIn()
            advanceUntilIdle()
            val read = shown(controller)
            assertEquals("2026-11-07T10:02:00.000Z", read.snapshot.collectedAt, "the signed-in reading replaces the signed-out one")
            assertEquals(RuntimeAccountUsageHealth.Ok, read.snapshot.accounts.single().health)
            assertEquals(ComputerRuntimeUsageCheckState.Idle, controller.checkState)
            assertEquals(4, api.calls.count { it.startsWith("GET") && it.contains("runtime-account-usage") })
        }
    }

    // --- Fixtures --------------------------------------------------------------------------------

    private suspend fun TestScope.withController(api: RuntimeLimitResetTest.FakeLimitResetApi, block: suspend (ComputerRuntimeUsageController) -> Unit) {
        val scope = CoroutineScope(coroutineContext + Job())
        val owner = RuntimeLimitResetTest.OWNER
        val store = SharedRuntimeAccountUsageStore(RaftComputersApiClient(api), currentIdentity = { owner }, clock = { 0L })
        // 30s after 2026-11-07T10:00:00Z so opening the card reads once without an immediate stale auto-check.
        val controller =
            ComputerRuntimeUsageController(
                key = RuntimeAccountUsageKey(owner, "s1", "m1", RuntimeAccountUsageProvider.Codex),
                store = store,
                scope = scope,
                nowMillis = { 1_794_045_630_000L },
            )
        try {
            block(controller)
        } finally {
            controller.cancel()
            scope.cancel()
        }
    }

    private fun shown(controller: ComputerRuntimeUsageController): RuntimeAccountUsageRead.Available =
        assertIs<RuntimeAccountUsageRead.Available>(assertIs<RuntimeAccountUsageLoad.Loaded>(controller.load).read)

    private fun usageAt(time: String, health: String) =
        """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-11-07T$time:00.000Z","accounts":[{"accountKey":"acct-1","health":"$health","windows":[]}]}}"""

    private fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)

    private fun account(json: String): RuntimeAccountUsageAccount {
        val body = """{"state":"fresh","snapshot":{"protocolVersion":2,"provider":"codex","collectedAt":"2026-11-07T10:00:00.000Z","accounts":[$json]}}"""
        return assertIs<RuntimeAccountUsageRead.Available>(Json.parseToJsonElement(body).toRuntimeAccountUsageRead()).snapshot.accounts.single()
    }

    private fun status(json: String) = Json.parseToJsonElement(json).toRuntimeAuthStatus()

    private fun window(
        label: String,
        ratio: Double,
        status: RuntimeAccountUsageWindowStatus = RuntimeAccountUsageWindowStatus.Ok,
        scope: RuntimeAccountUsageWindowScope? = null,
    ) = RuntimeAccountUsageWindow(id = label, label = label, status = status, usedRatio = ratio, resetsAt = null, scope = scope)

    private fun usage(vararg windows: RuntimeAccountUsageWindow, health: RuntimeAccountUsageHealth = RuntimeAccountUsageHealth.Ok) =
        RuntimeAccountUsageAccount(accountKey = "acct", planLabel = "pro", identityLabel = null, health = health, windows = windows.toList())

    private companion object {
        const val AUTH_VIEW =
            """{"status":{"collectedAt":"2026-11-07T10:00:00.000Z","runtimes":{"codex":{"kind":"logged_in"}}},"availability":"ok","runtimes":["codex"],"canAct":true}"""
    }
}
