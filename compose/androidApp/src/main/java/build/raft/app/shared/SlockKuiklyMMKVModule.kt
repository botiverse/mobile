package build.raft.app.shared

import android.content.Context
import android.util.Base64
import build.raft.app.core.data.diagnostics.SlockAndroidBuglyDiagnostics
import ai.slock.compose.data.NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
import ai.slock.compose.data.SLOCK_AUTH_SESSION_MMKV_NAMESPACE
import ai.slock.compose.data.SLOCK_AUTH_SESSION_MMKV_STORAGE_KEY
import ai.slock.compose.data.SlockLocalPreferenceStore
import ai.slock.compose.data.StoredAuthSession
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import org.json.JSONObject

internal class SlockKuiklyMMKVModule(context: Context) : KuiklyRenderBaseModule() {
    private val appContext = context.applicationContext
    private val gson = Gson()

    init {
        ensureInitialized(appContext)
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? =
        when (method) {
            METHOD_ENCODE -> encode(params)
            METHOD_DECODE -> decode(params)
            METHOD_CONTAINS_KEY -> containsKey(params)
            METHOD_REMOVE_VALUE_FOR_KEY -> removeValueForKey(params)
            METHOD_REMOVE_VALUES_FOR_KEYS -> removeValuesForKeys(params)
            METHOD_ALL_KEYS -> allKeys(params, callback)
            METHOD_COUNT -> count(params)
            METHOD_TOTAL_SIZE -> totalSize(params)
            METHOD_ACTUAL_SIZE -> actualSize(params)
            METHOD_CLEAR_ALL -> clearAll(params)
            METHOD_TRIM -> trim(params)
            METHOD_BATCH_ENCODE -> batchEncode(params)
            else -> null
        }

    private fun mmkv(mmapID: String?): MMKV =
        if (mmapID.isNullOrBlank()) {
            MMKV.defaultMMKV()
        } else {
            MMKV.mmkvWithID(mmapID)
        }

    private fun encode(params: String?): Any? {
        val json = params?.let(::JSONObject) ?: return null
        val key = json.optString("key").takeIf { it.isNotBlank() } ?: return null
        val store = mmkv(json.optNullableString("mmapID"))
        when (json.optString("type")) {
            "string" -> store.encode(key, json.optString("value"))
            "int" -> store.encode(key, json.optInt("value"))
            "long" -> store.encode(key, json.optLong("value"))
            "float" -> store.encode(key, json.optDouble("value").toFloat())
            "double" -> store.encode(key, json.optDouble("value"))
            "bool" -> store.encode(key, json.optBoolean("value"))
            "bytes" -> store.encode(key, Base64.decode(json.optString("value"), Base64.DEFAULT))
        }
        maybeUpdateAuthSessionContext(
            mmapID = json.optNullableString("mmapID"),
            key = key,
            encodedSession = json.optString("value").takeIf { json.optString("type") == "string" }
        )
        maybeUpdateForegroundKeepAlive(
            mmapID = json.optNullableString("mmapID"),
            key = key,
            value = json.optString("value").takeIf { json.optString("type") == "string" }
        )
        return null
    }

    private fun decode(params: String?): String {
        val json = params?.let(::JSONObject) ?: return ""
        val key = json.optString("key").takeIf { it.isNotBlank() } ?: return ""
        val store = mmkv(json.optNullableString("mmapID"))
        return when (json.optString("type")) {
            "string" -> store.decodeString(key, json.optString("defaultValue", "")) ?: json.optString("defaultValue", "")
            "int" -> store.decodeInt(key, json.optInt("defaultValue", 0)).toString()
            "long" -> store.decodeLong(key, json.optLong("defaultValue", 0L)).toString()
            "float" -> store.decodeFloat(key, json.optDouble("defaultValue", 0.0).toFloat()).toString()
            "double" -> store.decodeDouble(key, json.optDouble("defaultValue", 0.0)).toString()
            "bool" -> store.decodeBool(key, json.optBoolean("defaultValue", false)).toString()
            "bytes" -> store.decodeBytes(key)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty()
            else -> ""
        }
    }

    private fun containsKey(params: String?): String {
        val json = params?.let(::JSONObject) ?: return "false"
        val key = json.optString("key").takeIf { it.isNotBlank() } ?: return "false"
        return mmkv(json.optNullableString("mmapID")).containsKey(key).toString()
    }

    private fun removeValueForKey(params: String?): Any? {
        val json = params?.let(::JSONObject) ?: return null
        val key = json.optString("key").takeIf { it.isNotBlank() } ?: return null
        mmkv(json.optNullableString("mmapID")).removeValueForKey(key)
        maybeUpdateAuthSessionContext(
            mmapID = json.optNullableString("mmapID"),
            key = key,
            encodedSession = null
        )
        return null
    }

    private fun removeValuesForKeys(params: String?): Any? {
        val json = params?.let(::JSONObject) ?: return null
        val keys = json.optJSONArray("keys") ?: return null
        mmkv(json.optNullableString("mmapID")).removeValuesForKeys(
            Array(keys.length()) { index -> keys.optString(index) }
        )
        return null
    }

    private fun allKeys(params: String?, callback: KuiklyRenderCallback?): Any? {
        val json = params?.let(::JSONObject) ?: JSONObject()
        callback?.invoke(mapOf("keys" to mmkv(json.optNullableString("mmapID")).allKeys()?.joinToString(",").orEmpty()))
        return null
    }

    private fun count(params: String?): String =
        mmkv(params?.let(::JSONObject)?.optNullableString("mmapID")).count().toString()

    private fun totalSize(params: String?): String =
        mmkv(params?.let(::JSONObject)?.optNullableString("mmapID")).totalSize().toString()

    private fun actualSize(params: String?): String =
        mmkv(params?.let(::JSONObject)?.optNullableString("mmapID")).actualSize().toString()

    private fun clearAll(params: String?): Any? {
        mmkv(params?.let(::JSONObject)?.optNullableString("mmapID")).clearAll()
        return null
    }

    private fun trim(params: String?): Any? {
        mmkv(params?.let(::JSONObject)?.optNullableString("mmapID")).trim()
        return null
    }

    private fun batchEncode(params: String?): Any? {
        val json = params?.let(::JSONObject) ?: return null
        val entries = json.optJSONArray("entries") ?: return null
        val store = mmkv(json.optNullableString("mmapID"))
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val key = entry.optString("key").takeIf { it.isNotBlank() } ?: continue
            when (entry.optString("type", "string")) {
                "string" -> store.encode(key, entry.optString("value"))
                "int" -> store.encode(key, entry.optInt("value"))
                "long" -> store.encode(key, entry.optLong("value"))
                "float" -> store.encode(key, entry.optDouble("value").toFloat())
                "double" -> store.encode(key, entry.optDouble("value"))
                "bool" -> store.encode(key, entry.optBoolean("value"))
            }
            maybeUpdateAuthSessionContext(
                mmapID = json.optNullableString("mmapID"),
                key = key,
                encodedSession = entry.optString("value").takeIf { entry.optString("type", "string") == "string" }
            )
            maybeUpdateForegroundKeepAlive(
                mmapID = json.optNullableString("mmapID"),
                key = key,
                value = entry.optString("value").takeIf { entry.optString("type", "string") == "string" }
            )
        }
        return null
    }

    private fun maybeUpdateAuthSessionContext(mmapID: String?, key: String, encodedSession: String?) {
        if (mmapID != SLOCK_AUTH_SESSION_MMKV_NAMESPACE || key != SLOCK_AUTH_SESSION_MMKV_STORAGE_KEY) return
        val session =
            encodedSession
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { gson.fromJson(it, StoredAuthSession::class.java) }.getOrNull() }
        SlockAndroidBuglyDiagnostics.updateUserContext(
            context = appContext,
            userId = session?.user?.id,
            selectedServerId = session?.currentServerId,
            selectedServerSlug = session?.selectedServerSlug
        )
    }

    private fun maybeUpdateForegroundKeepAlive(mmapID: String?, key: String, value: String?) {
        if (mmapID != SlockLocalPreferenceStore.KuiklyNamespace ||
            key != NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY &&
            key != AndroidRealtimeKeepAliveController.localPreferenceKey(
                NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
            )
        ) {
            return
        }
        if (value == "true") {
            AndroidRealtimeKeepAliveController.start(appContext, source = "preference_enabled")
        } else {
            AndroidRealtimeKeepAliveController.stop(appContext, reason = "preference_disabled")
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        optString(name).takeIf { it.isNotBlank() }

    companion object {
        const val MODULE_NAME = "KuiklyMMKVModule"
        private const val METHOD_ENCODE = "encode"
        private const val METHOD_DECODE = "decode"
        private const val METHOD_CONTAINS_KEY = "containsKey"
        private const val METHOD_REMOVE_VALUE_FOR_KEY = "removeValueForKey"
        private const val METHOD_REMOVE_VALUES_FOR_KEYS = "removeValuesForKeys"
        private const val METHOD_ALL_KEYS = "allKeys"
        private const val METHOD_COUNT = "count"
        private const val METHOD_TOTAL_SIZE = "totalSize"
        private const val METHOD_ACTUAL_SIZE = "actualSize"
        private const val METHOD_CLEAR_ALL = "clearAll"
        private const val METHOD_TRIM = "trim"
        private const val METHOD_BATCH_ENCODE = "batchEncode"

        @Volatile
        private var initialized = false

        internal fun ensureInitialized(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    MMKV.initialize(context)
                    initialized = true
                }
            }
        }

        internal fun containsKey(context: Context, key: String, mmapID: String): Boolean {
            ensureInitialized(context)
            return MMKV.mmkvWithID(mmapID).containsKey(key)
        }

        internal fun decodeString(context: Context, key: String, mmapID: String): String? {
            ensureInitialized(context)
            return MMKV.mmkvWithID(mmapID).decodeString(key, null)
        }

        internal fun encodeString(context: Context, key: String, value: String, mmapID: String) {
            ensureInitialized(context)
            MMKV.mmkvWithID(mmapID).encode(key, value)
        }

        internal fun removeValueForKey(context: Context, key: String, mmapID: String) {
            ensureInitialized(context)
            MMKV.mmkvWithID(mmapID).removeValueForKey(key)
        }
    }
}
