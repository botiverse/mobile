package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.InboxItemKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `mobile_inbox_inplace_v0` drift check, web `checkInPlaceDrift` (botiverse/slock
 * packages/web/src/store/inboxInPlaceUnread.ts, #13694): each first-window answer of the All list
 * is checked against the rows a live message patched in place, so the drift the in-place patch
 * leaves on screen is measured the same way on web and mobile.
 *
 * One check per patched row the answer can judge, sent as the web's `slock.state.transition`
 * (domain `inbox`, event `activity:inplace_check`): outcome `noop` when the answer agrees,
 * `conflict` when it does not, with flat attributes `inbox.inplace.trigger` (message_new /
 * thread_reply), `inbox.inplace.row_existed`, `inbox.inplace.result` and
 * `inbox.inplace.sample_rate` (every conflict is sent; one `noop` in [ActivityInPlaceNoopSample]).
 * An answer still behind the patched row judges nothing: the row waits for the next answer, up to
 * [ActivityInPlaceCheckTtlMillis]; past it the check is `result=expired`. Mobile has no
 * persisted-read event yet, so `read_persisted` is never a trigger here. `order` leaves out rows
 * listed here ahead of the answer (`;ahead=N`, web `aheadOfServed`, slock #14247).
 *
 * As web (#triage:#244), whatever `inbox_row_position_v1` says: an answer only ahead of the patched
 * row that agrees is a match (`;served_ahead=1`), a note no answer judged is `expired`, a row whose
 * answer has an older read than the one accepted here is not judged on it (`;read_behind=N`, web
 * inboxServedReadBehind.ts, slock #14817), and the store judges nothing on an answer a read changed
 * under (web inboxStore `requestReadStateRevision`). Mobile also says why the list was read
 * (`inbox.inplace.fetch_reason`) and whether it was the first read after a return to the foreground
 * (`inbox.inplace.resumed`).
 */
@RaftCoreInternalApi const val ActivityInPlaceCheckTtlMillis = 90_000L
@RaftCoreInternalApi const val ActivityInPlaceExpectationsCap = 200
@RaftCoreInternalApi const val ActivityInPlaceNoopSample = 20

/**
 * `#triage:#245`: delay before re-checking a `selected=1` `unread=0/N` conflict so observability
 * can confirm whether the server's unread count converges to `0` once the in-flight read lands.
 */
@RaftCoreInternalApi const val ActivitySelectedReadFollowupDelayMillis = 30_000L

/** Cap on concurrent pending selected-read convergence follow-ups per store. */
@RaftCoreInternalApi const val ActivitySelectedReadFollowupsCap = 50

/** Deterministic `followup_reason` marker in `outcomeDetail` for selected-read convergence follow-ups. */
@RaftCoreInternalApi const val ActivitySelectedReadFollowupReason = "selected_read_in_flight"

@RaftCoreInternalApi const val ActivityInPlaceCheckTraceDomain = "inbox"
@RaftCoreInternalApi const val ActivityInPlaceCheckTraceEvent = "activity:inplace_check"

enum class ActivityInPlaceCheckOutcome(val wireValue: String) {
    Noop("noop"),
    Conflict("conflict"),
}

/** One `activity:inplace_check`, as web emits it. */
data class ActivityInPlaceCheck(
    /** Web `getInboxItemKey` of the row (`channel:<id>`, `dm:<id>`, `thread:<id>`). */
    val entityId: String,
    /** The live event that patched the row (or found it absent). */
    val trigger: InboxRefreshTrigger,
    val rowExisted: Boolean,
    /** `match`, `appeared`, `absent`, or the drifts joined by `+` (`served_ahead`, `unread`, `mention`, `order`). */
    val result: String,
    val outcome: ActivityInPlaceCheckOutcome,
    /** 1 for a conflict, [ActivityInPlaceNoopSample] for a sampled noop. */
    val sampleRate: Int,
    val outcomeDetail: String,
    val timestampMillis: Long,
    /**
     * Web `inbox.inplace.scope`: `list` (judged by a list answer, order included) or `row` (by an
     * `inbox_row_position_v1` row re-read's answer). Null: not sent.
     */
    val scope: String? = null,
    /** Web `inbox.inplace.reread`: `position` for a row the per-row rule patched or judged; null: not sent. */
    val reread: String? = null,
    /** Web `inbox.inplace.backend`: the judging answer's `X-Raft-Inbox-Backend`, unchanged; null: not sent. */
    val backend: String? = null,
    /** Web `inbox.inplace.not_judged_cause`, with `result=not_judged`. */
    val notJudgedCause: ActivityAuditNotJudgedCause? = null,
    /**
     * `inbox.inplace.fetch_reason` (mobile, list checks): the judging read's `X-Raft-Fetch-Reason`
     * ([InboxFetchReason.wireValue], `none` when it had none); null: not sent (row checks).
     */
    val fetchReason: String? = null,
    /** `inbox.inplace.resumed` (mobile, list checks): the judging read was the first after a return to the foreground. */
    val resumed: Boolean? = null,
    /**
     * `inbox.inplace.selected` (mobile): at check time the checked conversation is open on screen
     * (a page reads it on sight: [ConversationReadOnSight]), so its live messages are read on
     * arrival. Null: not known (no read-on-sight registry), not sent.
     */
    val selected: Boolean? = null,
)

/** Mobile: why the judging list read ran, and whether it was the first one after a return to the foreground. */
@RaftCoreInternalApi data class ActivityInPlaceRead(val fetchReason: String? = null, val resumed: Boolean = false) {
    val detail: String
        get() = ";fetch_reason=${fetchReason ?: "none"};resumed=${if (resumed) 1 else 0}"
}

/**
 * Why a scheduled audit (`position_audit`) was not judged (web `AuditNotJudgedCause`,
 * inboxInPlaceUnread.ts). Each is the one check its audit ends in, so audits scheduled = checks with
 * trigger `position_audit`.
 */
enum class ActivityAuditNotJudgedCause(val wireValue: String) {
    /** The row moved again before the answer (a newer live message, an answer). */
    Moved("moved"),
    /** A re-read of the row was asked for while the audit waited; it takes the audit's place. */
    SupersededByReread("superseded_by_reread"),
    /** Nobody could see the list when the audit's read was due (web: the page was hidden). */
    PageHidden("page_hidden"),
    /** A read changed while the audit's read was in flight (the answer may predate it). */
    ReadChanged("read_changed"),
    /** The audit's read failed. */
    RequestFailed("request_failed"),
    /** The answer left the row out (no longer the viewer's Activity row). */
    RowMissing("row_missing"),
    /** The answer had the row, but it is no longer listed here. */
    RowUnlisted("row_unlisted"),
    /** The server changed before the audit's read or its answer. */
    ServerSwitched("server_switched"),
    /** A follow-up read (after an audit's answer was behind the row) whose answer is still behind it. */
    AnswerBehind("answer_behind"),
}

/** Web `inbox.inplace.reread` for rows the per-row position rule took (`RereadOutcome` `position`). */
@RaftCoreInternalApi const val ActivityInPlaceRereadPosition = "position"

/** Web `inbox.inplace.reread` for an unlisted followed thread inserted live from `thread:updated`. */
@RaftCoreInternalApi const val ActivityInPlaceRereadInserted = "inserted"

/** Web `inbox.inplace.reread` for an unlisted unfollowed thread whose re-read was skipped. */
@RaftCoreInternalApi const val ActivityInPlaceRereadUnfollowed = "unfollowed"

@RaftCoreInternalApi const val ActivityInPlaceScopeList = "list"
@RaftCoreInternalApi const val ActivityInPlaceScopeRow = "row"
@RaftCoreInternalApi const val ActivityInPlaceScopeSyncRow = "sync_row"

@RaftCoreInternalApi const val ActivityForegroundSyncUpdatedTraceEvent = "activity:foreground_sync_updated"

/**
 * Tenny item 3 (#proj-mobile:#163): foreground return -> sync pull + Activity updated latency,
 * sent as `slock.state.transition` (`domain = "inbox"`, `event = "activity:foreground_sync_updated"`).
 */
@RaftCoreInternalApi data class ActivityForegroundSyncUpdated(
    val serverId: String,
    val durationMillis: Long,
    val inboxRowsApplied: Int,
    val syncedOk: Boolean,
    val timestampMillis: Long,
    /** Failure code from [RaftSyncPullResult.Failed.code] (e.g. `timeout`, `http_0`, `snapshot_failed`), null on success. */
    val failureCode: String? = null,
    /** Whether a failed pull kept the stored cursor for a transient retry, null on success. */
    val cursorKept: Boolean? = null,
    /** Time waiting on the scope's sync pull mutex before starting (ms), null when unavailable. */
    val mutexWaitMillis: Long? = null,
    /** Time spent sleeping in `snapshotJitterDelay` (0–20 s on reset / rebootstrap), null when unavailable. */
    val snapshotJitterMillis: Long? = null,
    /** Wall time spent inside the locked sync pull (ms), null when unavailable. */
    val pullDurationMillis: Long? = null,
    /** Pages read on a synced pull, null when failed or unavailable. */
    val pages: Int? = null,
    /** Whether a synced pull ran a first-sync bootstrap, null when failed or unavailable. */
    val firstSync: Boolean? = null,
    /** Server reset reason on a synced pull, null when none. */
    val resetReason: String? = null,
    /** Server `inboxReset` reason on a synced pull, null when none. */
    val inboxResetReason: String? = null,
    /** Whether a synced pull stopped at the page cap, null when failed or unavailable. */
    val truncated: Boolean? = null,
)

private const val MAX_FOREGROUND_SYNC_TOKEN_LENGTH = 64

private fun sanitizeForegroundSyncToken(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized =
        buildString(trimmed.length.coerceAtMost(MAX_FOREGROUND_SYNC_TOKEN_LENGTH)) {
            for (ch in trimmed) {
                if (length >= MAX_FOREGROUND_SYNC_TOKEN_LENGTH) break
                val safe =
                    ch in 'a'..'z' ||
                        ch in 'A'..'Z' ||
                        ch in '0'..'9' ||
                        ch == '_' ||
                        ch == '-' ||
                        ch == '.' ||
                        ch == ':'
                append(if (safe) ch else '_')
            }
        }
    return normalized.takeIf { it.isNotEmpty() }
}

@RaftCoreInternalApi fun activityForegroundSyncUpdatedOf(
    serverId: String,
    durationMillis: Long,
    outcome: RaftSyncPullOutcome,
    timestampMillis: Long,
): ActivityForegroundSyncUpdated {
    val synced = outcome.result as? RaftSyncPullResult.Synced
    val failed = outcome.result as? RaftSyncPullResult.Failed
    return ActivityForegroundSyncUpdated(
        serverId = serverId,
        durationMillis = durationMillis.coerceAtLeast(0L),
        inboxRowsApplied = synced?.inboxRowsApplied ?: 0,
        syncedOk = synced != null,
        timestampMillis = timestampMillis,
        failureCode = failed?.code,
        cursorKept = failed?.cursorKept,
        mutexWaitMillis = outcome.timing.mutexWaitMillis.coerceAtLeast(0L),
        snapshotJitterMillis = outcome.timing.snapshotJitterMillis.coerceAtLeast(0L),
        pullDurationMillis = outcome.timing.pullDurationMillis.coerceAtLeast(0L),
        pages = synced?.pages,
        firstSync = synced?.firstSync,
        resetReason = synced?.resetReason,
        inboxResetReason = synced?.inboxResetReason,
        truncated = synced?.truncated,
    )
}

@RaftCoreInternalApi fun ActivityForegroundSyncUpdated.stateTransitionTraceAttrs(): JsonObject {
    val cleanFailureCode = sanitizeForegroundSyncToken(failureCode)
    val cleanResetReason = sanitizeForegroundSyncToken(resetReason)
    val cleanInboxResetReason = sanitizeForegroundSyncToken(inboxResetReason)
    val detail =
        buildString {
            append("duration_ms=")
            append(durationMillis)
            append(";inbox_rows_applied=")
            append(inboxRowsApplied)
            append(";synced_ok=")
            append(if (syncedOk) 1 else 0)
            cleanFailureCode?.let { append(";failure_code=").append(it) }
            cursorKept?.let { append(";cursor_kept=").append(if (it) 1 else 0) }
            mutexWaitMillis?.let { append(";mutex_wait_ms=").append(it) }
            snapshotJitterMillis?.let { append(";snapshot_jitter_ms=").append(it) }
            pullDurationMillis?.let { append(";pull_duration_ms=").append(it) }
            pages?.let { append(";pages=").append(it) }
            firstSync?.let { append(";first_sync=").append(if (it) 1 else 0) }
            cleanResetReason?.let { append(";reset_reason=").append(it) }
            cleanInboxResetReason?.let { append(";inbox_reset_reason=").append(it) }
            truncated?.let { append(";truncated=").append(if (it) 1 else 0) }
        }
    return buildJsonObject {
        putJsonObject("key") {
            put("domain", ActivityInPlaceCheckTraceDomain)
            put("event", ActivityForegroundSyncUpdatedTraceEvent)
            put("outcome", if (syncedOk) "applied" else "failed")
            put("entityId", "server:$serverId")
        }
        putJsonObject("meta") {
            put("outcomeDetail", detail)
            put("touched", inboxRowsApplied)
            put("timestamp", timestampMillis)
        }
        put("duration_ms", durationMillis.toString())
        put("inbox_rows_applied", inboxRowsApplied.toString())
        put("synced_ok", if (syncedOk) "true" else "false")
        cleanFailureCode?.let { put("failure_code", it) }
        cursorKept?.let { put("cursor_kept", if (it) "true" else "false") }
        mutexWaitMillis?.let { put("mutex_wait_ms", it.toString()) }
        snapshotJitterMillis?.let { put("snapshot_jitter_ms", it.toString()) }
        pullDurationMillis?.let { put("pull_duration_ms", it.toString()) }
        pages?.let { put("pages", it.toString()) }
        firstSync?.let { put("first_sync", if (it) "true" else "false") }
        cleanResetReason?.let { put("reset_reason", it) }
        cleanInboxResetReason?.let { put("inbox_reset_reason", it) }
        truncated?.let { put("truncated", if (it) "true" else "false") }
    }
}

/** An audited row as the patch left it, and whether the audit is the follow-up read (web `audit` / `audit.followup`). */
@RaftCoreInternalApi data class ActivityRowAudit(val moved: Boolean, val followup: Boolean)

/**
 * The attributes web `emitStateTransitionTrace` sends (shared `buildStateTransitionTraceAttrs`
 * key/meta plus the flat `inbox.inplace.*` attributes).
 */
@RaftCoreInternalApi fun ActivityInPlaceCheck.stateTransitionTraceAttrs(): JsonObject =
    buildJsonObject {
        putJsonObject("key") {
            put("domain", ActivityInPlaceCheckTraceDomain)
            put("event", ActivityInPlaceCheckTraceEvent)
            put("outcome", outcome.wireValue)
            put("entityId", entityId)
        }
        putJsonObject("meta") {
            put("outcomeDetail", outcomeDetail)
            put("touched", 1)
            put("timestamp", timestampMillis)
        }
        put("inbox.inplace.trigger", trigger.wireValue)
        put("inbox.inplace.row_existed", if (rowExisted) "true" else "false")
        put("inbox.inplace.result", result)
        put("inbox.inplace.sample_rate", sampleRate.toString())
        scope?.let { put("inbox.inplace.scope", it) }
        reread?.let { put("inbox.inplace.reread", it) }
        backend?.let { put("inbox.inplace.backend", it) }
        notJudgedCause?.let { put("inbox.inplace.not_judged_cause", it.wireValue) }
        fetchReason?.let { put("inbox.inplace.fetch_reason", it) }
        resumed?.let { put("inbox.inplace.resumed", if (it) "true" else "false") }
        selected?.let { put("inbox.inplace.selected", if (it) "1" else "0") }
    }

/**
 * Web `servedReadBehindScopes` (inboxServedReadBehind.ts, slock #14817): the scopes of [served] rows
 * whose read cursor is older and lower than the read accepted here ([ledgerOf]), with activity past
 * it. Such an answer counts the replies read since as unread again; its row is not judged on it.
 * Web also skips a ledger that is a mark (Mark as Unread); the mobile checkpoint does not keep the
 * mark, and a mark is saved only when nothing countable is unread (slock #11795).
 */
@RaftCoreInternalApi fun servedReadBehindScopes(
    served: List<InboxItem>,
    ledgerOf: (scopeId: String) -> ActivityLedgerReadState?
): Set<String> {
    val behind = HashSet<String>()
    served.forEach { item ->
        if (!item.isConversationRow()) return@forEach
        val scopeId = item.inPlaceScopeId() ?: return@forEach
        val servedVersion = item.readStateVersion ?: return@forEach
        val servedReadSeq = item.readStateMaxReadSeq ?: return@forEach
        val latestSeq = item.readStateLatestActivitySeq ?: return@forEach
        val ledger = ledgerOf(scopeId) ?: return@forEach
        if (ledger.readStateVersion <= servedVersion) return@forEach
        val ledgerSeq = ledger.maxReadSeq.toString()
        if (compareActivitySeq(ledgerSeq, servedReadSeq) > 0 && compareActivitySeq(ledgerSeq, latestSeq) < 0) behind += scopeId
    }
    return behind
}

/** Web `inboxItemChannelId`: the conversation a row is (a thread's own channel); none for other rows. */
@RaftCoreInternalApi fun InboxItem.inPlaceScopeId(): String? =
    when (kind) {
        InboxItemKind.Thread -> (threadChannelId ?: channelId).value
        InboxItemKind.Channel, InboxItemKind.DirectMessage -> channelId.value
        else -> null
    }

/** Web `isConversationRow`: a channel, DM or thread row (not a mention action or object row). */
private fun InboxItem.isConversationRow(): Boolean =
    kind == InboxItemKind.Thread || kind == InboxItemKind.Channel || kind == InboxItemKind.DirectMessage

/** Not thread-safe: the store calls it under its realtime owner lock. */
@RaftCoreInternalApi class ActivityInPlaceDriftCheck(private val nowMillis: () -> Long) {
    private class Expectation(
        val trigger: InboxRefreshTrigger,
        val existed: Boolean,
        /** The row's seq once patched; the answer is judged only at this seq. */
        val seq: String?,
        val until: Long,
        /** Web `reread` (`position`: the per-row rule patched it); null otherwise. */
        val reread: String? = null,
        /** When the event came (`;age_ms=` at the check). */
        val at: Long = 0L,
        /** Web `causeDetail` (`;own=…;sender_type=…;message_type=…[;following=…]`); empty when not known. */
        val cause: String = "",
        /** A read state for the scope arrived after the event (web `read_state=1`). */
        var readState: Boolean = false,
        /** Answers not judged as their read was behind the accepted one (web `readBehind`, `;read_behind=N`). */
        var readBehind: Int = 0,
    )

    private val expectations = LinkedHashMap<String, Expectation>()
    private var noopChecks = 0L
    private var expiredAbsentChecks = 0L

    val isEmpty: Boolean
        get() = expectations.isEmpty()

    fun clear() {
        expectations.clear()
    }

    /** A row patched in place by a live message. */
    fun notePatched(row: InboxItem, trigger: InboxRefreshTrigger, reread: String? = null, cause: String = "") {
        val scopeId = row.inPlaceScopeId() ?: return
        remember(scopeId, Expectation(trigger, existed = true, seq = row.latestActivitySeq, until = 0L, reread = reread, cause = cause))
    }

    /** An unlisted followed thread inserted live in place from `thread:updated` (web `noteInsertedRow` + `noteInPlaceRow`). */
    fun noteInserted(row: InboxItem, trigger: InboxRefreshTrigger = InboxRefreshTrigger.ThreadReply, cause: String = "") {
        val scopeId = row.inPlaceScopeId() ?: return
        remember(
            scopeId,
            Expectation(
                trigger = trigger,
                existed = false,
                seq = row.latestActivitySeq,
                until = 0L,
                reread = ActivityInPlaceRereadInserted,
                cause = cause,
            )
        )
    }

    /** Web `noteInPlaceReadState`: a read state for [scopeId] arrived; the next check says so. */
    fun noteReadState(scopeId: String) {
        expectations[scopeId]?.readState = true
    }

    /** A live message for a row the list does not show: the re-read brings it; note whether it came. */
    fun noteAbsent(scopeId: String, trigger: InboxRefreshTrigger, seq: String?) {
        if (expectations[scopeId]?.hasLocalRow == true) return
        remember(scopeId, Expectation(trigger, existed = false, seq = seq, until = 0L))
    }

    /** Web `noteAbsentRereadSkipped`: an unlisted unfollowed thread whose re-read was skipped (conflict if a later answer shows it). */
    fun noteAbsentRereadSkipped(scopeId: String, trigger: InboxRefreshTrigger = InboxRefreshTrigger.ThreadReply, seq: String? = null) {
        if (expectations[scopeId]?.hasLocalRow == true) return
        remember(scopeId, Expectation(trigger, existed = false, seq = seq, until = 0L, reread = ActivityInPlaceRereadUnfollowed))
    }

    /** A `sync_inbox_rows` delete removed the row: clear any pending expectation so it does not expire later. */
    fun noteSyncRowDeleted(scopeId: String) {
        expectations.remove(scopeId)
    }

    private val Expectation.hasLocalRow: Boolean
        get() = existed || reread == ActivityInPlaceRereadInserted

    private fun remember(scopeId: String, expected: Expectation) {
        val now = nowMillis()
        expectations.remove(scopeId)
        expectations[scopeId] =
            Expectation(
                expected.trigger,
                expected.existed,
                expected.seq,
                until = now + ActivityInPlaceCheckTtlMillis,
                reread = expected.reread,
                at = now,
                cause = expected.cause
            )
        if (expectations.size <= ActivityInPlaceExpectationsCap) return
        expectations.entries.removeAll { (_, known) -> now > known.until }
        while (expectations.size > ActivityInPlaceExpectationsCap) {
            expectations.remove(expectations.keys.first())
        }
    }

    /**
     * Web `checkInPlaceDrift` (slock inboxInPlaceUnread.ts): a first-window answer of the All list,
     * newest first. Judges each noted row against what the answer puts on screen ([served], after the
     * local read/Done holds) and the list shown now ([local]); a row of a [readBehind] scope
     * ([servedReadBehindScopes]) waits for another answer. A note past the stale-list bound is sent as
     * `result=expired` (emitExpired). Every check carries `inbox.inplace.scope=list`, its `reread`,
     * the answer's [backend], `;age_ms=` and the [read]'s fetch reason and resume flag. Returns the
     * checks to send (all conflicts, sampled noops, every patched row's expiry).
     */
    fun check(
        local: List<InboxItem>,
        served: List<InboxItem>,
        readBehind: Set<String> = emptySet(),
        backend: String? = null,
        read: ActivityInPlaceRead = ActivityInPlaceRead()
    ): List<ActivityInPlaceCheck> {
        if (expectations.isEmpty()) return emptyList()
        val now = nowMillis()
        val ahead = aheadOfServed(local, served)
        val localKeys = local.asSequence().map { it.key }.filterTo(HashSet()) { it !in ahead }
        val servedKeys = served.asSequence().map { it.key }.filterTo(HashSet()) { it !in ahead }
        val checks = mutableListOf<ActivityInPlaceCheck>()
        val iterator = expectations.entries.iterator()
        while (iterator.hasNext()) {
            val (scopeId, expected) = iterator.next()
            val servedRow = served.firstOrNull { it.inPlaceScopeId() == scopeId }
            val localRow = local.firstOrNull { it.inPlaceScopeId() == scopeId }
            if (now > expected.until) {
                iterator.remove()
                // Done, read away or moved on since: nothing patched in place was left to judge.
                if (expected.hasLocalRow && (localRow == null || localRow.latestActivitySeq != expected.seq)) continue
                expiredCheck(localRow?.key ?: scopeId, expected, now, backend, read)?.let(checks::add)
                continue
            }
            val result: String
            var detail = ""
            if (!expected.hasLocalRow) {
                // Still absent: maybe the lag; wait. Arrived: the re-read was what showed it.
                if (servedRow == null) continue
                result = "appeared"
                detail = ";served_unread=${servedRow.unreadCount}"
            } else if (localRow == null || localRow.latestActivitySeq != expected.seq) {
                // Done, read away, or moved on since: nothing patched in place to judge.
                iterator.remove()
                continue
            } else if (servedRow == null) {
                result = "absent"
            } else {
                val seqOrder = compareActivitySeq(servedRow.latestActivitySeq, expected.seq)
                if (seqOrder < 0) continue
                // Its read is behind the one accepted here: its count is from the older cursor.
                if (scopeId in readBehind) {
                    expected.readBehind += 1
                    continue
                }
                val localIndex = sharedIndex(local, servedKeys, localRow.key)
                val servedIndex = sharedIndex(served, localKeys, localRow.key)
                val drift =
                    buildList {
                        if (servedRow.unreadCount != localRow.unreadCount) add("unread")
                        if (servedRow.hasMention != localRow.hasMention) add("mention")
                        if (localIndex != servedIndex) add("order")
                    }.toMutableList()
                // Web: an answer at a newer message than the patched row, that agrees on the unread,
                // the @ and the order (no row ahead of it here), is a match (`;served_ahead=1`).
                val servedAheadOnly = seqOrder > 0 && drift.isEmpty() && ahead.isEmpty()
                if (seqOrder > 0 && !servedAheadOnly) drift.add(0, "served_ahead")
                result = if (drift.isEmpty()) "match" else drift.joinToString("+")
                detail =
                    ";unread=${localRow.unreadCount}/${servedRow.unreadCount}" +
                        ";mention=${if (localRow.hasMention) 1 else 0}/${if (servedRow.hasMention) 1 else 0}" +
                        ";order=$localIndex/$servedIndex" +
                        ";ahead=${ahead.size}" +
                        if (servedAheadOnly) ";served_ahead=1" else ""
            }
            iterator.remove()
            val agrees =
                result == "match" ||
                    (result == "appeared" && expected.reread != ActivityInPlaceRereadUnfollowed)
            // Every conflict; one agreement in ActivityInPlaceNoopSample (counted, so exact over a session).
            if (agrees && noopChecks++ % ActivityInPlaceNoopSample != 0L) continue
            checks +=
                ActivityInPlaceCheck(
                    entityId = localRow?.key ?: servedRow?.key ?: scopeId,
                    trigger = expected.trigger,
                    rowExisted = expected.existed,
                    result = result,
                    outcome = if (agrees) ActivityInPlaceCheckOutcome.Noop else ActivityInPlaceCheckOutcome.Conflict,
                    sampleRate = if (agrees) ActivityInPlaceNoopSample else 1,
                    outcomeDetail =
                        "trigger=${expected.trigger.wireValue};existed=${if (expected.existed) 1 else 0};result=$result$detail" +
                            webListSuffix(expected, now, readBehindAlways = false) + read.detail,
                    timestampMillis = now,
                    scope = ActivityInPlaceScopeList,
                    reread = expected.reread,
                    backend = backend,
                    fetchReason = read.fetchReason ?: "none",
                    resumed = read.resumed,
                )
        }
        return checks
    }

    /**
     * Tenny requirement (#proj-mobile:#163 818f094b): judge a pending in-place expectation for
     * [scopeId] against an incoming `sync_inbox_rows` upsert ([servedRow], after local read/Done
     * holds) before [localRow] is replaced. Without full `/api/channels/inbox` reloads, expectations
     * would otherwise sit unjudged for 90 s and expire. Emits with `scope = "sync_row"` (no list
     * order comparison because incremental sync carries only changed rows).
     */
    fun checkSyncRow(
        scopeId: String,
        localRow: InboxItem?,
        servedRow: InboxItem,
        readBehind: Boolean = false,
    ): ActivityInPlaceCheck? {
        val expected = expectations[scopeId] ?: return null
        val now = nowMillis()
        val read = ActivityInPlaceRead(fetchReason = "sync_inbox_rows", resumed = false)
        if (now > expected.until) {
            expectations.remove(scopeId)
            if (expected.hasLocalRow && (localRow == null || localRow.latestActivitySeq != expected.seq)) return null
            return expiredCheck(localRow?.key ?: servedRow.key, expected, now, backend = null, read = read, scope = ActivityInPlaceScopeSyncRow)
        }
        val result: String
        val detail: String
        if (!expected.hasLocalRow) {
            result = "appeared"
            detail = ";served_unread=${servedRow.unreadCount}"
        } else if (localRow == null || localRow.latestActivitySeq != expected.seq) {
            expectations.remove(scopeId)
            return null
        } else {
            val seqOrder = compareActivitySeq(servedRow.latestActivitySeq, expected.seq)
            if (seqOrder < 0) return null
            if (readBehind) {
                expected.readBehind += 1
                return null
            }
            val drift =
                buildList {
                    if (servedRow.unreadCount != localRow.unreadCount) add("unread")
                    if (servedRow.hasMention != localRow.hasMention) add("mention")
                }.toMutableList()
            val servedAheadOnly = seqOrder > 0 && drift.isEmpty()
            if (seqOrder > 0 && !servedAheadOnly) drift.add(0, "served_ahead")
            result = if (drift.isEmpty()) "match" else drift.joinToString("+")
            detail =
                ";unread=${localRow.unreadCount}/${servedRow.unreadCount}" +
                    ";mention=${if (localRow.hasMention) 1 else 0}/${if (servedRow.hasMention) 1 else 0}" +
                    if (servedAheadOnly) ";served_ahead=1" else ""
        }
        expectations.remove(scopeId)
        val agrees =
            result == "match" ||
                (result == "appeared" && expected.reread != ActivityInPlaceRereadUnfollowed)
        if (agrees && noopChecks++ % ActivityInPlaceNoopSample != 0L) return null
        return ActivityInPlaceCheck(
            entityId = localRow?.key ?: servedRow.key,
            trigger = expected.trigger,
            rowExisted = expected.existed,
            result = result,
            outcome = if (agrees) ActivityInPlaceCheckOutcome.Noop else ActivityInPlaceCheckOutcome.Conflict,
            sampleRate = if (agrees) ActivityInPlaceNoopSample else 1,
            outcomeDetail =
                "trigger=${expected.trigger.wireValue};existed=${if (expected.existed) 1 else 0};result=$result$detail" +
                    webListSuffix(expected, now, readBehindAlways = false) + read.detail,
            timestampMillis = now,
            scope = ActivityInPlaceScopeSyncRow,
            reread = expected.reread,
            backend = null,
            fetchReason = read.fetchReason ?: "none",
            resumed = read.resumed,
        )
    }

    /**
     * Web `outcomeDetail` past the result: `;reread=`, the event's cause, `;age_ms=` (since the row
     * was patched), `;read_state=` and `;read_behind=N` (when an answer was behind the read, and
     * always for an expired check).
     */
    private fun webListSuffix(expected: Expectation, now: Long, readBehindAlways: Boolean): String =
        (expected.reread?.let { ";reread=$it" } ?: "") + expected.cause +
            ";age_ms=${now - expected.at};read_state=${if (expected.readState) 1 else 0}" +
            if (readBehindAlways || expected.readBehind > 0) ";read_behind=${expected.readBehind}" else ""

    /**
     * Web `emitExpired`: a note no answer judged within the stale-list bound, `result=expired`,
     * outcome noop. Every patched row's (sample rate 1); one unlisted row's in [ActivityInPlaceNoopSample].
     */
    private fun expiredCheck(
        entityId: String,
        expected: Expectation,
        now: Long,
        backend: String?,
        read: ActivityInPlaceRead,
        scope: String = ActivityInPlaceScopeList,
    ): ActivityInPlaceCheck? {
        if (!expected.hasLocalRow && expiredAbsentChecks++ % ActivityInPlaceNoopSample != 0L) return null
        return ActivityInPlaceCheck(
            entityId = entityId,
            trigger = expected.trigger,
            rowExisted = expected.existed,
            result = "expired",
            outcome = ActivityInPlaceCheckOutcome.Noop,
            sampleRate = if (expected.hasLocalRow) 1 else ActivityInPlaceNoopSample,
            outcomeDetail =
                "trigger=${expected.trigger.wireValue};existed=${if (expected.existed) 1 else 0};result=expired" +
                    webListSuffix(expected, now, readBehindAlways = true) + read.detail,
            timestampMillis = now,
            scope = scope,
            reread = expected.reread,
            backend = backend,
            fetchReason = read.fetchReason ?: "none",
            resumed = read.resumed,
        )
    }

    /**
     * Web `checkRowAnswerDrift` (`inbox_row_position_v1`): a row re-read's answer judges its own row.
     * [served] (after the store's holds) against [local], the row shown when it came: unread, @ and
     * position (no order). Every conflict, one agreement in [ActivityInPlaceNoopSample] (the counter
     * the list check uses), trigger = why the row was read, scope `row`.
     *
     * An audit ([audit] set, trigger `position_audit`) is always sent. One whose row moved before
     * its answer is `not_judged` / `moved`. An audit answer behind the row judges [merged] (the row
     * once the answer is taken) instead: `match_up_to_answer` or the drift with `_up_to_answer`, and
     * `;answer_behind=1`; the second value is then true (the caller reads the row once more,
     * [ActivityAuditFollowupDelayMillis] later). A follow-up whose answer is still behind is
     * `not_judged` / `answer_behind`. Returns the check to send (null: a sampled-out agreement).
     */
    fun checkRow(
        local: InboxItem,
        served: InboxItem,
        trigger: InboxRefreshTrigger,
        backend: String?,
        audit: ActivityRowAudit?,
        merged: InboxItem?,
        mergeNote: String
    ): Pair<ActivityInPlaceCheck?, Boolean> {
        val localSeq = local.latestActivitySeq
        val servedSeq = served.latestActivitySeq
        val followup = if (audit?.followup == true) ";followup=1" else ""
        val detail =
            ";unread=${local.unreadCount}/${served.unreadCount}" +
                ";mention=${if (local.hasMention) 1 else 0}/${if (served.hasMention) 1 else 0}" +
                ";position=${localSeq ?: "-"}/${servedSeq ?: "-"}$followup"
        if (audit?.moved == true) return notJudged(local, ActivityAuditNotJudgedCause.Moved, backend, detail) to false
        val behind = audit != null && localSeq != null && servedSeq != null && compareActivitySeq(servedSeq, localSeq) < 0
        if (behind && audit!!.followup) return notJudged(local, ActivityAuditNotJudgedCause.AnswerBehind, backend, detail) to false
        val judged = if (behind && merged != null) merged else served
        val judgedSeq = judged.latestActivitySeq
        val now = nowMillis()
        val drift =
            buildList {
                if (judged.unreadCount != local.unreadCount) add("unread")
                if (judged.hasMention != local.hasMention) add("mention")
                if (localSeq != judgedSeq) add("position")
            }
        val agrees = drift.isEmpty()
        val sampled = agrees && audit == null
        if (sampled && noopChecks++ % ActivityInPlaceNoopSample != 0L) return null to false
        val upToAnswer = behind && merged != null
        val result = (if (agrees) "match" else drift.joinToString("+")) + if (upToAnswer) "_up_to_answer" else ""
        val fullDetail =
            detail + if (upToAnswer) ";answer_behind=1;merged=${judged.unreadCount}/${judgedSeq ?: "-"}$mergeNote" else ""
        return rowCheck(
            entityId = local.key,
            trigger = trigger,
            outcome = if (agrees) ActivityInPlaceCheckOutcome.Noop else ActivityInPlaceCheckOutcome.Conflict,
            result = result,
            detail = fullDetail,
            sampleRate = if (sampled) ActivityInPlaceNoopSample else 1,
            backend = backend,
            notJudgedCause = null,
            now = now
        ) to upToAnswer
    }

    /**
     * Web `recordAuditNotJudged`: an audit that ends without a judgment. `activity:inplace_check`,
     * outcome noop, `result=not_judged`, `inbox.inplace.not_judged_cause`, trigger `position_audit`,
     * scope `row`, always sent. [row]: the row as the patch left it (or as shown).
     */
    fun notJudged(row: InboxItem, cause: ActivityAuditNotJudgedCause, backend: String? = null, detail: String = ""): ActivityInPlaceCheck =
        rowCheck(
            entityId = row.key,
            trigger = InboxRefreshTrigger.PositionAudit,
            outcome = ActivityInPlaceCheckOutcome.Noop,
            result = "not_judged",
            detail = ";cause=${cause.wireValue}$detail",
            sampleRate = 1,
            backend = backend,
            notJudgedCause = cause,
            now = nowMillis()
        )

    /** Web `emitCheck(rowCheckExpectation(...))`: existed, reread `position`, age 0, no read state. */
    private fun rowCheck(
        entityId: String,
        trigger: InboxRefreshTrigger,
        outcome: ActivityInPlaceCheckOutcome,
        result: String,
        detail: String,
        sampleRate: Int,
        backend: String?,
        notJudgedCause: ActivityAuditNotJudgedCause?,
        now: Long
    ): ActivityInPlaceCheck =
        ActivityInPlaceCheck(
            entityId = entityId,
            trigger = trigger,
            rowExisted = true,
            result = result,
            outcome = outcome,
            sampleRate = sampleRate,
            outcomeDetail =
                "trigger=${trigger.wireValue};existed=1;result=$result$detail;reread=$ActivityInPlaceRereadPosition;age_ms=0;read_state=0",
            timestampMillis = now,
            scope = "row",
            reread = ActivityInPlaceRereadPosition,
            backend = backend,
            notJudgedCause = notJudgedCause,
        )

    /**
     * Web `aheadOfServed` (slock #14247): keys of the rows listed here at a newer message than the
     * answer has (a live message the view has not caught up with). The answer still sorts them at
     * their old place, so they are left out of the order comparison: a lagging neighbour does not
     * move the judged row.
     */
    private fun aheadOfServed(local: List<InboxItem>, served: List<InboxItem>): Set<String> {
        val servedSeq = served.filter { it.isConversationRow() }.associate { it.key to it.latestActivitySeq }
        return local
            .asSequence()
            .filter { it.isConversationRow() && it.key in servedSeq }
            .filter { compareActivitySeq(it.latestActivitySeq, servedSeq[it.key]) > 0 }
            .mapTo(HashSet()) { it.key }
    }

    /** Index of [key] among the rows both lists have, so a row only one side lists does not shift it. */
    private fun sharedIndex(rows: List<InboxItem>, others: Set<String>, key: String): Int =
        rows.asSequence().map { it.key }.filter { it in others }.indexOf(key)
}

/** Reads `k=v` from a semicolon-delimited `outcomeDetail` string, or null when absent. */
private fun String.outcomeDetailField(key: String): String? {
    var start = 0
    val prefix = "$key="
    while (start <= length) {
        val end = indexOf(';', start).let { if (it < 0) length else it }
        if (regionMatches(start, prefix, 0, prefix.length)) {
            return substring(start + prefix.length, end)
        }
        if (end == length) break
        start = end + 1
    }
    return null
}

/** True when this check is already an audit follow-up (`;followup=1`) or a selected-read follow-up (`;selected_followup=1`). */
@RaftCoreInternalApi fun ActivityInPlaceCheck.isFollowupCheck(): Boolean =
    outcomeDetail.outcomeDetailField("followup") == "1" ||
        outcomeDetail.outcomeDetailField("selected_followup") == "1"

/**
 * `#triage:#245`: returns the served unread count (`N > 0`) when this check is an initial
 * (non-follow-up) `selected=1` conflict whose local row showed `unread=0` while the server
 * reported `unread=N`, or null otherwise.
 */
@RaftCoreInternalApi fun ActivityInPlaceCheck.selectedUnreadZeroServedCountOrNull(): Int? {
    if (outcome != ActivityInPlaceCheckOutcome.Conflict || selected != true) return null
    if (isFollowupCheck()) return null
    val resultParts = result.removeSuffix("_up_to_answer").split('+')
    if ("unread" !in resultParts) return null
    val unreadField = outcomeDetail.outcomeDetailField("unread") ?: return null
    val slash = unreadField.indexOf('/')
    if (slash <= 0 || slash >= unreadField.lastIndex) return null
    val localUnread = unreadField.substring(0, slash).toIntOrNull() ?: return null
    val servedUnread = unreadField.substring(slash + 1).toIntOrNull() ?: return null
    return servedUnread.takeIf { localUnread == 0 && servedUnread > 0 }
}

/**
 * `#triage:#245`: builds the ~30s follow-up `activity:inplace_check` after a `selected=1`
 * `unread=0/N` conflict. Always sent with `sampleRate = 1` (never sampled out, whether the server
 * converged to `match` / `noop` or remained a `conflict`) and never emits trigger `position_audit`,
 * preserving `audits scheduled = checks with trigger position_audit`. Carries no conversation ID in
 * `outcomeDetail` or flat attributes.
 */
@RaftCoreInternalApi fun buildSelectedReadFollowupCheck(
    entityId: String,
    trigger: InboxRefreshTrigger,
    reread: String?,
    initialScope: String?,
    initialPosition: String?,
    initialServedUnread: Int,
    served: InboxItem,
    localUnreadNow: Int?,
    selectedNow: Boolean?,
    backend: String?,
    readStateArrived: Boolean,
    ageMillis: Long,
    nowMillis: Long,
): ActivityInPlaceCheck {
    val followupTrigger = if (trigger == InboxRefreshTrigger.PositionAudit) InboxRefreshTrigger.Other else trigger
    val servedSeq = served.latestActivitySeq
    val servedUnread = served.unreadCount
    val servedMention = served.hasMention
    val servedAhead =
        initialPosition != null &&
            servedSeq != null &&
            compareActivitySeq(servedSeq, initialPosition) > 0
    val initialReadOnServer =
        initialPosition != null &&
            served.readStateMaxReadSeq != null &&
            compareActivitySeq(served.readStateMaxReadSeq, initialPosition) >= 0

    val outcome: ActivityInPlaceCheckOutcome
    val result: String
    val reportedLocalUnread: Int
    val reportedLocalMention: Int
    val extraDetail: String
    when {
        servedUnread == 0 && !servedMention -> {
            outcome = ActivityInPlaceCheckOutcome.Noop
            result = "match"
            reportedLocalUnread = 0
            reportedLocalMention = 0
            extraDetail = if (servedAhead) ";served_ahead=1" else ""
        }
        servedAhead && initialReadOnServer && selectedNow != true -> {
            outcome = ActivityInPlaceCheckOutcome.Noop
            result = "match"
            reportedLocalUnread = localUnreadNow ?: 0
            reportedLocalMention = 0
            extraDetail = ";served_ahead=1;read_converged=1"
        }
        else -> {
            val drift =
                buildList {
                    if (servedAhead) add("served_ahead")
                    if (servedUnread > 0) add("unread")
                    if (servedMention) add("mention")
                }
            outcome = ActivityInPlaceCheckOutcome.Conflict
            result = drift.joinToString("+")
            reportedLocalUnread = 0
            reportedLocalMention = 0
            extraDetail = ""
        }
    }

    val rereadPart = reread?.let { ";reread=$it" } ?: ""
    val initialScopePart = initialScope?.let { ";initial_scope=$it" } ?: ""
    val outcomeDetail =
        "trigger=${followupTrigger.wireValue};existed=1;result=$result" +
            ";unread=$reportedLocalUnread/$servedUnread" +
            ";mention=$reportedLocalMention/${if (servedMention) 1 else 0}" +
            ";position=${initialPosition ?: "-"}/${servedSeq ?: "-"}" +
            "$extraDetail$rereadPart$initialScopePart" +
            ";initial_served_unread=$initialServedUnread" +
            ";selected_followup=1;followup_reason=$ActivitySelectedReadFollowupReason" +
            ";age_ms=${ageMillis.coerceAtLeast(0L)}" +
            ";read_state=${if (readStateArrived) 1 else 0}"

    return ActivityInPlaceCheck(
        entityId = entityId,
        trigger = followupTrigger,
        rowExisted = true,
        result = result,
        outcome = outcome,
        sampleRate = 1,
        outcomeDetail = outcomeDetail,
        timestampMillis = nowMillis,
        scope = ActivityInPlaceScopeRow,
        reread = reread,
        backend = backend,
        notJudgedCause = null,
        fetchReason = null,
        resumed = null,
    )
}
