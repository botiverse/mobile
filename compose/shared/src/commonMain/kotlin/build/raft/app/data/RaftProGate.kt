package build.raft.app.data

// Pro feature gating, web parity. botiverse/slock 7d349b509d6511eb922f7870099017fdda0d3f61:
//  - shared/src/index.ts `canUseProBillingFeatures(plan, now)`: pro, founder and
//    partner always; free only during the launch trial window (TRIAL_START_DATE ..
//    TRIAL_END_DATE).
//  - web components/agentMigration/billing.ts `agentMigrationRequiresUpgrade`: the
//    billing projection (`GET /billing/subscription` `plan`) is advisory; an
//    unknown or unreadable plan never invents a denial, the server stays the
//    authority and refuses with `MIGRATION_PRO_PLAN_REQUIRED`, which also opens
//    the upgrade prompt instead of an error.
//  - The upgrade prompt's action goes to Settings > Billing (web `nav.toSettings("billing")`).

internal enum class RaftServerPlan(val wireValue: String) {
    Free("free"),
    Founder("founder"),
    Partner("partner"),
    Pro("pro");

    companion object {
        fun fromWire(raw: String?): RaftServerPlan? = entries.firstOrNull { it.wireValue == raw }
    }
}

/** `TRIAL_START_DATE` 2026-04-18T00:00:00Z. */
internal const val RAFT_TRIAL_START_EPOCH_MS = 1_776_470_400_000L

/** `TRIAL_END_DATE` 2026-06-23T12:00:00Z. */
internal const val RAFT_TRIAL_END_EPOCH_MS = 1_782_216_000_000L

internal fun raftTrialActive(nowEpochMs: Long): Boolean =
    nowEpochMs in RAFT_TRIAL_START_EPOCH_MS until RAFT_TRIAL_END_EPOCH_MS

internal fun RaftServerPlan.canUseProFeatures(nowEpochMs: Long): Boolean =
    when (this) {
        RaftServerPlan.Pro, RaftServerPlan.Founder, RaftServerPlan.Partner -> true
        RaftServerPlan.Free -> raftTrialActive(nowEpochMs)
    }

/** What the Pro gate shows for one Pro feature entry. */
internal enum class RaftProGateDecision {
    /** The plan includes the feature, or it is unknown (the server decides). */
    Allowed,

    /** A known plan without the feature: tapping opens the upgrade sheet. */
    UpgradeRequired
}

internal fun raftProGateDecision(planWire: String?, nowEpochMs: Long): RaftProGateDecision {
    val plan = RaftServerPlan.fromWire(planWire) ?: return RaftProGateDecision.Allowed
    return if (plan.canUseProFeatures(nowEpochMs)) RaftProGateDecision.Allowed else RaftProGateDecision.UpgradeRequired
}

/** The advisory plan read for the gate; `plan` null when the server sent none. */
internal data class RaftBillingPlanSnapshot(val plan: String?)
