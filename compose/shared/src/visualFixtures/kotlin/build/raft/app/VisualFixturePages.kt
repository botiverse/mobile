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

@Page("compose_members_agent_detail_visual", supportInLocal = true)
internal class ComposeMembersAgentDetailVisualPage : BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { MembersAgentDetailVisualContent() }
    }
}
