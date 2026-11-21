package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The upgrade dialog's trigger mapping (#proj-mobile #54/#63): which limit opens what, on which plan
 * and platform, and the web page its action opens (web `UpgradeDialog.tsx`, `usePaywallUpgrade`,
 * botiverse/slock staging afa0e4ada5).
 */
class RaftUpgradeDialogTest {
    @Test
    fun aFreeServerGetsTheUpgradeOfferForEveryLimit() {
        RaftUpgradeGate.entries.forEach { gate ->
            assertEquals(
                RaftUpgradeOutcome.Dialog(RaftUpgradeMode.Upgrade),
                raftUpgradeOutcome(gate, plan = "free", linksOut = true),
                gate.wire,
            )
            // An unknown plan counts as Free, like web `!plan || plan === "free"`.
            assertEquals(RaftUpgradeOutcome.Dialog(RaftUpgradeMode.Upgrade), raftUpgradeOutcome(gate, plan = null, linksOut = true))
        }
    }

    @Test
    fun aProServerOutOfSeatsGetsAddSeats() {
        assertEquals(
            RaftUpgradeOutcome.Dialog(RaftUpgradeMode.AddSeats),
            raftUpgradeOutcome(RaftUpgradeGate.SeatLimitInvite, plan = "pro", linksOut = true),
        )
        assertEquals(
            RaftUpgradeOutcome.Dialog(RaftUpgradeMode.AddSeats),
            raftUpgradeOutcome(RaftUpgradeGate.SeatLimitAgentCreate, plan = "pro", linksOut = true),
        )
    }

    @Test
    fun aPaidServerKeepsPlanAndBillingForJointChannelsAndNothingForLimitsProLifts() {
        listOf(
            RaftUpgradeGate.JointChannelFreeCap,
            RaftUpgradeGate.JointChannelHostLimit,
            RaftUpgradeGate.JointChannelOverLimit,
            RaftUpgradeGate.JointChannelReadOnly,
            RaftUpgradeGate.ExternalChannelFreeLimit,
            RaftUpgradeGate.ExternalChannelFrozen,
            RaftUpgradeGate.General,
        ).forEach { gate ->
            assertEquals(RaftUpgradeOutcome.OpenBilling, raftUpgradeOutcome(gate, plan = "pro", linksOut = true), gate.wire)
        }
        listOf(
            RaftUpgradeGate.History30d,
            RaftUpgradeGate.UploadMonthlyQuota,
            RaftUpgradeGate.UploadFileSize,
            RaftUpgradeGate.AgentMigration,
            RaftUpgradeGate.ProTheme,
        ).forEach { gate ->
            assertEquals(RaftUpgradeOutcome.None, raftUpgradeOutcome(gate, plan = "pro", linksOut = true), gate.wire)
        }
    }

    @Test
    fun withoutALinkOutTheLimitIsOnlyStated() {
        // iOS (artin 11-05): no upgrade button and no add-seats entry, only the limit.
        RaftUpgradeGate.entries.forEach { gate ->
            assertEquals(
                RaftUpgradeOutcome.Dialog(RaftUpgradeMode.LimitNotice),
                raftUpgradeOutcome(gate, plan = "free", linksOut = false),
                gate.wire,
            )
        }
        assertEquals(
            RaftUpgradeOutcome.Dialog(RaftUpgradeMode.LimitNotice),
            raftUpgradeOutcome(RaftUpgradeGate.SeatLimitInvite, plan = "pro", linksOut = false),
        )
        assertEquals(RaftUpgradeOutcome.None, raftUpgradeOutcome(RaftUpgradeGate.JointChannelReadOnly, plan = "pro", linksOut = false))
    }

    @Test
    fun iosLinksOutOnlyWithTheExternalPurchaseFlag() {
        assertTrue(raftUpgradeLinksOut(appStoreBillingRules = false, iosLinkEnabled = false))
        assertFalse(raftUpgradeLinksOut(appStoreBillingRules = true, iosLinkEnabled = false))
        assertTrue(raftUpgradeLinksOut(appStoreBillingRules = true, iosLinkEnabled = true))
        // Off by default; on, iOS Billing turns to the web in the system browser as well.
        assertFalse(iosExternalPurchaseLinkEnabled())
        assertEquals(IosBillingPresentation.ViewOnly, iosBillingPresentation())
        assertEquals(IosBillingPresentation.External, iosBillingPresentation(linkEnabled = true))
    }

    @Test
    fun serverErrorCodesMapToTheirGates() {
        assertEquals(RaftUpgradeGate.UploadMonthlyQuota, raftUpgradeGateForErrorCode("FILE_UPLOAD_QUOTA_EXCEEDED"))
        assertEquals(RaftUpgradeGate.UploadMonthlyQuota, raftUpgradeGateForErrorCode("file_upload_quota_exceeded"))
        assertEquals(RaftUpgradeGate.UploadMonthlyQuota, raftUpgradeGateForErrorCode("UPLOAD_QUOTA_EXCEEDED"))
        assertEquals(RaftUpgradeGate.ExternalChannelFrozen, raftUpgradeGateForErrorCode("external_limit_frozen"))
        assertEquals(RaftUpgradeGate.AgentMigration, raftUpgradeGateForErrorCode("MIGRATION_PRO_PLAN_REQUIRED"))
        assertEquals(RaftUpgradeGate.SeatLimitAgentCreate, raftUpgradeGateForErrorCode("agent_limit_reached"))
        assertEquals(RaftUpgradeGate.SeatLimitInvite, raftUpgradeGateForErrorCode("human_seat_limit_reached"))
        // botiverse/slock@44ce160f1 routes/paywallHit.ts: `free_external_limit` is this gate.
        assertEquals(RaftUpgradeGate.ExternalChannelFreeLimit, raftUpgradeGateForErrorCode("free_external_limit"))
        assertNull(raftUpgradeGateForErrorCode("UPLOAD_RATE_LIMITED"))
        assertNull(raftUpgradeGateForErrorCode(null))
        assertEquals(RaftUpgradeGate.UploadMonthlyQuota, threadAttachmentUploadFailureUpgradeGate("FILE_UPLOAD_QUOTA_EXCEEDED"))
        assertEquals(RaftUpgradeGate.ExternalChannelFrozen, threadAttachmentUploadFailureUpgradeGate("external_limit_frozen"))
        assertNull(threadAttachmentUploadFailureUpgradeGate("UPLOAD_FORBIDDEN"))
    }

    @Test
    fun aRefusedInviteIsTheSeatLimitByItsMessage() {
        assertTrue(raftIsSeatLimitMessage("Human seat limit reached (20/20 on Pro plan)"))
        assertTrue(raftIsSeatLimitMessage("Inviting members requires the Pro plan"))
        assertFalse(raftIsSeatLimitMessage("Invalid email"))
        assertFalse(raftIsSeatLimitMessage(null))
        assertTrue(raftIsSeatLimitError("human_seat_limit_reached", null))
        assertFalse(raftIsSeatLimitError("http_400", "Invalid email"))
    }

    @Test
    fun onlyFreesPerFileCeilingOffersTheUpgrade() {
        assertTrue(raftUploadSizeLimitIsFree(50L * 1024 * 1024))
        assertFalse(raftUploadSizeLimitIsFree(200L * 1024 * 1024))
        assertFalse(raftUploadSizeLimitIsFree(0))
    }

    @Test
    fun theUpgradeLinkOpensTheWebDialogForTheGate() {
        assertEquals(
            "https://raft.build/s/acme/settings/billing?upgrade=history_30d&upgradeCanceled=1&lang=zh-CN",
            raftUpgradeWebUrl("https://raft.build/", "acme", RaftUpgradeGate.History30d, RaftUpgradeMode.Upgrade, "zh-CN"),
        )
        // A seat limit on Free is the web's `general` offer.
        assertEquals(
            "https://raft.build/s/acme/settings/billing?upgrade=general&upgradeCanceled=1&lang=en",
            raftUpgradeWebUrl("https://raft.build", "acme", RaftUpgradeGate.SeatLimitInvite, RaftUpgradeMode.Upgrade, "en"),
        )
    }

    @Test
    fun addSeatsOpensPlanAndBilling() {
        assertEquals(
            "https://raft.build/s/acme/settings/billing?lang=ja",
            raftUpgradeWebUrl("https://raft.build", "acme", RaftUpgradeGate.SeatLimitAgentCreate, RaftUpgradeMode.AddSeats, "ja"),
        )
    }

    @Test
    fun comingBackEndsTheDialogOnceTheServerIsPro() {
        assertTrue(raftUpgradeDoneOnReturn(RaftUpgradeMode.Upgrade, "pro"))
        assertFalse(raftUpgradeDoneOnReturn(RaftUpgradeMode.Upgrade, "free"))
        assertFalse(raftUpgradeDoneOnReturn(RaftUpgradeMode.Upgrade, null))
        assertFalse(raftUpgradeDoneOnReturn(RaftUpgradeMode.AddSeats, "pro"))
    }

    @Test
    fun theLimitJustHitIsTheFirstBenefit() {
        assertEquals(RaftUpgradeBenefit.Files, raftUpgradeBenefits(raftUpgradeGateCopy(RaftUpgradeGate.UploadFileSize).first).first())
        assertEquals(RaftUpgradeBenefit.Joint, raftUpgradeBenefits(raftUpgradeGateCopy(RaftUpgradeGate.JointChannelReadOnly).first).first())
        assertEquals(RaftUpgradeBenefit.entries, raftUpgradeBenefits(raftUpgradeGateCopy(RaftUpgradeGate.General).first))
        assertEquals(4, raftUpgradeBenefits(RaftUpgradeBenefit.Migration).size)
    }

    @Test
    fun theExternalChannelGatesHaveTheirOwnCopyAndLeadWithExternalChannels() {
        // shared upgradeGates.ts UPGRADE_GATES; web upgradeGateCopy.ts GATE_COPY (upgradeDialog.externalLimit.*,
        // upgradeDialog.externalFrozen.*) and UpgradeDialog GATE_HIGHLIGHT `joint` (botiverse/slock #14533).
        val limit = RaftUpgradeGate.ExternalChannelFreeLimit
        assertEquals("external_channel_free_limit", limit.wire)
        assertEquals("external_channel_free_limit", limit.webDialogGate)
        assertFalse(limit.isSeatLimit)
        assertEquals(
            RaftUpgradeGateCopy(
                Res.string.upgrade_dialog_external_limit_title,
                Res.string.upgrade_dialog_external_limit_hint,
                Res.string.upgrade_dialog_limit_external_limit,
                RaftUpgradeBenefit.Joint,
            ),
            raftUpgradeGateCopy(limit),
        )
        val frozen = RaftUpgradeGate.ExternalChannelFrozen
        assertEquals("external_channel_frozen", frozen.wire)
        assertEquals("external_channel_frozen", frozen.webDialogGate)
        assertFalse(frozen.isSeatLimit)
        assertEquals(
            RaftUpgradeGateCopy(
                Res.string.upgrade_dialog_external_frozen_title,
                Res.string.upgrade_dialog_external_frozen_hint,
                Res.string.upgrade_dialog_limit_external_frozen,
                RaftUpgradeBenefit.Joint,
            ),
            raftUpgradeGateCopy(frozen),
        )
        listOf(limit, frozen).forEach { gate ->
            assertEquals(RaftUpgradeBenefit.Joint, raftUpgradeBenefits(raftUpgradeGateCopy(gate).first).first(), gate.wire)
        }
    }

    @Test
    fun legacyJointGatesUseExternalChannelCopy() {
        // botiverse/slock #15686: legacy joint_channel_* gates map to externalLimit / externalFrozen copy.
        val limitCopy = raftUpgradeGateCopy(RaftUpgradeGate.ExternalChannelFreeLimit)
        val frozenCopy = raftUpgradeGateCopy(RaftUpgradeGate.ExternalChannelFrozen)
        assertEquals(limitCopy, raftUpgradeGateCopy(RaftUpgradeGate.JointChannelFreeCap))
        assertEquals(limitCopy, raftUpgradeGateCopy(RaftUpgradeGate.JointChannelHostLimit))
        assertEquals(frozenCopy, raftUpgradeGateCopy(RaftUpgradeGate.JointChannelOverLimit))
        assertEquals(frozenCopy, raftUpgradeGateCopy(RaftUpgradeGate.JointChannelReadOnly))
    }

    @Test
    fun freezeUpcomingContextPairsOnlyWithExternalChannelFrozenAndEncodesInWebUrl() {
        val deadline = "2026-12-01T14:30:00Z"
        val context = RaftUpgradeExternalContext.FreezeUpcoming(deadline)
        assertEquals(context, externalContextForGate(RaftUpgradeGate.ExternalChannelFrozen, context))
        assertNull(externalContextForGate(RaftUpgradeGate.ExternalChannelFreeLimit, context))
        assertNull(externalContextForGate(RaftUpgradeGate.History30d, context))
        assertNull(externalContextForGate(RaftUpgradeGate.ExternalChannelFrozen, RaftUpgradeExternalContext.FreezeUpcoming("   ")))
        assertNull(externalContextForGate(RaftUpgradeGate.ExternalChannelFrozen, RaftUpgradeExternalContext.FreezeUpcoming("not-a-timestamp")))
        assertEquals(
            "https://raft.build/s/acme/settings/billing?upgrade=external_channel_frozen&upgradeCanceled=1&upgradeFreezeAt=2026-12-01T14%3A30%3A00Z&lang=en",
            raftUpgradeWebUrl(
                "https://raft.build",
                "acme",
                RaftUpgradeGate.ExternalChannelFrozen,
                RaftUpgradeMode.Upgrade,
                "en",
                context,
            ),
        )
        // Mismatched gate omits upgradeFreezeAt.
        assertEquals(
            "https://raft.build/s/acme/settings/billing?upgrade=external_channel_free_limit&upgradeCanceled=1&lang=en",
            raftUpgradeWebUrl(
                "https://raft.build",
                "acme",
                RaftUpgradeGate.ExternalChannelFreeLimit,
                RaftUpgradeMode.Upgrade,
                "en",
                context,
            ),
        )
    }
}
