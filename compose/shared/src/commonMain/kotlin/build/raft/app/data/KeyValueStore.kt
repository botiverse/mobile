package build.raft.app.data

interface SharedPreferenceStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

interface SlockKeyValueStore : SharedPreferenceStore

object SlockSharedKeyValueStoreProvider {
    private val appScopeFactories = mutableMapOf<String, () -> SlockKeyValueStore>()

    fun installAppScopeFactory(namespace: String, factory: () -> SlockKeyValueStore) {
        appScopeFactories[namespace] = factory
    }

    internal fun create(namespace: String, storageName: String): SlockSharedKeyValueStoreBinding {
        val factory = appScopeFactories[namespace]
        if (factory != null) {
            return SlockSharedKeyValueStoreBinding(
                store = factory(),
                scope = "app"
            )
        }
        val platformStore = slockCreatePlatformAppScopeKeyValueStore(namespace)
        if (platformStore != null) {
            return SlockSharedKeyValueStoreBinding(
                store = platformStore,
                scope = "app"
            )
        }
        error(
            "$storageName storage requires app-scope MMKV; install app-scope shared key-value stores before shared store creation."
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

    internal fun create(): SlockSharedKeyValueStoreBinding =
        SlockSharedKeyValueStoreProvider.create(
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

internal expect fun slockCreatePlatformAppScopeKeyValueStore(namespace: String): SlockKeyValueStore?

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
