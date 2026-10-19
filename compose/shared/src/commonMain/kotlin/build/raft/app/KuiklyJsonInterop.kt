package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Structural conversion between Kuikly's JSON types (Kuikly pages, page data, bridge payloads) and
 * the kotlinx types the UI-free data layer uses. Values map one to one, so serializing either side
 * yields the same bytes (`JSONObject.toString()` == `JsonObject.toSlockJsonString()`).
 */

fun JsonObject.toKuiklyJson(): JSONObject {
    val result = JSONObject()
    for (key in keys) {
        result.put(key, this[key]?.toKuiklyValue())
    }
    return result
}

fun JsonArray.toKuiklyJson(): JSONArray {
    val result = JSONArray()
    for (element in this) {
        result.put(element.toKuiklyValue())
    }
    return result
}

fun JSONObject.toJsonObject(): JsonObject {
    val values = LinkedHashMap<String, JsonElement>()
    for (key in keySet()) {
        values[key] = kuiklyValueToJsonElement(opt(key))
    }
    return JsonObject(values)
}

fun JSONArray.toJsonArray(): JsonArray {
    val values = ArrayList<JsonElement>(length())
    for (index in 0 until length()) {
        values += kuiklyValueToJsonElement(opt(index))
    }
    return JsonArray(values)
}

private fun JsonElement.toKuiklyValue(): Any? =
    when (this) {
        is JsonObject -> toKuiklyJson()
        is JsonArray -> toKuiklyJson()
        is JsonNull -> null
        is JsonPrimitive -> slockRawValue()
    }

private fun kuiklyValueToJsonElement(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JSONObject -> value.toJsonObject()
        is JSONArray -> value.toJsonArray()
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        // Kuikly serialized any other stored object as its quoted toString().
        else -> JsonPrimitive(value.toString())
    }
