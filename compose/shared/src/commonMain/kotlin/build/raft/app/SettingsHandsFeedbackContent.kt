package build.raft.app

import build.raft.app.base.Utils
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import build.raft.app.components.raftCardSurface
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.DataResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.SettingsHandsFeedbackSubmission
import build.raft.app.data.SettingsHandsFeedbackDraftSnapshot
import build.raft.app.data.SettingsHandsFeedbackDraftStore
import build.raft.app.format.RaftFormat
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.desc.desc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun SharedHandsFeedbackContent(
    pager: Pager,
    eventBus: RaftEventBus,
    settingsCapabilities: BridgeSettingsPlatformCapabilities,
    sourceStoreScope: RaftShellStoreScope,
    accountId: String,
    visualFixture: SettingsHandsFeedbackVisualFixture? = null,
    bottomContentPadding: Dp = RaftAppSpacing.Lg
) {
    val attachmentMaxBytes = raftFeedbackAttachmentMaxBytes()
    val attachmentMaxLabel = RaftFormat.byteLimit(attachmentMaxBytes)
    val copy = settingsHandsFeedbackCopy(attachmentMaxLabel)
    val bridge = remember(pager.pagerId) { Utils.bridgeModule(pager.pagerId) }
    val scope = rememberCoroutineScope()
    // Keep editor callbacks on the Kuikly dispatcher while detaching their Job from composition
    // cancellation. Disposal closes admission, waits for these jobs, then sweeps ownership.
    val imageEditEventScope = remember(eventBus, accountId, pager.pagerId) {
        CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())
    }
    // The process send scope is used only for the NonCancellable disposal handoff.
    val stableCleanupScope =
        RaftSharedStoreRegistry.currentOrNull()?.threadSendScope ?: imageEditEventScope
    val listState = rememberLazyListState()
    val chatLayoutPreferenceStore =
        remember {
            KeyValueChatLayoutPreferenceStore(raftSharedPreferenceStore())
        }
    val feedbackDraftStore =
        remember {
            SettingsHandsFeedbackDraftStore(raftSharedPreferenceStore())
        }
    val feedbackPageGeneration =
        remember(accountId) {
            newSettingsHandsFeedbackImageEditId()
        }
    val restoredDraft =
        remember(feedbackDraftStore, accountId, visualFixture) {
            visualFixture?.draft ?: feedbackDraftStore.read(accountId)
        }
    val lastPersistedDraftState =
        remember(feedbackDraftStore, accountId, visualFixture) {
            mutableStateOf(restoredDraft)
        }
    var lastPersistedDraft by lastPersistedDraftState
    val composerSendKeyEnabled = chatLayoutPreferenceStore.readComposerSendKeyEnabledSync()
    var messageFieldValue by remember(accountId) {
        mutableStateOf(
            TextFieldValue(
                text = restoredDraft.text,
                selection = TextRange(restoredDraft.text.length)
            )
        )
    }
    val draftTextForPersistenceState =
        remember(accountId) { mutableStateOf(restoredDraft.text) }
    var draftTextForPersistence by draftTextForPersistenceState
    val message = messageFieldValue.text
    val attachmentsState =
        remember(accountId) { mutableStateOf(restoredDraft.attachments) }
    var attachments by attachmentsState
    var pendingImageEditSession by remember(accountId, feedbackPageGeneration) {
        mutableStateOf<SettingsHandsFeedbackImageEditSession?>(null)
    }
    val imageEditCommitGate =
        remember(accountId, feedbackPageGeneration) {
            SettingsHandsFeedbackImageEditCommitGate()
        }
    val imageEditSessionTracker = remember(eventBus, accountId, feedbackPageGeneration) {
        SettingsHandsFeedbackImageEditSessionTracker()
    }
    val imageEditEventJobs = remember(eventBus, accountId, feedbackPageGeneration) {
        SettingsHandsFeedbackImageEditEventJobTracker()
    }
    val latestPendingImageEditSession by rememberUpdatedState(pendingImageEditSession)
    val latestFeedbackAccountId by rememberUpdatedState(accountId)
    val latestFeedbackPageGeneration by rememberUpdatedState(feedbackPageGeneration)
    val feedbackDraftResetGenerationState =
        remember(accountId) { mutableStateOf(0L) }
    var feedbackDraftResetGeneration by feedbackDraftResetGenerationState
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingRetryRequest by remember { mutableStateOf<SettingsHandsFeedbackRetryRequest?>(null) }
    var nextEntryId by remember { mutableStateOf(1) }
    var visualAutoSubmitted by remember(accountId, visualFixture) { mutableStateOf(false) }
    var entries by remember {
        mutableStateOf(
            listOf(
                SettingsHandsFeedbackEntry(
                    id = 0,
                    kind = SettingsHandsFeedbackEntryKind.Hands,
                    title = copy.title,
                    body = copy.introduction
                )
            )
        )
    }
    var previousEntryIds by remember(accountId) {
        mutableStateOf(entries.map(SettingsHandsFeedbackEntry::id))
    }
    var allowBottomAnchor by remember(accountId) { mutableStateOf(true) }
    val feedbackEntryIds = remember(entries) { entries.map(SettingsHandsFeedbackEntry::id) }
    // Native Hands SDK caps feedback submissions at maxAttachments=3 (one slot is
    // reserved for the attached log bundle); keep UI copy and merge in lockstep.
    val attachmentLimit = 3
    val remainingSlots = attachmentLimit - attachments.size
    val normalizedMessage = message.trim()

    fun abortOwnedImageEditSessions(sessionIds: Collection<String>) {
        abortSettingsHandsFeedbackImageEditSessions(
            sessionIds = sessionIds,
            retire = { sessionId, fileRefs ->
                bridge.retireFilePreviewTemporaryFiles(
                    sessionId = sessionId,
                    fileRefs = fileRefs.toList()
                )
            }
        )
    }

    fun abortAllOwnedImageEditSessions() {
        // Revoke partial witnesses atomically before touching the process registry.  Otherwise an
        // event which already passed `owns()` could mark its session in-flight while this function
        // was retiring the same temporary refs, deleting a file that the uploader is about to read.
        val partialSessionIds = imageEditSessionTracker.takePartialSessionIds()
        val pendingSessionId = pendingImageEditSession?.previewSessionId.orEmpty()
        val sessionIds =
            partialSessionIds +
                pendingSessionId.takeIf {
                    it.isNotBlank() && !imageEditSessionTracker.isHandoffStarted(it)
                }.orEmpty()
        if (sessionIds.isEmpty()) return
        abortOwnedImageEditSessions(sessionIds)
        sessionIds.forEach(imageEditSessionTracker::release)
    }

    LaunchedEffect(feedbackDraftStore, accountId, draftTextForPersistence, attachments) {
        delay(250L)
        val currentDraft =
            SettingsHandsFeedbackDraftSnapshot(
                text = draftTextForPersistence,
                attachments = attachments
            )
        if (currentDraft != lastPersistedDraft) {
            feedbackDraftStore.save(accountId, currentDraft.text, currentDraft.attachments)
            lastPersistedDraft = currentDraft
        }
    }

    DisposableEffect(feedbackDraftStore, accountId) {
        onDispose {
            val currentDraft =
                SettingsHandsFeedbackDraftSnapshot(
                    text = draftTextForPersistenceState.value,
                    attachments = attachmentsState.value
                )
            if (currentDraft != lastPersistedDraftState.value) {
                feedbackDraftStore.save(
                    accountId = accountId,
                    text = currentDraft.text,
                    attachments = currentDraft.attachments
                )
            }
        }
    }

    val feedbackLastListIndex = settingsFeedbackLastListIndex(entries.size, busy)
    LaunchedEffect(accountId, listState, feedbackLastListIndex) {
        snapshotFlow {
            SettingsFeedbackBottomAnchorAvailabilityState(
                currentAllow = allowBottomAnchor,
                isScrollInProgress = listState.isScrollInProgress,
                isAtBottom = listState.isSettingsFeedbackAtBottom(feedbackLastListIndex)
            )
        }
            .distinctUntilChanged()
            .collect { state ->
                val nextAllow = resolveSettingsFeedbackBottomAnchorAllowed(state)
                if (allowBottomAnchor != nextAllow) {
                    allowBottomAnchor = nextAllow
                }
            }
    }

    LaunchedEffect(feedbackEntryIds, busy) {
        val update =
            settingsFeedbackBottomUpdatePresentation(
                previousEntryIds = previousEntryIds,
                currentEntryIds = feedbackEntryIds,
                busy = busy,
                allowBottomAnchor = allowBottomAnchor,
                isScrollInProgress = listState.isScrollInProgress
            )
        previousEntryIds = feedbackEntryIds
        // Busy-only transitions (including attachment picking) are not a
        // conversation append and therefore never acquire the viewport.
        update.targetIndex?.let { targetIndex ->
            listState.scrollToItem(targetIndex)
        }
    }

    fun nextConversationEntryId(): Int {
        val id = nextEntryId
        nextEntryId += 1
        return id
    }

    fun pickAttachment(imageOnly: Boolean) {
        if (busy) return
        if (remainingSlots <= 0) {
            error = copy.attachmentLimitReached(attachmentLimit)
            return
        }
        busy = true
        error = null
        // A replacement picker supersedes any promotion already in flight. Keep its native
        // handoff alive until its finally cleanup, but make its draft commit stale immediately.
        feedbackDraftResetGeneration += 1L
        abortAllOwnedImageEditSessions()
        pendingImageEditSession = null
        scope.launch {
            when (val result = settingsCapabilities.pickFeedbackAttachment(imageOnly)) {
                is DataResult.Content -> {
                    val pickedFiles =
                        normalizeSettingsHandsFeedbackPickerFiles(
                            files = result.data,
                            imageOnly = imageOnly
                        )
                    // Feedback attachments mount straight into the form, identical to the
                    // file path: no chat-style annotation detour for images.
                    val merge =
                        settingsHandsFeedbackMergedAttachments(
                            existing = attachmentsState.value,
                            picked = pickedFiles,
                            attachmentMaxBytes = attachmentMaxBytes,
                            attachmentLimit = attachmentLimit
                        )
                    attachments = merge.attachments
                    error = when {
                        merge.rejectedCount > 0 -> copy.attachmentTooLarge(attachmentMaxLabel)
                        merge.limitTrimmed -> copy.attachmentLimitTrimmed(attachmentLimit)
                        else -> null
                    }
                }
                is DataResult.Empty -> Unit
                is DataResult.Error -> error = copy.attachmentPickerFailed
                is DataResult.NoPermission -> error = copy.attachmentPickerPermissionNeeded
            }
            busy = false
        }
    }

    fun previewAttachment(attachmentId: String) {
        val file =
            attachmentsState.value
                .firstOrNull { it.feedbackComposerAttachmentId() == attachmentId }
                ?: return
        // task #286: image previews reopen the shared editor so tapping a mounted image can
        // edit it in place; non-image previews stay read-only.
        val editRequest =
            if (
                raftFilePreviewIsImageAttachment(
                    file.fileName.ifBlank { copy.attachment },
                    file.mimeType.orEmpty()
                )
            ) {
                settingsHandsFeedbackImageEditPreviewRequest(
                    accountId = accountId,
                    pageGeneration = feedbackPageGeneration,
                    file = file,
                    sourceStoreScope = sourceStoreScope
                )
            } else {
                null
            }
        if (editRequest == null) {
            file.toSettingsHandsFeedbackAttachmentPreviewPayload(copy.attachment)
                ?.let { payload ->
                    pager.openRaftFilePreviewPage(payload, sourceStoreScope = sourceStoreScope)
                }
            return
        }
        // A new edit preview supersedes any session still pending; retire it first so its
        // admission cannot consume events meant for the replacement.
        abortAllOwnedImageEditSessions()
        pendingImageEditSession = null
        var previewSessionId = ""
        if (
            !pager.openRaftFilePreviewPage(
                editRequest.previewPayload,
                sourceStoreScope = editRequest.sourceStoreScope,
                onEphemeralSessionRegistered = { registeredId ->
                    previewSessionId = registeredId
                    imageEditSessionTracker.track(registeredId).also { tracked ->
                        if (tracked) {
                            pendingImageEditSession =
                                editRequest.session.copy(previewSessionId = registeredId)
                        }
                    }
                }
            ) || previewSessionId.isBlank()
        ) {
            imageEditSessionTracker.release(previewSessionId)
            pendingImageEditSession = null
            error = copy.attachmentPickerFailed
            return
        }
        if (
            !imageEditSessionTracker.owns(previewSessionId) &&
                !imageEditSessionTracker.isHandoffStarted(previewSessionId)
        ) {
            abortOwnedImageEditSessions(listOf(previewSessionId))
            pendingImageEditSession = null
            error = copy.attachmentPickerFailed
        }
    }

    DisposableEffect(eventBus, accountId, feedbackPageGeneration) {
        // Native editor callbacks can arrive on separate event-bus turns. Serialize the fold and
        // terminal registry transition so two members of one N/N group cannot both read the same
        // stale Compose session and overwrite each other's index.
        val imageEditEventMutex = Mutex()
        val subscription =
            eventBus.observe(RAFT_COMPOSER_IMAGE_EDIT_EVENT, crossProcess = true) { data ->
                val eventDataSnapshot = data?.toString() ?: return@observe
                imageEditEventJobs.launch(imageEditEventScope) {
                    imageEditEventMutex.withLock {
                        val eventData =
                            runCatching { JSONObject(eventDataSnapshot) }.getOrNull()
                                ?: return@withLock
                    val session = latestPendingImageEditSession
                    val eventSessionId = eventData.optString("previewSessionId").trim()
                    val eventThreadKey = eventData.optString("threadKey").trim()
                    if (!settingsHandsFeedbackImageEditEventMatchesSession(
                            session = session,
                            tracker = imageEditSessionTracker,
                            eventSessionId = eventSessionId,
                            eventThreadKey = eventThreadKey
                        )
                    ) {
                        return@launch
                    }
                    val event = parseSettingsHandsFeedbackImageEditEvent(eventData)
                    if (event == null) {
                        val cleanupRefs =
                            raftFilePreviewAbortComposerImageEventSession(eventSessionId)
                        if (cleanupRefs.isNotEmpty()) {
                            bridge.retireFilePreviewTemporaryFiles(
                                sessionId = eventSessionId,
                                fileRefs = cleanupRefs.toList()
                            )
                        }
                        imageEditSessionTracker.release(eventSessionId)
                        if (session != null) {
                            pendingImageEditSession = null
                            error = copy.attachmentPickerFailed
                        }
                        return@launch
                    }
                    val admission =
                        raftFilePreviewClaimComposerImageEvent(
                            eventData = eventData,
                            expectedThreadKey = session?.threadKey ?: event.threadKey
                        )
                    if (admission is RaftFilePreviewComposerImageEventAdmission.Rejected) {
                        if (admission.orphanedTemporaryFileRefs.isNotEmpty()) {
                            bridge.retireFilePreviewTemporaryFiles(
                                sessionId = admission.sessionId,
                                fileRefs = admission.orphanedTemporaryFileRefs.toList()
                            )
                        }
                        if (settingsHandsFeedbackRejectedAdmissionIsTerminal(admission)) {
                            imageEditSessionTracker.release(admission.sessionId)
                        }
                        return@launch
                    }
                    admission as RaftFilePreviewComposerImageEventAdmission.Accepted
                    fun eventSessionStillOwned(sessionId: String): Boolean {
                        val normalized = sessionId.trim()
                        return normalized.isNotBlank() &&
                            imageEditSessionTracker.owns(normalized) &&
                            latestPendingImageEditSession?.previewSessionId?.trim() == normalized
                    }
                    fun finishEventSession(transferred: Boolean) {
                        try {
                            val cleanupRefs =
                                raftFilePreviewFinishComposerImageEventSession(
                                    sessionId = admission.sessionId,
                                    transferred = transferred
                                )
                            if (cleanupRefs.isNotEmpty()) {
                                bridge.retireFilePreviewTemporaryFiles(
                                    sessionId = admission.sessionId,
                                    fileRefs = cleanupRefs.toList()
                                )
                            }
                        } finally {
                            // A bridge/retirement failure must not leave the page-local witness
                            // alive forever; the persistent cleanup ledger remains the fallback.
                            imageEditSessionTracker.release(admission.sessionId)
                        }
                    }
                    if (session == null) {
                        finishEventSession(transferred = false)
                        return@launch
                    }
                    // Replacement/disposal can revoke the page witness while this callback is
                    // waiting behind another event. Do not fold or mutate Compose state after the
                    // session has lost ownership, even if the process registry accepted the row.
                    if (!eventSessionStillOwned(admission.sessionId)) {
                        finishEventSession(transferred = false)
                        return@launch
                    }
                    when (
                        val result =
                            foldSettingsHandsFeedbackImageEditEvent(
                                session = session,
                                event = event,
                                currentAccountId = latestFeedbackAccountId,
                                currentPageGeneration = latestFeedbackPageGeneration
                            )
                    ) {
                        is SettingsHandsFeedbackImageEditFoldResult.Ignored -> {
                            if (eventSessionStillOwned(admission.sessionId)) {
                                pendingImageEditSession = null
                            }
                            finishEventSession(transferred = false)
                        }
                        is SettingsHandsFeedbackImageEditFoldResult.Pending -> {
                            if (admission.sessionComplete) {
                                val stillOwned = eventSessionStillOwned(admission.sessionId)
                                if (stillOwned) {
                                    pendingImageEditSession = null
                                }
                                finishEventSession(transferred = false)
                                if (stillOwned) {
                                    error = copy.attachmentPickerFailed
                                }
                            } else {
                                if (eventSessionStillOwned(admission.sessionId)) {
                                    pendingImageEditSession = result.session
                                } else {
                                    finishEventSession(transferred = false)
                                }
                            }
                        }
                        is SettingsHandsFeedbackImageEditFoldResult.Failed -> {
                            val stillOwned = eventSessionStillOwned(admission.sessionId)
                            if (stillOwned) {
                                pendingImageEditSession = null
                            }
                            finishEventSession(transferred = false)
                            if (stillOwned) {
                                error = copy.attachmentPickerFailed
                            }
                        }
                        is SettingsHandsFeedbackImageEditFoldResult.Complete -> {
                            if (!admission.sessionComplete) {
                                val stillOwned = eventSessionStillOwned(admission.sessionId)
                                if (stillOwned) {
                                    pendingImageEditSession = null
                                }
                                finishEventSession(transferred = false)
                                if (stillOwned) {
                                    error = copy.attachmentPickerFailed
                                }
                                return@launch
                            }
                            if (!imageEditCommitGate.tryClaim(session)) {
                                finishEventSession(transferred = false)
                                return@launch
                            }
                            if (!imageEditSessionTracker.markHandoffStarted(admission.sessionId)) {
                                imageEditCommitGate.release(session)
                                finishEventSession(transferred = false)
                                return@launch
                            }
                            if (pendingImageEditSession?.previewSessionId?.trim() == admission.sessionId.trim()) {
                                pendingImageEditSession = null
                            }
                            val initialDraftResetGeneration = feedbackDraftResetGenerationState.value
                            var transferred = false
                            try {
                                when (
                                    val commit =
                                        commitSettingsHandsFeedbackEditedGroup(
                                            existing = attachmentsState.value,
                                            edited = result.files,
                                            attachmentMaxBytes = attachmentMaxBytes,
                                            attachmentLimit = attachmentLimit,
                                            replaceAttachmentId = result.replaceAttachmentId,
                                            isCurrent = {
                                                !imageEditSessionTracker.isDisposed() &&
                                                    imageEditSessionTracker.owns(session.previewSessionId) &&
                                                    imageEditCommitGate.isCurrent(
                                                    session = session,
                                                    currentAccountId = latestFeedbackAccountId,
                                                    currentPageGeneration = latestFeedbackPageGeneration
                                                    )
                                            },
                                            currentExisting = { attachmentsState.value },
                                            initialDraftResetGeneration = initialDraftResetGeneration,
                                            currentDraftResetGeneration = {
                                                feedbackDraftResetGenerationState.value
                                            },
                                            promote = { selected ->
                                                settingsCapabilities.promoteFeedbackDraftAttachments(
                                                    accountId = accountId,
                                                    groupId = session.groupId,
                                                    files = selected,
                                                    maxBytes = attachmentMaxBytes
                                                )
                                            },
                                            persist = { promotedAttachments ->
                                                check(
                                                    imageEditSessionTracker.runIfOwned(session.previewSessionId) {
                                                        check(
                                                            feedbackDraftResetGenerationState.value ==
                                                                initialDraftResetGeneration &&
                                                                imageEditCommitGate.isCurrent(
                                                                    session = session,
                                                                    currentAccountId = latestFeedbackAccountId,
                                                                    currentPageGeneration = latestFeedbackPageGeneration
                                                                )
                                                        ) {
                                                            "settings feedback image edit session changed before persistence"
                                                        }
                                                        val currentDraftText = draftTextForPersistenceState.value
                                                        feedbackDraftStore.save(
                                                            accountId = accountId,
                                                            text = currentDraftText,
                                                            attachments = promotedAttachments
                                                        )
                                                        lastPersistedDraft =
                                                            SettingsHandsFeedbackDraftSnapshot(
                                                                text = currentDraftText,
                                                                attachments = promotedAttachments
                                                            )
                                                    }
                                                ) {
                                                    "settings feedback image edit session disposed before persistence"
                                                }
                                            },
                                            discard = { promotedAttachments ->
                                                settingsCapabilities.discardFeedbackDraftAttachments(
                                                    accountId = accountId,
                                                    files = promotedAttachments
                                                )
                                            }
                                        )
                                ) {
                                    is SettingsHandsFeedbackEditedGroupCommitResult.Committed -> {
                                        val promotion = commit.promotion
                                        val published = imageEditSessionTracker.runIfOwned(
                                            session.previewSessionId
                                        ) {
                                            attachments = promotion.attachments
                                            transferred = true
                                            error =
                                                if (promotion.trimmedByLimit) {
                                                    copy.attachmentLimitTrimmed(attachmentLimit)
                                                } else {
                                                    null
                                                }
                                        }
                                        if (!published) {
                                            error = null
                                        }
                                    }
                                    is SettingsHandsFeedbackEditedGroupCommitResult.Failed -> {
                                        if ("session_stale" !in commit.code) {
                                            error =
                                                if ("too_large" in commit.code) {
                                                    copy.attachmentTooLarge(attachmentMaxLabel)
                                                } else if ("limit_reached" in commit.code) {
                                                    copy.attachmentLimitReached(attachmentLimit)
                                                } else {
                                                    copy.attachmentPickerFailed
                                                }
                                        }
                                    }
                                }
                            } finally {
                                finishEventSession(transferred = transferred)
                                imageEditCommitGate.release(session)
                            }
                        }
                    }
                    }
                }
            }
        onDispose {
            subscription.close()
            val eventJobs = imageEditEventJobs.closeAndSnapshot()
            val closeResult = imageEditSessionTracker.closeAndSnapshot()
            val pendingSessionId = latestPendingImageEditSession?.previewSessionId.orEmpty()
            val immediateSessionIds =
                closeResult.abortSessionIds +
                    pendingSessionId.takeIf { it.isNotBlank() && it !in closeResult.inFlightSessionIds }.orEmpty()
            abortOwnedImageEditSessions(immediateSessionIds)
            imageEditCommitGate.clear()
            // Stop admitting callbacks before joining jobs which may still hold a claimed
            // session. The commit body has its own NonCancellable finally path; partial work is
            // swept below after the jobs have stopped.
            eventJobs.forEach { job -> job.cancel() }
            val deferredSessionIds = closeResult.allSessionIds
            if (deferredSessionIds.isNotEmpty() || eventJobs.isNotEmpty()) {
                stableCleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    withContext(NonCancellable) {
                        eventJobs.forEach { job ->
                            try {
                                job.cancelAndJoin()
                            } catch (_: CancellationException) {
                                // The surrounding NonCancellable handoff remains authoritative.
                            } catch (_: Throwable) {
                                // Always perform the final registry/ledger sweep below.
                            }
                        }
                        abortOwnedImageEditSessions(
                            deferredSessionIds + pendingSessionId.takeIf(String::isNotBlank).orEmpty()
                        )
                        imageEditEventScope.cancel()
                    }
                }
            } else {
                imageEditEventScope.cancel()
            }
        }
    }

    fun submitFeedback(explicitRetryRequest: SettingsHandsFeedbackRetryRequest? = null) {
        val currentAttachments = attachments.take(attachmentLimit)
        val retryRequest =
            explicitRetryRequest
                ?: pendingRetryRequest?.takeIf { request ->
                    request.matches(normalizedMessage, currentAttachments)
                }
        val submittedMessage = retryRequest?.message ?: normalizedMessage
        val submittedAttachments = retryRequest?.attachments ?: currentAttachments
        settingsCapabilities.log(
            "settings_hands_feedback_submit_click busy=$busy " +
                "messageLength=${submittedMessage.length} retry=${retryRequest != null} " +
                "attachmentCount=${submittedAttachments.size}"
        )
        if (busy) {
            settingsCapabilities.log("settings_hands_feedback_submit_ignored reason=busy")
            return
        }
        if (submittedMessage.isBlank()) {
            settingsCapabilities.log("settings_hands_feedback_submit_blocked reason=blank_message")
            error = copy.messageRequired
            return
        }
        feedbackDraftResetGeneration += 1L
        abortAllOwnedImageEditSessions()
        pendingImageEditSession = null
        settingsCapabilities.log("settings_hands_feedback_submit_enter")
        busy = true
        error = null
        pendingRetryRequest = null
        entries = entries.filterNot { it.retryRequest != null }
        attachments = submittedAttachments
        messageFieldValue = TextFieldValue("")
        if (retryRequest == null) {
            entries =
                entries +
                    SettingsHandsFeedbackEntry(
                        id = nextConversationEntryId(),
                        kind = SettingsHandsFeedbackEntryKind.User,
                        title = copy.you,
                        body = submittedMessage,
                        detail =
                            submittedAttachments
                                .takeIf { it.isNotEmpty() }
                                ?.joinToString { it.fileName.ifBlank { copy.attachment } }
                    )
        }
        val submittedRequest = SettingsHandsFeedbackRetryRequest(submittedMessage, submittedAttachments)

        fun appendFailure(failure: SettingsHandsFeedbackFailurePresentation) {
            val nextRetryRequest = submittedRequest.takeIf { failure.retryable }
            pendingRetryRequest = nextRetryRequest
            messageFieldValue = TextFieldValue(submittedMessage, selection = TextRange(submittedMessage.length))
            draftTextForPersistence = submittedMessage
            attachments = submittedAttachments
            error = failure.composerStatus
            entries =
                entries +
                    SettingsHandsFeedbackEntry(
                        id = nextConversationEntryId(),
                        kind = SettingsHandsFeedbackEntryKind.Error,
                        title = failure.title,
                        body = failure.body,
                        retryRequest = nextRetryRequest
                    )
        }

        scope.launch {
            try {
                when (val result = settingsCapabilities.submitHandsFeedback(submittedMessage, submittedAttachments)) {
                    is DataResult.Content -> {
                        settingsCapabilities.log(
                            "settings_hands_feedback_submit_result status=ok ticket=${result.data.ticketId.take(8)}"
                        )
                        pendingRetryRequest = null
                        entries =
                            entries +
                                result.data.toHandsFeedbackEntry(
                                    id = nextConversationEntryId(),
                                    copy = copy
                                )
                        messageFieldValue = TextFieldValue("")
                        draftTextForPersistence = ""
                        val emptyDraft = SettingsHandsFeedbackDraftSnapshot()
                        val draftReleased =
                            try {
                                feedbackDraftStore.clear(accountId)
                                lastPersistedDraft = emptyDraft
                                true
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (throwable: Throwable) {
                                settingsCapabilities.log(
                                    "settings_hands_feedback_draft_clear_failed " +
                                        "error=${throwable::class.simpleName}"
                                )
                                false
                            }
                        attachments = emptyList()
                        if (draftReleased) {
                            try {
                                val discarded =
                                    settingsCapabilities.discardFeedbackDraftAttachments(
                                        accountId = accountId,
                                        files = submittedAttachments
                                    )
                                if (!discarded) {
                                    settingsCapabilities.log(
                                        "settings_hands_feedback_attachment_cleanup_failed reason=host_rejected"
                                    )
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (throwable: Throwable) {
                                settingsCapabilities.log(
                                    "settings_hands_feedback_attachment_cleanup_failed " +
                                        "error=${throwable::class.simpleName}"
                                )
                            }
                        }
                    }
                    is DataResult.Empty -> {
                        settingsCapabilities.log("settings_hands_feedback_submit_result status=empty")
                        appendFailure(copy.submissionUnavailableFailure())
                    }
                    is DataResult.Error -> {
                        settingsCapabilities.log(
                            "settings_hands_feedback_submit_result status=error code=${result.error.code}"
                        )
                        appendFailure(copy.submissionFailure(result.error.code, result.error.retryable))
                    }
                    is DataResult.NoPermission -> {
                        settingsCapabilities.log(
                            "settings_hands_feedback_submit_result status=no_permission code=${result.blocker.code}"
                        )
                        appendFailure(copy.submissionPermissionFailure())
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                settingsCapabilities.log(
                        "settings_hands_feedback_submit_exception error=${throwable::class.simpleName} " +
                        "message=${throwable.message.orEmpty()}"
                )
                appendFailure(copy.submissionFailure())
            } finally {
                settingsCapabilities.log("settings_hands_feedback_submit_finished")
                busy = false
            }
        }
    }

    LaunchedEffect(visualFixture?.autoSubmit, accountId) {
        if (visualFixture?.autoSubmit == true && !visualAutoSubmitted) {
            visualAutoSubmitted = true
            settingsCapabilities.log(
                "settings_hands_feedback_visual_initial attachmentCount=${attachments.size} " +
                    "messageLength=${message.length}"
            )
            // Let the real composer render both attachment rows before the
            // immediate fake-success transition removes them and inserts the
            // yellow User row. This is the retained-view reuse window from
            // task #139, not a preview-only approximation.
            delay(750L)
            submitFeedback()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().dismissInputFocusOnScrollableChrome(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            entries.forEach { entry ->
                item(key = entry.id) {
                    SettingsHandsFeedbackConversationRow(
                        entry = entry,
                        copy = copy,
                        onCopy = { shareText ->
                            settingsCapabilities.bridgeCopyToClipboard(shareText)
                        },
                        onRetry = { request -> submitFeedback(request) }
                    )
                }
            }
            if (busy) {
                item {
                    SettingsHandsFeedbackConversationRow(
                        entry =
                            SettingsHandsFeedbackEntry(
                                id = -1,
                                kind = SettingsHandsFeedbackEntryKind.Hands,
                                title = copy.title,
                                body = copy.submitting
                            ),
                        copy = copy,
                        onCopy = {},
                        onRetry = {}
                    )
                }
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RaftColor.SurfaceWhite)
        ) {
            ThreadComposerContentLeaf(
                attachmentPreviewEnabled = true,
                surface =
                    ThreadComposerSurface(
                        kind = RaftThreadSurfaceKind.Channel,
                        channelId = "settings-feedback",
                        showTaskButton = false
                    ),
                state =
                    ThreadComposerUiState(
                        instanceId = "settings-hands-feedback-composer-$accountId",
                        seedText = message,
                        placeholder = copy.placeholder,
                        enabled = !busy,
                        attachmentActionsEnabled = remainingSlots > 0,
                        // Settings feedback accepts images and files as evidence, but it has no
                        // thread-composer image policy. Do not expose a checkbox whose event this
                        // surface cannot own or persist.
                        originalImageOptionEnabled = false,
                        composerMode = if (busy) "sending" else if (!error.isNullOrBlank()) "failed" else "idle",
                        statusLabel = error?.takeIf { it.isNotBlank() },
                        suggestionsEnabled = false,
                        pendingAttachments = attachments.toFeedbackComposerAttachmentItems(copy)
                    ),
                editorSessionId = 1L,
                editorValue = messageFieldValue,
                onEditorValueChange = { _, next ->
                    messageFieldValue = next
                    true
                },
                onNativeEditorValueChange = { _, incoming ->
                    repairThreadComposerLegacyZeroSelection(
                        previous = messageFieldValue,
                        incoming = incoming,
                    ).also { accepted ->
                        messageFieldValue = accepted
                    }
                },
                isEditorSessionActive = { true },
                editorAsTask = false,
                onEditorAsTaskChange = { _, _ -> true },
                composerSendKeyEnabled = composerSendKeyEnabled,
                dismissKeyboardOnBlur = true,
                onEvent = { action, value, _, attachmentId ->
                    when (action) {
                        "change" -> {
                            draftTextForPersistence = value.orEmpty()
                            error = null
                        }
                        "send" -> {
                            settingsCapabilities.log("settings_hands_feedback_composer_send")
                            submitFeedback()
                        }
                        "attachImage" -> pickAttachment(imageOnly = true)
                        "attachFile" -> pickAttachment(imageOnly = false)
                        "removeAttachment" ->
                            attachmentId?.let { id ->
                                val removed =
                                    attachments.filter { file ->
                                        file.feedbackComposerAttachmentId() == id
                                    }
                                if (removed.isNotEmpty()) {
                                    // Removing any attachment supersedes an editor promotion that
                                    // may be finishing concurrently. Its native handoff remains
                                    // alive for terminal cleanup, but its draft generation is no
                                    // longer allowed to publish into this edited list.
                                    feedbackDraftResetGeneration += 1L
                                    val remaining = attachments - removed.toSet()
                                    val nextDraft =
                                        SettingsHandsFeedbackDraftSnapshot(
                                            text = draftTextForPersistence,
                                            attachments = remaining
                                        )
                                    try {
                                        feedbackDraftStore.save(
                                            accountId = accountId,
                                            text = nextDraft.text,
                                            attachments = nextDraft.attachments
                                        )
                                        lastPersistedDraft = nextDraft
                                        attachments = remaining
                                        if (remaining.isEmpty()) {
                                            abortAllOwnedImageEditSessions()
                                            pendingImageEditSession = null
                                        }
                                        scope.launch {
                                            try {
                                                settingsCapabilities.discardFeedbackDraftAttachments(
                                                    accountId = accountId,
                                                    files = removed
                                                )
                                            } catch (cancellation: CancellationException) {
                                                throw cancellation
                                            } catch (_: Throwable) {
                                            }
                                        }
                                    } catch (throwable: Throwable) {
                                        settingsCapabilities.log(
                                            "settings_hands_feedback_attachment_remove_failed " +
                                                "error=${throwable::class.simpleName}"
                                        )
                                        error = copy.attachmentPickerFailed
                                    }
                                }
                            }
                        "previewAttachment" ->
                            attachmentId?.takeIf { it.isNotBlank() }?.let(::previewAttachment)
                        "retryAttachments", "blur", "toggleAsTask", "toggleOriginalImage" -> Unit
                    }
                },
                contentPaddingTop = 10.dp,
                contentPaddingBottom = 8.dp,
                hostPaddingHorizontal = RaftAppSpacing.Sm,
                hostPaddingTop = 12.dp,
                keyboardLiftBottomContentPadding = bottomContentPadding
            )
        }
    }
}

internal data class SettingsFeedbackBottomAnchorAvailabilityState(
    val currentAllow: Boolean,
    val isScrollInProgress: Boolean,
    val isAtBottom: Boolean
)

internal fun resolveSettingsFeedbackBottomAnchorAllowed(
    state: SettingsFeedbackBottomAnchorAvailabilityState
): Boolean =
    when {
        state.isScrollInProgress -> false
        state.isAtBottom -> true
        else -> state.currentAllow
    }

internal data class SettingsFeedbackBottomUpdatePresentation(
    val targetIndex: Int?
)

internal fun settingsFeedbackBottomUpdatePresentation(
    previousEntryIds: List<Int>,
    currentEntryIds: List<Int>,
    busy: Boolean,
    allowBottomAnchor: Boolean,
    isScrollInProgress: Boolean
): SettingsFeedbackBottomUpdatePresentation {
    val appendedConversationEntry =
        currentEntryIds.lastOrNull()?.let { latestEntryId ->
            latestEntryId !in previousEntryIds
        } == true
    val targetIndex =
        if (appendedConversationEntry && allowBottomAnchor && !isScrollInProgress) {
            settingsFeedbackLastListIndex(currentEntryIds.size, busy)
        } else {
            null
        }
    return SettingsFeedbackBottomUpdatePresentation(targetIndex = targetIndex)
}

internal fun settingsFeedbackLastListIndex(entryCount: Int, busy: Boolean): Int =
    when {
        entryCount <= 0 && !busy -> -1
        busy -> entryCount
        else -> entryCount - 1
    }

private fun LazyListState.isSettingsFeedbackAtBottom(lastIndex: Int): Boolean {
    if (lastIndex <= 0) return true
    if (!canScrollForward) return true
    return layoutInfo.visibleItemsInfo.any { item ->
        item.index == lastIndex && item.offset + item.size <= layoutInfo.viewportEndOffset + 4
    }
}

private enum class SettingsHandsFeedbackEntryKind {
    User,
    Hands,
    Error
}

private data class SettingsHandsFeedbackEntry(
    val id: Int,
    val kind: SettingsHandsFeedbackEntryKind,
    val title: String,
    val body: String,
    val detail: String? = null,
    val ticketId: String? = null,
    val shareText: String? = null,
    val retryRequest: SettingsHandsFeedbackRetryRequest? = null
)

internal data class SettingsHandsFeedbackVisualFixture(
    val draft: SettingsHandsFeedbackDraftSnapshot,
    val autoSubmit: Boolean
)

internal data class SettingsHandsFeedbackCopy(
    val title: String = "Send feedback",
    val introduction: String = "Share a problem or suggestion. Each submission creates a feedback message and copies a shareable reference.",
    val you: String = "You",
    val attachment: String = "Attachment",
    val attachmentLimitReached: (Int) -> String = { limit -> "You can upload up to $limit attachments at a time." },
    val attachmentTooLarge: (String) -> String = { maxSize -> "Attachments larger than $maxSize were skipped." },
    val attachmentLimitTrimmed: (Int) -> String = { limit -> "Only the first $limit attachments were kept." },
    val attachmentPickerFailed: String = "Unable to add the attachment.",
    val attachmentPickerPermissionNeeded: String = "Permission is needed to add an attachment.",
    val messageRequired: String = "A message is required.",
    val submissionUnavailable: String = "Feedback is unavailable right now. Please try again.",
    val submissionFailedTitle: String = "Feedback failed",
    val submissionFailedBody: String = "We could not send your feedback. Please try again.",
    val submissionNetworkBody: String = "Could not reach Hands. Check your connection and try again.",
    val submissionRateLimitedBody: String = "Too many feedback requests were sent. Wait a moment and try again.",
    val submissionAttachmentReadBody: String = "An attachment could not be read. Remove it and attach the file again.",
    val submissionAttachmentTooLargeBody: String = "An attachment is too large. Remove it or choose a smaller file.",
    val submissionAuthBody: String = "Feedback authentication failed. Update the app or contact support.",
    val submissionPermissionTitle: String = "Permission needed",
    val submissionPermissionBody: String = "You do not have permission to send feedback.",
    val submissionSucceeded: (String) -> String = { ticket -> "Submitted $ticket." },
    val referenceCopied: String = "Reference copied automatically.",
    val submitting: String = "Submitting feedback and preparing the copied reference...",
    val placeholder: String = "Share feedback",
    val copyResult: String = "Copy result",
    val retry: String = "Retry"
)

internal data class SettingsHandsFeedbackFailurePresentation(
    val composerStatus: String,
    val title: String,
    val body: String,
    val retryable: Boolean
)

internal fun SettingsHandsFeedbackCopy.submissionFailure(
    diagnosticCode: String? = null,
    retryable: Boolean = true
): SettingsHandsFeedbackFailurePresentation {
    val body =
        when (diagnosticCode) {
            "settings_feedback_network_failed" -> submissionNetworkBody
            "settings_feedback_server_unavailable" -> submissionUnavailable
            "settings_feedback_rate_limited" -> submissionRateLimitedBody
            "settings_feedback_attachment_read_failed" -> submissionAttachmentReadBody
            "settings_feedback_attachment_too_large" -> submissionAttachmentTooLargeBody
            "settings_feedback_auth_failed" -> submissionAuthBody
            else -> submissionFailedBody
        }
    val canRetry =
        retryable &&
            diagnosticCode !in
                setOf(
                    "settings_feedback_attachment_read_failed",
                    "settings_feedback_attachment_too_large",
                    "settings_feedback_auth_failed"
                )
    return SettingsHandsFeedbackFailurePresentation(
        composerStatus = body,
        title = submissionFailedTitle,
        body = body,
        retryable = canRetry
    )
}

internal fun SettingsHandsFeedbackCopy.submissionUnavailableFailure(): SettingsHandsFeedbackFailurePresentation =
    SettingsHandsFeedbackFailurePresentation(
        composerStatus = submissionUnavailable,
        title = submissionFailedTitle,
        body = submissionUnavailable,
        retryable = true
    )

internal fun SettingsHandsFeedbackCopy.submissionPermissionFailure(): SettingsHandsFeedbackFailurePresentation =
    SettingsHandsFeedbackFailurePresentation(
        composerStatus = submissionPermissionBody,
        title = submissionPermissionTitle,
        body = submissionPermissionBody,
        retryable = false
    )

@Composable
private fun settingsHandsFeedbackCopy(attachmentMaxLabel: String): SettingsHandsFeedbackCopy {
    val attachmentLimitReachedTemplate = stringResource(MR.strings.settings_feedback_attachment_limit_reached)
    val attachmentTooLargeTemplate = stringResource(MR.strings.settings_feedback_attachment_too_large)
    val attachmentLimitTrimmedTemplate = stringResource(MR.strings.settings_feedback_attachment_limit_trimmed)
    val submissionSucceededTemplate = stringResource(MR.strings.settings_feedback_submission_succeeded)
    return SettingsHandsFeedbackCopy(
        title = stringResource(MR.strings.settings_feedback_title),
        introduction = stringResource(MR.strings.settings_feedback_introduction),
        you = stringResource(MR.strings.settings_feedback_you),
        attachment = stringResource(MR.strings.settings_feedback_attachment),
        attachmentLimitReached = { limit -> attachmentLimitReachedTemplate.replaceLocalizedFormatArgs(limit) },
        attachmentTooLarge = { maxSize -> attachmentTooLargeTemplate.replaceLocalizedFormatArgs(maxSize) },
        attachmentLimitTrimmed = { limit -> attachmentLimitTrimmedTemplate.replaceLocalizedFormatArgs(limit) },
        attachmentPickerFailed = stringResource(MR.strings.settings_feedback_attachment_picker_failed),
        attachmentPickerPermissionNeeded = stringResource(MR.strings.settings_feedback_attachment_permission_needed),
        messageRequired = stringResource(MR.strings.settings_feedback_message_required),
        submissionUnavailable = stringResource(MR.strings.settings_feedback_submission_unavailable),
        submissionFailedTitle = stringResource(MR.strings.settings_feedback_submission_failed_title),
        submissionFailedBody = stringResource(MR.strings.settings_feedback_submission_failed_body),
        submissionNetworkBody = stringResource(MR.strings.settings_feedback_submission_network_body),
        submissionRateLimitedBody = stringResource(MR.strings.settings_feedback_submission_rate_limited_body),
        submissionAttachmentReadBody = stringResource(MR.strings.settings_feedback_submission_attachment_read_body),
        submissionAttachmentTooLargeBody =
            stringResource(MR.strings.settings_feedback_submission_attachment_too_large_body)
                .replaceLocalizedFormatArgs(attachmentMaxLabel),
        submissionAuthBody = stringResource(MR.strings.settings_feedback_submission_auth_body),
        submissionPermissionTitle = stringResource(MR.strings.settings_feedback_submission_permission_title),
        submissionPermissionBody = stringResource(MR.strings.settings_feedback_submission_permission_body),
        submissionSucceeded = { ticket -> submissionSucceededTemplate.replaceLocalizedFormatArgs(ticket) },
        referenceCopied = stringResource(MR.strings.settings_feedback_reference_copied),
        submitting = stringResource(MR.strings.settings_feedback_submitting),
        placeholder = stringResource(MR.strings.settings_feedback_placeholder),
        copyResult = stringResource(MR.strings.settings_feedback_copy_result),
        retry = stringResource(MR.strings.common_retry)
    )
}

internal data class SettingsHandsFeedbackRetryRequest(
    val message: String,
    val attachments: List<PlatformFile>
)

internal fun SettingsHandsFeedbackRetryRequest.matches(
    message: String,
    attachments: List<PlatformFile>
): Boolean =
    this.message == message.trim() &&
        this.attachments.map { it.feedbackComposerAttachmentId() } ==
        attachments.map { it.feedbackComposerAttachmentId() }

internal fun String.replaceLocalizedFormatArgs(vararg args: Any): String =
    args.foldIndexed(this) { index, value, argument ->
        val placeholderIndex = index + 1
        value
            .replace("%${placeholderIndex}${'$'}s", argument.toString())
            .replace("%${placeholderIndex}${'$'}d", argument.toString())
    }

private fun SettingsHandsFeedbackSubmission.toHandsFeedbackEntry(
    id: Int,
    copy: SettingsHandsFeedbackCopy
): SettingsHandsFeedbackEntry =
    SettingsHandsFeedbackEntry(
        id = id,
        kind = SettingsHandsFeedbackEntryKind.Hands,
        title = copy.title,
        body =
            listOf(
                copy.submissionSucceeded(ticketId.take(8).ifBlank { copy.title }),
                shareText.takeIf { it.isNotBlank() }?.let { copy.referenceCopied }
            ).filterNotNull().joinToString(" "),
        detail = shareText.takeIf { it.isNotBlank() },
        ticketId = ticketId,
        shareText = shareText
    )

private fun List<PlatformFile>.toFeedbackComposerAttachmentItems(
    copy: SettingsHandsFeedbackCopy
): List<RaftPendingAttachmentItem> =
    map { file ->
        RaftPendingAttachmentItem(
            id = file.feedbackComposerAttachmentId(),
            fileName = file.fileName.ifBlank { copy.attachment },
            sizeLabel = "",
            sizeBytes = file.sizeBytes ?: 0L,
            mimeType = file.mimeType,
            previewUri = file.previewUri,
            status = "ready"
        )
    }

internal data class SettingsHandsFeedbackAttachmentMerge(
    val attachments: List<PlatformFile>,
    val rejectedCount: Int,
    val limitTrimmed: Boolean
)

internal fun settingsHandsFeedbackMergedAttachments(
    existing: List<PlatformFile>,
    picked: List<PlatformFile>,
    attachmentMaxBytes: Long,
    attachmentLimit: Int
): SettingsHandsFeedbackAttachmentMerge {
    val accepted = picked.filter { file -> (file.sizeBytes ?: 0L) <= attachmentMaxBytes }
    val rejectedCount = picked.size - accepted.size
    val remainingSlots = (attachmentLimit - existing.size).coerceAtLeast(0)
    val merged =
        (existing + accepted)
            .distinctBy { it.fileRef ?: it.id }
            .take(attachmentLimit)
    return SettingsHandsFeedbackAttachmentMerge(
        attachments = merged,
        rejectedCount = rejectedCount,
        limitTrimmed = accepted.size > remainingSlots
    )
}

internal fun PlatformFile.feedbackComposerAttachmentId(): String =
    fileRef?.takeIf { it.isNotBlank() } ?: id

internal fun PlatformFile.toSettingsHandsFeedbackAttachmentPreviewPayload(
    fallbackFileName: String
): RaftFilePreviewPayload? {
    val localUri = composerLocalPreviewUri()?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val displayName = fileName.ifBlank { fallbackFileName }
    val displayMimeType = mimeType.orEmpty()
    val image = raftFilePreviewIsImageAttachment(displayName, displayMimeType)
    val previewKind = if (image) "image" else "file"
    return RaftFilePreviewPayload(
        kind = previewKind,
        requestedKind = previewKind,
        titleDesc =
            if (image) {
                MR.strings.file_preview_image.desc()
            } else {
                MR.strings.file_preview_title.desc()
            },
        subtitle = displayName,
        name = displayName,
        path = displayName,
        meta = sizeBytes?.takeIf { it > 0L }?.let { RaftFormat.bytes(it) }.orEmpty(),
        imageUrl = localUri.takeIf { image },
        externalUrl = localUri,
        externalFileName = displayName,
        externalMimeType = displayMimeType,
        source = source,
        attachmentId = feedbackComposerAttachmentId()
    )
}

@Composable
private fun SettingsHandsFeedbackConversationRow(
    entry: SettingsHandsFeedbackEntry,
    copy: SettingsHandsFeedbackCopy,
    onCopy: (String) -> Unit,
    onRetry: (SettingsHandsFeedbackRetryRequest) -> Unit
) {
    val isUser = entry.kind == SettingsHandsFeedbackEntryKind.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 560.dp)
                    .raftCardSurface(fill = when (entry.kind) {
                            SettingsHandsFeedbackEntryKind.User -> RaftColor.BrandSoft
                            SettingsHandsFeedbackEntryKind.Hands -> RaftColor.Card
                            // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1044
                            SettingsHandsFeedbackEntryKind.Error -> RaftColor.AccentRed.copy(alpha = 0.18f)
                        })
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = entry.title,
                style = RaftType.SectionLabel,
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.body,
                style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = RaftColor.TextBlack
            )
            entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                SelectionContainer(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = detail,
                        style = RaftType.Caption.copy(color = RaftColor.TextMutedGray),
                        color = RaftColor.TextMutedGray
                    )
                }
            }
            entry.shareText?.takeIf { it.isNotBlank() }?.let { shareText ->
                RaftButton(
                    text = copy.copyResult,
                    onClick = { onCopy(shareText) },
                    modifier = Modifier.fillMaxWidth(),
                    size = RaftButtonSize.Md,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.Clipboard) }
                )
            }
            entry.retryRequest?.let { retryRequest ->
                RaftButton(
                    text = copy.retry,
                    onClick = { onRetry(retryRequest) },
                    modifier = Modifier.fillMaxWidth(),
                    size = RaftButtonSize.Md,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) }
                )
            }
        }
    }
}
