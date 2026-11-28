package build.raft.app.data

import build.raft.app.isCanonicalIdentityHandle
import build.raft.app.isRaftHandleCodePoint
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

/** Platform Unicode NFKC normalization (`java.text.Normalizer` on JVM, `NSString` on iOS). */
internal expect fun normalizeUnicodeNfkc(text: String): String

// ---------------------------------------------------------------------------
// External sender normalization & formatting (botiverse/slock #17276)
// ---------------------------------------------------------------------------

/** Every character ECMAScript `/\s/` matches. */
private fun Char.isJsWhitespace(): Boolean =
    this == ' ' ||
        this == '\t' ||
        this == '\n' ||
        this == '\u000B' ||
        this == '\u000C' ||
        this == '\r' ||
        this == '\u00A0' ||
        this == '\u1680' ||
        this in '\u2000'..'\u200A' ||
        this == '\u2028' ||
        this == '\u2029' ||
        this == '\u202F' ||
        this == '\u205F' ||
        this == '\u3000' ||
        this == '\uFEFF'

/** Every character matched by slock `AGENT_ONE_LINE_FOLD` (`AGENT_BODY_LINE_SEPARATOR | \s`). */
private fun Char.isAgentOneLineWhitespace(): Boolean =
    isJsWhitespace() ||
        this == '\u001C' ||
        this == '\u001D' ||
        this == '\u001E' ||
        this == '\u0085'

private fun foldAgentOneLineText(text: String): String {
    if (text.isEmpty()) return ""
    val out = StringBuilder(text.length)
    var pendingSpace = false
    var hasContent = false
    for (ch in text) {
        if (ch.isAgentOneLineWhitespace()) {
            if (hasContent) pendingSpace = true
        } else {
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(ch)
            hasContent = true
        }
    }
    return out.toString()
}

private fun isExternalSenderAstralFormatCodePoint(codePoint: Int): Boolean =
    codePoint == 0x110BD ||
        codePoint == 0x110CD ||
        codePoint in 0x13430..0x1343F ||
        codePoint in 0x1BCA0..0x1BCA3 ||
        codePoint in 0x1D173..0x1D17A ||
        codePoint == 0xE0001 ||
        codePoint in 0xE0020..0xE007F

private fun Char.isExternalSenderReserved(): Boolean =
    this == '@' ||
        this == '#' ||
        this == ':' ||
        this == '+' ||
        this == '[' ||
        this == ']' ||
        this == '\u2502' ||
        this == '\u2014' ||
        this == '\u2013' ||
        this == '\u2015'

private fun stripExternalSenderInvisibleAndReserved(text: String): String =
    buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                val low = text[index + 1]
                val codePoint = 0x10000 + ((ch.code - 0xD800) shl 10) + (low.code - 0xDC00)
                if (!isExternalSenderAstralFormatCodePoint(codePoint)) {
                    append(ch)
                    append(low)
                }
                index += 2
            } else {
                val drop =
                    ch.category == CharCategory.CONTROL ||
                        ch.category == CharCategory.FORMAT ||
                        ch.isExternalSenderReserved()
                if (!drop) append(ch)
                index += 1
            }
        }
    }

private fun takeFirstCodePoints(text: String, maxCodePoints: Int): String {
    if (maxCodePoints <= 0 || text.isEmpty()) return ""
    var count = 0
    var index = 0
    while (index < text.length && count < maxCodePoints) {
        val step =
            if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                2
            } else {
                1
            }
        index += step
        count += 1
    }
    return text.substring(0, index)
}

/**
 * Normalizes an external display name to match `slock`'s `normalizeAgentExternalSenderDisplayName`
 * (`#17276`): NFKC -> one-line fold -> strip Unicode Cc/Cf and Raft reserved characters
 * (`@ # : + [ ] │ — – ―`) -> NFKC again -> one-line fold -> cap at 80 Unicode code points ->
 * trim end -> `"unknown"` if empty.
 */
fun normalizeAgentExternalSenderDisplayName(displayName: String): String {
    val firstPass = foldAgentOneLineText(normalizeUnicodeNfkc(displayName))
    val kept = normalizeUnicodeNfkc(stripExternalSenderInvisibleAndReserved(firstPass))
    val folded = foldAgentOneLineText(kept)
    val capped = takeFirstCodePoints(folded, AGENT_EXTERNAL_SENDER_DISPLAY_NAME_MAX).trimEnd { it.isJsWhitespace() }
    return capped.ifEmpty { AGENT_EXTERNAL_SENDER_UNNAMED }
}

/** `slack` for Slack, `external` for every other provider (`agentExternalSenderSourceOf`). */
fun agentExternalSenderSourceOf(provider: String?): AgentExternalSenderSource =
    if (provider == "slack") AgentExternalSenderSource.Slack else AgentExternalSenderSource.External

/** Builds a normalized [AgentExternalSender] (`toAgentExternalSender`). */
fun toAgentExternalSender(provider: String?, displayName: String): AgentExternalSender =
    AgentExternalSender(
        source = agentExternalSenderSourceOf(provider),
        displayName = normalizeAgentExternalSenderDisplayName(displayName),
    )

/** Narrows a wire external sender `(source, displayName)` to a known source + normalized name (`parseAgentExternalSender`). */
fun parseAgentExternalSender(source: String?, displayName: String?): AgentExternalSender? {
    val known = AgentExternalSenderSource.fromWire(source) ?: return null
    if (displayName == null) return null
    return AgentExternalSender(
        source = known,
        displayName = normalizeAgentExternalSenderDisplayName(displayName),
    )
}

/** `+<source>:<displayName>` (`formatAgentExternalSenderName`). */
fun formatAgentExternalSenderName(sender: AgentExternalSender): String =
    "+${sender.source.wireValue}:${sender.displayName}"

/** Quotes free text on one line as a JSON string with NEL/LS/PS escaped (`quoteAgentOneLineText`). */
fun quoteAgentOneLineText(text: String): String {
    val encoded = contextViewJson.encodeToString(JsonElement.serializer(), JsonPrimitive(text))
    return buildString(encoded.length) {
        for (ch in encoded) {
            when (ch) {
                '\u0085' -> append("\\u0085")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(ch)
            }
        }
    }
}

/** `slack:"Cindy Z"` / `external:"Pat"` (`formatAgentExternalSenderText`). */
fun formatAgentExternalSenderText(sender: AgentExternalSender): String =
    "${sender.source.wireValue}:${quoteAgentOneLineText(sender.displayName)}"

// ---------------------------------------------------------------------------
// Raft quoted blocks (botiverse/slock #17300, packages/shared/src/agentText/raftBlocks.ts)
// ---------------------------------------------------------------------------

private const val RAFT_BLOCK_TAG_NAMES =
    "objects-in-play|object|recent-messages|recent-actions|last-notes|last-note|turn-note|open-todos|memory-index|interrupted-turn|raft-standing-prompt-update"

private val RAFT_BLOCK_TAG_LINE_RE = Regex("^</?(?:$RAFT_BLOCK_TAG_NAMES)(?![\\w-])")
private val RAFT_BLOCK_OPEN_LINE_RE = Regex("^<($RAFT_BLOCK_TAG_NAMES)(?=[\\s>])")

/**
 * Returns which lines stand inside one of Raft's own quoted blocks (tag lines included), matching
 * `slock` `linesInsideRaftBlocks`. Each block runs to the LAST closing tag line of that name (or
 * to the end when unclosed).
 */
fun linesInsideRaftBlocks(lines: List<String>): BooleanArray {
    val inside = BooleanArray(lines.size)
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val name = RAFT_BLOCK_OPEN_LINE_RE.find(line)?.groups?.get(1)?.value
        if (name == null) {
            i += 1
            continue
        }
        var end = i
        if (!line.trimEnd { it.isJsWhitespace() }.endsWith("/>")) {
            val closing = "</$name>"
            end = lines.lastIndex
            for (j in lines.lastIndex downTo i + 1) {
                if (lines[j].startsWith(closing)) {
                    end = j
                    break
                }
            }
        }
        for (j in i..end) inside[j] = true
        i = end + 1
    }
    return inside
}

// ---------------------------------------------------------------------------
// Attached frames (botiverse/slock #17306, #17311, packages/shared/src/agentText/attachedFrames.ts)
// ---------------------------------------------------------------------------

/** Every character a universal-newline reader breaks a line on (`RAFT_FRAME_BREAKS`). */
val RAFT_FRAME_BREAKS: Set<Int> =
    setOf(0x0A, 0x0D, 0x0B, 0x0C, 0x1C, 0x1D, 0x1E, 0x85, 0x2028, 0x2029)

private const val RAFT_FRAME_GUTTER_CHAR: Char = '\u2502'

private val RAFT_MESSAGE_HEADER_STRICT_RE =
    Regex(
        "^\\[target=[^ \\t\\n\\r\\u000B\\u000C\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+ " +
            "msg=[0-9a-f]{8} " +
            "time=\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}Z " +
            "type=(?:human|agent|system|third_party_app)(?: [^\\]\\n]*)?\\] ",
    )

private val RAFT_FRAME_KIND_RE = Regex("^[a-z][a-z0-9_-]*$")
private val RAFT_FRAME_KEY_RE = Regex("^[a-z][a-z0-9_]*$")
private val RAFT_FRAME_ATTACHMENT_ID_RE =
    Regex("^(?:[0-9a-f]{8}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")
private val RAFT_FRAME_TASK_STATUS_RE = Regex("^[a-z][a-z_]*$")
private val RAFT_FRAME_DM_PEER_RE = Regex("^[A-Za-z0-9_.-]+(?:~(?:[Aa][Gg][Ee][Nn][Tt]|[Hh][Uu][Mm][Aa][Nn]))?$")

private const val RAFT_FRAME_REPLY_SENTENCE_PREFIX = "Reply: raft task reply \""

private fun raftLineIsUnsafe(line: String): Boolean {
    for (ch in line) {
        if (ch == RAFT_FRAME_GUTTER_CHAR || ch.code in RAFT_FRAME_BREAKS) return true
    }
    return false
}

/**
 * Whether [line] is a strict column-0 Raft message header carrying `frames=1` (`#17311`).
 * Attached frames are NEVER read unless this returns `true`.
 */
fun raftMessageHeaderHasFrames(line: String): Boolean {
    val header = RAFT_MESSAGE_HEADER_STRICT_RE.find(line)?.value ?: return false
    if (header.indexOf(']') != header.length - 2) return false
    if (raftLineIsUnsafe(header)) return false
    val fields = header.substring(1, header.length - 2).split(' ')
    if (fields.any { it.isEmpty() }) return false
    val prefix = "$RAFT_MESSAGE_FRAMES_FIELD="
    val named = fields.filter { it.startsWith(prefix) }
    return named.size == 1 && named[0] == RAFT_MESSAGE_FRAMES_MARKER
}

/** Splits [text] at every break code point in [RAFT_FRAME_BREAKS]; drops nothing. */
fun splitRaftFrameLines(text: String): List<String> {
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    for (ch in text) {
        if (ch.code in RAFT_FRAME_BREAKS) {
            lines += current.toString()
            current.clear()
        } else {
            current.append(ch)
        }
    }
    lines += current.toString()
    return lines
}

/** Strips leading `│` (plus at most one space after it), or returns null if [line] is not content. */
fun stripRaftFrameGutter(line: String): String? {
    if (!line.startsWith(RAFT_FRAME_GUTTER)) return null
    return if (line.startsWith(RAFT_FRAME_CONTENT_PREFIX)) {
        line.substring(RAFT_FRAME_CONTENT_PREFIX.length)
    } else {
        line.substring(RAFT_FRAME_GUTTER.length)
    }
}

private fun isRaftFrameValueValid(value: String): Boolean {
    if (value.isEmpty()) return false
    for (ch in value) {
        if (
            ch == ']' ||
            ch == '=' ||
            ch == RAFT_FRAME_GUTTER_CHAR ||
            ch.code in RAFT_FRAME_BREAKS ||
            ch.isJsWhitespace()
        ) {
            return false
        }
    }
    return true
}

/** Parses a whole column-0 `[raft <kind> key=value ...]` line, or returns null on any invalid shape or duplicate key. */
fun parseRaftFrameHeader(line: String): RaftFrameHeader? {
    if (!line.startsWith("[raft ") || !line.endsWith("]")) return null
    val inner = line.substring(6, line.length - 1)
    if (inner.isEmpty()) return null
    val parts = inner.split(' ')
    val kind = parts[0]
    if (!RAFT_FRAME_KIND_RE.matches(kind)) return null
    val fields = linkedMapOf<String, String>()
    for (i in 1 until parts.size) {
        val token = parts[i]
        if (token.isEmpty()) return null
        val eq = token.indexOf('=')
        if (eq <= 0 || eq == token.lastIndex) return null
        val key = token.substring(0, eq)
        val value = token.substring(eq + 1)
        if (!RAFT_FRAME_KEY_RE.matches(key) || !isRaftFrameValueValid(value)) return null
        if (key in fields) return null
        fields[key] = value
    }
    return RaftFrameHeader(kind = kind, fields = fields)
}

/** Formats `[raft <kind> ...]` if every field round-trips cleanly through [parseRaftFrameHeader], else null. */
fun formatRaftFrameHeader(
    kind: String,
    fields: List<Pair<String, String>>,
): String? {
    val line =
        buildString {
            append("[raft ")
            append(kind)
            for ((k, v) in fields) {
                append(' ')
                append(k)
                append('=')
                append(v)
            }
            append(']')
        }
    val parsed = parseRaftFrameHeader(line) ?: return null
    return if (parsed.kind == kind && parsed.fields.size == fields.size) line else null
}

/** Formats one attached-frame content line (`│` alone for an empty line). */
fun formatRaftFrameContentLine(line: String): String =
    if (line.isEmpty()) RAFT_FRAME_GUTTER else "$RAFT_FRAME_CONTENT_PREFIX$line"

/** `@handle`, where `handle` matches `NAME_REGEX` (`isCanonicalIdentityHandle`). */
fun isRaftFrameHandle(value: String): Boolean =
    value.startsWith("@") && value.substring(1).isCanonicalIdentityHandle()

private fun String.allCodePointsMatch(predicate: (Int) -> Boolean): Boolean {
    if (isEmpty()) return false
    var index = 0
    while (index < length) {
        val first = this[index]
        val codePoint: Int
        val width: Int
        when {
            first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                codePoint = 0x10000 + ((first.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00)
                width = 2
            }
            first.isHighSurrogate() || first.isLowSurrogate() -> return false
            else -> {
                codePoint = first.code
                width = 1
            }
        }
        if (!predicate(codePoint)) return false
        index += width
    }
    return true
}

private fun isCanonicalPositiveTaskNumber(raw: String): Boolean {
    if (raw.isEmpty() || raw[0] !in '1'..'9') return false
    for (ch in raw) {
        if (ch !in '0'..'9') return false
    }
    val num = raw.toLongOrNull() ?: return false
    return num > 0L
}

/**
 * Whether [value] is a canonical conversation-qualified task ref (`#channel:#7`, `dm:@peer:#7`,
 * `dm:@peer~agent:#7`, `lounge:@agent:#7`), matching `parseAgentTaskRef(value)?.ref === value`.
 */
fun isRaftFrameTaskRef(value: String): Boolean {
    if (value.isEmpty() || value != value.trim { it.isJsWhitespace() }) return false
    return when {
        value.startsWith("lounge:@") -> {
            val sep = value.indexOf(":#", startIndex = 8)
            if (sep <= 8) return false
            val agentName = value.substring(8, sep)
            val number = value.substring(sep + 2)
            agentName.allCodePointsMatch { it.isRaftHandleCodePoint() } && isCanonicalPositiveTaskNumber(number)
        }
        value.startsWith("dm:@") -> {
            val sep = value.indexOf(":#", startIndex = 4)
            if (sep <= 4) return false
            val peerName = value.substring(4, sep)
            val number = value.substring(sep + 2)
            RAFT_FRAME_DM_PEER_RE.matches(peerName) && isCanonicalPositiveTaskNumber(number)
        }
        value.startsWith("#") -> {
            val sep = value.indexOf(":#", startIndex = 1)
            if (sep <= 1) return false
            val channelName = value.substring(1, sep)
            val number = value.substring(sep + 2)
            channelName.allCodePointsMatch { it.isRaftHandleCodePoint() } && isCanonicalPositiveTaskNumber(number)
        }
        else -> false
    }
}

private fun parseRaftFrameReplySentenceRef(line: String): String? {
    if (!line.startsWith(RAFT_FRAME_REPLY_SENTENCE_PREFIX) || !line.endsWith("\"")) return null
    if (raftLineIsUnsafe(line)) return null
    val ref = line.substring(RAFT_FRAME_REPLY_SENTENCE_PREFIX.length, line.length - 1)
    if (ref.isEmpty()) return null
    for (ch in ref) {
        if (ch == '"' || ch.isJsWhitespace()) return null
    }
    return ref
}

/** Whether [line] starts an attached-frame run (a frame header or the canonical CLI reply sentence). */
fun startsRaftAttached(line: String): Boolean {
    if (stripRaftFrameGutter(line) != null) return false
    return parseRaftFrameHeader(line) != null || parseRaftFrameReplySentenceRef(line) != null
}

/**
 * Reads a contiguous run of attached frames and sentences starting at [from] (under a header with
 * `frames=1`), stopping at the first non-frame line or [until]. Mirrors `slock` `readRaftAttached`.
 */
fun readRaftAttached(
    lines: List<String>,
    from: Int,
    sender: String?,
    until: Int = lines.size,
    into: RaftAttached? = null,
): RaftAttachedRun? {
    if (from >= until || !startsRaftAttached(lines[from])) return null
    var about = into?.about
    val attachments = into?.attachments?.toMutableList() ?: mutableListOf()
    val tasks = into?.tasks?.toMutableList() ?: mutableListOf()
    var replyRef = into?.replyRef
    val values = into?.values?.toMutableList() ?: mutableListOf()
    val skipped = into?.skipped?.toMutableList() ?: mutableListOf()
    val units = mutableListOf<RaftAttachedUnit>()
    var attachmentAbove = false
    var at = from
    while (at < until) {
        val line = lines[at]
        if (stripRaftFrameGutter(line) != null) break
        val header = parseRaftFrameHeader(line)
        if (header == null) {
            if (raftLineIsUnsafe(line)) break
            if (line.startsWith(RAFT_FRAME_REPLY_LABEL)) {
                val reply = parseRaftFrameReplySentenceRef(line)
                if (reply != null && isRaftFrameTaskRef(reply) && replyRef == null) {
                    replyRef = reply
                }
                units += RaftAttachedUnit(start = at, end = at + 1)
            } else if (attachmentAbove && line.startsWith(RAFT_FRAME_DOWNLOAD_LABEL)) {
                val prev = units.last()
                units[units.lastIndex] = prev.copy(end = at + 1)
            } else {
                break
            }
            attachmentAbove = false
            at += 1
            continue
        }
        val content = mutableListOf<String>()
        var next = at + 1
        while (next < until) {
            val text = stripRaftFrameGutter(lines[next]) ?: break
            content += text
            next += 1
        }
        units += RaftAttachedUnit(start = at, end = next)
        at = next
        attachmentAbove = header.kind == "attachment"
        val who = header.fields["who"]
        val id = header.fields["id"]
        val ref = header.fields["ref"]
        val status = header.fields["status"]
        val assignee = header.fields["assignee"]
        val key = header.fields["key"]
        when {
            header.kind == "about" &&
                about == null &&
                who != null &&
                isRaftFrameHandle(who) &&
                who == sender &&
                header.fields["by"] == "self" -> {
                about = RaftAttachedAbout(who = who, description = content.joinToString("\n"))
            }
            header.kind == "attachment" &&
                id != null &&
                RAFT_FRAME_ATTACHMENT_ID_RE.matches(id) &&
                content.isNotEmpty() -> {
                attachments += RaftAttachedAttachment(id = id, filename = content.joinToString("\n"))
            }
            header.kind == "task" &&
                ref != null &&
                isRaftFrameTaskRef(ref) &&
                status != null &&
                RAFT_FRAME_TASK_STATUS_RE.matches(status) &&
                (assignee == null || isRaftFrameHandle(assignee)) -> {
                tasks += RaftAttachedTask(ref = ref, status = status, assignee = assignee)
            }
            header.kind == "value" && key != null -> {
                values += RaftAttachedValue(key = key, text = content.joinToString("\n"))
            }
            else -> {
                skipped += header.kind
            }
        }
    }
    return RaftAttachedRun(
        attached =
            RaftAttached(
                about = about,
                attachments = attachments,
                tasks = tasks,
                replyRef = replyRef,
                values = values,
                skipped = skipped,
            ),
        units = units,
        end = at,
    )
}

// ---------------------------------------------------------------------------
// Raft delivery parser (botiverse/slock packages/shared/src/agentText/raftDeliveryParse.ts)
// ---------------------------------------------------------------------------

private const val MAX_NOTICE_LINES = 2_000
private const val MAX_NOTICE_CHARS = 64 * 1024

private data class DeliveryLine(
    val start: Int,
    val text: String,
) {
    val end: Int get() = start + text.length
}

private class DeliveryScan(
    val text: String,
    val lines: List<DeliveryLine>,
    val texts: List<String>,
    val nextOpener: IntArray,
    val inBlock: BooleanArray,
)

private data class DeliveryFound(
    val segment: RaftNoticeSegment.Notice,
    val next: Int,
)

private class MutableInboxTarget(
    val target: String,
    val pending: Int?,
    var latestSender: String?,
    var latestExternalSender: AgentExternalSender?,
    var preview: String?,
    val mentioned: Boolean,
) {
    fun toImmutable(): RaftInboxTarget =
        RaftInboxTarget(
            target = target,
            pending = pending,
            latestSender = latestSender,
            latestExternalSender = latestExternalSender,
            preview = preview,
            mentioned = mentioned,
        )
}

private enum class PayloadJsonState {
    None,
    Label,
    Open,
}

private const val EXTERNAL_SOURCE_PATTERN = "(?:slack|external)"
private const val EXTERNAL_SENDER_SOURCE_PATTERN = "($EXTERNAL_SOURCE_PATTERN:\"(?:[^\"\\\\]|\\\\.)*\")"
private const val EXTERNAL_SENDER_PLAIN_PATTERN = "@\\+($EXTERNAL_SOURCE_PATTERN:[^:]+?)"

private val DELIVERY_GENERIC_LABEL_RE = Regex("^\\[Raft ([^:\\]]+)")
private val DELIVERY_INBOX_HEADER_RE = Regex("^Inbox update: (?:(\\d+) messages? pending)?")
private val DELIVERY_INBOX_ROW_RE = Regex("^(\\S.*?) {2}pending: (\\d+) messages?(.*)$")
private val DELIVERY_INBOX_PREVIEW_RE = Regex("^ {2}latest(?: @([^:]+))?: (\"(?:[^\"\\\\]|\\\\.)*\")")
private val DELIVERY_INBOX_PREVIEW_EXTERNAL_RE =
    Regex("^ {2}latest (?:$EXTERNAL_SENDER_SOURCE_PATTERN|$EXTERNAL_SENDER_PLAIN_PATTERN): (\"(?:[^\"\\\\]|\\\\.)*\")")
private val DELIVERY_INBOX_LATEST_EXTERNAL_RE =
    Regex("(?:^| \u00B7 | )latest (?:sender )?(?:$EXTERNAL_SENDER_SOURCE_PATTERN|$EXTERNAL_SENDER_PLAIN_PATTERN(?= \u00B7 |$))")
private val DELIVERY_INBOX_LATEST_SENDER_RE = Regex(" latest sender @(\\S+)")
private val DELIVERY_INBOX_ROW_V2_RE = Regex("^(\\S[^\u00B7]*?) \u00B7 (.+)$")
private val DELIVERY_INBOX_PREVIEW_V2_RE = Regex("^ {2}(\"(?:[^\"\\\\]|\\\\.)*\") \\((?:truncated )?preview\\)")
private val DELIVERY_INBOX_V2_LATEST_RE = Regex("^latest @(\\S+)(?: \\(agent\\))?$")
private val DELIVERY_INBOX_V2_UNREAD_RE = Regex("^(\\d+) unread$")
private val DELIVERY_INBOX_V2_NEW_RE = Regex("^(\\d+) new, not yet delivered$")
private val DELIVERY_INBOX_V2_PENDING_RE = Regex("^(\\d+) pending$")
private val DELIVERY_INBOX_V2_NOT_IN_LIST_RE = Regex("^(\\d+) not in this list$")
private val DELIVERY_INBOX_V2_BELOW_RE = Regex("^(\\d+) messages? below$")
private val DELIVERY_INBOX_V2_MENTION_RE = Regex("^mentions you(?: \\d+x)?$")
private val DELIVERY_INBOX_OPENER_RE =
    Regex(
        "^\\[Raft inbox notice: (?:" +
            "(new since your last notice|unread from while you were away), in \\d+ targets?" +
            "(?:; (?:a person is waiting on you in 1 conversation|people are waiting on you in \\d+ conversations))?" +
            "|nothing new(?:, \\d+ tasks? waiting on you)?(?:, \\d+ reminders? not acked)?)$",
    )
private val DELIVERY_INBOX_OLDER_RE = Regex("^Older unread: (\\d+) messages? in (\\d+) targets?\\.")
private val DELIVERY_INBOX_NOT_A_ROW_RE =
    Regex("^(?:Waiting on you, longest first:|Everything else, newest first:|\\d+ more conversations? where (?:a person is|people are) waiting: .*)$")
private val DELIVERY_TASK_HEADER_RE =
    Regex("^\\[Raft task notice: (?:(\\d+) tasks? (?:is|are) waiting on you(?:; (\\d+) you created (?:has|have) no assignee)?|(\\d+) tasks? you created (?:has|have) no assignee)\\.")
private val DELIVERY_TASK_LINE_RE = Regex("^(\\S*#\\d+) \\[([a-z_]+)\\] (.*?)(?: \u2014 (.*))?$")
private val DELIVERY_TASK_END_RE = Regex("no reply needed\\.\\]\\s*$")
private val DELIVERY_MESSAGE_HEADER_RE = Regex("^\\[(target=[^\\]]*)\\] ?(.*)$")
private val DELIVERY_SENDER_RE = Regex("^@([^\\s:]+)(?: \\([^)]*\\))?(?: \u2014 (.*)|: ?(.*))$")
private val DELIVERY_SENDER_EXTERNAL_RE = Regex("^$EXTERNAL_SENDER_SOURCE_PATTERN(?: \\([^)]*\\))?(?: \u2014 (.*)|: ?(.*))$")
private val DELIVERY_SENDER_EXTERNAL_PLAIN_RE = Regex("^$EXTERNAL_SENDER_PLAIN_PATTERN: ?(.*)$")
private val DELIVERY_HEADER_FIELD_RE = Regex("(\\w+)=(.*?)(?= \\w+=|$)")
private val DELIVERY_CATCHUP_SKIPPED_EARLIER_RE = Regex("^\\d+ earlier messages? here weren't sent and now counts? as read\\.")

private fun splitDeliveryLines(text: String): List<DeliveryLine> {
    val lines = mutableListOf<DeliveryLine>()
    var start = 0
    while (true) {
        val newline = text.indexOf('\n', startIndex = start)
        if (newline == -1) {
            lines += DeliveryLine(start = start, text = text.substring(start))
            return lines
        }
        lines += DeliveryLine(start = start, text = text.substring(start, newline))
        start = newline + 1
    }
}

private fun deliveryStartsNotice(line: String): Boolean =
    line.startsWith("[target=") || line.startsWith("[Raft ")

private fun deliveryNoticeLimit(scan: DeliveryScan, index: Int): Int {
    val lines = scan.lines
    val start = lines[index].start
    val cap = minOf(scan.nextOpener[index] - 1, index + MAX_NOTICE_LINES - 1, lines.lastIndex)
    var last = index
    while (last < cap && lines[last + 1].start - start <= MAX_NOTICE_CHARS) {
        last += 1
    }
    return last
}

private fun deliveryClosingBracket(text: String, from: Int, until: Int): Int {
    var depth = 0
    val end = minOf(until, from + MAX_NOTICE_CHARS)
    for (index in from until end) {
        when (text[index]) {
            '[' -> depth += 1
            ']' -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
    }
    return -1
}

private fun deliveryLineAt(lines: List<DeliveryLine>, first: Int, offset: Int): Int {
    var index = first
    while (index + 1 < lines.size && lines[index + 1].start <= offset) {
        index += 1
    }
    return index
}

private fun unquoteDeliveryJsonString(value: String): String? =
    try {
        (contextViewJson.parseToJsonElement(value) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun externalSenderOf(quoted: String?, plain: String? = null): AgentExternalSender? {
    val text = quoted ?: plain ?: return null
    val colon = text.indexOf(':')
    if (colon < 0) return null
    val name =
        if (quoted != null) {
            unquoteDeliveryJsonString(text.substring(colon + 1))
        } else {
            text.substring(colon + 1)
        }
            ?: return null
    return parseAgentExternalSender(text.substring(0, colon), name)
}

private fun parseDeliveryGeneric(scan: DeliveryScan, index: Int): DeliveryFound {
    val line = scan.lines[index]
    val close = deliveryClosingBracket(scan.text, line.start, scan.lines[deliveryNoticeLimit(scan, index)].end)
    val last = if (close == -1) index else deliveryLineAt(scan.lines, index, close)
    val label = DELIVERY_GENERIC_LABEL_RE.find(line.text)?.groups?.get(1)?.value?.trim().orEmpty()
    return DeliveryFound(
        segment = RaftNoticeSegment.Generic(start = line.start, end = scan.lines[last].end, label = label),
        next = last + 1,
    )
}

private fun parseDeliveryInboxRowV2(line: String): MutableInboxTarget? {
    val row = DELIVERY_INBOX_ROW_V2_RE.find(line) ?: return null
    val targetRaw = row.groups[1]?.value ?: return null
    val details = row.groups[2]?.value ?: return null
    val parts = details.split(" \u00B7 ")
    fun count(pattern: Regex): Int? {
        for (part in parts) {
            val match = pattern.find(part)
            if (match != null) return match.groups[1]?.value?.toIntOrNull()
        }
        return null
    }
    val external = DELIVERY_INBOX_LATEST_EXTERNAL_RE.find(details)
    val latestExternal =
        if (external != null) {
            externalSenderOf(external.groups[1]?.value, external.groups[2]?.value)
        } else {
            null
        }
    val latest =
        if (latestExternal != null) {
            null
        } else {
            parts.firstNotNullOfOrNull { part -> DELIVERY_INBOX_V2_LATEST_RE.find(part)?.groups?.get(1)?.value }
        }
    val pending =
        count(DELIVERY_INBOX_V2_UNREAD_RE)
            ?: count(DELIVERY_INBOX_V2_NEW_RE)
            ?: count(DELIVERY_INBOX_V2_PENDING_RE)
            ?: count(DELIVERY_INBOX_V2_NOT_IN_LIST_RE)
            ?: count(DELIVERY_INBOX_V2_BELOW_RE)
    return MutableInboxTarget(
        target = targetRaw.removePrefix("task "),
        pending = pending,
        latestSender = latest,
        latestExternalSender = latestExternal,
        preview = null,
        mentioned = parts.any { DELIVERY_INBOX_V2_MENTION_RE.matches(it) },
    )
}

private fun parseDeliveryInboxWithHeader(
    scan: DeliveryScan,
    index: Int,
    opener: MatchResult,
): DeliveryFound {
    val lines = scan.lines
    val limit = deliveryNoticeLimit(scan, index)
    var close = -1
    for (at in index + 1..limit) {
        if (lines[at].text == "]") {
            close = at
            break
        }
    }
    if (close == -1) return parseDeliveryGeneric(scan, index)
    val targets = mutableListOf<MutableInboxTarget>()
    var older: RaftInboxOlderUnread? = null
    var localSection = false
    var current: MutableInboxTarget? = null
    for (at in index + 1 until close) {
        val text = lines[at].text
        if (text.trim { it.isJsWhitespace() }.isEmpty()) {
            localSection = true
            continue
        }
        if (text.startsWith(" ")) {
            val active = current
            if (active != null) {
                val preview = DELIVERY_INBOX_PREVIEW_V2_RE.find(text)?.groups?.get(1)?.value
                if (preview != null) {
                    active.preview = unquoteDeliveryJsonString(preview)
                }
            }
            continue
        }
        val olderMatch = DELIVERY_INBOX_OLDER_RE.find(text)
        if (olderMatch != null) {
            val msgs = olderMatch.groups[1]?.value?.toIntOrNull()
            val tgts = olderMatch.groups[2]?.value?.toIntOrNull()
            if (msgs != null && tgts != null) {
                older = RaftInboxOlderUnread(messages = msgs, targets = tgts)
            }
            continue
        }
        if (DELIVERY_INBOX_NOT_A_ROW_RE.matches(text)) {
            current = null
            continue
        }
        current =
            parseDeliveryInboxRowV2(text)
                ?: if (localSection) {
                    null
                } else {
                    MutableInboxTarget(
                        target = text.removePrefix("task "),
                        pending = null,
                        latestSender = null,
                        latestExternalSender = null,
                        preview = null,
                        mentioned = false,
                    )
                }
        if (current != null) targets += current
    }
    val reasonStr = opener.groups[1]?.value
    val reason =
        when {
            reasonStr == null -> RaftInboxNoticeReason.Idle
            reasonStr.startsWith("new") -> RaftInboxNoticeReason.New
            else -> RaftInboxNoticeReason.Away
        }
    return DeliveryFound(
        segment =
            RaftNoticeSegment.Inbox(
                start = lines[index].start,
                end = lines[close].end,
                pendingTotal = null,
                targets = targets.map(MutableInboxTarget::toImmutable),
                reason = reason,
                older = older,
            ),
        next = close + 1,
    )
}

private fun parseDeliveryInbox(scan: DeliveryScan, index: Int): DeliveryFound {
    val lines = scan.lines
    val opener = DELIVERY_INBOX_OPENER_RE.find(lines[index].text)
    if (opener != null) return parseDeliveryInboxWithHeader(scan, index, opener)
    val limit = deliveryNoticeLimit(scan, index)
    var close = -1
    for (at in index + 1..limit) {
        if (lines[at].text.trim { it.isJsWhitespace() } == "]") {
            close = at
            break
        }
    }
    val header = if (close == -1) null else DELIVERY_INBOX_HEADER_RE.find(lines.getOrNull(index + 1)?.text.orEmpty())
    if (close == -1 || header == null) return parseDeliveryGeneric(scan, index)
    val targets = mutableListOf<MutableInboxTarget>()
    for (at in index + 2 until close) {
        val lineText = lines[at].text
        val row = DELIVERY_INBOX_ROW_RE.find(lineText)
        if (row != null) {
            val details = row.groups[3]?.value.orEmpty()
            val external = DELIVERY_INBOX_LATEST_EXTERNAL_RE.find(details)
            val latestExternal =
                if (external != null) {
                    externalSenderOf(external.groups[1]?.value, external.groups[2]?.value)
                } else {
                    null
                }
            val latestSender =
                if (latestExternal != null) {
                    null
                } else {
                    DELIVERY_INBOX_LATEST_SENDER_RE.find(details)?.groups?.get(1)?.value
                }
            targets +=
                MutableInboxTarget(
                    target = row.groups[1]!!.value,
                    pending = row.groups[2]!!.value.toIntOrNull(),
                    latestSender = latestSender,
                    latestExternalSender = latestExternal,
                    preview = null,
                    mentioned = details.contains("you were mentioned"),
                )
            continue
        }
        val rowV2 = parseDeliveryInboxRowV2(lineText)
        if (rowV2 != null) {
            targets += rowV2
            continue
        }
        val previewV2 = DELIVERY_INBOX_PREVIEW_V2_RE.find(lineText)
        if (previewV2 != null && targets.isNotEmpty()) {
            targets.last().preview = unquoteDeliveryJsonString(previewV2.groups[1]!!.value)
            continue
        }
        val externalPreview = DELIVERY_INBOX_PREVIEW_EXTERNAL_RE.find(lineText)
        if (externalPreview != null && targets.isNotEmpty()) {
            val targetRow = targets.last()
            targetRow.preview = unquoteDeliveryJsonString(externalPreview.groups[3]!!.value)
            val previewSender =
                targetRow.latestExternalSender
                    ?: externalSenderOf(externalPreview.groups[1]?.value, externalPreview.groups[2]?.value)
            if (previewSender != null) {
                targetRow.latestSender = null
                targetRow.latestExternalSender = previewSender
            }
            continue
        }
        val preview = DELIVERY_INBOX_PREVIEW_RE.find(lineText)
        val lastTarget = targets.lastOrNull()
        if (preview != null && lastTarget != null) {
            lastTarget.preview = unquoteDeliveryJsonString(preview.groups[2]!!.value)
            if (lastTarget.latestSender == null) {
                lastTarget.latestSender = preview.groups[1]?.value?.trim()
            }
        }
    }
    val guidance = lines.getOrNull(close + 1)?.text.orEmpty()
    val last =
        if (
            guidance.startsWith("These messages have not been read") ||
            guidance.contains("raft inbox read --target <item>")
        ) {
            close + 1
        } else {
            close
        }
    return DeliveryFound(
        segment =
            RaftNoticeSegment.Inbox(
                start = lines[index].start,
                end = lines[last].end,
                pendingTotal = header.groups[1]?.value?.toIntOrNull(),
                targets = targets.map(MutableInboxTarget::toImmutable),
            ),
        next = last + 1,
    )
}

private fun parseDeliveryTask(scan: DeliveryScan, index: Int): DeliveryFound {
    val lines = scan.lines
    val limit = deliveryNoticeLimit(scan, index)
    var last = -1
    for (at in index..limit) {
        if (DELIVERY_TASK_END_RE.containsMatchIn(lines[at].text)) {
            last = at
            break
        }
    }
    if (last == -1) {
        val close = deliveryClosingBracket(scan.text, lines[index].start, lines[limit].end)
        if (close != -1) last = deliveryLineAt(lines, index, close)
    }
    val header = DELIVERY_TASK_HEADER_RE.find(lines[index].text)
    val tasks = mutableListOf<RaftTaskItem>()
    if (last != -1) {
        for (at in index + 1..last) {
            val item = DELIVERY_TASK_LINE_RE.find(lines[at].text)
            if (item != null) {
                tasks +=
                    RaftTaskItem(
                        ref = item.groups[1]!!.value,
                        status = item.groups[2]!!.value,
                        title = item.groups[3]!!.value,
                        why = item.groups[4]?.value,
                    )
            }
        }
    }
    if (last == -1 || (header == null && tasks.isEmpty())) return parseDeliveryGeneric(scan, index)
    val count =
        if (header != null) {
            (header.groups[1]?.value?.toIntOrNull() ?: 0) +
                (header.groups[2]?.value?.toIntOrNull() ?: 0) +
                (header.groups[3]?.value?.toIntOrNull() ?: 0)
        } else {
            null
        }
    return DeliveryFound(
        segment =
            RaftNoticeSegment.Task(
                start = lines[index].start,
                end = lines[last].end,
                count = count,
                tasks = tasks,
            ),
        next = last + 1,
    )
}

private fun parseDeliveryHeaderFields(source: String): List<RaftField> =
    DELIVERY_HEADER_FIELD_RE.findAll(source)
        .map { match -> RaftField(key = match.groups[1]!!.value, value = match.groups[2]!!.value) }
        .toList()

private fun parseDeliveryMessage(scan: DeliveryScan, index: Int): DeliveryFound? {
    val lines = scan.lines
    val line = lines[index]
    val header = DELIVERY_MESSAGE_HEADER_RE.find(line.text) ?: return null
    var last = index
    while (last + 1 < lines.size) {
        val next = lines[last + 1].text
        if (
            next.trim { it.isJsWhitespace() }.isEmpty() ||
            scan.inBlock[last + 1] ||
            deliveryStartsNotice(next) ||
            next.startsWith("Respond as appropriate") ||
            DELIVERY_CATCHUP_SKIPPED_EARLIER_RE.containsMatchIn(next)
        ) {
            break
        }
        last += 1
    }
    val fields = parseDeliveryHeaderFields(header.groups[1]!!.value)
    val target = fields.firstOrNull { it.key == "target" }?.value
    val time = fields.firstOrNull { it.key == "time" }?.value
    val rest = header.groups[2]?.value.orEmpty()
    val external = DELIVERY_SENDER_EXTERNAL_RE.find(rest)
    val plain = if (external != null) null else DELIVERY_SENDER_EXTERNAL_PLAIN_RE.find(rest)
    val externalSender =
        when {
            external != null -> externalSenderOf(external.groups[1]?.value)
            plain != null -> externalSenderOf(null, plain.groups[1]?.value)
            else -> null
        }
    val senderMatch = if (externalSender != null) null else DELIVERY_SENDER_RE.find(rest)
    val senderHandle = senderMatch?.groups?.get(1)?.value
    val externalBody =
        when {
            external != null -> external.groups[2]?.value ?: external.groups[3]?.value ?: ""
            plain != null -> plain.groups[2]?.value ?: ""
            else -> null
        }
    val firstBodyLine =
        when {
            externalSender != null && externalBody != null -> externalBody
            senderMatch != null -> senderMatch.groups[2]?.value ?: senderMatch.groups[3]?.value ?: ""
            else -> rest
        }
    val bodyLines = mutableListOf(firstBodyLine)
    var attached: RaftAttached? = null
    // The header alone (`frames=1`) decides whether attached frames are read (#17311).
    val framed = raftMessageHeaderHasFrames(line.text)
    var payloadState = PayloadJsonState.None
    var at = index + 1
    while (at <= last) {
        val run =
            if (framed && payloadState != PayloadJsonState.Open) {
                readRaftAttached(
                    lines = scan.texts,
                    from = at,
                    sender = senderHandle?.let { "@$it" },
                    until = last + 1,
                    into = attached,
                )
            } else {
                null
            }
        if (run != null) {
            attached = run.attached
            at = run.end
            payloadState = PayloadJsonState.None
            continue
        }
        val text = lines[at].text
        payloadState =
            when {
                payloadState == PayloadJsonState.Label && text == "{" -> PayloadJsonState.Open
                payloadState == PayloadJsonState.Open && text.startsWith("  ") -> PayloadJsonState.Open
                text == "payload:" -> PayloadJsonState.Label
                else -> PayloadJsonState.None
            }
        bodyLines +=
            if (text.startsWith(AGENT_BODY_CONTINUATION_PREFIX)) {
                text.substring(AGENT_BODY_CONTINUATION_PREFIX.length)
            } else {
                text
            }
        at += 1
    }
    return DeliveryFound(
        segment =
            RaftNoticeSegment.Message(
                start = line.start,
                end = lines[last].end,
                fields = fields,
                target = target,
                time = time,
                sender = senderHandle,
                externalSender = externalSender,
                body = bodyLines.joinToString("\n"),
                attached = attached,
            ),
        next = last + 1,
    )
}

private fun parseDeliveryNoticeAt(scan: DeliveryScan, index: Int): DeliveryFound? {
    if (scan.inBlock[index]) return null
    val line = scan.lines[index].text
    return when {
        line.startsWith("[target=") -> parseDeliveryMessage(scan, index)
        line.startsWith("[Raft inbox notice:") -> parseDeliveryInbox(scan, index)
        line.startsWith("[Raft task notice:") -> parseDeliveryTask(scan, index)
        line.startsWith("[Raft ") -> parseDeliveryGeneric(scan, index)
        else -> null
    }
}

/**
 * Parses what Raft delivered into a user message into structured notices and surrounding envelope
 * text segments, skipping Raft's own quoted blocks (`#17300`), reading external authors into
 * `externalSender` (`#17276`), and reading attached frames strictly under `frames=1` (`#17306`,
 * `#17311`). Returns null when [text] contains no Raft notices.
 */
fun parseRaftDelivery(text: String): RaftDelivery? {
    if (!text.contains("[target=") && !text.contains("[Raft ")) return null
    val lines = splitDeliveryLines(text)
    val texts = lines.map(DeliveryLine::text)
    val inBlock = linesInsideRaftBlocks(texts)
    for (index in lines.indices) {
        if (RAFT_BLOCK_TAG_LINE_RE.containsMatchIn(lines[index].text)) {
            inBlock[index] = true
        }
    }
    val nextOpener = IntArray(lines.size)
    var next = lines.size
    for (index in lines.lastIndex downTo 0) {
        nextOpener[index] = next
        if (inBlock[index] || deliveryStartsNotice(lines[index].text)) {
            next = index
        }
    }
    val scan = DeliveryScan(text = text, lines = lines, texts = texts, nextOpener = nextOpener, inBlock = inBlock)
    val segments = mutableListOf<RaftNoticeSegment>()
    var textStart: Int? = null
    fun flush(until: Int) {
        val rawStart = textStart ?: return
        var start = rawStart
        var end = until
        while (start < end && text[start].isJsWhitespace()) start += 1
        while (end > start && text[end - 1].isJsWhitespace()) end -= 1
        if (end > start) {
            segments += RaftNoticeSegment.Text(start = start, end = end)
        }
        textStart = null
    }
    var notices = 0
    var index = 0
    while (index < lines.size) {
        val found = parseDeliveryNoticeAt(scan, index)
        if (found != null) {
            flush(lines[index].start)
            segments += found.segment
            notices += 1
            index = found.next
            continue
        }
        if (textStart == null) textStart = lines[index].start
        index += 1
    }
    flush(text.length)
    return if (notices > 0) RaftDelivery(segments = segments, notices = notices) else null
}

/** Index of the segment holding character [offset], or -1. */
fun raftSegmentAt(segments: List<RaftNoticeSegment>, offset: Int): Int =
    segments.indexOfFirst { offset in it.start until it.end }
