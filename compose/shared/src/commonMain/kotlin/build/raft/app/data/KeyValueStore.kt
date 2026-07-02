package build.raft.app.data

import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

interface SharedPreferenceStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

interface SlockKeyValueStore : SharedPreferenceStore

internal interface RebindableSlockKeyValueStore : SlockKeyValueStore {
    fun updatePager(pager: Pager)
}

object SlockSharedKeyValueStoreProvider {
    private val appScopeFactories = mutableMapOf<String, () -> SlockKeyValueStore>()

    fun installAppScopeFactory(namespace: String, factory: () -> SlockKeyValueStore) {
        appScopeFactories[namespace] = factory
    }

    internal fun create(
        pager: Pager,
        namespace: String,
        storageName: String
    ): SlockSharedKeyValueStoreBinding {
        val factory = appScopeFactories[namespace]
        if (factory != null) {
            return SlockSharedKeyValueStoreBinding(
                store = factory(),
                scope = "app"
            )
        }
        check(!slockRequiresAppScopeKeyValueStore(namespace)) {
            "Android $storageName storage requires app-scope MMKV; installAndroidAppScopeSharedKeyValueStores() must run before shared store creation."
        }
        return SlockSharedKeyValueStoreBinding(
            store =
                RebindableKuiklyMMKVKeyValueStore(
                    pager = pager,
                    namespace = namespace
                ),
            scope = "pager"
        )
    }

    internal fun clearAppScopeFactoryForTests() {
        appScopeFactories.clear()
    }
}

object SlockSharedAuthSessionKeyValueStoreProvider {
    fun installAppScopeFactory(factory: () -> SlockKeyValueStore) {
        SlockSharedKeyValueStoreProvider.installAppScopeFactory(
            namespace = SLOCK_AUTH_SESSION_MMKV_NAMESPACE,
            factory = factory
        )
    }

    internal fun create(pager: Pager): SlockSharedKeyValueStoreBinding =
        SlockSharedKeyValueStoreProvider.create(
            pager = pager,
            namespace = SLOCK_AUTH_SESSION_MMKV_NAMESPACE,
            storageName = "auth session"
        )

    internal fun clearAppScopeFactoryForTests() {
        SlockSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }
}

internal data class SlockSharedKeyValueStoreBinding(
    val store: SlockKeyValueStore,
    val scope: String
)

internal expect fun slockRequiresAppScopeKeyValueStore(namespace: String): Boolean

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

internal class RebindableKuiklyMMKVKeyValueBackend(
    pager: Pager
) : SlockMMKVKeyValueBackend {
    @Volatile
    private var currentPager: Pager = pager

    fun updatePager(pager: Pager) {
        currentPager = pager
    }

    private val module: KuiklyMMKVModule
        get() = currentPager.acquireModule(KuiklyMMKVModule.MODULE_NAME)

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

class RebindableKuiklyMMKVKeyValueStore(
    pager: Pager,
    namespace: String
) : RebindableSlockKeyValueStore {
    private val backend = RebindableKuiklyMMKVKeyValueBackend(pager)
    private val delegate =
        KuiklyMMKVKeyValueStoreDelegate(
            backend = backend,
            namespace = namespace
        )

    override fun updatePager(pager: Pager) {
        backend.updatePager(pager)
    }

    override fun getString(key: String): String? =
        delegate.getString(key)

    override fun putString(key: String, value: String?) {
        delegate.putString(key, value)
    }
}

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
