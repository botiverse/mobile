package build.raft.app

import build.raft.app.resources.*
import build.raft.app.data.StoredAuthSession
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
)

/** Row key of the Members entry at the top of the More (Settings) page. */
internal const val SettingsMoreMembersRowKey = "members"

/**
 * The Settings page as the phone More tab's root (stdrc / Jasper 2026-10-21): titled like the
 * tab ([title], 更多 / More / その他), Members and Computers lead as the first group, then the
 * Settings sections with their own headings; the rows named in [attentionRowKeys] wear the
 * attention dot the More tab shows ([RaftMoreAttention.settingsRowKey]).
 */
internal data class SettingsMoreRoot(
    val title: String,
    val membersTitle: String,
    val attentionRowKeys: Set<String> = emptySet(),
    /** False since D1a (raft-design #712): Members and Computers are on the server panel. */
    val leadsWithMembersAndComputers: Boolean = true,
)

/**
 * The Settings home rows for [moreRoot]: Members and Computers move to a first group (no section
 * heading) ahead of the existing sections, and attention rows get their dot. Without a More root
 * (the wide rail) the rows are unchanged.
 */
internal fun settingsHomeItemsForMoreRoot(
    items: List<SettingsHomePageLinkPayload>,
    moreRoot: SettingsMoreRoot?,
    membersSubtitle: String = "",
): List<SettingsHomePageLinkPayload> {
    if (moreRoot == null) return items
    if (!moreRoot.leadsWithMembersAndComputers) {
        return items.map { it.copy(attentionDot = it.key in moreRoot.attentionRowKeys) }
    }
    val computers = items.firstOrNull { it.key == SettingsDestinationComputers }
    val firstGroup =
        listOfNotNull(
            SettingsHomePageLinkPayload(
                key = SettingsMoreMembersRowKey,
                title = moreRoot.membersTitle,
                subtitle = membersSubtitle,
                iconName = RaftLucideIconName.Users,
            ),
            computers?.copy(section = null),
        )
    return (firstGroup + items.filter { it.key != SettingsDestinationComputers })
        .map { it.copy(attentionDot = it.key in moreRoot.attentionRowKeys) }
}

@Composable
internal fun SettingsAppShellHomeContent(
    session: StoredAuthSession?,
    bottomContentPadding: Dp,
    onSettingsHeaderClick: () -> Unit,
    onSelect: (String) -> Unit,
    moreRoot: SettingsMoreRoot? = null,
    scrollToTopRequest: Int = 0,
    onBack: (() -> Unit)? = null,
) {
    val payload = appShellSettingsHomePayload(session)
    val membersSubtitle = stringResource(Res.string.more_hub_members_subtitle)
    SettingsHomeContent(
        payload =
            payload.copy(
                title = moreRoot?.title ?: payload.title,
                items = settingsHomeItemsForMoreRoot(payload.items, moreRoot, membersSubtitle),
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
 * Personal; "On {server}", the member's own settings in the current server, with Theme (the web
 * then has the server's Notifications; the app keeps that mute on Notifications); Workspace;
 * Resources. Theme is there only with a current server ([hasThemeServer]), as is the web's group.
 * Mobile is a phone page: with [appPreferencesUnderAppearance] (the desktop app) its cards are part
 * of Appearance, the web's home for them, and there is no Mobile row.
 */
internal fun settingsHomeRowKeys(
    hasThemeServer: Boolean,
    appPreferencesUnderAppearance: Boolean = settingsAppPreferencesUnderAppearance(),
): List<String> =
    listOfNotNull(
        SettingsDestinationAccount,
        SettingsNativeDetailPage.LanguageRegion.key,
        SettingsNativeDetailPage.Appearance.key,
        SettingsNativeDetailPage.Notifications.key,
        SettingsNativeDetailPage.Mobile.key.takeUnless { appPreferencesUnderAppearance },
        SettingsDestinationStorage,
        SettingsNativeDetailPage.Theme.key.takeIf { hasThemeServer },
        SettingsNativeDetailPage.Server.key,
        SettingsDestinationBilling,
        SettingsDestinationAdministration,
        SettingsDestinationConnectedApps,
        SettingsDestinationComputers,
        SettingsNativeDetailPage.About.key,
        SettingsDestinationHandsFeedback,
        SettingsDestinationReleaseNotes,
    )

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
): RaftSettingsHomePayload {
    val personalSection = stringResource(Res.string.settings_navigation_section_personal)
    val workspaceSection = stringResource(Res.string.settings_navigation_section_workspace)
    val aboutSection = stringResource(Res.string.settings_navigation_section_about)
    val themeServer = session?.settingsThemeServer()
    val themeServerName = themeServer?.name.orEmpty()
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
                section = stringResource(Res.string.settings_navigation_section_on_server, themeServerName)
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
                section = workspaceSection
            ),
            SettingsHomePageLinkPayload(
                key = SettingsDestinationAdministration,
                title = stringResource(Res.string.settings_server_administration_title),
                subtitle = stringResource(Res.string.settings_server_administration_subtitle),
                iconName = RaftLucideIconName.Shield,
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
                onBack = onBack
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Spacer(Modifier.height(RaftAppSpacing.Md))
            }
            item {
                RaftVerticalTabBar(
                    items = payload.items.map(SettingsHomePageLinkPayload::toTabItemPayload),
                    showSelection = false,
                    onSelect = onSelect,
                    modifier = Modifier.padding(start = RaftAppSpacing.Md),
                    sectionStartPadding = 0.dp,
                    itemStartPadding = 0.dp
                )
            }
            item {
                Spacer(Modifier.height(RaftAppSpacing.Md))
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
    )
