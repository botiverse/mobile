package build.raft.app.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * botiverse/slock's shared Activity row contract cases (`packages/shared/contract-cases/activity-row/`,
 * format in its README; #triage:#244), vendored at
 * `src/commonTest/resources/contract-cases/activity-row/` by `scripts/sync_slock_contract_cases.py`.
 *
 * Three rules: `position` (a served row, then live messages / answers / reads, and whether the row is
 * re-read), `predicate` (does one live message count, and does it light the @) and `sync_inbox_row`
 * (a `GET /api/sync/changes` `inbox_row` change on the held row: an upsert is an `answer` step, a
 * delete a `syncDelete` step; the row may end `removed`; slock #15312).
 *
 * The decoder is lenient per case, like [decodeUnreadContractCase]: an unknown `act`, `formatVersion`,
 * field or expectation key skips and reports that one case (never fails the suite, never passes it).
 */
internal const val ActivityRowContractFormatVersion = 1

@Serializable
internal data class ActivityRowContractCase(
    val formatVersion: Int,
    val id: String,
    val rule: String,
    val title: String,
    val why: String? = null,
    val status: String,
    val sources: JsonObject? = null,
    val knownGaps: List<ActivityRowKnownGap> = emptyList(),
    val given: ActivityRowGiven,
    val steps: List<ActivityRowStep>,
    val checkpoints: List<ActivityRowCheckpoint> = emptyList(),
    val expect: ActivityRowExpectation? = null,
    val decision: ActivityRowDecision? = null,
)

@Serializable
internal data class ActivityRowKnownGap(val client: String, val reason: String, val followUp: String? = null)

@Serializable
internal data class ActivityRowGiven(
    val row: ActivityRowServed? = null,
    val viewer: ActivityRowViewer? = null,
    val scope: ActivityRowScope? = null,
    val message: ActivityRowMessage? = null,
)

/**
 * A served row's unread inputs. [position] is a decimal seq string (exact; one case is above 2^53).
 * [readSeq] (optional, an answer's row): the read position served with it (its `maxReadSeq`).
 */
@Serializable
internal data class ActivityRowServed(
    val unreadCount: Int,
    val hasMention: Boolean,
    val position: String?,
    val mentionOnly: Boolean,
    val readSeq: String? = null,
)

@Serializable
internal data class ActivityRowViewer(val type: String, val id: String)

@Serializable
internal enum class ActivityRowKind {
    @SerialName("channel") Channel,
    @SerialName("dm") Dm,
    @SerialName("thread") Thread,
    @SerialName("task") Task,
}

@Serializable
internal data class ActivityRowScope(
    val rowKind: ActivityRowKind,
    val threadFollowed: Boolean? = null,
    val activityMuted: Boolean? = null,
    val readOnArrival: Boolean? = null,
)

@Serializable
internal data class ActivityRowMention(val type: String, val id: String)

/**
 * An absent `systemSubtype` (the event carried no system provenance) and JSON `null` (no subtype,
 * provenance carried) mean different things; the decoder can tell them apart only from the raw
 * JSON, so it sets [carriesSystemProvenance] from the key's presence.
 */
@Serializable
internal data class ActivityRowMessage(
    val senderType: String,
    val senderId: String,
    val messageType: String,
    val causalActorType: String? = null,
    val causalActorId: String? = null,
    val systemSubtype: String? = null,
    val countsUnread: Boolean? = null,
    val quietForPeople: Boolean? = null,
    val mentions: List<ActivityRowMention> = emptyList(),
    @Transient val carriesSystemProvenance: Boolean = false,
)

/** One act; `act` is the class discriminator. */
@Serializable
internal sealed interface ActivityRowStep {
    /** [counts] null: the predicate cannot judge it (re-read the row). */
    @Serializable
    @SerialName("liveMessage")
    data class LiveMessage(
        val seq: String,
        val counts: Boolean?,
        val mentionsViewer: Boolean,
        /** The predicate's `moves` (absent = true); false: past a channel or DM mute. */
        val moves: Boolean = true,
    ) : ActivityRowStep

    @Serializable
    @SerialName("answer")
    data class Answer(val row: ActivityRowServed) : ActivityRowStep

    @Serializable
    @SerialName("read")
    data class Read(val seq: String) : ActivityRowStep

    /** The viewer marks the conversation unread (the read cursor moves back). */
    @Serializable
    @SerialName("markUnread")
    data object MarkUnread : ActivityRowStep

    /** `sync_inbox_row` only: an `inbox_row` delete arrives ([position] null: `left`). */
    @Serializable
    @SerialName("syncDelete")
    data class SyncDelete(val position: String?, val reason: String) : ActivityRowStep
}

/**
 * `row` stays a [JsonObject]: only the listed keys are asserted, and `position: null` (asserted: no
 * position) must stay distinct from an absent `position` (not asserted).
 */
@Serializable
internal data class ActivityRowExpectation(
    val row: JsonObject? = null,
    val reread: Boolean? = null,
    /** `sync_inbox_row` only: the row is no longer held. */
    val removed: Boolean? = null,
    val verdict: ActivityRowVerdict? = null,
    val flagOn: ActivityRowFlagOnExpectations? = null,
)

@Serializable
internal data class ActivityRowFlagOnExpectations(
    @SerialName("people_activity_rows_v0")
    val peopleActivityRowsV0: ActivityRowVerdictContainer? = null,
)

@Serializable
internal data class ActivityRowVerdictContainer(val verdict: ActivityRowVerdict)

@Serializable
internal data class ActivityRowCheckpoint(
    val afterStep: Int,
    val row: JsonObject? = null,
    val reread: Boolean? = null,
    val removed: Boolean? = null,
)

@Serializable
internal data class ActivityRowVerdict(
    val counts: Boolean?,
    val mention: Boolean,
    /** It becomes the row's latest activity (false past a channel or DM mute, slock #15111); null: not stated. */
    val moves: Boolean? = null,
)

@Serializable
internal data class ActivityRowDecision(val question: String, val owner: String, val candidates: JsonArray)

/** The `row` expectation keys of formatVersion 1 (README "Expectations"). */
internal val ActivityRowStateKeys = setOf("unreadCount", "hasMention", "position", "mentionOnly")

private val ActivityRowActs = setOf("liveMessage", "answer", "read", "markUnread", "syncDelete")

private val ActivityRowRules = setOf("position", "predicate", "sync_inbox_row")

/** Strict: an unknown field in a known act, `given` or `expect` is a case this code cannot read. */
private val activityRowCaseJson = Json { classDiscriminator = "act" }

internal sealed interface ActivityRowCaseDecoding {
    val fileId: String

    data class Decoded(override val fileId: String, val case: ActivityRowContractCase) : ActivityRowCaseDecoding

    /** Skipped and reported: this loader cannot read the case (a copy newer than the code). */
    data class Unreadable(override val fileId: String, val reason: String) : ActivityRowCaseDecoding
}

internal fun decodeActivityRowContractCase(fileId: String, text: String): ActivityRowCaseDecoding {
    val root =
        try {
            Json.parseToJsonElement(text).jsonObject
        } catch (error: IllegalArgumentException) {
            return ActivityRowCaseDecoding.Unreadable(fileId, "not a JSON object: ${error.message?.lineSequence()?.first()}")
        }
    val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
    if (version != ActivityRowContractFormatVersion) {
        return ActivityRowCaseDecoding.Unreadable(
            fileId,
            "formatVersion $version is not one this loader knows ($ActivityRowContractFormatVersion)"
        )
    }
    val rule = (root["rule"] as? JsonPrimitive)?.contentOrNull
    if (rule !in ActivityRowRules) {
        return ActivityRowCaseDecoding.Unreadable(fileId, "unknown rule '$rule' (the vendored copy is newer than this loader)")
    }
    val acts =
        runCatching { root["steps"]?.jsonArray?.map { it.jsonObject["act"]?.jsonPrimitive?.contentOrNull } }.getOrNull()
            ?: return ActivityRowCaseDecoding.Unreadable(fileId, "steps is not a list of acts")
    acts.firstOrNull { it !in ActivityRowActs }?.let { act ->
        return ActivityRowCaseDecoding.Unreadable(fileId, "unknown act '$act' (the vendored copy is newer than this loader)")
    }
    val rowKeys =
        buildList {
            (root["expect"] as? JsonObject)?.let { add(it["row"]) }
            (root["checkpoints"] as? JsonArray)?.forEach { add((it as? JsonObject)?.get("row")) }
        }.filterIsInstance<JsonObject>().flatMap { it.keys }
    rowKeys.firstOrNull { it !in ActivityRowStateKeys }?.let { key ->
        return ActivityRowCaseDecoding.Unreadable(fileId, "unknown row key '$key' (the vendored copy is newer than this loader)")
    }
    val carriesProvenance =
        ((root["given"] as? JsonObject)?.get("message") as? JsonObject)?.containsKey("systemSubtype") == true
    return try {
        val case = activityRowCaseJson.decodeFromJsonElement(ActivityRowContractCase.serializer(), root)
        val message = case.given.message?.copy(carriesSystemProvenance = carriesProvenance)
        ActivityRowCaseDecoding.Decoded(fileId, case.copy(given = case.given.copy(message = message)))
    } catch (error: SerializationException) {
        ActivityRowCaseDecoding.Unreadable(fileId, "cannot decode with this loader: ${error.message?.lineSequence()?.first()}")
    } catch (error: IllegalArgumentException) {
        ActivityRowCaseDecoding.Unreadable(fileId, "cannot decode with this loader: ${error.message?.lineSequence()?.first()}")
    }
}

/** What the app showed for the row, in the case's terms (`position` is the shown `latestActivitySeq`). */
internal data class ActivityRowObserved(val unreadCount: Int, val hasMention: Boolean, val position: String?) {
    val mentionOnly: Boolean get() = unreadCount == 0 && hasMention

    fun value(key: String): JsonElement =
        when (key) {
            "unreadCount" -> JsonPrimitive(unreadCount)
            "hasMention" -> JsonPrimitive(hasMention)
            "position" -> JsonPrimitive(position)
            "mentionOnly" -> JsonPrimitive(mentionOnly)
            else -> error("unknown row key $key")
        }
}

/** Expected vs actual for every key [expected] lists; [observed] null: the row is not shown at all. */
internal fun activityRowMismatches(where: String, expected: JsonObject, observed: ActivityRowObserved?): List<String> {
    if (observed == null) return listOf("$where: the row is not shown (expected $expected)")
    return expected.mapNotNull { (key, value) ->
        val actual = observed.value(key)
        if (actual == value) null else "$where: $key: expected $value, got $actual"
    }
}
