package build.raft.app.thread

import build.raft.app.data.AttachmentUploadCapabilities
import build.raft.app.data.AttachmentUploadCapabilitiesCache
import build.raft.app.data.AttachmentUploadRequest
import build.raft.app.data.AttachmentUploadResult
import build.raft.app.data.ConversationRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.DirectAttachmentUploadOperation
import build.raft.app.data.PlatformFile
import build.raft.app.data.ProgressiveConversationAttachmentUploader
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftExpectedAuthBinding
import build.raft.app.data.RaftThreadApi
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.data.map
import build.raft.app.data.matchesExpectedAuthority
import build.raft.app.data.normalizedEnvironmentId
import build.raft.app.model.ChannelId
import build.raft.app.traceId

/**
 * The attachment paths of [SharedThreadDataSource] (upload, the upload authority and its limits,
 * attachment URLs and the files list), moved out of it unchanged; the data source forwards to them.
 * Holds no state of its own: the upload-limits cache is the instance the data source was given.
 */
internal class ThreadAttachmentTransfers(
    private val api: RaftThreadApi,
    private val sessionStore: RaftAuthSessionStore,
    private val conversationRepository: ConversationRepository?,
    private val apiEnvironmentIdProvider: () -> String,
    private val attachmentUploadCapabilitiesCache: AttachmentUploadCapabilitiesCache,
) {
    suspend fun uploadAttachments(
        channelId: String,
        files: List<PlatformFile>,
        onProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)? = null
    ): DataResult<AttachmentUploadResult> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "attachment_upload_unavailable", retryable = false))
        if (channelId.isBlank()) {
            return DataResult.Error(ScreenError(code = "missing_channel_id", retryable = false))
        }
        if (files.isEmpty()) {
            return DataResult.Error(ScreenError(code = "attachment_read_failed", retryable = false))
        }
        val request =
            AttachmentUploadRequest(
                channelId = ChannelId(channelId),
                files = files
            )
        val progressive = repository as? ProgressiveConversationAttachmentUploader
        return if (onProgress != null && progressive != null) {
            progressive.uploadAttachmentsWithProgress(request, onProgress)
        } else {
            repository.uploadAttachments(request)
        }
    }

    /**
     * Capture one complete authority witness and bind it to one direct-session coordinator.  The
     * returned operation must be reused for capability admission, every upload mutation and all
     * later compensation; this data source intentionally exposes no reconstruct-on-cleanup seam.
     */
    suspend fun captureDirectAttachmentUploadOperation(
        diagnosticsLogger: (String) -> Unit = {},
    ): DirectAttachmentUploadOperation? {
        val repository =
            conversationRepository
                ?: return null
        val authority = captureExternalShareSendAuthority() ?: return null
        return (repository as? RealConversationRepository)
            ?.directAttachmentUploadOperation(authority, diagnosticsLogger)
    }

    /** Fresh cached capabilities for [operation]'s authority; never suspends or hits the network. */
    fun peekAttachmentUploadCapabilities(
        operation: DirectAttachmentUploadOperation,
    ): AttachmentUploadCapabilities? = attachmentUploadCapabilitiesCache.peek(operation.authority)

    /** Cached (TTL) or single-flight fetched capabilities for the operation's authority. */
    suspend fun attachmentUploadCapabilities(
        operation: DirectAttachmentUploadOperation,
    ): DataResult<AttachmentUploadCapabilities> =
        attachmentUploadCapabilitiesCache.getOrFetch(operation.authority) { operation.capabilities() }

    /** Warm the capability cache when the composer opens so the first pick needs no round trip. */
    suspend fun prefetchAttachmentUploadCapabilities() {
        val operation = captureDirectAttachmentUploadOperation() ?: return
        attachmentUploadCapabilities(operation)
    }

    suspend fun attachmentUploadMaxBytes(
        expectedAuthority: RaftExpectedAuthBinding? = null,
    ): DataResult<Long> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(
                    ScreenError(code = "attachment_upload_capabilities_unavailable", retryable = true)
                )
        val boundRepository =
            expectedAuthority?.let { authority ->
                (repository as? RealConversationRepository)?.boundTo(authority)
                    ?: return DataResult.Error(
                        ScreenError(
                            code = "attachment_upload_authority_binding_unsupported",
                            retryable = false,
                        )
                    )
            } ?: repository
        return boundRepository.getAttachmentUploadCapabilities().map { capabilities -> capabilities.maxBytes }
    }

    suspend fun captureExternalShareSendAuthority(): RaftExpectedAuthBinding? {
        val session = sessionStore.readSession() ?: return null
        val environmentId = apiEnvironmentIdProvider().normalizedEnvironmentId().takeIf { it.isNotBlank() } ?: return null
        val accountId = session.user.id.trim().takeIf { it.isNotBlank() } ?: return null
        val accessToken = session.accessToken.takeIf { it.isNotBlank() } ?: return null
        val refreshToken = session.refreshToken.takeIf { it.isNotBlank() } ?: return null
        val serverId = session.currentServerId?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (session.servers.none { server -> server.id.trim() == serverId }) return null
        return RaftExpectedAuthBinding(
            accountId = accountId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            serverId = serverId,
            environmentId = environmentId,
        )
    }

    suspend fun matchesExternalShareSendAuthority(expected: RaftExpectedAuthBinding): Boolean {
        val session = sessionStore.readSession() ?: return false
        val environmentId = apiEnvironmentIdProvider().normalizedEnvironmentId()
        val expectedEnvironment = expected.environmentId.normalizedEnvironmentId()
        return expectedEnvironment.isNotBlank() &&
            environmentId == expectedEnvironment &&
            session.matchesExpectedAuthority(
                expected = expected,
                effectiveServerId = expected.serverId,
                effectiveEnvironmentId = environmentId,
            ) &&
            session.servers.any { server -> server.id.trim() == expected.serverId.trim() }
    }

    suspend fun attachmentUrl(
        priority: DataRequestPriority,
        attachmentId: String,
        disposition: String? = null
    ): ThreadApiResult<ThreadAttachmentOpenTarget> =
        api.attachmentUrl(priority, attachmentId, disposition)

    suspend fun attachmentUrls(
        priority: DataRequestPriority,
        attachmentIds: List<String>,
    ): ThreadApiResult<Map<String, ThreadAttachmentOpenTarget>> =
        api.attachmentUrls(priority, attachmentIds)

    suspend fun attachmentHtmlPreviewUrl(attachmentId: String): ThreadApiResult<ThreadAttachmentOpenTarget> =
        api.attachmentHtmlPreviewUrl(attachmentId)

    suspend fun fetchFilesPayload(
        basePayload: RaftThreadScreenPayload,
        cursor: String? = null,
        currentPayload: RaftThreadFilesPayload? = null
    ): DataResult<RaftThreadFilesPayload> {
        val repository =
            conversationRepository
                ?: return DataResult.Error(ScreenError(code = "thread_files_unavailable", retryable = false, traceId = null))
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false, traceId = null))
        return when (val result = repository.getChannelFiles(ChannelId(threadId), cursor = cursor)) {
            is DataResult.Content ->
                DataResult.Content(
                    result.data.toRaftThreadFilesPayload(
                        basePayload = basePayload,
                        currentPayload = currentPayload,
                        append = cursor != null
                    )
                )
            is DataResult.Empty ->
                DataResult.Content(
                    currentPayload
                        ?.takeIf { cursor != null && it.items.isNotEmpty() }
                        ?.copy(
                            status = "content",
                            hasMore = false,
                            loadingMore = false,
                            nextCursor = null,
                            errorCode = null,
                            errorServerMessage = null,
                            errorTraceId = null
                        )
                        ?: RaftThreadFilesPayload(
                            instanceId = basePayload.instanceId,
                            status = "empty",
                            items = emptyList(),
                            hasMore = false,
                            loadingMore = false,
                            nextCursor = null
                        )
                )
            is DataResult.Error -> result
            is DataResult.NoPermission -> result
        }
    }
}
