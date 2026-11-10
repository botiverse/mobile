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
import build.raft.app.data.ASSISTANT_OWNER_NOTES_MAX_LENGTH
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.MyAssistantKey
import build.raft.app.data.MyAssistantOwnerNotesSaveOutcome
import build.raft.app.data.MyAssistantRecord
import build.raft.app.data.MyAssistantState
import build.raft.app.data.PERSONAL_ASSISTANT_FEATURE_FLAG_KEY
import build.raft.app.data.RaftAgentsApiClient
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
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldError
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowChevron
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.RaftTextareaCounter
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// The owner's side of their personal assistant's profile (web assistant/AssistantOwnerSection.tsx,
// AssistantInstructionsDialog.tsx, AssistantPaused.tsx `AssistantPausedNotice`): under the
// profile, one 「Assistant」 section with Extra Instructions; under the hero, while a server owner or
// admin has paused it, the notice with Start. Only the owner sees either: "my assistant" is the
// viewer's own, so another agent's profile, or anyone else's view of this one, shows nothing. The
// owner cannot pause it (web parity: pausing is the server owner's or an admin's).

/**
 * What the profile needs to find the viewer's own assistant, provided by the member detail route
 * (MembersPage `MemberDetailPageContent`) for the server it shows. Absent elsewhere: no section.
 */
internal class AssistantOwnerScope(
    val store: SharedMyAssistantStore,
    val featureFlags: SharedServerFeatureFlags,
    val serverMembersStore: SharedServerMembersStore,
    val agentsApiClient: RaftAgentsApiClient?,
    val serverId: String,
    val userId: String,
    val viewerIsGuest: Boolean,
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
 * The owner's 「Assistant」 section of their assistant's profile (web `AssistantOwnerSection`): the
 * Extra Instructions row, which opens the editor. Shown only while the server reports the notes
 * (web: the row needs `settings.ownerNotes !== null`). [frame] draws the profile's section chrome
 * (separator and padding) round the content.
 */
@Composable
internal fun AssistantOwnerSection(
    agentId: String,
    frame: @Composable (content: @Composable ColumnScope.() -> Unit) -> Unit,
) {
    val owner = LocalAssistantOwnerScope.current ?: return
    val assistant = rememberOwnAssistant(agentId) ?: return
    val savedNotes = assistant.ownerNotes ?: return
    var editing by remember(agentId) { mutableStateOf(false) }
    frame {
        AssistantOwnerSectionContent(name = assistant.name, onOpenInstructions = { editing = true })
    }
    if (editing) {
        val key = MyAssistantKey(owner.serverId, owner.userId)
        AssistantInstructionsSheet(
            name = assistant.name,
            savedNotes = savedNotes,
            onSave = { draft -> owner.store.saveOwnerNotes(key, draft) },
            onDismiss = { editing = false },
        )
    }
}

/** The section's eyebrow and its one row (web `assistant-owner-section`). */
@Composable
internal fun AssistantOwnerSectionContent(
    name: String,
    onOpenInstructions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("assistant-owner-section")) {
        // Web SectionEyebrow `mb-3`.
        RaftSectionEyebrow(
            text = stringResource(Res.string.assistant_owner_section_title),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        RaftListRowGroup(Modifier.fillMaxWidth()) {
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
    }
}

/** Why the editor could not save (web AssistantInstructionsDialog `SaveError`). */
internal enum class AssistantInstructionsSaveError { TooLong, Failed }

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
                    MyAssistantOwnerNotesSaveOutcome.Failed
                }
            saving = false
            when (outcome) {
                MyAssistantOwnerNotesSaveOutcome.Saved -> onDismiss()
                MyAssistantOwnerNotesSaveOutcome.TooLong -> saveError = AssistantInstructionsSaveError.TooLong
                MyAssistantOwnerNotesSaveOutcome.Failed -> saveError = AssistantInstructionsSaveError.Failed
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
            RaftBanner(
                description =
                    when (saveError) {
                        AssistantInstructionsSaveError.TooLong -> limitMessage
                        AssistantInstructionsSaveError.Failed -> stringResource(Res.string.assistant_instructions_save_failed)
                    },
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.testTag("assistant-instructions-error"),
            )
        }
    }
}

/** Web AlertDialog `assistant-instructions-discard`: Keep Editing, then the red Discard Changes. */
@Composable
private fun AssistantInstructionsDiscardDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    RaftDialog(
        onDismissRequest = onKeepEditing,
        title = stringResource(Res.string.member_avatar_unsaved_title),
        description = stringResource(Res.string.member_avatar_discard_body),
        dismissOnClickOutside = false,
        showClose = false,
        footer = {
            RaftButton(
                text = stringResource(Res.string.member_avatar_keep_editing),
                onClick = onKeepEditing,
                variant = RaftButtonVariant.Ghost,
                size = RaftButtonSize.Lg,
            )
            RaftButton(
                text = stringResource(Res.string.member_avatar_discard_changes),
                onClick = onDiscard,
                variant = RaftButtonVariant.Danger,
                size = RaftButtonSize.Lg,
            )
        },
    )
}

/**
 * The paused notice for the assistant's owner (web `AssistantPausedNotice` with `isOwner`): who
 * paused it and when, that it won't reply or run its brief, and Start (`POST agents/:id/start`),
 * after which the roster is read again. The pause rides on the roster's agent (`assistantPausedAt`,
 * `assistantPausedBy`); [fallbackPausedAt] is the profile's own value while the roster has none.
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
    var failed by remember(agentId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (pausedAt == null || startedFrom == pausedAt) return
    val client = owner.agentsApiClient
    AssistantPausedBanner(
        line = assistantPausedLine(pausedAt, rostered?.assistantPausedByName),
        starting = starting,
        failed = failed,
        onStart =
            client?.let {
                {
                    if (!starting) {
                        starting = true
                        failed = false
                        scope.launch {
                            val result =
                                try {
                                    client.startAgent(MemberId(agentId.trim()))
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Throwable) {
                                    null
                                }
                            starting = false
                            if (result is DataResult.Content) {
                                startedFrom = pausedAt
                                owner.serverMembersStore.refreshMembers(
                                    serverId,
                                    DataRequestPriority.Foreground,
                                    force = true,
                                    reason = "assistant_start",
                                )
                            } else {
                                failed = true
                            }
                        }
                    }
                }
            },
        modifier = modifier,
    )
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
 */
@Composable
internal fun AssistantPausedBanner(
    line: String,
    starting: Boolean,
    failed: Boolean,
    onStart: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val body = stringResource(Res.string.assistant_paused_owner_body)
    val failure = stringResource(Res.string.assistant_paused_start_failed)
    RaftBanner(
        description = if (failed) "$body\n$failure" else body,
        status = RaftBannerStatus.Info,
        title = line,
        modifier = modifier.fillMaxWidth().testTag("assistant-paused-notice"),
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
}
