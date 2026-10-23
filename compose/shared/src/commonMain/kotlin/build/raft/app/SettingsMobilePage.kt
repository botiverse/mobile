package build.raft.app

import build.raft.app.components.RaftSwitchButton
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.MOBILE_ACTION_LANGUAGE
import build.raft.app.data.MOBILE_ACTION_ACTIVITY_TASK_STATUS_BADGES
import build.raft.app.data.MOBILE_ACTION_COMPACT_MESSAGES
import build.raft.app.data.MOBILE_ACTION_LONG_MESSAGE_COLLAPSE
import build.raft.app.data.MOBILE_ACTION_SINGLE_TAP_OPENS_THREAD
import build.raft.app.data.MOBILE_ACTION_NEW_MESSAGE_MENU
import build.raft.app.data.MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE
import build.raft.app.data.MOBILE_ACTION_COMPOSER_SEND_KEY
import build.raft.app.data.MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS
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
import build.raft.app.data.SettingsMobileSelectionSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import com.tencent.tmm.kmmresource.compose.stringResource

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
    onSingleTapOpensThreadChange: (Boolean) -> Unit,
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
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
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
            item {
                SettingsSingleTapOpensThreadCard(
                    snapshot = snapshot,
                    actionState = actionState,
                    onSingleTapOpensThreadChange = onSingleTapOpensThreadChange
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
        title = stringResource(MR.strings.settings_mobile_multi_shell_host)
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
                    SettingsConnectedAppsBadge(stringResource(MR.strings.settings_badge_beta), RaftColor.BrandYellow)
                    Text(
                        preference.localizedToggleStatus(),
                        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray
                    )
                }
                Text(
                    stringResource(MR.strings.settings_mobile_multi_shell_host_detail),
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
        title = stringResource(MR.strings.settings_mobile_desktop_mode)
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
                    stringResource(MR.strings.settings_mobile_desktop_mode_detail),
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
        title = stringResource(MR.strings.settings_mobile_server_drawer)
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
                    SettingsConnectedAppsBadge(stringResource(MR.strings.settings_badge_beta), RaftColor.BrandYellow)
                    Text(
                        preference.localizedToggleStatus(),
                        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray
                    )
                }
                Text(
                    stringResource(MR.strings.settings_mobile_server_drawer_detail),
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
        title = stringResource(MR.strings.settings_mobile_home_server_avatar)
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
                    stringResource(MR.strings.settings_mobile_home_server_avatar_detail),
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
        title = stringResource(MR.strings.settings_mobile_thread_reverse_layout)
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
                    stringResource(MR.strings.settings_mobile_thread_reverse_layout_detail),
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
        title = stringResource(MR.strings.settings_app_language_title)
    ) {
        SettingsSegmentedControlField(
            detail = stringResource(MR.strings.settings_app_language_detail),
            selectedValue = preference.selectedKey.ifBlank { AppLocale.System.languageTag },
            options =
                listOf(
                    SegmentedControlOption(
                        AppLocale.System.languageTag,
                        stringResource(MR.strings.settings_app_language_system)
                    ),
                    SegmentedControlOption(
                        AppLocale.English.languageTag,
                        stringResource(MR.strings.settings_app_language_english)
                    ),
                    SegmentedControlOption(
                        AppLocale.SimplifiedChinese.languageTag,
                        stringResource(MR.strings.settings_app_language_simplified_chinese)
                    ),
                    SegmentedControlOption(
                        AppLocale.Japanese.languageTag,
                        stringResource(MR.strings.settings_app_language_japanese)
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
    SettingsFormSectionCard(iconName = RaftLucideIconName.Columns3, title = stringResource(MR.strings.settings_message_alignment_title)) {
        SettingsSegmentedControlField(
            detail = stringResource(MR.strings.settings_message_alignment_detail),
            selectedValue = selectedKey.ifBlank { "left" },
            options =
                listOf(
                    SegmentedControlOption("left", stringResource(MR.strings.settings_message_alignment_left)),
                    SegmentedControlOption("right", stringResource(MR.strings.settings_message_alignment_right))
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
        iconName = RaftLucideIconName.ExternalLink,
        title = stringResource(MR.strings.settings_mobile_link_open_mode_title)
    ) {
        SettingsSegmentedControlField(
            detail = stringResource(MR.strings.settings_mobile_link_open_mode_detail),
            selectedValue = preference.selectedKey.ifBlank { LinkOpenMode.Default.storageKey },
            options =
                listOf(
                    SegmentedControlOption(
                        LinkOpenMode.SystemBrowser.storageKey,
                        stringResource(MR.strings.settings_mobile_link_open_mode_system_browser)
                    ),
                    SegmentedControlOption(
                        LinkOpenMode.InAppBrowser.storageKey,
                        stringResource(MR.strings.settings_mobile_link_open_mode_in_app_browser)
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
    SettingsFormSectionCard(iconName = RaftLucideIconName.MessageSquare, title = stringResource(MR.strings.settings_mobile_compact_messages)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_compact_detail))
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
                            stringResource(MR.strings.settings_mobile_compact_enabled)
                        } else {
                            stringResource(MR.strings.settings_mobile_compact_disabled)
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
    SettingsFormSectionCard(iconName = RaftLucideIconName.ClipboardList, title = stringResource(MR.strings.settings_mobile_long_message_collapse)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_long_message_collapse_detail))
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
                            stringResource(MR.strings.settings_mobile_long_message_collapse_enabled)
                        } else {
                            stringResource(MR.strings.settings_mobile_long_message_collapse_disabled)
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
private fun SettingsSingleTapOpensThreadCard(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    onSingleTapOpensThreadChange: (Boolean) -> Unit
) {
    val busy = actionState?.action == MOBILE_ACTION_SINGLE_TAP_OPENS_THREAD && actionState.inProgress
    val preference = snapshot.singleTapOpensThread
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.MessageSquare,
        title = stringResource(MR.strings.settings_mobile_single_tap_thread)
    ) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_single_tap_thread_detail))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = preference.available && !busy) {
                        onSingleTapOpensThreadChange(!preference.enabled)
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
                            stringResource(MR.strings.settings_mobile_single_tap_thread_enabled)
                        } else {
                            stringResource(MR.strings.settings_mobile_single_tap_thread_disabled)
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
        if (actionState?.action == MOBILE_ACTION_SINGLE_TAP_OPENS_THREAD && actionState.errorCode != null) {
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
        title = stringResource(MR.strings.settings_mobile_new_message_menu)
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
                    SettingsConnectedAppsBadge(
                        stringResource(MR.strings.settings_badge_beta),
                        RaftColor.BrandYellow
                    )
                    Text(
                        preference.localizedToggleStatus(),
                        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray
                    )
                }
                Text(
                    stringResource(MR.strings.settings_mobile_new_message_menu_detail),
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
        stringResource(MR.strings.settings_mobile_conversation_forward_gesture_accessibility)
    val localizedStateDescription = preference.localizedToggleStatus()
    SettingsFormSectionCard(
        iconName = RaftLucideIconName.Redo2,
        title = stringResource(MR.strings.settings_mobile_conversation_forward_gesture)
    ) {
        SettingsFormDetailText(
            stringResource(MR.strings.settings_mobile_conversation_forward_gesture_detail)
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
                    SettingsConnectedAppsBadge(
                        stringResource(MR.strings.settings_badge_beta),
                        RaftColor.BrandYellow
                    )
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
    SettingsFormSectionCard(iconName = RaftLucideIconName.ClipboardCheck, title = stringResource(MR.strings.settings_mobile_activity_task_badges)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_activity_task_badges_detail))
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
                            stringResource(MR.strings.settings_mobile_activity_task_badges_enabled)
                        } else {
                            stringResource(MR.strings.settings_mobile_activity_task_badges_disabled)
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
    SettingsFormSectionCard(iconName = RaftLucideIconName.Send, title = stringResource(MR.strings.settings_mobile_composer_key)) {
        SettingsFormDetailText(stringResource(MR.strings.settings_mobile_composer_key_detail))
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
                            stringResource(MR.strings.settings_mobile_composer_key_send)
                        } else {
                            stringResource(MR.strings.settings_mobile_composer_key_new_line)
                        },
                    style = RaftType.Button.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text =
                        if (preference.enabled) {
                            stringResource(MR.strings.settings_mobile_composer_key_enabled)
                        } else {
                            stringResource(MR.strings.settings_mobile_composer_key_disabled)
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
        !available -> stringResource(MR.strings.settings_state_unavailable)
        enabled -> stringResource(MR.strings.settings_mobile_status_enabled)
        else -> stringResource(MR.strings.settings_state_off)
    }

internal fun ScreenLoadState<SettingsMobileSnapshot>.toMobilePayload(
    actionState: build.raft.app.data.SharedSettingsActionState?,
    refreshing: Boolean,
    errorStrings: ErrorPresentationStrings
): RaftSharedSettingsPayload {
    val mobile =
        (this as? ScreenLoadState.Content<SettingsMobileSnapshot>)?.data
            ?: return appShellMobilePlaceholderPayload(this, refreshing, errorStrings)
    return RaftSharedSettingsPayload(
        instanceId = "shared-shell-settings-mobile",
        title = "Mobile",
        heroIconName = RaftLucideIconName.Smartphone,
        heroLabel = "Mobile preferences",
        detail =
            if (refreshing) {
                "Refreshing mobile settings..."
            } else {
                "Manage link opening and conversation behavior on this device."
            },
        sections =
            listOf(
                RaftSharedSettingsSection(
                    title = "Conversation display",
                    rows =
                        listOfNotNull(
                            mobile.messageAlignmentPreference.toMobileSelectionRow(
                                key = MOBILE_ACTION_MESSAGE_ALIGNMENT,
                                iconName = RaftLucideIconName.AlignHorizontalSpaceAround,
                                title = "My message alignment",
                                fallbackStatus = "Unsupported",
                                actionState = actionState
                            ),
                            mobile.linkOpenMode.toMobileSelectionRow(
                                key = MOBILE_ACTION_LINK_OPEN_MODE,
                                iconName = RaftLucideIconName.ExternalLink,
                                title = "Open web links",
                                fallbackStatus = "Default browser",
                                actionState = actionState
                            ),
                            mobile.compactConsecutiveMessages.toMobileToggleRow(
                                key = MOBILE_ACTION_COMPACT_MESSAGES,
                                iconName = RaftLucideIconName.MessageSquare,
                                title = "Compact consecutive messages",
                                fallbackStatus = "Unsupported",
                                actionState = actionState,
                                errorStrings = errorStrings
                            ),
                            mobile.longMessageCollapse.toMobileToggleRow(
                                key = MOBILE_ACTION_LONG_MESSAGE_COLLAPSE,
                                iconName = RaftLucideIconName.ClipboardList,
                                title = "Collapse long messages",
                                fallbackStatus = "Off",
                                actionState = actionState,
                                errorStrings = errorStrings
                            ),
                            mobile.singleTapOpensThread.toMobileToggleRow(
                                key = MOBILE_ACTION_SINGLE_TAP_OPENS_THREAD,
                                iconName = RaftLucideIconName.MessageSquare,
                                title = "Single tap opens Thread",
                                fallbackStatus = "Off",
                                actionState = actionState,
                                errorStrings = errorStrings
                            ),
                            mobile.newMessageMenu.toMobileToggleRow(
                                key = MOBILE_ACTION_NEW_MESSAGE_MENU,
                                iconName = RaftLucideIconName.PanelBottomOpen,
                                title = "New message menu",
                                fallbackStatus = "Off",
                                actionState = actionState,
                                errorStrings = errorStrings,
                                badgeLabel = "Beta"
                            ),
                            if (mobile.conversationForwardGesture.available) {
                                mobile.conversationForwardGesture.toMobileToggleRow(
                                    key = MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE,
                                    iconName = RaftLucideIconName.Redo2,
                                    title = "Conversation forward gesture",
                                    fallbackStatus = "Off",
                                    actionState = actionState,
                                    errorStrings = errorStrings,
                                    badgeLabel = "Beta"
                                )
                            } else {
                                null
                            },
                            mobile.foregroundMessageBanners.toMobileToggleRow(
                                key = MOBILE_ACTION_FOREGROUND_MESSAGE_BANNERS,
                                iconName = RaftLucideIconName.MessageCircle,
                                title = "Foreground message banners",
                                fallbackStatus = "Off",
                                actionState = actionState,
                                errorStrings = errorStrings
                            ),
                            mobile.desktopModeOnLargeScreen.toMobileToggleRow(
                                key = MOBILE_ACTION_DESKTOP_MODE,
                                iconName = RaftLucideIconName.Monitor,
                                title = "Desktop mode on large screens",
                                fallbackStatus = "On",
                                actionState = actionState,
                                errorStrings = errorStrings
                            ),
                            mobile.serverDrawer.toMobileToggleRow(
                                key = MOBILE_ACTION_SERVER_DRAWER,
                                iconName = RaftLucideIconName.Menu,
                                title = "Multi-server Home drawer",
                                fallbackStatus = "Off",
                                actionState = actionState,
                                errorStrings = errorStrings,
                                badgeLabel = "Beta"
                            ),
                            mobile.homeServerAvatar.toMobileToggleRow(
                                key = MOBILE_ACTION_HOME_SERVER_AVATAR,
                                iconName = RaftLucideIconName.Image,
                                title = "Show workspace avatar in Home header",
                                fallbackStatus = "Off",
                                actionState = actionState,
                                errorStrings = errorStrings
                            )
                        )
                )
            ) +
                if (raftPlatformSupportsMultiShellHost()) {
                    listOf(
                        RaftSharedSettingsSection(
                            title = "Windowing",
                            rows =
                                listOf(
                                    mobile.multiShellHost.toMobileToggleRow(
                                        key = MOBILE_ACTION_MULTI_SHELL_HOST,
                                        iconName = RaftLucideIconName.AppWindow,
                                        title = "Multiple app windows",
                                        fallbackStatus = "Off",
                                        actionState = actionState,
                                        errorStrings = errorStrings,
                                        badgeLabel = "Beta"
                                    )
                                )
                        )
                    )
                } else {
                    emptyList()
                }
    )
}

private fun appShellMobilePlaceholderPayload(
    state: ScreenLoadState<SettingsMobileSnapshot>,
    refreshing: Boolean,
    errorStrings: ErrorPresentationStrings
): RaftSharedSettingsPayload {
    val status =
        when (state) {
            ScreenLoadState.Loading -> if (refreshing) "Refreshing" else "Loading"
            is ScreenLoadState.Empty -> "Unavailable"
            is ScreenLoadState.Error -> "Error"
            is ScreenLoadState.NoPermission -> "No permission"
            is ScreenLoadState.Content -> "Ready"
        }
    val detail =
        when (state) {
            is ScreenLoadState.Error -> state.error.userFacingMessage(errorStrings)
            is ScreenLoadState.NoPermission -> state.blocker.userFacingMessage(errorStrings)
            is ScreenLoadState.Empty -> state.reason.userFacingMessage(errorStrings)
            else -> "Loading mobile settings."
        }
    return RaftSharedSettingsPayload(
        instanceId = "shared-shell-settings-mobile-placeholder",
        title = "Mobile",
        heroIconName = RaftLucideIconName.Smartphone,
        heroLabel = "Mobile preferences",
        detail = detail,
        sections =
            listOf(
                RaftSharedSettingsSection(
                    title = "Status",
                    rows =
                        listOf(
                            RaftSharedSettingsRow(
                                key = "mobileStatus",
                                iconName = RaftLucideIconName.Smartphone,
                                title = "Mobile settings",
                                detail = detail,
                                status = status,
                                enabled = false
                            )
                        )
                )
            )
    )
}

internal fun SettingsMobileSelectionSnapshot.toMobileSelectionRow(
    key: String,
    iconName: RaftLucideIconName,
    title: String,
    fallbackStatus: String,
    actionState: build.raft.app.data.SharedSettingsActionState?
): RaftSharedSettingsRow {
    val nextOption =
        options.dropWhile { it.key != selectedKey }.drop(1).firstOrNull()
            ?: options.firstOrNull()
    return RaftSharedSettingsRow(
        key = key,
        iconName = iconName,
        title = title,
        detail =
            if (available && nextOption != null) {
                "$detail Next: ${nextOption.label}."
            } else {
                detail
            },
        value = nextOption?.key.orEmpty(),
        status = actionState.statusFor(key, statusLabel.ifBlank { fallbackStatus }),
        enabled = available && nextOption != null && actionState?.inProgress != true
    )
}

private fun SettingsMobileToggleSnapshot.toMobileToggleRow(
    key: String,
    iconName: RaftLucideIconName,
    title: String,
    fallbackStatus: String,
    actionState: build.raft.app.data.SharedSettingsActionState?,
    errorStrings: ErrorPresentationStrings,
    badgeLabel: String = ""
): RaftSharedSettingsRow =
    RaftSharedSettingsRow(
        key = key,
        iconName = iconName,
        title = title,
        detail = errorPresentation?.resolve(errorStrings) ?: detail,
        value = (!enabled).toString(),
        status = actionState.statusFor(key, statusLabel.ifBlank { fallbackStatus }),
        enabled = available && actionState?.inProgress != true,
        toggleValue = enabled,
        badgeLabel = badgeLabel
    )
