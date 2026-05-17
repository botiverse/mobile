package ai.slock.compose.data

import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.pager.Pager

interface SlockKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

fun SlockKeyValueStore.getBoolean(key: String, defaultValue: Boolean = false): Boolean =
    when (getString(key)) {
        "true" -> true
        "false" -> false
        else -> defaultValue
    }

fun SlockKeyValueStore.putBoolean(key: String, value: Boolean) {
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

class KuiklySharedPreferencesKeyValueStore(
    private val pager: Pager,
    private val namespace: String
) : SlockKeyValueStore {
    private val module: SharedPreferencesModule
        get() = pager.acquireModule(SharedPreferencesModule.MODULE_NAME)

    override fun getString(key: String): String? =
        module.getString(namespaced(key)).takeIf { it.isNotEmpty() }

    override fun putString(key: String, value: String?) {
        module.setString(namespaced(key), value.orEmpty())
    }

    private fun namespaced(key: String): String = "$namespace:$key"
}
