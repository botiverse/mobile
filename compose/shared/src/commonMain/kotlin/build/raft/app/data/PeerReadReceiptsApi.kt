package build.raft.app.data

import build.raft.app.model.ReadReceiptPeerKind
import build.raft.app.model.ScopeReadUpdated
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Read receipts (`read_receipts_v0`): other participants' read positions, the web's
 * `packages/web/src/store/readReceiptDomain.ts` + `readReceiptStore.ts`. Not the viewer's own read
 * position ([ReadReceiptCoordinator] marks that).
 *
 * Wire contract, botiverse/slock staging `dac70954080092d54811442e24f900b2bb5df0bc`:
 *  - Hydrate: `GET channels/{scopeId}` (`routes/channels.ts` `channelRouter.get("/:id")`) spreads
 *    `services/readReceiptService.ts` `getPeerReadHydrate` into the channel detail. Exactly one of
 *    `peerReadStates: [{ peerKind: "human" | "agent", peerId, maxReadSeq }]` (sorted by kind, then
 *    id) or `peerReadSummary: { peerCount, readCountAtSeq: [{ seq, count }] }` (seq ascending,
 *    count strictly decreasing: how many peers have read at least up to `seq`). NEITHER key when the
 *    flag is off for the server, the scope is not a channel / private channel / DM (a thread follows
 *    its parent; `#all` and joint channels never), or the viewer is not a member.
 *  - Only agents are exposed (`listExposedReadPeers`): a human's read state never leaves the server.
 *    The viewer itself is excluded. Above `READ_RECEIPT_PEER_STATE_LIMIT` (50) peers the hydrate
 *    degrades to the anonymous summary (so LIMIT + 1 agents is already a summary).
 *  - Realtime `scope_read:updated` to room `channel:{scopeId}`: `{ scopeId, peerKind, peerId,
 *    maxReadSeq }` when an agent's read cursor advances, or `{ scopeId, summaryChanged: true }`
 *    once the scope has more than LIMIT exposed peers (the client then re-fetches the hydrate).
 *
 * Hydrate presence is the client-side gate: a frame for a scope that has no hydrate is dropped and
 * never creates identities.
 */
private val peerReadReceiptsJson = Json { ignoreUnknownKeys = true }

/** `peerReadStates[]` row. */
@Serializable
internal data class PeerReadStateWire(
    val peerKind: String,
    val peerId: String,
    val maxReadSeq: Long,
)

/** `peerReadSummary.readCountAtSeq[]` row. */
@Serializable
internal data class PeerReadCountAtSeqWire(
    val seq: Long,
    val count: Int,
)

/** `peerReadSummary`. */
@Serializable
internal data class PeerReadSummaryWire(
    val peerCount: Int,
    val readCountAtSeq: List<PeerReadCountAtSeqWire>,
)

/** `scope_read:updated` payload: either the peer fields or `summaryChanged: true`. */
@Serializable
internal data class ScopeReadUpdatedWire(
    val scopeId: String = "",
    val peerKind: String? = null,
    val peerId: String? = null,
    val maxReadSeq: Long? = null,
    val summaryChanged: Boolean? = null,
)

data class PeerReadState(
    val peerKind: ReadReceiptPeerKind,
    val peerId: String,
    val maxReadSeq: Long,
)

data class PeerReadCountAtSeq(val seq: Long, val count: Int)

data class PeerReadSummary(
    val peerCount: Int,
    val readCountAtSeq: List<PeerReadCountAtSeq>,
)

/** One scope's hydrate: per-peer watermarks, or the anonymous large-scope summary. */
sealed interface PeerReadReceiptScope {
    data class Peers(val peers: List<PeerReadState>) : PeerReadReceiptScope

    data class Summary(val summary: PeerReadSummary) : PeerReadReceiptScope
}

internal fun readReceiptPeerKindFromWire(value: String?): ReadReceiptPeerKind? =
    when (value) {
        "human" -> ReadReceiptPeerKind.Human
        "agent" -> ReadReceiptPeerKind.Agent
        else -> null
    }

/**
 * The read-receipt part of a `GET channels/{id}` body, or null when the body carries none (the
 * scope is off) or a malformed one. Same strictness as web `normalizeReadReceiptHydrate`: both
 * keys present, an unknown peer kind, a blank or duplicate peer, a negative seq or a summary that
 * is not seq-ascending / count-descending all disable the scope rather than half-apply it.
 */
fun parsePeerReadReceiptHydrate(bodyText: String): PeerReadReceiptScope? {
    val root =
        try {
            peerReadReceiptsJson.parseToJsonElement(bodyText).jsonObject
        } catch (_: Exception) {
            return null
        }
    return parsePeerReadReceiptHydrate(root)
}

internal fun parsePeerReadReceiptHydrate(root: JsonObject): PeerReadReceiptScope? =
    try {
        decodePeerReadReceiptHydrate(root)
    } catch (_: Exception) {
        null
    }

private fun decodePeerReadReceiptHydrate(root: JsonObject): PeerReadReceiptScope? {
    if ("peerReadStates" in root && "peerReadSummary" in root) return null
    val statesElement = root["peerReadStates"]
    if (statesElement is JsonArray) {
        val seen = mutableSetOf<Pair<ReadReceiptPeerKind, String>>()
        val peers =
            statesElement.map { element ->
                val wire = peerReadReceiptsJson.decodeFromJsonElement(PeerReadStateWire.serializer(), element)
                val kind = readReceiptPeerKindFromWire(wire.peerKind) ?: return null
                if (wire.peerId.isEmpty() || wire.maxReadSeq < 0) return null
                if (!seen.add(kind to wire.peerId)) return null
                PeerReadState(kind, wire.peerId, wire.maxReadSeq)
            }
        return PeerReadReceiptScope.Peers(peers)
    }
    val summaryElement = root["peerReadSummary"] as? JsonObject ?: return null
    val wire = peerReadReceiptsJson.decodeFromJsonElement(PeerReadSummaryWire.serializer(), summaryElement)
    if (wire.peerCount < 0) return null
    var previousSeq = -1L
    var previousCount = wire.peerCount + 1
    val rows =
        wire.readCountAtSeq.map { row ->
            if (row.seq < 0 || row.count < 0) return null
            if (row.seq <= previousSeq || row.count > wire.peerCount || row.count >= previousCount) return null
            previousSeq = row.seq
            previousCount = row.count
            PeerReadCountAtSeq(row.seq, row.count)
        }
    return PeerReadReceiptScope.Summary(PeerReadSummary(wire.peerCount, rows))
}

/** web `normalizeScopeReadUpdated`: a peer advance, `summaryChanged`, or null (dropped). */
internal fun ScopeReadUpdatedWire.toScopeReadUpdated(): ScopeReadUpdated? {
    if (scopeId.isEmpty()) return null
    if (summaryChanged == true) return ScopeReadUpdated(scopeId = scopeId, summaryChanged = true)
    val kind = readReceiptPeerKindFromWire(peerKind) ?: return null
    val id = peerId?.takeIf { it.isNotEmpty() } ?: return null
    val seq = maxReadSeq?.takeIf { it >= 0 } ?: return null
    return ScopeReadUpdated(scopeId = scopeId, peerKind = kind, peerId = id, maxReadSeq = seq)
}

/**
 * web `mergePeerReadAdvance`: raises one known peer's watermark. Frames for an unknown peer, a
 * summary scope or a seq at or below the current watermark (out of order / replayed) return the
 * same instance.
 */
internal fun PeerReadReceiptScope.mergePeerReadAdvance(update: ScopeReadUpdated): PeerReadReceiptScope {
    if (this !is PeerReadReceiptScope.Peers || update.summaryChanged) return this
    val kind = update.peerKind ?: return this
    val seq = update.maxReadSeq ?: return this
    val index = peers.indexOfFirst { it.peerKind == kind && it.peerId == update.peerId }
    if (index < 0 || peers[index].maxReadSeq >= seq) return this
    return PeerReadReceiptScope.Peers(
        peers.toMutableList().also { it[index] = it[index].copy(maxReadSeq = seq) },
    )
}

/**
 * web `mergeReadReceiptHydrate`: a fresh per-peer hydrate keeps any watermark realtime already
 * advanced past it (a frame may land while the GET is in flight). Summary hydrates replace.
 */
internal fun mergePeerReadReceiptHydrate(
    current: PeerReadReceiptScope?,
    hydrate: PeerReadReceiptScope,
): PeerReadReceiptScope {
    if (current !is PeerReadReceiptScope.Peers || hydrate !is PeerReadReceiptScope.Peers) return hydrate
    val known = current.peers.associate { (it.peerKind to it.peerId) to it.maxReadSeq }
    var changed = false
    val peers =
        hydrate.peers.map { peer ->
            val seq = maxOf(peer.maxReadSeq, known[peer.peerKind to peer.peerId] ?: 0L)
            if (seq == peer.maxReadSeq) {
                peer
            } else {
                changed = true
                peer.copy(maxReadSeq = seq)
            }
        }
    return if (changed) PeerReadReceiptScope.Peers(peers) else hydrate
}

/** web `projectReadReceipt`. */
data class PeerReadReceiptProjection(val read: Boolean, val readCount: Int, val peerCount: Int)

internal fun projectPeerReadReceipt(scope: PeerReadReceiptScope?, messageSeq: Long?): PeerReadReceiptProjection {
    if (scope == null || messageSeq == null || messageSeq <= 0) return PeerReadReceiptProjection(false, 0, 0)
    return when (scope) {
        is PeerReadReceiptScope.Peers -> {
            val readCount = scope.peers.count { it.maxReadSeq >= messageSeq }
            PeerReadReceiptProjection(readCount > 0, readCount, scope.peers.size)
        }
        is PeerReadReceiptScope.Summary -> {
            val readCount = scope.summary.readCountAtSeq.firstOrNull { it.seq >= messageSeq }?.count ?: 0
            PeerReadReceiptProjection(readCount > 0, readCount, scope.summary.peerCount)
        }
    }
}

/**
 * web `projectAgentReadReceipt`: whether ONE agent peer has read [messageSeq]; null is unknown
 * (summary scope, no hydrate, not a peer, no seq) and must render nothing, never "unread".
 */
internal fun projectAgentReadReceipt(scope: PeerReadReceiptScope?, agentId: String, messageSeq: Long?): Boolean? {
    if (scope !is PeerReadReceiptScope.Peers) return null
    if (messageSeq == null || messageSeq <= 0 || agentId.isEmpty()) return null
    val peer = scope.peers.firstOrNull { it.peerKind == ReadReceiptPeerKind.Agent && it.peerId == agentId } ?: return null
    return peer.maxReadSeq >= messageSeq
}

/** Result of one hydrate fetch: the scope (null = the server sent none) or a failure. */
sealed interface PeerReadReceiptHydrateResult {
    data class Loaded(val scope: PeerReadReceiptScope?) : PeerReadReceiptHydrateResult

    data class Failed(val reason: String) : PeerReadReceiptHydrateResult
}

/** `GET channels/{scopeId}` for its read-receipt part (the web fetches the same detail). */
internal suspend fun fetchPeerReadReceiptHydrate(
    networkClient: RaftNetworkClient,
    serverId: ServerId,
    scopeId: String,
): PeerReadReceiptHydrateResult {
    val response =
        try {
            networkClient.request(
                priority = DataRequestPriority.Visible,
                path = "channels/$scopeId",
                serverIdOverride = serverId,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            return PeerReadReceiptHydrateResult.Failed(exception::class.simpleName ?: "exception")
        }
    if (!response.isSuccessful) return PeerReadReceiptHydrateResult.Failed("status_${response.code}")
    val root =
        try {
            peerReadReceiptsJson.parseToJsonElement(response.bodyText.orEmpty()).jsonObject
        } catch (_: Exception) {
            return PeerReadReceiptHydrateResult.Failed("body_not_object")
        }
    return PeerReadReceiptHydrateResult.Loaded(parsePeerReadReceiptHydrate(root))
}
