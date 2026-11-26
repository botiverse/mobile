package build.raft.app.contract

import build.raft.app.data.ActivityRealtimeBackgroundRefreshIntervalMillis
import build.raft.app.data.DataResult
import build.raft.app.data.FakeInboxRepository
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxFetchReason
import build.raft.app.data.InboxFetchReasonContext
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxRepository
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.InboxTaskConversation
import build.raft.app.data.SharedActivityIntent
import build.raft.app.data.SharedActivityStore
import build.raft.app.data.activityItem
import build.raft.app.data.activityStore
import build.raft.app.data.message
import build.raft.app.data.sampleSession
import build.raft.app.data.snapshot
import build.raft.app.data.threadItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.MessageMention
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageSystemProvenance
import build.raft.app.model.MessageType
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import build.raft.app.data.RaftSyncChange
import build.raft.app.data.RaftSyncInboxRowDeleteReason
import build.raft.app.data.RaftSyncInboxRowSource
import build.raft.app.data.RaftSyncInboxRowsOutcome
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * App-side policy for the vendored Activity row contract cases, beside the case's own `knownGaps`
 * (the vendored files stay byte-identical to slock, so the app's gaps live here).
 *
 * An agreed case the app currently disagrees with: skipped with the reason (this loader does not
 * change product behaviour). The runner still drives it and reports whether it still differs, so a
 * gap the product closes is noticed.
 *
 * Empty since `inbox_row_position_v1` (#triage:#244, PR B): the store, with the flag on as the
 * harness runs it, keeps each row by the shared rule and passes every agreed case. The flag-off path
 * is today's and still differs from the position and system-line cases; SharedActivityStoreTest pins it.
 *
 * A case with `markUnread` is not a product gap but one this store harness cannot drive: its `read` act
 * is a version-ordered read state, which the store rightly orders after the mark, while the case's late
 * read is a stale ack of a read from before it. The reducer runs the case
 * (ActivityRowContractCaseReducerTest); ActivityRowReadSeqTest pins the ledger's ceiling and
 * SharedActivityStoreRowPositionTest the store's Mark as Unread.
 */
internal val AppActivityRowKnownGaps: Map<String, String> =
    mapOf(
        "mark_unread_then_late_read_ack" to
            "store harness: its read act is a newer read state, not a stale read ack (reducer and ledger tests cover it)",
    )

/** One way the app can run a case: [decline] says why it cannot (null = it can). */
internal class ActivityRowCaseDriver(
    val name: String,
    val decline: (ActivityRowContractCase) -> String?,
    val run: suspend TestScope.(ActivityRowContractCase) -> List<String>,
)

internal sealed interface ActivityRowCasePlan {
    data class Run(val driver: ActivityRowCaseDriver) : ActivityRowCasePlan

    /** [driver] is set when the app could run it anyway, so the report can tell whether the gap still holds. */
    data class Skip(val reason: String, val driver: ActivityRowCaseDriver? = null) : ActivityRowCasePlan

    /** No driver for an agreed case nobody listed: a new kind of case this loader must learn. */
    data class Misconfigured(val reason: String) : ActivityRowCasePlan
}

internal val ActivityRowCaseDrivers: List<ActivityRowCaseDriver> = listOf(positionDriver(), syncInboxRowDriver(), predicateDriver())

internal fun planActivityRowCase(case: ActivityRowContractCase): ActivityRowCasePlan {
    if (case.status == "pending-product-decision") {
        return ActivityRowCasePlan.Skip(
            "pending product decision: ${case.decision?.question} (owner ${case.decision?.owner})"
        )
    }
    if (case.status != "agreed") return ActivityRowCasePlan.Skip("status '${case.status}' is not one this loader knows")
    val misses = mutableListOf<String>()
    val driver =
        ActivityRowCaseDrivers.firstOrNull { candidate ->
            val reason = candidate.decline(case)
            if (reason != null) misses += "${candidate.name}: $reason"
            reason == null
        }
    case.knownGaps.firstOrNull { it.client == "app" }?.let { gap ->
        return ActivityRowCasePlan.Skip("app gap: ${gap.reason}", driver)
    }
    AppActivityRowKnownGaps[case.id]?.let { reason -> return ActivityRowCasePlan.Skip("app gap: $reason", driver) }
    return if (driver != null) {
        ActivityRowCasePlan.Run(driver)
    } else {
        ActivityRowCasePlan.Misconfigured("${case.id}: no app driver (${misses.joinToString("; ")})")
    }
}

// ---- The harness: the real SharedActivityStore, one row, scripted inbox answers ------------------

private const val ViewerId = "viewer-1"
private const val ServerIdValue = "server-1"

/**
 * The inbox the store reads. The first read (the entry load) answers with the given row; every later
 * read waits for the case's next `answer`. A read the store starts on its own (not the harness's
 * `Refresh` for an answer, not the once-a-minute in-place reconcile) is the case's re-read: with
 * `inbox_row_position_v1` the row's own re-read (`?ids=`, [loadRows]), else a list read of the first
 * window, which covers the case's one row.
 */
private class ScriptedInbox(
    private val first: InboxSnapshot,
) : InboxRepository by FakeInboxRepository(initialSnapshots = listOf(first)) {
    data class Read(val reason: InboxFetchReason?, val byHarness: Boolean, val rowIds: List<String>? = null)

    val reads = mutableListOf<Read>()
    var harnessAsking = false
    var pending: CompletableDeferred<InboxSnapshot>? = null
        private set

    val rereadAsked: Boolean
        get() = reads.drop(1).any { !it.byHarness && it.reason != InboxFetchReason.InPlaceReconcile }

    /** A row re-read (`?ids=`) was asked: the store could not judge the message in place. */
    val rowRereadAsked: Boolean
        get() = reads.any { it.rowIds != null }

    override suspend fun loadInitial(serverId: ServerId, filter: InboxFilter, limit: Int): DataResult<InboxSnapshot> {
        val reason = currentCoroutineContext()[InboxFetchReasonContext]?.reason
        reads += Read(reason, byHarness = harnessAsking)
        harnessAsking = false
        if (reads.size == 1) return DataResult.Content(first)
        return awaitAnswer()
    }

    override suspend fun loadRows(serverId: ServerId, rowIds: List<String>): DataResult<InboxSnapshot> {
        reads += Read(currentCoroutineContext()[InboxFetchReasonContext]?.reason, byHarness = false, rowIds = rowIds)
        return awaitAnswer()
    }

    private suspend fun awaitAnswer(): DataResult<InboxSnapshot> {
        val answer = CompletableDeferred<InboxSnapshot>()
        pending = answer
        try {
            return DataResult.Content(answer.await())
        } finally {
            if (pending === answer) pending = null
        }
    }
}

private class ActivityRowHarness(val store: SharedActivityStore, val inbox: ScriptedInbox, val rowKey: String) {
    private var readStateVersion = 1L

    fun observed(): ActivityRowObserved? =
        store.state.value.items.firstOrNull { it.key == rowKey }?.let { row ->
            ActivityRowObserved(row.unreadCount, row.hasMention, row.latestActivitySeq)
        }

    fun row(): InboxItem? = store.state.value.items.firstOrNull { it.key == rowKey }

    fun nextReadStateVersion(): Long = ++readStateVersion
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.loadedHarness(row: InboxItem): ActivityRowHarness {
    val inbox = ScriptedInbox(snapshot(listOf(row), serverId = ServerIdValue))
    val store =
        activityStore(
            scope = backgroundScope,
            inboxRepository = inbox,
            authStore = InMemoryRaftAuthSessionStore(sampleSession(userId = ViewerId, serverId = ServerIdValue)),
            realtimeBackgroundRefreshIntervalMillis = ActivityRealtimeBackgroundRefreshIntervalMillis,
            nowMillis = { testScheduler.currentTime },
            inboxInPlaceEnabled = { true },
            inboxRowPositionEnabled = { true },
        )
    store.dispatch(SharedActivityIntent.LoadInitial)
    runCurrent()
    return ActivityRowHarness(store, inbox, row.key)
}

/**
 * Lets the step settle: whatever re-read it asked for starts (the store's throttle window is
 * [ActivityRealtimeBackgroundRefreshIntervalMillis]); far inside the in-place reconcile minute and the
 * live patch's hold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() {
    runCurrent()
    advanceTimeBy(ActivityRealtimeBackgroundRefreshIntervalMillis + 1L)
    runCurrent()
}

// ---- position ------------------------------------------------------------------------------------

private const val PositionChannel = "channel-1"

/** A served channel row; `position` is both its `latestActivitySeq` and its read-state frontier. */
private fun ActivityRowServed.toChannelRow(): InboxItem =
    activityItem(PositionChannel, unreadCount = unreadCount).copy(
        hasMention = hasMention,
        firstMentionMessageId = if (hasMention) MessageId("mention-$position") else null,
        lastMessageId = MessageId("m-${position ?: "unpaired"}"),
        latestActivitySeq = position,
        readStateLatestActivitySeq = position,
    )

/**
 * The live message the app judges the way the step's verdict says (the predicate's own inputs):
 * counts → another member's message (@ the viewer when [ActivityRowStep.LiveMessage.mentionsViewer]);
 * does not count, no @ → the viewer's own; does not count, @ → a mention past the Activity mute (the
 * README's example, delivered as the shell does: `allowLocalPatch = false`, `activityMuted = true`);
 * cannot judge → a system line without its provenance.
 */
private fun ActivityRowStep.LiveMessage.toIntent(seq: Long): SharedActivityIntent.ApplyRealtimeEvent {
    val id = "m-${this.seq}"
    val mentions = if (mentionsViewer) listOf(MessageMention(type = "user", id = ViewerId)) else emptyList()
    // `moves: false`: a message past the Activity mute, as the shell delivers it.
    if (!moves) {
        return SharedActivityIntent.ApplyRealtimeEvent(
            MessageNew(message(id, channelId = PositionChannel, seq = seq).copy(mentions = mentions)),
            allowLocalPatch = false,
            activityMuted = true,
        )
    }
    return when (counts) {
        true ->
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(message(id, channelId = PositionChannel, seq = seq).copy(mentions = mentions))
            )
        false ->
            if (mentionsViewer) {
                SharedActivityIntent.ApplyRealtimeEvent(
                    MessageNew(message(id, channelId = PositionChannel, seq = seq).copy(mentions = mentions)),
                    allowLocalPatch = false,
                    activityMuted = true,
                )
            } else {
                SharedActivityIntent.ApplyRealtimeEvent(
                    MessageNew(message(id, channelId = PositionChannel, authorId = ViewerId, seq = seq))
                )
            }
        null ->
            SharedActivityIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(id, channelId = PositionChannel, authorId = "system", seq = seq).let {
                        it.copy(
                            messageType = MessageType.System,
                            author = it.author.copy(kind = MemberKind.System),
                            mentions = mentions,
                        )
                    },
                    systemProvenance = null,
                )
            )
    }
}

private fun positionDriver() =
    ActivityRowCaseDriver(
        name = "position",
        decline = { case ->
            when {
                case.rule != "position" -> "not a position case"
                case.given.row == null -> "no given.row"
                case.expect?.reread == null -> "no expected reread"
                case.checkpoints.any { it.afterStep !in 1..case.steps.size } -> "a checkpoint is out of range"
                else -> null
            }
        },
        run = { case -> runPositionCase(case) },
    )

/**
 * `sync_inbox_row` (slock #15312): the same harness, with each `answer` delivered as a sync `inbox_row`
 * upsert of the row and each `syncDelete` as a delete, through [SharedActivityStore.applySyncInboxRows]
 * as the sync router hands them over (live messages and reads as in a position case).
 */
private fun syncInboxRowDriver() =
    ActivityRowCaseDriver(
        name = "sync_inbox_row",
        decline = { case ->
            when {
                case.rule != "sync_inbox_row" -> "not a sync_inbox_row case"
                case.given.row == null -> "no given.row"
                case.expect?.reread == null -> "no expected reread"
                case.checkpoints.any { it.afterStep !in 1..case.steps.size } -> "a checkpoint is out of range"
                else -> null
            }
        },
        run = { case -> runPositionCase(case) },
    )

/** The row as `GET /api/channels/inbox?ids=` (and so an `inbox_row` upsert) serves it: [toChannelRow] on the wire. */
private fun ActivityRowServed.toWireRow(): JsonObject {
    val fields =
        buildMap<String, JsonElement> {
            put("kind", JsonPrimitive("channel"))
            put("channelId", JsonPrimitive(PositionChannel))
            put("channelName", JsonPrimitive(PositionChannel))
            put("channelType", JsonPrimitive("channel"))
            put("unreadCount", JsonPrimitive(unreadCount))
            put("hasMention", JsonPrimitive(hasMention))
            if (hasMention) put("firstMentionMessageId", JsonPrimitive("mention-$position"))
            put("lastMessageId", JsonPrimitive("m-${position ?: "unpaired"}"))
            put("latestActivitySeq", position?.let(::JsonPrimitive) ?: JsonNull)
            if (position != null) {
                put(
                    "readState",
                    JsonObject(
                        buildMap<String, JsonElement> {
                            put("kind", JsonPrimitive("present"))
                            put("readStateVersion", JsonPrimitive(1))
                            readSeq?.let { put("maxReadSeq", JsonPrimitive(it)) }
                            put("latestActivity", JsonObject(mapOf("messageId" to JsonPrimitive("m-$position"), "seq" to JsonPrimitive(position))))
                        }
                    ),
                )
            }
        }
    return JsonObject(fields)
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.runPositionCase(case: ActivityRowContractCase): List<String> {
    val failures = mutableListOf<String>()
    val h = loadedHarness(case.given.row!!.toChannelRow())
    val shown = activityRowMismatches("as served", case.given.row.toExpectation(), h.observed())
    if (shown.isNotEmpty()) return listOf("the harness did not show the given row: $shown")

    fun check(where: String, row: JsonObject?, reread: Boolean?, removed: Boolean? = null) {
        if (removed != null && removed != (h.observed() == null)) failures += "$where: removed: expected $removed, got ${h.observed() == null}"
        row?.let { failures += activityRowMismatches(where, it, h.observed()) }
        if (reread != null && reread != h.inbox.rereadAsked) {
            failures += "$where: reread: expected $reread, got ${h.inbox.rereadAsked}"
        }
    }

    case.steps.forEachIndexed { index, step ->
        when (step) {
            is ActivityRowStep.LiveMessage -> {
                val seq = step.seq.toLongOrNull() ?: return listOf("step ${index + 1}: seq ${step.seq} is not a 64-bit seq")
                h.store.dispatch(step.toIntent(seq))
            }
            is ActivityRowStep.SyncDelete -> {
                val reason = if (step.reason == "left") RaftSyncInboxRowDeleteReason.Left else RaftSyncInboxRowDeleteReason.Done
                val delete = RaftSyncChange.InboxRowDelete(index + 1L, PositionChannel, step.position, reason)
                val outcome = h.store.applySyncInboxRows(ServerIdValue, listOf(delete))
                if (outcome !is RaftSyncInboxRowsOutcome.Applied) return listOf("step ${index + 1}: the store did not take the delete ($outcome)")
            }
            is ActivityRowStep.Answer ->
                if (case.rule == "sync_inbox_row") {
                    // An `inbox_row` upsert of the row (the same merge as an answer).
                    val upsert = RaftSyncChange.InboxRowUpsert(index + 1L, PositionChannel, step.row.toWireRow(), RaftSyncInboxRowSource.Log)
                    val outcome = h.store.applySyncInboxRows(ServerIdValue, listOf(upsert))
                    if (outcome !is RaftSyncInboxRowsOutcome.Applied) return listOf("step ${index + 1}: the store did not take the upsert ($outcome)")
                } else {
                    // The answer to a re-read already in flight, or else a list read the harness asks for.
                    if (h.inbox.pending == null) {
                        h.inbox.harnessAsking = true
                        h.store.dispatch(SharedActivityIntent.Refresh)
                        runCurrent()
                    }
                    val pending = h.inbox.pending ?: return listOf("step ${index + 1}: the store started no read to answer")
                    pending.complete(snapshot(listOf(step.row.toChannelRow()), serverId = ServerIdValue))
                }
            is ActivityRowStep.Read -> {
                val seq = step.seq.toLongOrNull() ?: return listOf("step ${index + 1}: seq ${step.seq} is not a 64-bit seq")
                h.store.dispatch(
                    SharedActivityIntent.ApplyRealtimeEvent(
                        PerReceiverReadStateChanged(
                            serverId = ServerId(ServerIdValue),
                            scopes =
                                listOf(
                                    PerReceiverReadStateScope(
                                        ChannelId(PositionChannel),
                                        maxReadSeq = seq,
                                        readStateVersion = h.nextReadStateVersion(),
                                    )
                                ),
                        )
                    )
                )
            }
            // Listed in AppActivityRowKnownGaps: a read after it would be a newer read state here, not the
            // case's stale ack, so the harness does not drive it.
            ActivityRowStep.MarkUnread -> return listOf("step ${index + 1}: markUnread is not driven by the store harness")
        }
        settle()
        case.checkpoints.filter { it.afterStep == index + 1 }.forEach { checkpoint ->
            check("after step ${index + 1}", checkpoint.row, checkpoint.reread, checkpoint.removed)
        }
    }
    check("at the end", case.expect!!.row, case.expect.reread, case.expect.removed)
    return failures
}

private fun ActivityRowServed.toExpectation() =
    JsonObject(
        mapOf(
            "unreadCount" to JsonPrimitive(unreadCount),
            "hasMention" to JsonPrimitive(hasMention),
            "position" to JsonPrimitive(position),
        )
    )

// ---- predicate -----------------------------------------------------------------------------------

private const val PredicateRowSeq = 10L

/** The row a predicate case's message lands on, read and unmentioned, with a position to order by. */
private fun ActivityRowScope.toRow(): InboxItem =
    when (rowKind) {
        ActivityRowKind.Channel -> activityItem("channel-1")
        ActivityRowKind.Dm -> activityItem("dm-1").copy(key = "dm:dm-1", kind = InboxItemKind.DirectMessage)
        ActivityRowKind.Thread -> threadItem("thread-1").copy(isFollowing = threadFollowed ?: true)
        ActivityRowKind.Task ->
            threadItem("thread-1").copy(
                isFollowing = threadFollowed ?: true,
                taskConversation = InboxTaskConversation(taskId = TaskId("task-1"), taskTitle = "Task"),
            )
    }.copy(latestActivitySeq = PredicateRowSeq.toString(), readStateLatestActivitySeq = PredicateRowSeq.toString())

private val InboxItem.streamChannelId: String get() = (threadChannelId ?: channelId).value

private fun ActivityRowMessage.toMessageNew(streamChannelId: String): MessageNew {
    val kind =
        when (senderType) {
            "agent" -> MemberKind.Agent
            "system" -> MemberKind.System
            else -> MemberKind.Human
        }
    val base = message("live-1", channelId = streamChannelId, authorId = senderId, seq = PredicateRowSeq + 1)
    val live =
        base.copy(
            messageType = if (messageType == "system") MessageType.System else MessageType.Chat,
            author = base.author.copy(id = MemberId(senderId), kind = kind),
            mentions = mentions.map { MessageMention(type = it.type, id = it.id) },
            countsUnread = countsUnread != false,
            quietForPeople = quietForPeople == true,
        )
    // Absent `systemSubtype`: the frame carried no provenance (README "Predicate inputs").
    val provenance =
        if (carriesSystemProvenance) {
            MessageSystemProvenance(
                systemSubtype = systemSubtype,
                causalActorType = causalActorType,
                causalActorId = causalActorId,
                countsUnread = countsUnread,
                quietForPeople = quietForPeople,
            )
        } else {
            null
        }
    return MessageNew(live, systemProvenance = provenance)
}

private fun predicateDriver() =
    ActivityRowCaseDriver(
        name = "predicate",
        decline = { case ->
            when {
                case.rule != "predicate" -> "not a predicate case"
                case.given.viewer == null || case.given.scope == null || case.given.message == null ->
                    "no given viewer/scope/message"
                case.given.viewer?.type != "user" -> "the app's Activity reader is a user, not '${case.given.viewer?.type}'"
                case.steps.isNotEmpty() -> "a predicate case has no steps"
                case.expect?.verdict == null -> "no expected verdict"
                else -> null
            }
        },
        run = { case -> runPredicateCase(case) },
    )

/**
 * The verdict as the app's live patch reaches it: the message arrives on a read row the list shows
 * and the store judges it in place (SharedActivityStore `InPlaceUnreadContext.unreadFor`). `counts`
 * is the unread it added; a message the store does not apply in place and re-reads the row for is
 * `counts: null` (cannot judge here). `mention` is the @ it lit. `moves`: the row did not stay where it
 * was past the mute or on a quiet task line (a message it re-reads is judged on the answer, not here).
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.runPredicateCase(case: ActivityRowContractCase): List<String> {
    val viewer = case.given.viewer!!
    val scope = case.given.scope!!
    // The harness signs in as ViewerId; the case's ids are mapped onto it.
    val message =
        case.given.message!!.let { m ->
            m.copy(
                senderId = if (m.senderId == viewer.id) ViewerId else m.senderId,
                causalActorId = if (m.causalActorId == viewer.id) ViewerId else m.causalActorId,
                mentions = m.mentions.map { if (it.id == viewer.id) it.copy(id = ViewerId) else it },
            )
        }
    val row = scope.toRow()
    val h = loadedHarness(row)
    val before = h.row() ?: return listOf("the harness did not show the row")
    if (before.unreadCount != 0 || before.hasMention) return listOf("the harness row is not read: $before")

    h.store.dispatch(
        SharedActivityIntent.ApplyRealtimeEvent(
            message.toMessageNew(row.streamChannelId),
            allowLocalPatch = scope.activityMuted != true,
            activeConversationId = if (scope.readOnArrival == true) row.streamChannelId else null,
            activityMuted = scope.activityMuted == true,
        )
    )
    settle()

    val after = h.row() ?: return listOf("the row left the list")
    val applied = after.latestActivitySeq == (PredicateRowSeq + 1).toString()
    // The row's own re-read: the store could not judge it in place. (A list read that follows a
    // message the position rule does not take, the reader's own with mentions, judges nothing here.)
    val rowReread = h.inbox.rowRereadAsked
    // A message past the mute on a row with a pre-mute message, or a quiet line on a person's row
    // (step 2c, raft-design #1440; every row kind since #proj-raft-ui:#319), is judged without moving the row.
    val stayedUnmoved =
        !applied && !rowReread && !h.inbox.rereadAsked &&
            (scope.activityMuted == true || message.quietForPeople == true)
    val counts: Boolean? =
        when {
            (applied || stayedUnmoved) && !rowReread -> after.unreadCount > 0
            !applied && rowReread -> null
            else ->
                return listOf(
                    "no verdict: applied=$applied rowReread=$rowReread reread=${h.inbox.rereadAsked} " +
                        "(row seq ${after.latestActivitySeq}, unread ${after.unreadCount})"
                )
        }
    val expected = case.expect!!.verdict!!
    val actual = ActivityRowVerdict(counts = counts, mention = after.hasMention, moves = (!stayedUnmoved).takeIf { expected.moves != null })
    return if (actual == expected) emptyList() else listOf("verdict: expected $expected, got $actual")
}
