package build.raft.app.data

/*
 * The per-row position rule for an Activity row (#triage:#244, inbox redesign "Fact 1"), as a pure
 * reducer: a line-for-line port of slock `packages/shared/src/activityRowPosition.ts`
 * (`reduceActivityRow`), pinned by the vendored contract cases in
 * `src/commonTest/resources/contract-cases/activity-row/` (README there). No clock, no I/O, no store:
 * nothing in the app calls it yet (SharedActivityStore adopts it in a later PR).
 *
 * A served row says what it has counted up to: its `latestActivitySeq` (P), the highest seq committed
 * and counted in that answer. It does not say that every lower seq is counted (rows commit out of seq
 * order, rarely), so:
 * - a live message above P is applied (counted if the unread predicate says so);
 * - one at or below P already applied by this client (delivered twice) is ignored;
 * - one at or below P not applied is ambiguous: re-read this one row.
 * Merging an answer (Pa): older than the row's delivered position, keep the row; otherwise take it and
 * re-apply the seqs this client applied above Pa. Applied seqs at or below Pa are trusted as counted in
 * it, unless an inversion (a live seq lower than one already applied since the last answer) was seen:
 * then re-read the row. A read covers the applied seqs at or below it: they no longer count, so an
 * answer below them does not count them again. A row with no position takes no live patches: every
 * event for it re-reads it.
 *
 * Seqs are canonical decimal strings (`^(0|[1-9][0-9]*)$`), taken and returned as such and compared
 * exactly (`messages.seq` is a 64-bit bigserial; contract case `seq_above_2_53`).
 */

/** A seq applied to the row by a live message since its last answer, with its verdict (re-applied on a merge). Web `ActivityRowAppliedSeq`. */
internal data class ActivityRowAppliedSeq(val seq: String, val counts: Boolean, val mention: Boolean)

/** Web `ActivityRowState`. */
internal data class ActivityRowState(
    val unreadCount: Int,
    val hasMention: Boolean,
    /** The position delivered by the row's last answer (its `latestActivitySeq`), or null: no live patches. */
    val position: String?,
    /** A mention-only row (nothing else unread): its position is its highest mention seq. */
    val mentionOnly: Boolean,
    /** Seqs applied above [position] since the last answer, ascending. */
    val applied: List<ActivityRowAppliedSeq> = emptyList(),
    /** A live seq lower than an applied one arrived since the last answer (two messages committed out of seq order). */
    val inversionObserved: Boolean = false,
)

/** A served row, as an answer carries it. Web `ActivityRowAnswer`. */
internal data class ActivityRowAnswer(
    val unreadCount: Int,
    val hasMention: Boolean,
    val position: String?,
    val mentionOnly: Boolean,
)

/** Web `ActivityRowEvent`. */
internal sealed interface ActivityRowEvent {
    /** [counts]: the unread predicate's verdict; null = cannot be judged (re-read the row). */
    data class Live(val seq: String, val counts: Boolean?, val mentionsViewer: Boolean) : ActivityRowEvent

    data class Answer(val row: ActivityRowAnswer) : ActivityRowEvent

    /** The viewer read the conversation up to [seq] (a persisted read or a read change's `refSeq`). */
    data class Read(val seq: String) : ActivityRowEvent
}

/** Web `ActivityRowRereadReason` (`ambiguous_seq`, `inversion`, `null_position`, `unjudged`, `partial_read`). */
internal enum class ActivityRowRereadReason { AmbiguousSeq, Inversion, NullPosition, Unjudged, PartialRead }

/** Web `ActivityRowResult`: [reread] asks to re-read this one row (`?ids=`), never the list. */
internal data class ActivityRowResult(
    val row: ActivityRowState,
    val reread: Boolean,
    val rereadReason: ActivityRowRereadReason? = null,
)

private fun String?.isCanonicalSeq(): Boolean = this != null && isCanonicalActivitySeq(this)

/** A row as an answer delivers it, with no client state. Web `activityRowFromAnswer`. */
internal fun activityRowFromAnswer(answer: ActivityRowAnswer): ActivityRowState =
    ActivityRowState(
        unreadCount = answer.unreadCount,
        hasMention = answer.hasMention,
        position = answer.position.takeIf { it.isCanonicalSeq() },
        mentionOnly = answer.mentionOnly,
    )

/** The position the row shows (its `latestActivitySeq` on screen): the delivered one or the highest applied above it. Web `activityRowDisplayPosition`. */
internal fun activityRowDisplayPosition(row: ActivityRowState): String? = row.applied.lastOrNull()?.seq ?: row.position

/** Web `reduceActivityRow`. */
internal fun reduceActivityRow(row: ActivityRowState, event: ActivityRowEvent): ActivityRowResult =
    when (event) {
        is ActivityRowEvent.Live -> applyLive(row, event)
        is ActivityRowEvent.Answer -> mergeAnswer(row, event.row)
        is ActivityRowEvent.Read -> applyRead(row, event.seq)
    }

/**
 * Exact order of two canonical decimal seq strings: >0, 0 or <0. Web `compareActivityRowSeq`: throws on
 * a non-canonical seq (the reducer never passes one), unlike the store's lenient [compareActivitySeq].
 */
internal fun compareActivityRowSeq(a: String, b: String): Int {
    require(isCanonicalActivitySeq(a) && isCanonicalActivitySeq(b)) { "not a canonical decimal seq: $a / $b" }
    if (a.length != b.length) return if (a.length > b.length) 1 else -1
    return a.compareTo(b).coerceIn(-1, 1)
}

private fun keep(row: ActivityRowState) = ActivityRowResult(row, reread = false)

private fun reread(row: ActivityRowState, reason: ActivityRowRereadReason) = ActivityRowResult(row, reread = true, rereadReason = reason)

/**
 * A counted message (a mention included) turns a mention-only row into a normal unread row; a mention
 * that does not count (a muted conversation) lights the @ and keeps it mention-only. Web `withVerdict`.
 */
private fun ActivityRowState.withVerdict(counts: Boolean, mention: Boolean): ActivityRowState {
    if (!counts) return if (mention) copy(hasMention = true) else this
    return copy(unreadCount = unreadCount + 1, hasMention = hasMention || mention, mentionOnly = false)
}

private fun applyLive(row: ActivityRowState, event: ActivityRowEvent.Live): ActivityRowResult {
    val position = row.position ?: return reread(row, ActivityRowRereadReason.NullPosition)
    if (!event.seq.isCanonicalSeq()) return reread(row, ActivityRowRereadReason.Unjudged)
    if (compareActivityRowSeq(event.seq, position) <= 0) return reread(row, ActivityRowRereadReason.AmbiguousSeq)
    if (row.applied.any { it.seq == event.seq }) return keep(row)
    val counts = event.counts ?: return reread(row, ActivityRowRereadReason.Unjudged)
    val highest = activityRowDisplayPosition(row)!!
    val inversion = compareActivityRowSeq(event.seq, highest) < 0
    val entry = ActivityRowAppliedSeq(event.seq, counts = counts, mention = event.mentionsViewer)
    val applied = (row.applied + entry).sortedWith { a, b -> compareActivityRowSeq(a.seq, b.seq) }
    return keep(
        row.withVerdict(entry.counts, entry.mention).copy(applied = applied, inversionObserved = row.inversionObserved || inversion)
    )
}

private fun mergeAnswer(row: ActivityRowState, served: ActivityRowAnswer): ActivityRowResult {
    // A position that is not a canonical seq is no position (fail closed: no live patches).
    val answer = if (served.position.isCanonicalSeq()) served else served.copy(position = null)
    val pa = answer.position ?: return keep(activityRowFromAnswer(answer))
    // An answer older than the one the row came from changes nothing.
    if (row.position != null && compareActivityRowSeq(pa, row.position) < 0) return keep(row)
    val above = row.applied.filter { compareActivityRowSeq(it.seq, pa) > 0 }
    val trusted = row.applied.size - above.size
    var next = activityRowFromAnswer(answer).copy(applied = above, inversionObserved = row.inversionObserved && above.isNotEmpty())
    for (entry in above) next = next.withVerdict(entry.counts, entry.mention)
    return if (trusted > 0 && row.inversionObserved) reread(next, ActivityRowRereadReason.Inversion) else keep(next)
}

private fun applyRead(original: ActivityRowState, seq: String): ActivityRowResult {
    // The seqs the read covers are read: a later answer below them must not count them again.
    val row = original.withAppliedRead(seq)
    val shown = activityRowDisplayPosition(row)
    if (row.unreadCount == 0 && !row.hasMention) return keep(row)
    // A mention-only row's @ is not a cursor matter on every server path; a partial read cannot be
    // split; a row without a position cannot be judged.
    if (shown == null) return reread(row, ActivityRowRereadReason.NullPosition)
    if (!seq.isCanonicalSeq()) return reread(row, ActivityRowRereadReason.Unjudged)
    if (row.mentionOnly || compareActivityRowSeq(seq, shown) < 0) return reread(row, ActivityRowRereadReason.PartialRead)
    return keep(row.copy(unreadCount = 0, hasMention = false))
}

/** The applied seqs at or below a read's seq no longer count or light the @ (they stay applied: a repeat is still a repeat). Web `withAppliedRead`. */
private fun ActivityRowState.withAppliedRead(seq: String): ActivityRowState {
    if (!seq.isCanonicalSeq()) return this
    val covered = { entry: ActivityRowAppliedSeq -> compareActivityRowSeq(entry.seq, seq) <= 0 && (entry.counts || entry.mention) }
    if (applied.none(covered)) return this
    return copy(applied = applied.map { if (covered(it)) it.copy(counts = false, mention = false) else it })
}
