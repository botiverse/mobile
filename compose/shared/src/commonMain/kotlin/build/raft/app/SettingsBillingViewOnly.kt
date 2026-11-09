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
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsBillingSubscriptionSnapshot
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.runCatchingPreservingCancellation
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
import kotlin.time.Clock

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
private const val SettingsBillingDayMillis = 24L * 60 * 60 * 1000

/** Shared PRO_AGENT_SEAT_FRACTION: an agent takes a tenth of a seat. */
private const val SettingsBillingAgentSeatFraction = 0.1

internal enum class SettingsBillingViewOnlyPlan { Free, Pro, Founder, Partner, AppSumo, Other }

/** The plan card's line under the plan name. */
internal sealed interface SettingsBillingViewOnlyDescription {
    data object FreeSummary : SettingsBillingViewOnlyDescription

    /** Pro on a subscription: its seats and when it renews or ends (the web line without the price). */
    data class ProSummary(val seats: Int, val ending: Boolean, val periodEnd: String) : SettingsBillingViewOnlyDescription

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
}

internal data class SettingsBillingViewOnlyDowngrade(val expired: Boolean, val daysLeft: Int)

internal data class SettingsBillingViewOnlySeats(val used: Double, val capacity: Double, val humans: Int, val agents: Int)

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
    val seats: SettingsBillingViewOnlySeats? = null,
    val appSumo: SettingsBillingViewOnlyAppSumo? = null,
    val uploads: SettingsBillingViewOnlyUploads? = null,
    val historyDays: Int? = null,
    val members: SettingsBillingViewOnlyMembers? = null,
    val details: SettingsBillingViewOnlyDetails? = null,
    val owner: Boolean = false,
)

internal fun settingsBillingViewOnlyModel(
    snapshot: SettingsBillingSnapshot?,
    sessionPlan: String?,
    nowEpochMillis: Long,
): SettingsBillingViewOnlyModel {
    val summary: SettingsBillingSubscriptionSnapshot? = snapshot?.summary
    val planId = (summary?.effectivePlan ?: snapshot?.serverPlan ?: sessionPlan)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "free"
    val appsumo = summary?.appsumo
    // web billingControls getBillingControlsState.
    val internal = planId == "founder" || planId == "partner" || summary?.source == "appsumo" || summary?.appsumoBillingReserved == true
    val entitling = !internal && (summary?.status == "active" || summary?.status == "past_due")
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
            pastDue -> SettingsBillingViewOnlyStatus.PaymentFailed
            ending && periodEnd != null -> SettingsBillingViewOnlyStatus.EndsOn(periodEnd)
            else -> null
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
internal fun settingsBillingFormatSeats(value: Double): String {
    val tenths = (value * 10).roundToLong()
    val whole = tenths / 10
    val fraction = kotlin.math.abs(tenths % 10)
    return if (fraction == 0L) whole.toString() else "$whole.$fraction"
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

/** The calendar day of an ISO timestamp ("Dec 1, 2026"; 2026年12月1日). */
@Composable
private fun settingsBillingDate(iso: String): String {
    val day = iso.take(10).split('-')
    val year = day.getOrNull(0)?.toIntOrNull()
    val month = day.getOrNull(1)?.toIntOrNull()
    val dayOfMonth = day.getOrNull(2)?.toIntOrNull()
    return if (year != null && month != null && dayOfMonth != null) {
        membersLocalizedDate(MembersJoinedDate(year, month, dayOfMonth))
    } else {
        iso
    }
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
    nowEpochMillis: Long = remember { Clock.System.now().toEpochMilliseconds() },
) {
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
                SettingsBillingViewOnlyOverview(settingsBillingViewOnlyModel(billing.data, sessionPlan, nowEpochMillis), onRetry)
            is ScreenLoadState.Empty,
            is ScreenLoadState.Error ->
                SettingsBillingViewOnlyOverview(settingsBillingViewOnlyModel(null, sessionPlan, nowEpochMillis), onRetry)
        }
    }
}

@Composable
private fun SettingsBillingViewOnlyOverview(model: SettingsBillingViewOnlyModel, onRetry: () -> Unit) {
    val freeUpload = settingsBillingFormatBytes(SettingsBillingFreeUploadBytes)
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
            description = stringResource(Res.string.settings_billing_vo_ending_body, SettingsBillingFreeHistoryDays, freeUpload),
            status = RaftBannerStatus.Info,
            title = stringResource(Res.string.settings_billing_vo_ending_title, settingsBillingDate(periodEnd)),
        )
    }

    // The plan
    val status = model.status
    val statusBadge: (@Composable () -> Unit)? = if (status != null) ({ SettingsBillingStatusBadge(status) }) else null
    RaftCard(
        modifier = Modifier.fillMaxWidth(),
        title = settingsBillingPlanName(model),
        description = settingsBillingPlanDescription(model.description, freeUpload),
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
private fun settingsBillingPlanDescription(description: SettingsBillingViewOnlyDescription, freeUpload: String): String? =
    when (description) {
        SettingsBillingViewOnlyDescription.FreeSummary ->
            stringResource(Res.string.settings_billing_vo_free_summary, SettingsBillingFreeHistoryDays, freeUpload)
        is SettingsBillingViewOnlyDescription.ProSummary ->
            stringResource(
                Res.string.settings_billing_vo_summary_join,
                settingsBillingSeatCount(description.seats),
                stringResource(
                    if (description.ending) Res.string.settings_billing_vo_ends_on else Res.string.settings_billing_vo_renews_on,
                    settingsBillingDate(description.periodEnd),
                ),
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
                    RaftDescriptionList(direction = RaftDescriptionListDirection.Inline) {
                        Item(
                            term = stringResource(Res.string.settings_billing_vo_people),
                            value = settingsBillingCountSeats(seats.humans, seats.humans.toDouble()),
                            copyable = false,
                        )
                        Item(
                            term = stringResource(Res.string.settings_billing_vo_agents),
                            value = settingsBillingCountSeats(seats.agents, seats.agents * SettingsBillingAgentSeatFraction),
                            copyable = false,
                        )
                    }
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

/** "3 · 3 seats": a count and the seats it takes. */
@Composable
private fun settingsBillingCountSeats(count: Int, seats: Double): String =
    stringResource(
        if (seats == 1.0) Res.string.settings_billing_vo_count_seats_one else Res.string.settings_billing_vo_count_seats_other,
        count,
        settingsBillingFormatSeats(seats),
    )

/** One AppSumo allowance: what is used of the license's limit (web UsageMeter). */
@Composable
private fun SettingsBillingCountMeter(label: String, used: Int, limit: Int) {
    RaftMeter(
        used = used.toFloat(),
        max = max(limit.toFloat(), 0.0001f),
        label = label,
        valueText = stringResource(Res.string.settings_billing_vo_usage_used, used.toString(), limit.toString()),
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
            repeat(3) { row ->
                RaftSkeleton(Modifier.fillMaxWidth(if (row == 2) 0.6f else 0.9f), variant = RaftSkeletonVariant.Line)
            }
        }
    }
}
