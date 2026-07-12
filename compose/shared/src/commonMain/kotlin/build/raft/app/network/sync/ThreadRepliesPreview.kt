package build.raft.app.network.sync

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
 *  3. `replyCount` is independent of window size (40 total vs window N).
 *  4. No-op on no-change — the same window + replyCount returns the SAME instance (reference equality) so a
 *     reply landing on one thread re-renders only that thread, never the whole timeline.
 */
data class ReplyPreview(
    val messageId: String,
    val seq: Long,
    val content: String,
    val senderId: String,
    val senderType: String,
    val senderName: String,
    val senderAvatarUrl: String? = null,
    val createdAt: String,
)

/** The maintained latest-N reply-preview window pushed by `listenLatestNReplies`. */
data class ThreadRepliesReadModel(
    val replies: List<ReplyPreview>,
    val replyCount: Int,
    val snapshotSeq: Long,
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
    val window = replies.dedupByMessageIdKeepingHigherSeq().sortedBy { it.seq }.takeLast(limit)
    return ThreadRepliesReadModel(
        replies = window,
        replyCount = replyCount,
        snapshotSeq = window.maxOfOrNull { it.seq } ?: 0L,
    )
}

/**
 * Fold one live reply delta (a `thread:updated.latestReply` frame) into the window. Returns the SAME
 * instance when nothing semantically changes (Gate 4). `replyCount` is DERIVED — it increments by one for a
 * genuinely-new reply and is unchanged on a drop/dup/replay — and is re-based to the authoritative total on
 * the next `getLatestNReplies` snapshot (so any missed-frame drift is bounded and self-correcting).
 */
fun ThreadRepliesReadModel.applyReplyFrame(
    reply: ReplyPreview,
    limit: Int,
): ThreadRepliesReadModel {
    return when {
        // Gate 2 — dedup by IDENTITY: a delta whose messageId is already in the window is a duplicate and is
        // DROPPED (the reply is already present at its real seq) — even if the dup frame claims a HIGHER seq.
        // Identity, not seq magnitude, decides a duplicate; keeping the existing entry keeps the window stable.
        replies.any { it.messageId == reply.messageId } -> this
        // Gate 2 — seam: a reconnect-replay delta at or below the fixed snapshot seam is already covered →
        // drop. A genuinely new reply, even one arriving out of order (9 before 8), has seq > snapshotSeq, so
        // this never drops a real reply — the out-of-order-no-loss case.
        reply.seq <= snapshotSeq -> this
        // Gate 1 — a genuinely new reply lands in its seq position; keep the latest N; count +1 (total, not
        // window size — an eviction still counts).
        else -> copy(
            replies = (replies + reply).sortedBy { it.seq }.takeLast(limit),
            replyCount = replyCount + 1,
        )
    }
}

/** Collapse duplicate messageIds in an initial batch, keeping the higher-seq copy (later state wins). */
private fun List<ReplyPreview>.dedupByMessageIdKeepingHigherSeq(): List<ReplyPreview> {
    val byId = LinkedHashMap<String, ReplyPreview>()
    for (reply in this) {
        val prior = byId[reply.messageId]
        if (prior == null || reply.seq >= prior.seq) byId[reply.messageId] = reply
    }
    return byId.values.toList()
}
