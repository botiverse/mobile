package build.raft.app.network.sync

import kotlinx.serialization.Serializable

/**
 * Message reply-preview window (RFC 043 App B `Message × Replies` preview), KMP side of the dual-end
 * `getLatestNReplies` / `listenLatestNReplies` SDK contract.
 *
 * The SDK maintains the latest-N window and pushes the whole maintained snapshot (not raw deltas); the UI
 * only paints, so the two ordering/dedup gates cannot be missed at the render layer. The web read-model
 * (`threadRepliesReadModel.ts`) and this KMP reducer are two implementations of ONE spec — a committed
 * cross-repo shared vector (`threadRepliesReadModel.vectors.json`, pinned by sha256) is fed to both and
 * asserted step-by-step logically deep-equal, so the two ends cannot silently drift.
 *
 * Contract (spec §4):
 *  1. Order by seq, converge under permutation — `replies` = latest N ascending by seq; a late / out-of-order
 *     delta lands in its seq position, NEVER naive-unshifted to "newest". Any arrival order of one delta set
 *     yields the identical window.
 *  2. Idempotent, dedup by IDENTITY (`messageId`), fixed seam — dedup by messageId, NOT by seq magnitude
 *     (using the sort key as the dedup key mis-kills out-of-order real replies). `snapshotSeq` is a fixed
 *     seam set from the initial snapshot; live deltas never advance it; a reconnect-replay delta with
 *     `seq <= snapshotSeq` that is not already in the window is already covered → dropped, window unchanged.
 *  3. `replyCount` is the server's AUTHORITATIVE total (independent of window size — 40 total vs window N),
 *     applied MONOTONICALLY (`max`): never `+1`-derived (that double-counts an evicted reply's replay),
 *     never naively overwritten (a stale out-of-order frame would drag the total backwards).
 *  4. No-op on no-change — the same window + replyCount returns the SAME instance (reference equality) so a
 *     reply landing on one thread re-renders only that thread, never the whole timeline.
 */
@Serializable
data class ReplyPreview(
    val messageId: String,
    val seq: Long,
    val content: String,
    val senderId: String,
    val senderType: String,
    val senderName: String,
    val senderAvatarUrl: String? = null,
    val createdAt: String,
    val senderDisplayName: String? = null,
)

/** The maintained latest-N reply-preview window pushed by `listenLatestNReplies`. */
@Serializable
data class ThreadRepliesReadModel(
    val replies: List<ReplyPreview>,
    val replyCount: Int,
    val snapshotSeq: Long,
)

const val InlineThreadReplyPreviewLimit: Int = 3

const val ThreadRepliesSyncWindowProducer: String = "message-service.thread-replies-window.v1"

/** Strictly projected existing v1 producer envelope. Cursor/epoch remain compatibility metadata. */
@Serializable
data class ThreadRepliesSyncWindowEnvelope(
    val producer: String,
    val rootKind: String,
    val rootServerId: String,
    val rootMessageId: String,
    val relationKind: String,
    val parentScopeServerId: String,
    val parentScopeKind: String,
    val parentScopeId: String,
    val backing: String,
    val windowKind: String,
    val scopeCursor: String? = null,
    val epoch: String? = null,
)

/**
 * Build the read model from the initial `getLatestNReplies(messageId, N)` snapshot. `snapshotSeq` is fixed
 * here to the newest seq in the batch — the seam live deltas are measured against.
 */
fun initialThreadRepliesReadModel(
    replies: List<ReplyPreview>,
    replyCount: Int,
    limit: Int,
): ThreadRepliesReadModel {
    val orderedSnapshot = replies.dedupByMessageIdKeepingHigherSeq().sortedBy { it.seq }
    val window = orderedSnapshot.filterNot(ReplyPreview::isSystemReply).takeLast(limit)
    return ThreadRepliesReadModel(
        replies = window,
        replyCount = replyCount,
        // System events remain part of the authoritative history/count and must
        // advance the fixed snapshot seam even though they never consume a UI slot.
        snapshotSeq = orderedSnapshot.maxOfOrNull { it.seq } ?: 0L,
    )
}

/**
 * Fold one live reply delta (a `thread:updated` frame carrying `latestReply` + the authoritative
 * `threadInfo.replyCount`) into the window. Returns the SAME instance when nothing semantically changes
 * (Gate 4), so a reply on one thread re-renders only that thread.
 *
 * `frameReplyCount` is the server's AUTHORITATIVE total, applied MONOTONICALLY (`max`) — never `+1`-derived
 * and never naively overwritten:
 *  - `+1` derive double-counts once a reply has been EVICTED from the window: a window-scoped identity check
 *    no longer sees it, so its replayed frame takes the "new" branch (@Android-Developer-2's finding,
 *    vector `evicted_live_reply_replay_is_noop`). The window forgets; the server's count cannot.
 *  - a naive overwrite would let a stale, out-of-order frame carrying an OLDER count drag the total backwards.
 * `max` is idempotent under replay and safe under reorder.
 *
 * (Reply delete/edit are v1-unsupported server-side — no `deletedAt`/`revision` column, no `message:deleted`
 * event — so this fold is create-only. Delete/edit fold into the same window as forward contract, RFC 043
 * §B.10: a delete must route by `messageId` BEFORE the seam check below, or it no-ops on a visible reply.)
 */
fun ThreadRepliesReadModel.applyReplyFrame(
    reply: ReplyPreview,
    frameReplyCount: Int,
    limit: Int,
): ThreadRepliesReadModel {
    val mergedCount = maxOf(replyCount, frameReplyCount)
    val newWindow = when {
        // Gate 2 — dedup by IDENTITY: a delta whose messageId is already in the window is a duplicate; keep
        // the existing entry (identity, not seq magnitude, decides a duplicate) → window unchanged.
        replies.any { it.messageId == reply.messageId } -> replies
        // Gate 2 — seam: a reconnect-replay delta at or below the fixed snapshot seam is already covered by
        // the initial snapshot → window unchanged. A genuinely new reply, even out of order (9 before 8), has
        // seq > snapshotSeq, so this never drops a real reply.
        reply.seq <= snapshotSeq -> replies
        // Gate 1 — a genuinely new reply lands in its seq position; keep the latest N. A reply already EVICTED
        // (seq below the window min) is simply re-evicted here, so the window is unchanged and — with the
        // authoritative count above — a replay of it is a true no-op (not a spurious +1).
        reply.isSystemReply() -> replies
        else -> (replies + reply).sortedBy { it.seq }.takeLast(limit)
    }
    // Gate 4 — no semantic change (same window values AND same count) ⇒ SAME instance (render isolation).
    return if (newWindow == replies && mergedCount == replyCount) this
    else copy(replies = newWindow, replyCount = mergedCount)
}

private fun ReplyPreview.isSystemReply(): Boolean = senderType.equals("system", ignoreCase = true)

/** Collapse duplicate messageIds in an initial batch, keeping the higher-seq copy (later state wins). */
private fun List<ReplyPreview>.dedupByMessageIdKeepingHigherSeq(): List<ReplyPreview> {
    val byId = LinkedHashMap<String, ReplyPreview>()
    for (reply in this) {
        val prior = byId[reply.messageId]
        if (prior == null || reply.seq >= prior.seq) byId[reply.messageId] = reply
    }
    return byId.values.toList()
}
