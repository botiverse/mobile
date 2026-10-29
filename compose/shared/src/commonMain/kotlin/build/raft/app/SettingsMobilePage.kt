package build.raft.app

import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.app.resources.*
import build.raft.app.components.RaftSwitchButton
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.MOBILE_ACTION_LANGUAGE
import build.raft.app.data.MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES
import build.raft.app.data.MOBILE_ACTION_COMPACT_MESSAGES
import build.raft.app.data.MOBILE_ACTION_LONG_MESSAGE_COLLAPSE
import build.raft.app.data.MOBILE_ACTION_NEW_MESSAGE_MENU
import build.raft.app.data.MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE
import build.raft.app.data.MOBILE_ACTION_COMPOSER_SEND_KEY
import build.raft.app.data.MOBILE_ACTION_LINK_OPEN_MODE
import build.raft.app.data.MOBILE_ACTION_MESSAGE_ALIGNMENT
import build.raft.app.data.MOBILE_ACTION_DESKTOP_MODE
import build.raft.app.data.MOBILE_ACTION_MULTI_SHELL_HOST
import build.raft.app.data.MOBILE_ACTION_SERVER_DRAWER
import build.raft.app.data.MOBILE_ACTION_HOME_SERVER_AVATAR
import build.raft.app.data.MOBILE_ACTION_THREAD_REVERSE_LAYOUT
import build.raft.app.data.AppLocale
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsMobileContent(
    state: ScreenLoadState<SettingsMobileSnapshot>,
    actionState: SharedSettingsActionState?,
    refreshing: Boolean,
    bottomContentPadding: Dp,
    onAlignmentChange: (String?) -> Unit,
    onLinkOpenModeChange: (String?) -> Unit,
    onCompactConsecutiveMessagesChange: (Boolean) -> Unit,
    onLongMessageCollapseChange: (Boolean) -> Unit,
    onNewMessageMenuChange: (Boolean) -> Unit,
    onConversationForwardGestureChange: (Boolean) -> Unit,
    onActivityTaskStatusBadgesChange: (Boolean) -> Unit,
    onComposerSendKeyChange: (Boolean) -> Unit,
    onMultiShellHostChange: (Boolean) -> Unit,
    onDesktopModeOnLargeScreenChange: (Boolean) -> Unit,
    onServerDrawerChange: (Boolean) -> Unit,
    onHomeServerAvatarChange: (Boolean) -> Unit,
    onThreadReverseLayoutChange: (Boolean) -> Unit = {},
    diagnosticsLogger: (String) -> Unit = {},
) {
    val snapshot =
        when (state) {
            is ScreenLoadState.Content -> state.data
            else -> null
        }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).raftSafeAreaHorizontalPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (snapshot != null) {
            item {
                SettingsMessageAlignmentCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onAlignmentChange = onAlignmentChange
                )
            }
            item {
                SettingsLinkOpenModeCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onLinkOpenModeChange = onLinkOpenModeChange
                )
            }
            item {
                SettingsCompactMessagesCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onCompactConsecutiveMessagesChange = onCompactConsecutiveMessagesChange
                )
            }
            item {
                SettingsLongMessageCollapseCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onLongMessageCollapseChange = onLongMessageCollapseChange
                )
            }
            if (snapshot.threadReverseLayout.available) {
                item {
                    SettingsThreadReverseLayoutCard(
                        snapshot = snapshot,
                        actionState = actionState,
                        onThreadReverseLayoutChange = onThreadReverseLayoutChange
                    )
                }
            }
            item {
                SettingsNewMessageMenuCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onNewMessageMenuChange = onNewMessageMenuChange
                )
            }
            if (snapshot.conversationForwardGesture.available) {
                item {
                    SettingsConversationForwardGestureCard(
                        snapshot = snapshot,
                        actionState = actionState,
                        onConversationForwardGestureChange = onConversationForwardGestureChange
                    )
                }
            }
            item {
                SettingsActivityTaskStatusBadgesCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onActivityTaskStatusBadgesChange = onActivityTaskStatusBadgesChange
                )
            }
            item {
                SettingsComposerSendKeyCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onComposerSendKeyChange = onComposerSendKeyChange
                )
            }
            if (raftPlatformSupportsMultiShellHost()) {
                item {
                    SettingsMultiShellHostCard(
                        snapshot = snapshot,
                        actionState = actionState,
                        onMultiShellHostChange = onMultiShellHostChange
                    )
                }
            }
            item {
                SettingsDesktopModeCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onDesktopModeOnLargeScreenChange = onDesktopModeOnLargeScreenChange,
                    diagnosticsLogger = diagnosticsLogger
                )
            }
            item {
                SettingsServerDrawerCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onServerDrawerChange = onServerDrawerChange,
                    diagnosticsLogger = diagnosticsLogger
                )
            }
            item {
                SettingsHomeServerAvatarCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onHomeServerAvatarChange = onHomeServerAvatarChange
                )
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun SettingsMultiShellHostCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onMultiShellHostChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_MULTI_SHELL_HOST && actionState.inProgress
    val preference = snapshot.multiShellHost
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.AppWindow,
        title = stringResource(Res.string.settings_mobile_multi_shell_host)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onMultiShellHostChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsConnectedAppsBadge(stringResource(Res.string.settings_badge_beta))
                    Text(
                        preference.localizedToggleStatus(),
                        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray
                    )
                }
                Text(
                    stringResource(Res.string.settings_mobile_multi_shell_host_detail),
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftSwitchButton(checked = preference.enabled, enabled = preference.available && !busy)
        }
        if (actionState?.action == MOBILE_ACTION_MULTI_SHELL_HOST && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsDesktopModeCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onDesktopModeOnLargeScreenChange: (Boolean) -> Unit,
    diagnosticsLogger: (String) -> Unit = {},
) {
    val busy = actionState?.action == MOBILE_ACTION_DESKTOP_MODE && actionState.inProgress
    val preference = snapshot.desktopModeOnLargeScreen
    LaunchedEffect(preference.available, preference.enabled, busy) {
        diagnosticsLogger(
            "desktop_mode_card_state enabled=${preference.enabled} " +
                "available=${preference.available} busy=$busy"
        )
    }
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.Monitor,
        title = stringResource(Res.string.settings_mobile_desktop_mode)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        diagnosticsLogger(
                            "desktop_mode_toggle_click target=${!preference.enabled} " +
                                "available=${preference.available} busy=$busy"
                        )
                        onDesktopModeOnLargeScreenChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    preference.localizedToggleStatus(),
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray
                )
                Text(
                    stringResource(Res.string.settings_mobile_desktop_mode_detail),
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftSwitchButton(checked = preference.enabled, enabled = preference.available && !busy)
        }
        if (actionState?.action == MOBILE_ACTION_DESKTOP_MODE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsServerDrawerCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onServerDrawerChange: (Boolean) -> Unit,
    diagnosticsLogger: (String) -> Unit = {},
) {
    val busy = actionState?.action == MOBILE_ACTION_SERVER_DRAWER && actionState.inProgress
    val preference = snapshot.serverDrawer
    LaunchedEffect(preference.available, preference.enabled, busy) {
        diagnosticsLogger(
            "server_drawer_card_state enabled=${preference.enabled} " +
                "available=${preference.available} busy=$busy"
        )
    }
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.Menu,
        title = stringResource(Res.string.settings_mobile_server_drawer)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        diagnosticsLogger(
                            "server_drawer_toggle_click target=${!preference.enabled} " +
                                "available=${preference.available} busy=$busy"
                        )
                        onServerDrawerChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsConnectedAppsBadge(stringResource(Res.string.settings_badge_beta))
                    Text(
                        preference.localizedToggleStatus(),
                        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray
                    )
                }
                Text(
                    stringResource(Res.string.settings_mobile_server_drawer_detail),
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftSwitchButton(checked = preference.enabled, enabled = preference.available && !busy)
        }
        if (actionState?.action == MOBILE_ACTION_SERVER_DRAWER && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsHomeServerAvatarCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onHomeServerAvatarChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_HOME_SERVER_AVATAR && actionState.inProgress
    val preference = snapshot.homeServerAvatar
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.Image,
        title = stringResource(Res.string.settings_mobile_home_server_avatar)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onHomeServerAvatarChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    preference.localizedToggleStatus(),
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray
                )
                Text(
                    stringResource(Res.string.settings_mobile_home_server_avatar_detail),
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftSwitchButton(checked = preference.enabled, enabled = preference.available && !busy)
        }
        if (actionState?.action == MOBILE_ACTION_HOME_SERVER_AVATAR && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

/** Task #154: realtime over NetworkKMM's curl Socket.IO client; shows the effective value. */
@Composable
private fun SettingsThreadReverseLayoutCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onThreadReverseLayoutChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_THREAD_REVERSE_LAYOUT && actionState.inProgress
    val preference = snapshot.threadReverseLayout
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.MessageSquare,
        title = stringResource(Res.string.settings_mobile_thread_reverse_layout)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onThreadReverseLayoutChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    preference.localizedToggleStatus(),
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray
                )
                Text(
                    stringResource(Res.string.settings_mobile_thread_reverse_layout_detail),
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftSwitchButton(checked = preference.enabled, enabled = preference.available && !busy)
        }
        if (actionState?.action == MOBILE_ACTION_THREAD_REVERSE_LAYOUT && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
internal fun SettingsAppLanguageCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onLocaleChange: (String) -> Unit
) {
    val preference = snapshot.localePreference
    val busy = actionState?.action == MOBILE_ACTION_LANGUAGE && actionState.inProgress
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.Languages,
        title = stringResource(Res.string.settings_app_language_title)
    ) {
        SettingsSegmentedControlField(
            detail = stringResource(Res.string.settings_app_language_detail),
            selectedValue = preference.selectedKey.ifBlank { AppLocale.System.languageTag },
            options =
                listOf(
                    SegmentedControlOption(
                        AppLocale.System.languageTag,
                        stringResource(Res.string.settings_app_language_system)
                    ),
                    SegmentedControlOption(
                        AppLocale.English.languageTag,
                        stringResource(Res.string.settings_app_language_english)
                    ),
                    SegmentedControlOption(
                        AppLocale.SimplifiedChinese.languageTag,
                        stringResource(Res.string.settings_app_language_simplified_chinese)
                    ),
                    SegmentedControlOption(
                        AppLocale.Japanese.languageTag,
                        stringResource(Res.string.settings_app_language_japanese)
                    )
                ),
            onValueSelected = { selected -> selected?.let(onLocaleChange) },
            enabled = preference.available && !busy
        )
        if (actionState?.action == MOBILE_ACTION_LANGUAGE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsMessageAlignmentCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onAlignmentChange: (String?) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_MESSAGE_ALIGNMENT && actionState.inProgress
    val selectedKey = snapshot.messageAlignmentPreference.selectedKey
    SettingsFormSectionCard(iconName = RaftLucideIconName.Columns3, title = stringResource(Res.string.settings_message_alignment_title)) {
        SettingsSegmentedControlField(
            detail = stringResource(Res.string.settings_message_alignment_detail),
            selectedValue = selectedKey.ifBlank { "left" },
            options =
                listOf(
                    SegmentedControlOption("left", stringResource(Res.string.settings_message_alignment_left)),
                    SegmentedControlOption("right", stringResource(Res.string.settings_message_alignment_right))
                ),
            onValueSelected = onAlignmentChange,
            enabled = snapshot.messageAlignmentPreference.available && !busy
        )
        if (actionState?.action == MOBILE_ACTION_MESSAGE_ALIGNMENT && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsLinkOpenModeCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onLinkOpenModeChange: (String?) -> Unit
) {
    val preference = snapshot.linkOpenMode
    val busy = actionState?.action == MOBILE_ACTION_LINK_OPEN_MODE && actionState.inProgress
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.ArrowUpRight,
        title = stringResource(Res.string.settings_mobile_link_open_mode_title)
    ) {
        SettingsSegmentedControlField(
            detail = stringResource(Res.string.settings_mobile_link_open_mode_detail),
            selectedValue = preference.selectedKey.ifBlank { LinkOpenMode.Default.storageKey },
            options =
                listOf(
                    SegmentedControlOption(
                        LinkOpenMode.SystemBrowser.storageKey,
                        stringResource(Res.string.settings_mobile_link_open_mode_system_browser)
                    ),
                    SegmentedControlOption(
                        LinkOpenMode.InAppBrowser.storageKey,
                        stringResource(Res.string.settings_mobile_link_open_mode_in_app_browser)
                    )
                ),
            onValueSelected = onLinkOpenModeChange,
            enabled = preference.available && !busy
        )
        if (actionState?.action == MOBILE_ACTION_LINK_OPEN_MODE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsCompactMessagesCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onCompactConsecutiveMessagesChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_COMPACT_MESSAGES && actionState.inProgress
    val preference = snapshot.compactConsecutiveMessages
    SettingsFormSectionCard(iconName = RaftLucideIconName.MessageSquare, title = stringResource(Res.string.settings_mobile_compact_messages)) {
        SettingsFormDetailText(stringResource(Res.string.settings_mobile_compact_detail))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onCompactConsecutiveMessagesChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preference.localizedToggleStatus(),
                    style = RaftType.Button.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(Res.string.settings_mobile_compact_enabled)
                        } else {
                            stringResource(Res.string.settings_mobile_compact_disabled)
                        },
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftSwitchButton(
                checked = preference.enabled,
                enabled = preference.available && !busy
            )
        }
        if (actionState?.action == MOBILE_ACTION_COMPACT_MESSAGES && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsLongMessageCollapseCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onLongMessageCollapseChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_LONG_MESSAGE_COLLAPSE && actionState.inProgress
    val preference = snapshot.longMessageCollapse
    SettingsFormSectionCard(iconName = RaftLucideIconName.ClipboardList, title = stringResource(Res.string.settings_mobile_long_message_collapse)) {
        SettingsFormDetailText(stringResource(Res.string.settings_mobile_long_message_collapse_detail))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onLongMessageCollapseChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preference.localizedToggleStatus(),
                    style = RaftType.Button.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack
                )
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(Res.string.settings_mobile_long_message_collapse_enabled)
                        } else {
                            stringResource(Res.string.settings_mobile_long_message_collapse_disabled)
                        },
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray
                )
            }
            RaftSwitchButton(
                checked = preference.enabled,
                enabled = preference.available && !busy
            )
        }
        if (actionState?.action == MOBILE_ACTION_LONG_MESSAGE_COLLAPSE && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsNewMessageMenuCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onNewMessageMenuChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_NEW_MESSAGE_MENU && actionState.inProgress
    val preference = snapshot.newMessageMenu
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.PanelBottomOpen,
        title = stringResource(Res.string.settings_mobile_new_message_menu)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onNewMessageMenuChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsConnectedAppsBadge(stringResource(Res.string.settings_badge_beta))
                    Text(
                        preference.localizedToggleStatus(),
                        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray
                    )
                }
                Text(
                    stringResource(Res.string.settings_mobile_new_message_menu_detail),
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftSwitchButton(
                checked = preference.enabled,
                enabled = preference.available && !busy
            )
        }
        if (actionState?.action == MOBILE_ACTION_NEW_MESSAGE_MENU && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsConversationForwardGestureCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onConversationForwardGestureChange: (Boolean) -> Unit
) {
    val busy =
        actionState?.action == MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE && actionState.inProgress
    val preference = snapshot.conversationForwardGesture
    val accessibilityDescription =
        stringResource(Res.string.settings_mobile_conversation_forward_gesture_accessibility)
    val localizedStateDescription = preference.localizedToggleStatus()
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.Redo2,
        title = stringResource(Res.string.settings_mobile_conversation_forward_gesture)
    ) {
        SettingsFormDetailText(
            stringResource(Res.string.settings_mobile_conversation_forward_gesture_detail)
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = accessibilityDescription
                        stateDescription = localizedStateDescription
                    }
                    .clickable(enabled = preference.available && !busy) {
                        onConversationForwardGestureChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsConnectedAppsBadge(stringResource(Res.string.settings_badge_beta))
                    Text(
                        localizedStateDescription,
                        style = RaftType.Button.copy(color = RaftColor.TextBlack),
                        color = RaftColor.TextBlack
                    )
                }
            }
            RaftSwitchButton(
                checked = preference.enabled,
                enabled = preference.available && !busy
            )
        }
        if (
            actionState?.action == MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE &&
            actionState.errorCode != null
        ) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsActivityTaskStatusBadgesCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onActivityTaskStatusBadgesChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES && actionState.inProgress
    val preference = snapshot.activityTaskStatusBadges
    SettingsFormSectionCard(iconName = RaftLucideIconName.ClipboardCheck, title = stringResource(Res.string.settings_mobile_activity_task_badges)) {
        SettingsFormDetailText(stringResource(Res.string.settings_mobile_activity_task_badges_detail))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onActivityTaskStatusBadgesChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preference.localizedToggleStatus(),
                    style = RaftType.Button.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(Res.string.settings_mobile_activity_task_badges_enabled)
                        } else {
                            stringResource(Res.string.settings_mobile_activity_task_badges_disabled)
                        },
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftSwitchButton(
                checked = preference.enabled,
                enabled = preference.available && !busy
            )
        }
        if (actionState?.action == MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsComposerSendKeyCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onComposerSendKeyChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_COMPOSER_SEND_KEY && actionState.inProgress
    val preference = snapshot.composerSendKey
    SettingsFormSectionCard(iconName = RaftLucideIconName.Send, title = stringResource(Res.string.settings_mobile_composer_key)) {
        SettingsFormDetailText(stringResource(Res.string.settings_mobile_composer_key_detail))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onComposerSendKeyChange(!preference.enabled)
                    }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(Res.string.settings_mobile_composer_key_send)
                        } else {
                            stringResource(Res.string.settings_mobile_composer_key_new_line)
                        },
                    style = RaftType.Button.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(Res.string.settings_mobile_composer_key_enabled)
                        } else {
                            stringResource(Res.string.settings_mobile_composer_key_disabled)
                        },
                    style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                    color = RaftColor.TextMutedGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftSwitchButton(
                checked = preference.enabled,
                enabled = preference.available && !busy
            )
        }
        if (actionState?.action == MOBILE_ACTION_COMPOSER_SEND_KEY && actionState.errorCode != null) {
            SettingsFormErrorText(actionState)
        }
    }
}

@Composable
private fun SettingsMobileToggleSnapshot.localizedToggleStatus(): String =
    when {
        !available -> stringResource(Res.string.settings_state_unavailable)
        enabled -> stringResource(Res.string.settings_mobile_status_enabled)
        else -> stringResource(Res.string.settings_state_off)
    }
