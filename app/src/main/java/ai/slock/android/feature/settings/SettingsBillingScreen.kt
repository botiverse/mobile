package ai.slock.android.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.slock.android.R
import ai.slock.android.ui.components.SlockErrorState
import ai.slock.android.ui.components.SlockIcons
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockTheme
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

data class SettingsCurrentPlanState(
    val planName: String,
    val usageRows: List<SettingsPlanUsageRow>,
    val trialActive: Boolean = false,
    val loading: Boolean = false,
    val error: Boolean = false,
    val noPermission: Boolean = false
)

data class SettingsPlanUsageRow(
    val label: String,
    val value: String
)

data class SettingsPlanCardState(
    val name: String,
    val price: String,
    val features: List<String>,
    val current: Boolean = false,
    val comingSoon: Boolean = false
)

@Composable
fun SettingsBillingScreen(
    currentPlan: SettingsCurrentPlanState,
    plans: List<SettingsPlanCardState>,
    modifier: Modifier = Modifier,
    onChoosePlan: (String) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        when {
            currentPlan.loading -> SettingsBillingStatusPanel(
                title = stringResource(R.string.settings_billing_loading)
            )
            currentPlan.error -> SlockErrorState(
                title = stringResource(R.string.settings_billing_error),
                detail = stringResource(R.string.settings_billing_trial_detail),
                retryLabel = stringResource(R.string.settings_billing_choose_plan),
                onRetry = {}
            )
            currentPlan.noPermission -> SettingsBillingStatusPanel(
                title = stringResource(R.string.settings_billing_no_permission)
            )
            else -> {
                if (currentPlan.trialActive) {
                    PlanTrialBanner()
                }
                CurrentPlanCard(currentPlan = currentPlan)
                SettingsSectionHeader(
                    iconBackground = SlockColor.EmptyStateGray,
                    label = stringResource(R.string.settings_billing_current_plan)
                )
                plans.forEach { plan ->
                    PlanCard(
                        plan = plan,
                        onChoosePlan = { onChoosePlan(plan.name) }
                    )
                }
            }
        }
    }
}

@Composable
fun PlanTrialBanner(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.AccentLime)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = stringResource(R.string.settings_billing_trial_active),
            style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack
        )
        Text(
            text = stringResource(R.string.settings_billing_trial_detail),
            style = SlockType.Body,
            color = SlockColor.TextBlack
        )
    }
}

@Composable
fun CurrentPlanCard(
    currentPlan: SettingsCurrentPlanState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
            .slockHardShadow(SlockShadow.Small)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Text(
                text = stringResource(R.string.settings_billing_current_plan),
                style = SlockType.SectionLabel,
                color = SlockColor.TextMutedGray,
                modifier = Modifier.weight(1f)
            )
            SettingsStatusBadge(
                text = currentPlan.planName.uppercase(),
                color = SlockColor.BrandYellow
            )
        }
        SettingsSectionHeader(
            iconBackground = SlockColor.EmptyStateGray,
            label = stringResource(R.string.settings_billing_usage)
        )
        currentPlan.usageRows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.label,
                    style = SlockType.Body,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = row.value,
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun PlanCard(
    plan: SettingsPlanCardState,
    modifier: Modifier = Modifier,
    onChoosePlan: () -> Unit = {}
) {
    val enabled = !plan.current && !plan.comingSoon
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (plan.comingSoon) {
                    Modifier.border(BorderStroke(SlockStroke.Default, SlockColor.TextMutedGray), RoundedCornerShape(SlockRadius.Xs))
                } else {
                    Modifier
                        .padding(end = SlockShadow.Small.offsetX, bottom = SlockShadow.Small.offsetY)
                        .slockHardShadow(SlockShadow.Small)
                        .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
                }
            )
            .background(SlockColor.SurfaceWhite, RoundedCornerShape(SlockRadius.Xs))
            .alpha(if (plan.comingSoon) 0.68f else 1f)
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plan.name.uppercase(),
                    style = SlockType.PageTitle,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = plan.price,
                    style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (plan.current) {
                SettingsStatusBadge(
                    text = stringResource(R.string.settings_billing_current_plan_button),
                    color = SlockColor.AccentLime
                )
            }
        }
        plan.features.forEach { feature ->
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                Icon(
                    imageVector = SlockIcons.Check,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(14.dp)
                )
                Text(
                    text = feature,
                    style = SlockType.Body,
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        PlanActionButton(
            text = when {
                plan.current -> stringResource(R.string.settings_billing_current_plan_button)
                plan.comingSoon -> stringResource(R.string.settings_billing_coming_soon)
                else -> stringResource(R.string.settings_billing_choose_plan)
            },
            enabled = enabled,
            current = plan.current,
            onClick = onChoosePlan
        )
    }
}

@Composable
private fun PlanActionButton(
    text: String,
    enabled: Boolean,
    current: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val background = when {
        current -> SlockColor.AccentLime
        enabled -> SlockColor.AccentPink
        else -> SlockColor.EmptyStateGray
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .background(background)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsBillingStatusPanel(title: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        color = SlockColor.SurfaceWhite
    ) {
        Text(
            text = title,
            style = SlockType.Body.copy(fontWeight = FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(SlockSpacing.Md)
        )
    }
}

private val currentBillingSample = SettingsCurrentPlanState(
    planName = "Hobby",
    trialActive = true,
    usageRows = listOf(
        SettingsPlanUsageRow("Messages", "4,120 / 10,000"),
        SettingsPlanUsageRow("Members", "8 / 20"),
        SettingsPlanUsageRow("AI agents", "5 active")
    )
)

private val planSamples = listOf(
    SettingsPlanCardState(
        name = "Hobby",
        price = "$0 / month",
        features = listOf("Core chat and task workflows", "Up to 20 members", "Local Android preview support"),
        current = true
    ),
    SettingsPlanCardState(
        name = "Team",
        price = "$29 / month",
        features = listOf("Shared team spaces", "More AI agent capacity", "Priority workspace controls")
    ),
    SettingsPlanCardState(
        name = "Business",
        price = "Custom",
        features = listOf("Advanced audit and billing controls with very long feature text that wraps cleanly", "Dedicated workspace support"),
        comingSoon = true
    )
)

@Preview(showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
private fun SettingsBillingPreview() {
    SlockTheme {
        SettingsBillingScreen(
            currentPlan = currentBillingSample,
            plans = planSamples
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
private fun SettingsBillingStatesPreview() {
    SlockTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SlockColor.AppBgLightBlue)
                .padding(SlockSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            PlanTrialBanner()
            CurrentPlanCard(currentPlan = currentBillingSample)
            PlanCard(plan = planSamples[2])
            SettingsBillingStatusPanel(title = stringResource(R.string.settings_billing_loading))
            SettingsBillingStatusPanel(title = stringResource(R.string.settings_billing_no_permission))
            SettingsBillingScreen(
                currentPlan = currentBillingSample.copy(error = true),
                plans = planSamples,
                modifier = Modifier.heightIn(max = 260.dp)
            )
        }
    }
}
