package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftMobileNavServerPopoverItem
import build.raft.designsystem.components.rememberRaftMobileNavServerPopoverState
import androidx.compose.ui.input.key.key
import build.raft.app.base.Utils
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.NotificationPreferenceSnapshot
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsHomeSnapshot
import build.raft.app.data.SharedSettingsIntent
import build.raft.app.data.SharedSettingsRoute
import build.raft.app.data.SharedSettingsState
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.toSettingsMobileSelectionSnapshot
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.isRefreshing
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.PlatformFile
import build.raft.app.data.SettingsHandsFeedbackSubmission
import build.raft.app.data.SettingsHandsFeedbackDraftSnapshot
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileSelectionOptionSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.data.SettingsPlanUsageSnapshot
import build.raft.app.data.SettingsLanguageRegionSnapshot
import build.raft.app.data.TranslationTargetLanguages
import build.raft.app.data.SettingsServerAdminSnapshot
import build.raft.app.data.SettingsServerAgreementSnapshot
import build.raft.app.data.SettingsServerInviteSnapshot
import build.raft.app.data.SettingsServerJoinLinkSnapshot
import build.raft.app.data.SettingsServerOnboardingAgentSnapshot
import build.raft.app.data.SettingsServerOnboardingSnapshot
import build.raft.app.data.SettingsServerSectionSnapshot
import build.raft.app.data.SettingsServerSnapshot
import build.raft.app.data.SettingsServerTranslationSnapshot
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.RaftTimeContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import build.raft.app.data.map

private const val SettingsDestinationHome = "home"

private const val SettingsRouteVisualDestinationKey = "destination"
private const val SettingsRouteVisualFixtureKey = "fixtureKey"
private const val SettingsRouteVisualItemIdKey = "itemId"
private const val SettingsRouteVisualMachineIdKey = "machineId"
internal val SettingsRouteVisualPageDataFields =
    setOf(
        SettingsRouteVisualDestinationKey,
        SettingsRouteVisualFixtureKey,
        SettingsRouteVisualItemIdKey,
        SettingsRouteVisualMachineIdKey,
        "fullHeight",
        "slockVisualFullHeight"
    )

internal val settingsTabBarSmokePayload = RaftSettingsHomePayload(
    title = "Settings",
    subtitle = "Tab bar smoke",
    items = listOf(
        SettingsHomePageLinkPayload(
            key = "account",
            title = "Account",
            subtitle = "Profile, email, password, connected accounts",
            iconName = RaftLucideIconName.User,
            value = "Verified",
        ),
        SettingsHomePageLinkPayload(
            key = "computers",
            title = "Computers",
            subtitle = "Connected machines and workspaces",
            iconName = RaftLucideIconName.Monitor
        ),
        SettingsHomePageLinkPayload(
            key = "server",
            title = "Server",
            subtitle = "Workspace name, members, invites, permissions",
            iconName = RaftLucideIconName.Users,
            value = "Owner"
        ),
        SettingsHomePageLinkPayload(
            key = "billing",
            title = "Billing",
            subtitle = "Plan, usage, invoices",
            iconName = RaftLucideIconName.ShieldCheck
        ),
        SettingsHomePageLinkPayload(
            key = "language-region",
            title = "Language & Region",
            subtitle = "Language, timezone, translation",
            iconName = RaftLucideIconName.Languages
        ),
        SettingsHomePageLinkPayload(
            key = "storage",
            title = "Storage",
            subtitle = "Local cache and diagnostics",
            iconName = RaftLucideIconName.Archive
        ),
        SettingsHomePageLinkPayload(
            key = "about",
            title = "About",
            subtitle = "Version and build information",
            iconName = RaftLucideIconName.BadgeInfo
        ),
        SettingsHomePageLinkPayload(
            key = SettingsDestinationHandsFeedback,
            title = "Send feedback",
            subtitle = "Report a problem or suggestion",
            iconName = RaftLucideIconName.MessageSquarePlus
        ),
        SettingsHomePageLinkPayload(
            key = SettingsDestinationReleaseNotes,
            title = "Release Notes",
            subtitle = "Product changes and migration notes",
            iconName = RaftLucideIconName.FileText
        ),
        SettingsHomePageLinkPayload(
            key = "router",
            title = "Smoke previews",
            subtitle = "Open Kuikly router and shared test pages",
            iconName = RaftLucideIconName.Route
        )
    )
)

private val settingsVisualItems = listOf(
    RaftTabItemPayload(
        key = "account",
        title = "Account",
        subtitle = "Profile, email, password, connected accounts",
        iconName = RaftLucideIconName.User,
        value = "Verified",
        selected = true
    ),
    RaftTabItemPayload(
        key = "language-region",
        title = "Language & Region",
        subtitle = "Language, timezone, translation",
        iconName = RaftLucideIconName.Languages
    ),
    RaftTabItemPayload(
        key = "appearance",
        title = "Appearance",
        subtitle = "Message font size and reading density",
        iconName = RaftLucideIconName.Type
    ),
    RaftTabItemPayload(
        key = "browser",
        title = "Device",
        subtitle = "Notifications and local device state",
        iconName = RaftLucideIconName.AppWindow
    ),
    RaftTabItemPayload(
        key = "server",
        title = "Server",
        subtitle = "Workspace, members, invites, permissions",
        iconName = RaftLucideIconName.Building2,
        value = "Owner"
    ),
    RaftTabItemPayload(
        key = "computers",
        title = "Computers",
        subtitle = "Connected machines and workspaces",
        iconName = RaftLucideIconName.Monitor
    ),
    RaftTabItemPayload(
        key = SettingsDestinationHandsFeedback,
        title = "Send feedback",
        subtitle = "Report a problem or suggestion",
        iconName = RaftLucideIconName.MessageSquarePlus
    ),
    RaftTabItemPayload(
        key = "release-notes",
        title = "Release Notes",
        subtitle = "Product changes and migration notes",
        iconName = RaftLucideIconName.FileText
    )
)

internal data class SettingsVisualFixtureValues(
    val serverId: String = "visual-server",
    val serverName: String = "Visual Server",
    val serverSlug: String = "visual",
    val serverPlan: String = "free",
    val ownerId: String = "visual-user",
    val ownerMemberId: String = "visual-user",
    val ownerName: String = "artin",
    val ownerDisplayName: String = "artin",
    val ownerHandle: String = "@artin",
    val ownerEmail: String = "artin@slock.ai",
    val ownerAvatarLabel: String = "AR",
    val ownerRole: String = "owner",
    val ownerRoleLabel: String = "Owner",
    val ownerDescription: String = "Owner",
    val ownerEmailVerified: Boolean = true,
    val designerEmail: String = "designer@slock.ai",
    val memberJoinedAtIso: String = "2026-06-18T00:00:00.000Z",
    val entityCreatedAtIso: String = "2026-06-18T00:00:00.000Z",
    val recentActivityAtIso: String = "2026-06-19T12:24:00.000Z"
) {
    val ownerHandleBare: String get() = ownerHandle.removePrefix("@")
    val ownerAdminDetail: String get() = "$ownerRoleLabel · $ownerEmail"
}

internal object SettingsVisualFixtureBridge {
    var values: SettingsVisualFixtureValues = SettingsVisualFixtureValues()
        private set

    fun install(values: SettingsVisualFixtureValues) {
        this.values = values
    }
}

internal val settingsVisualFixtureValues: SettingsVisualFixtureValues
    get() = SettingsVisualFixtureBridge.values

private val bottomTabBarSmokeItems = listOf(
    RaftBottomTabItemPayload("home", "Home", RaftLucideIconName.House, selected = true),
    RaftBottomTabItemPayload("activity", "Activity", RaftLucideIconName.Activity),
    RaftBottomTabItemPayload("tasks", "Tasks", RaftLucideIconName.ListTodo),
    RaftBottomTabItemPayload(AppShellSearchTabKey, "Search", RaftLucideIconName.Search),
    RaftBottomTabItemPayload("more", "More", RaftLucideIconName.Ellipsis)
)

/** Route-visual fixture compatibility for surfaces now owned by dedicated Pagers. */
private fun String.toVisualFixtureSharedSettingsDestination(): SharedSettingsDestination? =
    when (this) {
        SettingsDestinationAccount -> SharedSettingsDestination.Account
        SettingsDestinationComputers -> SharedSettingsDestination.Computers
        SettingsDestinationAdministration -> SharedSettingsDestination.Administration
        SettingsDestinationConnectedApps -> SharedSettingsDestination.ConnectedApps
        SettingsDestinationBilling -> SharedSettingsDestination.Billing
        SettingsDestinationStorage -> SharedSettingsDestination.Storage
        SettingsDestinationHome -> SharedSettingsDestination.Home
        else -> null
    }

@Composable
internal fun BottomTabBarSmokeContent() {
    val shellScope = rememberRaftAppShellScope()
    var selectedTab by remember { mutableStateOf("tasks") }
    val smokeItems = bottomTabBarSmokeItems.map { item -> item.copy(selected = item.key == selectedTab) }
    val onSmokeSelect: (String) -> Unit = { selectedTab = it }
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Bottom TabBar",
                    style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Shared tab bar smoke preview",
                    style = RaftType.Input.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    smokeItems.forEach { item ->
                        Text(
                            text = item.label,
                            style = RaftType.Input.copy(color = RaftColor.TextMutedGray),
                            color = RaftColor.TextMutedGray,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "Edge",
                style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            RaftBottomTabBar(items = smokeItems, onSelect = onSmokeSelect, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Navigation Rail",
                style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                RaftNavigationRailTabBar(items = smokeItems, onSelect = onSmokeSelect)
                Column(
                    modifier = Modifier.weight(1f).fillMaxSize().background(RaftColor.AppBgLightBlue).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Rail layout",
                        style = RaftType.PageTitle.copy(color = RaftColor.TextBlack),
                        color = RaftColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Shared TabBar variant parity with Android",
                        style = RaftType.Input.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        RaftAppBottomTabBarHost(
            items = smokeItems,
            onSelect = onSmokeSelect,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            bottomSafeArea = shellScope.bottomSafeArea
        )
    }
}

@Composable
internal fun BottomTabBarVisualContent() {
    // The bar is measured at full page width (390x58dp, approved mockup A), so
    // render the bar flush at the window origin at exactly 390dp — explicit width
    // rather than fillMaxWidth because the capture window can be wider than 390dp
    // (task #370).
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        // The fifth tab is the current server, as on the web: fixtureData homeChannelList.serverName.
        val serverState = rememberRaftMobileNavServerPopoverState()
        RaftBottomTabBar(
            items = bottomTabBarSmokeItems,
            onSelect = {},
            modifier = Modifier.width(390.dp),
            serverTab = { tabModifier ->
                val server = RaftServerSwitcherItem(id = "visual-server", name = "Raft Design", slug = "raft-design")
                RaftMobileNavServerPopoverItem(
                    label = server.name,
                    state = serverState,
                    avatar = { size -> ServerSwitcherAvatarTile(server, size = size) },
                    modifier = tabModifier,
                )
            },
        )
    }
}

@Composable
internal fun SettingsRowVisualContent() {
    Column(
        modifier =
            Modifier
                .width(342.dp)
                .background(RaftColor.SurfaceCream)
                .padding(top = 48.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        RaftVerticalTabBar(
            items = settingsVisualItems,
            showSelection = true,
            onSelect = {}
        )
    }
}

@Composable
internal fun SettingsPageVisualContent(
    // PROTOTYPE (native nav): a pushed page with its safe area and a working Back.
    protoVariant: String? = null,
    protoOnBack: (() -> Unit)? = null,
) {
    val pager = LocalActivity.current.getPager() as Pager
    val variant =
        remember(pager.pageData.params.toString()) {
            protoVariant ?: pager.pageData.params.optString("variant", "account").ifBlank { "account" }
        }
    if (variant == "server-danger-modal") {
        SettingsServerDangerModalVisualContent()
        return
    }
    val spec = settingsVisualSpec(variant)
    // React baselines render settings pages flush to the viewport top; no host
    // status-bar spacer or safe-area padding may leak into fixture captures.
    // web: the SettingsPanel sits in a 390x844 `data-visual-case` box, the full viewport.
    Box(
        modifier =
            Modifier
                .then(if (protoOnBack != null) Modifier.fillMaxSize() else Modifier.width(390.dp))
                .background(RaftColor.SurfaceWhite)
    ) {
        SettingsDetailShell(
            title = spec.title,
            subtitle = settingsVisualHeaderSubtitle(spec.variant),
            iconName = spec.iconName,
            applyTopSafeArea = protoOnBack != null,
            onBack = protoOnBack ?: {}
        ) {
            SettingsVisualPageBody(spec)
        }
    }
}

private data class SettingsVisualSpec(
    val variant: String,
    val title: String,
    val subtitle: String,
    val iconName: RaftLucideIconName
)

internal fun settingsVisualHeaderSubtitle(variant: String): String = ""

private fun settingsVisualSpec(variant: String): SettingsVisualSpec =
    when (variant) {
        "server-profile" ->
            SettingsVisualSpec(variant, "Server Profile", "Workspace profile, role, and slug", RaftLucideIconName.Building2)
        "server-danger" ->
            SettingsVisualSpec(variant, "Server Danger", "Leave or delete this workspace", RaftLucideIconName.TriangleAlert)
        "server-danger-modal" ->
            SettingsVisualSpec(variant, "Server Profile", "Workspace profile, role, and slug", RaftLucideIconName.Building2)
        "language-region" ->
            SettingsVisualSpec(variant, "Language & Region", "Language, timezone, and translation", RaftLucideIconName.Languages)
        "appearance" ->
            SettingsVisualSpec(variant, "Appearance", "Message font size and reading density", RaftLucideIconName.Type)
        "notifications" ->
            SettingsVisualSpec(variant, "Notifications", "Push notification preferences", RaftLucideIconName.Bell)
        "account-provider-linked",
        "account-password-expanded",
        "account-error" ->
            SettingsVisualSpec(variant, "Account", "Profile, email, password, connected accounts", RaftLucideIconName.User)
        else ->
            SettingsVisualSpec("account", "Account", "Profile, email, password, connected accounts", RaftLucideIconName.User)
    }

@Composable
private fun SettingsVisualPageBody(spec: SettingsVisualSpec) {
    when (spec.variant) {
        "server-profile",
        "server-danger",
        "server-danger-modal" -> SettingsVisualServerFocusedContent(spec.variant)
        "language-region" -> SettingsVisualLanguageRegionContent()
        "appearance" -> SettingsVisualAppearanceContent()
        "notifications" -> SettingsVisualNotificationsContent()
        else -> SettingsVisualAccountContent(spec.variant)
    }
}

@Composable
private fun SettingsVisualAccountContent(variant: String = "account") {
    val fixture = settingsVisualFixtureValues
    val connectedAccounts =
        when (variant) {
            "account-provider-linked" ->
                listOf(
                    RaftSettingsConnectedAccount("password", "Email password", fixture.ownerEmail, "connected", canConnect = false),
                    RaftSettingsConnectedAccount("google", "Google", fixture.ownerEmail, "connected", canConnect = false),
                    RaftSettingsConnectedAccount("github", "GitHub", "Not connected", "notConnected", canConnect = true)
                )
            // web react-provider stub: `/auth/identities` = [google <fxOwner.email>], GitHub
            // offered but not linked (account page and its error state alike).
            else ->
                listOf(
                    RaftSettingsConnectedAccount("password", "Email password", fixture.ownerEmail, "connected", canConnect = false),
                    RaftSettingsConnectedAccount("google", "Google", fixture.ownerEmail, "connected", canConnect = false),
                    RaftSettingsConnectedAccount("github", "GitHub", "Not connected", "notConnected", canConnect = true)
                )
        }
    SettingsAccountContent(
        payload =
            settingsAccountSmokePayload.copy(
                instanceId = "visual-settings-account-$variant",
                profile =
                    settingsAccountSmokePayload.profile.copy(
                        displayName = fixture.ownerDisplayName,
                        handle = fixture.ownerHandleBare,
                        email = fixture.ownerEmail,
                        verified = fixture.ownerEmailVerified,
                        roleLabel = fixture.ownerRoleLabel
                    ),
                connectedAccounts = connectedAccounts,
                passwordExpanded = variant == "account-password-expanded",
                // web components.settings.account.error-state surfaces only the avatar upload
                // failure (`accountInitialError`); no save, password or provider error.
                avatarErrorCode = "upload_failed".takeIf { variant == "account-error" },
                // web SettingsPanel account tab: UsageDataSettingsCard, on by default.
                usageDataVisible = true,
                usageDataSharing = true
            ),
        applyTopSafeArea = false,
        onPasswordChange = { _, _ -> },
        onEvent = { _, _ -> }
    )
}

@Composable
private fun SettingsVisualServerFocusedContent(variant: String) {
    val snapshot = remember { settingsVisualServerSnapshot() }
    var profileName by remember { mutableStateOf(snapshot.profile.name) }
    val serverPayload =
        ScreenLoadState.Content(snapshot).toServerPayload(
            session = homeSearchVisualSession,
            refreshing = false,
            actionState = null,
            timeContext = null
        )
    // Use the real production SharedSettingsServerContent so visual testing
    // always mirrors production — no duplicated card call sites.
    SharedSettingsServerContent(
        payload = serverPayload,
        profileName = profileName,
        onProfileNameChange = { profileName = it },
        serverSnapshot = snapshot,
        serverRole = "owner",
        expectedServerSlug = snapshot.profile.slug,
        actionState = null,
        bottomContentPadding = RaftAppSpacing.Lg,
        onAction = { _, _ -> }
    )
}

@Composable
private fun SettingsServerDangerModalVisualContent() {
    val snapshot = remember { settingsVisualServerSnapshot() }
    var modalSlug by remember { mutableStateOf("") }
    val serverSlug = snapshot.profile.slug
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        // web opens the dialog over the real Server Profile tab of SettingsPanel, so the
        // backdrop is the same production page components.settings.server.profile captures.
        val spec = settingsVisualSpec("server-profile")
        Box(modifier = Modifier.width(390.dp).fillMaxHeight().background(RaftColor.SurfaceWhite)) {
            SettingsDetailShell(
                title = spec.title,
                subtitle = settingsVisualHeaderSubtitle(spec.variant),
                iconName = spec.iconName,
                applyTopSafeArea = false,
                onBack = {}
            ) {
                SettingsVisualPageBody(spec)
            }
        }
        SettingsServerDeleteConfirmationDialog(
            serverName = snapshot.profile.name,
            expectedServerSlug = serverSlug,
            confirmationSlug = modalSlug,
            deleteBusy = false,
            deleteEnabled = settingsServerDeleteEnabled(canDelete = true, deleteBusy = false, expectedServerSlug = serverSlug, typedSlug = modalSlug),
            errorMessage = null,
            onDismiss = {},
            onConfirmationSlugChange = { modalSlug = it },
            onCancel = {},
            onDelete = {}
        )
    }
}

@Composable
private fun SettingsVisualLanguageRegionContent() {
    val snapshot = settingsVisualLanguageRegionSnapshot()
    SettingsLanguageRegionContent(
        state = ScreenLoadState.Content(snapshot),
        mobileState = ScreenLoadState.Content(settingsVisualMobileSnapshot()),
        actionState = null,
        pick = null,
        bottomContentPadding = RaftAppSpacing.Lg,
        onPick = {},
        onServerTranslationChange = {},
        onLocaleChange = {},
        onRetry = {}
    )
}

@Composable
private fun SettingsVisualAppearanceContent() {
    val snapshot = settingsVisualAppearanceSnapshot()
    SettingsAppearanceContent(
        state = ScreenLoadState.Content(snapshot),
        actionState = null,
        fontSizeDraft = snapshot.messageBodyFontSize.selectedKey,
        bottomContentPadding = RaftAppSpacing.Lg,
        onFontSizeDraftChange = {},
        onSave = {},
        onRetry = {},
        // The fixture has a current server, so light and dark is on the Theme page.
        showModeCard = false,
    )
}

@Composable
private fun SettingsVisualNotificationsContent() {
    SettingsNotificationsContent(
        state = ScreenLoadState.Content(settingsVisualMobileSnapshot()),
        actionState = null,
        bottomContentPadding = RaftAppSpacing.Lg,
        notificationPreferenceSnapshot = NotificationPreferenceSnapshot(enabled = true),
        preferenceBusy = false,
        foregroundKeepAliveBusy = false,
        foregroundMessageBannersBusy = false,
        onNotificationPreferenceChange = {},
        onForegroundKeepAlivePreferenceChange = {},
        onForegroundMessageBannersChange = {},
        onServerNotificationModeChange = {},
        onPushPreviewModeChange = {},
        onPermission = {},
        onSettings = {},
        onTest = {},
        onRetry = {},
        // The web case's account and zone (packages/visual-testing: a browser in Asia/Shanghai).
        nowEpochMillis = SETTINGS_VISUAL_NOTIFICATIONS_NOW_MILLIS,
        timeZoneId = "Asia/Shanghai",
        accountSettingsOverride =
            build.raft.app.data.RaftNotificationAccountSettings(
                quietHours = build.raft.app.data.RaftNotificationQuietHours(timeZone = "Asia/Shanghai"),
                mobileDelay = "inactive",
                version = 1
            )
    )
}

/** 2026-11-03T08:00:00Z: a fixed clock for the notifications fixture's pause choices. */
private const val SETTINGS_VISUAL_NOTIFICATIONS_NOW_MILLIS = 1_793_692_800_000L

private fun settingsVisualServerSnapshot(): SettingsServerSnapshot {
    val fixture = settingsVisualFixtureValues
    return SettingsServerSnapshot(
        profile =
            build.raft.app.data.SettingsServerProfileSnapshot(
                name = fixture.serverName,
                slug = fixture.serverSlug,
                hideHumansFromMembers = true,
                canSave = true
            ),
        sections =
            listOf(
                SettingsServerSectionSnapshot("admins", "Owners & Admins".desc(), "Control who can manage this workspace."),
                SettingsServerSectionSnapshot("pending_invites", "Pending Invites".desc(), "Invite humans by email and revoke pending invites."),
                SettingsServerSectionSnapshot("join_links", "Join Links".desc(), "Reusable links for onboarding larger teams."),
                SettingsServerSectionSnapshot("pre_join_agreement", "Pre-join Agreement".desc(), "Require humans to accept a workspace agreement before joining."),
                SettingsServerSectionSnapshot("onboarding_agent", "Onboarding".desc(), "Choose which agent greets new humans."),
                SettingsServerSectionSnapshot("member_permissions", "Member Permissions".desc(), "Limit what member-role users can see."),
                SettingsServerSectionSnapshot("translation", "Translation".desc(), "Enable server-level message translation."),
                SettingsServerSectionSnapshot("danger", "Danger Zone".desc(), "Leave or delete this workspace.")
            ),
        admins =
            listOf(
                SettingsServerAdminSnapshot(fixture.ownerId, fixture.ownerDisplayName, fixture.ownerAdminDetail, fixture.ownerRole)
            ),
        usageRows =
            listOf(
                SettingsPlanUsageSnapshot("Agents", "8 / 20"),
                SettingsPlanUsageSnapshot("Computers", "4 / 10")
            ),
        pendingInvites =
            listOf(
                SettingsServerInviteSnapshot("invite_1", fixture.designerEmail, fixture.ownerDisplayName, fixture.entityCreatedAtIso),
                SettingsServerInviteSnapshot("invite_2", "qa@slock.ai", "Cindy", fixture.recentActivityAtIso)
            ),
        joinLinks =
            listOf(
                SettingsServerJoinLinkSnapshot(
                    id = "link_1",
                    label = "Design review link",
                    url = "https://app.raft.build/join/design",
                    maxUsesLabel = "25 max",
                    usageLabel = "8 used",
                    expiresAtLabel = fixture.recentActivityAtIso
                )
            ),
        agreement =
            SettingsServerAgreementSnapshot(
                enabled = true,
                title = "Raft Design workspace agreement",
                bodyMarkdown = "Please keep feedback actionable and avoid sharing credentials in public channels.",
                statusLabel = "Required",
                detail = "New humans must accept this agreement before joining.",
                canSave = true
            ),
        onboarding =
            SettingsServerOnboardingSnapshot(
                setupModalReminderOptOut = false,
                onboardingAgentId = "agent_cindy",
                agentAllChannelGreetingEnabled = true,
                agents =
                    listOf(
                        SettingsServerOnboardingAgentSnapshot("agent_cindy", "cindy", "Cindy"),
                        SettingsServerOnboardingAgentSnapshot("agent_api", "api-integration", "API Integration")
                    ),
                statusLabel = "Cindy",
                detail = "Cindy greets new humans and explains the server.",
                canSave = true
            ),
        translation =
            SettingsServerTranslationSnapshot(
                translationEnabled = true,
                translationAvailable = true,
                canManageTranslation = true,
                statusLabel = "Enabled",
                detail = "Server messages can be translated on demand."
            )
    )
}

private fun settingsVisualLanguageRegionSnapshot(): SettingsLanguageRegionSnapshot =
    SettingsLanguageRegionSnapshot(
        serverTranslation =
            build.raft.app.data.SettingsMobileToggleSnapshot(
                enabled = true,
                available = true,
                statusLabel = "Enabled",
                detail = "Allow translation on messages in this server."
            ),
        autoTranslation =
            build.raft.app.data.SettingsMobileToggleSnapshot(
                enabled = false,
                available = true,
                statusLabel = "Manual",
                detail = "Only translate when requested."
            ),
        browserLanguage =
            settingsVisualSelection(
                selectedKey = "en",
                selectedLabel = "English",
                statusLabel = "English",
                detail = "Stored on this account.",
                options = TranslationTargetLanguages.options.map { it.key to it.label }
            ),
        timezone =
            settingsVisualSelection(
                selectedKey = "Asia/Shanghai",
                selectedLabel = "Asia/Shanghai",
                statusLabel = "GMT+8",
                detail = "Used when formatting timestamps."
            ),
        timeFormat =
            settingsVisualSelection(
                selectedKey = "24h",
                selectedLabel = "24-hour",
                statusLabel = "24h",
                detail = "Use 14:30 instead of 2:30 PM."
        )
    )

private fun settingsVisualAppearanceSnapshot(): SettingsAppearanceSnapshot =
    SettingsAppearanceSnapshot(
        messageBodyFontSize =
            settingsVisualSelection(
                selectedKey = "md",
                selectedLabel = "Medium",
                statusLabel = "Medium",
                detail = "Adjusts message body text only. Buttons, labels, sidebars, and code blocks keep fixed UI sizes.",
                options =
                    listOf(
                        "sm" to "Small",
                        "md" to "Medium",
                        "lg" to "Large"
                    )
            )
    )

private fun settingsVisualMobileSnapshot(): SettingsMobileSnapshot =
    SettingsMobileSnapshot(
        pushNotifications =
            build.raft.app.data.SettingsMobileToggleSnapshot(
                enabled = true,
                available = true,
                statusLabel = "On",
                detail = "Receive push notifications for mentions and assigned tasks."
            ),
        muteServer =
            build.raft.app.data.SettingsMobileToggleSnapshot(
                enabled = false,
                available = true,
                statusLabel = "Unmuted",
                detail = "This server can send push notifications."
            ),
        // Explicit: the snapshot default is the unknown (nothing selected) mode since #3795.
        serverNotificationMode =
            build.raft.app.data.ServerPushMode.All
                .toSettingsMobileSelectionSnapshot(available = true),
        pushPreviewMode =
            build.raft.app.data.PushPreviewMode.Full
                .toSettingsMobileSelectionSnapshot(available = true),
        sendTestEnabled = true,
        notificationPermission =
            build.raft.app.data.SettingsMobileActionSnapshot(
                available = true,
                statusLabel = "Allowed",
                detail = "System notification permission is granted."
            ),
        notificationSettings =
            build.raft.app.data.SettingsMobileActionSnapshot(
                available = true,
                statusLabel = "Open",
                detail = "Open OS notification settings."
            ),
        testNotification =
            build.raft.app.data.SettingsMobileActionSnapshot(
                available = true,
                statusLabel = "Ready",
                detail = "Send a test push notification."
            )
    )

private fun settingsVisualSelection(
    selectedKey: String,
    selectedLabel: String,
    statusLabel: String,
    detail: String,
    options: List<Pair<String, String>>? = null
): SettingsMobileSelectionSnapshot =
    SettingsMobileSelectionSnapshot(
        selectedKey = selectedKey,
        selectedLabel = selectedLabel,
        statusLabel = statusLabel,
        detail = detail,
        options =
            options?.map { SettingsMobileSelectionOptionSnapshot(it.first, it.second) }
                ?: listOf(
                    SettingsMobileSelectionOptionSnapshot(selectedKey, selectedLabel),
                    SettingsMobileSelectionOptionSnapshot("system", "System default")
                )
    )

internal val homeSearchVisualServer: ServerSummary
    get() {
        val fixture = settingsVisualFixtureValues
        return ServerSummary(
            id = ServerId(fixture.serverId),
            name = fixture.serverName,
            slug = fixture.serverSlug,
            role = fixture.ownerRole,
            plan = fixture.serverPlan,
            unreadCount = 24
        )
    }

internal val homeSearchVisualSession: StoredAuthSession
    get() {
        val fixture = settingsVisualFixtureValues
        return StoredAuthSession(
            user =
                StoredAuthUser(
                    id = fixture.ownerId,
                    email = fixture.ownerEmail,
                    name = fixture.ownerName,
                    displayName = fixture.ownerDisplayName,
                    emailVerified = fixture.ownerEmailVerified
                ),
            accessToken = "visual-token",
            refreshToken = "visual-refresh",
            selectedServerId = fixture.serverId,
            selectedServerSlug = fixture.serverSlug,
            servers =
                listOf(
                    StoredAuthServer(
                        id = fixture.serverId,
                        name = fixture.serverName,
                        slug = fixture.serverSlug,
                        role = fixture.ownerRole,
                        plan = fixture.serverPlan
                    )
                )
        )
    }

private val homeCreateChannelVisualMembers = listOf(
        MemberProfile(
            id = MemberId("agent-cindy"),
            displayName = "Cindy",
            handle = "Cindy",
            kind = MemberKind.Agent,
            avatarLabel = "CI",
            avatar = AvatarReference.Pixel("robot"),
            presence = Presence.Active,
            agentStatus = AgentStatus.Working
        ),
        MemberProfile(
            id = MemberId("agent-visual-qa"),
            displayName = "Visual QA",
            handle = "visual-qa",
            kind = MemberKind.Agent,
            avatarLabel = "VQ",
            avatar = AvatarReference.Pixel("eye"),
            presence = Presence.Offline,
            agentStatus = AgentStatus.Offline
        ),
        MemberProfile(
            id = MemberId("visual-human-designer"),
            displayName = "Designer",
            handle = "designer",
            kind = MemberKind.Human,
            avatarLabel = "DE"
        )
)

@Composable
internal fun HomeCreateChannelDialogVisualContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
    ) {
        SharedCreateChannelDialog(
            submitting = false,
            membersLoading = false,
            members = homeCreateChannelVisualMembers,
            errorMessage = null,
            initialName = "android-artifacts",
            initialDescription = textFieldValueAtEnd("Build reports, screenshots, release blockers"),
            initialVisibility = SharedCreateChannelVisibility.Public,
            initialSelectedMemberKeys = setOf(
                homeCreateChannelVisualMembers[0].memberPickerKey,
                homeCreateChannelVisualMembers[2].memberPickerKey
            ),
            fixtureStatusFallbackEnabled = true,
            onDismiss = {},
            onSubmit = { _, _, _, _, _ -> }
        )
    }
}

@Composable
internal fun SettingsRouteVisualScreen() {
    RememberKmmResourceLocaleSeedFromLocalActivity()
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val storeScope =
        remember(pager.pageData.params.toString(), pager.pagerId) {
            pager.pageData.params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
        }
    val route =
        remember(pager.pageData.params.toString()) {
            parseSettingsRouteVisualPayload(pager.pageData.params)
        }
    val session = remember(route.fixtureKey) { settingsRouteVisualSession() }
    val state =
        remember(route.destination, route.itemId, route.fixtureKey) {
            settingsRouteVisualState(route)
        }
    val handsFeedbackVisualFixture =
        remember(route.fixtureKey) {
            settingsHandsFeedbackVisualFixture(route.fixtureKey)
        }
    val copiedMessage = stringResource(Res.string.common_copied)
    val settingsCapabilities =
        remember(bridge, handsFeedbackVisualFixture, copiedMessage) {
            BridgeSettingsPlatformCapabilities(
                bridge = bridge,
                copiedMessage = copiedMessage,
                feedbackVisualSubmission = handsFeedbackVisualFixture?.let {
                    SettingsHandsFeedbackSubmission(
                        ticketId = "visual-feedback-ticket-139",
                        shareText = "Hands feedback visual-139"
                    )
                }
            )
        }
    val eventBus = remember(bridge) { RaftSharedStoreRegistry.get(bridge).eventBus }
    val sessionStore = remember(session) { InMemoryRaftAuthSessionStore(session) }
    val authDataSource =
        remember(sessionStore, session) {
            SharedAuthDataSource(
                api = AuthChooseServerVisualApi(session.user, session.servers),
                sessionStore = sessionStore,
                diagnosticsLogger = { message -> bridge.log("settings_route_visual_auth $message") }
            )
        }
    LaunchedEffect(route.destination, route.itemId, route.fixtureKey, route.fullHeight) {
        bridge.log(
            "settings_route_visual_render destination=${route.destination} " +
                "fixtureKey=${route.fixtureKey} itemId=${route.itemId.orEmpty()} fullHeight=${route.fullHeight}"
        )
        bridge.log(
            "settings_route_visual_ready destination=${route.destination} " +
                "fixtureKey=${route.fixtureKey}"
        )
    }

    // The web case's frozen clock: the Appearance preview message is sent at 20:30. Its device
    // switches are a fresh browser's (nothing saved, no app-only rows), not this device's.
    CompositionLocalProvider(
        LocalSettingsAppearancePreviewTimeLabel provides "20:30",
        LocalSettingsAppearanceVisualSwitches provides
            SettingsAppearanceVisualSwitches(
                showLiveAgentActivity = true,
                showAgentModelName = true,
                showSenderDescription = true,
                hideEmptySidebarSections = false,
                showAppOnlyRows = false,
            ),
    ) {
        if (route.destination == SettingsDestinationHome) {
            // On a phone Settings is a page pushed from the server panel (raft-design #712), so it
            // has Back, as the web's phone Settings does.
            SettingsHomeContent(
                payload = appShellSettingsHomePayload(session, serverPanelPages = true),
                onBack = {},
                onSelect = {},
                // The app's Settings home has Search settings on top (SettingsHomePage), as the
                // web's has: an empty field, nothing searched.
                search =
                    SettingsSearchHost(
                        state = rememberSettingsSearchState(null),
                        context =
                            RaftSettingsSearchContext(
                                rowKeys = emptyList(),
                                role = session.currentServerSummary()?.role,
                                publicServer = false,
                                serverSso = false,
                                followedThreadReplies = false,
                            ),
                        serverName = session.currentServerSummary()?.name.orEmpty(),
                        active = true,
                        onOpen = {},
                    ),
            )
        } else {
            SettingsRouteVisualFixtureDetailContent(
                pager = pager,
                eventBus = eventBus,
                session = session,
                authDataSource = authDataSource,
                settingsCapabilities = settingsCapabilities,
                storeScope = storeScope,
                handsFeedbackVisualFixture = handsFeedbackVisualFixture,
                settingsState = state,
                notificationPreferenceSnapshot = NotificationPreferenceSnapshot(enabled = true),
                onSettingsIntent = {},
                route = route,
                onBack = {},
                bottomContentPadding = RaftAppSpacing.Lg,
                timeContext = null,
                onLogout = {},
            )
        }
    }
}

/**
 * Screenshot-only adapter for the route visual harness. Production navigation never calls this
 * function: native detail pages receive one immutable page route, while dedicated Pager fixtures
 * below render their own content without exposing any destination-changing callback.
 */
@Composable
private fun SettingsRouteVisualFixtureDetailContent(
    pager: Pager,
    eventBus: RaftEventBus,
    session: StoredAuthSession,
    authDataSource: SharedAuthDataSource,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    storeScope: RaftShellStoreScope,
    handsFeedbackVisualFixture: SettingsHandsFeedbackVisualFixture?,
    settingsState: SharedSettingsState,
    notificationPreferenceSnapshot: NotificationPreferenceSnapshot,
    onSettingsIntent: (SharedSettingsIntent) -> Unit,
    route: SettingsRouteVisualPayload,
    onBack: () -> Unit,
    bottomContentPadding: Dp,
    timeContext: RaftTimeContext?,
    onLogout: () -> Unit,
) {
    val nativePage = SettingsNativeDetailPage.fromKey(route.destination)
    if (nativePage != null) {
        val nativeRoute =
            SettingsNativeDetailRoute.create(
                page = nativePage,
                itemId = route.itemId,
            )
        if (nativeRoute == null) {
            SettingsSharedPlaceholder(
                title = "Settings",
                detail = "This visual fixture route is invalid.",
                iconName = RaftLucideIconName.Settings,
                bottomContentPadding = bottomContentPadding,
            )
            return
        }
        SettingsNativeDetailPageContent(
            session = session,
            authDataSource = authDataSource,
            settingsCapabilities = settingsCapabilities,
            settingsState = settingsState,
            notificationPreferenceSnapshot = notificationPreferenceSnapshot,
            onSettingsIntent = onSettingsIntent,
            route = nativeRoute,
            onBack = onBack,
            onOpenRouterPage = {},
            onOpenAdministrationPage = {},
            onOpenHandsFeedbackPage = {},
            onOpenSourceLicensesPage = {},
            onOpenApiEnvironmentPage = {},
            onOpenDiagnosticsPage = {},
            onOpenLocalFeatureOverridesPage = {},
            onOpenLocalFeatureOverridePage = {},
            onMessageAlignmentSelected = {},
            onCompactConsecutiveMessagesSelected = {},
            onForegroundMessageBannersSelected = {},
            onConversationForwardGestureSelected = {},
            onServerDrawerSelected = {},
                bottomContentPadding = bottomContentPadding,
            timeContext = timeContext,
            onLogout = onLogout,
        )
        return
    }

    SettingsDedicatedPagerVisualFixtureContent(
        pager = pager,
        eventBus = eventBus,
        session = session,
        settingsCapabilities = settingsCapabilities,
        storeScope = storeScope,
        handsFeedbackVisualFixture = handsFeedbackVisualFixture,
        settingsState = settingsState,
        route = route,
        onBack = onBack,
        bottomContentPadding = bottomContentPadding,
        timeContext = timeContext,
    )
}

@Composable
private fun SettingsDedicatedPagerVisualFixtureContent(
    pager: Pager,
    eventBus: RaftEventBus,
    session: StoredAuthSession,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    storeScope: RaftShellStoreScope,
    handsFeedbackVisualFixture: SettingsHandsFeedbackVisualFixture?,
    settingsState: SharedSettingsState,
    route: SettingsRouteVisualPayload,
    onBack: () -> Unit,
    bottomContentPadding: Dp,
    timeContext: RaftTimeContext?,
) {
    SettingsDetailShell(
        title = settingsDedicatedPagerVisualFixtureTitle(route.destination),
        subtitle = if (route.destination == SettingsDestinationHandsFeedback) settingsHandsFeedbackSubtitle() else "",
        iconName = settingsDedicatedPagerVisualFixtureIcon(route.destination),
        onBack = onBack,
        trailingContent = null,
    ) {
        when (route.destination) {
            SettingsDestinationAccount ->
                SettingsAccountContent(
                    settingsState.account.toAccountPayload(
                        session,
                        settingsState.actionState,
                        localizedErrorPresentationStrings(),
                    ),
                    avatarCacheIdentity =
                        settingsState.account.settingsAccountAvatarCacheIdentity(session),
                    applyTopSafeArea = false,
                    bottomContentPadding = bottomContentPadding,
                    // As the web case: Login & security read (two-step off, no passkeys, as its
                    // second-factor mock) and Delete account at the end.
                    accountDeletion = SettingsAccountDeletionUi(onOpen = {}),
                    loginSecurity =
                        SettingsAccountLoginSecurityUi(
                            state =
                                build.raft.app.data.SharedAccountLoginSecurityState(
                                    security = build.raft.app.data.AccountLoginSecurity(twoStepEnabled = false, passkeyCount = 0),
                                ),
                            onOpen = {},
                            onRetry = {},
                        ),
                    onPasswordChange = { _, _ -> },
                    onEvent = { _, _ -> },
                )
            SettingsDestinationStorage ->
                SettingsStorageContent(
                    settingsState.storage.toStoragePayload(
                        actionState = settingsState.actionState,
                        refreshing = settingsState.isRefreshing(SharedSettingsDestination.Storage),
                    ),
                    bottomContentPadding = bottomContentPadding,
                    onEvent = { _, _ -> },
                )
            SettingsDestinationHandsFeedback ->
                SharedHandsFeedbackContent(
                    pager = pager,
                    eventBus = eventBus,
                    settingsCapabilities = settingsCapabilities,
                    sourceStoreScope = storeScope,
                    accountId = session.user.id,
                    visualFixture = handsFeedbackVisualFixture,
                    bottomContentPadding = bottomContentPadding,
                )
            SettingsDestinationComputers,
            SettingsDestinationBilling,
            SettingsDestinationReleaseNotes -> SettingsComputersLoadingContent()
            else ->
                SettingsSharedPlaceholder(
                    title = "Settings",
                    detail = "This visual fixture route is unavailable.",
                    iconName = RaftLucideIconName.Settings,
                    bottomContentPadding = bottomContentPadding,
                )
        }
    }
}

@Composable
private fun settingsDedicatedPagerVisualFixtureTitle(destination: String): String =
    when (destination) {
        SettingsDestinationAccount -> stringResource(Res.string.settings_account_title)
        SettingsDestinationComputers -> stringResource(Res.string.settings_computers_title)
        SettingsDestinationAdministration -> stringResource(Res.string.settings_server_administration_title)
        SettingsDestinationConnectedApps -> stringResource(Res.string.settings_connected_apps_title)
        SettingsDestinationBilling -> stringResource(Res.string.settings_billing_title)
        SettingsDestinationStorage -> stringResource(Res.string.settings_storage_title)
        SettingsDestinationReleaseNotes -> stringResource(Res.string.settings_release_notes_title)
        SettingsDestinationHandsFeedback -> settingsHandsFeedbackTitle()
        else -> stringResource(Res.string.settings_title)
    }

private fun settingsDedicatedPagerVisualFixtureIcon(destination: String): RaftLucideIconName =
    when (destination) {
        SettingsDestinationAccount -> RaftLucideIconName.User
        SettingsDestinationComputers -> RaftLucideIconName.Monitor
        SettingsDestinationAdministration -> RaftLucideIconName.Shield
        SettingsDestinationConnectedApps -> RaftLucideIconName.Link2
        SettingsDestinationBilling -> RaftLucideIconName.CreditCard
        SettingsDestinationStorage -> RaftLucideIconName.Archive
        SettingsDestinationReleaseNotes -> RaftLucideIconName.FileText
        SettingsDestinationHandsFeedback -> RaftLucideIconName.MessageSquarePlus
        else -> RaftLucideIconName.Settings
    }

private fun settingsHandsFeedbackVisualFixture(fixtureKey: String): SettingsHandsFeedbackVisualFixture? {
    if (fixtureKey != "feedback-two-attachments" && fixtureKey != "feedback-submit-success") return null
    return SettingsHandsFeedbackVisualFixture(
        draft =
            SettingsHandsFeedbackDraftSnapshot(
                text = "The attachment row should be replaced by a square-corner User feedback row.",
                attachments =
                    listOf(
                        PlatformFile(
                            id = "visual-feedback-attachment-1",
                            fileName = "reuse-before.png",
                            mimeType = "image/png",
                            sizeBytes = 12_340,
                            fileRef = "visual://feedback/reuse-before.png",
                            source = "visual_fixture"
                        ),
                        PlatformFile(
                            id = "visual-feedback-attachment-2",
                            fileName = "reuse-after.log",
                            mimeType = "text/plain",
                            sizeBytes = 4_096,
                            fileRef = "visual://feedback/reuse-after.log",
                            source = "visual_fixture"
                        )
                    )
            ),
        autoSubmit = fixtureKey == "feedback-submit-success"
    )
}

internal fun settingsRouteVisualState(route: SettingsRouteVisualPayload): SharedSettingsState {
    val destination =
        SettingsNativeDetailPage.fromKey(route.destination)?.storeDestination
            ?: route.destination.toVisualFixtureSharedSettingsDestination()
            ?: SharedSettingsDestination.Administration
    return SharedSettingsState(
        route = SharedSettingsRoute(destination, route.itemId),
        home = ScreenLoadState.Content(SharedSettingsHomeSnapshot()),
        server =
            if (destination == SharedSettingsDestination.Administration || destination == SharedSettingsDestination.Server) {
                ScreenLoadState.Content(settingsVisualServerSnapshot())
            } else {
                ScreenLoadState.Loading
            },
        languageRegion =
            if (destination == SharedSettingsDestination.LanguageRegion) {
                ScreenLoadState.Content(settingsVisualLanguageRegionSnapshot())
            } else {
                ScreenLoadState.Loading
            },
        appearance =
            if (destination == SharedSettingsDestination.Appearance) {
                ScreenLoadState.Content(settingsVisualAppearanceSnapshot())
            } else {
                ScreenLoadState.Loading
            },
        mobile =
            if (
                destination == SharedSettingsDestination.Notifications ||
                    destination == SharedSettingsDestination.Appearance
            ) {
                ScreenLoadState.Content(settingsVisualMobileSnapshot())
            } else {
                ScreenLoadState.Loading
            }
    )
}

/**
 * Visual-only full-height support (task #388 A). The capture harness grows the
 * emulator display so the whole Settings route fits one frame; it needs (1) the
 * fullHeight capture flag readable at the list sites and (2) the content-bottom
 * pixel to crop the tall frame to. Both read capture-intent pageData params the
 * same way the safe-area overrides do (RaftAppSafeArea.kt); production pages never
 * set them, so this is inert outside compose_settings_route_visual captures.
 */
@Composable
internal fun settingsVisualFullHeightEnabled(): Boolean {
    val params = (LocalActivity.current.getPager() as Pager).pageData.params
    return params.optBoolean("fullHeight", params.optBoolean("slockVisualFullHeight", false))
}

@Composable
internal fun SettingsVisualFullHeightSentinel() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    var lastLoggedBottom by remember { mutableStateOf(-1) }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val bottom = (coordinates.positionInRoot().y + coordinates.size.height).toInt()
                if (bottom != lastLoggedBottom) {
                    lastLoggedBottom = bottom
                    bridge.log("settings_route_visual_fullheight contentBottomPx=$bottom")
                }
            }
    )
}

internal data class SettingsRouteVisualPayload(
    val destination: String,
    val itemId: String?,
    val fixtureKey: String,
    val fullHeight: Boolean
)

private fun parseSettingsRouteVisualPayload(params: build.raft.app.compat.json.JSONObject): SettingsRouteVisualPayload {
    val destination =
        params.optString(SettingsRouteVisualDestinationKey)
            .trim()
            .takeIf {
                SettingsNativeDetailPage.fromKey(it) != null ||
                    it.toVisualFixtureSharedSettingsDestination() != null ||
                    it == SettingsDestinationHandsFeedback
            }
            ?: SettingsDestinationAdministration
    val itemId =
        params.optString(SettingsRouteVisualItemIdKey)
            .trim()
            .ifBlank { params.optString(SettingsRouteVisualMachineIdKey).trim() }
            .takeIf { it.isNotBlank() }
    val fixtureKey =
        params.optString(SettingsRouteVisualFixtureKey)
            .trim()
            .takeIf { it.isNotBlank() }
            ?: "canonical"
    return SettingsRouteVisualPayload(
        destination = destination,
        itemId = itemId,
        fixtureKey = fixtureKey,
        fullHeight = params.optBoolean("fullHeight", params.optBoolean("slockVisualFullHeight", false))
    )
}

private fun settingsRouteVisualSession(): StoredAuthSession {
    val fixture = settingsVisualFixtureValues
    return StoredAuthSession(
        user =
            StoredAuthUser(
                id = fixture.ownerId,
                email = fixture.ownerEmail,
                name = fixture.ownerName,
                displayName = fixture.ownerDisplayName,
                emailVerified = fixture.ownerEmailVerified,
                preferredTimeFormat = "24h",
                timeFormat = "24h"
            ),
        accessToken = "visual-access-token",
        refreshToken = "visual-refresh-token",
        selectedServerId = fixture.serverId,
        selectedServerSlug = fixture.serverSlug,
        servers =
            listOf(
                StoredAuthServer(
                    id = fixture.serverId,
                    name = fixture.serverName,
                    slug = fixture.serverSlug,
                    role = fixture.ownerRole,
                    plan = fixture.serverPlan
                )
            )
    )
}
