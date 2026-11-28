package build.raft.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftKbd
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import org.jetbrains.compose.resources.StringResource

/*
 * Keyboard shortcuts (web `features/keyboard-shortcuts`, the Help menu's panel, its rows from
 * `features/shortcuts/registry.ts`): only the shortcuts the app itself handles, each checked against
 * its handler:
 *  - Search, ⌘K / Ctrl+K anywhere (SettingsHomePage `raftSearchShortcut`);
 *  - Collapse or expand the sidebar, ⌘\ / Ctrl+\ in a wide window (SettingsHomePage
 *    `raftSidebarToggleShortcut`);
 *  - Keyboard shortcuts, ⌘/ / Ctrl+/ anywhere (SettingsHomePage `raftKeyboardShortcutsHelpShortcut`);
 *  - Previous match, Shift+Enter in thread search (ThreadFind; Enter goes to the next).
 * Offered under Appearance where a hardware keyboard is plausible: the desktop app (fine pointer)
 * or a device that can show Raft on a large screen (a tablet, an iPad).
 */

internal const val SettingsKeyboardShortcutsRowTag = "settings-keyboard-shortcuts-row"
internal const val SettingsKeyboardShortcutsDialogTag = "settings-keyboard-shortcuts-dialog"

/** One shortcut: what it does, where it works, and its keys pressed together. */
internal data class SettingsKeyboardShortcut(
    val id: String,
    val label: StringResource,
    val where: StringResource?,
    val keys: List<String>,
)

internal data class SettingsKeyboardShortcutGroup(
    val title: StringResource,
    val shortcuts: List<SettingsKeyboardShortcut>,
)

/** Where the entry shows: a hardware keyboard is plausible. */
internal fun settingsKeyboardShortcutsVisible(finePointer: Boolean, largeScreenPossible: Boolean): Boolean =
    finePointer || largeScreenPossible

/** The modifier key's cap: ⌘ on Apple platforms ([apple], `raftPlatformShortcutsUseCommand`), else Ctrl. */
private fun modifierKey(apple: Boolean): String = if (apple) "⌘" else "Ctrl"

/** Web `formatShortcut({ key: "Enter" })`: "Enter". */
internal fun raftComposerEnterKeyLabel(): String = "Enter"

/** Web `formatShortcut(MOD_ENTER_SEND_SHORTCUT)`: "⌘Enter" on Apple platforms, "Ctrl+Enter" elsewhere. */
internal fun raftComposerModEnterKeyLabel(apple: Boolean): String = if (apple) "⌘Enter" else "Ctrl+Enter"

/** Web HelpMenu shortcut label: "⌘/" on Apple platforms, "Ctrl+/" elsewhere. */
internal fun raftKeyboardShortcutsHelpShortcutLabel(apple: Boolean): String = if (apple) "⌘/" else "Ctrl+/"

/** ⌘/ on Apple platforms, Ctrl+/ elsewhere: toggles the Keyboard shortcuts dialog (web `HelpMenu`). */
internal fun raftKeyboardShortcutsHelpShortcut(
    keyDown: Boolean,
    key: Key,
    meta: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    apple: Boolean,
): Boolean {
    if (!keyDown || key != Key.Slash || alt || shift) return false
    return if (apple) meta && !ctrl else ctrl && !meta
}

/** The groups, in the web panel's order (Global, then Channels and threads). */
internal fun settingsKeyboardShortcutGroups(apple: Boolean): List<SettingsKeyboardShortcutGroup> =
    listOf(
        SettingsKeyboardShortcutGroup(
            Res.string.settings_keyboard_shortcuts_group_global,
            listOf(
                SettingsKeyboardShortcut(
                    "search",
                    Res.string.settings_keyboard_shortcuts_search,
                    Res.string.settings_keyboard_shortcuts_search_where,
                    listOf(modifierKey(apple), "K"),
                ),
                SettingsKeyboardShortcut(
                    "sidebar",
                    Res.string.settings_keyboard_shortcuts_sidebar,
                    Res.string.settings_keyboard_shortcuts_sidebar_where,
                    listOf(modifierKey(apple), "\\"),
                ),
                SettingsKeyboardShortcut(
                    "keyboard-shortcuts",
                    Res.string.settings_keyboard_shortcuts_title,
                    Res.string.settings_keyboard_shortcuts_search_where,
                    listOf(modifierKey(apple), "/"),
                ),
            ),
        ),
        SettingsKeyboardShortcutGroup(
            Res.string.settings_keyboard_shortcuts_group_channels_threads,
            listOf(
                SettingsKeyboardShortcut(
                    "previous-match",
                    Res.string.settings_keyboard_shortcuts_previous_match,
                    Res.string.settings_keyboard_shortcuts_previous_match_where,
                    listOf(if (apple) "⇧" else "Shift", "Enter"),
                ),
            ),
        ),
    )

/** One row inside Appearance › Keyboard that opens the Keyboard shortcuts dialog. */
@Composable
internal fun SettingsKeyboardShortcutsRow(divider: Boolean, onClick: () -> Unit) {
    RaftListRow(
        title = stringResource(Res.string.settings_keyboard_shortcuts_title),
        modifier = Modifier.settingsSearchAnchor("keyboard-shortcuts").testTag(SettingsKeyboardShortcutsRowTag),
        onClick = onClick,
        variant = RaftListRowVariant.Plain,
        divider = divider,
        titleMaxLines = Int.MAX_VALUE,
        trailing = { RaftListRowChevron() },
    )
}

@Composable
internal fun RaftShellKeyboardShortcutsDialogHost(
    open: Boolean,
    apple: Boolean,
    onDismiss: () -> Unit,
) {
    if (!open) return
    RaftDesignSystemTheme {
        SettingsKeyboardShortcutsDialog(
            apple = apple,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun SettingsKeyboardShortcutsDialog(apple: Boolean, onDismiss: () -> Unit) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_keyboard_shortcuts_title),
        modifier =
            Modifier
                .testTag(SettingsKeyboardShortcutsDialogTag)
                .onPreviewKeyEvent { event ->
                    if (
                        raftKeyboardShortcutsHelpShortcut(
                            keyDown = event.type == KeyEventType.KeyDown,
                            key = event.key,
                            meta = event.isMetaPressed,
                            ctrl = event.isCtrlPressed,
                            alt = event.isAltPressed,
                            shift = event.isShiftPressed,
                            apple = apple,
                        )
                    ) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        size = RaftDialogSize.Md,
        closeLabel = stringResource(Res.string.common_close),
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_done),
                onClick = onDismiss,
                variant = RaftButtonVariant.Default,
                size = RaftButtonSize.Lg
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            settingsKeyboardShortcutGroups(apple).forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftText(
                        stringResource(group.title),
                        style = RaftTheme.typography.footnote,
                        color = RaftTheme.colors.foregroundMuted
                    )
                    group.shortcuts.forEach { shortcut -> SettingsKeyboardShortcutRow(shortcut) }
                }
            }
        }
    }
}

@Composable
private fun SettingsKeyboardShortcutRow(shortcut: SettingsKeyboardShortcut) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RaftText(stringResource(shortcut.label), style = RaftTheme.typography.subhead, color = RaftTheme.colors.foreground)
            shortcut.where?.let {
                RaftText(stringResource(it), style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            shortcut.keys.forEach { key -> RaftKbd(key) }
        }
    }
}
