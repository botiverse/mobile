package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.reportUnknownTypeDropped
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * A channel being made an external channel (someone connected outside people or another server),
 * as the conversation's strip reads it: web botiverse/slock 922c8de664 (#15525)
 * `components/channel/useChannelConversionController.ts`, `store/channelConversionState.ts`.
 *
 * Contract: botiverse/slock origin/staging 23ae82825f
 *  - `packages/shared/src/channelConversionState.ts`: `ChannelConversionJobView`,
 *    `ChannelConversionCommandView`, `ChannelConversionState`, `projectConversionState`.
 *  - `packages/server/src/services/channelConversionService.ts` `attachLatestChannelConversionJobs`:
 *    a person's channel row (`GET /api/channels`, `GET /api/channels/:id`, and the `channel:updated`
 *    `{ channel }` the worker sends to the channel's room after every phase and when the job ends,
 *    routes/channels.ts `emitChannelConversionState` / `emitChannelConversionCompletion`) carries
 *    `conversionState` (the latest command and the current job: the active one, else the latest
 *    that ended) and the older `conversionJob` (active only) and `conversionCommand`.
 *    `conversionJobView` keeps only `canonicalCopyStarted`, `uploadCount`, `errorCode`, `failedAt`,
 *    `relockedAt` and `rollbackState` of `progress`, and reports `online_switched` as `residual_cleanup`.
 *  - `packages/server/src/routes/channels.ts`: `GET /api/channels/conversion-jobs/:jobId` answers
 *    `{ channel, conversionJob }`; `POST .../:jobId/retry` (a failed job only, else 409
 *    `channel_conversion_retry_not_allowed`) answers 202 and `POST .../:jobId/cancel` 200, both
 *    `{ channel, conversionJob }`. Both need `federateChannels` (403 `forbidden`), take an optional
 *    `{ commandId }` (the server makes one when it is absent), and answer a refused command 409
 *    `{ error, code }`, a job the person can't see 404.
 */

/** `ChannelConversionJobView.status`. */
@Serializable
enum class RaftChannelConversionJobStatus(val wireValue: String) {
    Pending("pending"),
    Running("running"),
    Failed("failed"),
    Done("done"),
    Canceled("canceled"),
    ;

    companion object {
        fun fromWire(value: String?): RaftChannelConversionJobStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/** `ChannelConversionCommandView.kind`. */
@Serializable
enum class RaftChannelConversionCommandKind(val wireValue: String) {
    Start("start"),
    Retry("retry"),
    Cancel("cancel"),
    ;

    companion object {
        fun fromWire(value: String?): RaftChannelConversionCommandKind? = entries.firstOrNull { it.wireValue == value }
    }
}

/** A conversion job as a client receives it (`ChannelConversionJobView`, with the progress keys the strip reads). */
@Serializable
data class RaftChannelConversionJob(
    val id: String,
    val status: RaftChannelConversionJobStatus,
    val phase: String,
    /** Whether Cancel is still possible; null from an older server (then the web's own rule decides). */
    val canCancel: Boolean? = null,
    val error: String? = null,
    /** `progress.canonicalCopyStarted`: the copy began, so an older server's job is past Cancel. */
    val canonicalCopyStarted: Boolean = false,
    /** `progress.uploadCount`: how many uploads keep it from going on. */
    val uploadCount: Int? = null,
    /** `progress.errorCode`. */
    val errorCode: String? = null,
)

/**
 * A channel's conversion (web `channelConversionState(channel)`): [job] is the current job (null for
 * none), [commandPending] a start, retry or cancel the server admitted that waits for the channel
 * (`ChannelConversionState.status === "pending"`), of [pendingCommandKind].
 */
@Serializable
data class RaftChannelConversion(
    val commandPending: Boolean = false,
    val pendingCommandKind: RaftChannelConversionCommandKind? = null,
    val job: RaftChannelConversionJob? = null,
) {
    companion object {
        val None = RaftChannelConversion()
    }
}

@Serializable
@RaftCoreInternalApi data class ChannelConversionJobWireDto(
    val id: String,
    val status: String,
    val phase: String = "",
    val canCancel: Boolean? = null,
    val progress: JsonObject? = null,
    val error: String? = null,
)

@Serializable
@RaftCoreInternalApi data class ChannelConversionCommandWireDto(
    val id: String,
    val kind: String,
    val status: String,
    val jobId: String? = null,
)

@Serializable
@RaftCoreInternalApi data class ChannelConversionStateWireDto(
    val status: String,
    val command: ChannelConversionCommandWireDto? = null,
    val job: ChannelConversionJobWireDto? = null,
)

/** The retry and cancel body: `{}`, the server makes the command's id. */
@Serializable
@RaftCoreInternalApi data class ChannelConversionCommandBody(val commandId: String? = null)

/** A refusal's body (`refuse()` / the command routes' `{ error, code }`). */
@Serializable
@RaftCoreInternalApi data class ChannelConversionRefusalWireDto(val error: String? = null, val code: String? = null)

private val channelConversionJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

private val channelConversionStateStatuses = setOf("idle", "pending", "running", "failed", "canceled", "done")

private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

private fun <T> JsonElement?.decodeOrNull(serializer: KSerializer<T>): T? {
    val element = this?.takeIf { it !is JsonNull } ?: return null
    return runCatching { channelConversionJson.decodeFromJsonElement(serializer, element) }.getOrNull()
}

private fun ChannelConversionJobWireDto.toDomainOrNull(): RaftChannelConversionJob? {
    if (id.isBlank()) return null
    val jobStatus =
        RaftChannelConversionJobStatus.fromWire(status)
            ?: run { reportUnknownTypeDropped("channel_conversion", "job_status", status, fallback = "no_strip"); return null }
    return RaftChannelConversionJob(
        id = id,
        status = jobStatus,
        phase = phase,
        canCancel = canCancel,
        error = error?.takeIf { it.isNotBlank() },
        canonicalCopyStarted = (progress?.get("canonicalCopyStarted") as? JsonPrimitive)?.booleanOrNull == true,
        uploadCount = (progress?.get("uploadCount") as? JsonPrimitive)?.intOrNull,
        errorCode = (progress?.get("errorCode") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
    )
}

/** `projectConversionState(command, job)` for what the strip needs: a waiting command, and the job. */
private fun projectChannelConversion(command: ChannelConversionCommandWireDto?, job: ChannelConversionJobWireDto?): RaftChannelConversion {
    val pending = command?.status == "pending"
    return RaftChannelConversion(
        commandPending = pending,
        pendingCommandKind = if (pending) commandKind(command) else null,
        job = job?.toDomainOrNull(),
    )
}

private fun commandKind(command: ChannelConversionCommandWireDto?): RaftChannelConversionCommandKind? =
    command?.let {
        RaftChannelConversionCommandKind.fromWire(it.kind)
            ?: run { reportUnknownTypeDropped("channel_conversion", "command_kind", it.kind, fallback = "kind_unknown"); null }
    }

private fun ChannelConversionStateWireDto.toDomain(): RaftChannelConversion {
    if (status !in channelConversionStateStatuses) {
        // The server builds it with projectConversionState: read it from its own parts.
        reportUnknownTypeDropped("channel_conversion", "state_status", status, fallback = "project_command_and_job")
        return projectChannelConversion(command, job)
    }
    val pending = status == "pending"
    return RaftChannelConversion(
        commandPending = pending,
        pendingCommandKind = if (pending) commandKind(command) else null,
        job = job?.toDomainOrNull(),
    )
}

private val channelConversionRowKeys = listOf("conversionState", "conversionJob", "conversionCommand")

/**
 * A channel row's conversion (web `channelConversionState`: `conversionState`, else the older
 * fields projected); null when the row carries none of the three (an edit's or archive's row says
 * nothing of it).
 */
@RaftCoreInternalApi fun parseRaftChannelConversionRow(row: JsonObject): RaftChannelConversion? {
    if (channelConversionRowKeys.none { it in row }) return null
    row["conversionState"].decodeOrNull(ChannelConversionStateWireDto.serializer())?.let { return it.toDomain() }
    return projectChannelConversion(
        command = row["conversionCommand"].decodeOrNull(ChannelConversionCommandWireDto.serializer()),
        job = row["conversionJob"].decodeOrNull(ChannelConversionJobWireDto.serializer()),
    )
}

private fun String?.parseObjectOrNull(): JsonObject? =
    this?.takeIf { it.isNotBlank() }?.let { runCatching { channelConversionJson.parseToJsonElement(it) }.getOrNull() }.objectOrNull()

/** `GET /api/channels/:id`: the row's conversion; a row with no conversion fields has none. Null: unreadable. */
fun parseSharedChannelConversionRead(bodyText: String?): RaftChannelConversion? {
    val row = bodyText.parseObjectOrNull() ?: return null
    return parseRaftChannelConversionRow(row) ?: RaftChannelConversion.None
}

/**
 * `{ channel, conversionJob }` (the job read, Retry and Cancel), as web `conversionResponseState`
 * reads it: the channel's `conversionState`, else its command with the answer's job. Null: unreadable.
 */
fun parseSharedChannelConversionJobAnswer(bodyText: String?): RaftChannelConversion? {
    val root = bodyText.parseObjectOrNull() ?: return null
    val channel = root["channel"].objectOrNull()
    channel?.get("conversionState").decodeOrNull(ChannelConversionStateWireDto.serializer())?.let { return it.toDomain() }
    return projectChannelConversion(
        command = channel?.get("conversionCommand").decodeOrNull(ChannelConversionCommandWireDto.serializer()),
        job =
            root["conversionJob"].decodeOrNull(ChannelConversionJobWireDto.serializer())
                ?: channel?.get("conversionJob").decodeOrNull(ChannelConversionJobWireDto.serializer()),
    )
}

/** The two commands the strip sends. */
enum class ChannelConversionAction(@RaftCoreInternalApi val pathSegment: String) {
    Retry("retry"),
    Cancel("cancel"),
}

@RaftCoreInternalApi fun channelConversionJobPath(jobId: String): String = "channels/conversion-jobs/${jobId.appLinkPathSegment()}"

@RaftCoreInternalApi interface RaftChannelConversionApi {
    suspend fun readChannel(serverId: ServerId, channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse

    suspend fun readJob(serverId: ServerId, jobId: String, priority: DataRequestPriority): HomeNetworkResponse

    suspend fun command(serverId: ServerId, jobId: String, action: ChannelConversionAction): HomeNetworkResponse
}

@RaftCoreInternalApi class NetworkRaftChannelConversionApi(private val networkClient: RaftNetworkClient) : RaftChannelConversionApi {
    override suspend fun readChannel(serverId: ServerId, channelId: ChannelId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority = priority, path = "channels/${channelId.value.appLinkPathSegment()}", serverIdOverride = serverId)

    override suspend fun readJob(serverId: ServerId, jobId: String, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority = priority, path = channelConversionJobPath(jobId), serverIdOverride = serverId)

    override suspend fun command(serverId: ServerId, jobId: String, action: ChannelConversionAction): HomeNetworkResponse =
        networkClient.request(
            // A person's Retry or Cancel: they wait for it.
            priority = DataRequestPriority.Foreground,
            path = "${channelConversionJobPath(jobId)}/${action.pathSegment}",
            method = "POST",
            bodyText = channelConversionJson.encodeToString(ChannelConversionCommandBody.serializer(), ChannelConversionCommandBody()),
            serverIdOverride = serverId,
        )
}

/** No answer, or the server failed (5xx): the outcome is unknown, not refused (web `!response || status >= 500`). */
@RaftCoreInternalApi const val ChannelConversionUncertainCode = "channel_conversion_uncertain"

/** A 2xx answer that could not be read: the command may have gone through. */
@RaftCoreInternalApi const val ChannelConversionUnreadableCode = "channel_conversion_unreadable"

/** Whether [this] failure leaves the outcome unknown (see [ChannelConversionUncertainCode]) rather than refused. */
@RaftCoreInternalApi fun ScreenError.channelConversionOutcomeUnknown(): Boolean =
    code == ChannelConversionUncertainCode || code == ChannelConversionUnreadableCode

private fun HomeNetworkResponse.toChannelConversionError(): ScreenError {
    val refusal =
        (bodyText ?: errorBodyText)?.takeIf { it.isNotBlank() }?.let {
            runCatching { channelConversionJson.decodeFromString(ChannelConversionRefusalWireDto.serializer(), it) }.getOrNull()
        }
    val serverCode = refusal?.code?.trim()?.takeIf { it.isNotBlank() }
    return ScreenError(
        code = if (code in 500..599) ChannelConversionUncertainCode else serverCode ?: "channel_conversion_http_$code",
        retryable = code in 500..599 || code == 429,
        serverMessage = refusal?.error?.trim()?.takeIf { it.isNotBlank() },
        serverCode = serverCode,
        traceId = traceId,
    )
}

/** The reads and commands; every failure is a typed [DataResult.Error], never a thrown one. */
@RaftCoreInternalApi class ChannelConversionRepository(private val api: RaftChannelConversionApi) {
    suspend fun readChannel(serverId: ServerId, channelId: ChannelId, priority: DataRequestPriority): DataResult<RaftChannelConversion> =
        call(::parseSharedChannelConversionRead) { api.readChannel(serverId, channelId, priority) }

    suspend fun readJob(serverId: ServerId, jobId: String, priority: DataRequestPriority): DataResult<RaftChannelConversion> =
        call(::parseSharedChannelConversionJobAnswer) { api.readJob(serverId, jobId, priority) }

    suspend fun command(serverId: ServerId, jobId: String, action: ChannelConversionAction): DataResult<RaftChannelConversion> =
        call(::parseSharedChannelConversionJobAnswer) { api.command(serverId, jobId, action) }

    private suspend fun call(
        parse: (String?) -> RaftChannelConversion?,
        request: suspend () -> HomeNetworkResponse,
    ): DataResult<RaftChannelConversion> {
        val response =
            runCatchingPreservingCancellation { request() }.getOrElse { error ->
                return DataResult.Error(ScreenError(ChannelConversionUncertainCode, retryable = true, serverMessage = error.message, traceId = null))
            }
        if (!response.isSuccessful) return DataResult.Error(response.toChannelConversionError())
        val conversion =
            parse(response.bodyText)
                ?: return DataResult.Error(ScreenError(ChannelConversionUnreadableCode, retryable = true, traceId = response.traceId))
        return DataResult.Content(conversion)
    }
}
