package build.raft.app

import build.raft.app.data.RuntimeAuthAvailability
import build.raft.app.data.RuntimeAuthCall
import build.raft.app.data.RuntimeAuthLoginRegion
import build.raft.app.data.RuntimeAuthLogoutResult
import build.raft.app.data.RuntimeAuthProviderConnectResult
import build.raft.app.data.RuntimeAuthProvidersResult
import build.raft.app.data.RuntimeAuthRequestError
import build.raft.app.data.RuntimeAuthRuntime
import build.raft.app.data.RuntimeAuthStatus
import build.raft.app.data.RuntimeAuthUnavailableReason
import build.raft.app.data.RuntimeAuthView
import build.raft.app.data.RuntimeInstallPlan
import build.raft.app.data.RuntimeInstallResult
import build.raft.app.data.RuntimeInstallUnsupportedReason
import build.raft.app.data.RUNTIME_UPDATE_CHECK_MAX_AGE_MILLIS
import build.raft.app.data.RuntimeOperationErrorCode
import build.raft.app.data.RuntimeUpdateCheck
import build.raft.app.data.RuntimeUpdateCheckUnavailableReason
import build.raft.app.data.RuntimeUpgradeResult
import build.raft.app.data.RuntimeUpgradeUnsupportedReason
import build.raft.app.resources.*
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Upgrade and Install on the Computer page against web runtimeUpgradeOutcomeCopy.ts,
 * runtimeInstallCopy.ts, useRuntimeUpgrade.ts, useRuntimeInstall.ts and RuntimeUsageSurface.tsx
 * (botiverse/slock #15117 940212580a, #15214 ffbf283ad2).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComputerRuntimeOperationsTest {
    @Test
    fun onlyTheServersOwnCodesNameARefusalAndOnlyItsTimeoutSaysItMayStillFinish() {
        assertEquals(ComputerRuntimeOperationRefusal.ComputerUpgradeRequired, computerRuntimeOperationRefusal("computer_upgrade_required"))
        assertEquals(ComputerRuntimeOperationRefusal.ComputerOffline, computerRuntimeOperationRefusal("computer_offline"))
        assertEquals(ComputerRuntimeOperationRefusal.ComputerTimeout, computerRuntimeOperationRefusal("computer_timeout"))
        assertEquals(ComputerRuntimeOperationRefusal.RateLimited, computerRuntimeOperationRefusal("rate_limited"))
        listOf(null, "forbidden", "malformed_response", "runtime_upgrade_disabled").forEach {
            assertEquals(ComputerRuntimeOperationRefusal.Failed, computerRuntimeOperationRefusal(it), it.toString())
        }
        assertEquals(
            ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_upgrade_timeout, success = false, traceId = "t1"),
            computerRuntimeUpgradeCopy(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.ComputerTimeout, "t1")),
        )
        assertEquals(
            Res.string.settings_computers_runtime_install_error,
            computerRuntimeInstallCopy(ComputerRuntimeInstallState.Refused(RuntimeAuthRuntime.Grok, "grok", "Grok", ComputerRuntimeOperationRefusal.Failed))?.message,
        )
    }

    /** Web #15424: a 429 `rate_limited` Upgrade or Install says to wait a few minutes, not "try again". */
    @Test
    fun aRateLimitedUpgradeOrInstallSaysToWaitNotTryAgain() {
        val reason = computerRuntimeOperationRefusal("rate_limited")
        assertEquals(
            ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_auth_provider_error_rate_limited, success = false, traceId = "t2"),
            computerRuntimeUpgradeCopy(ComputerRuntimeUpgradeState.Refused(reason, "t2")),
        )
        assertEquals(
            Res.string.settings_computers_runtime_auth_provider_error_rate_limited,
            computerRuntimeInstallCopy(ComputerRuntimeInstallState.Refused(RuntimeAuthRuntime.Grok, "grok", "Grok", reason))?.message,
        )
    }

    @Test
    fun everyUpgradeOutcomeReadsAsTheWebsSentence() {
        fun copy(result: RuntimeUpgradeResult) = computerRuntimeUpgradeCopy(ComputerRuntimeUpgradeState.Done(result))
        assertNull(computerRuntimeUpgradeCopy(ComputerRuntimeUpgradeState.Idle))
        assertNull(computerRuntimeUpgradeCopy(ComputerRuntimeUpgradeState.Running))
        assertEquals(
            ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_upgrade_upgraded_from_to, listOf("2.0.1", "2.0.5"), success = true),
            copy(RuntimeUpgradeResult.Upgraded("2.0.1", "2.0.5")),
        )
        assertEquals(ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_upgrade_upgraded, success = true), copy(RuntimeUpgradeResult.Upgraded("2.0.1", null)))
        assertEquals(
            ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_upgrade_current_version, listOf("2.0.5"), success = true),
            copy(RuntimeUpgradeResult.Current("2.0.5")),
        )
        assertEquals(Res.string.settings_computers_runtime_upgrade_current, copy(RuntimeUpgradeResult.Current())?.message)
        assertEquals(Res.string.settings_computers_runtime_upgrade_unchanged, copy(RuntimeUpgradeResult.Unchanged("2.0.5"))?.message)
        assertEquals(Res.string.settings_computers_runtime_upgrade_failed, copy(RuntimeUpgradeResult.Failed(1))?.message)
        assertEquals(
            Res.string.settings_computers_runtime_upgrade_requires_terminal,
            copy(RuntimeUpgradeResult.Unsupported(RuntimeUpgradeUnsupportedReason.RequiresTerminal))?.message,
        )
        assertEquals(
            Res.string.settings_computers_runtime_upgrade_unsupported_installation,
            copy(RuntimeUpgradeResult.Unsupported(RuntimeUpgradeUnsupportedReason.UnsupportedInstallation))?.message,
        )
        assertEquals(Res.string.settings_computers_runtime_auth_error_not_installed, copy(RuntimeUpgradeResult.NotInstalled)?.message)
        assertEquals(Res.string.settings_computers_runtime_upgrade_in_progress, copy(RuntimeUpgradeResult.Error(RuntimeOperationErrorCode.InProgress))?.message)
        assertEquals(Res.string.settings_computers_runtime_upgrade_error, copy(RuntimeUpgradeResult.Error(RuntimeOperationErrorCode.Internal))?.message)
        assertFalse(assertNotNull(copy(RuntimeUpgradeResult.Unchanged())).success, "something left to do is orange")
    }

    @Test
    fun aPlanLeadsToTheConfirmTheManualStepsOrAnOutcomeAtOnce() {
        val plan = RuntimeInstallPlan.Plan(listOf("curl -fsSL https://x.ai/cli/install.sh | bash"), "https://docs.x.ai/docs/grok-build")
        assertEquals(
            ComputerRuntimeInstallState.Confirm(RuntimeAuthRuntime.Grok, "grok", "Grok", plan),
            computerRuntimeInstallStateForPlan(RuntimeAuthRuntime.Grok, "grok", "Grok", plan),
        )
        val manual =
            computerRuntimeInstallStateForPlan(
                RuntimeAuthRuntime.Grok,
                "grok",
                "Grok",
                RuntimeInstallPlan.Unsupported(RuntimeInstallUnsupportedReason.RequiresPrivileges, "Needs sudo.", listOf("sudo npm i -g x"), "https://example.com/docs"),
            )
        assertEquals(listOf("sudo npm i -g x"), assertIs<ComputerRuntimeInstallState.Manual>(manual).plan.steps)
        val refused = computerRuntimeInstallStateForPlan(RuntimeAuthRuntime.Opencode, "opencode", "OpenCode", RuntimeInstallPlan.Unsupported(RuntimeInstallUnsupportedReason.LineRequired))
        assertEquals(RuntimeInstallResult.Unsupported(RuntimeInstallUnsupportedReason.LineRequired), assertIs<ComputerRuntimeInstallState.Done>(refused).result)
        assertEquals(Res.string.settings_computers_runtime_install_unsupported_other, computerRuntimeInstallCopy(refused)?.message)
        val unreadable = computerRuntimeInstallStateForPlan(RuntimeAuthRuntime.Grok, "grok", "Grok", RuntimeInstallPlan.Error)
        assertEquals(Res.string.settings_computers_runtime_install_error, computerRuntimeInstallCopy(unreadable)?.message)
        // Deciding is no outcome.
        assertNull(computerRuntimeInstallCopy(ComputerRuntimeInstallState.Confirm(RuntimeAuthRuntime.Grok, "grok", "Grok", plan)))
        assertNull(computerRuntimeInstallCopy(ComputerRuntimeInstallState.Idle))
    }

    @Test
    fun everyInstallOutcomeAndReasonReadsAsTheWebsSentence() {
        fun copy(result: RuntimeInstallResult) = computerRuntimeInstallCopy(ComputerRuntimeInstallState.Done(RuntimeAuthRuntime.Grok, "grok", "Grok", result))
        assertEquals(
            ComputerRuntimeOperationCopy(Res.string.settings_computers_runtime_install_installed_version, listOf("0.1.9"), success = true),
            copy(RuntimeInstallResult.Installed("0.1.9")),
        )
        assertEquals(Res.string.settings_computers_runtime_install_installed, copy(RuntimeInstallResult.Installed())?.message)
        assertEquals(Res.string.settings_computers_runtime_install_already_installed, copy(RuntimeInstallResult.AlreadyInstalled())?.message)
        assertEquals(Res.string.settings_computers_runtime_install_failed, copy(RuntimeInstallResult.Failed(null))?.message)
        assertEquals(Res.string.settings_computers_runtime_install_in_progress, copy(RuntimeInstallResult.Error(RuntimeOperationErrorCode.InProgress))?.message)
        mapOf(
            RuntimeInstallUnsupportedReason.Platform to Res.string.settings_computers_runtime_install_unsupported_platform,
            RuntimeInstallUnsupportedReason.RequiresPrivileges to Res.string.settings_computers_runtime_install_unsupported_requires_privileges,
            RuntimeInstallUnsupportedReason.RequiresGui to Res.string.settings_computers_runtime_install_unsupported_requires_gui,
            RuntimeInstallUnsupportedReason.MissingTool to Res.string.settings_computers_runtime_install_unsupported_missing_tool,
            RuntimeInstallUnsupportedReason.LocationUnknown to Res.string.settings_computers_runtime_install_unsupported_location_unknown,
            RuntimeInstallUnsupportedReason.Configuration to Res.string.settings_computers_runtime_install_unsupported_configuration,
            RuntimeInstallUnsupportedReason.Bundled to Res.string.settings_computers_runtime_install_unsupported_other,
            RuntimeInstallUnsupportedReason.LineRequired to Res.string.settings_computers_runtime_install_unsupported_other,
            RuntimeInstallUnsupportedReason.UnknownLine to Res.string.settings_computers_runtime_install_unsupported_other,
        ).forEach { (reason, expected) -> assertEquals(expected, computerRuntimeInstallUnsupportedCopy(reason), reason.wire) }
    }

    @Test
    fun installReadsThePlanRunsOnlyOnConfirmAndReadsTheComputerAgain() = runTest {
        val ops = FakeInstallOps()
        val controller = ComputerRuntimeInstallController(ops, backgroundScope)
        controller.read()
        runCurrent()
        assertEquals(RuntimeAuthRuntime.Grok, controller.installableAs("grok"))
        assertNull(controller.installableAs("claude"), "not offered for it")
        assertNull(controller.installableAs("kimi"), "the deprecated Kimi CLI has no sign-in id")

        controller.start(RuntimeAuthRuntime.Grok, "grok", "Grok")
        assertIs<ComputerRuntimeInstallState.Planning>(controller.state)
        assertTrue(controller.busy)
        controller.start(RuntimeAuthRuntime.Qwen, "qwen", "Qwen Code")
        runCurrent()
        assertEquals(listOf("view", "plan grok"), ops.calls, "one at a time")
        assertIs<ComputerRuntimeInstallState.Confirm>(controller.state)

        // Cancel drops the plan; nothing ran.
        controller.cancel()
        assertEquals(ComputerRuntimeInstallState.Idle, controller.state)
        controller.confirm()
        runCurrent()
        assertEquals(listOf("view", "plan grok"), ops.calls)

        controller.start(RuntimeAuthRuntime.Grok, "grok", "Grok")
        runCurrent()
        controller.confirm()
        assertIs<ComputerRuntimeInstallState.Running>(controller.state)
        runCurrent()
        assertEquals(listOf("view", "plan grok", "plan grok", "install grok", "view force"), ops.calls)
        assertEquals(RuntimeInstallResult.Installed("0.1.9"), assertIs<ComputerRuntimeInstallState.Done>(controller.state).result)
    }

    @Test
    fun aRefusedInstallSaysWhyWithItsTraceId() = runTest {
        val ops = FakeInstallOps(plan = RuntimeAuthCall.Refused(RuntimeAuthRequestError(status = 409, code = "computer_upgrade_required", traceId = "t1")))
        val controller = ComputerRuntimeInstallController(ops, backgroundScope)
        controller.start(RuntimeAuthRuntime.Grok, "grok", "Grok")
        runCurrent()
        val refused = assertIs<ComputerRuntimeInstallState.Refused>(controller.state)
        assertEquals(ComputerRuntimeOperationRefusal.ComputerUpgradeRequired, refused.reason)
        assertEquals("t1", computerRuntimeInstallCopy(refused)?.traceId)
        assertEquals(Res.string.settings_computers_runtime_install_computer_too_old, computerRuntimeInstallCopy(refused)?.message)
    }

    @Test
    fun upgradeIsOfferedOnTheCardOnlyWhereTheServerSaysAndRunsOncePerPress() = runTest {
        val ops = FakeUpgradeOps()
        val controller = ComputerRuntimeAuthController("s1", "m1", "claude", RuntimeAuthRuntime.Claude, ops, backgroundScope)
        controller.read()
        runCurrent()
        val offer = assertNotNull(controller.upgradeOffer)
        assertEquals(ComputerRuntimeUpgradeState.Idle, offer.state)

        offer.onUpgrade()
        assertEquals(ComputerRuntimeUpgradeState.Running, controller.upgrade)
        controller.startUpgrade()
        ops.upgrade.complete(RuntimeAuthCall.Done(RuntimeUpgradeResult.Upgraded("2.0.1", "2.0.5")))
        runCurrent()
        assertEquals(listOf("view", "upgrade claude", "view force"), ops.calls, "one press, one run; the Computer is read again after")
        assertEquals(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Upgraded("2.0.1", "2.0.5")), controller.upgrade)

        // Not upgradable here (or the flag off): no Upgrade on the card.
        val codex = ComputerRuntimeAuthController("s1", "m1", "codex", RuntimeAuthRuntime.Codex, ops, backgroundScope)
        codex.read()
        runCurrent()
        assertNull(codex.upgradeOffer)
    }

    @Test
    fun tooOldWithUpgradeOfferedDropsTheUpdateItThereGuidance() {
        val view = UPGRADE_VIEW.copy(status = mapOf(RuntimeAuthRuntime.Claude to RuntimeAuthStatus.Unavailable(RuntimeAuthUnavailableReason.VersionUnsupported)))
        val offer = ComputerRuntimeUpgradeOffer(ComputerRuntimeUpgradeState.Idle) {}
        assertEquals(
            ComputerRuntimeAuthAction.Unavailable(RuntimeAuthUnavailableReason.VersionUnsupported, upgradeOffered = true),
            ComputerRuntimeAuthCardState("claude", RuntimeAuthRuntime.Claude, view, upgrade = offer).action(),
        )
        assertEquals(
            ComputerRuntimeAuthAction.Unavailable(RuntimeAuthUnavailableReason.VersionUnsupported, upgradeOffered = false),
            ComputerRuntimeAuthCardState("claude", RuntimeAuthRuntime.Claude, view).action(),
        )
        // Signed in with no sign-out (Qoder): no action row; Upgrade is the version line's link (slock #15482).
        val qoder = UPGRADE_VIEW.copy(status = mapOf(RuntimeAuthRuntime.Qoder to RuntimeAuthStatus.LoggedIn()), upgradableRuntimes = listOf(RuntimeAuthRuntime.Qoder))
        assertNull(ComputerRuntimeAuthCardState("qoder", RuntimeAuthRuntime.Qoder, qoder).action())
        assertFalse(ComputerRuntimeAuthCardState("qoder", RuntimeAuthRuntime.Qoder, qoder, upgrade = offer).hasActions)
    }

    @Test
    fun upgradeIsOfferedAgainOnlyAfterAFailureAnotherPressMayFix() {
        // Web `upgradeAgainAfter` (slock #15482): an outcome takes the version line; the link comes back
        // only where a second press may help.
        assertTrue(computerRuntimeUpgradeAgainAfter(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.Failed)))
        assertTrue(computerRuntimeUpgradeAgainAfter(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.ComputerOffline)))
        assertFalse(computerRuntimeUpgradeAgainAfter(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.ComputerTimeout)))
        assertFalse(computerRuntimeUpgradeAgainAfter(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.RateLimited)))
        assertFalse(computerRuntimeUpgradeAgainAfter(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.ComputerUpgradeRequired)))
        assertTrue(computerRuntimeUpgradeAgainAfter(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Error(RuntimeOperationErrorCode.Internal))))
        assertFalse(computerRuntimeUpgradeAgainAfter(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Error(RuntimeOperationErrorCode.InProgress))))
        assertFalse(computerRuntimeUpgradeAgainAfter(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Upgraded(from = "1.0.0", to = "1.1.0"))))
        assertFalse(computerRuntimeUpgradeAgainAfter(ComputerRuntimeUpgradeState.Idle))
        assertFalse(computerRuntimeUpgradeAgainAfter(ComputerRuntimeUpgradeState.Running))
    }

    /**
     * slock #15429 (staging 5881c7b53c): web runtimeUpgradeOutcomeCopy.ts `runtimeUpdateCheckCopy` and
     * `runtimeUpgradeButtonShown`, the cases of tests/runtimeUpgrade.behavior.test.tsx. The line stands in
     * for the version; Upgrade shows only for a newer version, or where the Computer reported no check.
     */
    @Test
    fun theCardSaysWhatTheUpdateCheckFoundAndOffersUpgradeOnlyForANewerVersion() {
        fun card(
            update: RuntimeUpdateCheck?,
            status: RuntimeAuthStatus = RuntimeAuthStatus.LoggedIn(),
            state: ComputerRuntimeUpgradeState = ComputerRuntimeUpgradeState.Idle,
        ) = ComputerRuntimeAuthCardState(
            "claude",
            RuntimeAuthRuntime.Claude,
            UPGRADE_VIEW.copy(status = mapOf(RuntimeAuthRuntime.Claude to status)),
            upgrade = ComputerRuntimeUpgradeOffer(state) {},
            update = update,
        )
        data class Case(val name: String, val update: RuntimeUpdateCheck?, val version: String?, val line: ComputerRuntimeUpdateCheckCopy?, val upgrade: Boolean)
        listOf(
            Case(
                "a newer version",
                RuntimeUpdateCheck.Ok("1.5.10", "1.5.11", updateAvailable = true, checkedAtMillis = CHECKED_AT_MILLIS),
                "1.5.10",
                ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_update_available, listOf("1.5.10", "1.5.11")),
                upgrade = true,
            ),
            Case(
                "already the latest: no button to press just to ask",
                RuntimeUpdateCheck.Ok("1.5.11", "1.5.11", updateAvailable = false, checkedAtMillis = CHECKED_AT_MILLIS),
                "1.5.11",
                ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_latest, listOf("1.5.11")),
                upgrade = false,
            ),
            Case(
                "updates managed outside Raft",
                RuntimeUpdateCheck.Unavailable(RuntimeUpdateCheckUnavailableReason.PackageManager, CHECKED_AT_MILLIS),
                "2.0.5",
                ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_managed_elsewhere_version, listOf("2.0.5")),
                upgrade = false,
            ),
            Case(
                "updates managed outside Raft, no version reported",
                RuntimeUpdateCheck.Unavailable(RuntimeUpdateCheckUnavailableReason.UpdatesDisabled, CHECKED_AT_MILLIS),
                null,
                ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_managed_elsewhere),
                upgrade = false,
            ),
            Case(
                "the check failed",
                RuntimeUpdateCheck.Unavailable(RuntimeUpdateCheckUnavailableReason.LookupFailed, CHECKED_AT_MILLIS),
                "1.5.10",
                ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_check_unavailable_version, listOf("1.5.10")),
                upgrade = false,
            ),
            Case(
                "the check failed, no version reported",
                RuntimeUpdateCheck.Unavailable(RuntimeUpdateCheckUnavailableReason.VersionUnreadable, CHECKED_AT_MILLIS),
                " ",
                ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_check_unavailable),
                upgrade = false,
            ),
            Case("no check (an older Computer or server): the version line and Upgrade as before", null, "1.5.10", null, upgrade = true),
        ).forEach { case ->
            val shown = card(case.update)
            assertEquals(case.line, shown.updateCheckCopy(case.version), case.name)
            assertEquals(case.upgrade, shown.upgradeButtonShown, case.name)
        }
        // Each reason slock calls a deliberate choice reads as managed outside Raft; the others as a failed check.
        val managedElsewhere =
            setOf(
                RuntimeUpdateCheckUnavailableReason.PackageManager,
                RuntimeUpdateCheckUnavailableReason.UpdatesDisabled,
                RuntimeUpdateCheckUnavailableReason.UnmanagedInstallation,
                RuntimeUpdateCheckUnavailableReason.UnsupportedInstallation,
            )
        RuntimeUpdateCheckUnavailableReason.entries.forEach { reason ->
            val unavailable = RuntimeUpdateCheck.Unavailable(reason, CHECKED_AT_MILLIS)
            assertEquals(
                if (reason in managedElsewhere) {
                    Res.string.settings_computers_runtime_upgrade_managed_elsewhere_version
                } else {
                    Res.string.settings_computers_runtime_upgrade_check_unavailable_version
                },
                card(unavailable).updateCheckCopy("2.0.5")?.message,
                reason.wire,
            )
            assertFalse(card(unavailable).upgradeButtonShown, reason.wire)
        }

        // A CLI too old to sign in (`version_unsupported`) keeps Upgrade whatever the check says; one that runs stays busy.
        val tooOld = RuntimeAuthStatus.Unavailable(RuntimeAuthUnavailableReason.VersionUnsupported)
        val latest = RuntimeUpdateCheck.Ok("1.5.11", "1.5.11", updateAvailable = false, checkedAtMillis = CHECKED_AT_MILLIS)
        assertTrue(card(RuntimeUpdateCheck.Unavailable(RuntimeUpdateCheckUnavailableReason.PackageManager, CHECKED_AT_MILLIS), status = tooOld).upgradeButtonShown)
        assertTrue(card(latest, status = tooOld).upgradeButtonShown)
        assertTrue(card(latest, state = ComputerRuntimeUpgradeState.Running).upgradeButtonShown)
        // An outcome stays once the check says latest, without the button (web: "Upgraded … from 1.5.10 to 1.5.11.").
        val upgraded = card(latest, state = ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Upgraded("1.5.10", "1.5.11")))
        assertFalse(upgraded.upgradeButtonShown)
        assertNotNull(upgraded.upgrade?.state?.let(::computerRuntimeUpgradeCopy))

        // Not offered here, or to a viewer who may not act: neither the line nor the button.
        assertNull(card(latest).copy(upgrade = null).updateCheckCopy("1.5.11"))
        assertFalse(card(null).copy(upgrade = null).upgradeButtonShown)
        assertFalse(card(null).copy(view = UPGRADE_VIEW.copy(canAct = false)).upgradeButtonShown)

        // Qoder has no sign-out: no action row either way; Upgrade and its outcome are the version line's (slock #15482).
        val qoder = UPGRADE_VIEW.copy(status = mapOf(RuntimeAuthRuntime.Qoder to RuntimeAuthStatus.LoggedIn()), upgradableRuntimes = listOf(RuntimeAuthRuntime.Qoder))
        fun qoderCard(state: ComputerRuntimeUpgradeState) =
            ComputerRuntimeAuthCardState("qoder", RuntimeAuthRuntime.Qoder, qoder, upgrade = ComputerRuntimeUpgradeOffer(state) {}, update = latest)
        assertFalse(qoderCard(ComputerRuntimeUpgradeState.Idle).hasActions)
        assertFalse(qoderCard(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Current("1.5.11"))).hasActions)
    }

    /**
     * Web `runtimeUpdateOf` on the card: the controller reads the check only for a runtime Upgrade is
     * offered for, and only while it is under a day old by the Computer's `checkedAt` (virtual time
     * drives the wall clock); a stale "Latest" is no answer, so Upgrade is offered as before.
     */
    @Test
    fun theCardReadsTheUpdateCheckOnlyForAnOfferedRuntimeAndOnlyWhileItIsUnderADayOld() = runTest {
        val latest = RuntimeUpdateCheck.Ok("2.0.5", "2.0.5", updateAvailable = false, checkedAtMillis = CHECKED_AT_MILLIS)
        val ops =
            FakeUpgradeOps(
                UPGRADE_VIEW.copy(
                    status = mapOf(RuntimeAuthRuntime.Claude to RuntimeAuthStatus.LoggedIn()),
                    runtimeUpdates = mapOf(RuntimeAuthRuntime.Claude to latest, RuntimeAuthRuntime.Codex to latest),
                )
            )
        // The card is read a minute after the Computer ran its check.
        val now = { CHECKED_AT_MILLIS + 60_000L + testScheduler.currentTime }
        val controller = ComputerRuntimeAuthController("s1", "m1", "claude", RuntimeAuthRuntime.Claude, ops, backgroundScope, nowEpochMillis = now)
        assertNull(controller.cardState, "nothing to draw before the read")
        controller.read()
        runCurrent()
        val card = assertNotNull(controller.cardState)
        assertEquals(latest, card.update)
        assertEquals(ComputerRuntimeUpdateCheckCopy(Res.string.settings_computers_runtime_upgrade_latest, listOf("2.0.5")), card.updateCheckCopy("2.0.5"))
        assertFalse(card.upgradeButtonShown)

        // Codex is not offered on this Computer: its check is never read.
        val codex = ComputerRuntimeAuthController("s1", "m1", "codex", RuntimeAuthRuntime.Codex, ops, backgroundScope, nowEpochMillis = now)
        codex.read()
        runCurrent()
        assertNull(codex.updateCheck)
        assertNull(codex.cardState?.upgrade)

        // A day after the check it still counts; a moment later it is no answer: the version line and Upgrade as before.
        advanceTimeBy(RUNTIME_UPDATE_CHECK_MAX_AGE_MILLIS - 60_000L)
        assertEquals(latest, controller.updateCheck)
        advanceTimeBy(1L)
        val stale = assertNotNull(controller.cardState)
        assertNull(stale.update)
        assertNull(stale.updateCheckCopy("2.0.5"))
        assertTrue(stale.upgradeButtonShown)
        assertEquals(listOf("view", "view"), ops.calls, "the age is read from the clock, never by asking the Computer again")
    }

    private class FakeInstallOps(
        private val plan: RuntimeAuthCall<RuntimeInstallPlan> =
            RuntimeAuthCall.Done(RuntimeInstallPlan.Plan(listOf("curl -fsSL https://x.ai/cli/install.sh | bash"), "https://docs.x.ai/docs/grok-build")),
    ) : ComputerRuntimeInstallOps {
        val calls = mutableListOf<String>()

        override suspend fun view(force: Boolean): RuntimeAuthView {
            calls += if (force) "view force" else "view"
            return UPGRADE_VIEW
        }

        override suspend fun installPlan(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeInstallPlan> {
            calls += "plan ${runtime.wire}"
            return plan
        }

        override suspend fun install(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeInstallResult> {
            calls += "install ${runtime.wire}"
            return RuntimeAuthCall.Done(RuntimeInstallResult.Installed("0.1.9"))
        }
    }

    private class FakeUpgradeOps(private val answer: RuntimeAuthView = UPGRADE_VIEW) : ComputerRuntimeAuthOps {
        val calls = mutableListOf<String>()
        val upgrade = CompletableDeferred<RuntimeAuthCall<RuntimeUpgradeResult>>()

        override suspend fun view(force: Boolean): RuntimeAuthView {
            calls += if (force) "view force" else "view"
            return answer
        }

        override suspend fun upgrade(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeUpgradeResult> {
            calls += "upgrade ${runtime.wire}"
            return upgrade.await()
        }

        override suspend fun invalidate() = Unit

        override suspend fun startLogin(runtime: RuntimeAuthRuntime, region: RuntimeAuthLoginRegion?, providerId: String?, oauthMethod: String?): RuntimeAuthCall<String> =
            error("unexpected")

        override suspend fun submitCode(runtime: RuntimeAuthRuntime, loginId: String, code: String): RuntimeAuthCall<Unit> = error("unexpected")

        override fun cancelLogin(runtime: RuntimeAuthRuntime, loginId: String) = Unit

        override suspend fun logout(runtime: RuntimeAuthRuntime, providerId: String?): RuntimeAuthCall<RuntimeAuthLogoutResult> = error("unexpected")

        override suspend fun providers(runtime: RuntimeAuthRuntime): RuntimeAuthCall<RuntimeAuthProvidersResult> = error("unexpected")

        override suspend fun connect(runtime: RuntimeAuthRuntime, providerId: String, apiKey: String): RuntimeAuthCall<RuntimeAuthProviderConnectResult> =
            error("unexpected")
    }

    private companion object {
        /** slock runtimeUpgrade.behavior.test.tsx `CHECKED_AT`, 2026-11-18T10:00:00.000Z. */
        val CHECKED_AT_MILLIS = Instant.parse("2026-11-18T10:00:00.000Z").toEpochMilliseconds()

        val UPGRADE_VIEW =
            RuntimeAuthView(
                canAct = true,
                availability = RuntimeAuthAvailability.Ok,
                runtimes = listOf(RuntimeAuthRuntime.Claude, RuntimeAuthRuntime.Codex, RuntimeAuthRuntime.Qoder),
                upgradableRuntimes = listOf(RuntimeAuthRuntime.Claude),
                installableRuntimes = listOf(RuntimeAuthRuntime.Grok, RuntimeAuthRuntime.Qwen),
            )
    }
}
