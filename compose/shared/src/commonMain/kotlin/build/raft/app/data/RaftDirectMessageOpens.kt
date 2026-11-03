package build.raft.app.data

import build.raft.app.MemberKindWireContract
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.parsedMemberKindOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One DM open target: the server it is opened in and the peer (web `openDM` requestKey). */
internal data class RaftDirectMessageOpenKey(
    val serverId: String,
    val peerKind: MemberKind,
    val peerId: String,
)

/**
 * Opens a DM the way it should feel: at once when Home already lists a DM with that peer, and with
 * one `POST /channels/dm` per server + peer otherwise (web channelStore `openDM` in-flight map).
 * artin 11-03 (Android 1.19.0): on a slow network the Message button gave no sign of the request,
 * 18 taps sent 18 POSTs, and Back cancelled them all.
 */
internal class RaftDirectMessageOpens {
    private val _pending = MutableStateFlow<Set<RaftDirectMessageOpenKey>>(emptySet())

    /** The opens waiting on the server, for a button's loading state. */
    val pending: StateFlow<Set<RaftDirectMessageOpenKey>> = _pending.asStateFlow()

    /**
     * The DM with [peerId] for the caller to open, or null when a tap before this one is still
     * opening it (that tap opens the page; this one must not open a second).
     */
    suspend fun open(
        channelRepository: ChannelRepository,
        serverId: ServerId?,
        peerKind: MemberKind,
        peerId: MemberId,
        homeSnapshot: HomeSnapshot?,
    ): DataResult<OpenDirectMessageResult>? {
        val cleanServerId = serverId?.value?.trim()?.takeIf { it.isNotBlank() }
        val snapshot = homeSnapshot?.takeIf { cleanServerId != null && it.server.id.value.trim() == cleanServerId }
        snapshot?.existingDirectMessageWith(peerKind, peerId)?.let { existing ->
            return DataResult.Content(OpenDirectMessageResult(existing, snapshot.directMessages))
        }
        val key = RaftDirectMessageOpenKey(cleanServerId.orEmpty(), peerKind, peerId.value.trim())
        while (true) {
            val current = _pending.value
            if (key in current) return null
            if (_pending.compareAndSet(current, current + key)) break
        }
        return try {
            when (peerKind) {
                MemberKind.Agent -> channelRepository.openAgentDirectMessage(peerId)
                MemberKind.Human -> channelRepository.openUserDirectMessage(peerId)
                MemberKind.System, MemberKind.External -> DataResult.Empty(EmptyReason.NoChannels)
            }
        } finally {
            _pending.update { it - key }
        }
    }

    companion object {
        /** Process-wide, so taps on any surface (profile, Home pinned row) join one request. */
        val shared: RaftDirectMessageOpens = RaftDirectMessageOpens()
    }
}

/** True while a tap's request for the DM with this peer is out (any server). */
internal fun Set<RaftDirectMessageOpenKey>.isOpening(peerKind: MemberKind, peerId: MemberId): Boolean =
    any { it.peerKind == peerKind && it.peerId == peerId.value.trim() }

/** Home's DM row with this peer, if Home already lists one. */
internal fun HomeSnapshot.existingDirectMessageWith(peerKind: MemberKind, peerId: MemberId): HomeDirectMessageRow? {
    val cleanPeerId = peerId.value.trim().takeIf { it.isNotBlank() } ?: return null
    return directMessages.firstOrNull { row ->
        row.kind == ConversationKind.DirectMessage &&
            row.channelId.value.isNotBlank() &&
            row.presenceMemberId?.value?.trim() == cleanPeerId &&
            row.directMessagePeerType.parsedMemberKindOrNull(MemberKindWireContract.InternalActor) == peerKind
    }
}
