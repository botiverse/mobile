package build.raft.app.data

import build.raft.app.model.LinkCardOperationChanged
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter

/** One `link_card.operation_changed` hint, with the server whose socket it came on (the payload names none). */
internal data class RaftLinkCardOperationHint(
    val serverId: String,
    val operationId: String,
    val receiptRevision: Long,
)

/**
 * The process's link card receipt hints (RFC 076 §6.8). The realtime owner publishes each accepted
 * [LinkCardOperationChanged]; a card following one of its operations collects them and reads the
 * operation's status again (web `linkCardExecute.tsx` `onOperationChanged`). A lost hint costs
 * nothing: the card still reads itself again after a press.
 */
internal object RaftLinkCardOperationHints {
    private val mutableHints =
        MutableSharedFlow<RaftLinkCardOperationHint>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val hints: SharedFlow<RaftLinkCardOperationHint> = mutableHints.asSharedFlow()

    fun publish(serverId: String, event: LinkCardOperationChanged) {
        mutableHints.tryEmit(RaftLinkCardOperationHint(serverId, event.operationId, event.receiptRevision))
    }

    /** Hints for [serverId]'s operations. */
    fun forServer(serverId: String): Flow<RaftLinkCardOperationHint> = hints.filter { it.serverId == serverId }
}
