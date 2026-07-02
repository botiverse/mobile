package build.raft.app

import build.raft.app.base.BasePager
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.core.annotations.Page

@Page("compose_home_titlebar_visual", supportInLocal = true)
internal class ComposeHomeTitlebarVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeTitlebarVisualScreen() }
    }
}

@Page("compose_home_notification_center_visual", supportInLocal = true)
internal class ComposeHomeNotificationCenterVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeNotificationCenterVisualScreen() }
    }
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

@Page("compose_thread_reaction_atlas_visual", supportInLocal = true)
internal class ComposeThreadReactionAtlasVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ThreadReactionAtlasVisualScreen() }
    }
}

@Page("compose_channel_members_panel_visual", supportInLocal = true)
internal class ComposeChannelMembersPanelVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelMembersPanelVisualScreen() }
    }
}

@Page("compose_channel_settings_panel_visual", supportInLocal = true)
internal class ComposeChannelSettingsPanelVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { ChannelSettingsPanelVisualScreen() }
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

@Page("compose_settings_row_visual", supportInLocal = true)
internal class ComposeSettingsRowVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsRowVisualContent() }
    }
}

@Page("compose_settings_page_visual", supportInLocal = true)
internal class ComposeSettingsPageVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { SettingsPageVisualContent() }
    }
}

@Page("compose_home_search_visual", supportInLocal = true)
internal class ComposeHomeSearchVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchVisualContent() }
    }
}

@Page("compose_home_search_channel_dropdown_visual", supportInLocal = true)
internal class ComposeHomeSearchChannelDropdownVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSearchVisualContent(initialOpenFilterId = "channel") }
    }
}

@Page("compose_home_saved_visual", supportInLocal = true)
internal class ComposeHomeSavedVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeSavedVisualContent() }
    }
}

@Page("compose_home_activity_visual", supportInLocal = true)
internal class ComposeHomeActivityVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { HomeActivityVisualContent() }
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

@Page("compose_auth_login_inputs", supportInLocal = true)
internal class ComposeAuthLoginInputsVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthLoginInputsVisualScreen() }
    }
}

@Page("compose_auth_register_inputs", supportInLocal = true)
internal class ComposeAuthRegisterInputsVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { AuthRegisterInputsVisualScreen() }
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
        setContent { UiSegmentedControlVisualContent() }
    }
}

@Page("compose_ui_button_visual", supportInLocal = true)
internal class ComposeUiButtonVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiButtonVisualContent() }
    }
}

@Page("compose_ui_form_field_visual", supportInLocal = true)
internal class ComposeUiFormFieldVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiFormFieldVisualContent() }
    }
}

@Page("compose_ui_textarea_visual", supportInLocal = true)
internal class ComposeUiTextareaVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiTextareaVisualContent() }
    }
}

@Page("compose_ui_checkbox_visual", supportInLocal = true)
internal class ComposeUiCheckboxVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiCheckboxVisualContent() }
    }
}

@Page("compose_ui_check_marker_visual", supportInLocal = true)
internal class ComposeUiCheckMarkerVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiCheckMarkerVisualContent() }
    }
}

@Page("compose_ui_attention_dot_visual", supportInLocal = true)
internal class ComposeUiAttentionDotVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiAttentionDotVisualContent() }
    }
}

@Page("compose_ui_status_dot_visual", supportInLocal = true)
internal class ComposeUiStatusDotVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiStatusDotVisualContent() }
    }
}

@Page("compose_ui_progress_bar_visual", supportInLocal = true)
internal class ComposeUiProgressBarVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiProgressBarVisualContent() }
    }
}

@Page("compose_ui_skeleton_visual", supportInLocal = true)
internal class ComposeUiSkeletonVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiSkeletonVisualContent() }
    }
}

@Page("compose_ui_spinner_visual", supportInLocal = true)
internal class ComposeUiSpinnerVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiSpinnerVisualContent() }
    }
}

@Page("compose_ui_slug_input_visual", supportInLocal = true)
internal class ComposeUiSlugInputVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiSlugInputVisualContent() }
    }
}

@Page("compose_ui_section_eyebrow_visual", supportInLocal = true)
internal class ComposeUiSectionEyebrowVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiSectionEyebrowVisualContent() }
    }
}

@Page("compose_ui_selection_popover_visual", supportInLocal = true)
internal class ComposeUiSelectionPopoverVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiSelectionPopoverVisualContent() }
    }
}

@Page("compose_ui_menu_item_visual", supportInLocal = true)
internal class ComposeUiMenuItemVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiMenuItemVisualContent() }
    }
}

@Page("compose_ui_select_visual", supportInLocal = true)
internal class ComposeUiSelectVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiSelectVisualContent() }
    }
}

@Page("compose_ui_badge_visual", supportInLocal = true)
internal class ComposeUiBadgeVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiBadgeVisualContent() }
    }
}

@Page("compose_ui_section_header_visual", supportInLocal = true)
internal class ComposeUiSectionHeaderVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiSectionHeaderVisualContent() }
    }
}

@Page("compose_ui_surface_list_item_visual", supportInLocal = true)
internal class ComposeUiSurfaceListItemVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiSurfaceListItemVisualContent() }
    }
}

@Page("compose_ui_avatar_list_row_visual", supportInLocal = true)
internal class ComposeUiAvatarListRowVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { UiAvatarListRowVisualContent() }
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

@Page("compose_members_agent_detail_visual", supportInLocal = true)
internal class ComposeMembersAgentDetailVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { MembersAgentDetailVisualContent() }
    }
}
