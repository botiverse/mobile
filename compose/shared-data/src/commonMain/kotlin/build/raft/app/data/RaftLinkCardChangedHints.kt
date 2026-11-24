package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.LinkCardChanged
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One `link_card.changed` nudge, with the server whose socket it came on (the payload names none). */
@RaftCoreInternalApi data class RaftLinkCardChangedHint(
    val serverId: String,
    /** The digest the cards of that object carry ([AppLinkResult.Card.objectKey]). */
    val objectKey: String,
)

/**
 * The process's `link_card.changed` nudges (botiverse/slock docs/development/app-link-previews.md,
 * "Telling Raft a card changed"). The realtime owner publishes each accepted [LinkCardChanged];
 * [SharedAppLinkPreviewStore] collects them and reads the cards of that object again (web
 * `linkCardChangedRereads.ts`). A lost nudge costs nothing but freshness: the card is read again
 * when the app comes back ([RaftFreshnessSignal]) or the person presses Refresh.
 */
@RaftCoreInternalApi object RaftLinkCardChangedHints {
    private val mutableHints =
        MutableSharedFlow<RaftLinkCardChangedHint>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val hints: SharedFlow<RaftLinkCardChangedHint> = mutableHints.asSharedFlow()

    fun publish(serverId: String, event: LinkCardChanged) {
        mutableHints.tryEmit(RaftLinkCardChangedHint(serverId, event.objectKeySha256))
    }
}
