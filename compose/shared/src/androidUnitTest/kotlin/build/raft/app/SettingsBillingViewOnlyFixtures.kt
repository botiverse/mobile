package build.raft.app

import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsBillingCurrentPlanSnapshot
import build.raft.app.data.SettingsBillingFileUploadQuotaSnapshot
import build.raft.app.data.SettingsBillingSeatUsageSnapshot
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsBillingSubscriptionSnapshot

/** 2026-11-02T00:00:00Z: the screenshots' "now". */
internal const val SettingsBillingViewOnlyFixtureNow = 1_793_577_600_000L

private val fixturePlan = SettingsBillingCurrentPlanSnapshot(planName = "", usageRows = emptyList())

/** A Free server: 37 MB of 100 MB uploaded in November, 3 people and 12 agents. */
internal val settingsBillingViewOnlyFreeFixture =
    ScreenLoadState.Content(
        SettingsBillingSnapshot(
            currentPlan = fixturePlan,
            plans = emptyList(),
            serverPlan = "free",
            summary =
                SettingsBillingSubscriptionSnapshot(
                    effectivePlan = "free",
                    source = "server",
                    stripeConfigured = true,
                    canManageBilling = true,
                    maxUniversalSeats = -1.0,
                    usage = SettingsBillingSeatUsageSnapshot(humans = 3, agents = 12, universalSeats = 4.2),
                    fileUploadQuota =
                        SettingsBillingFileUploadQuotaSnapshot(
                            month = "2026-11",
                            limited = true,
                            limitBytes = 100L * 1024 * 1024,
                            remainingBytes = 63L * 1024 * 1024,
                        ),
                ),
        )
    )

/** A yearly Pro subscription with 5 seats, 4.2 used, renewing Dec 1 2026; seen by an admin. */
internal val settingsBillingViewOnlyProFixture =
    ScreenLoadState.Content(
        SettingsBillingSnapshot(
            currentPlan = fixturePlan,
            plans = emptyList(),
            serverPlan = "pro",
            summary =
                SettingsBillingSubscriptionSnapshot(
                    status = "active",
                    billingInterval = "annual",
                    currentPeriodEnd = "2026-12-01T00:00:00.000Z",
                    effectivePlan = "pro",
                    source = "subscription",
                    stripeConfigured = true,
                    canManageBilling = false,
                    maxUniversalSeats = 5.0,
                    usage = SettingsBillingSeatUsageSnapshot(humans = 3, agents = 12, universalSeats = 4.2),
                    provisionedHumans = 4,
                    provisionedAgents = 10,
                    proPackQuantity = 5,
                    fileUploadQuota = SettingsBillingFileUploadQuotaSnapshot(month = "2026-11", limited = false, limitBytes = -1, remainingBytes = -1),
                ),
        )
    )
