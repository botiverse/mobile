package build.raft.app.compat.json

import build.raft.app.parseSlockJsonArray
import build.raft.app.parseSlockJsonObject
import build.raft.app.slockRawValue
import build.raft.app.toSlockJsonString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * TODO(cmp-switch): drop-in replacement for Kuikly's nvi `JSONObject` / `JSONArray` (an AOSP
 * org.json port) so the ~375 UI-layer call sites keep compiling after Kuikly core is removed.
 * New code should use kotlinx `JsonObject` and the :core helpers in `build.raft.app.SlockJson`.
 *
 * Behaviour matches Kuikly: mutable, insertion-ordered, loose `opt*` coercions, string-encoded
 * nested values for `optJSONObject`/`optJSONArray` on objects, and `toString()` writes exactly the
 * bytes Kuikly wrote (it goes through `toSlockJsonString`, which is pinned to Kuikly's stringer).
 */

class JSONException(message: String) : Exception(message)

class JSONObject private constructor(private val nameValuePairs: MutableMap<String, Any?>) {
    constructor() : this(LinkedHashMap())

    /** Parses [json]; throws [JSONException] when it is not a JSON object, like Kuikly. */
    constructor(json: String) : this(parseObject(json))

    fun length(): Int = nameValuePairs.size

    fun put(name: String, value: Boolean): JSONObject = apply { nameValuePairs[name] = value }

    fun put(name: String, value: Int): JSONObject = apply { nameValuePairs[name] = value }

    fun put(name: String, value: Long): JSONObject = apply { nameValuePairs[name] = value }

    fun put(name: String, value: Double): JSONObject = apply { nameValuePairs[name] = value }

    fun put(name: String, value: Any?): JSONObject = apply { nameValuePairs[name] = value }

    fun has(name: String): Boolean = nameValuePairs.containsKey(name)

    fun opt(name: String): Any? = nameValuePairs[name]

    fun remove(name: String): Any? = nameValuePairs.remove(name)

    fun optBoolean(name: String): Boolean = optBoolean(name, false)

    fun optBoolean(name: String, fallback: Boolean): Boolean = jsonToBoolean(opt(name)) ?: fallback

    fun optDouble(name: String): Double = optDouble(name, 0.0)

    fun optDouble(name: String, fallback: Double): Double = jsonToDouble(opt(name)) ?: fallback

    fun optInt(name: String): Int = optInt(name, 0)

    fun optInt(name: String, fallback: Int): Int = jsonToInt(opt(name)) ?: fallback

    fun optLong(name: String): Long = optLong(name, 0L)

    fun optLong(name: String, fallback: Long): Long = jsonToLong(opt(name)) ?: fallback

    fun optString(name: String): String = optString(name, "")

    fun optString(name: String, fallback: String): String = opt(name)?.toString() ?: fallback

    fun optJSONArray(name: String): JSONArray? =
        when (val value = opt(name)) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrNull()
            else -> null
        }

    fun optJSONObject(name: String): JSONObject? =
        when (val value = opt(name)) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        }

    fun keys(): Iterator<String> = nameValuePairs.keys.iterator()

    fun keySet(): Set<String> = nameValuePairs.keys

    fun toMap(): MutableMap<String, Any> {
        val map = mutableMapOf<String, Any>()
        for ((key, value) in nameValuePairs) {
            when (value) {
                is JSONObject -> map[key] = value.toMap()
                is JSONArray -> map[key] = value.toList()
                is Int, is Long, is Double, is Float, is String, is Boolean -> map[key] = value
            }
        }
        return map
    }

    override fun toString(): String = toKotlinxJson().toSlockJsonString()

    override fun equals(other: Any?): Boolean = other is JSONObject && other.nameValuePairs == nameValuePairs

    override fun hashCode(): Int = nameValuePairs.hashCode()

    internal fun toKotlinxJson(): JsonObject {
        val values = LinkedHashMap<String, JsonElement>()
        for ((key, value) in nameValuePairs) values[key] = valueToJsonElement(value)
        return JsonObject(values)
    }

    companion object {
        /** Kuikly `JSONObject.quote`: [data] as a JSON string literal. */
        fun quote(data: String?): String = JsonPrimitive(data ?: "").toSlockJsonString()

        internal fun fromKotlinx(json: JsonObject): JSONObject {
            val map = LinkedHashMap<String, Any?>()
            for ((key, element) in json) map[key] = jsonElementToValue(element)
            return JSONObject(map)
        }

        private fun parseObject(json: String): MutableMap<String, Any?> {
            val parsed =
                try {
                    parseSlockJsonObject(json)
                } catch (error: Exception) {
                    throw JSONException(error.message ?: "Value cannot be converted to JSONObject")
                }
            return fromKotlinx(parsed).nameValuePairs
        }
    }
}

class JSONArray private constructor(private val values: MutableList<Any?>) {
    constructor() : this(ArrayList())

    /** Parses [json]; throws [JSONException] when it is not a JSON array, like Kuikly. */
    constructor(json: String) : this(parseArray(json))

    fun length(): Int = values.size

    fun put(value: Boolean): JSONArray = apply { values.add(value) }

    fun put(value: Double): JSONArray = apply { values.add(value) }

    fun put(value: Int): JSONArray = apply { values.add(value) }

    fun put(value: Long): JSONArray = apply { values.add(value) }

    fun put(value: Any?): JSONArray = apply { values.add(value) }

    fun opt(index: Int): Any? = values.getOrNull(index)

    fun remove(index: Int): Any? = if (index in values.indices) values.removeAt(index) else null

    fun optBoolean(index: Int): Boolean = optBoolean(index, false)

    fun optBoolean(index: Int, fallback: Boolean): Boolean = jsonToBoolean(opt(index)) ?: fallback

    fun optDouble(index: Int): Double = optDouble(index, Double.NaN)

    fun optDouble(index: Int, fallback: Double): Double = jsonToDouble(opt(index)) ?: fallback

    fun optInt(index: Int): Int = optInt(index, 0)

    fun optInt(index: Int, fallback: Int): Int = jsonToInt(opt(index)) ?: fallback

    fun optLong(index: Int): Long = optLong(index, 0L)

    fun optLong(index: Int, fallback: Long): Long = jsonToLong(opt(index)) ?: fallback

    fun optString(index: Int): String? = optString(index, "")

    fun optString(index: Int, fallback: String?): String? = opt(index)?.toString() ?: fallback

    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray

    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject

    fun toList(): MutableList<Any> {
        val list = mutableListOf<Any>()
        for (value in values) {
            when (value) {
                is JSONObject -> list.add(value.toMap())
                is JSONArray -> list.add(value.toList())
                is Int, is Long, is Double, is Float, is String, is Boolean -> list.add(value)
            }
        }
        return list
    }

    override fun toString(): String = toKotlinxJson().toSlockJsonString()

    override fun equals(other: Any?): Boolean = other is JSONArray && other.values == values

    override fun hashCode(): Int = values.hashCode()

    internal fun toKotlinxJson(): JsonArray = JsonArray(values.map(::valueToJsonElement))

    companion object {
        internal fun fromKotlinx(json: JsonArray): JSONArray = JSONArray(json.mapTo(ArrayList(), ::jsonElementToValue))

        private fun parseArray(json: String): MutableList<Any?> {
            val parsed =
                try {
                    parseSlockJsonArray(json)
                } catch (error: Exception) {
                    throw JSONException(error.message ?: "Value cannot be converted to JSONArray")
                }
            return fromKotlinx(parsed).values
        }
    }
}

private fun jsonElementToValue(element: JsonElement): Any? =
    when (element) {
        is JsonObject -> JSONObject.fromKotlinx(element)
        is JsonArray -> JSONArray.fromKotlinx(element)
        else -> element.slockRawValue()
    }

private fun valueToJsonElement(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JSONObject -> value.toKotlinxJson()
        is JSONArray -> value.toKotlinxJson()
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        // Kuikly serialized any other stored object as its quoted toString().
        else -> JsonPrimitive(value.toString())
    }

private fun jsonToBoolean(value: Any?): Boolean? =
    when (value) {
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

private fun jsonToDouble(value: Any?): Double? =
    when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

private fun jsonToInt(value: Any?): Int? =
    when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

private fun jsonToLong(value: Any?): Long? =
    when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
