package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.JOINT_CHANNEL_FREE_LIMIT_REACHED
import build.raft.app.data.JOINT_FREE_SERVER_LIMIT
import build.raft.app.data.JointChannelErrorDetails
import build.raft.app.data.ScreenError
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
        assertEquals(RaftUpgradeGate.JointChannelHostLimit, raftUpgradeGateForErrorCode(JOINT_CHANNEL_FREE_LIMIT_REACHED))
        assertEquals(RaftUpgradeGate.JointChannelFreeCap, raftUpgradeGateForErrorCode(JOINT_FREE_SERVER_LIMIT))
        assertEquals(RaftUpgradeGate.AgentMigration, raftUpgradeGateForErrorCode("MIGRATION_PRO_PLAN_REQUIRED"))
        assertEquals(RaftUpgradeGate.SeatLimitAgentCreate, raftUpgradeGateForErrorCode("agent_limit_reached"))
        assertEquals(RaftUpgradeGate.SeatLimitInvite, raftUpgradeGateForErrorCode("human_seat_limit_reached"))
        // botiverse/slock@44ce160f1 routes/paywallHit.ts: `free_external_limit` is this gate.
        assertEquals(RaftUpgradeGate.ExternalChannelFreeLimit, raftUpgradeGateForErrorCode("free_external_limit"))
        assertNull(raftUpgradeGateForErrorCode("UPLOAD_RATE_LIMITED"))
        assertNull(raftUpgradeGateForErrorCode(null))
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
    fun jointCreateErrorsNameTheirGate() {
        fun state(code: String?, message: String? = null) =
            CreateJointChannelState(
                error =
                    CreateJointChannelError.Server(
                        DataResult.Error(ScreenError(code ?: "joint_http_400", serverMessage = message)),
                        JointChannelErrorDetails(code, null, null, null, null, null, false, null),
                    ),
            )
        assertEquals(RaftUpgradeGate.JointChannelFreeCap, state(JOINT_FREE_SERVER_LIMIT).billingGate)
        assertEquals(RaftUpgradeGate.JointChannelHostLimit, state(JOINT_CHANNEL_FREE_LIMIT_REACHED).billingGate)
        assertEquals(RaftUpgradeGate.JointChannelHostLimit, state(null, "Hosting more requires the Pro plan").billingGate)
        assertNull(state(null, "Channel name already taken").billingGate)
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
    fun theExternalChannelLimitIsTheWebsGateAndReadsAsGeneral() {
        // shared upgradeGates.ts UPGRADE_GATES; web upgradeGateCopy.ts GATE_COPY and UpgradeDialog
        // GATE_HIGHLIGHT map it to the general copy and the offer's own order (#14034).
        val gate = RaftUpgradeGate.ExternalChannelFreeLimit
        assertEquals("external_channel_free_limit", gate.wire)
        assertEquals("external_channel_free_limit", gate.webDialogGate)
        assertFalse(gate.isSeatLimit)
        assertEquals(raftUpgradeGateCopy(RaftUpgradeGate.General), raftUpgradeGateCopy(gate))
        assertEquals(RaftUpgradeBenefit.entries, raftUpgradeBenefits(raftUpgradeGateCopy(gate).first))
    }
}
