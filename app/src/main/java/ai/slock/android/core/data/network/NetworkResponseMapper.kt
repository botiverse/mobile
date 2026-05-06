package ai.slock.android.core.data.network

import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.PermissionBlocker
import ai.slock.android.core.state.ScreenError
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.IOException
import okhttp3.ResponseBody
import retrofit2.Response

internal suspend fun <T> execute(
    emptyReason: EmptyReason,
    block: suspend () -> Response<ResponseBody>,
    emptyWhen: (T) -> Boolean = { false },
    mapper: (JsonObject) -> T
): DataResult<T> {
    return try {
        val response = block()
        if (!response.isSuccessful) return response.toDataError()
        val body = response.body()?.string().orEmpty()
        if (body.isBlank()) return DataResult.Empty(emptyReason)
        val json = Gson().fromJson(body, JsonElement::class.java)
        val root = when {
            json == null || json.isJsonNull -> return DataResult.Empty(emptyReason)
            json.isJsonArray -> JsonObject().apply { add("items", json.asJsonArray) }
            json.isJsonObject -> json.asJsonObject
            else -> return DataResult.Empty(emptyReason)
        }
        val result = mapper(root)
        if (emptyWhen(result)) DataResult.Empty(emptyReason) else DataResult.Content(result)
    } catch (_: IOException) {
        DataResult.Error(ScreenError(code = "network_unavailable", retryable = true))
    } catch (_: Exception) {
        DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
    }
}

internal fun <T> Response<ResponseBody>.toDataError(): DataResult<T> =
    when (code()) {
        401 -> authRequired()
        403 -> DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access"))
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        else -> DataResult.Error(ScreenError(code = "http_${code()}", retryable = code() != 404))
    }

internal fun <T> authRequired(): DataResult<T> =
    DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))

internal fun JsonElement.asObject(): JsonObject =
    if (isJsonObject) asJsonObject else JsonObject()

internal fun JsonObject.items(vararg names: String = arrayOf("items")): List<JsonElement> {
    if (entrySet().size == 1 && has("items") && get("items").isJsonArray) return getAsJsonArray("items").toList()
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonArray) return value.asJsonArray.toList()
    }
    val data = get("data")
    if (data != null) {
        if (data.isJsonArray) return data.asJsonArray.toList()
        if (data.isJsonObject) return data.asJsonObject.items(*names)
    }
    return emptyList()
}

internal fun JsonArray.toList(): List<JsonElement> =
    (0 until size()).map { get(it) }

internal fun JsonObject.string(vararg names: String): String? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            return value.asString.takeIf { it.isNotBlank() }
        }
    }
    return null
}

internal fun JsonObject.int(vararg names: String): Int? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            return value.asInt
        }
    }
    return null
}

internal fun JsonObject.boolean(vararg names: String): Boolean? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
            return value.asBoolean
        }
    }
    return null
}

internal fun JsonObject.stringList(vararg names: String): List<String> {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonArray) {
            return value.asJsonArray.mapNotNull { element ->
                when {
                    element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
                    element.isJsonObject -> element.asJsonObject.string("id", "userId", "memberId")
                    else -> null
                }
            }
        }
    }
    return emptyList()
}
