package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.StoredAuthSession
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusVariant
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
internal data class RaftSettingsHomePayload(
    val instanceId: String = "",
    val title: String = "Settings",
    val subtitle: String = "",
    val items: List<SettingsHomePageLinkPayload> = emptyList()
)

@Serializable
internal data class SettingsHomePageLinkPayload(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val iconName: RaftLucideIconName = RaftLucideIconName.Settings,
    val value: String? = null,
    val section: String? = null,
    /** Attention dot on the row (the More tab's signals on the phone Settings root). */
    val attentionDot: Boolean = false,
    /** The row leaves Raft for another app: a trailing ↗ (raft-ui 0.26 #758). */
    val opensExternally: Boolean = false,
)

/**
 * The Settings page as the phone's pushed server page (raft-design #712, opened from the server
 * panel; app-shell tab key "more"): titled [title], the rows named in [attentionRowKeys] wear the
 * attention dot ([RaftMoreAttention.settingsRowKey]). The wide rail's Settings page has none.
 */
internal data class SettingsMoreRoot(
    val title: String,
    val attentionRowKeys: Set<String> = emptySet(),
)

/** The Settings home rows for [moreRoot]: attention rows get their dot; without one (the wide rail) unchanged. */
internal fun settingsHomeItemsForMoreRoot(
    items: List<SettingsHomePageLinkPayload>,
    moreRoot: SettingsMoreRoot?,
): List<SettingsHomePageLinkPayload> =
    moreRoot?.let { root -> items.map { it.copy(attentionDot = it.key in root.attentionRowKeys) } } ?: items

@Composable
internal fun SettingsAppShellHomeContent(
    session: StoredAuthSession?,
    bottomContentPadding: Dp,
    onSettingsHeaderClick: () -> Unit,
    onSelect: (String) -> Unit,
    moreRoot: SettingsMoreRoot? = null,
    scrollToTopRequest: Int = 0,
    onBack: (() -> Unit)? = null,
    /** The server's `slack_bridge_v0` flag: the IM Bridges row. */
    imBridgesEnabled: Boolean = false,
) {
    val payload = appShellSettingsHomePayload(session, imBridgesEnabled = imBridgesEnabled)
    SettingsHomeContent(
        payload =
            payload.copy(
                title = moreRoot?.title ?: payload.title,
                items = settingsHomeItemsForMoreRoot(payload.items, moreRoot),
            ),
        bottomContentPadding = bottomContentPadding,
        onHeaderClick = onSettingsHeaderClick,
        onBack = onBack,
        onSelect = onSelect,
        scrollToTopRequest = scrollToTopRequest,
    )
}

/**
 * The Settings home rows top to bottom, the web's settings groups (botiverse/slock
 * packages/web/src/components/layout/Sidebar.tsx:2257-2328, settingsNavigation.ts:29-45):
 * Personal; "On {server}", the member's own settings in the current server: Theme and the server's
 * Notifications (its level alone; the personal Notifications page shows it too); Workspace;
 * Resources, with Documentation leaving Raft for the docs site. The "On {server}" group is there
 * only with a current server ([hasThemeServer]), as is the web's. The app's own rows stay: Mobile
 * and Storage (this device), Applications and Computers (the web has them on the rail).
 * Mobile is a phone page: with [appPreferencesUnderAppearance] (the desktop app) its cards are part
 * of Appearance, the web's home for them, and there is no Mobile row. IM Bridges follows
 * Administration, as on the web (settingsNavigation.ts, Sidebar.tsx), only with [imBridges]: the
 * server's `slack_bridge_v0` flag (web `isSlackBridgeSurfaceEnabled`).
 */
internal fun settingsHomeRowKeys(
    hasThemeServer: Boolean,
    appPreferencesUnderAppearance: Boolean = settingsAppPreferencesUnderAppearance(),
    imBridges: Boolean = false,
    releaseNotes: Boolean = settingsReleaseNotesAvailable(),
): List<String> =
    listOfNotNull(
        SettingsDestinationAccount,
        SettingsNativeDetailPage.LanguageRegion.key,
        SettingsNativeDetailPage.Appearance.key,
        SettingsNativeDetailPage.Notifications.key,
        SettingsNativeDetailPage.Mobile.key.takeUnless { appPreferencesUnderAppearance },
        SettingsDestinationStorage,
        SettingsNativeDetailPage.Theme.key.takeIf { hasThemeServer },
        SettingsNativeDetailPage.ServerNotifications.key.takeIf { hasThemeServer },
        SettingsNativeDetailPage.Server.key,
        SettingsDestinationBilling,
        SettingsDestinationAdministration,
        SettingsDestinationImBridges.takeIf { imBridges },
        SettingsDestinationConnectedApps,
        SettingsDestinationComputers,
        SettingsNativeDetailPage.About.key,
        SettingsDestinationDocumentation,
        SettingsDestinationHandsFeedback,
        SettingsDestinationReleaseNotes.takeIf { releaseNotes },
    )

/**
 * Release Notes is the platform's Hands release history, so the row shows only where the platform
 * has a Hands app ([raftReleaseNotesAppSlug] not blank). The Compose desktop app has none yet.
 */
internal fun settingsReleaseNotesAvailable(appSlug: String = raftReleaseNotesAppSlug()): Boolean = appSlug.isNotBlank()

/**
 * Whether the app preferences (message alignment, compact messages, composer key, ...) sit under
 * Appearance, as on the web (packages/web SettingsPanel AppearanceSection), instead of a "Mobile"
 * page: on a fine pointer, the desktop app. Phones keep Mobile.
 */
internal fun settingsAppPreferencesUnderAppearance(finePointer: Boolean = raftPlatformHasFinePointer()): Boolean = finePointer

@Composable
internal fun appShellSettingsHomePayload(
    session: StoredAuthSession?,
    appPreferencesUnderAppearance: Boolean = settingsAppPreferencesUnderAppearance(),
    /** The server's `slack_bridge_v0` flag: the IM Bridges row ([settingsHomeRowKeys]). */
    imBridgesEnabled: Boolean = false,
    /** iOS: Billing opens the system browser ([settingsBillingLeavesRaft]), its row a trailing ↗. */
    billingOpensExternally: Boolean = settingsBillingLeavesRaft(),
): RaftSettingsHomePayload {
    val personalSection = stringResource(Res.string.settings_navigation_section_personal)
    val workspaceSection = stringResource(Res.string.settings_navigation_section_workspace)
    val aboutSection = stringResource(Res.string.settings_navigation_section_about)
    val themeServer = session?.settingsThemeServer()
    val themeServerName = themeServer?.name.orEmpty()
    val onServerSection = stringResource(Res.string.settings_navigation_section_on_server, themeServerName)
    val rows =
        listOf(
            SettingsHomePageLinkPayload(
                key = SettingsDestinationAccount,
                title = stringResource(Res.string.settings_account_title),
                subtitle = stringResource(Res.string.settings_account_subtitle),
                iconName = RaftLucideIconName.User,
                value = session?.user?.bestDisplayName,
                section = personalSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsNativeDetailPage.LanguageRegion.key,
                title = stringResource(Res.string.settings_language_region_title),
                subtitle = stringResource(Res.string.settings_language_region_subtitle),
                iconName = RaftLucideIconName.Languages,
                section = personalSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsNativeDetailPage.Appearance.key,
                title = stringResource(Res.string.settings_appearance_title),
                subtitle = stringResource(Res.string.settings_appearance_subtitle),
                iconName = RaftLucideIconName.Type,
                section = personalSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsNativeDetailPage.Notifications.key,
                title = stringResource(Res.string.settings_notifications_title),
                subtitle = stringResource(Res.string.settings_notifications_subtitle),
                iconName = RaftLucideIconName.Bell,
                section = personalSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsNativeDetailPage.Mobile.key,
                title = stringResource(Res.string.settings_mobile_title),
                subtitle = stringResource(Res.string.settings_mobile_subtitle),
                iconName = RaftLucideIconName.Smartphone,
                section = personalSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsDestinationStorage,
                title = stringResource(Res.string.settings_storage_title),
                subtitle = stringResource(Res.string.settings_storage_subtitle),
                iconName = RaftLucideIconName.Archive,
                section = personalSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsNativeDetailPage.Theme.key,
                title = stringResource(Res.string.settings_theme_title),
                subtitle = stringResource(Res.string.settings_server_theme_description, themeServerName),
                iconName = RaftLucideIconName.Palette,
                section = onServerSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsNativeDetailPage.ServerNotifications.key,
                title = stringResource(Res.string.settings_notifications_title),
                subtitle = stringResource(Res.string.settings_server_notifications_subtitle, themeServerName),
                iconName = RaftLucideIconName.BellDot,
                section = onServerSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsNativeDetailPage.Server.key,
                title = stringResource(Res.string.settings_server_profile_title),
                subtitle = stringResource(Res.string.settings_server_profile_subtitle),
                iconName = RaftLucideIconName.Building2,
                value = session?.currentServerSummary()?.role?.replaceFirstChar { it.uppercase() },
                section = workspaceSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsDestinationBilling,
                title = stringResource(Res.string.settings_billing_title),
                subtitle = stringResource(Res.string.settings_billing_subtitle),
                iconName = RaftLucideIconName.CreditCard,
                value = session?.currentServerSummary()?.plan?.replaceFirstChar { it.uppercase() },
                section = workspaceSection,
                opensExternally = billingOpensExternally,
            ),
            SettingsHomePageLinkPayload(
                key = SettingsDestinationAdministration,
                title = stringResource(Res.string.settings_server_administration_title),
                subtitle = stringResource(Res.string.settings_server_administration_subtitle),
                iconName = RaftLucideIconName.Shield,
                section = workspaceSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsDestinationImBridges,
                title = stringResource(Res.string.settings_im_bridges_title),
                subtitle = stringResource(Res.string.settings_im_bridges_subtitle),
                iconName = RaftLucideIconName.Network,
                section = workspaceSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsDestinationConnectedApps,
                title = stringResource(Res.string.settings_connected_apps_title),
                subtitle = stringResource(Res.string.settings_connected_apps_subtitle),
                iconName = RaftLucideIconName.Link2,
                section = workspaceSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsDestinationComputers,
                title = stringResource(Res.string.settings_computers_title),
                subtitle = stringResource(Res.string.settings_computers_subtitle),
                iconName = RaftLucideIconName.Monitor,
                section = workspaceSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsNativeDetailPage.About.key,
                title = stringResource(Res.string.settings_about_title),
                subtitle = stringResource(Res.string.settings_about_subtitle),
                iconName = RaftLucideIconName.BadgeInfo,
                section = aboutSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsDestinationDocumentation,
                title = stringResource(Res.string.settings_documentation_title),
                subtitle = RaftDocumentationUrl,
                iconName = RaftLucideIconName.BookOpenText,
                section = aboutSection,
                opensExternally = true,
            ),
            SettingsHomePageLinkPayload(
                key = SettingsDestinationHandsFeedback,
                title = stringResource(Res.string.settings_feedback_title),
                subtitle = stringResource(Res.string.settings_feedback_subtitle),
                iconName = RaftLucideIconName.MessageSquarePlus,
                section = aboutSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsDestinationReleaseNotes,
                title = stringResource(Res.string.settings_release_notes_title),
                subtitle = stringResource(Res.string.settings_release_notes_subtitle),
                iconName = RaftLucideIconName.FileText,
                section = aboutSection
            )
        ).associateBy { it.key }
    return RaftSettingsHomePayload(
        title = stringResource(Res.string.settings_title),
        subtitle = "",
        items =
            settingsHomeRowKeys(
                hasThemeServer = themeServer != null,
                appPreferencesUnderAppearance = appPreferencesUnderAppearance,
                imBridges = imBridgesEnabled,
            ).mapNotNull(rows::get),
    )
}

@Composable
internal fun rememberSettingsHomePayload(
    payload: String,
    fallbackPageData: String,
    instanceId: String
): RaftSettingsHomePayload =
    androidx.compose.runtime.remember(payload, fallbackPageData, instanceId) {
        parseSettingsHomePayload(
            payload = payload,
            fallbackPageData = fallbackPageData,
            instanceId = instanceId
        )
    }

@Composable
internal fun SettingsHomeContent(
    payload: RaftSettingsHomePayload,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    applyTopSafeArea: Boolean = true,
    onHeaderClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    scrollToTopRequest: Int = 0,
    onSelect: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val scrollToTopRequestFence = remember { AppShellScrollToTopRequestFence(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequestFence.consume(scrollToTopRequest)) listState.animateScrollToTopExpressive()
    }
    val headerModifier =
        if (onHeaderClick != null) {
            Modifier.clickable(role = Role.Button, onClick = onHeaderClick)
        } else {
            Modifier
        }
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        // Under the wide top bar the Settings tab has no head (web Sidebar `hideTitle`, artin task #8).
        if (!raftUnderAppShellTopBar() || onBack != null) {
            RaftMainTabPageHeader(
                title = payload.title,
                subtitle = null,
                modifier = headerModifier,
                applyTopSafeArea = applyTopSafeArea,
                onBack = onBack,
                nativeOnTitleClick = onHeaderClick,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                SettingsHubSidebarList(
                    items = payload.items.map(SettingsHomePageLinkPayload::toTabItemPayload),
                    onSelect = onSelect,
                )
            }
            item {
                Spacer(Modifier.height(bottomContentPadding))
            }
        }
    }
}

private fun SettingsHomePageLinkPayload.toTabItemPayload(): RaftTabItemPayload =
    RaftTabItemPayload(
        key = key,
        title = title,
        subtitle = subtitle,
        iconName = iconName,
        value = value,
        section = section,
        attentionDot = attentionDot,
        opensExternally = opensExternally,
    )

/**
 * web `SettingsSidebarList` on a phone: each group under a raft-ui SidebarGroupLabel, its rows a
 * list of raft-ui SidebarItems (`flex-col gap-0.5 py-2`): 44dp touch rows, the glyph in its icon
 * box, the label in body semibold, a ↗ on a row that leaves Raft and the attention pixel on one
 * with something new. 8dp from the screen edges, as the Home list.
 */
@Composable
private fun SettingsHubSidebarList(
    items: List<RaftTabItemPayload>,
    onSelect: (String) -> Unit,
) {
    val groups = remember(items) {
        val out = mutableListOf<Pair<String?, MutableList<RaftTabItemPayload>>>()
        items.forEach { item ->
            if (out.isEmpty() || out.last().first != item.section) out += item.section to mutableListOf()
            out.last().second += item
        }
        out
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        groups.forEach { (section, rows) ->
            if (section != null) build.raft.designsystem.components.RaftSidebarGroupLabel(section)
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEach { item ->
                    build.raft.designsystem.components.RaftSidebarItem(
                        title = item.title,
                        onClick = if (item.enabled) ({ onSelect(item.key) }) else null,
                        leading = {
                            if (item.key == SettingsDestinationHandsFeedback) {
                                // raft-ui `FeedbackIcon` (web settings Feedback row), at the sidebar
                                // icon's stroke-2 in its 18-unit box.
                                build.raft.designsystem.components.RaftConversationKindIcon(
                                    build.raft.designsystem.components.RaftConversationKindGlyph.Feedback,
                                    size = build.raft.designsystem.components.raftSidebarItemIconSize(),
                                    strokeWidth = 2f,
                                )
                            } else {
                                RaftLucideIcon(
                                    item.iconName,
                                    Modifier.size(build.raft.designsystem.components.raftSidebarItemIconSize()),
                                    color = raftArgbHex(build.raft.designsystem.theme.LocalRaftContentColor.current),
                                )
                            }
                        },
                        trailing =
                            if (item.attentionDot || item.opensExternally) {
                                {
                                    if (item.attentionDot) RaftStatusDot(variant = RaftStatusVariant.Accent)
                                    if (item.opensExternally) {
                                        RaftLucideIcon(
                                            RaftLucideIconName.ArrowUpRight,
                                            Modifier.size(14.dp),
                                            color = raftArgbHex(RaftColor.ForegroundHint),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }
}
