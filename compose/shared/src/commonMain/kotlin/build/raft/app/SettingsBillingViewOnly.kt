package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import build.raft.app.base.Utils
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsBillingSnapshot
import build.raft.app.data.SettingsBillingUsageCount
import build.raft.app.data.SettingsBillingUsageSnapshot
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsViewModel
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowValue
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSection
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * iOS Plan & Billing, view-only (artin 11-02, App Store guideline 3.1.1): the current plan and its
 * usage from the native billing load (`SharedSettingsDestination.Billing`), and nothing that leads
 * to a purchase: no upgrade, plan choice, portal or payment entry and no browser link. Drawn by
 * the Billing route ([SettingsWebViewScreen]), so it is both the pushed page and the wide pane.
 */
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
    // The session's plan, as the Settings row shows it: what a member who is not the owner (the
    // billing load is owner-only) or a failed load still sees.
    val sessionPlanName by produceState<String?>(null, environment.authStore) {
        value =
            runCatchingPreservingCancellation { environment.authStore.readSession() }.getOrNull()
                ?.currentServerSummary()?.plan?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
    }
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceCream)) {
        RaftTopPageHeader(
            title = stringResource(Res.string.settings_billing_title),
            iconName = RaftLucideIconName.CreditCard,
            backgroundColor = RaftColor.SurfaceWhite,
            applyTopSafeArea = !wideDetail,
            onBack = if (wideDetail) null else ::close,
        )
        SettingsBillingViewOnlyContent(billing = settingsState.billing, sessionPlanName = sessionPlanName)
    }
}

@Composable
internal fun SettingsBillingViewOnlyContent(
    billing: ScreenLoadState<SettingsBillingSnapshot>,
    sessionPlanName: String?,
    modifier: Modifier = Modifier,
) {
    val content = (billing as? ScreenLoadState.Content<SettingsBillingSnapshot>)?.data
    val planName = content?.currentPlan?.planName?.takeIf { it.isNotBlank() } ?: sessionPlanName
    val usage = content?.currentPlan?.usage
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (planName != null) {
            RaftSection(title = stringResource(Res.string.settings_billing_current_plan), separator = false) {
                RaftText(planName, style = RaftTheme.typography.headline, color = RaftTheme.colors.foreground)
            }
        }
        if (usage != null) {
            RaftSection(title = stringResource(Res.string.settings_billing_usage_title), separator = planName != null) {
                SettingsBillingUsageRows(usage)
            }
        } else if (billing is ScreenLoadState.Error) {
            RaftSection(title = stringResource(Res.string.settings_billing_usage_title), separator = planName != null) {
                RaftText(
                    stringResource(Res.string.settings_billing_usage_load_failed),
                    style = RaftTheme.typography.body,
                    color = RaftTheme.colors.foregroundMuted,
                )
            }
        }
    }
}

@Composable
private fun SettingsBillingUsageRows(usage: SettingsBillingUsageSnapshot) {
    val rows =
        listOf(
            stringResource(Res.string.settings_billing_usage_computers) to settingsBillingUsageCountText(usage.computers, usage.trialActive),
            stringResource(Res.string.settings_billing_usage_agents) to settingsBillingUsageCountText(usage.agents, usage.trialActive),
            stringResource(Res.string.settings_billing_usage_channels) to settingsBillingUsageCountText(usage.channels, usage.trialActive),
            stringResource(Res.string.settings_billing_usage_message_history) to
                settingsBillingHistoryText(usage.messageHistoryDays, usage.trialActive),
        )
    RaftListRowGroup(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, (label, value) ->
            RaftListRow(
                title = label,
                variant = RaftListRowVariant.Plain,
                divider = index > 0,
                trailing = { RaftListRowValue(value) },
            )
        }
    }
}

/** A usage count against its limit: -1 is unlimited, null unknown; the trial lifts a finite limit. */
@Composable
private fun settingsBillingUsageCountText(count: SettingsBillingUsageCount, trialActive: Boolean): String {
    val limit = count.limit
    return when {
        limit == null -> count.used.toString()
        limit == -1 -> stringResource(Res.string.settings_billing_usage_count_unlimited, count.used)
        trialActive -> stringResource(Res.string.settings_billing_usage_count_trial, count.used)
        else -> stringResource(Res.string.settings_billing_usage_count_of_limit, count.used, limit)
    }
}

@Composable
private fun settingsBillingHistoryText(days: Int?, trialActive: Boolean): String =
    when {
        days == null -> stringResource(Res.string.settings_billing_usage_unavailable)
        days == -1 -> stringResource(Res.string.settings_billing_usage_unlimited)
        trialActive -> stringResource(Res.string.settings_billing_usage_unlimited_trial)
        else -> stringResource(Res.string.settings_billing_usage_history_days, days)
    }
