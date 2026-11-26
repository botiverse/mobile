package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadPendingSendInFlightRegistry
import build.raft.app.data.ownSentConversationIds
import build.raft.app.data.runCatchingPreservingCancellation
import build.raft.app.model.ServerId
import build.raft.app.network.RaftRequestTimeoutException
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val ThreadSendSilentRetryDelayMs = 900L
private const val ThreadSendMaxSilentRetries = 2

// task #1134: a send request whose socket stays open without a response beyond this bound is
// treated as a timeout so the row converges to failed + retry instead of hanging in "发送中"
// forever. Client-side cancellation may still leave a committed server request; the journaled
// randomId makes the follow-up retry idempotent server-side.
private const val ThreadSendRequestTimeoutMs = 30_000L

/**
 * The pending local message send: what used to be `sendPendingLocalMessage`, a local function of
 * `SharedThreadRouteContentContent`, moved verbatim with the constants only it reads. Built every
 * composition with that composition's values, as the local function captured them.
 */
internal class ThreadRoutePendingSender(
    private val bridge: BridgeModule,
    private val threadViewModel: SharedThreadViewModel,
    private val currentSession: StoredAuthSession?,
    private val threadSendScope: CoroutineScope,
    private val dataSource: SharedThreadDataSource,
    private val composerDraftViewModel: SharedThreadComposerDraftViewModel,
    private val sharedHomeStore: SharedHomeStore?,
    private val serverMembersStore: SharedServerMembersStore?,
    private val threadMembersServerId: ServerId?,
    private val coroutineScope: CoroutineScope,
) {
    fun sendPendingLocalMessage(
        localMessageId: String,
        context: RaftThreadPendingSendContext,
        startedFromComposer: Boolean,
        authority: ThreadLocalSendAuthority
    ) {
        val sendBasePayload = threadViewModel.state.value.payload
        val sendProvenance = authority.provenance
        val sendRoute = authority.route
        val localMessage =
            sendBasePayload.messages.firstOrNull { it.id == localMessageId }
                ?: buildThreadPendingSendMessage(
                    localMessageId = localMessageId,
                    context = context,
                    session = currentSession,
                    timestampLabel = buildThreadPendingSendTimestampLabel(bridge, sendBasePayload.nowEpochMillis)
                )
        val sendStartedAt = TimeSource.Monotonic.markNow()
        bridge.performanceMarker(
            phase = "composer_send",
            routeName = "kuikly_thread",
            event = "start",
            threadId = context.channelId,
            metadata =
                mapOf(
                    "asTask" to context.asTask.toString(),
                    "attachments" to context.attachmentIds.size.toString(),
                    "hasText" to context.body.isNotBlank().toString(),
                    "bodyLength" to context.body.length.toString()
                )
        )
        // Registers this send's randomId with the process-wide in-flight fence BEFORE the job
        // starts, so the pending-send drainer never treats a journaled row as orphaned while an
        // attempt owns it. invokeOnCompletion covers every return/cancellation path.
        ThreadPendingSendInFlightRegistry.begin(context.randomId)
        threadSendScope.launch {
            fun sendAuthorityStillCurrent(): Boolean =
                dataSource.isCurrentReplyCacheProvenanceForLocalSend(sendProvenance) &&
                    threadViewModel.state.value.payload
                        .resolveThreadRouteIdentity()
                        .isPendingSendEquivalentTo(sendRoute)

            if (!sendAuthorityStillCurrent()) {
                bridge.logWarn(
                    "thread_send_drop localMessageId=$localMessageId " +
                        "channelId=${context.channelId} reason=owner_changed_before_request"
                )
                return@launch
            }

            suspend fun markFailed(errorCode: String?, errorMessage: String? = null) {
                threadViewModel.accept(
                    SharedThreadIntent.ApplyLocalSendFailure(
                        messageId = localMessageId,
                        errorCode = errorCode,
                        errorMessage = errorMessage,
                        expectedProvenance = sendProvenance,
                        expectedRoute = sendRoute,
                        randomId = context.randomId,
                    )
                )
                if (startedFromComposer && sendAuthorityStillCurrent()) {
                    composerDraftViewModel.dispatch(SharedThreadComposerDraftIntent.CompleteInlineSendFailure)
                }
            }

            suspend fun keepSendingUnconfirmed(result: String, errorCode: String?, httpCode: Int? = null) {
                bridge.logWarn(
                    "thread_send_unconfirmed localMessageId=$localMessageId " +
                        "channelId=${context.channelId} result=$result code=${errorCode.orEmpty()} " +
                        "httpCode=${httpCode?.toString().orEmpty()} action=mark_failed"
                )
                // The request may already have committed server-side. A retry uses the same
                // randomId, so the server-side idempotency key prevents a duplicate message.
                markFailed(errorCode, "Delivery could not be confirmed. Tap retry to try again.")
            }

            val sendAttemptState =
                ThreadSendAttemptState(
                    randomId = context.randomId,
                    maxSilentRetries = ThreadSendMaxSilentRetries
                )
            while (true) {
                if (!sendAuthorityStillCurrent()) {
                    bridge.logWarn(
                        "thread_send_drop localMessageId=$localMessageId " +
                            "channelId=${context.channelId} reason=owner_changed_before_attempt"
                    )
                    return@launch
                }
                val attempt = sendAttemptState.attempt
                val sendResult =
                    runCatchingPreservingCancellation {
                        withTimeoutOrNull(ThreadSendRequestTimeoutMs) {
                            dataSource.sendMessage(
                                channelId = context.channelId,
                                body = context.body,
                                asTask = context.asTask,
                                attachmentIds = context.attachmentIds,
                                randomId = sendAttemptState.randomId,
                                mentions = context.mentions,
                            )
                        }
                            ?: throw RaftRequestTimeoutException(
                                "send request exceeded ${ThreadSendRequestTimeoutMs}ms"
                            )
                    }
                val sendException = sendResult.exceptionOrNull()
                if (sendException != null) {
                    val errorCode = threadSendExceptionErrorCode(sendException)
                    val errorType = sendException::class.simpleName.orEmpty().ifBlank { "Throwable" }
                    val durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds
                    bridge.log(
                        "thread_send_exception localMessageId=$localMessageId " +
                            "channelId=${context.channelId} errorType=$errorType " +
                            "mapped=$errorCode attempt=$attempt durationMs=$durationMs"
                    )
                    if (sendAttemptState.advanceForSilentRetry(threadSendShouldSilentlyRetry(errorCode))) {
                        bridge.log(
                            "thread_send_silent_retry localMessageId=$localMessageId " +
                                "channelId=${context.channelId} reason=$errorCode attempt=$attempt"
                        )
                        delay(ThreadSendSilentRetryDelayMs)
                        continue
                    }
                    bridge.performanceMarker(
                        phase = "composer_send",
                        routeName = "kuikly_thread",
                        event = "end",
                        threadId = context.channelId,
                        durationMs = durationMs,
                        metadata =
                            mapOf(
                                "result" to "exception",
                                "code" to errorCode,
                                "errorType" to errorType,
                                "attempt" to attempt.toString()
                            )
                    )
                    if (threadSendShouldSilentlyRetry(errorCode)) {
                        keepSendingUnconfirmed("exception", errorCode)
                    } else {
                        markFailed(errorCode, threadSendExceptionErrorMessage(sendException))
                    }
                    return@launch
                }
                val sendResponse = sendResult.getOrThrow()
                val sentPage =
                    when (sendResponse) {
                        is ThreadApiResult.Success -> sendResponse.data
                        is ThreadApiResult.Failure -> {
                            val errorCode =
                                threadSendFailureErrorCode(
                                    contractCode = sendResponse.contractCode,
                                    errorBodyText = sendResponse.errorBodyText,
                                    httpCode = sendResponse.code
                                )
                            val errorMessage = threadFailureReasonMessage(sendResponse.errorBodyText)
                            if (sendAttemptState.advanceForSilentRetry(
                                    threadSendShouldSilentlyRetry(errorCode, sendResponse.code)
                                )
                            ) {
                                bridge.log(
                                    "thread_send_silent_retry localMessageId=$localMessageId " +
                                        "channelId=${context.channelId} reason=$errorCode httpCode=${sendResponse.code} attempt=$attempt"
                                )
                                delay(ThreadSendSilentRetryDelayMs)
                                continue
                            }
                            bridge.performanceMarker(
                                phase = "composer_send",
                                routeName = "kuikly_thread",
                                event = "end",
                                threadId = context.channelId,
                                durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                                metadata =
                                    mapOf(
                                        "result" to "failure",
                                        "code" to errorCode,
                                        "httpCode" to sendResponse.code.toString(),
                                        "contractCode" to sendResponse.contractCode.orEmpty(),
                                        "errorBytes" to (sendResponse.errorBodyText?.length ?: 0).toString(),
                                        "attempt" to attempt.toString()
                                    )
                            )
                            if (threadSendShouldSilentlyRetry(errorCode, sendResponse.code)) {
                                keepSendingUnconfirmed("failure", errorCode, sendResponse.code)
                            } else {
                                markFailed(errorCode, errorMessage)
                            }
                            return@launch
                        }
                    }
                if (sentPage.messages.isEmpty()) {
                    if (sendAttemptState.advanceForSilentRetry(eligible = true)) {
                        bridge.log(
                            "thread_send_silent_retry localMessageId=$localMessageId " +
                                "channelId=${context.channelId} reason=empty_response attempt=$attempt"
                        )
                        delay(ThreadSendSilentRetryDelayMs)
                        continue
                    }
                    bridge.performanceMarker(
                        phase = "composer_send",
                        routeName = "kuikly_thread",
                        event = "end",
                        threadId = context.channelId,
                        durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                        metadata = mapOf("result" to "empty", "attempt" to attempt.toString())
                    )
                    keepSendingUnconfirmed("empty_response", "empty_response")
                    return@launch
                }
                threadViewModel.accept(
                    SharedThreadIntent.ReplaceLocalSendWithSentPage(
                        messageId = localMessageId,
                        page = sentPage,
                        expectedProvenance = sendProvenance,
                        expectedRoute = sendRoute
                    )
                )
                // Task #59 & slock #16474: replying in a hidden DM shows it in Home's DM list right away,
                // and posting in a Lounge or one of its threads keeps the Lounge's `listed` state in sync.
                val ownSentChannelIds =
                    sentPage.ownSentConversationIds(
                        fallbackChannelId = context.channelId,
                        parentConversationId = sendBasePayload.parentChannelId.takeIf { sendRoute.surfaceKind == RaftThreadSurfaceKind.ReplyThread }
                    )
                val loungeParentChannelId =
                    when (sendRoute.surfaceKind) {
                        RaftThreadSurfaceKind.Channel ->
                            sendBasePayload.parentChannelId?.takeIf { it.isNotBlank() } ?: context.channelId
                        RaftThreadSurfaceKind.ReplyThread ->
                            sendBasePayload.parentChannelId?.takeIf { it.isNotBlank() }
                        RaftThreadSurfaceKind.DirectMessage -> null
                        else -> sendBasePayload.parentChannelId?.takeIf { it.isNotBlank() }
                    }
                if (ownSentChannelIds.isNotEmpty()) {
                    sharedHomeStore?.dispatch(
                        SharedHomeIntent.OwnMessagesSent(
                            serverId = sendProvenance.serverId,
                            channelIds = ownSentChannelIds,
                            loungeParentChannelId = loungeParentChannelId,
                        )
                    )
                }
                // Agent Lounge v0: your first post in a Lounge joins it (no Join step), so its row
                // shows under the agent's DM right away.
                if (sendRoute.surfaceKind == RaftThreadSurfaceKind.Channel) {
                    RaftAgentLounges.markJoined(sendProvenance.serverId, context.channelId)
                }
                bridge.performanceMarker(
                    phase = "composer_send",
                    routeName = "kuikly_thread",
                    event = "end",
                    threadId = context.channelId,
                    durationMs = sendStartedAt.elapsedNow().inWholeMilliseconds,
                    metadata =
                        mapOf(
                            "result" to "success",
                            "messages" to sentPage.messages.size.toString(),
                            "attempt" to attempt.toString()
                        )
                )
                val pendingMentionActions = sentPage.pendingMentionActions.map { it.toRaftPendingMentionActionItem() }
                val externalShareTokens =
                    (context.externalShareSources.map { source -> source.token } +
                        context.attachments
                            .filter { attachment -> attachment.source == "external_share" }
                            .mapNotNull { attachment -> attachment.externalShareToken?.takeIf(String::isNotBlank) })
                        .distinct()
                if (externalShareTokens.isNotEmpty()) {
                    val envelopeRepository =
                        build.raft.app.data.RaftExternalShareEnvelopeRepositoryProvider.create()
                    externalShareTokens.forEach { token ->
                        runCatchingPreservingCancellation { envelopeRepository.delete(token) }
                            .onFailure { error ->
                                bridge.logWarn(
                                    "external_share_cleanup_failed error=${error::class.simpleName.orEmpty()}"
                                )
                            }
                    }
                }
                if (sendAuthorityStillCurrent()) {
                    if (startedFromComposer) {
                        composerDraftViewModel.dispatch(
                            SharedThreadComposerDraftIntent.CompleteSend(
                                pendingMentionActions = pendingMentionActions,
                                unresolvedMentionHandles = sentPage.unresolvedMentionHandles
                            )
                        )
                    } else {
                        composerDraftViewModel.dispatch(
                            SharedThreadComposerDraftIntent.ApplyPendingMentionActions(
                                pendingMentionActions = pendingMentionActions,
                                unresolvedMentionHandles = sentPage.unresolvedMentionHandles
                            )
                        )
                    }
                    if (pendingMentionActions.isNotEmpty() && serverMembersStore != null && threadMembersServerId != null) {
                        if (serverMembersStore.currentServerMembers(threadMembersServerId).isEmpty()) {
                            coroutineScope.launch {
                                serverMembersStore.refreshMembers(threadMembersServerId, DataRequestPriority.Background)
                            }
                        }
                    }
                } else {
                    bridge.logWarn(
                        "thread_send_success_terminal_projection_dropped localMessageId=$localMessageId " +
                            "channelId=${context.channelId} reason=owner_changed_after_response"
                    )
                }
                if (context.ownedTemporaryFileRefs.isNotEmpty()) {
                    // The local image copy for the sent bubble must be taken before the files go.
                    build.raft.app.image.RaftSentAttachmentLocalImages.registry.awaitSeeded(
                        attachmentIds = context.attachmentIds,
                        timeoutMillis = ThreadSentImageLocalSeedRetireWaitMs
                    )
                    bridge.retireFilePreviewTemporaryFiles(
                        sessionId = "thread-send-$localMessageId",
                        fileRefs = context.ownedTemporaryFileRefs
                    )
                }
                return@launch
            }
        }.invokeOnCompletion { ThreadPendingSendInFlightRegistry.end(context.randomId) }
    }
}
