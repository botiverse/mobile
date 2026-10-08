package build.raft.app

import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalCard
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SettingsBillingActionSnapshot
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.AppLocale
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.Serializable

// Opened from the Settings "Workspace" section through the single production
// Settings WebView page. Rendering inside the retained multi-tab child pager
// previously caused the header/WebView attach-order flash (task #227).
internal fun Pager.openSettingsBillingPage(storeScope: build.raft.app.data.SlockShellStoreScope) {
    openSettingsWebViewPage(SettingsWebViewSurface.Billing, storeScope)
}

@Serializable
internal data class KuiklySettingsBillingPayload(
    val instanceId: String = "",
    val status: String = "content",
    val currentPlan: KuiklySettingsCurrentPlan = KuiklySettingsCurrentPlan(),
    val plans: List<KuiklySettingsBillingPlan> = emptyList(),
    val notices: List<KuiklySettingsBillingNotice> = emptyList(),
    val actions: List<KuiklySettingsBillingAction> = emptyList(),
    val comparePlansUrl: String = "https://raft.build/#pricing",
    val labels: KuiklySettingsBillingLabels = KuiklySettingsBillingLabels()
)

@Serializable
internal data class KuiklySettingsBillingLabels(
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
internal data class KuiklySettingsCurrentPlan(
    val planName: String = "Hobby",
    val usageRows: List<KuiklySettingsPlanUsageRow> = emptyList(),
    val trialActive: Boolean = false,
    val description: String = "",
    val includedFeatures: List<String> = emptyList(),
    val excludedFeatures: List<String> = emptyList()
)

@Serializable
internal data class KuiklySettingsPlanUsageRow(
    val label: String = "",
    val value: String = ""
)

@Serializable
internal data class KuiklySettingsBillingPlan(
    val id: String = "",
    val name: String = "",
    val price: String = "",
    val features: List<String> = emptyList(),
    val current: Boolean = false,
    val comingSoon: Boolean = false,
    val badgeLabel: String? = null
)

@Serializable
internal data class KuiklySettingsBillingNotice(
    val key: String = "",
    val title: String = "",
    val detail: String = "",
    val tone: String = "info",
    val rows: List<KuiklySettingsPlanUsageRow> = emptyList()
)

@Serializable
internal data class KuiklySettingsBillingAction(
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
    KuiklySettingsBillingPayload(
        instanceId = "settings-billing-smoke",
        currentPlan =
            KuiklySettingsCurrentPlan(
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
                        KuiklySettingsPlanUsageRow("Messages", "4,120 / 10,000"),
                        KuiklySettingsPlanUsageRow("Members", "8 / 20"),
                        KuiklySettingsPlanUsageRow("AI agents", "5 active")
                    )
            ),
        notices =
            listOf(
                KuiklySettingsBillingNotice(
                    key = "trial",
                    title = "Final Trial Period",
                    detail = "All features are currently unlocked during the free trial.",
                    tone = "trial"
                )
            ),
        actions =
            listOf(
                KuiklySettingsBillingAction(
                    key = "portal",
                    label = "Billing portal",
                    detail = "Paid plans are not available yet."
                ),
                KuiklySettingsBillingAction(
                    key = "compare",
                    label = "See all features and compare plans",
                    detail = "Open public pricing comparison."
                )
            ),
        plans =
            listOf(
                KuiklySettingsBillingPlan(
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
                KuiklySettingsBillingPlan(
                    id = "team",
                    name = "Team",
                    price = "$29 / month",
                    features = listOf("Shared team spaces", "More AI agent capacity", "Priority workspace controls")
                ),
                KuiklySettingsBillingPlan(
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
    payload: KuiklySettingsBillingPayload,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    onEvent: (String, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (payload.status) {
            "loading" -> item { SettingsBillingLoadingContent() }
            "error" -> item { SettingsBillingStatusPanel(payload.labels.error) }
            "noPermission" ->
                item {
                    SettingsBillingSectionHeader(SlockLucideIconName.CreditCard, "Billing")
                    SettingsBillingStatusPanel(payload.labels.noPermission)
                }
            else -> {
                val notices =
                    payload.notices.ifEmpty {
                        if (payload.currentPlan.trialActive) {
                            listOf(
                                KuiklySettingsBillingNotice(
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
                item { SettingsBillingSectionHeader(SlockLucideIconName.CreditCard, payload.labels.currentPlan) }
                item { CurrentPlanCard(payload, onEvent) }
                item { SettingsBillingSectionHeader(SlockLucideIconName.CreditCard, payload.planSectionLabel()) }
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
private fun BillingNoticeCard(notice: KuiklySettingsBillingNotice) {
    val background = billingNoticeBackground(notice.tone)
    val iconName =
        when (notice.tone) {
            "warning" -> SlockLucideIconName.TriangleAlert
            else -> SlockLucideIconName.BadgeInfo
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(background)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlockLucideIcon(iconName, modifier = Modifier.size(16.dp))
            Text(notice.title, style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        }
        Text(notice.detail, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        notice.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.label, style = SlockType.TabLabel.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack, modifier = Modifier.weight(1f))
                Text(row.value, style = SlockType.TabLabel.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
            }
        }
    }
}

private fun billingNoticeBackground(tone: String): Color =
    when (tone) {
        "warning" -> SlockColor.AccentOrange
        "success" -> SlockColor.AccentLime
        "trial" -> SlockColor.BrandYellow
        else -> SlockColor.SurfaceCream
    }

private val SettingsBillingSoftDivider = Color(0x1A000000)
private const val SettingsBillingIncludedIconColor = "#FF15803D"
private const val SettingsBillingExcludedIconColor = "#FFF97264"

private fun KuiklySettingsBillingPayload.planSectionLabel(): String =
    if (plans.any { !it.current && it.name.equals("Pro", ignoreCase = true) }) {
        "UPGRADE TO PRO"
    } else {
        "MANAGE PLAN"
    }

@Composable
private fun CurrentPlanCard(
    payload: KuiklySettingsBillingPayload,
    onEvent: (String, String?) -> Unit
) {
    SlockBrutalCard(
        shadowOffset = 2.dp,
        contentPadding = 0.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceCream)
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                payload.currentPlan.planName,
                style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp, color = SlockColor.TextBlack),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            payload.currentPlan.description.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))

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
            Box(Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SettingsBillingSoftDivider))
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
                            style = SlockType.TabLabel.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            row.value,
                            style = SlockType.TabLabel.copy(color = SlockColor.TextMutedGray),
                            color = SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SettingsBillingSoftDivider))
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
            style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp, color = if (included) SlockColor.TextBlack else SlockColor.TextMutedGray),
            color = if (included) SlockColor.TextBlack else SlockColor.TextMutedGray
        )
        features.forEach { feature ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlockLucideIcon(
                    if (included) SlockLucideIconName.Check else SlockLucideIconName.X,
                    modifier = Modifier.padding(top = 2.dp).size(14.dp),
                    color = if (included) SettingsBillingIncludedIconColor else SettingsBillingExcludedIconColor
                )
                Text(
                    feature,
                    style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BillingActions(
    payload: KuiklySettingsBillingPayload,
    onEvent: (String, String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        payload.actions.forEach { action ->
            BillingActionButton(action) { onEvent(action.key, null) }
        }
        BillingActionButton(
            KuiklySettingsBillingAction(
                key = "comparePlans",
                label = stringResource(MR.strings.settings_billing_action_compare_plans),
                enabled = payload.comparePlansUrl.isNotBlank()
            )
        ) { onEvent("comparePlans", payload.comparePlansUrl.takeIf { it.isNotBlank() }) }
    }
}

@Composable
private fun BillingActionButton(action: KuiklySettingsBillingAction, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockBrutalActionButton(
            label = action.status.ifBlank { action.label },
            onClick = onClick,
            enabled = action.enabled && !action.inProgress,
            modifier = Modifier,
            minWidth = 0.dp,
            minHeight = 34.dp,
            shadowOffset = 2.dp
        )
        action.detail.takeIf { it.isNotBlank() }?.let { detail ->
            Text(detail, style = SlockType.Caption.copy(color = SlockColor.TextMutedGray), color = SlockColor.TextMutedGray)
        }
        action.errorCode?.takeIf { it.isNotBlank() }?.let { errorCode ->
            Text(errorCode, style = SlockType.Caption.copy(color = SlockColor.AccentRed), color = SlockColor.AccentRed)
        }
    }
}

internal fun SettingsBillingActionSnapshot.toBillingActionPayload(
    actionState: SharedSettingsActionState?,
    errorStrings: ErrorPresentationStrings
): KuiklySettingsBillingAction {
    val isCurrentAction = actionState?.action == key
    return KuiklySettingsBillingAction(
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
            SlockLucideIconName.CreditCard,
            stringResource(MR.strings.settings_section_billing_title)
        )
        HardShadowCard {
            repeat(4) { index ->
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(if (index == 0) 28.dp else 18.dp)
                            .background(if (index == 0) SlockColor.EmptyStateGray else SlockColor.SurfaceCream)
                            .border(1.dp, SlockColor.BorderBlack)
                )
            }
        }
        SettingsBillingSectionHeader(
            SlockLucideIconName.CreditCard,
            stringResource(MR.strings.settings_billing_plans_title)
        )
        repeat(3) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(112.dp)
                        .background(SlockColor.SurfaceWhite)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth().height(18.dp).background(SlockColor.EmptyStateGray))
                    Box(Modifier.fillMaxWidth().height(14.dp).background(SlockColor.SurfaceCream))
                    Box(Modifier.fillMaxWidth().height(14.dp).background(SlockColor.SurfaceCream))
                }
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: KuiklySettingsBillingPlan,
    labels: KuiklySettingsBillingLabels,
    onChoosePlan: () -> Unit
) {
    val enabled = !plan.current && !plan.comingSoon
    val content = @Composable {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .border(
                        SlockStroke.Default,
                        if (plan.comingSoon) SlockColor.TextMutedGray else SlockColor.BorderBlack
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
                        style = SlockType.PageTitle.copy(color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        plan.price,
                        style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextMutedGray),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (plan.current) {
                    SettingsStatusBadge(plan.badgeLabel ?: labels.currentPlanButton, SlockColor.AccentLime)
                } else if (plan.comingSoon) {
                    SettingsStatusBadge(plan.badgeLabel ?: labels.comingSoon, SlockColor.EmptyStateGray)
                }
            }
            plan.features.forEach { feature ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.padding(top = 3.dp).size(14.dp))
                    Text(
                        feature,
                        style = SlockType.Body.copy(color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
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
            Box(Modifier.fillMaxSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
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
            current -> SlockColor.AccentLime
            enabled -> SlockColor.AccentPink
            else -> SlockColor.EmptyStateGray
    }
    SlockBrutalActionButton(
        label = text,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = background,
        disabledBackgroundColor = background,
        minWidth = 0.dp,
        minHeight = 42.dp,
        textStyle = SlockType.ButtonBold.copy(color = SlockColor.TextBlack)
    )
}

@Composable
private fun SettingsBillingSectionHeader(iconName: SlockLucideIconName, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(16.dp), color = "#FF000000")
        Text(
            label,
            style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsStatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier.background(color).border(1.dp, SlockColor.BorderBlack).padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = SlockType.TabLabel.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
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
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SlockColor.TextMutedGray),
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun HardShadowCard(content: @Composable com.tencent.kuikly.compose.foundation.layout.ColumnScope.() -> Unit) {
    SlockBrutalCard(
        shadowOffset = 2.dp,
        contentPadding = 14.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}
