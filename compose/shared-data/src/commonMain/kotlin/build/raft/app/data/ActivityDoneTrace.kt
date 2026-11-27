package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.InboxItemKind
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mobile parity with `botiverse/slock` `packages/web/src/utils/activityDoneTrace.ts` (#16943,
 * #17177, `#proj-o11y:#53`): traces what happens to an Activity conversation row after the person
 * marks it Done, using identical event names and attributes so web and mobile can be queried
 * together under `otel.logs` (differentiated by `clientPlatform`).
 *
 * Closed sets, booleans and numbers only; no ids, names or seqs.
 */
@RaftCoreInternalApi const val ActivityItemReappearedTraceName = "slock.activity.item_reappeared"
@RaftCoreInternalApi const val ActivityDoneCommittedTraceName = "slock.activity.done_committed"
@RaftCoreInternalApi const val ActivityDoneReappearMaxCommitted = 200

@RaftCoreInternalApi enum class ActivityReappearCause(val wireValue: String) {
    SuppressionMarkerNewer("suppression_marker_newer"),
    MarkerNull("marker_null"),
    DoneFailed("done_failed"),
    Undo("undo"),
    AfterCommitNewMessage("after_commit_new_message"),
    AfterCommitOtherDevice("after_commit_other_device"),
    AfterCommitSyncReread("after_commit_sync_reread"),
    Unknown("unknown"),
}

@RaftCoreInternalApi enum class ActivityDoneRowChange {
    ListRead,
    UserAction,
    Other,
}

@RaftCoreInternalApi fun ActivityUnreadFlipSource.toDoneRowChange(): ActivityDoneRowChange =
    when (this) {
        ActivityUnreadFlipSource.BackgroundReread,
        ActivityUnreadFlipSource.ListRefresh -> ActivityDoneRowChange.ListRead
        ActivityUnreadFlipSource.UserAction -> ActivityDoneRowChange.UserAction
        else -> ActivityDoneRowChange.Other
    }

@RaftCoreInternalApi enum class ActivityDoneCommitTrigger(val wireValue: String) {
    ToastClosed("toast_closed"),
    NextRow("next_row"),
    PageHidden("page_hidden"),
    NoToast("no_toast"),
    FrontierCatchUp("frontier_catch_up"),
}

@RaftCoreInternalApi enum class ActivitySeqChange(val wireValue: String) {
    Latest("latest"),
    Frontier("frontier"),
    Both("both"),
    None("none"),
}

@RaftCoreInternalApi enum class ActivityDoneTraceRowKind(val wireValue: String) {
    Channel("channel"),
    Dm("dm"),
    Thread("thread"),
}

@RaftCoreInternalApi fun InboxItem.activityDoneTraceRowKind(): ActivityDoneTraceRowKind? =
    when (kind) {
        InboxItemKind.Channel -> ActivityDoneTraceRowKind.Channel
        InboxItemKind.DirectMessage -> ActivityDoneTraceRowKind.Dm
        InboxItemKind.Thread -> ActivityDoneTraceRowKind.Thread
        InboxItemKind.MentionAction,
        InboxItemKind.Object,
        InboxItemKind.System -> null
    }

@RaftCoreInternalApi sealed interface ActivityDoneTraceEvent {
    val traceName: String
    fun toTraceAttrs(): JsonObject

    @RaftCoreInternalApi data class ItemReappeared(
        val cause: ActivityReappearCause,
        val rowKind: ActivityDoneTraceRowKind,
        val wasUnreadAtClick: Boolean,
        val msSinceClick: Long,
        val seqChanged: ActivitySeqChange,
    ) : ActivityDoneTraceEvent {
        override val traceName: String = ActivityItemReappearedTraceName

        override fun toTraceAttrs(): JsonObject =
            buildJsonObject {
                put("cause", cause.wireValue)
                put("row_kind", rowKind.wireValue)
                put("was_unread_at_click", wasUnreadAtClick)
                put("ms_since_click", msSinceClick.coerceAtLeast(0L))
                put("seq_changed", seqChanged.wireValue)
            }
    }

    @RaftCoreInternalApi data class DoneCommitted(
        val trigger: ActivityDoneCommitTrigger,
        val rowKind: ActivityDoneTraceRowKind,
        val wasUnreadAtClick: Boolean,
        val waitMs: Long,
        val frontierCaughtUp: Boolean,
    ) : ActivityDoneTraceEvent {
        override val traceName: String = ActivityDoneCommittedTraceName

        override fun toTraceAttrs(): JsonObject =
            buildJsonObject {
                put("trigger", trigger.wireValue)
                put("row_kind", rowKind.wireValue)
                put("was_unread_at_click", wasUnreadAtClick)
                put("wait_ms", waitMs.coerceAtLeast(0L))
                put("frontier_caught_up", frontierCaughtUp)
            }
    }
}

/**
 * Remembers conversation rows whose Done write succeeded with an armed hold, and reports once per
 * Done if the row enters the Activity list again (mirroring `botiverse/slock` `#17177`
 * `packages/web/src/store/inboxDoneRows.ts`).
 */
@RaftCoreInternalApi class ActivityDoneReappearTrace(
    private val nowMillis: () -> Long,
    private val maxCommitted: Int = ActivityDoneReappearMaxCommitted,
) {
    private data class CommittedDone(
        val latestActivitySeq: String?,
        val doneFrontierSeq: String?,
        val rowKind: ActivityDoneTraceRowKind,
        val wasUnreadAtClick: Boolean,
        val clickedAtMillis: Long,
        var otherDeviceCleared: Boolean = false,
    )

    private val lock = SynchronizedObject()
    private val committed = LinkedHashMap<String, CommittedDone>()

    fun noteCommitted(key: String, item: InboxItem, meta: ActivityLocalDoneMeta) {
        if (item.activityDoneTraceRowKind() == null) return
        synchronized(lock) {
            committed.remove(key)
            committed[key] =
                CommittedDone(
                    latestActivitySeq = item.latestActivitySeq?.takeIf(::isCanonicalActivitySeq),
                    doneFrontierSeq = item.doneFrontierSeq?.takeIf { it.isNotBlank() },
                    rowKind = meta.rowKind,
                    wasUnreadAtClick = meta.wasUnreadAtClick,
                    clickedAtMillis = meta.clickedAtMillis,
                    otherDeviceCleared = false,
                )
            while (committed.size > maxCommitted) {
                val oldestKey = committed.keys.firstOrNull() ?: break
                committed.remove(oldestKey)
            }
        }
    }

    fun forget(key: String) {
        synchronized(lock) {
            committed.remove(key)
        }
    }

    fun noteDoneEventElsewhere(keys: Set<String>, done: Boolean) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            if (committed.isEmpty()) return
            for (key in keys) {
                committed[key]?.otherDeviceCleared = !done
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            committed.clear()
        }
    }

    fun detectReappeared(
        previous: List<InboxItem>,
        next: List<InboxItem>,
        change: ActivityDoneRowChange,
    ): List<ActivityDoneTraceEvent.ItemReappeared> {
        if (previous === next || next.isEmpty()) return emptyList()
        val now = nowMillis()
        return synchronized(lock) {
            if (committed.isEmpty()) return@synchronized emptyList()
            var beforeKeys: Set<String>? = null
            val events = mutableListOf<ActivityDoneTraceEvent.ItemReappeared>()
            for (row in next) {
                if (row.activityDoneTraceRowKind() == null) continue
                val done = committed[row.key] ?: continue
                val existingKeys =
                    beforeKeys ?: previous.mapTo(HashSet(previous.size)) { it.key }.also { beforeKeys = it }
                if (row.key in existingKeys) continue
                committed.remove(row.key)
                if (change == ActivityDoneRowChange.UserAction) continue
                val latestMoved = compareActivitySeq(row.latestActivitySeq, done.latestActivitySeq) > 0
                val rowFrontier = row.doneFrontierSeq?.takeIf { it.isNotBlank() }
                val frontierMoved = rowFrontier != done.doneFrontierSeq
                val cause =
                    when {
                        latestMoved -> ActivityReappearCause.AfterCommitNewMessage
                        done.otherDeviceCleared -> ActivityReappearCause.AfterCommitOtherDevice
                        change == ActivityDoneRowChange.ListRead -> ActivityReappearCause.AfterCommitSyncReread
                        else -> ActivityReappearCause.Unknown
                    }
                val seqChanged =
                    when {
                        latestMoved && frontierMoved -> ActivitySeqChange.Both
                        latestMoved -> ActivitySeqChange.Latest
                        frontierMoved -> ActivitySeqChange.Frontier
                        else -> ActivitySeqChange.None
                    }
                events +=
                    ActivityDoneTraceEvent.ItemReappeared(
                        cause = cause,
                        rowKind = done.rowKind,
                        wasUnreadAtClick = done.wasUnreadAtClick,
                        msSinceClick = (now - done.clickedAtMillis).coerceAtLeast(0L),
                        seqChanged = seqChanged,
                    )
            }
            events
        }
    }

    fun observeTransition(
        previous: SharedActivityState,
        next: SharedActivityState,
        source: ActivityUnreadFlipSource,
        sink: (serverId: String, event: ActivityDoneTraceEvent) -> Unit,
    ) {
        try {
            if (previous.ownerServerId != next.ownerServerId || previous.ownerUserId != next.ownerUserId) {
                reset()
                return
            }
            if (previous.selectedFilter != next.selectedFilter) return
            val nextSnapshot = next.snapshot ?: return
            val previousItems = previous.snapshot?.items.orEmpty()
            if (previous.snapshot != null && previousItems === nextSnapshot.items) return
            val serverId = next.ownerServerId?.trim()?.takeIf { it.isNotBlank() } ?: return
            val events = detectReappeared(previousItems, nextSnapshot.items, source.toDoneRowChange())
            for (event in events) {
                sink(serverId, event)
            }
        } catch (_: Throwable) {
            // Read-only telemetry never mutates store state or throws.
        }
    }
}
