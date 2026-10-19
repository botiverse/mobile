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

/**
 * Task #521: publish the stored font face to [SlockFontFaceState] as soon as the app-scope store is
 * available, i.e. before the first composition.
 *
 * `SlockFontFaceState.preference` is process-scoped state that starts at [FontPreference.Default].
 * Its other restore point lives in `SharedSettingsStore.withLocalMobilePreferences()`, which only
 * runs when a settings snapshot loads. On a cold start the first frames are composed before any
 * snapshot resolves, so the UI rendered the default face and only swapped once unrelated settings
 * data arrived — on a slow start it appeared to never swap at all (observed on Android and OHOS:
 * MMKV held `geist_inter` while shared reverted to Space Grotesk).
 *
 * Call this after the app-scope key-value stores are installed and before shared UI is composed.
 * Safe to call more than once: the snapshot path re-applies the same value and [apply] no-ops when
 * the preference is unchanged.
 *
 * @return the preference published, or null when the store was not readable.
 */
fun slockRestoreStoredFontPreferenceOnColdStart(): FontPreference? =
    runCatching {
        val binding = SlockSharedKeyValueStoreProvider.create(
            namespace = SlockLocalPreferenceStore.KuiklyNamespace,
            storageName = "local preference"
        )
        KeyValueFontPreferenceStore(binding.store).readFontPreferenceSync()
    }.getOrNull()?.also { stored ->
        build.raft.app.SlockFontFaceState.apply(stored)
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
