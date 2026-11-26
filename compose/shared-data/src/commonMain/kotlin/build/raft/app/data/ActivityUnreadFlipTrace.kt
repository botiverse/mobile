package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.InboxItemKind
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `slock.activity.unread_state_flipped` (#proj-o11y:#53 part 2, web parity with botiverse/slock
 * #16951 `utils/activityUnreadTrace.ts` + `store/inboxUnreadFlipTrace.ts`).
 *
 * Emitted once per listed conversation row (channel, DM, thread) whose unread state
 * (`unreadCount > 0`) differs between two store states, capped at [ActivityUnreadMaxFlipEventsPerChange]
 * events per change. Rows entering or leaving the list, non-conversation rows (mentions, system,
 * objects), filter/server switches, and the person's own Mark as Read / Unread / Read all in
 * Activity ([ActivityUnreadFlipSource.UserAction]) are not reported.
 *
 * Read-only tracing: never mutates the store and never throws.
 * No IDs, names, or raw seqs are included — closed-set enums and bounded whole numbers only.
 */
@RaftCoreInternalApi const val RaftActivityUnreadStateFlippedTraceName = "slock.activity.unread_state_flipped"

/** Unread counts are clamped at this ceiling (web `UNREAD_COUNT_CAP = 100`). */
@RaftCoreInternalApi const val ActivityUnreadFlipCountCap = 100

/** Maximum flip events emitted for a single store change (web `MAX_FLIP_EVENTS_PER_CHANGE = 20`). */
@RaftCoreInternalApi const val ActivityUnreadMaxFlipEventsPerChange = 20

/** Ceiling for `flips_in_change` (web `whole(input.flipsInChange, 10_000)`). */
@RaftCoreInternalApi const val ActivityUnreadFlipsInChangeCap = 10_000

/** Maximum remembered opened rows per store instance (web `MAX_OPENS = 200`). */
@RaftCoreInternalApi const val ActivityUnreadFlipMaxOpens = 200

@RaftCoreInternalApi enum class ActivityUnreadFlipDirection(val wireValue: String) {
    ToUnread("to_unread"),
    ToRead("to_read"),
}

@RaftCoreInternalApi enum class ActivityUnreadFlipSource(val wireValue: String) {
    InPlacePatch("in_place_patch"),
    BackgroundReread("background_reread"),
    ListRefresh("list_refresh"),
    RealtimeEvent("realtime_event"),
    LocalRead("local_read"),
    OpenRead("open_read"),
    Unknown("unknown"),

    /** Person's own Mark as Read / Mark as Unread / Read all in Activity; never emitted. */
    UserAction("user_action"),
}

@RaftCoreInternalApi enum class ActivityUnreadFlipCause(val wireValue: String) {
    OpenReadReverted("open_read_reverted"),
    NewerActivity("newer_activity"),
    None("none"),
}

@RaftCoreInternalApi enum class ActivityUnreadFlipRowKind(val wireValue: String) {
    Channel("channel"),
    Dm("dm"),
    Thread("thread"),
}

@RaftCoreInternalApi enum class ActivityUnreadSeqChange(val wireValue: String) {
    Latest("latest"),
    Marker("marker"),
    Both("both"),
    None("none"),
}

/** One `slock.activity.unread_state_flipped` event ready for [RaftClientTraceEvents]. */
@RaftCoreInternalApi data class ActivityUnreadStateFlipped(
    val direction: ActivityUnreadFlipDirection,
    val source: ActivityUnreadFlipSource,
    val cause: ActivityUnreadFlipCause,
    val rowKind: ActivityUnreadFlipRowKind,
    /** Whole ms since the row was last opened from Activity; null (omitted on wire) if never opened here. */
    val msSinceOpen: Long?,
    val seqChanged: ActivityUnreadSeqChange,
    val unreadBefore: Int,
    val unreadAfter: Int,
    val flipsInChange: Int,
) {
    fun toTraceAttrs(): JsonObject =
        JsonObject(
            buildMap {
                put("direction", JsonPrimitive(direction.wireValue))
                put("source", JsonPrimitive(source.wireValue))
                put("cause", JsonPrimitive(cause.wireValue))
                put("row_kind", JsonPrimitive(rowKind.wireValue))
                msSinceOpen?.let { put("ms_since_open", JsonPrimitive(it.coerceAtLeast(0L))) }
                put("seq_changed", JsonPrimitive(seqChanged.wireValue))
                put("unread_before", JsonPrimitive(unreadBefore.coerceIn(0, ActivityUnreadFlipCountCap)))
                put("unread_after", JsonPrimitive(unreadAfter.coerceIn(0, ActivityUnreadFlipCountCap)))
                put("flips_in_change", JsonPrimitive(flipsInChange.coerceIn(0, ActivityUnreadFlipsInChangeCap)))
            },
        )
}

/**
 * Per-store read-only detector for `slock.activity.unread_state_flipped` (web
 * `store/inboxUnreadFlipTrace.ts`). Remembers recently opened conversation rows (LRU bounded at
 * [ActivityUnreadFlipMaxOpens]) so a later flip back to unread with no newer activity is attributed
 * to [ActivityUnreadFlipCause.OpenReadReverted] with elapsed `ms_since_open`.
 */
@RaftCoreInternalApi class ActivityUnreadFlipTrace(
    private val nowMillis: () -> Long,
) {
    private data class OpenedRow(
        val atMs: Long,
        val latestActivitySeq: String?,
    )

    private val lock = SynchronizedObject()
    private val opens = LinkedHashMap<String, OpenedRow>()

    /** Records that the person opened [item] from Activity's list. */
    fun noteOpened(item: InboxItem) {
        if (item.unreadFlipRowKindOrNull() == null) return
        val now = nowMillis()
        synchronized(lock) {
            opens.remove(item.key)
            opens[item.key] = OpenedRow(atMs = now, latestActivitySeq = item.latestActivitySeq)
            while (opens.size > ActivityUnreadFlipMaxOpens) {
                val oldestKey = opens.keys.firstOrNull() ?: break
                opens.remove(oldestKey)
            }
        }
    }

    /** Clears remembered opens (e.g. server switch, account change, store clear). */
    fun reset() {
        synchronized(lock) {
            opens.clear()
        }
    }

    /**
     * Compares [previous] and [next] row by row and returns the flip events for [source]
     * (at most [ActivityUnreadMaxFlipEventsPerChange], each carrying the total flip count).
     */
    fun detectFlips(
        previous: List<InboxItem>,
        next: List<InboxItem>,
        source: ActivityUnreadFlipSource,
    ): List<ActivityUnreadStateFlipped> {
        if (previous === next || source == ActivityUnreadFlipSource.UserAction) return emptyList()
        if (previous.isEmpty() || next.isEmpty()) return emptyList()

        val beforeByKey = HashMap<String, InboxItem>(previous.size)
        for (item in previous) {
            if (item.unreadFlipRowKindOrNull() != null) {
                beforeByKey[item.key] = item
            }
        }
        if (beforeByKey.isEmpty()) return emptyList()

        val flips = mutableListOf<Pair<InboxItem, InboxItem>>()
        for (row in next) {
            if (row.unreadFlipRowKindOrNull() == null) continue
            val old = beforeByKey[row.key] ?: continue
            if ((old.unreadCount > 0) != (row.unreadCount > 0)) {
                flips += old to row
            }
        }
        if (flips.isEmpty()) return emptyList()

        val now = nowMillis()
        val totalFlips = flips.size
        val reportedCount = minOf(totalFlips, ActivityUnreadMaxFlipEventsPerChange)
        return buildList(reportedCount) {
            for (index in 0 until reportedCount) {
                val (old, row) = flips[index]
                val rowKind = row.unreadFlipRowKindOrNull() ?: continue
                val open = synchronized(lock) { opens[row.key] }
                val newer = compareActivitySeq(row.latestActivitySeq, old.latestActivitySeq) > 0
                val toUnread = row.unreadCount > 0
                val cause =
                    when {
                        toUnread && open != null && compareActivitySeq(row.latestActivitySeq, open.latestActivitySeq) <= 0 ->
                            ActivityUnreadFlipCause.OpenReadReverted
                        newer -> ActivityUnreadFlipCause.NewerActivity
                        else -> ActivityUnreadFlipCause.None
                    }
                add(
                    ActivityUnreadStateFlipped(
                        direction = if (toUnread) ActivityUnreadFlipDirection.ToUnread else ActivityUnreadFlipDirection.ToRead,
                        source = source,
                        cause = cause,
                        rowKind = rowKind,
                        msSinceOpen = open?.let { (now - it.atMs).coerceAtLeast(0L) },
                        seqChanged = activityUnreadSeqChange(old, row),
                        unreadBefore = old.unreadCount.coerceIn(0, ActivityUnreadFlipCountCap),
                        unreadAfter = row.unreadCount.coerceIn(0, ActivityUnreadFlipCountCap),
                        flipsInChange = totalFlips.coerceIn(0, ActivityUnreadFlipsInChangeCap),
                    ),
                )
            }
        }
    }

    /**
     * Observes a committed [SharedActivityStore] transition and emits any flips to [sink].
     * Never throws so tracing cannot affect the Activity list.
     */
    fun observeTransition(
        previous: SharedActivityState,
        next: SharedActivityState,
        source: ActivityUnreadFlipSource,
        sink: (serverId: String, event: ActivityUnreadStateFlipped) -> Unit,
    ) {
        try {
            if (previous.ownerServerId != next.ownerServerId || previous.ownerUserId != next.ownerUserId) {
                reset()
                return
            }
            if (previous.selectedFilter != next.selectedFilter) return
            val serverId = next.ownerServerId?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val previousItems = previous.snapshot?.items ?: return
            val nextItems = next.snapshot?.items ?: return
            val events = detectFlips(previousItems, nextItems, source)
            for (event in events) {
                sink(serverId, event)
            }
        } catch (_: Throwable) {
            // Tracing never affects the list it describes.
        }
    }
}

private fun InboxItem.unreadFlipRowKindOrNull(): ActivityUnreadFlipRowKind? =
    when (kind) {
        InboxItemKind.Channel -> ActivityUnreadFlipRowKind.Channel
        InboxItemKind.DirectMessage -> ActivityUnreadFlipRowKind.Dm
        InboxItemKind.Thread -> ActivityUnreadFlipRowKind.Thread
        InboxItemKind.MentionAction,
        InboxItemKind.System,
        InboxItemKind.Object -> null
    }

private fun activityUnreadSeqChange(before: InboxItem, after: InboxItem): ActivityUnreadSeqChange {
    val latest = before.latestActivitySeq != after.latestActivitySeq
    val marker = before.readStateLatestActivitySeq != after.readStateLatestActivitySeq
    return when {
        latest && marker -> ActivityUnreadSeqChange.Both
        latest -> ActivityUnreadSeqChange.Latest
        marker -> ActivityUnreadSeqChange.Marker
        else -> ActivityUnreadSeqChange.None
    }
}
