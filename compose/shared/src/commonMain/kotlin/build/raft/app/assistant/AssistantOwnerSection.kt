package build.raft.app.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftTraceIdNote
import build.raft.app.data.ASSISTANT_OWNER_NOTES_MAX_LENGTH
import build.raft.app.data.AssistantOwnerSetting
import build.raft.app.data.ASSISTANT_ACTION_SETTINGS
import build.raft.app.data.AssistantSettingChange
import build.raft.app.data.RaftAssistantSettingChangesApi
import build.raft.app.model.RaftTimeContext
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.ComputerAccessRevokedErrorCode
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MyAssistantDeleteOutcome
import build.raft.app.data.MyAssistantKey
import build.raft.app.data.RaftAssistantDmCardsApi
import build.raft.app.data.RaftAssistantWeChatConnectApi
import build.raft.app.data.MyAssistantOwnerNotesSaveOutcome
import build.raft.app.data.MyAssistantRecord
import build.raft.app.data.MyAssistantSettingSaveOutcome
import build.raft.app.data.MyAssistantState
import build.raft.app.data.PERSONAL_ASSISTANT_FEATURE_FLAG_KEY
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedMyAssistantStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.assistantOwnerNotesCanSave
import build.raft.app.data.assistantOwnerNotesShowsCounter
import build.raft.app.data.assistantOwnerNotesTooLong
import build.raft.app.data.myAssistantState
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.raftRelativeTimestampLabel
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftRiskTone
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldError
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSaveStatusState
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.RaftTextareaCounter
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// The owner's side of their personal assistant's profile (web assistant/AssistantOwnerSection.tsx,
// AssistantInstructionsDialog.tsx, AssistantOtterSettings.tsx, AssistantPaused.tsx
// `AssistantPausedNotice`; WeChat in AssistantOwnerWeChat.kt): under the profile, one 「Assistant」
// section with the permission switches, the otter, Extra Instructions, WeChat and Delete; under
// the hero, while a server owner or admin has paused it, the notice with Start. Only the owner
// sees either: "my assistant" is the viewer's own, so another agent's profile, or anyone else's
// view of this one, shows nothing. The owner cannot pause it (web parity: pausing is the server
// owner's or an admin's).

/**
 * What the profile needs to find the viewer's own assistant, provided by the member detail route
 * (MembersPage `MemberDetailPageContent`) for the server it shows. Absent elsewhere: no section.
 *
 * [startAgent] is the route's lifecycle Start (`MemberRepository.startAgent`, single-flight per
 * agent with the profile's own Start); null when the route cannot start agents. [onErrorToast]
 * is `bridge.errorToast`, for a failure that outlives the notice.
 */
internal class AssistantOwnerScope(
    val store: SharedMyAssistantStore,
    val featureFlags: SharedServerFeatureFlags,
    val serverMembersStore: SharedServerMembersStore,
    val startAgent: (suspend (MemberId) -> DataResult<AgentLifecycleActionResult>)?,
    val onErrorToast: (message: String, error: ScreenError) -> Unit,
    val serverId: String,
    val userId: String,
    val viewerIsGuest: Boolean,
    /** Reads what the assistant changed for its owner (Recent changes); null: no such row. */
    val settingChangesApi: RaftAssistantSettingChangesApi? = null,
    /** The WeChat bridge's status and Disconnect; null: no WeChat row. */
    val weChatApi: RaftAssistantDmCardsApi? = null,
    /** Connecting WeChat with a QR code (wide windows only); null: never offered. */
    val weChatConnectApi: RaftAssistantWeChatConnectApi? = null,
    /**
     * The route's own scope (it outlives the section): a delete or a disconnect the server may
     * already have done finishes, and what follows it runs, even once the section is gone.
     */
    val actionScope: CoroutineScope? = null,
    /** `bridge.toastWithIntent`: the outcome of a disconnect (web `toast.success` / `toast.info`). */
    val onToast: (message: String, intent: RaftToastIntent) -> Unit = { _, _ -> },
    /**
     * After the assistant is deleted: the profile closes (back to the assistant's page, which
     * offers Create again, as web's page does once `assistant` is null).
     */
    val onAssistantDeleted: () -> Unit = {},
)

internal val LocalAssistantOwnerScope = compositionLocalOf<AssistantOwnerScope?> { null }

/**
 * The viewer's own assistant when it is [agentId] (web AssistantOwnerSection's
 * `assistant.agentId !== agentId` check), else null: the `personal_assistant` flag must be on, the
 * viewer not a guest, and "my assistant" read and ready.
 */
@Composable
internal fun rememberOwnAssistant(agentId: String): MyAssistantRecord? {
    val owner = LocalAssistantOwnerScope.current ?: return null
    val serverId = ServerId(owner.serverId)
    val flagEnabled = rememberServerFeatureLatched(owner.featureFlags, serverId, PERSONAL_ASSISTANT_FEATURE_FLAG_KEY)
    val key = remember(owner.serverId, owner.userId) { MyAssistantKey(owner.serverId, owner.userId) }
    val shouldAsk = flagEnabled && !owner.viewerIsGuest
    LaunchedEffect(owner.store, key, shouldAsk) {
        if (shouldAsk) owner.store.ensureLoaded(key)
    }
    val answers by owner.store.answers.collectAsState()
    val state =
        myAssistantState(
            viewerIsGuest = owner.viewerIsGuest,
            flagDecisionPending = owner.featureFlags.isDecisionPending(serverId, PERSONAL_ASSISTANT_FEATURE_FLAG_KEY),
            flagEnabled = flagEnabled,
            answer = answers[key],
        )
    return (state as? MyAssistantState.Ready)?.assistant?.takeIf { it.agentId == agentId.trim() }
}

/**
 * The owner's 「Assistant」 section of their assistant's profile (web `AssistantOwnerSection`), in
 * web's order: the two permission switches (Read my direct messages, Send messages as me); Show
 * the otter on every page; Extra Instructions, WeChat and Recent changes; Delete assistant last.
 * A row that needs something from the server shows only while the server reports it (web: a
 * switch when its setting is not null, the instructions when `ownerNotes !== null`, WeChat while
 * it may be connected here or a connection exists). [frame] draws the profile's section chrome
 * (separator and padding) round the content.
 */
@Composable
internal fun AssistantOwnerSection(
    agentId: String,
    frame: @Composable (content: @Composable ColumnScope.() -> Unit) -> Unit,
) {
    val owner = LocalAssistantOwnerScope.current ?: return
    val assistant = rememberOwnAssistant(agentId) ?: return
    val savedNotes = assistant.ownerNotes
    // Web `useAssistantSettingChanges(ASSISTANT_ACTION_SETTINGS)`: what it did on the owner's say-so.
    val changesSource =
        remember(owner.serverId, owner.settingChangesApi) {
            owner.settingChangesApi?.let { AssistantSettingChangesSource(owner.serverId, it) }
        }
    val changes = rememberAssistantSettingChanges(ASSISTANT_ACTION_SETTINGS, changesSource)
    val key = remember(owner.serverId, owner.userId) { MyAssistantKey(owner.serverId, owner.userId) }
    var open by remember(agentId) { mutableStateOf<AssistantOwnerDialog?>(null) }
    val switches = remember(agentId, key) { AssistantOwnerSwitchesState() }
    val scope = rememberCoroutineScope()
    val actionScope = owner.actionScope ?: scope
    val otter = rememberAssistantOwnerOtterRow(owner)
    // Web reads the WeChat status again each time the panel opens (the bridge may have stopped).
    val weChat = remember(owner.weChatApi, owner.serverId, actionScope) { owner.weChatApi?.let { AssistantWeChatStore(it, owner.serverId, actionScope) } }
    LaunchedEffect(weChat) { weChat?.refresh() }
    val weChatStatus = weChat?.status?.collectAsState()?.value.readyStatus()
    // The store's delete is single flight; this is what the confirm shows meanwhile.
    var deleting by remember(agentId) { mutableStateOf(false) }
    frame {
        AssistantOwnerSectionContent(
            name = assistant.name,
            readsDms = assistant.readsDms,
            sendAsOwner = assistant.sendAsOwner,
            showInstructions = savedNotes != null,
            onOpenInstructions = { open = AssistantOwnerDialog.Instructions },
            showRecentChanges = changes.isNotEmpty(),
            onOpenRecentChanges = { open = AssistantOwnerDialog.Changes },
            onSettingChange = { setting, value ->
                if (!switches.isSaving(setting)) {
                    scope.launch { switches.flip(setting, value) { s, v -> owner.store.saveSetting(key, s, v) } }
                }
            },
            saving = switches.saving,
            failures = switches.failures,
            otter = otter,
            weChat = weChatStatus?.takeIf { it.offersWeChatRow() }?.let { AssistantOwnerWeChatRow(connected = it.binding != null) },
            onOpenWeChat = { open = AssistantOwnerDialog.WeChat },
            onDelete = { open = AssistantOwnerDialog.Delete },
        )
    }
    when (open) {
        AssistantOwnerDialog.Instructions ->
            if (savedNotes != null) {
                AssistantInstructionsSheet(
                    name = assistant.name,
                    savedNotes = savedNotes,
                    onSave = { draft -> owner.store.saveOwnerNotes(key, draft) },
                    onDismiss = { open = null },
                )
            }
        AssistantOwnerDialog.Changes ->
            if (changes.isNotEmpty()) {
                AssistantRecentChangesSheet(
                    changes = changes,
                    currentServerId = owner.serverId,
                    onDismiss = { open = null },
                )
            }
        AssistantOwnerDialog.WeChat ->
            if (weChat != null && weChatStatus != null) {
                val disconnecting by weChat.disconnecting.collectAsState()
                // The settings entry and the DM notice follow the status once connected.
                val connect = rememberAssistantWeChatConnectStore(owner.weChatConnectApi, owner.serverId) { weChat.refresh() }
                AssistantWeChatDialog(
                    connect = connect,
                    name = assistant.name,
                    status = weChatStatus,
                    disconnecting = disconnecting,
                    onDisconnect = weChat::disconnect,
                    onToastSuccess = { owner.onToast(it, RaftToastIntent.Success) },
                    onToastInfo = { owner.onToast(it, RaftToastIntent.Info) },
                    onClose = { open = null },
                )
            }
        AssistantOwnerDialog.Delete -> {
            val failedMessage = stringResource(Res.string.assistant_delete_failed)
            AssistantDeleteConfirm(
                name = assistant.name,
                busy = deleting,
                onDismiss = { if (!deleting) open = null },
                onConfirm = {
                    if (!deleting) {
                        deleting = true
                        actionScope.launch {
                            val outcome =
                                try {
                                    owner.store.delete(key)
                                } finally {
                                    deleting = false
                                }
                            // Web closes the confirm when the request ends, either way.
                            open = null
                            when (outcome) {
                                MyAssistantDeleteOutcome.Deleted -> owner.onAssistantDeleted()
                                // Web says nothing; the app says why, with the trace ID (AGENTS.md).
                                is MyAssistantDeleteOutcome.Failed -> owner.onErrorToast(failedMessage, outcome.error)
                            }
                        }
                    }
                },
            )
        }
        null -> Unit
    }
}

/** Which of the section's dialogs is open (web AssistantOwnerSection `Open`). */
internal enum class AssistantOwnerDialog { Instructions, WeChat, Changes, Delete }

/** The WeChat row: "WeChat" once connected, else "Connect WeChat" (web `menuConnected` / `menuConnect`). */
internal data class AssistantOwnerWeChatRow(val connected: Boolean)

/**
 * "Show {name} on every page" (web `OtterVisibleRow` with the profile's shorter line): the
 * account's switch ([checked]), disabled while it saves or before it is known, and the failure of
 * the last save with its trace ID.
 */
internal data class AssistantOwnerOtterRow(
    val checked: Boolean,
    val enabled: Boolean,
    val failure: ScreenError? = null,
    val onCheckedChange: (Boolean) -> Unit = {},
)

/** The otter row from the account's setting ([SharedMyAssistantStore.otterPrefs]); null without one. */
@Composable
internal fun rememberAssistantOtterRow(store: SharedMyAssistantStore, userId: String): AssistantOwnerOtterRow? {
    val prefs = store.otterPrefs ?: return null
    LaunchedEffect(prefs, userId) { prefs.ensureLoaded(userId) }
    val state by prefs.state.collectAsState()
    val scope = rememberCoroutineScope()
    val mine = state.accountId == userId.trim()
    return AssistantOwnerOtterRow(
        // Web: shown unless the account turned it off.
        checked = !mine || state.visible != false,
        enabled = mine && state.visible != null && !state.saving,
        failure = state.saveFailure.takeIf { mine },
        onCheckedChange = { value -> scope.launch { prefs.setVisible(userId, value) } },
    )
}

/** The otter row from the account's setting ([SharedMyAssistantStore.otterPrefs]); null without one. */
@Composable
private fun rememberAssistantOwnerOtterRow(owner: AssistantOwnerScope): AssistantOwnerOtterRow? =
    rememberAssistantOtterRow(owner.store, owner.userId)

/**
 * Delete assistant? (web AssistantOwnerSection `assistant-delete-confirm`): a small dialog, × in
 * the head, Cancel then the red Delete; nothing closes it while the delete runs.
 */
@Composable
internal fun AssistantDeleteConfirm(
    name: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    RaftDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.assistant_delete_title),
        modifier = Modifier.testTag("assistant-delete-confirm"),
        size = RaftDialogSize.Sm,
        description = stringResource(Res.string.assistant_delete_body, name),
        dismissOnClickOutside = !busy,
        footer = {
            RaftButton(
                text = stringResource(Res.string.common_cancel),
                onClick = onDismiss,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
                enabled = !busy,
            )
            RaftButton(
                text = stringResource(Res.string.assistant_delete),
                onClick = onConfirm,
                modifier = Modifier.testTag("assistant-delete-confirm-action"),
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
                enabled = !busy,
            )
        },
    )
}

/**
 * Recent changes (web AssistantOwnerSection's `assistant-changes-dialog`): the lines of what the
 * assistant did on the owner's say-so, with Done.
 */
@Composable
internal fun AssistantRecentChangesSheet(
    changes: List<AssistantSettingChange>,
    currentServerId: String?,
    onDismiss: () -> Unit,
) {
    RaftDialogSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.assistant_recent_changes),
        commitLabel = stringResource(Res.string.common_done),
        onCommit = onDismiss,
        modifier = Modifier.testTag("assistant-changes-dialog"),
        closeLabel = stringResource(Res.string.common_close),
    ) {
        AssistantRecentChangesBody(changes, currentServerId)
    }
}

/** The sheet's body: the lines, scrolling when long. */
@Composable
internal fun AssistantRecentChangesBody(
    changes: List<AssistantSettingChange>,
    currentServerId: String?,
    timeContext: RaftTimeContext? = null,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        AssistantSettingChangesList(changes, currentServerId, timeContext = timeContext)
    }
}

/**
 * The owner's switches' rows (web `OwnerSwitchRow`): which are saving (their switch is disabled,
 * web `disabled={saving}`) and which failed last (the failure line under the row, until the next
 * flip). The switch's value itself is the store's, which moves at once and moves back on failure.
 */
@Stable
internal class AssistantOwnerSwitchesState {
    var saving: Set<AssistantOwnerSetting> by mutableStateOf(emptySet())
        private set

    var failures: Map<AssistantOwnerSetting, ScreenError> by mutableStateOf(emptyMap())
        private set

    fun isSaving(setting: AssistantOwnerSetting): Boolean = setting in saving

    /**
     * Saves [setting] at [value] through [save] unless a save of it is running: clears its last
     * failure, keeps it saving until [save] answers, and remembers a failure.
     */
    suspend fun flip(
        setting: AssistantOwnerSetting,
        value: Boolean,
        save: suspend (AssistantOwnerSetting, Boolean) -> MyAssistantSettingSaveOutcome,
    ) {
        if (setting in saving) return
        saving = saving + setting
        failures = failures - setting
        try {
            val outcome =
                try {
                    save(setting, value)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // No server answer to trace.
                    MyAssistantSettingSaveOutcome.Failed(ScreenError("assistant_setting_save_failed", traceId = null))
                }
            if (outcome is MyAssistantSettingSaveOutcome.Failed) failures = failures + (setting to outcome.error)
        } finally {
            saving = saving - setting
        }
    }
}

/**
 * The section's eyebrow, the permissions group (web `assistant-owner-permissions`, in its order:
 * Read my direct messages, then Send messages as me) and the instructions group (web
 * `assistant-owner-section`, groups 16dp apart). A null switch value hides its row.
 */
@Composable
internal fun AssistantOwnerSectionContent(
    name: String,
    readsDms: Boolean?,
    sendAsOwner: Boolean?,
    showInstructions: Boolean,
    onOpenInstructions: () -> Unit,
    onSettingChange: (AssistantOwnerSetting, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Web `changes.length > 0`: the Recent changes row, which opens [onOpenRecentChanges]. */
    showRecentChanges: Boolean = false,
    onOpenRecentChanges: () -> Unit = {},
    saving: Set<AssistantOwnerSetting> = emptySet(),
    failures: Map<AssistantOwnerSetting, ScreenError> = emptyMap(),
    /** "Show {name} on every page"; null: no otter group. */
    otter: AssistantOwnerOtterRow? = null,
    /** The WeChat row (web `hasWeChat`); null: none. */
    weChat: AssistantOwnerWeChatRow? = null,
    onOpenWeChat: () -> Unit = {},
    /** Delete assistant, last; null: none. */
    onDelete: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().testTag("assistant-owner-section")) {
        // Web SectionEyebrow `mb-3`.
        RaftSectionEyebrow(
            text = stringResource(Res.string.assistant_owner_section_title),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        // Web `stack-y-4` between the groups.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (readsDms != null || sendAsOwner != null) {
                RaftListRowGroup(Modifier.fillMaxWidth().testTag("assistant-owner-permissions")) {
                    if (readsDms != null) {
                        AssistantOwnerSwitchRow(
                            setting = AssistantOwnerSetting.ReadsDms,
                            checked = readsDms,
                            title = stringResource(Res.string.assistant_setting_reads_dms),
                            description = stringResource(Res.string.assistant_setting_reads_dms_description),
                            saving = AssistantOwnerSetting.ReadsDms in saving,
                            failure = failures[AssistantOwnerSetting.ReadsDms],
                            divider = false,
                            onCheckedChange = { onSettingChange(AssistantOwnerSetting.ReadsDms, it) },
                        )
                    }
                    if (sendAsOwner != null) {
                        AssistantOwnerSwitchRow(
                            setting = AssistantOwnerSetting.SendAsOwner,
                            checked = sendAsOwner,
                            title = stringResource(Res.string.assistant_setting_send_as_owner),
                            description = stringResource(Res.string.assistant_setting_send_as_owner_description),
                            saving = AssistantOwnerSetting.SendAsOwner in saving,
                            failure = failures[AssistantOwnerSetting.SendAsOwner],
                            divider = readsDms != null,
                            onCheckedChange = { onSettingChange(AssistantOwnerSetting.SendAsOwner, it) },
                        )
                    }
                }
            }
            if (otter != null) {
                // Web `assistant-owner-otter`; its shortcut row is web's desktop only (no keyboard here).
                RaftListRowGroup(Modifier.fillMaxWidth().testTag("assistant-owner-otter")) {
                    AssistantOtterVisibleRow(name, otter)
                }
            }
            if (showInstructions || weChat != null || showRecentChanges) {
                RaftListRowGroup(Modifier.fillMaxWidth()) {
                    if (showInstructions) {
                        RaftListRow(
                            title = stringResource(Res.string.assistant_instructions_title),
                            modifier = Modifier.testTag("assistant-settings-instructions"),
                            onClick = onOpenInstructions,
                            variant = RaftListRowVariant.Plain,
                            description = stringResource(Res.string.assistant_instructions_row_description, name),
                            wrapDescription = true,
                            trailing = { RaftListRowChevron() },
                        )
                    }
                    if (weChat != null) {
                        RaftListRow(
                            title =
                                stringResource(
                                    if (weChat.connected) Res.string.assistant_wechat_menu_connected else Res.string.assistant_wechat_menu_connect
                                ),
                            modifier = Modifier.testTag("assistant-settings-wechat"),
                            onClick = onOpenWeChat,
                            variant = RaftListRowVariant.Plain,
                            description = stringResource(Res.string.assistant_wechat_row_description, name),
                            wrapDescription = true,
                            divider = showInstructions,
                            trailing = { RaftListRowChevron() },
                        )
                    }
                    if (showRecentChanges) {
                        RaftListRow(
                            title = stringResource(Res.string.assistant_recent_changes),
                            modifier = Modifier.testTag("assistant-settings-changes"),
                            onClick = onOpenRecentChanges,
                            variant = RaftListRowVariant.Plain,
                            description = stringResource(Res.string.assistant_recent_changes_description, name),
                            wrapDescription = true,
                            divider = showInstructions || weChat != null,
                            trailing = { RaftListRowChevron() },
                        )
                    }
                }
            }
            if (onDelete != null) {
                // Web's full-width `danger-outline` Button: the app's design system has no outlined
                // danger button (raft-ui's doc forbids it), so the danger zone's action row (RiskTone).
                RaftListRowGroup(Modifier.fillMaxWidth()) {
                    RaftListRow(
                        title = stringResource(Res.string.assistant_delete),
                        modifier = Modifier.testTag("assistant-settings-delete"),
                        onClick = onDelete,
                        variant = RaftListRowVariant.Plain,
                        risk = RaftRiskTone.Danger,
                    )
                }
            }
        }
    }
}

/**
 * One owner switch (web `OwnerSwitchRow`): a plain settings row with its switch at the end, which
 * saves when flipped and is disabled while that save runs. A failed save says so under the
 * description ([RaftListRow] `saveStatus`), with the request's trace ID under the row.
 */
@Composable
private fun AssistantOwnerSwitchRow(
    setting: AssistantOwnerSetting,
    checked: Boolean,
    title: String,
    description: String,
    saving: Boolean,
    failure: ScreenError?,
    divider: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val failedMessage = stringResource(Res.string.assistant_setting_save_failed)
    RaftListRow(
        title = title,
        modifier = Modifier.testTag("assistant-setting-${setting.wireName}-row"),
        variant = RaftListRowVariant.Plain,
        titleMaxLines = 2,
        description = description,
        wrapDescription = true,
        divider = divider,
        saveStatus = if (failure != null) RaftSaveStatusState.Failed(failedMessage) else RaftSaveStatusState.Idle,
        trailing = {
            RaftSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag("assistant-setting-${setting.wireName}"),
                enabled = !saving,
                label = title,
            )
        },
    )
    if (failure != null) {
        RaftTraceIdNote(failure.traceId, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
    }
}

/**
 * "Show {name} on every page" (web `OtterVisibleRow`): defaults to the profile panel's shorter
 * description; Settings › Appearance passes the full description.
 */
@Composable
internal fun AssistantOtterVisibleRow(
    name: String,
    otter: AssistantOwnerOtterRow,
    description: String = stringResource(Res.string.assistant_otter_show_everywhere_description),
) {
    val title = stringResource(Res.string.assistant_otter_show_everywhere, name)
    RaftListRow(
        title = title,
        modifier = Modifier.testTag("assistant-otter-visible-row"),
        variant = RaftListRowVariant.Plain,
        titleMaxLines = 2,
        description = description,
        wrapDescription = true,
        saveStatus =
            if (otter.failure != null) {
                RaftSaveStatusState.Failed(stringResource(Res.string.assistant_setting_save_failed))
            } else {
                RaftSaveStatusState.Idle
            },
        trailing = {
            RaftSwitch(
                checked = otter.checked,
                onCheckedChange = otter.onCheckedChange,
                modifier = Modifier.testTag("assistant-otter-visible"),
                enabled = otter.enabled,
                label = title,
            )
        },
    )
    if (otter.failure != null) {
        RaftTraceIdNote(otter.failure.traceId, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
    }
}

/**
 * Why the editor could not save (web AssistantInstructionsDialog `SaveError`), with the failed
 * request's [traceId] shown under the message (AGENTS.md "Errors carry the trace ID").
 */
internal sealed interface AssistantInstructionsSaveError {
    val traceId: String?

    data class TooLong(override val traceId: String? = null) : AssistantInstructionsSaveError

    data class Failed(override val traceId: String? = null) : AssistantInstructionsSaveError
}

/**
 * Extra Instructions (web `AssistantInstructionsDialog`, the phone sheet): the owner's own notes
 * for their assistant. The draft starts from [savedNotes]. Save (enabled once the trimmed draft
 * differs and fits) is not optimistic: the sheet stays with the draft until the server answers,
 * closes on success, and shows a failure as an orange banner inside it. Closing with unsaved
 * edits asks first, as Change avatar does.
 */
@Composable
internal fun AssistantInstructionsSheet(
    name: String,
    savedNotes: String,
    onSave: suspend (String) -> MyAssistantOwnerNotesSaveOutcome,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(savedNotes) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<AssistantInstructionsSaveError?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dirty = draft.trim() != savedNotes.trim()

    fun requestClose() {
        if (saving) return
        if (dirty) confirmDiscard = true else onDismiss()
    }

    fun save() {
        if (saving || !assistantOwnerNotesCanSave(draft, savedNotes)) return
        val staged = draft
        saving = true
        saveError = null
        scope.launch {
            val outcome =
                try {
                    onSave(staged)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // No server answer to trace.
                    MyAssistantOwnerNotesSaveOutcome.Failed(ScreenError("assistant_owner_notes_save_failed", traceId = null))
                }
            saving = false
            when (outcome) {
                MyAssistantOwnerNotesSaveOutcome.Saved -> onDismiss()
                is MyAssistantOwnerNotesSaveOutcome.TooLong ->
                    saveError = AssistantInstructionsSaveError.TooLong(outcome.error.traceId)
                is MyAssistantOwnerNotesSaveOutcome.Failed ->
                    saveError = AssistantInstructionsSaveError.Failed(outcome.error.traceId)
            }
        }
    }

    RaftDialogSheet(
        onDismissRequest = ::requestClose,
        title = stringResource(Res.string.assistant_instructions_title),
        commitLabel = stringResource(Res.string.assistant_instructions_save),
        onCommit = ::save,
        commitEnabled = assistantOwnerNotesCanSave(draft, savedNotes),
        commitLoading = saving,
        // Web `closeDisabled={saving}`.
        closeEnabled = !saving,
        closeLabel = stringResource(Res.string.common_close),
        cancelLabel = stringResource(Res.string.common_cancel),
    ) {
        AssistantInstructionsEditorBody(
            name = name,
            draft = draft,
            onDraftChange = {
                draft = it
                saveError = null
            },
            saving = saving,
            saveError = saveError,
        )
    }

    if (confirmDiscard) {
        AssistantInstructionsDiscardDialog(
            onKeepEditing = { confirmDiscard = false },
            onDiscard = {
                confirmDiscard = false
                onDismiss()
            },
        )
    }
}

/**
 * The editor's body: what the notes are for, the field, under it the limit's error and the
 * counter (from 90% of the limit on, counting the trimmed text), then a failed save's banner.
 */
@Composable
internal fun AssistantInstructionsEditorBody(
    name: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    saving: Boolean,
    saveError: AssistantInstructionsSaveError?,
) {
    val tooLong = assistantOwnerNotesTooLong(draft)
    val showCounter = assistantOwnerNotesShowsCounter(draft)
    val limitMessage = stringResource(Res.string.assistant_instructions_too_long, ASSISTANT_OWNER_NOTES_MAX_LENGTH.toString())
    // RaftDialogSheet leaves the phone's 16dp margin to its body; long notes scroll the body.
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md),
    ) {
        RaftText(
            text = stringResource(Res.string.assistant_instructions_description, name),
            style = RaftTheme.typography.subhead,
            color = RaftTheme.colors.foregroundMuted,
        )
        RaftField(label = null, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftTextarea(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    inputModifier = Modifier.testTag("assistant-instructions-input"),
                    placeholder = stringResource(Res.string.assistant_instructions_placeholder),
                    readOnly = saving,
                    invalid = tooLong,
                    // Web `rows={8}`: eight 24dp lines and the box's padding.
                    minHeight = 214.dp,
                    // Long notes scroll inside the box, so the limit's error and counter stay in view.
                    maxHeight = 360.dp,
                )
                if (tooLong || showCounter) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        if (tooLong) {
                            RaftFieldError(limitMessage, Modifier.weight(1f).testTag("assistant-instructions-too-long"))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        if (showCounter) {
                            RaftTextareaCounter(
                                count = draft.trim().length,
                                limit = ASSISTANT_OWNER_NOTES_MAX_LENGTH,
                                modifier = Modifier.padding(start = 12.dp).testTag("assistant-instructions-counter"),
                            )
                        }
                    }
                }
            }
        }
        if (saveError != null) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RaftBanner(
                    description =
                        when (saveError) {
                            is AssistantInstructionsSaveError.TooLong -> limitMessage
                            is AssistantInstructionsSaveError.Failed -> stringResource(Res.string.assistant_instructions_save_failed)
                        },
                    status = RaftBannerStatus.Warning,
                    size = RaftBannerSize.Sm,
                    modifier = Modifier.testTag("assistant-instructions-error"),
                )
                RaftTraceIdNote(saveError.traceId)
            }
        }
    }
}

/**
 * Discard changes? before closing the instructions or the WeChat connect flow with unsaved edits
 * (web `assistant-instructions-discard` / `wechat-discard-confirm`, slock #14958 and #14967): a
 * question with two buttons, so a small dialog with × in the head, Keep Editing then the red
 * Discard. ×, Back and a tap outside keep editing.
 */
@Composable
internal fun AssistantInstructionsDiscardDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
    /** The web's `data-testid`: `assistant-instructions-discard`, or WeChat's `wechat-discard-confirm`. */
    testTag: String = "assistant-instructions-discard",
) {
    RaftDialog(
        onDismissRequest = onKeepEditing,
        title = stringResource(Res.string.member_avatar_discard_title),
        modifier = Modifier.testTag(testTag),
        size = RaftDialogSize.Sm,
        description = stringResource(Res.string.member_avatar_discard_body),
        footer = {
            RaftButton(
                text = stringResource(Res.string.member_avatar_keep_editing),
                onClick = onKeepEditing,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
            RaftButton(
                text = stringResource(Res.string.member_avatar_discard),
                onClick = onDiscard,
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
            )
        },
    )
}

/**
 * The paused notice for the assistant's owner (web `AssistantPausedNotice` with `isOwner`): who
 * paused it and when, that it won't reply or run its brief, and Start (`POST agents/:id/start`,
 * through the route's lifecycle Start), after which the roster is read again. The pause rides on
 * the roster's agent (`assistantPausedAt`, `assistantPausedBy`); [fallbackPausedAt] is the
 * profile's own value while the roster has none.
 *
 * A failed start shows under the notice with its trace ID. The server lifts the pause before it
 * refuses an assistant with no Computer (409 `machine_unassigned`) or a revoked one
 * (`computer_access_revoked`), so the notice goes away with `agent:updated`: those say why in an
 * error toast instead, which outlives it.
 */
@Composable
internal fun AssistantOwnerPausedNotice(
    agentId: String,
    fallbackPausedAt: String?,
    modifier: Modifier = Modifier,
) {
    val owner = LocalAssistantOwnerScope.current ?: return
    rememberOwnAssistant(agentId) ?: return
    val serverId = ServerId(owner.serverId)
    val entryFlow = remember(owner.serverMembersStore, owner.serverId) { owner.serverMembersStore.serverMembersEntryFlow(serverId) }
    val initialEntry = remember(owner.serverMembersStore, owner.serverId) { owner.serverMembersStore.currentServerMembersEntry(serverId) }
    val entry by entryFlow.collectAsState(initialEntry)
    val rostered = entry.membersById[agentId.trim()]
    val pausedAt =
        (if (rostered != null) rostered.assistantPausedAt else fallbackPausedAt)?.takeIf { it.isNotBlank() }
    // Started here: hidden at once on the server's yes (web clears the pause before the refetch).
    var startedFrom by remember(agentId) { mutableStateOf<String?>(null) }
    var starting by remember(agentId) { mutableStateOf(false) }
    var failure by remember(agentId) { mutableStateOf<ScreenError?>(null) }
    val scope = rememberCoroutineScope()
    val revokedMessage = stringResource(Res.string.agent_status_computer_access_revoked)
    val noComputerMessage = stringResource(Res.string.member_machine_no_computer_assigned)
    if (pausedAt == null || startedFrom == pausedAt) return
    val start = owner.startAgent
    AssistantPausedBanner(
        line = assistantPausedLine(pausedAt, rostered?.assistantPausedByName),
        starting = starting,
        failed = failure != null,
        failureTraceId = failure?.traceId,
        onStart =
            start?.let {
                {
                    if (!starting) {
                        starting = true
                        failure = null
                        scope.launch {
                            val result =
                                try {
                                    start(MemberId(agentId.trim()))
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Throwable) {
                                    DataResult.Error(ScreenError("agent_start_failed", traceId = null))
                                }
                            starting = false
                            val error = assistantStartError(result)
                            when {
                                error == null -> {
                                    startedFrom = pausedAt
                                    owner.serverMembersStore.refreshMembers(
                                        serverId,
                                        DataRequestPriority.Foreground,
                                        force = true,
                                        reason = "assistant_start",
                                    )
                                }
                                // The profile's own Start is on its way; its answer updates the roster.
                                error.code == AssistantStartInFlightCode -> Unit
                                else -> {
                                    failure = error
                                    val refusal = assistantStartRefusal(error) ?: return@launch
                                    owner.onErrorToast(
                                        when (refusal) {
                                            AssistantStartRefusal.MachineUnassigned -> noComputerMessage
                                            AssistantStartRefusal.ComputerAccessRevoked -> revokedMessage
                                        },
                                        error.copy(serverMessage = null),
                                    )
                                    // The pause is lifted already: read the roster for it.
                                    owner.serverMembersStore.refreshMembers(
                                        serverId,
                                        DataRequestPriority.Foreground,
                                        force = true,
                                        reason = "assistant_start_refused",
                                    )
                                }
                            }
                        }
                    }
                }
            },
        modifier = modifier,
    )
}

/** `MemberRepository.startAgent`'s single-flight refusal: another Start of this agent is running. */
internal const val AssistantStartInFlightCode = "agent_start_in_flight"

/** The start refusals the server answers after it has lifted the pause (slock `POST agents/:id/start`). */
internal enum class AssistantStartRefusal { MachineUnassigned, ComputerAccessRevoked }

internal fun assistantStartRefusal(error: ScreenError): AssistantStartRefusal? =
    when (error.serverCode ?: error.code) {
        "machine_unassigned" -> AssistantStartRefusal.MachineUnassigned
        ComputerAccessRevokedErrorCode -> AssistantStartRefusal.ComputerAccessRevoked
        else -> null
    }

/** Null when the start went through, else why not. */
internal fun assistantStartError(result: DataResult<AgentLifecycleActionResult>): ScreenError? =
    when (result) {
        is DataResult.Content -> if (result.data.success) null else ScreenError("agent_start_failed", traceId = null)
        is DataResult.Empty -> ScreenError(result.reason.name, retryable = false, traceId = null)
        is DataResult.Error -> result.error
        is DataResult.NoPermission -> ScreenError(result.blocker.code, retryable = false, traceId = result.blocker.traceId)
    }

/**
 * "Paused by {name} · {time}" (web `useAssistantPausedLine`): without the name for a viewer who
 * isn't sent it, without the time when it can't be read.
 */
@Composable
internal fun assistantPausedLine(pausedAt: String, pausedByName: String?): String {
    val time = raftRelativeTimestampLabel(pausedAt).takeIf { it.isNotBlank() }
    val name = pausedByName?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        name != null && time != null -> stringResource(Res.string.assistant_paused_by_at, name, time)
        name != null -> stringResource(Res.string.assistant_paused_by, name)
        time != null -> stringResource(Res.string.assistant_paused_at, time)
        else -> stringResource(Res.string.assistant_paused_chip)
    }
}

/**
 * The owner's paused notice (web `PausedNotice`, `isOwner`): an info banner with [line] as its
 * title, the owner's body, a failed start under it, and one bordered Start ([onStart] null: none).
 * A failed start's [failureTraceId] shows under the banner.
 */
@Composable
internal fun AssistantPausedBanner(
    line: String,
    starting: Boolean,
    failed: Boolean,
    onStart: (() -> Unit)?,
    modifier: Modifier = Modifier,
    failureTraceId: String? = null,
) {
    val body = stringResource(Res.string.assistant_paused_owner_body)
    val failure = stringResource(Res.string.assistant_paused_start_failed)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftBanner(
            description = if (failed) "$body\n$failure" else body,
            status = RaftBannerStatus.Info,
            title = line,
            modifier = Modifier.fillMaxWidth().testTag("assistant-paused-notice"),
            action =
                onStart?.let { start ->
                    {
                        RaftButton(
                            text = stringResource(Res.string.assistant_paused_start),
                            onClick = start,
                            modifier = Modifier.testTag("assistant-paused-start"),
                            variant = RaftButtonVariant.Default,
                            size = RaftButtonSize.Md,
                            loading = starting,
                            loadingLabel = stringResource(Res.string.assistant_paused_starting),
                        )
                    }
                },
        )
        RaftTraceIdNote(failureTraceId.takeIf { failed })
    }
}
