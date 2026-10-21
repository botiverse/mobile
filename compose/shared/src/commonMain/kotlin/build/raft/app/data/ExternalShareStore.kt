package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal const val ExternalShareMaximumTargetCount = 1

internal data class ExternalShareServerOption(
    val id: String,
    val title: String,
    val slug: String? = null
)

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

    /** Marks the native envelope claimed without deleting its private files. */
    suspend fun consume(token: String, consumptionId: String): DataResult<Unit>

    /** Deletes the native envelope and its private files after send or explicit discard. */
    suspend fun delete(token: String)
}

/**
 * Confirming and Delivering remain decodable only so pre-task-376 drafts fail closed instead of
 * replaying an old upload/send side effect. New drafts use SelectingTargets or ComposerHandoff.
 */
@Serializable
internal enum class ExternalShareDraftStage {
    SelectingTargets,
    ComposerHandoff,
    Confirming,
    Delivering
}

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

@Serializable
internal data class ExternalShareDraft(
    val envelope: ExternalShareEnvelope,
    val serverId: String,
    val stage: ExternalShareDraftStage,
    val selectedTargets: List<ForwardMessageTarget> = emptyList(),
    // Legacy fields retained for backwards-compatible decoding only.
    val comment: String = "",
    val deliveries: List<ExternalShareDelivery> = emptyList(),
    val envelopeConsumed: Boolean = false
)

internal interface ExternalShareDraftRepository {
    suspend fun load(token: String): DataResult<ExternalShareDraft>

    suspend fun save(draft: ExternalShareDraft): DataResult<Unit>

    suspend fun delete(token: String): DataResult<Unit>
}

/** Content-bearing in-process handoff. It is never serialized into route/pageData. */
internal data class ExternalShareComposerHandoff(
    val envelope: ExternalShareEnvelope,
    val accountId: String,
    val serverId: String,
    val target: ForwardMessageTarget,
    val handoffId: Long
)

internal sealed interface ExternalShareState {
    data object Idle : ExternalShareState

    data class LoadingEnvelope(val token: String) : ExternalShareState

    data class SelectingTargets(
        val envelope: ExternalShareEnvelope,
        val accountId: String,
        val serverId: String,
        val serverOptions: List<ExternalShareServerOption> = emptyList(),
        val query: String = "",
        val targets: List<ForwardMessageTarget> = emptyList(),
        val selectedTargets: List<ForwardMessageTarget> = emptyList(),
        val loading: Boolean = false,
        val appending: Boolean = false,
        val handoffInProgress: Boolean = false,
        val requestGeneration: Int = 0,
        val nextCursor: ForwardMessageTargetDirectoryCursor? = null,
        val errorCode: String? = null,
        val selectionErrorCode: String? = null
    ) : ExternalShareState

    data class ReadyForComposer(
        val handoff: ExternalShareComposerHandoff
    ) : ExternalShareState

    data class Error(
        val token: String,
        val serverId: String,
        val errorCode: String,
        val retryable: Boolean
    ) : ExternalShareState
}

internal sealed interface ExternalShareIntent {
    data class Load(
        val token: String,
        val accountId: String,
        val serverId: String,
        val serverOptions: List<ExternalShareServerOption> =
            listOf(ExternalShareServerOption(id = serverId, title = serverId))
    ) : ExternalShareIntent

    data class SelectServer(val serverId: String) : ExternalShareIntent

    data class Search(val query: String) : ExternalShareIntent

    data object LoadMore : ExternalShareIntent

    data class ToggleTarget(
        val target: ForwardMessageTarget,
        val sessionId: String,
        val renderGeneration: Int
    ) : ExternalShareIntent

    data object Continue : ExternalShareIntent

    data class HandoffCompleted(val handoffId: Long) : ExternalShareIntent

    data class HandoffFailed(val handoffId: Long) : ExternalShareIntent

    data object Cancel : ExternalShareIntent
}

internal class ExternalShareStore(
    private val envelopeRepository: ExternalShareEnvelopeRepository,
    private val draftRepository: ExternalShareDraftRepository,
    private val targetRepository: ForwardMessageTargetRepository,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow<ExternalShareState>(ExternalShareState.Idle)
    val state: StateFlow<ExternalShareState> = _state

    private var loadGeneration = 0L
    private var targetGeneration = 0L
    private var handoffGeneration = 0L
    private var loadJob: Job? = null
    private var targetJob: Job? = null
    private var handoffJob: Job? = null
    private var draftWriteTail: Job? = null
    private var authorizedServerOptions: List<ExternalShareServerOption> = emptyList()

    fun dispatch(intent: ExternalShareIntent) {
        when (intent) {
            is ExternalShareIntent.Load -> load(intent)
            is ExternalShareIntent.SelectServer -> selectServer(intent.serverId)
            is ExternalShareIntent.Search -> search(intent.query)
            ExternalShareIntent.LoadMore -> loadMore()
            is ExternalShareIntent.ToggleTarget -> toggleTarget(intent)
            ExternalShareIntent.Continue -> continueToComposer()
            is ExternalShareIntent.HandoffCompleted -> completeHandoff(intent.handoffId)
            is ExternalShareIntent.HandoffFailed -> failReadyHandoff(intent.handoffId)
            ExternalShareIntent.Cancel -> cancelAndDiscard()
        }
    }

    fun clear() {
        (_state.value as? ExternalShareState.SelectingTargets)?.let { selecting ->
            targetRepository.releaseDirectorySnapshot(selecting.externalShareDirectorySessionId())
        }
        loadGeneration += 1
        targetGeneration += 1
        handoffGeneration += 1
        loadJob?.cancel()
        targetJob?.cancel()
        handoffJob?.cancel()
        loadJob = null
        targetJob = null
        handoffJob = null
        authorizedServerOptions = emptyList()
        _state.value = ExternalShareState.Idle
    }

    private fun load(intent: ExternalShareIntent.Load) {
        val token = intent.token.trim()
        val accountId = intent.accountId.trim()
        val serverId = intent.serverId.trim()
        val serverOptions = intent.serverOptions.normalizedExternalShareServerOptions()
        if (
            token.externalShareOpaqueTokenOrNull() == null ||
            accountId.isBlank() ||
            serverId.isBlank() ||
            serverOptions.none { it.id == serverId }
        ) {
            authorizedServerOptions = emptyList()
            _state.value =
                ExternalShareState.Error(
                    token = token,
                    serverId = serverId,
                    errorCode = "external_share_identity_invalid",
                    retryable = false
                )
            return
        }
        authorizedServerOptions = serverOptions
        loadGeneration += 1
        val generation = loadGeneration
        loadJob?.cancel()
        targetJob?.cancel()
        handoffJob?.cancel()
        _state.value = ExternalShareState.LoadingEnvelope(token)
        loadJob =
            scope.launch {
                when (val draftResult = draftRepository.load(token)) {
                    is DataResult.Content -> restoreDraft(draftResult.data, token, accountId, serverId, generation)
                    is DataResult.Empty -> loadFreshEnvelope(token, accountId, serverId, generation)
                    is DataResult.Error -> setLoadError(token, serverId, draftResult.error.code, draftResult.error.retryable, generation)
                    is DataResult.NoPermission -> setLoadError(token, serverId, draftResult.blocker.code, false, generation)
                }
            }
    }

    private suspend fun loadFreshEnvelope(token: String, accountId: String, serverId: String, generation: Long) {
        when (val result = envelopeRepository.load(token)) {
            is DataResult.Content -> {
                if (generation != loadGeneration) return
                val envelope = result.data
                if (envelope.token != token || !envelope.hasContent()) {
                    setLoadError(token, serverId, "external_share_envelope_invalid", false, generation)
                    return
                }
                val draft = ExternalShareDraft(envelope, serverId, ExternalShareDraftStage.SelectingTargets)
                when (val transfer = persistAndConsumeEnvelope(draft)) {
                    is DataResult.Content -> restoreDraft(transfer.data, token, accountId, serverId, generation)
                    is DataResult.Empty -> setLoadError(token, serverId, "external_share_draft_persist_empty", true, generation)
                    is DataResult.Error -> setLoadError(token, serverId, transfer.error.code, transfer.error.retryable, generation)
                    is DataResult.NoPermission -> setLoadError(token, serverId, transfer.blocker.code, false, generation)
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
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("external_share_envelope_consume_failed", retryable = true))
                }
            when (consume) {
                is DataResult.Content -> Unit
                is DataResult.Empty -> return DataResult.Error(ScreenError("external_share_envelope_consume_empty", true))
                is DataResult.Error -> return consume
                is DataResult.NoPermission -> return consume
            }
        }
        val consumedDraft = draft.copy(envelopeConsumed = true)
        return when (val save = draftRepository.save(consumedDraft)) {
            is DataResult.Content -> DataResult.Content(consumedDraft)
            is DataResult.Empty -> DataResult.Error(ScreenError("external_share_draft_persist_empty", true))
            is DataResult.Error -> save
            is DataResult.NoPermission -> save
        }
    }

    private suspend fun restoreDraft(
        draft: ExternalShareDraft,
        token: String,
        accountId: String,
        requestedServerId: String,
        generation: Long
    ) {
        if (generation != loadGeneration) return
        if (!draft.isValidFor(token, authorizedServerOptions.mapTo(linkedSetOf()) { it.id })) {
            setLoadError(token, requestedServerId, "external_share_draft_invalid", false, generation)
            return
        }
        val readyDraft =
            if (draft.envelopeConsumed) {
                draft
            } else {
                when (val transfer = persistAndConsumeEnvelope(draft)) {
                    is DataResult.Content -> transfer.data
                    is DataResult.Empty -> {
                        setLoadError(token, requestedServerId, "external_share_draft_persist_empty", true, generation)
                        return
                    }
                    is DataResult.Error -> {
                        setLoadError(token, requestedServerId, transfer.error.code, transfer.error.retryable, generation)
                        return
                    }
                    is DataResult.NoPermission -> {
                        setLoadError(token, requestedServerId, transfer.blocker.code, false, generation)
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
                        accountId = accountId,
                        serverId = readyDraft.serverId,
                        serverOptions = authorizedServerOptions,
                        selectedTargets = readyDraft.selectedTargets
                    )
                search("")
            }
            ExternalShareDraftStage.ComposerHandoff,
            ExternalShareDraftStage.Confirming -> {
                if (readyDraft.selectedTargets.size != 1 || readyDraft.deliveries.isNotEmpty()) {
                    setLoadError(token, requestedServerId, "external_share_legacy_draft_unsupported", false, generation)
                    return
                }
                val migrated = readyDraft.copy(stage = ExternalShareDraftStage.ComposerHandoff, comment = "")
                resolveComposerHandoff(migrated, accountId, generation)
            }
            ExternalShareDraftStage.Delivering ->
                setLoadError(token, requestedServerId, "external_share_legacy_delivery_not_replayed", false, generation)
        }
    }

    private fun setLoadError(
        token: String,
        serverId: String,
        errorCode: String,
        retryable: Boolean,
        generation: Long
    ) {
        if (generation == loadGeneration) {
            _state.value = ExternalShareState.Error(token, serverId, errorCode, retryable)
        }
    }

    private fun selectServer(rawServerId: String) {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        val serverId = rawServerId.trim()
        if (authorizedServerOptions.none { it.id == serverId }) {
            _state.value = current.copy(selectionErrorCode = "external_share_server_unavailable")
            return
        }
        if (serverId == current.serverId || current.handoffInProgress) return
        targetGeneration += 1
        targetJob?.cancel()
        targetRepository.releaseDirectorySnapshot(current.externalShareDirectorySessionId())
        _state.value =
            current.copy(
                serverId = serverId,
                serverOptions = authorizedServerOptions,
                query = "",
                targets = emptyList(),
                selectedTargets = emptyList(),
                loading = false,
                appending = false,
                requestGeneration = current.requestGeneration + 1,
                nextCursor = null,
                errorCode = null,
                selectionErrorCode = null
            )
        persistCurrentDraft()
        search("")
    }

    private fun search(rawQuery: String, append: Boolean = false) {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        if (current.handoffInProgress) return
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
                        // The host already debounces typing; local DM/channel matches render at
                        // once (still loading, so not selectable) until the server's list lands.
                        // A newer keystroke cancels this job, and with it the server request.
                        var final: DataResult<List<ForwardMessageTarget>>? = null
                        targetRepository.searchTargetsProgressively(ServerId(current.serverId), query, 40).collect { update ->
                            when (update) {
                                is ForwardTargetSearchUpdate.Interim -> {
                                    if (generation != targetGeneration) return@collect
                                    val latest = _state.value as? ExternalShareState.SelectingTargets ?: return@collect
                                    if (latest.envelope.token != current.envelope.token || latest.serverId != current.serverId) {
                                        return@collect
                                    }
                                    _state.value =
                                        latest.copy(
                                            targets = update.targets.distinctBy { it.externalShareSelectionKey() },
                                            nextCursor = null
                                        )
                                }
                                is ForwardTargetSearchUpdate.Final -> final = update.result
                            }
                        }
                        when (val searched = final ?: return@launch) {
                            is DataResult.Content -> DataResult.Content(ForwardMessageTargetDirectoryPage(items = searched.data))
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
                        is DataResult.Content -> latest.copy(
                            targets = if (append && !result.data.reset) {
                                (latest.targets + result.data.items).distinctBy { it.externalShareSelectionKey() }
                            } else {
                                result.data.items.distinctBy { it.externalShareSelectionKey() }
                            },
                            loading = false,
                            appending = false,
                            nextCursor = result.data.nextCursor,
                            errorCode = null
                        )
                        is DataResult.Empty -> latest.copy(
                            targets = if (append) latest.targets else emptyList(),
                            loading = false,
                            appending = false,
                            nextCursor = null,
                            errorCode = null
                        )
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
            current.handoffInProgress ||
            current.errorCode != null
        ) return
        if (target.serverId.value != current.serverId) {
            _state.value = current.copy(selectionErrorCode = "external_share_target_server_mismatch")
            return
        }
        val key = target.externalShareSelectionKey()
        val liveTarget = current.targets.firstOrNull { it.externalShareSelectionKey() == key }
        val selectedTarget = current.selectedTargets.singleOrNull()
        if (target != liveTarget && target != selectedTarget) return
        if (target.requiredAction == ForwardMessageTargetRequiredAction.JoinChannel && selectedTarget != target) {
            _state.value = current.copy(selectionErrorCode = "external_share_join_confirmation_required")
            return
        }
        _state.value =
            if (selectedTarget?.externalShareSelectionKey() == key) {
                current.copy(selectedTargets = emptyList(), selectionErrorCode = null)
            } else {
                current.copy(selectedTargets = listOf(target), selectionErrorCode = null)
            }
        persistCurrentDraft()
    }

    private fun continueToComposer() {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        val target = current.selectedTargets.singleOrNull()
        if (target == null) {
            _state.value = current.copy(selectionErrorCode = "external_share_single_target_required")
            return
        }
        if (current.loading || current.handoffInProgress || current.errorCode != null) return
        targetGeneration += 1
        targetJob?.cancel()
        targetRepository.releaseDirectorySnapshot(current.externalShareDirectorySessionId())
        _state.value = current.copy(handoffInProgress = true, selectionErrorCode = null)
        handoffGeneration += 1
        val generation = handoffGeneration
        val draft =
            ExternalShareDraft(
                envelope = current.envelope,
                serverId = current.serverId,
                stage = ExternalShareDraftStage.ComposerHandoff,
                selectedTargets = listOf(target),
                envelopeConsumed = true
            )
        handoffJob?.cancel()
        handoffJob =
            scope.launch {
                when (val save = draftRepository.save(draft)) {
                    is DataResult.Content -> resolveComposerHandoff(draft, current.accountId, loadGeneration)
                    is DataResult.Empty -> failHandoff(current, generation, "external_share_draft_persist_empty")
                    is DataResult.Error -> failHandoff(current, generation, save.error.code)
                    is DataResult.NoPermission -> failHandoff(current, generation, save.blocker.code)
                }
            }
    }

    private suspend fun resolveComposerHandoff(draft: ExternalShareDraft, accountId: String, loadAtStart: Long) {
        val target = draft.selectedTargets.singleOrNull() ?: return
        val resolution =
            try {
                targetRepository.resolveTargetChannel(
                    sourceServerId = ServerId(draft.serverId),
                    target = target,
                    allowJoin = false
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                DataResult.Error(ScreenError("external_share_target_resolution_failed", true))
            }
        if (loadAtStart != loadGeneration) return
        when (resolution) {
            is DataResult.Content -> {
                val destination = resolution.data
                val resolvedTarget =
                    target.copy(
                        channelId = destination.channelId,
                        channelKind = destination.channelKind,
                        requiredAction = ForwardMessageTargetRequiredAction.None,
                        canForwardNow = true
                    )
                val resolvedDraft = draft.copy(selectedTargets = listOf(resolvedTarget))
                when (val save = draftRepository.save(resolvedDraft)) {
                    is DataResult.Content -> {
                        handoffGeneration += 1
                        val handoffId = handoffGeneration
                        _state.value =
                            ExternalShareState.ReadyForComposer(
                                ExternalShareComposerHandoff(
                                    envelope = draft.envelope,
                                    accountId = accountId,
                                    serverId = draft.serverId,
                                    target = resolvedTarget,
                                    handoffId = handoffId
                                )
                            )
                    }
                    is DataResult.Empty -> setLoadError(draft.envelope.token, draft.serverId, "external_share_draft_persist_empty", true, loadAtStart)
                    is DataResult.Error -> setLoadError(draft.envelope.token, draft.serverId, save.error.code, save.error.retryable, loadAtStart)
                    is DataResult.NoPermission -> setLoadError(draft.envelope.token, draft.serverId, save.blocker.code, false, loadAtStart)
                }
            }
            is DataResult.Empty -> setLoadError(draft.envelope.token, draft.serverId, "external_share_target_resolution_empty", true, loadAtStart)
            is DataResult.Error -> setLoadError(draft.envelope.token, draft.serverId, resolution.error.code, resolution.error.retryable, loadAtStart)
            is DataResult.NoPermission -> setLoadError(draft.envelope.token, draft.serverId, resolution.blocker.code, false, loadAtStart)
        }
    }

    private fun failHandoff(current: ExternalShareState.SelectingTargets, generation: Long, errorCode: String) {
        if (generation == handoffGeneration) {
            _state.value = current.copy(handoffInProgress = false, selectionErrorCode = errorCode)
        }
    }

    private fun completeHandoff(handoffId: Long) {
        val current = (_state.value as? ExternalShareState.ReadyForComposer)?.handoff ?: return
        if (current.handoffId != handoffId) return
        _state.value = ExternalShareState.Idle
        draftWriteTail =
            scope.launch {
                when (val result = draftRepository.delete(current.envelope.token)) {
                    is DataResult.Content -> Unit
                    is DataResult.Empty -> diagnosticsLogger("external_share_handoff_draft_delete_empty")
                    is DataResult.Error -> diagnosticsLogger("external_share_handoff_draft_delete_failed code=${result.error.code}")
                    is DataResult.NoPermission -> diagnosticsLogger("external_share_handoff_draft_delete_failed code=${result.blocker.code}")
                }
            }
    }

    private fun failReadyHandoff(handoffId: Long) {
        val current = (_state.value as? ExternalShareState.ReadyForComposer)?.handoff ?: return
        if (current.handoffId != handoffId) return
        _state.value =
            ExternalShareState.Error(
                token = current.envelope.token,
                serverId = current.serverId,
                errorCode = "external_share_composer_handoff_rejected",
                retryable = true
            )
    }

    private fun persistCurrentDraft() {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        val draft =
            ExternalShareDraft(
                envelope = current.envelope,
                serverId = current.serverId,
                stage = ExternalShareDraftStage.SelectingTargets,
                selectedTargets = current.selectedTargets,
                envelopeConsumed = true
            )
        val previous = draftWriteTail
        draftWriteTail =
            scope.launch {
                previous?.join()
                when (val result = draftRepository.save(draft)) {
                    is DataResult.Content -> Unit
                    is DataResult.Empty -> diagnosticsLogger("external_share_draft_save_failed code=external_share_draft_persist_empty")
                    is DataResult.Error -> diagnosticsLogger("external_share_draft_save_failed code=${result.error.code}")
                    is DataResult.NoPermission -> diagnosticsLogger("external_share_draft_save_failed code=${result.blocker.code}")
                }
            }
    }

    private fun cancelAndDiscard() {
        val token = _state.value.externalShareTokenOrNull()
        clear()
        token?.let { discardedToken ->
            val previous = draftWriteTail
            draftWriteTail =
                scope.launch {
                    previous?.join()
                    runCatching { envelopeRepository.delete(discardedToken) }
                    draftRepository.delete(discardedToken)
                }
        }
    }
}

private fun ExternalShareState.externalShareTokenOrNull(): String? =
    when (this) {
        is ExternalShareState.LoadingEnvelope -> token
        is ExternalShareState.SelectingTargets -> envelope.token
        is ExternalShareState.ReadyForComposer -> handoff.envelope.token
        is ExternalShareState.Error -> token
        ExternalShareState.Idle -> null
    }

internal fun ExternalShareEnvelope.externalShareConsumptionId(): String =
    "external-share-consume:$token"

internal fun ForwardMessageTarget.externalShareSelectionKey(): String =
    "${serverId.value}:${kind.name}:$id"

internal fun ExternalShareState.SelectingTargets.externalShareDirectorySessionId(): String =
    "external-share:${envelope.token}:$serverId"

internal fun ExternalShareEnvelope.externalShareComposerText(): String =
    listOf(subject, text)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("\n\n")

private fun DataResult<Unit>.mapDraftFailure(): DataResult<ExternalShareDraft> =
    when (this) {
        is DataResult.Content -> DataResult.Error(ScreenError("external_share_draft_persist_invalid", true))
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
    }

private fun ExternalShareDraft.isValidFor(token: String, authorizedServerIds: Set<String>): Boolean {
    if (envelope.token != token || !envelope.hasContent()) return false
    if (serverId.isBlank() || serverId !in authorizedServerIds) return false
    if (selectedTargets.size > ExternalShareMaximumTargetCount) return false
    if (selectedTargets.any { it.id.isBlank() || it.serverId.value != serverId }) return false
    if (selectedTargets.any { it.requiredAction == ForwardMessageTargetRequiredAction.JoinChannel }) return false
    return when (stage) {
        ExternalShareDraftStage.SelectingTargets -> deliveries.isEmpty()
        ExternalShareDraftStage.ComposerHandoff,
        ExternalShareDraftStage.Confirming -> selectedTargets.size == 1 && deliveries.isEmpty()
        ExternalShareDraftStage.Delivering -> false
    }
}

private fun List<ExternalShareServerOption>.normalizedExternalShareServerOptions(): List<ExternalShareServerOption> =
    mapNotNull { option ->
        val id = option.id.trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
        ExternalShareServerOption(
            id = id,
            title = option.title.trim().takeIf(String::isNotBlank) ?: id,
            slug = option.slug?.trim()?.takeIf(String::isNotBlank)
        )
    }.distinctBy { it.id }
