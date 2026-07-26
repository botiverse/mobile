package build.raft.app.data

import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal const val ExternalShareMaximumTargetCount = 9

@Serializable
internal data class ExternalShareItem(
    val localRef: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

@Serializable
internal data class ExternalShareEnvelope(
    val token: String,
    val createdAtEpochMillis: Long,
    val sourcePlatform: String,
    val text: String = "",
    val subject: String = "",
    val items: List<ExternalShareItem> = emptyList()
) {
    fun hasContent(): Boolean =
        text.isNotBlank() || subject.isNotBlank() || items.isNotEmpty()
}

internal interface ExternalShareEnvelopeRepository {
    suspend fun load(token: String): DataResult<ExternalShareEnvelope>

    /** Idempotent by [consumptionId]; retries must not consume the native envelope twice. */
    suspend fun consume(token: String, consumptionId: String): DataResult<Unit>

    /** Explicit user discard. This is not the successful two-phase consume path. */
    suspend fun delete(token: String)
}

@Serializable
internal enum class ExternalShareDraftStage {
    SelectingTargets,
    Confirming,
    Delivering
}

@Serializable
internal data class ExternalShareDraft(
    val envelope: ExternalShareEnvelope,
    val serverId: String,
    val stage: ExternalShareDraftStage,
    val selectedTargets: List<ForwardMessageTarget> = emptyList(),
    val comment: String = "",
    val deliveries: List<ExternalShareDelivery> = emptyList(),
    val envelopeConsumed: Boolean = false
)

internal interface ExternalShareDraftRepository {
    suspend fun load(token: String): DataResult<ExternalShareDraft>

    suspend fun save(draft: ExternalShareDraft): DataResult<Unit>

    suspend fun delete(token: String): DataResult<Unit>
}

internal data class ExternalShareSendRequest(
    val attemptId: Long,
    val deliveryId: String,
    val sourceServerId: String,
    val envelope: ExternalShareEnvelope,
    val target: ForwardMessageTarget,
    val comment: String
)

internal data class ExternalShareSendAuthority(
    val accountId: String,
    val accessToken: String,
    val refreshToken: String,
    val serverId: String
)

internal interface ExternalShareSender {
    suspend fun send(request: ExternalShareSendRequest): DataResult<Unit>
}

/**
 * Production adapter for the normal composer upload/send path. Target resolution remains owned by
 * [ForwardMessageTargetRepository]; no directory or routing logic is duplicated here.
 */
internal class RealExternalShareSender(
    private val targetRepository: ForwardMessageTargetRepository,
    private val uploadAttachments: suspend (
        AttachmentUploadRequest,
        ExternalShareSendAuthority
    ) -> DataResult<AttachmentUploadResult>,
    private val sendMessage: suspend (ChannelId, String, List<AttachmentId>, String) -> DataResult<SendMessageResult>,
    private val currentAuthority: suspend () -> ExternalShareSendAuthority?
) : ExternalShareSender {
    constructor(
        targetRepository: ForwardMessageTargetRepository,
        conversationRepository: ConversationRepository,
        authorityBoundUploader: AuthorityBoundAttachmentUploader,
        sessionStore: SlockAuthSessionStore
    ) : this(
        targetRepository = targetRepository,
        uploadAttachments = { request, authority ->
            authorityBoundUploader.uploadAttachmentsBound(request, authority.toExpectedAuthBinding())
        },
        sendMessage = { channelId, body, attachmentIds, randomId ->
            conversationRepository.sendMessage(
                channelId = channelId,
                body = body,
                attachmentIds = attachmentIds,
                randomId = randomId
            )
        },
        currentAuthority = {
            sessionStore.readSession()?.let { session ->
                val serverId = session.currentServerId?.takeIf(String::isNotBlank) ?: return@let null
                ExternalShareSendAuthority(
                    accountId = session.user.id,
                    accessToken = session.accessToken,
                    refreshToken = session.refreshToken,
                    serverId = serverId
                )
            }
        }
    )

    override suspend fun send(request: ExternalShareSendRequest): DataResult<Unit> {
        val sourceServerId = request.sourceServerId.trim().takeIf { it.isNotBlank() }
            ?: return DataResult.Error(ScreenError("external_share_source_server_invalid", retryable = false))
        val authority = currentAuthority()?.takeIf { it.serverId == sourceServerId }
            ?: return DataResult.Error(ScreenError("external_share_authority_changed", retryable = true))
        val resolved =
            targetRepository.resolveTargetChannel(
                sourceServerId = ServerId(sourceServerId),
                target = request.target,
                allowJoin = false
            )
        val destination =
            when (resolved) {
                is DataResult.Content -> resolved.data
                is DataResult.Empty -> return DataResult.Empty(resolved.reason)
                is DataResult.Error -> return DataResult.Error(resolved.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(resolved.blocker)
            }
        if (currentAuthority() != authority) {
            return DataResult.Error(ScreenError("external_share_authority_changed", retryable = true))
        }
        val attachmentIds =
            if (request.envelope.items.isEmpty()) {
                emptyList()
            } else {
                when (
                    val uploaded =
                        uploadAttachments(
                            AttachmentUploadRequest(
                                channelId = destination.channelId,
                                files = request.envelope.items.mapIndexed { index, item ->
                                    item.toExternalSharePlatformFile(request.deliveryId, index)
                                }
                            ),
                            authority
                        )
                ) {
                    is DataResult.Content -> uploaded.data.attachmentIds
                    is DataResult.Empty -> return DataResult.Empty(uploaded.reason)
                    is DataResult.Error -> return DataResult.Error(uploaded.error)
                    is DataResult.NoPermission -> return DataResult.NoPermission(uploaded.blocker)
                }
            }
        if (currentAuthority() != authority) {
            return DataResult.Error(ScreenError("external_share_authority_changed", retryable = true))
        }
        val sendDestination =
            when (
                val refreshed =
                    targetRepository.resolveTargetChannel(
                        sourceServerId = ServerId(sourceServerId),
                        target = request.target,
                        allowJoin = false
                    )
            ) {
                is DataResult.Content -> refreshed.data
                is DataResult.Empty -> return DataResult.Empty(refreshed.reason)
                is DataResult.Error -> return DataResult.Error(refreshed.error)
                is DataResult.NoPermission -> return DataResult.NoPermission(refreshed.blocker)
            }
        if (sendDestination != destination || currentAuthority() != authority) {
            return DataResult.Error(ScreenError("external_share_authority_changed", retryable = true))
        }
        val sent = sendMessage(
            sendDestination.channelId,
            request.externalShareMessageBody(),
            attachmentIds,
            request.deliveryId
        )
        return when (sent) {
            is DataResult.Content -> DataResult.Content(Unit)
            is DataResult.Empty -> DataResult.Empty(sent.reason)
            is DataResult.Error -> DataResult.Error(sent.error)
            is DataResult.NoPermission -> DataResult.NoPermission(sent.blocker)
        }
    }
}

private fun ExternalShareSendAuthority.toExpectedAuthBinding(): SlockExpectedAuthBinding =
    SlockExpectedAuthBinding(
        accountId = accountId,
        accessToken = accessToken,
        refreshToken = refreshToken,
        serverId = serverId
    )

private fun ExternalShareItem.toExternalSharePlatformFile(deliveryId: String, index: Int): PlatformFile =
    PlatformFile(
        id = "external-share-$deliveryId-$index",
        fileName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        fileRef = localRef,
        source = "external_share"
    )

internal fun ExternalShareSendRequest.externalShareMessageBody(): String =
    listOf(comment, envelope.subject, envelope.text)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("\n\n")

@Serializable
internal enum class ExternalShareDeliveryStatus {
    Pending,
    Sending,
    Sent,
    Failed
}

@Serializable
internal data class ExternalShareDelivery(
    val target: ForwardMessageTarget,
    val deliveryId: String,
    val status: ExternalShareDeliveryStatus = ExternalShareDeliveryStatus.Pending,
    val errorCode: String? = null
)

internal sealed interface ExternalShareState {
    data object Idle : ExternalShareState

    data class LoadingEnvelope(val token: String) : ExternalShareState

    data class SelectingTargets(
        val envelope: ExternalShareEnvelope,
        val serverId: String,
        val query: String = "",
        val targets: List<ForwardMessageTarget> = emptyList(),
        val selectedTargets: List<ForwardMessageTarget> = emptyList(),
        val loading: Boolean = false,
        val appending: Boolean = false,
        val requestGeneration: Int = 0,
        val nextCursor: ForwardMessageTargetDirectoryCursor? = null,
        val errorCode: String? = null,
        val selectionErrorCode: String? = null
    ) : ExternalShareState

    data class Confirming(
        val envelope: ExternalShareEnvelope,
        val serverId: String,
        val selectedTargets: List<ForwardMessageTarget>,
        val comment: String = ""
    ) : ExternalShareState

    data class Sending(
        val envelope: ExternalShareEnvelope,
        val serverId: String,
        val comment: String,
        val attemptId: Long,
        val deliveries: List<ExternalShareDelivery>
    ) : ExternalShareState

    data class Completed(
        val envelope: ExternalShareEnvelope,
        val serverId: String,
        val comment: String,
        val attemptId: Long,
        val deliveries: List<ExternalShareDelivery>
    ) : ExternalShareState {
        val failedTargets: List<ForwardMessageTarget>
            get() = deliveries.filter { it.status == ExternalShareDeliveryStatus.Failed }.map { it.target }

        val sentTargets: List<ForwardMessageTarget>
            get() = deliveries.filter { it.status == ExternalShareDeliveryStatus.Sent }.map { it.target }
    }

    data class Error(
        val token: String,
        val serverId: String,
        val errorCode: String,
        val retryable: Boolean
    ) : ExternalShareState
}

internal sealed interface ExternalShareIntent {
    data class Load(val token: String, val serverId: String) : ExternalShareIntent

    data class Search(val query: String) : ExternalShareIntent

    data object LoadMore : ExternalShareIntent

    data class ToggleTarget(
        val target: ForwardMessageTarget,
        val sessionId: String,
        val renderGeneration: Int
    ) : ExternalShareIntent

    data object Continue : ExternalShareIntent

    data class RemoveTarget(val targetKey: String) : ExternalShareIntent

    data class ChangeComment(val value: String) : ExternalShareIntent

    data object Send : ExternalShareIntent

    data object RetryFailed : ExternalShareIntent

    data object BackToTargets : ExternalShareIntent

    data object Cancel : ExternalShareIntent
}

internal class ExternalShareStore(
    private val envelopeRepository: ExternalShareEnvelopeRepository,
    private val draftRepository: ExternalShareDraftRepository,
    private val targetRepository: ForwardMessageTargetRepository,
    private val sender: ExternalShareSender,
    private val scope: CoroutineScope,
    private val maximumTargetCount: Int = ExternalShareMaximumTargetCount,
    private val deliveryIdFactory: (token: String, targetKey: String) -> String = { token, targetKey ->
        "$token:$targetKey"
    },
    private val diagnosticsLogger: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow<ExternalShareState>(ExternalShareState.Idle)
    val state: StateFlow<ExternalShareState> = _state

    private var loadGeneration = 0L
    private var targetGeneration = 0L
    private var sendAttemptId = 0L
    private var loadJob: Job? = null
    private var targetJob: Job? = null
    private var sendJob: Job? = null
    private var draftWriteTail: Job? = null

    fun dispatch(intent: ExternalShareIntent) {
        when (intent) {
            is ExternalShareIntent.Load -> load(intent.token, intent.serverId)
            is ExternalShareIntent.Search -> search(intent.query)
            ExternalShareIntent.LoadMore -> loadMore()
            is ExternalShareIntent.ToggleTarget -> toggleTarget(intent)
            ExternalShareIntent.Continue -> continueToConfirmation()
            is ExternalShareIntent.RemoveTarget -> removeTarget(intent.targetKey)
            is ExternalShareIntent.ChangeComment -> changeComment(intent.value)
            ExternalShareIntent.Send -> sendSelected(retryFailedOnly = false)
            ExternalShareIntent.RetryFailed -> sendSelected(retryFailedOnly = true)
            ExternalShareIntent.BackToTargets -> backToTargets()
            ExternalShareIntent.Cancel -> cancelAndDiscard()
        }
    }

    fun clear() {
        (_state.value as? ExternalShareState.SelectingTargets)?.let { selecting ->
            targetRepository.releaseDirectorySnapshot(selecting.externalShareDirectorySessionId())
        }
        loadGeneration += 1
        targetGeneration += 1
        sendAttemptId += 1
        loadJob?.cancel()
        targetJob?.cancel()
        sendJob?.cancel()
        loadJob = null
        targetJob = null
        sendJob = null
        _state.value = ExternalShareState.Idle
    }

    private fun load(rawToken: String, rawServerId: String) {
        val token = rawToken.trim()
        val serverId = rawServerId.trim()
        if (token.isBlank() || serverId.isBlank()) {
            _state.value =
                ExternalShareState.Error(
                    token = token,
                    serverId = serverId,
                    errorCode = "external_share_identity_invalid",
                    retryable = false
                )
            return
        }
        loadGeneration += 1
        val generation = loadGeneration
        loadJob?.cancel()
        targetJob?.cancel()
        sendJob?.cancel()
        _state.value = ExternalShareState.LoadingEnvelope(token)
        loadJob =
            scope.launch {
                when (val draftResult = draftRepository.load(token)) {
                    is DataResult.Content -> restoreDraft(draftResult.data, token, serverId, generation)
                    is DataResult.Empty -> loadFreshEnvelope(token, serverId, generation)
                    is DataResult.Error ->
                        setLoadError(token, serverId, draftResult.error.code, draftResult.error.retryable, generation)
                    is DataResult.NoPermission ->
                        setLoadError(token, serverId, draftResult.blocker.code, false, generation)
                }
            }
    }

    private suspend fun loadFreshEnvelope(token: String, serverId: String, generation: Long) {
        when (val result = envelopeRepository.load(token)) {
            is DataResult.Content -> {
                if (generation != loadGeneration) return
                val envelope = result.data
                if (envelope.token != token || !envelope.hasContent()) {
                    setLoadError(token, serverId, "external_share_envelope_invalid", false, generation)
                    return
                }
                val draft =
                    ExternalShareDraft(
                        envelope = envelope,
                        serverId = serverId,
                        stage = ExternalShareDraftStage.SelectingTargets
                    )
                when (val transfer = persistAndConsumeEnvelope(draft)) {
                    is DataResult.Content -> restoreDraft(transfer.data, token, serverId, generation)
                    is DataResult.Empty ->
                        setLoadError(token, serverId, "external_share_draft_persist_empty", true, generation)
                    is DataResult.Error ->
                        setLoadError(token, serverId, transfer.error.code, transfer.error.retryable, generation)
                    is DataResult.NoPermission ->
                        setLoadError(token, serverId, transfer.blocker.code, false, generation)
                }
            }
            is DataResult.Empty -> setLoadError(token, serverId, "external_share_not_found", false, generation)
            is DataResult.Error -> setLoadError(token, serverId, result.error.code, result.error.retryable, generation)
            is DataResult.NoPermission -> setLoadError(token, serverId, result.blocker.code, false, generation)
        }
    }

    private suspend fun persistAndConsumeEnvelope(draft: ExternalShareDraft): DataResult<ExternalShareDraft> {
        val initialSave = draftRepository.save(draft)
        if (initialSave !is DataResult.Content) return initialSave.mapDraftFailure()
        if (!draft.envelopeConsumed) {
            val consume =
                try {
                    envelopeRepository.consume(
                        token = draft.envelope.token,
                        consumptionId = draft.envelope.externalShareConsumptionId()
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    DataResult.Error(ScreenError("external_share_envelope_consume_failed", retryable = true))
                }
            when (consume) {
                is DataResult.Content -> Unit
                is DataResult.Empty ->
                    return DataResult.Error(
                        ScreenError("external_share_envelope_consume_empty", retryable = true)
                    )
                is DataResult.Error -> return consume
                is DataResult.NoPermission -> return consume
            }
        }
        val consumedDraft = draft.copy(envelopeConsumed = true)
        return when (val consumedSave = draftRepository.save(consumedDraft)) {
            is DataResult.Content -> DataResult.Content(consumedDraft)
            is DataResult.Empty -> DataResult.Error(ScreenError("external_share_draft_persist_empty", retryable = true))
            is DataResult.Error -> consumedSave
            is DataResult.NoPermission -> consumedSave
        }
    }

    private suspend fun restoreDraft(
        draft: ExternalShareDraft,
        token: String,
        serverId: String,
        generation: Long
    ) {
        if (generation != loadGeneration) return
        if (!draft.isValidFor(token, serverId, maximumTargetCount)) {
            setLoadError(token, serverId, "external_share_draft_invalid", false, generation)
            return
        }
        val readyDraft =
            if (draft.envelopeConsumed) {
                draft
            } else {
                when (val transfer = persistAndConsumeEnvelope(draft)) {
                    is DataResult.Content -> transfer.data
                    is DataResult.Empty -> {
                        setLoadError(token, serverId, "external_share_draft_persist_empty", true, generation)
                        return
                    }
                    is DataResult.Error -> {
                        setLoadError(token, serverId, transfer.error.code, transfer.error.retryable, generation)
                        return
                    }
                    is DataResult.NoPermission -> {
                        setLoadError(token, serverId, transfer.blocker.code, false, generation)
                        return
                    }
                }
            }
        if (generation != loadGeneration) return
        when (readyDraft.stage) {
            ExternalShareDraftStage.SelectingTargets -> {
                _state.value =
                    ExternalShareState.SelectingTargets(
                        envelope = readyDraft.envelope,
                        serverId = readyDraft.serverId,
                        selectedTargets = readyDraft.selectedTargets
                    )
                search("")
            }
            ExternalShareDraftStage.Confirming -> {
                _state.value =
                    ExternalShareState.Confirming(
                        envelope = readyDraft.envelope,
                        serverId = readyDraft.serverId,
                        selectedTargets = readyDraft.selectedTargets,
                        comment = readyDraft.comment
                    )
            }
            ExternalShareDraftStage.Delivering -> restoreDeliveriesWithoutReplay(readyDraft, generation)
        }
    }

    private fun setLoadError(
        token: String,
        serverId: String,
        errorCode: String,
        retryable: Boolean,
        generation: Long
    ) {
        if (generation != loadGeneration) return
        _state.value = ExternalShareState.Error(token, serverId, errorCode, retryable)
    }

    private suspend fun restoreDeliveriesWithoutReplay(draft: ExternalShareDraft, generation: Long) {
        if (generation != loadGeneration) return
        val restoredDeliveries =
            draft.deliveries.map { delivery ->
                if (delivery.status == ExternalShareDeliveryStatus.Sent) {
                    delivery
                } else {
                    delivery.copy(
                        status = ExternalShareDeliveryStatus.Failed,
                        errorCode = delivery.errorCode ?: "external_share_send_interrupted"
                    )
                }
            }
        val restoredDraft = draft.copy(deliveries = restoredDeliveries)
        if (restoredDraft != draft) {
            when (val result = draftRepository.save(restoredDraft)) {
                is DataResult.Content -> Unit
                is DataResult.Empty -> {
                    setLoadError(
                        draft.envelope.token,
                        draft.serverId,
                        "external_share_draft_persist_empty",
                        true,
                        generation
                    )
                    return
                }
                is DataResult.Error -> {
                    setLoadError(
                        draft.envelope.token,
                        draft.serverId,
                        result.error.code,
                        result.error.retryable,
                        generation
                    )
                    return
                }
                is DataResult.NoPermission -> {
                    setLoadError(
                        draft.envelope.token,
                        draft.serverId,
                        result.blocker.code,
                        false,
                        generation
                    )
                    return
                }
            }
        }
        if (generation != loadGeneration) return
        val attemptId = (sendAttemptId + 1).also { sendAttemptId = it }
        _state.value =
            ExternalShareState.Completed(
                envelope = restoredDraft.envelope,
                serverId = restoredDraft.serverId,
                comment = restoredDraft.comment,
                attemptId = attemptId,
                deliveries = restoredDeliveries
            )
    }

    private fun persistCurrentDraft() {
        val draft = _state.value.toExternalShareDraft() ?: return
        val previousWrite = draftWriteTail
        draftWriteTail =
            scope.launch {
                previousWrite?.join()
                when (val result = draftRepository.save(draft)) {
                    is DataResult.Content -> Unit
                    is DataResult.Empty ->
                        diagnosticsLogger("external_share_draft_save_failed code=external_share_draft_persist_empty")
                    is DataResult.Error ->
                        diagnosticsLogger("external_share_draft_save_failed code=${result.error.code}")
                    is DataResult.NoPermission ->
                        diagnosticsLogger("external_share_draft_save_failed code=${result.blocker.code}")
                }
            }
    }

    private fun search(rawQuery: String, append: Boolean = false) {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        val query = rawQuery.trimStart()
        if (append && (query.isNotBlank() || current.nextCursor == null || current.loading)) return
        if (!append && current.query != query && current.query.isBlank()) {
            targetRepository.releaseDirectorySnapshot(current.externalShareDirectorySessionId())
        }
        targetGeneration += 1
        val generation = targetGeneration
        targetJob?.cancel()
        _state.value =
            current.copy(
                query = query,
                loading = true,
                appending = append,
                requestGeneration = current.requestGeneration + 1,
                nextCursor = if (append) current.nextCursor else null,
                errorCode = null,
                selectionErrorCode = null
            )
        targetJob =
            scope.launch {
                val result =
                    if (query.isBlank()) {
                        targetRepository.directoryTargets(
                            serverId = ServerId(current.serverId),
                            sessionId = current.externalShareDirectorySessionId(),
                            cursor = if (append) current.nextCursor ?: ForwardMessageTargetDirectoryCursor() else ForwardMessageTargetDirectoryCursor(),
                            pageSize = 40
                        )
                    } else {
                        when (val searched = targetRepository.searchTargets(ServerId(current.serverId), query, 40)) {
                            is DataResult.Content ->
                                DataResult.Content(ForwardMessageTargetDirectoryPage(items = searched.data))
                            is DataResult.Empty -> DataResult.Empty(searched.reason)
                            is DataResult.Error -> DataResult.Error(searched.error)
                            is DataResult.NoPermission -> DataResult.NoPermission(searched.blocker)
                        }
                    }
                if (generation != targetGeneration) return@launch
                val latest = _state.value as? ExternalShareState.SelectingTargets ?: return@launch
                if (latest.envelope.token != current.envelope.token || latest.serverId != current.serverId) return@launch
                _state.value =
                    when (result) {
                        is DataResult.Content ->
                            latest.copy(
                                targets =
                                    if (append && !result.data.reset) {
                                        (latest.targets + result.data.items).distinctBy { it.externalShareSelectionKey() }
                                    } else {
                                        result.data.items.distinctBy { it.externalShareSelectionKey() }
                                    },
                                loading = false,
                                appending = false,
                                nextCursor = result.data.nextCursor,
                                errorCode = null
                            )
                        is DataResult.Empty -> latest.copy(targets = if (append) latest.targets else emptyList(), loading = false, appending = false, nextCursor = null, errorCode = null)
                        is DataResult.Error -> latest.copy(loading = false, appending = false, errorCode = result.error.code)
                        is DataResult.NoPermission -> latest.copy(loading = false, appending = false, errorCode = result.blocker.code)
                    }
            }
    }

    private fun loadMore() {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        search(current.query, append = true)
    }

    private fun toggleTarget(intent: ExternalShareIntent.ToggleTarget) {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        val target = intent.target
        if (
            intent.sessionId != current.externalShareDirectorySessionId() ||
            intent.renderGeneration != current.requestGeneration ||
            current.loading ||
            current.errorCode != null
        ) {
            return
        }
        if (target.serverId.value != current.serverId) {
            _state.value = current.copy(selectionErrorCode = "external_share_target_server_mismatch")
            return
        }
        val key = target.externalShareSelectionKey()
        val selected = current.selectedTargets
        val liveTarget = current.targets.firstOrNull { it.externalShareSelectionKey() == key }
        val selectedTarget = selected.firstOrNull { it.externalShareSelectionKey() == key }
        if (target != liveTarget && target != selectedTarget) return
        if (target.requiredAction == ForwardMessageTargetRequiredAction.JoinChannel && selectedTarget == null) {
            _state.value = current.copy(selectionErrorCode = "external_share_join_confirmation_required")
            return
        }
        _state.value =
            if (selected.any { it.externalShareSelectionKey() == key }) {
                current.copy(
                    selectedTargets = selected.filterNot { it.externalShareSelectionKey() == key },
                    selectionErrorCode = null
                )
            } else if (selected.size >= maximumTargetCount) {
                current.copy(selectionErrorCode = "external_share_target_limit")
            } else {
                current.copy(selectedTargets = selected + target, selectionErrorCode = null)
            }
        persistCurrentDraft()
    }

    private fun continueToConfirmation() {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        if (current.selectedTargets.isEmpty()) {
            _state.value = current.copy(selectionErrorCode = "external_share_no_targets")
            return
        }
        targetGeneration += 1
        targetJob?.cancel()
        targetRepository.releaseDirectorySnapshot(current.externalShareDirectorySessionId())
        _state.value =
            ExternalShareState.Confirming(
                envelope = current.envelope,
                serverId = current.serverId,
                selectedTargets = current.selectedTargets
            )
        persistCurrentDraft()
    }

    private fun removeTarget(targetKey: String) {
        val current = _state.value as? ExternalShareState.Confirming ?: return
        val nextTargets = current.selectedTargets.filterNot { it.externalShareSelectionKey() == targetKey }
        _state.value =
            if (nextTargets.isEmpty()) {
                ExternalShareState.SelectingTargets(
                    envelope = current.envelope,
                    serverId = current.serverId
                )
            } else {
                current.copy(selectedTargets = nextTargets)
            }
        persistCurrentDraft()
    }

    private fun changeComment(value: String) {
        val current = _state.value as? ExternalShareState.Confirming ?: return
        _state.value = current.copy(comment = value)
        persistCurrentDraft()
    }

    private fun backToTargets() {
        val current = _state.value as? ExternalShareState.Confirming ?: return
        _state.value =
            ExternalShareState.SelectingTargets(
                envelope = current.envelope,
                serverId = current.serverId,
                selectedTargets = current.selectedTargets
            )
        persistCurrentDraft()
        search("")
    }

    private fun sendSelected(retryFailedOnly: Boolean) {
        val initialDeliveries: List<ExternalShareDelivery>
        val envelope: ExternalShareEnvelope
        val serverId: String
        val comment: String
        when (val current = _state.value) {
            is ExternalShareState.Confirming -> {
                if (retryFailedOnly || current.selectedTargets.isEmpty()) return
                envelope = current.envelope
                serverId = current.serverId
                comment = current.comment
                initialDeliveries =
                    current.selectedTargets.map { target ->
                        ExternalShareDelivery(
                            target = target,
                            deliveryId = deliveryIdFactory(
                                current.envelope.token,
                                target.externalShareSelectionKey()
                            )
                        )
                    }
            }
            is ExternalShareState.Completed -> {
                if (!retryFailedOnly || current.failedTargets.isEmpty()) return
                envelope = current.envelope
                serverId = current.serverId
                comment = current.comment
                initialDeliveries = current.deliveries
            }
            else -> return
        }

        sendAttemptId += 1
        val attemptId = sendAttemptId
        sendJob?.cancel()
        _state.value =
            ExternalShareState.Sending(
                envelope = envelope,
                serverId = serverId,
                comment = comment,
                attemptId = attemptId,
                deliveries = initialDeliveries
            )
        sendJob =
            scope.launch {
                var deliveries = initialDeliveries
                draftWriteTail?.join()
                val initialDraft =
                    ExternalShareDraft(
                        envelope = envelope,
                        serverId = serverId,
                        stage = ExternalShareDraftStage.Delivering,
                        selectedTargets = deliveries.map { it.target },
                        comment = comment,
                        deliveries = deliveries,
                        envelopeConsumed = true
                    )
                if (!saveDeliveryDraftOrFail(initialDraft, attemptId)) return@launch
                val targetKeys =
                    if (retryFailedOnly) {
                        deliveries.filter { it.status == ExternalShareDeliveryStatus.Failed }
                    } else {
                        deliveries
                    }.map { it.target.externalShareSelectionKey() }.toSet()
                for (index in deliveries.indices) {
                    val delivery = deliveries[index]
                    val key = delivery.target.externalShareSelectionKey()
                    if (key !in targetKeys) continue
                    if (attemptId != sendAttemptId) return@launch
                    deliveries =
                        deliveries.replaceDelivery(
                            key,
                            delivery.copy(
                                status = ExternalShareDeliveryStatus.Sending,
                                errorCode = null
                            )
                        )
                    _state.value = ExternalShareState.Sending(envelope, serverId, comment, attemptId, deliveries)
                    val sendingDraft = initialDraft.copy(deliveries = deliveries)
                    if (!saveDeliveryDraftOrFail(sendingDraft, attemptId)) return@launch
                    val result =
                        try {
                            sender.send(
                                ExternalShareSendRequest(
                                    attemptId = attemptId,
                                    deliveryId = delivery.deliveryId,
                                    sourceServerId = serverId,
                                    envelope = envelope,
                                    target = delivery.target,
                                    comment = comment
                                )
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (throwable: Throwable) {
                            DataResult.Error(ScreenError("external_share_send_exception", retryable = true))
                        }
                    if (attemptId != sendAttemptId) return@launch
                    val terminal =
                        when (result) {
                            is DataResult.Content ->
                                delivery.copy(
                                    status = ExternalShareDeliveryStatus.Sent,
                                    errorCode = null
                                )
                            is DataResult.Empty ->
                                delivery.copy(
                                    status = ExternalShareDeliveryStatus.Failed,
                                    errorCode = "external_share_send_empty"
                                )
                            is DataResult.Error ->
                                delivery.copy(
                                    status = ExternalShareDeliveryStatus.Failed,
                                    errorCode = result.error.code
                                )
                            is DataResult.NoPermission ->
                                delivery.copy(
                                    status = ExternalShareDeliveryStatus.Failed,
                                    errorCode = result.blocker.code
                                )
                        }
                    deliveries = deliveries.replaceDelivery(key, terminal)
                    _state.value = ExternalShareState.Sending(envelope, serverId, comment, attemptId, deliveries)
                    if (!saveDeliveryDraftOrFail(initialDraft.copy(deliveries = deliveries), attemptId)) return@launch
                }
                if (attemptId != sendAttemptId) return@launch
                _state.value = ExternalShareState.Completed(envelope, serverId, comment, attemptId, deliveries)
                if (deliveries.none { it.status == ExternalShareDeliveryStatus.Failed }) {
                    when (val deleteResult = draftRepository.delete(envelope.token)) {
                        is DataResult.Content -> Unit
                        is DataResult.Empty ->
                            diagnosticsLogger("external_share_draft_delete_empty token=${envelope.token}")
                        is DataResult.Error ->
                            diagnosticsLogger("external_share_draft_delete_failed code=${deleteResult.error.code}")
                        is DataResult.NoPermission ->
                            diagnosticsLogger("external_share_draft_delete_failed code=${deleteResult.blocker.code}")
                    }
                }
                diagnosticsLogger(
                    "external_share_send_complete attempt=$attemptId " +
                        "sent=${deliveries.count { it.status == ExternalShareDeliveryStatus.Sent }} " +
                        "failed=${deliveries.count { it.status == ExternalShareDeliveryStatus.Failed }}"
                )
            }
    }

    private suspend fun saveDeliveryDraftOrFail(draft: ExternalShareDraft, attemptId: Long): Boolean {
        return when (val result = draftRepository.save(draft)) {
            is DataResult.Content -> true
            is DataResult.Empty -> {
                failDeliveryPersistence(draft, attemptId, "external_share_draft_persist_empty")
                false
            }
            is DataResult.Error -> {
                failDeliveryPersistence(draft, attemptId, result.error.code)
                false
            }
            is DataResult.NoPermission -> {
                failDeliveryPersistence(draft, attemptId, result.blocker.code)
                false
            }
        }
    }

    private fun failDeliveryPersistence(draft: ExternalShareDraft, attemptId: Long, errorCode: String) {
        val failedDeliveries =
            draft.deliveries.map { delivery ->
                if (delivery.status == ExternalShareDeliveryStatus.Sent) {
                    delivery
                } else {
                    delivery.copy(
                        status = ExternalShareDeliveryStatus.Failed,
                        errorCode = errorCode
                    )
                }
            }
        _state.value =
            ExternalShareState.Completed(
                envelope = draft.envelope,
                serverId = draft.serverId,
                comment = draft.comment,
                attemptId = attemptId,
                deliveries = failedDeliveries
            )
        diagnosticsLogger("external_share_draft_save_failed code=$errorCode")
    }

    private fun cancelAndDiscard() {
        val current = _state.value
        val token = current.externalShareTokenOrNull()
        val discardNativeEnvelope =
            current is ExternalShareState.LoadingEnvelope || current is ExternalShareState.Error
        clear()
        token?.takeIf { it.isNotBlank() }?.let { discardedToken ->
            val previousWrite = draftWriteTail
            draftWriteTail =
                scope.launch {
                    previousWrite?.join()
                    if (discardNativeEnvelope) {
                        runCatching { envelopeRepository.delete(discardedToken) }
                    }
                    draftRepository.delete(discardedToken)
                }
        }
    }
}

private fun ExternalShareState.externalShareTokenOrNull(): String? =
    when (this) {
        is ExternalShareState.LoadingEnvelope -> token
        is ExternalShareState.SelectingTargets -> envelope.token
        is ExternalShareState.Confirming -> envelope.token
        is ExternalShareState.Sending -> envelope.token
        is ExternalShareState.Completed -> envelope.token
        is ExternalShareState.Error -> token
        ExternalShareState.Idle -> null
    }

internal fun ExternalShareEnvelope.externalShareConsumptionId(): String =
    "external-share-consume:$token"

internal fun ForwardMessageTarget.externalShareSelectionKey(): String =
    "${serverId.value}:${kind.name}:$id"

internal fun ExternalShareState.SelectingTargets.externalShareDirectorySessionId(): String =
    "external-share:${envelope.token}:$serverId"

private fun List<ExternalShareDelivery>.replaceDelivery(
    targetKey: String,
    replacement: ExternalShareDelivery
): List<ExternalShareDelivery> =
    map { delivery ->
        if (delivery.target.externalShareSelectionKey() == targetKey) replacement else delivery
    }

private fun DataResult<Unit>.mapDraftFailure(): DataResult<ExternalShareDraft> =
    when (this) {
        is DataResult.Content ->
            DataResult.Error(ScreenError("external_share_draft_persist_invalid", retryable = true))
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
    }

private fun ExternalShareDraft.isValidFor(
    token: String,
    requestedServerId: String,
    maximumTargetCount: Int
): Boolean {
    if (envelope.token != token || !envelope.hasContent()) return false
    if (serverId.isBlank() || serverId != requestedServerId) return false
    if (selectedTargets.size > maximumTargetCount) return false
    val selectedKeys = selectedTargets.map { it.externalShareSelectionKey() }
    if (selectedKeys.size != selectedKeys.toSet().size) return false
    if (selectedTargets.any { it.id.isBlank() || it.serverId.value != serverId }) return false
    if (selectedTargets.any { it.requiredAction == ForwardMessageTargetRequiredAction.JoinChannel }) return false
    return when (stage) {
        ExternalShareDraftStage.SelectingTargets -> deliveries.isEmpty()
        ExternalShareDraftStage.Confirming -> selectedTargets.isNotEmpty() && deliveries.isEmpty()
        ExternalShareDraftStage.Delivering -> {
            if (selectedTargets.isEmpty() || deliveries.isEmpty()) return false
            val deliveryKeys = deliveries.map { it.target.externalShareSelectionKey() }
            deliveryKeys == selectedKeys &&
                deliveries.all { it.deliveryId.isNotBlank() } &&
                deliveries.map { it.deliveryId }.distinct().size == deliveries.size
        }
    }
}

private fun ExternalShareState.toExternalShareDraft(): ExternalShareDraft? =
    when (this) {
        is ExternalShareState.SelectingTargets ->
            ExternalShareDraft(
                envelope = envelope,
                serverId = serverId,
                stage = ExternalShareDraftStage.SelectingTargets,
                selectedTargets = selectedTargets,
                envelopeConsumed = true
            )
        is ExternalShareState.Confirming ->
            ExternalShareDraft(
                envelope = envelope,
                serverId = serverId,
                stage = ExternalShareDraftStage.Confirming,
                selectedTargets = selectedTargets,
                comment = comment,
                envelopeConsumed = true
            )
        is ExternalShareState.Sending ->
            ExternalShareDraft(
                envelope = envelope,
                serverId = serverId,
                stage = ExternalShareDraftStage.Delivering,
                selectedTargets = deliveries.map { it.target },
                comment = comment,
                deliveries = deliveries,
                envelopeConsumed = true
            )
        is ExternalShareState.Completed ->
            ExternalShareDraft(
                envelope = envelope,
                serverId = serverId,
                stage = ExternalShareDraftStage.Delivering,
                selectedTargets = deliveries.map { it.target },
                comment = comment,
                deliveries = deliveries,
                envelopeConsumed = true
            )
        ExternalShareState.Idle,
        is ExternalShareState.LoadingEnvelope,
        is ExternalShareState.Error -> null
    }
