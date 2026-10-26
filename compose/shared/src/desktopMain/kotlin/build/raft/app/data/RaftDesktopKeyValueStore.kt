package build.raft.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The desktop stand-in for the app-scope MMKV stores: one JSON file per namespace under
 * [RaftDesktopPaths.keyValueDir], written through (atomic replace) on every change. One
 * instance per namespace per process, so every reader sees every write.
 *
 * TODO(desktop): values are stored in plain files; auth tokens should move to the macOS Keychain.
 */
internal class RaftDesktopFileKeyValueStore private constructor(private val file: File) : RaftKeyValueStore {
    private val values: MutableMap<String, String> = load(file)

    override fun getString(key: String): String? = synchronized(this) { values[key] }

    override fun putString(key: String, value: String?) {
        synchronized(this) {
            val changed = if (value == null) values.remove(key) != null else values.put(key, value) != value
            if (changed) persist()
        }
    }

    private fun persist() {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, values.toMap()))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), String.serializer())
        private val stores = ConcurrentHashMap<String, RaftDesktopFileKeyValueStore>()

        fun forNamespace(namespace: String): RaftDesktopFileKeyValueStore =
            stores.computeIfAbsent(namespace) {
                val safeName = namespace.replace(Regex("[^A-Za-z0-9._-]"), "_")
                RaftDesktopFileKeyValueStore(File(RaftDesktopPaths.keyValueDir, "$safeName.json"))
            }

        private fun load(file: File): MutableMap<String, String> =
            runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }
                .getOrElse { mutableMapOf() }
    }
}

// Same app-scope namespaces as Android; on desktop every namespace is file-backed.
internal actual fun raftRequiresAppScopeKeyValueStore(namespace: String): Boolean =
    namespace == RAFT_AUTH_SESSION_MMKV_NAMESPACE ||
        namespace == RaftLocalPreferenceStore.KuiklyNamespace ||
        namespace == SharedApiEnvironmentStore.Namespace ||
        namespace == RAFT_ACTIVITY_UNREAD_MMKV_NAMESPACE ||
        namespace == RAFT_APP_LOGIN_MMKV_NAMESPACE ||
        namespace == RAFT_PUSH_REGISTRATION_MMKV_NAMESPACE

internal actual fun raftCreatePlatformAppScopeKeyValueStore(namespace: String): RaftKeyValueStore? =
    RaftDesktopFileKeyValueStore.forNamespace(namespace)

/** The desktop host's handle on the app-scope store of [namespace] (Android: its MMKV instance). */
fun raftDesktopAppScopeKeyValueStore(namespace: String): RaftKeyValueStore =
    RaftDesktopFileKeyValueStore.forNamespace(namespace)
