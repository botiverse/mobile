package build.raft.app.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * botiverse/slock's shared unread contract cases (`packages/shared/contract-cases/unread/`, format in
 * its README; task #proj-mobile:#70), vendored at
 * `src/commonTest/resources/contract-cases/unread/` by `scripts/sync_slock_contract_cases.py`.
 *
 * The decoder is lenient per case: the vendored copy can be newer than this code, so an unknown
 * `act`, `formatVersion`, field or state key skips and reports that one case (never fails the suite,
 * never passes the case). Within slock the schema is checked at the same commit, so this is only the
 * app's half of the versioning rule.
 */
internal const val UnreadContractFormatVersion = 1

@Serializable
internal data class UnreadContractCase(
    val formatVersion: Int,
    val id: String,
    val rule: String,
    val title: String,
    val why: String? = null,
    val status: String,
    val sources: JsonObject? = null,
    val knownGaps: List<UnreadCaseKnownGap> = emptyList(),
    val given: UnreadCaseGiven,
    val steps: List<UnreadCaseStep>,
    val checkpoints: List<UnreadCaseCheckpoint> = emptyList(),
    val expect: UnreadCaseExpectation? = null,
    val decision: UnreadCaseDecision? = null,
)

@Serializable
internal data class UnreadCaseKnownGap(val client: String, val reason: String, val followUp: String? = null)

@Serializable
internal data class UnreadCaseGiven(
    val conversation: UnreadCaseConversation,
    val openThread: String? = null,
    val row: UnreadCaseRow? = null,
    val jumpTarget: UnreadCaseJumpTarget? = null,
)

@Serializable
internal data class UnreadCaseConversation(val id: String, val kind: UnreadCaseConversationKind)

@Serializable
internal enum class UnreadCaseConversationKind {
    @SerialName("channel") Channel,
    @SerialName("thread") Thread,
    @SerialName("dm") Dm,
}

@Serializable
internal data class UnreadCaseRow(
    val joined: Boolean,
    val muted: Boolean,
    val unread: Int,
    val threads: Int = 0,
    val hasNew: Boolean? = null,
    val markedUnread: Boolean = false,
    val mention: Boolean? = null,
)

@Serializable
internal data class UnreadCaseJumpTarget(val parentInHistory: Boolean?, val repliesInHistory: Boolean?)

/** One act; `act` is the class discriminator. */
@Serializable
internal sealed interface UnreadCaseStep {
    @Serializable
    @SerialName("markUnread")
    data class MarkUnread(val surface: UnreadCaseSurface) : UnreadCaseStep

    @Serializable
    @SerialName("serverResponds")
    data class ServerResponds(val status: Int, val body: JsonObject? = null, val from: String? = null) : UnreadCaseStep

    @Serializable
    @SerialName("realtimeEvent")
    data class RealtimeEvent(val type: String, val payload: JsonObject) : UnreadCaseStep

    @Serializable
    @SerialName("replyArrives")
    data class ReplyArrives(val visible: Boolean) : UnreadCaseStep

    @Serializable
    @SerialName("leaveThread")
    data object LeaveThread : UnreadCaseStep

    @Serializable
    @SerialName("jumpToUnreadThread")
    data object JumpToUnreadThread : UnreadCaseStep

    @Serializable
    @SerialName("jumpLands")
    data class JumpLands(val historyLimited: Boolean) : UnreadCaseStep
}

@Serializable
internal enum class UnreadCaseSurface {
    @SerialName("threadMenu") ThreadMenu,
    @SerialName("activityRow") ActivityRow,
    @SerialName("conversationRow") ConversationRow,
    @SerialName("channelMessage") ChannelMessage,
}

/** A mutating request, `/api` prefix included (GETs are never asserted). */
@Serializable
internal data class UnreadCaseRequest(val method: String, val path: String)

/**
 * `state` stays a [JsonObject]: only the listed keys are asserted, and `toast: null` (asserted: no
 * toast) must stay distinct from an absent `toast` (not asserted).
 */
@Serializable
internal data class UnreadCaseExpectation(val requests: List<UnreadCaseRequest>? = null, val state: JsonObject? = null)

@Serializable
internal data class UnreadCaseCheckpoint(
    val afterStep: Int,
    val requests: List<UnreadCaseRequest>? = null,
    val state: JsonObject? = null,
)

@Serializable
internal data class UnreadCaseDecision(val question: String, val owner: String, val candidates: JsonArray)

/** The `state` keys of formatVersion 1 (README "Expectations"). */
internal val UnreadCaseStateKeys =
    setOf("outcome", "unreadState", "toast", "holdActive", "threadUnreadCleared", "rowDisplay")

private val UnreadCaseActs =
    setOf("markUnread", "serverResponds", "realtimeEvent", "replyArrives", "leaveThread", "jumpToUnreadThread", "jumpLands")

/** Strict: an unknown field in a known act or in `given` is a case this code cannot read. */
private val unreadCaseJson = Json { classDiscriminator = "act" }

internal sealed interface UnreadCaseDecoding {
    val fileId: String

    data class Decoded(override val fileId: String, val case: UnreadContractCase) : UnreadCaseDecoding

    /** Skipped and reported: this loader cannot read the case (a copy newer than the code). */
    data class Unreadable(override val fileId: String, val reason: String) : UnreadCaseDecoding
}

internal fun decodeUnreadContractCase(fileId: String, text: String): UnreadCaseDecoding {
    val root =
        try {
            Json.parseToJsonElement(text).jsonObject
        } catch (error: IllegalArgumentException) {
            return UnreadCaseDecoding.Unreadable(fileId, "not a JSON object: ${error.message?.lineSequence()?.first()}")
        }
    val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
    if (version != UnreadContractFormatVersion) {
        return UnreadCaseDecoding.Unreadable(fileId, "formatVersion $version is not one this loader knows ($UnreadContractFormatVersion)")
    }
    val acts =
        runCatching { root["steps"]?.jsonArray?.map { it.jsonObject["act"]?.jsonPrimitive?.contentOrNull } }.getOrNull()
            ?: return UnreadCaseDecoding.Unreadable(fileId, "steps is not a list of acts")
    acts.firstOrNull { it !in UnreadCaseActs }?.let { act ->
        return UnreadCaseDecoding.Unreadable(fileId, "unknown act '$act' (the vendored copy is newer than this loader)")
    }
    val stateKeys =
        buildList {
            (root["expect"] as? JsonObject)?.let { add(it["state"]) }
            (root["checkpoints"] as? JsonArray)?.forEach { add((it as? JsonObject)?.get("state")) }
        }.filterIsInstance<JsonObject>().flatMap { it.keys }
    stateKeys.firstOrNull { it !in UnreadCaseStateKeys }?.let { key ->
        return UnreadCaseDecoding.Unreadable(fileId, "unknown state key '$key' (the vendored copy is newer than this loader)")
    }
    return try {
        UnreadCaseDecoding.Decoded(fileId, unreadCaseJson.decodeFromJsonElement(UnreadContractCase.serializer(), root))
    } catch (error: SerializationException) {
        UnreadCaseDecoding.Unreadable(fileId, "cannot decode with this loader: ${error.message?.lineSequence()?.first()}")
    } catch (error: IllegalArgumentException) {
        UnreadCaseDecoding.Unreadable(fileId, "cannot decode with this loader: ${error.message?.lineSequence()?.first()}")
    }
}

/** The state keys a case asserts anywhere (checkpoints and `expect`). */
internal val UnreadContractCase.assertedStateKeys: Set<String>
    get() = (checkpoints.mapNotNull { it.state } + listOfNotNull(expect?.state)).flatMap { it.keys }.toSet()

internal val UnreadContractCase.assertsRequests: Boolean
    get() = expect?.requests != null || checkpoints.any { it.requests != null }

/** Expected vs actual for one asserted key; `rowDisplay` compares only the sub-keys the case lists. */
internal fun unreadCaseStateMismatch(key: String, expected: JsonElement, actual: JsonElement?): String? {
    val compared =
        if (key == "rowDisplay" && expected is JsonObject && actual is JsonObject) {
            JsonObject(expected.keys.associateWith { sub -> actual[sub] ?: JsonNull })
        } else {
            actual
        }
    return if (compared == expected) null else "$key: expected $expected, got ${compared ?: "(not observed)"}"
}
