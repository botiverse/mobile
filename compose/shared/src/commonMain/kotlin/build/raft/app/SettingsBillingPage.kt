package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.module.RouterModule
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.AppLocale
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.compat.pager.Pager
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** What iOS shows for Plan & Billing, where the App Store's payment rules apply. */
internal enum class IosBillingPresentation {
    /**
     * The public web billing page in the system browser. Not shipped: kept for a possible
     * US-storefront link, where external purchase links are allowed.
     */
    External,

    /**
     * The current plan and usage, natively ([SettingsBillingViewOnlyPage]): no upgrade, plan
     * choice, portal or payment entry and no browser link, so no call to action leads to an
     * external purchase (App Store guideline 3.1.1).
     */
    ViewOnly,
}

/** The one switch for iOS Billing (artin 11-02: view-only). */
internal fun iosBillingPresentation(): IosBillingPresentation = IosBillingPresentation.ViewOnly

/** Where Settings › Plan & Billing opens ([settingsBillingOpenTarget]). */
internal enum class SettingsBillingOpenTarget {
    /** The Settings WebView page (Android, the Compose desktop app). */
    InAppWebView,

    /** The public web billing page in the system browser (iOS [IosBillingPresentation.External]). */
    ExternalBrowser,

    /** The native view-only page (iOS [IosBillingPresentation.ViewOnly]). */
    ViewOnly,
}

internal fun settingsBillingOpenTarget(
    appStoreBillingRules: Boolean = raftPlatformFollowsAppStoreBillingRules(),
    iosPresentation: IosBillingPresentation = iosBillingPresentation(),
): SettingsBillingOpenTarget =
    when {
        !appStoreBillingRules -> SettingsBillingOpenTarget.InAppWebView
        iosPresentation == IosBillingPresentation.External -> SettingsBillingOpenTarget.ExternalBrowser
        else -> SettingsBillingOpenTarget.ViewOnly
    }

/**
 * Whether the app may lead to buying a plan: Billing's upgrade entries (the Pro upgrade prompt's
 * Upgrade, the plan-limit banner's View Billing). Not in iOS view-only Billing, where those
 * prompts show their message only.
 */
internal fun settingsBillingOffersUpgrade(target: SettingsBillingOpenTarget = settingsBillingOpenTarget()): Boolean =
    target != SettingsBillingOpenTarget.ViewOnly

/** Whether a Billing entry leaves Raft (raft-ui 0.26 #758: a trailing ↗ on it). */
internal fun settingsBillingLeavesRaft(target: SettingsBillingOpenTarget = settingsBillingOpenTarget()): Boolean =
    target == SettingsBillingOpenTarget.ExternalBrowser

// Opened from the Settings "Workspace" section through the single production
// Settings WebView page. Rendering inside the retained multi-tab child pager
// previously caused the header/WebView attach-order flash (task #227).
// iOS (artin 11-02, App Store review): never the in-app WebView. The same Billing route draws the
// native view-only page there (SettingsWebViewScreen), so the route, the wide pane and restored
// routes stay as they are.
internal fun Pager.openSettingsBillingPage(storeScope: RaftShellStoreScope) {
    when (settingsBillingOpenTarget()) {
        SettingsBillingOpenTarget.ExternalBrowser -> openSettingsBillingInBrowser(storeScope)
        SettingsBillingOpenTarget.InAppWebView,
        SettingsBillingOpenTarget.ViewOnly -> openSettingsWebViewPage(SettingsWebViewSurface.Billing, storeScope)
    }
}

// Bridge calls go on the main thread, where CMP composes (RaftModuleCompat).
private val settingsBillingBrowserScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * Opens the public web billing page ([settingsBillingExternalWebUrl]) in the system browser: the
 * host's plain `openUrl` (iOS `UIApplication.openURL`, so Safari, not an in-app Safari sheet; the
 * app's Universal Links claim only channel / dm permalinks and /download, not this path). No
 * session goes with it: the user signs in on the web if needed.
 */
internal fun Pager.openSettingsBillingInBrowser(storeScope: RaftShellStoreScope) {
    val bridge = Utils.bridgeModule(pagerId)
    val sharedStores = RaftSharedStoreRegistry.get(bridge)
    // The Developer environment's web URL, the same source the Billing WebView reads.
    val webBaseUrl = standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)
    val authStore = sharedStores.authStore(storeScope)
    settingsBillingBrowserScope.launch {
        val session = runCatchingPreservingCancellation { authStore.readSession() }.getOrNull()
        val url = settingsBillingExternalWebUrl(webBaseUrl, session, readStandaloneSettingsLocaleTag())
        bridge.log("settings_billing_open_external_browser")
        bridge.hostCapabilities.openUrl(url, null)
    }
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
    return sharedSettingsHostShellWebUrl(
        webUrl = webBaseUrl,
        path = settingsBillingWebPath(session),
        extraQueryParameters = listOf("lang" to localeTag)
    )
}

private fun settingsBillingWebPath(session: StoredAuthSession?): String =
    standaloneSettingsServerSlug(session)
        ?.takeIf { it.isNotBlank() }
        ?.let { "s/$it/settings/billing" }
        ?: "settings/billing"

/**
 * The plain public web billing page for the system browser: the same path and `lang` as the
 * WebView page, without the host-shell embed query.
 */
internal fun settingsBillingExternalWebUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?,
    localeTag: String = AppLocale.Default.languageTag
): String = "${sharedSettingsWebUrl(webBaseUrl, settingsBillingWebPath(session))}?lang=$localeTag"

/**
 * iOS: Plan & Billing as a page (the wide Settings pane, or a restored / deep-linked Billing
 * route) without the WebView: the header, one line and a button to the system browser.
 */
@Composable
internal fun SettingsBillingExternalPage(pager: Pager) {
    val wideDetail = LocalRaftSettingsWideDetail.current
    val wideDetailClose = LocalRaftSettingsWideDetailClose.current
    val storeScope = pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    fun close() = settingsPageClose(wideDetailClose) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
    RaftBackHandler(enabled = !wideDetail, label = "settings_billing_external", blocksBackSwipe = false, onBack = ::close)
    Column(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        RaftTopPageHeader(
            title = stringResource(Res.string.settings_billing_title),
            iconName = RaftLucideIconName.CreditCard,
            backgroundColor = RaftColor.SurfaceWhite,
            applyTopSafeArea = !wideDetail,
            onBack = if (wideDetail) null else ::close,
        )
        SettingsBillingExternalPane(onOpenInBrowser = { pager.openSettingsBillingInBrowser(storeScope) })
    }
}

@Composable
internal fun SettingsBillingExternalPane(onOpenInBrowser: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RaftText(
            text = stringResource(Res.string.settings_billing_managed_on_web),
            style = RaftTheme.typography.body,
            color = RaftTheme.colors.foregroundMuted,
        )
        RaftButton(
            text = stringResource(Res.string.settings_billing_open_in_browser),
            onClick = onOpenInBrowser,
            variant = RaftButtonVariant.Primary,
            size = RaftButtonSize.Lg,
            // raft-ui 0.26 (#758): the browser leaves Raft: ↗ in the trailing icon's place.
            trailingIcon = { RaftContentLucideIcon(RaftLucideIconName.ArrowUpRight, size = 18.dp) },
        )
    }
}

internal fun settingsBillingWebViewInjectedJavaScript(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding? = null,
): String {
    return standaloneSettingsWebViewInjectedJavaScript(session, hostBinding, SettingsWebViewSurface.Billing)
}

