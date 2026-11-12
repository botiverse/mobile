package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.StoredAuthSession
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
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
import androidx.compose.ui.graphics.Color
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
    /**
     * Attention dot on the row (the More tab's signals on the phone Settings root; on the wide
     * Settings list, Feedback's only: [settingsWideAttentionRowKeys]).
     */
    val attentionDot: Boolean = false,
    /** The row leaves Raft for another app: a trailing ↗ (raft-ui 0.26 #758). */
    val opensExternally: Boolean = false,
)

/**
 * The Settings page as the phone's pushed server page (raft-design #712, opened from the server
 * panel; app-shell tab key "more"): titled [title], the rows named in [attentionRowKeys] wear the
 * attention dot ([RaftMoreAttention.settingsRowKey]). The wide rail's Settings list dots only
 * Feedback ([settingsWideAttentionRowKeys]).
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
    moreRoot?.let { root -> items.withAttentionRows(root.attentionRowKeys) } ?: items

/** [rowKeys] wear the attention dot, the other rows none. */
internal fun List<SettingsHomePageLinkPayload>.withAttentionRows(rowKeys: Set<String>): List<SettingsHomePageLinkPayload> =
    map { it.copy(attentionDot = it.key in rowKeys) }

/** The Settings list with [rowKeys] dotted (the wide Settings list: [settingsWideAttentionRowKeys]). */
internal fun RaftSettingsHomePayload.withAttentionRows(rowKeys: Set<String>): RaftSettingsHomePayload =
    copy(items = items.withAttentionRows(rowKeys))

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
    /** Search settings on top of the list; null: no field. */
    search: SettingsSearchHost? = null,
) {
    // The phone's Settings: Computers and Apps are on the server panel that opened it.
    val payload = appShellSettingsHomePayload(session, imBridgesEnabled = imBridgesEnabled, serverPanelPages = true)
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
        search = search,
    )
}

/**
 * The Settings home rows top to bottom, the web's settings groups (botiverse/slock
 * packages/web/src/components/layout/Sidebar.tsx:2257-2328, settingsNavigation.ts:29-45):
 * Personal; "On {server}", the member's own settings in the current server: Theme and the server's
 * Notifications (its level alone; the personal Notifications page shows it too); Workspace;
 * Resources, with Documentation leaving Raft for the docs site. The "On {server}" group is there
 * only with a current server ([hasThemeServer]), as is the web's. The app's own rows stay: Mobile
 * and Storage (this device's settings, which the web has not).
 * On the phone ([serverPanelPages]) the list is the web phone's (artin 2026-11-05, dm task #17):
 * Computers is not a row, it is a page on the server panel (web MobileServerTab), and Applications
 * is a row only for a guest ([guest]; web useSidebarRoute `currentRole === "guest"`): a guest has
 * no Apps page, and their Applications holds only their own link previews; everyone else opens
 * Apps from the server panel. The wide list keeps both rows, next to the rail's Computers and Apps.
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
    serverPanelPages: Boolean = false,
    guest: Boolean = false,
    storage: Boolean = settingsStorageAvailable(),
): List<String> =
    listOfNotNull(
        SettingsDestinationAccount,
        SettingsNativeDetailPage.LanguageRegion.key,
        SettingsNativeDetailPage.Appearance.key,
        SettingsNativeDetailPage.Notifications.key,
        SettingsNativeDetailPage.Mobile.key.takeUnless { appPreferencesUnderAppearance },
        SettingsDestinationStorage.takeIf { storage },
        SettingsNativeDetailPage.Theme.key.takeIf { hasThemeServer },
        SettingsNativeDetailPage.ServerNotifications.key.takeIf { hasThemeServer },
        SettingsNativeDetailPage.Server.key,
        SettingsDestinationBilling,
        SettingsDestinationAdministration,
        SettingsDestinationImBridges.takeIf { imBridges },
        SettingsDestinationConnectedApps.takeIf { !serverPanelPages || guest },
        SettingsDestinationComputers.takeUnless { serverPanelPages },
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

/** Settings › Storage shows only where the host reports local storage ([raftPlatformHasStorageInfo]). */
internal fun settingsStorageAvailable(hasStorageInfo: Boolean = raftPlatformHasStorageInfo()): Boolean = hasStorageInfo

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
    /** The phone: Computers and Apps are on the server panel ([settingsHomeRowKeys]). */
    serverPanelPages: Boolean = false,
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
                serverPanelPages = serverPanelPages,
                guest = session?.currentServerSummary()?.role.equals("guest", ignoreCase = true),
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
    /** Search settings above the groups (raft-design settings.md#searching-settings); null: none. */
    search: SettingsSearchHost? = null,
    onSelect: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val searchResults = search?.let { rememberSettingsSearchResults(it, payload) }
    val searching = search?.state?.searching == true
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
        // The web's phone Settings is a pushed server page (useSidebarHomeEntries
        // `isServerPushedPage`) headed by its PanelHeader: the canvas, white in light and the dark
        // canvas in dark, with the seam under it, not a tab root's frame. Every Settings sub-page
        // head (SettingsDetailShell) is the same, and the status bar icons follow the canvas.
        if (!raftUnderAppShellTopBar() || onBack != null) {
            RaftTopPageHeader(
                title = payload.title,
                subtitle = null,
                modifier = headerModifier,
                backgroundColor = SettingsHomeHeaderBackground,
                applyTopSafeArea = applyTopSafeArea,
                onBack = onBack,
                // The web page is a list column (raft-ui `listColumnMargin`, 20px at every width),
                // so its head's margin is 20: Back's arrow box lands on it (the button 9dp in),
                // the title follows the phone head's 8dp gap; with no Back the title starts on it.
                contentStartPadding =
                    if (onBack != null) {
                        SettingsHomeHeaderMargin - (RaftBackButtonDefaults.Size - RaftBackButtonDefaults.IconSize) / 2
                    } else {
                        SettingsHomeHeaderMargin
                    },
                contentSpacing = SettingsHomeHeaderGap,
                titleStyle = RaftMainTabHeaderTitleStyle,
                nativeOnTitleClick = onHeaderClick,
            )
        }
        // The field below can scroll out of composition; Back must still clear the query.
        if (search != null) SettingsSearchBackHandler(search)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (search != null) {
                // The list's first row, so it scrolls away with the groups and shows again at the
                // top (phone settings pattern, artin dm cb575320); the web's sticky field stays on
                // the wide split. 8dp from the screen edges like the rows.
                item(key = SettingsHubSearchFieldKey) {
                    SettingsSearchField(
                        host = search,
                        results = searchResults.orEmpty(),
                        modifier = Modifier.raftSafeAreaHorizontalPadding().padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 4.dp),
                        ownsBackHandler = false,
                    )
                }
            }
            if (search != null && searching) {
                // The results take the groups' place while there is text.
                settingsSearchResultItems(search, searchResults.orEmpty(), Modifier.padding(horizontal = 8.dp))
            } else {
                item {
                    SettingsHubSidebarList(
                        items = payload.items.map(SettingsHomePageLinkPayload::toTabItemPayload),
                        onSelect = onSelect,
                    )
                }
            }
            item {
                Spacer(Modifier.height(bottomContentPadding))
            }
        }
    }
}

/** The Settings home head's fill: the canvas, like every Settings page head (web PanelHeader). */
internal val SettingsHomeHeaderBackground: Color
    get() = RaftColor.Canvas

private const val SettingsHubSearchFieldKey = "settings_hub_search_field"

/** The web Settings list column's margin (raft-ui `listColumnMargin`), its head's too. */
private val SettingsHomeHeaderMargin = 20.dp

/** raft-ui panel head on a phone: `max-md:gap-2`. */
private val SettingsHomeHeaderGap = 8.dp

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
            // `gap-0.5 py-2`: each row keeps 1dp above and below (RaftSidebarItem), so the
            // column adds 7dp at its ends and nothing between the rows.
            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                rows.forEach { item ->
                    build.raft.designsystem.components.RaftSidebarItem(
                        title = item.title,
                        onClick = if (item.enabled) ({ onSelect(item.key) }) else null,
                        leading = { SettingsRowIcon(item.key, item.iconName) },
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

/**
 * A Settings list row's glyph in a raft-ui SidebarItem's icon box. Feedback is raft-ui
 * `FeedbackIcon` (the web settings list, useSidebarRoute.tsx), drawn at the box's stroke-2 in its
 * 18-unit viewBox; every other row is its Lucide [iconName].
 */
@Composable
internal fun SettingsRowIcon(key: String, iconName: RaftLucideIconName) {
    val size = build.raft.designsystem.components.raftSidebarItemIconSize()
    if (key == SettingsDestinationHandsFeedback) {
        build.raft.designsystem.components.RaftConversationKindIcon(
            build.raft.designsystem.components.RaftConversationKindGlyph.Feedback,
            size = size,
            strokeWidth = 2f,
        )
    } else {
        RaftLucideIcon(
            iconName,
            Modifier.size(size),
            color = raftArgbHex(build.raft.designsystem.theme.LocalRaftContentColor.current),
        )
    }
}
