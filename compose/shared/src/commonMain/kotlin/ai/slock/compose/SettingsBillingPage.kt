package ai.slock.compose

import ai.slock.compose.base.Utils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SETTINGS_BILLING_PAGE = "compose_settings_billing"

@Page(SETTINGS_BILLING_PAGE, supportInLocal = true)
internal class ComposeSettingsBillingPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsBillingScreen() }
    }
}

@Serializable
internal data class KuiklySettingsBillingPayload(
    val instanceId: String = "",
    val status: String = "content",
    val currentPlan: KuiklySettingsCurrentPlan = KuiklySettingsCurrentPlan(),
    val plans: List<KuiklySettingsBillingPlan> = emptyList(),
    val labels: KuiklySettingsBillingLabels = KuiklySettingsBillingLabels()
)

@Serializable
internal data class KuiklySettingsBillingLabels(
    val trialActive: String = "Free Trial Active",
    val trialDetail: String = "Your workspace can keep testing Slock with all core collaboration features enabled.",
    val currentPlan: String = "CURRENT PLAN",
    val usage: String = "USAGE",
    val currentPlanButton: String = "Current Plan",
    val choosePlan: String = "Choose Plan",
    val comingSoon: String = "Coming Soon",
    val loading: String = "Loading billing sample",
    val error: String = "Could not load plans",
    val noPermission: String = "Billing requires owner permission."
)

@Serializable
internal data class KuiklySettingsCurrentPlan(
    val planName: String = "Hobby",
    val usageRows: List<KuiklySettingsPlanUsageRow> = emptyList(),
    val trialActive: Boolean = false
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
    val comingSoon: Boolean = false
)

private val settingsBillingSmokePayload =
    KuiklySettingsBillingPayload(
        instanceId = "settings-billing-smoke",
        currentPlan =
            KuiklySettingsCurrentPlan(
                planName = "Hobby",
                trialActive = true,
                usageRows =
                    listOf(
                        KuiklySettingsPlanUsageRow("Messages", "4,120 / 10,000"),
                        KuiklySettingsPlanUsageRow("Members", "8 / 20"),
                        KuiklySettingsPlanUsageRow("AI agents", "5 active")
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
                            "Local Android preview support"
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

@Composable
private fun SettingsBillingScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val payload =
        rememberBillingPayload(
            pager.pageData.params.optString("payload"),
            pager.pageData.params.optString("instanceId")
        )
    LaunchedEffect(payload) {
        bridge.log(
            "settings_billing_render instanceId=${payload.instanceId} status=${payload.status} plans=${payload.plans.size}"
        )
    }
    SettingsBillingContent(payload) { action, planId ->
        bridge.settingsEvent(payload.instanceId, action, planId)
    }
}

@Composable
private fun rememberBillingPayload(payload: String, instanceId: String): KuiklySettingsBillingPayload =
    remember(payload, instanceId) {
        runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(KuiklySettingsBillingPayload.serializer(), payload)
                .let { if (it.instanceId.isBlank()) it.copy(instanceId = instanceId) else it }
        }.getOrElse { settingsBillingSmokePayload.copy(instanceId = instanceId) }
    }

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
            "loading" -> item { SettingsBillingStatusPanel(payload.labels.loading) }
            "error" -> item { SettingsBillingStatusPanel(payload.labels.error) }
            "noPermission" -> item { SettingsBillingStatusPanel(payload.labels.noPermission) }
            else -> {
                if (payload.currentPlan.trialActive) {
                    item { PlanTrialBanner(payload.labels.trialActive, payload.labels.trialDetail) }
                }
                item { CurrentPlanCard(payload) }
                item { SettingsBillingSectionHeader("credit-card", payload.labels.currentPlan) }
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

@Composable
private fun PlanTrialBanner(title: String, detail: String) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(SlockColor.AccentLime)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
        Text(detail, style = SlockType.Body.copy(color = SlockColor.TextBlack), color = SlockColor.TextBlack)
    }
}

@Composable
private fun CurrentPlanCard(payload: KuiklySettingsBillingPayload) {
    HardShadowCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                payload.labels.currentPlan,
                style = SlockType.SectionLabel.copy(color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SettingsStatusBadge(payload.currentPlan.planName.uppercase(), SlockColor.BrandYellow)
        }
        SettingsBillingSectionHeader("bar-chart-3", payload.labels.usage)
        payload.currentPlan.usageRows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.label,
                    style = SlockType.Body.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    row.value,
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                    SettingsStatusBadge(labels.currentPlanButton, SlockColor.AccentLime)
                }
            }
            plan.features.forEach { feature ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SlockLucideIcon("check", modifier = Modifier.padding(top = 3.dp).size(14.dp))
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
    Button(
        onClick = if (enabled) onClick else ({}),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(42.dp).background(background).border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        Text(
            text,
            style = SlockType.ButtonBold.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun SettingsBillingSectionHeader(iconName: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(SlockColor.EmptyStateGray).border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            SlockLucideIcon(iconName, modifier = Modifier.size(16.dp))
        }
        Text(
            label,
            style = SlockType.SectionLabel.copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack,
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
    Text(
        title,
        style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
        color = SlockColor.TextBlack,
        modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack).padding(14.dp)
    )
}

@Composable
private fun HardShadowCard(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.fillMaxSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}
