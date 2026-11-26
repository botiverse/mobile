package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.base.Utils
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager
import build.raft.app.data.PRO_AGENT_SEAT_BLOCK_SIZE
import build.raft.app.data.ProPricing
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsBillingSubscriptionSnapshot
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.billingPricing
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.formatSeatTenths
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.data.seatTenths
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardFooterText
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftMeter
import build.raft.designsystem.components.RaftMeterLevel
import build.raft.designsystem.components.raftMeterLevel
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftSkeletonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * iOS Plan & Billing, view-only (artin 11-02, App Store guideline 3.1.1), laid out like the web
 * page (botiverse/slock v1.21.44 packages/web/src/components/settings/PlanSectionContent.tsx,
 * SettingsPanel.tsx BillingTabContent / PlanBillingLoadingSection): the problem banners at the
 * top, then one card per question (the plan; what is used; on Pro, how it is billed). Every
 * purchase entry is left out: Add Seats, Manage in Stripe, Keep Pro, Update Payment Method, the
 * "Upgrade to Pro" card with Compare Plans, Remove Unused Seats and the Cancel subscription card,
 * and the plan line carries no price. The data is `GET /billing/subscription` and
 * `GET /servers/{id}/usage` (SharedSettingsDestination.Billing), what the web page reads.
 */

/** Free's message history and monthly upload allowance (shared PLAN_CONFIG / FREE_MONTHLY_FILE_UPLOAD_LIMIT_BYTES). */
internal const val SettingsBillingFreeHistoryDays = 30
internal const val SettingsBillingFreeUploadBytes = 100L * 1024 * 1024

/** Shared DOWNGRADE_GRACE_PERIOD_DAYS. */
private const val SettingsBillingDowngradeGraceDays = 7
/** Prepaid Pro shows its end banner and badge this many days ahead (web `PREPAID_REMINDER_DAYS`). */
internal const val SettingsBillingPrepaidReminderDays = 7
private const val SettingsBillingDayMillis = 24L * 60 * 60 * 1000

/** Shared PRO_AGENT_SEAT_BLOCK_SIZE: ten agents take one seat under `seat_v1`, so an agent takes a tenth of one. */
internal const val SettingsBillingAgentSeatBlock = PRO_AGENT_SEAT_BLOCK_SIZE

internal enum class SettingsBillingViewOnlyPlan { Free, Pro, Founder, Partner, AppSumo, Other }

/** The plan card's line under the plan name. */
internal sealed interface SettingsBillingViewOnlyDescription {
    data object FreeSummary : SettingsBillingViewOnlyDescription

    /** Pro on a subscription: its seats and when it renews or ends (the web line without the price). */
    data class ProSummary(val seats: Int, val ending: Boolean, val periodEnd: String) : SettingsBillingViewOnlyDescription

    /** Prepaid Pro: its seats and end date, price-free and payment-method-free for iOS App Store 3.1.1. */
    data class PrepaidSummary(val seats: Int, val endsAt: String) : SettingsBillingViewOnlyDescription

    data class ProSeats(val seats: Int) : SettingsBillingViewOnlyDescription

    data object ProDefault : SettingsBillingViewOnlyDescription

    data object Founder : SettingsBillingViewOnlyDescription

    data object Partner : SettingsBillingViewOnlyDescription

    data object AppSumo : SettingsBillingViewOnlyDescription

    data object None : SettingsBillingViewOnlyDescription
}

internal sealed interface SettingsBillingViewOnlyStatus {
    data object Lifetime : SettingsBillingViewOnlyStatus

    data object PaymentFailed : SettingsBillingViewOnlyStatus

    data class EndsOn(val periodEnd: String) : SettingsBillingViewOnlyStatus

    /** Prepaid Pro in grace: paid period ended on [endsAt] (warning soft badge). */
    data class PrepaidEnded(val endsAt: String) : SettingsBillingViewOnlyStatus
}

internal data class SettingsBillingViewOnlyPrepaidBanner(
    val inGrace: Boolean,
    val endsAt: String,
    val graceEndsAt: String,
)

internal data class SettingsBillingViewOnlyDowngrade(val expired: Boolean, val daysLeft: Int)

internal data class SettingsBillingViewOnlySeats(
    val used: Double,
    val capacity: Double,
    val humans: Int,
    val agents: Int,
    /** Server-resolved Pro pricing (slock #16345, #16367); under `person_v2` agents take no seat. */
    val pricing: ProPricing = ProPricing.SeatV1,
)

internal data class SettingsBillingViewOnlyAppSumo(
    val humans: Int,
    val maxHumans: Int,
    val agents: Int,
    val maxAgents: Int,
    val jointChannels: Int,
    val maxJointChannels: Int,
)

internal data class SettingsBillingViewOnlyUploads(val usedBytes: Long, val limitBytes: Long, val usedUp: Boolean, val month: String?)

internal data class SettingsBillingViewOnlyMembers(val people: Int, val agents: Int)

internal data class SettingsBillingViewOnlyDetails(val annual: Boolean, val periodEnd: String?, val ending: Boolean, val seats: Int)

/** What the view-only page shows, worked out like the web PlanSectionContent. */
internal data class SettingsBillingViewOnlyModel(
    val plan: SettingsBillingViewOnlyPlan,
    val appSumoTier: Int = 0,
    val description: SettingsBillingViewOnlyDescription,
    val status: SettingsBillingViewOnlyStatus? = null,
    val loadFailed: Boolean = false,
    val downgrade: SettingsBillingViewOnlyDowngrade? = null,
    val pastDue: Boolean = false,
    val endingOn: String? = null,
    val prepaidBanner: SettingsBillingViewOnlyPrepaidBanner? = null,
    val seats: SettingsBillingViewOnlySeats? = null,
    val appSumo: SettingsBillingViewOnlyAppSumo? = null,
    val uploads: SettingsBillingViewOnlyUploads? = null,
    val historyDays: Int? = null,
    val members: SettingsBillingViewOnlyMembers? = null,
    val details: SettingsBillingViewOnlyDetails? = null,
    val owner: Boolean = false,
    val pricing: ProPricing = ProPricing.SeatV1,
)

internal fun settingsBillingViewOnlyModel(
    snapshot: SettingsBillingSnapshot?,
    sessionPlan: String?,
    nowEpochMillis: Long,
): SettingsBillingViewOnlyModel {
    val summary: SettingsBillingSubscriptionSnapshot? = snapshot?.summary
    val pricing = billingPricing(summary?.pricing)
    val planId = (summary?.effectivePlan ?: snapshot?.serverPlan ?: sessionPlan)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "free"
    val appsumo = summary?.appsumo
    val summaryPrepaid = summary?.prepaid
    val prepaid = if (summary?.source == "prepaid") summaryPrepaid else null
    val prepaidEndsAtMillis = prepaid?.endsAt?.let { parseRaftTimestampEpochMillisOrNull(it, 0) }
    val prepaidEndingSoon =
        prepaid != null &&
            !prepaid.inGrace &&
            prepaidEndsAtMillis != null &&
            prepaidEndsAtMillis - nowEpochMillis <= SettingsBillingPrepaidReminderDays * SettingsBillingDayMillis
    // web billingControls getBillingControlsState.
    val internal = planId == "founder" || planId == "partner" || summary?.source == "appsumo" || summary?.appsumoBillingReserved == true
    val entitling = !internal && prepaid == null && (summary?.status == "active" || summary?.status == "past_due")
    val isFree = planId == "free" && appsumo == null
    val isPro = planId == "pro" && appsumo == null
    val pastDue = entitling && summary?.status == "past_due"
    val ending = entitling && summary?.cancelAtPeriodEnd == true
    val periodEnd = if (entitling) summary?.currentPeriodEnd?.takeIf { it.isNotBlank() } else null
    val seats = max(0, summary?.proPackQuantity ?: 0)
    val plan =
        when {
            appsumo != null -> SettingsBillingViewOnlyPlan.AppSumo
            planId == "free" -> SettingsBillingViewOnlyPlan.Free
            planId == "pro" -> SettingsBillingViewOnlyPlan.Pro
            planId == "founder" -> SettingsBillingViewOnlyPlan.Founder
            planId == "partner" -> SettingsBillingViewOnlyPlan.Partner
            else -> SettingsBillingViewOnlyPlan.Other
        }
    val description =
        when {
            appsumo != null -> SettingsBillingViewOnlyDescription.AppSumo
            prepaid != null -> SettingsBillingViewOnlyDescription.PrepaidSummary(seats = prepaid.seatQuantity, endsAt = prepaid.endsAt)
            isPro && entitling && periodEnd != null -> SettingsBillingViewOnlyDescription.ProSummary(seats, ending, periodEnd)
            isFree -> SettingsBillingViewOnlyDescription.FreeSummary
            planId == "founder" -> SettingsBillingViewOnlyDescription.Founder
            planId == "partner" -> SettingsBillingViewOnlyDescription.Partner
            isPro && ((summary?.provisionedHumans ?: 0) > 0 || (summary?.provisionedAgents ?: 0) > 0) ->
                SettingsBillingViewOnlyDescription.ProSeats(seats)
            isPro -> SettingsBillingViewOnlyDescription.ProDefault
            else -> SettingsBillingViewOnlyDescription.None
        }
    val status =
        when {
            appsumo != null -> SettingsBillingViewOnlyStatus.Lifetime
            prepaid != null && prepaid.inGrace -> SettingsBillingViewOnlyStatus.PrepaidEnded(prepaid.endsAt)
            prepaid != null && prepaidEndingSoon -> SettingsBillingViewOnlyStatus.EndsOn(prepaid.endsAt)
            pastDue -> SettingsBillingViewOnlyStatus.PaymentFailed
            ending && periodEnd != null -> SettingsBillingViewOnlyStatus.EndsOn(periodEnd)
            else -> null
        }
    val prepaidBanner =
        if (prepaid != null && (prepaid.inGrace || prepaidEndingSoon)) {
            SettingsBillingViewOnlyPrepaidBanner(
                inGrace = prepaid.inGrace,
                endsAt = prepaid.endsAt,
                graceEndsAt = prepaid.graceEndsAt,
            )
        } else {
            null
        }
    val downgrade =
        snapshot?.planDowngradedAt?.let(::parseRaftTimestampEpochMillisOrNull)?.let { downgradedAt ->
            val graceEnd = downgradedAt + SettingsBillingDowngradeGraceDays * SettingsBillingDayMillis
            val expired = nowEpochMillis >= graceEnd
            val daysLeft = if (expired) 0 else ceil((graceEnd - nowEpochMillis).toDouble() / SettingsBillingDayMillis).toInt()
            SettingsBillingViewOnlyDowngrade(expired = expired, daysLeft = daysLeft)
        }
    val usage = summary?.usage
    val maxSeats = summary?.maxUniversalSeats ?: -1.0
    val seatMeter =
        if (isPro && maxSeats != -1.0) {
            SettingsBillingViewOnlySeats(
                used = max(0.0, usage?.universalSeats ?: 0.0),
                capacity = max(0.0, maxSeats),
                humans = usage?.humans ?: 0,
                agents = usage?.agents ?: 0,
                pricing = pricing,
            )
        } else {
            null
        }
    val quota = summary?.fileUploadQuota?.takeIf { it.limited && it.limitBytes > 0 }
    val uploads =
        if ((isFree || appsumo != null) && quota != null) {
            SettingsBillingViewOnlyUploads(
                usedBytes = min(quota.limitBytes, max(0L, quota.limitBytes - max(0L, quota.remainingBytes))),
                limitBytes = quota.limitBytes,
                usedUp = quota.remainingBytes <= 0,
                month = quota.month?.takeIf { settingsBillingYearMonth(it) != null },
            )
        } else {
            null
        }
    val plainUsage = seatMeter == null && appsumo == null
    return SettingsBillingViewOnlyModel(
        plan = plan,
        appSumoTier = appsumo?.tier ?: 0,
        description = description,
        status = status,
        loadFailed = summary == null,
        downgrade = downgrade,
        pastDue = isPro && pastDue,
        endingOn = periodEnd?.takeIf { isPro && ending && !pastDue },
        prepaidBanner = prepaidBanner,
        seats = seatMeter,
        appSumo =
            appsumo?.let {
                SettingsBillingViewOnlyAppSumo(
                    humans = usage?.humans ?: 0,
                    maxHumans = it.maxHumans,
                    agents = usage?.agents ?: 0,
                    maxAgents = it.maxAgents,
                    jointChannels = it.hostedJointChannels,
                    maxJointChannels = it.maxHostedJointChannels,
                )
            },
        uploads = uploads,
        historyDays = SettingsBillingFreeHistoryDays.takeIf { plainUsage && planId == "free" },
        members = usage?.takeIf { plainUsage }?.let { SettingsBillingViewOnlyMembers(people = it.humans, agents = it.agents) },
        details =
            if (isPro && entitling) {
                SettingsBillingViewOnlyDetails(
                    annual = (summary?.billingInterval ?: summary?.priceBillingInterval) == "annual",
                    periodEnd = periodEnd,
                    ending = ending,
                    seats = seats,
                )
            } else {
                null
            },
        owner = summary?.canManageBilling == true,
        pricing = pricing,
    )
}

/** `YYYY-MM` as (year, month), or null. */
internal fun settingsBillingYearMonth(month: String): Pair<Int, Int>? {
    val parts = month.split('-')
    if (parts.size != 2 || parts[0].length != 4 || parts[1].length != 2) return null
    val year = parts[0].toIntOrNull() ?: return null
    val monthNumber = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    return year to monthNumber
}

/** Seats with at most one decimal (web `formatNumber(…, { maximumFractionDigits: 1 })`). */
internal fun settingsBillingFormatSeats(value: Double): String = settingsBillingFormatSeatTenths((value * 10).roundToLong())

/** Seats counted in tenths, with at most one decimal: 15 is "1.5", 40 is "4". */
internal fun settingsBillingFormatSeatTenths(tenths: Long): String = formatSeatTenths(tenths)

/**
 * Shared `seatTenths`: seats in tenths, as an integer (a person is 10; an agent is 1 under
 * `seat_v1`, 0 under `person_v2`). Seat math counts these, never the 0.1 share itself, which
 * drifts in floating point.
 */
internal fun settingsBillingSeatTenths(humans: Int, agents: Int, pricing: ProPricing = ProPricing.SeatV1): Long =
    seatTenths(humans, agents, pricing)

/** Who takes the seats of a [SettingsBillingSeatsBreakdownRow]. */
internal enum class SettingsBillingSeatHolder { People, Agents }

/** One row of the seats breakdown: [count] people or agents and the seats they take, in tenths. */
internal data class SettingsBillingSeatsBreakdownRow(val holder: SettingsBillingSeatHolder, val count: Int, val seatTenths: Long)

/**
 * What a server's seats in use are made of, one row per kind (web SeatsBreakdown, botiverse/slock
 * #15505, #16345, #16367 `packages/web/src/components/billing/SeatsBreakdown.tsx`): the people,
 * then the agents under `seat_v1`, with no agents row when there are none or under `person_v2`
 * (where agents take no seat). Agent seats are counted in tenths (`seatTenths(0, agents, pricing)`).
 */
internal fun settingsBillingSeatsBreakdown(
    humans: Int,
    agents: Int,
    pricing: ProPricing = ProPricing.SeatV1,
): List<SettingsBillingSeatsBreakdownRow> =
    buildList {
        add(SettingsBillingSeatsBreakdownRow(SettingsBillingSeatHolder.People, humans, seatTenths(humans, 0, pricing)))
        if (agents > 0 && pricing.agentsTakeSeats) {
            add(SettingsBillingSeatsBreakdownRow(SettingsBillingSeatHolder.Agents, agents, seatTenths(0, agents, pricing)))
        }
    }

/** The row's label, who takes the seats: "4 people", "15 agents" (web `planBilling.usage.peopleCount` / `agentsCount`). */
internal fun SettingsBillingSeatsBreakdownRow.termDesc(): StringDesc =
    when (holder) {
        SettingsBillingSeatHolder.People ->
            (if (count == 1) Res.string.settings_billing_vo_usage_people_count_one else Res.string.settings_billing_vo_usage_people_count_other)
                .format(count)
        SettingsBillingSeatHolder.Agents ->
            (if (count == 1) Res.string.settings_billing_vo_usage_agents_count_one else Res.string.settings_billing_vo_usage_agents_count_other)
                .format(count)
    }

/**
 * The seats the row takes: "4 seats"; for agents with the rule beside them, "1.5 seats (1 per 10
 * agents)" (web `planBilling.usage.seatsValue` / `agentSeatsValue`). Exactly one seat is singular
 * ("1 seat (1 per 10 agents)" for ten agents); any other number, a fraction too, plural. Russian's
 * few / many come from files/plurals/ru.json, where a fraction keeps the `other` form.
 */
internal fun SettingsBillingSeatsBreakdownRow.valueDesc(): StringDesc {
    val oneSeat = seatTenths == 10L
    val seats = settingsBillingFormatSeatTenths(seatTenths)
    return when (holder) {
        SettingsBillingSeatHolder.People ->
            (if (oneSeat) Res.string.settings_billing_vo_usage_seats_value_one else Res.string.settings_billing_vo_usage_seats_value_other)
                .format(seats)
        SettingsBillingSeatHolder.Agents ->
            (if (oneSeat) Res.string.settings_billing_vo_usage_agent_seats_value_one else Res.string.settings_billing_vo_usage_agent_seats_value_other)
                .format(seats, SettingsBillingAgentSeatBlock)
    }
}

@Composable
private fun settingsBillingFormatBytes(bytes: Long): String {
    val mib = 1024.0 * 1024.0
    val gib = mib * 1024.0
    return if (bytes >= gib) {
        stringResource(Res.string.settings_billing_vo_size_gb, settingsBillingFormatSeats(bytes / gib))
    } else {
        stringResource(Res.string.settings_billing_vo_size_mb, (bytes / mib).roundToLong().toString())
    }
}

/** Local calendar day of an ISO billing timestamp in [timeContext], or null if unreadable. */
internal fun settingsBillingLocalDate(iso: String, timeContext: RaftTimeContext): MembersJoinedDate? {
    val epochMillis = parseRaftTimestampEpochMillisOrNull(iso, timeContext.localOffsetMinutes) ?: return null
    val date = civilDateFromEpochDay(timeContext.localEpochDay(epochMillis))
    return MembersJoinedDate(date.year, date.month, date.day)
}

/** The local calendar day of an ISO timestamp ("Dec 1, 2026"; 2026年12月1日). */
@Composable
private fun settingsBillingDate(iso: String): String {
    val context = currentRaftEffectiveDateTimeContext()
    val localDate = settingsBillingLocalDate(iso, context)
    return if (localDate != null) membersLocalizedDate(localDate) else iso
}

/** When a `YYYY-MM` upload month starts over: the first of the next month ("Dec 1"). */
@Composable
private fun settingsBillingResetDay(month: String): String? {
    val (_, monthNumber) = settingsBillingYearMonth(month) ?: return null
    val next = if (monthNumber == 12) 1 else monthNumber + 1
    return stringResource(
        Res.string.settings_billing_vo_month_day,
        membersJoinedMonthLabel(stringResource(Res.string.member_joined_date_months), next),
        "1",
    )
}

@Composable
private fun settingsBillingSeatCount(count: Int): String =
    stringResource(if (count == 1) Res.string.settings_billing_vo_seat_count_one else Res.string.settings_billing_vo_seat_count_other, count)

@Composable
internal fun SettingsBillingViewOnlyPage(pager: Pager) {
    val wideDetail = LocalRaftSettingsWideDetail.current
    val wideDetailClose = LocalRaftSettingsWideDetailClose.current
    fun close() = settingsPageClose(wideDetailClose) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    RaftBackHandler(enabled = !wideDetail, label = "settings_billing_view_only", blocksBackSwipe = false, onBack = ::close)
    val bridge = Utils.bridgeModule(pager.pagerId)
    val environment = rememberStandaloneSettingsEnvironment(pager, bridge)
    val settingsViewModel: SharedSettingsViewModel =
        viewModel(key = "standalone-settings-billing-view-only") { SharedSettingsViewModel(environment.dataSource) }
    val settingsState by settingsViewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        bridge.log("settings_billing_view_only_open")
        settingsViewModel.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Billing))
    }
    StandaloneSettingsEffectHandler(pager, environment, settingsState, settingsViewModel::dispatch)
    // The session's plan: what the page falls back to when billing could not be loaded.
    val sessionPlan by produceState<String?>(null, environment.authStore) {
        value = runCatchingPreservingCancellation { environment.authStore.readSession() }.getOrNull()?.currentServerSummary()?.plan
    }
    Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        RaftTopPageHeader(
            title = stringResource(Res.string.settings_billing_title),
            iconName = RaftLucideIconName.CreditCard,
            backgroundColor = RaftColor.SurfaceWhite,
            applyTopSafeArea = !wideDetail,
            onBack = if (wideDetail) null else ::close,
        )
        SettingsBillingViewOnlyContent(
            billing = settingsState.billing,
            sessionPlan = sessionPlan,
            onRetry = { settingsViewModel.dispatch(SharedSettingsIntent.LoadPageContent(SharedSettingsDestination.Billing)) },
        )
    }
}

@Composable
internal fun SettingsBillingViewOnlyContent(
    billing: ScreenLoadState<SettingsBillingSnapshot>,
    sessionPlan: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMillis: Long? = null,
) {
    val resolvedNowEpochMillis =
        if (nowEpochMillis != null) {
            nowEpochMillis
        } else {
            val liveNow = currentRaftTimeContext(null)?.nowEpochMillis
            if (liveNow != null) liveNow else remember { defaultNowMillis() }
        }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (billing) {
            ScreenLoadState.Loading -> SettingsBillingViewOnlyLoading()
            // web BillingTabContent without `viewBilling`.
            is ScreenLoadState.NoPermission -> {
                RaftSectionEyebrow(stringResource(Res.string.settings_billing_vo_billing_title))
                RaftCard(modifier = Modifier.fillMaxWidth()) {
                    RaftText(
                        stringResource(Res.string.settings_billing_vo_no_permission),
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foregroundMuted,
                    )
                }
            }
            is ScreenLoadState.Content ->
                SettingsBillingViewOnlyOverview(settingsBillingViewOnlyModel(billing.data, sessionPlan, resolvedNowEpochMillis), onRetry)
            is ScreenLoadState.Empty,
            is ScreenLoadState.Error ->
                SettingsBillingViewOnlyOverview(settingsBillingViewOnlyModel(null, sessionPlan, resolvedNowEpochMillis), onRetry)
        }
    }
}

@Composable
private fun SettingsBillingViewOnlyOverview(model: SettingsBillingViewOnlyModel, onRetry: () -> Unit) {
    val freeUpload = settingsBillingFormatBytes(SettingsBillingFreeUploadBytes)
    val freePerFile = settingsBillingFormatBytes(RaftFreeSingleFileUploadBytes)
    if (model.loadFailed) {
        RaftBanner(
            description = stringResource(Res.string.settings_billing_vo_load_failed_body),
            status = RaftBannerStatus.Warning,
            title = stringResource(Res.string.settings_billing_vo_load_failed_title),
            showIcon = false,
            action = {
                RaftButton(
                    text = stringResource(Res.string.settings_billing_vo_retry),
                    onClick = onRetry,
                    variant = RaftButtonVariant.Default,
                    size = RaftButtonSize.Md,
                )
            },
        )
    }
    model.downgrade?.let { downgrade ->
        RaftBanner(
            description =
                if (downgrade.expired) {
                    stringResource(Res.string.settings_billing_vo_grace_expired_body)
                } else {
                    stringResource(
                        if (downgrade.daysLeft == 1) Res.string.settings_billing_vo_grace_remaining_one else Res.string.settings_billing_vo_grace_remaining_other,
                        downgrade.daysLeft,
                    )
                },
            status = RaftBannerStatus.Warning,
            title =
                stringResource(
                    if (downgrade.expired) Res.string.settings_billing_vo_grace_expired_title else Res.string.settings_billing_vo_downgraded_title
                ),
        )
    }
    if (model.pastDue) {
        RaftBanner(
            description = stringResource(Res.string.settings_billing_vo_past_due_body),
            status = RaftBannerStatus.Warning,
            title = stringResource(Res.string.settings_billing_vo_payment_failed),
        )
    }
    model.endingOn?.let { periodEnd ->
        RaftBanner(
            description = stringResource(Res.string.settings_billing_vo_ending_body, SettingsBillingFreeHistoryDays, freeUpload, freePerFile),
            status = RaftBannerStatus.Info,
            title = stringResource(Res.string.settings_billing_vo_ending_title, settingsBillingDate(periodEnd)),
        )
    }
    model.prepaidBanner?.let { banner ->
        RaftBanner(
            description =
                if (banner.inGrace) {
                    stringResource(Res.string.settings_billing_vo_prepaid_grace_body, settingsBillingDate(banner.graceEndsAt))
                } else {
                    stringResource(Res.string.settings_billing_vo_prepaid_ending_body)
                },
            status = if (banner.inGrace) RaftBannerStatus.Warning else RaftBannerStatus.Info,
            title =
                stringResource(
                    if (banner.inGrace) Res.string.settings_billing_vo_prepaid_grace_title else Res.string.settings_billing_vo_prepaid_ending_title,
                    settingsBillingDate(banner.endsAt),
                ),
        )
    }

    // The plan
    val status = model.status
    val statusBadge: (@Composable () -> Unit)? = if (status != null) ({ SettingsBillingStatusBadge(status) }) else null
    RaftCard(
        modifier = Modifier.fillMaxWidth(),
        title = settingsBillingPlanName(model),
        description = settingsBillingPlanDescription(model.description, freeUpload, freePerFile),
        trailing = statusBadge,
    )

    // What is used
    SettingsBillingUsageCard(model)

    // Pro: how it is billed
    model.details?.let { details ->
        RaftCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(Res.string.settings_billing_vo_billing_title),
            footer =
                if (model.owner) {
                    null
                } else {
                    { RaftCardFooterText(stringResource(Res.string.settings_billing_vo_member_note)) }
                },
        ) {
            RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                Item(
                    term = stringResource(Res.string.settings_billing_vo_billed),
                    value = stringResource(if (details.annual) Res.string.settings_billing_vo_yearly else Res.string.settings_billing_vo_monthly),
                    copyable = false,
                )
                details.periodEnd?.let { periodEnd ->
                    Item(
                        term = stringResource(if (details.ending) Res.string.settings_billing_vo_last_day else Res.string.settings_billing_vo_renews),
                        value = settingsBillingDate(periodEnd),
                        copyable = false,
                    )
                }
                Item(term = stringResource(Res.string.settings_billing_vo_seats), value = settingsBillingSeatCount(details.seats), copyable = false)
            }
        }
    }
}

@Composable
private fun settingsBillingPlanName(model: SettingsBillingViewOnlyModel): String =
    when (model.plan) {
        SettingsBillingViewOnlyPlan.AppSumo -> stringResource(Res.string.settings_billing_vo_plan_appsumo, model.appSumoTier)
        SettingsBillingViewOnlyPlan.Free -> stringResource(Res.string.settings_billing_vo_plan_free)
        SettingsBillingViewOnlyPlan.Pro -> stringResource(Res.string.settings_billing_vo_plan_pro)
        SettingsBillingViewOnlyPlan.Founder -> stringResource(Res.string.settings_billing_vo_plan_founder)
        SettingsBillingViewOnlyPlan.Partner -> stringResource(Res.string.settings_billing_vo_plan_partner)
        // A plan this app has no name for: Free's, as the web presentation falls back.
        SettingsBillingViewOnlyPlan.Other -> stringResource(Res.string.settings_billing_vo_plan_free)
    }

@Composable
private fun settingsBillingPlanDescription(
    description: SettingsBillingViewOnlyDescription,
    freeUpload: String,
    freePerFile: String,
): String? =
    when (description) {
        SettingsBillingViewOnlyDescription.FreeSummary ->
            stringResource(Res.string.settings_billing_vo_free_summary, SettingsBillingFreeHistoryDays, freeUpload, freePerFile)
        is SettingsBillingViewOnlyDescription.ProSummary ->
            stringResource(
                Res.string.settings_billing_vo_summary_join,
                settingsBillingSeatCount(description.seats),
                stringResource(
                    if (description.ending) Res.string.settings_billing_vo_ends_on else Res.string.settings_billing_vo_renews_on,
                    settingsBillingDate(description.periodEnd),
                ),
            )
        is SettingsBillingViewOnlyDescription.PrepaidSummary ->
            stringResource(
                Res.string.settings_billing_vo_summary_join,
                settingsBillingSeatCount(description.seats),
                stringResource(Res.string.settings_billing_vo_prepaid_until, settingsBillingDate(description.endsAt)),
            )
        is SettingsBillingViewOnlyDescription.ProSeats -> settingsBillingSeatCount(description.seats)
        SettingsBillingViewOnlyDescription.ProDefault -> stringResource(Res.string.settings_billing_vo_pro_description)
        SettingsBillingViewOnlyDescription.Founder -> stringResource(Res.string.settings_billing_vo_founder_description)
        SettingsBillingViewOnlyDescription.Partner -> stringResource(Res.string.settings_billing_vo_partner_description)
        SettingsBillingViewOnlyDescription.AppSumo -> stringResource(Res.string.settings_billing_vo_appsumo_description)
        SettingsBillingViewOnlyDescription.None -> null
    }

@Composable
private fun SettingsBillingStatusBadge(status: SettingsBillingViewOnlyStatus) {
    when (status) {
        SettingsBillingViewOnlyStatus.Lifetime ->
            RaftBadge(stringResource(Res.string.settings_billing_vo_status_lifetime), appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Success)
        SettingsBillingViewOnlyStatus.PaymentFailed ->
            RaftBadge(stringResource(Res.string.settings_billing_vo_payment_failed), appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Warning)
        is SettingsBillingViewOnlyStatus.EndsOn ->
            RaftBadge(
                stringResource(Res.string.settings_billing_vo_ends_on, settingsBillingDate(status.periodEnd)),
                appearance = RaftBadgeAppearance.Soft,
                variant = RaftBadgeVariant.Muted,
            )
        is SettingsBillingViewOnlyStatus.PrepaidEnded ->
            RaftBadge(
                stringResource(Res.string.settings_billing_vo_prepaid_ended, settingsBillingDate(status.endsAt)),
                appearance = RaftBadgeAppearance.Soft,
                variant = RaftBadgeVariant.Warning,
            )
    }
}

@Composable
private fun SettingsBillingUsageCard(model: SettingsBillingViewOnlyModel) {
    val hasRows = model.seats != null || model.appSumo != null || model.uploads != null || model.historyDays != null || model.members != null
    RaftCard(modifier = Modifier.fillMaxWidth(), title = stringResource(Res.string.settings_billing_vo_usage_title)) {
        if (!hasRows) return@RaftCard
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            model.seats?.let { seats ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftMeter(
                        used = seats.used.toFloat(),
                        max = max(seats.capacity, 0.0001).toFloat(),
                        label = stringResource(Res.string.settings_billing_vo_seats),
                        valueText =
                            stringResource(
                                Res.string.settings_billing_vo_usage_used,
                                settingsBillingFormatSeats(seats.used),
                                settingsBillingFormatSeats(seats.capacity),
                            ),
                    )
                    // Under "n of m used": what the seats in use are made of.
                    SettingsBillingSeatsBreakdown(seats.humans, seats.agents, seats.pricing)
                }
            }
            model.appSumo?.let { appSumo ->
                SettingsBillingCountMeter(stringResource(Res.string.settings_billing_vo_people), appSumo.humans, appSumo.maxHumans)
                SettingsBillingCountMeter(stringResource(Res.string.settings_billing_vo_agents), appSumo.agents, appSumo.maxAgents)
                SettingsBillingCountMeter(
                    stringResource(Res.string.settings_billing_vo_joint_channels),
                    appSumo.jointChannels,
                    appSumo.maxJointChannels,
                )
            }
            model.uploads?.let { uploads ->
                val used = settingsBillingFormatBytes(uploads.usedBytes)
                val limit = settingsBillingFormatBytes(uploads.limitBytes)
                val resets = uploads.month?.let { settingsBillingResetDay(it) }
                val valueText =
                    when {
                        resets == null -> stringResource(Res.string.settings_billing_vo_usage_used, used, limit)
                        uploads.usedUp -> stringResource(Res.string.settings_billing_vo_uploads_used_up, resets)
                        else -> stringResource(Res.string.settings_billing_vo_uploads_used, used, limit, resets)
                    }
                // The label and this long value don't fit one phone line (the label was cut off):
                // the value goes under the bar, in the meter's value type.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RaftMeter(
                        used = uploads.usedBytes.toFloat(),
                        max = uploads.limitBytes.toFloat(),
                        label = stringResource(Res.string.settings_billing_vo_uploads),
                        valueText = valueText,
                        showValue = false,
                    )
                    RaftText(
                        valueText,
                        style = RaftTheme.typography.footnote,
                        // The meter's own value colour (raftMeterLevel): warning from its warn share and when used up.
                        color =
                            if (raftMeterLevel(uploads.usedBytes.toFloat(), uploads.limitBytes.toFloat()) == RaftMeterLevel.Normal) {
                                RaftTheme.colors.foregroundMuted
                            } else {
                                RaftTheme.colors.warningStrong
                            },
                    )
                    // What counts, Artifacts too (botiverse/slock #15529, planBilling.usage.uploadsIncludes).
                    RaftText(
                        stringResource(Res.string.settings_billing_vo_uploads_includes),
                        style = RaftTheme.typography.footnote,
                        color = RaftTheme.colors.foregroundMuted,
                    )
                }
            }
            if (model.historyDays != null || model.members != null) {
                RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                    model.historyDays?.let { days ->
                        Item(
                            term = stringResource(Res.string.settings_billing_vo_history),
                            value = stringResource(Res.string.settings_billing_vo_history_value, days),
                            copyable = false,
                        )
                    }
                    model.members?.let { members ->
                        Item(
                            term = stringResource(Res.string.settings_billing_vo_members),
                            value =
                                stringResource(
                                    Res.string.settings_billing_vo_summary_join,
                                    stringResource(
                                        if (members.people == 1) Res.string.settings_billing_vo_people_count_one else Res.string.settings_billing_vo_people_count_other,
                                        members.people,
                                    ),
                                    stringResource(
                                        if (members.agents == 1) Res.string.settings_billing_vo_agent_count_one else Res.string.settings_billing_vo_agent_count_other,
                                        members.agents,
                                    ),
                                ),
                            copyable = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * web SeatsBreakdown (botiverse/slock #15505, #16345, #16367; cindyz option 2): what the seats in
 * use are made of, in two columns, one row per kind, label then seats: "4 people | 4 seats", and
 * under `seat_v1` "15 agents | 1.5 seats (1 per 10 agents)". Under `person_v2`, agents take no seat
 * and the agents row is omitted ([settingsBillingSeatsBreakdown]).
 */
@Composable
private fun SettingsBillingSeatsBreakdown(humans: Int, agents: Int, pricing: ProPricing) {
    RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
        settingsBillingSeatsBreakdown(humans, agents, pricing).forEach { row ->
            Item(term = row.termDesc().localized(), value = row.valueDesc().localized(), copyable = false)
        }
    }
}

/** Whether an AppSumo allowance cap represents unlimited usage (`-1` on the wire, botiverse/slock #16801). */
internal fun settingsBillingCountIsUnlimited(limit: Int): Boolean = limit < 0

/**
 * Localized usage copy for one AppSumo allowance (web `UsageMeter`, botiverse/slock #16801):
 * unlimited (`"11 used · Unlimited"`) when [limit] < 0, else finite (`"1 of 1 used"`).
 */
internal fun settingsBillingCountUsageDesc(used: Int, limit: Int): StringDesc =
    if (settingsBillingCountIsUnlimited(limit)) {
        Res.string.settings_billing_vo_appsumo_unlimited_usage.format(used)
    } else {
        Res.string.settings_billing_vo_usage_used.format(used.toString(), limit.toString())
    }

/**
 * One AppSumo allowance: what is used of the license's limit (web `UsageMeter`, botiverse/slock
 * #16801). A negative limit (`-1`) is unlimited and renders the localized unlimited usage line
 * instead of a finite ratio bar.
 */
@Composable
private fun SettingsBillingCountMeter(label: String, used: Int, limit: Int) {
    if (settingsBillingCountIsUnlimited(limit)) {
        RaftDescriptionList {
            Item(
                term = label,
                value = settingsBillingCountUsageDesc(used, limit).localized(),
                copyable = false,
            )
        }
        return
    }
    RaftMeter(
        used = used.toFloat(),
        max = max(limit.toFloat(), 0.0001f),
        label = label,
        valueText = settingsBillingCountUsageDesc(used, limit).localized(),
    )
}

/** web PlanBillingLoadingSection, the cards only: placeholders where the plan and its usage go. */
@Composable
private fun SettingsBillingViewOnlyLoading() {
    RaftCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RaftSkeleton(Modifier.fillMaxWidth(0.3f), variant = RaftSkeletonVariant.Line)
            RaftSkeleton(Modifier.fillMaxWidth(0.7f), variant = RaftSkeletonVariant.Line)
        }
    }
    RaftCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The meter, then the seats breakdown's two rows, people and agents, as the web's
            // skeleton draws them (botiverse/slock 16f5f8bc40), so the card grows less when billing loads.
            listOf(0.9f, 0.9f, 0.6f, 0.6f).forEach { width ->
                RaftSkeleton(Modifier.fillMaxWidth(width), variant = RaftSkeletonVariant.Line)
            }
        }
    }
}
