package build.raft.app

import build.raft.app.resources.*
import build.raft.app.base.Utils
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.app.transfer.RaftTransferProgress
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.KeyValueChatLayoutPreferenceStore
import build.raft.app.data.RaftEventBus
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.DataResult
import build.raft.app.data.PlatformFile
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsFeedbackProxyAttempt
import build.raft.app.data.SettingsFeedbackProxyOutcome
import build.raft.app.data.SettingsFeedbackProxySubmitter
import build.raft.app.data.SETTINGS_FEEDBACK_ATTACHMENT_TYPE_INVALID
import build.raft.app.data.PRODUCT_FEEDBACK_MAX_ATTACHMENTS
import build.raft.app.data.SettingsFeedbackType
import build.raft.app.data.SettingsHandsFeedbackSubmission
import build.raft.app.data.SettingsHandsFeedbackDraftSnapshot
import build.raft.app.data.SettingsHandsFeedbackDraftStore
import build.raft.app.format.RaftFormat
import build.raft.app.data.raftSharedPreferenceStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    /**
     * Signed in: files through the Raft server (`POST /api/product-feedback`), so the ticket is
     * this person's and shows in their ticket list. Null keeps the native Hands form (visual
     * fixtures); the submitter itself hands over to it when the server cannot file.
     */
    proxySubmitter: SettingsFeedbackProxySubmitter? = null,
    /** A ticket filed through [proxySubmitter], after the draft is cleared (the page shows it). */
    onTicketFiled: ((ticketId: String) -> Unit)? = null
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
    // Sending (not picking an attachment): the tiles show the upload overlay, as the web's do.
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingRetryRequest by remember { mutableStateOf<SettingsHandsFeedbackRetryRequest?>(null) }
    // The banner text of the last failed send: Retry shows while that failure is the one shown.
    var failedSubmitError by remember { mutableStateOf<String?>(null) }
    // A ticket the native form filed (no ticket page to open): said in the banner, with its reference.
    var filedSubmission by remember(accountId) { mutableStateOf<SettingsHandsFeedbackSubmission?>(null) }
    // Web NewFeedbackForm: Idea first and picked, then Bug.
    var feedbackType by remember(accountId) { mutableStateOf(SettingsFeedbackType.Idea) }
    var keyboardInsetDp by remember { mutableStateOf(0f) }
    var visualAutoSubmitted by remember(accountId, visualFixture) { mutableStateOf(false) }
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
                    request.matches(normalizedMessage, currentAttachments, feedbackType)
                }
        val submittedMessage = retryRequest?.message ?: normalizedMessage
        val submittedAttachments = retryRequest?.attachments ?: currentAttachments
        val submittedType = retryRequest?.type ?: feedbackType
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
        submitting = true
        error = null
        failedSubmitError = null
        filedSubmission = null
        pendingRetryRequest = null
        attachments = submittedAttachments
        // The form keeps what is being sent on screen (read-only while it goes), as the web's does.
        feedbackType = submittedType
        // A retry of the same feedback keeps its submission id and uploaded diagnostics.
        val proxyAttempt =
            proxySubmitter?.let { submitter ->
                retryRequest?.proxyAttempt ?: submitter.newAttempt(submittedMessage, submittedAttachments, submittedType)
            }
        val submittedRequest = SettingsHandsFeedbackRetryRequest(submittedMessage, submittedAttachments, proxyAttempt, submittedType)

        fun appendFailure(
            failure: SettingsHandsFeedbackFailurePresentation,
            nextProxyAttempt: SettingsFeedbackProxyAttempt? = proxyAttempt
        ) {
            val nextRetryRequest = submittedRequest.copy(proxyAttempt = nextProxyAttempt).takeIf { failure.retryable }
            pendingRetryRequest = nextRetryRequest
            messageFieldValue = TextFieldValue(submittedMessage, selection = TextRange(submittedMessage.length))
            draftTextForPersistence = submittedMessage
            attachments = submittedAttachments
            error = failure.composerStatus
            failedSubmitError = failure.composerStatus
        }

        scope.launch {
            var filedTicketId: String? = null
            var nextProxyAttempt = proxyAttempt
            try {
                val result: DataResult<SettingsHandsFeedbackSubmission> =
                    if (proxySubmitter != null && proxyAttempt != null) {
                        when (val outcome = proxySubmitter.submit(proxyAttempt)) {
                            is SettingsFeedbackProxyOutcome.Filed -> {
                                filedTicketId = outcome.ticketId
                                DataResult.Content(SettingsHandsFeedbackSubmission(ticketId = outcome.ticketId, shareText = ""))
                            }
                            is SettingsFeedbackProxyOutcome.Failed -> {
                                nextProxyAttempt = outcome.retry
                                DataResult.Error(ScreenError(outcome.code, retryable = outcome.retryable))
                            }
                            SettingsFeedbackProxyOutcome.UseNativeForm -> {
                                settingsCapabilities.log("settings_hands_feedback_submit_native_fallback")
                                settingsCapabilities.submitHandsFeedback(submittedMessage, submittedAttachments)
                            }
                        }
                    } else {
                        settingsCapabilities.submitHandsFeedback(submittedMessage, submittedAttachments)
                    }
                when (result) {
                    is DataResult.Content -> {
                        settingsCapabilities.log(
                            "settings_hands_feedback_submit_result status=ok ticket=${result.data.ticketId.take(8)}"
                        )
                        pendingRetryRequest = null
                        // Filed through the server, the page opens the ticket; the native form
                        // has no ticket page, so the banner says it went and keeps its reference.
                        if (filedTicketId == null) filedSubmission = result.data
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
                        filedTicketId?.let { ticketId -> onTicketFiled?.invoke(ticketId) }
                    }
                    is DataResult.Empty -> {
                        settingsCapabilities.log("settings_hands_feedback_submit_result status=empty")
                        appendFailure(copy.submissionUnavailableFailure())
                    }
                    is DataResult.Error -> {
                        settingsCapabilities.log(
                            "settings_hands_feedback_submit_result status=error code=${result.error.code}"
                        )
                        appendFailure(copy.submissionFailure(result.error.code, result.error.retryable), nextProxyAttempt)
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
                submitting = false
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
            // Let the real composer render both attachment tiles before the
            // immediate fake-success transition clears the form and shows the
            // filed banner. This is the retained-view reuse window from
            // task #139, not a preview-only approximation.
            delay(750L)
            submitFeedback()
        }
    }

    fun onComposerEvent(action: String, value: String?, attachmentId: String?) {
        when (action) {
            "change" -> {
                draftTextForPersistence = value.orEmpty()
                error = null
                // Writing the next feedback retires the last one's filed banner.
                if (!value.isNullOrBlank()) filedSubmission = null
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
    }

    val notice =
        when {
            !error.isNullOrBlank() ->
                SettingsHandsFeedbackNotice.Problem(
                    message = error.orEmpty(),
                    // Retry only while the failure it would resend is the one shown, for the
                    // type still picked.
                    retryable =
                        !busy && error == failedSubmitError &&
                            pendingRetryRequest?.type == feedbackType
                )
            else -> filedSubmission?.toSettingsHandsFeedbackFiledNotice(copy)
        }

    SettingsHandsFeedbackForm(
        copy = copy,
        type = feedbackType,
        onTypeChange = { next -> if (!busy) feedbackType = next },
        enabled = !busy,
        notice = notice,
        onRetry = { pendingRetryRequest?.let { request -> submitFeedback(request) } },
        onCopyResult = { shareText -> settingsCapabilities.bridgeCopyToClipboard(shareText) },
        bottomContentPadding = bottomContentPadding,
        keyboardInsetDp = keyboardInsetDp,
    ) {
        SettingsHandsFeedbackComposer(
            instanceId = "settings-hands-feedback-composer-$accountId",
            value = messageFieldValue,
            onValueChange = { next -> messageFieldValue = next },
            currentValue = { messageFieldValue },
            placeholder = copy.placeholder,
            enabled = !busy,
            submitting = submitting,
            attachmentActionsEnabled = remainingSlots > 0,
            attachments = attachments.toFeedbackComposerAttachmentItems(copy, uploading = submitting),
            composerSendKeyEnabled = composerSendKeyEnabled,
            onKeyboardHeightChange = { inset -> if (keyboardInsetDp != inset) keyboardInsetDp = inset },
            onEvent = { action, value, attachmentId -> onComposerEvent(action, value, attachmentId) },
        )
    }
}

internal data class SettingsHandsFeedbackVisualFixture(
    val draft: SettingsHandsFeedbackDraftSnapshot,
    val autoSubmit: Boolean
)

internal data class SettingsHandsFeedbackCopy(
    val title: String = "Feedback",
    val typeLabel: String = "Type",
    val idea: String = "Idea",
    val bug: String = "Bug",
    val attachmentHint: String = "Up to 3 images, 50 MB each.",
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
    val submissionAttachmentTypeBody: String = "Screenshots must be PNG, JPEG, GIF, or WebP images.",
    val submissionAuthBody: String = "Feedback authentication failed. Update the app or contact support.",
    val submissionPermissionTitle: String = "Permission needed",
    val submissionPermissionBody: String = "You do not have permission to send feedback.",
    val submissionSucceeded: (String) -> String = { ticket -> "Submitted $ticket." },
    val referenceCopied: String = "Reference copied automatically.",
    val placeholder: String = "Describe your idea or the bug you ran into…",
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
            SETTINGS_FEEDBACK_ATTACHMENT_TYPE_INVALID -> submissionAttachmentTypeBody
            "settings_feedback_auth_failed" -> submissionAuthBody
            else -> submissionFailedBody
        }
    val canRetry =
        retryable &&
            diagnosticCode !in
                setOf(
                    "settings_feedback_attachment_read_failed",
                    "settings_feedback_attachment_too_large",
                    SETTINGS_FEEDBACK_ATTACHMENT_TYPE_INVALID,
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
internal fun settingsHandsFeedbackCopy(attachmentMaxLabel: String): SettingsHandsFeedbackCopy {
    val attachmentLimitReachedTemplate = stringResource(Res.string.settings_feedback_attachment_limit_reached)
    val attachmentTooLargeTemplate = stringResource(Res.string.settings_feedback_attachment_too_large)
    val attachmentLimitTrimmedTemplate = stringResource(Res.string.settings_feedback_attachment_limit_trimmed)
    val submissionSucceededTemplate = stringResource(Res.string.settings_feedback_submission_succeeded)
    return SettingsHandsFeedbackCopy(
        title = stringResource(Res.string.settings_feedback_title),
        typeLabel = stringResource(Res.string.settings_feedback_type_label),
        idea = stringResource(Res.string.settings_feedback_tickets_kind_idea),
        bug = stringResource(Res.string.settings_feedback_tickets_kind_bug),
        attachmentHint =
            stringResource(Res.string.settings_feedback_attachment_hint)
                .replaceLocalizedFormatArgs(PRODUCT_FEEDBACK_MAX_ATTACHMENTS, attachmentMaxLabel),
        attachment = stringResource(Res.string.settings_feedback_attachment),
        attachmentLimitReached = { limit -> attachmentLimitReachedTemplate.replaceLocalizedFormatArgs(limit) },
        attachmentTooLarge = { maxSize -> attachmentTooLargeTemplate.replaceLocalizedFormatArgs(maxSize) },
        attachmentLimitTrimmed = { limit -> attachmentLimitTrimmedTemplate.replaceLocalizedFormatArgs(limit) },
        attachmentPickerFailed = stringResource(Res.string.settings_feedback_attachment_picker_failed),
        attachmentPickerPermissionNeeded = stringResource(Res.string.settings_feedback_attachment_permission_needed),
        messageRequired = stringResource(Res.string.settings_feedback_message_required),
        submissionUnavailable = stringResource(Res.string.settings_feedback_submission_unavailable),
        submissionFailedTitle = stringResource(Res.string.settings_feedback_submission_failed_title),
        submissionFailedBody = stringResource(Res.string.settings_feedback_submission_failed_body),
        submissionNetworkBody = stringResource(Res.string.settings_feedback_submission_network_body),
        submissionRateLimitedBody = stringResource(Res.string.settings_feedback_submission_rate_limited_body),
        submissionAttachmentReadBody = stringResource(Res.string.settings_feedback_submission_attachment_read_body),
        submissionAttachmentTooLargeBody =
            stringResource(Res.string.settings_feedback_submission_attachment_too_large_body)
                .replaceLocalizedFormatArgs(attachmentMaxLabel),
        submissionAttachmentTypeBody = stringResource(Res.string.settings_feedback_submission_attachment_type_body),
        submissionAuthBody = stringResource(Res.string.settings_feedback_submission_auth_body),
        submissionPermissionTitle = stringResource(Res.string.settings_feedback_submission_permission_title),
        submissionPermissionBody = stringResource(Res.string.settings_feedback_submission_permission_body),
        submissionSucceeded = { ticket -> submissionSucceededTemplate.replaceLocalizedFormatArgs(ticket) },
        referenceCopied = stringResource(Res.string.settings_feedback_reference_copied),
        placeholder = stringResource(Res.string.settings_feedback_describe_placeholder),
        copyResult = stringResource(Res.string.settings_feedback_copy_result),
        retry = stringResource(Res.string.common_retry)
    )
}

internal data class SettingsHandsFeedbackRetryRequest(
    val message: String,
    val attachments: List<PlatformFile>,
    /** The server submission being retried (same id, same diagnostics); null on the native form. */
    val proxyAttempt: SettingsFeedbackProxyAttempt? = null,
    val type: SettingsFeedbackType = SettingsFeedbackType.Idea
)

internal fun SettingsHandsFeedbackRetryRequest.matches(
    message: String,
    attachments: List<PlatformFile>,
    type: SettingsFeedbackType = this.type
): Boolean =
    this.message == message.trim() &&
        this.type == type &&
        this.attachments.map { it.feedbackComposerAttachmentId() } ==
        attachments.map { it.feedbackComposerAttachmentId() }

internal fun String.replaceLocalizedFormatArgs(vararg args: Any): String =
    args.foldIndexed(this) { index, value, argument ->
        val placeholderIndex = index + 1
        value
            .replace("%${placeholderIndex}${'$'}s", argument.toString())
            .replace("%${placeholderIndex}${'$'}d", argument.toString())
    }

/**
 * The form's files as the composer tray's tiles. While the feedback is sent each shows the web's
 * upload overlay ("Uploading"; the server request reports no per-file progress, so no bar).
 */
internal fun List<PlatformFile>.toFeedbackComposerAttachmentItems(
    copy: SettingsHandsFeedbackCopy,
    uploading: Boolean = false
): List<RaftPendingAttachmentItem> =
    map { file ->
        RaftPendingAttachmentItem(
            id = file.feedbackComposerAttachmentId(),
            fileName = file.fileName.ifBlank { copy.attachment },
            sizeLabel = "",
            sizeBytes = file.sizeBytes ?: 0L,
            mimeType = file.mimeType,
            previewUri = file.previewUri,
            status = if (uploading) "uploading" else "ready",
            uploadTransfer = if (uploading) RaftTransferProgress.notStarted() else null
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
                Res.string.file_preview_image.desc()
            } else {
                Res.string.file_preview_title.desc()
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

/** What the banner over the composer says: what went wrong, or that the native form filed it. */
internal sealed interface SettingsHandsFeedbackNotice {
    /** A send or an attachment that failed; [retryable] shows Retry (it resends the same feedback). */
    data class Problem(val message: String, val retryable: Boolean) : SettingsHandsFeedbackNotice

    /** Filed through the native form, which has no ticket page; [shareText] is its reference to copy. */
    data class Filed(val message: String, val shareText: String?) : SettingsHandsFeedbackNotice
}

internal fun SettingsHandsFeedbackSubmission.toSettingsHandsFeedbackFiledNotice(
    copy: SettingsHandsFeedbackCopy
): SettingsHandsFeedbackNotice.Filed {
    val reference = shareText.takeIf { it.isNotBlank() }
    return SettingsHandsFeedbackNotice.Filed(
        message =
            listOfNotNull(
                copy.submissionSucceeded(ticketId.take(8).ifBlank { copy.title }),
                reference?.let { copy.referenceCopied }
            ).joinToString(" "),
        shareText = reference
    )
}

/**
 * Settings › Feedback › New Feedback, laid out as the web's (hands-feedback-react
 * `NewFeedbackForm`): the Type choice (Idea / Bug segmented control), then the composer it shares
 * with a reply (the attachment tiles, the message, attach image / file at its bottom start and
 * send at its bottom end) with a warning banner over it when something went wrong, then the
 * attachment note. 24dp above and below, 16dp at the sides, 20dp between the parts, at most
 * 640dp wide. The page head (title and "The team replies in the ticket.") is the caller's.
 *
 * [composer] is the conversation composer ([SettingsHandsFeedbackComposer]); the page scrolls,
 * and keeps [keyboardInsetDp] of room under the form so the composer can sit above the keyboard.
 */
@Composable
internal fun SettingsHandsFeedbackForm(
    copy: SettingsHandsFeedbackCopy,
    type: SettingsFeedbackType,
    onTypeChange: (SettingsFeedbackType) -> Unit,
    enabled: Boolean,
    notice: SettingsHandsFeedbackNotice?,
    onRetry: () -> Unit,
    onCopyResult: (String) -> Unit,
    bottomContentPadding: Dp,
    keyboardInsetDp: Float = 0f,
    modifier: Modifier = Modifier,
    composer: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val bottomRoom = maxOf(bottomContentPadding, keyboardInsetDp.coerceAtLeast(0f).dp)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.layerCanvas)
            .verticalScroll(rememberScrollState())
            .dismissInputFocusOnScrollableChrome()
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp + bottomRoom),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // A group, not a field control: the label names the choice, as on the web.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftFieldLabel(copy.typeLabel, enabled = enabled)
                RaftSegmentedControl {
                    RaftSegment(
                        text = copy.idea,
                        selected = type == SettingsFeedbackType.Idea,
                        onClick = { onTypeChange(SettingsFeedbackType.Idea) },
                        enabled = enabled,
                        // The App's Idea glyph (the ticket list's); the web draws Lucide `lightbulb`.
                        icon = { SettingsHandsFeedbackTypeIcon(RaftLucideIconName.Sparkles) },
                    )
                    RaftSegment(
                        text = copy.bug,
                        selected = type == SettingsFeedbackType.Problem,
                        onClick = { onTypeChange(SettingsFeedbackType.Problem) },
                        enabled = enabled,
                        icon = { SettingsHandsFeedbackTypeIcon(RaftLucideIconName.Bug) },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notice?.let { shown ->
                    SettingsHandsFeedbackNoticeBanner(shown, copy, onRetry, onCopyResult)
                }
                composer()
            }
            RaftText(
                copy.attachmentHint,
                style = RaftTheme.typography.caption,
                color = colors.foregroundMuted,
            )
        }
    }
}

@Composable
private fun SettingsHandsFeedbackTypeIcon(name: RaftLucideIconName) {
    val ink = LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
    RaftLucideIcon(name, modifier = Modifier.size(16.dp), color = raftArgbHex(ink))
}

/** Web FeedbackErrorBanner (warning, small) with Retry; the native form's filed ticket as success. */
@Composable
private fun SettingsHandsFeedbackNoticeBanner(
    notice: SettingsHandsFeedbackNotice,
    copy: SettingsHandsFeedbackCopy,
    onRetry: () -> Unit,
    onCopyResult: (String) -> Unit,
) {
    when (notice) {
        is SettingsHandsFeedbackNotice.Problem ->
            RaftBanner(
                description = notice.message,
                status = RaftBannerStatus.Warning,
                size = RaftBannerSize.Sm,
                modifier = Modifier.testTag(SettingsHandsFeedbackNoticeTag),
                action =
                    if (notice.retryable) {
                        {
                            RaftButton(
                                text = copy.retry,
                                onClick = onRetry,
                                size = RaftButtonSize.Md,
                            )
                        }
                    } else {
                        null
                    },
            )
        is SettingsHandsFeedbackNotice.Filed -> {
            val shareText = notice.shareText
            RaftBanner(
                description = notice.message,
                status = RaftBannerStatus.Success,
                size = RaftBannerSize.Sm,
                modifier = Modifier.testTag(SettingsHandsFeedbackNoticeTag),
                action =
                    if (shareText != null) {
                        {
                            RaftButton(
                                text = copy.copyResult,
                                onClick = { onCopyResult(shareText) },
                                size = RaftButtonSize.Md,
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

internal const val SettingsHandsFeedbackNoticeTag = "settings_hands_feedback_notice"

/**
 * The New Feedback message field: the conversation composer (the one under a ticket, and the
 * web's `FeedbackComposer` on raft-ui Composer), with the form's attachment tray and its attach
 * image / attach file / send actions. Inline in the form, so it reports the keyboard height
 * ([onKeyboardHeightChange]) instead of lifting itself.
 */
@Composable
internal fun SettingsHandsFeedbackComposer(
    instanceId: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    enabled: Boolean,
    submitting: Boolean,
    attachmentActionsEnabled: Boolean,
    attachments: List<RaftPendingAttachmentItem>,
    composerSendKeyEnabled: Boolean,
    onEvent: (action: String, value: String?, attachmentId: String?) -> Unit,
    onKeyboardHeightChange: (Float) -> Unit = {},
    /** The field's value now (a second native callback may come before the next recomposition). */
    currentValue: () -> TextFieldValue = { value },
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
                instanceId = instanceId,
                seedText = value.text,
                placeholder = placeholder,
                enabled = enabled,
                attachmentActionsEnabled = attachmentActionsEnabled && enabled,
                // Settings feedback accepts images and files as evidence, but it has no
                // thread-composer image policy. Do not expose a checkbox whose event this
                // surface cannot own or persist.
                originalImageOptionEnabled = false,
                composerMode = if (submitting) "sending" else "idle",
                // What went wrong is the form's banner over the composer, not a status line.
                statusLabel = null,
                suggestionsEnabled = false,
                pendingAttachments = attachments
            ),
        editorSessionId = 1L,
        editorValue = value,
        onEditorValueChange = { _, next ->
            onValueChange(next)
            true
        },
        onNativeEditorValueChange = { _, incoming ->
            repairThreadComposerLegacyZeroSelection(
                previous = currentValue(),
                incoming = incoming,
            ).also { accepted ->
                onValueChange(accepted)
            }
        },
        isEditorSessionActive = { true },
        editorAsTask = false,
        onEditorAsTaskChange = { _, _ -> true },
        composerSendKeyEnabled = composerSendKeyEnabled,
        dismissKeyboardOnBlur = true,
        onEvent = { action, eventValue, _, attachmentId -> onEvent(action, eventValue, attachmentId) },
        onKeyboardHeightChange = onKeyboardHeightChange,
        contentPaddingTop = 0.dp,
        contentPaddingBottom = 0.dp,
    )
}
