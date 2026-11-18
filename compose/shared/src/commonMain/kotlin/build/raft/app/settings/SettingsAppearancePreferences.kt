package build.raft.app

import build.raft.app.resources.*
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.MOBILE_ACTION_COMPACT_MESSAGES
import build.raft.app.data.MOBILE_ACTION_COMPOSER_SEND_KEY
import build.raft.app.data.MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE
import build.raft.app.data.MOBILE_ACTION_DESKTOP_MODE
import build.raft.app.data.MOBILE_ACTION_LINK_OPEN_MODE
import build.raft.app.data.MOBILE_ACTION_LONG_MESSAGE_COLLAPSE
import build.raft.app.data.MOBILE_ACTION_MESSAGE_ALIGNMENT
import build.raft.app.data.MOBILE_ACTION_MULTI_SHELL_HOST
import build.raft.app.data.MOBILE_ACTION_NEW_MESSAGE_MENU
import build.raft.app.data.MOBILE_ACTION_SERVER_DRAWER
import build.raft.app.data.RaftAppSettingKey
import build.raft.app.data.RaftAppSettingSurface
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SettingsMobileToggleSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.raftAppSettingToggleValue
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.nav.RaftIosNativeNavFlag
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource

/*
 * The app's own preferences under Settings > Appearance (settings cleanup, 1.27.0: the former
 * "Mobile" page is gone). Grouped by what they change: Messages, Home, Windows (only where a large
 * screen is possible) and the web's display switches. Every row is a plain settings row: the
 * title (with a small Beta badge for an experiment), one line saying what it does, the control.
 */

/** What the app preference rows do when changed. */
internal class SettingsAppPreferenceActions(
    /** Which settings page shows the rows (`app_setting_changed.surface`): always Appearance now. */
    val surface: RaftAppSettingSurface = RaftAppSettingSurface.SettingsAppearance,
    val onAlignmentChange: (String?) -> Unit = {},
    val onLinkOpenModeChange: (String?) -> Unit = {},
    val onCompactConsecutiveMessagesChange: (Boolean) -> Unit = {},
    val onLongMessageCollapseChange: (Boolean) -> Unit = {},
    val onNewMessageMenuChange: (Boolean) -> Unit = {},
    val onConversationForwardGestureChange: (Boolean) -> Unit = {},
    val onComposerSendKeyChange: (Boolean) -> Unit = {},
    val onMultiShellHostChange: (Boolean) -> Unit = {},
    val onDesktopModeOnLargeScreenChange: (Boolean) -> Unit = {},
    val onServerDrawerChange: (Boolean) -> Unit = {},
    val diagnosticsLogger: (String) -> Unit = {},
)

/** Which app preference rows, and which copy, this platform shows. */
internal data class SettingsAppPreferenceVisibility(
    /** "Open web links": only where an in-app browser can show a page (not desktop yet). */
    val linkOpenMode: Boolean,
    /** Multiple app windows: only where the host can open another app window (Android). */
    val multiShellHost: Boolean,
    /** Enter-to-send copy about Enter / Shift+Enter instead of the soft keyboard's action key. */
    val hardwareKeyboardComposerCopy: Boolean,
    /** "iOS native navigation (Beta)": only on an iPhone host ([RaftIosNativeNavFlag.hostSupported]). */
    val iosNativeNav: Boolean = false,
    /** A large screen is possible here ([raftPlatformLargeScreenPossible]): the Windows group. */
    val largeScreen: Boolean = false,
    /** A hardware keyboard is plausible here ([settingsKeyboardShortcutsVisible]): the Keyboard shortcuts group. */
    val keyboardShortcuts: Boolean = false,
) {
    /**
     * "Desktop layout on large screens": on every device. A large phone held sideways is wide enough
     * for the desktop layout too, so it needs the switch to turn it off (artin 2026-11-18,
     * #proj-mobile:d36389d7, option A).
     */
    val desktopMode: Boolean get() = true

    /**
     * Multiple app windows: where the host has them and the screen can be large. A phone that has
     * it on from before still shows it, so it can be turned off.
     */
    fun multiShellHostRow(enabled: Boolean): Boolean = multiShellHost && (largeScreen || enabled)

    /** The Windows group: shown when it has a row. */
    fun windowsGroup(multiShellHostEnabled: Boolean): Boolean = desktopMode || multiShellHostRow(multiShellHostEnabled)
}

internal fun settingsAppPreferenceVisibility(
    finePointer: Boolean = raftPlatformHasFinePointer(),
    supportsInAppBrowser: Boolean = raftPlatformSupportsInAppBrowser(),
    supportsMultiShellHost: Boolean = raftPlatformSupportsMultiShellHost(),
    supportsIosNativeNav: Boolean = RaftIosNativeNavFlag.hostSupported,
    largeScreenPossible: Boolean = raftPlatformLargeScreenPossible(),
): SettingsAppPreferenceVisibility =
    SettingsAppPreferenceVisibility(
        linkOpenMode = supportsInAppBrowser,
        multiShellHost = supportsMultiShellHost,
        hardwareKeyboardComposerCopy = finePointer,
        iosNativeNav = supportsIosNativeNav,
        largeScreen = largeScreenPossible,
        keyboardShortcuts = settingsKeyboardShortcutsVisible(finePointer, largeScreenPossible),
    )

/** The Enter-to-send row's description: Enter / Shift+Enter with a hardware keyboard, else the soft keyboard's key. */
internal fun settingsComposerKeyDetail(hardwareKeyboard: Boolean): StringResource =
    if (hardwareKeyboard) {
        Res.string.settings_mobile_composer_key_detail_hardware_keyboard
    } else {
        Res.string.settings_mobile_composer_key_detail
    }

/**
 * Appearance > Messages, after the message font size: my message alignment, compact consecutive
 * messages, collapse long messages, Enter to send, open web links, then the Beta ones.
 */
@Composable
internal fun SettingsAppearanceMessageRows(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    actions: SettingsAppPreferenceActions,
    visibility: SettingsAppPreferenceVisibility,
) {
    val rows = SettingsAppearanceRowCounter()
    val alignment = snapshot.messageAlignmentPreference
    SettingsAppearanceRow(
        title = stringResource(Res.string.settings_message_alignment_title),
        description = stringResource(Res.string.settings_message_alignment_detail),
        divider = rows.next(),
        error = actionState.errorFor(MOBILE_ACTION_MESSAGE_ALIGNMENT),
        below = {
            SegmentedControl(
                selectedValue = alignment.selectedKey.ifBlank { "left" },
                options =
                    listOf(
                        SegmentedControlOption("left", stringResource(Res.string.settings_message_alignment_left)),
                        SegmentedControlOption("right", stringResource(Res.string.settings_message_alignment_right))
                    ),
                onValueSelected = actions.onAlignmentChange,
                enabled = alignment.available && !actionState.busyWith(MOBILE_ACTION_MESSAGE_ALIGNMENT),
            )
        }
    )
    SettingsAppearanceToggleRow(
        title = stringResource(Res.string.settings_mobile_compact_messages),
        description = stringResource(Res.string.settings_mobile_compact_detail),
        preference = snapshot.compactConsecutiveMessages,
        action = MOBILE_ACTION_COMPACT_MESSAGES,
        actionState = actionState,
        divider = rows.next(),
        onCheckedChange = actions.onCompactConsecutiveMessagesChange,
    )
    SettingsAppearanceToggleRow(
        title = stringResource(Res.string.settings_mobile_long_message_collapse),
        description = stringResource(Res.string.settings_mobile_long_message_collapse_detail),
        preference = snapshot.longMessageCollapse,
        action = MOBILE_ACTION_LONG_MESSAGE_COLLAPSE,
        actionState = actionState,
        divider = rows.next(),
        onCheckedChange = actions.onLongMessageCollapseChange,
    )
    SettingsAppearanceToggleRow(
        title = stringResource(Res.string.settings_mobile_composer_key),
        description = stringResource(settingsComposerKeyDetail(visibility.hardwareKeyboardComposerCopy)),
        preference = snapshot.composerSendKey,
        action = MOBILE_ACTION_COMPOSER_SEND_KEY,
        actionState = actionState,
        divider = rows.next(),
        anchorId = "send-with",
        onCheckedChange = actions.onComposerSendKeyChange,
    )
    if (visibility.linkOpenMode) {
        val linkOpenMode = snapshot.linkOpenMode
        SettingsAppearanceRow(
            title = stringResource(Res.string.settings_mobile_link_open_mode_title),
            description = stringResource(Res.string.settings_mobile_link_open_mode_detail),
            divider = rows.next(),
            error = actionState.errorFor(MOBILE_ACTION_LINK_OPEN_MODE),
            below = {
                SegmentedControl(
                    selectedValue = linkOpenMode.selectedKey.ifBlank { LinkOpenMode.Default.storageKey },
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
                    onValueSelected = actions.onLinkOpenModeChange,
                    enabled = linkOpenMode.available && !actionState.busyWith(MOBILE_ACTION_LINK_OPEN_MODE),
                )
            }
        )
    }
    SettingsAppearanceToggleRow(
        title = stringResource(Res.string.settings_mobile_new_message_menu),
        description = stringResource(Res.string.settings_mobile_new_message_menu_detail),
        preference = snapshot.newMessageMenu,
        action = MOBILE_ACTION_NEW_MESSAGE_MENU,
        actionState = actionState,
        divider = rows.next(),
        beta = true,
        onCheckedChange = actions.onNewMessageMenuChange,
    )
    // iOS only: the host with a native right-edge Forward recognizer.
    if (snapshot.conversationForwardGesture.available) {
        val accessibility = stringResource(Res.string.settings_mobile_conversation_forward_gesture_accessibility)
        SettingsAppearanceToggleRow(
            title = stringResource(Res.string.settings_mobile_conversation_forward_gesture),
            description = stringResource(Res.string.settings_mobile_conversation_forward_gesture_detail),
            preference = snapshot.conversationForwardGesture,
            action = MOBILE_ACTION_CONVERSATION_FORWARD_GESTURE,
            actionState = actionState,
            divider = rows.next(),
            beta = true,
            modifier = Modifier.semantics { contentDescription = accessibility },
            onCheckedChange = actions.onConversationForwardGestureChange,
        )
    }
}

/**
 * Appearance > Home, after "Hide empty Home sections": the multi-server Home drawer (Beta) and,
 * on an iPhone, iOS native navigation (Beta), which changes how the app moves between pages.
 */
@Composable
internal fun SettingsAppearanceHomeRows(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    actions: SettingsAppPreferenceActions,
    visibility: SettingsAppPreferenceVisibility,
) {
    val serverDrawer = snapshot.serverDrawer
    LaunchedEffect(serverDrawer.available, serverDrawer.enabled) {
        actions.diagnosticsLogger(
            "server_drawer_card_state enabled=${serverDrawer.enabled} available=${serverDrawer.available}"
        )
    }
    SettingsAppearanceToggleRow(
        title = stringResource(Res.string.settings_mobile_server_drawer),
        description = stringResource(Res.string.settings_mobile_server_drawer_detail),
        preference = serverDrawer,
        action = MOBILE_ACTION_SERVER_DRAWER,
        actionState = actionState,
        divider = true,
        beta = true,
        onCheckedChange = { enabled ->
            actions.diagnosticsLogger("server_drawer_toggle_click target=$enabled available=${serverDrawer.available}")
            actions.onServerDrawerChange(enabled)
        },
    )
    if (visibility.iosNativeNav) {
        SettingsIosNativeNavRow(actions.surface)
    }
}

/**
 * Appearance > Windows, only where a large screen is possible ([SettingsAppPreferenceVisibility]):
 * desktop layout on large screens, multiple app windows (Beta, Android).
 */
@Composable
internal fun SettingsAppearanceWindowRows(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    actions: SettingsAppPreferenceActions,
    visibility: SettingsAppPreferenceVisibility,
) {
    val rows = SettingsAppearanceRowCounter()
    if (visibility.desktopMode) {
        val desktopMode = snapshot.desktopModeOnLargeScreen
        LaunchedEffect(desktopMode.available, desktopMode.enabled) {
            actions.diagnosticsLogger(
                "desktop_mode_card_state enabled=${desktopMode.enabled} available=${desktopMode.available}"
            )
        }
        SettingsAppearanceToggleRow(
            title = stringResource(Res.string.settings_mobile_desktop_mode),
            description = stringResource(Res.string.settings_mobile_desktop_mode_detail),
            preference = desktopMode,
            action = MOBILE_ACTION_DESKTOP_MODE,
            actionState = actionState,
            divider = rows.next(),
            onCheckedChange = { enabled ->
                actions.diagnosticsLogger("desktop_mode_toggle_click target=$enabled available=${desktopMode.available}")
                actions.onDesktopModeOnLargeScreenChange(enabled)
            },
        )
    }
    if (visibility.multiShellHostRow(snapshot.multiShellHost.enabled)) {
        SettingsAppearanceToggleRow(
            title = stringResource(Res.string.settings_mobile_multi_shell_host),
            description = stringResource(Res.string.settings_mobile_multi_shell_host_detail),
            preference = snapshot.multiShellHost,
            action = MOBILE_ACTION_MULTI_SHELL_HOST,
            actionState = actionState,
            divider = rows.next(),
            beta = true,
            onCheckedChange = actions.onMultiShellHostChange,
        )
    }
}

/**
 * iOS native navigation (Beta, iPhone only). The same stored switch as before
 * ([RaftIosNativeNavFlag.KEY_LOCAL_OVERRIDE]); it shows what the next start decides (the switch,
 * else the server's last value: off by default). Read once per process, so it takes effect on the
 * next app start (the description says so).
 */
@Composable
private fun SettingsIosNativeNavRow(surface: RaftAppSettingSurface) {
    val preferences = remember { raftSharedPreferenceStore() }
    var enabled by remember { mutableStateOf(RaftIosNativeNavFlag.stored(preferences)) }
    SettingsAppearanceSwitchRow(
        title = stringResource(Res.string.settings_mobile_ios_native_nav),
        description = stringResource(Res.string.settings_mobile_ios_native_nav_detail),
        checked = enabled,
        onCheckedChange = { checked ->
            val previous = enabled
            RaftIosNativeNavFlag.setLocalOverride(preferences, checked)
            enabled = RaftIosNativeNavFlag.stored(preferences)
            RaftProductEvents.appSettingChanged(
                RaftAppSettingKey.IosNativeNav,
                previous = raftAppSettingToggleValue(previous),
                value = raftAppSettingToggleValue(enabled),
                surface = surface,
            )
        },
        divider = true,
        beta = true,
    )
}

/** One stored on/off preference: off while it is unavailable or its change is saving. */
@Composable
private fun SettingsAppearanceToggleRow(
    title: String,
    description: String,
    preference: SettingsMobileToggleSnapshot,
    action: String,
    actionState: SharedSettingsActionState?,
    divider: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    beta: Boolean = false,
    anchorId: String? = null,
    modifier: Modifier = Modifier,
) {
    SettingsAppearanceSwitchRow(
        title = title,
        description = description,
        checked = preference.enabled,
        onCheckedChange = onCheckedChange,
        divider = divider,
        anchorId = anchorId,
        beta = beta,
        enabled = preference.available && !actionState.busyWith(action),
        error = actionState.errorFor(action),
        modifier = modifier,
    )
}

/** raft-ui ListRow `plain` with a switch as its control (px 16, py 12, gap 12). */
@Composable
internal fun SettingsAppearanceSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    divider: Boolean,
    /** The Settings search id of the row ([settingsSearchAnchor]); null: not in the index. */
    anchorId: String? = null,
    beta: Boolean = false,
    enabled: Boolean = true,
    error: SharedSettingsActionState? = null,
    modifier: Modifier = Modifier,
) {
    SettingsAppearanceRow(
        title = title,
        description = description,
        divider = divider,
        anchorId = anchorId,
        beta = beta,
        error = error,
        modifier = modifier,
        trailing = { RaftSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, label = title) },
    )
}

/**
 * One row of an Appearance group: a hairline above it unless it is the first ([divider]), the
 * title (a small Beta badge after it for an experiment) over its description, the control at the
 * end ([trailing]) or under the words ([below], a segmented choice), and what failed under it.
 */
@Composable
private fun SettingsAppearanceRow(
    title: String,
    description: String,
    divider: Boolean,
    anchorId: String? = null,
    beta: Boolean = false,
    error: SharedSettingsActionState? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(
        Modifier.fillMaxWidth().then(if (anchorId != null) Modifier.settingsSearchAnchor(anchorId) else Modifier).then(modifier)
    ) {
        if (divider) {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(RaftAppStroke.Thin).background(colors.lineHairline))
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftText(
                            title,
                            modifier = Modifier.weight(1f, fill = false),
                            style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.foreground
                        )
                        if (beta) SettingsBetaBadge()
                    }
                    RaftText(description, style = typography.callout, color = colors.foregroundMuted)
                }
                trailing?.invoke()
            }
            below?.invoke()
            error?.let { SettingsFormErrorText(it) }
        }
    }
}

/** The small Beta tag after an experiment's title (raft-ui Badge, soft brand). */
@Composable
internal fun SettingsBetaBadge() {
    RaftBadge(
        text = stringResource(Res.string.settings_badge_beta),
        appearance = RaftBadgeAppearance.Soft,
        variant = RaftBadgeVariant.Primary,
    )
}

/** Hands out the rows' dividers in order: none above the first row of a group. */
internal class SettingsAppearanceRowCounter {
    private var count = 0

    fun next(): Boolean = count++ > 0
}

private fun SharedSettingsActionState?.busyWith(action: String): Boolean = this != null && this.action == action && inProgress

private fun SharedSettingsActionState?.errorFor(action: String): SharedSettingsActionState? =
    this?.takeIf { it.action == action && it.errorCode != null }
