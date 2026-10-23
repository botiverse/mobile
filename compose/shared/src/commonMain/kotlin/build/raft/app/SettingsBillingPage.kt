package build.raft.app

import build.raft.app.data.StoredAuthSession
import build.raft.app.data.AppLocale
import build.raft.app.compat.pager.Pager
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

