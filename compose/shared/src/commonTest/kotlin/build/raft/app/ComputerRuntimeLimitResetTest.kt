package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftComputersApiClient
import build.raft.app.data.RuntimeAccountUsageKey
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RuntimeLimitResetRefusal
import build.raft.app.data.RuntimeLimitResetResult
import build.raft.app.data.RuntimeLimitResetTest
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.toRuntimeAccountUsageRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * slock #13504 web `useRuntimeUsageCard` / `RuntimeLimitResetDialog` / `limitResetOutcome.ts`:
 * who is offered "Use a reset", what each outcome says, and that a retry after an unknown outcome
 * sends the same idempotency key while a settled one starts a new attempt.
 */
class ComputerRuntimeLimitResetTest {
    private val actor = RuntimeAuthView(canAct = true, availability = RuntimeAuthAvailability.Ok, statusKinds = mapOf("codex" to "logged_in"))

    @Test
    fun offeredOnlyToSomeoneWhoMayActWhileTheAccountHoldsAReset() {
        val withReset = usage("2")
        assertEquals(ComputerRuntimeLimitResetOffer(primary = false), computerRuntimeLimitResetOffer(withReset, actor, RuntimeAccountUsageProvider.Codex))
        assertNull(computerRuntimeLimitResetOffer(usage("0"), actor, RuntimeAccountUsageProvider.Codex), "no reset left")
        assertNull(computerRuntimeLimitResetOffer(withReset, null, RuntimeAccountUsageProvider.Codex), "no view (refused or not read yet)")
        assertNull(computerRuntimeLimitResetOffer(withReset, actor.copy(canAct = false), RuntimeAccountUsageProvider.Codex), "an editMachines viewer who is not owner/admin/attacher")
        assertNull(
            computerRuntimeLimitResetOffer(withReset, actor.copy(availability = RuntimeAuthAvailability.ComputerOffline), RuntimeAccountUsageProvider.Codex),
        )
        assertNull(
            computerRuntimeLimitResetOffer(withReset, actor.copy(availability = RuntimeAuthAvailability.ComputerNotAttached), RuntimeAccountUsageProvider.Codex),
        )
        assertNull(
            computerRuntimeLimitResetOffer(withReset, actor.copy(statusKinds = mapOf("codex" to "logged_out")), RuntimeAccountUsageProvider.Codex),
            "signed out",
        )
        assertNull(computerRuntimeLimitResetOffer(withReset, actor, RuntimeAccountUsageProvider.Grok), "no sign-in from the web, so no view")
        // The reset is the main action while the account is rate-limited.
        assertEquals(true, computerRuntimeLimitResetOffer(usage("1", health = "rate_limited"), actor, RuntimeAccountUsageProvider.Codex)?.primary)
        assertEquals(true, computerRuntimeLimitResetOffer(usage("1", windowStatus = "limit_reached"), actor, RuntimeAccountUsageProvider.Codex)?.primary)
    }

    @Test
    fun outcomesMapToTheWebsCopy() {
        assertEquals(ComputerRuntimeLimitResetNote.Done, RuntimeLimitResetResult.Reset.limitResetNote())
        assertEquals(ComputerRuntimeLimitResetNote.NothingToReset, RuntimeLimitResetResult.NothingToReset.limitResetNote())
        assertEquals(ComputerRuntimeLimitResetNote.NoReset, RuntimeLimitResetResult.NoReset.limitResetNote())
        assertEquals(ComputerRuntimeLimitResetNote.AlreadyRedeemed, RuntimeLimitResetResult.AlreadyRedeemed.limitResetNote())
        assertNull(RuntimeLimitResetResult.Unknown.limitResetNote())
        assertNull(RuntimeLimitResetResult.Reset.limitResetError())
        assertEquals(ComputerRuntimeLimitResetError.Reauth, RuntimeLimitResetResult.ReauthRequired("scope_missing").limitResetError())
        assertEquals(ComputerRuntimeLimitResetError.NotInstalled, RuntimeLimitResetResult.Unsupported("not_installed").limitResetError())
        assertEquals(ComputerRuntimeLimitResetError.Unsupported, RuntimeLimitResetResult.Unsupported("endpoint_unavailable").limitResetError())
        assertEquals(ComputerRuntimeLimitResetError.Unknown, RuntimeLimitResetResult.Unknown.limitResetError())
        assertEquals(ComputerRuntimeLimitResetError.Forbidden, RuntimeLimitResetRefusal(403, "forbidden").limitResetError())
        assertEquals(ComputerRuntimeLimitResetError.ComputerOffline, RuntimeLimitResetRefusal(409, "computer_offline").limitResetError())
        assertEquals(ComputerRuntimeLimitResetError.UpgradeRequired, RuntimeLimitResetRefusal(409, "computer_upgrade_required").limitResetError())
        assertEquals(ComputerRuntimeLimitResetError.NotAttached, RuntimeLimitResetRefusal(409, "computer_not_attached").limitResetError())
        assertEquals(ComputerRuntimeLimitResetError.Unknown, RuntimeLimitResetRefusal(504, "computer_timeout").limitResetError())
        assertEquals(ComputerRuntimeLimitResetError.Unknown, RuntimeLimitResetRefusal(null, null).limitResetError())
    }

    @Test
    fun aResetShowsTheFreshUsageAndSaysSoAndTheNextAttemptHasANewKey() = runPortableCoroutineTest {
        val redeems = mutableListOf<HomeNetworkResponse>()
        val api =
            RuntimeLimitResetTest.FakeLimitResetApi(
                usage = { ok(RuntimeLimitResetTest.usageBody("2")) },
                auth = { ok(AUTH_VIEW) },
                redeem = { redeems.removeAt(0) },
                refresh = { HomeNetworkResponse(202, """{"accepted":false,"state":"computer_offline"}""") },
            )
        redeems += ok("""{"result":{"kind":"reset"},"snapshot":${RuntimeLimitResetTest.snapshot(1)}}""")
        withController(api) { controller ->
            controller.onOpen()
            settle()
            assertNotNull(controller.limitResetOffer)
            controller.openLimitReset()
            settle()
            val dialog = assertNotNull(controller.limitResetDialog)
            assertEquals("tea****r@example.com", dialog.account)
            assertEquals(2, dialog.resetsAvailable)
            controller.confirmLimitReset()
            settle()
            assertNull(controller.limitResetDialog, "a reset closes the dialog")
            assertEquals(ComputerRuntimeLimitResetNote.Done, controller.limitResetNote)
            val shown = assertIs<RuntimeAccountUsageRead.Available>(assertIs<RuntimeAccountUsageLoad.Loaded>(controller.load).read)
            assertEquals(1, shown.snapshot.accounts.single().limitResetsAvailable)
            assertEquals(1, api.calls.count { it.contains("/limit-reset") })
            // A settled attempt: the next click is a new one with a new key.
            controller.openLimitReset()
            settle()
            assertNotEquals(dialog.idempotencyKey, assertNotNull(controller.limitResetDialog).idempotencyKey)
            controller.dismissLimitReset()
            // A refresh replaces what the reset said.
            controller.refreshNow()
            assertNull(controller.limitResetNote)
        }
    }

    @Test
    fun anUnknownOutcomeStaysOpenAndTheRetrySendsTheSameKey() = runPortableCoroutineTest {
        val redeems =
            mutableListOf(
                ok("""{"result":{"kind":"unknown"},"snapshot":null}"""),
                HomeNetworkResponse(504, """{"error":"The Computer did not answer in time.","code":"computer_timeout"}"""),
                ok("""{"result":{"kind":"already_redeemed"},"snapshot":${RuntimeLimitResetTest.snapshot(1)}}"""),
            )
        val bodies = mutableListOf<String>()
        val api =
            RuntimeLimitResetTest.FakeLimitResetApi(
                usage = { ok(RuntimeLimitResetTest.usageBody("2", health = "rate_limited")) },
                auth = { ok(AUTH_VIEW) },
                redeem = { redeems.removeAt(0) },
                refresh = { HomeNetworkResponse(202, """{"accepted":false,"state":"computer_offline"}""") },
            )
        withController(api) { controller ->
            controller.onOpen()
            settle()
            assertEquals(true, controller.limitResetOffer?.primary)
            controller.openLimitReset()
            settle()
            controller.confirmLimitReset()
            settle()
            assertEquals(ComputerRuntimeLimitResetError.Unknown, assertNotNull(controller.limitResetDialog).error)
            assertNull(controller.limitResetNote)
            // No usage came with it: the card refreshes instead of assuming the limits recovered.
            assertEquals(1, api.calls.count { it.endsWith("/refresh {\"reason\":\"manual\"}") })
            controller.confirmLimitReset()
            settle()
            assertEquals(ComputerRuntimeLimitResetError.Unknown, assertNotNull(controller.limitResetDialog).error, "a timeout")
            controller.confirmLimitReset()
            settle()
            assertNull(controller.limitResetDialog)
            assertEquals(ComputerRuntimeLimitResetNote.AlreadyRedeemed, controller.limitResetNote)
            api.calls.filter { it.contains("/limit-reset") }.forEach { bodies += it.substringAfter("/limit-reset ") }
            assertEquals(3, bodies.size)
            assertEquals(1, bodies.toSet().size, "every retry of one attempt sends the same key: $bodies")
        }
    }

    @Test
    fun aRefusedViewerGetsNoAction() = runPortableCoroutineTest {
        val api =
            RuntimeLimitResetTest.FakeLimitResetApi(
                usage = { ok(RuntimeLimitResetTest.usageBody("2")) },
                auth = { ok("""{"status":null,"availability":"ok","runtimes":["codex"],"canAct":false}""") },
            )
        withController(api) { controller ->
            controller.onOpen()
            settle()
            assertNull(controller.limitResetOffer)
            controller.openLimitReset()
            settle()
            assertNull(controller.limitResetDialog)
        }
    }

    private suspend fun CoroutineScope.withController(
        api: RuntimeLimitResetTest.FakeLimitResetApi,
        block: suspend (ComputerRuntimeUsageController) -> Unit,
    ) {
        val scope = CoroutineScope(coroutineContext + Job())
        val owner = RuntimeLimitResetTest.OWNER
        val store = SharedRuntimeAccountUsageStore(RaftComputersApiClient(api), currentIdentity = { owner }, clock = { 0L })
        val controller =
            ComputerRuntimeUsageController(
                key = RuntimeAccountUsageKey(owner, "s1", "m1", RuntimeAccountUsageProvider.Codex),
                store = store,
                scope = scope,
            )
        try {
            block(controller)
        } finally {
            controller.cancel()
            scope.cancel()
        }
    }

    private suspend fun settle() = repeat(50) { yield() }

    private fun usage(resets: String, health: String = "ok", windowStatus: String = "ok"): RuntimeAccountUsageLoad =
        RuntimeAccountUsageLoad.Loaded(
            assertNotNull(Json.parseToJsonElement(RuntimeLimitResetTest.usageBody(resets, health, windowStatus)).toRuntimeAccountUsageRead())
        )

    private fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)

    private companion object {
        const val AUTH_VIEW =
            """{"status":{"collectedAt":"2026-11-07T10:00:00.000Z","runtimes":{"codex":{"kind":"logged_in"}}},"availability":"ok","runtimes":["codex"],"canAct":true}"""
    }
}
