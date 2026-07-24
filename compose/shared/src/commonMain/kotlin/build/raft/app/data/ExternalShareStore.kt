package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal const val ExternalShareMaximumTargetCount = 9

internal data class ExternalShareItem(
    val localRef: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

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

    suspend fun delete(token: String)
}

internal data class ExternalShareSendRequest(
    val attemptId: Long,
    val envelope: ExternalShareEnvelope,
    val target: ForwardMessageTarget,
    val comment: String
)

internal interface ExternalShareSender {
    suspend fun send(request: ExternalShareSendRequest): DataResult<Unit>
}

internal enum class ExternalShareDeliveryStatus {
    Pending,
    Sending,
    Sent,
    Failed
}

internal data class ExternalShareDelivery(
    val target: ForwardMessageTarget,
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

    data class ToggleTarget(val target: ForwardMessageTarget) : ExternalShareIntent

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
    private val targetRepository: ForwardMessageTargetRepository,
    private val sender: ExternalShareSender,
    private val scope: CoroutineScope,
    private val maximumTargetCount: Int = ExternalShareMaximumTargetCount,
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

    fun dispatch(intent: ExternalShareIntent) {
        when (intent) {
            is ExternalShareIntent.Load -> load(intent.token, intent.serverId)
            is ExternalShareIntent.Search -> search(intent.query)
            is ExternalShareIntent.ToggleTarget -> toggleTarget(intent.target)
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
                when (val result = envelopeRepository.load(token)) {
                    is DataResult.Content -> {
                        if (generation != loadGeneration) return@launch
                        if (result.data.token != token || !result.data.hasContent()) {
                            _state.value =
                                ExternalShareState.Error(
                                    token = token,
                                    serverId = serverId,
                                    errorCode = "external_share_envelope_invalid",
                                    retryable = false
                                )
                            return@launch
                        }
                        _state.value =
                            ExternalShareState.SelectingTargets(
                                envelope = result.data,
                                serverId = serverId
                            )
                        search("")
                    }
                    is DataResult.Empty -> setLoadError(token, serverId, "external_share_not_found", false, generation)
                    is DataResult.Error -> setLoadError(token, serverId, result.error.code, result.error.retryable, generation)
                    is DataResult.NoPermission -> setLoadError(token, serverId, result.blocker.code, false, generation)
                }
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

    private fun search(rawQuery: String) {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        val query = rawQuery.trimStart()
        targetGeneration += 1
        val generation = targetGeneration
        targetJob?.cancel()
        _state.value = current.copy(query = query, loading = true, errorCode = null, selectionErrorCode = null)
        targetJob =
            scope.launch {
                val result =
                    if (query.isBlank()) {
                        targetRepository.recentTargets(build.raft.app.model.ServerId(current.serverId), 40)
                    } else {
                        targetRepository.searchTargets(build.raft.app.model.ServerId(current.serverId), query, 40)
                    }
                if (generation != targetGeneration) return@launch
                val latest = _state.value as? ExternalShareState.SelectingTargets ?: return@launch
                if (latest.envelope.token != current.envelope.token || latest.serverId != current.serverId) return@launch
                _state.value =
                    when (result) {
                        is DataResult.Content ->
                            latest.copy(
                                targets = result.data.distinctBy { it.externalShareSelectionKey() },
                                loading = false,
                                errorCode = null
                            )
                        is DataResult.Empty -> latest.copy(targets = emptyList(), loading = false, errorCode = null)
                        is DataResult.Error -> latest.copy(targets = emptyList(), loading = false, errorCode = result.error.code)
                        is DataResult.NoPermission -> latest.copy(targets = emptyList(), loading = false, errorCode = result.blocker.code)
                    }
            }
    }

    private fun toggleTarget(target: ForwardMessageTarget) {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        if (target.serverId.value != current.serverId) {
            _state.value = current.copy(selectionErrorCode = "external_share_target_server_mismatch")
            return
        }
        val key = target.externalShareSelectionKey()
        val selected = current.selectedTargets
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
    }

    private fun continueToConfirmation() {
        val current = _state.value as? ExternalShareState.SelectingTargets ?: return
        if (current.selectedTargets.isEmpty()) {
            _state.value = current.copy(selectionErrorCode = "external_share_no_targets")
            return
        }
        targetGeneration += 1
        targetJob?.cancel()
        _state.value =
            ExternalShareState.Confirming(
                envelope = current.envelope,
                serverId = current.serverId,
                selectedTargets = current.selectedTargets
            )
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
    }

    private fun changeComment(value: String) {
        val current = _state.value as? ExternalShareState.Confirming ?: return
        _state.value = current.copy(comment = value)
    }

    private fun backToTargets() {
        val current = _state.value as? ExternalShareState.Confirming ?: return
        _state.value =
            ExternalShareState.SelectingTargets(
                envelope = current.envelope,
                serverId = current.serverId,
                selectedTargets = current.selectedTargets
            )
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
                initialDeliveries = current.selectedTargets.map(::ExternalShareDelivery)
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
                            ExternalShareDelivery(delivery.target, ExternalShareDeliveryStatus.Sending)
                        )
                    _state.value = ExternalShareState.Sending(envelope, serverId, comment, attemptId, deliveries)
                    val result =
                        try {
                            sender.send(ExternalShareSendRequest(attemptId, envelope, delivery.target, comment))
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (throwable: Throwable) {
                            DataResult.Error(ScreenError("external_share_send_exception", retryable = true))
                        }
                    if (attemptId != sendAttemptId) return@launch
                    val terminal =
                        when (result) {
                            is DataResult.Content ->
                                ExternalShareDelivery(delivery.target, ExternalShareDeliveryStatus.Sent)
                            is DataResult.Empty ->
                                ExternalShareDelivery(delivery.target, ExternalShareDeliveryStatus.Failed, "external_share_send_empty")
                            is DataResult.Error ->
                                ExternalShareDelivery(delivery.target, ExternalShareDeliveryStatus.Failed, result.error.code)
                            is DataResult.NoPermission ->
                                ExternalShareDelivery(delivery.target, ExternalShareDeliveryStatus.Failed, result.blocker.code)
                        }
                    deliveries = deliveries.replaceDelivery(key, terminal)
                    _state.value = ExternalShareState.Sending(envelope, serverId, comment, attemptId, deliveries)
                }
                if (attemptId != sendAttemptId) return@launch
                _state.value = ExternalShareState.Completed(envelope, serverId, comment, attemptId, deliveries)
                if (deliveries.none { it.status == ExternalShareDeliveryStatus.Failed }) {
                    runCatching { envelopeRepository.delete(envelope.token) }
                }
                diagnosticsLogger(
                    "external_share_send_complete attempt=$attemptId " +
                        "sent=${deliveries.count { it.status == ExternalShareDeliveryStatus.Sent }} " +
                        "failed=${deliveries.count { it.status == ExternalShareDeliveryStatus.Failed }}"
                )
            }
    }

    private fun cancelAndDiscard() {
        val token =
            when (val current = _state.value) {
                is ExternalShareState.LoadingEnvelope -> current.token
                is ExternalShareState.SelectingTargets -> current.envelope.token
                is ExternalShareState.Confirming -> current.envelope.token
                is ExternalShareState.Sending -> current.envelope.token
                is ExternalShareState.Completed -> current.envelope.token
                is ExternalShareState.Error -> current.token
                ExternalShareState.Idle -> null
            }
        clear()
        token?.takeIf { it.isNotBlank() }?.let { discardedToken ->
            scope.launch { runCatching { envelopeRepository.delete(discardedToken) } }
        }
    }
}

internal fun ForwardMessageTarget.externalShareSelectionKey(): String =
    "${serverId.value}:${kind.name}:$id"

private fun List<ExternalShareDelivery>.replaceDelivery(
    targetKey: String,
    replacement: ExternalShareDelivery
): List<ExternalShareDelivery> =
    map { delivery ->
        if (delivery.target.externalShareSelectionKey() == targetKey) replacement else delivery
    }
