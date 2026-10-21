package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

// botiverse/slock AGENT_CONTROLS_SLOCK_SHA: shared/src/index.ts canUseProBillingFeatures +
// TRIAL_START_DATE/TRIAL_END_DATE; web components/agentMigration/billing.ts.
class RaftProGateTest {
    private val afterTrial = 1_792_540_800_000L // 2026-10-21T00:00:00Z
    private val duringTrial = 1_779_000_000_000L // 2026-05-17

    @Test
    fun freePlanNeedsAnUpgradeAndPaidPlansDoNot() {
        assertEquals(RaftProGateDecision.UpgradeRequired, raftProGateDecision("free", afterTrial))
        listOf("pro", "founder", "partner").forEach { plan ->
            assertEquals(RaftProGateDecision.Allowed, raftProGateDecision(plan, afterTrial), plan)
        }
    }

    @Test
    fun freePlanIsAllowedDuringTheLaunchTrialOnly() {
        assertEquals(RaftProGateDecision.Allowed, raftProGateDecision("free", duringTrial))
        assertEquals(RaftProGateDecision.Allowed, raftProGateDecision("free", RAFT_TRIAL_START_EPOCH_MS))
        assertEquals(RaftProGateDecision.UpgradeRequired, raftProGateDecision("free", RAFT_TRIAL_END_EPOCH_MS))
    }

    @Test
    fun unknownOrUnreadablePlanNeverInventsADenial() {
        assertEquals(RaftProGateDecision.Allowed, raftProGateDecision(null, afterTrial))
        assertEquals(RaftProGateDecision.Allowed, raftProGateDecision("enterprise", afterTrial))
    }

    @Test
    fun billingPlanIsReadFromTheSubscriptionSummary() {
        val body =
            """{"plan":"free","displayName":"Free","subscription":null,"stripeConfigured":true,""" +
                """"permissions":{"canReadBillingSummary":true,"canManageBilling":true}}"""
        assertEquals("free", parseSharedSettingsBillingSubscription(body)?.effectivePlan)
    }
}
