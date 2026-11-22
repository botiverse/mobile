package build.raft.app.data

import build.raft.app.reportUnknownTypeDropped
import build.raft.app.strictProjectionDiagnosticsLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * `GET /api/sync/changes` (incremental sync), the wire contract of botiverse/slock
 * `packages/shared/src/syncChanges.ts` and `packages/server/src/routes/sync.ts` at 0d030e341
 * (PR #11459):
 *
 *   { cursor, hasMore, changes: SyncChange[], reset: null | { reason, snapshots } }
 *
 * - No `since`: the current cursor and no changes. Take the snapshots, then pull from it.
 * - `since=<cursor>`: apply `changes` in order, keep `cursor`; while `hasMore`, pull again at once.
 * - `reset`: take `reset.snapshots`, continue from the returned cursor (`changes` is empty).
 * - An unknown `entity` is skipped; a known entity with an unknown `op` means "resnapshot that
 *   entity". New entities and ops are added, never renamed.
 * - `task` (slock #14975) is known and not read: its changes are skipped and counted
 *   ([RaftSyncChangesPage.ignoredEntities]), not reported row by row as an unknown entity.
 *
 * The cursor is opaque: echo it back, never parse or compare it.
 *
 * `inbox_row` capability (botiverse/slock #15312, `SyncInboxRowChange` / `SyncInboxReset` in
 * packages/shared/src/syncChanges.ts; asked for with `capabilities=inbox_row` only while the server
 * flag `sync_inbox_rows_v0` is on, [SYNC_CAPABILITY_INBOX_ROW]): `inbox_row` changes carry each changed
 * Activity row as `GET /api/channels/inbox?ids=` answers it ([RaftSyncChange.InboxRowUpsert]) or its
 * removal ([RaftSyncChange.InboxRowDelete]); `inboxReset` asks for the inbox snapshot again while the
 * page's changes still apply; `retryAfterMs` asks for one more pull after that delay (a row is held
 * back until its read model catches up). Without the capability none of them is sent.
 */

/** A snapshot the client (re)takes through its existing list endpoint (`SYNC_SNAPSHOT_ENDPOINTS`). */
enum class RaftSyncSnapshot(val wireName: String) {
    /** `GET /api/channels` + `GET /api/channels/dm`. */
    Channels("channels"),

    /** `GET /api/channels/:id/members`. */
    ChannelMembers("channel_members"),

    /** `GET /api/servers/:id/members`. */
    ServerMembers("server_members"),

    /** `GET /api/agents`. */
    Agents("agents"),

    /** `GET /api/channels/inbox`. */
    Inbox("inbox"),

    /** `GET /api/channels/unread`. */
    Unread("unread"),

    /** `GET /api/channels/threads/followed`. */
    FollowedThreads("followed_threads"),

    /** `GET /api/servers/:id/machines` (viewers with `viewMachines` only). */
    Machines("machines"),

    /** `GET /api/servers/:id/sidebar-order`. */
    SidebarOrder("sidebar_order"),

    /** `GET /api/servers/order`. */
    ServerOrder("server_order");

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        /** Null for a snapshot name this client does not know (a newer server). */
        fun fromWire(name: String?): RaftSyncSnapshot? = name?.let(byWireName::get)

        /**
         * The snapshots a `reset` / scope change names. A name this client does not know (a newer
         * server) means retake every snapshot (contract, Tenny 10-27): skipping it would advance
         * the cursor past data the client never reloaded.
         */
        fun setFromWire(names: List<String?>): Set<RaftSyncSnapshot> {
            val known = LinkedHashSet<RaftSyncSnapshot>()
            names.forEach { name -> known += fromWire(name) ?: return All }
            return known
        }

        /** Every snapshot this client takes on a first sync. */
        val All: Set<RaftSyncSnapshot> = entries.toSet()
    }
}

/**
 * An entity whose upsert carries the row of its list endpoint (`SyncRowEntity`); [snapshot] is
 * the snapshot that list is, null for an entity without one ([Message]).
 */
enum class RaftSyncRowEntity(val wireName: String, val snapshot: RaftSyncSnapshot?) {
    /** A `GET /api/channels` item, or a `GET /api/channels/dm` item for a DM. */
    Channel("channel", RaftSyncSnapshot.Channels),

    /** id `<channelId>:user:<userId>` / `<channelId>:agent:<agentId>`; a `humans` / `agents` item. */
    ChannelMember("channel_member", RaftSyncSnapshot.ChannelMembers),

    /** id = user id; a `GET /api/servers/:id/members` item. */
    ServerMember("server_member", RaftSyncSnapshot.ServerMembers),

    /** id = agent id; a `GET /api/agents` item. */
    Agent("agent", RaftSyncSnapshot.Agents),

    /** id = machine id; an item of `machines` in `GET /api/servers/:id/machines`. */
    Machine("machine", RaftSyncSnapshot.Machines),

    /** id = server id; the `GET /api/servers/:id/sidebar-order` body. */
    SidebarOrder("sidebar_order", RaftSyncSnapshot.SidebarOrder),

    /** id = the viewer's user id; the `GET /api/servers/order` body. */
    ServerOrder("server_order", RaftSyncSnapshot.ServerOrder),

    /**
     * RFC 082 §4.4 (botiverse/slock 1e9266662, `SyncRowEntity` `message`): id = message id; the
     * message as `GET /api/messages/channel/:channelId` lists it. Sent when a message a client may
     * already hold changed in place (a card message's update, a Slack-bridged edit): replace that
     * message where it is held; one that is not held needs nothing. Upserts only, and no snapshot.
     */
    Message("message", null);

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        fun fromWire(name: String?): RaftSyncRowEntity? = name?.let(byWireName::get)
    }
}

/** What a `conversation` change (the viewer's own read / inbox / follow state) invalidates. */
val RaftSyncConversationSnapshots: Set<RaftSyncSnapshot> =
    setOf(RaftSyncSnapshot.Inbox, RaftSyncSnapshot.Unread, RaftSyncSnapshot.FollowedThreads)

enum class RaftSyncScopeKind { Channel, Server }

/** One change of a page, in the server's order. */
sealed interface RaftSyncChange {
    val seq: Long

    /** The entity's current row for this viewer, the same JSON its list endpoint returns. */
    data class Upsert(
        override val seq: Long,
        val entity: RaftSyncRowEntity,
        val id: String,
        val row: JsonObject,
    ) : RaftSyncChange

    /** No longer in this viewer's list (deleted, archived, no longer visible). */
    data class Delete(
        override val seq: Long,
        val entity: RaftSyncRowEntity,
        val id: String,
    ) : RaftSyncChange

    /**
     * The viewer's own state of a conversation changed (read position, inbox done, thread follow /
     * done): refresh it from the inbox, unread and followed-thread snapshots. [refSeq] is the read
     * position it moved to when the change was one.
     */
    data class ConversationStale(
        override val seq: Long,
        val conversationId: String,
        val refSeq: Long?,
        /** The state the change carries (null: none, or a part this client can't read). */
        val state: RaftSyncConversationState? = null,
    ) : RaftSyncChange

    /** The viewer gained a channel or a server: take [snapshots] for it. */
    data class ScopeAdded(
        override val seq: Long,
        val kind: RaftSyncScopeKind,
        val id: String,
        val snapshots: Set<RaftSyncSnapshot>,
    ) : RaftSyncChange

    /** The viewer lost a channel or a server: drop everything held under it. */
    data class ScopeRemoved(
        override val seq: Long,
        val kind: RaftSyncScopeKind,
        val id: String,
    ) : RaftSyncChange

    /**
     * `inbox_row` upsert: [row] is the Activity row as `GET /api/channels/inbox?ids=<id>` answers it,
     * applied through the same merge as that answer. [id] is the row's own id (a channel / DM row's
     * channelId, a thread / task row's threadChannelId).
     */
    data class InboxRowUpsert(
        override val seq: Long,
        override val id: String,
        val row: JsonObject,
        val source: RaftSyncInboxRowSource,
    ) : RaftSyncInboxRowChange

    /**
     * `inbox_row` delete: the row left the inbox. Applied when [position] is null or at or past the
     * position the held row shows; below it a newer message brought the row back and it stays.
     */
    data class InboxRowDelete(
        override val seq: Long,
        override val id: String,
        val position: String?,
        val reason: RaftSyncInboxRowDeleteReason,
    ) : RaftSyncInboxRowChange

    /** A known entity with an op this client does not know: retake that entity's snapshots. */
    data class Resnapshot(
        override val seq: Long,
        val entity: String,
        val op: String,
        val snapshots: Set<RaftSyncSnapshot>,
    ) : RaftSyncChange
}

/**
 * The viewer's current state of one conversation, as a `conversation` change carries it
 * (botiverse/slock `SyncConversationChange` in packages/shared/src/syncChanges.ts; web
 * `readSyncConversationState`): one part per kind that changed in the page, each read when the
 * server built the page. Additive: an older server sends none.
 */
data class RaftSyncConversationState(
    val read: Read? = null,
    val done: Done? = null,
    val follow: Follow? = null,
) {
    /** `SyncConversationReadState`. */
    data class Read(val readSeq: Long, val readStateVersion: Long, val markedUnread: Boolean)

    /**
     * `SyncConversationDoneState`: [doneFrontierSeq] (decimal string, or null) is the
     * conversation's latest message seq in its storage channel. Compare it with an Activity row's
     * `latestActivitySeq`, never the row's own `doneFrontierSeq`.
     */
    data class Done(val done: Boolean, val doneFrontierSeq: String?)

    /** `SyncConversationFollowState`. */
    data class Follow(val followed: Boolean, val threadDone: Boolean)
}

/** An `inbox_row` change ([RaftSyncChange.InboxRowUpsert] / [RaftSyncChange.InboxRowDelete]); [id] is the row's id. */
sealed interface RaftSyncInboxRowChange : RaftSyncChange {
    val id: String
}

/** The capability name asking for `inbox_row` changes (slock `SYNC_CAPABILITY_INBOX_ROW`). */
const val SYNC_CAPABILITY_INBOX_ROW: String = "inbox_row"

/** Why an `inbox_row` upsert was rebuilt (informational: every source applies the same way). */
enum class RaftSyncInboxRowSource {
    /** A change_log change (a read, Done, follow, leave). */
    Log,

    /** A new message reached the row. */
    Message,

    /** A source this client does not know (reported; the row still applies). */
    Unknown,
}

/** Why an `inbox_row` delete removed the row. */
enum class RaftSyncInboxRowDeleteReason {
    /** Marked Done; `position` is the conversation's latest seq when it was. */
    Done,

    /** The viewer lost the conversation; `position` is null. */
    Left,
}

/**
 * `inboxReset` (inbox_row capability): retake the inbox snapshot; the page's changes still apply and
 * the cursor goes on. [reason]: `init` | `too_many` | `held_too_long` (kept as sent).
 */
data class RaftSyncInboxReset(val reason: String)

/** `reset` set: drop the cursor's state and take [snapshots]. */
data class RaftSyncReset(
    /** `too_old` | `too_many` | `forced` (kept as sent so a newer reason still parses). */
    val reason: String,
    val snapshots: Set<RaftSyncSnapshot>,
)

/** One parsed `GET /api/sync/changes` response. */
data class RaftSyncChangesPage(
    val cursor: String,
    val hasMore: Boolean,
    val changes: List<RaftSyncChange>,
    val reset: RaftSyncReset?,
    /** Changes of an entity this client does not know (skipped, per the contract). */
    val skippedUnknownEntities: Int = 0,
    /** Changes of an entity this client knows and does not read (`task`), per entity: skipped and counted. */
    val ignoredEntities: Map<String, Int> = emptyMap(),
    /** inbox_row capability: retake the inbox snapshot (the changes still apply). */
    val inboxReset: RaftSyncInboxReset? = null,
    /** inbox_row capability: pull again after about this many milliseconds (a row is held back). */
    val retryAfterMillis: Long? = null,
)

@Serializable
private data class SyncChangesResponseWire(
    val cursor: String,
    val hasMore: Boolean,
    val changes: List<JsonObject> = emptyList(),
    val reset: SyncResetWire? = null,
    val inboxReset: SyncInboxResetWire? = null,
    val retryAfterMs: Double? = null,
)

@Serializable
private data class SyncInboxResetWire(
    val reason: String,
    val snapshots: List<String> = emptyList(),
)

@Serializable
private data class SyncResetWire(
    val reason: String,
    val snapshots: List<String> = emptyList(),
)

private val syncChangesJson = Json {
    ignoreUnknownKeys = true
}

/**
 * Parses a `GET /api/sync/changes` body; null on a contract mismatch (no cursor, no `hasMore`, a
 * change without `seq` / `entity` / `id` / `op`, or a row-entity upsert without a `row` object).
 * [diagnostics] gets the `projection_value_unknown` lines of the values this client does not know.
 */
fun parseSyncChangesPage(
    bodyText: String,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): RaftSyncChangesPage? {
    val wire =
        try {
            syncChangesJson.decodeFromString(SyncChangesResponseWire.serializer(), bodyText)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    val cursor = wire.cursor.takeIf { it.isNotBlank() } ?: return null
    var skipped = 0
    val ignored = LinkedHashMap<String, Int>()
    val changes = ArrayList<RaftSyncChange>(wire.changes.size)
    for (element in wire.changes) {
        when (val parsed = parseSyncChange(element, diagnostics)) {
            ParsedSyncChange.Malformed -> return null
            ParsedSyncChange.UnknownEntity -> skipped += 1
            is ParsedSyncChange.IgnoredEntity -> ignored[parsed.entity] = (ignored[parsed.entity] ?: 0) + 1
            is ParsedSyncChange.Known -> changes += parsed.change
        }
    }
    return RaftSyncChangesPage(
        cursor = cursor,
        hasMore = wire.hasMore,
        changes = changes,
        reset = wire.reset?.let { reset ->
            RaftSyncReset(
                reason = reset.reason,
                snapshots = RaftSyncSnapshot.setFromWire(reset.snapshots),
            )
        },
        skippedUnknownEntities = skipped,
        ignoredEntities = ignored,
        inboxReset = wire.inboxReset?.let { reset ->
            if (reset.reason !in SyncInboxResetReasons) {
                // Still retaken: a reset of a kind this client does not know is a reset all the same.
                reportUnknownTypeDropped(
                    "sync_changes", "inbox_reset_reason", reset.reason, fallback = "inbox_snapshot", diagnostics = diagnostics,
                )
            }
            RaftSyncInboxReset(reset.reason)
        },
        retryAfterMillis = wire.retryAfterMs?.takeIf { it.isFinite() && it >= 0.0 }?.toLong(),
    )
}

private val SyncInboxResetReasons = setOf("init", "too_many", "held_too_long")

private sealed interface ParsedSyncChange {
    data class Known(val change: RaftSyncChange) : ParsedSyncChange

    /** A known entity this client does not read: skipped, counted per entity, not reported. */
    data class IgnoredEntity(val entity: String) : ParsedSyncChange

    data object UnknownEntity : ParsedSyncChange

    data object Malformed : ParsedSyncChange
}

private fun parseSyncChange(element: JsonObject, diagnostics: (String) -> Unit): ParsedSyncChange {
    val seq = element.longField("seq") ?: return ParsedSyncChange.Malformed
    val entity = element.stringField("entity") ?: return ParsedSyncChange.Malformed
    val id = element.stringField("id") ?: return ParsedSyncChange.Malformed
    val op = element.stringField("op") ?: return ParsedSyncChange.Malformed
    RaftSyncRowEntity.fromWire(entity)?.let { rowEntity ->
        return ParsedSyncChange.Known(
            when (op) {
                "upsert" -> {
                    val row = element["row"] as? JsonObject ?: return ParsedSyncChange.Malformed
                    RaftSyncChange.Upsert(seq, rowEntity, id, row)
                }
                "delete" -> RaftSyncChange.Delete(seq, rowEntity, id)
                // `message` has no snapshot: an op this client doesn't know retakes nothing (the open
                // conversation reads its messages again when it is entered).
                else -> RaftSyncChange.Resnapshot(seq, entity, op, setOfNotNull(rowEntity.snapshot))
            }
        )
    }
    return when (entity) {
        "conversation" ->
            ParsedSyncChange.Known(
                if (op == "stale") {
                    RaftSyncChange.ConversationStale(seq, id, element.longField("refSeq"), parseConversationState(element))
                } else {
                    RaftSyncChange.Resnapshot(seq, entity, op, RaftSyncConversationSnapshots)
                }
            )
        "scope" -> ParsedSyncChange.Known(parseScopeChange(element, seq, id, op))
        "inbox_row" -> parseInboxRowChange(element, seq, id, op, diagnostics)
        // slock #14975 sends every client its task rows (an upsert with the GET /api/tasks/channel/:id
        // row, a delete naming its `channelId`, a `resnapshot` of one channel's list). Not read here:
        // task lists follow the realtime `task:*` pushes plus the RaftLiveCatchUpEffect re-reads
        // (foreground, reconnect), as on web (slock packages/web/src/store/syncChangesClient.ts skips
        // them too). Known, so counted for the pull's log line (`ignored=task:N`), not reported per row.
        "task" -> ParsedSyncChange.IgnoredEntity(entity)
        else -> {
            // Skipped on purpose (the log moves on), but never silently.
            reportUnknownTypeDropped("sync_changes", "entity", entity, diagnostics = diagnostics)
            ParsedSyncChange.UnknownEntity
        }
    }
}

/**
 * An `inbox_row` change. An op or delete reason this client does not know is reported and retakes the
 * inbox snapshot ([RaftSyncChange.Resnapshot]); an unknown upsert source is reported and the row still
 * applies (the source does not change how). An upsert without a `row` object, or a delete whose
 * `position` is neither a string nor null, is a contract mismatch.
 */
private fun parseInboxRowChange(
    element: JsonObject,
    seq: Long,
    id: String,
    op: String,
    diagnostics: (String) -> Unit,
): ParsedSyncChange {
    val inboxOnly = setOf(RaftSyncSnapshot.Inbox)
    return when (op) {
        "upsert" -> {
            val row = element["row"] as? JsonObject ?: return ParsedSyncChange.Malformed
            val source =
                when (val raw = element.stringField("source")) {
                    "log" -> RaftSyncInboxRowSource.Log
                    "message" -> RaftSyncInboxRowSource.Message
                    else -> {
                        reportUnknownTypeDropped("sync_changes", "inbox_row_source", raw, fallback = "applied", diagnostics = diagnostics)
                        RaftSyncInboxRowSource.Unknown
                    }
                }
            ParsedSyncChange.Known(RaftSyncChange.InboxRowUpsert(seq, id, row, source))
        }
        "delete" -> {
            val position =
                when (val value = element["position"]) {
                    null, JsonNull -> null
                    else -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ParsedSyncChange.Malformed
                }
            val reason =
                when (val raw = element.stringField("reason")) {
                    "done" -> RaftSyncInboxRowDeleteReason.Done
                    "left" -> RaftSyncInboxRowDeleteReason.Left
                    else -> {
                        reportUnknownTypeDropped(
                            "sync_changes", "inbox_row_delete_reason", raw, fallback = "inbox_snapshot", diagnostics = diagnostics,
                        )
                        return ParsedSyncChange.Known(RaftSyncChange.Resnapshot(seq, "inbox_row", op, inboxOnly))
                    }
                }
            ParsedSyncChange.Known(RaftSyncChange.InboxRowDelete(seq, id, position, reason))
        }
        else -> {
            reportUnknownTypeDropped("sync_changes", "inbox_row_op", op, fallback = "inbox_snapshot", diagnostics = diagnostics)
            ParsedSyncChange.Known(RaftSyncChange.Resnapshot(seq, "inbox_row", op, inboxOnly))
        }
    }
}

private fun parseScopeChange(element: JsonObject, seq: Long, id: String, op: String): RaftSyncChange {
    val kind =
        when (element.stringField("scopeKind")) {
            "channel" -> RaftSyncScopeKind.Channel
            "server" -> RaftSyncScopeKind.Server
            else -> null
        }
    val snapshots =
        (element["snapshots"] as? JsonArray)
            ?.let { names -> RaftSyncSnapshot.setFromWire(names.map { (it as? JsonPrimitive)?.contentOrNull }) }
            .orEmpty()
    return when {
        // A scope kind or op this client does not know: retake everything the scope may hold.
        kind == null -> RaftSyncChange.Resnapshot(seq, "scope", op, snapshots.ifEmpty { RaftSyncSnapshot.All })
        op == "added" -> RaftSyncChange.ScopeAdded(seq, kind, id, snapshots)
        op == "removed" -> RaftSyncChange.ScopeRemoved(seq, kind, id)
        else -> RaftSyncChange.Resnapshot(seq, "scope", op, snapshots.ifEmpty { RaftSyncSnapshot.All })
    }
}

/**
 * Web `readSyncConversationState`: the state a change carries, or null when it carries none this
 * client can use. A part that is present but malformed nulls the whole state (the change then
 * reads the lists): dropping that part would leave its kind of state unapplied.
 */
private fun parseConversationState(element: JsonObject): RaftSyncConversationState? {
    var read: RaftSyncConversationState.Read? = null
    var done: RaftSyncConversationState.Done? = null
    var follow: RaftSyncConversationState.Follow? = null
    if ("read" in element) {
        val part = element["read"] as? JsonObject ?: return null
        val readSeq = part.nonNegativeIntegerField("readSeq") ?: return null
        val version = part.nonNegativeIntegerField("readStateVersion") ?: return null
        read = RaftSyncConversationState.Read(readSeq, version, markedUnread = part.booleanField("markedUnread") == true)
    }
    if ("done" in element) {
        val part = element["done"] as? JsonObject ?: return null
        val isDone = part.booleanField("done") ?: return null
        val frontier =
            when (val value = part["doneFrontierSeq"]) {
                null, JsonNull -> null
                else ->
                    (value as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.content
                        ?.takeIf { seq -> seq.isNotEmpty() && seq.all { it in '0'..'9' } }
                        ?: return null
            }
        done = RaftSyncConversationState.Done(isDone, frontier)
    }
    if ("follow" in element) {
        val part = element["follow"] as? JsonObject ?: return null
        val followed = part.booleanField("followed") ?: return null
        val threadDone = part.booleanField("threadDone") ?: return null
        follow = RaftSyncConversationState.Follow(followed, threadDone)
    }
    if (read == null && done == null && follow == null) return null
    return RaftSyncConversationState(read = read, done = done, follow = follow)
}

private fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** A JSON integer >= 0 within JS's safe range (web `Number.isSafeInteger(value) && value >= 0`). */
private fun JsonObject.nonNegativeIntegerField(name: String): Long? =
    longField(name)?.takeIf { it in 0L..MaxSafeJsonInteger }

private const val MaxSafeJsonInteger: Long = 9_007_199_254_740_991L

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.longField(name: String): Long? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.longOrNull
}
