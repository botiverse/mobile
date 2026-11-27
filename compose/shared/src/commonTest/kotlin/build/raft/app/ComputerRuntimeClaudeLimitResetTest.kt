package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeLimitResetKind
import build.raft.app.data.RuntimeLimitResetTest
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.thread.key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Use a limit reset" on the Claude usage card, as Claude Code's own `/limit-reset` (botiverse/slock
 * #15574 `claudeLimitResetReadStep`, `ClaudeLimitResetsDialog`; cases follow its
 * `tests/claudeLimitResetsDialog.behavior.test.tsx`, payloads its shared `runtimeAccountUsage.ts`
 * schemas and `runtimeAuthRoutes.ts`): one entry behind `claude_limit_reset_v2`; the press reads at
 * once; consent only for someone who may use a reset, at once at the 5-hour limit or once more
 * after `consent_required`; one usable kind goes to its confirm, several to the choice; Claude's
 * older program ends with a line and never a second reset; nothing usable says nothing was used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComputerRuntimeClaudeLimitResetTest {
    @Test
    fun theEntryIsOfferedOnlyWithTheFlagAndTheServersOk() = runTest {
        withController(api(read = { error("no read") })) { controller ->
            controller.claudeLimitResetFlagOn = false
            assertNull(controller.claudeLimitResetOffer, "claude_limit_reset_v2 off: nothing about Claude resets")
            controller.openClaudeLimitReset()
            assertNull(controller.claudeLimitResetsDialog)
            controller.claudeLimitResetFlagOn = true
            assertEquals(ComputerRuntimeClaudeLimitResetOffer(canUse = true, primary = false), controller.claudeLimitResetOffer)
        }
        withController(api(read = { error("no read") }, auth = AUTH_WITHOUT_RESETS)) { controller ->
            assertNull(controller.claudeLimitResetOffer, "the server sends no claudeLimitResets: its flag is off or the viewer may not act")
        }
        withController(api(read = { error("no read") }, atLimit = true)) { controller ->
            assertEquals(ComputerRuntimeClaudeLimitResetOffer(canUse = true, primary = true), controller.claudeLimitResetOffer, "the main action at the 5-hour limit")
        }
    }

    @Test
    fun thePressReadsAtOnceAndSaysItTakesAMoment() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = api(read = { ok(NONE) }, beforeRead = { gate.await() })
        withController(api) { controller ->
            controller.openClaudeLimitReset()
            runCurrent()
            assertEquals(ComputerRuntimeClaudeLimitResetsPhase.Reading(consent = false), controller.claudeLimitResetsDialog?.phase)
            assertEquals(listOf(false), api.readConsents())
            // A second tap while it reads sends nothing more.
            controller.openClaudeLimitReset()
            runCurrent()
            assertEquals(1, api.readConsents().size)
            gate.complete(Unit)
            runCurrent()
            assertNull(controller.claudeLimitResetsDialog)
        }
    }

    @Test
    fun consentGoesOnlyWithSomeoneWhoMayUseAReset() = runTest {
        // At the 5-hour limit the one read carries consent, unasked.
        val atLimit = api(read = { ok(NONE) }, atLimit = true)
        withController(atLimit) { controller ->
            controller.openClaudeLimitReset()
            runCurrent()
            assertEquals(listOf(true), atLimit.readConsents())
        }
        // Below it, a consent_required answer (stale usage) gets one more read, with consent.
        val answers = ArrayDeque(listOf(CONSENT_REQUIRED, NONE))
        val stale = api(read = { ok(answers.removeFirst()) })
        withController(stale) { controller ->
            controller.openClaudeLimitReset()
            runCurrent()
            assertEquals(listOf(false, true), stale.readConsents())
            assertEquals(ComputerRuntimeClaudeLimitResetText.NoneToUse, controller.claudeLimitResetNote)
        }
        // A viewer who may not use resets never sends consent: below the limit one read, then who can.
        val viewOnly = api(read = { ok(CONSENT_REQUIRED) }, auth = AUTH_VIEW_ONLY)
        withController(viewOnly) { controller ->
            assertEquals(ComputerRuntimeClaudeLimitResetOffer(canUse = false, primary = false), controller.claudeLimitResetOffer)
            controller.openClaudeLimitReset()
            runCurrent()
            assertEquals(listOf(false), viewOnly.readConsents())
            assertEquals(ComputerRuntimeClaudeLimitResetsPhase.Failed(ComputerRuntimeClaudeLimitResetText.Forbidden), controller.claudeLimitResetsDialog?.phase)
        }
        // ...and at the limit no entry and no read at all.
        val viewOnlyAtLimit = api(read = { error("no read") }, auth = AUTH_VIEW_ONLY, atLimit = true)
        withController(viewOnlyAtLimit) { controller ->
            assertNull(controller.claudeLimitResetOffer)
            controller.openClaudeLimitReset()
            runCurrent()
            assertEquals(emptyList(), viewOnlyAtLimit.readConsents())
        }
    }

    @Test
    fun oneUsableKindGoesStraightToItsConfirmAndNoKeepsIt() = runTest {
        val api = api(read = { ok(ONE_WEEKLY) })
        withController(api) { controller ->
            controller.openClaudeLimitReset()
            runCurrent()
            assertNull(controller.claudeLimitResetsDialog, "no list for one kind")
            val confirm = assertNotNull(controller.claudeLimitResetConfirm)
            assertEquals(RuntimeLimitResetKind.Weekly, confirm.kind)
            assertEquals("Nov 26, 4:59pm", confirm.status?.expiresText)
            assertEquals("x*****n@botiverse.dev", confirm.account)
            // "No, keep it" before anything was sent: kept, said on the card; no redeem.
            controller.dismissClaudeLimitResetConfirm()
            assertNull(controller.claudeLimitResetConfirm)
            assertEquals(ComputerRuntimeClaudeLimitResetText.Kept(RuntimeLimitResetKind.Weekly, "Nov 26, 4:59pm"), controller.claudeLimitResetNote)
            assertTrue(api.calls.none { it.contains("/limit-reset ") })
        }
        // At the 5-hour limit only the 5-hour reset counts.
        val atLimit = api(read = { ok(FIVE_HOUR_AND_OTHERS) }, atLimit = true)
        withController(atLimit) { controller ->
            controller.openClaudeLimitReset()
            runCurrent()
            assertEquals(RuntimeLimitResetKind.FiveHour, controller.claudeLimitResetConfirm?.kind)
        }
    }

    @Test
    fun theOlderProgramsReadThatUsedTheFiveHourResetEndsWithALineAndAUsageReadNeverASecondReset() = runTest {
        val api = api(read = { ok(LEGACY_SPENT) }, atLimit = true)
        withController(api) { controller ->
            controller.openClaudeLimitReset()
            runCurrent()
            assertEquals(listOf(true), api.readConsents())
            assertNull(controller.claudeLimitResetsDialog)
            assertNull(controller.claudeLimitResetConfirm, "never a confirm after it")
            assertEquals(ComputerRuntimeClaudeLimitResetText.LegacyReset, controller.claudeLimitResetNote)
            assertTrue(api.calls.none { it.contains("/limit-reset ") }, "never a second reset")
            assertEquals(1, api.calls.count { it.contains("/runtime-account-usage/claude/refresh") }, "the usage is read again")
        }
        // This week's 5-hour reset already gone: nothing was used, no usage read.
        val used = api(read = { ok(LEGACY_USED) }, atLimit = true)
        withController(used) { controller ->
            controller.openClaudeLimitReset()
            runCurrent()
            assertEquals(ComputerRuntimeClaudeLimitResetText.LegacyUsed, controller.claudeLimitResetNote)
            assertTrue(used.calls.none { it.contains("/refresh") || it.contains("/limit-reset ") })
        }
        // Closed while it read: the card still says the reset was used.
        val gate = CompletableDeferred<Unit>()
        val closed = api(read = { ok(LEGACY_SPENT) }, atLimit = true, beforeRead = { gate.await() })
        withController(closed) { controller ->
            controller.openClaudeLimitReset()
            runCurrent()
            controller.dismissClaudeLimitResets()
            gate.complete(Unit)
            runCurrent()
            assertEquals(ComputerRuntimeClaudeLimitResetText.LegacyReset, controller.claudeLimitResetNote)
        }
    }

    private fun api(
        read: () -> HomeNetworkResponse,
        atLimit: Boolean = false,
        auth: String = AUTH,
        redeem: () -> HomeNetworkResponse = { error("no redeem expected") },
        beforeRead: suspend () -> Unit = {},
    ) = RuntimeLimitResetTest.FakeLimitResetApi(
        usage = { ok("""{"state":"fresh","snapshot":${snapshot(atLimit)}}""") },
        auth = { ok(auth) },
        redeem = redeem,
        // The usage read again after a reset: the Computer is offline, so nothing follows it.
        refresh = { HomeNetworkResponse(202, """{"accepted":false,"state":"computer_offline"}""") },
        readClaude = read,
        beforeReadClaude = beforeRead,
    )

    /** The `consent` of each read sent, in order. */
    private fun RuntimeLimitResetTest.FakeLimitResetApi.readConsents(): List<Boolean> =
        calls.filter { it.contains("/limit-resets/read ") }.map { call ->
            val body = Json.parseToJsonElement(call.substringAfter("/limit-resets/read ")).jsonObject
            assertEquals(true, body["onboardingRequiredReason"]?.jsonPrimitive?.booleanOrNull, call)
            body["consent"]?.jsonPrimitive?.booleanOrNull == true
        }

    private suspend fun TestScope.withController(
        api: RuntimeLimitResetTest.FakeLimitResetApi,
        block: suspend (ComputerRuntimeUsageController) -> Unit,
    ) {
        val scope = CoroutineScope(coroutineContext + Job())
        val owner = RuntimeLimitResetTest.OWNER
        val store = SharedRuntimeAccountUsageStore(RaftComputersApiClient(api), currentIdentity = { owner }, clock = { 0L })
        // 30s after 2026-11-19T03:00:00Z so opening the card reads once without triggering a stale auto-check (#16501).
        val controller =
            ComputerRuntimeUsageController(
                key = RuntimeAccountUsageKey(owner, "s1", "m1", RuntimeAccountUsageProvider.Claude),
                store = store,
                scope = scope,
                nowMillis = { 1_795_057_230_000L },
            )
        controller.claudeLimitResetFlagOn = true
        try {
            controller.onOpen()
            runCurrent()
            block(controller)
        } finally {
            controller.cancel()
            scope.cancel()
        }
    }

    private fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)

    private companion object {
        /** slock web tests `claudeSnapshot(atLimit)`: one account, its "Current session" window. */
        fun snapshot(atLimit: Boolean): String =
            """{"protocolVersion":2,"provider":"claude","collectedAt":"2026-11-19T03:00:00.000Z","staleAfter":"2099-11-19T03:30:00.000Z","collectorVersion":"test","accounts":[{"accountKey":"${"c".repeat(64)}","maskedLabel":"x*****n@botiverse.dev","health":"${if (atLimit) "rate_limited" else "ok"}","windows":[{"id":"session","label":"Current session","status":"${if (atLimit) "limit_reached" else "ok"}","usedRatio":${if (atLimit) "1" else "0.4"},"resetsAt":"2099-11-24T09:00:00.000Z"}]}]}"""

        // slock runtimeAuthRoutes.ts `GET …/runtime-auth`: claudeLimitResets only while claude_limit_reset_v2 is on.
        const val AUTH =
            """{"status":{"collectedAt":"2026-11-19T03:00:00.000Z","runtimes":{"claude":{"kind":"logged_in"}}},"availability":"ok","runtimes":["claude"],"canAct":true,"claudeLimitResets":"ok","claudeLimitResetAccess":{"canUse":true,"canConsent":true}}"""
        const val AUTH_VIEW_ONLY =
            """{"status":{"collectedAt":"2026-11-19T03:00:00.000Z","runtimes":{"claude":{"kind":"logged_in"}}},"availability":"ok","runtimes":["claude"],"canAct":true,"claudeLimitResets":"ok","claudeLimitResetAccess":{"canUse":false,"canConsent":false}}"""
        const val AUTH_WITHOUT_RESETS =
            """{"status":{"collectedAt":"2026-11-19T03:00:00.000Z","runtimes":{"claude":{"kind":"logged_in"}}},"availability":"ok","runtimes":["claude"],"canAct":true}"""

        // slock `runtimeLimitResetsReadResultSchema` answers, as `{result}` (web tests' READ, WEEKLY, FABLE).
        const val CONSENT_REQUIRED = """{"result":{"kind":"unsupported","reason":"consent_required"}}"""
        const val NONE = """{"result":{"kind":"read","resets":[{"kind":"five_hour","state":"none"},{"kind":"weekly","state":"none"}]}}"""
        const val ONE_WEEKLY =
            """{"result":{"kind":"read","resets":[{"kind":"five_hour","state":"none"},{"kind":"weekly","state":"available","resetsLeft":1,"expiresText":"Nov 26, 4:59pm"},{"kind":"full","state":"none"}]}}"""
        const val FIVE_HOUR_AND_OTHERS =
            """{"result":{"kind":"read","resets":[{"kind":"five_hour","state":"available","resetsLeft":2},{"kind":"weekly","state":"available","resetsLeft":1,"expiresText":"Nov 26, 4:59pm"},{"kind":"fable","state":"available","resetsLeft":3}]}}"""
        const val LEGACY_SPENT = """{"result":{"kind":"read","program":"legacy","fiveHourSpent":true,"resets":[{"kind":"five_hour","state":"none"}]}}"""
        const val LEGACY_USED = """{"result":{"kind":"read","program":"legacy","resets":[{"kind":"five_hour","state":"none"}]}}"""
    }
}
