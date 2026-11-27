package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ExternalMentionSpan
import build.raft.app.model.MemberKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient wire decoder for `externalMentionSpans` on `senderType=external_projection` message rows
 * (botiverse/slock #17299 `packages/web/src/components/message/externalMentionSpans.ts`).
 *
 * - Non-external rows ignore the field completely (matching web `isExternal ? externalMentionSpansOf(...) : []`).
 * - A non-array or null value decodes as empty without failing message decode.
 * - Individual malformed entries (non-object, blank/non-string `id` or `label`, non-integer or
 *   out-of-order `[start, end)` offsets) are dropped while preserving valid siblings.
 * - `label` is kept verbatim (never trimmed) because render-time validation checks exact UTF-16
 *   equality `content.substring(start, end) == label`.
 */
@RaftCoreInternalApi fun decodeExternalMentionSpans(
    element: JsonElement?,
    senderKind: MemberKind?,
): List<ExternalMentionSpan> {
    if (senderKind != MemberKind.External) return emptyList()
    val array = element as? JsonArray ?: return emptyList()
    if (array.isEmpty()) return emptyList()
    return array.mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val id = obj.strictNonEmptyStringField("id") ?: return@mapNotNull null
        val label = obj.strictNonEmptyStringField("label") ?: return@mapNotNull null
        val start = obj.strictIntField("start") ?: return@mapNotNull null
        val end = obj.strictIntField("end") ?: return@mapNotNull null
        if (start < 0 || end <= start) return@mapNotNull null
        ExternalMentionSpan(
            id = id,
            label = label,
            start = start,
            end = end,
        )
    }
}

private fun JsonObject.strictNonEmptyStringField(key: String): String? {
    val primitive = get(key) as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content.takeIf { it.isNotEmpty() }
}

private fun JsonObject.strictIntField(key: String): Int? {
    val primitive = get(key) as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toIntOrNull()
}
