package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import build.raft.app.base.BasePager
import build.raft.app.data.AppLocale
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.raftSharedPreferenceStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import build.raft.app.compat.pager.Page
import build.raft.designsystem.components.LocalRaftListSkeletonDelayMillis
import build.raft.app.resources.StringDesc

@Page("compose_home_titlebar_visual", supportInLocal = true)
internal class ComposeHomeTitlebarVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeTitlebarVisualScreen() }
    }
}

@Page("compose_home_titlebar_preference_visual", supportInLocal = true)
internal class ComposeHomeTitlebarPreferenceVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val enabled =
            KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())
                .readHomeServerAvatarEnabledSync()
        setContent { HomeTitlebarPreferenceVisualScreen(enabled) }
    }
}

@Page("compose_home_titlebar_server_switch_visual", supportInLocal = true)
internal class ComposeHomeTitlebarServerSwitchVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeTitlebarServerSwitchVisualScreen() }
    }
}

@Page("compose_home_titlebar_loading_visual", supportInLocal = true)
internal class ComposeHomeTitlebarLoadingVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeTitlebarLoadingVisualScreen() }
    }
}

@Page("compose_home_titlebar_error_visual", supportInLocal = true)
internal class ComposeHomeTitlebarErrorVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeTitlebarErrorVisualScreen() }
    }
}

@Page("compose_home_titlebar_resize_visual", supportInLocal = true)
internal class ComposeHomeTitlebarResizeVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeTitlebarResizeVisualScreen() }
    }
}

// #372 screens.home.loading: pins the post-sign-in Loading-with-session home
// state (session restored, channels / directMessages not yet fetched). This
// renders exactly what SharedHomeSessionShell renders for
// SharedHomeContentState.Loading — empty conversation lists plus the loading
// skeleton rows — with `showBottomBar = true` standing in for the app-shell
// bottom tab bar that hosts the home page in production (React shows it
// during loading too). The skeleton primitives are static (no shimmer), so
// the capture settles deterministically.
@Page("compose_home_loading_visual", supportInLocal = true)
internal class ComposeHomeLoadingVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeLoadingVisualContent() }
    }
}

@Composable
private fun HomeLoadingVisualContent() {
    // A capture: the skeleton rows show in the first frame, not after the 1s list delay.
    CompositionLocalProvider(LocalRaftListSkeletonDelayMillis provides 0L) {
        RaftStructuralHomeShell(
            currentServerName = VisualFixtureData.Server.NAME,
            currentServerId = VisualFixtureData.Server.ID,
            servers =
                listOf(
                    RaftServerSwitcherItem(
                        id = VisualFixtureData.Server.ID,
                        name = VisualFixtureData.Server.NAME,
                        slug = VisualFixtureData.Server.SLUG
                    )
                ),
            channels = emptyList(),
            directMessages = emptyList(),
            showLoadingSkeletons = true,
            showBottomBar = true,
            outerPadding = 0.dp
        )
    }
}

@Page("compose_home_notification_center_visual", supportInLocal = true)
internal class ComposeHomeNotificationCenterVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeNotificationCenterVisualScreen() }
    }
}

@Page("compose_home_app_update_banner_visual", supportInLocal = true)
internal class ComposeHomeAppUpdateBannerVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeAppUpdateBannerVisualScreen() }
    }
}

@Page("compose_home_notification_trigger_states_visual", supportInLocal = true)
internal class ComposeHomeNotificationTriggerStatesVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeNotificationTriggerStatesVisualScreen() }
    }
}

@Page("compose_app_update_details_visual", supportInLocal = true)
internal class ComposeAppUpdateDetailsVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AppUpdateDetailsVisualScreen() }
    }
}

@Page("compose_app_update_details_zh_visual", supportInLocal = true)
internal class ComposeAppUpdateDetailsZhVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AppUpdateDetailsChineseVisualContent() }
    }
}

@Composable
private fun AppUpdateDetailsChineseVisualContent() {
    val previousLocaleType = remember { StringDesc.localeType }
    StringDesc.localeType = AppLocale.SimplifiedChinese.stringDescLocaleType
    DisposableEffect(Unit) {
        onDispose { StringDesc.localeType = previousLocaleType }
    }
    AppUpdateDetailsVisualScreen(chinese = true)
}

@Page("compose_thread_files_visual", supportInLocal = true)
internal class ComposeThreadFilesVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadFilesVisualScreen() }
    }
}

@Page("compose_thread_message_visual", supportInLocal = true)
internal class ComposeThreadMessageVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadMessageVisualScreen() }
    }
}

@Page("compose_thread_header_visual", supportInLocal = true)
internal class ComposeThreadHeaderVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadHeaderVisualScreen() }
    }
}

@Page("compose_task797_thread_header_visual", supportInLocal = true)
internal class ComposeTask797ThreadHeaderVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { Task797ThreadHeaderVisualScreen() }
    }
}

@Page("compose_thread_dm_agent_activity_header_visual", supportInLocal = true)
internal class ComposeThreadDmAgentActivityHeaderVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadDmAgentActivityHeaderVisualScreen() }
    }
}

@Page("compose_thread_activity_mute_visual", supportInLocal = true)
internal class ComposeThreadActivityMuteVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadActivityMuteVisualScreen() }
    }
}

@Page("compose_thread_reaction_atlas_visual", supportInLocal = true)
internal class ComposeThreadReactionAtlasVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadReactionAtlasVisualScreen() }
    }
}

@Page("compose_channel_settings_panel_visual", supportInLocal = true)
internal class ComposeChannelSettingsPanelVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelSettingsPanelVisualScreen() }
    }
}

@Page("compose_channel_settings_pinned_panel_visual", supportInLocal = true)
internal class ComposeChannelSettingsPinnedPanelVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelSettingsPanelVisualScreen(pinned = true) }
    }
}

@Page("compose_dm_settings_panel_visual", supportInLocal = true)
internal class ComposeDirectMessageSettingsPanelVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { DirectMessageSettingsPanelVisualScreen() }
    }
}

@Page("compose_channel_add_member_panel_visual", supportInLocal = true)
internal class ComposeChannelAddMemberPanelVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelAddMemberPanelVisualScreen() }
    }
}

@Page("compose_thread_composer_visual", supportInLocal = true)
internal class ComposeThreadComposerVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadComposerScreen() }
    }
}

@Page("compose_bottom_tabbar_visual", supportInLocal = true)
internal class ComposeBottomTabBarVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { BottomTabBarVisualContent() }
    }
}

@Page("compose_android_floating_tabbar_visual", supportInLocal = true)
internal class ComposeAndroidFloatingTabBarVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val initialSelectedKey = pageData.params.optString("selectedKey").ifBlank { "home" }
        val disabledKey = pageData.params.optString("disabledKey")
        val bottomSafeArea = pageData.params.optInt("bottomSafeAreaDp").dp
        setContent {
            var selectedKey by remember { mutableStateOf(initialSelectedKey) }
            RaftAppBottomTabBarHost(
                items =
                    appShellPhoneBottomTabs(
                        selectedTab = selectedKey,
                        homeLabel = "Home",
                        activityLabel = "Activity",
                        searchLabel = "Search",
                        tasksLabel = "Tasks",
                        moreLabel = "More",
                        activityUnreadCount = 12
                    ).map { it.copy(enabled = it.key != disabledKey) },
                onSelect = { selectedKey = it },
                modifier = Modifier.width(390.dp),
                bottomSafeArea = bottomSafeArea
            )
        }
    }
}

/**
 * The phone bottom bar (stdrc 2026-10-21): the full-width edge bar 主页 · 动态 · 任务 · 搜索 · 更多
 * (搜索 is an action, never selected), rendered by the shared Compose bar at 390dp. Top: 动态 selected (no dot on the
 * selected tab); middle: 任务 selected with the unread dot on 动态; bottom: a page pushed from
 * 更多 (成员) keeps 更多 selected.
 */
@Page("compose_bottom_tabbar_mockup_visual", supportInLocal = true)
internal class ComposeBottomTabBarMockupVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            Column(
                modifier = Modifier.background(RaftColor.Canvas).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                listOf("activity", "tasks", "members").forEach { selectedTab ->
                    RaftBottomTabBar(
                        items =
                            appShellPhoneBottomTabs(
                                selectedTab = selectedTab,
                                homeLabel = "主页",
                                activityLabel = "动态",
                                searchLabel = "搜索",
                                tasksLabel = "任务",
                                moreLabel = "更多",
                                activityUnreadCount = 51
                            ),
                        onSelect = {},
                        modifier = Modifier.width(390.dp)
                    )
                }
            }
        }
    }
}

@Page("compose_lucide_catalog_atlas_visual", supportInLocal = true)
internal class ComposeLucideCatalogAtlasVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            Column(
                modifier =
                    Modifier
                        .width(390.dp)
                        .height(798.dp)
                        .background(RaftColor.SurfaceWhite),
            ) {
                RaftLucideIcons.Names.chunked(10).forEach { rowNames ->
                    Row(
                        modifier = Modifier.width(390.dp).height(42.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowNames.forEach { name ->
                            Box(
                                modifier = Modifier.width(39.dp).height(42.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                RaftLucideIcon(name = name, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Page("compose_lucide_state_atlas_visual", supportInLocal = true)
internal class ComposeLucideStateAtlasVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            Row(
                modifier =
                    Modifier
                        .width(390.dp)
                        .height(84.dp)
                        .background(RaftColor.SurfaceWhite),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideStateAtlasCell { RaftLucideIcon(RaftLucideIconName.Bookmark, Modifier.size(24.dp)) }
                LucideStateAtlasCell {
                    RaftLucideIcon(
                        RaftLucideIconName.Bookmark,
                        Modifier.size(24.dp),
                        fill = RaftColor.TextBlackArgb,
                    )
                }
                LucideStateAtlasCell {
                    RaftLucideIcon(RaftLucideIconName.Check, Modifier.size(24.dp), strokeWidth = 2f)
                }
                LucideStateAtlasCell {
                    RaftLucideIcon(RaftLucideIconName.Check, Modifier.size(24.dp), strokeWidth = 4f)
                }
                LucideStateAtlasCell {
                    RaftLucideIcon(RaftLucideIconName.Check, Modifier.size(24.dp), strokeWidth = 6f)
                }
                listOf(0f, 90f, 180f, 270f).forEach { rotation ->
                    LucideStateAtlasCell {
                        RaftLucideIcon(
                            RaftLucideIconName.ArrowLeft,
                            Modifier.size(24.dp),
                            color = if (rotation == 0f) RaftColor.AccentRedArgb else RaftColor.TextBlackArgb,
                            rotationDegrees = rotation,
                        )
                    }
                }
                LucideStateAtlasCell {
                    RaftLucideIcon(
                        RaftLucideIconName.CircleArrowUp,
                        Modifier.size(24.dp),
                        verticalOffsetDp = 4f,
                    )
                }
            }
        }
    }
}

@Composable
private fun LucideStateAtlasCell(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.width(39.dp).height(84.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Page("compose_foreground_banner_visual", supportInLocal = true)
internal class ComposeForegroundBannerVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ForegroundBannerVisualContent() }
    }
}

@Page("compose_settings_row_visual", supportInLocal = true)
internal class ComposeSettingsRowVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        installSettingsVisualFixtureData()
        setContent { SettingsRowVisualContent() }
    }
}

@Page("compose_settings_page_visual", supportInLocal = true)
internal class ComposeSettingsPageVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        installSettingsVisualFixtureData()
        setContent { SettingsPageVisualContent() }
    }
}

private fun installSettingsVisualFixtureData() {
    SettingsVisualFixtureBridge.install(
        SettingsVisualFixtureValues(
            serverId = VisualFixtureData.Server.ID,
            serverName = VisualFixtureData.Server.NAME,
            serverSlug = VisualFixtureData.Server.SLUG,
            serverPlan = VisualFixtureData.Server.PLAN,
            ownerId = VisualFixtureData.Humans.Owner.ID,
            ownerMemberId = VisualFixtureData.Humans.Owner.ID,
            ownerName = VisualFixtureData.Humans.Owner.NAME,
            ownerDisplayName = VisualFixtureData.Humans.Owner.DISPLAY_NAME,
            ownerHandle = VisualFixtureData.Humans.Owner.HANDLE,
            ownerEmail = VisualFixtureData.Humans.Owner.EMAIL,
            ownerAvatarLabel = VisualFixtureData.Humans.Owner.AVATAR_LABEL,
            ownerRole = VisualFixtureData.Humans.Owner.ROLE,
            ownerRoleLabel = VisualFixtureData.Humans.Owner.ROLE_LABEL,
            ownerDescription = VisualFixtureData.Humans.Owner.DESCRIPTION,
            ownerEmailVerified = VisualFixtureData.Humans.Owner.EMAIL_VERIFIED,
            designerEmail = VisualFixtureData.Humans.Designer.EMAIL,
            memberJoinedAtIso = VisualFixtureData.Times.MEMBER_JOINED_AT_ISO,
            entityCreatedAtIso = VisualFixtureData.Times.ENTITY_CREATED_AT_ISO,
            recentActivityAtIso = VisualFixtureData.Times.RECENT_ACTIVITY_AT_ISO
        )
    )
}

@Page("compose_home_search_visual", supportInLocal = true)
internal class ComposeHomeSearchVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchVisualContent() }
    }
}

@Page("compose_home_search_home_visual", supportInLocal = true)
internal class ComposeHomeSearchHomeVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchHomeVisualContent() }
    }
}

@Page("compose_home_search_home_edit_visual", supportInLocal = true)
internal class ComposeHomeSearchHomeEditVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchHomeVisualContent(editing = true) }
    }
}

@Page("compose_home_search_home_large_text_visual", supportInLocal = true)
internal class ComposeHomeSearchHomeLargeTextVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchHomeVisualContent(editing = true, visualTextScale = 1.3f) }
    }
}

@Page("compose_home_search_channel_pin_visual", supportInLocal = true)
internal class ComposeHomeSearchChannelPinVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchPinVisualContent() }
    }
}

@Page("compose_home_search_dm_pin_visual", supportInLocal = true)
internal class ComposeHomeSearchDmPinVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchPinVisualContent(query = "reviewer") }
    }
}

@Page("compose_home_search_agent_pin_visual", supportInLocal = true)
internal class ComposeHomeSearchAgentPinVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchPinVisualContent(query = "developer") }
    }
}

@Page("compose_home_search_human_pin_visual", supportInLocal = true)
internal class ComposeHomeSearchHumanPinVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchPinVisualContent(query = "pin human") }
    }
}

@Page("compose_home_search_channel_dropdown_visual", supportInLocal = true)
internal class ComposeHomeSearchChannelDropdownVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchVisualContent(initialOpenFilterId = "channel") }
    }
}

@Page("compose_home_search_scope_dropdown_visual", supportInLocal = true)
internal class ComposeHomeSearchScopeDropdownVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchVisualContent(initialOpenFilterId = "scope") }
    }
}

@Page("compose_home_search_error_visual", supportInLocal = true)
internal class ComposeHomeSearchErrorVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchErrorVisualContent() }
    }
}

@Page("compose_home_saved_visual", supportInLocal = true)
internal class ComposeHomeSavedVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSavedVisualContent() }
    }
}

// Activity as the second bottom tab: head, three segments (pageData `segment`), 5-tab bar + badge.
@Page("compose_activity_tab_visual", supportInLocal = true)
internal class ComposeRaftActivityTabVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftActivityTabVisualContent() }
    }
}

@Page("compose_home_activity_visual", supportInLocal = true)
internal class ComposeHomeActivityVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeActivityVisualContent() }
    }
}

// Activity as the web ThreadsInbox list layout, with and
// without sidebar pins (the Pinned / All conversations sections only appear with pins).
@Page("compose_home_activity_conversation_list_visual", supportInLocal = true)
internal class ComposeHomeActivityConversationListVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeActivityConversationListVisualContent(withPins = true) }
    }
}

@Page("compose_home_activity_conversation_list_unpinned_visual", supportInLocal = true)
internal class ComposeHomeActivityConversationListUnpinnedVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeActivityConversationListVisualContent(withPins = false) }
    }
}

@Page("compose_home_create_channel_dialog_visual", supportInLocal = true)
internal class ComposeHomeCreateChannelDialogVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeCreateChannelDialogVisualContent() }
    }
}

@Page("compose_auth_choose_server_visual", supportInLocal = true)
internal class ComposeAuthChooseServerVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthChooseServerVisualContent() }
    }
}

@Page("compose_auth_email_verification_visual", supportInLocal = true)
internal class ComposeAuthEmailVerificationVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthEmailVerificationVisualContent() }
    }
}

@Page("compose_app_shell_restore_splash_visual", supportInLocal = true)
internal class ComposeAppShellRestoreSplashVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AppShellRestoreSplashContent() }
    }
}

@Page("compose_auth_profile_setup_visual", supportInLocal = true)
internal class ComposeAuthProfileSetupVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthProfileSetupVisualScreen() }
    }
}

@Page("compose_create_agent_dialog_visual", supportInLocal = true)
internal class ComposeCreateAgentDialogVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { CreateAgentDialogVisualContent() }
    }
}

@Page("compose_tokens_palette_visual", supportInLocal = true)
internal class TokenPaletteVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { TokenPaletteVisualContent() }
    }
}

@Page("compose_ui_segmented_control_visual", supportInLocal = true)
internal class ComposeUiSegmentedControlVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiSegmentedControlVisualContent() } }
    }
}

@Page("compose_ui_button_visual", supportInLocal = true)
internal class ComposeUiButtonVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiButtonVisualContent() } }
    }
}

@Page("compose_ui_button_theme_visual", supportInLocal = true)
internal class ComposeUiButtonThemeVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiButtonThemeVisualContent() }
    }
}

// raft-ui 0.6.0 removed the elegant register: web renders the raft look for both the
// base and the ".elegant" case ids (slock #10148), so both pages pin raftLightTheme and
// render the same primitives. The ".elegant" page id is kept for the case manifest.
@Page("compose_ui_button_elegant_visual", supportInLocal = true)
internal class ComposeUiButtonElegantVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiButtonVisualContent() } }
    }
}

@Page("compose_ui_card_visual", supportInLocal = true)
internal class ComposeUiCardVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiCardVisualContent() } }
    }
}

// Same raft card as compose_ui_card_visual (the ".elegant" case id is kept; see button).
@Page("compose_ui_card_elegant_visual", supportInLocal = true)
internal class ComposeUiCardElegantVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiCardVisualContent() } }
    }
}

@Page("compose_ui_form_field_visual", supportInLocal = true)
internal class ComposeUiFormFieldVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiFormFieldVisualContent() } }
    }
}

// Same raft form fields as compose_ui_form_field_visual (".elegant" case id kept).
@Page("compose_ui_form_field_elegant_visual", supportInLocal = true)
internal class ComposeUiFormFieldElegantVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiFormFieldVisualContent() } }
    }
}

@Page("compose_ui_textarea_visual", supportInLocal = true)
internal class ComposeUiTextareaVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiTextareaVisualContent() } }
    }
}

// Same raft textarea as compose_ui_textarea_visual (".elegant" case id kept).
@Page("compose_ui_textarea_elegant_visual", supportInLocal = true)
internal class ComposeUiTextareaElegantVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiTextareaVisualContent() } }
    }
}

@Page("compose_ui_checkbox_visual", supportInLocal = true)
internal class ComposeUiCheckboxVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiCheckboxVisualContent() } }
    }
}

// Same raft checkbox as compose_ui_checkbox_visual (".elegant" case id kept).
@Page("compose_ui_checkbox_elegant_visual", supportInLocal = true)
internal class ComposeUiCheckboxElegantVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiCheckboxVisualContent() } }
    }
}

@Page("compose_ui_check_marker_visual", supportInLocal = true)
internal class ComposeUiCheckMarkerVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiCheckMarkerVisualContent() } }
    }
}

@Page("compose_ui_attention_dot_visual", supportInLocal = true)
internal class ComposeUiAttentionDotVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiAttentionDotVisualContent() } }
    }
}

@Page("compose_ui_status_dot_visual", supportInLocal = true)
internal class ComposeUiStatusDotVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiStatusDotVisualContent() } }
    }
}

@Page("compose_ui_progress_bar_visual", supportInLocal = true)
internal class ComposeUiProgressBarVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiProgressBarVisualContent() } }
    }
}

@Page("compose_ui_skeleton_visual", supportInLocal = true)
internal class ComposeUiSkeletonVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiSkeletonVisualContent() } }
    }
}

@Page("compose_ui_spinner_visual", supportInLocal = true)
internal class ComposeUiSpinnerVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiSpinnerVisualContent() } }
    }
}

@Page("compose_ui_slug_input_visual", supportInLocal = true)
internal class ComposeUiSlugInputVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiSlugInputVisualContent() } }
    }
}

@Page("compose_ui_section_eyebrow_visual", supportInLocal = true)
internal class ComposeUiSectionEyebrowVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiSectionEyebrowVisualContent() } }
    }
}

@Page("compose_ui_selection_popover_visual", supportInLocal = true)
internal class ComposeUiSelectionPopoverVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiSelectionPopoverVisualContent() } }
    }
}

@Page("compose_ui_menu_item_visual", supportInLocal = true)
internal class ComposeUiMenuItemVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiMenuItemVisualContent() } }
    }
}

@Page("compose_ui_select_visual", supportInLocal = true)
internal class ComposeUiSelectVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiSelectVisualContent() } }
    }
}

@Page("compose_ui_badge_visual", supportInLocal = true)
internal class ComposeUiBadgeVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiBadgeVisualContent() } }
    }
}

@Page("compose_ui_section_header_visual", supportInLocal = true)
internal class ComposeUiSectionHeaderVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiSectionHeaderVisualContent() } }
    }
}

@Page("compose_ui_surface_list_item_visual", supportInLocal = true)
internal class ComposeUiSurfaceListItemVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiSurfaceListItemVisualContent() } }
    }
}

@Page("compose_ui_avatar_list_row_visual", supportInLocal = true)
internal class ComposeUiAvatarListRowVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftThemeProvider(theme = raftLightTheme) { UiAvatarListRowVisualContent() } }
    }
}

@Page("compose_raft_ui_banner_visual", supportInLocal = true)
internal class ComposeRaftUiBannerVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiBannerVisualContent() }
    }
}

@Page("compose_raft_ui_empty_state_visual", supportInLocal = true)
internal class ComposeRaftUiEmptyStateVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiEmptyStateVisualContent() }
    }
}

@Page("compose_raft_ui_typography_visual", supportInLocal = true)
internal class ComposeRaftUiTypographyVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiTypographyVisualContent() }
    }
}

@Page("compose_raft_ui_field_input_visual", supportInLocal = true)
internal class ComposeRaftUiFieldInputVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiFieldInputVisualContent() }
    }
}

@Page("compose_raft_ui_avatar_visual", supportInLocal = true)
internal class ComposeRaftUiAvatarVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiAvatarVisualContent() }
    }
}

@Page("compose_raft_ui_button_badge_visual", supportInLocal = true)
internal class ComposeRaftUiButtonBadgeVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiButtonBadgeVisualContent() }
    }
}

@Page("compose_raft_ui_checkbox_status_visual", supportInLocal = true)
internal class ComposeRaftUiCheckboxStatusVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiCheckboxStatusVisualContent() }
    }
}

@Page("compose_raft_ui_textarea_visual", supportInLocal = true)
internal class ComposeRaftUiTextareaVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiTextareaVisualContent() }
    }
}

@Page("compose_raft_ui_tabs_visual", supportInLocal = true)
internal class ComposeRaftUiTabsVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiTabsVisualContent() }
    }
}

@Page("compose_raft_ui_segmented_control_visual", supportInLocal = true)
internal class ComposeRaftUiSegmentedControlVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiSegmentedControlVisualContent() }
    }
}

@Page("compose_raft_ui_label_radio_visual", supportInLocal = true)
internal class ComposeRaftUiLabelRadioVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiLabelRadioVisualContent() }
    }
}

@Page("compose_raft_ui_select_visual", supportInLocal = true)
internal class ComposeRaftUiSelectVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiSelectVisualContent() }
    }
}

@Page("compose_raft_ui_menu_surfaces_visual", supportInLocal = true)
internal class ComposeRaftUiMenuSurfacesVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiMenuSurfacesVisualContent() }
    }
}

@Page("compose_raft_ui_popover_surface_visual", supportInLocal = true)
internal class ComposeRaftUiPopoverSurfaceVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiPopoverSurfaceVisualContent() }
    }
}

@Page("compose_raft_ui_combobox_surface_visual", supportInLocal = true)
internal class ComposeRaftUiComboboxSurfaceVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiComboboxSurfaceVisualContent() }
    }
}

@Page("compose_raft_ui_notification_center_visual", supportInLocal = true)
internal class ComposeRaftUiNotificationCenterVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiNotificationCenterVisualContent() }
    }
}

@Page("compose_raft_ui_interaction_states_visual", supportInLocal = true)
internal class ComposeRaftUiInteractionStatesVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiInteractionStatesVisualContent() }
    }
}

@Page("compose_raft_ui_controlled_open_overlays_visual", supportInLocal = true)
internal class ComposeRaftUiControlledOpenOverlaysVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiControlledOpenOverlaysVisualContent() }
    }
}

@Page("compose_raft_ui_wip_toggle_group_visual", supportInLocal = true)
internal class ComposeRaftUiWipToggleGroupVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiWipToggleGroupVisualContent() }
    }
}

@Page("compose_raft_ui_wip_list_panels_visual", supportInLocal = true)
internal class ComposeRaftUiWipListPanelsVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiWipListPanelsVisualContent() }
    }
}

@Page("compose_raft_ui_wip_message_cards_visual", supportInLocal = true)
internal class ComposeRaftUiWipMessageCardsVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiWipMessageCardsVisualContent() }
    }
}

@Page("compose_raft_ui_wip_overlay_shells_visual", supportInLocal = true)
internal class ComposeRaftUiWipOverlayShellsVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { RaftUiWipOverlayShellsVisualContent() }
    }
}

@Page("compose_members_agent_detail_visual", supportInLocal = true)
internal class ComposeMembersAgentDetailVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { MembersAgentDetailVisualContent() }
    }
}

@Page("compose_members_restart_dialog_visual", supportInLocal = true)
internal class ComposeMembersRestartDialogVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val actionKey = pageData.params.optString("action")
        val agentName = pageData.params.optString("agentName").ifBlank { "沈括" }
        setContent {
            var selected by remember {
                mutableStateOf(
                    MembersAgentLifecycleAction.entries.firstOrNull { it.actionKey == actionKey }
                        ?: MembersAgentLifecycleAction.Restart
                )
            }
            MembersRestartResetDialog(
                agentName = agentName,
                selectedAction = selected,
                busy = false,
                errorMessage = null,
                onSelectedActionChange = { selected = it },
                onConfirm = {},
                onDismiss = {}
            )
        }
    }
}

@Page("compose_announcement_modal_visual", supportInLocal = true)
internal class ComposeAnnouncementModalVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val pageIndex = pageData.params.optInt("pageIndex", 0)
        setContent {
            SharedAnnouncementModal(
                announcement =
                    build.raft.app.data.AnnouncementItem(
                        id = "visual-announcement",
                        title = "Raft 1.13 更新",
                        body = "",
                        pages =
                            listOf(
                                build.raft.app.data.AnnouncementPageItem(
                                    title = "新的动态页",
                                    body = "动态页现在按时间线展示提及、回复和任务变化。"
                                ),
                                build.raft.app.data.AnnouncementPageItem(
                                    title = "大屏模式",
                                    body = "平板和折叠屏上可以同时看到列表和详情。"
                                ),
                                build.raft.app.data.AnnouncementPageItem(
                                    title = null,
                                    body = "更多改动见更新日志。"
                                )
                            )
                    ),
                onDismiss = {},
                initialPageIndex = pageIndex
            )
        }
    }
}

@Page("compose_home_sidebar_row_states_visual", supportInLocal = true)
internal class ComposeHomeSidebarRowStatesVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSidebarRowStatesVisualContent() }
    }
}

@Page("compose_thread_inline_audio_controls_visual", supportInLocal = true)
internal class ComposeThreadInlineAudioControlsVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            Column(
                modifier = androidx.compose.ui.Modifier.padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
            ) {
                // Paused at the start, playing mid-way, and finished.
                build.raft.app.components.RaftDesignSystemTheme {
                    ThreadInlineAudioControls(false, true, 0, 135_000, {}, {})
                    ThreadInlineAudioControls(true, true, 42_000, 135_000, {}, {})
                    ThreadInlineAudioControls(false, true, 135_000, 135_000, {}, {})
                }
            }
        }
    }
}

@Page("compose_tasks_panel_visual", supportInLocal = true)
internal class ComposeTasksPanelVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        val fixture = pageData.params.optString("fixture")
        setContent { TasksPanelVisualContent(fixture = fixture) }
    }
}

@Page("compose_tasks_status_menu_visual", supportInLocal = true)
internal class ComposeTasksStatusMenuVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { TasksStatusMenuVisualContent() }
    }
}
