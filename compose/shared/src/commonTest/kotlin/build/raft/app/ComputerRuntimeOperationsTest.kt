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
import build.raft.app.data.RuntimeOperationErrorCode
import build.raft.app.data.RuntimeUpgradeResult
import build.raft.app.data.RuntimeUpgradeUnsupportedReason
import build.raft.app.resources.*
import kotlinx.coroutines.CompletableDeferred
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
        // Signed in with no sign-out (Qoder): the card still has Upgrade to show.
        val qoder = UPGRADE_VIEW.copy(status = mapOf(RuntimeAuthRuntime.Qoder to RuntimeAuthStatus.LoggedIn()), upgradableRuntimes = listOf(RuntimeAuthRuntime.Qoder))
        assertNull(ComputerRuntimeAuthCardState("qoder", RuntimeAuthRuntime.Qoder, qoder).action())
        assertTrue(ComputerRuntimeAuthCardState("qoder", RuntimeAuthRuntime.Qoder, qoder, upgrade = offer).hasActions)
        assertFalse(ComputerRuntimeAuthCardState("qoder", RuntimeAuthRuntime.Qoder, qoder).hasActions)
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

    private class FakeUpgradeOps : ComputerRuntimeAuthOps {
        val calls = mutableListOf<String>()
        val upgrade = CompletableDeferred<RuntimeAuthCall<RuntimeUpgradeResult>>()

        override suspend fun view(force: Boolean): RuntimeAuthView {
            calls += if (force) "view force" else "view"
            return UPGRADE_VIEW
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
