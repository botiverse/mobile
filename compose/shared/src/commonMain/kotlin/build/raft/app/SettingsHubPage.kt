package build.raft.app

import build.raft.app.data.StoredAuthSession
import build.raft.app.resources.MR
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
import com.tencent.tmm.kmmresource.compose.stringResource
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
    val membersSubtitle = stringResource(MR.strings.more_hub_members_subtitle)
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

@Composable
internal fun appShellSettingsHomePayload(
    session: StoredAuthSession?,
): RaftSettingsHomePayload {
    val personalSection = stringResource(MR.strings.settings_navigation_section_personal)
    val workspaceSection = stringResource(MR.strings.settings_navigation_section_workspace)
    val aboutSection = stringResource(MR.strings.settings_navigation_section_about)
    return RaftSettingsHomePayload(
        title = stringResource(MR.strings.settings_title),
        subtitle = "",
        items =
            listOf(
                SettingsHomePageLinkPayload(
                    key = SettingsDestinationAccount,
                    title = stringResource(MR.strings.settings_account_title),
                    subtitle = stringResource(MR.strings.settings_account_subtitle),
                    iconName = RaftLucideIconName.User,
                    value = session?.user?.bestDisplayName,
                    section = personalSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsNativeDetailPage.LanguageRegion.key,
                    title = stringResource(MR.strings.settings_language_region_title),
                    subtitle = stringResource(MR.strings.settings_language_region_subtitle),
                    iconName = RaftLucideIconName.Languages,
                    section = personalSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsNativeDetailPage.Appearance.key,
                    title = stringResource(MR.strings.settings_appearance_title),
                    subtitle = stringResource(MR.strings.settings_appearance_subtitle),
                    iconName = RaftLucideIconName.Type,
                    section = personalSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsNativeDetailPage.Notifications.key,
                    title = stringResource(MR.strings.settings_notifications_title),
                    subtitle = stringResource(MR.strings.settings_notifications_subtitle),
                    iconName = RaftLucideIconName.Bell,
                    section = personalSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsNativeDetailPage.Mobile.key,
                    title = stringResource(MR.strings.settings_mobile_title),
                    subtitle = stringResource(MR.strings.settings_mobile_subtitle),
                    iconName = RaftLucideIconName.Smartphone,
                    section = personalSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsDestinationStorage,
                    title = stringResource(MR.strings.settings_storage_title),
                    subtitle = stringResource(MR.strings.settings_storage_subtitle),
                    iconName = RaftLucideIconName.Archive,
                    section = personalSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsNativeDetailPage.Server.key,
                    title = stringResource(MR.strings.settings_server_profile_title),
                    subtitle = stringResource(MR.strings.settings_server_profile_subtitle),
                    iconName = RaftLucideIconName.Building2,
                    value = session?.currentServerSummary()?.role?.replaceFirstChar { it.uppercase() },
                    section = workspaceSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsDestinationBilling,
                    title = stringResource(MR.strings.settings_billing_title),
                    subtitle = stringResource(MR.strings.settings_billing_subtitle),
                    iconName = RaftLucideIconName.CreditCard,
                    value = session?.currentServerSummary()?.plan?.replaceFirstChar { it.uppercase() },
                    section = workspaceSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsDestinationAdministration,
                    title = stringResource(MR.strings.settings_server_administration_title),
                    subtitle = stringResource(MR.strings.settings_server_administration_subtitle),
                    iconName = RaftLucideIconName.Shield,
                    section = workspaceSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsDestinationConnectedApps,
                    title = stringResource(MR.strings.settings_connected_apps_title),
                    subtitle = stringResource(MR.strings.settings_connected_apps_subtitle),
                    iconName = RaftLucideIconName.Link2,
                    section = workspaceSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsDestinationComputers,
                    title = stringResource(MR.strings.settings_computers_title),
                    subtitle = stringResource(MR.strings.settings_computers_subtitle),
                    iconName = RaftLucideIconName.Monitor,
                    section = workspaceSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsNativeDetailPage.About.key,
                    title = stringResource(MR.strings.settings_about_title),
                    subtitle = stringResource(MR.strings.settings_about_subtitle),
                    iconName = RaftLucideIconName.BadgeInfo,
                    section = aboutSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsDestinationHandsFeedback,
                    title = stringResource(MR.strings.settings_feedback_title),
                    subtitle = stringResource(MR.strings.settings_feedback_subtitle),
                    iconName = RaftLucideIconName.MessageSquarePlus,
                    section = aboutSection
                ),
                SettingsHomePageLinkPayload(
                    key = SettingsDestinationReleaseNotes,
                    title = stringResource(MR.strings.settings_release_notes_title),
                    subtitle = stringResource(MR.strings.settings_release_notes_subtitle),
                    iconName = RaftLucideIconName.FileText,
                    section = aboutSection
                )
            )
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
        if (LocalRaftAppShellTopBar.current == null || onBack != null) {
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
