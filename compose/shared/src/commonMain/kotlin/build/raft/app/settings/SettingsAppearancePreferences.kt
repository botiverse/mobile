package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.MOBILE_ACTION_COMPACT_MESSAGES
import build.raft.app.data.MOBILE_ACTION_COMPOSER_SEND_KEY
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
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSaveStatus
import build.raft.designsystem.components.RaftSaveStatusDefaults
import build.raft.designsystem.components.RaftSaveStatusState
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * The app's own preferences under Settings > Appearance (web AppearanceSection parity, 1.29.0):
 * every row lives inside Appearance's single display group, named by its title alone (no per-item
 * descriptions; DS Forms › A setting's row, #proj-raft-ui:#220), with a small Beta badge for an
 * experiment. On Compose desktop, iPad app on Mac, and devices with a hardware keyboard, the
 * composer send key moves into the Keyboard section ("Send messages with": Enter / ⌘Enter or
 * Ctrl+Enter), leaving touch-only mobile with its soft-keyboard "Enter to send" toggle here.
 */

/** Web `@max-lg` (32rem = 512px): below this group width a trailing segmented control wraps under the title. */
internal val SettingsAppearanceInlineControlBreakpoint = 512.dp

/** What the app preference rows do when changed. */
internal class SettingsAppPreferenceActions(
    /** Which settings page shows the rows (`app_setting_changed.surface`): always Appearance now. */
    val surface: RaftAppSettingSurface = RaftAppSettingSurface.SettingsAppearance,
    val onAlignmentChange: (String?) -> Unit = {},
    val onLinkOpenModeChange: (String?) -> Unit = {},
    val onCompactConsecutiveMessagesChange: (Boolean) -> Unit = {},
    val onLongMessageCollapseChange: (Boolean) -> Unit = {},
    val onNewMessageMenuChange: (Boolean) -> Unit = {},
    val onComposerSendKeyChange: (Boolean) -> Unit = {},
    val onMultiShellHostChange: (Boolean) -> Unit = {},
    val onDesktopModeOnLargeScreenChange: (Boolean) -> Unit = {},
    val onServerDrawerChange: (Boolean) -> Unit = {},
    val diagnosticsLogger: (String) -> Unit = {},
)

/** Which preference rows and Keyboard section entries this platform and input setup shows. */
internal data class SettingsAppPreferenceVisibility(
    /** "Open web links": only where an in-app browser can show a page (not desktop yet). */
    val linkOpenMode: Boolean,
    /** Multiple app windows: only where the host can open another app window (Android). */
    val multiShellHost: Boolean,
    /**
     * Web's Keyboard › "Send messages with" (`Enter` / `⌘Enter` or `Ctrl+Enter`): shown on
     * Compose desktop, iPad app on Mac, and whenever a hardware keyboard is connected. Hidden on
     * touch-only mobile without a hardware keyboard (which keeps the soft-keyboard "Enter to send"
     * switch in Appearance).
     */
    val keyboardSendKey: Boolean,
    /** "iOS native navigation (Beta)": only on an iPhone host ([RaftIosNativeNavFlag.hostSupported]). */
    val iosNativeNav: Boolean = false,
    /** A large screen is possible here ([raftPlatformLargeScreenPossible]): gates multiple app windows. */
    val largeScreen: Boolean = false,
    /** A hardware keyboard is plausible or connected: the Keyboard shortcuts row. */
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

    /** Appearance › Keyboard section: shown when either "Send messages with" or "Keyboard shortcuts" is shown. */
    val keyboardSection: Boolean get() = keyboardSendKey || keyboardShortcuts
}

internal fun settingsAppPreferenceVisibility(
    finePointer: Boolean = raftPlatformHasFinePointer(),
    supportsInAppBrowser: Boolean = raftPlatformSupportsInAppBrowser(),
    supportsMultiShellHost: Boolean = raftPlatformSupportsMultiShellHost(),
    supportsIosNativeNav: Boolean = RaftIosNativeNavFlag.hostSupported,
    largeScreenPossible: Boolean = raftPlatformLargeScreenPossible(),
    hardwareKeyboardConnected: Boolean = false,
    isiOSAppOnMac: Boolean = raftPlatformIsiOSAppOnMac(),
): SettingsAppPreferenceVisibility {
    val hasHardwareKeyboard = finePointer || hardwareKeyboardConnected || isiOSAppOnMac
    return SettingsAppPreferenceVisibility(
        linkOpenMode = supportsInAppBrowser,
        multiShellHost = supportsMultiShellHost,
        keyboardSendKey = hasHardwareKeyboard,
        iosNativeNav = supportsIosNativeNav,
        largeScreen = largeScreenPossible,
        keyboardShortcuts = settingsKeyboardShortcutsVisible(finePointer, largeScreenPossible) || hasHardwareKeyboard,
    )
}

@Composable
internal fun rememberSettingsAppPreferenceVisibility(): SettingsAppPreferenceVisibility {
    val hardwareKeyboardConnected = rememberRaftHardwareKeyboardConnected()
    return settingsAppPreferenceVisibility(hardwareKeyboardConnected = hardwareKeyboardConnected)
}

/**
 * The app's own rows inside Appearance's single display group, immediately after the web's four
 * switches (every row starts with a hairline divider and shows its title alone, with no per-item
 * descriptions).
 */
@Composable
internal fun SettingsAppearanceAppRows(
    snapshot: SettingsMobileSnapshot,
    actionState: SharedSettingsActionState?,
    actions: SettingsAppPreferenceActions,
    visibility: SettingsAppPreferenceVisibility,
) {
    if (visibility.linkOpenMode) {
        val linkOpenMode = snapshot.linkOpenMode
        SettingsAppearanceSegmentedRow(
            title = stringResource(Res.string.settings_mobile_link_open_mode_title),
            divider = true,
            error = actionState.errorFor(MOBILE_ACTION_LINK_OPEN_MODE),
        ) {
            SegmentedControl(
                selectedValue = linkOpenMode.selectedKey.ifBlank { LinkOpenMode.Default.storageKey },
                options =
                    listOf(
                        SegmentedControlOption(
                            LinkOpenMode.SystemBrowser.storageKey,
                            stringResource(Res.string.settings_mobile_link_open_mode_system_browser),
                        ),
                        SegmentedControlOption(
                            LinkOpenMode.InAppBrowser.storageKey,
                            stringResource(Res.string.settings_mobile_link_open_mode_in_app_browser),
                        ),
                    ),
                onValueSelected = actions.onLinkOpenModeChange,
                enabled = linkOpenMode.available && !actionState.busyWith(MOBILE_ACTION_LINK_OPEN_MODE),
            )
        }
    }
    val alignment = snapshot.messageAlignmentPreference
    SettingsAppearanceSegmentedRow(
        title = stringResource(Res.string.settings_message_alignment_title),
        divider = true,
        error = actionState.errorFor(MOBILE_ACTION_MESSAGE_ALIGNMENT),
    ) {
        SegmentedControl(
            selectedValue = alignment.selectedKey.ifBlank { "left" },
            options =
                listOf(
                    SegmentedControlOption("left", stringResource(Res.string.settings_message_alignment_left)),
                    SegmentedControlOption("right", stringResource(Res.string.settings_message_alignment_right)),
                ),
            onValueSelected = actions.onAlignmentChange,
            enabled = alignment.available && !actionState.busyWith(MOBILE_ACTION_MESSAGE_ALIGNMENT),
        )
    }
    SettingsAppearanceToggleRow(
        title = stringResource(Res.string.settings_mobile_compact_messages),
        preference = snapshot.compactConsecutiveMessages,
        action = MOBILE_ACTION_COMPACT_MESSAGES,
        actionState = actionState,
        divider = true,
        onCheckedChange = actions.onCompactConsecutiveMessagesChange,
    )
    SettingsAppearanceToggleRow(
        title = stringResource(Res.string.settings_mobile_long_message_collapse),
        preference = snapshot.longMessageCollapse,
        action = MOBILE_ACTION_LONG_MESSAGE_COLLAPSE,
        actionState = actionState,
        divider = true,
        onCheckedChange = actions.onLongMessageCollapseChange,
    )
    if (!visibility.keyboardSendKey) {
        SettingsAppearanceToggleRow(
            title = stringResource(Res.string.settings_mobile_composer_key),
            preference = snapshot.composerSendKey,
            action = MOBILE_ACTION_COMPOSER_SEND_KEY,
            actionState = actionState,
            divider = true,
            anchorId = "send-with",
            onCheckedChange = actions.onComposerSendKeyChange,
        )
    }
    if (visibility.desktopMode) {
        val desktopMode = snapshot.desktopModeOnLargeScreen
        LaunchedEffect(desktopMode.available, desktopMode.enabled) {
            actions.diagnosticsLogger(
                "desktop_mode_card_state enabled=${desktopMode.enabled} available=${desktopMode.available}",
            )
        }
        SettingsAppearanceToggleRow(
            title = stringResource(Res.string.settings_mobile_desktop_mode),
            preference = desktopMode,
            action = MOBILE_ACTION_DESKTOP_MODE,
            actionState = actionState,
            divider = true,
            onCheckedChange = { enabled ->
                actions.diagnosticsLogger("desktop_mode_toggle_click target=$enabled available=${desktopMode.available}")
                actions.onDesktopModeOnLargeScreenChange(enabled)
            },
        )
    }
    SettingsAppearanceToggleRow(
        title = stringResource(Res.string.settings_mobile_new_message_menu),
        preference = snapshot.newMessageMenu,
        action = MOBILE_ACTION_NEW_MESSAGE_MENU,
        actionState = actionState,
        divider = true,
        beta = true,
        onCheckedChange = actions.onNewMessageMenuChange,
    )
    val serverDrawer = snapshot.serverDrawer
    LaunchedEffect(serverDrawer.available, serverDrawer.enabled) {
        actions.diagnosticsLogger(
            "server_drawer_card_state enabled=${serverDrawer.enabled} available=${serverDrawer.available}",
        )
    }
    SettingsAppearanceToggleRow(
        title = stringResource(Res.string.settings_mobile_server_drawer),
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
    if (visibility.multiShellHostRow(snapshot.multiShellHost.enabled)) {
        SettingsAppearanceToggleRow(
            title = stringResource(Res.string.settings_mobile_multi_shell_host),
            preference = snapshot.multiShellHost,
            action = MOBILE_ACTION_MULTI_SHELL_HOST,
            actionState = actionState,
            divider = true,
            beta = true,
            onCheckedChange = actions.onMultiShellHostChange,
        )
    }
}

/**
 * iOS native navigation (Beta, iPhone only). Stored under [RaftIosNativeNavFlag.KEY_LOCAL_OVERRIDE]
 * and read on app start.
 */
@Composable
private fun SettingsIosNativeNavRow(surface: RaftAppSettingSurface) {
    val preferences = remember { raftSharedPreferenceStore() }
    var enabled by remember { mutableStateOf(RaftIosNativeNavFlag.stored(preferences)) }
    SettingsAppearanceSwitchRow(
        title = stringResource(Res.string.settings_mobile_ios_native_nav),
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

/** One stored on/off preference: disabled while unavailable or while its change is saving. */
@Composable
private fun SettingsAppearanceToggleRow(
    title: String,
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

/** raft-ui `ListRow` `plain` with a title (optional [description], omitted in Appearance) and a switch as its control. */
@Composable
internal fun SettingsAppearanceSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    divider: Boolean,
    description: String? = null,
    /** The Settings search id of the row ([settingsSearchAnchor]); null: not in the index. */
    anchorId: String? = null,
    beta: Boolean = false,
    enabled: Boolean = true,
    error: SharedSettingsActionState? = null,
    modifier: Modifier = Modifier,
) {
    val errorMessage = error?.userFacingErrorMessage(localizedErrorPresentationStrings())
    val traceId = error?.screenError?.traceId ?: error?.permissionBlocker?.traceId
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (anchorId != null) Modifier.settingsSearchAnchor(anchorId) else Modifier)
            .then(modifier),
    ) {
        RaftListRow(
            title = title,
            variant = RaftListRowVariant.Plain,
            divider = divider,
            description = description,
            wrapDescription = description != null,
            titleMaxLines = Int.MAX_VALUE,
            titleTrailing = if (beta) ({ SettingsBetaBadge() }) else null,
            saveStatus = errorMessage?.let { RaftSaveStatusState.Failed(it) },
            trailing = { RaftSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, label = title) },
        )
        if (errorMessage != null && !traceId.isNullOrBlank()) {
            RaftTraceIdNote(traceId, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
    }
}

/**
 * One plain settings row whose control is a [SegmentedControl] (web `ListRow variant="plain"
 * className="@max-lg:flex-wrap"`): title on the left and control on the right when wide enough
 * (>= [SettingsAppearanceInlineControlBreakpoint]), wrapping the control under the title on
 * narrow widths. Shows the title alone, with no per-item description.
 */
@Composable
internal fun SettingsAppearanceSegmentedRow(
    title: String,
    divider: Boolean,
    anchorId: String? = null,
    saveStatus: RaftSaveStatusState? = null,
    saveStatusTag: String? = null,
    error: SharedSettingsActionState? = null,
    traceId: String? = null,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (anchorId != null) Modifier.settingsSearchAnchor(anchorId) else Modifier)
            .then(modifier),
    ) {
        if (divider) {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(RaftAppStroke.Thin).background(colors.lineHairline))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= SettingsAppearanceInlineControlBreakpoint
            val labelBlock: @Composable (Modifier) -> Unit = { blockModifier ->
                Column(blockModifier, verticalArrangement = Arrangement.spacedBy(RaftSaveStatusDefaults.Gap)) {
                    RaftText(
                        title,
                        style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                    )
                    if (saveStatus != null) {
                        RaftSaveStatus(
                            saveStatus,
                            modifier = if (saveStatusTag != null) Modifier.testTag(saveStatusTag) else Modifier,
                        )
                        if (!traceId.isNullOrBlank()) {
                            RaftTraceIdNote(traceId)
                        }
                    } else if (error != null) {
                        SettingsFormErrorText(error)
                    }
                }
            }
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    labelBlock(Modifier.weight(1f))
                    control()
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    labelBlock(Modifier.fillMaxWidth())
                    control()
                }
            }
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

private fun SharedSettingsActionState?.busyWith(action: String): Boolean = this != null && this.action == action && inProgress

private fun SharedSettingsActionState?.errorFor(action: String): SharedSettingsActionState? =
    this?.takeIf { it.action == action && it.errorCode != null }
