package build.raft.app.thread

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.RAFT_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE
import build.raft.app.RaftComposerServerUploads
import build.raft.app.RaftFilePreviewCompressedImageResult
import build.raft.app.RaftFilePreviewPayload
import build.raft.app.RaftPendingAttachmentItem
import build.raft.app.RaftThreadComposerPayload
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ReplyCacheProvenance
import build.raft.app.SharedThreadComposerDraftIntent
import build.raft.app.SharedThreadComposerDraftPersistSlice
import build.raft.app.SharedThreadComposerDraftViewModel
import build.raft.app.SharedThreadComposerExternalShareSource
import build.raft.app.ThreadAttachmentUploadAttemptIdentity
import build.raft.app.ThreadAttachmentUploadOrchestrator
import build.raft.app.ThreadComposerAttachImageAction
import build.raft.app.ThreadComposerAttachmentRemovalDispatchResult
import build.raft.app.ThreadComposerCaptureImageAction
import build.raft.app.ThreadComposerDismissUnresolvedMentionsEvent
import build.raft.app.ThreadComposerImagePasteMaxImages
import build.raft.app.ThreadComposerImagePasteRequests
import build.raft.app.ThreadComposerKeyboardHandoff
import build.raft.app.ThreadComposerPasteAttachmentsAction
import build.raft.app.ThreadComposerReorderAttachmentsAction
import build.raft.app.ThreadComposerSelectMentionEvent
import build.raft.app.ThreadComposerSendAdmission
import build.raft.app.ThreadComposerSendRequest
import build.raft.app.ThreadComposerSurface
import build.raft.app.assistant.assistantContextTokenForSend
import build.raft.app.assistant.withAssistantContextReference
import build.raft.app.buildPendingMentionTargetMap
import build.raft.app.compat.json.JSONObject
import build.raft.app.data.PlatformFile
import build.raft.app.data.RaftAttachmentUploadCancelReason
import build.raft.app.data.SharedMembersIntent
import build.raft.app.data.SharedMembersStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.parseSharedThreadPickedPlatformFiles
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.decodeThreadComposerMentionEvent
import build.raft.app.dispatchThreadComposerAttachmentRemoval
import build.raft.app.hasBlockingThreadAttachmentUpload
import build.raft.app.hasDiscardFailedThreadAttachment
import build.raft.app.hasFailedThreadAttachmentUpload
import build.raft.app.hasRemovingThreadAttachment
import build.raft.app.isCompletedServerDraftAttachment
import build.raft.app.mentionScopeChannelLabel
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.normalizeThreadAttachmentUploadDraftKey
import build.raft.app.optInt
import build.raft.app.optString
import build.raft.app.parseRaftFilePreviewCompressedImageResult
import build.raft.app.raftPlatformPastedImagesOpenEditor
import build.raft.app.raftPlatformReadClipboardAttachments
import build.raft.app.readyThreadAttachmentIdsForSend
import build.raft.app.reorderThreadComposerAttachments
import build.raft.app.settle
import build.raft.app.threadAttachmentUploadFile
import build.raft.app.threadComposerSendGateAttachmentStates
import build.raft.app.toThreadComposerDraftDiagnosticsSummary
import build.raft.app.visibleStructuredMentionsIn
import build.raft.app.withoutThreadAttachmentUploadFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The composer's send, image annotation, picked files and events: what used to be [performComposerSend],
 * [handleComposerSendRequest], [openComposerImageAnnotation], [admitComposerPickedAttachments], the
 * member-suggestion refreshes and the body of `onComposerEvent`, local functions and a lambda of
 * `SharedThreadRouteContentContent`, moved verbatim.
 *
 * Built every composition from the values they captured (states as their [State] / [MutableState], read
 * and written when an event runs, as they did); never remembered. The route's own local functions they
 * call are function values behind members of the same signature.
 */
internal class ThreadRouteComposerActions(
    val bridge: build.raft.app.base.BridgeModule,
    val dataSource: SharedThreadDataSource,
    val membersStore: SharedMembersStore?,
    val serverMembersStore: SharedServerMembersStore?,
    val composerDraftPersistSlice: SharedThreadComposerDraftPersistSlice,
    val coroutineScope: CoroutineScope,
    val threadViewModel: SharedThreadViewModel,
    val renderSliceState: State<SharedThreadRenderSlice>,
    val payload: RaftThreadScreenPayload,
    val threadInstanceKey: String,
    val currentSession: StoredAuthSession?,
    val currentServerId: String?,
    val replyCacheProvenance: ReplyCacheProvenance?,
    val threadMembersServerId: ServerId?,
    val threadLimitLocked: Boolean,
    val memberSuggestionScopeChannelId: String?,
    val memberSuggestionScopedMentions: Boolean,
    val memberSuggestionExcludeOutsiderGuests: Boolean = false,
    val labels: ThreadRouteLabels,
    val userFacingErrorLabels: ThreadUserFacingErrorLabels,
    val composerDraftViewModel: SharedThreadComposerDraftViewModel,
    val composerKeyboardHandoff: ThreadComposerKeyboardHandoff,
    val composerSurface: ThreadComposerSurface,
    val attachmentUploads: RaftComposerServerUploads,
    val attachmentUploadOrchestrator: ThreadAttachmentUploadOrchestrator,
    val pendingMentionActionBatchRequestGate: PendingMentionActionBatchRequestGate,
    val threadRouteAttachmentActions: ThreadRouteAttachmentActions,
    val threadRouteAttachmentUploader: ThreadRouteAttachmentUploader,
    val adaptivePageKeyboardInsetDpState: MutableState<Float>,
    val attachmentUploadFilesByLocalIdState: MutableState<Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile>>,
    val externalShareSendRequestedState: MutableState<Boolean>,
    val externalShareExpectedAuthorityState: MutableState<build.raft.app.data.RaftExpectedAuthBinding?>,
    val pendingOptimisticSendState: MutableState<ThreadComposerSendRequest?>,
    val composerImageActionCounterState: MutableState<Int>,
    val latestUploadComposerAttachmentFilesState: State<suspend (List<PlatformFile>, String?, Boolean, Map<String, String>) -> Boolean>,
    val currentComposerPayloadFn: () -> RaftThreadComposerPayload,
    val sendPendingLocalMessageFn: (String, RaftThreadPendingSendContext, Boolean, ThreadLocalSendAuthority) -> Unit,
    val localizedAttachmentUploadFailedDetailFn: (detail: String) -> String,
    val localizedAttachmentSkippedManyFn: (count: Int) -> String,
) {
    val renderSlice by renderSliceState
    val adaptivePageKeyboardInsetDp by adaptivePageKeyboardInsetDpState
    var attachmentUploadFilesByLocalId by attachmentUploadFilesByLocalIdState
    var externalShareSendRequested by externalShareSendRequestedState
    var externalShareExpectedAuthority by externalShareExpectedAuthorityState
    var composerImageActionCounter by composerImageActionCounterState
    val latestUploadComposerAttachmentFiles by latestUploadComposerAttachmentFilesState
    val genericThreadErrorLabel = labels.genericThreadErrorLabel
    val pendingMentionActionBatchStrings = labels.pendingMentionActionBatchStrings
    val attachmentPickerUnavailableLabel = labels.attachmentPickerUnavailableLabel
    val attachmentRetryUnavailableLabel = labels.attachmentRetryUnavailableLabel
    val attachmentSkippedOneLabel = labels.attachmentSkippedOneLabel

    fun currentComposerPayload(): RaftThreadComposerPayload = currentComposerPayloadFn()

    fun sendPendingLocalMessage(
        localMessageId: String,
        context: RaftThreadPendingSendContext,
        startedFromComposer: Boolean,
        authority: ThreadLocalSendAuthority
    ): Unit = sendPendingLocalMessageFn(localMessageId, context, startedFromComposer, authority)

    fun localizedAttachmentUploadFailedDetail(detail: String): String = localizedAttachmentUploadFailedDetailFn(detail)

    fun localizedAttachmentSkippedMany(count: Int): String = localizedAttachmentSkippedManyFn(count)

    fun openComposerAttachmentPreview(attachmentId: String): Boolean =
        threadRouteAttachmentActions.openComposerAttachmentPreview(attachmentId)

    fun openTrackedComposerImagePreview(payload: RaftFilePreviewPayload): Boolean =
        threadRouteAttachmentActions.openTrackedComposerImagePreview(payload)

    suspend fun uploadComposerAttachmentFiles(
        files: List<PlatformFile>,
        replaceAttachmentId: String? = null,
        requireAllFiles: Boolean = false,
        replaceAttachmentIdsByLocalId: Map<String, String> = emptyMap()
    ): Boolean =
        threadRouteAttachmentUploader.uploadComposerAttachmentFiles(
            files,
            replaceAttachmentId,
            requireAllFiles,
            replaceAttachmentIdsByLocalId
        )

    fun performComposerSend(
        optimisticResolvedQuotes: Map<String, build.raft.app.model.ResolvedInlineQuote>? = null,
    ): Boolean {
        val currentComposer = currentComposerPayload()
        val currentDraftState = composerDraftViewModel.state.value
        val composerInstanceId = currentComposer.instanceId.ifBlank { payload.instanceId }
        val threadId = payload.composerSendTargetChannelId()
        val externalShareAttachments =
            currentComposer.pendingAttachments.filter { attachment -> attachment.source == "external_share" }
        val externalShareSources = currentDraftState.externalShareSources
        val session = currentSession
        if (
            externalShareSources.any { source ->
                session == null || source.accountId != session.user.id || source.serverId != session.currentServerId
            } || externalShareAttachments.any { attachment ->
                session == null ||
                    attachment.externalShareAccountId != session.user.id ||
                    attachment.externalShareServerId != session.currentServerId
            }
        ) {
            bridge.logWarn("external_share_send_rejected reason=authority_changed_before_message_send")
            resolveExternalShareCrossServerNotice(
                session = session,
                externalShareSources = externalShareSources,
                externalShareAttachments = externalShareAttachments,
                otherServerTemplate = labels.externalShareOtherServerTemplate,
                otherServerGenericLabel = labels.externalShareOtherServerGenericLabel,
            )?.let(bridge::toast)
            return false
        }
        val draftText = currentDraftState.seedText.trim()
        val attachmentIds = currentComposer.pendingAttachments.readyThreadAttachmentIdsForSend()
        val hasBlockingAttachmentUpload = currentComposer.pendingAttachments.hasBlockingThreadAttachmentUpload()
        val hasFailedAttachmentUpload = currentComposer.pendingAttachments.hasFailedThreadAttachmentUpload()
        val hasRemovingAttachment = currentComposer.pendingAttachments.hasRemovingThreadAttachment()
        val hasDiscardFailedAttachment = currentComposer.pendingAttachments.hasDiscardFailedThreadAttachment()
        val allowOptimisticLoadingSend =
            threadViewModel.state.value.allowsOptimisticSendWhileAccessLoading(
                sendChannelId = threadId,
                limitLocked = threadLimitLocked,
            )
        val gateBlockReason =
            when {
                !payload.canSend && !allowOptimisticLoadingSend -> "payload_can_send_false"
                threadId == null -> "missing_send_channel"
                hasDiscardFailedAttachment -> "attachment_discard_failed"
                hasRemovingAttachment -> "attachment_discard_in_progress"
                hasFailedAttachmentUpload -> "attachment_upload_failed"
                hasBlockingAttachmentUpload -> "attachment_upload_in_progress"
                draftText.isBlank() && attachmentIds.isEmpty() -> "empty_draft"
                else -> null
            }
        if (gateBlockReason != null) {
            bridge.log(
                "thread_composer_send_gate result=blocked reason=$gateBlockReason " +
                    "surface=${payload.surfaceKind} status=${payload.status} canSend=${payload.canSend} " +
                    "sendChannelId=${threadId.orEmpty()} hasText=${draftText.isNotBlank()} " +
                    "attachments=${attachmentIds.size} blockingAttachments=$hasBlockingAttachmentUpload " +
                    "failedAttachments=$hasFailedAttachmentUpload " +
                    "attachmentStates=${currentComposer.pendingAttachments.threadComposerSendGateAttachmentStates()}"
            )
            return false
        }
        val sendAuthority =
            dataSource.captureThreadLocalSendAuthority(
                state = threadViewModel.state.value,
                mountedOwnerProvenance = replyCacheProvenance
            )
                ?: run {
                    bridge.logWarn("thread_composer_send_rejected reason=missing_owner_provenance")
                    return false
                }
        val pendingAttachments = currentComposer.pendingAttachments
        val ownedTemporaryFileRefs =
            threadPendingSendOwnedTemporaryFileRefs(
                attachments = currentDraftState.uploadedAttachments,
                attachmentIds = attachmentIds
            )
        // The assistant's DM in its panel: a message with text carries the conversation the panel
        // was opened from, its token on a line of its own (web MessageInput withComposerReference).
        val assistantContextAttachment = composerSurface.assistantContext
        val assistantContextToken = assistantContextTokenForSend(draftText, assistantContextAttachment?.context)
        val composedBody =
            threadComposerMessageBodyForSend(
                draftText = draftText,
                attachmentCount = pendingAttachments.size
            )
        val pendingContext =
            RaftThreadPendingSendContext(
                channelId = threadId ?: return false,
                body = withAssistantContextReference(composedBody, assistantContextToken),
                asTask = currentDraftState.seedAsTask && composerSurface.sendsAsTask,
                attachmentIds = attachmentIds,
                attachments = pendingAttachments,
                ownedTemporaryFileRefs = ownedTemporaryFileRefs,
                externalShareSources = externalShareSources,
                mentionChannelId = composerSurface.mentionScopeChannelId,
                parentMessageId = composerSurface.parentMessageId,
                streamChannelId = composerSurface.streamChannelId,
                memberScopedMentions = payload.memberScopedMentions,
                randomId = newThreadSendRandomId(),
                // Only picks whose `@handle` is still in the text go out; the list is journaled as is.
                mentions = currentDraftState.structuredMentions.visibleStructuredMentionsIn(draftText),
            )
        val localMessageId = ThreadLocalSendNextId.next(composerInstanceId)
        val localTimestampLabel = buildThreadPendingSendTimestampLabel(bridge, payload.nowEpochMillis)
        val localMessage =
            buildThreadPendingSendMessage(
                localMessageId = localMessageId,
                context = pendingContext,
                session = currentSession,
                timestampLabel = localTimestampLabel,
                resolvedQuotes = optimisticResolvedQuotes,
            )
        if (
            !threadViewModel.admitLocalSend(
                message = localMessage,
                expectedProvenance = sendAuthority.provenance,
                expectedRoute = sendAuthority.route,
                composerSeed =
                    ThreadLocalSendComposerSeed(
                        // What the composer held: the reference token was never in it.
                        sentBody = composedBody,
                        sentAttachmentIds = pendingContext.attachmentIds.toSet()
                    )
            )
        ) {
            bridge.logWarn("thread_composer_send_rejected reason=owner_changed_before_admission")
            return false
        }
        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        // The message took the reference (it is in the journaled body, retries included): the chip clears.
        if (assistantContextToken != null) assistantContextAttachment?.detach()
        // The journal row now owns crash protection for this send (restart adoption,
        // failed row with retry), so the persisted composer draft must be cleared at
        // admission — otherwise a mid-send process kill leaves a second copy of the same
        // text that reloads into the composer and invites a duplicate send (#1134 cell 3,
        // Argus ruling). Journal-write failure keeps the draft because admission already
        // returned false above. CompleteSend only clears the durable record; the send
        // completion below still dispatches its own CompleteSend for mention actions.
        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.CompleteSend())
        // The draft went out with its ready attachments: forget its uploads (the send gate let it
        // through only with none in flight).
        attachmentUploads.retireDraft(currentDraftState.draftKey, RaftAttachmentUploadCancelReason.DraftSent)
        // Before the request: the canonical echo may beat the HTTP response, and it must already
        // resolve these attachment ids to the local image instead of downloading it again.
        build.raft.app.image.RaftSentAttachmentLocalImages.registry.register(
            threadSentAttachmentLocalImageSources(
                attachments = currentDraftState.uploadedAttachments,
                attachmentIds = attachmentIds
            )
        )
        sendPendingLocalMessage(
            localMessageId = localMessageId,
            context = pendingContext,
            startedFromComposer = true,
            authority = sendAuthority
        )
        return true
    }
    fun handleComposerSendRequest(request: ThreadComposerSendRequest): ThreadComposerSendAdmission {
        if (threadLimitLocked) {
            bridge.log("thread_composer_action_blocked action=send reason=channel_limit_locked")
            return ThreadComposerSendAdmission.Rejected
        }
        val currentThreadState = threadViewModel.state.value
        val sendTargetChannelId = payload.composerSendTargetChannelId()
        val allowOptimisticLoadingSend =
            currentThreadState.allowsOptimisticSendWhileAccessLoading(
                sendChannelId = sendTargetChannelId,
                limitLocked = threadLimitLocked,
            )
        val canDeferWhileTargetOrAccessResolving =
            currentThreadState.canDeferOptimisticSendWhileAccessLoading(limitLocked = threadLimitLocked) ||
                (
                    !threadLimitLocked &&
                        !payload.channelArchived &&
                        payload.status != "error" &&
                        payload.status != "no_permission" &&
                        (
                            (sendTargetChannelId == null && payload.status == "loading") ||
                                (
                                    renderSlice.accessResolution == ThreadAccessResolution.Resolved &&
                                        !renderSlice.canonicalAccessResolved &&
                                        !renderSlice.writeActionsEnabled &&
                                        payload.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
                                        payload.joined
                                )
                        )
                )
        if ((!renderSlice.writeActionsEnabled && !allowOptimisticLoadingSend) || sendTargetChannelId == null) {
            if (canDeferWhileTargetOrAccessResolving) {
                composerDraftViewModel.dispatch(
                    SharedThreadComposerDraftIntent.Change(
                        seedText = request.value.text,
                        seedAsTask = request.asTask,
                    )
                )
                val hasSendableDraft =
                    request.value.text.isNotBlank() ||
                        composerDraftViewModel.state.value.uploadedAttachments.isNotEmpty()
                if (!hasSendableDraft) {
                    return ThreadComposerSendAdmission.Rejected
                }
                pendingOptimisticSendState.value = request
                bridge.log(
                    "thread_composer_send_deferred_for_access instanceId=${payload.instanceId} " +
                        "surface=${payload.surfaceKind} textLen=${request.value.text.length} " +
                        "sendChannelId=${sendTargetChannelId.orEmpty()}"
                )
                return ThreadComposerSendAdmission.Deferred
            }
            bridge.log(
                "thread_composer_action_blocked action=send reason=" +
                    if (sendTargetChannelId == null) "missing_send_channel" else "read_only_access_fallback"
            )
            return ThreadComposerSendAdmission.Rejected
        }
        if (externalShareSendRequested) {
            bridge.log("external_share_send_duplicate_drop reason=upload_chain_active")
            return ThreadComposerSendAdmission.Deferred
        }
        // Reconcile the explicit native tap-time snapshot before any host gate reads the draft.
        // This closes the window where UIKit already displays a final character while its normal
        // text-change callback is still queued for Compose.
        composerDraftViewModel.dispatch(
            SharedThreadComposerDraftIntent.Change(
                seedText = request.value.text,
                seedAsTask = request.asTask,
            )
        )
        val currentDraftState = composerDraftViewModel.state.value
        bridge.log(
            "thread_composer_send_snapshot session=${request.editorSessionId} " +
                "textLen=${request.value.text.length} textBlank=${request.value.text.isBlank()} " +
                "asTask=${request.asTask}"
        )
        val queuedExternalAttachments =
            currentDraftState.uploadedAttachments.filter { attachment ->
                attachment.source == "external_share" &&
                    attachment.status == "queued" &&
                    attachment.id !in currentDraftState.removedAttachmentIds
            }
        val externalShareSources = currentDraftState.externalShareSources
        if (externalShareSources.isNotEmpty()) {
            val pendingNow = currentComposerPayload().pendingAttachments
            if (pendingNow.hasRemovingThreadAttachment() || pendingNow.hasDiscardFailedThreadAttachment()) {
                bridge.log("thread_composer_send_gate result=blocked reason=attachment_discard_unresolved")
                return ThreadComposerSendAdmission.Rejected
            }
            externalShareSendRequested = true
            coroutineScope.launch {
                val expected = dataSource.captureExternalShareSendAuthority()
                val authorityMatches =
                    expected != null &&
                        externalShareSources.all { source ->
                            source.accountId == expected.accountId &&
                                source.serverId == expected.serverId
                        }
                if (!authorityMatches) {
                    externalShareSendRequested = false
                    externalShareExpectedAuthority = null
                    bridge.logWarn("external_share_send_rejected reason=authority_changed")
                    val crossServerNotice =
                        if (expected != null) {
                            resolveExternalShareCrossServerNotice(
                                session = currentSession,
                                externalShareSources = externalShareSources,
                                externalShareAttachments = emptyList(),
                                otherServerTemplate = labels.externalShareOtherServerTemplate,
                                otherServerGenericLabel = labels.externalShareOtherServerGenericLabel,
                            )
                        } else {
                            null
                        }
                    bridge.toast(crossServerNotice ?: genericThreadErrorLabel)
                    return@launch
                }
                externalShareExpectedAuthority = expected
                if (queuedExternalAttachments.isNotEmpty()) {
                    val files =
                        queuedExternalAttachments.mapNotNull { attachment ->
                            attachmentUploadFilesByLocalId.threadAttachmentUploadFile(
                                attachment.id,
                                normalizeThreadAttachmentUploadDraftKey(currentDraftState.draftKey)
                            )
                        }
                    if (files.size != queuedExternalAttachments.size) {
                        externalShareSendRequested = false
                        externalShareExpectedAuthority = null
                        bridge.logWarn("external_share_send_rejected reason=missing_file_ref")
                        bridge.toast(attachmentRetryUnavailableLabel)
                        return@launch
                    }
                    val uploadStarted = latestUploadComposerAttachmentFiles(files, null, false, emptyMap())
                    if (!uploadStarted) {
                        externalShareSendRequested = false
                        externalShareExpectedAuthority = null
                    }
                } else {
                    val stillCurrent = dataSource.matchesExternalShareSendAuthority(expected)
                    externalShareSendRequested = false
                    externalShareExpectedAuthority = null
                    if (stillCurrent) {
                        performComposerSend(request.optimisticResolvedQuotes)
                    } else {
                        bridge.logWarn(
                            "external_share_send_rejected reason=auth_generation_changed_before_send"
                        )
                    }
                }
            }
            return ThreadComposerSendAdmission.Deferred
        }
        return if (performComposerSend(request.optimisticResolvedQuotes)) {
            ThreadComposerSendAdmission.Started
        } else {
            ThreadComposerSendAdmission.Rejected
        }
    }
    fun openComposerImageAnnotation(files: List<PlatformFile>) {
        val firstFile = files.firstOrNull() ?: return
        val currentDraftState = composerDraftViewModel.state.value
        composerImageActionCounter += 1
        val actionId = "composer-image-${threadInstanceKey}-${composerImageActionCounter}-${firstFile.id.take(12)}"
        val opened =
            openTrackedComposerImagePreview(
                threadComposerImageAnnotationPayload(
                    threadInstanceKey = threadInstanceKey,
                    actionId = actionId,
                    files = files,
                    originalImage = currentDraftState.originalImage,
                ) ?: return
            )
        if (opened) composerKeyboardHandoff.pageOpened()
    }
    /**
     * A host files envelope from the attach buttons' picker or a drop on the composer
     * (`threadAttachmentDrop`): one admission path for both, so a dropped file is admitted, sized
     * and reported exactly like a picked one.
     */
    suspend fun admitComposerPickedAttachments(action: String, response: JSONObject?) {
        val files = parseSharedThreadPickedPlatformFiles(response)
        bridge.log(
            "thread_attachment_picker_result action=$action code=${response?.optInt("code", -1) ?: -1} " +
                "files=${files.size} errorCode=${response?.optString("errorCode").orEmpty()} " +
                "bodyBytes=${response?.optString("bodyText").orEmpty().encodeToByteArray().size}"
        )
        // Native generic pickers may finish a mixed selection with a readable
        // subset and a compression error (code 206).  Never upload that subset:
        // doing so would silently drop the failed image while the user believes
        // the whole selection was admitted.  Retire every ref exposed by the
        // envelope before returning, including refs from successful files and
        // any future error/fileRefs compatibility fields.
        if (threadAttachmentPickerHasCompressionFailure(response)) {
            val cleanupRefs = threadAttachmentPickerCompressionFailureFileRefs(response)
            if (cleanupRefs.isNotEmpty()) {
                bridge.retireFilePreviewTemporaryFiles(
                    sessionId = "thread-picker-compression-$threadInstanceKey",
                    fileRefs = cleanupRefs.toList()
                )
            }
            bridge.logWarn(
                "thread_attachment_picker_rejected reason=image_compression_failed " +
                    "cleanupRefs=${cleanupRefs.size}"
            )
            bridge.toast(
                localizedAttachmentUploadFailedDetail(
                    userFacingErrorLabels.forErrorCode(
                        RAFT_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE
                    ) ?: genericThreadErrorLabel
                )
            )
            return
        }
        if (files.isEmpty()) {
            val errorCode = response?.optString("errorCode")?.takeIf { it.isNotBlank() }
            when {
                errorCode != null ->
                    bridge.toast(
                        localizedAttachmentUploadFailedDetail(
                            userFacingErrorLabels.forErrorCode(errorCode) ?: genericThreadErrorLabel
                        )
                    )
                response == null ->
                    bridge.toast(attachmentPickerUnavailableLabel)
            }
            return
        }
        val skippedCount = response?.optInt("skippedCount", 0) ?: 0
        if (skippedCount > 0) {
            bridge.toast(
                if (skippedCount == 1) {
                    attachmentSkippedOneLabel
                } else {
                    localizedAttachmentSkippedMany(skippedCount)
                }
            )
        }
        when (
            resolveThreadPickedAttachmentComposerAction(
                action = action,
                fileCount = files.size,
                allFilesAreImages = raftComposerImagePickerFilesAreStrictImages(files),
            )
        ) {
            ThreadPickedAttachmentComposerAction.OpenImageAnnotation ->
                // The picker-reported bytes are the source image, not the final
                // editor output.  Defer the server-size gate until the confirmed
                // PNG/original or compressor-produced JPEG reaches upload.
                openComposerImageAnnotation(files)
            ThreadPickedAttachmentComposerAction.UploadToComposer -> latestUploadComposerAttachmentFiles(files, null, false, emptyMap())
            ThreadPickedAttachmentComposerAction.None -> Unit
        }
    }
    suspend fun refreshComposerMemberSuggestions(reason: String) {
        val store = serverMembersStore ?: return
        val serverId = threadMembersServerId ?: currentServerId?.let(::ServerId) ?: return
        bridge.log(
            "thread_composer_member_suggestions_refresh reason=$reason " +
                "serverId=${serverId.value} mentionChannelId=${memberSuggestionScopeChannelId.orEmpty()} " +
                "memberScoped=$memberSuggestionScopedMentions"
        )
        store.ensureMemberSuggestionsForServer(
            serverId = serverId,
            mentionChannelId = memberSuggestionScopeChannelId,
            memberScopedMentions = memberSuggestionScopedMentions,
            force = true,
            excludeOutsiderGuests = memberSuggestionExcludeOutsiderGuests,
        )
    }

    suspend fun refreshComposerMemberSuggestionsAfterMembershipMutation() {
        refreshComposerMemberSuggestions(reason = "membership_mutation")
    }

    fun onComposerEvent(action: String, value: String?, asTask: Boolean?, attachmentId: String?): Unit =
        run ThreadPageContent@{
                if (action == "send") {
                    val currentDraftState = composerDraftViewModel.state.value
                    handleComposerSendRequest(
                        ThreadComposerSendRequest(
                            editorSessionId = 0L,
                            value = TextFieldValue(value ?: currentDraftState.seedText),
                            asTask = asTask ?: currentDraftState.seedAsTask,
                        )
                    )
                    return@ThreadPageContent
                }
                if (!threadComposerEventAllowed(renderSlice.writeActionsEnabled && !threadLimitLocked, action)) {
                    bridge.log("thread_composer_action_blocked action=$action reason=${if (threadLimitLocked) "channel_limit_locked" else "read_only_access_fallback"}")
                    return@ThreadPageContent
                }
                val currentComposer = currentComposerPayload()
                val currentDraftState = composerDraftViewModel.state.value
                val composerInstanceId = currentComposer.instanceId.ifBlank { payload.instanceId }
                when (action) {
                "refreshMemberSuggestions" -> {
                    coroutineScope.launch {
                        refreshComposerMemberSuggestions(reason = "panel_open")
                    }
                }
                "refreshChannelSuggestions" -> {
                    bridge.log("thread_composer_channel_suggestions_refresh reason=panel_open")
                    membersStore?.dispatch(SharedMembersIntent.RefreshChannelSuggestions)
                }
                "staleChangeIgnored" -> {
                    bridge.log(
                        "thread_composer_stale_change_ignored instanceId=$composerInstanceId " +
                            "valueLen=${value?.length ?: -1} valueBlank=${value?.isBlank()} asTask=$asTask " +
                            "stateTextLen=${currentDraftState.seedText.length} stateTextBlank=${currentDraftState.seedText.isBlank()} " +
                            "stateAsTask=${currentDraftState.seedAsTask} draftKey=${currentDraftState.draftKey.orEmpty()}"
                    )
                }
                "sendSnapshotUnavailable" -> {
                    bridge.logWarn(
                        "thread_composer_send_snapshot_unavailable " +
                            "instanceId=$composerInstanceId reason=${value.orEmpty()}"
                    )
                    bridge.toast(genericThreadErrorLabel)
                }
                "staleEditorCommandIgnored" -> {
                    bridge.log(
                        "thread_composer_editor_command_ignored instanceId=$composerInstanceId " +
                            "reason=local_editor_advanced localTextLen=${value?.length ?: -1} localAsTask=$asTask"
                    )
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.Change(
                            seedText = value,
                            seedAsTask = asTask,
                        )
                    )
                }
                "change" -> {
                    bridge.log(
                        "thread_composer_change instanceId=$composerInstanceId " +
                            "valueLen=${value?.length ?: -1} valueBlank=${value?.isBlank()} asTask=$asTask " +
                            "stateTextLen=${currentDraftState.seedText.length} stateTextBlank=${currentDraftState.seedText.isBlank()} " +
                            "stateAsTask=${currentDraftState.seedAsTask} draftKey=${currentDraftState.draftKey.orEmpty()} " +
                            "surfaceSendChannelId=${composerSurface.sendChannelId.orEmpty()} " +
                            "surfaceParentMessageId=${composerSurface.parentMessageId.orEmpty()}"
                    )
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.Change(
                            seedText = value,
                            seedAsTask = asTask
                        )
                    )
                    val nextDraftState = composerDraftViewModel.state.value
                    bridge.log(
                        "thread_composer_change_result instanceId=$composerInstanceId " +
                            "textLen=${nextDraftState.seedText.length} textBlank=${nextDraftState.seedText.isBlank()} " +
                            "asTask=${nextDraftState.seedAsTask} draftKey=${nextDraftState.draftKey.orEmpty()} " +
                            "activeRecords=${composerDraftPersistSlice.activeDraftRecordsState.value.toThreadComposerDraftDiagnosticsSummary()}"
                    )
                }
                ThreadComposerSelectMentionEvent -> {
                    val mention = decodeThreadComposerMentionEvent(value)
                    bridge.log(
                        "thread_composer_select_mention instanceId=$composerInstanceId " +
                            "type=${mention?.type.orEmpty()} draftKey=${currentDraftState.draftKey.orEmpty()}"
                    )
                    mention?.let { composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.SelectMention(it)) }
                }
                "toggleAsTask" -> {
                    bridge.log(
                        "thread_composer_toggle_as_task instanceId=$composerInstanceId asTask=$asTask " +
                            "stateTextLen=${currentDraftState.seedText.length} stateTextBlank=${currentDraftState.seedText.isBlank()} " +
                            "draftKey=${currentDraftState.draftKey.orEmpty()}"
                    )
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.Change(seedAsTask = asTask)
                    )
                }
                "toggleOriginalImage" -> {
                    val enabled = asTask ?: !currentDraftState.originalImage
                    bridge.log(
                        "thread_composer_toggle_original_image instanceId=$composerInstanceId " +
                            "originalImage=$enabled draftKey=${currentDraftState.draftKey.orEmpty()}"
                    )
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.SetOriginalImage(enabled)
                    )
                }
                "send" -> {
                    handleComposerSendRequest(
                        ThreadComposerSendRequest(
                            editorSessionId = 0L,
                            value = TextFieldValue(value ?: currentDraftState.seedText),
                            asTask = asTask ?: currentDraftState.seedAsTask,
                        )
                    )
                }
                "dismissMention" -> {
                    value?.takeIf { it.isNotBlank() }?.let { resolutionId ->
                        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.DismissMentionAction(resolutionId))
                    }
                }
                "dismissMentionNotice" -> {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.ClearMentionActionNotice)
                }
                ThreadComposerDismissUnresolvedMentionsEvent -> {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.DismissUnresolvedMentions)
                }
                "addAllMentions" -> {
                    if (!pendingMentionActionBatchRequestGate.tryBegin()) return@ThreadPageContent
                    val eligibleActions =
                        eligiblePendingMentionActionBatch(
                            actions = currentDraftState.pendingMentionActions,
                            actionStates = currentDraftState.mentionActionStates,
                            removingIds = currentDraftState.removingMentionActionIds,
                            executingIds = currentDraftState.executingMentionActionIds
                        )
                    if (eligibleActions.size < 2) {
                        pendingMentionActionBatchRequestGate.finish()
                        return@ThreadPageContent
                    }
                    val resolutionIds = eligibleActions.map { it.resolutionId.trim() }
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.BeginMentionActionBatch(resolutionIds)
                    )
                    coroutineScope.launch {
                        try {
                            val requestAttempt =
                                runCatchingPreservingCancellation {
                                    dataSource.executePendingMentionActions(
                                        action = "add",
                                        resolutionIds = resolutionIds
                                    )
                                }
                            val requestResult = requestAttempt.getOrNull()
                            val requestFailureNotice =
                                localizedPendingMentionActionRequestFailure(
                                    result = requestResult,
                                    requestThrew = requestAttempt.isFailure,
                                    strings = pendingMentionActionBatchStrings
                                )
                            val results =
                                (requestResult as? ThreadApiResult.Success)?.data.orEmpty()
                            val outcome = pendingMentionActionBatchOutcome(resolutionIds, results)
                            composerDraftViewModel.dispatch(
                                SharedThreadComposerDraftIntent.FinishMentionActionBatch(
                                    requestedIds = outcome.requestedIds,
                                    deliveredIds = outcome.deliveredIds,
                                    noticeTitle = outcome.localizedNoticeTitle(pendingMentionActionBatchStrings),
                                    notice =
                                        requestFailureNotice
                                            ?: outcome.localizedFailureNotice(pendingMentionActionBatchStrings)
                                )
                            )
                            if (outcome.deliveredIds.isNotEmpty()) {
                                refreshComposerMemberSuggestionsAfterMembershipMutation()
                            }
                        } finally {
                            pendingMentionActionBatchRequestGate.finish()
                        }
                    }
                }
                "inviteMention", "notifyMention" -> {
                    val resolutionId = value?.takeIf { it.isNotBlank() } ?: return@ThreadPageContent
                    if (resolutionId in currentDraftState.executingMentionActionIds) {
                        return@ThreadPageContent
                    }
                    val pendingAction =
                        currentDraftState.pendingMentionActions.firstOrNull { it.resolutionId == resolutionId }
                            ?: return@ThreadPageContent
                    val verb = if (action == "inviteMention") "add" else "notify"
                    val localState = if (verb == "add") "added" else "notified"
                    composerDraftViewModel.dispatch(
                        SharedThreadComposerDraftIntent.BeginMentionAction(
                            resolutionId = resolutionId,
                            state = localState
                        )
                    )
                    coroutineScope.launch {
                        val requestAttempt =
                            runCatchingPreservingCancellation {
                                dataSource.executePendingMentionActions(
                                    action = verb,
                                    resolutionIds = listOf(resolutionId)
                                )
                            }
                        val result = requestAttempt.getOrNull()
                        val rowResult =
                            (result as? ThreadApiResult.Success)
                                ?.data
                                ?.firstOrNull { it.resolutionId == resolutionId }
                        val succeeded = mentionActionSucceeded(verb, rowResult)
                        val notice =
                            if (succeeded) {
                                val targetDisplayName =
                                    buildPendingMentionTargetMap(
                                        serverMembers =
                                            threadMembersServerId
                                                ?.let { serverMembersStore?.currentServerMembers(it) }
                                                .orEmpty(),
                                        channelMembers =
                                            if (serverMembersStore != null && threadMembersServerId != null) {
                                                composerSurface.mentionScopeChannelId
                                                    ?.let(::ChannelId)
                                                    ?.let { serverMembersStore.currentChannelMembers(threadMembersServerId, it) }
                                                    .orEmpty()
                                            } else {
                                                emptyList()
                                            },
                                        payloadMembers = payload.members,
                                        payloadMemberCandidates = payload.memberCandidates,
                                        jointActors =
                                            threadMembersServerId
                                                ?.let { serverMembersStore?.jointActors?.currentActors(it) }
                                                .orEmpty(),
                                    ).displayName(pendingAction)
                                pendingAction.mentionActionDeliveredNotice(
                                    action = verb,
                                    channelLabel = composerSurface.mentionScopeChannelLabel(),
                                    displayName = targetDisplayName,
                                )
                            } else {
                                localizedPendingMentionActionRequestFailure(
                                    result = result,
                                    requestThrew = requestAttempt.isFailure,
                                    strings = pendingMentionActionBatchStrings
                                ) ?: formatMentionActionStatusNotice(verb, rowResult, pendingMentionActionBatchStrings)
                            }
                        composerDraftViewModel.dispatch(
                            SharedThreadComposerDraftIntent.FinishMentionAction(
                                resolutionId = resolutionId,
                                notice = notice,
                                delivered = succeeded,
                                state = localState
                            )
                        )
                        if (succeeded && verb == "add") {
                            refreshComposerMemberSuggestionsAfterMembershipMutation()
                        }
                        if (succeeded) {
                            delay(ThreadComposerMentionActionFadeDelayMs)
                            composerDraftViewModel.dispatch(
                                SharedThreadComposerDraftIntent.MarkMentionActionRemoving(resolutionId)
                            )
                            delay(ThreadComposerMentionActionRemoveDelayMs - ThreadComposerMentionActionFadeDelayMs)
                            composerDraftViewModel.dispatch(
                                SharedThreadComposerDraftIntent.RemoveMentionAction(resolutionId)
                            )
                        }
                    }
                }
                ThreadComposerPasteAttachmentsAction -> {
                    // Desktop paste of files / an image: straight into the tray, as the web
                    // composer's paste does (no annotation editor). A copied image is compressed
                    // by the host (2048 px / JPEG 82, as editor output on mobile) unless the
                    // draft asks for the original; Finder files stay their own bytes, as a File
                    // pick does on mobile.
                    // iOS: pasted images open in the image editor, as picked photos do.
                    val pasteOriginalImage = currentDraftState.originalImage
                    // Images the host already read inside its paste action (no clipboard read here).
                    val hostPasted = ThreadComposerImagePasteRequests.takePending()
                    coroutineScope.launch {
                        if (payload.composerSendTargetChannelId() == null) {
                            bridge.toast(attachmentPickerUnavailableLabel)
                            return@launch
                        }
                        val files =
                            (hostPasted ?: withContext(Dispatchers.Default) { raftPlatformReadClipboardAttachments() })
                                .take(ThreadComposerImagePasteMaxImages.takeIf { raftPlatformPastedImagesOpenEditor() } ?: Int.MAX_VALUE)
                        bridge.log("thread_attachment_paste files=${files.size} host=${hostPasted != null}")
                        if (files.isEmpty()) return@launch
                        if (raftPlatformPastedImagesOpenEditor() && raftComposerImagePickerFilesAreStrictImages(files)) {
                            // The picked-photo path: the editor first, compressed after Done
                            // unless the draft asks for the original. The keyboard the paste
                            // came from goes down over the editor and comes back after it (dm #119).
                            composerKeyboardHandoff.leave(keyboardOpen = adaptivePageKeyboardInsetDp > 0f)
                            openComposerImageAnnotation(files)
                            composerKeyboardHandoff.settle()
                            return@launch
                        }
                        val prepared =
                            threadComposerCompressPastedImages(
                                files = files,
                                originalImage = pasteOriginalImage,
                                compress = { file ->
                                    val response =
                                        bridge.compressThreadImage(
                                            fileRef = file.fileRef.orEmpty(),
                                            fileName = file.fileName.ifBlank { "image.png" },
                                            mimeType = file.mimeType?.ifBlank { null } ?: "image/png",
                                        )
                                    (parseRaftFilePreviewCompressedImageResult(response)
                                        as? RaftFilePreviewCompressedImageResult.Success)?.file
                                },
                                retire = { refs ->
                                    bridge.retireFilePreviewTemporaryFiles(
                                        sessionId = "thread-paste-compression-$threadInstanceKey",
                                        fileRefs = refs
                                    )
                                },
                                log = bridge::log,
                            )
                        uploadComposerAttachmentFiles(prepared)
                    }
                }
                ThreadComposerAttachImageAction, ThreadComposerCaptureImageAction, "attachFile" -> {
                    // Freeze the session's image policy at picker admission.  The picker may
                    // complete after a recomposition (or a new toggle event), but its result must
                    // retain the policy that was visible when the user opened it.
                    val compressPickerImages =
                        shouldCompressThreadAttachmentPickerImages(
                            action = action,
                            originalImage = currentDraftState.originalImage
                        )
                    // A picked photo opens the image editor: the keyboard goes down before the
                    // picker, or coming back to the window raises it over the editor (dm #119).
                    // A file pick goes straight into the tray and keeps the keyboard as it is.
                    val opensImageEditor = action != "attachFile"
                    if (opensImageEditor) composerKeyboardHandoff.leave(keyboardOpen = adaptivePageKeyboardInsetDp > 0f)
                    coroutineScope.launch {
                        try {
                            val channelId = payload.composerSendTargetChannelId()
                            if (channelId == null) {
                                bridge.toast(attachmentPickerUnavailableLabel)
                                return@launch
                            }
                            val response =
                                bridge.threadAttachmentUpload(
                                    channelId = channelId,
                                    imageOnly = opensImageEditor,
                                    capture = action == ThreadComposerCaptureImageAction,
                                    // Image picker/camera results enter the annotation editor first.
                                    // Do not encode before the editor decision: picker JPEG -> editor
                                    // PNG -> JPEG would incur two lossy generations. Generic file
                                    // picks also stay uncompressed so a JPEG remains source bytes
                                    // and the shared size gate sees the real file.
                                    compressImages = compressPickerImages,
                                )
                            admitComposerPickedAttachments(action, response)
                        } finally {
                            // Cancelled, rejected or straight into the tray: back at the composer.
                            if (opensImageEditor) composerKeyboardHandoff.settle()
                        }
                    }
                }
                "removeAttachment" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        val draftState = composerDraftViewModel.state.value
                        val draftKey = normalizeThreadAttachmentUploadDraftKey(draftState.draftKey)
                        val targetServerId =
                            draftState.serverId?.trim()?.takeIf(String::isNotBlank)
                                ?: attachmentUploads.serverId.trim().takeIf(String::isNotBlank)
                                ?: currentServerId?.trim()?.takeIf(String::isNotBlank)
                                ?: currentSession?.currentServerId?.trim()?.takeIf(String::isNotBlank)
                        when (
                            val outcome =
                                dispatchThreadComposerAttachmentRemoval(
                                    attachmentId = id,
                                    currentState = draftState,
                                    attachmentUploads = attachmentUploads,
                                    dispatch = composerDraftViewModel::dispatch,
                                    discardCall = { serverAttachmentId ->
                                        dataSource.discardDraftAttachment(
                                            serverId = targetServerId,
                                            attachmentId = serverAttachmentId,
                                        )
                                    },
                                    onQuotaRefresh = {
                                        dataSource.refreshAttachmentUploadCapabilities()
                                    },
                                )
                        ) {
                            is ThreadComposerAttachmentRemovalDispatchResult.Rejected -> {
                                bridge.logWarn(
                                    "thread_composer_attachment_remove_rejected attachmentId=$id reason=${outcome.reason}"
                                )
                                return@ThreadPageContent
                            }
                            ThreadComposerAttachmentRemovalDispatchResult.DiscardAlreadyInFlight -> {
                                bridge.log(
                                    "thread_composer_attachment_discard_skipped attachmentId=$id reason=in_flight"
                                )
                                return@ThreadPageContent
                            }
                            is ThreadComposerAttachmentRemovalDispatchResult.DiscardStarted -> {
                                bridge.log(
                                    "thread_composer_attachment_discard_started attachmentId=${outcome.attachmentId} attempt=${outcome.attemptToken}"
                                )
                                return@ThreadPageContent
                            }
                            is ThreadComposerAttachmentRemovalDispatchResult.RemovedLocally -> {
                                attachmentUploadFilesByLocalId =
                                    attachmentUploadFilesByLocalId.withoutThreadAttachmentUploadFile(outcome.attachmentId, draftKey)
                                if (outcome.fileRefToRetire != null) {
                                    bridge.retireFilePreviewTemporaryFiles(
                                        sessionId = "thread-composer-${draftKey.orEmpty()}",
                                        fileRefs = listOf(outcome.fileRefToRetire)
                                    )
                                }
                            }
                        }
                    }
                }
                ThreadComposerReorderAttachmentsAction ->
                    composerDraftViewModel.reorderThreadComposerAttachments(value, bridge::log)
                "retryAttachments" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        val existingRow =
                            composerDraftViewModel.state.value.uploadedAttachments.firstOrNull { it.id == id }
                        if (existingRow != null && existingRow.isCompletedServerDraftAttachment()) {
                            bridge.log(
                                "thread_composer_attachment_retry result=not_upload_retryable attachmentId=$id status=${existingRow.status}"
                            )
                            return@ThreadPageContent
                        }
                        val draftKey = normalizeThreadAttachmentUploadDraftKey(composerDraftViewModel.state.value.draftKey)
                        if (draftKey == null) {
                            bridge.log("thread_composer_attachment_retry result=missing_draft_key attachmentId=$id")
                            bridge.toast(attachmentRetryUnavailableLabel)
                            return@ThreadPageContent
                        }
                        val file = attachmentUploadFilesByLocalId.threadAttachmentUploadFile(id, draftKey)
                        if (file == null) {
                            bridge.log(
                                "thread_composer_attachment_retry result=missing_file_ref attachmentId=$id"
                            )
                            bridge.toast(attachmentRetryUnavailableLabel)
                            return@ThreadPageContent
                        }
                        if (attachmentUploadOrchestrator.isActive(id, draftKey)) {
                            bridge.log(
                                "thread_composer_attachment_retry result=already_uploading attachmentId=$id"
                            )
                            return@ThreadPageContent
                        }
                        bridge.log(
                            "thread_composer_attachment_retry result=start attachmentId=$id fileName=${file.fileName}"
                        )
                        // Preserve an editor's remote replacement slot across a failed attempt.
                        // RetryAttachment clears the old admission so the next AddLocal mint gets
                        // a fresh generation; capture the slot before that clear, otherwise a
                        // successful retry would append beside the original remote row.
                        val replacementSlot =
                            composerDraftViewModel.state.value.attachmentAdmissionsByLocalId[id]
                                ?.replacementAttachmentId
                                ?.trim()
                                ?.takeIf { it.isNotBlank() && it != id }
                        composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.RetryAttachment(id))
                        coroutineScope.launch {
                            uploadComposerAttachmentFiles(
                                files = listOf(file),
                                replaceAttachmentId = replacementSlot
                            )
                        }
                    }
                }
                "previewAttachment" -> {
                    attachmentId?.takeIf { it.isNotBlank() }?.let { id ->
                        // The tile opens its preview or the image editor: the keyboard goes down
                        // first and comes back when that page closes (dm #119).
                        composerKeyboardHandoff.leave(keyboardOpen = adaptivePageKeyboardInsetDp > 0f)
                        if (openComposerAttachmentPreview(id)) composerKeyboardHandoff.pageOpened()
                        composerKeyboardHandoff.settle()
                    }
                }
                "blur" -> {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.Flush)
                    bridge.log(
                        "thread_composer_blur instanceId=$composerInstanceId"
                    )
                }
                else -> {
                    bridge.log(
                        "thread_composer_event_unsupported instanceId=$composerInstanceId action=$action attachmentId=${attachmentId.orEmpty()}"
                    )
                }
                }
        }
}

internal fun resolveExternalShareCrossServerNotice(
    session: StoredAuthSession?,
    externalShareSources: List<SharedThreadComposerExternalShareSource>,
    externalShareAttachments: List<RaftPendingAttachmentItem>,
    otherServerTemplate: String,
    otherServerGenericLabel: String,
): String? {
    val activeSession = session ?: return null
    val activeUserId = activeSession.user.id.takeIf { it.isNotBlank() } ?: return null
    val activeServerId = activeSession.currentServerId?.takeIf { it.isNotBlank() } ?: return null
    val sourceAccountMismatch = externalShareSources.any { it.accountId != activeUserId }
    val attachmentAccountMismatch = externalShareAttachments.any { it.externalShareAccountId != activeUserId }
    if (sourceAccountMismatch || attachmentAccountMismatch) return null
    val foreignServerId =
        externalShareSources.firstOrNull { it.serverId.isNotBlank() && it.serverId != activeServerId }?.serverId
            ?: externalShareAttachments
                .firstOrNull { !it.externalShareServerId.isNullOrBlank() && it.externalShareServerId != activeServerId }
                ?.externalShareServerId
            ?: return null
    val targetServerName =
        activeSession.servers
            .firstOrNull { it.id == foreignServerId }
            ?.name
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    return if (targetServerName != null) {
        otherServerTemplate.replace(ThreadLocalizedFeedbackPlaceholder, targetServerName)
    } else {
        otherServerGenericLabel
    }
}
