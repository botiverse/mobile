package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

interface SharedPreferenceStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

interface RaftKeyValueStore : SharedPreferenceStore

object RaftSharedKeyValueStoreProvider {
    private val appScopeFactories = mutableMapOf<String, () -> RaftKeyValueStore>()

    fun installAppScopeFactory(namespace: String, factory: () -> RaftKeyValueStore) {
        appScopeFactories[namespace] = factory
    }

    @RaftCoreInternalApi fun create(namespace: String, storageName: String): RaftSharedKeyValueStoreBinding {
        val factory = appScopeFactories[namespace]
        if (factory != null) {
            return RaftSharedKeyValueStoreBinding(
                store = factory(),
                scope = "app"
            )
        }
        val platformStore = raftCreatePlatformAppScopeKeyValueStore(namespace)
        if (platformStore != null) {
            return RaftSharedKeyValueStoreBinding(
                store = platformStore,
                scope = "app"
            )
        }
        error(
            "$storageName storage requires app-scope MMKV; install app-scope shared key-value stores before shared store creation."
        )
    }

    @RaftCoreInternalApi fun clearAppScopeFactoryForTests() {
        appScopeFactories.clear()
    }
}

object RaftSharedAuthSessionKeyValueStoreProvider {
    fun installAppScopeFactory(factory: () -> RaftKeyValueStore) {
        RaftSharedKeyValueStoreProvider.installAppScopeFactory(
            namespace = RAFT_AUTH_SESSION_MMKV_NAMESPACE,
            factory = factory
        )
    }

    @RaftCoreInternalApi fun create(): RaftSharedKeyValueStoreBinding =
        RaftSharedKeyValueStoreProvider.create(
            namespace = RAFT_AUTH_SESSION_MMKV_NAMESPACE,
            storageName = "auth session"
        )

    @RaftCoreInternalApi fun clearAppScopeFactoryForTests() {
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }
}

@RaftCoreInternalApi data class RaftSharedKeyValueStoreBinding(
    val store: RaftKeyValueStore,
    val scope: String
)

@RaftCoreInternalApi expect fun raftRequiresAppScopeKeyValueStore(namespace: String): Boolean

@RaftCoreInternalApi expect fun raftCreatePlatformAppScopeKeyValueStore(namespace: String): RaftKeyValueStore?

object RaftLocalPreferenceStore {
    const val KuiklyNamespace = "slockLocal"
}

/**
 * Storage key of the removed font picker inside the `slockLocal` app-scope store (persisted
 * contract string). The Raft Design System fixes the faces (Space Grotesk / Space Mono) and the
 * Web does not let users change them, so a stored choice is never applied.
 */
@RaftCoreInternalApi const val KEY_LEGACY_FONT_PREFERENCE = "appearance:font_preference"

/**
 * Drops the stored choice of the removed font picker so it cannot come back with an old build.
 * Call once at startup after the app-scope key-value stores are installed. Never throws.
 *
 * @return true when a stored value was found and cleared.
 */
fun raftClearLegacyFontPreference(): Boolean =
    runCatching {
        val store =
            RaftSharedKeyValueStoreProvider.create(
                namespace = RaftLocalPreferenceStore.KuiklyNamespace,
                storageName = "local preference"
            ).store
        raftClearLegacyFontPreference(store)
    }.getOrDefault(false)

@RaftCoreInternalApi fun raftClearLegacyFontPreference(store: SharedPreferenceStore): Boolean {
    if (store.getString(KEY_LEGACY_FONT_PREFERENCE) == null) return false
    store.putString(KEY_LEGACY_FONT_PREFERENCE, null)
    return true
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

class InMemoryRaftKeyValueStore(
    initialValues: Map<String, String> = emptyMap()
) : RaftKeyValueStore {
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
