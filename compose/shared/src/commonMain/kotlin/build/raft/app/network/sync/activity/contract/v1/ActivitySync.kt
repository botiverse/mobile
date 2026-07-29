// Generated directly from activity-sync.tsp via the TypeSpec compiler semantic graph.
// DO NOT EDIT. JSON Schema and OpenAPI are parallel outputs, not inputs.
// Source SHA-256: f464700d04af3d47290f6b7df21dc74f6ef36b0093fac4397f64078fd9d64b67
@file:OptIn(ExperimentalSerializationApi::class)

package build.raft.app.network.sync.activity.contract.v1

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

private val UINT64_DECIMAL_REGEX = Regex("^(0|[1-9][0-9]*)$")

@Serializable(with = OptionalFieldSerializer::class)
sealed interface OptionalField<out T> {
    data object Missing : OptionalField<Nothing>
    data class Present<T>(val value: T) : OptionalField<T>
}

class OptionalFieldSerializer<T>(
    private val valueSerializer: KSerializer<T>,
) : KSerializer<OptionalField<T>> {
    override val descriptor: SerialDescriptor = valueSerializer.descriptor

    override fun serialize(encoder: Encoder, value: OptionalField<T>) {
        when (value) {
            OptionalField.Missing -> error("OptionalField.Missing must be omitted by the generated codec")
            is OptionalField.Present -> encoder.encodeSerializableValue(valueSerializer, value.value)
        }
    }

    override fun deserialize(decoder: Decoder): OptionalField<T> =
        OptionalField.Present(decoder.decodeSerializableValue(valueSerializer))
}

object ActivitySyncContractJson {
    val strict: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
        encodeDefaults = false
        classDiscriminator = "type"
    }

}

const val ACTIVITY_SYNC_TYPESPEC_SHA256: String = "f464700d04af3d47290f6b7df21dc74f6ef36b0093fac4397f64078fd9d64b67"

typealias OpaqueId = String

@Serializable(with = UInt64StringSerializer::class)
data class UInt64String(val value: String) {
    init {
        require(UINT64_DECIMAL_REGEX.matches(value)) { "UInt64String must be canonical unsigned decimal" }
    }

    override fun toString(): String = value
}

object UInt64StringSerializer : KSerializer<UInt64String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UInt64String", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UInt64String) = encoder.encodeString(value.value)

    override fun deserialize(decoder: Decoder): UInt64String = UInt64String(decoder.decodeString())
}

typealias UtcInstantString = String

@Serializable
enum class ActivityFilter {
    @SerialName("all")
    All,
    @SerialName("unread")
    Unread,
    @SerialName("mentions")
    Mentions
}

@Serializable
enum class ActivitySyncState {
    @SerialName("empty")
    Empty,
    @SerialName("loading")
    Loading,
    @SerialName("ready")
    Ready,
    @SerialName("stale")
    Stale,
    @SerialName("recovering")
    Recovering
}

@Serializable
enum class ActorKind {
    @SerialName("user")
    User,
    @SerialName("agent")
    Agent,
    @SerialName("system")
    System
}

@Serializable
enum class ChannelKind {
    @SerialName("channel")
    Channel,
    @SerialName("private")
    Private,
    @SerialName("joint")
    Joint,
    @SerialName("dm")
    Dm
}

@Serializable
enum class ConstraintClass {
    @SerialName("positive")
    Positive,
    @SerialName("structural")
    Structural,
    @SerialName("valueDomain")
    ValueDomain
}

@Serializable
enum class ContractErrorCode {
    @SerialName("invalidRequest")
    InvalidRequest,
    @SerialName("staleVersion")
    StaleVersion,
    @SerialName("unauthorized")
    Unauthorized,
    @SerialName("forbidden")
    Forbidden,
    @SerialName("conflict")
    Conflict,
    @SerialName("unavailable")
    Unavailable,
    @SerialName("internal")
    Internal
}

@Serializable
enum class ContractVerdict {
    @SerialName("accept")
    Accept,
    @SerialName("reject")
    Reject,
    @SerialName("exempt")
    Exempt
}

@Serializable
enum class TombstoneReason {
    @SerialName("done")
    Done,
    @SerialName("deleted")
    Deleted,
    @SerialName("outOfWindow")
    OutOfWindow
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ActivityFixtureStep

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ActivityIngress

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ActivityIntent

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ActivityRow

@Serializable
data class ActivityCommandReceipt(
    val commandId: OpaqueId,
    val applied: Boolean,
    val activityVersion: UInt64String,
    val affectedScopes: List<AffectedReadState>,
    val tombstones: List<ActivityRowTombstone>
)

@Serializable
data class ActivityFixtureEnvelope(
    val contract: String,
    val contractVersion: Long,
    val runnerProtocol: Long,
    val status: String,
    val caseId: OpaqueId,
    val description: String,
    val initialState: FixtureInitialState,
    val steps: List<ActivityFixtureStep>
) {
    init {
        require(contract == "raft.activity-sync")
        require(contractVersion == 1L)
        require(runnerProtocol == 1L)
        require(status == "experimental")
    }
}

@Serializable
data class ActivityRowCommon(
    val rowId: OpaqueId,
    val rowVersion: UInt64String,
    val lastActivityAt: UtcInstantString,
    val unreadCount: UInt,
    val hasMention: Boolean,
    val firstUnreadMessageId: OpaqueId?,
    val firstMentionMessageId: OpaqueId?,
    val maxReadSeq: UInt64String,
    val readStateVersion: UInt64String
)

@Serializable
data class ActivityRowTombstone(
    val rowId: OpaqueId,
    val rowVersion: UInt64String,
    val reason: TombstoneReason
)

@Serializable
data class ActivityScope(
    val serverId: OpaqueId,
    val principalId: OpaqueId,
    val filter: ActivityFilter,
    val windowId: OpaqueId
)

@Serializable
data class ActivityScopeState(
    val scope: ActivityScope,
    val epoch: UInt64String?,
    val watermark: UInt64String?,
    val activityVersion: UInt64String?,
    val revision: UInt64String,
    val syncState: ActivitySyncState,
    val window: ActivityWindow
)

@Serializable
data class ActivityWindow(
    val rows: List<ActivityRow>,
    val tombstones: List<ActivityRowTombstone>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val complete: Boolean,
    val totalCount: UInt,
    val totalUnreadCount: UInt
)

@Serializable
data class AffectedReadState(
    val scopeId: OpaqueId,
    val channelId: OpaqueId?,
    val maxReadSeq: UInt64String,
    val readStateVersion: UInt64String
)

@Serializable
data class BadRequestResponse(
    val statusCode: Long,
    val body: ContractError
) {
    init {
        require(statusCode == 400L)
    }
}

@SerialName("channel")
@Serializable
data class ChannelActivityRow(
    val rowId: OpaqueId,
    val rowVersion: UInt64String,
    val lastActivityAt: UtcInstantString,
    val unreadCount: UInt,
    val hasMention: Boolean,
    val firstUnreadMessageId: OpaqueId?,
    val firstMentionMessageId: OpaqueId?,
    val maxReadSeq: UInt64String,
    val readStateVersion: UInt64String,
    val channelId: OpaqueId,
    val channelName: String,
    val channelKind: String,
    val lastMessageId: OpaqueId,
    val lastMessagePreview: String,
    val lastMessageSenderKind: ActorKind,
    val lastMessageSenderId: OpaqueId,
    val lastMessageSenderName: String?
) : ActivityRow {
    init {
        require(channelKind in setOf("channel", "private", "joint"))
    }
}

@SerialName("checkpoint")
@Serializable
data class CheckpointFixtureStep(
    val stepId: OpaqueId,
    val checkpointId: OpaqueId
) : ActivityFixtureStep

@SerialName("commandReceipt")
@Serializable
data class CommandReceiptIngress(
    val scope: ActivityScope,
    val receipt: ActivityCommandReceipt
) : ActivityIngress

@SerialName("commandRejected")
@Serializable
data class CommandRejectedIngress(
    val scope: ActivityScope,
    val commandId: OpaqueId,
    val activityVersion: UInt64String?,
    val error: ContractError
) : ActivityIngress

@Serializable
data class CommandRequest(
    val commandId: OpaqueId,
    val expectedActivityVersion: UInt64String
)

@Serializable
data class ConflictResponse(
    val statusCode: Long,
    val body: ContractError
) {
    init {
        require(statusCode == 409L)
    }
}

@Serializable
data class ContractError(
    val code: ContractErrorCode,
    val message: String,
    val retryable: Boolean
)

@Serializable
data class ContractVectorEnvelope(
    val vectorId: OpaqueId,
    val constraintClass: ConstraintClass,
    val candidate: JsonElement,
    val expectation: ContractVectorExpectation,
    val reason: String
)

@Serializable
data class ContractVectorExpectation(
    val validator: ContractVerdict,
    val typescriptStatic: ContractVerdict,
    val kotlinRuntime: ContractVerdict
)

@SerialName("difference")
@Serializable
data class DifferenceIngress(
    val requestId: OpaqueId,
    val scope: ActivityScope,
    val epoch: UInt64String,
    val fromSeq: UInt64String,
    val toSeq: UInt64String,
    val activityVersion: UInt64String,
    val rows: List<ActivityRow>,
    val tombstones: List<ActivityRowTombstone>,
    val nextFromSeq: UInt64String?
) : ActivityIngress

@SerialName("dispatch")
@Serializable
data class DispatchFixtureStep(
    val stepId: OpaqueId,
    val intent: ActivityIntent
) : ActivityFixtureStep

@SerialName("dm")
@Serializable
data class DmActivityRow(
    val rowId: OpaqueId,
    val rowVersion: UInt64String,
    val lastActivityAt: UtcInstantString,
    val unreadCount: UInt,
    val hasMention: Boolean,
    val firstUnreadMessageId: OpaqueId?,
    val firstMentionMessageId: OpaqueId?,
    val maxReadSeq: UInt64String,
    val readStateVersion: UInt64String,
    val channelId: OpaqueId,
    val channelName: String,
    val channelKind: String,
    val lastMessageId: OpaqueId,
    val lastMessagePreview: String,
    val lastMessageSenderKind: ActorKind,
    val lastMessageSenderId: OpaqueId,
    val lastMessageSenderName: String?
) : ActivityRow {
    init {
        require(channelKind == "dm")
    }
}

@SerialName("ensureWindow")
@Serializable
data class EnsureWindowIntent(
    val scope: ActivityScope
) : ActivityIntent

@Serializable
data class FixtureInitialState(
    val scopes: List<ActivityScopeState>
)

@Serializable
data class ForbiddenResponse(
    val statusCode: Long,
    val body: ContractError
) {
    init {
        require(statusCode == 403L)
    }
}

@SerialName("frame")
@Serializable
data class FrameIngress(
    val scope: ActivityScope,
    val epoch: UInt64String,
    val seq: UInt64String,
    val activityVersion: UInt64String,
    val rows: List<ActivityRow>,
    val tombstones: List<ActivityRowTombstone>
) : ActivityIngress

@Serializable
data class InboxQuery(
    val filter: OptionalField<ActivityFilter> = OptionalField.Missing,
    val cursor: OptionalField<String> = OptionalField.Missing,
    val limit: OptionalField<UInt> = OptionalField.Missing,
    val epoch: OptionalField<UInt64String> = OptionalField.Missing,
    val activityVersion: OptionalField<UInt64String> = OptionalField.Missing
)

@SerialName("ingest")
@Serializable
data class IngestFixtureStep(
    val stepId: OpaqueId,
    val ingress: ActivityIngress
) : ActivityFixtureStep

@SerialName("loadMore")
@Serializable
data class LoadMoreIntent(
    val scope: ActivityScope,
    val cursor: String
) : ActivityIntent

@SerialName("markChannelReadAll")
@Serializable
data class MarkChannelReadAllIntent(
    val scope: ActivityScope,
    val commandId: OpaqueId,
    val channelId: OpaqueId,
    val throughSeq: UInt64String
) : ActivityIntent

@Serializable
data class MarkChannelReadAllRequest(
    val commandId: OpaqueId,
    val expectedActivityVersion: UInt64String,
    val throughSeq: UInt64String
)

@SerialName("markInboxDone")
@Serializable
data class MarkInboxDoneIntent(
    val scope: ActivityScope,
    val commandId: OpaqueId,
    val rowId: OpaqueId,
    val expectedRowVersion: UInt64String
) : ActivityIntent

@SerialName("markInboxReadAll")
@Serializable
data class MarkInboxReadAllIntent(
    val scope: ActivityScope,
    val commandId: OpaqueId,
    val throughSeq: UInt64String
) : ActivityIntent

@Serializable
data class MarkInboxReadAllRequest(
    val commandId: OpaqueId,
    val expectedActivityVersion: UInt64String,
    val throughSeq: UInt64String
)

@SerialName("markThreadDone")
@Serializable
data class MarkThreadDoneIntent(
    val scope: ActivityScope,
    val commandId: OpaqueId,
    val threadChannelId: OpaqueId,
    val expectedRowVersion: UInt64String
) : ActivityIntent

@SerialName("notModified")
@Serializable
data class NotModifiedIngress(
    val requestId: OpaqueId,
    val scope: ActivityScope,
    val epoch: UInt64String,
    val watermark: UInt64String,
    val activityVersion: UInt64String
) : ActivityIngress

@SerialName("readStateUpdated")
@Serializable
data class ReadStateUpdatedIngress(
    val scope: ActivityScope,
    val epoch: UInt64String,
    val seq: UInt64String,
    val activityVersion: UInt64String,
    val updates: List<AffectedReadState>
) : ActivityIngress

@SerialName("refresh")
@Serializable
data class RefreshIntent(
    val scope: ActivityScope
) : ActivityIntent

@SerialName("restart")
@Serializable
data class RestartFixtureStep(
    val stepId: OpaqueId,
    val mode: String
) : ActivityFixtureStep {
    init {
        require(mode in setOf("cold", "persisted"))
    }
}

@SerialName("snapshot")
@Serializable
data class SnapshotIngress(
    val requestId: OpaqueId,
    val scope: ActivityScope,
    val epoch: UInt64String,
    val watermark: UInt64String,
    val activityVersion: UInt64String,
    val window: ActivityWindow
) : ActivityIngress

@SerialName("thread")
@Serializable
data class ThreadActivityRow(
    val rowId: OpaqueId,
    val rowVersion: UInt64String,
    val lastActivityAt: UtcInstantString,
    val unreadCount: UInt,
    val hasMention: Boolean,
    val firstUnreadMessageId: OpaqueId?,
    val firstMentionMessageId: OpaqueId?,
    val maxReadSeq: UInt64String,
    val readStateVersion: UInt64String,
    val threadChannelId: OpaqueId,
    val parentMessageId: OpaqueId,
    val parentChannelId: OpaqueId,
    val parentChannelName: String,
    val parentChannelKind: ChannelKind,
    val parentMessagePreview: String,
    val parentMessageSenderKind: String,
    val parentMessageSenderId: OpaqueId,
    val latestActivityPreview: String,
    val latestActivitySenderKind: ActorKind,
    val latestActivitySenderId: OpaqueId,
    val latestActivityMessageId: OpaqueId,
    val replyCount: UInt,
    val lastReplyAt: UtcInstantString?,
    val taskNumber: UInt?,
    val taskStatus: String?,
    val taskClaimedByName: String?
) : ActivityRow {
    init {
        require(parentMessageSenderKind in setOf("user", "agent"))
    }
}

@Serializable
data class UnauthorizedResponse(
    val statusCode: Long,
    val body: ContractError
) {
    init {
        require(statusCode == 401L)
    }
}

@Serializable
data class UnavailableResponse(
    val statusCode: Long,
    val body: ContractError
) {
    init {
        require(statusCode == 503L)
    }
}
