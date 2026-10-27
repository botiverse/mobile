package build.raft.app.data

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
 *
 * The cursor is opaque: echo it back, never parse or compare it.
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

/** An entity whose upsert carries the row of its list endpoint (`SyncRowEntity`). */
enum class RaftSyncRowEntity(val wireName: String, val snapshot: RaftSyncSnapshot) {
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
    ServerOrder("server_order", RaftSyncSnapshot.ServerOrder);

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

    /** A known entity with an op this client does not know: retake that entity's snapshots. */
    data class Resnapshot(
        override val seq: Long,
        val entity: String,
        val op: String,
        val snapshots: Set<RaftSyncSnapshot>,
    ) : RaftSyncChange
}

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
)

@Serializable
private data class SyncChangesResponseWire(
    val cursor: String,
    val hasMore: Boolean,
    val changes: List<JsonObject> = emptyList(),
    val reset: SyncResetWire? = null,
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
 */
fun parseSyncChangesPage(bodyText: String): RaftSyncChangesPage? {
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
    val changes = ArrayList<RaftSyncChange>(wire.changes.size)
    for (element in wire.changes) {
        when (val parsed = parseSyncChange(element)) {
            ParsedSyncChange.Malformed -> return null
            ParsedSyncChange.UnknownEntity -> skipped += 1
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
    )
}

private sealed interface ParsedSyncChange {
    data class Known(val change: RaftSyncChange) : ParsedSyncChange

    data object UnknownEntity : ParsedSyncChange

    data object Malformed : ParsedSyncChange
}

private fun parseSyncChange(element: JsonObject): ParsedSyncChange {
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
                else -> RaftSyncChange.Resnapshot(seq, entity, op, setOf(rowEntity.snapshot))
            }
        )
    }
    return when (entity) {
        "conversation" ->
            ParsedSyncChange.Known(
                if (op == "stale") {
                    RaftSyncChange.ConversationStale(seq, id, element.longField("refSeq"))
                } else {
                    RaftSyncChange.Resnapshot(seq, entity, op, RaftSyncConversationSnapshots)
                }
            )
        "scope" -> ParsedSyncChange.Known(parseScopeChange(element, seq, id, op))
        else -> ParsedSyncChange.UnknownEntity
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

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.longField(name: String): Long? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.longOrNull
}
