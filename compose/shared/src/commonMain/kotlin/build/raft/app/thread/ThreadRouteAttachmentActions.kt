package build.raft.app

import build.raft.app.base.reduceAttachmentDownloadProgress
import build.raft.app.data.PlatformFile
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.failureTraceIdOrNull
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentCommentItem
import build.raft.app.data.ThreadAttachmentCommentsPage
import build.raft.app.data.normalizedOrNull
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.desc
import build.raft.app.resources.resolve
import build.raft.app.compat.pager.Pager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Retires page-local attachment operation tokens when the active owner changes or when a reused
 * wide-pane thread route switches to another [threadInstanceKey], so an in-flight download started
 * in Channel A cannot mutate Channel A's stale state or fire a toast over Channel B.
 * Kept out of `SharedThreadRouteContentContent` to stay below its JVM method-size ceiling.
 */
@Composable
internal fun ThreadAttachmentOperationLifecycleEffects(
    threadInstanceKey: String,
    attachmentOwner: AttachmentOwnerLease?,
    attachmentOpenStatesHolder: MutableState<Map<String, ThreadAttachmentOpenUiState>>,
    attachmentOperationTokensState: MutableState<Map<String, AttachmentOperationToken>>,
) {
    LaunchedEffect(attachmentOwner?.scopeId, attachmentOwner?.generation, threadInstanceKey) {
        val currentOwner = attachmentOwner
        val hasForeign = attachmentOperationTokensState.value.values.any { it.owner != currentOwner }
        if (hasForeign) {
            attachmentOperationTokensState.value = emptyMap()
            attachmentOpenStatesHolder.value = emptyMap()
        }
    }
    DisposableEffect(threadInstanceKey, attachmentOperationTokensState) {
        onDispose {
            attachmentOperationTokensState.value = emptyMap()
        }
    }
}

/**
 * The route's attachment open and comment actions: what used to be local functions of
 * `SharedThreadRouteContentContent` (the comment sheet's load / send / resolve, the image preview and
 * lightbox, open / save outside the app, the file page, the inline audio / video / image-fallback
 * requests and the composer tile's preview), moved verbatim.
 *
 * Built every composition from the values those functions captured (states as their [State] /
 * [MutableState], read and written when an action runs, as the functions did); never remembered.
 * The route's own local functions the actions call ([routeAuthRequired], [currentComposerPayload])
 * are function values behind members of the same signature.
 */
internal class ThreadRouteAttachmentActions(
    val bridge: build.raft.app.base.BridgeModule,
    val dataSource: SharedThreadDataSource,
    val pager: Pager,
    val coroutineScope: CoroutineScope,
    val threadViewModel: SharedThreadViewModel,
    val composerDraftViewModel: SharedThreadComposerDraftViewModel,
    val renderedTimelineSlice: ThreadTimelineSlice,
    val threadInstanceKey: String,
    val attachmentOwner: AttachmentOwnerLease?,
    val routeOwnerProvider: () -> build.raft.app.data.AuthenticatedRouteOwner? = { null },
    val inlineImageUrlBatcher: ThreadInlineAttachmentUrlBatcher? = null,
    val labels: ThreadRouteLabels,
    val imageLightboxEnabled: Boolean,
    val attachmentCommentsDialogStateHolder: MutableState<ThreadAttachmentCommentsDialogState?>,
    val inlineImageFallbackStateHolder: MutableState<ThreadInlineImageFallbackState>,
    val imageLightboxRequestState: MutableState<RaftImageLightboxRequest?>,
    val attachmentOpenStatesHolder: MutableState<Map<String, ThreadAttachmentOpenUiState>>,
    val attachmentOperationTokensState: MutableState<Map<String, AttachmentOperationToken>>,
    val nextAttachmentDownloadGenerationState: MutableState<Long>,
    val inlineVideoPreviewStatesHolder: MutableState<Map<String, ThreadInlineVideoPreviewUiState>>,
    val inlineVideoPreviewJobs: MutableMap<String, Job>,
    val inlineVideoPreviewRetryNudges: MutableSharedFlow<String?>,
    val inlineAudioPreviewStatesHolder: MutableState<Map<String, ThreadInlineAudioPreviewUiState>>,
    val attachmentUploadFilesByLocalIdState: State<Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile>>,
    val composerImageActionCounterState: MutableState<Int>,
    val composerImageEventSessionTracker: ThreadComposerImageEventSessionTracker,
    val routeAuthRequiredForCodeFn: (String?) -> Boolean,
    val routeAuthRequiredFn: (DataResult.NoPermission) -> Boolean,
    val currentComposerPayloadFn: () -> RaftThreadComposerPayload,
) {
    var attachmentCommentsDialogState by attachmentCommentsDialogStateHolder
    var inlineImageFallbackState by inlineImageFallbackStateHolder
    var imageLightboxRequest by imageLightboxRequestState
    var attachmentOpenStates by attachmentOpenStatesHolder
    var attachmentOperationTokens by attachmentOperationTokensState
    var nextAttachmentDownloadGeneration by nextAttachmentDownloadGenerationState
    var inlineVideoPreviewStates by inlineVideoPreviewStatesHolder
    var inlineAudioPreviewStates by inlineAudioPreviewStatesHolder
    val attachmentUploadFilesByLocalId by attachmentUploadFilesByLocalIdState
    var composerImageActionCounter by composerImageActionCounterState
    val commentsUnavailableLabel = labels.commentsUnavailableLabel
    val unableToSendCommentLabel = labels.unableToSendCommentLabel
    val unableToResolveCommentLabel = labels.unableToResolveCommentLabel
    val unableToOpenAttachmentLabel = labels.unableToOpenAttachmentLabel
    val unableToDownloadAttachmentLabel = labels.unableToDownloadAttachmentLabel
    val openAttachmentActionLabel = labels.openAttachmentActionLabel
    val attachmentOpenResultTerms = labels.attachmentOpenResultTerms
    val attachmentSavedToDownloadsLabel = labels.attachmentSavedToDownloadsLabel
    val attachmentSavedToFilesLabel = labels.attachmentSavedToFilesLabel
    val attachmentSavedToPhotosLabel = labels.attachmentSavedToPhotosLabel
    val attachmentDownloadedLabel = labels.attachmentDownloadedLabel
    val attachmentSavedLabel =
        raftFilePreviewSavedMessage(
            exportPrivateDownload = false,
            toPhotos = false,
            supportsPublicDownloadReadback = raftPlatformSupportsPublicAttachmentDownloadReadback(),
            supportsPrivateAttachmentExport = raftPlatformSupportsPrivateAttachmentExport(),
            downloadSaved = attachmentSavedToDownloadsLabel,
            privateDownloadSaved = attachmentDownloadedLabel,
            fileExportSaved = attachmentSavedToFilesLabel,
            imageSavedToPhotos = attachmentSavedToPhotosLabel
        )
    val attachmentUploadFailedLabel = labels.attachmentUploadFailedLabel
    val preparingAttachmentPreviewLabel = labels.preparingAttachmentPreviewLabel

    fun routeAuthRequired(code: String? = null): Boolean = routeAuthRequiredForCodeFn(code)

    fun routeAuthRequired(result: DataResult.NoPermission): Boolean = routeAuthRequiredFn(result)

    fun currentComposerPayload(): RaftThreadComposerPayload = currentComposerPayloadFn()

    fun openTrackedComposerImagePreview(payload: RaftFilePreviewPayload): Boolean {
        var registeredSessionId = ""
        var registrationRejected = false
        val opened =
            pager.openRaftFilePreviewPage(
                payload,
                onEphemeralSessionRegistered = { sessionId ->
                    registeredSessionId = sessionId
                    val accepted = registerThreadComposerImagePreviewSession(
                        sessionId = sessionId,
                        tracker = composerImageEventSessionTracker,
                        abort = { rejectedSessionId ->
                            registrationRejected = true
                            // The preview opener may race page disposal between registry admission
                            // and its callback. Retire the process-owned row immediately; returning
                            // false alone is not enough if the opener has already crossed its open
                            // boundary.
                            val cleanupRefs = raftFilePreviewAbortComposerImageEventSession(rejectedSessionId)
                            if (cleanupRefs.isNotEmpty()) {
                                bridge.retireFilePreviewTemporaryFiles(
                                    sessionId = rejectedSessionId,
                                    fileRefs = cleanupRefs.toList()
                                )
                            }
                        }
                    )
                    accepted
                }
            )
        if ((registrationRejected || !opened) && registeredSessionId.isNotBlank()) {
            // The preview opener retires its registry/ledger on a failed route open; this page
            // still owns the local tracker witness and must release it as well.
            composerImageEventSessionTracker.release(registeredSessionId)
        }
        if (opened && registeredSessionId.isNotBlank() &&
            composerImageEventSessionTracker.isClosed() &&
            !composerImageEventSessionTracker.owns(registeredSessionId) &&
            !composerImageEventSessionTracker.isHandoffStarted(registeredSessionId)
        ) {
            // Disposal can close the tracker immediately after the opener callback returns but
            // before the router reports success. Do a second ownership check after the boundary
            // so a successfully opened preview can never retain an ownerless registry row.
            val cleanupRefs = raftFilePreviewAbortComposerImageEventSession(registeredSessionId)
            if (cleanupRefs.isNotEmpty()) {
                bridge.retireFilePreviewTemporaryFiles(
                    sessionId = registeredSessionId,
                    fileRefs = cleanupRefs.toList()
                )
            }
            composerImageEventSessionTracker.release(registeredSessionId)
            return false
        }
        return opened
    }

    // Attachment comments are on for every server (web, end of June); the server still decides who may comment.
    val attachmentCommentsEnabled = true
    fun threadWritesEnabledNow(): Boolean = threadViewModel.state.value.writeActionsEnabled

    fun setAttachmentCommentsState(next: ThreadAttachmentCommentsDialogState) {
        attachmentCommentsDialogState =
            attachmentCommentsDialogState
                ?.takeIf { current -> current.attachment.id == next.attachment.id }
                ?.let { next }
    }

    fun loadAttachmentCommentsIntoDialog(state: ThreadAttachmentCommentsDialogState) {
        coroutineScope.launch {
            val nextState =
                when (val result = dataSource.loadAttachmentComments(state.attachment.id)) {
                    is DataResult.Content ->
                        state.copy(
                            status = "loaded",
                            page = result.data,
                            sending = false,
                            resolvingCommentIds = emptySet()
                        ).clearAttachmentCommentsError()
                    is DataResult.Empty ->
                        state.copy(
                            status = "loaded",
                            page = ThreadAttachmentCommentsPage(comments = emptyList(), threadChannelId = null, viewer = null),
                            sending = false,
                            resolvingCommentIds = emptySet()
                        ).clearAttachmentCommentsError()
                    is DataResult.Error ->
                        state.copy(
                            status = "error",
                            sending = false,
                            resolvingCommentIds = emptySet()
                        ).withAttachmentCommentsError(
                            errorCode = result.error.code,
                            serverMessage = result.error.serverMessage,
                            traceId = result.error.traceId
                        )
                    is DataResult.NoPermission -> {
                        if (routeAuthRequired(result)) return@launch
                        state.copy(
                            status = "error",
                            sending = false,
                            resolvingCommentIds = emptySet()
                        ).withAttachmentCommentsError(
                            errorCode = result.blocker.code,
                            serverMessage = null,
                            traceId = result.blocker.traceId
                        )
                    }
                }
            setAttachmentCommentsState(nextState)
        }
    }

    fun openAttachmentComments(attachmentId: String) {
        if (!threadWritesEnabledNow() || !attachmentCommentsEnabled) return
        val target = renderedTimelineSlice.findAttachmentCommentTarget(attachmentId)
        if (target == null) {
            bridge.toast(commentsUnavailableLabel)
            return
        }
        if (!target.attachment.supportsThreadAttachmentComments()) return
        target.attachment.toThreadAttachmentCommentsPreviewPayload(attachmentCommentsEnabled)?.let { previewPayload ->
            pager.openRaftFilePreviewPage(previewPayload)
            return
        }
        val state =
            ThreadAttachmentCommentsDialogState(
                attachment = target.attachment,
                status = "loading",
                pendingAnchorLabel = target.attachment.name
            )
        attachmentCommentsDialogState = state
        loadAttachmentCommentsIntoDialog(state)
    }

    fun reloadAttachmentComments() {
        val current = attachmentCommentsDialogState ?: return
        val state = current.copy(status = "loading").clearAttachmentCommentsError()
        attachmentCommentsDialogState = state
        loadAttachmentCommentsIntoDialog(state)
    }

    fun sendAttachmentComment() {
        if (!threadWritesEnabledNow()) {
            attachmentCommentsDialogState = null
            return
        }
        val current = attachmentCommentsDialogState ?: return
        val cleanDraft = current.draft.text.trim()
        if (cleanDraft.isBlank() || current.sending) return
        attachmentCommentsDialogState = current.copy(sending = true).clearAttachmentCommentsError()
        coroutineScope.launch {
            when (
                val result =
                    runThreadWriteActionIfEnabled(
                        writeActionsEnabled = ::threadWritesEnabledNow,
                        blockedCode = "thread_attachment_comment_write_disabled"
                    ) {
                        dataSource.sendAttachmentComment(current.attachment.id, cleanDraft)
                    }
            ) {
                is DataResult.Content -> {
                    val loadingState =
                        current.withAttachmentCommentDraftProjection("").copy(
                            status = "loading",
                            sending = false,
                            pendingQuoteLabel = null
                        ).clearAttachmentCommentsError()
                    attachmentCommentsDialogState = loadingState
                    loadAttachmentCommentsIntoDialog(loadingState)
                }
                is DataResult.Empty,
                is DataResult.Error -> {
                    if ((result as? DataResult.Error)?.error?.code == "thread_attachment_comment_write_disabled") {
                        attachmentCommentsDialogState = null
                        return@launch
                    }
                    val code = (result as? DataResult.Error)?.error?.code ?: "thread_attachment_comment_send_failed"
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(sending = false)
                            ?.withAttachmentCommentsError(
                                errorCode = code,
                                serverMessage = (result as? DataResult.Error)?.error?.serverMessage,
                                traceId = result.failureTraceIdOrNull()
                            )
                    bridge.errorToast(unableToSendCommentLabel, result)
                }
                is DataResult.NoPermission -> {
                    if (routeAuthRequired(result)) return@launch
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(sending = false)
                            ?.withAttachmentCommentsError(
                                errorCode = result.blocker.code,
                                serverMessage = null,
                                traceId = result.blocker.traceId
                            )
                    bridge.errorToast(unableToSendCommentLabel, result.blocker)
                }
            }
        }
    }

    fun resolveAttachmentComment(comment: ThreadAttachmentCommentItem) {
        if (!threadWritesEnabledNow()) {
            attachmentCommentsDialogState = null
            return
        }
        val current = attachmentCommentsDialogState ?: return
        if (comment.id.isBlank() || comment.isThreadAttachmentCommentResolved()) return
        if (comment.id in current.resolvingCommentIds) return
        attachmentCommentsDialogState =
            current.copy(resolvingCommentIds = current.resolvingCommentIds + comment.id)
                .clearAttachmentCommentsError()
        coroutineScope.launch {
            when (
                val result =
                    runThreadWriteActionIfEnabled(
                        writeActionsEnabled = ::threadWritesEnabledNow,
                        blockedCode = "thread_attachment_comment_write_disabled"
                    ) {
                        dataSource.setMessageReaction(comment.id, "✅", selected = true)
                    }
            ) {
                is DataResult.Content -> {
                    val loadingState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.copy(status = "loading", resolvingCommentIds = emptySet())
                            ?.clearAttachmentCommentsError()
                            ?: return@launch
                    attachmentCommentsDialogState = loadingState
                    loadAttachmentCommentsIntoDialog(loadingState)
                }
                is DataResult.Empty,
                is DataResult.Error -> {
                    if ((result as? DataResult.Error)?.error?.code == "thread_attachment_comment_write_disabled") {
                        attachmentCommentsDialogState = null
                        return@launch
                    }
                    val code = (result as? DataResult.Error)?.error?.code ?: "thread_attachment_comment_resolve_failed"
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.let { state ->
                                state.copy(
                                    resolvingCommentIds = state.resolvingCommentIds - comment.id
                                ).withAttachmentCommentsError(
                                    errorCode = code,
                                    serverMessage = (result as? DataResult.Error)?.error?.serverMessage,
                                    traceId = result.failureTraceIdOrNull()
                                )
                            }
                    bridge.errorToast(unableToResolveCommentLabel, result)
                }
                is DataResult.NoPermission -> {
                    if (routeAuthRequired(result)) return@launch
                    attachmentCommentsDialogState =
                        attachmentCommentsDialogState
                            ?.takeIf { it.attachment.id == current.attachment.id }
                            ?.let { state ->
                                state.copy(
                                    resolvingCommentIds = state.resolvingCommentIds - comment.id
                                ).withAttachmentCommentsError(
                                    errorCode = result.blocker.code,
                                    serverMessage = null,
                                    traceId = result.blocker.traceId
                                )
                            }
                    bridge.errorToast(unableToResolveCommentLabel, result.blocker)
                }
            }
        }
    }

    fun inlineVideoPreviewHost(): ThreadInlineVideoPreviewHost =
        ThreadInlineVideoPreviewHost(
            states = inlineVideoPreviewStates,
            requestPreview = ::requestInlineVideoPreview,
            peekCachedUrl = ::peekCachedInlineAttachmentUrl,
        )

    fun inlineAudioPreviewHost(): ThreadInlineAudioPreviewHost =
        ThreadInlineAudioPreviewHost(
            states = inlineAudioPreviewStates,
            requestPreview = ::requestInlineAudioPreview,
            peekCachedUrl = ::peekCachedInlineAttachmentUrl,
        )

    fun inlineImageFallbackHost(): ThreadInlineImageFallbackHost =
        ThreadInlineImageFallbackHost(
            state = inlineImageFallbackState,
            requestFallback = ::requestInlineImageFallback,
            onFallbackError = ::handleInlineImageFallbackError,
            retryFallback = ::retryInlineImageFallback,
            peekCachedUrl = ::peekCachedInlineImageUrl,
        )

    fun peekCachedInlineAttachmentUrl(attachmentId: String): String? {
        val cleanId = attachmentId.trim()
        if (cleanId.isEmpty()) return null
        val owner = routeOwnerProvider()?.normalizedOrNull() ?: return null
        return raftFilePreviewAttachmentUrlCache()
            .get(RaftFilePreviewAttachmentUrlCacheKey(owner, cleanId, "inline"))
    }

    fun peekCachedInlineImageUrl(attachmentId: String): String? =
        inlineImageUrlBatcher?.peekCachedUrl(attachmentId) ?: peekCachedInlineAttachmentUrl(attachmentId)

    fun requestInlineImageFallback(attachmentId: String) {
        val batcher = inlineImageUrlBatcher
        if (batcher != null) {
            batcher.request(attachmentId)
            return
        }
        val cleanId = attachmentId.trim()
        if (cleanId.isEmpty()) return
        peekCachedInlineAttachmentUrl(cleanId)?.let { cachedUrl ->
            val (begun, _) = inlineImageFallbackState.beginRequest(cleanId)
            inlineImageFallbackState = begun.resolve(cleanId, cachedUrl)
            bridge.log("thread_inline_image_fallback status=cache_hit attachmentId=${cleanId.take(8)}")
            return
        }
        val (nextState, shouldRequest) = inlineImageFallbackState.beginRequest(cleanId)
        inlineImageFallbackState = nextState
        if (!shouldRequest) return
        val startOwner = routeOwnerProvider()?.normalizedOrNull()
        bridge.log("thread_inline_image_fallback status=start attachmentId=${cleanId.take(8)}")
        coroutineScope.launch {
            val result =
                runCatchingPreservingCancellation {
                    dataSource.attachmentUrl(DataRequestPriority.Visible, cleanId, disposition = "inline")
                }
            val response =
                result.getOrElse { throwable ->
                    inlineImageFallbackState = inlineImageFallbackState.markFailed(cleanId)
                    bridge.log(
                        "thread_inline_image_fallback status=exception attachmentId=${cleanId.take(8)} " +
                            "reason=${throwable::class.simpleName ?: "unknown"}"
                    )
                    return@launch
                }
            when (response) {
                is ThreadApiResult.Success -> {
                    val resolvedUrl = response.data.url.trim()
                    if (resolvedUrl.isNotEmpty()) {
                        val currentOwner = routeOwnerProvider()?.normalizedOrNull()
                        if (startOwner != null && currentOwner == startOwner) {
                            raftFilePreviewAttachmentUrlCache().put(
                                key = RaftFilePreviewAttachmentUrlCacheKey(startOwner, cleanId, "inline"),
                                url = resolvedUrl,
                                expiresAtEpochMillis = parseRaftTimestampEpochMillisOrNull(response.data.expiresAt),
                            )
                        }
                        inlineImageFallbackState =
                            inlineImageFallbackState.resolve(cleanId, resolvedUrl)
                        bridge.log("thread_inline_image_fallback status=resolved attachmentId=${cleanId.take(8)}")
                    } else {
                        inlineImageFallbackState = inlineImageFallbackState.markFailed(cleanId)
                        bridge.log("thread_inline_image_fallback status=empty_url attachmentId=${cleanId.take(8)}")
                    }
                }
                is ThreadApiResult.Failure -> {
                    inlineImageFallbackState = inlineImageFallbackState.markFailed(cleanId)
                    bridge.log(
                        "thread_inline_image_fallback status=failed attachmentId=${cleanId.take(8)} " +
                            "code=${response.code} contractCode=${response.contractCode.orEmpty()}"
                    )
                }
            }
        }
    }

    fun handleInlineImageFallbackError(attachmentId: String, failedUrl: String) {
        inlineImageUrlBatcher?.invalidateAndRetry(attachmentId, failedUrl)
    }

    fun retryInlineImageFallback(attachmentId: String) {
        val batcher = inlineImageUrlBatcher
        if (batcher != null) {
            batcher.retry(attachmentId)
            return
        }
        val cleanId = attachmentId.trim()
        if (cleanId.isEmpty()) return
        routeOwnerProvider()?.normalizedOrNull()?.let { owner ->
            raftFilePreviewAttachmentUrlCache()
                .invalidate(RaftFilePreviewAttachmentUrlCacheKey(owner, cleanId, "inline"))
        }
        inlineImageFallbackState = inlineImageFallbackState.invalidate(cleanId)
        requestInlineImageFallback(cleanId)
    }

    private fun currentInlineImageUrlsFor(attachments: Collection<RaftThreadAttachmentItem>): Map<String, String> {
        val owner = routeOwnerProvider()?.normalizedOrNull()
        val urlCache = raftFilePreviewAttachmentUrlCache()
        val resolved = inlineImageFallbackState.resolvedUrls
        return buildMap {
            attachments.forEach { item ->
                val id = item.id.trim()
                if (id.isEmpty()) return@forEach
                val cached = owner?.let { urlCache.get(RaftFilePreviewAttachmentUrlCacheKey(it, id, "inline")) }
                val inlineUrl = cached ?: resolved[id]
                if (!inlineUrl.isNullOrBlank()) put(id, inlineUrl)
            }
        }
    }
    /**
     * Desktop and wide windows open images in the lightbox over the app (artin 10-29); phones
     * keep the full-page viewer. [conversationWide]: step through every loaded image of the
     * conversation (a click in the timeline) rather than only [group] (the Files tab's list).
     */
    fun openImagePreview(group: ThreadImageAttachmentGroup, conversationWide: Boolean = true) {
        if (group.images.isEmpty()) return
        val currentIndex = group.currentIndex.coerceIn(0, group.images.lastIndex)
        val attachment = group.images[currentIndex]
        val inlineUrlsById = currentInlineImageUrlsFor(group.images)
        if (imageLightboxEnabled) {
            val baseRequest =
                (if (conversationWide) {
                    raftImageLightboxRequestFor(
                        messages = listOfNotNull(renderedTimelineSlice.originMessage) + renderedTimelineSlice.messages,
                        attachmentId = attachment.id,
                        isImage = { it.isImageAttachment() }
                    )
                } else {
                    null
                }) ?: RaftImageLightboxRequest(group.images.map { RaftImageLightboxItem(it) }, currentIndex)
            val allInlineUrls = currentInlineImageUrlsFor(baseRequest.items.map { it.attachment })
            imageLightboxRequest =
                baseRequest.copy(
                    items =
                        baseRequest.items.map { item ->
                            item.copy(inlinePlaceholderUrl = allInlineUrls[item.attachment.id] ?: item.inlinePlaceholderUrl)
                        }
                )
            bridge.log("thread_image_lightbox status=open count=${imageLightboxRequest?.items?.size ?: 0}")
            return
        }
        RaftViewerImageEditOpener.threadKey = threadInstanceKey
        pager.openRaftFilePreviewPage(
            attachment.toThreadAttachmentLazyPreviewPayload(
                kind = "image",
                imageGallery = group.images,
                imageGalleryIndex = currentIndex,
                attachmentCommentsEnabled = attachmentCommentsEnabled,
                inlineImageUrlsByAttachmentId = inlineUrlsById,
            )
        )
    }
    fun openAttachmentExternally(attachment: RaftThreadAttachmentItem, share: Boolean = false) {
        if (attachment.id.isBlank() || attachmentOpenStates[attachment.id].threadAttachmentIsOpening()) return
        val owner = attachmentOwner ?: return
        nextAttachmentDownloadGeneration += 1L
        val downloadId = attachment.threadAttachmentDownloadId(nextAttachmentDownloadGeneration)
        val operation = AttachmentOperationToken(owner, downloadId, downloadId)
        attachmentOperationTokens = attachmentOperationTokens + (attachment.id to operation)
        attachmentOpenStates =
            attachmentOpenStates +
                (attachment.id to ThreadAttachmentOpenUiState(
                    status = "downloading",
                    downloadId = downloadId,
                    progress = RaftFilePreviewInitialDownloadProgress.copy(downloadId = downloadId)
                ))
        coroutineScope.launch {
            val result =
                openSharedThreadAttachment(
                    bridge = bridge,
                    dataSource = dataSource,
                    attachment = attachment,
                    owner = owner,
                    downloadId = downloadId,
                    share = share,
                    onProgress = { progress ->
                        val token = attachmentOperationTokens[attachment.id]
                        if (token != operation || !attachmentOperationCommitAllowed(operation, token.operationId)) {
                            return@openSharedThreadAttachment
                        }
                        val current = attachmentOpenStates[attachment.id]
                        if (current?.downloadId != downloadId) return@openSharedThreadAttachment
                        val reduced =
                            reduceAttachmentDownloadProgress(
                                current = current.progress,
                                incoming = progress,
                                expectedDownloadId = downloadId
                            )
                        if (reduced === current.progress) return@openSharedThreadAttachment
                        attachmentOpenStates =
                            attachmentOpenStates +
                                (attachment.id to current.copy(
                                    status = if (reduced?.status == "paused") "paused" else "downloading",
                                    progress = reduced
                                ))
                    }
                )
            val token = attachmentOperationTokens[attachment.id]
            if (token != operation || !attachmentOperationCommitAllowed(operation, token.operationId)) return@launch
            if (result.status == "opened" || result.status == "save_cancelled") {
                attachmentOpenStates = attachmentOpenStates - attachment.id
                attachmentOperationTokens = attachmentOperationTokens - attachment.id
            } else {
                attachmentOpenStates =
                    attachmentOpenStates +
                        (attachment.id to ThreadAttachmentOpenUiState(
                            status = result.status,
                            downloadId = downloadId
                        ))
                if (result.status != "cancelled") {
                    bridge.errorToast(
                        raftAttachmentOpenResultMessage(result.status, attachmentOpenResultTerms)
                            ?: unableToOpenAttachmentLabel,
                        result.traceId
                    )
                }
                attachmentOperationTokens = attachmentOperationTokens - attachment.id
            }
        }
    }
    fun saveAttachmentExternally(attachment: RaftThreadAttachmentItem) {
        if (attachment.id.isBlank() || attachmentOpenStates[attachment.id].threadAttachmentIsOpening()) return
        val owner = attachmentOwner ?: return
        nextAttachmentDownloadGeneration += 1L
        val downloadId = attachment.threadAttachmentDownloadId(nextAttachmentDownloadGeneration)
        val operation = AttachmentOperationToken(owner, downloadId, downloadId)
        attachmentOperationTokens = attachmentOperationTokens + (attachment.id to operation)
        attachmentOpenStates =
            attachmentOpenStates +
                (attachment.id to ThreadAttachmentOpenUiState(
                    status = "downloading",
                    downloadId = downloadId,
                    progress = RaftFilePreviewInitialDownloadProgress.copy(downloadId = downloadId)
                ))
        coroutineScope.launch {
            val result =
                saveSharedThreadAttachment(
                    bridge = bridge,
                    dataSource = dataSource,
                    attachment = attachment,
                    owner = owner,
                    downloadId = downloadId,
                    onProgress = { progress ->
                        val token = attachmentOperationTokens[attachment.id]
                        if (token != operation || !attachmentOperationCommitAllowed(operation, token.operationId)) {
                            return@saveSharedThreadAttachment
                        }
                        val current = attachmentOpenStates[attachment.id]
                        if (current?.downloadId != downloadId) return@saveSharedThreadAttachment
                        val reduced =
                            raftFilePreviewReducePageProgress(
                                current = current.progress,
                                incoming = progress,
                                owner = owner,
                                activeDownloadId = downloadId,
                            )
                        if (reduced === current.progress) return@saveSharedThreadAttachment
                        attachmentOpenStates =
                            attachmentOpenStates +
                                (attachment.id to current.copy(
                                    status = if (reduced?.status == "paused") "paused" else "downloading",
                                    progress = reduced
                                ))
                    }
                )
            val token = attachmentOperationTokens[attachment.id]
            if (token != operation || !attachmentOperationCommitAllowed(operation, token.operationId)) return@launch
            val toast =
                raftThreadAttachmentSaveToast(
                    result = result,
                    hasDesktopDownloadBar = raftPlatformSavedFileHost() != null,
                    savedMessage = attachmentSavedLabel,
                    fallbackSavedMessage = attachmentDownloadedLabel,
                    downloadFailedMessage = attachmentOpenResultTerms.downloadFailed,
                    storageUnavailableMessage = attachmentOpenResultTerms.storageUnavailable,
                    unableToDownloadMessage = unableToDownloadAttachmentLabel,
                )
            when (result.status) {
                "saved" -> {
                    attachmentOpenStates = attachmentOpenStates - attachment.id
                    attachmentOperationTokens = attachmentOperationTokens - attachment.id
                    if (toast is ThreadAttachmentSaveToast.Info) {
                        raftShowSavedDownloadToast(
                            bridge = bridge,
                            message = toast.message,
                            openLabel = openAttachmentActionLabel,
                            target =
                                RaftSavedDownloadOpenTarget(
                                    attachmentId = attachment.id,
                                    fileName = attachment.name.ifBlank { "attachment-${attachment.id.take(8)}" },
                                    owner = owner,
                                    mimeType = attachment.mimeType.takeIf { it.isNotBlank() },
                                ),
                            openResultTerms = attachmentOpenResultTerms,
                        )
                    }
                }
                "cancelled" -> {
                    attachmentOpenStates = attachmentOpenStates - attachment.id
                    attachmentOperationTokens = attachmentOperationTokens - attachment.id
                }
                else -> {
                    attachmentOpenStates =
                        attachmentOpenStates +
                            (attachment.id to ThreadAttachmentOpenUiState(
                                status = result.status,
                                downloadId = downloadId
                            ))
                    val failure =
                        raftAttachmentSaveFailure(result.status, RaftAttachmentSaveDestination.Downloads)
                            ?: RaftAttachmentSaveFailure.UnableToDownload
                    bridge.log(
                        "thread_attachment_save_failed attachment=${attachment.id.take(8)} " +
                            "status=${raftAttachmentOpenDiagnosticStatus(result.status)} " +
                            "errorKind=${raftAttachmentOpenDiagnosticErrorKind(result.errorKind)} " +
                            "failure=${failure.name} message=${raftAttachmentHostMessageForLog(result.message)}"
                    )
                    when (toast) {
                        is ThreadAttachmentSaveToast.Info -> bridge.toast(toast.message)
                        is ThreadAttachmentSaveToast.Error -> bridge.errorToast(toast.message, toast.traceId)
                        null -> Unit
                    }
                    attachmentOperationTokens = attachmentOperationTokens - attachment.id
                }
            }
        }
    }
    /**
     * The wide lightbox's Save to Photos, by touch (#305): the phone viewer's Save to Photos
     * (RaftFilePreview) for one conversation image. Its own file goes into the photo library
     * ([saveSharedThreadImageToPhotos]); then the viewer's words: "Saved to Photos", nothing when the
     * save stopped, otherwise the Photos failure for the host's status ([raftAttachmentSaveFailure]).
     * One operation at a time per attachment, as the other actions here.
     */
    fun saveImageToPhotos(attachment: RaftThreadAttachmentItem) {
        if (attachment.id.isBlank() || attachmentOpenStates[attachment.id].threadAttachmentIsOpening()) return
        val owner = attachmentOwner ?: return
        nextAttachmentDownloadGeneration += 1L
        val downloadId = attachment.threadAttachmentDownloadId(nextAttachmentDownloadGeneration)
        val operation = AttachmentOperationToken(owner, downloadId, downloadId)
        attachmentOperationTokens = attachmentOperationTokens + (attachment.id to operation)
        attachmentOpenStates =
            attachmentOpenStates +
                (attachment.id to ThreadAttachmentOpenUiState(
                    status = "downloading",
                    downloadId = downloadId,
                    progress = RaftFilePreviewInitialDownloadProgress.copy(downloadId = downloadId)
                ))
        coroutineScope.launch {
            val result =
                saveSharedThreadImageToPhotos(
                    bridge = bridge,
                    dataSource = dataSource,
                    attachment = attachment,
                    owner = owner,
                    downloadId = downloadId
                )
            val token = attachmentOperationTokens[attachment.id]
            if (token != operation || !attachmentOperationCommitAllowed(operation, token.operationId)) return@launch
            attachmentOpenStates = attachmentOpenStates - attachment.id
            attachmentOperationTokens = attachmentOperationTokens - attachment.id
            when (result.status) {
                "saved", "exported" -> bridge.toast(attachmentSavedToPhotosLabel)
                "cancelled" -> Unit
                else -> {
                    val failure =
                        raftAttachmentSaveFailure(result.status, RaftAttachmentSaveDestination.Photos)
                            ?: RaftAttachmentSaveFailure.PhotosSaveFailed
                    bridge.log(
                        "thread_attachment_save_failed attachment=${attachment.id.take(8)} " +
                            "status=${raftAttachmentOpenDiagnosticStatus(result.status)} " +
                            "errorKind=${raftAttachmentOpenDiagnosticErrorKind(result.errorKind)} " +
                            "failure=${failure.name} message=${raftAttachmentHostMessageForLog(result.message)}"
                    )
                    bridge.errorToast(failure.messageRes.desc().resolve(), result.traceId)
                }
            }
        }
    }
    /** True when the file page opened. */
    fun openAttachmentNoPreview(attachment: RaftThreadAttachmentItem): Boolean {
        if (attachment.id.isBlank()) return false
        // A tap never downloads in the conversation: previewable files open their preview, any
        // other file its file page, where Download shows progress (task #75).
        return pager.openRaftFilePreviewPage(
            attachment.toThreadAttachmentLazyPreviewPayload(
                kind = attachment.threadAttachmentOpenRoute().requestedPreviewKind,
                attachmentCommentsEnabled = attachmentCommentsEnabled
            )
        )
    }

    fun requestInlineAudioPreview(attachmentId: String, force: Boolean = false) {
        val cleanId = attachmentId.trim()
        if (cleanId.isEmpty()) return
        if (inlineAudioPreviewStates[attachmentId]?.status == ThreadInlineAudioPreviewStatus.Loading) return
        if (!force && !inlineAudioPreviewStates.canRequestInlineAudioPreview(attachmentId)) return
        val startOwner = routeOwnerProvider()?.normalizedOrNull()
        val urlCache = raftFilePreviewAttachmentUrlCache()
        if (!force && startOwner != null) {
            urlCache.get(RaftFilePreviewAttachmentUrlCacheKey(startOwner, cleanId, "inline"))?.let { cachedUrl ->
                inlineAudioPreviewStates =
                    inlineAudioPreviewStates +
                        (attachmentId to ThreadInlineAudioPreviewUiState(
                            status = ThreadInlineAudioPreviewStatus.Ready,
                            url = cachedUrl,
                        ))
                return
            }
        }
        // Mark Loading synchronously to prevent duplicate concurrent launches before the coroutine starts.
        inlineAudioPreviewStates =
            inlineAudioPreviewStates +
                (attachmentId to ThreadInlineAudioPreviewUiState(status = ThreadInlineAudioPreviewStatus.Loading))
        coroutineScope.launch {
            runThreadInlineAudioPreviewFetch(
                attachmentId = attachmentId,
                force = force,
                ownerProvider = routeOwnerProvider,
                urlCache = urlCache,
                fetch = { dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "inline") },
                onState = { state -> inlineAudioPreviewStates = inlineAudioPreviewStates + (attachmentId to state) },
                onAuthRequired = { routeAuthRequired("auth_required") },
            )
        }
    }
    fun requestInlineVideoPreview(attachmentId: String, force: Boolean = false) {
        val cleanId = attachmentId.trim()
        if (cleanId.isEmpty()) return
        val startOwner = routeOwnerProvider()?.normalizedOrNull()
        val urlCache = raftFilePreviewAttachmentUrlCache()
        if (force && startOwner != null) {
            urlCache.invalidate(RaftFilePreviewAttachmentUrlCacheKey(startOwner, cleanId, "inline"))
        }
        val running = inlineVideoPreviewJobs[attachmentId]?.isActive == true
        if (running) {
            // A retry is waiting out its backoff: a forced request (tap, realtime update) runs it now.
            if (force) inlineVideoPreviewRetryNudges.tryEmit(attachmentId)
            return
        }
        if (!force && !inlineVideoPreviewStates.canRequestInlineVideoPreview(attachmentId)) return
        if (!force && startOwner != null) {
            urlCache.get(RaftFilePreviewAttachmentUrlCacheKey(startOwner, cleanId, "inline"))?.let { cachedUrl ->
                inlineVideoPreviewStates =
                    inlineVideoPreviewStates +
                        (attachmentId to ThreadInlineVideoPreviewUiState(
                            status = ThreadInlineVideoPreviewStatus.Ready,
                            url = cachedUrl,
                        ))
                return
            }
        }
        inlineVideoPreviewJobs[attachmentId] =
            coroutineScope.launch {
                val outcome =
                    runThreadInlineVideoPreviewFetch(
                        policy = ThreadInlineVideoRetryPolicy.Default,
                        fetch = {
                            runCatchingPreservingCancellation {
                                dataSource.attachmentUrl(DataRequestPriority.Visible, attachmentId, disposition = "inline")
                            }.fold(
                                onSuccess = { it.toInlineVideoFetchOutcome() },
                                onFailure = {
                                    ThreadInlineVideoFetchOutcome.Failed(0, ThreadInlineVideoFailureKind.Transient)
                                },
                            )
                        },
                        awaitRetry = { delayMs ->
                            withTimeoutOrNull(delayMs) {
                                inlineVideoPreviewRetryNudges.first { it == attachmentId || it == null }
                            }
                        },
                        onState = { state ->
                            inlineVideoPreviewStates = inlineVideoPreviewStates + (attachmentId to state)
                        }
                    )
                if (
                    outcome is ThreadInlineVideoFetchOutcome.Ready &&
                    startOwner != null &&
                    routeOwnerProvider()?.normalizedOrNull() == startOwner
                ) {
                    urlCache.put(
                        key = RaftFilePreviewAttachmentUrlCacheKey(startOwner, cleanId, "inline"),
                        url = outcome.url,
                        expiresAtEpochMillis = parseRaftTimestampEpochMillisOrNull(outcome.expiresAt),
                    )
                }
                if (outcome is ThreadInlineVideoFetchOutcome.Failed && outcome.code == 401) {
                    routeAuthRequired("auth_required")
                }
            }
    }

    /** True when a page (the preview, the image editor) opened over the conversation. */
    fun openComposerAttachmentPreview(attachmentId: String): Boolean {
        val composerPayload = currentComposerPayload()
        val attachment = composerPayload.pendingAttachments.firstOrNull { it.id == attachmentId } ?: return false
        val currentDraftState = composerDraftViewModel.state.value
        val name = attachment.fileName.ifBlank { "Attachment" }
        val meta = attachment.displaySizeLabel()
        if (!attachment.isReadyThreadAttachmentForSend()) {
            return pager.openRaftFilePreviewPage(
                RaftFilePreviewPayload(
                    kind = if (attachment.status == "failed") "error" else "loading",
                    requestedKind = "composer_attachment",
                    title = name,
                    subtitle = meta,
                    name = name,
                    path = name,
                    meta = meta,
                    message =
                        when (attachment.status) {
                            "failed" ->
                                listOf(
                                    attachmentUploadFailedLabel,
                                    threadFailureReasonLabel(attachment.errorCode)
                                ).filterNotNull().joinToString(": ") + ". Retry the upload from the composer."
                            else -> preparingAttachmentPreviewLabel
                        },
                    messageDesc = null,
                    error =
                        if (attachment.status == "failed") {
                            "attachment_upload_failed"
                        } else {
                            null
                        },
                    externalFileName = name,
                    externalMimeType = attachment.mimeType.orEmpty()
                )
            )
        }
        val threadAttachment =
            RaftThreadAttachmentItem(
                id = attachment.id,
                name = name,
                mimeType = attachment.mimeType.orEmpty(),
                sizeLabel = attachment.sizeLabel,
                sizeBytes = attachment.sizeBytes,
                thumbnailUrl = attachment.previewUri
        )
        val composerImageGroup = composerPayload.pendingAttachments.composerPendingImageAttachmentGroup(attachmentId)
        // RaftPendingAttachmentItem is intentionally a render-only projection and therefore
        // does not carry the platform file handle. Resolve each gallery item back to the
        // session-owned draft/upload descriptor before opening an editable preview; otherwise
        // the gallery payload would contain only thumbnails and the Done/session registry could
        // never admit the resulting events.
        val draftAttachmentsById = currentDraftState.uploadedAttachments.associateBy { it.id }
        val currentDraftKey = normalizeThreadAttachmentUploadDraftKey(currentDraftState.draftKey)
        fun composerImageFileFor(id: String): PlatformFile? =
            attachmentUploadFilesByLocalId.threadAttachmentUploadFile(id, currentDraftKey)
                ?.takeIf { file -> !file.fileRef.isNullOrBlank() }
                ?: draftAttachmentsById[id]
                    ?.toComposerImagePlatformFile()
                    ?.takeIf { file -> !file.fileRef.isNullOrBlank() }
        return if (composerImageGroup != null && composerImageGroup.images.size > 1) {
            val galleryFiles =
                composerImageGroup.images.mapNotNull { image -> composerImageFileFor(image.id) }
            // A ready tile without a local file ref is not safely editable. Keep it as an
            // ordinary preview rather than creating a composer session whose Done action can
            // never produce a deliverable event.
            if (galleryFiles.size != composerImageGroup.images.size || galleryFiles.isEmpty()) {
                return pager.openRaftFilePreviewPage(
                    threadAttachment.toThreadAttachmentLazyPreviewPayload(
                        kind = "image",
                        imageGallery = composerImageGroup.images,
                        imageGalleryIndex = composerImageGroup.currentIndex
                    ).copy(imageUrl = attachment.previewUri?.takeIf { it.isNotBlank() })
                )
            }
            composerImageActionCounter += 1
            val baseActionId =
                "composer-image-${threadInstanceKey}-${composerImageActionCounter}-${attachmentId.take(12)}"
            openTrackedComposerImagePreview(
                threadComposerUploadedImagePreviewPayload(
                    threadInstanceKey = threadInstanceKey,
                    actionId = baseActionId,
                    files = galleryFiles,
                    replacementAttachmentIds = composerImageGroup.images.map { it.id },
                    selectedIndex = composerImageGroup.currentIndex,
                    originalImage = currentDraftState.originalImage
                ) ?: return false
            )
        } else if (threadAttachment.isImageAttachment()) {
            composerImageActionCounter += 1
            val composerImageActionId = "composer-image-${threadInstanceKey}-${composerImageActionCounter}-${threadAttachment.id.take(12)}"
            val composerImageFile = composerImageFileFor(attachment.id)
            // A server-ready tile can still be previewable after its local picker ref has been
            // retired.  It is not safely editable/compressible in that state, so keep it a plain
            // read-only preview instead of creating a Done session that can never emit an event.
            if (composerImageFile == null) {
                return pager.openRaftFilePreviewPage(
                    threadAttachment.toThreadAttachmentLazyPreviewPayload(
                        kind = "image"
                    ).copy(imageUrl = attachment.previewUri?.takeIf { it.isNotBlank() })
                )
            }
            openTrackedComposerImagePreview(
                threadComposerUploadedImagePreviewPayload(
                    threadInstanceKey = threadInstanceKey,
                    actionId = composerImageActionId,
                    files = listOf(composerImageFile),
                    replacementAttachmentIds = listOf(attachment.id),
                    selectedIndex = 0,
                    originalImage = currentDraftState.originalImage
                ) ?: return false
            )
        } else {
            openAttachmentNoPreview(threadAttachment)
        }
    }
}

private fun RaftThreadAttachmentItem.threadAttachmentDownloadId(generation: Long): String =
    listOf("attempt-$generation", "thread_attachment", id.ifBlank { name.ifBlank { "attachment" } }, name.ifBlank { "attachment" })
        .joinToString(":")
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ':' || it == '.' }
        .take(160)
        .ifBlank { "thread-attachment-download" }
