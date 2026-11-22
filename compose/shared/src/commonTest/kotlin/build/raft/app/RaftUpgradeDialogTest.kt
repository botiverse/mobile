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
    fun comingBackEndsTheDialogOnceTheServerIsPro() {
        assertTrue(raftUpgradeDoneOnReturn(RaftUpgradeMode.Upgrade, "pro"))
        assertFalse(raftUpgradeDoneOnReturn(RaftUpgradeMode.Upgrade, "free"))
        assertFalse(raftUpgradeDoneOnReturn(RaftUpgradeMode.Upgrade, null))
        assertFalse(raftUpgradeDoneOnReturn(RaftUpgradeMode.AddSeats, "pro"))
    }
}
