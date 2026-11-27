package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.assistant.AssistantOtterVisibleRow
import build.raft.app.assistant.AssistantOwnerOtterRow
import build.raft.app.assistant.AssistantSettingChangesSection
import build.raft.app.assistant.LocalAssistantSettingChangesSource
import build.raft.app.assistant.assistantViewerIsGuest
import build.raft.app.assistant.rememberAssistantOtterRow
import build.raft.app.assistant.rememberAssistantSettingChanges
import build.raft.app.assistant.rememberMyAssistantGate
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.data.APPEARANCE_ACTION_COMPOSER_SEND_KEY
import build.raft.app.data.APPEARANCE_ACTION_MESSAGE_FONT_SIZE
import build.raft.app.data.ASSISTANT_ACTION_SETTINGS
import build.raft.app.data.AssistantSettingChange
import build.raft.app.data.MyAssistantRecord
import build.raft.app.data.MyAssistantState
import build.raft.app.data.RaftAppAppearanceRuntime
import build.raft.app.data.RaftComposerSendKey
import build.raft.app.data.RaftComposerSendKeyRuntime
import build.raft.app.data.RaftDisplayPreferenceRuntime
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsAppearanceSnapshot
import build.raft.app.data.SettingsMobileSnapshot
import build.raft.app.data.SharedMyAssistantStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.defaultNowMillis
import build.raft.app.resources.*
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadMessageCard
import build.raft.app.thread.isKnownThreadMarkdownTag
import build.raft.app.thread.key
import build.raft.app.thread.raftMessageBodyTextStyle
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.components.RaftSaveStatusState
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.StringResource

internal const val SettingsAppearanceSectionTag = "settings-appearance-section"
internal const val SettingsAppearanceKeyboardSectionTag = "settings-appearance-keyboard-section"
internal const val SettingsAppearanceSendKeyRowTag = "settings-appearance-send-key-row"
internal const val SettingsAppearanceSendKeyErrorTag = "settings-appearance-send-key-error"
internal const val SettingsAppearanceAssistantSectionTag = "assistant-otter-settings"
internal const val SettingsAppearanceAssistantPanelLinkTag = "assistant-otter-settings-panel-link"

/**
 * What Settings › Appearance needs for its Assistant section (web `AssistantOtterSettingsSection`):
 * "my assistant" ([store]) and its account-level otter switch ([SharedMyAssistantStore.otterPrefs]),
 * gated by [featureFlags] (`personal_assistant`) and the signed-in viewer's server and role.
 * Null hides the section (e.g. screenshot fixtures or callers without a shell store).
 */
internal class SettingsAppearanceAssistantSource(
    val store: SharedMyAssistantStore,
    val featureFlags: SharedServerFeatureFlags,
    val serverId: String?,
    val userId: String?,
    val serverRole: String?,
    val onOpenAssistantSettings: (agentId: String) -> Unit,
)

/**
 * The preview message's time. Null: the current time, like the Web's preview (`new Date()`); the
 * screenshot host pins it.
 */
internal val LocalSettingsAppearancePreviewTimeLabel = staticCompositionLocalOf<String?> { null }

/**
 * The screenshot host's device switches: the web case's fresh browser, where nothing is saved
 * yet (Live agent activity on, Show agent model on, Show member descriptions on, Hide empty Home
 * sections off, the web's own default) and the app's own rows do not exist. Null: the saved values
 * and every row, as in the app.
 */
internal class SettingsAppearanceVisualSwitches(
    val showLiveAgentActivity: Boolean,
    val showAgentModelName: Boolean,
    val showSenderDescription: Boolean,
    val hideEmptySidebarSections: Boolean,
    val showAppOnlyRows: Boolean,
)

internal val LocalSettingsAppearanceVisualSwitches =
    staticCompositionLocalOf<SettingsAppearanceVisualSwitches?> { null }

/**
 * Settings > Appearance (web `AppearanceSection` parity, 1.29.0):
 * - Optional Light/Dark/System mode card only when there is no Theme page (`showModeCard`).
 * - "Theme on <server>" plain row (web `ServerThemeLink`, title only, no description).
 * - One **Appearance** section (`Type` icon, 12dp between groups):
 *   1. Message font size row + attached Preview row inside one `RaftListRowGroup`.
 *   2. One `RaftListRowGroup` with the web's four switches (`Live agent activity`, `Show agent
 *      model`, `Show member descriptions`, `Hide empty Home sections`) followed by the app's own
 *      rows when [preferences] is loaded, each named by its title alone (no per-item descriptions).
 * - **Keyboard** section (`Keyboard` icon) when a hardware keyboard is connected or plausible:
 *   "Send messages with" (`Enter` / `⌘Enter` or `Ctrl+Enter`, stored on the account) and
 *   "Keyboard shortcuts" in one `RaftListRowGroup`.
 * - **Assistant** section (`PawPrint` icon, web `AssistantOtterSettingsSection`) when
 *   `personal_assistant` is on, the viewer is not a guest, and they have an assistant on the
 *   current server: "Show {name} on every page" switch, "More {name} settings" link to its profile,
 *   and "Changed by your assistant" (`ASSISTANT_ACTION_SETTINGS`).
 */
@Composable
internal fun SettingsAppearanceContent(
    state: ScreenLoadState<SettingsAppearanceSnapshot>,
    actionState: SharedSettingsActionState?,
    fontSizeDraft: String,
    bottomContentPadding: Dp,
    onFontSizeDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    /**
     * Light, Dark or System. Web (botiverse/slock#11355) keeps it on the Theme page; Appearance shows
     * it only where there is no Theme page, so it has one place: [settingsAppearanceShowsModeCard].
     */
    showModeCard: Boolean,
    /** The app preference snapshot (alignment, link mode, ...); null: not read yet, no app rows. */
    preferences: SettingsMobileSnapshot? = null,
    preferenceActions: SettingsAppPreferenceActions = SettingsAppPreferenceActions(),
    preferenceVisibility: SettingsAppPreferenceVisibility = rememberSettingsAppPreferenceVisibility(),
    /** The current server's name for the "Theme on <server>" row (Web `ServerThemeLink`); null hides it. */
    themeServerName: String? = null,
    onOpenTheme: () -> Unit = {},
    /** Account-synced composer send key choice (`Enter` vs `⌘Enter`/`Ctrl+Enter`) for Keyboard section. */
    onComposerSendKeyChange: (RaftComposerSendKey) -> Unit = {},
    shortcutsUseCommand: Boolean = raftPlatformShortcutsUseCommand(),
    /** Viewer's personal assistant binding (web `AssistantOtterSettingsSection`); null hides the section. */
    assistantSource: SettingsAppearanceAssistantSource? = null,
) {
    val visual = LocalSettingsAppearanceVisualSwitches.current
    val appRows = preferences?.takeIf { visual?.showAppOnlyRows != false }
    val fontSizeSnapshot = (state as? ScreenLoadState.Content)?.data
    val observedComposerSendKey = RaftComposerSendKeyRuntime.observeSendKey()
    val composerSendKey = fontSizeSnapshot?.composerSendKey ?: observedComposerSendKey
    // Web `AssistantOtterSettingsSection`: shown only when `personal_assistant` is on, the viewer
    // is not a guest, and they have an assistant here; hidden in visual screenshot fixtures so
    // existing SettingsAppearanceVisual baselines stay unaffected.
    val assistant =
        if (visual == null && assistantSource != null) {
            val gate =
                rememberMyAssistantGate(
                    store = assistantSource.store,
                    flags = assistantSource.featureFlags,
                    serverId = assistantSource.serverId,
                    userId = assistantSource.userId,
                    viewerIsGuest = assistantViewerIsGuest(assistantSource.serverRole),
                )
            (gate.state as? MyAssistantState.Ready)?.assistant
        } else {
            null
        }
    val assistantOtter =
        if (assistant != null && assistantSource != null) {
            assistantSource.userId?.takeIf(String::isNotBlank)?.let { userId ->
                rememberAssistantOtterRow(assistantSource.store, userId)
            }
        } else {
            null
        }
    val assistantChangesSource = LocalAssistantSettingChangesSource.current
    val assistantChanges =
        if (assistant != null) {
            rememberAssistantSettingChanges(ASSISTANT_ACTION_SETTINGS, assistantChangesSource)
        } else {
            emptyList()
        }
    LazyColumn(
        state = rememberSettingsSearchListState(),
        modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).raftSafeAreaHorizontalPadding().padding(horizontal = 16.dp),
        // The top and bottom inset inside the list, so a located section first on the page has room
        // for its highlight (SettingsSearchSectionShape) instead of being cut where the list starts.
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (showModeCard) {
            item {
                Box(Modifier.fillMaxWidth().settingsSearchAnchor("theme", SettingsSearchSectionShape)) {
                    SettingsAppearanceThemeCard(
                        title = stringResource(Res.string.settings_appearance_theme_title),
                        showDescription = false,
                    )
                }
            }
        }
        if (themeServerName != null) {
            item { SettingsAppearanceServerThemeLink(serverName = themeServerName, onClick = onOpenTheme) }
        }
        when (state) {
            ScreenLoadState.Loading ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Type,
                        stringResource(Res.string.common_loading),
                        stringResource(Res.string.settings_appearance_loading),
                        null,
                        onRetry,
                        loading = true,
                    )
                }
            is ScreenLoadState.Empty ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Type,
                        stringResource(Res.string.settings_state_unavailable),
                        state.reason.localizedUserFacingMessage(),
                        stringResource(Res.string.common_retry),
                        onRetry,
                    )
                }
            is ScreenLoadState.Error ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.TriangleAlert,
                        stringResource(Res.string.settings_error_appearance),
                        state.error.localizedUserFacingMessage(),
                        stringResource(Res.string.common_retry),
                        onRetry,
                        traceId = state.error.traceId,
                    )
                }
            is ScreenLoadState.NoPermission ->
                item {
                    SettingsLoadStateCard(
                        RaftLucideIconName.Lock,
                        stringResource(Res.string.settings_state_permission_required),
                        state.blocker.localizedUserFacingMessage(),
                        null,
                        onRetry,
                        traceId = state.blocker.traceId,
                    )
                }
            is ScreenLoadState.Content -> Unit
        }
        item {
            Box(Modifier.fillMaxWidth().testTag(SettingsAppearanceSectionTag)) {
                SettingsFormSection(
                    iconName = RaftLucideIconName.Type,
                    title = stringResource(Res.string.settings_appearance_title),
                ) {
                    fontSizeSnapshot?.let { snapshot ->
                        SettingsAppearanceFontSizeGroup(
                            snapshot = snapshot,
                            fontSizeDraft = fontSizeDraft,
                            actionState = actionState,
                            onFontSizeDraftChange = onFontSizeDraftChange,
                            onSubmit = onSave,
                        )
                    }
                    SettingsAppearanceDisplayGroup(
                        visual = visual,
                        appRows = appRows,
                        actionState = actionState,
                        preferenceActions = preferenceActions,
                        preferenceVisibility = preferenceVisibility,
                    )
                }
            }
        }
        if (preferenceVisibility.keyboardSection) {
            item {
                SettingsAppearanceKeyboardSection(
                    visibility = preferenceVisibility,
                    composerSendKey = composerSendKey,
                    actionState = actionState,
                    onComposerSendKeyChange = onComposerSendKeyChange,
                    shortcutsUseCommand = shortcutsUseCommand,
                )
            }
        }
        if (assistant != null && assistantSource != null) {
            item {
                SettingsAppearanceAssistantSection(
                    assistant = assistant,
                    otter = assistantOtter,
                    changes = assistantChanges,
                    changesServerId = assistantChangesSource?.serverId,
                    onOpenAssistantSettings = { assistantSource.onOpenAssistantSettings(assistant.agentId) },
                )
            }
        }
        item { Spacer(Modifier.height(bottomContentPadding)) }
    }
}

/** An Appearance group: raft-ui `ListRowGroup`, its rows on one inset card. */
@Composable
private fun SettingsAppearanceGroup(content: @Composable () -> Unit) {
    RaftDesignSystemTheme {
        RaftListRowGroup(Modifier.fillMaxWidth()) { content() }
    }
}

/** Web `ServerThemeLink`: one plain row in an inset group (title only, no description), to Settings > Theme. */
@Composable
private fun SettingsAppearanceServerThemeLink(serverName: String, onClick: () -> Unit) {
    RaftDesignSystemTheme {
        RaftListRowGroup(Modifier.fillMaxWidth()) {
            RaftListRow(
                title = stringResource(Res.string.settings_appearance_server_theme_link_title, serverName),
                variant = RaftListRowVariant.Plain,
                titleMaxLines = Int.MAX_VALUE,
                onClick = onClick,
                trailing = { RaftListRowChevron() },
            )
        }
    }
}

/**
 * Web's display group: the four web switches in web order (Live agent activity, Show agent model,
 * Show member descriptions, Hide empty Home sections) followed by the app's own rows in the same
 * inset card, each named by its title alone (no per-item descriptions).
 */
@Composable
private fun SettingsAppearanceDisplayGroup(
    visual: SettingsAppearanceVisualSwitches?,
    appRows: SettingsMobileSnapshot?,
    actionState: SharedSettingsActionState?,
    preferenceActions: SettingsAppPreferenceActions,
    preferenceVisibility: SettingsAppPreferenceVisibility,
) {
    val showLiveAgentActivity = RaftDisplayPreferenceRuntime.observeShowLiveAgentActivity()
    val showAgentModelName = RaftDisplayPreferenceRuntime.observeShowAgentModelName()
    val showSenderDescription = RaftDisplayPreferenceRuntime.observeShowSenderDescription()
    val hideEmptySidebarSections = RaftDisplayPreferenceRuntime.observeHideEmptySidebarSections()
    SettingsAppearanceGroup {
        SettingsAppearanceSwitchRow(
            anchorId = "live-agent-activity",
            title = stringResource(Res.string.settings_appearance_live_agent_activity_title),
            checked = visual?.showLiveAgentActivity ?: showLiveAgentActivity,
            onCheckedChange = RaftDisplayPreferenceRuntime::setShowLiveAgentActivity,
            divider = false,
        )
        SettingsAppearanceSwitchRow(
            anchorId = "show-agent-model",
            title = stringResource(Res.string.settings_appearance_show_agent_model_title),
            checked = visual?.showAgentModelName ?: showAgentModelName,
            onCheckedChange = RaftDisplayPreferenceRuntime::setShowAgentModelName,
            divider = true,
        )
        SettingsAppearanceSwitchRow(
            anchorId = "show-sender-description",
            title = stringResource(Res.string.settings_appearance_show_sender_description_title),
            checked = visual?.showSenderDescription ?: showSenderDescription,
            onCheckedChange = RaftDisplayPreferenceRuntime::setShowSenderDescription,
            divider = true,
        )
        SettingsAppearanceSwitchRow(
            anchorId = "hide-empty-sidebar-sections",
            title = stringResource(Res.string.settings_appearance_hide_empty_sidebar_sections_title),
            checked = visual?.hideEmptySidebarSections ?: hideEmptySidebarSections,
            onCheckedChange = RaftDisplayPreferenceRuntime::setHideEmptySidebarSections,
            divider = true,
        )
        appRows?.let { snapshot ->
            SettingsAppearanceAppRows(snapshot, actionState, preferenceActions, preferenceVisibility)
        }
    }
}

/**
 * Web `ComposerSendKeySetting`: Appearance › Keyboard section, with "Send messages with" (`Enter` /
 * `⌘Enter` or `Ctrl+Enter`, stored on the account) and "Keyboard shortcuts" in one `ListRowGroup`.
 */
@Composable
private fun SettingsAppearanceKeyboardSection(
    visibility: SettingsAppPreferenceVisibility,
    composerSendKey: RaftComposerSendKey,
    actionState: SharedSettingsActionState?,
    onComposerSendKeyChange: (RaftComposerSendKey) -> Unit,
    shortcutsUseCommand: Boolean,
) {
    var shortcutsDialogOpen by remember { mutableStateOf(false) }
    val sendKeyError = actionState?.takeIf { it.action == APPEARANCE_ACTION_COMPOSER_SEND_KEY && it.errorCode != null }
    val sendKeySaveStatus =
        sendKeyError?.let {
            RaftSaveStatusState.Failed(stringResource(Res.string.settings_appearance_send_key_save_failed))
        }
    val sendKeyTraceId = sendKeyError?.screenError?.traceId ?: sendKeyError?.permissionBlocker?.traceId
    Box(Modifier.fillMaxWidth().testTag(SettingsAppearanceKeyboardSectionTag)) {
        SettingsFormSection(
            iconName = RaftLucideIconName.Keyboard,
            title = stringResource(Res.string.settings_appearance_keyboard_section),
        ) {
            SettingsAppearanceGroup {
                if (visibility.keyboardSendKey) {
                    SettingsAppearanceSegmentedRow(
                        title = stringResource(Res.string.settings_appearance_send_key_title),
                        divider = false,
                        anchorId = "send-with",
                        saveStatus = sendKeySaveStatus,
                        saveStatusTag = SettingsAppearanceSendKeyErrorTag,
                        traceId = sendKeyTraceId,
                        modifier = Modifier.testTag(SettingsAppearanceSendKeyRowTag),
                    ) {
                        SegmentedControl(
                            selectedValue = composerSendKey.wireValue,
                            options =
                                listOf(
                                    SegmentedControlOption(
                                        RaftComposerSendKey.Enter.wireValue,
                                        raftComposerEnterKeyLabel(),
                                    ),
                                    SegmentedControlOption(
                                        RaftComposerSendKey.ModEnter.wireValue,
                                        raftComposerModEnterKeyLabel(shortcutsUseCommand),
                                    ),
                                ),
                            onValueSelected = { value ->
                                onComposerSendKeyChange(RaftComposerSendKey.fromWire(value))
                            },
                        )
                    }
                }
                if (visibility.keyboardShortcuts) {
                    SettingsKeyboardShortcutsRow(
                        divider = visibility.keyboardSendKey,
                        onClick = { shortcutsDialogOpen = true },
                    )
                }
            }
        }
        if (shortcutsDialogOpen) {
            RaftDesignSystemTheme {
                SettingsKeyboardShortcutsDialog(
                    apple = shortcutsUseCommand,
                    onDismiss = { shortcutsDialogOpen = false },
                )
            }
        }
    }
}

/**
 * Web `AssistantOtterSettingsSection` (Settings › Appearance › Assistant):
 * - Group 1: "Show {name} on every page" switch wired to account-level [SharedMyAssistantStore.otterPrefs].
 *   Web's desktop-only `OtterShortcutRow` is omitted on mobile because mobile has no keyboard
 *   shortcut customization UI (matching web on phones).
 * - Group 2 (12dp gap inside [SettingsFormSection]): "More {name} settings" link to the assistant's
 *   profile on its Profile tab.
 * - Optional "Changed by your assistant" audit block (24dp below, [ASSISTANT_ACTION_SETTINGS]).
 */
@Composable
private fun SettingsAppearanceAssistantSection(
    assistant: MyAssistantRecord,
    otter: AssistantOwnerOtterRow?,
    changes: List<AssistantSettingChange>,
    changesServerId: String?,
    onOpenAssistantSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(SettingsAppearanceAssistantSectionTag),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SettingsFormSection(
            iconName = RaftLucideIconName.PawPrint,
            title = stringResource(Res.string.settings_appearance_assistant_section),
        ) {
            if (otter != null) {
                SettingsAppearanceGroup {
                    AssistantOtterVisibleRow(
                        name = assistant.name,
                        otter = otter,
                        description = stringResource(Res.string.settings_appearance_assistant_otter_description, assistant.name),
                    )
                }
            }
            SettingsAppearanceGroup {
                RaftListRow(
                    title = stringResource(Res.string.settings_appearance_assistant_panel_link_title, assistant.name),
                    modifier = Modifier.testTag(SettingsAppearanceAssistantPanelLinkTag),
                    onClick = onOpenAssistantSettings,
                    variant = RaftListRowVariant.Plain,
                    titleMaxLines = Int.MAX_VALUE,
                    description = stringResource(Res.string.settings_appearance_assistant_panel_link_description, assistant.name),
                    wrapDescription = true,
                    trailing = { RaftListRowChevron() },
                )
            }
        }
        if (changes.isNotEmpty()) {
            RaftDesignSystemTheme {
                AssistantSettingChangesSection(
                    changes = changes,
                    currentServerId = changesServerId,
                )
            }
        }
    }
}

/**
 * Whether Appearance shows Light, Dark or System (web AppearanceSection `themePageShown`): only
 * when there is no Theme page, i.e. no current server. Unknown until the session is read, so a
 * page with a server never flashes it.
 */
internal fun settingsAppearanceShowsModeCard(session: StoredAuthSession?): Boolean =
    session != null && session.settingsThemeServer() == null

/**
 * Light, Dark or System, the Web's AppearanceThemePicker, applied at once and saved on this device
 * for every server ([RaftAppAppearanceRuntime]). Settings > Theme's "Light and dark"; Appearance's
 * "Theme" where there is no Theme page.
 */
@Composable
internal fun SettingsAppearanceThemeCard(title: String, showDescription: Boolean = true) {
    val selected = RaftAppAppearanceRuntime.appearance
    SettingsFormSectionCard(iconName = RaftLucideIconName.Eye, title = title) {
        if (showDescription) {
            SettingsFormDetailText(stringResource(Res.string.settings_appearance_theme_description))
        }
        RaftDesignSystemTheme {
            RaftRadioGroup {
                RaftAppAppearanceOptions.forEach { (appearance, label) ->
                    RaftRadioRow(
                        label = stringResource(label),
                        selected = selected == appearance,
                        onClick = { RaftAppAppearanceRuntime.select(appearance) },
                    )
                }
            }
        }
    }
}

/** The Web's order (`APP_APPEARANCE_MODES`): light, dark, system. */
private val RaftAppAppearanceOptions =
    listOf(
        RaftAppAppearance.Light to Res.string.settings_appearance_mode_light,
        RaftAppAppearance.Dark to Res.string.settings_appearance_mode_dark,
        RaftAppAppearance.System to Res.string.settings_appearance_mode_system,
    )

/**
 * Web `appearance-font-size-group`: Message font size plain row (title + segmented control, no
 * description) and its attached Preview row (no hairline between them) inside one `ListRowGroup`.
 */
@Composable
private fun SettingsAppearanceFontSizeGroup(
    snapshot: SettingsAppearanceSnapshot,
    fontSizeDraft: String,
    actionState: SharedSettingsActionState?,
    onFontSizeDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val busy = actionState?.action == APPEARANCE_ACTION_MESSAGE_FONT_SIZE && actionState.inProgress
    val selected = fontSizeDraft.ifBlank { snapshot.messageBodyFontSize.selectedKey }
    val dirty = selected != snapshot.messageBodyFontSize.selectedKey
    // Auto-save immediately on font selection change.
    LaunchedEffect(fontSizeDraft) {
        if (!dirty) return@LaunchedEffect
        onSubmit()
    }
    val fontSizeError = actionState?.takeIf { it.action == APPEARANCE_ACTION_MESSAGE_FONT_SIZE && it.errorCode != null }
    SettingsAppearanceGroup {
        SettingsAppearanceSegmentedRow(
            title = stringResource(Res.string.settings_appearance_message_font_size_title),
            divider = false,
            anchorId = "message-font-size",
            error = fontSizeError,
        ) {
            SegmentedControl(
                selectedValue = selected,
                options =
                    snapshot.messageBodyFontSize.options.map {
                        SegmentedControlOption(
                            it.key,
                            settingsMessageFontSizeLabelRes(it.key)?.let { res -> stringResource(res) } ?: it.label,
                        )
                    },
                onValueSelected = onFontSizeDraftChange,
                enabled = snapshot.messageBodyFontSize.available && !busy,
            )
        }
        // Attached preview row (web `ListRow variant="plain" attached`): no hairline, no top padding.
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(Res.string.settings_appearance_preview),
                style = RaftType.SectionLabel.copy(color = RaftColor.TextMutedGray),
                color = RaftColor.TextMutedGray,
            )
            SettingsAppearancePreview(fontSizeKey = selected)
        }
    }
}

private const val APPEARANCE_PREVIEW_AGENT_ID = "appearance-preview-agent"

/** The Web's preview agent (`APPEARANCE_PREVIEW_AGENT`): Cindy, model "preview". */
private val AppearancePreviewModelLabelResolver =
    RaftMemberModelLabelResolver(
        currentLabel = { "preview" },
        labelFlow = { flowOf("preview") },
    )

/** The preview agent's pixel avatar (`pixel:random:cindy-preview`). */
private val AppearancePreviewAvatarResolver =
    RaftMemberAvatarResolver(
        currentAvatar = { "pixel:random:cindy-preview" },
        avatarFlow = { flowOf("pixel:random:cindy-preview") },
    )

/**
 * The Web's preview: a real message row (`MessageItem`) from the agent Cindy in the reader's
 * message size, with a mention, a channel and inline code. It follows "Show agent model" too.
 */
@Composable
private fun SettingsAppearancePreview(fontSizeKey: String) {
    val bodyStyle = raftMessageBodyTextStyle(fontSizeKey)
    val body = stringResource(Res.string.settings_appearance_preview_message)
    val pinnedTime = LocalSettingsAppearancePreviewTimeLabel.current
    val previewDateContext = currentRaftEffectiveDateTimeContext()
    val timeProvider = currentRaftTimeContextProvider
    val previewNowMillis =
        remember(timeProvider) {
            Snapshot.withoutReadObservation { timeProvider?.current?.nowEpochMillis } ?: defaultNowMillis()
        }
    val timeLabel = pinnedTime ?: previewDateContext.formatClock(previewNowMillis)
    val message =
        remember(body, timeLabel) {
            RaftThreadMessageItem(
                id = "appearance-preview-message",
                authorId = APPEARANCE_PREVIEW_AGENT_ID,
                authorName = "Cindy",
                authorHandle = "cindy-preview",
                authorKind = "agent",
                avatarLabel = "Cindy",
                avatarWireValue = "pixel:random:cindy-preview",
                timestampLabel = timeLabel,
                body = body,
            )
        }
    val knownChannels = remember { setOf("proj-uiux") }
    // The Web previews @Joy as plain text (no member of that name is loaded there).
    val knownMentions = remember { emptySet<String>() }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(RaftAppRadius.Control)).background(RaftColor.Canvas)) {
        ThreadMessageCard(
            message = message,
            messageBodyTextStyle = bodyStyle,
            showReplyAction = false,
            showSaveAction = false,
            taskOpenEnabled = false,
            taskStatusActionsEnabled = false,
            isMarkdownTagValid = { tag -> tag.isKnownThreadMarkdownTag(knownChannels, knownMentions, emptySet()) },
            markdownTagContextSignature = "appearance-preview",
            onShowActions = {},
            onOpenMember = {},
            onOpenThread = {},
            onOpenTask = {},
            onOpenTaskStatusMenu = {},
            onToggleReaction = {},
            onOpenAttachment = {},
            onOpenMarkdownTag = { _, _ -> },
            onOpenMarkdownUrl = {},
            memberAvatarResolver = AppearancePreviewAvatarResolver,
            memberModelLabelResolver = AppearancePreviewModelLabelResolver,
            outerPadding = 0.dp,
        )
    }
}

/**
 * Web `settings.appearance.fontSizeSmall` / `fontSizeMedium` / `fontSizeLarge`: a message font
 * size's word in this locale, by its key (the store's label is only its id).
 */
internal fun settingsMessageFontSizeLabelRes(key: String): StringResource? =
    when (key) {
        "sm" -> Res.string.settings_appearance_message_font_size_small
        "md" -> Res.string.settings_appearance_message_font_size_medium
        "lg" -> Res.string.settings_appearance_message_font_size_large
        else -> null
    }
