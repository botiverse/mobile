package build.raft.app.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.RaftComposerServerUploads
import build.raft.app.RaftTextFieldNativeEditingStateReadResult
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.SharedThreadComposerDraftIntent
import build.raft.app.SharedThreadComposerDraftViewModel
import build.raft.app.ThreadAttachmentUploadAttemptIdentity
import build.raft.app.ThreadAttachmentUploadOrchestrator
import build.raft.app.ThreadComposerSendAdmission
import build.raft.app.ThreadComposerSendRequest
import build.raft.app.ThreadComposerSurface
import build.raft.app.base.BridgeModule
import build.raft.app.data.PlatformFile
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.normalizeThreadAttachmentUploadDraftKey
import build.raft.app.recoverOrphanedThreadComposerUploads
import build.raft.app.threadAttachmentUploadAttemptIdentity
import build.raft.app.threadAttachmentUploadFile
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The composer draft's effects that sit with its uploads: A4 adoption's latest-draft capture, the
 * upload-capability warm-up, and the restore of the draft's uploads (with the orphaned-upload
 * recovery). Takes the page's states, not their values: the effects read and write them live.
 */
@Composable
@NonRestartableComposable
internal fun ThreadComposerDraftUploadEffects(
    bridge: BridgeModule,
    dataSource: SharedThreadDataSource,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    threadInstanceKey: String,
    attachmentUploads: RaftComposerServerUploads,
    attachmentUploadOwnerResolved: Boolean,
    attachmentUploadOrchestrator: ThreadAttachmentUploadOrchestrator,
    attachmentUploadFilesByLocalIdState: MutableState<Map<ThreadAttachmentUploadAttemptIdentity, PlatformFile>>,
    restoredAttachmentUploadDraftKeyState: MutableState<String?>,
    restoredAttachmentUploadFileIdsState: MutableState<Set<ThreadAttachmentUploadAttemptIdentity>>,
) {
    var attachmentUploadFilesByLocalId by attachmentUploadFilesByLocalIdState
    var restoredAttachmentUploadDraftKey by restoredAttachmentUploadDraftKeyState
    var restoredAttachmentUploadFileIds by restoredAttachmentUploadFileIdsState
    // A4 adoption (task #18): register the draft-persist barrier + viewport anchor providers
    // with the holder the page-level initiator calls. Registration is keyed on the objects
    // themselves so a rotated detail never serves a stale provider.
    val adaptiveNativeEditingStateReader = LocalThreadAdaptiveNativeEditingReader.current
    val adaptiveInitiationCaptureHolder = LocalThreadAdaptiveInitiationCapture.current
    DisposableEffect(
        adaptiveInitiationCaptureHolder,
        adaptiveNativeEditingStateReader,
        composerDraftViewModel,
        threadInstanceKey,
    ) {
        val holder = adaptiveInitiationCaptureHolder
        val reader = adaptiveNativeEditingStateReader
        if (holder != null && reader != null) {
            holder.captureLatestDraft = captureLatestDraft@{ _ ->
                // Unavailable must abort the migration — proceeding with only the stale shared
                // draft would silently lose whatever is in the live editor (bridge contract).
                val identityBefore = composerDraftViewModel.state.value
                val nativeRead =
                    suspendCancellableCoroutine<RaftTextFieldNativeEditingStateReadResult.Available?> {
                            continuation ->
                        reader.readCurrentValue { readResult ->
                            continuation.resume(
                                readResult as? RaftTextFieldNativeEditingStateReadResult.Available
                            )
                        }
                    } ?: return@captureLatestDraft null
                val nativeText = nativeRead.value.text
                // The draft the native value belongs to is pinned by the state read above;
                // a composer draft switch/rebind mid-read would attribute the persisted text
                // to the wrong conversation.
                val identityAfterRead = composerDraftViewModel.state.value
                if (
                    identityAfterRead.draftKey != identityBefore.draftKey ||
                    identityAfterRead.instanceId != identityBefore.instanceId
                ) {
                    return@captureLatestDraft null
                }
                val changeReceipt =
                    runCatchingPreservingCancellation {
                        composerDraftViewModel.acceptQueued(
                            SharedThreadComposerDraftIntent.Change(seedText = nativeText)
                        )
                    }.getOrElse { return@captureLatestDraft null }
                // The receipt state must still carry the same draft identity we started from.
                // A rejected (accepted=false) receipt is a satisfied no-op only when the
                // serialized lane proves the store already carries the exact native text —
                // MviFoundation reports an equivalent-state transition as not-accepted with
                // state=previous. Any other rejection means the draft projection did not
                // accept our text, and persisting would durably store the wrong content.
                if (
                    changeReceipt.state.draftKey != identityBefore.draftKey ||
                    changeReceipt.state.instanceId != identityBefore.instanceId
                ) {
                    return@captureLatestDraft null
                }
                val changeSatisfied =
                    changeReceipt.accepted ||
                        changeReceipt.state.seedText == nativeText
                if (!changeSatisfied) {
                    return@captureLatestDraft null
                }
                val flushReceipt =
                    runCatchingPreservingCancellation {
                        composerDraftViewModel.acceptQueued(
                            SharedThreadComposerDraftIntent.Flush
                        )
                    }.getOrElse { return@captureLatestDraft null }
                if (
                    !flushReceipt.accepted ||
                    flushReceipt.state.draftKey != identityBefore.draftKey ||
                    flushReceipt.state.instanceId != identityBefore.instanceId
                ) {
                    return@captureLatestDraft null
                }
                // Identity-bearing snapshot: the pre-commit fence compares a later capture
                // field-for-field — same text under a different draft/instance is drift.
                SharedThreadComposerDraftSnapshot(
                    text = nativeText,
                    draftKey = identityBefore.draftKey,
                    instanceId = identityBefore.instanceId,
                    editorNativeRef = nativeRead.editorNativeRef,
                )
            }
        }
        onDispose {
            if (holder != null && holder.captureLatestDraft != null) {
                holder.captureLatestDraft = null
            }
        }
    }
    // Warm the upload-capability cache when the composer opens so a later pick adds its chip
    // without waiting on `attachments/upload-capabilities` (slow on weak networks).
    LaunchedEffect(threadInstanceKey) {
        runCatchingPreservingCancellation { dataSource.prefetchAttachmentUploadCapabilities() }
    }
    LaunchedEffect(composerDraftViewModel, threadInstanceKey, attachmentUploads, attachmentUploadOwnerResolved) {
        composerDraftViewModel.state.collect { draftState ->
            val draftKey = normalizeThreadAttachmentUploadDraftKey(draftState.draftKey)
            val previousRestoredKey = restoredAttachmentUploadDraftKey
            if (draftKey != null && previousRestoredKey != null && draftKey != previousRestoredKey) {
                // A reused Thread surface moved its composer store from draft A to draft B: A's
                // uploads keep going (they belong to draft A, not to this page) and publish only
                // into draft A's uploads, never into B.
                // The map key is the complete (draft,id) identity.  Retire every handle owned by
                // the old draft; a same-named local id admitted by the new draft is a different
                // key and therefore remains untouched.  This also prevents a late old-job
                // cleanup from deleting or shadowing the new draft's handle.
                attachmentUploadFilesByLocalId =
                    attachmentUploadFilesByLocalId.filterKeys { key -> key.draftKey != previousRestoredKey }
                restoredAttachmentUploadFileIds =
                    restoredAttachmentUploadFileIds.filter { key -> key.draftKey != previousRestoredKey }.toSet()
            }

            // A transient null key can be emitted while a route is being rebound.  Keep the last
            // non-empty key so the first real key after an initial null does not clear an active
            // session, while a genuine A -> B transition still retires the old restoration batch.
            if (draftKey == null) return@collect
            if (draftKey != previousRestoredKey) {
                restoredAttachmentUploadDraftKey = draftKey
            }

            val restoredFiles =
                draftState.uploadedAttachments
                    .mapNotNull { attachment ->
                        attachment.toRestoredThreadComposerPlatformFile()?.let { file ->
                            attachment.id to file
                        }
                    }
                    .toMap()
            if (restoredFiles.isNotEmpty()) {
                // Never shadow the handle a live attempt of this page registered; a draft-owned
                // upload started by a page that is gone gets the restored file (Retry after a
                // background failure).
                val safeRestoredFiles =
                    restoredFiles.filterKeys { id ->
                        attachmentUploadFilesByLocalId.threadAttachmentUploadFile(id, draftKey) == null
                    }
                if (safeRestoredFiles.isNotEmpty()) {
                    val keyedFiles =
                        safeRestoredFiles.mapNotNull { (id, file) ->
                            threadAttachmentUploadAttemptIdentity(id, draftKey)?.let { identity ->
                                identity to file
                            }
                        }.toMap()
                    attachmentUploadFilesByLocalId = attachmentUploadFilesByLocalId + keyedFiles
                    restoredAttachmentUploadFileIds = restoredAttachmentUploadFileIds + keyedFiles.keys
                }
            }
            // A restored row whose upload nothing owns any more (the process died meanwhile, or
            // the upload was cancelled) can never finish: fail it (upload_interrupted) so the chip
            // offers Retry with the file registered above, instead of blocking Send forever. The
            // draft's own uploads are left to the applier below.
            if (!attachmentUploadOwnerResolved) return@collect
            recoverOrphanedThreadComposerUploads(
                draftKey = draftKey,
                state = draftState,
                acceptWithState = composerDraftViewModel::acceptWithState,
                currentState = { composerDraftViewModel.state.value },
                orchestrator = attachmentUploadOrchestrator,
                log = { message -> bridge.logWarn(message) },
                ownedByDraftUpload = { id -> attachmentUploads.ownsUpload(draftKey, id) },
                ownedByDraftDiscard = { id -> attachmentUploads.ownsDiscard(draftKey, id) }
            )
        }
    }
}

/**
 * The composer's deferred sends: the optimistic send held until the conversation's access
 * resolves, and the external-share auto-send once its uploads finish. Takes the page's states,
 * not their values: the effects read and write them live.
 */
@Composable
@NonRestartableComposable
internal fun ThreadComposerDeferredSendEffects(
    bridge: BridgeModule,
    dataSource: SharedThreadDataSource,
    threadViewModel: SharedThreadViewModel,
    composerDraftViewModel: SharedThreadComposerDraftViewModel,
    threadInstanceKey: String,
    pendingOptimisticSendState: MutableState<ThreadComposerSendRequest?>,
    renderSliceState: State<SharedThreadRenderSlice>,
    threadLimitLocked: Boolean,
    renderedThread: RaftThreadScreenPayload,
    composerSurface: ThreadComposerSurface,
    externalShareSendRequestedState: MutableState<Boolean>,
    externalShareExpectedAuthorityState: MutableState<build.raft.app.data.RaftExpectedAuthBinding?>,
    latestPerformComposerSendState: State<() -> Boolean>,
    latestHandleComposerSendRequestState: State<(ThreadComposerSendRequest) -> ThreadComposerSendAdmission>,
) {
    val renderSlice by renderSliceState
    var externalShareSendRequested by externalShareSendRequestedState
    var externalShareExpectedAuthority by externalShareExpectedAuthorityState
    val latestPerformComposerSend by latestPerformComposerSendState
    val latestHandleComposerSendRequest by latestHandleComposerSendRequestState
    val pendingOptimisticSend = pendingOptimisticSendState.value
    val sendTargetChannelId = renderedThread.composerSendTargetChannelId()
    LaunchedEffect(
        pendingOptimisticSend,
        renderSlice.accessResolution,
        renderSlice.canonicalAccessResolved,
        renderSlice.writeActionsEnabled,
        renderSlice.replyParentAuthority,
        threadLimitLocked,
        renderedThread.status,
        renderedThread.channelArchived,
        renderedThread.joined,
        renderedThread.canSend,
        sendTargetChannelId,
    ) {
        val pending = pendingOptimisticSend ?: return@LaunchedEffect
        val currentThreadState = threadViewModel.state.value
        val canFlushNow =
            !threadLimitLocked &&
                sendTargetChannelId != null &&
                (
                    (
                        renderSlice.accessResolution == ThreadAccessResolution.Resolved &&
                            renderSlice.writeActionsEnabled &&
                            !renderedThread.channelArchived &&
                            renderedThread.joined &&
                            renderedThread.canSend
                    ) ||
                        currentThreadState.allowsOptimisticSendWhileAccessLoading(
                            sendChannelId = sendTargetChannelId,
                            limitLocked = threadLimitLocked,
                        )
                )
        if (canFlushNow) {
            pendingOptimisticSendState.value = null
            val latestDraft = composerDraftViewModel.state.value
            val resolvedRequest =
                pending.copy(
                    value = TextFieldValue(latestDraft.seedText),
                    asTask = latestDraft.seedAsTask,
                )
            latestHandleComposerSendRequest(resolvedRequest)
            return@LaunchedEffect
        }
        val keepWaitingForTargetOrAccess =
            currentThreadState.canDeferOptimisticSendWhileAccessLoading(limitLocked = threadLimitLocked) ||
                (
                    !threadLimitLocked &&
                        !renderedThread.channelArchived &&
                        renderedThread.status != "error" &&
                        renderedThread.status != "no_permission" &&
                        (
                            (sendTargetChannelId == null && renderedThread.status == "loading") ||
                                (
                                    renderSlice.accessResolution == ThreadAccessResolution.Resolved &&
                                        !renderSlice.canonicalAccessResolved &&
                                        !renderSlice.writeActionsEnabled &&
                                        renderedThread.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
                                        renderedThread.joined
                                )
                        )
                )
        if (keepWaitingForTargetOrAccess) {
            delay(10_000L)
            if (pendingOptimisticSendState.value === pending) {
                pendingOptimisticSendState.value = null
                bridge.log("thread_composer_deferred_send_aborted reason=access_timeout")
            }
            return@LaunchedEffect
        }
        pendingOptimisticSendState.value = null
        bridge.log(
            "thread_composer_deferred_send_aborted " +
                "accessResolution=${renderSlice.accessResolution.name} " +
                "writeActions=${renderSlice.writeActionsEnabled} " +
                "limitLocked=$threadLimitLocked archived=${renderedThread.channelArchived} " +
                "joined=${renderedThread.joined} canSend=${renderedThread.canSend}"
        )
    }
    LaunchedEffect(composerDraftViewModel, threadInstanceKey) {
        composerDraftViewModel.state.collect { draftState ->
            if (!externalShareSendRequested) return@collect
            val externalAttachments =
                draftState.uploadedAttachments.filter { attachment ->
                    attachment.source == "external_share" &&
                        attachment.id !in draftState.removedAttachmentIds
                }
            when {
                externalAttachments.any { it.status == "failed" || it.status == "discard_failed" } -> externalShareSendRequested = false
                externalAttachments.any { it.status == "queued" || it.status == "uploading" || it.status == "removing" } -> Unit
                externalAttachments.isNotEmpty() -> {
                    val expected = externalShareExpectedAuthority
                    val authorityCurrent =
                        expected != null && dataSource.matchesExternalShareSendAuthority(expected)
                    externalShareSendRequested = false
                    externalShareExpectedAuthority = null
                    if (authorityCurrent) {
                        latestPerformComposerSend()
                    } else {
                        bridge.logWarn(
                            "external_share_send_rejected reason=auth_generation_changed_after_upload"
                        )
                    }
                }
                else -> {
                    externalShareSendRequested = false
                    externalShareExpectedAuthority = null
                }
            }
        }
    }
}
