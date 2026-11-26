package build.raft.app

import build.raft.app.data.ProPricing
import build.raft.app.data.SettingsBillingCurrentPlanSnapshot
import build.raft.app.data.SettingsBillingFileUploadQuotaSnapshot
import build.raft.app.data.SettingsBillingSeatUsageSnapshot
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsBillingSubscriptionSnapshot
import build.raft.app.data.parseSharedSettingsBillingSubscription
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

    /** Web SeatsBreakdown (botiverse/slock 16f5f8bc40, #16345): people, then agents under seat_v1; person_v2 omits agents. */
    @Test
    fun theSeatsBreakdownHasPeopleThenAgentsCountedInTenths() {
        val people = SettingsBillingSeatHolder.People
        val agents = SettingsBillingSeatHolder.Agents
        mapOf(
            (4 to 15) to listOf(SettingsBillingSeatsBreakdownRow(people, 4, 40), SettingsBillingSeatsBreakdownRow(agents, 15, 15)),
            (2 to 10) to listOf(SettingsBillingSeatsBreakdownRow(people, 2, 20), SettingsBillingSeatsBreakdownRow(agents, 10, 10)),
            (6 to 0) to listOf(SettingsBillingSeatsBreakdownRow(people, 6, 60)),
        ).forEach { (usage, rows) ->
            assertEquals(rows, settingsBillingSeatsBreakdown(humans = usage.first, agents = usage.second), "$usage")
        }
        // Under person_v2, agents take no seats and are omitted even when agents > 0.
        assertEquals(
            listOf(SettingsBillingSeatsBreakdownRow(people, 4, 40)),
            settingsBillingSeatsBreakdown(humans = 4, agents = 15, pricing = ProPricing.PersonV2),
        )
    }

    @Test
    fun proSubscriptionPropagatesServerResolvedPricingAndFallsBackOnOmission() {
        val personV2 =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","pricing":"person_v2","source":"subscription","stripeConfigured":true,"subscription":{"status":"active","billingInterval":"monthly"},"capacity":{"maxUniversalSeats":5},"usage":{"humans":3,"agents":20,"universalSeats":3},"provisioned":{"humans":3,"agents":20,"proPackQuantity":5}}"""
            )
        assertEquals(ProPricing.PersonV2, personV2?.pricing)
        val v2Model = settingsBillingViewOnlyModel(snapshot(personV2), sessionPlan = null, nowEpochMillis = now)
        assertEquals(ProPricing.PersonV2, v2Model.pricing)
        assertEquals(ProPricing.PersonV2, v2Model.seats?.pricing)

        val omitted =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"subscription","stripeConfigured":true,"subscription":{"status":"active","billingInterval":"monthly"},"capacity":{"maxUniversalSeats":5},"usage":{"humans":3,"agents":20,"universalSeats":5},"provisioned":{"humans":3,"agents":20,"proPackQuantity":5}}"""
            )
        assertNull(omitted?.pricing)
        val legacyModel = settingsBillingViewOnlyModel(snapshot(omitted), sessionPlan = null, nowEpochMillis = now)
        assertEquals(ProPricing.SeatV1, legacyModel.pricing)
        assertEquals(ProPricing.SeatV1, legacyModel.seats?.pricing)
    }

    @Test
    fun appSumoUnlimitedAgentsCarriesNegativeLimitAndMarksCountUnlimited() {
        val summary =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"appsumo","stripeConfigured":true,"appsumo":{"tier":1,"lifetime":true,"maxHumans":1,"maxAgents":-1,"maxHostedJointChannels":2,"hostedJointChannels":1,"monthlyUploadBytes":1073741824},"capacity":{"maxHumans":1,"maxAgents":-1,"maxUniversalSeats":-1},"usage":{"humans":1,"agents":11,"universalSeats":2.1},"fileUploadQuota":{"limited":true,"usedBytes":536870912,"remainingBytes":536870912,"limitBytes":1073741824}}"""
            )
        val model = settingsBillingViewOnlyModel(snapshot(summary), sessionPlan = null, nowEpochMillis = now)

        assertEquals(SettingsBillingViewOnlyPlan.AppSumo, model.plan)
        assertEquals(1, model.appSumoTier)
        assertEquals(SettingsBillingViewOnlyStatus.Lifetime, model.status)
        assertEquals(
            SettingsBillingViewOnlyAppSumo(
                humans = 1,
                maxHumans = 1,
                agents = 11,
                maxAgents = -1,
                jointChannels = 1,
                maxJointChannels = 2,
            ),
            model.appSumo,
        )
        assertTrue(settingsBillingCountIsUnlimited(-1))
        assertTrue(settingsBillingCountIsUnlimited(-5))
        assertFalse(settingsBillingCountIsUnlimited(0))
        assertFalse(settingsBillingCountIsUnlimited(1))
    }
}
