package build.raft.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * kotlinx.serialization JSON helpers for the UI-free data layer.
 *
 * The data layer used to carry Kuikly's `com.tencent.kuikly.core.nvi.serialization.json.JSONObject`
 * (an AOSP org.json port). These helpers keep that type's observable behaviour on top of the
 * immutable kotlinx [JsonObject]/[JsonArray]:
 *
 * - `opt*` accessors apply the same loose coercions (`"5"` → 5, `1.9` → 1, `"TRUE"` → true,
 *   string-encoded nested objects for [optJsonObject]/[optJsonArray] on objects only).
 * - [toSlockJsonString] writes exactly what Kuikly's `JSONObject.toString()` wrote (`": "` after
 *   object keys, escaped `/`, `\u00XX` control chars, integral doubles printed without a fraction),
 *   so bridge, event-bus and HTTP wire bytes stay identical.
 *
 * Numbers are interpreted the way the Kuikly tokener read them: a literal without `.`/`e`/`E`
 * is an Int when it fits, else a Long; everything else is a Double.
 */

private val slockJsonParser = Json

/** Parses [text] as a JSON object; throws like `JSONObject(text)` did when it is not one. */
fun parseSlockJsonObject(text: String): JsonObject =
    slockJsonParser.parseToJsonElement(text.removePrefix("﻿")) as? JsonObject
        ?: throw IllegalArgumentException("JSON value is not an object")

/** Parses [text] as a JSON array; throws like `JSONArray(text)` did when it is not one. */
fun parseSlockJsonArray(text: String): JsonArray =
    slockJsonParser.parseToJsonElement(text.removePrefix("﻿")) as? JsonArray
        ?: throw IllegalArgumentException("JSON value is not an array")

fun parseSlockJsonObjectOrNull(text: String?): JsonObject? =
    text?.let { raw -> runCatching { parseSlockJsonObject(raw) }.getOrNull() }

fun parseSlockJsonArrayOrNull(text: String?): JsonArray? =
    text?.let { raw -> runCatching { parseSlockJsonArray(raw) }.getOrNull() }

/** Kuikly-compatible serialization (see file header). */
fun JsonElement.toSlockJsonString(): String = StringBuilder().also { out -> writeSlockJson(this, out) }.toString()

fun JsonObject.has(key: String): Boolean = containsKey(key)

/**
 * Copy of this object with [key] set. Like Kuikly's mutating `put`, an existing key keeps its
 * position and a new key is appended, so the serialized key order is unchanged.
 */
fun JsonObject.withEntry(key: String, value: JsonElement): JsonObject =
    JsonObject(LinkedHashMap<String, JsonElement>(this).apply { put(key, value) })

fun JsonObject.withEntry(key: String, value: String?): JsonObject = withEntry(key, JsonPrimitive(value))

fun JsonObject.withEntry(key: String, value: Number?): JsonObject = withEntry(key, JsonPrimitive(value))

fun JsonObject.withEntry(key: String, value: Boolean?): JsonObject = withEntry(key, JsonPrimitive(value))

/**
 * The raw value as Kuikly stored it: `null`, [String], [Boolean], [Int]/[Long]/[Double],
 * [JsonObject] or [JsonArray].
 */
fun JsonObject.opt(key: String): Any? = this[key]?.slockRawValue()

fun JsonObject.optString(key: String, fallback: String = ""): String =
    slockRawString(this[key]) ?: fallback

fun JsonObject.optBoolean(key: String, fallback: Boolean = false): Boolean =
    slockRawBoolean(this[key]) ?: fallback

fun JsonObject.optInt(key: String, fallback: Int = 0): Int =
    slockRawInt(this[key]) ?: fallback

fun JsonObject.optLong(key: String, fallback: Long = 0L): Long =
    slockRawLong(this[key]) ?: fallback

/** Kuikly's object default is 0.0 while its array default is NaN; both are preserved. */
fun JsonObject.optDouble(key: String, fallback: Double = 0.0): Double =
    slockRawDouble(this[key]) ?: fallback

/** Nested object, or a string value that parses as one (Kuikly `optJSONObject` semantics). */
fun JsonObject.optJsonObject(key: String): JsonObject? =
    when (val value = this[key]) {
        is JsonObject -> value
        is JsonPrimitive -> if (value.isString) parseSlockJsonObjectOrNull(value.content) else null
        else -> null
    }

/** Nested array, or a string value that parses as one (Kuikly `optJSONArray` semantics). */
fun JsonObject.optJsonArray(key: String): JsonArray? =
    when (val value = this[key]) {
        is JsonArray -> value
        is JsonPrimitive -> if (value.isString) parseSlockJsonArrayOrNull(value.content) else null
        else -> null
    }

fun JsonArray.opt(index: Int): Any? = getOrNull(index)?.slockRawValue()

fun JsonArray.optString(index: Int, fallback: String = ""): String =
    slockRawString(getOrNull(index)) ?: fallback

fun JsonArray.optBoolean(index: Int, fallback: Boolean = false): Boolean =
    slockRawBoolean(getOrNull(index)) ?: fallback

fun JsonArray.optInt(index: Int, fallback: Int = 0): Int =
    slockRawInt(getOrNull(index)) ?: fallback

fun JsonArray.optLong(index: Int, fallback: Long = 0L): Long =
    slockRawLong(getOrNull(index)) ?: fallback

fun JsonArray.optDouble(index: Int, fallback: Double = Double.NaN): Double =
    slockRawDouble(getOrNull(index)) ?: fallback

/** Array elements are never re-parsed from strings (Kuikly `JSONArray.optJSONObject`). */
fun JsonArray.optJsonObject(index: Int): JsonObject? = getOrNull(index) as? JsonObject

fun JsonArray.optJsonArray(index: Int): JsonArray? = getOrNull(index) as? JsonArray

/** The raw Kuikly-equivalent value of this element (see [opt]). */
fun JsonElement.slockRawValue(): Any? =
    when (this) {
        is JsonObject -> this
        is JsonArray -> this
        is JsonNull -> null
        is JsonPrimitive -> slockPrimitiveValue()
    }

private fun JsonPrimitive.slockPrimitiveValue(): Any? {
    if (isString) return content
    return when (content) {
        "true" -> true
        "false" -> false
        "null" -> null
        else -> slockNumberOrNull(content) ?: content
    }
}

/** Number literal → Int/Long/Double with the Kuikly tokener's rules. */
private fun slockNumberOrNull(literal: String): Number? {
    if (literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0) {
        literal.toLongOrNull()?.let { value ->
            return if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else value
        }
    }
    return literal.toDoubleOrNull()
}

private fun slockRawString(element: JsonElement?): String? =
    when (val value = element?.slockRawValue()) {
        null -> null
        is String -> value
        is JsonElement -> value.toSlockJsonString()
        else -> value.toString()
    }

private fun slockRawBoolean(element: JsonElement?): Boolean? =
    when (val value = element?.slockRawValue()) {
        is Boolean -> value
        is String ->
            when {
                value.equals("true", ignoreCase = true) -> true
                value.equals("false", ignoreCase = true) -> false
                else -> null
            }
        is Number -> value.toInt() != 0
        else -> null
    }

private fun slockRawInt(element: JsonElement?): Int? =
    when (val value = element?.slockRawValue()) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

private fun slockRawLong(element: JsonElement?): Long? =
    when (val value = element?.slockRawValue()) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

private fun slockRawDouble(element: JsonElement?): Double? =
    when (val value = element?.slockRawValue()) {
        is Double -> value
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

private fun writeSlockJson(element: JsonElement, out: StringBuilder) {
    when (element) {
        is JsonObject -> {
            out.append('{')
            var first = true
            for ((key, value) in element) {
                if (!first) out.append(',')
                first = false
                writeSlockJsonString(key, out)
                // Kuikly's JSONStringer always writes ": " between a key and its value.
                out.append(": ")
                writeSlockJson(value, out)
            }
            out.append('}')
        }
        is JsonArray -> {
            out.append('[')
            element.forEachIndexed { index, value ->
                if (index > 0) out.append(',')
                writeSlockJson(value, out)
            }
            out.append(']')
        }
        is JsonNull -> out.append("null")
        is JsonPrimitive ->
            when (val value = element.slockPrimitiveValue()) {
                null -> out.append("null")
                is String -> writeSlockJsonString(value, out)
                is Boolean -> out.append(value)
                is Number -> out.append(slockNumberToString(value))
                else -> writeSlockJsonString(value.toString(), out)
            }
    }
}

/** Kuikly `JSON.numberToString`: integral values print as a long, everything else via toString. */
private fun slockNumberToString(number: Number): String {
    val doubleValue = number.toDouble()
    val longValue = number.toLong()
    return if (doubleValue == longValue.toDouble()) longValue.toString() else number.toString()
}

/** Kuikly `JSONStringer.string`: escapes `"`, `\`, `/`, \t \b \n \r and other C0 controls as \u00xx. */
private fun writeSlockJsonString(value: String, out: StringBuilder) {
    out.append('"')
    for (c in value) {
        when (c) {
            '"', '\\', '/' -> out.append('\\').append(c)
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            else ->
                if (c.code <= 0x1F) {
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
        }
    }
    out.append('"')
}
