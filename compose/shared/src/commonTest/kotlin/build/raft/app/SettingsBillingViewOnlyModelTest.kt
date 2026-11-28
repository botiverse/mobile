package build.raft.app

import build.raft.app.data.ProPricing
import build.raft.app.data.SettingsBillingCurrentPlanSnapshot
import build.raft.app.data.SettingsBillingFileUploadQuotaSnapshot
import build.raft.app.data.SettingsBillingPrepaidSnapshot
import build.raft.app.data.SettingsBillingSeatUsageSnapshot
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsBillingSubscriptionSnapshot
import build.raft.app.data.parseSharedSettingsBillingSubscription
import build.raft.app.settings.SettingsBillingSeatHolder
import build.raft.app.settings.SettingsBillingSeatsBreakdownRow
import build.raft.app.settings.SettingsBillingViewOnlyAppSumo
import build.raft.app.settings.SettingsBillingViewOnlyDescription
import build.raft.app.settings.SettingsBillingViewOnlyDetails
import build.raft.app.settings.SettingsBillingViewOnlyMembers
import build.raft.app.settings.SettingsBillingViewOnlyPlan
import build.raft.app.settings.SettingsBillingViewOnlyPrepaidBanner
import build.raft.app.settings.SettingsBillingViewOnlySeats
import build.raft.app.settings.SettingsBillingViewOnlyStatus
import build.raft.app.settings.SettingsBillingViewOnlyUploads
import build.raft.app.settings.settingsBillingCountIsUnlimited
import build.raft.app.settings.settingsBillingSeatsBreakdown
import build.raft.app.settings.settingsBillingViewOnlyModel
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

    @Test
    fun parsesPrepaidEntitlementAndRejectsIncompleteOrNegativeSeatPayloads() {
        val valid =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"prepaid","stripeConfigured":true,"prepaid":{"endsAt":"2026-12-01T00:00:00.000Z","graceEndsAt":"2026-12-04T00:00:00.000Z","inGrace":false,"seatQuantity":6},"capacity":{"maxUniversalSeats":6},"usage":{"humans":4,"agents":10,"universalSeats":5.0}}"""
            )
        assertEquals(
            SettingsBillingPrepaidSnapshot(
                endsAt = "2026-12-01T00:00:00.000Z",
                graceEndsAt = "2026-12-04T00:00:00.000Z",
                inGrace = false,
                seatQuantity = 6,
            ),
            valid?.prepaid,
        )

        val zeroSeats =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"prepaid","stripeConfigured":true,"prepaid":{"endsAt":"2026-12-01T00:00:00.000Z","graceEndsAt":"2026-12-04T00:00:00.000Z","inGrace":true,"seatQuantity":0}}"""
            )
        assertEquals(0, zeroSeats?.prepaid?.seatQuantity)
        assertEquals(true, zeroSeats?.prepaid?.inGrace)

        val blankEndsAt =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"prepaid","stripeConfigured":true,"prepaid":{"endsAt":"  ","graceEndsAt":"2026-12-04T00:00:00.000Z","inGrace":false,"seatQuantity":5}}"""
            )
        assertNull(blankEndsAt?.prepaid)

        val blankGraceEndsAt =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"prepaid","stripeConfigured":true,"prepaid":{"endsAt":"2026-12-01T00:00:00.000Z","graceEndsAt":"","inGrace":false,"seatQuantity":5}}"""
            )
        assertNull(blankGraceEndsAt?.prepaid)

        val negativeSeats =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"prepaid","stripeConfigured":true,"prepaid":{"endsAt":"2026-12-01T00:00:00.000Z","graceEndsAt":"2026-12-04T00:00:00.000Z","inGrace":false,"seatQuantity":-1}}"""
            )
        assertNull(negativeSeats?.prepaid)

        val missingSeats =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"prepaid","stripeConfigured":true,"prepaid":{"endsAt":"2026-12-01T00:00:00.000Z","graceEndsAt":"2026-12-04T00:00:00.000Z","inGrace":false}}"""
            )
        assertNull(missingSeats?.prepaid)
    }

    @Test
    fun prepaidProActiveOverSevenDaysShowsSummaryAndSeatMeterWithoutBannerOrSubscriptionDetails() {
        // now = 2026-11-02T00:00:00Z, endsAt = 2026-11-20T00:00:00Z (18 days remaining -> > 7 days)
        val model =
            settingsBillingViewOnlyModel(
                snapshot(
                    SettingsBillingSubscriptionSnapshot(
                        effectivePlan = "pro",
                        source = "prepaid",
                        status = "active",
                        currentPeriodEnd = "2026-12-01T00:00:00.000Z",
                        prepaid =
                            SettingsBillingPrepaidSnapshot(
                                endsAt = "2026-11-20T00:00:00.000Z",
                                graceEndsAt = "2026-11-23T00:00:00.000Z",
                                inGrace = false,
                                seatQuantity = 8,
                            ),
                        maxUniversalSeats = 8.0,
                        usage = SettingsBillingSeatUsageSnapshot(humans = 4, agents = 15, universalSeats = 5.5),
                        pricing = ProPricing.SeatV1,
                    )
                ),
                sessionPlan = null,
                nowEpochMillis = now,
            )

        assertEquals(SettingsBillingViewOnlyPlan.Pro, model.plan)
        assertEquals(
            SettingsBillingViewOnlyDescription.PrepaidSummary(seats = 8, endsAt = "2026-11-20T00:00:00.000Z"),
            model.description,
        )
        assertNull(model.status)
        assertNull(model.prepaidBanner)
        assertNull(model.details)
        assertFalse(model.pastDue)
        assertNull(model.endingOn)
        assertEquals(
            SettingsBillingViewOnlySeats(used = 5.5, capacity = 8.0, humans = 4, agents = 15, pricing = ProPricing.SeatV1),
            model.seats,
        )
    }

    @Test
    fun prepaidProEndingWithinSevenDaysShowsEndsBadgeAndInfoBannerWithoutSubscriptionDetails() {
        // now = 2026-11-02T00:00:00Z, endsAt = 2026-11-09T00:00:00Z (exactly 7 days -> ending soon)
        val model =
            settingsBillingViewOnlyModel(
                snapshot(
                    SettingsBillingSubscriptionSnapshot(
                        effectivePlan = "pro",
                        source = "prepaid",
                        prepaid =
                            SettingsBillingPrepaidSnapshot(
                                endsAt = "2026-11-09T00:00:00.000Z",
                                graceEndsAt = "2026-11-12T00:00:00.000Z",
                                inGrace = false,
                                seatQuantity = 5,
                            ),
                        maxUniversalSeats = 5.0,
                        usage = SettingsBillingSeatUsageSnapshot(humans = 3, agents = 10, universalSeats = 4.0),
                    )
                ),
                sessionPlan = null,
                nowEpochMillis = now,
            )

        assertEquals(
            SettingsBillingViewOnlyDescription.PrepaidSummary(seats = 5, endsAt = "2026-11-09T00:00:00.000Z"),
            model.description,
        )
        assertEquals(SettingsBillingViewOnlyStatus.EndsOn("2026-11-09T00:00:00.000Z"), model.status)
        assertEquals(
            SettingsBillingViewOnlyPrepaidBanner(
                inGrace = false,
                endsAt = "2026-11-09T00:00:00.000Z",
                graceEndsAt = "2026-11-12T00:00:00.000Z",
            ),
            model.prepaidBanner,
        )
        assertNull(model.details)
        assertEquals(SettingsBillingViewOnlySeats(used = 4.0, capacity = 5.0, humans = 3, agents = 10), model.seats)
    }

    @Test
    fun prepaidProInGraceShowsPrepaidEndedBadgeAndWarningBannerWithGraceDate() {
        val model =
            settingsBillingViewOnlyModel(
                snapshot(
                    SettingsBillingSubscriptionSnapshot(
                        effectivePlan = "pro",
                        source = "prepaid",
                        prepaid =
                            SettingsBillingPrepaidSnapshot(
                                endsAt = "2026-11-01T00:00:00.000Z",
                                graceEndsAt = "2026-11-04T00:00:00.000Z",
                                inGrace = true,
                                seatQuantity = 4,
                            ),
                        maxUniversalSeats = 4.0,
                        usage = SettingsBillingSeatUsageSnapshot(humans = 2, agents = 5, universalSeats = 2.5),
                    )
                ),
                sessionPlan = null,
                nowEpochMillis = now,
            )

        assertEquals(
            SettingsBillingViewOnlyDescription.PrepaidSummary(seats = 4, endsAt = "2026-11-01T00:00:00.000Z"),
            model.description,
        )
        assertEquals(SettingsBillingViewOnlyStatus.PrepaidEnded("2026-11-01T00:00:00.000Z"), model.status)
        assertEquals(
            SettingsBillingViewOnlyPrepaidBanner(
                inGrace = true,
                endsAt = "2026-11-01T00:00:00.000Z",
                graceEndsAt = "2026-11-04T00:00:00.000Z",
            ),
            model.prepaidBanner,
        )
        assertNull(model.details)
        assertEquals(SettingsBillingViewOnlySeats(used = 2.5, capacity = 4.0, humans = 2, agents = 5), model.seats)
    }

    @Test
    fun appSumoRetainedStorageUsesUsedPlusReservedOmitsResetMonthAndAllowsOverLimit() {
        val mib = 1024L * 1024L
        val gib = 1024L * mib
        val withReserved =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"appsumo","stripeConfigured":true,"appsumo":{"tier":1,"lifetime":true,"maxHumans":1,"maxAgents":-1,"maxHostedJointChannels":2,"hostedJointChannels":1},"usage":{"humans":1,"agents":2,"universalSeats":1.2},"fileUploadQuota":{"month":"2026-11","limited":true,"usedBytes":${512L * mib},"reservedBytes":${128L * mib},"remainingBytes":${384L * mib},"limitBytes":$gib}}"""
            )
        val withReservedModel = settingsBillingViewOnlyModel(snapshot(withReserved), sessionPlan = null, nowEpochMillis = now)
        assertEquals(
            SettingsBillingViewOnlyUploads(
                usedBytes = 640L * mib,
                limitBytes = gib,
                usedUp = false,
                month = null,
                retainedStorage = true,
            ),
            withReservedModel.uploads,
        )

        // Tier downgrade can leave retained usage above the new limit: never clamp to limitBytes.
        val overLimit =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"appsumo","stripeConfigured":true,"appsumo":{"tier":1,"lifetime":true,"maxHumans":1,"maxAgents":-1,"maxHostedJointChannels":2,"hostedJointChannels":0},"usage":{"humans":1,"agents":0,"universalSeats":1.0},"fileUploadQuota":{"month":"2026-11","limited":true,"usedBytes":${4L * gib},"reservedBytes":0,"remainingBytes":0,"limitBytes":$gib}}"""
            )
        val overLimitModel = settingsBillingViewOnlyModel(snapshot(overLimit), sessionPlan = null, nowEpochMillis = now)
        assertEquals(
            SettingsBillingViewOnlyUploads(
                usedBytes = 4L * gib,
                limitBytes = gib,
                usedUp = true,
                month = null,
                retainedStorage = true,
            ),
            overLimitModel.uploads,
        )

        // Older server omitting usedBytes/reservedBytes falls back cleanly to limit - remaining.
        val olderServer =
            parseSharedSettingsBillingSubscription(
                """{"plan":"pro","source":"appsumo","stripeConfigured":true,"appsumo":{"tier":1,"lifetime":true,"maxHumans":1,"maxAgents":-1,"maxHostedJointChannels":2,"hostedJointChannels":0},"usage":{"humans":1,"agents":0,"universalSeats":1.0},"fileUploadQuota":{"month":"2026-11","limited":true,"remainingBytes":${512L * mib},"limitBytes":$gib}}"""
            )
        val olderServerModel = settingsBillingViewOnlyModel(snapshot(olderServer), sessionPlan = null, nowEpochMillis = now)
        assertEquals(
            SettingsBillingViewOnlyUploads(
                usedBytes = 512L * mib,
                limitBytes = gib,
                usedUp = false,
                month = null,
                retainedStorage = true,
            ),
            olderServerModel.uploads,
        )
    }
}
