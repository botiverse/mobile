package build.raft.app

import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalCard
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SettingsBillingActionSnapshot
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.AppLocale
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.Serializable

// Opened from the Settings "Workspace" section through the single production
// Settings WebView page. Rendering inside the retained multi-tab child pager
// previously caused the header/WebView attach-order flash (task #227).
internal fun Pager.openSettingsBillingPage(storeScope: build.raft.app.data.RaftShellStoreScope) {
    openSettingsWebViewPage(SettingsWebViewSurface.Billing, storeScope)
}

@Serializable
internal data class RaftSettingsBillingPayload(
    val instanceId: String = "",
    val status: String = "content",
    val currentPlan: RaftSettingsCurrentPlan = RaftSettingsCurrentPlan(),
    val plans: List<RaftSettingsBillingPlan> = emptyList(),
    val notices: List<RaftSettingsBillingNotice> = emptyList(),
    val actions: List<RaftSettingsBillingAction> = emptyList(),
    val comparePlansUrl: String = "https://raft.build/#pricing",
    val labels: RaftSettingsBillingLabels = RaftSettingsBillingLabels()
)

@Serializable
internal data class RaftSettingsBillingLabels(
    val trialActive: String = "Free Trial Active",
    val trialDetail: String = "Your workspace can keep testing Raft with all core collaboration features enabled.",
    val currentPlan: String = "CURRENT PLAN",
    val usage: String = "USAGE",
    val currentPlanButton: String = "Current Plan",
    val choosePlan: String = "Choose Plan",
    val comingSoon: String = "Coming Soon",
    val loading: String = "Loading billing",
    val error: String = "Could not load plans",
    val noPermission: String = "Only server owners and admins can manage billing."
)

@Serializable
internal data class RaftSettingsCurrentPlan(
    val planName: String = "Hobby",
    val usageRows: List<RaftSettingsPlanUsageRow> = emptyList(),
    val trialActive: Boolean = false,
    val description: String = "",
    val includedFeatures: List<String> = emptyList(),
    val excludedFeatures: List<String> = emptyList()
)

@Serializable
internal data class RaftSettingsPlanUsageRow(
    val label: String = "",
    val value: String = ""
)

@Serializable
internal data class RaftSettingsBillingPlan(
    val id: String = "",
    val name: String = "",
    val price: String = "",
    val features: List<String> = emptyList(),
    val current: Boolean = false,
    val comingSoon: Boolean = false,
    val badgeLabel: String? = null
)

@Serializable
internal data class RaftSettingsBillingNotice(
    val key: String = "",
    val title: String = "",
    val detail: String = "",
    val tone: String = "info",
    val rows: List<RaftSettingsPlanUsageRow> = emptyList()
)

@Serializable
internal data class RaftSettingsBillingAction(
    val key: String = "",
    val label: String = "",
    val detail: String = "",
    val enabled: Boolean = false,
    val style: String = "secondary",
    val status: String = "",
    val errorCode: String? = null,
    val inProgress: Boolean = false
)

internal val settingsBillingSmokePayload =
    RaftSettingsBillingPayload(
        instanceId = "settings-billing-smoke",
        currentPlan =
            RaftSettingsCurrentPlan(
                planName = "Hobby",
                trialActive = true,
                description = "Get started in minutes.",
                includedFeatures =
                    listOf(
                        "Channels",
                        "Tasks",
                        "Unlimited agents on your own computers",
                        "30 days of message history"
                    ),
                excludedFeatures = listOf("Unlimited message history"),
                usageRows =
                    listOf(
                        RaftSettingsPlanUsageRow("Messages", "4,120 / 10,000"),
                        RaftSettingsPlanUsageRow("Members", "8 / 20"),
                        RaftSettingsPlanUsageRow("AI agents", "5 active")
                    )
            ),
        notices =
            listOf(
                RaftSettingsBillingNotice(
                    key = "trial",
                    title = "Final Trial Period",
                    detail = "All features are currently unlocked during the free trial.",
                    tone = "trial"
                )
            ),
        actions =
            listOf(
                RaftSettingsBillingAction(
                    key = "portal",
                    label = "Billing portal",
                    detail = "Paid plans are not available yet."
                ),
                RaftSettingsBillingAction(
                    key = "compare",
                    label = "See all features and compare plans",
                    detail = "Open public pricing comparison."
                )
            ),
        plans =
            listOf(
                RaftSettingsBillingPlan(
                    id = "hobby",
                    name = "Hobby",
                    price = "$0 / month",
                    features =
                        listOf(
                            "Core chat and task workflows",
                            "Up to 20 members",
                            "Mobile app access"
                        ),
                    current = true
                ),
                RaftSettingsBillingPlan(
                    id = "team",
                    name = "Team",
                    price = "$29 / month",
                    features = listOf("Shared team spaces", "More AI agent capacity", "Priority workspace controls")
                ),
                RaftSettingsBillingPlan(
                    id = "business",
                    name = "Business",
                    price = "Custom",
                    features =
                        listOf(
                            "Advanced audit and billing controls with very long feature text that wraps cleanly",
                            "Dedicated workspace support"
                        ),
                    comingSoon = true
                )
            )
    )

internal val settingsBillingWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.Billing,
        includeLocaleQuery = true,
        buildWebUrl = ::settingsBillingWebViewUrl,
    )

@Composable
internal fun SettingsBillingContent(
    payload: RaftSettingsBillingPayload,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    onEvent: (String, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (payload.status) {
            "loading" -> item { SettingsBillingLoadingContent() }
            "error" -> item { SettingsBillingStatusPanel(payload.labels.error) }
            "noPermission" ->
                item {
                    SettingsBillingSectionHeader(RaftLucideIconName.CreditCard, "Billing")
                    SettingsBillingStatusPanel(payload.labels.noPermission)
                }
            else -> {
                val notices =
                    payload.notices.ifEmpty {
                        if (payload.currentPlan.trialActive) {
                            listOf(
                                RaftSettingsBillingNotice(
                                    key = "trial",
                                    title = payload.labels.trialActive,
                                    detail = payload.labels.trialDetail,
                                    tone = "trial"
                                )
                            )
                        } else {
                            emptyList()
                        }
                    }
                notices.forEach { notice ->
                    item(key = "notice-${notice.key}") { BillingNoticeCard(notice) }
                }
                item { SettingsBillingSectionHeader(RaftLucideIconName.CreditCard, payload.labels.currentPlan) }
                item { CurrentPlanCard(payload, onEvent) }
                item { SettingsBillingSectionHeader(RaftLucideIconName.CreditCard, payload.planSectionLabel()) }
                items(payload.plans.size, key = { index -> "plan-$index-${payload.plans[index].id}" }) { index ->
                    val plan = payload.plans[index]
                    PlanCard(
                        plan = plan,
                        labels = payload.labels,
                        onChoosePlan = { onEvent("choosePlan", plan.id.ifBlank { plan.name }) }
                    )
                }
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

internal fun settingsBillingWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?,
    localeTag: String = AppLocale.Default.languageTag
): String {
    val serverSlug = standaloneSettingsServerSlug(session)
    val path = serverSlug
        ?.takeIf { it.isNotBlank() }
        ?.let { "s/$it/settings/billing" }
        ?: "settings/billing"
    return sharedSettingsHostShellWebUrl(
        webUrl = webBaseUrl,
        path = path,
        extraQueryParameters = listOf("lang" to localeTag)
    )
}

internal fun settingsBillingWebViewInjectedJavaScript(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding? = null,
): String {
    return standaloneSettingsWebViewInjectedJavaScript(session, hostBinding, SettingsWebViewSurface.Billing)
}

@Composable
private fun BillingNoticeCard(notice: RaftSettingsBillingNotice) {
    val background = billingNoticeBackground(notice.tone)
    // Sticker tones keep ink type in both modes; the cream fallback is a themed surface.
    val ink = if (background == RaftColor.SurfaceCream) RaftColor.TextBlack else RaftColor.StickerInk
    val iconName =
        when (notice.tone) {
            "warning" -> RaftLucideIconName.TriangleAlert
            else -> RaftLucideIconName.BadgeInfo
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(background)
                .border(RaftAppStroke.Default, ink)
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RaftLucideIcon(iconName, modifier = Modifier.size(16.dp), color = raftArgbHex(ink))
            Text(notice.title, style = RaftType.ButtonBold.copy(color = ink), color = ink)
        }
        Text(notice.detail, style = RaftType.Body.copy(color = ink), color = ink)
        notice.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.label, style = RaftType.TabLabel.copy(color = ink), color = ink, modifier = Modifier.weight(1f))
                Text(row.value, style = RaftType.TabLabel.copy(color = ink), color = ink)
            }
        }
    }
}

private fun billingNoticeBackground(tone: String): Color =
    when (tone) {
        "warning" -> RaftColor.AccentOrange
        "success" -> RaftColor.AccentLime
        "trial" -> RaftColor.BrandYellow
        else -> RaftColor.SurfaceCream
    }

private val SettingsBillingSoftDivider: Color get() = RaftColor.BorderHairline
private const val SettingsBillingIncludedIconColor = "#FF15803D"
private const val SettingsBillingExcludedIconColor = "#FFF97264"

private fun RaftSettingsBillingPayload.planSectionLabel(): String =
    if (plans.any { !it.current && it.name.equals("Pro", ignoreCase = true) }) {
        "UPGRADE TO PRO"
    } else {
        "MANAGE PLAN"
    }

@Composable
private fun CurrentPlanCard(
    payload: RaftSettingsBillingPayload,
    onEvent: (String, String?) -> Unit
) {
    RaftBrutalCard(
        shadowOffset = 2.dp,
        contentPadding = 0.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RaftColor.SurfaceCream)
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                payload.currentPlan.planName,
                style = RaftType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp, color = RaftColor.TextBlack),
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            payload.currentPlan.description.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftAppStroke.Default).background(RaftColor.BorderBlack))

        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BillingFeatureSection("Included", payload.currentPlan.includedFeatures, included = true)
            if (payload.currentPlan.excludedFeatures.isNotEmpty()) {
                BillingFeatureSection("Not included", payload.currentPlan.excludedFeatures, included = false)
            }
        }

        if (payload.currentPlan.usageRows.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(SettingsBillingSoftDivider))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                payload.currentPlan.usageRows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.label,
                            style = RaftType.TabLabel.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextBlack),
                            color = RaftColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            row.value,
                            style = RaftType.TabLabel.copy(color = RaftColor.TextMutedGray),
                            color = RaftColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(SettingsBillingSoftDivider))
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            BillingActions(payload, onEvent)
        }
    }
}

@Composable
private fun BillingFeatureSection(
    title: String,
    features: List<String>,
    included: Boolean
) {
    if (features.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp, color = if (included) RaftColor.TextBlack else RaftColor.TextMutedGray),
            color = if (included) RaftColor.TextBlack else RaftColor.TextMutedGray
        )
        features.forEach { feature ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftLucideIcon(
                    if (included) RaftLucideIconName.Check else RaftLucideIconName.X,
                    modifier = Modifier.padding(top = 2.dp).size(14.dp),
                    color = if (included) SettingsBillingIncludedIconColor else SettingsBillingExcludedIconColor
                )
                Text(
                    feature,
                    style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BillingActions(
    payload: RaftSettingsBillingPayload,
    onEvent: (String, String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        payload.actions.forEach { action ->
            BillingActionButton(action) { onEvent(action.key, null) }
        }
        BillingActionButton(
            RaftSettingsBillingAction(
                key = "comparePlans",
                label = stringResource(MR.strings.settings_billing_action_compare_plans),
                enabled = payload.comparePlansUrl.isNotBlank()
            )
        ) { onEvent("comparePlans", payload.comparePlansUrl.takeIf { it.isNotBlank() }) }
    }
}

@Composable
private fun BillingActionButton(action: RaftSettingsBillingAction, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBrutalActionButton(
            label = action.status.ifBlank { action.label },
            onClick = onClick,
            enabled = action.enabled && !action.inProgress,
            modifier = Modifier,
            minWidth = 0.dp,
            minHeight = 34.dp,
            shadowOffset = 2.dp
        )
        action.detail.takeIf { it.isNotBlank() }?.let { detail ->
            Text(detail, style = RaftType.Caption.copy(color = RaftColor.TextMutedGray), color = RaftColor.TextMutedGray)
        }
        action.errorCode?.takeIf { it.isNotBlank() }?.let { errorCode ->
            Text(errorCode, style = RaftType.Caption.copy(color = RaftColor.AccentRed), color = RaftColor.AccentRed)
        }
    }
}

internal fun SettingsBillingActionSnapshot.toBillingActionPayload(
    actionState: SharedSettingsActionState?,
    errorStrings: ErrorPresentationStrings
): RaftSettingsBillingAction {
    val isCurrentAction = actionState?.action == key
    return RaftSettingsBillingAction(
        key = key,
        label = label,
        detail = detail,
        enabled = enabled && actionState?.inProgress != true,
        style = style,
        status =
            when {
                !isCurrentAction -> ""
                actionState?.inProgress == true -> "Loading"
                actionState?.hasUserFacingError() == true -> "Error"
                else -> "Saved"
            },
        errorCode = actionState?.userFacingErrorMessage(errorStrings)?.takeIf { isCurrentAction },
        inProgress = actionState?.inProgress == true && isCurrentAction
    )
}

@Composable
private fun SettingsBillingLoadingContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsBillingSectionHeader(
            RaftLucideIconName.CreditCard,
            stringResource(MR.strings.settings_section_billing_title)
        )
        HardShadowCard {
            repeat(4) { index ->
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(if (index == 0) 28.dp else 18.dp)
                            .background(if (index == 0) RaftColor.EmptyStateGray else RaftColor.SurfaceCream)
                            .border(1.dp, RaftColor.BorderBlack)
                )
            }
        }
        SettingsBillingSectionHeader(
            RaftLucideIconName.CreditCard,
            stringResource(MR.strings.settings_billing_plans_title)
        )
        repeat(3) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(112.dp)
                        .background(RaftColor.SurfaceWhite)
                        .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                        .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth().height(18.dp).background(RaftColor.EmptyStateGray))
                    Box(Modifier.fillMaxWidth().height(14.dp).background(RaftColor.SurfaceCream))
                    Box(Modifier.fillMaxWidth().height(14.dp).background(RaftColor.SurfaceCream))
                }
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: RaftSettingsBillingPlan,
    labels: RaftSettingsBillingLabels,
    onChoosePlan: () -> Unit
) {
    val enabled = !plan.current && !plan.comingSoon
    val content = @Composable {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .background(RaftColor.SurfaceWhite)
                    .border(
                        RaftAppStroke.Default,
                        if (plan.comingSoon) RaftColor.TextMutedGray else RaftColor.BorderBlack
                    )
                    .alpha(if (plan.comingSoon) 0.68f else 1f)
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        plan.name.uppercase(),
                        style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
                        color = RaftColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        plan.price,
                        style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (plan.current) {
                    SettingsStatusBadge(plan.badgeLabel ?: labels.currentPlanButton, RaftColor.AccentLime)
                } else if (plan.comingSoon) {
                    SettingsStatusBadge(plan.badgeLabel ?: labels.comingSoon, RaftColor.EmptyStateGray)
                }
            }
            plan.features.forEach { feature ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RaftLucideIcon(RaftLucideIconName.Check, modifier = Modifier.padding(top = 3.dp).size(14.dp))
                    Text(
                        feature,
                        style = RaftType.Body.copy(color = RaftColor.TextBlack),
                        color = RaftColor.TextBlack,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            PlanActionButton(
                text =
                    when {
                        plan.current -> labels.currentPlanButton
                        plan.comingSoon -> labels.comingSoon
                        else -> labels.choosePlan
                    },
                enabled = enabled,
                current = plan.current,
                onClick = onChoosePlan
            )
        }
    }
    if (plan.comingSoon) {
        Box(modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Box(modifier = Modifier.fillMaxWidth().padding(end = 2.dp, bottom = 2.dp)) {
            Box(Modifier.fillMaxSize().offset(x = 2.dp, y = 2.dp).background(RaftColor.ShadowBlack))
            content()
        }
    }
}

@Composable
private fun PlanActionButton(
    text: String,
    enabled: Boolean,
    current: Boolean,
    onClick: () -> Unit
) {
    val background =
        when {
            current -> RaftColor.AccentLime
            enabled -> RaftColor.AccentPink
            else -> RaftColor.EmptyStateGray
    }
    RaftBrutalActionButton(
        label = text,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = background,
        disabledBackgroundColor = background,
        minWidth = 0.dp,
        minHeight = 42.dp,
        textStyle = RaftType.ButtonBold.copy(color = RaftColor.TextBlack)
    )
}

@Composable
private fun SettingsBillingSectionHeader(iconName: RaftLucideIconName, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RaftLucideIcon(iconName, modifier = Modifier.size(16.dp), color = RaftColor.TextBlackArgb)
        Text(
            label,
            style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsStatusBadge(text: String, color: Color) {
    val ink = if (color == RaftColor.EmptyStateGray) RaftColor.TextBlack else RaftColor.StickerInk
    Box(
        modifier = Modifier.background(color).border(1.dp, ink).padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = RaftType.TabLabel.copy(color = ink),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsBillingStatusPanel(title: String) {
    HardShadowCard {
        Text(
            title,
            style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = RaftColor.TextMutedGray),
            color = RaftColor.TextMutedGray
        )
    }
}

@Composable
private fun HardShadowCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    RaftBrutalCard(
        shadowOffset = 2.dp,
        contentPadding = 14.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}
