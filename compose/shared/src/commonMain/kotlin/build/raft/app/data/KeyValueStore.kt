package build.raft.app.data

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

interface SharedPreferenceStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

interface SlockKeyValueStore : SharedPreferenceStore

object SlockLocalPreferenceStore {
    const val KuiklyNamespace = "slockLocal"
}

fun SharedPreferenceStore.getBoolean(key: String, defaultValue: Boolean = false): Boolean =
    when (getString(key)) {
        "true" -> true
        "false" -> false
        else -> defaultValue
    }

fun SharedPreferenceStore.putBoolean(key: String, value: Boolean) {
    putString(key, value.toString())
}

class InMemorySlockKeyValueStore(
    initialValues: Map<String, String> = emptyMap()
) : SlockKeyValueStore {
    private val values = initialValues.toMutableMap()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) {
            values.remove(key)
        } else {
            values[key] = value
        }
    }
}

internal interface SlockMMKVKeyValueBackend {
    fun containsKey(key: String, mmapID: String): Boolean
    fun getString(key: String, mmapID: String): String?
    fun putString(key: String, value: String, mmapID: String)
    fun removeString(key: String, mmapID: String)
}

internal class KuiklyMMKVModule : Module() {
    override fun moduleName(): String = MODULE_NAME

    fun encodeString(key: String, value: String, mmapID: String) {
        toNative(false, METHOD_ENCODE, encodeParams(key, value, mmapID).toString(), null, true)
    }

    fun decodeString(key: String, defaultValue: String = "", mmapID: String): String {
        val result =
            toNative(
                false,
                METHOD_DECODE,
                JSONObject().apply {
                    put("key", key)
                    put("defaultValue", defaultValue)
                    put("type", "string")
                    put("mmapID", mmapID)
                }.toString(),
                null,
                true
            ).toString()
        return result.ifEmpty { defaultValue }
    }

    fun containsKey(key: String, mmapID: String): Boolean {
        val result =
            toNative(
                false,
                METHOD_CONTAINS_KEY,
                JSONObject().apply {
                    put("key", key)
                    put("mmapID", mmapID)
                }.toString(),
                null,
                true
            ).toString()
        return result == "true" || result == "1"
    }

    fun removeValueForKey(key: String, mmapID: String) {
        toNative(
            false,
            METHOD_REMOVE_VALUE_FOR_KEY,
            JSONObject().apply {
                put("key", key)
                put("mmapID", mmapID)
            }.toString(),
            null,
            true
        )
    }

    private fun encodeParams(key: String, value: String, mmapID: String): JSONObject =
        JSONObject().apply {
            put("key", key)
            put("value", value)
            put("type", "string")
            put("mmapID", mmapID)
        }

    companion object {
        const val MODULE_NAME = "KuiklyMMKVModule"
        private const val METHOD_ENCODE = "encode"
        private const val METHOD_DECODE = "decode"
        private const val METHOD_CONTAINS_KEY = "containsKey"
        private const val METHOD_REMOVE_VALUE_FOR_KEY = "removeValueForKey"
    }
}

internal class KuiklyMMKVKeyValueBackend(
    private val pager: Pager
) : SlockMMKVKeyValueBackend {
    private val module: KuiklyMMKVModule
        get() = pager.acquireModule(KuiklyMMKVModule.MODULE_NAME)

    override fun containsKey(key: String, mmapID: String): Boolean =
        module.containsKey(key, mmapID)

    override fun getString(key: String, mmapID: String): String? =
        module.decodeString(key, mmapID = mmapID).takeIf { it.isNotEmpty() }

    override fun putString(key: String, value: String, mmapID: String) {
        module.encodeString(key, value, mmapID)
    }

    override fun removeString(key: String, mmapID: String) {
        module.removeValueForKey(key, mmapID)
    }
}

class KuiklyMMKVKeyValueStore(
    pager: Pager,
    namespace: String
) : SlockKeyValueStore by KuiklyMMKVKeyValueStoreDelegate(
    backend = KuiklyMMKVKeyValueBackend(pager),
    namespace = namespace
)

internal class KuiklyMMKVKeyValueStoreDelegate(
    private val backend: SlockMMKVKeyValueBackend,
    private val namespace: String
) : SlockKeyValueStore {
    private val mmapID = namespace

    override fun getString(key: String): String? {
        val namespacedKey = namespaced(key)
        return if (backend.containsKey(namespacedKey, mmapID)) {
            backend.getString(namespacedKey, mmapID)
        } else {
            null
        }
    }

    override fun putString(key: String, value: String?) {
        val namespacedKey = namespaced(key)
        if (value == null) {
            backend.removeString(namespacedKey, mmapID)
        } else {
            backend.putString(namespacedKey, value, mmapID)
        }
    }

    private fun namespaced(key: String): String = "$namespace:$key"
}
