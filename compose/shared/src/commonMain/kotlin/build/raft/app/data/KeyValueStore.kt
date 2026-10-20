package build.raft.app.data

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

    internal fun create(namespace: String, storageName: String): RaftSharedKeyValueStoreBinding {
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

    internal fun clearAppScopeFactoryForTests() {
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

    internal fun create(): RaftSharedKeyValueStoreBinding =
        RaftSharedKeyValueStoreProvider.create(
            namespace = RAFT_AUTH_SESSION_MMKV_NAMESPACE,
            storageName = "auth session"
        )

    internal fun clearAppScopeFactoryForTests() {
        RaftSharedKeyValueStoreProvider.clearAppScopeFactoryForTests()
    }
}

internal data class RaftSharedKeyValueStoreBinding(
    val store: RaftKeyValueStore,
    val scope: String
)

internal expect fun raftRequiresAppScopeKeyValueStore(namespace: String): Boolean

internal expect fun raftCreatePlatformAppScopeKeyValueStore(namespace: String): RaftKeyValueStore?

object RaftLocalPreferenceStore {
    const val KuiklyNamespace = "slockLocal"
}

/**
 * Task #521: publish the stored font face to [RaftFontFaceState] as soon as the app-scope store is
 * available, i.e. before the first composition.
 *
 * `RaftFontFaceState.preference` is process-scoped state that starts at [FontPreference.Default].
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
fun raftRestoreStoredFontPreferenceOnColdStart(): FontPreference? =
    runCatching {
        val binding = RaftSharedKeyValueStoreProvider.create(
            namespace = RaftLocalPreferenceStore.KuiklyNamespace,
            storageName = "local preference"
        )
        KeyValueFontPreferenceStore(binding.store).readFontPreferenceSync()
    }.getOrNull()?.also { stored ->
        build.raft.app.RaftFontFaceState.apply(stored)
    }

/**
 * Task #521 diagnostic: what the cold-start restore actually reads. Codex raised a competing
 * hypothesis that the restore may be reading a default because the value is not reachable from
 * this store/instance at all — in which case restoring earlier would fix nothing. One cold start
 * with this line settles which hypothesis holds.
 *
 * Returns the RAW stored string (or null when absent/unreadable) plus the parsed face, so a device
 * log distinguishes "key missing", "key present but unparsed", and "key present and parsed".
 */
fun raftReadStoredFontPreferenceForDiagnostics(): String =
    runCatching {
        val binding = RaftSharedKeyValueStoreProvider.create(
            namespace = RaftLocalPreferenceStore.KuiklyNamespace,
            storageName = "local preference"
        )
        val raw = binding.store.getString(KEY_FONT_PREFERENCE_STORAGE_KEY)
        val parsed = FontPreference.fromStorageKey(raw)
        // ShenKuo: a bare `raw=<absent>` merges "the key genuinely is not there" with "I asked the
        // wrong place", which are the two cases this diagnostic exists to separate. Carry the store
        // identity and the exact key with the value.
        //
        // storeId is the object identity, not the class: the class is invariant across two handles,
        // so it cannot discriminate them. Read the comparison in ONE direction only (Ark): this
        // tracks the WRAPPER object, not the underlying MMKV. Same storeId => definitely the same
        // object => definitely the same MMKV. Different storeId => only that the wrappers differ;
        // two wrappers over one MMKV also differ, so a different storeId alone does NOT prove two
        // underlying stores. To claim that the values must diverge too (same key, one reads a value
        // and the other reads absent).
        "font_preference_coldstart store=${binding.store::class.simpleName}" +
            " storeId=${raftStoreIdentity(binding.store)} scope=${binding.scope}" +
            " namespace=${RaftLocalPreferenceStore.KuiklyNamespace} key=$KEY_FONT_PREFERENCE_STORAGE_KEY" +
            " raw=${raw ?: "<absent>"} parsed=${parsed.storageKey}"
    }.getOrElse { error ->
        "font_preference_coldstart store=<unavailable> namespace=${RaftLocalPreferenceStore.KuiklyNamespace}" +
            " key=$KEY_FONT_PREFERENCE_STORAGE_KEY raw=<unreadable> error=${error.message}"
    }

/**
 * Stable per-instance identity for diagnostics: distinguishes two store handles that share a class.
 * Repeated calls for the SAME object keep returning the SAME value, so two call sites can be
 * compared within one process.
 *
 * **The comparison is sound in one direction only** — see the note at the call site: this is the
 * wrapper's identity, not the underlying MMKV's.
 */
private val raftStoreIdentities = mutableListOf<Pair<Any, Int>>()

internal fun raftStoreIdentity(store: Any): String {
    // Identity comparison, not equals(): a store wrapper with value equality (or two equal-content
    // handles) must still read as two distinct instances here, which is the whole point.
    raftStoreIdentities.firstOrNull { (known, _) -> known === store }?.let { return it.second.toString() }
    val next = raftStoreIdentities.size
    raftStoreIdentities += store to next
    return next.toString()
}

/** Storage key of the font face inside the `slockLocal` app-scope store. */
internal const val KEY_FONT_PREFERENCE_STORAGE_KEY = "appearance:font_preference"

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
