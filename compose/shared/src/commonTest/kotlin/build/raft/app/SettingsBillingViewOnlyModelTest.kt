package build.raft.app

import build.raft.app.data.SettingsBillingAppSumoSnapshot
import build.raft.app.data.SettingsBillingCurrentPlanSnapshot
import build.raft.app.data.SettingsBillingFileUploadQuotaSnapshot
import build.raft.app.data.SettingsBillingSeatUsageSnapshot
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsBillingSubscriptionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iOS view-only Plan & Billing page follows the web PlanSectionContent decisions. */
class SettingsBillingViewOnlyModelTest {
    private val now = 1_793_577_600_000L // 2026-11-02T00:00:00Z

    private fun snapshot(summary: SettingsBillingSubscriptionSnapshot?, serverPlan: String? = null, downgradedAt: String? = null) =
        SettingsBillingSnapshot(
            currentPlan = SettingsBillingCurrentPlanSnapshot(planName = "", usageRows = emptyList()),
            plans = emptyList(),
            summary = summary,
            serverPlan = serverPlan,
            planDowngradedAt = downgradedAt,
        )

    @Test
    fun freeShowsTheUploadMeterHistoryAndMembers() {
        val model =
            settingsBillingViewOnlyModel(
                snapshot(
                    SettingsBillingSubscriptionSnapshot(
                        effectivePlan = "free",
                        maxUniversalSeats = -1.0,
                        usage = SettingsBillingSeatUsageSnapshot(humans = 3, agents = 12, universalSeats = 4.2),
                        fileUploadQuota = SettingsBillingFileUploadQuotaSnapshot("2026-11", limited = true, limitBytes = 100, remainingBytes = 63),
                    )
                ),
                sessionPlan = null,
                nowEpochMillis = now,
            )

        assertEquals(SettingsBillingViewOnlyPlan.Free, model.plan)
        assertEquals(SettingsBillingViewOnlyDescription.FreeSummary, model.description)
        assertEquals(SettingsBillingViewOnlyUploads(usedBytes = 37, limitBytes = 100, usedUp = false, month = "2026-11"), model.uploads)
        assertEquals(30, model.historyDays)
        assertEquals(SettingsBillingViewOnlyMembers(people = 3, agents = 12), model.members)
        assertNull(model.seats)
        assertNull(model.details)
        assertFalse(model.loadFailed)
    }

    @Test
    fun proSubscriptionShowsSeatsAndBillingWithoutThePrice() {
        val model =
            settingsBillingViewOnlyModel(
                snapshot(
                    SettingsBillingSubscriptionSnapshot(
                        status = "past_due",
                        billingInterval = "annual",
                        currentPeriodEnd = "2026-12-01T00:00:00.000Z",
                        effectivePlan = "pro",
                        source = "subscription",
                        maxUniversalSeats = 5.0,
                        usage = SettingsBillingSeatUsageSnapshot(humans = 3, agents = 12, universalSeats = 4.2),
                        proPackQuantity = 5,
                    )
                ),
                sessionPlan = null,
                nowEpochMillis = now,
            )

        assertEquals(SettingsBillingViewOnlyDescription.ProSummary(seats = 5, ending = false, periodEnd = "2026-12-01T00:00:00.000Z"), model.description)
        assertEquals(SettingsBillingViewOnlyStatus.PaymentFailed, model.status)
        assertTrue(model.pastDue)
        assertNull(model.endingOn)
        assertEquals(SettingsBillingViewOnlySeats(used = 4.2, capacity = 5.0, humans = 3, agents = 12), model.seats)
        assertEquals(SettingsBillingViewOnlyDetails(annual = true, periodEnd = "2026-12-01T00:00:00.000Z", ending = false, seats = 5), model.details)
        assertNull(model.members)
    }

    @Test
    fun appSumoLicenseShowsItsAllowancesAndLifetimeStatus() {
        val model =
            settingsBillingViewOnlyModel(
                snapshot(
                    SettingsBillingSubscriptionSnapshot(
                        effectivePlan = "pro",
                        source = "appsumo",
                        appsumo = SettingsBillingAppSumoSnapshot(tier = 2, maxHumans = 10, maxAgents = 50, maxHostedJointChannels = 5, hostedJointChannels = 1),
                        usage = SettingsBillingSeatUsageSnapshot(humans = 4, agents = 9, universalSeats = 4.9),
                    )
                ),
                sessionPlan = null,
                nowEpochMillis = now,
            )

        assertEquals(SettingsBillingViewOnlyPlan.AppSumo, model.plan)
        assertEquals(2, model.appSumoTier)
        assertEquals(SettingsBillingViewOnlyStatus.Lifetime, model.status)
        assertEquals(SettingsBillingViewOnlyAppSumo(4, 10, 9, 50, 1, 5), model.appSumo)
        assertNull(model.details)
    }

    @Test
    fun missingBillingFallsBackToTheSessionPlanAndCountsTheGracePeriod() {
        val model = settingsBillingViewOnlyModel(snapshot(null, serverPlan = null, downgradedAt = "2026-10-30T12:00:00Z"), sessionPlan = "free", nowEpochMillis = now)

        assertTrue(model.loadFailed)
        assertEquals(SettingsBillingViewOnlyPlan.Free, model.plan)
        assertEquals(SettingsBillingViewOnlyDowngrade(expired = false, daysLeft = 5), model.downgrade)
        assertEquals(30, model.historyDays)
        assertNull(model.members)
    }

    @Test
    fun seatsShowAtMostOneDecimal() {
        assertEquals("4.2", settingsBillingFormatSeats(4.2))
        assertEquals("5", settingsBillingFormatSeats(5.0))
        assertEquals("1.2", settingsBillingFormatSeats(12 * 0.1))
    }
}
