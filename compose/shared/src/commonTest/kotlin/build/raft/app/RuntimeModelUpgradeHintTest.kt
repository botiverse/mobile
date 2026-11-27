package build.raft.app

import build.raft.app.data.RuntimeOperationErrorCode
import build.raft.app.data.RuntimeUpgradeResult
import build.raft.app.data.RuntimeUpgradeUnsupportedReason
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SourceStatus
import build.raft.app.data.runtimeform.v2.RuntimeFormV2UnavailableChoice
import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for `#wg-mobile-sync:#344` (`botiverse/slock` `#17086`, commit `0c6b52da`):
 * SemVer 2.0.0 extraction & comparison, OAR suggested-versions floor, unavailable model reason
 * classification, unified priority resolver, CJK-aware sentence/action joining, and upgrade
 * settlement/failure copy mapping.
 */
class RuntimeModelUpgradeHintTest {
    @Test
    fun extractAndCompareSemverFollowsSemver200IncludingPrereleaseAndRawCliStrings() {
        assertEquals("2.1.59", extractRuntimeSemver("2.1.59 (Claude Code)"))
        assertEquals("0.150.0", extractRuntimeSemver("codex-cli 0.150.0"))
        assertEquals("0.166.0-rc.1", extractRuntimeSemver("v0.166.0-rc.1"))
        assertNull(extractRuntimeSemver("unknown"))
        assertNull(extractRuntimeSemver(null))

        assertTrue(requireNotNull(compareRuntimeSemver("0.150.0", "0.166.0")) < 0)
        assertTrue(requireNotNull(compareRuntimeSemver("0.166.0-rc.1", "0.166.0")) < 0)
        assertTrue(requireNotNull(compareRuntimeSemver("0.166.0-beta.2", "0.166.0-beta.10")) < 0)
        assertTrue(requireNotNull(compareRuntimeSemver("0.166.0-1", "0.166.0-alpha")) < 0)
        assertEquals(0, compareRuntimeSemver("0.166.0", "0.166.0"))
        assertTrue(requireNotNull(compareRuntimeSemver("2.1.321", "2.1.200")) > 0)
        assertNull(compareRuntimeSemver("not-a-version", "1.0.0"))
    }

    @Test
    fun classifyUnavailableModelReasonDistinguishesUpgradeVersionGenericAndPlanGatedReasons() {
        assertEquals(
            UnavailableModelReasonDisplay.UpgradeVersion("2.1.321"),
            classifyUnavailableModelReason("Requires Claude Code 2.1.321+"),
        )
        assertEquals(
            UnavailableModelReasonDisplay.UpgradeVersion("0.166.0-beta.1"),
            classifyUnavailableModelReason("Needs v0.166.0-beta.1 or newer"),
        )
        assertEquals(
            UnavailableModelReasonDisplay.UpgradeGeneric,
            classifyUnavailableModelReason("Update Claude Code to use this model"),
        )
        assertEquals(
            UnavailableModelReasonDisplay.UpgradeGeneric,
            classifyUnavailableModelReason("需要升级 CLI 才能使用"),
        )
        assertEquals(
            UnavailableModelReasonDisplay.Raw("Requires Claude Max plan"),
            classifyUnavailableModelReason("Requires Claude Max plan"),
        )
        assertEquals(
            UnavailableModelReasonDisplay.Raw("Needs a paid plan"),
            classifyUnavailableModelReason("Needs a paid plan"),
        )
        assertNull(classifyUnavailableModelReason("   "))
        assertNull(classifyUnavailableModelReason(null))
    }

    @Test
    fun priorityResolverOrdersRuntimeOutdatedOverUnavailableModelsOverSuggestedVersion() {
        val upgradeGated =
            listOf(
                RuntimeFormV2UnavailableChoice("claude-opus-4-6", "Claude Opus 4.6", reason = "Requires Claude Code 2.1.321+"),
                RuntimeFormV2UnavailableChoice("claude-sonnet-4-6", "Claude Sonnet 4.6", reason = "Update Claude Code to use this model"),
                RuntimeFormV2UnavailableChoice("claude-max-only", "Claude Max", reason = "Requires Claude Max plan"),
                RuntimeFormV2UnavailableChoice("connection:c1", "Saved conn", reason = "Update required", connectionId = "c1"),
            )

        // Priority 1: runtime_outdated (non-stale) beats Layer 1 & Layer 2.
        val outdated =
            resolveModelUpgradeHint(
                runtimeId = "claude",
                installedVersion = "2.1.200",
                sourceStatus = RuntimeFormV2SourceStatus(reason = "runtime_outdated", unavailable = false, stale = false, retryable = true),
                unavailableOptions = upgradeGated,
            )
        assertEquals(RuntimeModelUpgradeHint.RuntimeOutdated, outdated)
        assertTrue(outdated!!.isDanger)

        // Non-outdated sourceStatus (probe_timeout, stale, not_signed_in) suppresses upgrade hint.
        assertNull(
            resolveModelUpgradeHint(
                runtimeId = "claude",
                installedVersion = "2.1.200",
                sourceStatus = RuntimeFormV2SourceStatus(reason = "probe_timeout", unavailable = false, stale = false, retryable = true),
                unavailableOptions = upgradeGated,
            )
        )
        assertNull(
            resolveModelUpgradeHint(
                runtimeId = "claude",
                installedVersion = "2.1.200",
                sourceStatus = RuntimeFormV2SourceStatus(reason = "runtime_outdated", unavailable = false, stale = true, retryable = true),
                unavailableOptions = upgradeGated,
            )
        )
        assertNull(
            resolveModelUpgradeHint(
                runtimeId = "claude",
                installedVersion = "2.1.200",
                sourceStatus = RuntimeFormV2SourceStatus(reason = "not_signed_in", unavailable = true, stale = false, retryable = false),
                unavailableOptions = upgradeGated,
            )
        )

        // Priority 2 (Layer 1): counts only non-connection upgrade-gated unavailable models (2 of 4).
        val layer1 =
            resolveModelUpgradeHint(
                runtimeId = "claude",
                installedVersion = "2.1.200",
                sourceStatus = null,
                unavailableOptions = upgradeGated,
            )
        assertEquals(RuntimeModelUpgradeHint.UnavailableModels(2), layer1)
        assertFalse(layer1!!.isDanger)

        // Priority 3 (Layer 2): plan-gated unavailable models do not trigger Layer 1 -> falls through to suggested_version.
        val planOnly = listOf(RuntimeFormV2UnavailableChoice("m1", "M1", reason = "Requires Max plan"))
        val layer2Codex =
            resolveModelUpgradeHint(
                runtimeId = "codex",
                installedVersion = "codex-cli 0.150.0",
                sourceStatus = null,
                unavailableOptions = planOnly,
            )
        assertEquals(RuntimeModelUpgradeHint.SuggestedVersion("0.150.0", "0.166.0"), layer2Codex)
        assertFalse(layer2Codex!!.isDanger)

        // Prerelease strictly below floor triggers Layer 2; at or above floor returns null.
        assertEquals(
            RuntimeModelUpgradeHint.SuggestedVersion("0.166.0-rc.1", "0.166.0"),
            resolveModelUpgradeHint("codex", "0.166.0-rc.1", null, emptyList()),
        )
        assertNull(resolveModelUpgradeHint("codex", "0.166.0", null, emptyList()))
        assertNull(resolveModelUpgradeHint("claude", "2.1.321 (Claude Code)", null, emptyList()))
        assertNull(resolveModelUpgradeHint("gemini", "1.0.0", null, emptyList()))
    }

    @Test
    fun cjkSentenceJoinerAndInlineActionGapRespectPunctuation() {
        assertEquals("Sentence one. Sentence two.", joinLocalizedSentences("Sentence one.", "Sentence two."))
        assertEquals("Codex CLI 版本较旧，可能缺少新模型。Computer 的所有者可以升级。", joinLocalizedSentences("Codex CLI 版本较旧，可能缺少新模型。", "Computer 的所有者可以升级。"))
        assertEquals(" ", inlineActionGap("Sentence one."))
        assertEquals("", inlineActionGap("Codex CLI 版本较旧，可能缺少新模型。"))
        assertEquals("", inlineActionGap("升级没有完成："))
    }

    @Test
    fun upgradeSuccessAndFailureCopyMapAllRefusalsAndDoneResults() {
        assertTrue(isRuntimeUpgradeSuccess(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Upgraded("2.1.200", "2.1.321"))))
        assertTrue(isRuntimeUpgradeSuccess(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Current("2.1.321"))))
        assertFalse(isRuntimeUpgradeSuccess(ComputerRuntimeUpgradeState.Idle))
        assertFalse(isRuntimeUpgradeSuccess(ComputerRuntimeUpgradeState.Running))
        assertFalse(isRuntimeUpgradeSuccess(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Failed(1))))

        assertNull(runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Idle))
        assertNull(runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Running))
        assertNull(runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Upgraded())))
        assertNull(runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Current())))

        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_offline, retryable = true),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.ComputerOffline)),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_timeout, retryable = true),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.ComputerTimeout)),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_rate_limited, retryable = true),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.RateLimited)),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_computer_too_old, retryable = false),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.ComputerUpgradeRequired)),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed, retryable = true),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Refused(ComputerRuntimeOperationRefusal.Failed)),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_unchanged, retryable = true),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Unchanged("2.1.200"))),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed, retryable = true),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Failed(1))),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_requires_terminal, retryable = false),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Unsupported(RuntimeUpgradeUnsupportedReason.RequiresTerminal))),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_unsupported_installation, retryable = false),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Unsupported(RuntimeUpgradeUnsupportedReason.UnsupportedInstallation))),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_not_installed, retryable = false),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.NotInstalled)),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed_in_progress, retryable = true),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Error(RuntimeOperationErrorCode.InProgress))),
        )
        assertEquals(
            RuntimeModelUpgradeFailureCopy(Res.string.runtime_form_v2_upgrade_hint_failed, retryable = true),
            runtimeModelUpgradeFailureCopy(ComputerRuntimeUpgradeState.Done(RuntimeUpgradeResult.Error(RuntimeOperationErrorCode.Internal))),
        )
    }
}
