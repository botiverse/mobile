package build.raft.app

import build.raft.sprout.runtime.ActionResultReceipt
import build.raft.sprout.runtime.OutboundEffect
import build.raft.sprout.runtime.SharedStateOp
import build.raft.sprout.runtime.StateReceipt
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class ParsedFenceBlock(
    val id: String?,
    val source: String,
    val complete: Boolean,
)

internal data class ParsedFenceMessage(
    val leadingProse: String,
    val trailingProse: String,
    val blocks: List<ParsedFenceBlock>,
)

/**
 * Exact Kotlin port of `demo-server/src/fence.ts` (````genui [id=...]` ... ```` ``` ````).
 */
internal object SproutFenceParser {
    private val OPEN_RE = Regex("^```genui(?:[ \\t]+id=([A-Za-z0-9_.-]{1,64}))?[ \\t]*$")
    private val ANY_OPEN_RE = Regex("^```genui(?:[ \\t]+id=(\\S+))?[ \\t]*$")
    private val CLOSE_RE = Regex("^```[ \\t]*$")

    fun parseWhole(text: String): ParsedFenceMessage {
        val leading = StringBuilder()
        val trailing = StringBuilder()
        val blocks = ArrayList<ParsedFenceBlock>()
        var currentBlockId: String? = null
        var currentSource = StringBuilder()
        var inside = false

        var lineBuf = ""
        var emitted = 0

        fun emitText(piece: String) {
            if (piece.isEmpty()) return
            if (inside) {
                currentSource.append(piece)
            } else if (blocks.isEmpty()) {
                leading.append(piece)
            } else {
                trailing.append(piece)
            }
        }

        fun mayBeFence(line: String): Boolean {
            if (line.length <= 3) return "```".startsWith(line)
            if (!line.startsWith("```")) return false
            return if (inside) {
                Regex("^```[ \\t\\r]*$").matches(line)
            } else {
                "```genui".startsWith(line.take(8)) && line.length <= 212
            }
        }

        fun flushPartial() {
            if (emitted == 0 && mayBeFence(lineBuf)) return
            emitText(lineBuf.substring(emitted))
            emitted = lineBuf.length
        }

        fun endLine() {
            val clean = lineBuf.removeSuffix("\r")
            if (emitted == 0 && !inside && ANY_OPEN_RE.matches(clean)) {
                inside = true
                currentBlockId = OPEN_RE.matchEntire(clean)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
                currentSource = StringBuilder()
            } else if (emitted == 0 && inside && CLOSE_RE.matches(clean)) {
                inside = false
                blocks.add(ParsedFenceBlock(id = currentBlockId, source = currentSource.toString(), complete = true))
                currentBlockId = null
            } else {
                emitText(lineBuf.substring(emitted) + "\n")
            }
            lineBuf = ""
            emitted = 0
        }

        var i = 0
        while (i < text.length) {
            val nl = text.indexOf('\n', i)
            if (nl == -1) {
                lineBuf += text.substring(i)
                flushPartial()
                break
            }
            lineBuf += text.substring(i, nl)
            endLine()
            i = nl + 1
        }

        if (lineBuf.isNotEmpty()) {
            val clean = lineBuf.removeSuffix("\r")
            if (inside && CLOSE_RE.matches(clean) && emitted == 0) {
                inside = false
                blocks.add(ParsedFenceBlock(id = currentBlockId, source = currentSource.toString(), complete = true))
            } else if (!inside && ANY_OPEN_RE.matches(clean) && emitted == 0) {
                val id = OPEN_RE.matchEntire(clean)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
                blocks.add(ParsedFenceBlock(id = id, source = "", complete = false))
            } else {
                emitText(lineBuf.substring(emitted))
            }
        }
        if (inside) {
            blocks.add(ParsedFenceBlock(id = currentBlockId, source = currentSource.toString(), complete = false))
        }

        return ParsedFenceMessage(
            leadingProse = leading.toString().trim(),
            trailingProse = trailing.toString().trim(),
            blocks = blocks,
        )
    }
}

internal data class SharedSurfaceKey(
    val scopeId: String,
    val surfaceId: String,
    val surfaceGeneration: Long = 1L,
)

internal data class SharedSurfaceRecord(
    val revision: Long,
    val state: JsonObject,
)

/**
 * Local shared-state coordinator synchronizing Viewer A (`你`) and Viewer B (`队友`) in real time.
 */
internal class LocalSharedStateHub {
    private val records = ConcurrentHashMap<SharedSurfaceKey, SharedSurfaceRecord>()
    private val listeners = CopyOnWriteArrayList<(SharedSurfaceKey, SharedSurfaceRecord, String?) -> Unit>()

    fun subscribe(listener: (SharedSurfaceKey, SharedSurfaceRecord, String?) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun read(key: SharedSurfaceKey): SharedSurfaceRecord? = records[key]

    @Synchronized
    fun applyServerSnapshot(key: SharedSurfaceKey, revision: Long, state: JsonObject, originOpId: String? = null) {
        val prev = records[key]
        if (prev != null && revision < prev.revision) return
        val next = SharedSurfaceRecord(revision = revision, state = state)
        records[key] = next
        for (l in listeners) l(key, next, originOpId)
    }

    @Synchronized
    fun initIfAbsent(
        key: SharedSurfaceKey,
        operationId: String,
        defaults: JsonObject,
    ): StateReceipt {
        val existing = records[key]
        if (existing != null && existing.revision > 0L) {
            return StateReceipt.Conflict(
                operationId = operationId,
                revision = existing.revision,
                state = existing.state,
                surfaceId = key.surfaceId,
                surfaceGeneration = key.surfaceGeneration,
            )
        }
        val created = SharedSurfaceRecord(revision = 1L, state = defaults)
        records[key] = created
        for (l in listeners) l(key, created, operationId)
        return StateReceipt.Accepted(
            operationId = operationId,
            revision = 1L,
            state = defaults,
            surfaceId = key.surfaceId,
            surfaceGeneration = key.surfaceGeneration,
        )
    }

    @Synchronized
    fun propose(
        key: SharedSurfaceKey,
        operationId: String,
        baseRevision: Long,
        ops: List<SharedStateOp>,
    ): StateReceipt {
        val current = records[key] ?: SharedSurfaceRecord(revision = 0L, state = JsonObject(emptyMap()))
        if (current.revision != baseRevision) {
            return StateReceipt.Conflict(
                operationId = operationId,
                revision = current.revision,
                state = current.state,
                surfaceId = key.surfaceId,
                surfaceGeneration = key.surfaceGeneration,
            )
        }
        val nextMap = LinkedHashMap<String, JsonElement>(current.state)
        for (op in ops) {
            when (op) {
                is SharedStateOp.Set -> nextMap[op.key] = op.value
                is SharedStateOp.Delete -> nextMap.remove(op.key)
            }
        }
        val nextRecord = SharedSurfaceRecord(
            revision = current.revision + 1L,
            state = JsonObject(nextMap),
        )
        records[key] = nextRecord
        for (l in listeners) l(key, nextRecord, operationId)
        return StateReceipt.Accepted(
            operationId = operationId,
            revision = nextRecord.revision,
            state = nextRecord.state,
            surfaceId = key.surfaceId,
            surfaceGeneration = key.surfaceGeneration,
        )
    }

    @Synchronized
    fun forcePatchFromAgent(key: SharedSurfaceKey, nextState: JsonObject): SharedSurfaceRecord {
        val current = records[key] ?: SharedSurfaceRecord(revision = 0L, state = JsonObject(emptyMap()))
        val updated = SharedSurfaceRecord(revision = current.revision + 1L, state = nextState)
        records[key] = updated
        for (l in listeners) l(key, updated, null)
        return updated
    }

    @Synchronized
    fun clearScope(scopeId: String) {
        val toRemove = records.keys.filter { it.scopeId == scopeId }
        for (k in toRemove) records.remove(k)
    }
}

internal data class DemoServerConfig(
    val serverUrl: String,
    val token: String,
    val scopeId: String = "channel-generative-ui",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(rawInput: String): DemoServerConfig? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{")) {
                return runCatching {
                    val obj = json.parseToJsonElement(trimmed).jsonObject
                    val url = obj["serverUrl"]?.jsonPrimitive?.content?.trim()?.trimEnd('/') ?: return null
                    val tok = obj["token"]?.jsonPrimitive?.content?.trim() ?: return null
                    val scope = obj["scopeId"]?.jsonPrimitive?.content?.trim()?.ifBlank { "channel-generative-ui" }
                        ?: "channel-generative-ui"
                    if (url.startsWith("http") && tok.isNotEmpty()) {
                        DemoServerConfig(serverUrl = url, token = tok, scopeId = scope)
                    } else {
                        null
                    }
                }.getOrNull()
            }
            return runCatching {
                val uri = URI(trimmed)
                val base = "${uri.scheme}://${uri.authority}".trimEnd('/')
                val frag = uri.rawFragment ?: ""
                val tokenParam = frag
                    .removePrefix("/dev")
                    .removePrefix("&")
                    .split("&")
                    .firstOrNull { it.startsWith("token=") }
                    ?.substringAfter("token=")
                    ?.trim()
                if (!tokenParam.isNullOrEmpty() && (uri.scheme == "https" || uri.scheme == "http")) {
                    DemoServerConfig(serverUrl = base, token = tokenParam)
                } else {
                    null
                }
            }.getOrNull()
        }
    }
}

internal data class ServerHealthInfo(
    val ok: Boolean,
    val provider: String,
    val model: String?,
    val error: String? = null,
)

internal data class RemoteChatMessage(
    val id: String,
    val seq: Long,
    val version: Long,
    val atMs: Long,
    val kind: String, // "user" | "agent"
    val actorId: String = "",
    val text: String = "",
    val targetMessageId: String? = null,
    val turnId: String = "",
    val status: String = "done", // "queued" | "running" | "done" | "failed" | "cancelled"
    val delivery: String = "stream", // "stream" | "oneshot"
    val surfaceId: String? = null,
    val surfaceGeneration: Long = 1L,
    val stateSchemaVersion: Int = 1,
    val source: String? = null,
    val sourceRevision: Long = 1L,
    val revisesMessageId: String? = null,
    val revisesSourceRevision: Long? = null,
    val errorText: String? = null,
)

internal sealed class RemotePollEvent {
    data class Hello(val provider: String, val logId: String?) : RemotePollEvent()
    data class Messages(val list: List<RemoteChatMessage>, val logId: String?) : RemotePollEvent()
    data class MessageUpsert(val message: RemoteChatMessage) : RemotePollEvent()
    data class MessageTextDelta(val messageId: String, val offset: Int, val text: String) : RemotePollEvent()
    data class SnapshotPush(
        val scopeId: String,
        val surfaceId: String,
        val surfaceGeneration: Long,
        val revision: Long,
        val state: JsonObject,
        val originActorId: String?,
        val originOpId: String?,
    ) : RemotePollEvent()
    data class Halted(val message: String) : RemotePollEvent()
}

internal data class PollBatchResult(
    val resync: Boolean,
    val next: Long,
    val events: List<RemotePollEvent>,
)

/**
 * Pure JVM/Android HTTP client speaking `botiverse/sprout` `demo-server` JSON wire contract.
 */
internal class SproutDemoServerClient(val config: DemoServerConfig) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkHealth(): ServerHealthInfo = withContext(Dispatchers.IO) {
        runCatching {
            val (code, text) = request("GET", "/api/health", null, timeoutMs = 8_000)
            val obj = json.parseToJsonElement(text).jsonObject
            if (code == 200) {
                ServerHealthInfo(
                    ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true,
                    provider = obj["provider"]?.jsonPrimitive?.content ?: "unknown",
                    model = obj["model"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                )
            } else {
                val errMsg = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "HTTP $code"
                ServerHealthInfo(ok = false, provider = "error", model = null, error = errMsg)
            }
        }.getOrElse { err ->
            ServerHealthInfo(ok = false, provider = "offline", model = null, error = err.message ?: "连接失败")
        }
    }

    suspend fun pollEvents(scopeId: String, viewerId: String, after: Long?): PollBatchResult = withContext(Dispatchers.IO) {
        val qScope = URLEncoder.encode(scopeId, "UTF-8")
        val qViewer = URLEncoder.encode(viewerId, "UTF-8")
        val afterPart = if (after != null) "&after=$after" else ""
        val (_, text) = request("GET", "/api/events?scopeId=$qScope&viewerId=$qViewer&poll=1$afterPart", null, timeoutMs = 28_000)
        val root = json.parseToJsonElement(text).jsonObject
        val resync = root["resync"]?.jsonPrimitive?.booleanOrNull == true
        val next = root["next"]?.jsonPrimitive?.longOrNull ?: 0L
        val rawEvents = root["events"]?.jsonArray ?: JsonArray(emptyList())
        val parsed = rawEvents.mapNotNull { el -> parseServerEvent(el.jsonObject) }
        PollBatchResult(resync = resync, next = next, events = parsed)
    }

    suspend fun sendChatMessage(
        viewerId: String,
        scopeId: String,
        clientMessageId: String,
        text: String,
        delivery: String,
        targetMessageId: String? = null,
    ): Pair<RemoteChatMessage, RemoteChatMessage> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("viewerId", viewerId)
            put("scopeId", scopeId)
            put("clientMessageId", clientMessageId)
            put("text", text)
            put("delivery", delivery)
            if (targetMessageId != null) {
                put("target", buildJsonObject { put("messageId", targetMessageId) })
            }
        }
        val (code, respText) = request("POST", "/api/chat/send", body.toString(), timeoutMs = 15_000)
        if (code !in 200..299) {
            val errMsg = runCatching {
                json.parseToJsonElement(respText).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            }.getOrNull() ?: "HTTP $code"
            throw IllegalStateException(errMsg)
        }
        val obj = json.parseToJsonElement(respText).jsonObject
        val userMsg = parseChatMessage(obj.getValue("userMessage").jsonObject)
        val agentMsg = parseChatMessage(obj.getValue("agentMessage").jsonObject)
        userMsg to agentMsg
    }

    suspend fun cancelTurn(turnId: String) = withContext(Dispatchers.IO) {
        val cleanId = URLEncoder.encode(turnId, "UTF-8")
        runCatching { request("POST", "/api/generate/$cleanId/cancel", "{}", timeoutMs = 8_000) }
    }

    suspend fun proposeInit(
        viewerId: String,
        scopeId: String,
        eff: OutboundEffect.InitState,
    ): StateReceipt = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("viewerId", viewerId)
            put("input", buildJsonObject {
                put("identity", buildIdentityJson(scopeId, eff.surfaceId, eff.surfaceGeneration ?: 1L, eff.schemaVersion))
                put("sourceRevision", eff.sourceRevision.coerceAtLeast(1L))
                put("expectedRevision", 0)
                put("operationId", eff.operationId)
                put("operations", buildJsonArray {
                    add(buildJsonObject {
                        put("op", "init")
                        put("initialState", eff.defaults)
                    })
                })
            })
        }
        sendProposeAndMapReceipt(eff.operationId, eff.surfaceId, eff.surfaceGeneration ?: 1L, body.toString())
    }

    suspend fun proposeWrite(
        viewerId: String,
        scopeId: String,
        eff: OutboundEffect.ProposeState,
        schemaVersion: Int = 1,
    ): StateReceipt = withContext(Dispatchers.IO) {
        val opsJson = buildOperationsJson(eff.operations)
        if (opsJson.isEmpty()) {
            return@withContext StateReceipt.Denied(
                operationId = eff.operationId,
                code = "UNSUPPORTED_CAPABILITY",
                surfaceId = eff.surfaceId,
                surfaceGeneration = eff.surfaceGeneration ?: 1L,
                local = true,
            )
        }
        val body = buildJsonObject {
            put("viewerId", viewerId)
            put("input", buildJsonObject {
                put("identity", buildIdentityJson(scopeId, eff.surfaceId, eff.surfaceGeneration ?: 1L, schemaVersion))
                put("sourceRevision", eff.sourceRevision.coerceAtLeast(1L))
                put("expectedRevision", eff.baseRevision)
                put("operationId", eff.operationId)
                put("operations", opsJson)
            })
        }
        sendProposeAndMapReceipt(eff.operationId, eff.surfaceId, eff.surfaceGeneration ?: 1L, body.toString())
    }

    suspend fun emitAction(
        viewerId: String,
        scopeId: String,
        act: OutboundEffect.ActionRequest,
        coalescedWrite: OutboundEffect.ProposeState?,
        sessionInstanceId: String,
        runtimeEpoch: Long,
        frameRevision: Long,
        schemaVersion: Int = 1,
    ): Pair<StateReceipt?, ActionResultReceipt> = withContext(Dispatchers.IO) {
        val surfaceGen = act.surfaceGeneration ?: 1L
        val saveOpsObj = coalescedWrite?.let { w ->
            val opsArr = buildOperationsJson(w.operations)
            if (opsArr.isNotEmpty()) {
                buildJsonObject {
                    put("operationId", w.operationId)
                    put("operations", opsArr)
                }
            } else {
                null
            }
        }
        val body = buildJsonObject {
            put("viewerId", viewerId)
            put("input", buildJsonObject {
                put("identity", buildIdentityJson(scopeId, act.surfaceId, surfaceGen, schemaVersion))
                put("sourceRevision", act.sourceRevision.coerceAtLeast(1L))
                put("action", act.action)
                put("requestId", act.requestId)
                put("expectedStateRevision", coalescedWrite?.baseRevision ?: act.expectedStateRevision)
                put("payload", act.payload)
                if (saveOpsObj != null) put("saveOperations", saveOpsObj)
            })
            put("gesture", buildJsonObject {
                put("sessionInstanceId", sessionInstanceId)
                put("runtimeEpoch", runtimeEpoch.coerceAtLeast(0L))
                put("frameRevision", frameRevision.coerceAtLeast(0L))
            })
        }
        runCatching {
            val (_, respText) = request("POST", "/api/action", body.toString(), timeoutMs = 15_000)
            val outcome = json.parseToJsonElement(respText).jsonObject["outcome"]?.jsonObject
                ?: throw IllegalStateException("missing outcome")
            when (outcome["kind"]?.jsonPrimitive?.content) {
                "receipt" -> {
                    val rev = (outcome["stateRevision"] ?: outcome["committedStateRevision"])?.jsonPrimitive?.longOrNull ?: 0L
                    val st = outcome["state"]?.jsonObject ?: JsonObject(emptyMap())
                    val stateRcpt = coalescedWrite?.let { w ->
                        StateReceipt.Accepted(
                            operationId = w.operationId,
                            revision = rev,
                            state = st,
                            surfaceId = act.surfaceId,
                            surfaceGeneration = surfaceGen,
                        )
                    }
                    stateRcpt to ActionResultReceipt(
                        requestId = act.requestId,
                        status = outcome["status"]?.jsonPrimitive?.content ?: "queued",
                        committedStateRevision = rev,
                        state = st,
                        surfaceId = act.surfaceId,
                        surfaceGeneration = surfaceGen,
                    )
                }
                "conflict" -> {
                    val rev = outcome["currentStateRevision"]?.jsonPrimitive?.longOrNull ?: 0L
                    val st = outcome["state"]?.jsonObject ?: JsonObject(emptyMap())
                    val stateRcpt = coalescedWrite?.let { w ->
                        StateReceipt.Conflict(
                            operationId = w.operationId,
                            revision = rev,
                            state = st,
                            surfaceId = act.surfaceId,
                            surfaceGeneration = surfaceGen,
                        )
                    }
                    stateRcpt to ActionResultReceipt(
                        requestId = act.requestId,
                        status = "conflict",
                        committedStateRevision = rev,
                        state = st,
                        surfaceId = act.surfaceId,
                        surfaceGeneration = surfaceGen,
                    )
                }
                else -> {
                    val code = outcome["code"]?.jsonPrimitive?.content ?: "FORBIDDEN"
                    val stateRcpt = coalescedWrite?.let { w ->
                        StateReceipt.Denied(
                            operationId = w.operationId,
                            code = code,
                            surfaceId = act.surfaceId,
                            surfaceGeneration = surfaceGen,
                        )
                    }
                    stateRcpt to ActionResultReceipt(
                        requestId = act.requestId,
                        status = "denied",
                        surfaceId = act.surfaceId,
                        surfaceGeneration = surfaceGen,
                    )
                }
            }
        }.getOrElse {
            val stateRcpt = coalescedWrite?.let { w ->
                StateReceipt.Uncertain(
                    operationId = w.operationId,
                    reason = "network",
                    surfaceId = act.surfaceId,
                    surfaceGeneration = surfaceGen,
                )
            }
            stateRcpt to ActionResultReceipt(
                requestId = act.requestId,
                status = "uncertain",
                surfaceId = act.surfaceId,
                surfaceGeneration = surfaceGen,
            )
        }
    }

    private fun sendProposeAndMapReceipt(
        operationId: String,
        surfaceId: String,
        surfaceGeneration: Long,
        bodyJson: String,
    ): StateReceipt = runCatching {
        val (_, respText) = request("POST", "/api/state/propose", bodyJson, timeoutMs = 12_000)
        val outcome = json.parseToJsonElement(respText).jsonObject["outcome"]?.jsonObject
            ?: return StateReceipt.Uncertain(operationId, "invalid_response", surfaceId, surfaceGeneration)
        when (outcome["kind"]?.jsonPrimitive?.content) {
            "accepted" -> StateReceipt.Accepted(
                operationId = operationId,
                revision = outcome["revision"]?.jsonPrimitive?.longOrNull ?: 1L,
                state = outcome["state"]?.jsonObject ?: JsonObject(emptyMap()),
                surfaceId = surfaceId,
                surfaceGeneration = surfaceGeneration,
            )
            "conflict" -> StateReceipt.Conflict(
                operationId = operationId,
                revision = outcome["revision"]?.jsonPrimitive?.longOrNull ?: 0L,
                state = outcome["state"]?.jsonObject ?: JsonObject(emptyMap()),
                surfaceId = surfaceId,
                surfaceGeneration = surfaceGeneration,
            )
            "denied" -> StateReceipt.Denied(
                operationId = operationId,
                code = outcome["code"]?.jsonPrimitive?.content ?: "FORBIDDEN",
                surfaceId = surfaceId,
                surfaceGeneration = surfaceGeneration,
            )
            else -> StateReceipt.Uncertain(operationId, "unknown_kind", surfaceId, surfaceGeneration)
        }
    }.getOrElse { err ->
        StateReceipt.Uncertain(operationId, err.message, surfaceId, surfaceGeneration)
    }

    private fun buildIdentityJson(scopeId: String, surfaceId: String, generation: Long, schemaVersion: Int): JsonObject =
        buildJsonObject {
            put("scopeId", scopeId)
            put("surfaceId", surfaceId)
            put("surfaceGeneration", generation.coerceAtLeast(1L))
            put("stateSchemaVersion", schemaVersion.coerceAtLeast(1))
        }

    private fun buildOperationsJson(ops: List<SharedStateOp>): JsonArray = buildJsonArray {
        for (op in ops) {
            if (op is SharedStateOp.Set) {
                add(buildJsonObject {
                    put("op", "set")
                    put("key", op.key)
                    put("value", op.value)
                })
            }
        }
    }

    private fun parseServerEvent(obj: JsonObject): RemotePollEvent? {
        return when (obj["type"]?.jsonPrimitive?.content) {
            "hello" -> RemotePollEvent.Hello(
                provider = obj["provider"]?.jsonPrimitive?.content ?: "unknown",
                logId = obj["logId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            )
            "messages" -> {
                val arr = obj["messages"]?.jsonArray ?: JsonArray(emptyList())
                RemotePollEvent.Messages(
                    list = arr.map { parseChatMessage(it.jsonObject) },
                    logId = obj["logId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                )
            }
            "message" -> {
                val msgObj = obj["message"]?.jsonObject ?: return null
                RemotePollEvent.MessageUpsert(parseChatMessage(msgObj))
            }
            "message-text" -> RemotePollEvent.MessageTextDelta(
                messageId = obj["messageId"]?.jsonPrimitive?.content ?: return null,
                offset = obj["offset"]?.jsonPrimitive?.intOrNull ?: 0,
                text = obj["text"]?.jsonPrimitive?.content ?: "",
            )
            "snapshot" -> {
                val idObj = obj["identity"]?.jsonObject ?: return null
                val originObj = obj["origin"]?.takeIf { it !is JsonNull }?.jsonObject
                RemotePollEvent.SnapshotPush(
                    scopeId = idObj["scopeId"]?.jsonPrimitive?.content ?: "",
                    surfaceId = idObj["surfaceId"]?.jsonPrimitive?.content ?: "",
                    surfaceGeneration = idObj["surfaceGeneration"]?.jsonPrimitive?.longOrNull ?: 1L,
                    revision = obj["revision"]?.jsonPrimitive?.longOrNull ?: 0L,
                    state = obj["state"]?.jsonObject ?: JsonObject(emptyMap()),
                    originActorId = originObj?.get("actorId")?.jsonPrimitive?.content,
                    originOpId = originObj?.get("operationId")?.jsonPrimitive?.content,
                )
            }
            "halted" -> RemotePollEvent.Halted(
                message = obj["message"]?.jsonPrimitive?.content ?: "服务端持久化暂停",
            )
            else -> null
        }
    }

    private fun parseChatMessage(obj: JsonObject): RemoteChatMessage {
        val kind = obj["kind"]?.jsonPrimitive?.content ?: "agent"
        val surfaceObj = obj["surface"]?.takeIf { it !is JsonNull }?.jsonObject
        val revisesObj = obj["revises"]?.takeIf { it !is JsonNull }?.jsonObject
        return RemoteChatMessage(
            id = obj["id"]?.jsonPrimitive?.content ?: "",
            seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: 0L,
            version = obj["version"]?.jsonPrimitive?.longOrNull ?: 1L,
            atMs = obj["at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
            kind = kind,
            actorId = obj["actorId"]?.jsonPrimitive?.content ?: "",
            text = obj["text"]?.jsonPrimitive?.content ?: "",
            targetMessageId = obj["targetMessageId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            turnId = obj["turnId"]?.jsonPrimitive?.content ?: "",
            status = obj["status"]?.jsonPrimitive?.content ?: "done",
            delivery = obj["delivery"]?.jsonPrimitive?.content ?: "stream",
            surfaceId = surfaceObj?.get("surfaceId")?.jsonPrimitive?.content,
            surfaceGeneration = surfaceObj?.get("surfaceGeneration")?.jsonPrimitive?.longOrNull ?: 1L,
            stateSchemaVersion = surfaceObj?.get("stateSchemaVersion")?.jsonPrimitive?.intOrNull ?: 1,
            source = obj["source"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
            sourceRevision = obj["sourceRevision"]?.jsonPrimitive?.longOrNull ?: 1L,
            revisesMessageId = revisesObj?.get("messageId")?.jsonPrimitive?.content,
            revisesSourceRevision = revisesObj?.get("sourceRevision")?.jsonPrimitive?.longOrNull,
            errorText = obj["error"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        )
    }

    private fun request(method: String, path: String, bodyJson: String?, timeoutMs: Int): Pair<Int, String> {
        val conn = (URI(config.serverUrl + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("x-sprout-token", config.token)
            setRequestProperty("accept", "application/json")
            if (bodyJson != null) {
                doOutput = true
                setRequestProperty("content-type", "application/json; charset=utf-8")
            }
        }
        try {
            if (bodyJson != null) {
                conn.outputStream.use { out ->
                    out.write(bodyJson.toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val resp = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""
            return code to resp
        } finally {
            conn.disconnect()
        }
    }
}
