package build.raft.app.data

import build.raft.app.reportProjectionRowDropped
import build.raft.app.reportUnknownTypeDropped
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Strict wire parsers for Agent View Context and Context Access grants
 * (`botiverse/slock/packages/shared/src/agentContextView.ts`, `agentContextViewStatus.ts`,
 * `agentContextViewGrant.ts`).
 */

private val contextViewJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private val contextViewPrettyJson = Json {
    prettyPrint = true
}

private const val TOOL_SUMMARY_MAX_CHARS = 160

@Serializable
private data class NamedRefWireDto(
    val id: String? = null,
    val name: String? = null,
) {
    fun toNamedRef(): AgentContextViewNamedRef? {
        val cleanId = id?.takeIf { it.isNotBlank() } ?: return null
        val cleanName = name ?: return null
        return AgentContextViewNamedRef(id = cleanId, name = cleanName)
    }
}

@Serializable
private data class AgentContextViewStatusWireDto(
    val state: String? = null,
    val reason: String? = null,
    val runtime: String? = null,
    val computer: NamedRefWireDto? = null,
    val computerOwner: NamedRefWireDto? = null,
    val retainedSince: String? = null,
    val retentionDays: Int? = null,
)

@Serializable
private data class AgentContextViewGrantWireDto(
    val id: String? = null,
    val agent: NamedRefWireDto? = null,
    val granter: NamedRefWireDto? = null,
    val grantedAt: String? = null,
    val usable: Boolean? = null,
    val unusableReason: String? = null,
)

@Serializable
private data class AgentContextViewGrantListWireDto(
    val grants: List<AgentContextViewGrantWireDto>? = null,
)

@Serializable
private data class AgentContextViewGrantCreatedWireDto(
    val grant: AgentContextViewGrantWireDto? = null,
    val created: Boolean? = null,
)

@Serializable
private data class AgentContextViewGrantRevokedWireDto(
    val ok: Boolean? = null,
    val revoked: Boolean? = null,
)

@Serializable
internal data class AgentContextViewGrantRefusal(
    val code: String? = null,
    val error: String? = null,
)

fun parseAgentContextViewStatus(raw: String): AgentContextViewStatus? {
    val dto =
        try {
            contextViewJson.decodeFromString(AgentContextViewStatusWireDto.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    val runtime = dto.runtime?.takeIf { it.isNotBlank() } ?: return null
    val state =
        when (dto.state) {
            "available" -> AgentContextViewStatusState.Available
            "unavailable" -> {
                val reason = AgentContextViewStatusReason.fromWire(dto.reason)
                if (reason == null) {
                    reportUnknownTypeDropped(
                        source = "agent_context_view_status",
                        field = "reason",
                        value = dto.reason,
                    )
                    return null
                }
                AgentContextViewStatusState.Unavailable(reason)
            }
            else -> {
                reportUnknownTypeDropped(
                    source = "agent_context_view_status",
                    field = "state",
                    value = dto.state,
                )
                return null
            }
        }
    val retentionDays =
        dto.retentionDays?.takeIf { it in 1..3650 } ?: AGENT_CONTEXT_VIEW_DEFAULT_RETENTION_DAYS
    return AgentContextViewStatus(
        runtime = runtime,
        computer = dto.computer?.toNamedRef(),
        computerOwner = dto.computerOwner?.toNamedRef(),
        retainedSince = dto.retainedSince?.takeIf { it.isNotBlank() },
        retentionDays = retentionDays,
        state = state,
    )
}

/**
 * Parses `GET /api/agents/:id/messages/:messageId/context` body (web `readAgentContextViewAnswer`).
 * Any malformed, truncated, or invalid body becomes [AgentContextViewFailureReason.ReadFailed]
 * (never `too_large`).
 */
fun parseAgentContextViewAnswer(
    body: String?,
    traceId: String? = null,
): AgentContextViewAnswer {
    if (body.isNullOrBlank()) {
        return AgentContextViewAnswer.Failed(
            reason = AgentContextViewFailureReason.ReadFailed,
            traceId = traceId,
        )
    }
    val root =
        try {
            contextViewJson.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
            ?: return AgentContextViewAnswer.Failed(
                reason = AgentContextViewFailureReason.ReadFailed,
                traceId = traceId,
            )

    val ok = root["ok"]?.asStrictBooleanOrNull()
        ?: return AgentContextViewAnswer.Failed(
            reason = AgentContextViewFailureReason.ReadFailed,
            traceId = traceId,
        )

    if (ok) {
        val contextEl = root["context"] as? JsonObject
            ?: return AgentContextViewAnswer.Failed(
                reason = AgentContextViewFailureReason.ReadFailed,
                traceId = traceId,
            )
        val parsed = parseAgentContextViewObject(contextEl)
            ?: return AgentContextViewAnswer.Failed(
                reason = AgentContextViewFailureReason.ReadFailed,
                traceId = traceId,
            )
        val rawPretty =
            try {
                contextViewPrettyJson.encodeToString(JsonElement.serializer(), contextEl)
            } catch (_: Throwable) {
                body
            }
        return AgentContextViewAnswer.Ok(context = parsed, rawJson = rawPretty)
    }

    val rawReason = root["reason"]?.asStrictStringOrNull()
    val reason =
        if (rawReason.isNullOrBlank()) {
            AgentContextViewFailureReason.ReadFailed
        } else {
            AgentContextViewFailureReason.fromWire(rawReason) ?: run {
                reportUnknownTypeDropped(
                    source = "agent_context_view_answer",
                    field = "reason",
                    value = rawReason,
                    fallback = "read_failed",
                )
                AgentContextViewFailureReason.ReadFailed
            }
        }
    val rawTooLargeCause = root["tooLargeCause"]?.asStrictStringOrNull()
    val tooLargeCause =
        if (reason == AgentContextViewFailureReason.TooLarge && !rawTooLargeCause.isNullOrBlank()) {
            AgentContextViewTooLargeCause.fromWire(rawTooLargeCause) ?: run {
                reportUnknownTypeDropped(
                    source = "agent_context_view_answer",
                    field = "tooLargeCause",
                    value = rawTooLargeCause,
                )
                null
            }
        } else {
            null
        }
    return AgentContextViewAnswer.Failed(
        reason = reason,
        tooLargeCause = tooLargeCause,
        computer = (root["computer"] as? JsonObject)?.toNamedRefOrNull(),
        computerOwner = (root["computerOwner"] as? JsonObject)?.toNamedRefOrNull(),
        traceId = traceId,
    )
}

fun parseAgentContextView(raw: String): AgentContextView? {
    val obj =
        try {
            contextViewJson.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
            ?: return null
    return parseAgentContextViewObject(obj)
}

private fun parseAgentContextViewObject(obj: JsonObject): AgentContextView? {
    val runtime = obj["runtime"]?.asNonEmptyStrictStringOrNull() ?: return null
    val model = obj.readNullableStrictString("model") ?: return null
    val sessionId = obj["sessionId"]?.asNonEmptyStrictStringOrNull() ?: return null
    val requestAt = obj.readNullableIsoString("requestAt") ?: return null
    val messageId = obj["messageId"]?.asNonEmptyStrictStringOrNull() ?: return null

    val tokensObj = obj["tokens"] as? JsonObject ?: return null
    val inputTokens = tokensObj.readNullableCount("input") ?: return null
    val cacheReadTokens = tokensObj.readNullableCount("cacheRead") ?: return null
    val totalTokens = tokensObj.readNullableCount("total") ?: return null

    val systemArray = obj["system"] as? JsonArray ?: return null
    val systemBlocks =
        systemArray.map { entry ->
            val blockObj = entry as? JsonObject ?: return null
            val text = blockObj["text"]?.asStrictStringOrNull() ?: return null
            AgentContextViewSystemBlock(text = text)
        }

    val toolsArray = obj["tools"] as? JsonArray ?: return null
    val tools =
        toolsArray.map { entry ->
            val toolObj = entry as? JsonObject ?: return null
            val name = toolObj["name"]?.asNonEmptyStrictStringOrNull() ?: return null
            val description = toolObj["description"]?.asStrictStringOrNull() ?: return null
            val schemaEl = toolObj["inputSchema"]
            val inputSchemaJson =
                if (schemaEl == null || schemaEl is JsonNull) {
                    null
                } else {
                    contextViewPrettyJson.encodeToString(JsonElement.serializer(), schemaEl)
                }
            AgentContextViewTool(name = name, description = description, inputSchemaJson = inputSchemaJson)
        }

    val messagesArray = obj["messages"] as? JsonArray ?: return null
    val messages =
        messagesArray.map { entry ->
            val msgObj = entry as? JsonObject ?: return null
            val roleRaw = msgObj["role"]?.asStrictStringOrNull() ?: return null
            val role = AgentContextViewMessageRole.fromWire(roleRaw) ?: return null
            val partsArray = msgObj["parts"] as? JsonArray ?: return null
            val parts = partsArray.map { partEl -> parsePart(partEl) ?: return null }
            AgentContextViewMessage(role = role, parts = parts)
        }

    val compaction = parseCompaction(obj["compaction"]) ?: return null

    val triggerEl = obj["trigger"]
    val trigger: AgentContextViewTrigger? =
        if (triggerEl == null || triggerEl is JsonNull) {
            null
        } else {
            val tObj = triggerEl as? JsonObject ?: return null
            val messageIndex = tObj["messageIndex"]?.asNonNegativeIntOrNull() ?: return null
            val partIndex = tObj["partIndex"]?.asNonNegativeIntOrNull() ?: return null
            val toolUseIdBox = tObj.readNullableStrictString("toolUseId") ?: return null
            val targetPart = messages.getOrNull(messageIndex)?.parts?.getOrNull(partIndex) as? AgentContextViewPart.ToolUse
                ?: return null
            if (toolUseIdBox.value != null && targetPart.toolUseId != toolUseIdBox.value) return null
            AgentContextViewTrigger(
                messageIndex = messageIndex,
                partIndex = partIndex,
                toolUseId = toolUseIdBox.value,
            )
        }

    val redactions = parseRedactions(obj["redactions"]) ?: return null

    val sourceObj = obj["source"] as? JsonObject ?: return null
    val sourceKindRaw = sourceObj["kind"]?.asStrictStringOrNull() ?: return null
    val sourceKind = AgentContextViewSourceKind.fromWire(sourceKindRaw) ?: return null
    val sourceFormat = sourceObj["format"]?.asNonEmptyStrictStringOrNull() ?: return null
    val sourceVersion = sourceObj.readNullableStrictString("version") ?: return null

    val verificationEl = obj["verification"]
    val verification =
        if (verificationEl == null || verificationEl is JsonNull) {
            null
        } else {
            val rawVer = verificationEl.asStrictStringOrNull()
            AgentContextViewVerification.fromWire(rawVer) ?: run {
                if (rawVer != null) {
                    reportUnknownTypeDropped(
                        source = "agent_context_view",
                        field = "verification",
                        value = rawVer,
                    )
                }
                null
            }
        }

    return AgentContextView(
        runtime = runtime,
        model = model.value,
        sessionId = sessionId,
        requestAt = requestAt.value,
        messageId = messageId,
        tokens =
            AgentContextViewTokens(
                input = inputTokens.value,
                cacheRead = cacheReadTokens.value,
                total = totalTokens.value,
            ),
        system = systemBlocks,
        tools = tools,
        messages = messages,
        compaction = compaction,
        trigger = trigger,
        redactions = redactions,
        source = AgentContextViewSource(kind = sourceKind, format = sourceFormat, version = sourceVersion.value),
        verification = verification,
    )
}

private fun parsePart(el: JsonElement): AgentContextViewPart? {
    val obj = el as? JsonObject ?: return null
    val kind = obj["kind"]?.asNonEmptyStrictStringOrNull() ?: return null
    return when (kind) {
        "text" -> {
            val text = obj["text"]?.asStrictStringOrNull() ?: return null
            AgentContextViewPart.Text(text = text)
        }
        "thinking" -> {
            val text = obj.readNullableStrictString("text") ?: return null
            AgentContextViewPart.Thinking(text = text.value)
        }
        "tool_use" -> {
            val toolName = obj["toolName"]?.asNonEmptyStrictStringOrNull() ?: return null
            val toolUseId = obj.readNullableStrictString("toolUseId") ?: return null
            val inputEl = obj["input"] ?: return null
            val flatArgs = extractFlatArguments(inputEl)
            val inputJson =
                if (flatArgs != null) {
                    flatArgs.joinToString("\n") { (k, v) -> "$k: $v" }
                } else {
                    contextViewPrettyJson.encodeToString(JsonElement.serializer(), inputEl)
                }
            AgentContextViewPart.ToolUse(
                toolName = toolName,
                toolUseId = toolUseId.value,
                inputJson = inputJson,
                flatArguments = flatArgs,
                summary = extractToolSummary(inputEl),
            )
        }
        "tool_result" -> {
            val toolName = obj.readNullableStrictString("toolName") ?: return null
            val toolUseId = obj.readNullableStrictString("toolUseId") ?: return null
            val output = obj["output"]?.asStrictStringOrNull() ?: return null
            val isError = obj["isError"]?.asStrictBooleanOrNull() ?: return null
            AgentContextViewPart.ToolResult(
                toolName = toolName.value,
                toolUseId = toolUseId.value,
                output = output,
                isError = isError,
            )
        }
        "image" -> {
            // Contract guard: image bytes never cross this wire contract.
            if ("data" in obj || "bytes" in obj || "base64" in obj) return null
            val mediaType = obj.readNullableStrictString("mediaType") ?: return null
            val sizeBytes = obj.readNullableCount("sizeBytes") ?: return null
            AgentContextViewPart.Image(mediaType = mediaType.value, sizeBytes = sizeBytes.value)
        }
        "compaction" -> {
            val text = obj.readNullableStrictString("text") ?: return null
            AgentContextViewPart.Compaction(text = text.value)
        }
        else -> {
            val original = kind.take(64)
            reportUnknownTypeDropped(
                source = "agent_context_view",
                field = "part.kind",
                value = original,
                fallback = "unknown_part",
            )
            AgentContextViewPart.Unknown(originalKind = original)
        }
    }
}

private fun extractFlatArguments(input: JsonElement): List<Pair<String, String>>? {
    val obj = input as? JsonObject ?: return null
    if (obj.isEmpty()) return null
    val result = mutableListOf<Pair<String, String>>()
    for ((key, value) in obj) {
        when (value) {
            is JsonNull -> result += key to "null"
            is JsonPrimitive -> result += key to value.content
            else -> return null
        }
    }
    return result
}

private fun extractToolSummary(input: JsonElement): String? {
    val raw: String? =
        when (input) {
            is JsonPrimitive -> if (input.isString) input.content else null
            is JsonObject -> {
                val desc = input["description"]?.asStrictStringOrNull()?.takeIf { it.isNotBlank() }
                desc ?: input.values.firstNotNullOfOrNull { it.asStrictStringOrNull()?.takeIf(String::isNotBlank) }
            }
            else -> null
        }
    val line = raw?.replace(Regex("\\s+"), " ")?.trim() ?: return null
    return line.takeIf { it.isNotEmpty() }?.take(TOOL_SUMMARY_MAX_CHARS)
}

private fun parseCompaction(el: JsonElement?): AgentContextViewCompaction? {
    val obj = el as? JsonObject ?: return null
    val happened = obj["happened"]?.asStrictBooleanOrNull() ?: return null
    if (!happened) return AgentContextViewCompaction.None
    val summaryEl = obj["summary"]
    if (summaryEl == null || summaryEl is JsonNull) {
        return AgentContextViewCompaction.Happened(summaryKind = null, summaryText = null)
    }
    val summaryObj = summaryEl as? JsonObject ?: return null
    val kind = summaryObj["kind"]?.asStrictStringOrNull() ?: return null
    return when (kind) {
        "encrypted" -> AgentContextViewCompaction.Happened(summaryKind = "encrypted", summaryText = null)
        "text" -> {
            val text = summaryObj["text"]?.asStrictStringOrNull() ?: return null
            AgentContextViewCompaction.Happened(summaryKind = "text", summaryText = text)
        }
        else -> null
    }
}

private fun parseRedactions(el: JsonElement?): Map<AgentContextViewRedactionKind, Int>? {
    val obj = el as? JsonObject ?: return null
    val counts = linkedMapOf<AgentContextViewRedactionKind, Int>()
    for ((key, value) in obj) {
        val count = value.asNonNegativeIntOrNull() ?: return null
        val kind = AgentContextViewRedactionKind.fromWire(key)
        if (kind == null) {
            reportUnknownTypeDropped(
                source = "agent_context_view",
                field = "redactions",
                value = key.take(64),
            )
            continue
        }
        if (count > 0) {
            counts[kind] = count
        }
    }
    return counts
}

fun parseAgentContextViewRecords(raw: String): List<AgentContextViewRecord>? {
    val root =
        try {
            contextViewJson.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
            ?: return null
    val views = root["views"] as? JsonArray ?: return null
    if (views.isEmpty()) return emptyList()
    val decoded = mutableListOf<AgentContextViewRecord>()
    views.forEachIndexed { index, element ->
        val row = parseRecordElement(element)
        if (row == null) {
            reportProjectionRowDropped(
                source = "agent_context_view_records",
                reason = "decode_failed",
                index = index,
            )
        } else {
            decoded += row
        }
    }
    if (decoded.isEmpty()) return null
    return decoded
}

private fun parseRecordElement(el: JsonElement): AgentContextViewRecord? {
    val obj = el as? JsonObject ?: return null
    val at = obj["at"]?.asNonEmptyStrictStringOrNull() ?: return null
    val outcomeRaw = obj["outcome"]?.asNonEmptyStrictStringOrNull() ?: return null
    val outcome =
        AgentContextViewOutcome.fromWire(outcomeRaw) ?: run {
            reportUnknownTypeDropped(
                source = "agent_context_view_records",
                field = "outcome",
                value = outcomeRaw,
                fallback = "omit_outcome",
            )
            null
        }
    val viewerEl = obj["viewer"]
    val viewer =
        if (viewerEl == null || viewerEl is JsonNull) {
            null
        } else {
            (viewerEl as? JsonObject)?.toNamedRefOrNull() ?: return null
        }
    val viewerIsAgent = obj["viewerKind"]?.asStrictStringOrNull() == "agent"
    val query = (obj["query"] as? JsonObject)?.let(::parseQueryRecord)
    val grant =
        (obj["grant"] as? JsonObject)?.let { grantObj ->
            val grantId = grantObj["id"]?.asNonEmptyStrictStringOrNull() ?: return@let null
            val granter = (grantObj["granter"] as? JsonObject)?.toNamedRefOrNull()
            AgentContextViewRecordGrant(id = grantId, granter = granter)
        }
    return AgentContextViewRecord(
        viewer = viewer,
        at = at,
        outcome = outcome,
        outcomeRaw = outcomeRaw,
        viewerIsAgent = viewerIsAgent,
        query = query,
        grant = grant,
    )
}

private fun parseQueryRecord(obj: JsonObject): AgentContextViewQueryRecord? =
    when (obj["mode"]?.asStrictStringOrNull()) {
        "search" -> {
            val query = obj["query"]?.asStrictStringOrNull() ?: return null
            val matches = obj["matches"]?.asNonNegativeIntOrNull()
            AgentContextViewQueryRecord.Search(query = query, matches = matches)
        }
        "read" -> {
            val around = obj["around"]?.asStrictStringOrNull() ?: return null
            AgentContextViewQueryRecord.Read(around = around)
        }
        else -> null
    }

fun parseAgentContextViewGrantList(raw: String): List<AgentContextViewGrant>? {
    val dto =
        try {
            contextViewJson.decodeFromString(AgentContextViewGrantListWireDto.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    val wireGrants = dto.grants ?: return null
    if (wireGrants.isEmpty()) return emptyList()
    val decoded = mutableListOf<AgentContextViewGrant>()
    wireGrants.forEachIndexed { index, wire ->
        val mapped = wire.toGrantOrNull()
        if (mapped == null) {
            reportProjectionRowDropped(
                source = "agent_context_view_grants",
                reason = "decode_failed",
                index = index,
            )
        } else {
            decoded += mapped
        }
    }
    if (decoded.isEmpty()) return null
    return decoded
}

fun parseAgentContextViewGrantCreated(raw: String): AgentContextViewGrantCreated? {
    val dto =
        try {
            contextViewJson.decodeFromString(AgentContextViewGrantCreatedWireDto.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    val grant = dto.grant?.toGrantOrNull() ?: return null
    val created = dto.created ?: return null
    return AgentContextViewGrantCreated(grant = grant, created = created)
}

fun parseAgentContextViewGrantRevoked(raw: String): Boolean? {
    val dto =
        try {
            contextViewJson.decodeFromString(AgentContextViewGrantRevokedWireDto.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    if (dto.ok != true) return null
    return dto.revoked
}

internal fun parseAgentContextViewGrantRefusal(raw: String?): AgentContextViewGrantRefusal? {
    if (raw.isNullOrBlank()) return null
    return try {
        contextViewJson.decodeFromString(AgentContextViewGrantRefusal.serializer(), raw)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun AgentContextViewGrantWireDto.toGrantOrNull(): AgentContextViewGrant? {
    val grantId = id?.takeIf { it.isNotBlank() } ?: return null
    val agentRef = agent?.toNamedRef() ?: return null
    val grantedAtValue = grantedAt?.takeIf { it.isNotBlank() } ?: return null
    val usableValue = usable ?: true
    val reason =
        if (!usableValue && !unusableReason.isNullOrBlank()) {
            AgentContextViewGrantUnusableReason.fromWire(unusableReason) ?: run {
                reportUnknownTypeDropped(
                    source = "agent_context_view_grants",
                    field = "unusableReason",
                    value = unusableReason,
                    fallback = "inactive",
                )
                null
            }
        } else {
            null
        }
    return AgentContextViewGrant(
        id = grantId,
        agent = agentRef,
        granter = granter?.toNamedRef(),
        grantedAt = grantedAtValue,
        usable = usableValue,
        unusableReason = reason,
    )
}

private data class NullableBox<T>(val value: T?)

private fun JsonElement.asStrictStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonElement.asNonEmptyStrictStringOrNull(): String? =
    asStrictStringOrNull()?.takeIf { it.isNotEmpty() }

private fun JsonElement.asStrictBooleanOrNull(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonElement.asNonNegativeLongOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val value = primitive.longOrNull ?: return null
    return value.takeIf { it >= 0L }
}

private fun JsonElement.asNonNegativeIntOrNull(): Int? =
    asNonNegativeLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt()

private fun JsonObject.readNullableStrictString(key: String): NullableBox<String>? {
    val el = this[key] ?: return null
    if (el is JsonNull) return NullableBox(null)
    val value = el.asStrictStringOrNull() ?: return null
    return NullableBox(value)
}

private fun JsonObject.readNullableIsoString(key: String): NullableBox<String>? {
    val box = readNullableStrictString(key) ?: return null
    val str = box.value ?: return box
    if (str.length > 64 || !ISO_PREFIX_REGEX.containsMatchIn(str)) return null
    return box
}

private fun JsonObject.readNullableCount(key: String): NullableBox<Long>? {
    val el = this[key] ?: return null
    if (el is JsonNull) return NullableBox(null)
    val value = el.asNonNegativeLongOrNull() ?: return null
    return NullableBox(value)
}

private fun JsonObject.toNamedRefOrNull(): AgentContextViewNamedRef? {
    val id = this["id"]?.asNonEmptyStrictStringOrNull() ?: return null
    val name = this["name"]?.asStrictStringOrNull() ?: return null
    return AgentContextViewNamedRef(id = id, name = name)
}

private val ISO_PREFIX_REGEX = Regex("^\\d{4}-\\d{2}-\\d{2}T")
